package tech.starryapi.platform.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GeminiResponseFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeminiResponseFactory factory = new GeminiResponseFactory(objectMapper);

    @Test
    void returnsGenerateContentTextResponse() {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "Hello.",
            new PlatformUsage(3, 2, BigDecimal.ZERO, 0, 0, 0)
        );

        JsonNode response = factory.generateContent("gemini-2.5-pro", result);

        assertThat(response.path("candidates").get(0).path("content").path("role").asString("")).isEqualTo("model");
        assertThat(response.path("candidates").get(0).path("content").path("parts").get(0).path("text").asString(""))
            .isEqualTo("Hello.");
        assertThat(response.path("usageMetadata").path("totalTokenCount").asLong(0L)).isEqualTo(5);
        assertThat(response.path("modelVersion").asString("")).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void returnsGenerateContentFunctionCallParts() throws Exception {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "",
            List.of(new PlatformToolUse("lookup", "lookup", objectMapper.readTree("{\"id\":\"1\"}"))),
            PlatformUsage.zero()
        );

        JsonNode functionCall = factory.generateContent("gemini-2.5-pro", result)
            .path("candidates").get(0)
            .path("content")
            .path("parts").get(0)
            .path("functionCall");

        assertThat(functionCall.path("name").asString("")).isEqualTo("lookup");
        assertThat(functionCall.path("args").path("id").asString("")).isEqualTo("1");
    }

    @Test
    void aResultWithoutAStopReasonFinishesWithStop() throws Exception {
        // The bytes of this path are pinned by ProxyWireFormatTest; this is the field the new stop reasons change.
        assertThat(finishReason(textResult())).isEqualTo("STOP");
        assertThat(finishReason(toolResult())).isEqualTo("STOP");
    }

    @Test
    void endTurnAndToolUseRenderStop() throws Exception {
        assertThat(finishReason(withStopReason(textResult(), PlatformCompletionResult.StopReason.END_TURN))).isEqualTo("STOP");
        assertThat(finishReason(withStopReason(toolResult(), PlatformCompletionResult.StopReason.TOOL_USE))).isEqualTo("STOP");
    }

    @Test
    void maxTokensIsRenderedAsMaxTokens() throws Exception {
        JsonNode response = factory.generateContent(
            "gemini-2.5-pro",
            withStopReason(textResult(), PlatformCompletionResult.StopReason.MAX_TOKENS)
        );

        assertThat(response.path("candidates").get(0).path("finishReason").asString("")).isEqualTo("MAX_TOKENS");
        assertThat(response.path("candidates").get(0).path("content").path("parts").get(0).path("text").asString(""))
            .isEqualTo("Hello.");
        assertThat(finishReason(withStopReason(toolResult(), PlatformCompletionResult.StopReason.MAX_TOKENS))).isEqualTo("MAX_TOKENS");
    }

    @Test
    void contentFilterIsRenderedAsSafety() throws Exception {
        assertThat(finishReason(withStopReason(textResult(), PlatformCompletionResult.StopReason.CONTENT_FILTER))).isEqualTo("SAFETY");
    }

    private PlatformCompletionResult textResult() {
        return new PlatformCompletionResult("Hello.", new PlatformUsage(3, 2, BigDecimal.ZERO, 0, 0, 0));
    }

    private PlatformCompletionResult toolResult() throws Exception {
        return new PlatformCompletionResult(
            "",
            List.of(new PlatformToolUse("lookup", "lookup", objectMapper.readTree("{\"id\":\"1\"}"))),
            PlatformUsage.zero()
        );
    }

    private PlatformCompletionResult withStopReason(PlatformCompletionResult plain, PlatformCompletionResult.StopReason stopReason) {
        return new PlatformCompletionResult(
            plain.content(), plain.reasoningContent(), plain.toolUses(), plain.usage(), stopReason, null, null
        );
    }

    private String finishReason(PlatformCompletionResult result) {
        return factory.generateContent("gemini-2.5-pro", result).path("candidates").get(0).path("finishReason").asString("");
    }
}
