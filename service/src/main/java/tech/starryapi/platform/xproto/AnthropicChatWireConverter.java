package tech.starryapi.platform.xproto;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a Messages request into the Chat Completions body that {@link ChatWireSanitizer} makes a relay's request from
 * (design 7.6, MESSAGES, phase 8). The entry's own conversion, {@code AnthropicRequestAdapter#messagesToChat}, is what Kiro
 * and the Anthropic keys are given, and it stays as it is because their requests must not change (design 0, I4); it flattens
 * every content to text, so what Claude Code sends a relay was text and nothing else. Here the things a relay can take and
 * the native accounts could not are kept, and everything else is made by the same code as the native conversion
 * ({@code AnthropicRequestAdapter}'s shared methods: the fields that stay fields, the text of a content, the tools and the
 * tool choice), so that a request none of those things is in is the same Chat body from both, which {@code
 * AnthropicChatWireConverterTest} holds them to:
 *
 * <ul>
 *   <li><b>Images.</b> An {@code image} block of a user turn is an {@code image_url} part, in its place among the text parts
 *       of the turn: a {@code base64} source is a data URI, a {@code url} source (http or https) its address. A source the
 *       conversion has nothing to give a relay for (a {@code file} of the Files API, no data, an address of another kind) is
 *       a part without a {@code url}, which the sanitizer replaces and records as it does the image of a member without
 *       vision; {@link RequestFeatureDetector} asks {@link #imageUrl} the same question, so the policy does not promise a
 *       relay an image it is not given. A turn with an image is a list of parts; one without is the string the native
 *       conversion makes. The sanitizer keeps the parts for a member with vision and puts {@code [image omitted]} in their
 *       place for one without.
 *   <li><b>The image in a tool result</b> has no place: a tool message of Chat holds text, and the sanitizer replaces an image
 *       in any message but the user's. The tool message says it was there, {@code [image omitted]} for each, after the
 *       text of the result, where it used to be the empty string that the canonicalizer would have made {@code [no output]}
 *       of, as if the tool had returned nothing. The detector names it {@code tool_output_image}, apart from the images of
 *       the user.
 *   <li><b>{@code tool_result.is_error}.</b> A tool message has no flag for it. The text of the result starts with {@code
 *       Error: } (a result with no text is {@code Error}), which is how a model is told a tool failed in a format that has no
 *       word for it, and nothing is sent that a relay could refuse.
 *   <li><b>{@code cache_control}.</b> The breakpoints of the client, where Chat can hold them: on the text of a turn, which
 *       is where Claude Code puts them (on the blocks of {@code system}, and on the last block of the last turns), as a
 *       {@code cache_control} on the part when the member passes it through, and on the message for what is a message of its
 *       own in Chat (a tool result, an assistant turn with calls); the {@code cache_control} of the request itself (automatic
 *       caching) marks the last message. A cache control on a tool definition has no place in a function of Chat and is not
 *       kept (the first breakpoint after the tools covers them). Which form a message takes depends on the member: the
 *       conversion cannot know the member, and a member that does not pass cache control through must be sent what it always
 *       was, one string. So a message with text blocks that carry one has both: {@code content}, the string the native
 *       conversion makes, and {@code cache_parts} ({@link ChatWireSanitizer#CACHE_PARTS}), the same text as parts that carry
 *       their cache controls, of which the sanitizer sends the one its member takes and neither leaves the pipeline. At most
 *       four blocks of a request can carry a breakpoint, so the second copy of the text is small.
 * </ul>
 *
 * <p>The rest is the native conversion's: the text of a turn is its non-blank text blocks a newline apart (the calls of an
 * assistant turn make it the text blocks, blank ones too, as the native conversion does), a turn of the user that has tool
 * results is their tool messages, in order, then a user message with what else it said, and the thinking blocks, documents
 * and the blocks of the tools Anthropic runs are left out. The request is not changed, and the result shares with it only
 * what cannot be edited through the result.
 */
@Component
public final class AnthropicChatWireConverter {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    /** How a tool result that failed starts; one with no text is {@link #ERROR_ALONE}. */
    static final String ERROR_PREFIX = "Error: ";
    static final String ERROR_ALONE = "Error";

    private static final Pattern IMAGE_MEDIA_TYPE = Pattern.compile("^image/[A-Za-z0-9.+-]{1,64}$");

    private final ObjectMapper objectMapper;

    public AnthropicChatWireConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param request the Messages request as the client sent it
     * @return the Chat Completions body, new and not shared with the request in anything that could be edited
     */
    public ObjectNode toChat(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("The Messages request must be a JSON object");
        }
        ObjectNode chat = AnthropicRequestAdapter.chatShell(request, "");
        ArrayNode messages = chat.putArray("messages");
        if (request.hasNonNull("system")) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", AnthropicRequestAdapter.contentText(request.path("system")));
            ArrayNode cached = cachedTexts(request.path("system"));
            if (cached != null) {
                system.set(ChatWireSanitizer.CACHE_PARTS, cached);
            }
        }
        JsonNode input = request.path("messages");
        if (input.isArray()) {
            for (JsonNode item : input) {
                message(messages, item);
            }
        }
        AnthropicRequestAdapter.putTools(objectMapper, request, chat);
        // The native conversion hands these on as they are, containers included; the body here is given its own copies.
        for (String field : List.of("metadata", "thinking", "stop_sequences", "tool_choice")) {
            JsonNode value = chat.get(field);
            if (value != null && value.isContainer()) {
                chat.set(field, value.deepCopy());
            }
        }
        ObjectNode control = cacheControl(request);
        if (control != null && !messages.isEmpty() && messages.get(messages.size() - 1) instanceof ObjectNode last
            && !last.has("cache_control")) {
            last.set("cache_control", control);
        }
        return chat;
    }

    // ---- the messages ----

    private void message(ArrayNode messages, JsonNode item) {
        boolean assistant = "assistant".equals(item.path("role").asString("user"));
        String role = assistant ? "assistant" : "user";
        JsonNode content = item.path("content");
        if (!content.isArray()) {
            messages.addObject().put("role", role).put("content", AnthropicRequestAdapter.contentText(content));
            return;
        }
        if (assistant && AnthropicRequestAdapter.hasContentType(content, "tool_use")) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant");
            message.put("content", AnthropicRequestAdapter.textOnly(content));
            ArrayNode calls = message.putArray("tool_calls");
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asString(""))) {
                    calls.add(AnthropicRequestAdapter.toolCall(block));
                }
            }
            ObjectNode control = firstCacheControl(content, "text", "tool_use");
            if (control != null) {
                message.set("cache_control", control);
            }
            return;
        }
        if (!assistant && AnthropicRequestAdapter.hasContentType(content, "tool_result")) {
            for (JsonNode block : content) {
                if ("tool_result".equals(block.path("type").asString(""))) {
                    toolMessage(messages, block);
                }
            }
            turn(messages, role, content, true);
            return;
        }
        turn(messages, role, content, false);
    }

    private void toolMessage(ArrayNode messages, JsonNode result) {
        ObjectNode message = messages.addObject();
        message.put("role", "tool");
        message.put("tool_call_id", result.path("tool_use_id").asString(""));
        message.put("content", toolResultContent(result));
        ObjectNode control = cacheControl(result);
        if (control == null && result.path("content").isArray()) {
            control = firstCacheControl(result.path("content"), "text", "image", "document");
        }
        if (control != null) {
            message.set("cache_control", control);
        }
    }

    /**
     * The text of a tool result as the native conversion has it, with what it cannot say added: a mark for each image or document
     * it had, in the order of its blocks (a tool message holds text), then, when the tool failed, the word for it.
     */
    private static String toolResultContent(JsonNode result) {
        String text = AnthropicRequestAdapter.toolResultText(result);
        JsonNode content = result.path("content");
        if (content.isArray()) {
            StringBuilder marked = null;
            for (JsonNode block : content) {
                String mark = switch (block.path("type").asString("")) {
                    case "image" -> ChatWireSanitizer.IMAGE_PLACEHOLDER;
                    case "document" -> ChatWireSanitizer.FILE_PLACEHOLDER;
                    default -> null;
                };
                if (mark != null) {
                    if (marked == null) {
                        marked = new StringBuilder(text.isBlank() ? "" : text);
                    }
                    marked.append(marked.isEmpty() ? "" : "\n").append(mark);
                }
            }
            if (marked != null) {
                text = marked.toString();
            }
        }
        if (result.path("is_error").isBoolean() && result.path("is_error").booleanValue()) {
            text = text.isBlank() ? ERROR_ALONE : ERROR_PREFIX + text;
        }
        return text;
    }

    /**
     * The message of the user or the assistant that the blocks of a turn make, if they make one: the string of their text
     * when no block is an image or carries a cache control, which is what the native conversion makes; the parts in the order
     * of the blocks when one is an image; both the string and the parts that carry the cache controls (see the class
     * description) when only a cache control asks for them.
     *
     * @param afterToolResults the turn has tool results, whose messages are made; the native conversion adds a message for the
     *                         rest of the turn only when it has text
     */
    private void turn(ArrayNode messages, String role, JsonNode blocks, boolean afterToolResults) {
        boolean user = "user".equals(role);
        boolean images = false;
        boolean cached = false;
        for (JsonNode block : blocks) {
            String type = block.path("type").asString("");
            if ("image".equals(type) && user) {
                images = true;
            } else if ("text".equals(type) && !block.path("text").asString("").isBlank() && cacheControl(block) != null) {
                cached = true;
            }
        }
        if (images) {
            ArrayNode parts = parts(blocks, true);
            messages.addObject().put("role", role).set("content", parts);
            return;
        }
        String text = afterToolResults ? AnthropicRequestAdapter.textOnly(blocks) : AnthropicRequestAdapter.contentText(blocks);
        if (afterToolResults && text.isBlank()) {
            return;
        }
        ObjectNode message = messages.addObject();
        message.put("role", role);
        message.put("content", text);
        if (cached) {
            message.set(ChatWireSanitizer.CACHE_PARTS, cachedParts(blocks));
        }
    }

    /**
     * The text of a turn as the parts its cache controls ask for: the text of the blocks, a newline apart as the string of the
     * message has it, cut after each block that carries a cache control and not elsewhere. The breakpoint of Claude Code moves
     * to the last block of the newest turns, so a turn that has it at its end is one part with the bytes of the string it is
     * sent as once the breakpoint has moved on, and a relay that caches by the prefix of what it renders finds its cache of the
     * request before still there; cut in a block of its own per block it would not, for the one request in which it is kept.
     */
    private static ArrayNode cachedParts(JsonNode blocks) {
        ArrayNode parts = FACTORY.arrayNode();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : blocks) {
            if (!"text".equals(block.path("type").asString("")) || block.path("text").asString("").isBlank()) {
                continue;
            }
            text.append(text.isEmpty() ? "" : "\n").append(block.path("text").asString(""));
            ObjectNode control = cacheControl(block);
            if (control != null) {
                textPart(parts, text, control);
            }
        }
        if (!text.isEmpty()) {
            textPart(parts, text, null);
        }
        return parts;
    }

    private static void textPart(ArrayNode parts, StringBuilder text, ObjectNode control) {
        ObjectNode part = parts.addObject();
        part.put("type", "text");
        part.put("text", text.toString());
        if (control != null) {
            part.set("cache_control", control);
        }
        text.setLength(0);
    }

    /** The parts of the text blocks (the blank ones left out) and, for the user, the images, each with its cache control. */
    private static ArrayNode parts(JsonNode blocks, boolean withImages) {
        ArrayNode parts = FACTORY.arrayNode();
        for (JsonNode block : blocks) {
            String type = block.path("type").asString("");
            ObjectNode part = null;
            if ("text".equals(type) && !block.path("text").asString("").isBlank()) {
                part = FACTORY.objectNode();
                part.put("type", "text");
                part.put("text", block.path("text").asString(""));
            } else if ("image".equals(type) && withImages) {
                part = FACTORY.objectNode();
                part.put("type", "image_url");
                ObjectNode target = part.putObject("image_url");
                String url = imageUrl(block);
                if (url != null) {
                    target.put("url", url);
                }
            }
            if (part != null) {
                ObjectNode control = cacheControl(block);
                if (control != null) {
                    part.set("cache_control", control);
                }
                parts.add(part);
            }
        }
        return parts;
    }

    /** The text parts of a {@code system} list with their cache controls; null when none of its blocks carries one. */
    private static ArrayNode cachedTexts(JsonNode content) {
        if (!content.isArray()) {
            return null;
        }
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asString("")) && !block.path("text").asString("").isBlank()
                && cacheControl(block) != null) {
                return parts(content, false);
            }
        }
        return null;
    }

    // ---- images and cache control ----

    /**
     * The address a relay is given for an {@code image} block: a data URI for a base64 source with an image type, the address
     * of a url source when it is an http or https one; null for any source a relay cannot be given (the Files API, no data,
     * another kind of address). The detector asks the same, to tell the images a member could carry from those no member can.
     */
    static String imageUrl(JsonNode block) {
        JsonNode source = block.path("source");
        if (!carriesImage(block)) {
            return null;
        }
        return "base64".equals(source.path("type").asString(""))
            ? "data:" + source.path("media_type").asString("") + ";base64," + source.path("data").asString("")
            : source.path("url").asString("");
    }

    /**
     * Whether {@link #imageUrl} has an address for the block, found out without building it: the data of a screenshot is
     * megabytes, and the detector asks for every image of every request that is read for relays.
     */
    static boolean carriesImage(JsonNode block) {
        JsonNode source = block.path("source");
        return switch (source.path("type").asString("")) {
            case "base64" -> IMAGE_MEDIA_TYPE.matcher(source.path("media_type").asString("")).matches()
                && !source.path("data").asString("").isBlank();
            case "url" -> {
                String lower = source.path("url").asString("").toLowerCase(Locale.ROOT);
                yield lower.startsWith("https://") || lower.startsWith("http://");
            }
            default -> false;
        };
    }

    /** The cache control of a block or of the request, as the sanitizer would make it: {@code ephemeral} and a {@code ttl}; null when there is none. */
    private static ObjectNode cacheControl(JsonNode holder) {
        JsonNode control = holder.path("cache_control");
        if (control.isMissingNode() || control.isNull()) {
            return null;
        }
        ObjectNode rebuilt = FACTORY.objectNode();
        rebuilt.put("type", "ephemeral");
        if (control.path("ttl").isString()) {
            rebuilt.set("ttl", control.path("ttl"));
        }
        return rebuilt;
    }

    /** The cache control of the first block of one of the types that carries one. */
    private static ObjectNode firstCacheControl(JsonNode blocks, String... types) {
        for (JsonNode block : blocks) {
            String type = block.path("type").asString("");
            for (String wanted : types) {
                if (wanted.equals(type)) {
                    ObjectNode control = cacheControl(block);
                    if (control != null) {
                        return control;
                    }
                }
            }
        }
        return null;
    }
}
