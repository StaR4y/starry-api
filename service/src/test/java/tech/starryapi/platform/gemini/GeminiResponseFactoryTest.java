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

    private PlatformCompletionResult crossProtocol(String content, List<PlatformToolUse> calls, PlatformUsage usage) {
        return new PlatformCompletionResult(
            content, "", calls, usage, PlatformCompletionResult.StopReason.END_TURN, PlatformCompletionResult.UsageSource.REPORTED, List.of()
        );
    }

    /** The part of a call a Gemini model signed carries the signature beside the call, as the API writes it. */
    @Test
    void aSignedCallIsGivenWithItsThoughtSignature() throws Exception {
        PlatformCompletionResult result = crossProtocol("", List.of(
            new PlatformToolUse("call_1", "lookup", objectMapper.readTree("{\"q\":1}"), "function_call", "{\"q\":1}", null, "SIG-1"),
            new PlatformToolUse("call_2", "other", objectMapper.readTree("{}"))), PlatformUsage.zero());

        JsonNode parts = factory.generateContent("gemini-3-pro", result).path("candidates").get(0).path("content").path("parts");

        assertThat(parts.get(0).propertyNames()).containsExactly("functionCall", "thoughtSignature");
        assertThat(parts.get(0).path("thoughtSignature").asString("")).isEqualTo("SIG-1");
        assertThat(parts.get(0).path("functionCall").path("id").asString("")).isEqualTo("call_1");
        assertThat(parts.get(1).propertyNames()).containsExactly("functionCall");
    }

    @Test
    void aNativeResultIsToldInThreeCountsWhateverItsUsageHolds() {
        PlatformCompletionResult result = new PlatformCompletionResult("Hello.", "", List.of(),
            new PlatformUsage(100, 30, BigDecimal.ZERO, 40, 5, 12));

        JsonNode usage = factory.generateContent("gemini-2.5-pro", result).path("usageMetadata");

        assertThat(usage.propertyNames()).containsExactly("promptTokenCount", "candidatesTokenCount", "totalTokenCount");
        assertThat(usage.path("promptTokenCount").asLong()).isEqualTo(100);
        assertThat(usage.path("candidatesTokenCount").asLong()).isEqualTo(30);
        assertThat(usage.path("totalTokenCount").asLong()).isEqualTo(130);
    }

    /** The cache read stays inside the prompt, and the thinking comes out of the candidates: the total is the sum of the three Gemini counts. */
    @Test
    void aCrossProtocolResultAlsoTellsTheCacheReadAndTheThinking() {
        JsonNode usage = factory.generateContent("gemini-2.5-pro",
            crossProtocol("Hello.", List.of(), new PlatformUsage(100, 30, BigDecimal.ZERO, 40, 5, 12))).path("usageMetadata");

        assertThat(usage.path("promptTokenCount").asLong()).isEqualTo(100);
        assertThat(usage.path("cachedContentTokenCount").asLong()).isEqualTo(40);
        assertThat(usage.path("candidatesTokenCount").asLong()).isEqualTo(18);
        assertThat(usage.path("thoughtsTokenCount").asLong()).isEqualTo(12);
        assertThat(usage.path("totalTokenCount").asLong()).isEqualTo(130);
        assertThat(usage.path("promptTokenCount").asLong() + usage.path("candidatesTokenCount").asLong()
            + usage.path("thoughtsTokenCount").asLong()).isEqualTo(usage.path("totalTokenCount").asLong());
    }

    @Test
    void aCrossProtocolResultWithoutCacheOrThinkingTellsNeitherAndNeverMoreThanItHas() {
        JsonNode plain = factory.generateContent("gemini-2.5-pro",
            crossProtocol("Hello.", List.of(), new PlatformUsage(100, 30, BigDecimal.ZERO, 0, 0, 0))).path("usageMetadata");
        JsonNode beyond = factory.generateContent("gemini-2.5-pro",
            crossProtocol("Hello.", List.of(), new PlatformUsage(10, 3, BigDecimal.ZERO, 50, 0, 9))).path("usageMetadata");

        assertThat(plain.propertyNames()).containsExactly("promptTokenCount", "candidatesTokenCount", "totalTokenCount");
        assertThat(plain.path("candidatesTokenCount").asLong()).isEqualTo(30);
        assertThat(beyond.path("cachedContentTokenCount").asLong()).isEqualTo(10);
        assertThat(beyond.path("thoughtsTokenCount").asLong()).isEqualTo(3);
        assertThat(beyond.path("candidatesTokenCount").asLong()).isZero();
        assertThat(beyond.path("totalTokenCount").asLong()).isEqualTo(13);
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
