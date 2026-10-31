package tech.starryapi.platform.gemini;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
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
            ObjectNode part = parts.addObject();
            ObjectNode functionCall = part.putObject("functionCall");
            functionCall.put("id", toolUse.id());
            functionCall.put("name", toolUse.name());
            functionCall.set("args", toolUse.input());
            // A call a Gemini model signed is given back with its signature, which the client sends with the call in its
            // next request: the model's thinking is lost without it, and Gemini 3 refuses the request (design 7.10).
            if (toolUse.thoughtSignature() != null) {
                part.put("thoughtSignature", toolUse.thoughtSignature());
            }
        }
        candidate.put("finishReason", finishReason(result));
        candidate.put("index", 0);

        ObjectNode usage = root.putObject("usageMetadata");
        usageMetadata(usage, result);
        root.put("modelVersion", model);
        root.put("responseId", "gen-" + UUID.randomUUID());
        return root;
    }

    /**
     * The tokens of the answer. A native result is told in the three counts it always was. A cross-protocol one (its usage
     * source is known) also tells what its source reported of the prompt and the thinking: the platform counts the cache
     * read inside the prompt and the thinking inside the output, and Gemini counts the cache inside the prompt too but
     * keeps the thinking out of the candidates, so the candidates are the output without it and the total is unchanged.
     */
    private void usageMetadata(ObjectNode usage, PlatformCompletionResult result) {
        PlatformUsage tokens = result.usage();
        if (result.usageSource() == null) {
            usage.put("promptTokenCount", tokens.inputTokens());
            usage.put("candidatesTokenCount", tokens.outputTokens());
            usage.put("totalTokenCount", tokens.totalTokens());
            return;
        }
        long thoughts = Math.max(0, Math.min(tokens.reasoningTokens(), tokens.outputTokens()));
        usage.put("promptTokenCount", tokens.inputTokens());
        usage.put("candidatesTokenCount", tokens.outputTokens() - thoughts);
        usage.put("totalTokenCount", tokens.totalTokens());
        long cached = Math.max(0, Math.min(tokens.cacheReadTokens(), tokens.inputTokens()));
        if (cached > 0) {
            usage.put("cachedContentTokenCount", cached);
        }
        if (thoughts > 0) {
            usage.put("thoughtsTokenCount", thoughts);
        }
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
