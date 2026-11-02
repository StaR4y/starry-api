package tech.starryapi.platform.anthropic;

import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.platform.core.ToolSchemaSanitizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The conversion of a Messages request to Chat as {@link AnthropicRequestAdapter} did it before the shared helpers of the
 * conversion for relays were taken out of it, copied as it was committed (phase 7). It is the oracle of the tests
 * that say {@code messagesToChat} still gives byte for byte what the native accounts were always given, and that the
 * conversion for relays gives a body that comes to the same for a request none of whose content it has a new place for.
 * It is not used by anything else, and is never to be edited to follow the adapter.
 */
public final class PhaseFiveAnthropicRequestAdapter {

    private final ObjectMapper objectMapper;
    private final ModelCatalogProvider modelCatalog;

    public PhaseFiveAnthropicRequestAdapter(ObjectMapper objectMapper, ModelCatalogProvider modelCatalog) {
        this.objectMapper = objectMapper;
        this.modelCatalog = modelCatalog;
    }

    public ObjectNode messagesToChat(JsonNode request) {
        ObjectNode chat = objectMapper.createObjectNode();
        chat.put("model", request.path("model").asString(modelCatalog.defaultTargetModelId()));
        if (request.has("max_tokens")) chat.set("max_tokens", request.get("max_tokens"));
        if (request.has("temperature")) chat.set("temperature", request.get("temperature"));
        if (request.has("top_p")) chat.set("top_p", request.get("top_p"));
        if (request.has("top_k")) chat.set("top_k", request.get("top_k"));
        if (request.has("stop_sequences")) chat.set("stop_sequences", request.get("stop_sequences"));
        if (request.has("metadata")) chat.set("metadata", request.get("metadata"));
        if (request.has("thinking")) chat.set("thinking", request.get("thinking"));
        if (request.has("stream")) chat.set("stream", request.get("stream"));
        ArrayNode messages = chat.putArray("messages");
        if (request.hasNonNull("system")) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", anthropicContentText(request.path("system")));
        }
        JsonNode inputMessages = request.path("messages");
        if (inputMessages.isArray()) {
            for (JsonNode item : inputMessages) {
                appendAnthropicMessage(messages, item);
            }
        }
        if (request.path("tools").isArray() && !request.path("tools").isEmpty()) {
            chat.set("tools", anthropicToolsToOpenAi(request.path("tools")));
        }
        if (request.has("tool_choice")) {
            JsonNode toolChoice = request.path("tool_choice");
            chat.set("tool_choice", anthropicToolChoiceToOpenAi(toolChoice));
            if (toolChoice.path("disable_parallel_tool_use").asBoolean(false)) {
                chat.put("parallel_tool_calls", false);
            }
        }
        return chat;
    }

    private void appendAnthropicMessage(ArrayNode messages, JsonNode item) {
        String role = item.path("role").asString("user");
        JsonNode content = item.path("content");
        if (!content.isArray()) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant".equals(role) ? "assistant" : "user");
            message.put("content", anthropicContentText(content));
            return;
        }
        if ("assistant".equals(role) && hasAnthropicContentType(content, "tool_use")) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant");
            message.put("content", anthropicTextOnly(content));
            ArrayNode toolCalls = message.putArray("tool_calls");
            for (JsonNode part : content) {
                if (!"tool_use".equals(part.path("type").asString(""))) {
                    continue;
                }
                ObjectNode call = toolCalls.addObject();
                call.put("id", part.path("id").asString(""));
                call.put("type", "function");
                ObjectNode function = call.putObject("function");
                function.put("name", part.path("name").asString(""));
                function.put("arguments", part.path("input").toString());
            }
            return;
        }
        if (!"assistant".equals(role) && hasAnthropicContentType(content, "tool_result")) {
            for (JsonNode part : content) {
                if (!"tool_result".equals(part.path("type").asString(""))) {
                    continue;
                }
                ObjectNode message = messages.addObject();
                message.put("role", "tool");
                message.put("tool_call_id", part.path("tool_use_id").asString(""));
                message.put("content", anthropicToolResultText(part));
            }
            String text = anthropicTextOnly(content);
            if (!text.isBlank()) {
                ObjectNode message = messages.addObject();
                message.put("role", "user");
                message.put("content", text);
            }
            return;
        }
        ObjectNode message = messages.addObject();
        message.put("role", "assistant".equals(role) ? "assistant" : "user");
        message.put("content", anthropicContentText(content));
    }

    private boolean hasAnthropicContentType(JsonNode content, String type) {
        for (JsonNode part : content) {
            if (type.equals(part.path("type").asString(""))) {
                return true;
            }
        }
        return false;
    }

    private String anthropicTextOnly(JsonNode content) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            if ("text".equals(part.path("type").asString(""))) {
                if (!builder.isEmpty()) builder.append("\n");
                builder.append(part.path("text").asString(""));
            }
        }
        return builder.toString();
    }

    private String anthropicContentText(JsonNode content) {
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
                case "tool_result" -> anthropicToolResultText(part);
                case "tool_use" -> anthropicToolUseText(part);
                default -> "";
            };
            if (!text.isBlank()) {
                if (!builder.isEmpty()) builder.append("\n");
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private String anthropicToolResultText(JsonNode part) {
        JsonNode content = part.path("content");
        if (content.isString()) {
            return content.asString("");
        }
        if (content.isArray()) {
            return anthropicContentText(content);
        }
        return content.isMissingNode() || content.isNull() ? "" : content.toString();
    }

    private String anthropicToolUseText(JsonNode part) {
        String name = part.path("name").asString("");
        JsonNode input = part.path("input");
        if (input.isMissingNode() || input.isNull() || input.isEmpty()) {
            return "Tool use: " + name;
        }
        return "Tool use: " + name + "\nInput: " + input;
    }

    private JsonNode anthropicToolChoiceToOpenAi(JsonNode toolChoice) {
        String type = toolChoice.path("type").asString("");
        return switch (type) {
            case "auto" -> objectMapper.getNodeFactory().stringNode("auto");
            case "any" -> objectMapper.getNodeFactory().stringNode("required");
            case "none" -> objectMapper.getNodeFactory().stringNode("none");
            case "tool" -> {
                ObjectNode converted = objectMapper.createObjectNode();
                converted.put("type", "function");
                ObjectNode function = converted.putObject("function");
                function.put("name", toolChoice.path("name").asString(""));
                yield converted;
            }
            default -> toolChoice;
        };
    }

    private ArrayNode anthropicToolsToOpenAi(JsonNode tools) {
        ArrayNode converted = objectMapper.createArrayNode();
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
