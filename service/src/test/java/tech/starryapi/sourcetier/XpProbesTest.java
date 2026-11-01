package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.core.PlatformCompletionResult.StopReason;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.StrictChatLint;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.XpProbes.Probe;
import tech.starryapi.sourcetier.XpProbes.Setup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The questions of a relay's verification (design 7.4) and how their answers are read: that the requests are the
 * ones the gateway builds (whitelist, canonical form, the member's own settings), each probe is what the design lists,
 * and a judgement says no to every answer a relay could give that traffic cannot use.
 */
class XpProbesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Member member(boolean vision, boolean stream, String reasoning) {
        return new Member("anthropic", "relay-1", "relay", "OPENAI_CHAT", true, vision, false, false, false, stream, reasoning, 540, true, true);
    }

    private static final MemberModel MODEL = new MemberModel("anthropic", "relay-1", "claude-sonnet-4.5", "Relay/Claude-Sonnet-4.5", 200_000,
        8192, true, null, null);

    private static Setup setup(Member member, boolean maxCompletionTokens) {
        return new Setup(member, MODEL, maxCompletionTokens);
    }

    private static Map<String, Probe> probes(Setup setup) {
        return XpProbes.required(setup).stream().collect(Collectors.toMap(Probe::name, probe -> probe, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static Completion answer(String content, List<PlatformToolUse> tools, String finishReason, String usage) {
        return new Completion(content, "", tools, tools.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE, tree(usage), finishReason);
    }

    private static final String USAGE = "{\"prompt_tokens\":12,\"completion_tokens\":3}";

    // ---- the requests ----

    @Test
    void aMemberWithoutVisionOrStreamIsAskedThreeThings() {
        assertThat(probes(setup(member(false, false, "NONE"), false)).keySet()).containsExactly("basic", "tool_call", "tool_loop");
    }

    @Test
    void aStreamAndAnImageAreAskedOnlyOfTheMemberThatHasThem() {
        assertThat(probes(setup(member(false, true, "NONE"), false)).keySet()).containsExactly("basic", "tool_call", "tool_loop", "stream");
        assertThat(probes(setup(member(true, false, "NONE"), false)).keySet()).containsExactly("basic", "tool_call", "tool_loop", "vision");
        assertThat(probes(setup(member(true, true, "NONE"), false)).keySet())
            .containsExactly("basic", "tool_call", "tool_loop", "stream", "vision");
    }

    @Test
    void everyRequestIsABodyThatPassesTheLintAndNamesTheModelAsTheRelayKnowsIt() {
        for (boolean maxCompletion : new boolean[] {false, true}) {
            for (Probe probe : XpProbes.required(setup(member(true, true, "EFFORT"), maxCompletion))) {
                assertThat(StrictChatLint.problems(probe.body())).as(probe.name()).isEmpty();
                assertThat(probe.body().path("model").asString("")).isEqualTo("Relay/Claude-Sonnet-4.5");
            }
        }
    }

    @Test
    void theBasicProbeIsAShortPromptOfSixteenTokensAskedAsJsonAndAGPT5ModelGetsRoomToReason() {
        Probe plain = probes(setup(member(false, true, "NONE"), false)).get("basic");
        assertThat(plain.body().path("stream").booleanValue()).as("the basic probe is never a stream").isFalse();
        assertThat(plain.body().has("stream_options")).isFalse();
        assertThat(plain.body().path("max_tokens").asInt()).isEqualTo(16);
        assertThat(plain.body().has("max_completion_tokens")).isFalse();
        assertThat(plain.body().path("messages")).hasSize(1);

        Probe reasoning = probes(setup(member(false, true, "NONE"), true)).get("basic");
        assertThat(reasoning.body().has("max_tokens")).isFalse();
        assertThat(reasoning.body().path("max_completion_tokens").asInt()).isEqualTo(XpProbes.REASONING_BUDGET);
    }

    @Test
    void theStreamProbeAsksForUsageChunksAndTheToolProbesFollowTheMembersStreamSetting() {
        Map<String, Probe> streaming = probes(setup(member(false, true, "NONE"), false));
        assertThat(streaming.get("stream").body().path("stream").booleanValue()).isTrue();
        assertThat(streaming.get("stream").body().path("stream_options").path("include_usage").booleanValue()).isTrue();
        assertThat(streaming.get("tool_call").body().path("stream").booleanValue()).isTrue();
        assertThat(streaming.get("tool_loop").body().path("stream").booleanValue()).isTrue();

        Map<String, Probe> json = probes(setup(member(false, false, "NONE"), false));
        assertThat(json.get("tool_call").body().path("stream").booleanValue()).isFalse();
        assertThat(json.get("tool_loop").body().path("stream").booleanValue()).isFalse();
    }

    @Test
    void theToolProbeOffersOneFunctionWithChoiceAutoNeverRequired() {
        JsonNode body = probes(setup(member(false, false, "NONE"), false)).get("tool_call").body();

        assertThat(body.path("tool_choice").asString("")).isEqualTo("auto");
        assertThat(body.path("tools")).hasSize(1);
        assertThat(body.path("tools").get(0).path("function").path("name").asString("")).isEqualTo("get_weather");
        assertThat(body.path("tools").get(0).path("function").path("parameters").path("required").get(0).asString("")).isEqualTo("city");
        assertThat(body.toString()).doesNotContain("\"tool_choice\":\"required\"");
        assertThat(body.path("messages").get(1).path("content").asString("")).contains("get_weather");
    }

    @Test
    void theLoopProbeIsTheShapeOfEveryTurnAfterAToolUse() {
        JsonNode messages = probes(setup(member(false, false, "NONE"), false)).get("tool_loop").body().path("messages");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).path("role").asString("")).isEqualTo("user");
        assertThat(messages.get(1).path("role").asString("")).isEqualTo("assistant");
        assertThat(messages.get(1).path("content").isNull()).as("an assistant turn of only calls has no content").isTrue();
        String id = messages.get(1).path("tool_calls").get(0).path("id").asString("");
        assertThat(id).matches("^[A-Za-z0-9_-]{1,64}$");
        assertThat(messages.get(1).path("tool_calls").get(0).path("function").path("arguments").asString("")).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(messages.get(2).path("role").asString("")).isEqualTo("tool");
        assertThat(messages.get(2).path("tool_call_id").asString("")).isEqualTo(id);
    }

    @Test
    void theVisionProbeSendsAOnePixelPngAndTheCaliberOfNothingElse() throws Exception {
        JsonNode content = probes(setup(member(true, false, "NONE"), false)).get("vision").body().path("messages").get(0).path("content");

        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("type").asString("")).isEqualTo("text");
        String url = content.get(1).path("image_url").path("url").asString("");
        assertThat(url).startsWith("data:image/png;base64,");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1))));
        assertThat(image.getWidth()).isEqualTo(1);
        assertThat(image.getHeight()).isEqualTo(1);
    }

    @Test
    void theCacheProbeIsOnePromptOfAboutFiveThousandTokensThatStartsWithTheNonceAndIsTheSameBothTimes() {
        JsonNode first = XpProbes.cacheRequest(setup(member(false, false, "NONE"), false), "nonce-1");
        JsonNode again = XpProbes.cacheRequest(setup(member(false, false, "NONE"), false), "nonce-1");
        JsonNode other = XpProbes.cacheRequest(setup(member(false, false, "NONE"), false), "nonce-2");

        String prompt = first.path("messages").get(0).path("content").asString("");
        assertThat(prompt).startsWith("Marker nonce-1. ");
        // A model's cache starts at 1,024 tokens, 2,048 or, for the newest Claude models, 4,096; a shorter prompt is silently not
        // cached, and the probe would see no cache of the model that needs the most. The sentence is ten tokens.
        assertThat(prompt.length()).isBetween(21_000, 23_000);
        assertThat(XpProbes.CACHE_SENTENCES * 10).isGreaterThanOrEqualTo(4_096 + 512);
        assertThat(first).isEqualTo(again);
        assertThat(first).isNotEqualTo(other);
        assertThat(StrictChatLint.problems(first)).isEmpty();
        assertThat(first.path("max_tokens").asInt()).isEqualTo(16);
    }

    private static Member passingCacheControl(String vendor) {
        return new Member(vendor, "relay-1", "relay", "OPENAI_CHAT", true, false, false, false, true, false, "NONE", 540, true, true);
    }

    /**
     * Claude Code puts a cache control on almost every request, in more places than the text of the last message: on a block of
     * the system prompt, and on the assistant message that has the call and the tool message that answers it (a field of a
     * message of its own, not of a part). A member that passes cache control through is verified with the same, or a relay
     * that refuses the marker in one of those places passes verification and then refuses every turn of a tool loop, one failed
     * attempt after the other.
     */
    @Test
    void aMemberThatPassesCacheControlThroughIsAskedWithTheMarkersOfAToolLoopAndAnyOtherIsNot() {
        Map<String, Probe> marked = probes(setup(passingCacheControl("anthropic"), false));

        assertThat(marked.keySet()).containsExactly("basic", "tool_call", "tool_loop", "cache_control");
        JsonNode messages = marked.get("cache_control").body().path("messages");
        List<String> roles = new java.util.ArrayList<>();
        messages.forEach(message -> roles.add(message.path("role").asString("")));
        assertThat(roles).containsExactly("system", "user", "assistant", "tool");
        JsonNode part = messages.get(0).path("content").get(0);
        assertThat(part.path("type").asString("")).isEqualTo("text");
        assertThat(part.path("cache_control")).isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(messages.get(1).has("cache_control")).isFalse();
        assertThat(messages.get(2).path("cache_control")).as("the assistant message that has the call").isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(messages.get(2).path("tool_calls")).hasSize(1);
        assertThat(messages.get(3).path("cache_control")).as("the tool message that answers it").isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(marked.get("cache_control").body().path("tools")).hasSize(1);
        assertThat(StrictChatLint.problems(marked.get("cache_control").body())).isEmpty();
        assertThat(marked.get("cache_control").judge().apply(answer("ok", List.of(), "stop", USAGE))).isNull();
        assertThat(marked.get("cache_control").judge().apply(answer("ok", List.of(), null, USAGE))).isNotNull();
        assertThat(marked.get("cache_control").judge().apply(answer("", List.of(), "stop", USAGE))).as("the loop must end in words").isNotNull();
        assertThat(probes(setup(member(true, true, "EFFORT"), false)).keySet()).doesNotContain("cache_control");
        assertThat(probes(setup(member(true, true, "EFFORT"), false)).values())
            .allSatisfy(probe -> assertThat(probe.body().toString()).doesNotContain("cache_control"));
    }

    /**
     * A relay that caches where it is told to caches nothing of a prompt without a marker: the probe then sees no cached
     * tokens and concludes nothing, and a relay that reports its prompt without the cache could never be saved as such.
     */
    @Test
    void theCacheProbeCarriesTheMarkerForAMemberThatPassesCacheControlThroughAndOnlyThatMember() {
        JsonNode marked = XpProbes.cacheRequest(setup(passingCacheControl("anthropic"), false), "nonce-1");
        JsonNode again = XpProbes.cacheRequest(setup(passingCacheControl("anthropic"), false), "nonce-1");
        JsonNode plain = XpProbes.cacheRequest(setup(member(false, false, "NONE"), false), "nonce-1");

        JsonNode part = marked.path("messages").get(0).path("content").get(0);
        assertThat(part.path("text").asString("")).startsWith("Marker nonce-1. ");
        assertThat(part.path("text").asString("").length()).isBetween(21_000, 23_000);
        assertThat(part.path("cache_control")).isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(marked).isEqualTo(again);
        assertThat(StrictChatLint.problems(marked)).isEmpty();
        assertThat(plain.toString()).doesNotContain("cache_control");
        assertThat(plain.path("messages").get(0).path("content").isString()).isTrue();
    }

    /**
     * A Gemini 3 model refuses a history whose first call is not signed, and the call of the loop probe is one the relay never
     * made: it is given the placeholder that every unsigned call of a body for such a model is given, which is what the history of
     * traffic has too. No other vendor's probe has one.
     */
    @Test
    void theCallOfTheLoopProbeOfAModelThatSignsHasThePlaceholderSignatureAndNoOtherDoes() {
        JsonNode google = probes(setup(passingCacheControl("google"), false)).get("tool_loop").body();
        JsonNode anthropic = probes(setup(passingCacheControl("anthropic"), false)).get("tool_loop").body();

        assertThat(google.path("messages").get(1).path("tool_calls").get(0).path("extra_content")).isEqualTo(
            tree("{\"google\":{\"thought_signature\":\"skip_thought_signature_validator\"}}"));
        assertThat(anthropic.toString()).doesNotContain("extra_content", "skip_thought_signature_validator");
        assertThat(StrictChatLint.problems(google)).isEmpty();
        assertThat(probes(setup(passingCacheControl("google"), false)).get("tool_call").body().toString()).doesNotContain("extra_content");
    }

    @Test
    void aMemberWithReasoningEffortDoesNotMakeTheProbesThinkOutOfTheirBudget() {
        for (Probe probe : XpProbes.required(setup(member(true, true, "EFFORT"), false))) {
            assertThat(probe.body().has("reasoning_effort")).as(probe.name()).isFalse();
        }
    }

    // ---- the judgements ----

    private static final PlatformToolUse WEATHER = new PlatformToolUse("call_1", "get_weather", tree("{\"city\":\"Paris\"}"));

    @Test
    void theBasicAndStreamProbesNeedAFinishReasonAndAUsage() {
        Function<Completion, String> basic = probes(setup(member(false, true, "NONE"), false)).get("basic").judge();
        Function<Completion, String> stream = probes(setup(member(false, true, "NONE"), false)).get("stream").judge();

        for (Function<Completion, String> judge : List.of(basic, stream)) {
            assertThat(judge.apply(answer("ok", List.of(), "stop", USAGE))).isNull();
            assertThat(judge.apply(answer("ok", List.of(), "length", USAGE))).isNull();
            assertThat(judge.apply(answer("ok", List.of(), null, USAGE))).contains("finish_reason");
            assertThat(judge.apply(answer("ok", List.of(), "stop", "{}"))).contains("usage");
            assertThat(judge.apply(answer("ok", List.of(), "stop", "{\"prompt_tokens\":12}"))).contains("usage");
            assertThat(judge.apply(answer("ok", List.of(), "stop", "{\"completion_tokens\":3}"))).contains("usage");
            assertThat(judge.apply(answer("ok", List.of(), "stop", "{\"prompt_tokens\":0,\"completion_tokens\":0}"))).contains("usage");
            assertThat(judge.apply(answer("ok", List.of(), "stop", "{\"input_tokens\":12,\"output_tokens\":3}"))).isNull();
        }
    }

    @Test
    void theToolProbeNeedsTheOfferedFunctionCalledWithTheCity() {
        Function<Completion, String> judge = probes(setup(member(false, false, "NONE"), false)).get("tool_call").judge();

        assertThat(judge.apply(answer("", List.of(WEATHER), "tool_calls", USAGE))).isNull();
        assertThat(judge.apply(answer("Sure, I will.", List.of(WEATHER), "stop", USAGE))).isNull();
        assertThat(judge.apply(answer("It is sunny.", List.of(), "stop", USAGE))).contains("did not call");
        assertThat(judge.apply(answer("", List.of(new PlatformToolUse("c", "other_function", tree("{\"city\":\"Paris\"}"))), "tool_calls", USAGE)))
            .contains("not offered");
        assertThat(judge.apply(answer("", List.of(new PlatformToolUse("c", "get_weather", tree("{}"))), "tool_calls", USAGE)))
            .contains("arguments");
        assertThat(judge.apply(answer("", List.of(new PlatformToolUse("c", "get_weather", tree("{\"city\":\"  \"}"))), "tool_calls", USAGE)))
            .contains("arguments");
        assertThat(judge.apply(answer("", List.of(new PlatformToolUse("c", "get_weather", tree("{\"city\":5}"))), "tool_calls", USAGE)))
            .contains("arguments");
    }

    @Test
    void theLoopProbeNeedsTextAfterTheResult() {
        Function<Completion, String> judge = probes(setup(member(false, false, "NONE"), false)).get("tool_loop").judge();

        assertThat(judge.apply(answer("It is 21 degrees and sunny.", List.of(), "stop", USAGE))).isNull();
        assertThat(judge.apply(answer("   ", List.of(WEATHER), "tool_calls", USAGE))).contains("no text");
        assertThat(judge.apply(answer("", List.of(), "stop", USAGE))).contains("no text");
    }

    @Test
    void theVisionProbeAcceptsAnyAnswerTheAccumulatorAccepts() {
        Function<Completion, String> judge = probes(setup(member(true, false, "NONE"), false)).get("vision").judge();

        assertThat(judge.apply(answer("A pixel.", List.of(), null, "{}"))).isNull();
    }

    @Test
    void theMessagesOfAJudgementAreTheGatewaysOwnWords() {
        Map<String, Probe> all = probes(setup(member(true, true, "NONE"), false));
        Completion hostile = answer("relay.internal.example.com sk-secret", List.of(new PlatformToolUse("c", "https://relay.internal.example.com", tree("{}"))),
            "https://relay.internal.example.com", "{}");

        for (Probe probe : all.values()) {
            String problem = probe.judge().apply(hostile);
            if (problem != null) {
                assertThat(problem).doesNotContain("relay.internal").doesNotContain("sk-secret");
            }
        }
    }
}
