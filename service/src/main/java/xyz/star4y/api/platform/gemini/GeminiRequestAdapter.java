package xyz.star4y.api.platform.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import xyz.star4y.api.platform.core.ToolSchemaSanitizer;

@Component
public class GeminiRequestAdapter {

    private final ObjectMapper objectMapper;

    public GeminiRequestAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode generateContentToChat(String model, JsonNode request) {
        ObjectNode chat = objectMapper.createObjectNode();
        chat.put("model", normalizeModel(model));

        JsonNode generationConfig = request.path("generationConfig");
        if (generationConfig.has("temperature")) chat.set("temperature", generationConfig.get("temperature"));
        if (generationConfig.has("topP")) chat.set("top_p", generationConfig.get("topP"));
        if (generationConfig.has("maxOutputTokens")) chat.set("max_tokens", generationConfig.get("maxOutputTokens"));
        if (request.path("tools").isArray() && !request.path("tools").isEmpty()) {
            chat.set("tools", toolsToOpenAi(request.path("tools")));
        }

        ArrayNode messages = chat.putArray("messages");
        if (request.hasNonNull("systemInstruction")) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", partsText(request.path("systemInstruction").path("parts")));
        }
        JsonNode contents = request.path("contents");
        if (contents.isArray()) {
            for (JsonNode content : contents) {
                appendContent(messages, content);
            }
        }
        return chat;
    }

    private void appendContent(ArrayNode messages, JsonNode content) {
        String role = content.path("role").asText("user");
        JsonNode parts = content.path("parts");
        String text = partsText(parts);

        ArrayNode functionCalls = objectMapper.createArrayNode();
        ArrayNode functionResponses = objectMapper.createArrayNode();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.has("functionCall")) {
                    functionCalls.add(part.path("functionCall"));
                }
                if (part.has("functionResponse")) {
                    functionResponses.add(part.path("functionResponse"));
                }
            }
        }

        if ("model".equals(role) && !functionCalls.isEmpty()) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant");
            message.put("content", text);
            ArrayNode toolCalls = message.putArray("tool_calls");
            for (JsonNode call : functionCalls) {
                ObjectNode toolCall = toolCalls.addObject();
                toolCall.put("id", call.path("id").asText(call.path("name").asText()));
                toolCall.put("type", "function");
                ObjectNode function = toolCall.putObject("function");
                function.put("name", call.path("name").asText());
                function.put("arguments", call.path("args").toString());
            }
            return;
        }

        if (!functionResponses.isEmpty()) {
            for (JsonNode response : functionResponses) {
                ObjectNode message = messages.addObject();
                message.put("role", "tool");
                message.put("tool_call_id", response.path("id").asText(response.path("name").asText()));
                message.put("content", response.path("response").toString());
            }
            if (!text.isBlank()) {
                ObjectNode user = messages.addObject();
                user.put("role", "user");
                user.put("content", text);
            }
            return;
        }

        ObjectNode message = messages.addObject();
        message.put("role", "model".equals(role) ? "assistant" : "user");
        message.put("content", text.isBlank() ? "Continue" : text);
    }

    private ArrayNode toolsToOpenAi(JsonNode tools) {
        ArrayNode converted = objectMapper.createArrayNode();
        for (JsonNode tool : tools) {
            JsonNode declarations = tool.path("functionDeclarations");
            if (!declarations.isArray()) {
                continue;
            }
            for (JsonNode declaration : declarations) {
                ObjectNode wrapper = converted.addObject();
                wrapper.put("type", "function");
                ObjectNode function = wrapper.putObject("function");
                function.put("name", declaration.path("name").asText());
                function.put("description", declaration.path("description").asText(""));
                JsonNode parameters = declaration.has("parameters")
                    ? declaration.path("parameters")
                    : objectMapper.createObjectNode();
                function.set("parameters", ToolSchemaSanitizer.sanitizeParameters(objectMapper, parameters));
            }
        }
        return converted;
    }

    private String partsText(JsonNode parts) {
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : parts) {
            if (!part.has("text")) {
                continue;
            }
            if (!builder.isEmpty()) builder.append("\n");
            builder.append(part.path("text").asText(""));
        }
        return builder.toString();
    }

    private String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            return "gemini-2.5-pro";
        }
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }
}
