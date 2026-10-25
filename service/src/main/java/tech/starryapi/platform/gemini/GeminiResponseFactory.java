package tech.starryapi.platform.gemini;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GeminiResponseFactory {

    private final ObjectMapper objectMapper;

    public GeminiResponseFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode generateContent(String model, PlatformCompletionResult result) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode candidates = root.putArray("candidates");
        ObjectNode candidate = candidates.addObject();
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        ArrayNode parts = content.putArray("parts");
        if (!result.content().isBlank() || result.toolUses().isEmpty()) {
            ObjectNode text = parts.addObject();
            text.put("text", result.content());
        }
        for (PlatformToolUse toolUse : result.toolUses()) {
            ObjectNode functionCall = parts.addObject().putObject("functionCall");
            functionCall.put("id", toolUse.id());
            functionCall.put("name", toolUse.name());
            functionCall.set("args", toolUse.input());
        }
        candidate.put("finishReason", finishReason(result));
        candidate.put("index", 0);

        ObjectNode usage = root.putObject("usageMetadata");
        usage.put("promptTokenCount", result.usage().inputTokens());
        usage.put("candidatesTokenCount", result.usage().outputTokens());
        usage.put("totalTokenCount", result.usage().totalTokens());
        root.put("modelVersion", model);
        root.put("responseId", "gen-" + UUID.randomUUID());
        return root;
    }

    /** Only a non-null MAX_TOKENS or CONTENT_FILTER changes the answer; every other result keeps STOP. */
    private String finishReason(PlatformCompletionResult result) {
        if (result.stopReason() == PlatformCompletionResult.StopReason.MAX_TOKENS) {
            return "MAX_TOKENS";
        }
        if (result.stopReason() == PlatformCompletionResult.StopReason.CONTENT_FILTER) {
            return "SAFETY";
        }
        return "STOP";
    }
}
