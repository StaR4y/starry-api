package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Design 7.6: the structure a strict relay requires, one check at a time. The lint prunes a member for the request, so
 * what it must not do matters as much as what it must: the last tests give it everything legal that the sanitizer and
 * the canonicalizer can produce, and check that nothing in its answer holds a word of the body.
 */
class StrictChatLintTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static List<String> problems(String body) {
        return StrictChatLint.problems(tree(body));
    }

    private static String body(String messages) {
        return "{\"messages\":" + messages + "}";
    }

    private static final String USER = "{\"role\":\"user\",\"content\":\"hi\"}";

    private static String call(String id, String name) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"{}\"}}";
    }

    private static String assistantCalling(String... calls) {
        return "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + String.join(",", calls) + "]}";
    }

    private static String result(String id) {
        return "{\"role\":\"tool\",\"tool_call_id\":\"" + id + "\",\"content\":\"r\"}";
    }

    // ---------------------------------------------------------------------------------------- what passes

    @Test
    void aCompleteLegalBodyPasses() {
        String legal = """
            {"model":"w","messages":[{"role":"system","content":"s","cache_control":{"type":"ephemeral"}},
             {"role":"user","content":[{"type":"text","text":"a","cache_control":{"type":"ephemeral"}},
              {"type":"image_url","image_url":{"url":"u","detail":"low"}}]},
             {"role":"assistant","content":"x","tool_calls":[%s,%s]},%s,%s,
             {"role":"assistant","content":"done"},{"role":"user","content":"more"}],
             "max_tokens":5,"temperature":0.1,"top_p":1,"stop":["a","b","c","d"],
             "tools":[{"type":"function","function":{"name":"f","description":"d","parameters":{},"strict":true}}],
             "tool_choice":"auto","parallel_tool_calls":false,"frequency_penalty":0,"presence_penalty":0,"seed":1,
             "response_format":{"type":"json_object"},"reasoning_effort":"low","service_tier":"priority",
             "stream":true,"stream_options":{"include_usage":true}}
            """.formatted(call("a", "f"), call("b", "g"), result("a"), result("b"));

        assertThat(problems(legal)).isEmpty();
        assertThat(StrictChatLint.passes(tree(legal))).isTrue();
    }

    @Test
    void anAssistantMessageWithCallsMayHaveNullContentAndTextAlike() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f")) + "," + result("a") + "]"))).isEmpty();
        assertThat(problems(body("[" + USER + ",{\"role\":\"assistant\",\"content\":\"t\",\"tool_calls\":[" + call("a", "f") + "]}," + result("a") + "]")))
            .isEmpty();
    }

    @Test
    void theCallsOfTheLastTurnMayBeUnansweredOrPartlyAnswered() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f")) + "]"))).isEmpty();
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f"), call("b", "f")) + "," + result("a") + "]"))).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- the shape of the body

    @Test
    void whateverIsNotAnObjectFails() {
        assertThat(StrictChatLint.problems(null)).hasSize(1);
        assertThat(StrictChatLint.problems(tree("[]"))).hasSize(1);
        assertThat(StrictChatLint.passes(tree("\"text\""))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"messages\":[]}", "{\"messages\":{}}", "{\"messages\":\"hi\"}", "{\"messages\":null}"})
    void aBodyWithoutMessagesFails(String body) {
        assertThat(problems(body)).containsExactly("The body has no messages");
    }

    @Test
    void anUnknownKeyFailsAtEveryLevel() {
        String[] bodies = {
            "{\"messages\":[" + USER + "],\"top_k\":5}",
            "{\"messages\":[" + USER + "],\"metadata\":{}}",
            "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\",\"name\":\"bob\"}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\",\"reasoning_content\":\"x\"}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\",\"annotations\":[]}]}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"u\",\"x\":1}}]}]}",
            "{\"messages\":[" + USER + ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"index\":0,"
                + "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}]}",
            "{\"messages\":[" + USER + ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\","
                + "\"function\":{\"name\":\"f\",\"arguments\":\"{}\",\"x\":1}}]}]}",
            "{\"messages\":[" + USER + "],\"tools\":[{\"type\":\"function\",\"cache_control\":{},\"function\":{\"name\":\"f\"}}]}",
            "{\"messages\":[" + USER + "],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\",\"x\":1}}]}"
        };
        for (String body : bodies) {
            assertThat(problems(body)).as(body).isNotEmpty();
        }
    }

    @Test
    void aToolChoiceMustBeOneOfTheWhitelistedFormsForTheToolsOfTheBody() {
        String tools = "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}]";
        for (String legal : List.of("\"auto\"", "\"none\"", "\"required\"", "{\"type\":\"function\",\"function\":{\"name\":\"f\"}}")) {
            assertThat(problems("{\"messages\":[" + USER + "]," + tools + ",\"tool_choice\":" + legal + "}")).as(legal).isEmpty();
        }
        for (String illegal : List.of(
            "\"any\"", "\"Auto\"", "7", "null", "[]", "{}",
            "{\"type\":\"allowed_tools\",\"allowed_tools\":{\"mode\":\"auto\",\"tools\":[]}}",
            "{\"type\":\"function\",\"function\":{\"name\":\"f\"},\"metadata\":{\"user_id\":\"u\"}}",
            "{\"type\":\"function\",\"function\":{\"name\":\"f\",\"x\":1}}",
            "{\"type\":\"function\",\"function\":{\"name\":\"elsewhere\"}}",
            "{\"type\":\"custom\",\"function\":{\"name\":\"f\"}}")) {
            assertThat(problems("{\"messages\":[" + USER + "]," + tools + ",\"tool_choice\":" + illegal + "}")).as(illegal)
                .containsExactly("tool_choice is not one of the whitelisted forms for the tools of the body");
        }
        // With no tools there is nothing to choose among, and relays refuse the field alone.
        assertThat(problems("{\"messages\":[" + USER + "],\"tool_choice\":\"auto\"}")).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "]}")).isEmpty();
    }

    @Test
    void aCacheControlMustBeEphemeralWithAtMostATtl() {
        for (String legal : List.of("{\"type\":\"ephemeral\"}", "{\"type\":\"ephemeral\",\"ttl\":\"1h\"}")) {
            assertThat(problems(body("[{\"role\":\"user\",\"content\":\"hi\",\"cache_control\":" + legal + "}]"))).as(legal).isEmpty();
            assertThat(problems(body("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\",\"cache_control\":" + legal + "}]}]")))
                .as(legal).isEmpty();
        }
        for (String illegal : List.of("{}", "{\"type\":\"persistent\"}", "{\"type\":\"ephemeral\",\"ttl\":5}",
            "{\"type\":\"ephemeral\",\"metadata\":{\"user_id\":\"u\"}}", "\"ephemeral\"", "null", "[]")) {
            assertThat(problems(body("[{\"role\":\"user\",\"content\":\"hi\",\"cache_control\":" + illegal + "}]"))).as(illegal).isNotEmpty();
            assertThat(problems(body("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\",\"cache_control\":" + illegal + "}]}]")))
                .as(illegal).isNotEmpty();
        }
    }

    @Test
    void everyKeyTheSanitizerCanProduceIsKnownToTheLint() {
        assertThat(ChatWireSanitizer.BODY_KEYS).contains("model", "messages", "stream", "stream_options", "reasoning_effort");
        assertThat(ChatWireSanitizer.MESSAGE_KEYS).containsExactlyInAnyOrder("role", "content", "tool_calls", "tool_call_id", "cache_control");
        assertThat(ChatWireSanitizer.PART_KEYS).containsExactlyInAnyOrder("type", "text", "image_url", "cache_control");
    }

    // ---------------------------------------------------------------------------------------- one message

    @ParameterizedTest
    @ValueSource(strings = {"{\"content\":\"x\"}", "{\"role\":\"developer\",\"content\":\"x\"}", "{\"role\":\"function\",\"content\":\"x\"}",
        "{\"role\":7,\"content\":\"x\"}", "\"text\"", "null"})
    void aMessageNeedsAnObjectShapeAndARoleOfTheFour(String message) {
        assertThat(problems(body("[" + message + "," + USER + "]"))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"role\":\"user\",\"content\":null}", "{\"role\":\"user\"}", "{\"role\":\"assistant\",\"content\":null}",
        "{\"role\":\"system\",\"content\":null}", "{\"role\":\"user\",\"content\":7}", "{\"role\":\"user\",\"content\":{}}",
        "{\"role\":\"user\",\"content\":[\"text\"]}", "{\"role\":\"user\",\"content\":[{\"type\":\"audio\"}]}",
        "{\"role\":\"user\",\"content\":[{\"type\":\"text\"}]}", "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\"}]}",
        "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{}}]}",
        "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\",\"image_url\":{\"url\":\"u\"}}]}"})
    void contentMustBeTextOrPartsInTheWhitelistedShapeAndOnlyAnAssistantWithCallsMayHaveNone(String message) {
        assertThat(problems(body("[" + message + "]"))).isNotEmpty();
    }

    @Test
    void callsBelongToAssistantMessagesAndAreANonEmptyArrayOfFunctionCalls() {
        assertThat(problems(body("[{\"role\":\"user\",\"content\":\"x\",\"tool_calls\":[" + call("a", "f") + "]}]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[]}]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":{}}]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[\"a\"]}]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"custom\","
            + "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\","
            + "\"function\":{\"name\":\"f\",\"arguments\":{}}}]}]"))).isNotEmpty();
    }

    @Test
    void aFunctionNeedsANameInACallAndInATool() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "")) + "]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "  ")) + "]"))).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"\"}}]}")).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"tools\":[{\"type\":\"function\",\"function\":{}}]}")).isNotEmpty();
    }

    @Test
    void everyToolMustBeAFunction() {
        assertThat(problems("{\"messages\":[" + USER + "],\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]}")).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"tools\":[\"f\"]}")).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"tools\":[]}")).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"tools\":{}}")).isNotEmpty();
    }

    @Test
    void aToolMessageNeedsTheIdOfACallAndOnlyToolMessagesName() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f")) + ",{\"role\":\"tool\",\"content\":\"r\"}]"))).isNotEmpty();
        assertThat(problems(body("[{\"role\":\"user\",\"content\":\"x\",\"tool_call_id\":\"a\"}]"))).isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------- the ids

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "dot.id", "colon:id", "é", "x\\ny"})
    void aCallIdMustMatchThePattern(String id) {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call(id, "f")) + "," + result(id) + "]"))).isNotEmpty();
    }

    @Test
    void aCallIdOfSixtyFiveCharactersFailsAndOfSixtyFourPasses() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("x".repeat(64), "f")) + "]"))).isEmpty();
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("x".repeat(65), "f")) + "]"))).isNotEmpty();
    }

    @Test
    void aCallIdUsedTwiceFailsInOneMessageOrAcrossTheHistory() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f"), call("a", "g")) + "]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("call_0", "f")) + "," + result("call_0") + ","
            + assistantCalling(call("call_0", "f")) + "," + result("call_0") + ",{\"role\":\"assistant\",\"content\":\"done\"}]")))
            .isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------- the answers

    @Test
    void aToolMessageWithNoCallBeforeItFails() {
        assertThat(problems(body("[{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"r\"}," + USER + "]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + "," + result("a") + "]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + ",{\"role\":\"assistant\",\"content\":\"t\"}," + result("a") + "]"))).isNotEmpty();
    }

    @Test
    void anAnswerMustNameACallOfTheAssistantMessageDirectlyBeforeItsGroup() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f")) + "," + result("zzz") + "]"))).isNotEmpty();
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f")) + "," + result("a") + "," + assistantCalling(call("b", "f"))
            + "," + result("a") + "]"))).isNotEmpty();
    }

    @Test
    void aCallAnsweredTwiceFails() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f")) + "," + result("a") + "," + result("a") + "]"))).isNotEmpty();
    }

    @Test
    void aCallLeftUnansweredBeforeTheLastTurnFails() {
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f"), call("b", "f")) + "," + result("a") + "," + USER + "]")))
            .isNotEmpty();
        assertThat(problems(body("[" + USER + "," + assistantCalling(call("a", "f")) + "," + USER + "," + assistantCalling(call("b", "f")) + "," + result("b")
            + "]"))).isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------- stop

    @Test
    void stopIsAStringOrAtMostFourStrings() {
        assertThat(problems("{\"messages\":[" + USER + "],\"stop\":\"x\"}")).isEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"stop\":[\"a\",\"b\",\"c\",\"d\"]}")).isEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"stop\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}")).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"stop\":[1]}")).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"stop\":5}")).isNotEmpty();
        assertThat(problems("{\"messages\":[" + USER + "],\"stop\":null}")).isNotEmpty();
    }

    // ---------------------------------------------------------------------------------------- what the answer says

    @Test
    void theProblemsHoldNothingOfTheBodyAndAreAtMostTwenty() {
        String secret = "SECRET-WORDS-OF-THE-USER";
        StringBuilder messages = new StringBuilder("[");
        for (int i = 0; i < 40; i++) {
            messages.append(i == 0 ? "" : ",").append("{\"role\":\"user\",\"content\":\"").append(secret).append("\",\"").append(secret)
                .append("\":\"").append(secret).append("\"}");
        }
        String body = "{\"" + secret + "\":1,\"messages\":" + messages + "]}";

        List<String> problems = problems(body);

        assertThat(problems).hasSize(20);
        assertThat(String.join("\n", problems)).doesNotContain(secret);
    }
}
