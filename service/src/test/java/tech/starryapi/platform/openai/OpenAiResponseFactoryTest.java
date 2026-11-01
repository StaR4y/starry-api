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

    private PlatformCompletionResult signedAndUnsigned() throws Exception {
        return new PlatformCompletionResult(
            "",
            "",
            List.of(
                new PlatformToolUse("call_1", "lookup", objectMapper.readTree("{\"q\":1}"), "function_call", "{\"q\":1}", null, "SIG-1"),
                new PlatformToolUse("call_2", "other", objectMapper.readTree("{}"))
            ),
            new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0),
            PlatformCompletionResult.StopReason.TOOL_USE,
            PlatformCompletionResult.UsageSource.REPORTED,
            List.of()
        );
    }

    /** The signature of a Gemini model's call goes to the Chat client as Google's OpenAI-compatible API writes it, and only then. */
    @Test
    void aChatCompletionGivesTheThoughtSignatureOfACallAsExtraContent() throws Exception {
        JsonNode calls = factory.chatCompletion("gemini-2.5-pro", signedAndUnsigned()).path("choices").get(0).path("message").path("tool_calls");

        assertThat(calls.get(0).path("extra_content")).isEqualTo(objectMapper.readTree("{\"google\":{\"thought_signature\":\"SIG-1\"}}"));
        assertThat(calls.get(0).propertyNames()).containsExactly("id", "type", "function", "extra_content");
        assertThat(calls.get(1).has("extra_content")).isFalse();
    }

    @Test
    void aChatStreamGivesTheThoughtSignatureOfACallWithTheCall() throws Exception {
        List<JsonNode> chunks = factory.chatStreamEvents("gemini-2.5-pro", signedAndUnsigned())
            .map(ServerSentEvent::data)
            .filter(data -> !"[DONE]".equals(data))
            .map(objectMapper::readTree)
            .collectList()
            .block();

        JsonNode first = chunks.get(0).path("choices").get(0).path("delta").path("tool_calls").get(0);
        JsonNode second = chunks.get(1).path("choices").get(0).path("delta").path("tool_calls").get(0);
        assertThat(first.path("extra_content").path("google").path("thought_signature").asString("")).isEqualTo("SIG-1");
        assertThat(second.has("extra_content")).isFalse();
    }

    @Test
    void aCallWithoutASignatureIsRenderedAsItAlwaysWasInEveryMode() throws Exception {
        PlatformCompletionResult native1 = new PlatformCompletionResult(
            "", List.of(new PlatformToolUse("toolu_1", "Bash", objectMapper.readTree("{}"))), PlatformUsage.zero());

        assertThat(factory.chatCompletion("claude-sonnet-4.5", native1).toString()).doesNotContain("extra_content", "thought");
        assertThat(factory.responses("claude-sonnet-4.5", signedAndUnsigned(), null).toString()).doesNotContain("extra_content", "SIG-1");
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

    @Test
    void theReasoningItemTheFactoryWritesIsTheOneItRecognizesAndNoItemOfOpenAIIs() throws Exception {
        JsonNode response = factory.responses("gpt-5", reasoningResult(), null);
        JsonNode written = response.path("output").get(0);

        assertThat(written.path("type").asString("")).isEqualTo("reasoning");
        assertThat(OpenAiResponseFactory.isRenderedReasoning(written)).isTrue();
        // As a client that sends its history back writes it: without the id and the status.
        assertThat(OpenAiResponseFactory.isRenderedReasoning(objectMapper.readTree("""
            {"type":"reasoning","summary":[],"content":[{"type":"reasoning_text","text":"t"}],"encrypted_content":null}"""))).isTrue();
        for (String openAis : List.of(
            """
            {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"gAAAAB"}""",
            """
            {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"s"}]}""",
            """
            {"type":"reasoning","summary":[],"content":[{"type":"reasoning_text","text":"t"}],"encrypted_content":"gAAAAB"}""",
            """
            {"type":"reasoning","summary":[],"content":[{"type":"text","text":"t"}]}""",
            """
            {"type":"reasoning","summary":[],"content":[]}""",
            """
            {"type":"reasoning","id":"rs_1","summary":[]}""",
            """
            {"type":"message","role":"assistant","content":[{"type":"reasoning_text","text":"t"}]}""",
            "\"reasoning\"")) {
            assertThat(OpenAiResponseFactory.isRenderedReasoning(objectMapper.readTree(openAis))).as(openAis).isFalse();
        }
    }

    @Test
    void theRenderedReasoningIsTakenOutOfARequestWithoutChangingIt() throws Exception {
        JsonNode request = objectMapper.readTree("""
            {"model":"gpt-5.4","store":false,"input":[
              {"type":"message","role":"user","content":"one"},
              {"type":"reasoning","summary":[],"content":[{"type":"reasoning_text","text":"relay"}]},
              {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"gAAAAB"},
              {"type":"message","role":"assistant","content":"two"},
              {"type":"reasoning","summary":[],"content":[{"type":"reasoning_text","text":"relay again"}]}]}""");
        JsonNode before = request.deepCopy();

        JsonNode without = OpenAiResponseFactory.withoutRenderedReasoning(request);

        assertThat(without.path("input")).extracting(item -> item.path("type").asString("")).containsExactly("message", "reasoning", "message");
        assertThat(without.path("input").get(1).path("encrypted_content").asString("")).isEqualTo("gAAAAB");
        assertThat(without.path("model").asString("")).isEqualTo("gpt-5.4");
        assertThat(without.path("store").booleanValue()).isFalse();
        assertThat(request).isEqualTo(before);
        // A request that holds none is the very same request, and so is one whose input is not a list.
        JsonNode plain = objectMapper.readTree("{\"model\":\"m\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"q\"}]}");
        assertThat(OpenAiResponseFactory.withoutRenderedReasoning(plain)).isSameAs(plain);
        JsonNode text = objectMapper.readTree("{\"model\":\"m\",\"input\":\"q\"}");
        assertThat(OpenAiResponseFactory.withoutRenderedReasoning(text)).isSameAs(text);
    }

    /**
     * What a relay called the editing tool with, and what the client answered, are a custom tool call and its output in the
     * chain; the sources whose conversion knows only functions are given them as the call of a function and its result.
     */
    @Test
    void theCustomToolCallsOfAnInputAreFunctionCallsForTheRoutesThatKnowOnlyThose() {
        JsonNode request = objectMapper.readTree("""
            {"model":"claude-sonnet-4.5","store":false,"input":[
              {"type":"message","role":"user","content":"add a file"},
              {"type":"custom_tool_call","id":"ctc_1","call_id":"call_1","name":"apply_patch","input":"*** Begin Patch\\n*** End Patch\\n","status":"completed"},
              {"type":"custom_tool_call_output","call_id":"call_1","output":"Done!"},
              {"type":"custom_tool_call","call_id":"call_2","name":"shell","input":"ls -la"},
              {"type":"custom_tool_call_output","call_id":"call_2","output":[{"type":"output_text","text":"a.txt"}]},
              {"type":"function_call","call_id":"call_3","name":"exec_command","arguments":"{}"}]}""");
        JsonNode before = request.deepCopy();

        JsonNode converted = OpenAiResponseFactory.withCustomCallsAsFunctionCalls(request);

        JsonNode input = converted.path("input");
        assertThat(input).extracting(item -> item.path("type").asString("")).containsExactly(
            "message", "function_call", "function_call_output", "function_call", "function_call_output", "function_call");
        assertThat(input.get(1).path("call_id").asString()).isEqualTo("call_1");
        assertThat(input.get(1).path("name").asString()).isEqualTo("apply_patch");
        // No tool of the request is the editing tool, so there is nothing to follow: the argument of any custom tool.
        assertThat(objectMapper.readTree(input.get(1).path("arguments").asString()).path("input").asString())
            .isEqualTo("*** Begin Patch\n*** End Patch\n");
        assertThat(input.get(1).has("input")).isFalse();
        assertThat(input.get(2).path("call_id").asString()).isEqualTo("call_1");
        assertThat(input.get(2).path("output").asString()).isEqualTo("Done!");
        assertThat(objectMapper.readTree(input.get(3).path("arguments").asString()).path("input").asString()).isEqualTo("ls -la");
        assertThat(input.get(4).path("output").get(0).path("text").asString()).isEqualTo("a.txt");
        assertThat(input.get(5)).isSameAs(request.path("input").get(5));
        assertThat(converted.path("model").asString()).isEqualTo("claude-sonnet-4.5");
        assertThat(converted.path("store").booleanValue()).isFalse();
        // The request is not changed; one that holds no custom call is the very same request, and so is one whose input is a text.
        assertThat(request).isEqualTo(before);
        JsonNode plain = objectMapper.readTree("{\"model\":\"m\",\"input\":[{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"f\",\"arguments\":\"{}\"}]}");
        assertThat(OpenAiResponseFactory.withCustomCallsAsFunctionCalls(plain)).isSameAs(plain);
        JsonNode text = objectMapper.readTree("{\"model\":\"m\",\"input\":\"q\"}");
        assertThat(OpenAiResponseFactory.withCustomCallsAsFunctionCalls(text)).isSameAs(text);
    }

    /**
     * The Kiro and Anthropic routes are given the client's own declaration of the editing tool, not the gateway's function with
     * its {@code patch}, so the history shows the call with the argument that declaration has: the string property of the
     * function (Codex's JSON tool takes {@code input}), and {@code input} for a custom tool, which those routes declare with no
     * properties.
     */
    @Test
    void theArgumentOfAReplayedPatchIsTheOneTheRouteDeclaresTheEditingToolWith() {
        assertThat(replayedPatchArgument("[{\"type\":\"function\",\"name\":\"apply_patch\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"]}}]")).isEqualTo("input");
        assertThat(replayedPatchArgument("[{\"type\":\"function\",\"name\":\"apply_patch\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"diff\":{\"type\":\"string\"}},\"required\":[\"diff\"]}}]")).isEqualTo("diff");
        // The required one when there are several, else the first string one; the nested shape of the Chat API is the same function.
        assertThat(replayedPatchArgument("[{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"dry_run\":{\"type\":\"boolean\"},\"note\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}},"
            + "\"required\":[\"text\"]}}}]")).isEqualTo("text");
        assertThat(replayedPatchArgument("[{\"type\":\"function\",\"name\":\"apply_patch\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"dry_run\":{\"type\":\"boolean\"},\"note\":{\"type\":\"string\"}}}}]")).isEqualTo("note");
        // A custom tool left as it is would be declared to those routes with an empty schema (the facade declares it as the
        // gateway's patch function first, see below), a function with none to name is too, and so is no declaration.
        assertThat(replayedPatchArgument("[{\"type\":\"custom\",\"name\":\"apply_patch\",\"format\":{\"type\":\"grammar\"}}]")).isEqualTo("input");
        assertThat(replayedPatchArgument("[{\"type\":\"function\",\"name\":\"apply_patch\"}]")).isEqualTo("input");
        assertThat(replayedPatchArgument("[{\"type\":\"function\",\"name\":\"shell\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"command\":{\"type\":\"string\"}}}}]")).isEqualTo("input");
        assertThat(replayedPatchArgument(null)).isEqualTo("input");
    }

    // ---- the editing tool on the Kiro and Anthropic routes of a vendor that has relays (D13) ----

    private static final String PATCH = "*** Begin Patch\n*** Add File: a.txt\n+hi\n*** End Patch";

    private JsonNode requestWithTools(String tools, String toolChoice) {
        return objectMapper.readTree("{\"model\":\"claude-sonnet-4.5\",\"stream\":true,\"tools\":" + tools
            + (toolChoice == null ? "" : ",\"tool_choice\":" + toolChoice) + ",\"input\":\"q\"}");
    }

    private static final String CUSTOM_PATCH_TOOL = "{\"type\":\"custom\",\"name\":\"apply_patch\",\"description\":\"Free form, no JSON\","
        + "\"format\":{\"type\":\"grammar\",\"syntax\":\"lark\",\"definition\":\"start: begin_patch\"}}";

    @Test
    void aClientDeclaresTheEditingToolAsACustomToolOnlyWhenTheToolSaysSo() {
        assertThat(OpenAiResponseFactory.declaresEditingToolAsCustom(requestWithTools("[" + CUSTOM_PATCH_TOOL + "]", null))).isTrue();
        assertThat(OpenAiResponseFactory.declaresEditingToolAsCustom(requestWithTools(
            "[{\"type\":\"function\",\"name\":\"shell\"}," + CUSTOM_PATCH_TOOL + "]", null))).isTrue();
        // As a function, in either shape, it is the client's own function; another custom tool is not the editing tool.
        assertThat(OpenAiResponseFactory.declaresEditingToolAsCustom(requestWithTools(
            "[{\"type\":\"function\",\"name\":\"apply_patch\",\"parameters\":{\"type\":\"object\"}}]", null))).isFalse();
        assertThat(OpenAiResponseFactory.declaresEditingToolAsCustom(requestWithTools(
            "[{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\"}}]", null))).isFalse();
        assertThat(OpenAiResponseFactory.declaresEditingToolAsCustom(requestWithTools(
            "[{\"type\":\"custom\",\"name\":\"shell_grammar\"}]", null))).isFalse();
        assertThat(OpenAiResponseFactory.declaresEditingToolAsCustom(objectMapper.readTree("{\"model\":\"m\",\"input\":\"q\"}"))).isFalse();
    }

    @Test
    void theEditingToolDeclaredAsACustomToolIsDeclaredToTheKiroAndAnthropicRoutesAsTheGatewaysPatchFunction() {
        JsonNode request = requestWithTools("[{\"type\":\"function\",\"name\":\"shell\",\"parameters\":{\"type\":\"object\"}},"
            + CUSTOM_PATCH_TOOL + "]", "\"auto\"");
        JsonNode before = request.deepCopy();

        JsonNode converted = OpenAiResponseFactory.withEditingToolAsPatchFunction(request);

        JsonNode tools = converted.path("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0)).isSameAs(request.path("tools").get(0));
        // The function the relays are given: the Chat shape, one required string argument, the gateway's words and no strict.
        JsonNode patch = tools.get(1);
        assertThat(patch.path("type").asString()).isEqualTo("function");
        assertThat(patch.path("function").path("name").asString()).isEqualTo("apply_patch");
        assertThat(patch.path("function").path("description").asString()).isEqualTo(ApplyPatchEmulation.CHAT_DESCRIPTION);
        assertThat(patch.path("function").path("parameters").path("properties").path("patch").path("type").asString()).isEqualTo("string");
        assertThat(patch.path("function").path("parameters").path("required")).extracting(JsonNode::asString).containsExactly("patch");
        assertThat(patch.path("function").has("strict")).isFalse();
        assertThat(converted.path("tool_choice").asString()).isEqualTo("auto");
        assertThat(converted.path("stream").booleanValue()).isTrue();
        // The request of the client is not changed.
        assertThat(request).isEqualTo(before);
        // The replay of a history that has the call in it reads the argument from this declaration.
        JsonNode history = objectMapper.readTree("{\"model\":\"m\",\"tools\":" + tools + ",\"input\":["
            + "{\"type\":\"custom_tool_call\",\"call_id\":\"call_1\",\"name\":\"apply_patch\",\"input\":\"" + PATCH.replace("\n", "\\n") + "\"}]}");
        assertThat(objectMapper.readTree(OpenAiResponseFactory.withCustomCallsAsFunctionCalls(history).path("input").get(0)
            .path("arguments").asString()).path("patch").asString()).isEqualTo(PATCH);
    }

    @Test
    void aToolChoiceThatForcesTheEditingToolFollowsItsDeclaration() {
        JsonNode forced = OpenAiResponseFactory.withEditingToolAsPatchFunction(
            requestWithTools("[" + CUSTOM_PATCH_TOOL + "]", "{\"type\":\"custom\",\"name\":\"apply_patch\"}"));
        assertThat(forced.path("tool_choice").path("type").asString()).isEqualTo("function");
        assertThat(forced.path("tool_choice").path("function").path("name").asString()).isEqualTo("apply_patch");

        JsonNode other = OpenAiResponseFactory.withEditingToolAsPatchFunction(
            requestWithTools("[" + CUSTOM_PATCH_TOOL + "]", "{\"type\":\"function\",\"name\":\"shell\"}"));
        assertThat(other.path("tool_choice").path("name").asString()).isEqualTo("shell");
    }

    @Test
    void aRequestThatDoesNotDeclareTheEditingToolAsACustomToolIsLeftAsItIs() {
        JsonNode asFunction = requestWithTools("[{\"type\":\"function\",\"name\":\"apply_patch\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"input\":{\"type\":\"string\"}}}}]", null);
        JsonNode none = objectMapper.readTree("{\"model\":\"m\",\"input\":\"q\"}");

        assertThat(OpenAiResponseFactory.withEditingToolAsPatchFunction(asFunction)).isSameAs(asFunction);
        assertThat(OpenAiResponseFactory.withEditingToolAsPatchFunction(none)).isSameAs(none);
    }

    @Test
    void aCallOfTheEditingToolThatKiroOrAnAnthropicKeyMadeIsToldAsTheCustomToolCallCodexApplies() {
        PlatformUsage usage = new PlatformUsage(10, 2, java.math.BigDecimal.ZERO, 0, 0, 0);
        PlatformToolUse patch = new PlatformToolUse("tooluse_1", "apply_patch", objectMapper.createObjectNode().put("patch", PATCH));
        PlatformToolUse other = new PlatformToolUse("tooluse_2", "shell", objectMapper.createObjectNode().put("command", "ls"));
        PlatformCompletionResult answer = new PlatformCompletionResult("I will patch it", "", List.of(patch, other), usage,
            PlatformCompletionResult.StopReason.TOOL_USE, PlatformCompletionResult.UsageSource.REPORTED, List.of());

        PlatformCompletionResult converted = OpenAiResponseFactory.withEditingCallsAsCustomCalls(answer);

        PlatformToolUse call = converted.toolUses().get(0);
        assertThat(call.type()).isEqualTo("custom_tool_call");
        assertThat(call.id()).isEqualTo("tooluse_1");
        assertThat(call.name()).isEqualTo("apply_patch");
        assertThat(call.rawInput()).isEqualTo(PATCH + "\n");
        // Another call, and everything else of the answer, is what it was.
        assertThat(converted.toolUses().get(1)).isSameAs(other);
        assertThat(converted.content()).isEqualTo("I will patch it");
        assertThat(converted.usage()).isEqualTo(usage);
        assertThat(converted.stopReason()).isEqualTo(PlatformCompletionResult.StopReason.TOOL_USE);
        assertThat(converted.usageSource()).isEqualTo(PlatformCompletionResult.UsageSource.REPORTED);
        // Rendered, it is the item Codex applies, with the patch for its input.
        JsonNode item = factory.responses("claude-sonnet-4.5", converted, null).path("output").get(1);
        assertThat(item.path("type").asString()).isEqualTo("custom_tool_call");
        assertThat(item.path("call_id").asString()).isEqualTo("tooluse_1");
        assertThat(item.path("input").asString()).isEqualTo(PATCH + "\n");
        assertThat(item.has("arguments")).isFalse();
        // An answer with no such call is the very same answer.
        PlatformCompletionResult plain = new PlatformCompletionResult("", List.of(other), usage);
        assertThat(OpenAiResponseFactory.withEditingCallsAsCustomCalls(plain)).isSameAs(plain);
    }

    private String replayedPatchArgument(String tools) {
        JsonNode request = objectMapper.readTree("{\"model\":\"claude-sonnet-4.5\"," + (tools == null ? "" : "\"tools\":" + tools + ",") + "\"input\":["
            + "{\"type\":\"custom_tool_call\",\"call_id\":\"call_1\",\"name\":\"apply_patch\",\"input\":\"*** Begin Patch\\n*** End Patch\\n\"}]}");
        JsonNode arguments = objectMapper.readTree(OpenAiResponseFactory.withCustomCallsAsFunctionCalls(request)
            .path("input").get(0).path("arguments").asString());
        assertThat(arguments.propertyNames()).hasSize(1);
        assertThat(arguments.properties().iterator().next().getValue().asString()).isEqualTo("*** Begin Patch\n*** End Patch\n");
        return arguments.propertyNames().iterator().next();
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
