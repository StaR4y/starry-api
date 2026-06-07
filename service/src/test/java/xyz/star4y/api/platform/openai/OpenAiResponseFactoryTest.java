package xyz.star4y.api.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.core.PlatformToolUse;
import xyz.star4y.api.platform.core.PlatformUsage;
import xyz.star4y.api.platform.openai.OpenAiResponseFactory;

class OpenAiResponseFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiResponseFactory factory = new OpenAiResponseFactory(objectMapper);

    @Test
    void responsesApiReturnsFunctionCallOutputItems() throws Exception {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "",
            List.of(new PlatformToolUse(
                "toolu_1",
                "Bash",
                objectMapper.readTree("{\"command\":\"pwd\"}")
            )),
            new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0)
        );

        JsonNode response = factory.responses("claude-sonnet-4.5", result, null);

        JsonNode call = response.path("output").get(0);
        assertThat(call.path("type").asText()).isEqualTo("function_call");
        assertThat(call.path("call_id").asText()).isEqualTo("toolu_1");
        assertThat(call.path("name").asText()).isEqualTo("Bash");
        assertThat(call.path("arguments").asText()).isEqualTo("{\"command\":\"pwd\"}");
        assertThat(call.path("status").asText()).isEqualTo("completed");
    }

    @Test
    void chatStreamReturnsToolCallDeltas() throws Exception {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "",
            List.of(new PlatformToolUse(
                "toolu_1",
                "Bash",
                objectMapper.readTree("{\"command\":\"pwd\"}")
            )),
            new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0)
        );

        List<String> events = factory.chatStreamEvents("claude-sonnet-4.5", result)
            .map(event -> event.data())
            .collectList()
            .block();

        assertThat(events).hasSize(3);
        JsonNode toolChunk = objectMapper.readTree(events.get(0));
        JsonNode toolCall = toolChunk.path("choices").get(0).path("delta").path("tool_calls").get(0);
        assertThat(toolCall.path("id").asText()).isEqualTo("toolu_1");
        assertThat(toolCall.path("function").path("name").asText()).isEqualTo("Bash");
        assertThat(toolCall.path("function").path("arguments").asText()).isEqualTo("{\"command\":\"pwd\"}");

        JsonNode stopChunk = objectMapper.readTree(events.get(1));
        assertThat(stopChunk.path("choices").get(0).path("finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(events.get(2)).isEqualTo("[DONE]");
    }
}
