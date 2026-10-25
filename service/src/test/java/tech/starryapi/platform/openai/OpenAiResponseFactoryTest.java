package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
        assertThat(call.path("type").asString("")).isEqualTo("function_call");
        assertThat(call.path("call_id").asString("")).isEqualTo("toolu_1");
        assertThat(call.path("name").asString("")).isEqualTo("Bash");
        assertThat(call.path("arguments").asString("")).isEqualTo("{\"command\":\"pwd\"}");
        assertThat(call.path("status").asString("")).isEqualTo("completed");
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
        assertThat(toolCall.path("id").asString("")).isEqualTo("toolu_1");
        assertThat(toolCall.path("function").path("name").asString("")).isEqualTo("Bash");
        assertThat(toolCall.path("function").path("arguments").asString("")).isEqualTo("{\"command\":\"pwd\"}");

        JsonNode stopChunk = objectMapper.readTree(events.get(1));
        assertThat(stopChunk.path("choices").get(0).path("finish_reason").asString("")).isEqualTo("tool_calls");
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
        assertThat(message.path("reasoning_content").asString("")).isEqualTo("checked the request");
        assertThat(message.path("content").isNull()).isTrue();
        assertThat(message.path("tool_calls")).hasSize(1);
        JsonNode toolCall = message.path("tool_calls").get(0);
        assertThat(toolCall.path("id").asString("")).isEqualTo("call_lookup");
        assertThat(toolCall.path("function").path("name").asString("")).isEqualTo("lookup");
        assertThat(toolCall.path("function").path("arguments").asString(""))
            .isEqualTo("{\"query\":\"stars\"}");
        assertThat(choice.path("finish_reason").asString("")).isEqualTo("tool_calls");
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
        assertThat(reasoningDelta.path("reasoning_content").asString("")).isEqualTo("checked the request");

        JsonNode toolChunk = objectMapper.readTree(events.get(1));
        JsonNode toolCall = toolChunk.path("choices").get(0).path("delta").path("tool_calls").get(0);
        assertThat(toolCall.path("id").asString("")).isEqualTo("call_lookup");
        assertThat(toolCall.path("function").path("name").asString("")).isEqualTo("lookup");
        assertThat(toolCall.path("function").path("arguments").asString(""))
            .isEqualTo("{\"query\":\"stars\"}");

        JsonNode stopChunk = objectMapper.readTree(events.get(2));
        assertThat(stopChunk.path("choices").get(0).path("finish_reason").asString("")).isEqualTo("tool_calls");
        assertThat(events.get(3)).isEqualTo("[DONE]");
    }

    @Test
    void responsesApiStreamReturnsResponsesEvents() {
        PlatformCompletionResult result = new PlatformCompletionResult(
            "OK",
            new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0)
        );

        List<ServerSentEvent<String>> events = factory.responsesStreamEvents("gpt-5.5", result, "resp_prev")
            .collectList()
            .block();

        assertThat(events).isNotNull();
        assertThat(events)
            .extracting(ServerSentEvent::event)
            .contains("response.created", "response.output_text.delta", "response.completed");
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");

        List<JsonNode> jsonEvents = jsonEvents(events);
        assertThat(jsonEvents)
            .extracting(event -> event.path("type").asString(""))
            .contains("response.output_text.delta", "response.output_text.done", "response.completed");

        JsonNode completed = jsonEvents.stream()
            .filter(event -> "response.completed".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(completed.path("response").path("object").asString("")).isEqualTo("response");
        assertThat(completed.path("response").path("status").asString("")).isEqualTo("completed");
        assertThat(completed.path("response").path("previous_response_id").asString("")).isEqualTo("resp_prev");
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
            .extracting(item -> item.path("type").asString(""))
            .containsExactly("reasoning", "message", "function_call");
        JsonNode reasoningItem = response.path("output").get(0);
        assertThat(reasoningItem.path("id").asString("")).startsWith("rs_");
        assertThat(reasoningItem.path("status").asString("")).isEqualTo("completed");
        assertThat(reasoningItem.path("summary")).isEmpty();
        assertThat(reasoningItem.path("content").get(0).path("type").asString("")).isEqualTo("reasoning_text");
        assertThat(reasoningItem.path("content").get(0).path("text").asString("")).isEqualTo("checked the response");

        List<ServerSentEvent<String>> events = factory
            .responsesStreamEvents((tools.jackson.databind.node.ObjectNode) response)
            .collectList()
            .block();

        assertThat(events).isNotNull();
        List<JsonNode> jsonEvents = jsonEvents(events);
        assertThat(jsonEvents)
            .extracting(event -> event.path("type").asString(""))
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
            .filter(event -> "response.output_item.added".equals(event.path("type").asString("")))
            .filter(event -> "reasoning".equals(event.path("item").path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(reasoningAdded.path("item").path("content").get(0).path("text").asString("")).isEmpty();

        JsonNode reasoningDelta = jsonEvents.stream()
            .filter(event -> "response.reasoning_text.delta".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(reasoningDelta.path("delta").asString("")).isEqualTo("checked the response");

        JsonNode reasoningDone = jsonEvents.stream()
            .filter(event -> "response.reasoning_text.done".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(reasoningDone.path("text").asString("")).isEqualTo("checked the response");

        JsonNode completed = jsonEvents.stream()
            .filter(event -> "response.completed".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(completed.path("response").path("output").get(0).path("type").asString(""))
            .isEqualTo("reasoning");
        assertThat(completed.path("response").path("output").get(2).path("type").asString(""))
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

        List<ServerSentEvent<String>> events = factory.responsesStreamEvents("gpt-5.5", result, null)
            .collectList()
            .block();

        assertThat(events).isNotNull();
        List<JsonNode> jsonEvents = jsonEvents(events);
        assertThat(jsonEvents)
            .extracting(event -> event.path("type").asString(""))
            .contains("response.output_item.added", "response.function_call_arguments.done", "response.output_item.done", "response.completed");

        JsonNode toolAdded = jsonEvents.stream()
            .filter(event -> "response.output_item.added".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(toolAdded.path("item").path("arguments").asString("")).isEmpty();

        JsonNode toolDone = jsonEvents.stream()
            .filter(event -> "response.output_item.done".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(toolDone.path("item").path("type").asString("")).isEqualTo("function_call");
        assertThat(toolDone.path("item").path("call_id").asString("")).isEqualTo("call_1");
        assertThat(toolDone.path("item").path("name").asString("")).isEqualTo("Bash");
        assertThat(toolDone.path("item").path("arguments").asString("")).isEqualTo("{\"command\":\"pwd\"}");
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
            .extracting(event -> event.path("type").asString(""))
            .contains(
                "response.output_item.added",
                "response.custom_tool_call_input.delta",
                "response.custom_tool_call_input.done",
                "response.output_item.done",
                "response.completed"
            );

        JsonNode toolAdded = jsonEvents.stream()
            .filter(event -> "response.output_item.added".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(toolAdded.path("item").path("input").asString("")).isEmpty();

        JsonNode inputDelta = jsonEvents.stream()
            .filter(event -> "response.custom_tool_call_input.delta".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(inputDelta.path("delta").asString("")).endsWith("*** End Patch\n");

        JsonNode inputDone = jsonEvents.stream()
            .filter(event -> "response.custom_tool_call_input.done".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(inputDone.path("input").asString("")).endsWith("*** End Patch\n");

        JsonNode toolDone = jsonEvents.stream()
            .filter(event -> "response.output_item.done".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(toolDone.path("item").path("type").asString("")).isEqualTo("custom_tool_call");
        assertThat(toolDone.path("item").path("call_id").asString("")).isEqualTo("call_patch");
        assertThat(toolDone.path("item").path("name").asString("")).isEqualTo("apply_patch");
        assertThat(toolDone.path("item").path("input").asString("")).endsWith("*** End Patch\n");
    }

    @Test
    void endTurnAndToolUseKeepTheFinishReasonDerivedFromTheToolUses() throws Exception {
        PlatformCompletionResult text = withStopReason(textResult(), PlatformCompletionResult.StopReason.END_TURN);
        PlatformCompletionResult tools = withStopReason(toolResult(), PlatformCompletionResult.StopReason.TOOL_USE);
        PlatformCompletionResult inconsistent = withStopReason(toolResult(), PlatformCompletionResult.StopReason.END_TURN);

        assertThat(chatFinishReason(text)).isEqualTo("stop");
        assertThat(chatFinishReason(tools)).isEqualTo("tool_calls");
        assertThat(chatFinishReason(inconsistent)).isEqualTo("tool_calls");
        assertThat(chatStreamFinishReason(text)).isEqualTo("stop");
        assertThat(chatStreamFinishReason(tools)).isEqualTo("tool_calls");
        for (PlatformCompletionResult result : List.of(text, tools, inconsistent)) {
            JsonNode response = factory.responses("gpt-5", result, null);
            assertThat(response.path("status").asString("")).isEqualTo("completed");
            assertThat(response.path("incomplete_details").isNull()).isTrue();
        }
    }

    @Test
    void maxTokensIsRenderedAsLengthAndAnIncompleteResponse() throws Exception {
        PlatformCompletionResult text = withStopReason(textResult(), PlatformCompletionResult.StopReason.MAX_TOKENS);
        PlatformCompletionResult tools = withStopReason(toolResult(), PlatformCompletionResult.StopReason.MAX_TOKENS);

        assertThat(chatFinishReason(text)).isEqualTo("length");
        assertThat(chatFinishReason(tools)).isEqualTo("length");
        assertThat(chatStreamFinishReason(text)).isEqualTo("length");
        assertThat(chatStreamFinishReason(tools)).isEqualTo("length");
        assertThat(factory.chatCompletion("gpt-5", tools).path("choices").get(0).path("message").path("tool_calls"))
            .hasSize(1);

        JsonNode response = factory.responses("gpt-5", text, null);
        assertThat(response.path("status").asString("")).isEqualTo("incomplete");
        assertThat(response.path("incomplete_details").path("reason").asString("")).isEqualTo("max_output_tokens");
        assertThat(response.path("output_text").asString("")).isEqualTo("Hello");
        assertThat(factory.responses("gpt-5", tools, null).path("status").asString("")).isEqualTo("incomplete");
    }

    @Test
    void contentFilterIsRenderedAsContentFilter() throws Exception {
        PlatformCompletionResult text = withStopReason(textResult(), PlatformCompletionResult.StopReason.CONTENT_FILTER);

        assertThat(chatFinishReason(text)).isEqualTo("content_filter");
        assertThat(chatStreamFinishReason(text)).isEqualTo("content_filter");
        assertThat(chatStreamFinishReason(withStopReason(toolResult(), PlatformCompletionResult.StopReason.CONTENT_FILTER)))
            .isEqualTo("content_filter");

        JsonNode response = factory.responses("gpt-5", text, null);
        assertThat(response.path("status").asString("")).isEqualTo("incomplete");
        assertThat(response.path("incomplete_details").path("reason").asString("")).isEqualTo("content_filter");
    }

    @Test
    void anIncompleteResponseEndsItsStreamWithResponseIncomplete() {
        PlatformCompletionResult truncated = withStopReason(textResult(), PlatformCompletionResult.StopReason.MAX_TOKENS);

        List<ServerSentEvent<String>> events = factory.responsesStreamEvents("gpt-5", truncated, null)
            .collectList()
            .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
            .contains("response.created", "response.incomplete")
            .doesNotContain("response.completed");
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        List<JsonNode> json = jsonEvents(events);
        JsonNode created = json.get(0);
        assertThat(created.path("type").asString("")).isEqualTo("response.created");
        assertThat(created.path("response").path("status").asString("")).isEqualTo("in_progress");
        assertThat(created.path("response").path("incomplete_details").isNull()).isTrue();
        JsonNode terminal = json.get(json.size() - 1);
        assertThat(terminal.path("type").asString("")).isEqualTo("response.incomplete");
        assertThat(terminal.path("response").path("status").asString("")).isEqualTo("incomplete");
        assertThat(terminal.path("response").path("incomplete_details").path("reason").asString(""))
            .isEqualTo("max_output_tokens");
    }

    private PlatformCompletionResult textResult() {
        return new PlatformCompletionResult("Hello", new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0));
    }

    private PlatformCompletionResult reasoningResult() {
        return new PlatformCompletionResult(
            "Hello", "thought about it", List.of(), new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 1)
        );
    }

    private PlatformCompletionResult toolResult() throws Exception {
        return new PlatformCompletionResult(
            "",
            List.of(new PlatformToolUse("toolu_1", "Bash", objectMapper.readTree("{\"command\":\"pwd\"}"))),
            new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0)
        );
    }

    private PlatformCompletionResult withStopReason(PlatformCompletionResult plain, PlatformCompletionResult.StopReason stopReason) {
        return new PlatformCompletionResult(
            plain.content(), plain.reasoningContent(), plain.toolUses(), plain.usage(), stopReason, null, null
        );
    }

    private String chatFinishReason(PlatformCompletionResult result) {
        return factory.chatCompletion("gpt-5", result).path("choices").get(0).path("finish_reason").asString("");
    }

    private String chatStreamFinishReason(PlatformCompletionResult result) {
        List<JsonNode> chunks = jsonEvents(factory.chatStreamEvents("gpt-5", result).collectList().block());
        return chunks.get(chunks.size() - 1).path("choices").get(0).path("finish_reason").asString("");
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
