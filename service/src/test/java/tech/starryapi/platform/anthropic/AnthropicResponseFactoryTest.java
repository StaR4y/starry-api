package tech.starryapi.platform.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;

class AnthropicResponseFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicResponseFactory factory = new AnthropicResponseFactory(objectMapper);

    @Test
    void messageReturnsToolUseBlocks() throws Exception {
        PlatformCompletionResult result = toolUseResult();

        JsonNode response = factory.message("claude-sonnet-4.5", result);

        assertThat(response.path("stop_reason").asText()).isEqualTo("tool_use");
        JsonNode block = response.path("content").path(0);
        assertThat(block.path("type").asText()).isEqualTo("tool_use");
        assertThat(block.path("id").asText()).isEqualTo("toolu_1");
        assertThat(block.path("name").asText()).isEqualTo("Bash");
        assertThat(block.path("input").path("command").asText()).isEqualTo("pwd");
    }

    @Test
    void messageStreamReturnsToolUseEvents() throws Exception {
        PlatformCompletionResult result = toolUseResult();

        List<ServerSentEvent<String>> events = factory.messageStream("claude-sonnet-4.5", result)
            .collectList()
            .block();

        assertThat(events).isNotNull();
        assertThat(events)
            .extracting(ServerSentEvent::event)
            .contains("message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop");

        List<JsonNode> jsonEvents = events.stream()
            .map(ServerSentEvent::data)
            .map(this::readJson)
            .toList();
        JsonNode blockStart = jsonEvents.stream()
            .filter(event -> "content_block_start".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(blockStart.path("content_block").path("type").asText()).isEqualTo("tool_use");
        assertThat(blockStart.path("content_block").path("id").asText()).isEqualTo("toolu_1");

        JsonNode inputDelta = jsonEvents.stream()
            .filter(event -> "content_block_delta".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(inputDelta.path("delta").path("type").asText()).isEqualTo("input_json_delta");
        assertThat(inputDelta.path("delta").path("partial_json").asText()).isEqualTo("{\"command\":\"pwd\"}");

        JsonNode messageDelta = jsonEvents.stream()
            .filter(event -> "message_delta".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(messageDelta.path("delta").path("stop_reason").asText()).isEqualTo("tool_use");
    }

    private PlatformCompletionResult toolUseResult() throws Exception {
        return new PlatformCompletionResult(
            "",
            List.of(new PlatformToolUse(
                "toolu_1",
                "Bash",
                objectMapper.readTree("{\"command\":\"pwd\"}")
            )),
            new PlatformUsage(10, 2, BigDecimal.ZERO, 0, 0, 0)
        );
    }

    private JsonNode readJson(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
