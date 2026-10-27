package tech.starryapi.platform.xproto;

import java.util.Set;
import org.springframework.stereotype.Component;
import tech.starryapi.platform.core.ToolSchemaSanitizer;
import tech.starryapi.platform.openai.ApplyPatchEmulation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a Responses request into the Chat Completions body that {@link ChatWireSanitizer} makes a relay's request from
 * (design 7.6, RESPONSES). Codex and the clients like it talk to {@code /v1/responses}; a relay speaks Chat Completions, and
 * the entry's own conversion to Chat, {@code OpenAiRequestAdapter#responsesToChat}, is what Kiro and the Anthropic keys are
 * given, and it stays as it is because their requests must not change (design 0, I4). It is not good enough for a strict
 * relay:
 *
 * <ul>
 *   <li>Every {@code function_call} became an assistant message of its own, so the parallel calls of one model turn were a
 *       chain of single calls. Here the calls that follow each other are the {@code tool_calls} of <em>one</em> assistant
 *       message, and when the model's turn began with text (a {@code message} item right before the calls, with
 *       {@code reasoning} items between that are not carried) they are the calls of that message, which is how a Chat
 *       history has the turn.
 *   <li>{@code tool_choice} was copied as the Responses API writes it, {@code {type:function, name}}, which no relay
 *       knows; it is {@code {type:function, function:{name}}} here. The shapes a relay has no word for ({@code
 *       allowed_tools}, a hosted tool, the choice of a custom tool other than {@code apply_patch}) are handed on as they
 *       are, for the sanitizer to leave out and record: they must not be turned into a choice that means something else.
 *   <li>Every item that was not a message or a call became a user message, including the {@code reasoning} items the
 *       gateway itself writes into the history of a chain ({@code OpenAiResponseStateStore}) and the {@code item_reference}
 *       and the compaction items of Codex. A {@code reasoning} item, an {@code item_reference} and any item of a type this
 *       class does not know are left out; that is also what the feature policy says (design 7.9, the reasoning of earlier
 *       turns cannot be carried to another source).
 *   <li>{@code previous_response_id} was copied, which a relay would refuse. It is never copied: the request is the
 *       <em>expanded</em> one ({@code OpenAiResponseStateStore#expand}), whose input already has the history, and a chain
 *       that cannot be expanded is the policy's (HARD), not this class's.
 * </ul>
 *
 * <p>The rest, in the order of the body. {@code instructions} is the first {@code system} message, and a {@code developer}
 * (or {@code system}) message of the input is a {@code system} message where it is: the text parts of its content, joined by
 * a newline, as the Messages conversion makes the system prompt. A user message with only text is a string; with images it
 * is a list of parts: an {@code input_text} is a {@code text} part, an {@code input_image} an {@code image_url} part (without
 * a {@code url} when the image is only a {@code file_id}, which the sanitizer then replaces and records, as it does the
 * images of a member without vision; the detector says so, {@link RequestFeatureDetector.Detected#imageNotCarried}, so that
 * the policy does not promise a relay an image it is not given), an {@code input_file} a {@code file} part with the name and
 * the id and nothing of the content, which is never sent (the sanitizer makes it {@code [file omitted]} and records {@code
 * file}). The refusal of an earlier assistant turn is text. A tool's output is the text of its parts (an output that is an image is empty, which
 * the canonicalizer makes {@code [no output]}).
 *
 * <p>The tools are the functions, with the parameters in the schema vocabulary the other conversions use ({@link
 * ToolSchemaSanitizer}), and {@code apply_patch}: Codex declares it as a custom tool, which a relay cannot be asked for,
 * and {@link ApplyPatchEmulation} gives it the function with one {@code patch} argument that the native Codex path sends
 * as well. Its calls in the history ({@code custom_tool_call} and the answer, {@code custom_tool_call_output}) are calls of
 * that function with the patch as the argument; a custom tool of another name is a call of a function of that name with
 * its text as an {@code input} argument, though a request that declares one is not sent to a relay (design 7.9, HARD).
 * Every other tool (the hosted ones, {@code tool_search}, an MCP server) is left out, and a {@code tool_choice} and
 * {@code parallel_tool_calls} go only with tools. The {@code strict} of a client's own function is carried as the client
 * wrote it, as the Chat entry carries it and the native Codex path does after the same reduction of the schema: a relay
 * that is asked for arguments that fit the schema should be held to it where the client asked.
 *
 * <p>Besides, {@code temperature}, {@code top_p}, {@code max_output_tokens} (as {@code max_tokens}) and {@code stream}; the
 * {@code reasoning.effort} as {@code reasoning_effort}, which is what the sanitizer reads to decide whether to send it; and
 * {@code text.format} as {@code response_format} ({@code json_schema} with its name and schema, {@code json_object}; the
 * default {@code text} is nothing). {@code text.verbosity}, {@code include}, {@code store}, {@code metadata}, {@code user},
 * {@code prompt_cache_key}, {@code client_metadata}, {@code service_tier} and the rest are not carried: they are the
 * client's or the gateway's, and the sanitizer's whitelist would keep them from a relay anyway.
 *
 * <p>Not done here, because they have a place of their own: unique and legal call ids, answers that follow their calls,
 * empty messages and the tool history of a body without tools ({@link ChatCanonicalizer}); the whitelist, the cache
 * control and the placeholders of what a member cannot take ({@link ChatWireSanitizer}); and the judgment of what the
 * request asks for ({@link RequestFeatureDetector}). The body that results passes {@link StrictChatLint} once those have
 * been at it, for any request a Responses client can send that has a message to answer. The request is not changed, and
 * the result shares nothing with it that could be edited.
 */
@Component
public final class ResponsesChatConverter {

    /** The values of the {@code detail} of an image that the Chat API has; the Responses API has more, which a relay would refuse. */
    private static final Set<String> IMAGE_DETAILS = Set.of("auto", "low", "high");

    /** The name of the argument of a custom tool that is not {@code apply_patch}, which has its text as the only one. */
    static final String CUSTOM_INPUT_ARGUMENT = "input";

    private final ObjectMapper objectMapper;
    private final ApplyPatchEmulation applyPatch;

    public ResponsesChatConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.applyPatch = new ApplyPatchEmulation(objectMapper);
    }

    /**
     * @param request the Responses request after the gateway expanded its {@code previous_response_id}, if it had one
     * @return the Chat Completions body, new and not shared with the request
     */
    public ObjectNode toChat(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("The Responses request must be a JSON object");
        }
        ObjectNode chat = objectMapper.createObjectNode();
        if (request.path("model").isString()) {
            chat.set("model", request.path("model"));
        }
        number(request, "temperature", chat);
        number(request, "top_p", chat);
        if (request.path("max_output_tokens").isNumber()) {
            chat.set("max_tokens", request.path("max_output_tokens"));
        }
        if (request.path("stream").isBoolean()) {
            chat.set("stream", request.path("stream"));
        }
        ArrayNode messages = chat.putArray("messages");
        String instructions = request.path("instructions").stringValue("");
        if (!instructions.isBlank()) {
            systemMessage(messages, instructions);
        }
        input(messages, request.path("input"));
        ArrayNode tools = tools(request.path("tools"));
        if (!tools.isEmpty()) {
            chat.set("tools", tools);
            if (request.hasNonNull("tool_choice")) {
                chat.set("tool_choice", toolChoice(request.path("tool_choice")));
            }
            if (request.path("parallel_tool_calls").isBoolean()) {
                chat.set("parallel_tool_calls", request.path("parallel_tool_calls"));
            }
        }
        String effort = request.path("reasoning").path("effort").stringValue("");
        if (!effort.isBlank()) {
            chat.put("reasoning_effort", effort);
        }
        responseFormat(request.path("text").path("format"), chat);
        return chat;
    }

    // ---- the input ----

    private void input(ArrayNode messages, JsonNode input) {
        if (input.isString()) {
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", input.stringValue());
            return;
        }
        if (!input.isArray()) {
            return;
        }
        // The assistant message that the next call belongs to: the one just made from a message or a call, with nothing
        // but items that are left out since. A call after anything else starts a message of its own.
        ObjectNode open = null;
        for (JsonNode item : input) {
            if (!item.isObject()) {
                continue;
            }
            switch (item.path("type").stringValue("")) {
                case "message" -> open = message(messages, item, open);
                case "function_call" -> open = call(messages, open, functionCall(item));
                case "custom_tool_call" -> open = call(messages, open, customCall(item));
                case "function_call_output", "custom_tool_call_output" -> {
                    toolMessage(messages, item);
                    open = null;
                }
                case "" -> open = item.has("role") ? message(messages, item, open) : open;
                default -> {
                    // reasoning, item_reference, the compaction of Codex, the hosted tools' calls: nothing a relay can be given.
                }
            }
        }
    }

    /** Appends the message of an item and returns the assistant message that calls may join, or null. */
    private ObjectNode message(ArrayNode messages, JsonNode item, ObjectNode open) {
        String role = item.path("role").stringValue("user");
        JsonNode content = item.path("content");
        switch (role) {
            case "user" -> {
                ObjectNode message = messages.addObject();
                message.put("role", "user");
                message.set("content", userContent(content));
                return null;
            }
            case "assistant" -> {
                ObjectNode message = messages.addObject();
                message.put("role", "assistant");
                message.put("content", text(content));
                return message;
            }
            case "system", "developer" -> {
                systemMessage(messages, text(content));
                return null;
            }
            default -> {
                // A role the Responses API does not have; the item is not a message of the conversation.
                return open;
            }
        }
    }

    private void systemMessage(ArrayNode messages, String text) {
        ObjectNode message = messages.addObject();
        message.put("role", "system");
        message.put("content", text);
    }

    /** Adds a call to the assistant message that is open, or to a new one, and returns it. */
    private ObjectNode call(ArrayNode messages, ObjectNode open, ObjectNode call) {
        ObjectNode assistant = open;
        if (assistant == null) {
            assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.put("content", "");
        }
        if (!assistant.has("tool_calls")) {
            assistant.putArray("tool_calls");
        }
        ((ArrayNode) assistant.get("tool_calls")).add(call);
        return assistant;
    }

    private ObjectNode functionCall(JsonNode item) {
        ObjectNode call = objectMapper.createObjectNode();
        call.put("id", callId(item));
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", item.path("name").stringValue(""));
        JsonNode arguments = item.path("arguments");
        function.put("arguments", arguments.isString() ? arguments.stringValue()
            : arguments.isMissingNode() || arguments.isNull() ? "{}" : arguments.toString());
        return call;
    }

    /** A call of a custom tool: the patch is the argument of the editing tool's function, any other text is the {@code input}. */
    private ObjectNode customCall(JsonNode item) {
        ObjectNode call = objectMapper.createObjectNode();
        call.put("id", callId(item));
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        String name = item.path("name").stringValue("");
        function.put("name", name);
        JsonNode input = item.path("input");
        String text = input.isString() ? input.stringValue() : input.isMissingNode() || input.isNull() ? "" : input.toString();
        function.put("arguments", ApplyPatchEmulation.TOOL_NAME.equals(name)
            ? applyPatch.patchArguments(text)
            : objectMapper.createObjectNode().put(CUSTOM_INPUT_ARGUMENT, text).toString());
        return call;
    }

    /** What the answer names: {@code call_id}, which is what the Responses API pairs by, and the item's own id for a client that wrote only that. */
    private static String callId(JsonNode item) {
        String callId = item.path("call_id").stringValue("");
        return callId.isBlank() ? item.path("id").stringValue("") : callId;
    }

    private void toolMessage(ArrayNode messages, JsonNode item) {
        ObjectNode message = messages.addObject();
        message.put("role", "tool");
        message.put("tool_call_id", item.path("call_id").stringValue(""));
        message.put("content", outputText(item.path("output")));
    }

    // ---- content ----

    /**
     * The text of a content: a string as it is, or the text parts of a list (an {@code input_text}, an {@code output_text},
     * a {@code text}, a refusal) joined by a newline, the blank ones left out.
     */
    private static String text(JsonNode content) {
        if (content.isString()) {
            return content.stringValue();
        }
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                append(text, textOf(part));
            }
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String more) {
        if (more != null && !more.isBlank()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(more);
        }
    }

    /** The text of one part, or null when it is not a text part. */
    private static String textOf(JsonNode part) {
        if (part.isString()) {
            return part.stringValue();
        }
        return switch (part.path("type").stringValue("")) {
            case "input_text", "output_text", "text" -> part.path("text").stringValue("");
            case "refusal" -> part.path("refusal").stringValue(part.path("text").stringValue(""));
            default -> null;
        };
    }

    /** A string for a message of text, else the parts in their order. */
    private JsonNode userContent(JsonNode content) {
        if (!content.isArray()) {
            return objectMapper.getNodeFactory().stringNode(text(content));
        }
        ArrayNode parts = objectMapper.createArrayNode();
        boolean onlyText = true;
        for (JsonNode part : content) {
            String type = part.path("type").stringValue("");
            if ("input_image".equals(type)) {
                parts.add(imagePart(part));
                onlyText = false;
            } else if ("input_file".equals(type)) {
                parts.add(filePart(part));
                onlyText = false;
            } else {
                String text = textOf(part);
                if (text != null && !text.isBlank()) {
                    ObjectNode textPart = parts.addObject();
                    textPart.put("type", "text");
                    textPart.put("text", text);
                }
            }
        }
        return onlyText ? objectMapper.getNodeFactory().stringNode(text(content)) : parts;
    }

    /**
     * Whether an {@code input_image} part names a URL for the image, which {@link RequestFeatureDetector} asks to tell the images
     * a relay can be given from those that are only a {@code file_id}: nothing but OpenAI can read a file of its own.
     */
    static boolean hasImageUrl(JsonNode part) {
        return !imageUrl(part.path("image_url")).isBlank();
    }

    /** The Responses API writes the URL as a string and the detail beside it; a client of the Chat API may nest both. */
    private static String imageUrl(JsonNode source) {
        return source.isObject() ? source.path("url").stringValue("") : source.stringValue("");
    }

    private ObjectNode imagePart(JsonNode part) {
        ObjectNode image = objectMapper.createObjectNode();
        image.put("type", "image_url");
        ObjectNode target = image.putObject("image_url");
        JsonNode source = part.path("image_url");
        String url = imageUrl(source);
        if (!url.isBlank()) {
            target.put("url", url);
        }
        String detail = part.path("detail").stringValue(source.path("detail").stringValue(""));
        if (IMAGE_DETAILS.contains(detail)) {
            target.put("detail", detail);
        }
        return image;
    }

    private ObjectNode filePart(JsonNode part) {
        ObjectNode file = objectMapper.createObjectNode();
        file.put("type", "file");
        ObjectNode target = file.putObject("file");
        if (part.path("filename").isString()) {
            target.set("filename", part.path("filename"));
        }
        if (part.path("file_id").isString()) {
            target.set("file_id", part.path("file_id"));
        }
        return file;
    }

    /** The text of a tool's output: a string, the text of the parts of a list, or the JSON of anything else. */
    private static String outputText(JsonNode output) {
        if (output.isMissingNode() || output.isNull()) {
            return "";
        }
        if (output.isString()) {
            return output.stringValue();
        }
        if (!output.isArray()) {
            return output.toString();
        }
        return text(output);
    }

    // ---- tools ----

    private ArrayNode tools(JsonNode tools) {
        ArrayNode converted = objectMapper.createArrayNode();
        if (!tools.isArray()) {
            return converted;
        }
        for (JsonNode tool : tools) {
            if (!tool.isObject()) {
                continue;
            }
            String type = tool.path("type").stringValue("function");
            boolean function = "function".equals(type) || type.isBlank();
            if (!function && !"custom".equals(type)) {
                continue;
            }
            // The Responses API writes the function at the top of the tool; a client of the Chat API may nest it.
            JsonNode source = tool.path("function").isObject() ? tool.path("function") : tool;
            if (applyPatch.isApplyPatchTool(source)) {
                converted.add(applyPatch.chatFunctionTool(source));
            } else if (function && !source.path("name").stringValue("").isBlank()) {
                converted.add(functionTool(source));
            }
        }
        return converted;
    }

    private ObjectNode functionTool(JsonNode source) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", source.path("name").stringValue(""));
        if (!source.path("description").stringValue("").isBlank()) {
            function.set("description", source.path("description"));
        }
        function.set("parameters", ToolSchemaSanitizer.sanitizeParameters(objectMapper, source.path("parameters")));
        if (source.path("strict").isBoolean()) {
            function.set("strict", source.path("strict"));
        }
        return tool;
    }

    /**
     * The choice in the Chat shape when it is one a relay knows ({@code auto}, {@code none}, {@code required}, a function,
     * and the editing tool as a function), else the choice as it was written.
     */
    private JsonNode toolChoice(JsonNode choice) {
        if (!choice.isObject()) {
            return choice;
        }
        JsonNode forced = applyPatch.chatToolChoice(choice);
        if (forced != choice) {
            return forced;
        }
        String name = ApplyPatchEmulation.toolChoiceName(choice);
        if (!"function".equals(choice.path("type").stringValue("")) || name.isBlank()) {
            return choice.deepCopy();
        }
        ObjectNode converted = objectMapper.createObjectNode();
        converted.put("type", "function");
        converted.putObject("function").put("name", name);
        return converted;
    }

    // ---- the rest of the body ----

    private static void number(JsonNode request, String name, ObjectNode chat) {
        if (request.path(name).isNumber()) {
            chat.set(name, request.path(name));
        }
    }

    /** {@code text.format}: a schema with its name, or the JSON mode; the default format, text, is no format. */
    private void responseFormat(JsonNode format, ObjectNode chat) {
        if (!format.isObject()) {
            return;
        }
        switch (format.path("type").stringValue("")) {
            case "json_schema" -> {
                if (!format.path("schema").isObject()) {
                    return;
                }
                ObjectNode schema = chat.putObject("response_format");
                schema.put("type", "json_schema");
                ObjectNode target = schema.putObject("json_schema");
                String name = format.path("name").stringValue("");
                target.put("name", name.isBlank() ? "response" : name);
                if (!format.path("description").stringValue("").isBlank()) {
                    target.set("description", format.path("description"));
                }
                target.set("schema", format.path("schema").deepCopy());
                if (format.path("strict").isBoolean()) {
                    target.set("strict", format.path("strict"));
                }
            }
            case "json_object" -> chat.putObject("response_format").put("type", "json_object");
            default -> {
                // text is the default.
            }
        }
    }
}
