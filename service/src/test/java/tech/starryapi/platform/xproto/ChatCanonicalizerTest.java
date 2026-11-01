package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Design 7.6, the repair half: the histories Anthropic accepts and a strict relay does not, rule by rule as the class
 * description numbers them. The histories the real conversion makes of Claude Code's requests are in {@code
 * ChatWirePipelineTest}; here the Chat messages are written out, so each expectation can be read against the rule.
 */
class ChatCanonicalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A Claude model behind the relay: its upstream refuses tool calls in a request that defines no tools. */
    private static final Settings CLAUDE = new Settings(false, false, false, false, false, false, false, false, true, true);
    /** A gpt model behind the relay: its upstream takes the history as it is. */
    private static final Settings GPT = new Settings(false, false, false, false, false, false, false, false, false, false);

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    /** A body that declares a tool: a history with tool calls in a body that declares none is flattened (see the last section). */
    private static final String TOOLS = "[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}]";

    private static Wire canonicalize(String messages, String... degraded) {
        ObjectNode body = (ObjectNode) tree("{\"tools\":" + TOOLS + ",\"messages\":" + messages + "}");
        return ChatCanonicalizer.canonicalize(new Wire(body, List.of(degraded)), CLAUDE);
    }

    private static Wire canonicalizeWithoutTools(String messages, String... degraded) {
        ObjectNode body = (ObjectNode) tree("{\"messages\":" + messages + "}");
        return ChatCanonicalizer.canonicalize(new Wire(body, List.of(degraded)), CLAUDE);
    }

    private static JsonNode messages(Wire wire) {
        return wire.body().path("messages");
    }

    private static String call(String id, String name) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"{}\"}}";
    }

    private static String callWith(String id, String name, String arguments) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + arguments + "\"}}";
    }

    private static String assistantCalling(String... calls) {
        return "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + String.join(",", calls) + "]}";
    }

    private static String result(String id, String content) {
        return "{\"role\":\"tool\",\"tool_call_id\":\"" + id + "\",\"content\":\"" + content + "\"}";
    }

    private static String user(String text) {
        return "{\"role\":\"user\",\"content\":\"" + text + "\"}";
    }

    private static String assistant(String text) {
        return "{\"role\":\"assistant\",\"content\":\"" + text + "\"}";
    }

    private static List<String> idsOfCalls(JsonNode messages) {
        List<String> ids = new ArrayList<>();
        for (JsonNode message : messages) {
            for (JsonNode call : message.path("tool_calls")) {
                ids.add(call.path("id").stringValue());
            }
        }
        return ids;
    }

    private static List<String> idsOfResults(JsonNode messages) {
        List<String> ids = new ArrayList<>();
        for (JsonNode message : messages) {
            if ("tool".equals(message.path("role").stringValue())) {
                ids.add(message.path("tool_call_id").stringValue());
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------------------------------- a clean history

    @Test
    void aHistoryThatNeedsNothingComesOutEqual() {
        String history = "[{\"role\":\"system\",\"content\":\"be brief\"}," + user("list the files") + ","
            + "{\"role\":\"assistant\",\"content\":\"ok\",\"tool_calls\":[" + call("call_a", "ls") + "]},"
            + result("call_a", "a.txt") + "," + assistant("one file") + "," + user("thanks") + "]";

        Wire wire = canonicalize(history);

        assertThat(messages(wire)).isEqualTo(tree(history));
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void theInputIsNotChangedAndTheOtherFieldsAreKeptWhereTheyWere() {
        String json = "{\"model\":\"m\",\"messages\":[" + user("a") + "," + assistant("") + "," + user("b") + ","
            + assistantCalling(call("x", "f")) + "],\"tools\":" + TOOLS + ",\"max_tokens\":5,\"stream\":true}";
        ObjectNode input = (ObjectNode) tree(json);

        Wire wire = ChatCanonicalizer.canonicalize(new Wire(input, List.of("image")), CLAUDE);

        assertThat(input).isEqualTo(tree(json));
        assertThat(wire.body().propertyNames()).containsExactly("model", "messages", "tools", "max_tokens", "stream");
        assertThat(wire.body().path("max_tokens").intValue()).isEqualTo(5);
        assertThat(wire.degraded()).containsExactly("image");
    }

    @Test
    void canonicalizingTheResultAgainChangesNothing() {
        Wire once = canonicalize("[" + user("a") + "," + assistant("") + "," + user("") + ","
            + assistantCalling(call("call_0", "f")) + "," + result("call_0", "") + ","
            + assistantCalling(call("call_0", "f")) + "," + result("call_0", "x") + ","
            + assistantCalling(call("call_0", "f")) + "," + result("zzz", "orphan") + "," + user("last") + "]");

        Wire twice = ChatCanonicalizer.canonicalize(once, CLAUDE);

        assertThat(twice.body()).isEqualTo(once.body());
        assertThat(twice.degraded()).isEqualTo(once.degraded());
    }

    // ---------------------------------------------------------------------------------------- rule 1: empty messages

    @Test
    void anAssistantMessageWithNothingInItIsLeftOutAndTheUserMessagesAroundItAreOne() {
        Wire wire = canonicalize("[" + user("first") + "," + assistant("") + "," + user("second") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("first\\n\\nsecond") + "]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"  \\n\\t\"", "[]", "[{\"type\":\"text\",\"text\":\" \"}]"})
    void anAssistantMessageWithoutTextOrCallsIsEmptyHoweverTheEmptinessIsWritten(String content) {
        Wire wire = canonicalize("[" + user("a") + ",{\"role\":\"assistant\",\"content\":" + content + "}," + user("b") + "]");

        assertThat(messages(wire)).hasSize(1);
        assertThat(messages(wire).get(0).path("content").stringValue()).isEqualTo("a\n\nb");
    }

    @Test
    void anAssistantMessageWithCallsIsNeverEmptyAndHasNullContentWhenItHasNoText() {
        Wire wire = canonicalize("[" + user("a") + ",{\"role\":\"assistant\",\"content\":\"  \",\"tool_calls\":[" + call("c", "f") + "]},"
            + result("c", "r") + "]");

        assertThat(messages(wire).get(1).path("content").isNull()).isTrue();
        assertThat(messages(wire).get(1).path("tool_calls")).hasSize(1);
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void aMessageWithoutContentAtAllIsTreatedAsEmpty() {
        Wire wire = canonicalize("[" + user("a") + ",{\"role\":\"assistant\"}," + user("b") + ",{\"role\":\"user\"}]");

        assertThat(messages(wire)).hasSize(1);
        assertThat(messages(wire).get(0).path("content").stringValue()).isEqualTo("a\n\nb");
    }

    @Test
    void anEmptySystemMessageIsLeftOutAndAFullOneStaysWhereItIs() {
        Wire wire = canonicalize("[{\"role\":\"system\",\"content\":\"\"}," + user("a") + ",{\"role\":\"system\",\"content\":\"mid\"},"
            + assistant("b") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("a") + ",{\"role\":\"system\",\"content\":\"mid\"}," + assistant("b") + "]"));
    }

    @Test
    void systemMessagesAreNeverMergedWithOneAnother() {
        Wire wire = canonicalize("[{\"role\":\"system\",\"content\":\"one\"},{\"role\":\"system\",\"content\":\"two\"}," + user("a") + "]");

        assertThat(messages(wire)).hasSize(3);
    }

    // ---------------------------------------------------------------------------------------- rule 5: placeholders

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "null", "\"   \"", "[]", "[{\"type\":\"text\",\"text\":\"\"}]"})
    void aToolResultWithNoContentSaysSoAndIsRecorded(String content) {
        Wire wire = canonicalize("[" + user("run it") + "," + assistantCalling(call("c", "bash")) + ","
            + "{\"role\":\"tool\",\"tool_call_id\":\"c\",\"content\":" + content + "}]");

        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("[no output]");
        assertThat(wire.degraded()).containsExactly("empty_tool_result");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "null", "\"   \"", "[]", "[{\"type\":\"text\",\"text\":\" \"}]"})
    void aUserTurnWithNothingLeftIsKeptWithAPlaceholderAndRecorded(String content) {
        Wire wire = canonicalize("[" + user("first") + "," + assistant("ok") + ",{\"role\":\"user\",\"content\":" + content + "},"
            + assistant("and?") + "," + user("second") + "]");

        assertThat(messages(wire)).hasSize(5);
        assertThat(messages(wire).get(2).path("role").stringValue()).isEqualTo("user");
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("[non-text content omitted]");
        assertThat(wire.degraded()).containsExactly("empty_user_message");
    }

    @Test
    void aUserTurnWithAnImageIsNotEmpty() {
        String turn = "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"u\"}}]}";

        Wire wire = canonicalize("[" + turn + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + turn + "]"));
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void anEmptyUserTurnNextToAFullOneAddsNothingToIt() {
        Wire wire = canonicalize("[" + user("") + "," + user("text") + "," + user("  ") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("text") + "]"));
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void callsWithoutArgumentsGetAnEmptyObject() {
        Wire wire = canonicalize("[" + user("a") + ",{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
            + "{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"\"}},"
            + "{\"id\":\"d\",\"type\":\"function\",\"function\":{\"name\":\"g\",\"arguments\":\" \"}},"
            + "{\"id\":\"e\",\"type\":\"function\",\"function\":{\"name\":\"h\",\"arguments\":\"{\\\"k\\\":1}\"}}]}]");

        JsonNode calls = messages(wire).get(1).path("tool_calls");
        assertThat(calls.get(0).path("function").path("arguments").stringValue()).isEqualTo("{}");
        assertThat(calls.get(1).path("function").path("arguments").stringValue()).isEqualTo("{}");
        assertThat(calls.get(2).path("function").path("arguments").stringValue()).isEqualTo("{\"k\":1}");
    }

    // ---------------------------------------------------------------------------------------- rule 4: merging

    @Test
    void adjacentUserMessagesAreOneWithABlankLineBetween() {
        Wire wire = canonicalize("[" + user("a") + "," + user("b") + "," + user("c") + "," + assistant("x") + "," + assistant("y") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("a\\n\\nb\\n\\nc") + "," + assistant("x\\n\\ny") + "]"));
    }

    @Test
    void messagesWithPartsAreMergedPartAfterPart() {
        Wire wire = canonicalize("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\"}]},"
            + "{\"role\":\"user\",\"content\":\"b\"},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"u\"}}]}]");

        assertThat(messages(wire)).isEqualTo(tree("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a\"},"
            + "{\"type\":\"text\",\"text\":\"b\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"u\"}}]}]"));
    }

    @Test
    void theCacheControlOfTheLaterMessageWinsWhenMessagesAreMerged() {
        Wire wire = canonicalize("[{\"role\":\"user\",\"content\":\"a\",\"cache_control\":{\"type\":\"first\"}},"
            + "{\"role\":\"user\",\"content\":\"b\",\"cache_control\":{\"type\":\"second\"}},"
            + "{\"role\":\"user\",\"content\":\"c\"}]");

        assertThat(messages(wire)).hasSize(1);
        assertThat(messages(wire).get(0).path("cache_control").path("type").stringValue()).isEqualTo("second");
    }

    @Test
    void anAssistantTextAndTheAssistantMessageWithCallsAfterItAreOneMessage() {
        Wire wire = canonicalize("[" + user("go") + "," + assistant("Let me look.") + "," + assistantCalling(call("c", "ls")) + ","
            + result("c", "files") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("go") + ",{\"role\":\"assistant\",\"content\":\"Let me look.\","
            + "\"tool_calls\":[" + call("c", "ls") + "]}," + result("c", "files") + "]"));
    }

    @Test
    void anAssistantMessageWithCallsFollowedByTextKeepsItsAnswersBetween() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("c", "ls")) + "," + result("c", "files") + ","
            + assistant("Here.") + "]");

        assertThat(messages(wire)).hasSize(4);
        assertThat(messages(wire).get(3).path("role").stringValue()).isEqualTo("assistant");
    }

    @Test
    void toolMessagesAreNeverMerged() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("a", "f"), call("b", "f")) + ","
            + result("a", "1") + "," + result("b", "2") + "]");

        assertThat(messages(wire)).hasSize(4);
    }

    // ---------------------------------------------------------------------------------------- rules 2 and 3: pairing

    @Test
    void answersFollowTheirCallsInTheOrderOfTheCallsWhereverTheyWere() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("a", "f"), call("b", "g")) + ","
            + result("b", "second") + "," + user("interjection") + "," + result("a", "first") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("go") + "," + assistantCalling(call("a", "f"), call("b", "g")) + ","
            + result("a", "first") + "," + result("b", "second") + "," + user("interjection") + "]"));
    }

    @Test
    void aToolMessageThatAnswersNoCallBecomesUserText() {
        Wire wire = canonicalize("[" + user("go") + "," + assistant("hi") + "," + result("ghost", "lost output") + "," + user("next") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("go") + "," + assistant("hi") + ","
            + user("Tool result:\\nlost output\\n\\nnext") + "]"));
    }

    @Test
    void anOrphanWithNoContentIsAShortLabelAndOneWithoutAnIdIsAnOrphanToo() {
        Wire wire = canonicalize("[" + user("go") + ",{\"role\":\"assistant\",\"content\":\"x\"},{\"role\":\"tool\",\"tool_call_id\":\"q\",\"content\":\"\"},"
            + "{\"role\":\"assistant\",\"content\":\"y\"},{\"role\":\"tool\",\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}]");

        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("Tool result:");
        assertThat(messages(wire).get(4).path("content").stringValue()).isEqualTo("Tool result:\na\nb");
        assertThat(messages(wire).get(4).has("tool_call_id")).isFalse();
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void aSecondAnswerToTheSameCallAndAnAnswerBeforeItsCallAreOrphans() {
        Wire wire = canonicalize("[" + user("go") + "," + result("c", "early") + "," + assistantCalling(call("c", "f")) + ","
            + result("c", "real") + "," + assistant("ok") + "," + result("c", "again") + "]");

        assertThat(messages(wire).get(0).path("content").stringValue()).isEqualTo("go\n\nTool result:\nearly");
        assertThat(messages(wire).get(1).path("tool_calls")).hasSize(1);
        assertThat(messages(wire).get(2).path("tool_call_id").stringValue()).isEqualTo("c");
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("real");
        assertThat(messages(wire).get(3).path("role").stringValue()).isEqualTo("assistant");
        assertThat(messages(wire).get(4).path("role").stringValue()).isEqualTo("user");
        assertThat(messages(wire).get(4).path("content").stringValue()).isEqualTo("Tool result:\nagain");
    }

    @Test
    void aCallThatNothingAnswersBeforeTheLastTurnGetsAnAnswerSayingSo() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("a", "f"), call("b", "g")) + "," + result("b", "got b") + ","
            + user("never mind") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("go") + "," + assistantCalling(call("a", "f"), call("b", "g")) + ","
            + result("a", "Tool result missing") + "," + result("b", "got b") + "," + user("never mind") + "]"));
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void theCallsOfTheLastTurnAreLeftUnansweredAndPartialAnswersStayPartial() {
        Wire none = canonicalize("[" + user("go") + "," + assistantCalling(call("a", "f")) + "]");
        Wire some = canonicalize("[" + user("go") + "," + assistantCalling(call("a", "f"), call("b", "g")) + "," + result("a", "x") + "]");

        assertThat(idsOfResults(messages(none))).isEmpty();
        assertThat(idsOfResults(messages(some))).containsExactly("a");
        assertThat(messages(none)).hasSize(2);
    }

    @Test
    void anUnansweredCallIsFilledWhenOnlyTheAnswersOfAnEarlierGroupComeAfterIt() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("a", "f")) + "," + user("hm") + ","
            + assistantCalling(call("b", "g")) + "," + result("b", "ok") + "]");

        assertThat(idsOfResults(messages(wire))).containsExactly("a", "b");
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("Tool result missing");
    }

    // ---------------------------------------------------------------------------------------- rule 6: ids

    @Test
    void anIdThatIsLegalAndUniqueIsNotTouched() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("toolu_01A-b_C", "f"), call("call_0", "g")) + ","
            + result("toolu_01A-b_C", "1") + "," + result("call_0", "2") + "]");

        assertThat(idsOfCalls(messages(wire))).containsExactly("toolu_01A-b_C", "call_0");
        assertThat(idsOfResults(messages(wire))).containsExactly("toolu_01A-b_C", "call_0");
    }

    @Test
    void aRelayThatAnswersEveryTurnWithCall0GetsDistinctIdsMatchedByPosition() {
        Wire wire = canonicalize("[" + user("1") + "," + assistantCalling(call("call_0", "f")) + "," + result("call_0", "r1") + ","
            + assistantCalling(call("call_0", "f")) + "," + result("call_0", "r2") + ","
            + assistantCalling(call("call_0", "g")) + "," + result("call_0", "r3") + "," + assistant("done") + "]");

        assertThat(idsOfCalls(messages(wire))).containsExactly("call_0", "call_0_2", "call_0_3");
        assertThat(idsOfResults(messages(wire))).containsExactly("call_0", "call_0_2", "call_0_3");
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("r1");
        assertThat(messages(wire).get(4).path("content").stringValue()).isEqualTo("r2");
        assertThat(messages(wire).get(6).path("content").stringValue()).isEqualTo("r3");
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    @Test
    void aRepeatedIdWhoseFirstCallWasNeverAnsweredPairsTheLaterAnswerWithTheNearestCall() {
        Wire wire = canonicalize("[" + user("1") + "," + assistantCalling(call("call_0", "f")) + "," + user("skip") + ","
            + assistantCalling(call("call_0", "f")) + "," + result("call_0", "only") + "," + assistant("done") + "]");

        assertThat(idsOfCalls(messages(wire))).containsExactly("call_0", "call_0_2");
        assertThat(idsOfResults(messages(wire))).containsExactly("call_0", "call_0_2");
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("Tool result missing");
        assertThat(messages(wire).get(5).path("content").stringValue()).isEqualTo("only");
    }

    @Test
    void theSameIdTwiceInOneAssistantMessageIsPairedInOrder() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("x", "f"), call("x", "g")) + ","
            + result("x", "first") + "," + result("x", "second") + "]");

        assertThat(idsOfCalls(messages(wire))).containsExactly("x", "x_2");
        assertThat(messages(wire).get(2).path("tool_call_id").stringValue()).isEqualTo("x");
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("first");
        assertThat(messages(wire).get(3).path("tool_call_id").stringValue()).isEqualTo("x_2");
        assertThat(messages(wire).get(3).path("content").stringValue()).isEqualTo("second");
    }

    private static String signedCall(String id, String name, String signature) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"{}\"},"
            + "\"extra_content\":{\"google\":{\"thought_signature\":\"" + signature + "\"}}}";
    }

    /** A call keeps the signature of its model wherever the rules put it: answers moved after it, ids renamed, messages merged. */
    @Test
    void aCallKeepsItsThoughtSignatureThroughEveryRearrangement() {
        Wire wire = canonicalize("[" + user("go") + "," + assistant("thinking") + ","
            + assistantCalling(signedCall("call_0", "f", "SIG-1"), call("b", "g")) + ","
            + result("b", "second") + "," + user("interjection") + "," + result("call_0", "first") + ","
            + assistantCalling(signedCall("call_0", "f", "SIG-2")) + "," + result("call_0", "third") + "]");

        List<String> signatures = new ArrayList<>();
        for (JsonNode message : messages(wire)) {
            for (JsonNode call : message.path("tool_calls")) {
                signatures.add(call.path("extra_content").path("google").path("thought_signature").stringValue(""));
            }
        }
        assertThat(idsOfCalls(messages(wire))).containsExactly("call_0", "b", "call_0_2");
        assertThat(idsOfResults(messages(wire))).containsExactly("call_0", "b", "call_0_2");
        assertThat(signatures).containsExactly("SIG-1", "", "SIG-2");
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        assertThat(ChatCanonicalizer.canonicalize(wire, CLAUDE).body()).isEqualTo(wire.body());
    }

    /** The calls made text have no function left to give the signature to. */
    @Test
    void aFlattenedHistoryHasNoSignature() {
        Wire wire = canonicalizeWithoutTools("[" + user("go") + "," + assistantCalling(signedCall("a", "f", "SIG-1")) + ","
            + result("a", "r") + "," + user("again") + "]");

        assertThat(wire.degraded()).contains(ChatCanonicalizer.TOOL_HISTORY_FLATTENED);
        assertThat(wire.body().toString()).doesNotContain("extra_content", "SIG-1", "tool_calls");
    }

    @Test
    void charactersOutsideTheAllowedSetBecomeUnderscoresAndAnEmptyIdIsCall() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("toolu.01:A b", "f"), call("", "g"), call("é😀", "h")) + ","
            + result("toolu.01:A b", "1") + "," + result("", "2") + "," + result("é😀", "3") + "]");

        assertThat(idsOfCalls(messages(wire))).containsExactly("toolu_01_A_b", "call", "__");
        assertThat(idsOfResults(messages(wire))).containsExactly("toolu_01_A_b", "call", "__");
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    @Test
    void idsThatBecomeEqualAreMadeDistinct() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("a.b", "f"), call("a:b", "f"), call("a_b", "f")) + ","
            + result("a.b", "1") + "," + result("a:b", "2") + "," + result("a_b", "3") + "]");

        assertThat(idsOfCalls(messages(wire))).containsExactly("a_b", "a_b_2", "a_b_3");
        assertThat(idsOfResults(messages(wire))).containsExactly("a_b", "a_b_2", "a_b_3");
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("1");
        assertThat(messages(wire).get(3).path("content").stringValue()).isEqualTo("2");
        assertThat(messages(wire).get(4).path("content").stringValue()).isEqualTo("3");
    }

    @Test
    void aLongIdIsCutToSixtyFourAndASuffixStillFits() {
        String id = "x".repeat(80);
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call(id, "f"), call(id, "g")) + ","
            + result(id, "1") + "," + result(id, "2") + "]");

        List<String> ids = idsOfCalls(messages(wire));
        assertThat(ids.get(0)).isEqualTo("x".repeat(64));
        // The suffix goes after 57 characters, however many digits it has (see the unique ids of the last section).
        assertThat(ids.get(1)).isEqualTo("x".repeat(57) + "_2");
        assertThat(ids).allMatch(value -> ChatCanonicalizer.LEGAL_ID.matcher(value).matches());
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
    }

    @Test
    void aNaturalIdThatFollowsARenamedOneIsRenamedInsteadOfCollidingWithIt() {
        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(call("c", "f"), call("c", "f"), call("c_2", "f")) + ","
            + result("c", "1") + "," + result("c", "2") + "," + result("c_2", "3") + "]");

        List<String> ids = idsOfCalls(messages(wire));
        assertThat(ids).containsExactly("c", "c_2", "c_2_2");
        assertThat(idsOfResults(messages(wire))).containsExactly("c", "c_2", "c_2_2");
        assertThat(messages(wire).get(4).path("content").stringValue()).isEqualTo("3");
    }

    // ---------------------------------------------------------------------------------------- what is left to the lint

    @Test
    void aMessageWithAnUnknownRoleIsLeftAsItIsForTheLintToRefuse() {
        Wire wire = canonicalize("[" + user("a") + ",{\"role\":\"function\",\"content\":\"x\"}]");

        assertThat(messages(wire).get(1).path("role").stringValue()).isEqualTo("function");
        assertThat(StrictChatLint.passes(wire.body())).isFalse();
    }

    @Test
    void aBodyWithoutMessagesComesOutWithNone() {
        ObjectNode body = (ObjectNode) tree("{\"model\":\"m\"}");

        Wire wire = ChatCanonicalizer.canonicalize(new Wire(body, List.of()), CLAUDE);

        assertThat(messages(wire)).isEmpty();
        assertThat(wire.body().has("model")).isTrue();
    }

    @Test
    void theDegradedNamesOfTheSanitizerAreKeptAndTheNewOnesAreAddedOnce() {
        Wire wire = canonicalize("[" + user("") + "," + assistant("a") + "," + user("") + "," + assistantCalling(call("c", "f")) + "," + result("c", "") + ","
            + assistantCalling(call("d", "f")) + "," + result("d", "") + "," + assistant("z") + "]", "image", "cache_control");

        assertThat(wire.degraded()).containsExactly("image", "cache_control", "empty_user_message", "empty_tool_result");
    }

    @Test
    void theSetOfRuleNamesIsWhatThePolicyTalksAbout() {
        assertThat(Set.of(ChatCanonicalizer.EMPTY_TOOL_RESULT, ChatCanonicalizer.EMPTY_USER_MESSAGE, ChatCanonicalizer.TOOL_HISTORY_FLATTENED))
            .containsExactlyInAnyOrder("empty_tool_result", "empty_user_message", "tool_history_flattened");
    }

    // ---------------------------------------------------------------------------------------- a body without tools

    /** A client that resends the conversation to have it summarized, with its tools taken out: the shape Anthropic and Bedrock refuse. */
    private static final String SUMMARY_HISTORY = "[" + user("list the files") + ","
        + "{\"role\":\"assistant\",\"content\":\"Looking.\",\"tool_calls\":[{\"id\":\"toolu_01AAA\",\"type\":\"function\","
        + "\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]},"
        + result("toolu_01AAA", "a.txt") + "," + assistant("One file.") + "," + user("Write a summary") + "]";

    @Test
    void aHistoryWithToolCallsInABodyWithoutToolsBecomesText() {
        Wire wire = canonicalizeWithoutTools(SUMMARY_HISTORY);

        assertThat(messages(wire)).isEqualTo(tree("[" + user("list the files") + ","
            + assistant("Looking.\\n\\nTool use: Bash\\nInput: {\\\"command\\\":\\\"ls\\\"}") + ","
            + user("Tool result:\\na.txt") + "," + assistant("One file.") + "," + user("Write a summary") + "]"));
        assertThat(wire.degraded()).containsExactly("tool_history_flattened");
        assertThat(wire.body().has("tools")).isFalse();
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    @Test
    void theSameHistoryWithAToolInTheBodyKeepsItsCalls() {
        Wire wire = canonicalize(SUMMARY_HISTORY);

        assertThat(idsOfCalls(messages(wire))).containsExactly("toolu_01AAA");
        assertThat(idsOfResults(messages(wire))).containsExactly("toolu_01AAA");
        assertThat(wire.degraded()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"tools\":[]", "\"tools\":null", "\"tools\":\"none\""})
    void aBodyWhoseToolsAreEmptyOrNotAListDeclaresNone(String tools) {
        ObjectNode body = (ObjectNode) tree("{" + tools + ",\"messages\":" + SUMMARY_HISTORY + "}");

        Wire wire = ChatCanonicalizer.canonicalize(new Wire(body, List.of()), CLAUDE);

        assertThat(idsOfCalls(messages(wire))).isEmpty();
        assertThat(wire.degraded()).containsExactly("tool_history_flattened");
    }

    @Test
    void callsWithoutArgumentsAreTextWithoutAnInputLineAndSeveralCallsOfOneTurnAreOneBlock() {
        Wire wire = canonicalizeWithoutTools("[" + user("go") + ","
            + assistantCalling(call("a", "Read"), "{\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"Idle\",\"arguments\":\"\"}}",
            "{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"Edit\",\"arguments\":\"{\\\"line\\\":3}\"}}") + ","
            + result("a", "1") + "," + result("b", "") + "," + result("c", "3") + "," + user("and?") + "]");

        assertThat(messages(wire)).hasSize(3);
        assertThat(messages(wire).get(1).path("role").stringValue()).isEqualTo("assistant");
        assertThat(messages(wire).get(1).path("content").stringValue())
            .isEqualTo("Tool use: Read\n\nTool use: Idle\n\nTool use: Edit\nInput: {\"line\":3}");
        assertThat(messages(wire).get(2).path("role").stringValue()).isEqualTo("user");
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("Tool result:\n1\n\nTool result:\n\nTool result:\n3\n\nand?");
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    @Test
    void theTextOfTheTurnThatWasPartsKeepsItsPartsAndTheCallsAreAnotherTextPart() {
        Wire wire = canonicalizeWithoutTools("[" + user("go") + ",{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hm\"}],"
            + "\"tool_calls\":[" + call("a", "Read") + "]}," + result("a", "x") + "]");

        assertThat(messages(wire).get(1).path("content")).isEqualTo(tree("[{\"type\":\"text\",\"text\":\"hm\"},"
            + "{\"type\":\"text\",\"text\":\"Tool use: Read\"}]"));
    }

    @Test
    void aBodyWithoutToolsAndWithoutToolCallsIsNotRecordedAsFlattened() {
        Wire wire = canonicalizeWithoutTools("[" + user("a") + "," + assistant("b") + "," + result("zzz", "orphan") + "]");

        assertThat(wire.degraded()).isEmpty();
        assertThat(messages(wire).get(messages(wire).size() - 1).path("content").stringValue()).startsWith("Tool result:");
    }

    /** The member decides: a history that the upstream takes as it is is not made text, and no repair is recorded for it. */
    @Test
    void aMemberWhoseUpstreamTakesTheHistoryAsItIsGetsItAsItIsAndNothingIsRecorded() {
        ObjectNode body = (ObjectNode) tree("{\"messages\":" + SUMMARY_HISTORY + "}");

        Wire wire = ChatCanonicalizer.canonicalize(new Wire(body, List.of()), GPT);

        assertThat(idsOfCalls(messages(wire))).containsExactly("toolu_01AAA");
        assertThat(idsOfResults(messages(wire))).containsExactly("toolu_01AAA");
        assertThat(wire.degraded()).isEmpty();
        assertThat(wire.body().has("tools")).isFalse();
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        assertThat(ChatCanonicalizer.canonicalize(wire, GPT).body()).isEqualTo(wire.body());
        // The other rules apply to it all the same: a repeated id is made unique and its answer follows its call.
        Wire repeated = ChatCanonicalizer.canonicalize(new Wire((ObjectNode) tree("{\"messages\":[" + user("go") + ","
            + assistantCalling(call("x", "f")) + "," + result("x", "1") + "," + assistantCalling(call("x", "f")) + ","
            + result("x", "2") + "," + user("summarize") + "]}"), List.of()), GPT);
        assertThat(idsOfCalls(messages(repeated))).containsExactly("x", "x_2");
        assertThat(idsOfResults(messages(repeated))).containsExactly("x", "x_2");
        assertThat(repeated.degraded()).isEmpty();
    }

    /**
     * Flattening the calls before they were paired gave each result the place it had been written in and lost the call it
     * answered: the results of parallel calls that a client wrote in another order were read against the wrong calls.
     */
    @Test
    void resultsOfParallelCallsInAnotherOrderThanTheCallsAreReadAgainstTheirOwnCallsInTheFlattenedHistory() {
        Wire wire = canonicalizeWithoutTools("[" + user("look around") + ","
            + assistantCalling(callWith("call_a", "shell", "{\\\"command\\\":\\\"ls\\\"}"), callWith("call_b", "shell", "{\\\"command\\\":\\\"pwd\\\"}")) + ","
            + result("call_b", "/w") + "," + result("call_a", "a.txt") + "," + user("summarize") + "]");

        assertThat(messages(wire)).isEqualTo(tree("[" + user("look around") + ","
            + assistant("Tool use: shell\\nInput: {\\\"command\\\":\\\"ls\\\"}\\n\\nTool use: shell\\nInput: {\\\"command\\\":\\\"pwd\\\"}") + ","
            + user("Tool result:\\na.txt\\n\\nTool result:\\n/w\\n\\nsummarize") + "]"));
        assertThat(wire.degraded()).containsExactly("tool_history_flattened");
        // The same history with a tool declared puts the same results in the same order, as calls and answers.
        Wire withTools = canonicalize("[" + user("look around") + ","
            + assistantCalling(call("call_a", "shell"), call("call_b", "shell")) + ","
            + result("call_b", "/w") + "," + result("call_a", "a.txt") + "," + user("summarize") + "]");
        assertThat(idsOfResults(messages(withTools))).containsExactly("call_a", "call_b");
        assertThat(messages(withTools).get(2).path("content").stringValue()).isEqualTo("a.txt");
        assertThat(messages(withTools).get(3).path("content").stringValue()).isEqualTo("/w");
    }

    /** A call of an earlier turn that was never answered has its missing result in the text as well. */
    @Test
    void aCallOfAnEarlierTurnThatWasNeverAnsweredHasItsMissingResultInTheFlattenedHistoryToo() {
        Wire wire = canonicalizeWithoutTools("[" + user("go") + "," + assistantCalling(call("a", "Read"), call("b", "Edit")) + ","
            + result("b", "edited") + "," + user("and?") + "]");

        assertThat(messages(wire).get(1).path("content").stringValue()).isEqualTo("Tool use: Read\n\nTool use: Edit");
        assertThat(messages(wire).get(2).path("content").stringValue())
            .isEqualTo("Tool result:\nTool result missing\n\nTool result:\nedited\n\nand?");
        assertThat(wire.degraded()).containsExactly("tool_history_flattened");
    }

    @Test
    void aFlattenedHistoryIsLeftAloneWhenCanonicalizedAgain() {
        Wire once = canonicalizeWithoutTools(SUMMARY_HISTORY);

        Wire twice = ChatCanonicalizer.canonicalize(once, CLAUDE);

        assertThat(twice.body()).isEqualTo(once.body());
        assertThat(twice.degraded()).isEqualTo(once.degraded());
    }

    // ---------------------------------------------------------------------------------------- size

    private static final Duration PLENTY = Duration.ofSeconds(30);

    private static ObjectNode withMessages(ArrayNode messages) {
        ObjectNode body = JSON.createObjectNode();
        body.set("tools", tree(TOOLS));
        body.set("messages", messages);
        return body;
    }

    @Test
    void aVeryLongRunOfUserMessagesIsJoinedInOneLinearPass() {
        ArrayNode messages = JSON.createArrayNode();
        for (int i = 0; i < 100_000; i++) {
            messages.addObject().put("role", "user").put("content", "message number " + i);
        }

        Wire wire = assertTimeoutPreemptively(PLENTY, () -> ChatCanonicalizer.canonicalize(new Wire(withMessages(messages), List.of()), CLAUDE));

        assertThat(messages(wire)).hasSize(1);
        assertThat(messages(wire).get(0).path("content").stringValue()).startsWith("message number 0\n\nmessage number 1\n\n").endsWith("message number 99999");
    }

    @Test
    void thousandsOfCallsWithTheSameIdAreRenamedAndPairedInOneLinearPass() {
        int calls = 40_000;
        ArrayNode messages = JSON.createArrayNode();
        messages.addObject().put("role", "user").put("content", "go");
        ObjectNode assistant = messages.addObject().put("role", "assistant");
        assistant.putNull("content");
        ArrayNode toolCalls = assistant.putArray("tool_calls");
        for (int i = 0; i < calls; i++) {
            ObjectNode call = toolCalls.addObject().put("id", "call_0").put("type", "function");
            call.putObject("function").put("name", "f").put("arguments", "{}");
        }
        for (int i = 0; i < calls; i++) {
            messages.addObject().put("role", "tool").put("tool_call_id", "call_0").put("content", "result " + i);
        }

        Wire wire = assertTimeoutPreemptively(PLENTY, () -> ChatCanonicalizer.canonicalize(new Wire(withMessages(messages), List.of()), CLAUDE));

        assertThat(messages(wire)).hasSize(calls + 2);
        assertThat(new HashSet<>(idsOfCalls(messages(wire)))).hasSize(calls);
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("result 0");
        assertThat(messages(wire).get(calls + 1).path("content").stringValue()).isEqualTo("result " + (calls - 1));
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** 64 characters, the first 61 of them the same for every number up to 238,327. */
    private static String idSharingItsStart(int number) {
        return "x".repeat(61) + ALPHABET.charAt(number / 3844) + ALPHABET.charAt(number / 62 % 62) + ALPHABET.charAt(number % 62);
    }

    /**
     * Ids that begin alike, each used by two turns. The suffix goes after the same 57 characters for all of them, so one
     * count serves them all; with a count for each id, the second use of every one would walk past every name the others
     * had taken, and the time would grow with the square of the number of ids (50,000 of them took half a minute, and
     * 8.5 MB of a request is room for more).
     */
    @Test
    void manyDistinctIdsThatShareTheirFirstCharactersAreRenamedInOneLinearPass() {
        int ids = 50_000;
        ArrayNode messages = JSON.createArrayNode();
        messages.addObject().put("role", "user").put("content", "go");
        for (int turn = 0; turn < 2; turn++) {
            ObjectNode assistant = messages.addObject().put("role", "assistant");
            assistant.putNull("content");
            ArrayNode toolCalls = assistant.putArray("tool_calls");
            for (int i = 0; i < ids; i++) {
                ObjectNode call = toolCalls.addObject().put("id", idSharingItsStart(i)).put("type", "function");
                call.putObject("function").put("name", "f").put("arguments", "{}");
            }
            for (int i = 0; i < ids; i++) {
                messages.addObject().put("role", "tool").put("tool_call_id", idSharingItsStart(i)).put("content", "result " + turn + " " + i);
            }
        }
        messages.addObject().put("role", "user").put("content", "done");

        Wire wire = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> ChatCanonicalizer.canonicalize(new Wire(withMessages(messages), List.of()), CLAUDE));

        List<String> renamed = idsOfCalls(messages(wire));
        assertThat(renamed).hasSize(2 * ids);
        assertThat(new HashSet<>(renamed)).hasSameSizeAs(renamed);
        assertThat(renamed).allMatch(value -> ChatCanonicalizer.LEGAL_ID.matcher(value).matches());
        assertThat(idsOfResults(messages(wire))).containsExactlyElementsOf(renamed);
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    /** Ids the client wrote that are the next names of the count are stepped over, once, and are not renamed themselves. */
    @Test
    void idsTheClientWroteThatLookLikeRenamedOnesAreSteppedOver() {
        String stem = "y".repeat(57);

        Wire wire = canonicalize("[" + user("go") + "," + assistantCalling(
            call(stem + "_2", "f"), call(stem + "_3", "f"), call(stem + "AAA", "f"), call(stem + "AAA", "f"), call(stem + "AAA", "f"))
            + "]");

        assertThat(idsOfCalls(messages(wire))).containsExactly(stem + "_2", stem + "_3", stem + "AAA", stem + "_4", stem + "_5");
    }

    @Test
    void thousandsOfCallsAndOfAnswersThatNeverMatchArePairedInOneLinearPass() {
        int count = 40_000;
        ArrayNode messages = JSON.createArrayNode();
        messages.addObject().put("role", "user").put("content", "go");
        for (int i = 0; i < count; i++) {
            ObjectNode assistant = messages.addObject().put("role", "assistant");
            assistant.putNull("content");
            ObjectNode call = assistant.putArray("tool_calls").addObject().put("id", "call_" + i).put("type", "function");
            call.putObject("function").put("name", "f").put("arguments", "{}");
        }
        for (int i = 0; i < count; i++) {
            messages.addObject().put("role", "tool").put("tool_call_id", "other_" + i).put("content", "orphan " + i);
        }

        Wire wire = assertTimeoutPreemptively(PLENTY, () -> ChatCanonicalizer.canonicalize(new Wire(withMessages(messages), List.of()), CLAUDE));

        // The answers that match nothing become one user message after every call, so no call is in the last turn.
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        assertThat(idsOfResults(messages(wire)).size()).isEqualTo(count);
        assertThat(messages(wire).get(2).path("content").stringValue()).isEqualTo("Tool result missing");
        assertThat(messages(wire).get(messages(wire).size() - 1).path("content").stringValue()).startsWith("Tool result:\norphan 0\n\n");
    }
}
