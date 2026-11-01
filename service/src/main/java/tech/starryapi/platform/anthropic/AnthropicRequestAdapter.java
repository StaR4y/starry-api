package tech.starryapi.platform.anthropic;

import org.springframework.stereotype.Component;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.platform.core.ToolSchemaSanitizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a Messages request into the Chat body that Kiro and the Anthropic keys are given. What it keeps is text: images,
 * documents, {@code cache_control} and {@code tool_result.is_error} do not survive it, and that is what those accounts have
 * always been sent, so its output does not change.
 *
 * <p>The public static methods are the pieces of the conversion that {@code AnthropicChatWireConverter} (the Chat body of
 * an OpenAI-format relay, which does keep those things) uses as well: the fields that stay fields, the text of a content,
 * the tools and the tool choice. They are shared so that the two conversions cannot drift apart where they are meant to be
 * one, and a request none of whose content only the second keeps is the same Chat body from both
 * ({@code AnthropicChatWireConverterTest} holds them to it).
 */
@Component
public class AnthropicRequestAdapter {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private final ObjectMapper objectMapper;
    private final ModelCatalogProvider modelCatalog;

    public AnthropicRequestAdapter(ObjectMapper objectMapper, ModelCatalogProvider modelCatalog) {
        this.objectMapper = objectMapper;
        this.modelCatalog = modelCatalog;
    }

    public ObjectNode messagesToChat(JsonNode request) {
        ObjectNode chat = chatShell(request, modelCatalog.defaultTargetModelId());
        ArrayNode messages = chat.putArray("messages");
        if (request.hasNonNull("system")) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", contentText(request.path("system")));
        }
        JsonNode inputMessages = request.path("messages");
        if (inputMessages.isArray()) {
            for (JsonNode item : inputMessages) {
                appendAnthropicMessage(messages, item);
            }
        }
        putTools(objectMapper, request, chat);
        return chat;
    }

    /**
     * The fields of a Messages request that are fields of the Chat body as they are, in the order the conversion writes
     * them; the messages, the tools and the tool choice come after.
     *
     * @param defaultModel the model of the body when the request names none
     */
    public static ObjectNode chatShell(JsonNode request, String defaultModel) {
        ObjectNode chat = FACTORY.objectNode();
        chat.put("model", request.path("model").asString(defaultModel));
        if (request.has("max_tokens")) chat.set("max_tokens", request.get("max_tokens"));
        if (request.has("temperature")) chat.set("temperature", request.get("temperature"));
        if (request.has("top_p")) chat.set("top_p", request.get("top_p"));
        if (request.has("top_k")) chat.set("top_k", request.get("top_k"));
        if (request.has("stop_sequences")) chat.set("stop_sequences", request.get("stop_sequences"));
        if (request.has("metadata")) chat.set("metadata", request.get("metadata"));
        if (request.has("thinking")) chat.set("thinking", request.get("thinking"));
        if (request.has("stream")) chat.set("stream", request.get("stream"));
        return chat;
    }

    /** The tools, the tool choice and whether the calls may be parallel, as the Chat body has them. */
    public static void putTools(ObjectMapper objectMapper, JsonNode request, ObjectNode chat) {
        if (request.path("tools").isArray() && !request.path("tools").isEmpty()) {
            chat.set("tools", toolsToOpenAi(objectMapper, request.path("tools")));
        }
        if (request.has("tool_choice")) {
            JsonNode toolChoice = request.path("tool_choice");
            chat.set("tool_choice", toolChoiceToOpenAi(toolChoice));
            if (toolChoice.path("disable_parallel_tool_use").asBoolean(false)) {
                chat.put("parallel_tool_calls", false);
            }
        }
    }

    private void appendAnthropicMessage(ArrayNode messages, JsonNode item) {
        String role = item.path("role").asString("user");
        JsonNode content = item.path("content");
        if (!content.isArray()) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant".equals(role) ? "assistant" : "user");
            message.put("content", contentText(content));
            return;
        }
        if ("assistant".equals(role) && hasContentType(content, "tool_use")) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant");
            message.put("content", textOnly(content));
            ArrayNode toolCalls = message.putArray("tool_calls");
            for (JsonNode part : content) {
                if (!"tool_use".equals(part.path("type").asString(""))) {
                    continue;
                }
                toolCalls.add(toolCall(part));
            }
            return;
        }
        if (!"assistant".equals(role) && hasContentType(content, "tool_result")) {
            for (JsonNode part : content) {
                if (!"tool_result".equals(part.path("type").asString(""))) {
                    continue;
                }
                ObjectNode message = messages.addObject();
                message.put("role", "tool");
                message.put("tool_call_id", part.path("tool_use_id").asString(""));
                message.put("content", toolResultText(part));
            }
            String text = textOnly(content);
            if (!text.isBlank()) {
                ObjectNode message = messages.addObject();
                message.put("role", "user");
                message.put("content", text);
            }
            return;
        }
        ObjectNode message = messages.addObject();
        message.put("role", "assistant".equals(role) ? "assistant" : "user");
        message.put("content", contentText(content));
    }

    /** The call of a {@code tool_use} block, as the Chat body of an assistant message has it. */
    public static ObjectNode toolCall(JsonNode toolUse) {
        ObjectNode call = FACTORY.objectNode();
        call.put("id", toolUse.path("id").asString(""));
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", toolUse.path("name").asString(""));
        function.put("arguments", toolUse.path("input").toString());
        return call;
    }

    /** Whether a list of blocks has one of the type. */
    public static boolean hasContentType(JsonNode content, String type) {
        for (JsonNode part : content) {
            if (type.equals(part.path("type").asString(""))) {
                return true;
            }
        }
        return false;
    }

    /** The text blocks of a list of blocks, one after the other (a blank one too), a newline between. */
    public static String textOnly(JsonNode content) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asString(""))) {
                if (!builder.isEmpty()) builder.append("\n");
                builder.append(part.path("text").asString(""));
            }
        }
        return builder.toString();
    }

    /** The text of a content: a string as it is, the text of the blocks of a list (a blank one left out) a newline apart. */
    public static String contentText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isString()) {
            return content.asString("");
        }
        if (!content.isArray()) {
            return content.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            String type = part.path("type").asString("");
            String text = switch (type) {
                case "text" -> part.path("text").asString("");
                case "tool_result" -> toolResultText(part);
                case "tool_use" -> toolUseText(part);
                default -> "";
            };
            if (!text.isBlank()) {
                if (!builder.isEmpty()) builder.append("\n");
                builder.append(text);
            }
        }
        return builder.toString();
    }

    /** The text of a {@code tool_result} block: its content as a string, as the text of its blocks, or as JSON for anything else. */
    public static String toolResultText(JsonNode part) {
        JsonNode content = part.path("content");
        if (content.isString()) {
            return content.asString("");
        }
        if (content.isArray()) {
            return contentText(content);
        }
        return content.isMissingNode() || content.isNull() ? "" : content.toString();
    }

    private static String toolUseText(JsonNode part) {
        String name = part.path("name").asString("");
        JsonNode input = part.path("input");
        if (input.isMissingNode() || input.isNull() || input.isEmpty()) {
            return "Tool use: " + name;
        }
        return "Tool use: " + name + "\nInput: " + input;
    }

    private static JsonNode toolChoiceToOpenAi(JsonNode toolChoice) {
        String type = toolChoice.path("type").asString("");
        return switch (type) {
            case "auto" -> FACTORY.stringNode("auto");
            case "any" -> FACTORY.stringNode("required");
            case "none" -> FACTORY.stringNode("none");
            case "tool" -> {
                ObjectNode converted = FACTORY.objectNode();
                converted.put("type", "function");
                ObjectNode function = converted.putObject("function");
                function.put("name", toolChoice.path("name").asString(""));
                yield converted;
            }
            default -> toolChoice;
        };
    }

    private static ArrayNode toolsToOpenAi(ObjectMapper objectMapper, JsonNode tools) {
        ArrayNode converted = FACTORY.arrayNode();
        for (JsonNode tool : tools) {
            ObjectNode wrapper = converted.addObject();
            wrapper.put("type", "function");
            ObjectNode function = wrapper.putObject("function");
            function.put("name", tool.path("name").asString(""));
            function.put("description", tool.path("description").asString(""));
            function.set("parameters", ToolSchemaSanitizer.sanitizeParameters(objectMapper, tool.path("input_schema")));
        }
        return converted;
    }
}
