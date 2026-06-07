package xyz.star4y.api.platform.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.core.PlatformToolUse;
import xyz.star4y.api.platform.core.PlatformUsage;

@Component
public class OpenAiResponseFactory {

    private final ObjectMapper objectMapper;

    public OpenAiResponseFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode chatCompletion(String model, PlatformCompletionResult result) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "chatcmpl-" + UUID.randomUUID());
        root.put("object", "chat.completion");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", model);
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (result.toolUses().isEmpty()) {
            message.put("content", result.content());
            choice.put("finish_reason", "stop");
        } else {
            message.put("content", result.content().isBlank() ? null : result.content());
            ArrayNode toolCalls = message.putArray("tool_calls");
            for (PlatformToolUse toolUse : result.toolUses()) {
                ObjectNode call = toolCalls.addObject();
                call.put("id", toolUse.id());
                call.put("type", "function");
                ObjectNode function = call.putObject("function");
                function.put("name", toolUse.name());
                function.put("arguments", toolUse.input().toString());
            }
            choice.put("finish_reason", "tool_calls");
        }
        appendUsage(root, result.usage(), "prompt_tokens", "completion_tokens");
        return root;
    }

    public ObjectNode responses(String model, PlatformCompletionResult result, String previousResponseId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "resp-" + UUID.randomUUID());
        root.put("object", "response");
        root.put("created_at", Instant.now().getEpochSecond());
        root.put("model", model);
        if (previousResponseId != null) {
            root.put("previous_response_id", previousResponseId);
        }
        ArrayNode output = root.putArray("output");
        if (result.toolUses().isEmpty()) {
            ObjectNode item = output.addObject();
            item.put("type", "message");
            item.put("id", "msg-" + UUID.randomUUID());
            item.put("role", "assistant");
            ArrayNode content = item.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "output_text");
            text.put("text", result.content());
        } else {
            if (!result.content().isBlank()) {
                ObjectNode item = output.addObject();
                item.put("type", "message");
                item.put("id", "msg-" + UUID.randomUUID());
                item.put("role", "assistant");
                ArrayNode content = item.putArray("content");
                ObjectNode text = content.addObject();
                text.put("type", "output_text");
                text.put("text", result.content());
            }
            for (PlatformToolUse toolUse : result.toolUses()) {
                ObjectNode call = output.addObject();
                call.put("type", "function_call");
                call.put("id", "fc-" + UUID.randomUUID());
                call.put("call_id", toolUse.id());
                call.put("name", toolUse.name());
                call.put("arguments", toolUse.input().toString());
                call.put("status", "completed");
            }
        }
        appendUsage(root, result.usage(), "input_tokens", "output_tokens");
        return root;
    }

    public String chatStreamChunk(String model, String content) {
        return "data: " + chatStreamChunkBody(model, content) + "\n\n";
    }

    public String chatStreamStop(String model) {
        return "data: " + chatStreamStopBody(model) + "\n\ndata: [DONE]\n\n";
    }

    public Flux<ServerSentEvent<String>> chatStreamEvents(String model, PlatformCompletionResult result) {
        if (!result.toolUses().isEmpty()) {
            return chatToolStreamEvents(model, result);
        }
        return Flux.just(
            ServerSentEvent.builder(chatStreamChunkBody(model, result.content()).toString()).build(),
            ServerSentEvent.builder(chatStreamStopBody(model).toString()).build(),
            ServerSentEvent.builder("[DONE]").build()
        );
    }

    private ObjectNode chatStreamChunkBody(String model, String content) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "chatcmpl-" + UUID.randomUUID());
        root.put("object", "chat.completion.chunk");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", model);
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("role", "assistant");
        delta.put("content", content);
        choice.putNull("finish_reason");
        return root;
    }

    private ObjectNode chatStreamStopBody(String model) {
        return chatStreamStopBody(model, "stop");
    }

    private ObjectNode chatStreamStopBody(String model, String finishReason) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "chatcmpl-" + UUID.randomUUID());
        root.put("object", "chat.completion.chunk");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", model);
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", finishReason);
        return root;
    }

    private Flux<ServerSentEvent<String>> chatToolStreamEvents(String model, PlatformCompletionResult result) {
        List<ServerSentEvent<String>> events = new ArrayList<>();
        String content = result.content();
        if (!content.isBlank()) {
            events.add(ServerSentEvent.builder(chatStreamChunkBody(model, content).toString()).build());
        }
        for (int i = 0; i < result.toolUses().size(); i++) {
            events.add(ServerSentEvent.builder(chatToolCallChunkBody(model, i, result.toolUses().get(i)).toString()).build());
        }
        events.add(ServerSentEvent.builder(chatStreamStopBody(model, "tool_calls").toString()).build());
        events.add(ServerSentEvent.builder("[DONE]").build());
        return Flux.fromIterable(events);
    }

    private ObjectNode chatToolCallChunkBody(String model, int index, PlatformToolUse toolUse) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "chatcmpl-" + UUID.randomUUID());
        root.put("object", "chat.completion.chunk");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", model);
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        ArrayNode toolCalls = delta.putArray("tool_calls");
        ObjectNode call = toolCalls.addObject();
        call.put("index", index);
        call.put("id", toolUse.id());
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", toolUse.name());
        function.put("arguments", toolUse.input().toString());
        choice.putNull("finish_reason");
        return root;
    }

    private void appendUsage(ObjectNode root, PlatformUsage usage, String inputName, String outputName) {
        ObjectNode usageNode = root.putObject("usage");
        usageNode.put(inputName, usage.inputTokens());
        usageNode.put(outputName, usage.outputTokens());
        usageNode.put("total_tokens", usage.totalTokens());
        ObjectNode inputDetails = usageNode.putObject(inputName + "_details");
        inputDetails.put("cached_tokens", usage.cacheReadTokens());
        ObjectNode outputDetails = usageNode.putObject(outputName + "_details");
        outputDetails.put("reasoning_tokens", usage.reasoningTokens());
    }
}
