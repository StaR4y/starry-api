package tech.starryapi.platform.openai;

import org.springframework.stereotype.Component;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.platform.core.ToolSchemaSanitizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class OpenAiRequestAdapter {

    private final ObjectMapper objectMapper;
    private final ModelCatalogProvider modelCatalog;

    public OpenAiRequestAdapter(ObjectMapper objectMapper, ModelCatalogProvider modelCatalog) {
        this.objectMapper = objectMapper;
        this.modelCatalog = modelCatalog;
    }

    public ObjectNode responsesToChat(JsonNode request) {
        ObjectNode chat = objectMapper.createObjectNode();
        chat.put("model", request.path("model").asString(modelCatalog.defaultTargetModelId()));
        if (request.has("temperature")) chat.set("temperature", request.get("temperature"));
        if (request.has("top_p")) chat.set("top_p", request.get("top_p"));
        if (request.has("max_output_tokens")) chat.set("max_tokens", request.get("max_output_tokens"));
        if (request.has("stream")) chat.set("stream", request.get("stream"));
        if (request.has("previous_response_id")) chat.set("previous_response_id", request.get("previous_response_id"));
        if (request.has("tools")) chat.set("tools", responsesToolsToOpenAi(request.path("tools")));
        if (request.has("tool_choice")) chat.set("tool_choice", request.get("tool_choice"));
        ArrayNode messages = chat.putArray("messages");
        if (request.hasNonNull("instructions")) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.path("instructions").asString(""));
        }
        JsonNode input = request.path("input");
        if (input.isString()) {
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", input.asString(""));
        } else if (input.isArray()) {
            for (JsonNode item : input) {
                appendResponsesInputItem(messages, item);
            }
        }
        return chat;
    }

    private void appendResponsesInputItem(ArrayNode messages, JsonNode item) {
        String type = item.path("type").asString("");
        if ("function_call".equals(type)) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant");
            message.put("content", "");
            ArrayNode toolCalls = message.putArray("tool_calls");
            ObjectNode call = toolCalls.addObject();
            call.put("id", item.path("call_id").asString(item.path("id").asString("")));
            call.put("type", "function");
            ObjectNode function = call.putObject("function");
            function.put("name", item.path("name").asString(""));
            function.put("arguments", item.path("arguments").asString("{}"));
            return;
        }
        if ("function_call_output".equals(type)) {
            ObjectNode message = messages.addObject();
            message.put("role", "tool");
            message.put("tool_call_id", item.path("call_id").asString(""));
            message.put("content", responsesOutputText(item.path("output")));
            return;
        }
        ObjectNode message = messages.addObject();
        message.put("role", item.path("role").asString("user"));
        message.set("content", normalizeResponsesContent(item.path("content")));
        if (item.has("tool_call_id")) {
            message.set("tool_call_id", item.get("tool_call_id"));
        }
    }

    private ArrayNode responsesToolsToOpenAi(JsonNode tools) {
        ArrayNode converted = objectMapper.createArrayNode();
        if (!tools.isArray()) {
            return converted;
        }
        for (JsonNode tool : tools) {
            if ("function".equals(tool.path("type").asString("")) && tool.has("function")) {
                ObjectNode wrapper = (ObjectNode) tool.deepCopy();
                JsonNode function = wrapper.path("function");
                if (function.isObject()) {
                    ((ObjectNode) function).set(
                        "parameters",
                        ToolSchemaSanitizer.sanitizeParameters(objectMapper, function.path("parameters"))
                    );
                }
                converted.add(wrapper);
                continue;
            }
            ObjectNode wrapper = converted.addObject();
            wrapper.put("type", "function");
            ObjectNode function = wrapper.putObject("function");
            function.put("name", tool.path("name").asString(tool.path("function").path("name").asString("")));
            function.put("description", tool.path("description").asString(tool.path("function").path("description").asString("")));
            JsonNode parameters = tool.has("parameters")
                ? tool.path("parameters")
                : tool.path("function").path("parameters");
            function.set("parameters", ToolSchemaSanitizer.sanitizeParameters(objectMapper, parameters));
        }
        return converted;
    }

    private JsonNode normalizeResponsesContent(JsonNode content) {
        if (!content.isArray()) {
            return content;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode part : content) {
            if ("input_text".equals(part.path("type").asString("")) || "output_text".equals(part.path("type").asString(""))) {
                ObjectNode item = normalized.addObject();
                item.put("type", "text");
                item.put("text", part.path("text").asString(""));
            } else if ("input_image".equals(part.path("type").asString(""))) {
                ObjectNode item = normalized.addObject();
                item.put("type", "image_url");
                ObjectNode imageUrl = item.putObject("image_url");
                if (part.has("image_url")) {
                    imageUrl.set("url", part.get("image_url"));
                } else if (part.has("file_id")) {
                    imageUrl.set("url", part.get("file_id"));
                }
                if (part.has("detail")) {
                    imageUrl.set("detail", part.get("detail"));
                }
            } else if ("input_file".equals(part.path("type").asString(""))) {
                ObjectNode item = normalized.addObject();
                item.put("type", "text");
                item.put("text", responsesFileText(part));
            } else if ("refusal".equals(part.path("type").asString(""))) {
                ObjectNode item = normalized.addObject();
                item.put("type", "text");
                item.put("text", part.path("refusal").asString(part.path("text").asString("")));
            }
        }
        return normalized;
    }

    private String responsesFileText(JsonNode part) {
        String filename = part.path("filename").asString("");
        String fileId = part.path("file_id").asString("");
        if (!filename.isBlank() && !fileId.isBlank()) {
            return "[Input file: " + filename + " (" + fileId + ")]";
        }
        if (!filename.isBlank()) {
            return "[Input file: " + filename + "]";
        }
        if (!fileId.isBlank()) {
            return "[Input file: " + fileId + "]";
        }
        return "[Input file]";
    }

    private String responsesOutputText(JsonNode output) {
        if (output == null || output.isMissingNode() || output.isNull()) {
            return "";
        }
        if (output.isString()) {
            return output.asString("");
        }
        if (!output.isArray()) {
            return output.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : output) {
            String text = part.path("text").asString("");
            if (!text.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }
}
