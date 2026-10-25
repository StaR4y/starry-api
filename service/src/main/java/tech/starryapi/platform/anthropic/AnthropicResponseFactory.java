package tech.starryapi.platform.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AnthropicResponseFactory {

    private final ObjectMapper objectMapper;

    public AnthropicResponseFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode message(String model, PlatformCompletionResult result) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "msg_" + UUID.randomUUID());
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", model);
        ArrayNode content = root.putArray("content");
        appendContentBlocks(content, result);
        root.put("stop_reason", stopReason(result));
        root.putNull("stop_sequence");
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", result.usage().inputTokens());
        usage.put("output_tokens", result.usage().outputTokens());
        return root;
    }

    public Flux<ServerSentEvent<String>> messageStream(String model, PlatformCompletionResult result) {
        String messageId = "msg_" + UUID.randomUUID();
        ObjectNode start = objectMapper.createObjectNode();
        start.put("type", "message_start");
        ObjectNode message = start.putObject("message");
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.putArray("content");
        message.putNull("stop_reason");
        message.putNull("stop_sequence");
        ObjectNode startUsage = message.putObject("usage");
        startUsage.put("input_tokens", result.usage().inputTokens());
        startUsage.put("output_tokens", 0);

        List<ServerSentEvent<String>> events = new ArrayList<>();
        events.add(sse("message_start", start));
        int index = 0;
        if (!result.content().isBlank() || result.toolUses().isEmpty()) {
            events.add(textBlockStart(index));
            events.add(textBlockDelta(index, result.content()));
            events.add(blockStop(index));
            index++;
        }
        for (PlatformToolUse toolUse : result.toolUses()) {
            events.add(toolBlockStart(index, toolUse));
            events.add(toolInputDelta(index, toolUse));
            events.add(blockStop(index));
            index++;
        }

        ObjectNode messageDelta = objectMapper.createObjectNode();
        messageDelta.put("type", "message_delta");
        ObjectNode stopDelta = messageDelta.putObject("delta");
        stopDelta.put("stop_reason", stopReason(result));
        stopDelta.putNull("stop_sequence");
        ObjectNode deltaUsage = messageDelta.putObject("usage");
        deltaUsage.put("output_tokens", result.usage().outputTokens());

        ObjectNode messageStop = objectMapper.createObjectNode();
        messageStop.put("type", "message_stop");

        events.add(sse("message_delta", messageDelta));
        events.add(sse("message_stop", messageStop));
        return Flux.fromIterable(events);
    }

    /** Only a non-null MAX_TOKENS or CONTENT_FILTER changes the answer; every other result keeps the derived value. */
    private String stopReason(PlatformCompletionResult result) {
        if (result.stopReason() == PlatformCompletionResult.StopReason.MAX_TOKENS) {
            return "max_tokens";
        }
        if (result.stopReason() == PlatformCompletionResult.StopReason.CONTENT_FILTER) {
            return "refusal";
        }
        return result.toolUses().isEmpty() ? "end_turn" : "tool_use";
    }

    private void appendContentBlocks(ArrayNode content, PlatformCompletionResult result) {
        if (!result.content().isBlank() || result.toolUses().isEmpty()) {
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", result.content());
        }
        for (PlatformToolUse toolUse : result.toolUses()) {
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", toolUse.id());
            block.put("name", toolUse.name());
            block.set("input", toolUse.input());
        }
    }

    private ServerSentEvent<String> textBlockStart(int index) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        ObjectNode block = event.putObject("content_block");
        block.put("type", "text");
        block.put("text", "");
        return sse("content_block_start", event);
    }

    private ServerSentEvent<String> textBlockDelta(int index, String content) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        ObjectNode delta = event.putObject("delta");
        delta.put("type", "text_delta");
        delta.put("text", content);
        return sse("content_block_delta", event);
    }

    private ServerSentEvent<String> toolBlockStart(int index, PlatformToolUse toolUse) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_start");
        event.put("index", index);
        ObjectNode block = event.putObject("content_block");
        block.put("type", "tool_use");
        block.put("id", toolUse.id());
        block.put("name", toolUse.name());
        block.set("input", objectMapper.createObjectNode());
        return sse("content_block_start", event);
    }

    private ServerSentEvent<String> toolInputDelta(int index, PlatformToolUse toolUse) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_delta");
        event.put("index", index);
        ObjectNode delta = event.putObject("delta");
        delta.put("type", "input_json_delta");
        delta.put("partial_json", toolUse.input().toString());
        return sse("content_block_delta", event);
    }

    private ServerSentEvent<String> blockStop(int index) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "content_block_stop");
        event.put("index", index);
        return sse("content_block_stop", event);
    }

    private ServerSentEvent<String> sse(String event, ObjectNode data) {
        return ServerSentEvent.builder(data.toString()).event(event).build();
    }
}
