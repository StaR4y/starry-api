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
    void chatCompletionPreservesDeepSeekReasoningAlongsideToolCalls() throws Exception {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "",
            "checked the request",
            List.of(new PlatformToolUse(
                "call_lookup",
                "lookup",
                objectMapper.readTree("{\"query\":\"stars\"}")
            )),
            new PlatformUsage(110, 20, java.math.BigDecimal.ZERO, 100, 0, 10)
        );

        JsonNode response = factory.chatCompletion("deepseek-v4-flash", result);

        JsonNode choice = response.path("choices").get(0);
        JsonNode message = choice.path("message");
        assertThat(message.path("reasoning_content").asText()).isEqualTo("checked the request");
        assertThat(message.path("content").isNull()).isTrue();
        assertThat(message.path("tool_calls")).hasSize(1);
        JsonNode toolCall = message.path("tool_calls").get(0);
        assertThat(toolCall.path("id").asText()).isEqualTo("call_lookup");
        assertThat(toolCall.path("function").path("name").asText()).isEqualTo("lookup");
        assertThat(toolCall.path("function").path("arguments").asText())
            .isEqualTo("{\"query\":\"stars\"}");
        assertThat(choice.path("finish_reason").asText()).isEqualTo("tool_calls");
    }

    @Test
    void chatStreamPreservesDeepSeekReasoningAlongsideToolCallDeltas() throws Exception {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "",
            "checked the request",
            List.of(new PlatformToolUse(
                "call_lookup",
                "lookup",
                objectMapper.readTree("{\"query\":\"stars\"}")
            )),
            new PlatformUsage(110, 20, java.math.BigDecimal.ZERO, 100, 0, 10)
        );

        List<String> events = factory.chatStreamEvents("deepseek-v4-flash", result)
            .map(ServerSentEvent::data)
            .collectList()
            .block();

        assertThat(events).hasSize(4);
        JsonNode reasoningChunk = objectMapper.readTree(events.get(0));
        JsonNode reasoningDelta = reasoningChunk.path("choices").get(0).path("delta");
        assertThat(reasoningDelta.path("reasoning_content").asText()).isEqualTo("checked the request");

        JsonNode toolChunk = objectMapper.readTree(events.get(1));
        JsonNode toolCall = toolChunk.path("choices").get(0).path("delta").path("tool_calls").get(0);
        assertThat(toolCall.path("id").asText()).isEqualTo("call_lookup");
        assertThat(toolCall.path("function").path("name").asText()).isEqualTo("lookup");
        assertThat(toolCall.path("function").path("arguments").asText())
            .isEqualTo("{\"query\":\"stars\"}");

        JsonNode stopChunk = objectMapper.readTree(events.get(2));
        assertThat(stopChunk.path("choices").get(0).path("finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(events.get(3)).isEqualTo("[DONE]");
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
    void responsesApiPreservesReasoningItemAndReasoningStreamEvents() throws Exception {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "OK",
            "checked the response",
            List.of(new PlatformToolUse(
                "call_lookup",
                "lookup",
                objectMapper.readTree("{\"query\":\"stars\"}")
            )),
            new PlatformUsage(110, 20, java.math.BigDecimal.ZERO, 100, 0, 10)
        );

        JsonNode response = factory.responses("deepseek-v4-flash", result, null);

        assertThat(response.path("output"))
            .extracting(item -> item.path("type").asText())
            .containsExactly("reasoning", "message", "function_call");
        JsonNode reasoningItem = response.path("output").get(0);
        assertThat(reasoningItem.path("id").asText()).startsWith("rs_");
        assertThat(reasoningItem.path("status").asText()).isEqualTo("completed");
        assertThat(reasoningItem.path("summary")).isEmpty();
        assertThat(reasoningItem.path("content").get(0).path("type").asText()).isEqualTo("reasoning_text");
        assertThat(reasoningItem.path("content").get(0).path("text").asText()).isEqualTo("checked the response");

        List<ServerSentEvent<String>> events = factory
            .responsesStreamEvents((com.fasterxml.jackson.databind.node.ObjectNode) response)
            .collectList()
            .block();

        assertThat(events).isNotNull();
        List<JsonNode> jsonEvents = jsonEvents(events);
        assertThat(jsonEvents)
            .extracting(event -> event.path("type").asText())
            .containsSubsequence(
                "response.output_item.added",
                "response.reasoning_text.delta",
                "response.reasoning_text.done",
                "response.output_item.done"
            )
            .contains(
                "response.output_text.delta",
                "response.function_call_arguments.done",
                "response.completed"
            );

        JsonNode reasoningAdded = jsonEvents.stream()
            .filter(event -> "response.output_item.added".equals(event.path("type").asText()))
            .filter(event -> "reasoning".equals(event.path("item").path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(reasoningAdded.path("item").path("content").get(0).path("text").asText()).isEmpty();

        JsonNode reasoningDelta = jsonEvents.stream()
            .filter(event -> "response.reasoning_text.delta".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(reasoningDelta.path("delta").asText()).isEqualTo("checked the response");

        JsonNode reasoningDone = jsonEvents.stream()
            .filter(event -> "response.reasoning_text.done".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(reasoningDone.path("text").asText()).isEqualTo("checked the response");

        JsonNode completed = jsonEvents.stream()
            .filter(event -> "response.completed".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(completed.path("response").path("output").get(0).path("type").asText())
            .isEqualTo("reasoning");
        assertThat(completed.path("response").path("output").get(2).path("type").asText())
            .isEqualTo("function_call");
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
                objectMapper.createObjectNode().put(
                    "_rawArguments",
                    "*** Begin Patch\n*** Add File: codex_apply_patch_test.txt\n+hello\n*** End Patch"
                ),
                "custom_tool_call",
                "*** Begin Patch\n*** Add File: codex_apply_patch_test.txt\n+hello\n*** End Patch",
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
                "response.custom_tool_call_input.delta",
                "response.custom_tool_call_input.done",
                "response.output_item.done",
                "response.completed"
            );

        JsonNode toolAdded = jsonEvents.stream()
            .filter(event -> "response.output_item.added".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(toolAdded.path("item").path("input").asText()).isEmpty();

        JsonNode inputDelta = jsonEvents.stream()
            .filter(event -> "response.custom_tool_call_input.delta".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(inputDelta.path("delta").asText()).endsWith("*** End Patch\n");

        JsonNode inputDone = jsonEvents.stream()
            .filter(event -> "response.custom_tool_call_input.done".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(inputDone.path("input").asText()).endsWith("*** End Patch\n");

        JsonNode toolDone = jsonEvents.stream()
            .filter(event -> "response.output_item.done".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(toolDone.path("item").path("type").asText()).isEqualTo("custom_tool_call");
        assertThat(toolDone.path("item").path("call_id").asText()).isEqualTo("call_patch");
        assertThat(toolDone.path("item").path("name").asText()).isEqualTo("apply_patch");
        assertThat(toolDone.path("item").path("input").asText()).endsWith("*** End Patch\n");
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
