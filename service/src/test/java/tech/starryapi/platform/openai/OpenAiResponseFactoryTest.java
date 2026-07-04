package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;

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

    @Test
    void responsesApiStreamReturnsResponsesEvents() {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "OK",
            new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0)
        );

        List<ServerSentEvent<String>> events = factory.responsesStreamEvents("gpt-5.4-mini", result, "resp_prev")
            .collectList()
            .block();

        assertThat(events).isNotNull();
        assertThat(events)
            .extracting(ServerSentEvent::event)
            .contains("response.created", "response.output_text.delta", "response.completed");
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");

        List<JsonNode> jsonEvents = jsonEvents(events);
        assertThat(jsonEvents)
            .extracting(event -> event.path("type").asText())
            .contains("response.output_text.delta", "response.output_text.done", "response.completed");

        JsonNode completed = jsonEvents.stream()
            .filter(event -> "response.completed".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(completed.path("response").path("object").asText()).isEqualTo("response");
        assertThat(completed.path("response").path("status").asText()).isEqualTo("completed");
        assertThat(completed.path("response").path("previous_response_id").asText()).isEqualTo("resp_prev");
    }

    @Test
    void responsesApiStreamReturnsFunctionCallEvents() throws Exception {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "",
            List.of(new PlatformToolUse(
                "call_1",
                "Bash",
                objectMapper.readTree("{\"command\":\"pwd\"}")
            )),
            new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0)
        );

        List<ServerSentEvent<String>> events = factory.responsesStreamEvents("gpt-5.4-mini", result, null)
            .collectList()
            .block();

        assertThat(events).isNotNull();
        List<JsonNode> jsonEvents = jsonEvents(events);
        assertThat(jsonEvents)
            .extracting(event -> event.path("type").asText())
            .contains("response.output_item.added", "response.function_call_arguments.done", "response.output_item.done", "response.completed");

        JsonNode toolAdded = jsonEvents.stream()
            .filter(event -> "response.output_item.added".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(toolAdded.path("item").path("arguments").asText()).isEmpty();

        JsonNode toolDone = jsonEvents.stream()
            .filter(event -> "response.output_item.done".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(toolDone.path("item").path("type").asText()).isEqualTo("function_call");
        assertThat(toolDone.path("item").path("call_id").asText()).isEqualTo("call_1");
        assertThat(toolDone.path("item").path("name").asText()).isEqualTo("Bash");
        assertThat(toolDone.path("item").path("arguments").asText()).isEqualTo("{\"command\":\"pwd\"}");
    }

    @Test
    void responsesApiStreamReturnsCustomToolCallEvents() {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "",
            List.of(new PlatformToolUse(
                "call_patch",
                "apply_patch",
                objectMapper.createObjectNode().put("_rawArguments", "*** Begin Patch"),
                "custom_tool_call",
                "*** Begin Patch",
                null
            )),
            new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0)
        );

        List<ServerSentEvent<String>> events = factory.responsesStreamEvents("gpt-5.5", result, null)
            .collectList()
            .block();

        assertThat(events).isNotNull();
        List<JsonNode> jsonEvents = jsonEvents(events);
        assertThat(jsonEvents)
            .extracting(event -> event.path("type").asText())
            .contains(
                "response.output_item.added",
                "response.custom_tool_call_input.done",
                "response.output_item.done",
                "response.completed"
            );
        assertThat(jsonEvents)
            .extracting(event -> event.path("type").asText())
            .doesNotContain("response.custom_tool_call_input.delta");

        JsonNode toolAdded = jsonEvents.stream()
            .filter(event -> "response.output_item.added".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(toolAdded.path("item").path("input").asText()).isEqualTo("*** Begin Patch");

        JsonNode toolDone = jsonEvents.stream()
            .filter(event -> "response.output_item.done".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(toolDone.path("item").path("type").asText()).isEqualTo("custom_tool_call");
        assertThat(toolDone.path("item").path("call_id").asText()).isEqualTo("call_patch");
        assertThat(toolDone.path("item").path("name").asText()).isEqualTo("apply_patch");
        assertThat(toolDone.path("item").path("input").asText()).isEqualTo("*** Begin Patch");
    }

    private List<JsonNode> jsonEvents(List<ServerSentEvent<String>> events) {
        return events.stream()
            .map(ServerSentEvent::data)
            .filter(data -> data != null && !"[DONE]".equals(data))
            .map(this::readJson)
            .toList();
    }

    private JsonNode readJson(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
