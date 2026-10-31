package tech.starryapi.platform.openai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tech.starryapi.common.JsonNodes;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
public class OpenAiResponseFactory {

    /** The argument a replayed call of a custom tool is written with when no function of the client says another. */
    private static final String CUSTOM_CALL_ARGUMENT = "input";

    /** What the static helpers below write of the editing tool; it keeps nothing, and its mapper only makes nodes. */
    private static final ApplyPatchEmulation EDITING_TOOL = new ApplyPatchEmulation(new ObjectMapper());

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
            choice.put("finish_reason", chatFinishReason(result, "stop"));
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
                putThoughtSignature(call, toolUse);
            }
            choice.put("finish_reason", chatFinishReason(result, "tool_calls"));
        }
        appendUsage(root, result.usage(), "prompt_tokens", "completion_tokens");
        return root;
    }

    public ObjectNode responses(String model, PlatformCompletionResult result, String previousResponseId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", generatedId("resp_"));
        root.put("object", "response");
        root.put("created_at", Instant.now().getEpochSecond());
        String incompleteReason = responsesIncompleteReason(result);
        root.put("status", incompleteReason == null ? "completed" : "incomplete");
        root.putNull("error");
        if (incompleteReason == null) {
            root.putNull("incomplete_details");
        } else {
            root.putObject("incomplete_details").put("reason", incompleteReason);
        }
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
            ServerSentEvent.builder(chatStreamStopBody(model, chatFinishReason(result, "stop")).toString()).build(),
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
        boolean incomplete = "incomplete".equals(response.path("status").asString(""));
        if (incomplete) {
            inProgress.putNull("incomplete_details");
        }
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

        ObjectNode completed = responseEvent(incomplete ? "response.incomplete" : "response.completed", sequence);
        completed.set("response", response);
        events.add(responseSse(completed));
        events.add(ServerSentEvent.builder("[DONE]").build());
        return Flux.fromIterable(events);
    }

    /** Only a non-null MAX_TOKENS or CONTENT_FILTER changes the answer; every other result keeps {@code derived}. */
    private String chatFinishReason(PlatformCompletionResult result, String derived) {
        if (result.stopReason() == PlatformCompletionResult.StopReason.MAX_TOKENS) {
            return "length";
        }
        if (result.stopReason() == PlatformCompletionResult.StopReason.CONTENT_FILTER) {
            return "content_filter";
        }
        return derived;
    }

    /** The {@code incomplete_details.reason} for a truncated or filtered result, or null when the response completes. */
    private String responsesIncompleteReason(PlatformCompletionResult result) {
        if (result.stopReason() == PlatformCompletionResult.StopReason.MAX_TOKENS) {
            return "max_output_tokens";
        }
        if (result.stopReason() == PlatformCompletionResult.StopReason.CONTENT_FILTER) {
            return "content_filter";
        }
        return null;
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
        events.add(ServerSentEvent.builder(chatStreamStopBody(model, chatFinishReason(result, "tool_calls")).toString()).build());
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
        putThoughtSignature(call, toolUse);
        choice.putNull("finish_reason");
        return root;
    }

    /**
     * The thought signature of a call that a Gemini model signed, in the form Google's OpenAI-compatible API writes it
     * ({@code extra_content.google.thought_signature}), so that a Chat client which gives it back with the call in its
     * history gives the model what it needs (design 7.10). Only a cross-protocol result has one; a call without it is
     * written as it always was.
     */
    private void putThoughtSignature(ObjectNode call, PlatformToolUse toolUse) {
        if (toolUse.thoughtSignature() != null) {
            call.putObject("extra_content").putObject("google").put("thought_signature", toolUse.thoughtSignature());
        }
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

    /**
     * Whether an item of a request's input is a reasoning item as {@link #responseReasoningItem} writes it for an answer
     * whose source gave its reasoning as text and no more (a relay's {@code reasoning_content}): no summary, no encrypted
     * content, and the text as {@code reasoning_text}. It is the gateway's own rendering, which no OpenAI model gave and
     * none can read back: OpenAI's reasoning items have an encrypted content or a summary, and a request that carries one
     * of these to an OpenAI account with {@code store: false} is a request that account may refuse.
     */
    public static boolean isRenderedReasoning(JsonNode item) {
        if (!item.isObject() || !"reasoning".equals(item.path("type").asString(""))) {
            return false;
        }
        JsonNode encrypted = item.path("encrypted_content");
        JsonNode summary = item.path("summary");
        JsonNode content = item.path("content");
        if (!(encrypted.isMissingNode() || encrypted.isNull() || encrypted.asString("").isBlank())
            || !(summary.isMissingNode() || summary.isNull() || (summary.isArray() && summary.isEmpty()))
            || !content.isArray() || content.isEmpty()) {
            return false;
        }
        for (JsonNode part : content) {
            if (!"reasoning_text".equals(part.path("type").asString(""))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The request without the reasoning items {@link #isRenderedReasoning} names, or the request itself when it holds none.
     * The input is rebuilt; everything else is shared with the request, which is not changed.
     */
    public static JsonNode withoutRenderedReasoning(JsonNode request) {
        JsonNode input = request.path("input");
        if (!request.isObject() || !input.isArray()) {
            return request;
        }
        ArrayNode kept = null;
        for (int i = 0; i < input.size(); i++) {
            if (isRenderedReasoning(input.get(i))) {
                if (kept == null) {
                    kept = JsonNodeFactory.instance.arrayNode();
                    for (int before = 0; before < i; before++) {
                        kept.add(input.get(before));
                    }
                }
            } else if (kept != null) {
                kept.add(input.get(i));
            }
        }
        if (kept == null) {
            return request;
        }
        ObjectNode copy = JsonNodes.shallowCopy((ObjectNode) request);
        copy.set("input", kept);
        return copy;
    }

    /**
     * The request with the calls of custom tools in its input written as the calls of functions, or the request itself when it
     * holds none: {@code custom_tool_call} is a {@code function_call} and {@code custom_tool_call_output} a {@code
     * function_call_output}, which is all the conversion of the Kiro and Anthropic routes ({@code
     * OpenAiRequestAdapter#responsesToChat}) knows how to replay. The text of a call is its one string argument, in the
     * shape those routes declare the tool, which this reads from the tools of the request it is given: the string property
     * that the client's function declares ({@code input} for the JSON tool Codex has), and {@code input} for a tool the client
     * declared as a custom tool, which those routes are given with no properties. For a vendor that has relays the facade
     * declares such a custom tool to them as the gateway's {@code patch} function first ({@link
     * #withEditingToolAsPatchFunction}), and the argument read here is then {@code patch}. The input is rebuilt; everything
     * else is shared with the request, which is not changed.
     *
     * <p>A relay serves the editing tool as a custom tool call whatever the client declared it as (it is what Codex expects),
     * and the chain keeps that call. Replayed to a source of those routes it was a blank user message, and the patch with
     * its result were gone from what the model was asked (the model then answered without knowing the patch was applied).
     */
    public static JsonNode withCustomCallsAsFunctionCalls(JsonNode request) {
        JsonNode input = request.path("input");
        if (!request.isObject() || !input.isArray()) {
            return request;
        }
        ArrayNode converted = null;
        for (int i = 0; i < input.size(); i++) {
            JsonNode item = input.get(i);
            String type = item.path("type").asString("");
            boolean call = "custom_tool_call".equals(type);
            boolean output = "custom_tool_call_output".equals(type);
            if ((call || output) && item.isObject()) {
                if (converted == null) {
                    converted = JsonNodeFactory.instance.arrayNode();
                    for (int before = 0; before < i; before++) {
                        converted.add(input.get(before));
                    }
                }
                converted.add(call ? customCallAsFunctionCall(item, request.path("tools")) : customOutputAsFunctionOutput(item));
            } else if (converted != null) {
                converted.add(item);
            }
        }
        if (converted == null) {
            return request;
        }
        ObjectNode copy = JsonNodes.shallowCopy((ObjectNode) request);
        copy.set("input", converted);
        return copy;
    }

    /** Whether the client declared the editing tool, {@code apply_patch}, as a custom tool (as Codex does), and not as a function. */
    public static boolean declaresEditingToolAsCustom(JsonNode request) {
        JsonNode tools = request.path("tools");
        if (!tools.isArray()) {
            return false;
        }
        for (JsonNode tool : tools) {
            if (isCustomEditingTool(tool)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCustomEditingTool(JsonNode tool) {
        return "custom".equals(tool.path("type").asString("")) && ApplyPatchEmulation.TOOL_NAME.equals(tool.path("name").asString(""));
    }

    /**
     * The request with the editing tool, when the client declared it as a custom tool, declared as the gateway's function for
     * it (the function the relays are given, {@link ApplyPatchEmulation#chatFunctionTool}: the {@code patch} argument and the
     * gateway's own description of it; the OpenAI route is given the same argument in the Responses shape with the client's
     * description, {@link ApplyPatchEmulation#functionTool}), or the request itself when it holds none. A {@code tool_choice}
     * that forces the tool follows it. This is for the Kiro and Anthropic routes of a vendor that has relays: they are told
     * the same function a relay is, so that the call of a history that a relay made, replayed with the {@code patch} argument
     * ({@link #withCustomCallsAsFunctionCalls} reads the argument from this declaration), is the call of the function they
     * were declared, and so that their own calls can be told as the custom tool calls Codex applies ({@link
     * #withEditingCallsAsCustomCalls}). Everything else is shared with the request, which is not changed.
     */
    public static JsonNode withEditingToolAsPatchFunction(JsonNode request) {
        if (!request.isObject() || !declaresEditingToolAsCustom(request)) {
            return request;
        }
        ArrayNode tools = JsonNodeFactory.instance.arrayNode();
        for (JsonNode tool : request.path("tools")) {
            tools.add(isCustomEditingTool(tool) ? EDITING_TOOL.chatFunctionTool(tool) : tool);
        }
        ObjectNode copy = JsonNodes.shallowCopy((ObjectNode) request);
        copy.set("tools", tools);
        if (request.has("tool_choice")) {
            copy.set("tool_choice", EDITING_TOOL.chatToolChoice(request.path("tool_choice")));
        }
        return copy;
    }

    /**
     * The answer of an account that speaks no custom tools (Kiro, an Anthropic key) with its calls of the editing tool told as
     * the custom tool calls the client declared it as, the patch for their input; or the answer itself when it has none. It is
     * what the relays and the OpenAI route answer with already ({@link ApplyPatchEmulation#editingToolCall}); Codex 0.142.5
     * applies a custom tool call and reports a {@code function_call} for the editing tool as an incompatible payload.
     */
    public static PlatformCompletionResult withEditingCallsAsCustomCalls(PlatformCompletionResult result) {
        if (result.toolUses().stream().noneMatch(call -> ApplyPatchEmulation.TOOL_NAME.equals(call.name()))) {
            return result;
        }
        List<PlatformToolUse> calls = result.toolUses().stream()
            .map(call -> ApplyPatchEmulation.TOOL_NAME.equals(call.name()) ? EDITING_TOOL.editingToolCall(call.id(), call) : call)
            .toList();
        return new PlatformCompletionResult(result.content(), result.reasoningContent(), calls, result.usage(), result.stopReason(),
            result.usageSource(), result.degraded());
    }

    private static ObjectNode customCallAsFunctionCall(JsonNode item, JsonNode tools) {
        ObjectNode call = JsonNodes.shallowCopy((ObjectNode) item);
        call.put("type", "function_call");
        call.remove("input");
        JsonNode input = item.path("input");
        String text = input.isString() ? input.asString("") : input.isMissingNode() || input.isNull() ? "" : input.toString();
        String argument = ApplyPatchEmulation.TOOL_NAME.equals(item.path("name").asString(""))
            ? editingToolArgument(tools)
            : CUSTOM_CALL_ARGUMENT;
        call.put("arguments", JsonNodeFactory.instance.objectNode().put(argument, text).toString());
        return call;
    }

    /**
     * The argument of the editing tool as the Kiro and Anthropic routes declare it: the string property of the function it is
     * declared as (the one that is required, else the first), or {@code input} when the tool is declared as a custom tool,
     * which those routes give an empty schema, or not at all.
     */
    private static String editingToolArgument(JsonNode tools) {
        if (!tools.isArray()) {
            return CUSTOM_CALL_ARGUMENT;
        }
        for (JsonNode tool : tools) {
            JsonNode source = tool.path("function").isObject() ? tool.path("function") : tool;
            if (!ApplyPatchEmulation.TOOL_NAME.equals(source.path("name").asString("")) || "custom".equals(tool.path("type").asString(""))) {
                continue;
            }
            JsonNode parameters = source.path("parameters");
            String first = null;
            for (Map.Entry<String, JsonNode> property : parameters.path("properties").properties()) {
                if (!"string".equals(property.getValue().path("type").asString(""))) {
                    continue;
                }
                if (containsText(parameters.path("required"), property.getKey())) {
                    return property.getKey();
                }
                first = first != null ? first : property.getKey();
            }
            return first != null ? first : CUSTOM_CALL_ARGUMENT;
        }
        return CUSTOM_CALL_ARGUMENT;
    }

    private static boolean containsText(JsonNode array, String text) {
        for (JsonNode element : array) {
            if (text.equals(element.asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode customOutputAsFunctionOutput(JsonNode item) {
        ObjectNode output = JsonNodes.shallowCopy((ObjectNode) item);
        output.put("type", "function_call_output");
        return output;
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
