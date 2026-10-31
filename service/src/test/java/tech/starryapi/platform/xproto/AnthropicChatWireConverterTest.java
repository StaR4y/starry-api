package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.PhaseFiveAnthropicRequestAdapter;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.platform.xproto.XprotoFixtures.Case;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Chat body of a relay made of a Messages request (design 7.6, phase 8): what it keeps that the native conversion does
 * not (images of the user's turns, cache controls, the failure of a tool result), how a member's settings decide what is
 * sent of it, and that a request none of that is in comes to the body the native conversion makes, which is what the relays
 * were sent before phase 8.
 */
class AnthropicChatWireConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultModelCatalogProvider CATALOG = new DefaultModelCatalogProvider();
    private static final AnthropicChatWireConverter CONVERTER = new AnthropicChatWireConverter(JSON);
    private static final AnthropicRequestAdapter ADAPTER = new AnthropicRequestAdapter(JSON, CATALOG);
    private static final PhaseFiveAnthropicRequestAdapter PHASE_FIVE = new PhaseFiveAnthropicRequestAdapter(JSON, CATALOG);

    /** A member that takes nothing of what is new: no vision, no cache control. What every member was until phase 8. */
    private static final Settings PLAIN = new Settings(false, false, false, false, false, false, false, false, true, true);
    private static final Settings VISION = new Settings(true, false, false, false, false, false, false, false, true, true);
    private static final Settings CACHE = new Settings(false, false, false, true, false, false, false, false, true, true);
    private static final Settings BOTH = new Settings(true, false, false, true, false, false, false, false, true, true);

    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";
    private static final String IMAGE = "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + PNG + "\"}}";
    private static final String DATA_URI = "data:image/png;base64," + PNG;
    private static final String EPHEMERAL = "\"cache_control\":{\"type\":\"ephemeral\"}";

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static String request(String extra, String... messages) {
        return "{\"model\":\"claude-sonnet-4.5\",\"max_tokens\":1000" + (extra.isEmpty() ? "" : "," + extra)
            + ",\"messages\":[" + String.join(",", messages) + "]}";
    }

    private static String user(String content) {
        return "{\"role\":\"user\",\"content\":" + content + "}";
    }

    private static Wire wire(String request, Settings settings) {
        JsonNode body = tree(request);
        return ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(CONVERTER.toChat(body), body, settings, "relay-model"), settings);
    }

    private static Wire phaseFiveWire(JsonNode body, Settings settings) {
        return ChatCanonicalizer.canonicalize(
            ChatWireSanitizer.sanitize(PHASE_FIVE.messagesToChat(body), body, settings, "relay-model"), settings);
    }

    private static JsonNode message(Wire wire, int index) {
        return wire.body().path("messages").get(index);
    }

    // ---------------------------------------------------------------------------------------- the native conversion

    @Test
    void theNativeConversionIsStillByteForByteWhatItWasBeforeTheHelpersWereSharedAndWhateverTheRequestHas() {
        int checked = 0;
        for (JsonNode request : corpus()) {
            assertThat(ADAPTER.messagesToChat(request).toString()).as("%s", request).isEqualTo(PHASE_FIVE.messagesToChat(request).toString());
            checked++;
        }
        assertThat(checked).isGreaterThan(800);
    }

    @Test
    void aRequestWithNoImageCacheControlOrFailedToolResultIsTheBodyTheNativeConversionMakes() {
        for (JsonNode request : corpus()) {
            JsonNode plain = withoutWhatIsNew(request);
            assertThat(CONVERTER.toChat(plain).toString()).as("%s", plain).isEqualTo(ADAPTER.messagesToChat(plain).toString());
        }
    }

    @Test
    void forThatRequestEveryMemberIsSentWhatItWasSentBeforePhaseEight() {
        for (JsonNode request : corpus()) {
            JsonNode plain = withoutWhatIsNew(request);
            for (Settings member : List.of(PLAIN, VISION, CACHE, BOTH)) {
                Wire wire = ChatCanonicalizer.canonicalize(
                    ChatWireSanitizer.sanitize(CONVERTER.toChat(plain), plain, member, "relay-model"), member);
                Wire before = phaseFiveWire(plain, member);
                assertThat(wire.body()).as("%s, %s", plain, member).isEqualTo(before.body());
                assertThat(wire.degraded()).as("%s, %s", plain, member).isEqualTo(before.degraded());
            }
        }
    }

    @Test
    void aMemberThatTakesNeitherCacheControlNorImagesIsSentWhatItWasSentWhateverTheRequestSaysOfCacheControl() {
        // Cache controls where Claude Code puts them, and the cache control of the request: the member that does not pass it
        // through is sent the same body as before, with the cache control named as left out, as it was.
        int checked = 0;
        for (JsonNode request : corpus()) {
            JsonNode cached = withoutImagesAndErrors(request);
            Wire wire = ChatCanonicalizer.canonicalize(
                ChatWireSanitizer.sanitize(CONVERTER.toChat(cached), cached, PLAIN, "relay-model"), PLAIN);
            Wire before = phaseFiveWire(cached, PLAIN);
            assertThat(wire.body()).as("%s", cached).isEqualTo(before.body());
            assertThat(wire.degraded()).as("%s", cached).containsAll(before.degraded());
            checked++;
        }
        assertThat(checked).isGreaterThan(800);
    }

    // ---------------------------------------------------------------------------------------- images

    @Test
    void anImageOfAUserTurnIsAPartInItsPlaceAndAMemberWithVisionIsSentIt() {
        String body = request("", user("[{\"type\":\"text\",\"text\":\"look\"}," + IMAGE + ",{\"type\":\"text\",\"text\":\"what is it\"}]"));

        Wire wire = wire(body, VISION);

        JsonNode content = message(wire, 0).path("content");
        assertThat(content).hasSize(3);
        assertThat(content.get(0).path("text").stringValue()).isEqualTo("look");
        assertThat(content.get(1).path("type").stringValue()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").stringValue()).isEqualTo(DATA_URI);
        assertThat(content.get(2).path("text").stringValue()).isEqualTo("what is it");
        assertThat(wire.degraded()).isEmpty();
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    @Test
    void aMemberWithoutVisionIsToldTheImageWasThere() {
        String body = request("", user("[{\"type\":\"text\",\"text\":\"look\"}," + IMAGE + "]"));

        Wire wire = wire(body, PLAIN);

        JsonNode content = message(wire, 0).path("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(1).path("text").stringValue()).isEqualTo("[image omitted]");
        assertThat(wire.body().toString()).doesNotContain("image_url", PNG);
        assertThat(wire.degraded()).containsExactly("image");
    }

    @Test
    void aTurnThatIsOnlyAnImageIsNotEmptyToAnyMember() {
        String body = request("", user("[" + IMAGE + "]"));

        assertThat(message(wire(body, VISION), 0).path("content").get(0).path("image_url").path("url").stringValue()).isEqualTo(DATA_URI);
        Wire plain = wire(body, PLAIN);
        assertThat(message(plain, 0).path("content").get(0).path("text").stringValue()).isEqualTo("[image omitted]");
        // The generic placeholder of a turn with nothing in it is not what the model is told.
        assertThat(plain.degraded()).containsExactly("image");
        assertThat(plain.body().toString()).doesNotContain("[non-text content omitted]");
    }

    @Test
    void anImageFromAnAddressIsGivenAsItsAddressAndOneNoRelayCanReadIsReplaced() {
        String url = "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://images.example/cat.png\"}}";
        String file = "{\"type\":\"image\",\"source\":{\"type\":\"file\",\"file_id\":\"file_011\"}}";
        String local = "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"file:///etc/passwd\"}}";

        Wire wire = wire(request("", user("[" + url + "," + file + "," + local + "]")), VISION);

        JsonNode content = message(wire, 0).path("content");
        assertThat(content.get(0).path("image_url").path("url").stringValue()).isEqualTo("https://images.example/cat.png");
        assertThat(content.get(1).path("text").stringValue()).isEqualTo("[image omitted]");
        assertThat(content.get(2).path("text").stringValue()).isEqualTo("[image omitted]");
        assertThat(wire.body().toString()).doesNotContain("file_011", "/etc/passwd");
        assertThat(wire.degraded()).containsExactly("image");
    }

    @Test
    void theAddressOfAnImageIsOnlyAnHttpOrHttpsOneOrADataUriOfAnImageType() {
        assertThat(AnthropicChatWireConverter.imageUrl(tree(IMAGE))).isEqualTo(DATA_URI);
        assertThat(AnthropicChatWireConverter.imageUrl(tree(
            "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"HTTP://x.example/a.png\"}}"))).isEqualTo("HTTP://x.example/a.png");
        for (String source : List.of(
            "{\"type\":\"base64\",\"media_type\":\"text/html\",\"data\":\"QUJD\"}",
            "{\"type\":\"base64\",\"media_type\":\"image/png;charset=x\",\"data\":\"QUJD\"}",
            "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"\"}",
            "{\"type\":\"base64\",\"media_type\":\"image/png\"}",
            "{\"type\":\"url\",\"url\":\"\"}",
            "{\"type\":\"url\",\"url\":\"javascript:alert(1)\"}",
            "{\"type\":\"url\",\"url\":\"data:text/html,x\"}",
            "{\"type\":\"file\",\"file_id\":\"f\"}",
            "{\"type\":\"content\"}",
            "\"base64\"")) {
            assertThat(AnthropicChatWireConverter.imageUrl(tree("{\"type\":\"image\",\"source\":" + source + "}"))).as(source).isNull();
        }
        assertThat(AnthropicChatWireConverter.imageUrl(tree("{\"type\":\"image\"}"))).isNull();
    }

    @Test
    void theImagesAfterToolResultsAreTheUsersTurnAfterTheirToolMessages() {
        String history = "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{}}]}";
        String answer = user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"done\"},"
            + "{\"type\":\"text\",\"text\":\"and this one\"}," + IMAGE + "]");

        Wire wire = wire(request("\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}]", user("\"go\""), history, answer), VISION);

        assertThat(message(wire, 2).path("role").stringValue()).isEqualTo("tool");
        assertThat(message(wire, 2).path("content").stringValue()).isEqualTo("done");
        JsonNode last = message(wire, 3);
        assertThat(last.path("role").stringValue()).isEqualTo("user");
        assertThat(last.path("content").get(0).path("text").stringValue()).isEqualTo("and this one");
        assertThat(last.path("content").get(1).path("image_url").path("url").stringValue()).isEqualTo(DATA_URI);
    }

    @Test
    void anImageOfTheAssistantHasNoPlaceAndNoMemberIsPromisedIt() {
        String body = request("", user("\"q\""),
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"here\"}," + IMAGE + "]}", user("\"and?\""));

        Wire wire = wire(body, BOTH);

        assertThat(message(wire, 1).path("content").stringValue()).isEqualTo("here");
        assertThat(wire.body().toString()).doesNotContain("image_url", PNG);
        assertThat(RequestFeatureDetector.detect(Entry.MESSAGES, tree(body)).imageNotCarried()).isTrue();
    }

    // ---------------------------------------------------------------------------------------- the image in a tool result

    @Test
    void anImageInAToolResultIsSaidToHaveBeenThereAndNotTheEmptyOutputOfAToolThatReturnedNothing() {
        String history = "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{}}]}";
        String answer = user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[" + IMAGE + "]}]");

        for (Settings member : List.of(PLAIN, BOTH)) {
            Wire wire = wire(request("\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}]", user("\"go\""), history, answer), member);

            assertThat(message(wire, 2).path("content").stringValue()).isEqualTo("[image omitted]");
            assertThat(wire.body().toString()).doesNotContain("[no output]", "image_url", PNG);
            assertThat(wire.degraded()).doesNotContain("empty_tool_result");
        }
    }

    @Test
    void textAndImagesOfAToolResultAreTheTextThenAMarkForEachImage() {
        JsonNode chat = CONVERTER.toChat(tree(request("", user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":["
            + "{\"type\":\"text\",\"text\":\"screenshot taken\"}," + IMAGE + "," + IMAGE + "]}]"))));

        assertThat(chat.path("messages").get(0).path("content").stringValue()).isEqualTo("screenshot taken\n[image omitted]\n[image omitted]");
    }

    // ---------------------------------------------------------------------------------------- the failure of a tool result

    @Test
    void aToolResultThatFailedStartsWithTheWordForIt() {
        for (String[] pair : new String[][] {
            {"\"content\":\"File does not exist.\"", "Error: File does not exist."},
            {"\"content\":\"\"", "Error"},
            {"", "Error"},
            {"\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]", "Error: a\nb"},
            {"\"content\":[" + IMAGE + "]", "Error: [image omitted]"},
        }) {
            String result = "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\"," + (pair[0].isEmpty() ? "" : pair[0] + ",") + "\"is_error\":true}";

            JsonNode chat = CONVERTER.toChat(tree(request("", user("[" + result + "]"))));

            assertThat(chat.path("messages").get(0).path("content").stringValue()).as(pair[0]).isEqualTo(pair[1]);
        }
    }

    @Test
    void aToolResultThatDidNotFailIsTheTextOfTheNativeConversionWhateverItsFlagSays() {
        for (String flag : List.of("", ",\"is_error\":false", ",\"is_error\":null", ",\"is_error\":\"true\"")) {
            JsonNode chat = CONVERTER.toChat(tree(request("", user(
                "[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"fine\"" + flag + "}]"))));

            assertThat(chat.path("messages").get(0).path("content").stringValue()).as(flag).isEqualTo("fine");
        }
    }

    @Test
    void theFailureIsInTheBodyEveryMemberIsSent() {
        String history = "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{}}]}";
        String answer = user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"boom\",\"is_error\":true}]");

        for (Settings member : List.of(PLAIN, VISION, CACHE, BOTH)) {
            Wire wire = wire(request("\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}]", user("\"go\""), history, answer), member);

            assertThat(message(wire, 2).path("content").stringValue()).isEqualTo("Error: boom");
            assertThat(wire.degraded()).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------- cache control

    private static final String CLAUDE_CODE_SYSTEM = "\"system\":[{\"type\":\"text\",\"text\":\"x-anthropic-billing-header: cc_version=2.1.168.177; cch=278c4;\"},"
        + "{\"type\":\"text\",\"text\":\"You are a Claude agent.\"," + EPHEMERAL + "},"
        + "{\"type\":\"text\",\"text\":\"\\nYou help with software engineering.\"," + EPHEMERAL + "}]";

    @Test
    void theBlocksOfSystemThatCarryACacheControlAreSentAsPartsToAMemberThatPassesItThrough() {
        String body = request(CLAUDE_CODE_SYSTEM, user("\"hi\""));

        Wire wire = wire(body, CACHE);

        JsonNode system = message(wire, 0).path("content");
        assertThat(system).hasSize(2);
        assertThat(system.get(0).path("text").stringValue()).isEqualTo("You are a Claude agent.");
        assertThat(system.get(0).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");
        assertThat(system.get(1).path("text").stringValue()).isEqualTo("\nYou help with software engineering.");
        assertThat(system.get(1).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");
        assertThat(wire.body().toString()).doesNotContain("cache_parts", "x-anthropic-billing-header");
        assertThat(wire.degraded()).containsExactly("billing_header_dropped");
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
    }

    @Test
    void theSameSystemIsTheOneStringItWasToAMemberThatDoesNot() {
        String body = request(CLAUDE_CODE_SYSTEM, user("\"hi\""));

        Wire wire = wire(body, PLAIN);

        assertThat(message(wire, 0).path("content").stringValue()).isEqualTo("You are a Claude agent.\n\nYou help with software engineering.");
        assertThat(wire.body().toString()).doesNotContain("cache_control", "cache_parts");
        assertThat(wire.degraded()).containsExactlyInAnyOrder("cache_control", "billing_header_dropped");
        assertThat(wire.body()).isEqualTo(phaseFiveWire(tree(body), PLAIN).body());
    }

    @Test
    void aSystemThatHadOnlyTheHeaderAndACacheControlOnItIsNotSentToAnyMember() {
        String body = request("\"system\":[{\"type\":\"text\",\"text\":\"x-anthropic-billing-header: cc_version=1; cch=a;\"," + EPHEMERAL + "}]",
            user("\"hi\""));

        for (Settings member : List.of(PLAIN, CACHE)) {
            Wire wire = wire(body, member);

            assertThat(wire.body().path("messages")).hasSize(1);
            assertThat(message(wire, 0).path("role").stringValue()).isEqualTo("user");
        }
    }

    @Test
    void theTextOfATurnThatCarriesACacheControlIsAPartWithItForAMemberThatPassesItThroughAndTheStringItWasForOthers() {
        String body = request("", user("[{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"," + EPHEMERAL + ",\"x\":1}]"));

        // The text is cut after a block that carries a cache control and not elsewhere: this one is a single part, with the bytes
        // of the string the message is sent as to a member that takes no cache control (or once the breakpoint has moved on).
        JsonNode withParts = message(wire(body, CACHE), 0).path("content");
        assertThat(withParts).hasSize(1);
        assertThat(withParts.get(0).path("text").stringValue()).isEqualTo("first\nsecond");
        assertThat(withParts.get(0).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");

        Wire plain = wire(body, PLAIN);
        assertThat(message(plain, 0).path("content").stringValue()).isEqualTo("first\nsecond");
        assertThat(plain.degraded()).containsExactly("cache_control");
    }

    @Test
    void aTurnIsCutAfterEveryBlockThatCarriesACacheControlAndNotWhereNoneIs() {
        String blocks = "[{\"type\":\"text\",\"text\":\"a\"," + EPHEMERAL + "},{\"type\":\"text\",\"text\":\"b\"},"
            + "{\"type\":\"text\",\"text\":\" \"},{\"type\":\"text\",\"text\":\"c\"," + EPHEMERAL + "},{\"type\":\"text\",\"text\":\"d\"}]";

        JsonNode parts = message(wire(request("", user(blocks)), CACHE), 0).path("content");

        assertThat(parts.size()).isEqualTo(3);
        assertThat(parts.get(0).path("text").stringValue()).isEqualTo("a");
        assertThat(parts.get(0).has("cache_control")).isTrue();
        assertThat(parts.get(1).path("text").stringValue()).isEqualTo("b\nc");
        assertThat(parts.get(1).has("cache_control")).isTrue();
        assertThat(parts.get(2).path("text").stringValue()).isEqualTo("d");
        assertThat(parts.get(2).has("cache_control")).isFalse();
    }

    /**
     * The first message of Claude Code is several text blocks, the breakpoint on the last. Once the breakpoint has moved on to a
     * later turn the same message is sent as the string, and a relay that caches by what it renders must find the text it cached
     * the request before: the one part of that request is the one block the string is.
     */
    @Test
    void aMessageWhoseBreakpointHasMovedOnIsTheSameTextAsWhenItHadOne() {
        String first = "[{\"type\":\"text\",\"text\":\"<reminder>A</reminder>\"},{\"type\":\"text\",\"text\":\"<reminder>B</reminder>\"},"
            + "{\"type\":\"text\",\"text\":\"the task\"," + EPHEMERAL + "}]";
        String later = first.replace("," + EPHEMERAL, "");
        String reply = "{\"role\":\"assistant\",\"content\":\"done\"}";

        JsonNode turnOne = message(wire(request("", user(first)), CACHE), 0).path("content");
        JsonNode turnThree = message(wire(request("", user(later), reply, user("\"next\"")), CACHE), 0).path("content");

        assertThat(turnOne.isArray()).isTrue();
        assertThat(turnOne).hasSize(1);
        assertThat(turnThree.isString()).isTrue();
        assertThat(turnOne.get(0).path("text").stringValue()).isEqualTo(turnThree.stringValue());
        // And for a member that takes no cache control it is the string at both requests, as it always was.
        assertThat(message(wire(request("", user(first)), PLAIN), 0).path("content").stringValue()).isEqualTo(turnThree.stringValue());
    }

    @Test
    void aDocumentInAToolResultIsToldAsPresentAndNotAsNoOutput() {
        String history = "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{}}]}";
        String document = "{\"type\":\"document\",\"source\":{\"type\":\"base64\",\"media_type\":\"application/pdf\",\"data\":\"JVBERi0=\"}}";
        String tools = "\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}]";

        Wire alone = wire(request(tools, user("\"go\""), history,
            user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[" + document + "]}]")), PLAIN);
        Wire mixed = wire(request(tools, user("\"go\""), history,
            user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[{\"type\":\"text\",\"text\":\"the file\"}," + document + "," + IMAGE + "]}]")), PLAIN);

        assertThat(message(alone, 2).path("content").stringValue()).isEqualTo("[file omitted]");
        assertThat(alone.degraded()).doesNotContain(ChatCanonicalizer.EMPTY_TOOL_RESULT);
        assertThat(message(mixed, 2).path("content").stringValue()).isEqualTo("the file\n[file omitted]\n[image omitted]");
    }

    @Test
    void theCacheControlOfAnImageInAToolResultIsTheOneOfItsToolMessage() {
        String history = "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{}}]}";
        String marked = IMAGE.substring(0, IMAGE.length() - 1) + "," + EPHEMERAL + "}";
        String body = request("\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}]", user("\"go\""), history,
            user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[" + marked + "]}]"));

        Wire passed = wire(body, CACHE);

        assertThat(message(passed, 2).path("content").stringValue()).isEqualTo("[image omitted]");
        assertThat(message(passed, 2).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");
        assertThat(passed.degraded()).doesNotContain("cache_control");
        assertThat(wire(body, PLAIN).degraded()).contains("cache_control");
    }

    /**
     * A member that cannot see replaces a screenshot with a text that says it was there; when the screenshot is the last block of
     * the turn it carries the breakpoint of the request, and the text that took its place carries it now.
     */
    @Test
    void theCacheControlOfAnImageAMemberCannotSeeStaysOnTheTextThatTakesItsPlace() {
        String marked = IMAGE.substring(0, IMAGE.length() - 1) + "," + EPHEMERAL + "}";
        String body = request("", user("[{\"type\":\"text\",\"text\":\"look\"}," + marked + "]"));

        Wire passed = wire(body, CACHE);
        Wire seeing = wire(body, BOTH);
        Wire plain = wire(body, PLAIN);

        JsonNode parts = message(passed, 0).path("content");
        assertThat(parts.size()).isEqualTo(2);
        assertThat(parts.get(0).path("text").stringValue()).isEqualTo("look");
        assertThat(parts.get(0).has("cache_control")).isFalse();
        assertThat(parts.get(1).path("text").stringValue()).isEqualTo("[image omitted]");
        assertThat(parts.get(1).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");
        assertThat(passed.degraded()).containsExactly("image");
        assertThat(message(seeing, 0).path("content").get(1).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");
        assertThat(plain.body().toString()).doesNotContain("cache_control");
        assertThat(plain.degraded()).contains("image", "cache_control");
    }

    @Test
    void theDetectorAsksIfAnImageIsCarriedWithoutBuildingItsAddress() {
        for (String block : List.of(IMAGE, "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://x.example/a.png\"}}",
            "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"ftp://x.example/a.png\"}}",
            "{\"type\":\"image\",\"source\":{\"type\":\"file\",\"file_id\":\"f1\"}}",
            "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"text/plain\",\"data\":\"AAAA\"}}",
            "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\" \"}}", "{\"type\":\"image\"}")) {
            JsonNode image = tree(block);
            assertThat(AnthropicChatWireConverter.carriesImage(image)).as(block).isEqualTo(AnthropicChatWireConverter.imageUrl(image) != null);
        }
        assertThat(AnthropicChatWireConverter.imageUrl(tree(IMAGE))).isEqualTo(DATA_URI);
    }

    @Test
    void theTimeToLiveOfACacheControlIsKeptAndNothingElseOfIt() {
        String body = request("", user("[{\"type\":\"text\",\"text\":\"t\",\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\",\"x\":1}}]"));

        JsonNode control = message(wire(body, CACHE), 0).path("content").get(0).path("cache_control");

        assertThat(control.toString()).isEqualTo("{\"type\":\"ephemeral\",\"ttl\":\"1h\"}");
    }

    @Test
    void aToolResultThatCarriesACacheControlIsAToolMessageThatCarriesIt() {
        String history = "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{}}]}";
        String answer = user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"done\"," + EPHEMERAL + "}]");
        String body = request("\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}]", user("\"go\""), history, answer);

        Wire passed = wire(body, CACHE);
        Wire dropped = wire(body, PLAIN);

        assertThat(message(passed, 2).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");
        assertThat(message(passed, 2).path("content").stringValue()).isEqualTo("done");
        assertThat(passed.degraded()).isEmpty();
        assertThat(dropped.body().toString()).doesNotContain("cache_control");
        assertThat(dropped.degraded()).containsExactly("cache_control");
    }

    @Test
    void anAssistantTurnWithCallsCarriesTheCacheControlOfItsBlocksOnTheMessage() {
        String history = "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"reading\"},"
            + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{}," + EPHEMERAL + "}]}";
        String answer = user("[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"done\"}]");
        String body = request("\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}]", user("\"go\""), history, answer);

        Wire wire = wire(body, CACHE);

        assertThat(message(wire, 1).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");
        assertThat(message(wire, 1).path("tool_calls")).hasSize(1);
    }

    @Test
    void theCacheControlOfTheRequestMarksTheLastMessage() {
        String body = request("\"cache_control\":{\"type\":\"ephemeral\"}", user("\"one\""), "{\"role\":\"assistant\",\"content\":\"two\"}", user("\"three\""));

        Wire passed = wire(body, CACHE);
        Wire dropped = wire(body, PLAIN);

        assertThat(message(passed, 2).path("cache_control").path("type").stringValue()).isEqualTo("ephemeral");
        assertThat(message(passed, 0).has("cache_control")).isFalse();
        assertThat(passed.degraded()).isEmpty();
        assertThat(dropped.body().toString()).doesNotContain("cache_control");
        assertThat(dropped.degraded()).containsExactly("cache_control");
    }

    @Test
    void aCacheControlOnAToolDefinitionHasNoPlaceInAFunctionAndIsNotInTheBody() {
        String body = request("\"tools\":[{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}," + EPHEMERAL + "}]", user("\"hi\""));

        Wire wire = wire(body, CACHE);

        assertThat(wire.body().toString()).doesNotContain("cache_control");
        assertThat(wire.body().path("tools")).hasSize(1);
    }

    @Test
    void aCacheControlThatIsNotInAClientBodyOfTheChatEntryIsNotReadFromItsCachePartsKey() {
        JsonNode chat = tree("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"real\","
            + "\"cache_parts\":[{\"type\":\"text\",\"text\":\"smuggled\"}]}]}");

        Wire wire = ChatWireSanitizer.sanitize(chat, null, CACHE, "relay-model");

        assertThat(wire.body().path("messages").get(0).path("content").stringValue()).isEqualTo("real");
        assertThat(wire.body().toString()).doesNotContain("smuggled", "cache_parts");
    }

    // ---------------------------------------------------------------------------------------- the rest

    @Test
    void theRequestIsNotChangedAndTheBodySharesNothingThatCouldChangeIt() {
        JsonNode request = tree(request(CLAUDE_CODE_SYSTEM + ",\"cache_control\":{\"type\":\"ephemeral\"},\"metadata\":{\"user_id\":\"u\"},"
            + "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2000},\"stop_sequences\":[\"a\"],"
            + "\"tool_choice\":{\"type\":\"auto\",\"extra\":{\"x\":1}}",
            user("[{\"type\":\"text\",\"text\":\"look\"," + EPHEMERAL + "}," + IMAGE + "]")));
        JsonNode snapshot = request.deepCopy();

        ObjectNode chat = CONVERTER.toChat(request);
        for (String field : List.of("metadata", "thinking")) {
            ((ObjectNode) chat.get(field)).put("mutated", true);
        }
        ((ArrayNode) chat.get("stop_sequences")).add("mutated");
        chat.path("messages").forEach(message -> {
            if (message instanceof ObjectNode object) {
                object.put("role", "mutated");
                if (object.path("cache_parts") instanceof ArrayNode parts) {
                    parts.removeAll();
                }
                if (object.path("content") instanceof ArrayNode parts) {
                    parts.removeAll();
                }
                if (object.path("cache_control") instanceof ObjectNode control) {
                    control.put("type", "mutated");
                }
            }
        });

        assertThat(request).isEqualTo(snapshot);
    }

    @Test
    void oddShapesAreConvertedWithoutFailingAndLikeTheNativeConversionDoes() {
        for (String odd : List.of(
            "{\"messages\":[null,1,\"x\",{\"role\":5,\"content\":[null,3,{\"type\":7}]},{\"content\":{\"type\":\"text\"}}]}",
            "{\"model\":\"m\",\"system\":[1,null,{\"type\":\"text\",\"text\":5,\"cache_control\":3}],\"messages\":[]}",
            "{\"model\":\"m\",\"cache_control\":1,\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}",
            "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":3},{\"type\":\"tool_result\",\"is_error\":true}]}]}",
            "{\"model\":\"m\",\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\"},{\"type\":\"image\"}]}]}")) {
            JsonNode request = tree(odd);
            ObjectNode chat = CONVERTER.toChat(request);
            assertThat(chat.path("messages")).isNotNull();
            // Nothing of these is anything the new conversion has a place for, or all of it is read like a client's own.
            Wire wire = ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, request, BOTH, "relay-model"), BOTH);
            assertThat(StrictChatLint.problems(wire.body())).as(odd).isEmpty();
        }
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> CONVERTER.toChat(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CONVERTER.toChat(tree("[]"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatTheConverterAddsNeverReachesTheLintOrTheRelay() {
        Random random = new Random(20261031);
        for (int serial = 0; serial < 600; serial++) {
            ObjectNode request = ChatWirePipelineTest.randomAnthropicRequest(random, serial);
            addCacheControls(request, random);
            JsonNode snapshot = request.deepCopy();
            for (Settings member : List.of(PLAIN, VISION, CACHE, BOTH)) {
                ObjectNode chat = CONVERTER.toChat(request);
                Wire wire = ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, request, member, "relay-model"), member);
                String where = "request " + serial + ", " + member + ": " + snapshot;
                assertThat(StrictChatLint.problems(wire.body())).as(where).isEmpty();
                assertThat(wire.body().toString()).as(where).doesNotContain("cache_parts", "SECRET-USER", "top_k");
                assertThat(ChatCanonicalizer.canonicalize(wire, member).body()).as(where).isEqualTo(wire.body());
                if (!member.cacheControlPassthrough()) {
                    assertThat(wire.body().toString()).as(where).doesNotContain("cache_control");
                }
                if (!member.vision()) {
                    assertThat(wire.body().toString()).as(where).doesNotContain("image_url", "QUJD");
                }
            }
            assertThat(request).as("request %d is not changed", serial).isEqualTo(snapshot);
        }
    }

    @Test
    void theTextOfEveryRequestIsInTheBodyOfEveryMember() {
        Random random = new Random(20261101);
        for (int serial = 0; serial < 300; serial++) {
            ObjectNode request = ChatWirePipelineTest.randomAnthropicRequest(random, serial);
            addCacheControls(request, random);
            List<String> wanted = new ArrayList<>();
            for (JsonNode message : request.path("messages")) {
                collectTexts(message.path("content"), wanted);
            }
            for (Settings member : List.of(PLAIN, BOTH)) {
                Wire wire = ChatCanonicalizer.canonicalize(
                    ChatWireSanitizer.sanitize(CONVERTER.toChat(request), request, member, "relay-model"), member);
                String sent = wire.body().toString();
                for (String text : wanted) {
                    if (!text.isBlank()) {
                        String escaped = JSON.writeValueAsString(text);
                        assertThat(sent).as("request %d, %s lost %s", serial, member, text)
                            .contains(escaped.substring(1, escaped.length() - 1));
                    }
                }
            }
        }
    }

    private static void collectTexts(JsonNode content, List<String> into) {
        if (content.isString()) {
            into.add(content.stringValue());
        } else if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").stringValue("");
                if ("text".equals(type)) {
                    into.add(block.path("text").stringValue(""));
                } else if ("tool_result".equals(type)) {
                    collectTexts(block.path("content"), into);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------- the corpus

    /**
     * Requests of every shape the tests of the pipeline know, plus the first request of every fixture case of the Messages pairs
     * and the conversations they go on to (the follow-ups, which are tool results that follow a call).
     */
    private static List<JsonNode> corpus() {
        List<JsonNode> corpus = new ArrayList<>(fixtureRequests());
        Random random = new Random(20261030);
        for (int serial = 0; serial < 600; serial++) {
            ObjectNode request = ChatWirePipelineTest.randomAnthropicRequest(random, serial);
            if (serial % 2 == 0) {
                addCacheControls(request, random);
            }
            corpus.add(request);
        }
        return corpus;
    }

    private static List<JsonNode> fixtureRequests() {
        List<JsonNode> requests = new ArrayList<>();
        Path root = XprotoFixtures.classpathRoot();
        for (String vendor : List.of("anthropic", "openai")) {
            for (Case fixture : XprotoFixtures.cases(root, new PairKey(SourceKind.OPENAI_CHAT_RELAY, Entry.MESSAGES, vendor))) {
                JsonNode first = tree(new String(fixture.input("request.json"), StandardCharsets.UTF_8));
                requests.add(first);
                Path turns = fixture.directory().resolve(XprotoFixtures.INPUT).resolve("turns");
                if (!Files.isDirectory(turns)) {
                    continue;
                }
                ObjectNode history = (ObjectNode) first.deepCopy();
                ArrayNode messages = history.withArray("messages");
                try (var stream = Files.list(turns)) {
                    for (Path turn : stream.sorted().toList()) {
                        Path followUp = turn.resolve("follow-up.json");
                        if (!Files.exists(followUp)) {
                            continue;
                        }
                        String text = Files.readString(followUp, StandardCharsets.UTF_8).replace("${tool.0}", "toolu_0")
                            .replace("${tool.1}", "toolu_1").replace("${tool.2}", "toolu_2");
                        ObjectNode call = messages.addObject().put("role", "assistant");
                        ArrayNode blocks = call.putArray("content");
                        blocks.addObject().put("type", "text").put("text", "Let me look.");
                        for (int i = 0; i < 3; i++) {
                            blocks.addObject().put("type", "tool_use").put("id", "toolu_" + i).put("name", "Read").putObject("input");
                        }
                        tree(text).forEach(messages::add);
                        requests.add(history.deepCopy());
                    }
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
            }
        }
        return requests;
    }

    /** Cache controls where a client puts them: blocks of the system prompt, the last block of a turn, a tool result, the request. */
    private static void addCacheControls(ObjectNode request, Random random) {
        if (request.path("system").isArray()) {
            for (JsonNode block : request.path("system")) {
                if (block instanceof ObjectNode object && random.nextBoolean()) {
                    object.putObject("cache_control").put("type", "ephemeral");
                }
            }
        }
        for (JsonNode message : request.path("messages")) {
            if (message.path("content") instanceof ArrayNode blocks && !blocks.isEmpty() && random.nextInt(3) == 0) {
                JsonNode block = blocks.get(random.nextInt(blocks.size()));
                if (block instanceof ObjectNode object) {
                    object.putObject("cache_control").put("type", "ephemeral").put("ttl", random.nextBoolean() ? "1h" : "5m");
                }
            }
        }
        if (random.nextInt(8) == 0) {
            request.putObject("cache_control").put("type", "ephemeral");
        }
    }

    /** The same request without the images, the cache controls and the failed tool results: nothing the conversion has a new place for. */
    private static JsonNode withoutWhatIsNew(JsonNode request) {
        return withoutImagesAndErrors(withoutCacheControl(request));
    }

    private static JsonNode withoutCacheControl(JsonNode request) {
        ObjectNode copy = (ObjectNode) request.deepCopy();
        copy.remove("cache_control");
        removeCacheControl(copy.path("system"));
        removeCacheControl(copy.path("tools"));
        copy.path("messages").forEach(message -> {
            removeCacheControl(message.path("content"));
            message.path("content").forEach(block -> removeCacheControl(block.path("content")));
        });
        return copy;
    }

    private static void removeCacheControl(JsonNode blocks) {
        blocks.forEach(block -> {
            if (block instanceof ObjectNode object) {
                object.remove("cache_control");
            }
        });
    }

    private static JsonNode withoutImagesAndErrors(JsonNode request) {
        ObjectNode copy = (ObjectNode) request.deepCopy();
        copy.path("messages").forEach(message -> {
            if (message.path("content") instanceof ArrayNode blocks) {
                removeImages(blocks);
                blocks.forEach(block -> {
                    if (block instanceof ObjectNode object) {
                        object.remove("is_error");
                        if (object.path("content") instanceof ArrayNode nested) {
                            removeImages(nested);
                            // The result of a tool that is a document is told as present now, where it used to vanish.
                            for (int i = nested.size() - 1; i >= 0; i--) {
                                if ("document".equals(nested.get(i).path("type").asString(""))) {
                                    nested.remove(i);
                                }
                            }
                        }
                    }
                });
            }
        });
        return copy;
    }

    private static void removeImages(ArrayNode blocks) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if ("image".equals(blocks.get(i).path("type").stringValue(""))) {
                blocks.remove(i);
            }
        }
    }
}
