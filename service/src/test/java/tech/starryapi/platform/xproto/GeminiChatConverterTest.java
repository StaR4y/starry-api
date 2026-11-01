package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformCompletionResult.UsageSource;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Design 7.6, GEMINI: what the conversion of a {@code generateContent} request to Chat does that the entry's own conversion
 * ({@code GeminiRequestAdapter}) does not, as the class description lists it, and the acceptance of the task: the body passes
 * the sanitizer, the canonicalizer and the lint, for the request of a real client and for any history a seeded random run
 * can make, and the thought signature of a call goes out and comes back.
 */
class GeminiChatConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GeminiChatConverter CONVERTER = new GeminiChatConverter(JSON);

    /** A Gemini model behind the relay: it signs its calls, and its upstream wants the tools declared for a tool history. */
    private static final Settings GEMINI = new Settings(true, true, false, false, false, true, false, false, false, true, true);
    /** A Claude model behind the relay, asked for through the Gemini endpoint: nothing is signed. */
    private static final Settings CLAUDE = new Settings(false, false, false, false, false, true, false, false, true, true, false);

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static ObjectNode convert(String request) {
        return CONVERTER.toChat("gemini-2.5-pro", tree(request));
    }

    private static List<String> roles(JsonNode chat) {
        List<String> roles = new ArrayList<>();
        chat.path("messages").forEach(message -> roles.add(message.path("role").stringValue()));
        return roles;
    }

    private static List<String> callIds(JsonNode message) {
        List<String> ids = new ArrayList<>();
        message.path("tool_calls").forEach(call -> ids.add(call.path("id").stringValue()));
        return ids;
    }

    private static List<String> answeredIds(JsonNode chat) {
        List<String> ids = new ArrayList<>();
        chat.path("messages").forEach(message -> {
            if ("tool".equals(message.path("role").stringValue())) {
                ids.add(message.path("tool_call_id").stringValue());
            }
        });
        return ids;
    }

    private static final String USER_HI = "{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}";

    private static String request(String contents, String rest) {
        return "{\"contents\":[" + contents + "]" + (rest.isEmpty() ? "" : "," + rest) + "}";
    }

    private static String modelCalling(String... parts) {
        return "{\"role\":\"model\",\"parts\":[" + String.join(",", parts) + "]}";
    }

    private static String userAnswering(String... parts) {
        return "{\"role\":\"user\",\"parts\":[" + String.join(",", parts) + "]}";
    }

    private static String call(String name, String args) {
        return "{\"functionCall\":{\"name\":\"" + name + "\",\"args\":" + args + "}}";
    }

    private static String callWithId(String id, String name, String args) {
        return "{\"functionCall\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"args\":" + args + "}}";
    }

    private static String answer(String name, String response) {
        return "{\"functionResponse\":{\"name\":\"" + name + "\",\"response\":" + response + "}}";
    }

    private static String answerWithId(String id, String name, String response) {
        return "{\"functionResponse\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"response\":" + response + "}}";
    }

    // ---------------------------------------------------------------------------------------- the calls of a history

    /** The reason the converter exists: the entry's conversion gave both reads the id {@code read_file}. */
    @Test
    void twoCallsOfOneFunctionAreTwoCallsAndEachAnswerIsPairedWithTheOlderUnansweredOne() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(call("read_file", "{\"path\":\"a\"}"), call("read_file", "{\"path\":\"b\"}")),
            userAnswering(answer("read_file", "{\"output\":\"AAA\"}"), answer("read_file", "{\"output\":\"BBB\"}"))), ""));

        assertThat(roles(chat)).containsExactly("user", "assistant", "tool", "tool");
        assertThat(callIds(chat.path("messages").get(1))).containsExactly("call_1", "call_2");
        assertThat(answeredIds(chat)).containsExactly("call_1", "call_2");
        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("{\"output\":\"AAA\"}");
        assertThat(chat.path("messages").get(3).path("content").stringValue()).isEqualTo("{\"output\":\"BBB\"}");
        JsonNode first = chat.path("messages").get(1).path("tool_calls").get(0);
        assertThat(first.propertyNames()).containsExactly("id", "type", "function");
        assertThat(first.path("function").path("arguments").stringValue()).isEqualTo("{\"path\":\"a\"}");
    }

    @Test
    void theSameFunctionCalledAgainInALaterTurnIsAnotherCallWithAnotherId() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(call("read_file", "{\"path\":\"a\"}")), userAnswering(answer("read_file", "{\"output\":\"AAA\"}")),
            modelCalling(call("read_file", "{\"path\":\"b\"}")), userAnswering(answer("read_file", "{\"output\":\"BBB\"}"))), ""));

        assertThat(roles(chat)).containsExactly("user", "assistant", "tool", "assistant", "tool");
        assertThat(callIds(chat.path("messages").get(1))).containsExactly("call_1");
        assertThat(callIds(chat.path("messages").get(3))).containsExactly("call_2");
        assertThat(answeredIds(chat)).containsExactly("call_1", "call_2");
    }

    @Test
    void anAnswerIsPairedWithTheCallOfItsFunctionWhateverTheOrderOfTheAnswers() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(call("list", "{}"), call("read_file", "{}"), call("list", "{}")),
            userAnswering(answer("read_file", "{}"), answer("list", "{\"n\":1}"), answer("list", "{\"n\":2}"))), ""));

        assertThat(callIds(chat.path("messages").get(1))).containsExactly("call_1", "call_2", "call_3");
        assertThat(answeredIds(chat)).containsExactly("call_2", "call_1", "call_3");
    }

    @Test
    void aCallIsKnownByTheIdTheClientWroteAndAnAnswerThatSaysItIsPairedByIt() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(callWithId("fc-a", "read_file", "{}"), callWithId("fc-b", "read_file", "{}")),
            userAnswering(answerWithId("fc-b", "read_file", "{\"o\":\"B\"}"), answerWithId("fc-a", "read_file", "{\"o\":\"A\"}"))), ""));

        assertThat(callIds(chat.path("messages").get(1))).containsExactly("fc-a", "fc-b");
        assertThat(answeredIds(chat)).containsExactly("fc-b", "fc-a");
        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("{\"o\":\"B\"}");
    }

    @Test
    void anIdThatIsUsedAlreadyOrIsBlankIsNotKeptAndAnIdOfOursIsNeverAClientsAlso() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(callWithId("x", "f", "{}"), callWithId("x", "g", "{}"), callWithId(" ", "h", "{}"), callWithId("call_1", "i", "{}"),
                callWithId("call_1", "j", "{}")),
            userAnswering(answer("f", "{}"), answer("g", "{}"), answer("h", "{}"), answer("i", "{}"), answer("j", "{}"))), ""));

        // The second x and the blank id are ours, call_1 and call_2; the client's own call_1 comes after one of ours and so is not kept.
        List<String> ids = callIds(chat.path("messages").get(1));
        assertThat(ids).containsExactly("x", "call_1", "call_2", "call_3", "call_4");
        assertThat(answeredIds(chat)).isEqualTo(ids);
    }

    @Test
    void anAnswerWithAnIdOfACallThatIsAnsweredAlreadyIsPairedByTheNameOfItsFunction() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(callWithId("same", "f", "{}")), userAnswering(answerWithId("same", "f", "{}")),
            modelCalling(callWithId("same", "f", "{}")), userAnswering(answerWithId("same", "f", "{}"))), ""));

        assertThat(callIds(chat.path("messages").get(1))).containsExactly("same");
        assertThat(callIds(chat.path("messages").get(3))).hasSize(1).doesNotContain("same");
        assertThat(answeredIds(chat)).containsExactly("same", callIds(chat.path("messages").get(3)).get(0));
    }

    /** Parallel calls that a client numbered with one id: an answer is paired with the call of its function, not with whichever has the id. */
    @Test
    void anAnswerWithAnIdThatTwoCallsWereWrittenWithIsPairedWithTheCallOfItsOwnFunction() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(callWithId("x", "f", "{}"), callWithId("x", "g", "{}")),
            userAnswering(answerWithId("x", "g", "{\"o\":\"of g\"}"), answerWithId("x", "f", "{\"o\":\"of f\"}"))), ""));

        List<String> ids = callIds(chat.path("messages").get(1));
        assertThat(ids).containsExactly("x", "call_1");
        assertThat(answeredIds(chat)).containsExactly("call_1", "x");
        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("{\"o\":\"of g\"}");
        assertThat(chat.path("messages").get(3).path("content").stringValue()).isEqualTo("{\"o\":\"of f\"}");
        assertThat(roles(chat)).containsExactly("user", "assistant", "tool", "tool");
    }

    /** The id of a client is not the id of ours, whatever number it ends in: the call numbered call_1 by this class is not the one a client calls so. */
    @Test
    void anIdOfOursThatAClientAlsoWroteForAnotherCallIsNotTheCallsOfTheClient() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(call("f", "{}"), callWithId("call_1", "g", "{}")),
            userAnswering(answerWithId("call_1", "g", "{\"o\":\"of g\"}"), answer("f", "{\"o\":\"of f\"}"))), ""));

        assertThat(callIds(chat.path("messages").get(1))).containsExactly("call_1", "call_2");
        assertThat(answeredIds(chat)).containsExactly("call_2", "call_1");
        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("{\"o\":\"of g\"}");
        assertThat(chat.path("messages").get(3).path("content").stringValue()).isEqualTo("{\"o\":\"of f\"}");
    }

    @Test
    void anAnswerThatNamesNoFunctionAnswersTheOldestCallThatIsNotAnswered() {
        String nameless = "{\"functionResponse\":{\"response\":{\"o\":1}}}";

        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(call("a", "{}"), call("b", "{}")), userAnswering(answer("b", "{}"), nameless)), ""));

        assertThat(answeredIds(chat)).containsExactly("call_2", "call_1");
    }

    @Test
    void anAnswerToNothingIsAToolMessageThatNoCallHasAndTheCanonicalizerTellsItAsText() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(call("f", "{}")), userAnswering(answer("f", "{}"), answer("f", "{\"again\":true}"), answer("other", "{}"))),
            "\"tools\":[{\"functionDeclarations\":[{\"name\":\"f\"}]}]"));

        assertThat(answeredIds(chat)).hasSize(3).doesNotHaveDuplicates();
        assertThat(answeredIds(chat).get(0)).isEqualTo("call_1");
        assertThat(answeredIds(chat).subList(1, 3)).doesNotContain("call_1");
        Wire wire = ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, null, GEMINI, "relay"), GEMINI);
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        assertThat(wire.body().toString()).contains("Tool result:");
    }

    @Test
    void theSameHistoryIsTheSameBodyEveryTime() {
        String history = request(String.join(",", USER_HI,
            modelCalling(call("f", "{}"), call("f", "{}")), userAnswering(answer("f", "{}"), answer("f", "{}"))), "");

        assertThat(convert(history)).isEqualTo(convert(history));
    }

    @Test
    void aTurnOfTheModelIsTheTextOfItsPartsAndTheCallsTheyMake() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling("{\"text\":\"Let me look.\"}", call("read_file", "{}"), "{\"text\":\"One moment.\"}")), ""));

        JsonNode assistant = chat.path("messages").get(1);
        assertThat(assistant.path("content").stringValue()).isEqualTo("Let me look.\nOne moment.");
        assertThat(callIds(assistant)).containsExactly("call_1");
    }

    @Test
    void aCallWithoutArgumentsHasAnEmptyObjectAndArgumentsAreTheTextOfTheObject() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling("{\"functionCall\":{\"name\":\"a\"}}", call("b", "{\"x\":[1,{\"y\":null}]}"))), ""));

        JsonNode calls = chat.path("messages").get(1).path("tool_calls");
        assertThat(calls.get(0).path("function").path("arguments").stringValue()).isEqualTo("{}");
        assertThat(calls.get(1).path("function").path("arguments").stringValue()).isEqualTo("{\"x\":[1,{\"y\":null}]}");
    }

    @Test
    void anAnswerHoldsTheTextOfItsResponseAndNothingWhenThereIsNone() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(call("a", "{}"), call("b", "{}")),
            userAnswering(answer("a", "\"plain text\""), "{\"functionResponse\":{\"name\":\"b\"}}")), ""));

        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("\"plain text\"");
        assertThat(chat.path("messages").get(3).path("content").stringValue()).isEmpty();
    }

    @Test
    void theTextOfTheUserBesideAnAnswerIsAUserMessageAfterTheAnswers() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling(call("a", "{}")), userAnswering(answer("a", "{}"), "{\"text\":\"and then?\"}")), ""));

        assertThat(roles(chat)).containsExactly("user", "assistant", "tool", "user");
        assertThat(chat.path("messages").get(3).path("content").stringValue()).isEqualTo("and then?");
    }

    @Test
    void aRoleThatIsNotModelIsTheUsersAndATurnOfTheModelWithoutPartsMakesNoMessage() {
        ObjectNode chat = convert(request(String.join(",", "{\"parts\":[{\"text\":\"no role\"}]}",
            "{\"role\":\"function\",\"parts\":[{\"text\":\"older client\"}]}", "{\"role\":\"model\"}",
            "{\"role\":\"model\",\"parts\":[]}", "{\"role\":\"model\",\"parts\":[{\"text\":\" \"}]}",
            "{\"role\":\"model\",\"parts\":[{\"text\":\"thinking\",\"thought\":true}]}"), ""));

        assertThat(roles(chat)).containsExactly("user", "user");
        assertThat(chat.path("messages").get(0).path("content").stringValue()).isEqualTo("no role");
    }

    /**
     * A turn of the user is never left out: when it is the last, the relay would answer the turn before it (or refuse a history
     * that ends with the model), and the native sources are given {@code Continue}. It is an empty user message, which the
     * canonicalizer fills and records.
     */
    @Test
    void aTurnOfTheUserThatHoldsNothingARelayCanBeGivenIsAnEmptyUserMessage() {
        for (String empty : List.of("{\"role\":\"user\"}", "{\"role\":\"user\",\"parts\":null}", "{\"role\":\"user\",\"parts\":[]}",
            "{\"role\":\"user\",\"parts\":[{\"text\":\"   \"}]}", "{\"role\":\"user\",\"parts\":[{\"text\":\"t\",\"thought\":true}]}",
            "{\"role\":\"user\",\"parts\":[{\"videoMetadata\":{\"startOffset\":\"1s\"}}]}")) {
            String history = request(String.join(",", USER_HI, "{\"role\":\"model\",\"parts\":[{\"text\":\"hello\"}]}", empty), "");

            ObjectNode chat = convert(history);
            assertThat(roles(chat)).as(empty).containsExactly("user", "assistant", "user");
            assertThat(chat.path("messages").get(2).path("content").stringValue()).as(empty).isEmpty();

            Wire wire = pipeline(history, GEMINI);
            assertThat(wire.body().path("messages").get(2).path("content").stringValue()).as(empty)
                .isEqualTo(ChatCanonicalizer.NON_TEXT_USER_TURN);
            assertThat(wire.degraded()).as(empty).contains(ChatCanonicalizer.EMPTY_USER_MESSAGE);
            assertThat(StrictChatLint.problems(wire.body())).as(empty).isEmpty();
        }
    }

    @Test
    void aTurnOfTheUserThatHoldsOnlyTheAnswersOfFunctionsIsNotAnEmptyOneToo() {
        ObjectNode chat = convert(request(String.join(",", USER_HI, modelCalling(call("f", "{}")), userAnswering(answer("f", "{}"))), ""));

        assertThat(roles(chat)).containsExactly("user", "assistant", "tool");
    }

    /**
     * A turn of the model that holds only media would otherwise vanish and make one message of the user turns around it, as if the
     * model had answered nothing between them; the message says what it held, as the answer of a function does.
     */
    @Test
    void aTurnOfTheModelThatHoldsOnlyMediaIsToldAsPresent() {
        String image = "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}";
        String audio = "{\"inlineData\":{\"mimeType\":\"audio/wav\",\"data\":\"UklGRg==\"}}";
        String history = request(String.join(",", "{\"role\":\"user\",\"parts\":[{\"text\":\"draw\"}]}",
            "{\"role\":\"model\",\"parts\":[" + image + "," + audio + "]}", "{\"role\":\"user\",\"parts\":[{\"text\":\"nicer\"}]}"), "");

        ObjectNode chat = convert(history);

        assertThat(roles(chat)).containsExactly("user", "assistant", "user");
        assertThat(chat.path("messages").get(1).path("content").stringValue()).isEqualTo("[image omitted]\n[audio omitted]");
        Wire wire = pipeline(history, GEMINI);
        assertThat(wire.body().path("messages")).hasSize(3);
        assertThat(wire.body().path("messages").get(0).path("content").stringValue()).isEqualTo("draw");
        assertThat(wire.body().path("messages").get(2).path("content").stringValue()).isEqualTo("nicer");
        // Text beside the media is what the turn said; the media are not told then.
        ObjectNode withText = convert(request(String.join(",", USER_HI,
            "{\"role\":\"model\",\"parts\":[{\"text\":\"here\"}," + image + "]}"), ""));
        assertThat(withText.path("messages").get(1).path("content").stringValue()).isEqualTo("here");
    }

    /**
     * A call that no answer was paired with is not waiting for the answers that come after the model's next step: a client that
     * went on (the user said "never mind") and the model called the function again is answered by the second call. Native
     * Gemini refuses such a history; one comes from a turn Kiro served, where the gateway repaired the unanswered call.
     */
    @Test
    void anAnswerWithoutAnIdIsPairedWithTheCallOfTheLatestStepAndNotWithAnOlderOneNoOneAnswered() {
        String history = request(String.join(",", USER_HI,
            modelCalling(call("f", "{\"n\":1}")), "{\"role\":\"user\",\"parts\":[{\"text\":\"never mind, do it again\"}]}",
            modelCalling(call("f", "{\"n\":2}")), userAnswering(answer("f", "{\"output\":\"second\"}"))),
            "\"tools\":[{\"functionDeclarations\":[{\"name\":\"f\"}]}]");

        ObjectNode chat = convert(history);

        assertThat(roles(chat)).containsExactly("user", "assistant", "user", "assistant", "tool");
        assertThat(callIds(chat.path("messages").get(1))).containsExactly("call_1");
        assertThat(callIds(chat.path("messages").get(3))).containsExactly("call_2");
        assertThat(answeredIds(chat)).containsExactly("call_2");
        assertThat(chat.path("messages").get(4).path("content").stringValue()).isEqualTo("{\"output\":\"second\"}");
        // The first call stays in the history and the canonicalizer answers it, so that the last call is the one still to be answered.
        Wire wire = pipeline(history, GEMINI);
        List<String> roles = new ArrayList<>();
        wire.body().path("messages").forEach(message -> roles.add(message.path("role").stringValue()));
        assertThat(roles).containsExactly("user", "assistant", "tool", "user", "assistant", "tool");
        assertThat(wire.body().path("messages").get(2).path("content").stringValue()).isEqualTo(ChatCanonicalizer.MISSING_RESULT);
        assertThat(wire.body().path("messages").get(5).path("tool_call_id").stringValue()).isEqualTo("call_2");
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    @Test
    void anOlderCallIsStillAnsweredByItsIdAndStepsOfTheModelThatFollowEachOtherAreOneStep() {
        // By its id, an older call is paired with whatever the step: the client said which call it answers.
        ObjectNode byId = convert(request(String.join(",", USER_HI,
            modelCalling(callWithId("fc-a", "f", "{}")), "{\"role\":\"user\",\"parts\":[{\"text\":\"wait\"}]}",
            modelCalling(call("f", "{}")), userAnswering(answerWithId("fc-a", "f", "{\"o\":\"A\"}"))), ""));
        assertThat(answeredIds(byId)).containsExactly("fc-a");

        // Two turns of the model in a row (a call after a turn of words) are one step: the answers of the user's turn after them
        // are paired in the order the calls were made.
        ObjectNode oneStep = convert(request(String.join(",", USER_HI,
            modelCalling(call("f", "{\"n\":1}")), modelCalling(call("f", "{\"n\":2}")),
            userAnswering(answer("f", "{\"o\":1}"), answer("f", "{\"o\":2}"))), ""));
        assertThat(answeredIds(oneStep)).containsExactly("call_1", "call_2");
    }

    /**
     * A turn that cannot be read is not a turn that is left out: a relay would then answer the turn before it, and be paid for
     * the answer. The conversion fails, which costs the request its relays (the next tier serves it).
     */
    @Test
    void aTurnThatCannotBeReadFailsTheConversionAndIsNotLeftOut() {
        for (String turn : List.of("\"text\"", "7", "{\"role\":\"user\",\"parts\":\"text\"}", "{\"role\":\"user\",\"parts\":[\"text\"]}",
            "{\"role\":\"model\",\"parts\":[null]}")) {
            assertThatThrownBy(() -> convert(request(String.join(",", USER_HI, turn), "")), turn).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> convert("{\"contents\":\"text\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneTurnWhereTheApiHasAListOfThemAndOnePartWhereItHasAListOfPartsAreReadAsSuch() {
        ObjectNode chat = convert("{\"contents\":{\"role\":\"user\",\"parts\":{\"text\":\"hi\"}}}");

        assertThat(roles(chat)).containsExactly("user");
        assertThat(chat.path("messages").get(0).path("content").stringValue()).isEqualTo("hi");
    }

    // ---------------------------------------------------------------------------------------- the thought signature

    @Test
    void theThoughtSignatureOfACallGoesInTheCallAsExtraContent() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling("{\"functionCall\":{\"name\":\"a\",\"args\":{}},\"thoughtSignature\":\"SIG-1\"}", call("b", "{}")),
            userAnswering(answer("a", "{}"), answer("b", "{}"))), ""));

        JsonNode calls = chat.path("messages").get(1).path("tool_calls");
        assertThat(calls.get(0).path("extra_content")).isEqualTo(tree("{\"google\":{\"thought_signature\":\"SIG-1\"}}"));
        assertThat(calls.get(1).has("extra_content")).isFalse();
    }

    @Test
    void theSignaturesOfPartsThatAreNotCallsAndTheSummariesOfThoughtsAreNotCarried() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling("{\"text\":\"Thinking about it.\",\"thought\":true}",
                "{\"text\":\"Answer.\",\"thoughtSignature\":\"SIG-TEXT\"}")), ""));

        assertThat(chat.path("messages").get(1).path("content").stringValue()).isEqualTo("Answer.");
        assertThat(chat.toString()).doesNotContain("SIG-TEXT", "Thinking about it.", "extra_content");
    }

    /** A call that was signed goes out and comes back: the relay's answer is the history of the next request. */
    @Test
    void theSignatureOfACallGoesToAModelThatSignsAndToNoOtherThroughTheWholePipeline() {
        String history = request(String.join(",", USER_HI,
            modelCalling("{\"functionCall\":{\"name\":\"a\",\"args\":{}},\"thoughtSignature\":\"SIG-1\"}"), userAnswering(answer("a", "{}"))),
            "\"tools\":[{\"functionDeclarations\":[{\"name\":\"a\"}]}]");

        Wire google = pipeline(history, GEMINI);
        Wire claude = pipeline(history, CLAUDE);

        assertThat(StrictChatLint.problems(google.body())).isEmpty();
        assertThat(google.body().path("messages").get(1).path("tool_calls").get(0).path("extra_content"))
            .isEqualTo(tree("{\"google\":{\"thought_signature\":\"SIG-1\"}}"));
        assertThat(claude.body().toString()).doesNotContain("extra_content", "SIG-1");
    }

    /**
     * The whole way a signature goes: the relay's answer, read by the accumulator, rendered for the Gemini client, which sends
     * the part back in the history of its next request, converted and sanitized for the same relay.
     */
    @Test
    void aSignatureTheRelayGaveComesBackToItInTheNextRequest() {
        ChatCompletionAccumulator accumulator = new ChatCompletionAccumulator(JSON, error -> null, System::nanoTime,
            IncrementalSseDecoder.DEFAULT_MAX_BYTES);
        accumulator.acceptBytes(("""
            {"choices":[{"message":{"content":null,"tool_calls":[
             {"id":"call_0","type":"function","extra_content":{"google":{"thought_signature":"SIG/opaque+value=="}},
              "function":{"name":"read_file","arguments":"{\\"file_path\\":\\"a.txt\\"}"}},
             {"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\\"file_path\\":\\"b.txt\\"}"}}]},
             "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PlatformCompletionResult result = accumulator.finish()
            .toResult(new PlatformUsage(10, 5, java.math.BigDecimal.ZERO, 0, 0, 0), UsageSource.REPORTED, List.of());

        JsonNode answer = new GeminiResponseFactory(JSON).generateContent("gemini-3-pro", result);
        JsonNode parts = answer.path("candidates").get(0).path("content").path("parts");
        assertThat(parts.get(0).path("thoughtSignature").stringValue()).isEqualTo("SIG/opaque+value==");
        assertThat(parts.get(1).has("thoughtSignature")).isFalse();

        ObjectNode next = JSON.createObjectNode();
        ArrayNode contents = next.putArray("contents");
        contents.add(tree(USER_HI));
        contents.addObject().put("role", "model").set("parts", parts);
        contents.add(tree(userAnswering(answerWithId(parts.get(0).path("functionCall").path("id").stringValue(), "read_file", "{\"output\":\"A\"}"),
            answerWithId(parts.get(1).path("functionCall").path("id").stringValue(), "read_file", "{\"output\":\"B\"}"))));
        next.putArray("tools").addObject().putArray("functionDeclarations").addObject().put("name", "read_file");

        Wire wire = pipeline(next.toString(), GEMINI);

        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        JsonNode calls = wire.body().path("messages").get(1).path("tool_calls");
        assertThat(calls.get(0).path("extra_content").path("google").path("thought_signature").stringValue()).isEqualTo("SIG/opaque+value==");
        assertThat(calls.get(1).has("extra_content")).isFalse();
        assertThat(calls.get(0).path("function").path("arguments").stringValue()).isEqualTo("{\"file_path\":\"a.txt\"}");
        assertThat(answeredIds(wire.body())).isEqualTo(callIds(wire.body().path("messages").get(1)));
    }

    // ---------------------------------------------------------------------------------------- media

    private static final String PNG = "iVBORw0KGgo=";

    @Test
    void anInlineImageOfTheUserIsAnImagePartWithADataUriInTheOrderOfTheParts() {
        ObjectNode chat = convert(request(
            userAnswering("{\"text\":\"what is this?\"}", "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + PNG + "\"}}",
                "{\"text\":\"and this?\"}"), ""));

        JsonNode content = chat.path("messages").get(0).path("content");
        assertThat(content).isEqualTo(tree("[{\"type\":\"text\",\"text\":\"what is this?\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + PNG + "\"}},"
            + "{\"type\":\"text\",\"text\":\"and this?\"}]"));
    }

    @Test
    void aFileImageIsCarriedByItsAddressOnlyWhenARelayCanFetchIt() {
        String[] uris = {"https://example.com/a.png", "http://cdn.example.org:8080/b.jpg?x=1",
            "https://generativelanguage.googleapis.com/v1beta/files/abc123", "gs://bucket/c.png", "files/abc", "https://storage.googleapis.com/b/c.png", ""};
        StringBuilder parts = new StringBuilder();
        for (String uri : uris) {
            parts.append(parts.isEmpty() ? "" : ",").append("{\"fileData\":{\"mimeType\":\"image/png\",\"fileUri\":\"").append(uri).append("\"}}");
        }

        ObjectNode chat = convert(request(userAnswering(parts.toString()), ""));

        List<String> urls = new ArrayList<>();
        chat.path("messages").get(0).path("content").forEach(part -> urls.add(part.path("image_url").path("url").stringValue("")));
        assertThat(urls).containsExactly("https://example.com/a.png", "http://cdn.example.org:8080/b.jpg?x=1", "", "", "", "", "");
    }

    @Test
    void audioAndOtherFilesNameTheirKindAndNeverCarryTheirData() {
        ObjectNode chat = convert(request(userAnswering("{\"text\":\"listen\"}",
            "{\"inlineData\":{\"mimeType\":\"audio/wav\",\"data\":\"AAAAAAAAAAAA\"}}",
            "{\"inlineData\":{\"mimeType\":\"application/pdf\",\"data\":\"JVBERi0x\"}}",
            "{\"fileData\":{\"mimeType\":\"video/mp4\",\"fileUri\":\"https://example.com/a.mp4\"}}",
            "{\"inlineData\":{\"data\":\"QUJD\"}}"), ""));

        JsonNode content = chat.path("messages").get(0).path("content");
        assertThat(content.get(1)).isEqualTo(tree("{\"type\":\"input_audio\",\"input_audio\":{}}"));
        assertThat(content.get(2)).isEqualTo(tree("{\"type\":\"file\",\"file\":{}}"));
        assertThat(content.get(3)).isEqualTo(tree("{\"type\":\"file\",\"file\":{}}"));
        assertThat(content.get(4)).isEqualTo(tree("{\"type\":\"file\",\"file\":{}}"));
        assertThat(chat.toString()).doesNotContain("AAAAAAAAAAAA", "JVBERi0x", "a.mp4", "QUJD");
    }

    @Test
    void theMediaOfTheModelAndOfAnAnswerHaveNoPlaceInTheHistory() {
        ObjectNode chat = convert(request(String.join(",", USER_HI,
            modelCalling("{\"text\":\"Here you are.\"}", "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + PNG + "\"}}",
                call("shot", "{}")),
            userAnswering("{\"functionResponse\":{\"name\":\"shot\",\"response\":{\"ok\":true},\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                + PNG + "\"}}]}}")), ""));

        assertThat(chat.toString()).doesNotContain(PNG, "image_url");
        assertThat(chat.path("messages").get(1).path("content").stringValue()).isEqualTo("Here you are.");
    }

    /** The Python SDK encodes bytes in the URL-safe alphabet; a data URI holds the standard one, which a relay decodes. */
    @Test
    void theDataOfAnImageIsInTheAlphabetOfADataUriWhicheverAlphabetTheClientUsed() {
        ObjectNode urlSafe = convert(request(userAnswering("{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"8PHy8_T19vf4-fr7_P3-__Dx\"}}"), ""));
        ObjectNode standard = convert(request(userAnswering("{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"8PHy8/T19vf4+fr7/P3+//Dx\"}}"), ""));

        String expected = "data:image/png;base64,8PHy8/T19vf4+fr7/P3+//Dx";
        assertThat(urlSafe.path("messages").get(0).path("content").get(0).path("image_url").path("url").stringValue()).isEqualTo(expected);
        assertThat(standard.path("messages").get(0).path("content").get(0).path("image_url").path("url").stringValue()).isEqualTo(expected);
        assertThat(urlSafe.toString()).doesNotContain("-fr7_");
    }

    /**
     * The answer of a function that holds media says they were there, as the Messages conversion says it of the image of a tool
     * result: a tool message holds text, and an answer of only an image would otherwise be the empty one that the canonicalizer
     * makes {@code [no output]} of, as if the tool had returned nothing.
     */
    @Test
    void theMediaOfAnAnswerAreToldAsPresentInTheToolMessage() {
        String image = "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + PNG + "\"}}";
        String audio = "{\"inlineData\":{\"mimeType\":\"audio/wav\",\"data\":\"UklGRg==\"}}";
        String pdf = "{\"fileData\":{\"mimeType\":\"application/pdf\",\"fileUri\":\"https://example.com/a.pdf\"}}";
        ObjectNode chat = convert(request(String.join(",", USER_HI, modelCalling(call("shot", "{}"), call("shot", "{}"), call("shot", "{}")),
            userAnswering("{\"functionResponse\":{\"name\":\"shot\",\"parts\":[" + image + "]}}",
                "{\"functionResponse\":{\"name\":\"shot\",\"response\":{\"ok\":true},\"parts\":[" + image + "," + audio + "," + pdf + "]}}",
                "{\"functionResponse\":{\"name\":\"shot\",\"response\":{}}}")), ""));

        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("[image omitted]");
        assertThat(chat.path("messages").get(3).path("content").stringValue()).isEqualTo("{\"ok\":true}\n[image omitted]\n[audio omitted]\n[file omitted]");
        assertThat(chat.path("messages").get(4).path("content").stringValue()).isEqualTo("{}");
        assertThat(chat.toString()).doesNotContain(PNG, "UklGRg", "a.pdf");
    }

    // ---------------------------------------------------------------------------------------- the rest of the body

    @Test
    void theSystemInstructionIsTheFirstSystemMessageAndAnEmptyOneIsNone() {
        ObjectNode chat = convert(request(USER_HI, "\"systemInstruction\":{\"role\":\"user\",\"parts\":[{\"text\":\"Be brief.\"},{\"text\":\"No lists.\"}]}"));
        ObjectNode asText = convert(request(USER_HI, "\"systemInstruction\":\"Be terse.\""));
        ObjectNode empty = convert(request(USER_HI, "\"systemInstruction\":{\"parts\":[{\"text\":\" \"}]}"));

        assertThat(roles(chat)).containsExactly("system", "user");
        assertThat(chat.path("messages").get(0).path("content").stringValue()).isEqualTo("Be brief.\nNo lists.");
        assertThat(asText.path("messages").get(0).path("content").stringValue()).isEqualTo("Be terse.");
        assertThat(roles(empty)).containsExactly("user");
    }

    @Test
    void theGenerationConfigIsTheSamplingOfTheChatBody() {
        ObjectNode chat = convert(request(USER_HI, """
            "generationConfig":{"temperature":0.2,"topP":0.9,"topK":40,"maxOutputTokens":128,"stopSequences":["END","","STOP"],
             "presencePenalty":0.5,"frequencyPenalty":-0.5,"seed":7,"candidateCount":1,"responseLogprobs":false}"""));

        assertThat(chat.propertyNames()).containsExactly("model", "temperature", "top_p", "max_tokens", "presence_penalty",
            "frequency_penalty", "seed", "stop", "messages");
        assertThat(chat.path("model").stringValue()).isEqualTo("gemini-2.5-pro");
        assertThat(chat.path("max_tokens").intValue()).isEqualTo(128);
        assertThat(chat.path("stop")).isEqualTo(tree("[\"END\",\"STOP\"]"));
        assertThat(chat.path("seed").intValue()).isEqualTo(7);
    }

    @Test
    void theModelIsTheNameOfThePathWithoutModelsAndNoneWhenThereIsNone() {
        assertThat(CONVERTER.toChat("models/gemini-2.5-pro", tree(request(USER_HI, ""))).path("model").stringValue()).isEqualTo("gemini-2.5-pro");
        assertThat(CONVERTER.toChat(null, tree(request(USER_HI, ""))).has("model")).isFalse();
        assertThat(CONVERTER.toChat(" ", tree(request(USER_HI, ""))).has("model")).isFalse();
    }

    @Test
    void theThinkingConfigIsLeftToTheSanitizerWhichReadsItFromTheOriginalBody() {
        String original = request(USER_HI, "\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":8192}}");

        ObjectNode chat = convert(original);

        assertThat(chat.has("reasoning_effort")).isFalse();
        assertThat(ChatWireSanitizer.sanitize(chat, tree(original), GEMINI, null).body().path("reasoning_effort").stringValue()).isEqualTo("medium");
        assertThat(ChatWireSanitizer.sanitize(chat, tree(original), GEMINI, null).degraded()).isEmpty();
        assertThat(ChatWireSanitizer.sanitize(chat, ChatWireSanitizer.entryDigest(tree(original)), GEMINI, null).body().path("reasoning_effort").stringValue())
            .isEqualTo("medium");
    }

    @Test
    void aDynamicThinkingBudgetAsksForNoLevel() {
        String original = request(USER_HI, "\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":-1}}");

        Wire wire = ChatWireSanitizer.sanitize(convert(original), tree(original), GEMINI, null);

        assertThat(wire.body().has("reasoning_effort")).isFalse();
        assertThat(wire.degraded()).isEmpty();
    }

    // ---- the tools ----

    private static final String READ_FILE = "{\"name\":\"read_file\",\"description\":\"Reads a file\","
        + "\"parametersJsonSchema\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"],\"additionalProperties\":false}}";

    @Test
    void aSchemaInJsonSchemaFormIsSentAsTheClientWroteIt() {
        ObjectNode chat = convert(request(USER_HI, "\"tools\":[{\"functionDeclarations\":[" + READ_FILE + "]}]"));

        JsonNode function = chat.path("tools").get(0).path("function");
        assertThat(function.propertyNames()).containsExactly("name", "description", "parameters");
        assertThat(function.path("parameters")).isEqualTo(tree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},"
            + "\"required\":[\"path\"],\"additionalProperties\":false}"));
    }

    @Test
    void theTypesOfGeminisOwnSchemaAreInTheLowerCaseAWholeTreeDeep() {
        ObjectNode chat = convert(request(USER_HI, """
            "tools":[{"functionDeclarations":[{"name":"search","parameters":{"type":"OBJECT","description":"TYPE: kept",
             "properties":{"type":{"type":"STRING","enum":["A","B"]},"tags":{"type":"ARRAY","items":{"type":"STRING"}},
              "opt":{"anyOf":[{"type":"INTEGER"},{"type":"TYPE_UNSPECIFIED"}],"nullable":true},"n":{"type":"NUMBER","minimum":0}},
             "required":["tags"],"propertyOrdering":["tags"]}}]}]"""));

        assertThat(chat.path("tools").get(0).path("function").path("parameters")).isEqualTo(tree("""
            {"type":"object","description":"TYPE: kept",
             "properties":{"type":{"type":"string","enum":["A","B"]},"tags":{"type":"array","items":{"type":"string"}},
              "opt":{"anyOf":[{"type":"integer"},{},{"type":"null"}]},"n":{"type":"number","minimum":0}},
             "required":["tags"]}"""));
    }

    /**
     * The dialect of Gemini's own schema as the SDKs write it: the counts of the API are int64, which its JSON writes as strings
     * ({@code @google/genai} types {@code minItems} as a string); the Python SDK writes the keywords in snake_case; {@code
     * nullable} is the union with null; the order of the properties is the SDK's own.
     */
    @Test
    void theDialectOfGeminisSchemaIsMadeIntoJsonSchema() {
        ObjectNode chat = convert(request(USER_HI, """
            "tools":[{"functionDeclarations":[
             {"name":"a","parameters":{"type":"OBJECT","properties":{
               "list":{"type":"ARRAY","minItems":"1","maxItems":"3","nullable":true,"items":{"type":"STRING","min_length":"2","max_length":4}},
               "text":{"type":"STRING","nullable":true,"minLength":"x"},
               "min_items":{"type":"STRING"}},
              "property_ordering":["list","text"],"min_properties":"1","any_of":[{"type":"OBJECT","nullable":true}]}}]}]"""));

        assertThat(chat.path("tools").get(0).path("function").path("parameters")).isEqualTo(tree("""
            {"type":"object","properties":{
              "list":{"type":["array","null"],"minItems":1,"maxItems":3,"items":{"type":"string","minLength":2,"maxLength":4}},
              "text":{"type":["string","null"],"minLength":"x"},
              "min_items":{"type":"string"}},
             "minProperties":1,"anyOf":[{"type":["object","null"]}]}"""));
    }

    /** The schema in {@code parametersJsonSchema} is the client's JSON schema and is sent as it is, whatever its keys look like. */
    @Test
    void aJsonSchemaIsNotReadAsGeminisDialect() {
        String schema = "{\"type\":\"object\",\"properties\":{\"min_items\":{\"type\":\"array\",\"minItems\":\"1\",\"nullable\":true}}}";
        ObjectNode chat = convert(request(USER_HI, "\"tools\":[{\"functionDeclarations\":[{\"name\":\"a\",\"parametersJsonSchema\":" + schema + "}]}]"));

        assertThat(chat.path("tools").get(0).path("function").path("parameters")).isEqualTo(tree(schema));
    }

    @Test
    void aFunctionWithoutAnObjectSchemaIsAnObjectOverNone() {
        ObjectNode chat = convert(request(USER_HI, """
            "tools":[{"functionDeclarations":[{"name":"ping"},{"name":"mcp","parametersJsonSchema":{}},{"name":"typed","parameters":{"properties":{}}},
             {"name":"arr","parametersJsonSchema":{"type":"array"}},{"name":" "},{"description":"no name"}]}]"""));

        JsonNode tools = chat.path("tools");
        assertThat(tools).hasSize(4);
        for (int i = 0; i < 3; i++) {
            assertThat(tools.get(i).path("function").path("parameters")).isEqualTo(tree("{\"type\":\"object\",\"properties\":{}}"));
        }
        assertThat(tools.get(3).path("function").path("parameters")).isEqualTo(tree("{\"type\":\"array\"}"));
    }

    @Test
    void theToolsThatAreNotFunctionsAreLeftOut() {
        ObjectNode chat = convert(request(USER_HI, "\"tools\":[{\"googleSearch\":{}},{\"codeExecution\":{}},"
            + "{\"functionDeclarations\":[" + READ_FILE + "]}]"));
        ObjectNode none = convert(request(USER_HI, "\"tools\":[{\"googleSearch\":{}}],\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\"}}"));

        assertThat(chat.path("tools")).hasSize(1);
        assertThat(none.has("tools")).isFalse();
        assertThat(none.has("tool_choice")).isFalse();
    }

    @Test
    void theModeOfTheToolConfigIsTheToolChoice() {
        String tools = "\"tools\":[{\"functionDeclarations\":[{\"name\":\"a\"},{\"name\":\"b\"}]}],";
        assertThat(choiceOf(tools, "")).isNull();
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"AUTO\"}}")).isNull();
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"NONE\"}}")).isEqualTo(tree("\"none\""));
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\"}}")).isEqualTo(tree("\"required\""));
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"any\",\"allowedFunctionNames\":[]}}")).isEqualTo(tree("\"required\""));
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"b\"]}}"))
            .isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"b\"}}"));
        // Every function allowed is the required of a relay, whatever the order and the repeats.
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"b\",\"a\",\"b\"]}}"))
            .isEqualTo(tree("\"required\""));
    }

    /**
     * VALIDATED leaves the choice between a call and words to the model, as AUTO does, and checks the calls it makes: a relay
     * whose model were forced to call a function would keep calling functions, and the agent loop would be paid for each turn.
     */
    @Test
    void theValidatedModeForcesNoCallAndItsAllowedNamesAreOnlyARestriction() {
        String tools = "\"tools\":[{\"functionDeclarations\":[{\"name\":\"a\"},{\"name\":\"b\"}]}],";

        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"VALIDATED\"}}")).isNull();
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"validated\",\"allowedFunctionNames\":[\"a\",\"b\"]}}")).isNull();
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"VALIDATED\",\"allowedFunctionNames\":[\"b\"]}}"))
            .isEqualTo(tree("{\"type\":\"allowed_tools\",\"allowed_tools\":{\"mode\":\"auto\","
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"b\"}}]}}"));
    }

    @Test
    void aChoiceAmongSomeOfThreeFunctionsIsAnAllowedToolsChoiceAndAmongAllOfThemIsRequired() {
        String tools = "\"tools\":[{\"functionDeclarations\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}],";

        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"b\",\"a\",\"b\"]}}"))
            .isEqualTo(tree("{\"type\":\"allowed_tools\",\"allowed_tools\":{\"mode\":\"required\","
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"b\"}},{\"type\":\"function\",\"function\":{\"name\":\"a\"}}]}}"));
        assertThat(choiceOf(tools, "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"c\",\"a\",\"b\"]}}"))
            .isEqualTo(tree("\"required\""));
    }

    private static JsonNode choiceOf(String tools, String toolConfig) {
        JsonNode choice = convert(request(USER_HI, tools + toolConfig).replace(",}", "}")).path("tool_choice");
        return choice.isMissingNode() ? null : choice;
    }

    @Test
    void aChoiceAmongSeveralFunctionsIsLeftOutByTheSanitizerAndRecordedAsAToolChoice() {
        String original = request(USER_HI, "\"tools\":[{\"functionDeclarations\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}],"
            + "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"a\",\"b\"]}}");

        Wire wire = ChatWireSanitizer.sanitize(convert(original), tree(original), GEMINI, null);

        assertThat(wire.body().has("tool_choice")).isFalse();
        assertThat(wire.degraded()).containsExactly(ChatWireSanitizer.TOOL_CHOICE);
    }

    // ---- the response format ----

    @Test
    void theJsonModeIsAJsonObjectAndASchemaIsAJsonSchemaNamedResponse() {
        ObjectNode mode = convert(request(USER_HI, "\"generationConfig\":{\"responseMimeType\":\"application/json\"}"));
        ObjectNode schema = convert(request(USER_HI, """
            "generationConfig":{"responseMimeType":"application/json","responseSchema":{"type":"OBJECT","properties":{"n":{"type":"INTEGER"}}}}"""));
        ObjectNode jsonSchema = convert(request(USER_HI, """
            "generationConfig":{"responseMimeType":"Application/JSON; charset=utf-8","responseJsonSchema":{"type":"object","properties":{"n":{"type":"integer"}}},
             "responseSchema":{"type":"STRING"}}"""));
        ObjectNode enumMode = convert(request(USER_HI, "\"generationConfig\":{\"responseMimeType\":\"text/x.enum\",\"responseSchema\":{\"type\":\"STRING\"}}"));
        ObjectNode noMime = convert(request(USER_HI, "\"generationConfig\":{\"responseSchema\":{\"type\":\"OBJECT\"}}"));

        assertThat(mode.path("response_format")).isEqualTo(tree("{\"type\":\"json_object\"}"));
        assertThat(schema.path("response_format")).isEqualTo(tree("{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"response\","
            + "\"schema\":{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}}}"));
        assertThat(jsonSchema.path("response_format").path("json_schema").path("schema"))
            .isEqualTo(tree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}"));
        assertThat(enumMode.has("response_format")).isFalse();
        assertThat(noMime.has("response_format")).isFalse();
    }

    // ---------------------------------------------------------------------------------------- not changed, not shared

    @Test
    void theRequestIsNotChangedAndTheResultSharesNothingWithIt() {
        String json = request(String.join(",", USER_HI, modelCalling(call("f", "{\"a\":[1]}")), userAnswering(answer("f", "{\"o\":1}"))),
            "\"tools\":[{\"functionDeclarations\":[" + READ_FILE + ",{\"name\":\"g\",\"parameters\":{\"type\":\"OBJECT\"}}]}],"
                + "\"generationConfig\":{\"stopSequences\":[\"a\"],\"responseMimeType\":\"application/json\",\"responseSchema\":{\"type\":\"OBJECT\"}}");
        JsonNode request = tree(json);

        ObjectNode chat = CONVERTER.toChat("m", request);
        chat.path("tools").forEach(tool -> ((ObjectNode) tool.path("function")).put("name", "changed"));
        ((ArrayNode) chat.path("stop")).add("changed");
        ((ObjectNode) chat.path("response_format").path("json_schema").path("schema")).put("x", 1);
        ((ObjectNode) chat.path("tools").get(0).path("function").path("parameters")).put("y", 1);

        assertThat(request).isEqualTo(tree(json));
    }

    @Test
    void aRequestThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> CONVERTER.toChat("m", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CONVERTER.toChat("m", tree("[]"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(CONVERTER.toChat("m", tree("{}")).path("messages")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- the pipeline

    private static Wire pipeline(String original, Settings settings) {
        JsonNode request = tree(original);
        ObjectNode chat = CONVERTER.toChat("gemini-2.5-pro", request);
        return ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, request, settings, "relay-model"), settings);
    }

    /** The shape of what Gemini CLI sends in the middle of a session, with placeholders for what it says. */
    private static final String GEMINI_CLI = """
        {"systemInstruction":{"role":"user","parts":[{"text":"You are an interactive CLI agent."}]},
         "contents":[
          {"role":"user","parts":[{"text":"<session_context>cwd: /work</session_context>"}]},
          {"role":"user","parts":[{"text":"read a.txt and b.txt, then summarize"}]},
          {"role":"model","parts":[{"text":"Reading both.","thought":true},
            {"functionCall":{"name":"read_file","args":{"file_path":"a.txt"}},"thoughtSignature":"SIG-FIRST"},
            {"functionCall":{"name":"read_file","args":{"file_path":"b.txt"}}}]},
          {"role":"user","parts":[{"functionResponse":{"name":"read_file","response":{"output":"alpha"}}},
            {"functionResponse":{"name":"read_file","response":{"output":"beta"}}}]},
          {"role":"model","parts":[{"text":"Both files are short."},
            {"functionCall":{"name":"run_shell_command","args":{"command":"wc -c a.txt b.txt"}},"thoughtSignature":"SIG-SECOND"}]},
          {"role":"user","parts":[{"functionResponse":{"name":"run_shell_command","response":{"output":"6 a.txt\\n5 b.txt"}}}]}],
         "tools":[{"functionDeclarations":[
           {"name":"read_file","description":"Reads a file","parametersJsonSchema":{"type":"object","properties":{"file_path":{"type":"string"}},"required":["file_path"]}},
           {"name":"run_shell_command","description":"Runs a command","parametersJsonSchema":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}}]}],
         "generationConfig":{"temperature":1,"topP":0.95,"topK":64,"thinkingConfig":{"includeThoughts":true,"thinkingLevel":"HIGH"}}}
        """;

    @Test
    void aGeminiCliRequestIsSentAsAStrictChatRequest() {
        Wire wire = pipeline(GEMINI_CLI, GEMINI);

        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        JsonNode body = wire.body();
        assertThat(body.propertyNames()).containsExactly("model", "messages", "temperature", "top_p", "tools", "reasoning_effort", "stream");
        assertThat(body.path("model").stringValue()).isEqualTo("relay-model");
        assertThat(body.path("reasoning_effort").stringValue()).isEqualTo("high");
        List<String> roles = new ArrayList<>();
        body.path("messages").forEach(message -> roles.add(message.path("role").stringValue()));
        assertThat(roles).containsExactly("system", "user", "assistant", "tool", "tool", "assistant", "tool");
        JsonNode firstTurn = body.path("messages").get(2);
        assertThat(callIds(firstTurn)).containsExactly("call_1", "call_2");
        assertThat(firstTurn.path("tool_calls").get(0).path("extra_content").path("google").path("thought_signature").stringValue()).isEqualTo("SIG-FIRST");
        assertThat(firstTurn.path("tool_calls").get(1).has("extra_content")).isFalse();
        assertThat(body.path("messages").get(3).path("content").stringValue()).isEqualTo("{\"output\":\"alpha\"}");
        assertThat(body.path("messages").get(4).path("content").stringValue()).isEqualTo("{\"output\":\"beta\"}");
        assertThat(body.path("messages").get(5).path("tool_calls").get(0).path("extra_content").path("google").path("thought_signature").stringValue())
            .isEqualTo("SIG-SECOND");
        assertThat(body.toString()).doesNotContain("Reading both.", "topK", "includeThoughts");
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void theSameRequestForAClaudeModelIsSentWithoutSignaturesAndAsksNoLevelOnAContinuation() {
        Wire wire = pipeline(GEMINI_CLI, CLAUDE);

        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        assertThat(wire.body().toString()).doesNotContain("extra_content", "SIG-");
        // The request ends with an answer, a turn that needs the thinking of the one before, which a relay's history lacks.
        assertThat(wire.body().has("reasoning_effort")).isFalse();
        assertThat(wire.degraded()).containsExactly("thinking");
    }

    @Test
    void aSummaryOfTheSessionWithoutTheToolsIsToldAsTextToTheModelsWhoseUpstreamWantsThem() {
        JsonNode withoutTools = ((ObjectNode) tree(GEMINI_CLI)).deepCopy();
        ((ObjectNode) withoutTools).remove("tools");

        Wire google = pipeline(withoutTools.toString(), GEMINI);

        assertThat(StrictChatLint.problems(google.body())).isEmpty();
        assertThat(google.degraded()).contains(ChatCanonicalizer.TOOL_HISTORY_FLATTENED);
        assertThat(google.body().toString()).doesNotContain("tool_calls", "extra_content");
        assertThat(google.body().toString()).contains("Tool use: read_file", "Tool result:");
    }

    @Test
    void aTextOnlyConversationAndOneWithMediaAndASchemaPassTheLintToo() {
        Wire plain = pipeline(request(String.join(",", USER_HI, "{\"role\":\"model\",\"parts\":[{\"text\":\"Hello.\"}]}",
            "{\"role\":\"user\",\"parts\":[{\"text\":\"again\"}]}"), ""), CLAUDE);
        Wire media = pipeline(request(String.join(",", USER_HI,
            userAnswering("{\"text\":\"look\"}", "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + PNG + "\"}}",
                "{\"inlineData\":{\"mimeType\":\"audio/wav\",\"data\":\"AAAA\"}}")),
            "\"generationConfig\":{\"responseMimeType\":\"application/json\",\"responseSchema\":{\"type\":\"OBJECT\"}}"), CLAUDE);

        assertThat(StrictChatLint.problems(plain.body())).isEmpty();
        assertThat(StrictChatLint.problems(media.body())).isEmpty();
        // The member cannot see and has no schema: both are told as left out.
        assertThat(media.degraded()).containsExactly("image", "audio", "json_schema");
        assertThat(media.body().toString()).doesNotContain(PNG, "AAAA");
    }

    // ---------------------------------------------------------------------------------------- any history

    /** Any history a client could build passes the whole pipeline, whatever the settings, and a signed call keeps its signature. */
    @Test
    void anyHistoryAGeminiClientCouldSendPassesTheLintWithoutLosingASignature() {
        Random random = new Random(20261031);
        String[] names = {"read_file", "list", "run"};
        for (int round = 0; round < 400; round++) {
            ArrayNode contents = JSON.createArrayNode();
            int signed = 0;
            int turns = 1 + random.nextInt(8);
            for (int turn = 0; turn < turns; turn++) {
                ObjectNode content = contents.addObject();
                String role = random.nextInt(10) == 0 ? "" : random.nextInt(3) == 0 ? "model" : random.nextBoolean() ? "user" : "function";
                boolean model = role.equals("model");
                content.put("role", role);
                ArrayNode parts = content.putArray("parts");
                int count = random.nextInt(4);
                for (int i = 0; i < count; i++) {
                    ObjectNode part = parts.addObject();
                    switch (random.nextInt(7)) {
                        case 0 -> part.put("text", random.nextBoolean() ? "text " + turn : " ");
                        case 1 -> {
                            ObjectNode functionCall = part.putObject("functionCall");
                            functionCall.put("name", names[random.nextInt(names.length)]);
                            if (random.nextBoolean()) {
                                functionCall.put("id", "id" + random.nextInt(4));
                            }
                            functionCall.putObject("args").put("n", random.nextInt(9));
                            if (random.nextBoolean()) {
                                part.put("thoughtSignature", "SIG-" + round + "-" + turn + "-" + i);
                                // The calls of a turn of the user are not calls of a history: the conversion has no place for them.
                                signed += model ? 1 : 0;
                            }
                        }
                        case 2, 3 -> {
                            ObjectNode functionResponse = part.putObject("functionResponse");
                            functionResponse.put("name", random.nextInt(6) == 0 ? "" : names[random.nextInt(names.length)]);
                            if (random.nextBoolean()) {
                                functionResponse.put("id", "id" + random.nextInt(4));
                            }
                            if (random.nextInt(5) != 0) {
                                functionResponse.putObject("response").put("output", "out " + i);
                            }
                        }
                        case 4 -> part.putObject("inlineData").put("mimeType", random.nextBoolean() ? "image/png" : "audio/wav").put("data", PNG);
                        case 5 -> part.putObject("fileData").put("mimeType", "image/jpeg").put("fileUri", random.nextBoolean() ? "https://x.example/a.jpg" : "files/x");
                        default -> part.put("text", "thought").put("thought", true);
                    }
                }
            }
            ObjectNode request = JSON.createObjectNode();
            request.set("contents", contents);
            if (random.nextBoolean()) {
                request.putArray("tools").addObject().putArray("functionDeclarations").addObject().put("name", "read_file");
            }
            JsonNode before = request.deepCopy();
            ObjectNode chat = CONVERTER.toChat("m", request);
            assertThat(request).isEqualTo(before);

            for (Settings settings : List.of(GEMINI, CLAUDE)) {
                Wire wire = ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, request, settings, "relay-model"), settings);
                // A request with nothing to answer is one the Gemini API refuses, and the lint says so.
                if (!wire.body().path("messages").isEmpty()) {
                    assertThat(StrictChatLint.problems(wire.body())).as("round %d, %s: %s", round, settings, request).isEmpty();
                }
                assertThat(ChatCanonicalizer.canonicalize(wire, settings).body()).isEqualTo(wire.body());
            }
            // Every signature the client sent is in the Chat body, and the pipeline of a model that signs keeps those of calls it keeps.
            assertThat(chat.toString().split("thought_signature", -1).length - 1).as("round %d", round).isEqualTo(signed);
        }
    }
}
