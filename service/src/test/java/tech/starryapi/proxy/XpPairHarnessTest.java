package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The harness that plays the cases of the fixtures (see {@link XpPairHarness}): what it needs of a case, what it makes of
 * the turns after the first, and what it refuses to play. The cases themselves are the contract test's.
 */
class XpPairHarnessTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PairKey MESSAGES_ANTHROPIC = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "anthropic");
    private static final PairKey CHAT_ANTHROPIC = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.CHAT, "anthropic");
    private static final PairKey MESSAGES_OPENAI = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, "openai");

    private static final String MESSAGES_REQUEST = """
        {"model":"claude-sonnet-4.5","max_tokens":100,"messages":[{"role":"user","content":"hi"}],
         "tools":[{"name":"Bash","description":"Runs a command","input_schema":{"type":"object","properties":{"command":{"type":"string"}}}}]}
        """;
    private static final String CHAT_REQUEST = """
        {"model":"claude-sonnet-4.5","max_tokens":100,"messages":[{"role":"user","content":"hi"}],
         "tools":[{"type":"function","function":{"name":"bash","description":"Runs a command","parameters":{"type":"object","properties":{"command":{"type":"string"}}}}}]}
        """;
    private static final String TEXT_ANSWER = """
        {"id":"c1","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}
        """;
    private static final String CALL_ANSWER = """
        {"id":"c1","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":null,
         "tool_calls":[{"id":"call_0","type":"function","function":{"name":"Bash","arguments":"{\\"command\\":\\"ls\\"}"}}]},"finish_reason":"tool_calls"}],
         "usage":{"prompt_tokens":100,"completion_tokens":9,"total_tokens":109}}
        """;

    @Test
    void aCaseComesOutAsTheFourFilesOfItsFirstTurn() throws Exception {
        Map<String, byte[]> out = XpPairHarness.run(MESSAGES_ANTHROPIC, "plain", messages(TEXT_ANSWER));

        assertThat(out.keySet()).containsExactly("relay-request.json", "client-response.json", "route.json", "billing.json");
        JsonNode reply = JSON.readTree(out.get("client-response.json"));
        assertThat(reply.path("status").asInt()).isEqualTo(200);
        assertThat(reply.path("headers").path("X-Starry-Tier").asString()).isEqualTo("relay");
        assertThat(reply.path("body").path("content").path(0).path("text").asString()).isEqualTo("hello");
        JsonNode relay = JSON.readTree(out.get("relay-request.json"));
        assertThat(relay.path("body").path("model").asString()).isEqualTo("relay-sonnet");
        assertThat(relay.path("headers").path("authorization").asString()).isEqualTo("Bearer <relay key>");
        // 100 tokens in at 3 per million and 5 out at 15 per million, at the price factor 1 of the relay's tier.
        assertThat(JSON.readTree(out.get("billing.json")).path("settled").path("credits").asString()).isEqualTo("0.000375");
    }

    @Test
    void theFilesAreWrittenWithTwoSpacesAndLineFeedsAndEndWithOne() throws Exception {
        Map<String, byte[]> out = XpPairHarness.run(MESSAGES_ANTHROPIC, "plain", messages(TEXT_ANSWER));

        String billing = new String(out.get("billing.json"), StandardCharsets.UTF_8);
        assertThat(billing).startsWith("{\n  \"reserved\": \"").endsWith("\n}\n").doesNotContain("\r");
        // An empty array and an empty object are one token, so a file does not change with the number of items it lacks.
        assertThat(new String(out.get("route.json"), StandardCharsets.UTF_8)).contains("\"relaysLeftOut\": []");
    }

    @Test
    void theTurnsAfterTheFirstAreBuiltFromTheAnswerTheGatewayGaveAndTheFollowUp() throws Exception {
        Map<String, byte[]> input = messages(CALL_ANSWER);
        put(input, "turns/2/follow-up.json", """
            [{"role":"user","content":[{"type":"tool_result","tool_use_id":"${tool.0}","content":"a.txt"}]}]
            """);
        put(input, "turns/2/relay.json", "{\"status\":200,\"body\":\"relay-body.json\"}");
        put(input, "turns/2/relay-body.json", TEXT_ANSWER);

        Map<String, byte[]> out = XpPairHarness.run(MESSAGES_ANTHROPIC, "loop", input);

        assertThat(out.keySet()).contains("client-response.json", "turns/2/relay-request.json", "turns/2/client-response.json");
        // The id the client was given is the one the gateway made, not the relay's own call_0, and the result names it.
        String given = JSON.readTree(out.get("client-response.json")).path("body").path("content").path(0).path("id").asString();
        assertThat(given).matches("toolu_[A-Za-z0-9]{24}");
        JsonNode second = JSON.readTree(out.get("turns/2/relay-request.json")).path("body").path("messages");
        assertThat(second.path(1).path("tool_calls").path(0).path("id").asString()).isEqualTo(given);
        assertThat(second.path(2).path("role").asString()).isEqualTo("tool");
        assertThat(second.path(2).path("tool_call_id").asString()).isEqualTo(given);
        assertThat(JSON.readTree(out.get("turns/2/client-response.json")).path("body").path("content").path(0).path("text").asString())
            .isEqualTo("hello");
    }

    @Test
    void aChatClientGetsGatewayIdsInTheSameWay() throws Exception {
        Map<String, byte[]> input = new LinkedHashMap<>();
        put(input, "request.json", CHAT_REQUEST);
        put(input, "relay.json", "{\"status\":200,\"body\":\"relay-body.json\"}");
        put(input, "relay-body.json", CALL_ANSWER);
        put(input, "turns/2/follow-up.json", "[{\"role\":\"tool\",\"tool_call_id\":\"${tool.0}\",\"content\":\"a.txt\"}]");
        put(input, "turns/2/relay.json", "{\"status\":200,\"body\":\"relay-body.json\"}");
        put(input, "turns/2/relay-body.json", TEXT_ANSWER);

        Map<String, byte[]> out = XpPairHarness.run(CHAT_ANTHROPIC, "loop", input);

        String given = JSON.readTree(out.get("client-response.json")).path("body").path("choices").path(0).path("message")
            .path("tool_calls").path(0).path("id").asString();
        assertThat(given).matches("call_[A-Za-z0-9]{24}");
        JsonNode second = JSON.readTree(out.get("turns/2/relay-request.json")).path("body").path("messages");
        assertThat(second.path(1).path("tool_calls").path(0).path("id").asString()).isEqualTo(given);
        assertThat(second.path(2).path("tool_call_id").asString()).isEqualTo(given);
    }

    @Test
    void aFollowUpThatNamesAToolCallTheAnswerDidNotHaveIsRefused() {
        Map<String, byte[]> input = messages(TEXT_ANSWER);
        put(input, "turns/2/follow-up.json", "[{\"role\":\"user\",\"content\":\"${tool.0}\"}]");

        assertThatThrownBy(() -> XpPairHarness.run(MESSAGES_ANTHROPIC, "bad", input))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("names tool call 0");
    }

    @Test
    void aTurnWithNeitherAFollowUpNorARequestIsRefused() {
        Map<String, byte[]> input = messages(TEXT_ANSWER);
        put(input, "turns/2/relay.json", "{\"status\":200}");

        assertThatThrownBy(() -> XpPairHarness.run(MESSAGES_ANTHROPIC, "bad", input))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("neither a request nor a follow-up");
    }

    @Test
    void aRequestThatIsNotPlannedOverTheTiersOfThePairsVendorIsNotPlayed() {
        // A Claude model asked of the openai pair: the vendor is anthropic, which this play has no tiers for, so the facade
        // would serve it as it always did and the fixture would say nothing about a relay.
        assertThatThrownBy(() -> XpPairHarness.run(MESSAGES_OPENAI, "wrong-vendor", messages(TEXT_ANSWER)))
            .isInstanceOf(AssertionError.class).hasMessageContaining("was not planned over the tiers");
    }

    @Test
    void aBodyThatIsNotThereIsRefused() {
        Map<String, byte[]> input = new LinkedHashMap<>();
        put(input, "request.json", MESSAGES_REQUEST);
        put(input, "relay.json", "{\"status\":200,\"body\":\"relay-body.json\"}");

        assertThatThrownBy(() -> XpPairHarness.run(MESSAGES_ANTHROPIC, "bad", input))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("relay-body.json, which is not there");
    }

    @Test
    void aRelayThatIsNotScriptedAnswersWithAnErrorAndTheNativeTierServes() throws Exception {
        Map<String, byte[]> input = new LinkedHashMap<>();
        put(input, "request.json", MESSAGES_REQUEST);

        Map<String, byte[]> out = XpPairHarness.run(MESSAGES_ANTHROPIC, "unscripted", input);

        JsonNode route = JSON.readTree(out.get("route.json"));
        assertThat(route.path("attempts").path(0).path("account").asString()).isEqualTo("relay-1");
        assertThat(route.path("attempts").path(0).path("success").asBoolean()).isFalse();
        assertThat(JSON.readTree(out.get("client-response.json")).path("headers").path("X-Starry-Tier").asString()).isEqualTo("native");
    }

    @Test
    void theGateOfACaseIsTheOneItsFileSays() throws Exception {
        Map<String, byte[]> input = messages(TEXT_ANSWER);
        put(input, "gate.json", "{\"state\":\"KEYS\",\"keys\":[\"key-2\"]}");

        Map<String, byte[]> out = XpPairHarness.run(MESSAGES_ANTHROPIC, "gate", input);

        assertThat(out).doesNotContainKey("relay-request.json");
        assertThat(JSON.readTree(out.get("route.json")).path("relaysLeftOut").path(0).path("reason").asString()).isEqualTo("GATE");
    }

    @Test
    void theMemberOfACaseIsTheOneItsFileSays() throws Exception {
        Map<String, byte[]> input = messages(TEXT_ANSWER);
        put(input, "member.json", "{\"upstreamStream\":false}");

        Map<String, byte[]> out = XpPairHarness.run(MESSAGES_ANTHROPIC, "member", input);

        JsonNode sent = JSON.readTree(out.get("relay-request.json")).path("body");
        assertThat(sent.path("stream").asBoolean(true)).isFalse();
        assertThat(sent.has("stream_options")).isFalse();
    }

    private static Map<String, byte[]> messages(String answer) {
        Map<String, byte[]> input = new LinkedHashMap<>();
        put(input, "request.json", MESSAGES_REQUEST);
        put(input, "relay.json", "{\"status\":200,\"body\":\"relay-body.json\"}");
        put(input, "relay-body.json", answer);
        return input;
    }

    private static void put(Map<String, byte[]> input, String name, String text) {
        input.put(name, text.getBytes(StandardCharsets.UTF_8));
    }
}
