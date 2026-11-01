package tech.starryapi.platform.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AnthropicResponseFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicResponseFactory factory = new AnthropicResponseFactory(objectMapper);

    @Test
    void messageReturnsToolUseBlocks() throws Exception {
        PlatformCompletionResult result = toolUseResult();

        JsonNode response = factory.message("claude-sonnet-4.5", result);

        assertThat(response.path("stop_reason").asString("")).isEqualTo("tool_use");
        JsonNode block = response.path("content").path(0);
        assertThat(block.path("type").asString("")).isEqualTo("tool_use");
        assertThat(block.path("id").asString("")).isEqualTo("toolu_1");
        assertThat(block.path("name").asString("")).isEqualTo("Bash");
        assertThat(block.path("input").path("command").asString("")).isEqualTo("pwd");
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
            .filter(event -> "content_block_start".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(blockStart.path("content_block").path("type").asString("")).isEqualTo("tool_use");
        assertThat(blockStart.path("content_block").path("id").asString("")).isEqualTo("toolu_1");

        JsonNode inputDelta = jsonEvents.stream()
            .filter(event -> "content_block_delta".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(inputDelta.path("delta").path("type").asString("")).isEqualTo("input_json_delta");
        assertThat(inputDelta.path("delta").path("partial_json").asString("")).isEqualTo("{\"command\":\"pwd\"}");

        JsonNode messageDelta = jsonEvents.stream()
            .filter(event -> "message_delta".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(messageDelta.path("delta").path("stop_reason").asString("")).isEqualTo("tool_use");
    }

    @Test
    void endTurnAndToolUseKeepTheStopReasonDerivedFromTheToolUses() throws Exception {
        PlatformCompletionResult text = withStopReason(new PlatformCompletionResult("Hello", usage()), PlatformCompletionResult.StopReason.END_TURN);
        PlatformCompletionResult tools = withStopReason(toolUseResult(), PlatformCompletionResult.StopReason.TOOL_USE);
        PlatformCompletionResult inconsistent = withStopReason(toolUseResult(), PlatformCompletionResult.StopReason.END_TURN);

        assertThat(factory.message("m", text).path("stop_reason").asString("")).isEqualTo("end_turn");
        assertThat(factory.message("m", tools).path("stop_reason").asString("")).isEqualTo("tool_use");
        assertThat(factory.message("m", inconsistent).path("stop_reason").asString("")).isEqualTo("tool_use");
        assertThat(streamStopReason(text)).isEqualTo("end_turn");
        assertThat(streamStopReason(tools)).isEqualTo("tool_use");
    }

    @Test
    void maxTokensIsRenderedAsMaxTokens() throws Exception {
        PlatformCompletionResult text = withStopReason(new PlatformCompletionResult("cut off", usage()), PlatformCompletionResult.StopReason.MAX_TOKENS);
        PlatformCompletionResult withTool = withStopReason(toolUseResult(), PlatformCompletionResult.StopReason.MAX_TOKENS);

        JsonNode message = factory.message("m", text);
        assertThat(message.path("stop_reason").asString("")).isEqualTo("max_tokens");
        assertThat(message.path("content").path(0).path("text").asString("")).isEqualTo("cut off");
        assertThat(streamStopReason(text)).isEqualTo("max_tokens");

        JsonNode toolMessage = factory.message("m", withTool);
        assertThat(toolMessage.path("stop_reason").asString("")).isEqualTo("max_tokens");
        assertThat(toolMessage.path("content").path(0).path("type").asString("")).isEqualTo("tool_use");
        assertThat(streamStopReason(withTool)).isEqualTo("max_tokens");
    }

    @Test
    void contentFilterIsRenderedAsRefusal() throws Exception {
        PlatformCompletionResult result = withStopReason(new PlatformCompletionResult("", usage()), PlatformCompletionResult.StopReason.CONTENT_FILTER);

        assertThat(factory.message("m", result).path("stop_reason").asString("")).isEqualTo("refusal");
        assertThat(streamStopReason(result)).isEqualTo("refusal");
    }

    private PlatformUsage usage() {
        return new PlatformUsage(10, 2, BigDecimal.ZERO, 0, 0, 0);
    }

    private PlatformCompletionResult withStopReason(PlatformCompletionResult plain, PlatformCompletionResult.StopReason stopReason) {
        return new PlatformCompletionResult(
            plain.content(), plain.reasoningContent(), plain.toolUses(), plain.usage(), stopReason, null, null
        );
    }

    private String streamStopReason(PlatformCompletionResult result) {
        return factory.messageStream("m", result)
            .map(ServerSentEvent::data)
            .map(this::readJson)
            .filter(event -> "message_delta".equals(event.path("type").asString("")))
            .blockFirst()
            .path("delta").path("stop_reason").asString("");
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
