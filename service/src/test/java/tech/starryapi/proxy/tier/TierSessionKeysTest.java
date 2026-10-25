package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a tiered request calls its session (design 4.7): the client's own name for it, the response it continues, or a
 * fingerprint of how the conversation begins.
 */
class TierSessionKeysTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- the name the client gave ----

    @Test
    void aHintWithAnythingBesidesTheKeyIdNamesTheSession() {
        assertThat(TierSessionKeys.named("key-1:session-9")).isEqualTo("key-1:session-9");
    }

    @Test
    void theKeyIdAloneNamesNoSession() {
        assertThat(TierSessionKeys.named("key-1")).isNull();
        assertThat(TierSessionKeys.named(null)).isNull();
        assertThat(TierSessionKeys.named("")).isNull();
    }

    @Test
    void aResponsesRequestIsNamedByItsOwnHintBeforeTheResponseItContinues() {
        assertThat(TierSessionKeys.ofResponses("key-1", "key-1:session-9", "resp_1")).isEqualTo("key-1:session-9");
    }

    @Test
    void aResponsesRequestWithoutAHintIsNamedByTheResponseItContinues() {
        assertThat(TierSessionKeys.ofResponses("key-1", "key-1", "resp_1"))
            .isEqualTo(TierSessionKeys.ofResponse("key-1", "resp_1"))
            .isEqualTo("key-1:resp:resp_1");
    }

    @Test
    void aResponsesRequestThatNamesNeitherIsLeftToTheFingerprint() {
        assertThat(TierSessionKeys.ofResponses("key-1", "key-1", null)).isNull();
        assertThat(TierSessionKeys.ofResponses("key-1", "key-1", " ")).isNull();
    }

    // ---- the beginning of the conversation ----

    @Test
    void theSameBeginningGivesTheSameFingerprintWhateverFollows() {
        ObjectNode first = chat("You are helpful.", "Fix the build", "ok", "Now the tests");
        ObjectNode later = chat("You are helpful.", "Fix the build", "done", "And the docs", "sure", "thanks");

        assertThat(TierSessionKeys.fingerprint("key-1", "claude-sonnet-4.5", first))
            .isNotNull()
            .isEqualTo(TierSessionKeys.fingerprint("key-1", "claude-sonnet-4.5", later));
    }

    @Test
    void theFingerprintTellsKeysModelsSystemTextsAndFirstQuestionsApart() {
        String base = TierSessionKeys.fingerprint("key-1", "claude-sonnet-4.5", chat("system", "question"));

        assertThat(TierSessionKeys.fingerprint("key-2", "claude-sonnet-4.5", chat("system", "question"))).isNotEqualTo(base);
        assertThat(TierSessionKeys.fingerprint("key-1", "claude-opus-4.5", chat("system", "question"))).isNotEqualTo(base);
        assertThat(TierSessionKeys.fingerprint("key-1", "claude-sonnet-4.5", chat("other", "question"))).isNotEqualTo(base);
        assertThat(TierSessionKeys.fingerprint("key-1", "claude-sonnet-4.5", chat("system", "another"))).isNotEqualTo(base);
    }

    @Test
    void theSystemTextAndTheFirstQuestionAreNotMistakenForOneAnother() {
        // The boundary between the two is part of what is hashed.
        assertThat(TierSessionKeys.fingerprint("key-1", "m", chat("ab", "c")))
            .isNotEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat("a", "bc")));
    }

    @Test
    void onlyTheFirstTwoKilobytesOfEachTextCount() {
        String head = "x".repeat(TierSessionKeys.FINGERPRINT_TEXT_CHARS);

        assertThat(TierSessionKeys.fingerprint("key-1", "m", chat(head + " one", "q")))
            .isEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat(head + " two", "q")));
        assertThat(TierSessionKeys.fingerprint("key-1", "m", chat("s", head + " one")))
            .isEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat("s", head + " two")));
        assertThat(TierSessionKeys.fingerprint("key-1", "m", chat(head.substring(1), "q")))
            .isNotEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat(head, "q")));
    }

    @Test
    void imagesAndFilesDoNotTakePartInTheFingerprint() {
        ObjectNode withImage = mapper.createObjectNode();
        ArrayNode messages = withImage.putArray("messages");
        messages.addObject().put("role", "system").put("content", "system");
        ArrayNode parts = messages.addObject().put("role", "user").putArray("content");
        parts.addObject().put("type", "text").put("text", "question");
        parts.addObject().put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64," + "A".repeat(50_000));

        assertThat(TierSessionKeys.fingerprint("key-1", "m", withImage))
            .isEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat("system", "question")));
    }

    @Test
    void textInPartsCountsLikeTextInAString() {
        ObjectNode parted = mapper.createObjectNode();
        ArrayNode messages = parted.putArray("messages");
        messages.addObject().put("role", "system").putArray("content").addObject().put("type", "text").put("text", "system");
        messages.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", "question");

        assertThat(TierSessionKeys.fingerprint("key-1", "m", parted))
            .isEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat("system", "question")));
    }

    @Test
    void developerMessagesAreSystemText() {
        ObjectNode developer = mapper.createObjectNode();
        ArrayNode messages = developer.putArray("messages");
        messages.addObject().put("role", "developer").put("content", "system");
        messages.addObject().put("role", "user").put("content", "question");

        assertThat(TierSessionKeys.fingerprint("key-1", "m", developer))
            .isEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat("system", "question")));
    }

    @Test
    void aResponsesRequestIsToldByItsInstructionsAndItsFirstUserItem() {
        ObjectNode responses = mapper.createObjectNode();
        responses.put("instructions", "system");
        ArrayNode input = responses.putArray("input");
        input.addObject().put("type", "function_call_output").put("output", "ignored");
        input.addObject().put("type", "message").put("role", "user")
            .putArray("content").addObject().put("type", "input_text").put("text", "question");
        input.addObject().put("type", "message").put("role", "user").put("content", "a later question");

        assertThat(TierSessionKeys.fingerprint("key-1", "m", responses))
            .isEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat("system", "question")));
    }

    @Test
    void aResponsesRequestWhoseInputIsTheTextItselfIsToldByIt() {
        ObjectNode responses = mapper.createObjectNode();
        responses.put("instructions", "system").put("input", "question");

        assertThat(TierSessionKeys.fingerprint("key-1", "m", responses))
            .isEqualTo(TierSessionKeys.fingerprint("key-1", "m", chat("system", "question")));
    }

    @Test
    void aRequestWithNoTextToTellItByHasNoFingerprint() {
        ObjectNode empty = mapper.createObjectNode();
        empty.putArray("messages").addObject().put("role", "assistant").put("content", "hello");

        assertThat(TierSessionKeys.fingerprint("key-1", "m", empty)).isNull();
        assertThat(TierSessionKeys.fingerprint("key-1", "m", mapper.createObjectNode())).isNull();
    }

    // ---- putting it together ----

    @Test
    void theNameTheClientGaveIsUsedBeforeAFingerprint() {
        assertThat(TierSessionKeys.resolve("key-1", "m", "key-1:session-9", chat("s", "q"))).contains("key-1:session-9");
    }

    @Test
    void withoutANameTheFingerprintIsUsed() {
        assertThat(TierSessionKeys.resolve("key-1", "m", null, chat("s", "q")))
            .hasValue(TierSessionKeys.fingerprint("key-1", "m", chat("s", "q")));
    }

    @Test
    void aRequestThatCannotBeToldFromOthersHasNoSession() {
        assertThat(TierSessionKeys.resolve("key-1", "m", null, mapper.createObjectNode())).isEmpty();
        assertThat(TierSessionKeys.resolve(null, "m", "key-1:session-9", chat("s", "q"))).isEmpty();
        assertThat(TierSessionKeys.resolve(" ", "m", "key-1:session-9", chat("s", "q"))).isEmpty();
    }

    @Test
    void theBindingKeyHoldsTheVendorTheModelAndTheSession() {
        assertThat(TierSessionKeys.bindingKey("anthropic", "claude-sonnet-4.5", "key-1:session-9"))
            .isEqualTo("tier:anthropic|claude-sonnet-4.5|key-1:session-9");
    }

    private ObjectNode chat(String system, String... turns) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        for (int i = 0; i < turns.length; i++) {
            messages.addObject().put("role", i % 2 == 0 ? "user" : "assistant").put("content", turns[i]);
        }
        return body;
    }
}
