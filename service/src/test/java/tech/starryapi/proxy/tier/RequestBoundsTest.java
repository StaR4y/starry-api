package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RequestBoundsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- output ----

    @Test
    void theOutputBoundIsTheLargestLimitAndAtLeastTheDefault() {
        assertThat(RequestBounds.outputTokens(json("{\"max_tokens\": 8000}"))).isEqualTo(8000);
        assertThat(RequestBounds.outputTokens(json("{\"max_completion_tokens\": 12000}"))).isEqualTo(12000);
        assertThat(RequestBounds.outputTokens(json("{\"max_output_tokens\": 9000}"))).isEqualTo(9000);
        assertThat(RequestBounds.outputTokens(json("{\"max_tokens\": 100, \"max_output_tokens\": 50}"))).isEqualTo(100);
        // The default stands in for a missing max_output_tokens, so a small max_tokens still reserves for 4096.
        assertThat(RequestBounds.outputTokens(json("{\"max_tokens\": 100}"))).isEqualTo(4096);
        assertThat(RequestBounds.outputTokens(json("{}"))).isEqualTo(4096);
    }

    // ---- a route that takes the whole request ----

    @Test
    void aPlainRequestIsBoundedByItsSizeAndTheEnvelope() {
        JsonNode body = json("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertThat(RequestBounds.nativeInputTokens(body)).isEqualTo(body.toString().length() + 1024L);
    }

    @Test
    void aSizeIsCountedInUtf8Bytes() {
        JsonNode body = json("{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}");

        assertThat(RequestBounds.nativeInputTokens(body))
            .isEqualTo(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1024L);
    }

    @Test
    void aResponseChainThatWasNotExpandedRaisesTheBoundToTheFloor() {
        JsonNode body = json("{\"model\":\"m\",\"previous_response_id\":\"resp_1\",\"input\":\"hi\"}");

        assertThat(RequestBounds.nativeInputTokens(body)).isEqualTo(1_000_000L);
    }

    @Test
    void anyMediaRaisesTheBoundToTheFloor() {
        assertThat(RequestBounds.nativeInputTokens(chatWithImage("data:image/png;base64,AAAA"))).isEqualTo(1_000_000L);
        assertThat(RequestBounds.nativeInputTokens(chatWithImage("https://example.com/cat.png"))).isEqualTo(1_000_000L);
        assertThat(RequestBounds.nativeInputTokens(json("{\"input\":[{\"type\":\"input_image\",\"image_url\":\"x\"}]}")))
            .isEqualTo(1_000_000L);
        assertThat(RequestBounds.nativeInputTokens(json("{\"input\":[{\"type\":\"input_file\",\"file_id\":\"f\"}]}")))
            .isEqualTo(1_000_000L);
        assertThat(RequestBounds.nativeInputTokens(json("{\"messages\":[{\"content\":[{\"type\":\"document\"}]}]}")))
            .isEqualTo(1_000_000L);
    }

    @Test
    void aRequestLargerThanTheFloorKeepsItsOwnSize() {
        ObjectNode body = chatWithImage("data:image/png;base64," + "A".repeat(1_500_000));

        assertThat(RequestBounds.nativeInputTokens(body)).isEqualTo(body.toString().length() + 1024L);
    }

    // ---- a route that is sent text only ----

    @Test
    void anInlineImageDoesNotCountForKiro() {
        ObjectNode body = chatWithImage("data:image/png;base64," + "A".repeat(900_000));

        long bound = RequestBounds.kiroInputTokens(body);

        // What is left is the JSON around the payload and the header of the data URI.
        assertThat(bound).isGreaterThan(1024L).isLessThan(2_000L);
    }

    @Test
    void aRemoteImageDoesNotRaiseTheKiroBoundEither() {
        ObjectNode body = chatWithImage("https://example.com/cat.png");

        assertThat(RequestBounds.kiroInputTokens(body)).isEqualTo(body.toString().length() + 1024L);
    }

    @Test
    void textIsCountedInFullForKiro() {
        JsonNode body = json("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(10_000) + "\"}]}");

        assertThat(RequestBounds.kiroInputTokens(body)).isEqualTo(RequestBounds.nativeInputTokens(body));
    }

    @Test
    void aResponseChainThatWasNotExpandedStillRaisesTheKiroBoundToTheFloor() {
        JsonNode body = json("{\"model\":\"m\",\"previous_response_id\":\"resp_1\",\"messages\":[]}");

        assertThat(RequestBounds.kiroInputTokens(body)).isEqualTo(1_000_000L);
    }

    @Test
    void aMessageWhoseContentIsASingleObjectIsSentToKiroAsItsJsonSoItCountsInFull() {
        // KiroPayloadFactory turns any content that is neither a string nor a list of parts into its JSON text.
        ObjectNode body = json("{\"messages\":[{\"role\":\"user\",\"content\":"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + "A".repeat(50_000) + "\"}}}]}").deepCopy();

        assertThat(RequestBounds.kiroInputTokens(body)).isEqualTo(body.toString().length() + 1024L);
    }

    @Test
    void aResponsesInputItemWhoseContentIsASingleObjectCountsInFullToo() {
        ObjectNode body = json("{\"input\":[{\"role\":\"user\",\"content\":"
            + "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64," + "A".repeat(50_000) + "\"}}]}");

        assertThat(RequestBounds.kiroInputTokens(body)).isEqualTo(body.toString().length() + 1024L);
    }

    @Test
    void aResponsesInputWithPartListsIsStrippedLikeAChat() {
        ObjectNode body = json("{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"look\"},"
            + "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64," + "A".repeat(50_000) + "\"}]}]}");

        assertThat(RequestBounds.kiroInputTokens(body)).isLessThan(2_000L);
    }

    @Test
    void aMessageWithoutContentOrWithNullContentIsNotAnObjectContent() {
        JsonNode body = json("{\"messages\":[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[]},{\"role\":\"user\"}]}");

        assertThat(RequestBounds.kiroInputTokens(body)).isEqualTo(RequestBounds.nativeInputTokens(body));
    }

    @Test
    void aKiroBoundIsNeverAboveTheBoundOfARouteThatTakesTheWholeRequest() {
        for (JsonNode body : new JsonNode[] {
            chatWithImage("data:image/png;base64," + "A".repeat(30_000)),
            chatWithImage("https://example.com/cat.png"),
            json("{\"messages\":[{\"content\":\"plain\"}]}"),
            json("{\"previous_response_id\":\"resp_1\"}"),
            json("{\"messages\":[{\"content\":{\"type\":\"text\",\"text\":\"object\"}}]}")
        }) {
            assertThat(RequestBounds.kiroInputTokens(body)).isLessThanOrEqualTo(RequestBounds.nativeInputTokens(body));
        }
    }

    // ---- what Kiro is sent beyond the text parts of the messages ----
    //
    // Each shape below is built into the payload KiroPayloadFactory sends, to show that the base64 really reaches Kiro, and
    // is then bounded by what it costs there: only the media of the dropped content parts is left out.

    private static final String IMAGE = "data:image/png;base64," + "A".repeat(400_000);

    @Test
    void aToolSchemaIsSentToKiroWhateverItHolds() {
        ObjectNode body = json("{\"model\":\"claude-sonnet-4.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
            + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"look\",\"description\":\"d\",\"parameters\":"
            + "{\"type\":\"object\",\"properties\":{\"x\":{\"enum\":[{\"image_url\":\"" + IMAGE + "\"}]}}}}}]}");

        assertThat(kiroPayload(body)).contains(IMAGE);
        assertThat(RequestBounds.kiroInputTokens(body)).isGreaterThan(400_000L);
    }

    @Test
    void theThinkingFieldIsSentToKiroWhateverItHolds() {
        ObjectNode body = json("{\"model\":\"claude-sonnet-4.5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
            + "\"thinking\":{\"type\":\"enabled\",\"extra\":{\"image_url\":{\"url\":\"" + IMAGE + "\"}}}}");

        assertThat(kiroPayload(body)).contains(IMAGE);
        assertThat(RequestBounds.kiroInputTokens(body)).isGreaterThan(400_000L);
    }

    @Test
    void aFunctionCallOutputThatIsAnObjectIsSentToKiroAsItsJson() {
        ObjectNode body = json("{\"model\":\"claude-sonnet-4.5\",\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"c1\","
            + "\"output\":{\"type\":\"input_image\",\"image_url\":\"" + IMAGE + "\"}}]}");

        assertThat(kiroPayload(new OpenAiRequestAdapter(mapper, new DefaultModelCatalogProvider()).responsesToChat(body)))
            .contains(IMAGE);
        assertThat(RequestBounds.kiroInputTokens(body)).isGreaterThan(400_000L);
    }

    @Test
    void aFunctionCallOutputThatIsAListOfPartsLosesItsImagesBeforeKiro() {
        ObjectNode body = json("{\"model\":\"claude-sonnet-4.5\",\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"c1\","
            + "\"output\":[{\"type\":\"input_text\",\"text\":\"screenshot\"},"
            + "{\"type\":\"input_image\",\"image_url\":\"" + IMAGE + "\"}]}]}");

        assertThat(kiroPayload(new OpenAiRequestAdapter(mapper, new DefaultModelCatalogProvider()).responsesToChat(body)))
            .doesNotContain("AAAAAAAA");
        assertThat(RequestBounds.kiroInputTokens(body)).isLessThan(2_000L);
    }

    @Test
    void mediaInDroppedPartsIsLeftOutWhileTheSameMediaElsewhereInTheRequestIsCounted() {
        ObjectNode body = json("{\"model\":\"claude-sonnet-4.5\",\"messages\":[{\"role\":\"user\",\"content\":["
            + "{\"type\":\"text\",\"text\":\"look\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + IMAGE + "\"}}]}],"
            + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"look\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"x\":{\"default\":{\"image_url\":\"" + IMAGE + "\"}}}}}}]}");

        long bound = RequestBounds.kiroInputTokens(body);

        // One image of two is dropped; the one in the tool schema stays.
        assertThat(bound).isGreaterThan(400_000L).isLessThan(401_500L);
    }

    @Test
    void aKiroBoundCoversTheTextOfEveryPartKiroKeeps() {
        ObjectNode body = json("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"" + "t".repeat(5_000) + "\"},"
            + "{\"type\":\"input_text\",\"text\":\"" + "u".repeat(5_000) + "\"}]}]}");

        assertThat(RequestBounds.kiroInputTokens(body)).isEqualTo(RequestBounds.nativeInputTokens(body));
    }

    // ---- a relay ----

    @Test
    void aRelayIsBoundedByTheSizeOfItsBodyWithoutTheBase64AndTheCapOfEachInlineImage() {
        ObjectNode body = chatWithImage("data:image/png;base64," + "A".repeat(500_000));
        long text = tech.starryapi.common.JsonNodes.mediaStats(body).bytesWithoutBase64();

        // One image: the text of the body, the envelope and the cap of the mapping, whatever the image weighs.
        assertThat(RequestBounds.xpInputTokens(body, 8192)).isEqualTo(text + 1024L + 8192L);
        assertThat(RequestBounds.xpInputTokens(body, 1)).isEqualTo(text + 1024L + 1L);
        assertThat(RequestBounds.nativeInputTokens(body)).isEqualTo(1_000_000L);
    }

    @Test
    void everyInlineImageCountsItsOwnCap() {
        ObjectNode body = chatWithImage("data:image/png;base64,AAAA");
        body.withArray("messages").get(0).withArray("content").addObject().put("type", "image_url")
            .putObject("image_url").put("url", "data:image/png;base64,BBBB");
        long text = tech.starryapi.common.JsonNodes.mediaStats(body).bytesWithoutBase64();

        assertThat(RequestBounds.xpInputTokens(body, 5000)).isEqualTo(text + 1024L + 2 * 5000L);
    }

    @Test
    void mediaARelayOnlyPointsAtRaisesTheBoundToTheFloorAsItDoesForTheNativeAccounts() {
        assertThat(RequestBounds.xpInputTokens(chatWithImage("https://example.com/cat.png"), 100)).isEqualTo(1_000_000L);
        // A body of text is bounded by its size, as the native accounts' is.
        JsonNode plain = json("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(RequestBounds.xpInputTokens(plain, 100)).isEqualTo(RequestBounds.nativeInputTokens(plain));
    }

    private String kiroPayload(JsonNode chat) {
        KiroPayloadFactory factory = new KiroPayloadFactory(mapper, new DefaultModelCatalogProvider());
        return factory.fromOpenAiChat(chat, new ProxyAccountEntity(), "claude-sonnet-4.5", "AI_EDITOR", "session-1").toString();
    }

    private ObjectNode chatWithImage(String url) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "m");
        var parts = body.putArray("messages").addObject().put("role", "user").putArray("content");
        parts.addObject().put("type", "text").put("text", "describe it");
        parts.addObject().put("type", "image_url").putObject("image_url").put("url", url);
        return body;
    }

    private ObjectNode json(String value) {
        return (ObjectNode) mapper.readTree(value);
    }
}
