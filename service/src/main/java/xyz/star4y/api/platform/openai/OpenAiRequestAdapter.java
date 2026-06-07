package xyz.star4y.api.platform.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import xyz.star4y.api.model.ModelCatalogProvider;

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
        chat.put("model", request.path("model").asText(modelCatalog.defaultTargetModelId()));
        if (request.has("temperature")) chat.set("temperature", request.get("temperature"));
        if (request.has("top_p")) chat.set("top_p", request.get("top_p"));
        if (request.has("max_output_tokens")) chat.set("max_tokens", request.get("max_output_tokens"));
        if (request.has("stream")) chat.set("stream", request.get("stream"));
        if (request.has("tools")) chat.set("tools", responsesToolsToOpenAi(request.path("tools")));
        if (request.has("tool_choice")) chat.set("tool_choice", request.get("tool_choice"));
        ArrayNode messages = chat.putArray("messages");
        if (request.hasNonNull("instructions")) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.path("instructions").asText());
        }
        JsonNode input = request.path("input");
        if (input.isTextual()) {
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", input.asText());
        } else if (input.isArray()) {
            for (JsonNode item : input) {
                appendResponsesInputItem(messages, item);
            }
        }
        return chat;
    }

    private void appendResponsesInputItem(ArrayNode messages, JsonNode item) {
        String type = item.path("type").asText("");
        if ("function_call".equals(type)) {
            ObjectNode message = messages.addObject();
            message.put("role", "assistant");
            message.put("content", "");
            ArrayNode toolCalls = message.putArray("tool_calls");
            ObjectNode call = toolCalls.addObject();
            call.put("id", item.path("call_id").asText(item.path("id").asText()));
            call.put("type", "function");
            ObjectNode function = call.putObject("function");
            function.put("name", item.path("name").asText());
            function.put("arguments", item.path("arguments").asText("{}"));
            return;
        }
        if ("function_call_output".equals(type)) {
            ObjectNode message = messages.addObject();
            message.put("role", "tool");
            message.put("tool_call_id", item.path("call_id").asText());
            message.put("content", item.path("output").asText(""));
            return;
        }
        ObjectNode message = messages.addObject();
        message.put("role", item.path("role").asText("user"));
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
            if ("function".equals(tool.path("type").asText()) && tool.has("function")) {
                converted.add(tool);
                continue;
            }
            ObjectNode wrapper = converted.addObject();
            wrapper.put("type", "function");
            ObjectNode function = wrapper.putObject("function");
            function.put("name", tool.path("name").asText(tool.path("function").path("name").asText()));
            function.put("description", tool.path("description").asText(tool.path("function").path("description").asText("")));
            JsonNode parameters = tool.has("parameters")
                ? tool.path("parameters")
                : tool.path("function").path("parameters");
            function.set("parameters", parameters);
        }
        return converted;
    }

    private JsonNode normalizeResponsesContent(JsonNode content) {
        if (!content.isArray()) {
            return content;
        }
        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode part : content) {
            if ("input_text".equals(part.path("type").asText()) || "output_text".equals(part.path("type").asText())) {
                ObjectNode item = normalized.addObject();
                item.put("type", "text");
                item.put("text", part.path("text").asText());
            }
        }
        return normalized;
    }
}
