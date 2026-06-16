package tech.starryapi.platform.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;

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

        assertThat(response.path("candidates").get(0).path("content").path("role").asText()).isEqualTo("model");
        assertThat(response.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText())
            .isEqualTo("Hello.");
        assertThat(response.path("usageMetadata").path("totalTokenCount").asLong()).isEqualTo(5);
        assertThat(response.path("modelVersion").asText()).isEqualTo("gemini-2.5-pro");
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

        assertThat(functionCall.path("name").asText()).isEqualTo("lookup");
        assertThat(functionCall.path("args").path("id").asText()).isEqualTo("1");
    }
}
