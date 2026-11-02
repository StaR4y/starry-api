package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.starryapi.account.PoolCell;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.FeaturePolicy.Baseline;
import tech.starryapi.platform.xproto.FeaturePolicy.Treatment;
import tech.starryapi.platform.xproto.FeaturePolicy.Verdict;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Detected;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Feature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Design 7.9 line by line: for the Messages, Chat and Responses entries, what each feature comes to for a member (carried, left out
 * and recorded, or the reason to skip the member), under the two baselines of design 7.2. The scenario tests of the
 * task come first: the Claude Code main loop has no HARD feature, {@code web_search} has, and a {@code gpt} request's
 * images and schemas are HARD where a Claude request's are SOFT.
 */
class FeaturePolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Settings NONE = member(false, false, false, false, false, false);
    private static final Settings VISION = member(true, false, false, false, false, false);
    private static final Settings SCHEMA = member(false, true, false, false, false, false);
    private static final Settings EFFORT = member(false, false, false, false, true, false);
    private static final Settings FULL = member(true, true, true, true, true, false);

    private static Settings member(
        boolean vision,
        boolean jsonSchema,
        boolean priorityTier,
        boolean cacheControlPassthrough,
        boolean reasoningEffort,
        boolean billedFastMode
    ) {
        // A Claude model behind the relay, the one whose tool continuation turns are not asked for a reasoning level.
        return new Settings(vision, jsonSchema, priorityTier, cacheControlPassthrough, false, reasoningEffort, billedFastMode, false, true, true);
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static Verdict verdict(Entry entry, String body, Baseline baseline, Settings member) {
        return FeaturePolicy.judge(RequestFeatureDetector.detect(entry, tree(body)), baseline, member);
    }

    private static Verdict messages(String body, Settings member) {
        return verdict(Entry.MESSAGES, body, Baseline.TEXT_ONLY, member);
    }

    private static Verdict chat(String body, Baseline baseline, Settings member) {
        return verdict(Entry.CHAT, body, baseline, member);
    }

    private static Set<Feature> hard(Verdict verdict) {
        return features(verdict.hard());
    }

    private static Set<Feature> soft(Verdict verdict) {
        return features(verdict.soft());
    }

    private static Set<Feature> features(List<Feature> list) {
        Set<Feature> set = EnumSet.noneOf(Feature.class);
        set.addAll(list);
        return set;
    }

    private static String messagesBody(String extra, String content) {
        return "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":1024" + (extra.isEmpty() ? "" : "," + extra)
            + ",\"messages\":[{\"role\":\"user\",\"content\":" + content + "}]}";
    }

    private static Verdict responses(String body, Baseline baseline, Settings member) {
        return verdict(Entry.RESPONSES, body, baseline, member);
    }

    private static String responsesBody(String extra, String content) {
        return "{\"model\":\"claude-sonnet-4.5\"" + (extra.isEmpty() ? "" : "," + extra)
            + ",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":" + content + "}]}";
    }

    private static String chatBody(String extra, String content) {
        return "{\"model\":\"gpt-5\"" + (extra.isEmpty() ? "" : "," + extra)
            + ",\"messages\":[{\"role\":\"user\",\"content\":" + content + "}]}";
    }

    private static final String IMAGE_PART = "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,iVBORw0KGgo=\"}}";
    private static final String SCHEMA_FORMAT = "\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"s\",\"schema\":{}}}";
    private static final String AUDIO_PART = "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AAAA\",\"format\":\"wav\"}}";
    private static final String FILE_PART = "{\"type\":\"file\",\"file\":{\"file_id\":\"file-1\"}}";

    // ---------------------------------------------------------------------------------------- the scenarios of the task

    @Test
    void theClaudeCodeMainLoopHasNoHardFeatureForAnyMemberOrRoute() {
        for (String last : List.of(RequestFeatureDetectorTest.NEXT_PROMPT, RequestFeatureDetectorTest.TOOL_RESULT_LAST)) {
            Detected detected = RequestFeatureDetector.detect(Entry.MESSAGES, tree(RequestFeatureDetectorTest.CLAUDE_CODE_TURN.formatted(last)));
            for (Baseline baseline : Baseline.values()) {
                for (Settings member : allMembers()) {
                    assertThat(FeaturePolicy.judge(detected, baseline, member).hard()).isEmpty();
                }
            }
        }
    }

    @Test
    void theClaudeCodeMainLoopLeavesOutOnlyWhatTheNativePathLeavesOut() {
        Detected prompt = RequestFeatureDetector.detect(Entry.MESSAGES,
            tree(RequestFeatureDetectorTest.CLAUDE_CODE_TURN.formatted(RequestFeatureDetectorTest.NEXT_PROMPT)));
        Detected continuation = RequestFeatureDetector.detect(Entry.MESSAGES,
            tree(RequestFeatureDetectorTest.CLAUDE_CODE_TURN.formatted(RequestFeatureDetectorTest.TOOL_RESULT_LAST)));
        Set<Feature> alwaysLeftOut = EnumSet.of(
            Feature.TOOL_SEARCH, Feature.CONTEXT_MANAGEMENT, Feature.METADATA, Feature.HISTORY_THINKING
        );

        // A member without reasoning effort gets no thinking, and one that does not pass cache control through none of the
        // cache controls (the conversion for relays has them); with both, a new turn gets thinking and a tool
        // continuation does not.
        Set<Feature> memberTakesNothing = withThinking(alwaysLeftOut);
        memberTakesNothing.add(Feature.CACHE_CONTROL);
        assertThat(soft(FeaturePolicy.judge(prompt, Baseline.TEXT_ONLY, NONE))).containsExactlyInAnyOrderElementsOf(memberTakesNothing);
        assertThat(soft(FeaturePolicy.judge(prompt, Baseline.TEXT_ONLY, FULL))).containsExactlyInAnyOrderElementsOf(alwaysLeftOut);
        assertThat(soft(FeaturePolicy.judge(continuation, Baseline.TEXT_ONLY, FULL)))
            .containsExactlyInAnyOrderElementsOf(withThinking(alwaysLeftOut));
    }

    private static Set<Feature> withThinking(Set<Feature> features) {
        Set<Feature> all = features(List.copyOf(features));
        all.add(Feature.THINKING);
        return all;
    }

    @Test
    void aWebSearchRequestIsHardForAnyMember() {
        String webSearch = """
            {"model":"claude-haiku-4-5","max_tokens":32000,
             "system":[{"type":"text","text":"You are an assistant for performing a web search tool use"}],
             "tools":[{"type":"web_search_20250305","name":"web_search","max_uses":8}],
             "messages":[{"role":"user","content":"Perform a web search for the query: weather"}]}
            """;

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                Verdict verdict = verdict(Entry.MESSAGES, webSearch, baseline, member);
                assertThat(verdict.hard()).containsExactly(Feature.WEB_SEARCH);
                assertThat(verdict.allowed()).isFalse();
            }
        }
    }

    @Test
    void aGptRequestsImageAndSchemaAreHardWhereAClaudeRequestsAreSoft() {
        String body = chatBody(SCHEMA_FORMAT, "[{\"type\":\"text\",\"text\":\"describe\"}," + IMAGE_PART + "]");
        // The routes of the planner (DefaultSourceTierPlanner.nativeUnits): the openai vendor, then anthropic and google.
        Baseline openai = Baseline.of(List.of(PoolCell.OFFICIAL_OPENAI));
        Baseline anthropic = Baseline.of(List.of(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE));
        Baseline google = Baseline.of(List.of(PoolCell.KIRO_COMPATIBLE));

        Verdict gpt = chat(body, openai, NONE);
        assertThat(hard(gpt)).containsExactlyInAnyOrder(Feature.IMAGE, Feature.JSON_SCHEMA);
        assertThat(soft(gpt)).isEmpty();
        for (Baseline claude : List.of(anthropic, google)) {
            Verdict verdict = chat(body, claude, NONE);
            assertThat(hard(verdict)).isEmpty();
            assertThat(soft(verdict)).containsExactlyInAnyOrder(Feature.IMAGE, Feature.JSON_SCHEMA);
        }
        // A member that can carry them is given them, whichever the route.
        for (Baseline baseline : List.of(openai, anthropic)) {
            Verdict carried = chat(body, baseline, member(true, true, false, false, false, false));
            assertThat(hard(carried)).isEmpty();
            assertThat(soft(carried)).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------- the baseline

    @Test
    void theBaselineFollowsTheNativeCellsOfTheRoute() {
        // The cell lists DefaultSourceTierPlanner.nativeUnits makes for each route.
        assertThat(Baseline.of(List.of(PoolCell.DEEPSEEK))).isEqualTo(Baseline.PASSTHROUGH);
        assertThat(Baseline.of(List.of(PoolCell.OPENAI_COMPATIBLE))).isEqualTo(Baseline.PASSTHROUGH);
        assertThat(Baseline.of(List.of(PoolCell.CODEX))).isEqualTo(Baseline.PASSTHROUGH);
        assertThat(Baseline.of(List.of(PoolCell.CODEX, PoolCell.OFFICIAL_OR_CODEX))).isEqualTo(Baseline.PASSTHROUGH);
        assertThat(Baseline.of(List.of(PoolCell.OFFICIAL_OPENAI))).isEqualTo(Baseline.PASSTHROUGH);
        assertThat(Baseline.of(List.of(PoolCell.OFFICIAL_OR_CODEX))).isEqualTo(Baseline.PASSTHROUGH);
        assertThat(Baseline.of(List.of(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE))).isEqualTo(Baseline.TEXT_ONLY);
        assertThat(Baseline.of(List.of(PoolCell.KIRO_COMPATIBLE))).isEqualTo(Baseline.TEXT_ONLY);
    }

    @Test
    void oneCellThatTakesTheBodyWholeMakesTheRouteAPassthrough() {
        assertThat(Baseline.of(List.of(PoolCell.KIRO_COMPATIBLE, PoolCell.OFFICIAL_OPENAI))).isEqualTo(Baseline.PASSTHROUGH);
        assertThat(Baseline.of(List.of(PoolCell.OFFICIAL_OPENAI, PoolCell.KIRO_COMPATIBLE))).isEqualTo(Baseline.PASSTHROUGH);
    }

    @Test
    void theCrossProtocolCellIsNotANativeOne() {
        assertThat(Baseline.of(List.of(PoolCell.KIRO_COMPATIBLE, PoolCell.XP_OPENAI_CHAT))).isEqualTo(Baseline.TEXT_ONLY);
        assertThat(Baseline.of(List.of(PoolCell.OFFICIAL_OPENAI, PoolCell.XP_OPENAI_CHAT))).isEqualTo(Baseline.PASSTHROUGH);
    }

    @Test
    void aRouteWithoutNativeCellsIsTheSafeSideAndNoCellMakesTheBaselineUndecided() {
        assertThat(Baseline.of(List.of())).isEqualTo(Baseline.PASSTHROUGH);
        assertThat(Baseline.of(List.of(PoolCell.XP_OPENAI_CHAT))).isEqualTo(Baseline.PASSTHROUGH);
        for (PoolCell cell : PoolCell.values()) {
            assertThat(Baseline.of(List.of(cell))).isNotNull();
        }
    }

    // ---------------------------------------------------------------------------------------- 7.9, MESSAGES

    @Test
    void whatMessagesCarriesNeedsNoSayInAnyWay() {
        Verdict verdict = messages("""
            {"model":"claude-sonnet-4-5","max_tokens":2048,"temperature":0.4,"top_p":0.9,"stop_sequences":["a","b","c","d"],
             "system":"Be brief","tool_choice":{"type":"any","disable_parallel_tool_use":true},
             "tools":[{"name":"Read","description":"d","input_schema":{"type":"object"}}],
             "messages":[{"role":"user","content":"read"},
               {"role":"assistant","content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}]},
               {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"x"}]},
               {"role":"assistant","content":"done"},{"role":"user","content":"thanks"}]}
            """, NONE);

        assertThat(verdict.hard()).isEmpty();
        assertThat(verdict.soft()).isEmpty();
        assertThat(verdict.allowed()).isTrue();
    }

    @Test
    void moreThanFourStopSequencesAreHard() {
        assertThat(messages(messagesBody("\"stop_sequences\":[\"a\",\"b\",\"c\",\"d\"]", "\"q\""), FULL).hard()).isEmpty();
        assertThat(messages(messagesBody("\"stop_sequences\":[\"a\",\"b\",\"c\",\"d\",\"e\"]", "\"q\""), FULL).hard())
            .containsExactly(Feature.STOP_SEQUENCES_OVER_4);
    }

    @Test
    void theToolsAnthropicRunsMcpServersAndAContainerAreHardWhateverTheMember() {
        for (String field : List.of(
            "\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]",
            "\"tools\":[{\"type\":\"web_fetch_20250910\",\"name\":\"web_fetch\"}]",
            "\"tools\":[{\"type\":\"code_execution_20250522\",\"name\":\"code_execution\"}]",
            "\"tools\":[{\"type\":\"bash_20250124\",\"name\":\"bash\"}]",
            "\"tools\":[{\"type\":\"text_editor_20250728\",\"name\":\"str_replace_based_edit_tool\"}]",
            "\"tools\":[{\"type\":\"computer_20251124\",\"name\":\"computer\"}]",
            "\"tools\":[{\"type\":\"memory_20250818\",\"name\":\"memory\"}]",
            "\"tools\":[{\"type\":\"mcp_toolset\",\"mcp_server_name\":\"x\"}]",
            "\"mcp_servers\":[{\"type\":\"url\",\"url\":\"https://x.example/mcp\",\"name\":\"x\"}]",
            "\"container\":\"container_011CT\""
        )) {
            Verdict verdict = messages(messagesBody(field, "\"q\""), FULL);
            assertThat(verdict.hard()).as(field).hasSize(1);
            assertThat(verdict.allowed()).as(field).isFalse();
        }
    }

    @Test
    void theToolSearchToolIsOnlyLeftOutSoThatTheMainLoopStillReachesTheRelay() {
        Verdict verdict = messages(messagesBody(
            "\"tools\":[{\"type\":\"tool_search_tool_bm25_20251119\",\"name\":\"tool_search_tool_bm25\"},"
                + "{\"name\":\"deferred\",\"description\":\"d\",\"defer_loading\":true,\"input_schema\":{\"type\":\"object\"}}]",
            "\"q\""
        ), NONE);

        assertThat(verdict.hard()).isEmpty();
        assertThat(verdict.soft()).containsExactly(Feature.TOOL_SEARCH);
    }

    @Test
    void imagesOfMessagesAreCarriedToAMemberWithVisionAndDocumentsAreSoftForEveryMemberAndRoute() {
        String content = "[{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://x.example/a.png\"}},"
            + "{\"type\":\"document\",\"source\":{\"type\":\"url\",\"url\":\"https://x.example/a.pdf\"}}]";

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : List.of(NONE, VISION, FULL)) {
                Verdict verdict = verdict(Entry.MESSAGES, messagesBody("", content), baseline, member);
                assertThat(verdict.hard()).isEmpty();
                assertThat(verdict.soft()).containsExactlyElementsOf(member.vision()
                    ? List.of(Feature.DOCUMENT) : List.of(Feature.IMAGE, Feature.DOCUMENT));
            }
        }
    }

    @Test
    void anImageTheConversionHasNoPlaceForIsSoftEvenForAMemberWithVision() {
        String file = "{\"type\":\"image\",\"source\":{\"type\":\"file\",\"file_id\":\"file_011\"}}";
        String data = "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}";
        String inAnswer = "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"a\"}," + data + "]}";

        for (Settings member : List.of(VISION, FULL)) {
            assertThat(messages(messagesBody("", "[" + data + "," + file + "]"), member).soft()).containsExactly(Feature.IMAGE);
            Detected assistant = RequestFeatureDetector.detect(Entry.MESSAGES, tree(
                "{\"model\":\"m\",\"max_tokens\":9,\"messages\":[{\"role\":\"user\",\"content\":\"q\"}," + inAnswer + "]}"));
            assertThat(FeaturePolicy.judge(assistant, Baseline.TEXT_ONLY, member).soft()).containsExactly(Feature.IMAGE);
        }
        // Every one of the user's images that the conversion gives an address to is carried.
        assertThat(messages(messagesBody("", "[" + data + "]"), VISION).soft()).isEmpty();
    }

    @Test
    void anImageInTheOutputOfAToolIsSoftForEveryMemberAndRouteAMessagesRequestHasNoBaseline() {
        String data = "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}";
        String content = "[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[" + data + "]}]";

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                Verdict verdict = verdict(Entry.MESSAGES, messagesBody("", content), baseline, member);
                assertThat(verdict.hard()).isEmpty();
                assertThat(verdict.soft()).containsExactly(Feature.TOOL_OUTPUT_IMAGE);
            }
        }
    }

    @Test
    void cacheControlIsCarriedToAMemberThatPassesItThroughAndTheToolResultErrorToEveryMember() {
        String body = """
            {"model":"claude-sonnet-4-5","max_tokens":10,
             "system":[{"type":"text","text":"s","cache_control":{"type":"ephemeral"}}],
             "messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"boom","is_error":true}]}]}
            """;

        // The failure of a tool result is told in the text of the result, which no member can refuse; the cache control is
        // sent to the members that pass it through and recorded as left out for the others.
        Verdict passes = messages(body, FULL);
        Verdict doesNot = messages(body, NONE);

        assertThat(passes.hard()).isEmpty();
        assertThat(passes.soft()).isEmpty();
        assertThat(doesNot.hard()).isEmpty();
        assertThat(doesNot.soft()).containsExactly(Feature.CACHE_CONTROL);
    }

    @Test
    void theFieldsTheConversionDropsAreSoft() {
        Verdict verdict = messages(messagesBody(
            "\"top_k\":40,\"metadata\":{\"user_id\":\"u\"},\"service_tier\":\"auto\",\"context_management\":{\"edits\":[{}]}",
            "[{\"type\":\"thinking\",\"thinking\":\"x\",\"signature\":\"s\"}]"
        ), FULL);

        assertThat(verdict.hard()).isEmpty();
        assertThat(verdict.soft()).containsExactly(
            Feature.CONTEXT_MANAGEMENT, Feature.HISTORY_THINKING, Feature.TOP_K, Feature.METADATA, Feature.SERVICE_TIER
        );
    }

    @Test
    void messagesThinkingIsCarriedOnlyToAnEffortMemberOnANewTurnWithALevelItKnows() {
        String adaptive = "\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"high\"}";
        String budget = "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":4000}";
        String unknown = "\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"turbo\"}";
        String toolResult = "[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"r\"}]";

        for (String thinking : List.of(adaptive, budget)) {
            assertThat(messages(messagesBody(thinking, "\"q\""), EFFORT).soft()).as(thinking).isEmpty();
            assertThat(messages(messagesBody(thinking, "\"q\""), NONE).soft()).as(thinking).containsExactly(Feature.THINKING);
            assertThat(messages(messagesBody(thinking, toolResult), EFFORT).soft()).as(thinking).containsExactly(Feature.THINKING);
            assertThat(messages(messagesBody(thinking, "\"q\""), FULL).hard()).as(thinking).isEmpty();
        }
        assertThat(messages(messagesBody(unknown, "\"q\""), EFFORT).soft()).containsExactly(Feature.THINKING);
    }

    @Test
    void anEffortThatSetsNothingIsSoft() {
        Verdict verdict = messages(messagesBody("\"output_config\":{\"effort\":\"low\"}", "\"q\""), EFFORT);

        assertThat(verdict.hard()).isEmpty();
        assertThat(verdict.soft()).containsExactly(Feature.OUTPUT_CONFIG);
    }

    @Test
    void aStructuredOutputRequestOfMessagesIsSoft() {
        Verdict verdict = messages(messagesBody(
            "\"output_config\":{\"format\":{\"type\":\"json_schema\",\"schema\":{\"type\":\"object\"}}}", "\"q\""), FULL);

        assertThat(verdict.hard()).isEmpty();
        assertThat(verdict.soft()).containsExactly(Feature.JSON_SCHEMA);
    }

    @ParameterizedTest
    @EnumSource(Baseline.class)
    void theBaselineDoesNotChangeWhatMessagesComesTo(Baseline baseline) {
        for (String last : List.of(RequestFeatureDetectorTest.NEXT_PROMPT, RequestFeatureDetectorTest.TOOL_RESULT_LAST)) {
            Detected detected = RequestFeatureDetector.detect(Entry.MESSAGES,
                tree(RequestFeatureDetectorTest.CLAUDE_CODE_TURN.formatted(last)));
            for (Settings member : allMembers()) {
                assertThat(FeaturePolicy.judge(detected, baseline, member))
                    .isEqualTo(FeaturePolicy.judge(detected, Baseline.TEXT_ONLY, member));
            }
        }
    }

    // ---------------------------------------------------------------------------------------- 7.9, CHAT

    @Test
    void whatChatCarriesNeedsNoSayInAnyWay() {
        Verdict verdict = chat("""
            {"model":"gpt-5","max_tokens":100,"temperature":0.3,"top_p":1,"seed":7,"frequency_penalty":0.1,"presence_penalty":0.2,
             "stop":["a","b","c","d"],"parallel_tool_calls":false,"tool_choice":"auto","response_format":{"type":"json_object"},
             "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],
             "messages":[{"role":"developer","content":"rules"},{"role":"user","content":"q"},
              {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]},
              {"role":"tool","tool_call_id":"c1","content":"r"},{"role":"assistant","content":"done"},{"role":"user","content":"next"}]}
            """, Baseline.PASSTHROUGH, NONE);

        assertThat(verdict.hard()).isEmpty();
        assertThat(verdict.soft()).isEmpty();
    }

    @Test
    void whatNoRelayTakesIsHardOnEveryRoute() {
        for (String field : List.of(
            "\"stop\":[\"a\",\"b\",\"c\",\"d\",\"e\"]", "\"n\":2", "\"web_search_options\":{}",
            "\"functions\":[{\"name\":\"f\"}]", "\"function_call\":\"auto\"",
            "\"tools\":[{\"type\":\"custom\",\"custom\":{\"name\":\"c\"}}]"
        )) {
            for (Baseline baseline : Baseline.values()) {
                Verdict verdict = chat(chatBody(field, "\"q\""), baseline, FULL);
                assertThat(verdict.hard()).as(field).hasSize(1);
            }
        }
    }

    @Test
    void theFastFlagOfTheBodyIsNothingAndAFastBilledRequestNeedsAPriorityTier() {
        String body = chatBody("\"service_tier\":\"priority\"", "\"q\"");
        Settings billedFast = member(false, false, false, false, false, true);
        Settings billedFastWithTier = member(false, false, true, false, false, true);

        assertThat(chat(body, Baseline.PASSTHROUGH, NONE).hard()).isEmpty();
        assertThat(chat(body, Baseline.PASSTHROUGH, billedFast).hard()).containsExactly(Feature.FAST);
        assertThat(chat(body, Baseline.PASSTHROUGH, billedFastWithTier).hard()).isEmpty();
        assertThat(chat(body, Baseline.PASSTHROUGH, billedFastWithTier).soft()).isEmpty();
        // The billing decides, not the entry or the body: a request billed as fast with no mention of it is the same.
        assertThat(chat(chatBody("", "\"q\""), Baseline.TEXT_ONLY, billedFast).hard()).containsExactly(Feature.FAST);
        assertThat(messages(messagesBody("", "\"q\""), billedFast).hard()).containsExactly(Feature.FAST);
    }

    @Test
    void chatReasoningEffortIsCarriedToAnEffortMemberAndSoftOtherwise() {
        String body = chatBody("\"reasoning_effort\":\"medium\"", "\"q\"");
        String continuation = "{\"model\":\"gpt-5\",\"reasoning_effort\":\"medium\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"},"
            + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}]}";

        assertThat(chat(body, Baseline.PASSTHROUGH, EFFORT).soft()).isEmpty();
        assertThat(chat(body, Baseline.PASSTHROUGH, NONE).soft()).containsExactly(Feature.REASONING_EFFORT);
        assertThat(chat(body, Baseline.PASSTHROUGH, NONE).hard()).isEmpty();
        assertThat(chat(continuation, Baseline.PASSTHROUGH, EFFORT).soft()).containsExactly(Feature.REASONING_EFFORT);
    }

    private static Settings forModelOf(Settings member, boolean continuationDropsReasoning) {
        return new Settings(member.vision(), member.jsonSchema(), member.priorityTier(), member.cacheControlPassthrough(),
            member.upstreamStream(), member.reasoningEffort(), member.billedFastMode(), member.maxCompletionTokens(),
            continuationDropsReasoning, member.needsToolsForToolHistory());
    }

    /**
     * The turn after a tool call is not asked for a level only where the model needs the thinking of the turn before, which
     * is Claude's. A gpt request in an agent loop is nearly all continuation turns, and a relay that never gets the level
     * would answer at the model's default for all of them.
     */
    @Test
    void aToolContinuationKeepsItsReasoningLevelForAnOpenAiModelAndLosesItForAClaudeModel() {
        String continuation = "{\"model\":\"gpt-5\",\"reasoning_effort\":\"minimal\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"},"
            + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}]}";
        Settings openAi = forModelOf(EFFORT, false);
        Settings claude = forModelOf(EFFORT, true);

        for (Baseline baseline : Baseline.values()) {
            assertThat(chat(continuation, baseline, openAi).soft()).as("openai %s", baseline).isEmpty();
            assertThat(chat(continuation, baseline, openAi).hard()).isEmpty();
            assertThat(chat(continuation, baseline, claude).soft()).as("claude %s", baseline).containsExactly(Feature.REASONING_EFFORT);
            // What else decides is not changed: a member that is not in effort mode does not get it either way.
            assertThat(chat(continuation, baseline, forModelOf(NONE, false)).soft()).containsExactly(Feature.REASONING_EFFORT);
        }
        // An Anthropic thinking request of a Messages entry follows the model too, not the entry.
        String toolResult = "[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"r\"}]";
        String thinking = "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":4000}";
        assertThat(messages(messagesBody(thinking, toolResult), openAi).soft()).isEmpty();
        assertThat(messages(messagesBody(thinking, toolResult), claude).soft()).containsExactly(Feature.THINKING);
    }

    private static final String ALLOWED_TOOLS = "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"bash\"}},"
        + "{\"type\":\"function\",\"function\":{\"name\":\"read\"}}],"
        + "\"tool_choice\":{\"type\":\"allowed_tools\",\"allowed_tools\":{\"mode\":\"required\","
        + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"read\"}}]}}";

    /** A restriction of the tools that a native OpenAI account would have carried must not be lost on the way to a relay. */
    @Test
    void aToolChoiceThatRestrictsTheToolsIsHardOnAPassthroughRouteAndSoftOnATextRoute() {
        String body = chatBody(ALLOWED_TOOLS, "\"q\"");

        for (Settings member : allMembers()) {
            assertThat(hard(chat(body, Baseline.PASSTHROUGH, member))).as("%s", member).containsExactly(Feature.TOOL_CHOICE);
            assertThat(chat(body, Baseline.PASSTHROUGH, member).soft()).isEmpty();
            assertThat(chat(body, Baseline.TEXT_ONLY, member).hard()).isEmpty();
            assertThat(soft(chat(body, Baseline.TEXT_ONLY, member))).containsExactly(Feature.TOOL_CHOICE);
        }
    }

    /** A native OpenAI account is handed the odds and the answer follows them: no relay is sent them, so they cannot be dropped quietly. */
    @Test
    void theOddsOfTheAnswerAreHardOnAPassthroughRouteAndSoftOnATextRoute() {
        String body = chatBody("\"logit_bias\":{\"50256\":-100}", "\"q\"");

        for (Settings member : allMembers()) {
            assertThat(hard(chat(body, Baseline.PASSTHROUGH, member))).as("%s", member).containsExactly(Feature.LOGIT_BIAS);
            assertThat(chat(body, Baseline.PASSTHROUGH, member).soft()).isEmpty();
            assertThat(chat(body, Baseline.TEXT_ONLY, member).hard()).isEmpty();
            assertThat(soft(chat(body, Baseline.TEXT_ONLY, member))).containsExactly(Feature.LOGIT_BIAS);
        }
    }

    /**
     * The length of the answer is only advice, which OpenCode sends with every request of a gpt model: it is left out of
     * the relay's body and recorded on every route, entry and member, so that a relay can serve those requests at all.
     * A request that asks for it and for the odds still has the odds as its hard feature.
     */
    @Test
    void theLengthOfTheAnswerIsSoftOnEveryRouteAndEntryAndMember() {
        String body = chatBody("\"verbosity\":\"low\"", "\"q\"");

        for (Settings member : allMembers()) {
            for (Baseline baseline : Baseline.values()) {
                Verdict verdict = chat(body, baseline, member);

                assertThat(verdict.hard()).as("%s %s", baseline, member).isEmpty();
                assertThat(soft(verdict)).as("%s %s", baseline, member).containsExactly(Feature.VERBOSITY);
                assertThat(verdict.allowed()).isTrue();
            }
            for (Entry entry : Entry.values()) {
                for (Baseline baseline : Baseline.values()) {
                    Detected detected = new Detected(entry, EnumSet.of(Feature.VERBOSITY), false, false);
                    assertThat(FeaturePolicy.treatment(Feature.VERBOSITY, detected, baseline, member)).as("%s %s", entry, baseline)
                        .isEqualTo(Treatment.SOFT);
                }
            }
        }
        String both = chatBody("\"verbosity\":\"high\",\"logit_bias\":{\"50256\":-100}", "\"q\"");
        for (Settings member : allMembers()) {
            Verdict verdict = chat(both, Baseline.PASSTHROUGH, member);
            assertThat(hard(verdict)).containsExactly(Feature.LOGIT_BIAS);
            assertThat(soft(verdict)).containsExactly(Feature.VERBOSITY);
        }
    }

    /** The default stated out loud asks for nothing: a gpt request that has it is not kept from every relay member. */
    @Test
    void aVerbosityOfMediumStatedOnAPassthroughRouteLeavesNoMemberOut() {
        String body = chatBody("\"verbosity\":\"medium\"", "\"q\"");

        for (Settings member : allMembers()) {
            for (Baseline baseline : Baseline.values()) {
                Verdict verdict = chat(body, baseline, member);

                assertThat(verdict.hard()).as("%s %s", baseline, member).isEmpty();
                assertThat(verdict.soft()).as("%s %s", baseline, member).isEmpty();
            }
        }
    }

    @Test
    void theCacheAndTheSafetyIdentifiersOfChatAreOnlyLeftOutOnEveryRoute() {
        String body = chatBody("\"prompt_cache_key\":\"sess-1\",\"prompt_cache_retention\":\"24h\",\"safety_identifier\":\"u-hash\"", "\"q\"");

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                assertThat(chat(body, baseline, member).hard()).isEmpty();
                assertThat(soft(chat(body, baseline, member))).as("%s %s", baseline, member)
                    .containsExactly(Feature.PROMPT_CACHE_KEY, Feature.PROMPT_CACHE_RETENTION, Feature.SAFETY_IDENTIFIER);
            }
        }
    }

    @Test
    void aToolChoiceARelayCanBeGivenIsNoFeature() {
        for (String choice : List.of("\"auto\"", "\"none\"", "\"required\"",
            "{\"type\":\"function\",\"function\":{\"name\":\"bash\"}}")) {
            String body = chatBody("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"bash\"}}],\"tool_choice\":" + choice, "\"q\"");
            for (Baseline baseline : Baseline.values()) {
                assertThat(chat(body, baseline, NONE).hard()).as(choice).isEmpty();
                assertThat(chat(body, baseline, NONE).soft()).as(choice).isEmpty();
            }
        }
    }

    @Test
    void theFieldsTheRelayIsNotSentAreSoft() {
        Verdict verdict = chat(chatBody("""
            "logprobs":true,"top_logprobs":2,"user":"u","metadata":{"k":"v"},"store":true,"prediction":{"type":"content","content":"x"},
            "modalities":["text","audio"],"top_k":5,"service_tier":"flex"
            """, "\"q\""), Baseline.PASSTHROUGH, FULL);

        assertThat(verdict.hard()).isEmpty();
        assertThat(verdict.soft()).containsExactly(
            Feature.TOP_K, Feature.METADATA, Feature.SERVICE_TIER, Feature.LOGPROBS, Feature.USER, Feature.STORE,
            Feature.PREDICTION, Feature.MODALITIES
        );
    }

    @Test
    void cacheControlOfChatIsCarriedToAMemberThatPassesItThrough() {
        String body = chatBody("", "[{\"type\":\"text\",\"text\":\"t\",\"cache_control\":{\"type\":\"ephemeral\"}}]");
        Settings passes = member(false, false, false, true, false, false);

        assertThat(chat(body, Baseline.PASSTHROUGH, passes).soft()).isEmpty();
        assertThat(chat(body, Baseline.PASSTHROUGH, NONE).soft()).containsExactly(Feature.CACHE_CONTROL);
        // Cache control is advice, not a message of the client: leaving it out is no reason to skip a member on any route.
        assertThat(chat(body, Baseline.PASSTHROUGH, NONE).hard()).isEmpty();
    }

    @Test
    void imagesOfChatFollowTheBaselineWhenTheMemberHasNoVision() {
        String body = chatBody("", "[" + IMAGE_PART + "]");

        assertThat(hard(chat(body, Baseline.PASSTHROUGH, NONE))).containsExactly(Feature.IMAGE);
        assertThat(soft(chat(body, Baseline.PASSTHROUGH, NONE))).isEmpty();
        assertThat(hard(chat(body, Baseline.TEXT_ONLY, NONE))).isEmpty();
        assertThat(soft(chat(body, Baseline.TEXT_ONLY, NONE))).containsExactly(Feature.IMAGE);
        // Another member's switch does not help.
        assertThat(hard(chat(body, Baseline.PASSTHROUGH, SCHEMA))).containsExactly(Feature.IMAGE);
        for (Baseline baseline : Baseline.values()) {
            assertThat(chat(body, baseline, VISION).hard()).isEmpty();
            assertThat(chat(body, baseline, VISION).soft()).isEmpty();
        }
    }

    @Test
    void responseSchemasOfChatFollowTheBaselineWhenTheMemberHasNoSchema() {
        String body = chatBody(SCHEMA_FORMAT, "\"q\"");

        assertThat(hard(chat(body, Baseline.PASSTHROUGH, NONE))).containsExactly(Feature.JSON_SCHEMA);
        assertThat(soft(chat(body, Baseline.TEXT_ONLY, NONE))).containsExactly(Feature.JSON_SCHEMA);
        assertThat(hard(chat(body, Baseline.PASSTHROUGH, VISION))).containsExactly(Feature.JSON_SCHEMA);
        for (Baseline baseline : Baseline.values()) {
            assertThat(chat(body, baseline, SCHEMA).hard()).isEmpty();
            assertThat(chat(body, baseline, SCHEMA).soft()).isEmpty();
        }
    }

    @Test
    void audioAndFilePartsAreNeverCarriedSoTheBaselineDecidesForEveryMember() {
        String body = chatBody("", "[" + AUDIO_PART + "," + FILE_PART + "]");

        for (Settings member : allMembers()) {
            assertThat(hard(chat(body, Baseline.PASSTHROUGH, member))).containsExactlyInAnyOrder(Feature.AUDIO, Feature.FILE);
            assertThat(soft(chat(body, Baseline.TEXT_ONLY, member))).containsExactlyInAnyOrder(Feature.AUDIO, Feature.FILE);
            assertThat(chat(body, Baseline.TEXT_ONLY, member).hard()).isEmpty();
        }
    }

    @Test
    void aFullMemberCarriesTheImageAndSchemaOfAGptRequestButNotItsAudio() {
        String body = chatBody(SCHEMA_FORMAT, "[" + IMAGE_PART + "," + AUDIO_PART + "]");

        Verdict verdict = chat(body, Baseline.PASSTHROUGH, FULL);

        assertThat(verdict.hard()).containsExactly(Feature.AUDIO);
        assertThat(verdict.soft()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- 7.9, RESPONSES

    /** The routes of the planner (DefaultSourceTierPlanner.nativeUnits) of the two models Codex is used with here. */
    private static final Baseline CLAUDE_ROUTE = Baseline.of(List.of(PoolCell.KIRO_COMPATIBLE));
    private static final Baseline GPT_ROUTE = Baseline.of(List.of(PoolCell.OFFICIAL_OPENAI));

    @Test
    void whatResponsesCarriesNeedsNoSayInAnyWay() {
        String body = """
            {"model":"claude-sonnet-4.5","instructions":"rules","max_output_tokens":100,"temperature":0.3,"top_p":1,
             "parallel_tool_calls":false,"tool_choice":{"type":"function","name":"f"},
             "tools":[{"type":"function","name":"f","parameters":{"type":"object"}},{"type":"custom","name":"apply_patch"}],
             "input":[{"type":"message","role":"developer","content":[{"type":"input_text","text":"rules"}]},
              {"type":"message","role":"user","content":[{"type":"input_text","text":"q"}]},
              {"type":"function_call","call_id":"c1","name":"f","arguments":"{}"},
              {"type":"function_call_output","call_id":"c1","output":"r"},
              {"type":"custom_tool_call","call_id":"c2","name":"apply_patch","input":"*** Begin Patch"},
              {"type":"custom_tool_call_output","call_id":"c2","output":"done"},
              {"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]},
              {"type":"message","role":"user","content":[{"type":"input_text","text":"next"}]}]}
            """;

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                Verdict verdict = responses(body, baseline, member);
                assertThat(verdict.hard()).isEmpty();
                assertThat(verdict.soft()).isEmpty();
            }
        }
    }

    /**
     * Codex declares a cached web search and the sub-agents' namespace in every request it makes for a model it has no
     * metadata for, so the request of the reference scenario has none of the things that keep a relay out.
     */
    @Test
    void theCodexRequestForAClaudeModelHasNoHardFeatureAndItsBuiltInToolsAreOnlyLeftOut() {
        for (Settings member : allMembers()) {
            Verdict verdict = responses(CodexRequests.CLAUDE_TURN, CLAUDE_ROUTE, member);

            assertThat(verdict.hard()).as("%s", member).isEmpty();
            assertThat(verdict.allowed()).isTrue();
            assertThat(soft(verdict)).containsExactlyInAnyOrder(Feature.BUILTIN_TOOL, Feature.PROMPT_CACHE_KEY);
        }
    }

    /**
     * The same request for a model OpenAI serves is handed to an OpenAI account whole, which runs the web search and finds
     * the deferred tools: a relay cannot, so it is not given the request at all (design 7.2).
     */
    @Test
    void theCodexRequestForAGptModelKeepsEveryRelayOutBecauseOfItsBuiltInTools() {
        for (Settings member : allMembers()) {
            Verdict verdict = responses(CodexRequests.GPT_TURN, GPT_ROUTE, member);

            assertThat(hard(verdict)).as("%s", member).containsExactly(Feature.BUILTIN_TOOL);
            // What is not the relay's to carry is only left out and recorded; the effort goes with a member that takes it.
            Set<Feature> left = EnumSet.of(Feature.INCLUDE, Feature.VERBOSITY, Feature.PROMPT_CACHE_KEY);
            if (!member.reasoningEffort()) {
                left.add(Feature.REASONING_EFFORT);
            }
            assertThat(soft(verdict)).isEqualTo(left);
        }
        // Without the tools nothing keeps a relay out.
        String lean = withoutBuiltInTools(CodexRequests.GPT_TURN);
        assertThat(lean).doesNotContain("web_search").doesNotContain("tool_search").contains("apply_patch");
        assertThat(responses(lean, GPT_ROUTE, EFFORT).hard()).isEmpty();
        assertThat(soft(responses(lean, GPT_ROUTE, EFFORT))).containsExactlyInAnyOrder(Feature.INCLUDE, Feature.VERBOSITY, Feature.PROMPT_CACHE_KEY);
        assertThat(soft(responses(lean, GPT_ROUTE, NONE))).contains(Feature.REASONING_EFFORT);
    }

    private static String withoutBuiltInTools(String json) {
        ObjectNode body = (ObjectNode) tree(json);
        ArrayNode kept = JSON.createArrayNode();
        for (JsonNode tool : body.path("tools")) {
            if (!Set.of("tool_search", "web_search").contains(tool.path("type").stringValue(""))) {
                kept.add(tool);
            }
        }
        body.set("tools", kept);
        return body.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "web_search", "web_search_preview", "file_search", "code_interpreter", "image_generation", "computer_use_preview", "mcp",
        "local_shell", "tool_search", "namespace"
    })
    void aBuiltInToolIsHardOnAPassthroughRouteAndSoftOnATextRoute(String type) {
        String body = responsesBody("\"tools\":[{\"type\":\"function\",\"name\":\"f\"},{\"type\":\"" + type + "\"}]", "\"q\"");

        for (Settings member : allMembers()) {
            assertThat(hard(responses(body, Baseline.PASSTHROUGH, member))).as("%s", member).containsExactly(Feature.BUILTIN_TOOL);
            assertThat(responses(body, Baseline.PASSTHROUGH, member).soft()).isEmpty();
            assertThat(responses(body, Baseline.TEXT_ONLY, member).hard()).isEmpty();
            assertThat(soft(responses(body, Baseline.TEXT_ONLY, member))).containsExactly(Feature.BUILTIN_TOOL);
        }
    }

    @Test
    void aCustomToolOtherThanTheEditingToolIsHardOnEveryRoute() {
        String body = responsesBody("\"tools\":[{\"type\":\"custom\",\"name\":\"shell\"}]", "\"q\"");

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                assertThat(hard(responses(body, baseline, member))).containsExactly(Feature.CUSTOM_TOOL);
            }
        }
    }

    @Test
    void aChainThisNodeCouldNotExpandIsHardOnEveryRoute() {
        String body = responsesBody("\"previous_response_id\":\"resp_0123\"", "\"q\"");

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                Verdict verdict = responses(body, baseline, member);
                assertThat(hard(verdict)).containsExactly(Feature.PREVIOUS_RESPONSE_ID);
                assertThat(verdict.allowed()).isFalse();
            }
        }
    }

    /**
     * What the request only points at (a conversation, a stored prompt, an item of an earlier response, a compaction) is held
     * by OpenAI, which a native account is handed the body for and the conversion to a relay's body cannot make: the relay
     * would answer without it and be paid for the answer, so it is out on every route, as an unexpandable chain is.
     */
    @Test
    void contextTheServerHoldsIsHardOnEveryRoute() {
        for (String extra : List.of("\"conversation\":\"conv_abc123\"", "\"conversation\":{\"id\":\"conv_abc123\"}",
            "\"prompt\":{\"id\":\"pmpt_abc\",\"variables\":{\"city\":\"Paris\"}}")) {
            for (Baseline baseline : Baseline.values()) {
                for (Settings member : allMembers()) {
                    Verdict verdict = responses(responsesBody(extra, "\"q\""), baseline, member);
                    assertThat(hard(verdict)).as("%s %s %s", extra, baseline, member).containsExactly(Feature.SERVER_HELD_CONTEXT);
                    assertThat(verdict.allowed()).isFalse();
                }
            }
        }
        for (String item : List.of("{\"type\":\"item_reference\",\"id\":\"msg_stored_earlier\"}",
            "{\"type\":\"compaction\",\"encrypted_content\":\"gAAAA\"}")) {
            String body = "{\"model\":\"m\",\"input\":[" + item + ",{\"type\":\"message\",\"role\":\"user\",\"content\":\"continue\"}]}";
            for (Baseline baseline : Baseline.values()) {
                for (Settings member : allMembers()) {
                    assertThat(hard(responses(body, baseline, member))).as("%s %s %s", item, baseline, member)
                        .containsExactly(Feature.SERVER_HELD_CONTEXT);
                }
            }
        }
    }

    /**
     * An image the conversion has no place for is lost by every member, the one with vision too, so it is what an image
     * is for a member without: a passthrough route keeps the relay out, a text route leaves the image out and says so.
     */
    @Test
    void anImageTheConversionHasNoPlaceForFollowsTheBaselineWhateverTheVisionOfTheMember() {
        String fileOnly = responsesBody("", "[{\"type\":\"input_text\",\"text\":\"what is this?\"},{\"type\":\"input_image\",\"file_id\":\"file-abc123\"}]");
        String inTheDeveloperMessage = "{\"model\":\"m\",\"input\":[{\"type\":\"message\",\"role\":\"developer\",\"content\":"
            + "[{\"type\":\"input_text\",\"text\":\"use this logo\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,iVBORw0KGgo=\"}]},"
            + "{\"type\":\"message\",\"role\":\"user\",\"content\":\"q\"}]}";

        for (String body : List.of(fileOnly, inTheDeveloperMessage)) {
            for (Settings member : allMembers()) {
                assertThat(hard(responses(body, GPT_ROUTE, member))).as("%s", member).containsExactly(Feature.IMAGE);
                assertThat(responses(body, GPT_ROUTE, member).soft()).isEmpty();
                assertThat(responses(body, CLAUDE_ROUTE, member).hard()).isEmpty();
                assertThat(soft(responses(body, CLAUDE_ROUTE, member))).as("%s", member).containsExactly(Feature.IMAGE);
            }
        }
        // The same image by its URL is the member's to carry.
        String byUrl = responsesBody("", "[{\"type\":\"input_text\",\"text\":\"what is this?\"},{\"type\":\"input_image\",\"image_url\":\"https://x.example/a.png\"}]");
        assertThat(responses(byUrl, GPT_ROUTE, VISION).hard()).isEmpty();
        assertThat(responses(byUrl, GPT_ROUTE, VISION).soft()).isEmpty();
        assertThat(hard(responses(byUrl, GPT_ROUTE, NONE))).containsExactly(Feature.IMAGE);
    }

    @Test
    void theCapOnToolCallsAndTheSummaryOfTheReasoningAreSoftOnEveryRouteAndMember() {
        String body = responsesBody("\"max_tool_calls\":2,\"reasoning\":{\"summary\":\"auto\"}", "\"q\"");

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                Verdict verdict = responses(body, baseline, member);
                assertThat(verdict.hard()).isEmpty();
                assertThat(soft(verdict)).containsExactlyInAnyOrder(Feature.MAX_TOOL_CALLS, Feature.REASONING_SUMMARY);
            }
        }
    }

    @Test
    void theLengthOfTheAnswerOfAResponsesRequestIsSoftOnEveryRouteAndMemberAndMediumIsNothing() {
        for (Settings member : allMembers()) {
            for (Baseline baseline : Baseline.values()) {
                for (String level : List.of("low", "high")) {
                    Verdict verdict = responses(responsesBody("\"text\":{\"verbosity\":\"" + level + "\"}", "\"q\""), baseline, member);

                    assertThat(verdict.hard()).as("%s %s %s", level, baseline, member).isEmpty();
                    assertThat(soft(verdict)).containsExactly(Feature.VERBOSITY);
                }
                Verdict medium = responses(responsesBody("\"text\":{\"verbosity\":\"medium\"}", "\"q\""), baseline, member);
                assertThat(medium.hard()).isEmpty();
                assertThat(medium.soft()).isEmpty();
            }
        }
    }

    @Test
    void theReasoningEffortOfResponsesIsCarriedToAnEffortMemberOnAnyTurnOfAnOpenAiModelAndLostOnAToolContinuationOfAClaudeModel() {
        String effort = "\"reasoning\":{\"effort\":\"high\"}";
        String continuation = "{\"model\":\"m\"," + effort + ",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"q\"},"
            + "{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"f\",\"arguments\":\"{}\"},"
            + "{\"type\":\"function_call_output\",\"call_id\":\"c\",\"output\":\"r\"}]}";
        Settings openAi = forModelOf(EFFORT, false);
        Settings claude = forModelOf(EFFORT, true);

        for (Baseline baseline : Baseline.values()) {
            assertThat(responses(responsesBody(effort, "\"q\""), baseline, claude).soft()).isEmpty();
            assertThat(responses(continuation, baseline, openAi).soft()).as("openai %s", baseline).isEmpty();
            assertThat(responses(continuation, baseline, claude).soft()).as("claude %s", baseline).containsExactly(Feature.REASONING_EFFORT);
            assertThat(responses(responsesBody(effort, "\"q\""), baseline, NONE).soft()).containsExactly(Feature.REASONING_EFFORT);
            assertThat(responses(continuation, baseline, claude).hard()).isEmpty();
        }
    }

    @Test
    void theReasoningOfEarlierTurnsAndTheFieldsOnlyLeftOutAreSoftOnEveryRoute() {
        String body = "{\"model\":\"m\",\"include\":[\"reasoning.encrypted_content\"],\"store\":true,\"truncation\":\"auto\","
            + "\"prompt_cache_key\":\"k\",\"metadata\":{\"a\":\"b\"},\"service_tier\":\"flex\","
            + "\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"q\"},{\"type\":\"reasoning\",\"id\":\"rs_1\"}]}";

        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                Verdict verdict = responses(body, baseline, member);
                assertThat(verdict.hard()).isEmpty();
                assertThat(soft(verdict)).containsExactlyInAnyOrder(Feature.INCLUDE, Feature.STORE, Feature.TRUNCATION,
                    Feature.PROMPT_CACHE_KEY, Feature.METADATA, Feature.SERVICE_TIER, Feature.HISTORY_THINKING);
            }
        }
    }

    /** The image of a user's message is given to a member that has vision; otherwise a route that hands it to its account whole keeps the relay out. */
    @Test
    void imagesAndSchemasOfResponsesFollowTheBaselineWhenTheMemberHasNoVisionOrSchema() {
        String body = responsesBody("\"text\":{\"format\":{\"type\":\"json_schema\",\"name\":\"s\",\"schema\":{\"type\":\"object\"}}}",
            "[{\"type\":\"input_text\",\"text\":\"describe\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,iVBORw0KGgo=\"}]");

        Verdict gpt = responses(body, GPT_ROUTE, NONE);
        assertThat(hard(gpt)).containsExactlyInAnyOrder(Feature.IMAGE, Feature.JSON_SCHEMA);
        assertThat(soft(gpt)).isEmpty();
        Verdict claude = responses(body, CLAUDE_ROUTE, NONE);
        assertThat(claude.hard()).isEmpty();
        assertThat(soft(claude)).containsExactlyInAnyOrder(Feature.IMAGE, Feature.JSON_SCHEMA);
        for (Baseline baseline : Baseline.values()) {
            Verdict carried = responses(body, baseline, member(true, true, false, false, false, false));
            assertThat(carried.hard()).isEmpty();
            assertThat(carried.soft()).isEmpty();
        }
        // Only what the member can carry goes with it.
        assertThat(hard(responses(body, Baseline.PASSTHROUGH, VISION))).containsExactly(Feature.JSON_SCHEMA);
        assertThat(hard(responses(body, Baseline.PASSTHROUGH, SCHEMA))).containsExactly(Feature.IMAGE);
    }

    @Test
    void anImageInTheOutputOfAToolAndTheAudioAndFilesOfResponsesAreNeverCarriedSoTheBaselineDecides() {
        String call = "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"view_image\",\"arguments\":\"{}\"}";
        String body = "{\"model\":\"m\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":["
            + "{\"type\":\"input_file\",\"file_id\":\"f1\"},{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AAAA\",\"format\":\"wav\"}}]},"
            + call + ",{\"type\":\"function_call_output\",\"call_id\":\"c1\",\"output\":[{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,iVBORw0KGgo=\"}]}]}";

        for (Settings member : allMembers()) {
            assertThat(hard(responses(body, Baseline.PASSTHROUGH, member)))
                .containsExactlyInAnyOrder(Feature.FILE, Feature.AUDIO, Feature.TOOL_OUTPUT_IMAGE);
            assertThat(responses(body, Baseline.TEXT_ONLY, member).hard()).isEmpty();
            assertThat(soft(responses(body, Baseline.TEXT_ONLY, member)))
                .containsExactlyInAnyOrder(Feature.FILE, Feature.AUDIO, Feature.TOOL_OUTPUT_IMAGE);
        }
    }

    @Test
    void aToolChoiceThatRestrictsTheToolsOfAResponsesRequestIsHardOnAPassthroughRouteAndSoftOnATextRoute() {
        String body = responsesBody("\"tools\":[{\"type\":\"function\",\"name\":\"bash\"},{\"type\":\"function\",\"name\":\"read\"}],"
            + "\"tool_choice\":{\"type\":\"allowed_tools\",\"mode\":\"required\",\"tools\":[{\"type\":\"function\",\"name\":\"read\"}]}", "\"q\"");

        for (Settings member : allMembers()) {
            assertThat(hard(responses(body, Baseline.PASSTHROUGH, member))).containsExactly(Feature.TOOL_CHOICE);
            assertThat(responses(body, Baseline.TEXT_ONLY, member).hard()).isEmpty();
            assertThat(soft(responses(body, Baseline.TEXT_ONLY, member))).containsExactly(Feature.TOOL_CHOICE);
        }
    }

    @Test
    void aFastBilledResponsesRequestNeedsAPriorityTier() {
        Settings billedFast = member(false, false, false, false, false, true);
        Settings billedFastWithTier = member(false, false, true, false, false, true);

        assertThat(responses(responsesBody("", "\"q\""), Baseline.PASSTHROUGH, billedFast).hard()).containsExactly(Feature.FAST);
        assertThat(responses(responsesBody("\"service_tier\":\"priority\"", "\"q\""), Baseline.PASSTHROUGH, billedFastWithTier).hard()).isEmpty();
        assertThat(responses(responsesBody("\"service_tier\":\"priority\"", "\"q\""), Baseline.PASSTHROUGH, NONE).hard()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- the rest

    @ParameterizedTest
    @EnumSource(Entry.class)
    void noEntryOfTheRegistryIsHardForWantingATable(Entry entry) {
        for (Baseline baseline : Baseline.values()) {
            for (Settings member : allMembers()) {
                Verdict verdict = verdict(entry, "{\"model\":\"m\"}", baseline, member);
                assertThat(verdict.hard()).doesNotContain(Feature.UNSUPPORTED_ENTRY);
            }
        }
    }

    @Test
    void theVerdictListsFeaturesInTheOrderOfTheEnumAndCannotBeChanged() {
        Verdict verdict = messages(messagesBody("\"top_k\":1,\"mcp_servers\":[{\"name\":\"x\"}],\"stop_sequences\":[\"a\",\"b\",\"c\",\"d\",\"e\"]",
            "\"q\""), NONE);

        assertThat(verdict.hard()).containsExactly(Feature.STOP_SEQUENCES_OVER_4, Feature.MCP_SERVERS);
        assertThat(verdict.soft()).containsExactly(Feature.TOP_K);
        assertThatThrownBy(() -> verdict.hard().add(Feature.FAST))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRequestWithoutFeaturesIsAllowedAndRecordsNothing() {
        Verdict verdict = chat(chatBody("", "\"q\""), Baseline.PASSTHROUGH, NONE);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.hard()).isEmpty();
        assertThat(verdict.soft()).isEmpty();
    }

    /** Whatever the request, a member that can carry more is never worse off than one that can carry less. */
    @Test
    void aSwitchTurnedOnNeverMakesAFeatureWorse() {
        List<Detected> requests = List.of(
            RequestFeatureDetector.detect(Entry.MESSAGES, tree(RequestFeatureDetectorTest.CLAUDE_CODE_TURN.formatted(RequestFeatureDetectorTest.NEXT_PROMPT))),
            RequestFeatureDetector.detect(Entry.MESSAGES, tree(RequestFeatureDetectorTest.CLAUDE_CODE_TURN.formatted(RequestFeatureDetectorTest.TOOL_RESULT_LAST))),
            RequestFeatureDetector.detect(Entry.CHAT, tree(chatBody("\"reasoning_effort\":\"low\"," + SCHEMA_FORMAT,
                "[{\"type\":\"text\",\"text\":\"t\",\"cache_control\":{\"type\":\"ephemeral\"}}," + IMAGE_PART + "," + AUDIO_PART + "," + FILE_PART + "]"))),
            RequestFeatureDetector.detect(Entry.RESPONSES, tree(CodexRequests.GPT_TURN)),
            RequestFeatureDetector.detect(Entry.RESPONSES, tree(CodexRequests.CLAUDE_TURN))
        );
        for (Detected detected : requests) {
            for (Baseline baseline : Baseline.values()) {
                for (Feature feature : Feature.values()) {
                    for (int bits = 0; bits < 64; bits++) {
                        Treatment base = FeaturePolicy.treatment(feature, detected, baseline, fromBits(bits));
                        // Bit 5 is what the request is billed as, not what the member can do, so it is not a switch.
                        for (int bit = 0; bit < 5; bit++) {
                            if ((bits & (1 << bit)) != 0) {
                                continue;
                            }
                            Treatment more = FeaturePolicy.treatment(feature, detected, baseline, fromBits(bits | (1 << bit)));
                            assertThat(more.ordinal()).as("%s with bit %d on top of %d", feature, bit, bits)
                                .isLessThanOrEqualTo(base.ordinal());
                        }
                    }
                }
            }
        }
    }

    /**
     * A function name with a dot or a colon is the Gemini API's, and the native accounts of a Gemini request take it as it is: a
     * relay in front of any other upstream is left out for it (HARD, so that a request pinned to such a relay is told why), and
     * one in front of Gemini carries it.
     */
    @Test
    void aFunctionNameOnlyGeminiTakesIsCarriedByAGeminiVendorAndHardForAnyOther() {
        Detected detected = new Detected(Entry.GEMINI, EnumSet.of(Feature.FUNCTION_NAME), false, false);
        Settings other = new Settings(false, false, false, false, true, false, false, false, false, false, false);
        Settings google = new Settings(false, false, false, false, true, false, false, false, false, true, true);

        for (Baseline baseline : Baseline.values()) {
            assertThat(FeaturePolicy.judge(detected, baseline, other).hard()).containsExactly(Feature.FUNCTION_NAME);
            assertThat(FeaturePolicy.judge(detected, baseline, google).hard()).isEmpty();
            assertThat(FeaturePolicy.judge(detected, baseline, google).soft()).isEmpty();
        }
    }

    @Test
    void theFeaturesNoRelayCarriesAreHardForEveryMemberRouteAndEntry() {
        EnumSet<Feature> alwaysHard = EnumSet.of(
            Feature.STOP_SEQUENCES_OVER_4, Feature.N_OVER_ONE, Feature.WEB_SEARCH_OPTIONS, Feature.LEGACY_FUNCTIONS,
            Feature.NON_FUNCTION_TOOL, Feature.WEB_SEARCH, Feature.WEB_FETCH, Feature.CODE_EXECUTION, Feature.BASH_TOOL,
            Feature.TEXT_EDITOR_TOOL, Feature.COMPUTER_TOOL, Feature.MEMORY_TOOL, Feature.ANTHROPIC_DEFINED_TOOL,
            Feature.MCP_SERVERS, Feature.CONTAINER, Feature.PREVIOUS_RESPONSE_ID, Feature.SERVER_HELD_CONTEXT, Feature.CUSTOM_TOOL,
            Feature.UNSUPPORTED_ENTRY
        );
        Set<Feature> neverHard = EnumSet.complementOf(alwaysHard);
        neverHard.remove(Feature.FAST);
        neverHard.remove(Feature.FUNCTION_NAME);
        neverHard.removeAll(EnumSet.of(
            Feature.IMAGE, Feature.JSON_SCHEMA, Feature.AUDIO, Feature.FILE, Feature.TOOL_CHOICE, Feature.LOGIT_BIAS,
            Feature.BUILTIN_TOOL, Feature.TOOL_OUTPUT_IMAGE
        ));

        for (Entry entry : Entry.values()) {
            Detected detected = new Detected(entry, EnumSet.noneOf(Feature.class), false, false);
            for (Baseline baseline : Baseline.values()) {
                for (Settings member : allMembers()) {
                    for (Feature feature : alwaysHard) {
                        assertThat(FeaturePolicy.treatment(feature, detected, baseline, member)).as("%s %s", entry, feature)
                            .isEqualTo(Treatment.HARD);
                    }
                    for (Feature feature : neverHard) {
                        assertThat(FeaturePolicy.treatment(feature, detected, baseline, member)).as("%s %s", entry, feature)
                            .isNotEqualTo(Treatment.HARD);
                    }
                }
            }
        }
    }

    /** What an entry's conversion carries, by entry: the table the class keeps, read through the only door there is to it. */
    @Test
    void theConversionOfEachEntryCarriesOnlyWhatItHas() {
        Map<Entry, Set<Feature>> carried = Map.of(
            Entry.CHAT, EnumSet.of(Feature.IMAGE, Feature.CACHE_CONTROL, Feature.JSON_SCHEMA, Feature.THINKING, Feature.REASONING_EFFORT),
            Entry.MESSAGES, EnumSet.of(Feature.IMAGE, Feature.CACHE_CONTROL, Feature.TOOL_RESULT_ERROR, Feature.THINKING),
            Entry.RESPONSES, EnumSet.of(Feature.IMAGE, Feature.JSON_SCHEMA, Feature.REASONING_EFFORT),
            Entry.GEMINI, EnumSet.of(Feature.IMAGE, Feature.JSON_SCHEMA, Feature.THINKING));
        for (Entry entry : Entry.values()) {
            Detected detected = new Detected(entry, EnumSet.noneOf(Feature.class), false, false);
            for (Feature feature : EnumSet.of(Feature.IMAGE, Feature.JSON_SCHEMA, Feature.THINKING, Feature.REASONING_EFFORT,
                Feature.CACHE_CONTROL, Feature.TOOL_RESULT_ERROR)) {
                assertThat(FeaturePolicy.treatment(feature, detected, Baseline.TEXT_ONLY, FULL) == Treatment.CARRY)
                    .as("%s %s", entry, feature).isEqualTo(carried.get(entry).contains(feature));
            }
        }
    }

    @Test
    void onlyAChatOrResponsesRequestToAPassthroughRouteMakesTheConditionalFeaturesHard() {
        Set<Feature> conditional = EnumSet.of(
            Feature.IMAGE, Feature.JSON_SCHEMA, Feature.AUDIO, Feature.FILE, Feature.TOOL_CHOICE, Feature.BUILTIN_TOOL,
            Feature.TOOL_OUTPUT_IMAGE
        );
        for (Entry entry : Entry.values()) {
            Detected detected = new Detected(entry, EnumSet.noneOf(Feature.class), false, false);
            for (Baseline baseline : Baseline.values()) {
                for (Settings member : allMembers()) {
                    for (Feature feature : conditional) {
                        boolean hard = FeaturePolicy.treatment(feature, detected, baseline, member) == Treatment.HARD;
                        assertThat(hard).as("%s %s %s", entry, baseline, feature)
                            .isEqualTo((entry == Entry.CHAT || entry == Entry.RESPONSES) && baseline == Baseline.PASSTHROUGH
                                && !carries(feature, member));
                    }
                }
            }
        }
    }

    private static boolean carries(Feature feature, Settings member) {
        return switch (feature) {
            case IMAGE -> member.vision();
            case JSON_SCHEMA -> member.jsonSchema();
            default -> false;
        };
    }

    // ---- helpers ----

    private static Settings fromBits(int bits) {
        return member((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0, (bits & 16) != 0, (bits & 32) != 0);
    }

    /** Every combination of a member's five switches, for a request that is not billed as fast. */
    private static List<Settings> allMembers() {
        return IntStream.range(0, 32).mapToObj(FeaturePolicyTest::fromBits).toList();
    }
}
