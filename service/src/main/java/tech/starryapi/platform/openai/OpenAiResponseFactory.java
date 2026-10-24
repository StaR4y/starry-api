package tech.starryapi.platform.openai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
        if (!result.reasoningContent().isBlank()) {
            message.put("reasoning_content", result.reasoningContent());
        }
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
                function.put("arguments", toolInputString(toolUse));
            }
            choice.put("finish_reason", "tool_calls");
        }
        appendUsage(root, result.usage(), "prompt_tokens", "completion_tokens");
        return root;
    }

    public ObjectNode responses(String model, PlatformCompletionResult result, String previousResponseId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", generatedId("resp_"));
        root.put("object", "response");
        root.put("created_at", Instant.now().getEpochSecond());
        root.put("status", "completed");
        root.putNull("error");
        root.putNull("incomplete_details");
        root.put("model", model);
        if (previousResponseId != null) {
            root.put("previous_response_id", previousResponseId);
        }
        ArrayNode output = root.putArray("output");
        if (!result.reasoningContent().isBlank()) {
            output.add(responseReasoningItem(result.reasoningContent()));
        }
        if (result.toolUses().isEmpty()) {
            ObjectNode item = output.addObject();
            item.put("type", "message");
            item.put("id", generatedId("msg_"));
            item.put("role", "assistant");
            item.put("status", "completed");
            ArrayNode content = item.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "output_text");
            text.put("text", result.content());
        } else {
            if (!result.content().isBlank()) {
                ObjectNode item = output.addObject();
                item.put("type", "message");
                item.put("id", generatedId("msg_"));
                item.put("role", "assistant");
                item.put("status", "completed");
                ArrayNode content = item.putArray("content");
                ObjectNode text = content.addObject();
                text.put("type", "output_text");
                text.put("text", result.content());
            }
            for (PlatformToolUse toolUse : result.toolUses()) {
                output.add(responseToolCallItem(toolUse));
            }
        }
        root.put("output_text", result.content());
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
        Flux<ServerSentEvent<String>> completionEvents = !result.toolUses().isEmpty()
            ? chatToolStreamEvents(model, result)
            : Flux.just(
            ServerSentEvent.builder(chatStreamChunkBody(model, result.content()).toString()).build(),
            ServerSentEvent.builder(chatStreamStopBody(model).toString()).build(),
            ServerSentEvent.builder("[DONE]").build()
        );
        if (result.reasoningContent().isBlank()) {
            return completionEvents;
        }
        return Flux.concat(
            Flux.just(ServerSentEvent.builder(
                chatReasoningStreamChunkBody(model, result.reasoningContent()).toString()
            ).build()),
            completionEvents
        );
    }

    public Flux<ServerSentEvent<String>> responsesStreamEvents(
        String model,
        PlatformCompletionResult result,
        String previousResponseId
    ) {
        return responsesStreamEvents(responses(model, result, previousResponseId));
    }

    public Flux<ServerSentEvent<String>> responsesStreamEvents(ObjectNode response) {
        ArrayNode output = (ArrayNode) response.path("output");
        String responseId = response.path("id").asString("");
        List<ServerSentEvent<String>> events = new ArrayList<>();
        int sequence = 0;

        ObjectNode created = responseEvent("response.created", sequence++);
        ObjectNode inProgress = response.deepCopy();
        inProgress.put("status", "in_progress");
        inProgress.set("output", objectMapper.createArrayNode());
        created.set("response", inProgress);
        events.add(responseSse(created));

        for (int outputIndex = 0; outputIndex < output.size(); outputIndex++) {
            JsonNode item = output.get(outputIndex);
            events.add(responseSse(outputItemEvent(
                "response.output_item.added",
                responseId,
                outputItemAddedShape(item),
                outputIndex,
                sequence++
            )));
            if ("message".equals(item.path("type").asString(""))) {
                appendMessageStreamEvents(events, responseId, item, outputIndex, sequence);
                sequence += messageEventCount(item);
            } else if ("reasoning".equals(item.path("type").asString(""))) {
                appendReasoningStreamEvents(events, responseId, item, outputIndex, sequence);
                sequence += reasoningEventCount(item);
            } else if ("function_call".equals(item.path("type").asString(""))) {
                appendFunctionCallStreamEvents(events, responseId, item, outputIndex, sequence);
                sequence += functionCallEventCount(item);
            } else if ("custom_tool_call".equals(item.path("type").asString(""))) {
                appendCustomToolCallStreamEvents(events, responseId, item, outputIndex, sequence);
                sequence += customToolCallEventCount(item);
            }
            events.add(responseSse(outputItemEvent("response.output_item.done", responseId, item, outputIndex, sequence++)));
        }

        ObjectNode completed = responseEvent("response.completed", sequence);
        completed.set("response", response);
        events.add(responseSse(completed));
        events.add(ServerSentEvent.builder("[DONE]").build());
        return Flux.fromIterable(events);
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

    private ObjectNode chatReasoningStreamChunkBody(String model, String reasoningContent) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "chatcmpl-" + UUID.randomUUID());
        root.put("object", "chat.completion.chunk");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", model);
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("role", "assistant");
        delta.put("reasoning_content", reasoningContent);
        choice.putNull("finish_reason");
        return root;
    }

    private void appendMessageStreamEvents(
        List<ServerSentEvent<String>> events,
        String responseId,
        JsonNode item,
        int outputIndex,
        int sequence
    ) {
        JsonNode content = item.path("content");
        if (!content.isArray()) {
            return;
        }
        for (int contentIndex = 0; contentIndex < content.size(); contentIndex++) {
            JsonNode part = content.get(contentIndex);
            if (!"output_text".equals(part.path("type").asString(""))) {
                continue;
            }
            events.add(responseSse(contentPartEvent(
                "response.content_part.added",
                responseId,
                item,
                part,
                outputIndex,
                contentIndex,
                sequence++
            )));
            String text = part.path("text").asString("");
            if (!text.isEmpty()) {
                events.add(responseSse(outputTextDeltaEvent(responseId, item, outputIndex, contentIndex, text, sequence++)));
            }
            events.add(responseSse(outputTextDoneEvent(responseId, item, outputIndex, contentIndex, text, sequence++)));
            events.add(responseSse(contentPartEvent(
                "response.content_part.done",
                responseId,
                item,
                part,
                outputIndex,
                contentIndex,
                sequence++
            )));
        }
    }

    private int messageEventCount(JsonNode item) {
        JsonNode content = item.path("content");
        if (!content.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode part : content) {
            if (!"output_text".equals(part.path("type").asString(""))) {
                continue;
            }
            count += part.path("text").asString("").isEmpty() ? 3 : 4;
        }
        return count;
    }

    private void appendReasoningStreamEvents(
        List<ServerSentEvent<String>> events,
        String responseId,
        JsonNode item,
        int outputIndex,
        int sequence
    ) {
        String text = responseReasoningItemText(item);
        if (!text.isEmpty()) {
            events.add(responseSse(reasoningTextEvent(
                "response.reasoning_text.delta",
                responseId,
                item,
                outputIndex,
                "delta",
                text,
                sequence++
            )));
        }
        events.add(responseSse(reasoningTextEvent(
            "response.reasoning_text.done",
            responseId,
            item,
            outputIndex,
            "text",
            text,
            sequence
        )));
    }

    private int reasoningEventCount(JsonNode item) {
        return responseReasoningItemText(item).isEmpty() ? 1 : 2;
    }

    private void appendFunctionCallStreamEvents(
        List<ServerSentEvent<String>> events,
        String responseId,
        JsonNode item,
        int outputIndex,
        int sequence
    ) {
        String arguments = item.path("arguments").asString("");
        if (!arguments.isEmpty()) {
            events.add(responseSse(functionCallArgumentsEvent(
                "response.function_call_arguments.delta",
                responseId,
                item,
                outputIndex,
                "delta",
                arguments,
                sequence++
            )));
        }
        events.add(responseSse(functionCallArgumentsEvent(
            "response.function_call_arguments.done",
            responseId,
            item,
            outputIndex,
            "arguments",
            arguments,
            sequence
        )));
    }

    private int functionCallEventCount(JsonNode item) {
        return item.path("arguments").asString("").isEmpty() ? 1 : 2;
    }

    private ObjectNode responseEvent(String type, int sequence) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", type);
        event.put("sequence_number", sequence);
        return event;
    }

    private ServerSentEvent<String> responseSse(ObjectNode event) {
        return ServerSentEvent.<String>builder(event.toString())
            .event(event.path("type").asString(""))
            .build();
    }

    private String generatedId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private ObjectNode outputItemEvent(String type, String responseId, JsonNode item, int outputIndex, int sequence) {
        ObjectNode event = responseEvent(type, sequence);
        event.put("response_id", responseId);
        event.put("output_index", outputIndex);
        event.set("item", item.deepCopy());
        return event;
    }

    private JsonNode outputItemAddedShape(JsonNode item) {
        if (!item.isObject()) {
            return item;
        }
        ObjectNode copy = (ObjectNode) item.deepCopy();
        String type = copy.path("type").asString("");
        if ("function_call".equals(type)) {
            copy.put("arguments", "");
        } else if ("custom_tool_call".equals(type)) {
            copy.put("input", "");
        } else if ("reasoning".equals(type) && copy.path("content").isArray()) {
            for (JsonNode part : copy.path("content")) {
                if (part.isObject() && "reasoning_text".equals(part.path("type").asString(""))) {
                    ((ObjectNode) part).put("text", "");
                }
            }
        }
        return copy;
    }

    private ObjectNode contentPartEvent(
        String type,
        String responseId,
        JsonNode item,
        JsonNode part,
        int outputIndex,
        int contentIndex,
        int sequence
    ) {
        ObjectNode event = responseEvent(type, sequence);
        event.put("response_id", responseId);
        event.put("item_id", item.path("id").asString(""));
        event.put("output_index", outputIndex);
        event.put("content_index", contentIndex);
        event.set("part", part.deepCopy());
        return event;
    }

    private ObjectNode outputTextDeltaEvent(
        String responseId,
        JsonNode item,
        int outputIndex,
        int contentIndex,
        String text,
        int sequence
    ) {
        ObjectNode event = responseEvent("response.output_text.delta", sequence);
        event.put("response_id", responseId);
        event.put("item_id", item.path("id").asString(""));
        event.put("output_index", outputIndex);
        event.put("content_index", contentIndex);
        event.put("delta", text);
        return event;
    }

    private ObjectNode outputTextDoneEvent(
        String responseId,
        JsonNode item,
        int outputIndex,
        int contentIndex,
        String text,
        int sequence
    ) {
        ObjectNode event = responseEvent("response.output_text.done", sequence);
        event.put("response_id", responseId);
        event.put("item_id", item.path("id").asString(""));
        event.put("output_index", outputIndex);
        event.put("content_index", contentIndex);
        event.put("text", text);
        return event;
    }

    private ObjectNode reasoningTextEvent(
        String type,
        String responseId,
        JsonNode item,
        int outputIndex,
        String valueName,
        String text,
        int sequence
    ) {
        ObjectNode event = responseEvent(type, sequence);
        event.put("response_id", responseId);
        event.put("item_id", item.path("id").asString(""));
        event.put("output_index", outputIndex);
        event.put("content_index", 0);
        event.put(valueName, text);
        return event;
    }

    private ObjectNode functionCallArgumentsEvent(
        String type,
        String responseId,
        JsonNode item,
        int outputIndex,
        String valueName,
        String arguments,
        int sequence
    ) {
        ObjectNode event = responseEvent(type, sequence);
        event.put("response_id", responseId);
        event.put("item_id", item.path("id").asString(""));
        event.put("name", item.path("name").asString(""));
        event.put("output_index", outputIndex);
        event.put(valueName, arguments);
        return event;
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
        function.put("arguments", toolInputString(toolUse));
        choice.putNull("finish_reason");
        return root;
    }

    private ObjectNode responseToolCallItem(PlatformToolUse toolUse) {
        ObjectNode call = toolUse.rawOutputItem() != null && toolUse.rawOutputItem().isObject()
            ? (ObjectNode) toolUse.rawOutputItem().deepCopy()
            : objectMapper.createObjectNode();
        String type = toolUse.type() == null || toolUse.type().isBlank() ? "function_call" : toolUse.type();
        call.put("type", type);
        putIfBlank(call, "id", generatedId("custom_tool_call".equals(type) ? "ctc_" : "fc_"));
        putIfBlank(call, "call_id", toolUse.id());
        putIfBlank(call, "name", toolUse.name());
        if ("custom_tool_call".equals(type)) {
            putIfBlank(call, "input", toolInputString(toolUse));
        } else {
            putIfBlank(call, "arguments", toolInputString(toolUse));
            putIfBlank(call, "status", "completed");
        }
        return call;
    }

    private ObjectNode responseReasoningItem(String reasoningContent) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", generatedId("rs_"));
        item.put("status", "completed");
        item.putArray("summary");
        ObjectNode text = item.putArray("content").addObject();
        text.put("type", "reasoning_text");
        text.put("text", reasoningContent);
        return item;
    }

    private String responseReasoningItemText(JsonNode item) {
        JsonNode content = item.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            if (!"reasoning_text".equals(part.path("type").asString(""))) {
                continue;
            }
            String text = part.path("text").asString("");
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private void appendCustomToolCallStreamEvents(
        List<ServerSentEvent<String>> events,
        String responseId,
        JsonNode item,
        int outputIndex,
        int sequence
    ) {
        String input = item.path("input").asString("");
        if (!input.isEmpty()) {
            events.add(responseSse(customToolCallInputEvent(
                "response.custom_tool_call_input.delta",
                responseId,
                item,
                outputIndex,
                "delta",
                input,
                sequence++
            )));
        }
        events.add(responseSse(customToolCallInputEvent(
            "response.custom_tool_call_input.done",
            responseId,
            item,
            outputIndex,
            "input",
            input,
            sequence
        )));
    }

    private int customToolCallEventCount(JsonNode item) {
        return item.path("input").asString("").isEmpty() ? 1 : 2;
    }

    private ObjectNode customToolCallInputEvent(
        String type,
        String responseId,
        JsonNode item,
        int outputIndex,
        String valueName,
        String input,
        int sequence
    ) {
        ObjectNode event = responseEvent(type, sequence);
        event.put("response_id", responseId);
        event.put("item_id", item.path("id").asString(""));
        event.put("call_id", item.path("call_id").asString(""));
        event.put("name", item.path("name").asString(""));
        event.put("output_index", outputIndex);
        event.put(valueName, input);
        return event;
    }

    private void putIfBlank(ObjectNode node, String field, String value) {
        if (value == null) {
            return;
        }
        JsonNode current = node.path(field);
        if (current.isMissingNode() || current.isNull() || current.asString("").isBlank()) {
            node.put(field, value);
        }
    }

    private String toolInputString(PlatformToolUse toolUse) {
        boolean applyPatchCustomTool = "custom_tool_call".equals(toolUse.type())
            && "apply_patch".equals(toolUse.name());
        if (toolUse.rawInput() != null) {
            return applyPatchCustomTool ? normalizeApplyPatchInput(toolUse.rawInput()) : toolUse.rawInput();
        }
        JsonNode input = toolUse.input();
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "{}";
        }
        if (input.has("_rawArguments")) {
            String raw = input.path("_rawArguments").asString("");
            return applyPatchCustomTool ? normalizeApplyPatchInput(raw) : raw;
        }
        String raw = input.toString();
        return applyPatchCustomTool ? normalizeApplyPatchInput(raw) : raw;
    }

    private String normalizeApplyPatchInput(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        int begin = normalized.indexOf("*** Begin Patch");
        if (begin > 0) {
            normalized = normalized.substring(begin);
        }
        int end = normalized.lastIndexOf("*** End Patch");
        if (end >= 0) {
            normalized = normalized.substring(0, end + "*** End Patch".length());
            return normalized + "\n";
        }
        return normalized;
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
