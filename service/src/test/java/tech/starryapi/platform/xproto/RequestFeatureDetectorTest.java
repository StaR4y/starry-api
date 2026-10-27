package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Detected;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Feature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Design 7.9: what is found in the requests of the Messages, Chat and Responses entries. A feature is found by where the wire
 * format puts it, so the negative cases matter as much as the positive ones: a tool input with a {@code cache_control}
 * key and a question about {@code web_search} are not features. The Claude Code request is the shape of the main loop
 * (adaptive thinking with an effort, cache control on the system prompt, a tool and a user text, a deferred tool and
 * the tool-search tool, context management and metadata, thinking of earlier turns in the history).
 */
class RequestFeatureDetectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static Detected messages(String json) {
        return RequestFeatureDetector.detect(Entry.MESSAGES, tree(json));
    }

    private static Detected chat(String json) {
        return RequestFeatureDetector.detect(Entry.CHAT, tree(json));
    }

    private static Detected responses(String json) {
        return RequestFeatureDetector.detect(Entry.RESPONSES, tree(json));
    }

    private static String messagesBody(String extra, String messages) {
        return "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":1024" + (extra.isEmpty() ? "" : "," + extra)
            + ",\"messages\":" + messages + "}";
    }

    private static String oneUserMessage(String content) {
        return "[{\"role\":\"user\",\"content\":" + content + "}]";
    }

    private static String chatBody(String extra, String messages) {
        return "{\"model\":\"gpt-5\"" + (extra.isEmpty() ? "" : "," + extra) + ",\"messages\":" + messages + "}";
    }

    // ---------------------------------------------------------------------------------------- Messages

    static final String CLAUDE_CODE_TURN = """
        {"model":"claude-sonnet-4-5-20250929","max_tokens":32000,"stream":true,"temperature":1,
         "metadata":{"user_id":"user_0123456789abcdef_account__session_00000000-0000-0000-0000-000000000000"},
         "thinking":{"type":"adaptive"},"output_config":{"effort":"medium"},
         "context_management":{"edits":[{"type":"clear_thinking_20251015"}]},
         "system":[{"type":"text","text":"You are Claude Code.","cache_control":{"type":"ephemeral"}},{"type":"text","text":"cwd: /work"}],
         "tools":[
          {"name":"Bash","description":"Runs a command","input_schema":{"type":"object","properties":{"command":{"type":"string"}}},
           "cache_control":{"type":"ephemeral"}},
          {"type":"tool_search_tool_regex_20251119","name":"tool_search_tool_regex"},
          {"name":"mcp__github__search_issues","description":"Searches issues","defer_loading":true,
           "input_schema":{"type":"object","properties":{"query":{"type":"string"}}}}],
         "tool_choice":{"type":"auto"},
         "messages":[
          {"role":"user","content":[{"type":"text","text":"<system-reminder>context</system-reminder>"},
           {"type":"text","text":"list the files","cache_control":{"type":"ephemeral"}}]},
          {"role":"assistant","content":[{"type":"thinking","thinking":"hm","signature":"sig"},{"type":"text","text":"Listing."},
           {"type":"tool_use","id":"toolu_01AAAA","name":"Bash","input":{"command":"ls"}}]},
          %s]}
        """;

    static final String NEXT_PROMPT = "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01AAAA\","
        + "\"content\":\"a.txt\"},{\"type\":\"text\",\"text\":\"<system-reminder>todo</system-reminder>\"}]},"
        + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"One file.\"}]},"
        + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"thanks\"}]}";
    static final String TOOL_RESULT_LAST = "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
        + "\"tool_use_id\":\"toolu_01AAAA\",\"content\":\"a.txt\"}]}";

    @Test
    void theClaudeCodeMainLoopHasTheFeaturesItAlwaysHasAndNoHardOne() {
        Detected detected = messages(CLAUDE_CODE_TURN.formatted(NEXT_PROMPT));

        assertThat(detected.features()).containsExactlyInAnyOrder(
            Feature.CACHE_CONTROL, Feature.THINKING, Feature.TOOL_SEARCH, Feature.CONTEXT_MANAGEMENT, Feature.METADATA,
            Feature.HISTORY_THINKING
        );
        assertThat(detected.toolContinuation()).isFalse();
        assertThat(detected.reasoningUnmapped()).isFalse();
    }

    @Test
    void aRequestThatEndsWithToolResultsIsAToolContinuation() {
        assertThat(messages(CLAUDE_CODE_TURN.formatted(TOOL_RESULT_LAST)).toolContinuation()).isTrue();
    }

    @Test
    void aPlainTextRequestHasNoFeature() {
        Detected detected = messages(messagesBody("\"temperature\":0.2,\"system\":\"Be brief\",\"stream\":true",
            oneUserMessage("\"hello\"")));

        assertThat(detected.features()).isEmpty();
        assertThat(detected.entry()).isEqualTo(Entry.MESSAGES);
        assertThat(detected.toolContinuation()).isFalse();
    }

    @Test
    void textAndToolsOfTheClientsOwnAreNothing() {
        Detected detected = messages(messagesBody("""
            "system":[{"type":"text","text":"sys"}],
            "tools":[{"name":"Read","description":"d","input_schema":{"type":"object"}},
                     {"type":"custom","name":"Write","description":"d","input_schema":{"type":"object"}}],
            "tool_choice":{"type":"any","disable_parallel_tool_use":true},"top_p":0.9
            """, """
            [{"role":"user","content":"read it"},
             {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"p":"a"}}]},
             {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"ok"}]}]}]
            """));

        assertThat(detected.features()).isEmpty();
        assertThat(detected.toolContinuation()).isTrue();
    }

    // ---- the tools Anthropic defines ----

    @ParameterizedTest
    @CsvSource({
        "web_search_20250305, WEB_SEARCH",
        "web_search_20260209, WEB_SEARCH",
        "web_fetch_20250910, WEB_FETCH",
        "code_execution_20250522, CODE_EXECUTION",
        "code_execution_20250825, CODE_EXECUTION",
        "bash_20250124, BASH_TOOL",
        "text_editor_20250728, TEXT_EDITOR_TOOL",
        "computer_20251124, COMPUTER_TOOL",
        "memory_20250818, MEMORY_TOOL",
        "tool_search_tool_regex_20251119, TOOL_SEARCH",
        "tool_search_tool_bm25_20251119, TOOL_SEARCH",
        "Web_Search_20250305, WEB_SEARCH",
        "mcp_toolset, ANTHROPIC_DEFINED_TOOL",
        "something_new_20300101, ANTHROPIC_DEFINED_TOOL"
    })
    void aToolAnthropicDefinesIsTheFeatureOfItsFamily(String type, Feature expected) {
        Detected detected = messages(messagesBody(
            "\"tools\":[{\"name\":\"x\",\"description\":\"d\",\"input_schema\":{\"type\":\"object\"}},"
                + "{\"type\":\"" + type + "\",\"name\":\"x2\"}]",
            oneUserMessage("\"q\"")
        ));

        assertThat(detected.features()).containsExactly(expected);
    }

    @Test
    void aClientsFunctionNamedLikeAServerToolIsNotOne() {
        Detected detected = messages(messagesBody(
            "\"tools\":[{\"name\":\"web_search\",\"description\":\"search the web\",\"input_schema\":{\"type\":\"object\"}},"
                + "{\"name\":\"bash\",\"description\":\"bash_20250124\",\"input_schema\":{\"type\":\"object\"}}]",
            oneUserMessage("\"please use web_search_20250305 and computer use\"")
        ));

        assertThat(detected.features()).isEmpty();
    }

    @Test
    void mcpServersAndAContainerAreFeaturesOnlyWhenTheyHoldSomething() {
        assertThat(messages(messagesBody("\"mcp_servers\":[{\"type\":\"url\",\"url\":\"https://x.example/mcp\",\"name\":\"x\"}]",
            oneUserMessage("\"q\""))).features()).containsExactly(Feature.MCP_SERVERS);
        assertThat(messages(messagesBody("\"container\":\"container_011CT\"", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.CONTAINER);
        assertThat(messages(messagesBody("\"mcp_servers\":[],\"container\":null", oneUserMessage("\"q\""))).features())
            .isEmpty();
    }

    // ---- stop sequences ----

    @Test
    void fourStopSequencesAreCarriedAndFiveAreNot() {
        assertThat(messages(messagesBody("\"stop_sequences\":[\"a\",\"b\",\"c\",\"d\"]", oneUserMessage("\"q\""))).features())
            .isEmpty();
        assertThat(messages(messagesBody("\"stop_sequences\":[\"a\",\"b\",\"c\",\"d\",\"e\"]", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.STOP_SEQUENCES_OVER_4);
    }

    @Test
    void emptyStopSequencesDoNotCountBecauseTheSanitizerDropsThem() {
        assertThat(messages(messagesBody("\"stop_sequences\":[\"a\",\"\",\"b\",\"c\",\"\",\"d\"]", oneUserMessage("\"q\""))).features())
            .isEmpty();
    }

    // ---- left out, recorded ----

    @Test
    void theFieldsTheConversionDropsAreSoftFeatures() {
        Detected detected = messages(messagesBody("""
            "top_k":40,"metadata":{"user_id":"u"},"service_tier":"auto","context_management":{"edits":[]},
            "cache_control":{"type":"ephemeral"}
            """, oneUserMessage("\"q\"")));

        assertThat(detected.features()).containsExactlyInAnyOrder(
            Feature.TOP_K, Feature.METADATA, Feature.SERVICE_TIER, Feature.CONTEXT_MANAGEMENT, Feature.CACHE_CONTROL
        );
    }

    @Test
    void emptyOrNullSoftFieldsAreNothing() {
        Detected detected = messages(messagesBody(
            "\"top_k\":null,\"metadata\":{},\"service_tier\":null,\"context_management\":{},\"cache_control\":null",
            oneUserMessage("\"q\"")
        ));

        assertThat(detected.features()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // system block
        "\"system\":[{\"type\":\"text\",\"text\":\"s\",\"cache_control\":{\"type\":\"ephemeral\"}}]",
        // tool
        "\"tools\":[{\"name\":\"t\",\"description\":\"d\",\"input_schema\":{\"type\":\"object\"},\"cache_control\":{\"type\":\"ephemeral\"}}]"
    })
    void cacheControlOnTheSystemPromptOrAToolIsFound(String field) {
        assertThat(messages(messagesBody(field, oneUserMessage("\"q\""))).features()).containsExactly(Feature.CACHE_CONTROL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{\"type\":\"text\",\"text\":\"t\",\"cache_control\":{\"type\":\"ephemeral\"}}]",
        "[{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://x.example/a.png\"},\"cache_control\":{\"type\":\"ephemeral\"}}]"
    })
    void cacheControlOnAContentBlockIsFound(String content) {
        assertThat(messages(messagesBody("", oneUserMessage(content))).features()).contains(Feature.CACHE_CONTROL);
    }

    @Test
    void cacheControlOnAToolUseAToolResultAndInsideItIsFound() {
        String toolUse = "[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"n\",\"input\":{},"
            + "\"cache_control\":{\"type\":\"ephemeral\"}}]}]";
        String toolResult = "[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"x\","
            + "\"cache_control\":{\"type\":\"ephemeral\"}}]}]";
        String inside = "[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":"
            + "[{\"type\":\"text\",\"text\":\"x\",\"cache_control\":{\"type\":\"ephemeral\"}}]}]}]";

        assertThat(messages(messagesBody("", toolUse)).features()).containsExactly(Feature.CACHE_CONTROL);
        assertThat(messages(messagesBody("", toolResult)).features()).containsExactly(Feature.CACHE_CONTROL);
        assertThat(messages(messagesBody("", inside)).features()).containsExactly(Feature.CACHE_CONTROL);
    }

    @Test
    void aCacheControlKeyInsideToolDataIsNotAFeature() {
        Detected detected = messages(messagesBody("""
            "tools":[{"name":"t","description":"d","input_schema":{"type":"object",
              "properties":{"cache_control":{"type":"string"},"image":{"type":"string"}}}}]
            """, """
            [{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"t",
               "input":{"cache_control":{"type":"ephemeral"},"type":"image","is_error":true}}]},
             {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"{\\"cache_control\\":1}"}]},
             {"role":"user","content":"my cache_control is on and my image is attached as thinking"}]
            """));

        assertThat(detected.features()).isEmpty();
    }

    // ---- content the native path drops ----

    @Test
    void imagesAndDocumentsAreFoundInMessagesAndInsideToolResults() {
        String image = "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}";
        String document = "{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"JVBERi0=\"}}";

        assertThat(messages(messagesBody("", oneUserMessage("[" + image + "]"))).features()).containsExactly(Feature.IMAGE);
        assertThat(messages(messagesBody("", oneUserMessage("[" + document + "]"))).features()).containsExactly(Feature.DOCUMENT);
        assertThat(messages(messagesBody("", oneUserMessage(
            "[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[" + image + "," + document + "]}]"
        ))).features()).containsExactlyInAnyOrder(Feature.IMAGE, Feature.DOCUMENT);
    }

    @Test
    void aToolResultErrorIsFoundOnlyWhenItIsOne() {
        String result = "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"boom\",\"is_error\":%s}";

        assertThat(messages(messagesBody("", oneUserMessage("[" + result.formatted("true") + "]"))).features())
            .containsExactly(Feature.TOOL_RESULT_ERROR);
        assertThat(messages(messagesBody("", oneUserMessage("[" + result.formatted("false") + "]"))).features()).isEmpty();
        assertThat(messages(messagesBody("", oneUserMessage("[" + result.formatted("null") + "]"))).features()).isEmpty();
    }

    @Test
    void thinkingBlocksAndServerToolBlocksOfEarlierTurnsAreFound() {
        Detected thinking = messages(messagesBody("", """
            [{"role":"user","content":"q"},
             {"role":"assistant","content":[{"type":"redacted_thinking","data":"x"},{"type":"text","text":"a"}]}]
            """));
        Detected server = messages(messagesBody("", """
            [{"role":"user","content":"q"},
             {"role":"assistant","content":[{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"x"}},
               {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[]},{"type":"text","text":"a"}]}]
            """));

        assertThat(thinking.features()).containsExactly(Feature.HISTORY_THINKING);
        assertThat(server.features()).containsExactly(Feature.SERVER_TOOL_HISTORY);
    }

    // ---- reasoning ----

    @Test
    void anAdaptiveThinkingWithAnEffortIsTheThinkingFeatureAndNothingElse() {
        Detected detected = messages(messagesBody("\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"high\"}",
            oneUserMessage("\"q\"")));

        assertThat(detected.features()).containsExactly(Feature.THINKING);
        assertThat(detected.reasoningUnmapped()).isFalse();
    }

    @Test
    void anEffortNoRelaySettingHasANameForIsUnmapped() {
        Detected detected = messages(messagesBody("\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"turbo\"}",
            oneUserMessage("\"q\"")));

        assertThat(detected.features()).containsExactly(Feature.THINKING);
        assertThat(detected.reasoningUnmapped()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2000}",
        "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":31999}",
        "\"thinking\":{\"type\":\"enabled\"}"
    })
    void aThinkingBudgetIsTheThinkingFeature(String thinking) {
        assertThat(messages(messagesBody(thinking, oneUserMessage("\"q\""))).features()).containsExactly(Feature.THINKING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"thinking\":{\"type\":\"disabled\"}",
        "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":0}",
        "\"thinking\":{}"
    })
    void noThinkingIsNoFeature(String thinking) {
        assertThat(messages(messagesBody(thinking, oneUserMessage("\"q\""))).features()).isEmpty();
    }

    @Test
    void anEffortThatSetsNothingIsLeftOutAndRecorded() {
        // Without thinking, and beside a budget, the sanitizer does not read output_config.effort.
        assertThat(messages(messagesBody("\"output_config\":{\"effort\":\"low\"}", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.OUTPUT_CONFIG);
        assertThat(messages(messagesBody(
            "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":5000},\"output_config\":{\"effort\":\"low\"}",
            oneUserMessage("\"q\"")
        )).features()).containsExactlyInAnyOrder(Feature.THINKING, Feature.OUTPUT_CONFIG);
    }

    @Test
    void aStructuredOutputRequestOfMessagesIsFound() {
        String schema = "{\"type\":\"json_schema\",\"schema\":{\"type\":\"object\"}}";

        assertThat(messages(messagesBody("\"output_config\":{\"format\":" + schema + "}", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.JSON_SCHEMA);
        assertThat(messages(messagesBody("\"output_format\":" + schema, oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.JSON_SCHEMA);
    }

    // ---- tool continuation, Messages ----

    @Test
    void toolContinuationFollowsTheConversionsOwnReading() {
        String user = "{\"role\":\"user\",\"content\":\"text\"}";
        String assistant = "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}";
        String results = "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"x\"}]}";
        String resultsWithText = "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"x\"},"
            + "{\"type\":\"text\",\"text\":\"and\"}]}";

        assertThat(continuation(user)).isFalse();
        assertThat(continuation(results)).isTrue();
        assertThat(continuation(resultsWithText)).isTrue();
        assertThat(continuation(results, assistant, user)).isFalse();
        // Two user messages in a row: the conversion makes tool messages of the first and a user message of the second.
        assertThat(continuation(results, user)).isTrue();
        assertThat(continuation(assistant)).isFalse();
        assertThat(messages(messagesBody("", "[]")).toolContinuation()).isFalse();
    }

    private static boolean continuation(String... messages) {
        return messages(messagesBody("", "[" + String.join(",", messages) + "]")).toolContinuation();
    }

    // ---------------------------------------------------------------------------------------- Chat

    @Test
    void aPlainChatRequestHasNoFeature() {
        Detected detected = chat(chatBody("\"temperature\":0.3,\"top_p\":1,\"max_tokens\":100,\"seed\":7,\"stream\":true,"
            + "\"frequency_penalty\":0.1,\"presence_penalty\":0.1,\"parallel_tool_calls\":false,\"stop\":[\"a\"],"
            + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\",\"parameters\":{\"type\":\"object\"}}}],"
            + "\"tool_choice\":\"auto\",\"response_format\":{\"type\":\"json_object\"},\"fast\":true",
            "[{\"role\":\"developer\",\"content\":\"rules\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"r\"}]"));

        // The fast flag is not read from the body: the facade decides what is billed as fast, and the policy takes it from the member.
        assertThat(detected.features()).isEmpty();
        assertThat(detected.entry()).isEqualTo(Entry.CHAT);
        assertThat(detected.toolContinuation()).isTrue();
    }

    @Test
    void chatStopSequencesCountTheSameAsTheSanitizerSendsThem() {
        assertThat(chat(chatBody("\"stop\":\"a\"", oneUserMessage("\"q\""))).features()).isEmpty();
        assertThat(chat(chatBody("\"stop\":[\"a\",\"b\",\"c\",\"d\"]", oneUserMessage("\"q\""))).features()).isEmpty();
        assertThat(chat(chatBody("\"stop\":[\"a\",\"b\",\"c\",\"d\",\"e\"]", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.STOP_SEQUENCES_OVER_4);
        assertThat(chat(chatBody("\"stop\":[\"a\",\"b\",\"c\",\"d\",\"\"]", oneUserMessage("\"q\""))).features()).isEmpty();
        // The sanitizer falls back to stop_sequences when stop is absent, and so does the detector.
        assertThat(chat(chatBody("\"stop_sequences\":[\"a\",\"b\",\"c\",\"d\",\"e\"]", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.STOP_SEQUENCES_OVER_4);
    }

    @Test
    void moreThanOneChoiceIsFoundAndOneIsNot() {
        assertThat(chat(chatBody("\"n\":1", oneUserMessage("\"q\""))).features()).isEmpty();
        assertThat(chat(chatBody("\"n\":2", oneUserMessage("\"q\""))).features()).containsExactly(Feature.N_OVER_ONE);
    }

    @Test
    void whatTheRelayCannotDoIsFound() {
        assertThat(chat(chatBody("\"web_search_options\":{}", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.WEB_SEARCH_OPTIONS);
        assertThat(chat(chatBody("\"functions\":[{\"name\":\"f\"}],\"function_call\":\"auto\"", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.LEGACY_FUNCTIONS);
        assertThat(chat(chatBody("", "[{\"role\":\"user\",\"content\":\"q\"},{\"role\":\"function\",\"name\":\"f\",\"content\":\"r\"}]"))
            .features()).containsExactly(Feature.LEGACY_FUNCTIONS);
        assertThat(chat(chatBody("", "[{\"role\":\"assistant\",\"content\":null,\"function_call\":{\"name\":\"f\",\"arguments\":\"{}\"}}]"))
            .features()).containsExactly(Feature.LEGACY_FUNCTIONS);
        assertThat(chat(chatBody("\"tools\":[{\"type\":\"custom\",\"custom\":{\"name\":\"c\"}}]", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.NON_FUNCTION_TOOL);
        // A tool without a type is a function, as the sanitizer reads it.
        assertThat(chat(chatBody("\"tools\":[{\"function\":{\"name\":\"f\"}}]", oneUserMessage("\"q\""))).features()).isEmpty();
    }

    @Test
    void aToolChoiceOfAKindTheRelayHasNoWordForIsFoundAndTheOthersAreNot() {
        String tools = "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"bash\"}},{\"type\":\"function\",\"function\":{\"name\":\"read\"}}],";
        String allowed = "{\"type\":\"allowed_tools\",\"allowed_tools\":{\"mode\":\"required\",\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"read\"}}]}}";

        assertThat(chat(chatBody(tools + "\"tool_choice\":" + allowed, oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.TOOL_CHOICE);
        assertThat(chat(chatBody(tools + "\"tool_choice\":{\"type\":\"custom\",\"custom\":{\"name\":\"c\"}}", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.TOOL_CHOICE);
        assertThat(chat(chatBody(tools + "\"tool_choice\":\"any\"", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.TOOL_CHOICE);
        assertThat(chat(chatBody(tools + "\"tool_choice\":7", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.TOOL_CHOICE);
        for (String carried : List.of("\"auto\"", "\"none\"", "\"required\"", "{\"type\":\"function\",\"function\":{\"name\":\"read\"}}", "null")) {
            assertThat(chat(chatBody(tools + "\"tool_choice\":" + carried, oneUserMessage("\"q\""))).features()).as(carried).isEmpty();
        }
        // The sanitizer reads a choice only of a body that has tools, as relays refuse one without.
        assertThat(chat(chatBody("\"tool_choice\":" + allowed, oneUserMessage("\"q\""))).features()).isEmpty();
        assertThat(chat(chatBody("\"tools\":[],\"tool_choice\":" + allowed, oneUserMessage("\"q\""))).features()).isEmpty();
    }

    @Test
    void partsOfTheKindsTheRelayMayNotTakeAreFound() {
        assertThat(chat(chatBody("", oneUserMessage("[{\"type\":\"text\",\"text\":\"t\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,iVBORw0KGgo=\"}}]"))).features())
            .containsExactly(Feature.IMAGE);
        assertThat(chat(chatBody("", oneUserMessage("[{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AAAA\",\"format\":\"wav\"}}]")))
            .features()).containsExactly(Feature.AUDIO);
        assertThat(chat(chatBody("", oneUserMessage("[{\"type\":\"file\",\"file\":{\"file_id\":\"file-1\"}}]"))).features())
            .containsExactly(Feature.FILE);
        assertThat(chat(chatBody("", "[{\"role\":\"assistant\",\"content\":null,\"audio\":{\"id\":\"audio_1\"}}]")).features())
            .containsExactly(Feature.AUDIO);
        // The single-object form of content is read as the one part it is.
        assertThat(chat(chatBody("", oneUserMessage("{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x.example/a.png\"}}")))
            .features()).containsExactly(Feature.IMAGE);
    }

    @Test
    void theTextPartsOfTheResponsesApiAreTextAndSoNothingIsFoundOfThemExceptTheirCacheControl() {
        assertThat(chat(chatBody("", oneUserMessage("[{\"type\":\"input_text\",\"text\":\"Explain X\"}]"))).features()).isEmpty();
        assertThat(chat(chatBody("", "[{\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"X is\"}]}]")).features())
            .isEmpty();
        assertThat(chat(chatBody("", oneUserMessage("[{\"type\":\"input_text\",\"text\":\"t\",\"cache_control\":{\"type\":\"ephemeral\"}}]")))
            .features()).containsExactly(Feature.CACHE_CONTROL);
        assertThat(chat(chatBody("", oneUserMessage("[{\"type\":\"output_text\",\"text\":\"t\",\"cache_control\":{\"type\":\"ephemeral\"}}]")))
            .features()).containsExactly(Feature.CACHE_CONTROL);
    }

    @Test
    void textThatMentionsTheKindsIsNotAPart() {
        assertThat(chat(chatBody("", oneUserMessage("\"an image_url, a file and input_audio, web_search_options\""))).features())
            .isEmpty();
        assertThat(chat(chatBody("", oneUserMessage("[{\"text\":\"t\"},{\"type\":\"text\",\"text\":\"image\"},\"plain\"]"))).features())
            .isEmpty();
    }

    @Test
    void aResponseSchemaIsFoundAndAJsonObjectIsNot() {
        assertThat(chat(chatBody("\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"s\",\"schema\":{}}}",
            oneUserMessage("\"q\""))).features()).containsExactly(Feature.JSON_SCHEMA);
        assertThat(chat(chatBody("\"response_format\":{\"type\":\"json_object\"}", oneUserMessage("\"q\""))).features()).isEmpty();
        assertThat(chat(chatBody("\"response_format\":{\"type\":\"text\"}", oneUserMessage("\"q\""))).features()).isEmpty();
    }

    @Test
    void cacheControlOnAChatMessageOrPartIsFound() {
        assertThat(chat(chatBody("", "[{\"role\":\"system\",\"content\":\"s\",\"cache_control\":{\"type\":\"ephemeral\"}}]")).features())
            .containsExactly(Feature.CACHE_CONTROL);
        assertThat(chat(chatBody("", oneUserMessage("[{\"type\":\"text\",\"text\":\"t\",\"cache_control\":{\"type\":\"ephemeral\"}}]")))
            .features()).containsExactly(Feature.CACHE_CONTROL);
        assertThat(chat(chatBody("", oneUserMessage("[{\"type\":\"text\",\"text\":\"t\",\"cache_control\":null}]"))).features())
            .isEmpty();
    }

    @Test
    void reasoningOfAChatRequestIsFoundAsTheSanitizerSeesIt() {
        Detected effort = chat(chatBody("\"reasoning_effort\":\"high\"", oneUserMessage("\"q\"")));
        Detected thinking = chat(chatBody("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":8000}", oneUserMessage("\"q\"")));

        assertThat(effort.features()).containsExactly(Feature.REASONING_EFFORT);
        assertThat(thinking.features()).containsExactly(Feature.THINKING);
        assertThat(chat(chatBody("\"reasoning_effort\":\" \"", oneUserMessage("\"q\""))).features()).isEmpty();
        assertThat(chat(chatBody("\"thinking\":{\"type\":\"disabled\"}", oneUserMessage("\"q\""))).features()).isEmpty();
    }

    @Test
    void theFieldsThatAreOnlyLeftOutAreSoftFeatures() {
        Detected detected = chat(chatBody("""
            "logprobs":true,"top_logprobs":3,"user":"u-1","metadata":{"k":"v"},"store":true,"prediction":{"type":"content","content":"x"},
            "modalities":["text","audio"],"top_k":20,"service_tier":"flex"
            """, oneUserMessage("\"q\"")));

        assertThat(detected.features()).containsExactlyInAnyOrder(
            Feature.LOGPROBS, Feature.USER, Feature.METADATA, Feature.STORE, Feature.PREDICTION, Feature.MODALITIES,
            Feature.TOP_K, Feature.SERVICE_TIER
        );
    }

    /** The Chat fields a native account of the OpenAI kind is handed and the relay's body does not have are named, not lost without a word. */
    @Test
    void theFieldsOfTheChatApiThatTheSanitizerDoesNotSendAreFound() {
        Detected detected = chat(chatBody("\"verbosity\":\"low\",\"logit_bias\":{\"50256\":-100},\"prompt_cache_key\":\"sess-1\","
            + "\"prompt_cache_retention\":\"24h\",\"safety_identifier\":\"u-hash\"", oneUserMessage("\"q\"")));

        assertThat(detected.features()).containsExactlyInAnyOrder(
            Feature.VERBOSITY, Feature.LOGIT_BIAS, Feature.PROMPT_CACHE_KEY, Feature.PROMPT_CACHE_RETENTION, Feature.SAFETY_IDENTIFIER
        );
        assertThat(chat(chatBody("\"verbosity\":\"high\"", oneUserMessage("\"q\""))).features()).containsExactly(Feature.VERBOSITY);
        assertThat(chat(chatBody("\"logit_bias\":{\"1\":5}", oneUserMessage("\"q\""))).features()).containsExactly(Feature.LOGIT_BIAS);
    }

    /**
     * {@code medium} is what the model does when it is not asked: a client that states it (a coding agent's
     * configuration sends it on every request) asks for no other answer, and a route that is handed the body whole would
     * have had no different one.
     */
    @Test
    void aVerbosityOfMediumIsTheDefaultAndNoFeature() {
        for (String medium : List.of("medium", "Medium", " MEDIUM ")) {
            assertThat(chat(chatBody("\"verbosity\":\"" + medium + "\"", oneUserMessage("\"q\""))).features()).as(medium).isEmpty();
        }
        for (String other : List.of("low", "high", "verbose", "0")) {
            assertThat(chat(chatBody("\"verbosity\":\"" + other + "\"", oneUserMessage("\"q\""))).features()).as(other)
                .containsExactly(Feature.VERBOSITY);
        }
        // Not a string at all: a value this class does not know is not the default.
        assertThat(chat(chatBody("\"verbosity\":2", oneUserMessage("\"q\""))).features()).containsExactly(Feature.VERBOSITY);
    }

    @Test
    void thoseFieldsAtTheirQuietValuesAreNothing() {
        Detected detected = chat(chatBody("\"verbosity\":\" \",\"logit_bias\":{},\"prompt_cache_key\":\"\",\"prompt_cache_retention\":null,"
            + "\"safety_identifier\":\" \"", oneUserMessage("\"q\"")));

        assertThat(detected.features()).isEmpty();
    }

    @Test
    void theSoftFieldsAtTheirQuietValuesAreNothing() {
        Detected detected = chat(chatBody("""
            "logprobs":false,"user":" ","metadata":{},"store":false,"modalities":["text"],"service_tier":"priority"
            """, oneUserMessage("\"q\"")));
        Detected fast = chat(chatBody("\"service_tier\":\"fast\"", oneUserMessage("\"q\"")));

        assertThat(detected.features()).isEmpty();
        assertThat(fast.features()).isEmpty();
    }

    @Test
    void anAudioOutputParameterIsAModalityRequest() {
        assertThat(chat(chatBody("\"audio\":{\"voice\":\"alloy\",\"format\":\"wav\"}", oneUserMessage("\"q\""))).features())
            .containsExactly(Feature.MODALITIES);
    }

    @Test
    void toolContinuationOfChatIsTheSanitizersReading() {
        assertThat(chatContinuation("{\"role\":\"user\",\"content\":\"q\"}")).isFalse();
        assertThat(chatContinuation("{\"role\":\"assistant\",\"content\":\"a\"}")).isFalse();
        assertThat(chatContinuation("{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}")).isTrue();
        assertThat(chatContinuation("{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}",
            "{\"role\":\"user\",\"content\":\"and this\"}")).isTrue();
        assertThat(chatContinuation("{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":\"r\"}",
            "{\"role\":\"assistant\",\"content\":\"a\"}", "{\"role\":\"user\",\"content\":\"q\"}")).isFalse();
    }

    private static boolean chatContinuation(String... messages) {
        return chat(chatBody("", "[" + String.join(",", messages) + "]")).toolContinuation();
    }

    // ---------------------------------------------------------------------------------------- Responses

    private static String responsesBody(String extra, String input) {
        return "{\"model\":\"claude-sonnet-4.5\"" + (extra.isEmpty() ? "" : "," + extra) + ",\"input\":" + input + "}";
    }

    private static final String FUNCTION_F = "{\"type\":\"function\",\"name\":\"f\",\"parameters\":{\"type\":\"object\"}}";
    private static final String USER_TEXT = "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"q\"}]}";

    @Test
    void theCodexRequestForAClaudeModelHasTheFeaturesItAlwaysHasAndNoHardOne() {
        Detected detected = responses(CodexRequests.CLAUDE_TURN);

        // The web search and the namespace are in every request of the client; the empty include and the store flag set to
        // false say nothing; the client's own metadata is not read.
        assertThat(detected.features()).containsExactlyInAnyOrder(Feature.BUILTIN_TOOL, Feature.PROMPT_CACHE_KEY);
        assertThat(detected.entry()).isEqualTo(Entry.RESPONSES);
        assertThat(detected.toolContinuation()).isFalse();
        assertThat(detected.reasoningUnmapped()).isFalse();
    }

    @Test
    void theCodexRequestForAGptModelAddsItsReasoningTheEncryptedReasoningAndTheVerbosity() {
        Detected detected = responses(CodexRequests.GPT_TURN);

        assertThat(detected.features()).containsExactlyInAnyOrder(
            Feature.BUILTIN_TOOL, Feature.REASONING_EFFORT, Feature.INCLUDE, Feature.VERBOSITY, Feature.PROMPT_CACHE_KEY
        );
    }

    @Test
    void aPlainTextRequestOfTheResponsesApiHasNoFeature() {
        assertThat(responses(responsesBody("\"instructions\":\"Be brief\",\"temperature\":0.2,\"top_p\":0.9,\"max_output_tokens\":100,\"stream\":true",
            "\"hello\"")).features()).isEmpty();
        assertThat(responses(responsesBody("", "[" + USER_TEXT + "]")).features()).isEmpty();
        assertThat(responses("{}").features()).isEmpty();
    }

    @Test
    void functionsTheEditingToolAndTheirCallsAndAnswersAreCarried() {
        Detected detected = responses(responsesBody("\"tools\":[" + FUNCTION_F + ",{\"type\":\"custom\",\"name\":\"apply_patch\"},"
            + "{\"type\":\"function\",\"name\":\"apply_patch\",\"parameters\":{}},{\"name\":\"untyped\"}],"
            + "\"tool_choice\":{\"type\":\"function\",\"name\":\"f\"},\"parallel_tool_calls\":true", "["
            + USER_TEXT + ",{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":\"{}\"},"
            + "{\"type\":\"function_call_output\",\"call_id\":\"c1\",\"output\":\"ok\"},"
            + "{\"type\":\"custom_tool_call\",\"call_id\":\"c2\",\"name\":\"apply_patch\",\"input\":\"*** Begin Patch\"},"
            + "{\"type\":\"custom_tool_call_output\",\"call_id\":\"c2\",\"output\":\"done\"},"
            + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}]"));

        assertThat(detected.features()).isEmpty();
    }

    // ---- the tools ----

    @Test
    void aCustomToolOtherThanTheEditingToolIsAFeatureNoRelayCarries() {
        assertThat(responses(responsesBody("\"tools\":[" + FUNCTION_F + ",{\"type\":\"custom\",\"name\":\"shell\",\"format\":{\"type\":\"text\"}}]",
            "[" + USER_TEXT + "]")).features()).containsExactly(Feature.CUSTOM_TOOL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "web_search", "web_search_preview", "web_search_2025_08_26", "file_search", "code_interpreter", "image_generation",
        "computer_use_preview", "computer", "mcp", "local_shell", "tool_search", "namespace", "a_tool_of_a_later_api"
    })
    void aToolThatIsNeitherAFunctionNorACustomToolIsABuiltInTool(String type) {
        assertThat(responses(responsesBody("\"tools\":[" + FUNCTION_F + ",{\"type\":\"" + type + "\"}]", "[" + USER_TEXT + "]")).features())
            .containsExactly(Feature.BUILTIN_TOOL);
    }

    @Test
    void aFunctionWithoutANameIsNothingAsTheConversionLeavesItOut() {
        assertThat(responses(responsesBody("\"tools\":[{\"type\":\"function\",\"parameters\":{}},{\"type\":\"function\",\"name\":\" \"},"
            + "null,\"x\",{\"type\":5}]", "[" + USER_TEXT + "]")).features()).isEmpty();
    }

    @Test
    void aToolChoiceIsAFeatureWhenTheRelayHasNoWordForItOrTheToolIsNotCarried() {
        String tools = "\"tools\":[" + FUNCTION_F + ",{\"type\":\"custom\",\"name\":\"apply_patch\"},{\"type\":\"web_search\"}],";
        for (String carried : List.of("\"auto\"", "\"none\"", "\"required\"", "{\"type\":\"function\",\"name\":\"f\"}",
            "{\"type\":\"function\",\"function\":{\"name\":\"f\"}}", "{\"type\":\"custom\",\"name\":\"apply_patch\"}",
            "{\"type\":\"function\",\"name\":\"apply_patch\"}")) {
            assertThat(responses(responsesBody(tools + "\"tool_choice\":" + carried, "[" + USER_TEXT + "]")).features())
                .as(carried).containsExactly(Feature.BUILTIN_TOOL);
        }
        for (String left : List.of("{\"type\":\"allowed_tools\",\"mode\":\"auto\",\"tools\":[{\"type\":\"function\",\"name\":\"f\"}]}",
            "{\"type\":\"web_search\"}", "{\"type\":\"function\",\"name\":\"not_declared\"}", "{\"type\":\"custom\",\"name\":\"shell\"}",
            "{\"type\":\"function\"}", "\"web_search\"", "{}")) {
            assertThat(responses(responsesBody(tools + "\"tool_choice\":" + left, "[" + USER_TEXT + "]")).features())
                .as(left).containsExactlyInAnyOrder(Feature.BUILTIN_TOOL, Feature.TOOL_CHOICE);
        }
    }

    @Test
    void aToolChoiceOfARequestWithoutAFunctionIsNotReadAsTheConversionLeavesItOut() {
        assertThat(responses(responsesBody("\"tools\":[{\"type\":\"web_search\"}],\"tool_choice\":{\"type\":\"allowed_tools\",\"tools\":[]}",
            "[" + USER_TEXT + "]")).features()).containsExactly(Feature.BUILTIN_TOOL);
        assertThat(responses(responsesBody("\"tool_choice\":{\"type\":\"allowed_tools\",\"tools\":[]}", "[" + USER_TEXT + "]")).features())
            .isEmpty();
    }

    // ---- the chain ----

    @Test
    void aPreviousResponseIdThatIsStillInTheExpandedRequestIsAChainThisNodeCouldNotExpand() {
        assertThat(responses(responsesBody("\"previous_response_id\":\"resp_0123\"", "[" + USER_TEXT + "]")).features())
            .containsExactly(Feature.PREVIOUS_RESPONSE_ID);
        for (String quiet : List.of("null", "\"\"", "\"  \"")) {
            assertThat(responses(responsesBody("\"previous_response_id\":" + quiet, "[" + USER_TEXT + "]")).features())
                .as(quiet).isEmpty();
        }
    }

    // ---- the input ----

    @Test
    void theImagesAndFilesOfTheMessagesOfTheUserAreFoundWhereTheConversionCarriesThem() {
        String parts = "[{\"type\":\"input_text\",\"text\":\"look\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,iVBORw0KGgo=\"},"
            + "{\"type\":\"input_file\",\"file_id\":\"file-1\"},{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AAAA\",\"format\":\"wav\"}}]";

        Detected user = responses(responsesBody("", "[{\"type\":\"message\",\"role\":\"user\",\"content\":" + parts + "}]"));
        assertThat(user.features()).containsExactlyInAnyOrder(Feature.IMAGE, Feature.FILE, Feature.AUDIO);
        assertThat(user.imageNotCarried()).isFalse();
        // An item without a type but with a role is a message, as the conversion has it.
        Detected untyped = responses(responsesBody("", "[{\"role\":\"user\",\"content\":" + parts + "}]"));
        assertThat(untyped.features()).containsExactlyInAnyOrder(Feature.IMAGE, Feature.FILE, Feature.AUDIO);
        assertThat(untyped.imageNotCarried()).isFalse();
    }

    /**
     * What the assistant, the developer or the system wrote is its text, whatever else the item holds: the conversion keeps
     * nothing of the parts of such a message, so a relay would answer without them. An image of them is found, and said not
     * to be one that any member can carry.
     */
    @Test
    void thePartsOfAMessageThatIsNotTheUsersAreLostAndSaySo() {
        String parts = "[{\"type\":\"input_text\",\"text\":\"look\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,iVBORw0KGgo=\"},"
            + "{\"type\":\"input_file\",\"file_id\":\"file-1\"},{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AAAA\",\"format\":\"wav\"}}]";

        for (String role : List.of("assistant", "developer", "system")) {
            Detected detected = responses(responsesBody("", "[{\"type\":\"message\",\"role\":\"" + role + "\",\"content\":" + parts + "}]"));
            assertThat(detected.features()).as(role).containsExactlyInAnyOrder(Feature.IMAGE, Feature.FILE, Feature.AUDIO);
            assertThat(detected.imageNotCarried()).as(role).isTrue();
        }
        // Text is all they hold otherwise.
        assertThat(responses(responsesBody("", "[{\"type\":\"message\",\"role\":\"developer\",\"content\":[{\"type\":\"input_text\",\"text\":\"rules\"}]}]"))
            .features()).isEmpty();
    }

    /**
     * An image that is only a file of OpenAI (the conversion writes an {@code image_url} without a URL, which the sanitizer
     * replaces with a note) is not an image any member carries, whatever its setting for vision. The images that name a URL
     * are, and the fields of the part decide it as the conversion reads them.
     */
    @Test
    void anImageThatIsOnlyAFileIsFoundAsOneNoMemberCarries() {
        for (String image : List.of("{\"type\":\"input_image\",\"file_id\":\"file-abc123\"}",
            "{\"type\":\"input_image\",\"file_id\":\"file-abc123\",\"detail\":\"auto\"}",
            "{\"type\":\"input_image\",\"image_url\":\"\",\"file_id\":\"file-abc123\"}",
            "{\"type\":\"input_image\",\"image_url\":\"  \"}", "{\"type\":\"input_image\",\"image_url\":{}}", "{\"type\":\"input_image\"}")) {
            Detected detected = responses(responsesBody("", "[{\"type\":\"message\",\"role\":\"user\",\"content\":"
                + "[{\"type\":\"input_text\",\"text\":\"what is this?\"}," + image + "]}]"));
            assertThat(detected.features()).as(image).containsExactly(Feature.IMAGE);
            assertThat(detected.imageNotCarried()).as(image).isTrue();
        }
        for (String image : List.of("{\"type\":\"input_image\",\"image_url\":\"https://x.example/a.png\"}",
            "{\"type\":\"input_image\",\"image_url\":{\"url\":\"https://x.example/a.png\",\"detail\":\"low\"}}",
            "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,iVBORw0KGgo=\",\"file_id\":\"file-abc123\"}")) {
            Detected detected = responses(responsesBody("", "[{\"type\":\"message\",\"role\":\"user\",\"content\":[" + image + "]}]"));
            assertThat(detected.features()).as(image).containsExactly(Feature.IMAGE);
            assertThat(detected.imageNotCarried()).as(image).isFalse();
        }
        // One image that cannot be carried is enough: the request as a whole loses an image.
        assertThat(responses(responsesBody("", "[{\"type\":\"message\",\"role\":\"user\",\"content\":["
            + "{\"type\":\"input_image\",\"image_url\":\"https://x.example/a.png\"},{\"type\":\"input_image\",\"file_id\":\"file-1\"}]}]"))
            .imageNotCarried()).isTrue();
    }

    @Test
    void anImageInTheOutputOfAToolIsAFeatureOfItsOwn() {
        String output = "[{\"type\":\"input_text\",\"text\":\"saved\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,iVBORw0KGgo=\"},"
            + "{\"type\":\"input_file\",\"file_id\":\"file-1\"}]";
        String call = "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"view_image\",\"arguments\":\"{}\"}";

        assertThat(responses(responsesBody("", "[" + USER_TEXT + "," + call + ",{\"type\":\"function_call_output\",\"call_id\":\"c1\",\"output\":"
            + output + "}]")).features()).containsExactlyInAnyOrder(Feature.TOOL_OUTPUT_IMAGE, Feature.FILE);
        assertThat(responses(responsesBody("", "[" + USER_TEXT + "," + call + ",{\"type\":\"custom_tool_call_output\",\"call_id\":\"c1\",\"output\":"
            + output + "}]")).features()).containsExactlyInAnyOrder(Feature.TOOL_OUTPUT_IMAGE, Feature.FILE);
        // A text output, as a string or a list, is carried.
        assertThat(responses(responsesBody("", "[" + USER_TEXT + "," + call + ",{\"type\":\"function_call_output\",\"call_id\":\"c1\","
            + "\"output\":[{\"type\":\"input_text\",\"text\":\"saved\"}]}]")).features()).isEmpty();
    }

    @Test
    void theReasoningOfEarlierTurnsIsFoundAndWhatTheConversionLeavesOutWithoutAWordIsNot() {
        assertThat(responses(responsesBody("", "[" + USER_TEXT + ",{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],"
            + "\"encrypted_content\":\"gAAAA\"}]")).features()).containsExactly(Feature.HISTORY_THINKING);
        assertThat(responses(responsesBody("", "[" + USER_TEXT + ","
            + "{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\"},{\"type\":\"a_later_item\"},null,5]")).features())
            .isEmpty();
    }

    /**
     * Context the server holds and the request only points at is lost by the conversion, and a native OpenAI account would
     * have resolved it: a stored prompt, a conversation, an item of an earlier response, the compaction of a history.
     */
    @Test
    void contextTheServerHoldsIsFoundWhereverTheRequestPointsAtIt() {
        assertThat(responses(responsesBody("\"conversation\":\"conv_abc123\"", "[" + USER_TEXT + "]")).features())
            .containsExactly(Feature.SERVER_HELD_CONTEXT);
        assertThat(responses(responsesBody("\"conversation\":{\"id\":\"conv_abc123\"}", "[" + USER_TEXT + "]")).features())
            .containsExactly(Feature.SERVER_HELD_CONTEXT);
        assertThat(responses(responsesBody("\"prompt\":{\"id\":\"pmpt_abc\",\"variables\":{\"city\":\"Paris\"}}", "[" + USER_TEXT + "]")).features())
            .containsExactly(Feature.SERVER_HELD_CONTEXT);
        assertThat(responses(responsesBody("", "[{\"type\":\"item_reference\",\"id\":\"msg_stored_earlier\"}," + USER_TEXT + "]")).features())
            .containsExactly(Feature.SERVER_HELD_CONTEXT);
        assertThat(responses(responsesBody("", "[{\"type\":\"compaction\",\"encrypted_content\":\"gAAAA\"}," + USER_TEXT + "]")).features())
            .containsExactly(Feature.SERVER_HELD_CONTEXT);
        assertThat(responses(responsesBody("", "[{\"type\":\"compaction_summary\",\"encrypted_content\":\"gAAAA\"}," + USER_TEXT + "]")).features())
            .containsExactly(Feature.SERVER_HELD_CONTEXT);
        // Nothing filled in is nothing.
        for (String quiet : List.of("\"conversation\":null", "\"conversation\":\"\"", "\"conversation\":{}", "\"conversation\":{\"id\":\" \"}",
            "\"prompt\":null", "\"prompt\":{}", "\"prompt\":\"\"")) {
            assertThat(responses(responsesBody(quiet, "[" + USER_TEXT + "]")).features()).as(quiet).isEmpty();
        }
    }

    @Test
    void theCapOnToolCallsAndTheSummaryOfTheReasoningAreFoundAsFieldsThatAreOnlyLeftOut() {
        assertThat(responses(responsesBody("\"max_tool_calls\":2", "[" + USER_TEXT + "]")).features()).containsExactly(Feature.MAX_TOOL_CALLS);
        assertThat(responses(responsesBody("\"max_tool_calls\":null", "[" + USER_TEXT + "]")).features()).isEmpty();
        for (String summary : List.of("auto", "concise", "detailed")) {
            Detected detected = responses(responsesBody("\"reasoning\":{\"summary\":\"" + summary + "\"}", "[" + USER_TEXT + "]"));
            assertThat(detected.features()).as(summary).containsExactly(Feature.REASONING_SUMMARY);
        }
        // A client that is configured to want no summary says none.
        for (String quiet : List.of("null", "\"\"", "\"none\"", "\" None \"")) {
            assertThat(responses(responsesBody("\"reasoning\":{\"summary\":" + quiet + "}", "[" + USER_TEXT + "]")).features())
                .as(quiet).isEmpty();
        }
    }

    // ---- reasoning, format and the fields only left out ----

    @ParameterizedTest
    @CsvSource({"minimal", "low", "medium", "high", "xhigh", "none", "a-level-of-a-later-api"})
    void aReasoningEffortIsTheFeatureTheSanitizerSeesAndIsAlwaysMapped(String effort) {
        Detected detected = responses(responsesBody("\"reasoning\":{\"effort\":\"" + effort + "\"}", "[" + USER_TEXT + "]"));

        assertThat(detected.features()).containsExactly(Feature.REASONING_EFFORT);
        assertThat(detected.reasoningUnmapped()).isFalse();
    }

    @Test
    void noEffortIsNoReasoningRequest() {
        for (String reasoning : List.of("null", "{}", "{\"effort\":null}", "{\"effort\":\"\"}", "{\"effort\":\" \"}", "\"high\"")) {
            assertThat(responses(responsesBody("\"reasoning\":" + reasoning, "[" + USER_TEXT + "]")).features())
                .as(reasoning).isEmpty();
        }
    }

    @Test
    void aResponseSchemaIsFoundAsTheConversionCarriesItAndAJsonObjectIsNot() {
        assertThat(responses(responsesBody("\"text\":{\"format\":{\"type\":\"json_schema\",\"name\":\"s\",\"schema\":{\"type\":\"object\"},\"strict\":true}}",
            "[" + USER_TEXT + "]")).features()).containsExactly(Feature.JSON_SCHEMA);
        for (String format : List.of("{\"type\":\"json_object\"}", "{\"type\":\"text\"}", "{\"type\":\"json_schema\",\"name\":\"s\"}", "\"json_schema\"")) {
            assertThat(responses(responsesBody("\"text\":{\"format\":" + format + "}", "[" + USER_TEXT + "]")).features())
                .as(format).isEmpty();
        }
    }

    @Test
    void aVerbosityIsAdviceAboutTheLengthAndMediumIsTheDefault() {
        for (String level : List.of("low", "high", "a-level-of-a-later-api")) {
            assertThat(responses(responsesBody("\"text\":{\"verbosity\":\"" + level + "\"}", "[" + USER_TEXT + "]")).features())
                .as(level).containsExactly(Feature.VERBOSITY);
        }
        for (String quiet : List.of("\"medium\"", "\" Medium \"", "null", "\"\"")) {
            assertThat(responses(responsesBody("\"text\":{\"verbosity\":" + quiet + "}", "[" + USER_TEXT + "]")).features())
                .as(quiet).isEmpty();
        }
    }

    @Test
    void theFieldsThatAreOnlyLeftOutAreSoftFeaturesOfTheResponsesApi() {
        String body = responsesBody("\"include\":[\"reasoning.encrypted_content\"],\"store\":true,\"truncation\":\"auto\","
            + "\"prompt_cache_key\":\"k\",\"prompt_cache_retention\":\"24h\",\"safety_identifier\":\"u-hash\",\"metadata\":{\"a\":\"b\"},"
            + "\"user\":\"u\",\"top_logprobs\":3,\"service_tier\":\"flex\"", "[" + USER_TEXT + "]");

        assertThat(responses(body).features()).containsExactlyInAnyOrder(Feature.INCLUDE, Feature.STORE, Feature.TRUNCATION,
            Feature.PROMPT_CACHE_KEY, Feature.PROMPT_CACHE_RETENTION, Feature.SAFETY_IDENTIFIER, Feature.METADATA, Feature.USER,
            Feature.LOGPROBS, Feature.SERVICE_TIER);
    }

    @Test
    void theSoftFieldsOfTheResponsesApiAtTheirQuietValuesAreNothing() {
        String body = responsesBody("\"include\":[],\"store\":false,\"truncation\":\"disabled\",\"prompt_cache_key\":\"\","
            + "\"prompt_cache_retention\":null,\"safety_identifier\":\" \",\"metadata\":{},\"user\":\"\",\"top_logprobs\":null,"
            + "\"service_tier\":\"auto\",\"background\":false,\"client_metadata\":{\"session_id\":\"s\"}", "[" + USER_TEXT + "]");

        // service_tier "auto" is a tier and not one of the two that mean fast.
        assertThat(responses(body).features()).containsExactly(Feature.SERVICE_TIER);
        assertThat(responses(body.replace("\"service_tier\":\"auto\"", "\"service_tier\":\"priority\"")).features()).isEmpty();
        assertThat(responses(body.replace("\"service_tier\":\"auto\"", "\"service_tier\":\"fast\"")).features()).isEmpty();
    }

    // ---- tool continuation, Responses ----

    @Test
    void toolContinuationOfResponsesIsTheConversionsReading() {
        String call = "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":\"{}\"}";
        String answer = "{\"type\":\"function_call_output\",\"call_id\":\"c1\",\"output\":\"r\"}";
        String customAnswer = "{\"type\":\"custom_tool_call_output\",\"call_id\":\"c2\",\"output\":\"r\"}";
        String reasoning = "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}";
        String assistant = "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"a\"}";

        assertThat(responsesContinuation(USER_TEXT)).isFalse();
        assertThat(responsesContinuation(USER_TEXT, call, answer)).isTrue();
        assertThat(responsesContinuation(USER_TEXT, call, customAnswer)).isTrue();
        // The items the conversion leaves out, and the user's text after the answer, are the same turn.
        assertThat(responsesContinuation(USER_TEXT, call, answer, reasoning)).isTrue();
        assertThat(responsesContinuation(USER_TEXT, call, answer, USER_TEXT, "{\"type\":\"item_reference\",\"id\":\"m\"}")).isTrue();
        assertThat(responsesContinuation(USER_TEXT, call, answer, assistant)).isFalse();
        assertThat(responsesContinuation(USER_TEXT, call, answer, assistant, USER_TEXT)).isFalse();
        // A call the model made and nobody answered, and what the developer said, are not an answer.
        assertThat(responsesContinuation(USER_TEXT, answer, call)).isFalse();
        assertThat(responsesContinuation(USER_TEXT, answer, "{\"type\":\"message\",\"role\":\"developer\",\"content\":\"d\"}")).isFalse();
        assertThat(responsesContinuation(USER_TEXT, answer, "{\"type\":\"message\",\"role\":\"a-role-of-a-later-api\",\"content\":\"d\"}")).isTrue();
        assertThat(responsesContinuation("{\"role\":\"assistant\",\"content\":\"a\"}")).isFalse();
        assertThat(responses("{\"input\":\"text\"}").toolContinuation()).isFalse();
        assertThat(responses("{}").toolContinuation()).isFalse();
    }

    private static boolean responsesContinuation(String... items) {
        return responses(responsesBody("", "[" + String.join(",", items) + "]")).toolContinuation();
    }

    // ---------------------------------------------------------------------------------------- both

    @Test
    void detectionDoesNotChangeTheBody() {
        JsonNode body = tree(CLAUDE_CODE_TURN.formatted(NEXT_PROMPT));
        JsonNode copy = body.deepCopy();
        RequestFeatureDetector.detect(Entry.MESSAGES, body);
        JsonNode chat = tree(chatBody("\"n\":3,\"reasoning_effort\":\"low\"", oneUserMessage("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"u\"}}]")));
        JsonNode chatCopy = chat.deepCopy();
        RequestFeatureDetector.detect(Entry.CHAT, chat);
        JsonNode codex = tree(CodexRequests.GPT_TURN);
        JsonNode codexCopy = codex.deepCopy();
        RequestFeatureDetector.detect(Entry.RESPONSES, codex);

        assertThat(body).isEqualTo(copy);
        assertThat(chat).isEqualTo(chatCopy);
        assertThat(codex).isEqualTo(codexCopy);
    }

    @Test
    void theResultIsNotChangeable() {
        Detected detected = messages(messagesBody("\"top_k\":5", oneUserMessage("\"q\"")));

        assertThatThrownBy(() -> detected.features().add(Feature.WEB_SEARCH)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Entry.class, names = {"GEMINI"})
    void anEntryWithoutATableIsNeverCleared(Entry entry) {
        Detected detected = RequestFeatureDetector.detect(entry, tree("{\"model\":\"m\"}"));

        assertThat(detected.entry()).isEqualTo(entry);
        assertThat(detected.features()).containsExactly(Feature.UNSUPPORTED_ENTRY);
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> RequestFeatureDetector.detect(Entry.CHAT, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestFeatureDetector.detect(Entry.MESSAGES, tree("[]"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestFeatureDetector.detect(Entry.MESSAGES, tree("\"text\""))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oddShapesAreReadWithoutFailing() {
        // A client can send anything; the detector reads what it knows and leaves the rest.
        assertThat(messages("{\"messages\":[null,1,\"x\",{\"role\":5,\"content\":[null,3,{\"type\":7}]},{\"content\":{\"type\":\"text\"}}],"
            + "\"tools\":[null,\"x\",{\"type\":5}],\"system\":[1,null],\"stop_sequences\":7,\"thinking\":\"on\"}").features()).isEmpty();
        assertThat(chat("{\"messages\":[null,1,\"x\",{\"role\":5,\"content\":[null,3,{\"type\":7}]},{\"content\":7}],"
            + "\"tools\":[null,\"x\"],\"stop\":7,\"n\":\"two\",\"modalities\":\"audio\",\"service_tier\":3}").features()).isEmpty();
        assertThat(messages("{}").features()).isEmpty();
        assertThat(chat("{}").features()).isEmpty();
        assertThat(responses("{\"input\":[null,1,\"x\",{\"type\":7},{\"role\":5,\"content\":[null,3,{\"type\":7}]},{\"type\":\"function_call_output\",\"output\":7},"
            + "{\"type\":\"message\",\"role\":\"user\",\"content\":{\"type\":\"input_image\"}}],\"tools\":[null,\"x\",{\"type\":5},{\"function\":7}],"
            + "\"tool_choice\":7,\"text\":\"plain\",\"reasoning\":7,\"include\":\"x\",\"metadata\":3,\"truncation\":4}").features())
            .containsExactlyInAnyOrder(Feature.INCLUDE, Feature.METADATA, Feature.TRUNCATION);
    }

    @Test
    void everyFeatureHasADistinctSnakeCaseLabelAndTheSharedOnesAreTheSanitizersWords() {
        assertThat(EnumSet.allOf(Feature.class).stream().map(Feature::label)).doesNotHaveDuplicates()
            .allMatch(label -> label.matches("[a-z][a-z_]{0,39}"));
        assertThat(Feature.IMAGE.label()).isEqualTo("image");
        assertThat(Feature.AUDIO.label()).isEqualTo("audio");
        assertThat(Feature.FILE.label()).isEqualTo("file");
        assertThat(Feature.CACHE_CONTROL.label()).isEqualTo("cache_control");
        assertThat(Feature.JSON_SCHEMA.label()).isEqualTo("json_schema");
        assertThat(Feature.THINKING.label()).isEqualTo("thinking");
        assertThat(Feature.REASONING_EFFORT.label()).isEqualTo("reasoning_effort");
        assertThat(Feature.TOOL_CHOICE.label()).isEqualTo("tool_choice");
        assertThat(Feature.VERBOSITY.label()).isEqualTo("verbosity");
        assertThat(Feature.LOGIT_BIAS.label()).isEqualTo("logit_bias");
        assertThat(Feature.PROMPT_CACHE_KEY.label()).isEqualTo("prompt_cache_key");
        assertThat(Feature.PROMPT_CACHE_RETENTION.label()).isEqualTo("prompt_cache_retention");
        assertThat(Feature.SAFETY_IDENTIFIER.label()).isEqualTo("safety_identifier");
        assertThat(Feature.PREVIOUS_RESPONSE_ID.label()).isEqualTo("previous_response_id");
    }
}
