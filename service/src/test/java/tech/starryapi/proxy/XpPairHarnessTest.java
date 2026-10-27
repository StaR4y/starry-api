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
    private static final PairKey RESPONSES_ANTHROPIC = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "anthropic");
    private static final PairKey RESPONSES_OPENAI = new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.RESPONSES, "openai");

    private static final String MESSAGES_REQUEST = """
        {"model":"claude-sonnet-4.5","max_tokens":100,"messages":[{"role":"user","content":"hi"}],
         "tools":[{"name":"Bash","description":"Runs a command","input_schema":{"type":"object","properties":{"command":{"type":"string"}}}}]}
        """;
    private static final String CHAT_REQUEST = """
        {"model":"claude-sonnet-4.5","max_tokens":100,"messages":[{"role":"user","content":"hi"}],
         "tools":[{"type":"function","function":{"name":"bash","description":"Runs a command","parameters":{"type":"object","properties":{"command":{"type":"string"}}}}}]}
        """;
    private static final String RESPONSES_REQUEST = """
        {"model":"claude-sonnet-4.5","instructions":"You are Codex.",
         "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}],
         "tools":[{"type":"function","name":"exec_command","description":"Runs a command","strict":false,
                   "parameters":{"type":"object","properties":{"cmd":{"type":"string"}}}}],
         "tool_choice":"auto","parallel_tool_calls":false,"store":false,"stream":true}
        """;
    /** The relay's call, as it names the function of the Responses request. */
    private static final String RESPONSES_CALL_ANSWER = """
        {"id":"c1","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"I will run it.",
         "tool_calls":[{"id":"call_0","type":"function","function":{"name":"exec_command","arguments":"{\\"cmd\\":\\"ls\\"}"}}]},"finish_reason":"tool_calls"}],
         "usage":{"prompt_tokens":100,"completion_tokens":9,"total_tokens":109}}
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

    @Test
    void aResponsesCaseIsAnsweredWithTheEventsOfAResponseAndTheRelayIsSentNothingOfCodex() throws Exception {
        Map<String, byte[]> out = XpPairHarness.run(RESPONSES_ANTHROPIC, "plain", responses(TEXT_ANSWER));

        assertThat(out.keySet()).containsExactly("relay-request.json", "client-response.json", "route.json", "billing.json");
        JsonNode reply = JSON.readTree(out.get("client-response.json"));
        assertThat(reply.path("status").asInt()).isEqualTo(200);
        assertThat(reply.path("headers").path("X-Starry-Tier").asString()).isEqualTo("relay");
        JsonNode events = reply.path("events");
        assertThat(events.get(0).path("event").asString()).isEqualTo("response.created");
        assertThat(events.get(events.size() - 2).path("event").asString()).isEqualTo("response.completed");
        // The ids the gateway makes at random are placeholders, the ones of an item and a response as well as a message's.
        assertThat(events.toString()).contains("\"resp_<id>\"", "\"msg_<id>\"").doesNotContainPattern("resp_[0-9a-f]{32}");
        JsonNode sent = JSON.readTree(out.get("relay-request.json")).path("body");
        assertThat(sent.path("model").asString()).isEqualTo("relay-sonnet");
        assertThat(sent.path("messages").get(0).path("content").asString()).isEqualTo("You are Codex.");
        assertThat(sent.has("input")).isFalse();
        assertThat(sent.has("instructions")).isFalse();
    }

    @Test
    void theTurnsAfterTheFirstOfAResponsesCaseAreBuiltFromTheItemsOfTheAnswerAsCodexPutsThemBack() throws Exception {
        Map<String, byte[]> input = responses(RESPONSES_CALL_ANSWER);
        put(input, "turns/2/follow-up.json", """
            [{"type":"function_call_output","call_id":"${tool.0}","output":"a.txt"}]
            """);
        put(input, "turns/2/relay.json", "{\"status\":200,\"body\":\"relay-body.json\"}");
        put(input, "turns/2/relay-body.json", TEXT_ANSWER);

        Map<String, byte[]> out = XpPairHarness.run(RESPONSES_ANTHROPIC, "loop", input);

        // The id the client was given is the one the gateway made, not the relay's own call_0, and the result names it.
        JsonNode first = JSON.readTree(out.get("client-response.json")).path("events");
        String given = null;
        for (JsonNode event : first) {
            if (event.path("event").asString("").equals("response.output_item.done")
                && event.path("data").path("item").path("type").asString("").equals("function_call")) {
                given = event.path("data").path("item").path("call_id").asString();
            }
        }
        assertThat(given).matches("call_[A-Za-z0-9]{24}");
        JsonNode second = JSON.readTree(out.get("turns/2/relay-request.json")).path("body").path("messages");
        // system, the user's message, the assistant's text and call as one message, the result.
        assertThat(second.get(second.size() - 2).path("tool_calls").path(0).path("id").asString()).isEqualTo(given);
        assertThat(second.get(second.size() - 1).path("role").asString()).isEqualTo("tool");
        assertThat(second.get(second.size() - 1).path("tool_call_id").asString()).isEqualTo(given);
        assertThat(JSON.readTree(out.get("turns/2/client-response.json")).path("events").toString()).contains("hello");
    }

    @Test
    void aResponsesTurnThatContinuesTheResponseOfThePreviousOneIsGivenItsId() throws Exception {
        Map<String, byte[]> input = responses(TEXT_ANSWER);
        put(input, "turns/2/request.json", """
            {"model":"claude-sonnet-4.5","instructions":"You are Codex.","previous_response_id":"${response.id}",
             "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"and again?"}]}],
             "tools":[{"type":"function","name":"exec_command","description":"Runs a command","parameters":{"type":"object","properties":{"cmd":{"type":"string"}}}}],
             "stream":true}
            """);
        put(input, "turns/2/relay.json", "{\"status\":200,\"body\":\"relay-body.json\"}");
        put(input, "turns/2/relay-body.json", TEXT_ANSWER);

        Map<String, byte[]> out = XpPairHarness.run(RESPONSES_ANTHROPIC, "chain", input);

        // The chain is expanded by the gateway into the history it holds: the first question, its answer, the new question.
        JsonNode messages = JSON.readTree(out.get("turns/2/relay-request.json")).path("body").path("messages");
        assertThat(messages).extracting(message -> message.path("role").asString())
            .containsExactly("system", "user", "assistant", "user");
        assertThat(messages.get(2).path("content").asString()).isEqualTo("hello");
        assertThat(out.get("turns/2/relay-request.json")).isNotEmpty();
        assertThat(new String(out.get("turns/2/relay-request.json"), StandardCharsets.UTF_8)).doesNotContain("previous_response_id");
    }

    @Test
    void aTurnThatContinuesAResponseThePreviousTurnDidNotAnswerWithIsRefused() {
        Map<String, byte[]> input = responses(TEXT_ANSWER);
        put(input, "relay.json", "{\"status\":503,\"body\":\"relay-body.json\"}");
        put(input, "native.json", "{\"accounts\":false}");
        put(input, "turns/2/request.json", "{\"previous_response_id\":\"${response.id}\",\"input\":\"x\"}");

        assertThatThrownBy(() -> XpPairHarness.run(RESPONSES_ANTHROPIC, "chain", input))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("continues the response of turn 1");
    }

    @Test
    void aResponsesFollowUpThatNamesAToolCallTheAnswerDidNotHaveIsRefused() {
        Map<String, byte[]> input = responses(TEXT_ANSWER);
        put(input, "turns/2/follow-up.json", "[{\"type\":\"function_call_output\",\"call_id\":\"${tool.0}\",\"output\":\"x\"}]");

        assertThatThrownBy(() -> XpPairHarness.run(RESPONSES_ANTHROPIC, "bad", input))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("names tool call 0");
    }

    @Test
    void aGptRequestOfCodexThatNoRelayTakesIsServedByTheCodexAccountOfTheNativeTier() throws Exception {
        Map<String, byte[]> input = new LinkedHashMap<>();
        // The web search that Codex declares for a gpt model by default is a tool the relay cannot be given.
        put(input, "request.json", RESPONSES_REQUEST.replace("claude-sonnet-4.5", "gpt-5.5")
            .replace("\"tools\":[", "\"tools\":[{\"type\":\"web_search\",\"external_web_access\":false},"));

        Map<String, byte[]> out = XpPairHarness.run(RESPONSES_OPENAI, "web-search", input);

        assertThat(out).doesNotContainKey("relay-request.json");
        JsonNode route = JSON.readTree(out.get("route.json"));
        assertThat(route.path("relaysLeftOut").path(0).path("reason").asString()).isEqualTo("FEATURE");
        assertThat(route.path("relaysLeftOut").path(0).path("detail").asString()).isEqualTo("builtin_tool");
        assertThat(route.path("attempts").path(0).path("account").asString()).isEqualTo("codex-1");
        assertThat(JSON.readTree(out.get("client-response.json")).path("headers").path("X-Starry-Tier").asString()).isEqualTo("native");
    }

    @Test
    void aClaudeRequestOfCodexThatNoRelayTakesIsServedByKiroWithTheToolIdsItCarriedPaired() throws Exception {
        Map<String, byte[]> input = responses(RESPONSES_CALL_ANSWER);
        put(input, "turns/2/follow-up.json", "[{\"type\":\"function_call_output\",\"call_id\":\"${tool.0}\",\"output\":\"a.txt\"}]");
        put(input, "turns/2/relay.json", "{\"status\":503,\"body\":\"relay-body.json\"}");
        put(input, "turns/2/relay-body.json", "{\"error\":{\"message\":\"overloaded\",\"type\":\"server_error\"}}");

        Map<String, byte[]> out = XpPairHarness.run(RESPONSES_ANTHROPIC, "native-second-turn", input);

        JsonNode attempts = JSON.readTree(out.get("turns/2/route.json")).path("attempts");
        assertThat(attempts.get(attempts.size() - 1).path("account").asString()).isEqualTo("kiro-1");
        String given = JSON.readTree(out.get("route.json")).path("attempts").path(0).path("account").asString();
        assertThat(given).isEqualTo("relay-1");
        // Kiro is sent the call and its result under the one id the client was given.
        JsonNode ids = attempts.get(attempts.size() - 1).path("nativeToolIds");
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0).asString()).isEqualTo(ids.get(1).asString()).matches("call_[A-Za-z0-9]{24}");
    }

    private static Map<String, byte[]> responses(String answer) {
        Map<String, byte[]> input = new LinkedHashMap<>();
        put(input, "request.json", RESPONSES_REQUEST);
        put(input, "relay.json", "{\"status\":200,\"body\":\"relay-body.json\"}");
        put(input, "relay-body.json", answer);
        return input;
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
