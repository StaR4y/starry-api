package tech.starryapi.platform.openai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountTokenRefreshService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.common.JsonNodes;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.core.ToolSchemaSanitizer;
import tech.starryapi.platform.deepseek.DeepSeekModelCatalog;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class OpenAiUpstreamClient {

    private static final String CODEX_USER_AGENT = "codex_cli_rs/0.139.0";
    private static final String CODEX_ORIGINATOR = "codex_cli_rs";
    private static final String APPLY_PATCH_TOOL_NAME = "apply_patch";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final AccountTokenRefreshService tokenRefreshService;

    public OpenAiUpstreamClient(
        ObjectMapper objectMapper,
        WebClient upstreamWebClient,
        AccountTokenRefreshService tokenRefreshService
    ) {
        this.objectMapper = objectMapper;
        this.webClient = upstreamWebClient;
        this.tokenRefreshService = tokenRefreshService;
    }

    public Mono<PlatformCompletionResult> complete(ProxyAccountEntity account, JsonNode request, String model) {
        if (AccountPlatforms.isCodex(account)) {
            return completeCodex(account, request, openAiModel(model, account));
        }
        String upstreamModel = openAiModel(model, account);
        return completeChatCompletions(account, request, upstreamModel)
            .onErrorResume(error -> retryWithUnprefixedOpenAiModel(error, account, upstreamModel)
                .flatMap(retryModel -> completeChatCompletions(account, request, retryModel)));
    }

    public Mono<PlatformCompletionResult> completeResponses(ProxyAccountEntity account, JsonNode request, String model) {
        if (AccountPlatforms.isCodex(account)) {
            return completeCodex(account, request, openAiModel(model, account));
        }
        String upstreamModel = openAiModel(model, account);
        if (AccountPlatforms.isDeepSeek(account) && !DeepSeekModelCatalog.supportsResponses(upstreamModel)) {
            return Mono.error(new UpstreamException(
                400,
                "DeepSeek Responses API currently supports only " + DeepSeekModelCatalog.FLASH_MODEL_ID,
                false
            ));
        }
        return completeResponsesApi(account, request, upstreamModel)
            .onErrorResume(error -> retryWithUnprefixedOpenAiModel(error, account, upstreamModel)
                .flatMap(retryModel -> completeResponsesApi(account, request, retryModel)));
    }

    private Mono<PlatformCompletionResult> completeChatCompletions(
        ProxyAccountEntity account,
        JsonNode request,
        String model
    ) {
        ObjectNode body = copyForUpstream(request);
        body.put("model", model);
        normalizeChatTokenLimit(body, model);
        forceNonStreaming(body);

        String endpoint = OpenAiEndpoints.openAiApi(account.getBaseUrl(), "/chat/completions");
        return webClient.post()
            .uri(endpoint)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyOpenAiHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, this::parseChatCompletions, endpoint));
    }

    private Mono<PlatformCompletionResult> completeCodex(
        ProxyAccountEntity account,
        JsonNode request,
        String model
    ) {
        ObjectNode body = codexRequestBody(request, model);

        return completeCodexOnce(account, body)
            .onErrorResume(error -> {
                if (isUnauthorized(error) && tokenRefreshService.canRefresh(account)) {
                    return tokenRefreshService.refresh(account)
                        .flatMap(refreshedAccount -> completeCodexOnce(refreshedAccount, body));
                }
                return Mono.error(error);
            });
    }

    private Mono<PlatformCompletionResult> completeCodexOnce(ProxyAccountEntity account, ObjectNode body) {
        String endpoint = OpenAiEndpoints.codexApi(account.getBaseUrl(), "/responses");
        return webClient.post()
            .uri(endpoint)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyCodexHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, this::parseResponses, endpoint));
    }

    private ObjectNode codexRequestBody(JsonNode request, String model) {
        boolean responsesShape = request.has("input");
        ObjectNode body = responsesShape && request.isObject()
            ? copyForUpstream(request)
            : objectMapper.createObjectNode();
        body.put("model", codexCompatibleModel(model));
        body.put("store", false);
        body.put("stream", true);
        if (responsesShape) {
            body.set("input", codexResponsesInput(request.path("input")));
        } else {
            String instructions = codexInstructions(request);
            if (instructions != null) {
                body.put("instructions", instructions);
            }
            body.set("input", codexChatInput(request));
            if (request.hasNonNull("service_tier")) {
                body.set("service_tier", request.get("service_tier"));
            }
        }
        stripCodexUnsupportedFields(body);
        appendCodexTools(body, request);
        return body;
    }

    private Mono<PlatformCompletionResult> completeResponsesApi(
        ProxyAccountEntity account,
        JsonNode request,
        String model
    ) {
        ObjectNode body = copyForUpstream(request);
        body.put("model", model);
        normalizeResponsesApplyPatchTool(body);
        forceNonStreaming(body);

        String endpoint = OpenAiEndpoints.openAiApi(account.getBaseUrl(), "/responses");
        return webClient.post()
            .uri(endpoint)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyOpenAiResponsesHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, this::parseResponses, endpoint));
    }

    private void applyOpenAiHeaders(HttpHeaders headers, ProxyAccountEntity account) {
        headers.setBearerAuth(account.getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    private void applyCodexHeaders(HttpHeaders headers, ProxyAccountEntity account) {
        applyOpenAiHeaders(headers, account);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.USER_AGENT, CODEX_USER_AGENT);
        headers.set(HttpHeaders.CONNECTION, "Keep-Alive");
        headers.set("Originator", CODEX_ORIGINATOR);
        headers.set("OpenAI-Beta", "responses=experimental");
        if (account.getUpstreamAccountId() != null && !account.getUpstreamAccountId().isBlank()) {
            headers.set("Chatgpt-Account-Id", account.getUpstreamAccountId());
        }
    }

    private Mono<PlatformCompletionResult> handleResponse(
        ClientResponse response,
        ResponseParser parser,
        String endpoint
    ) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .flatMap(rawBody -> {
                if (status.isError()) {
                    return Mono.error(new UpstreamException(
                        status.value(),
                        "OpenAI upstream error " + status.value() + ": " + truncate(rawBody),
                        isRecoverableStatus(status.value()), response.headers().asHttpHeaders().getFirst("Retry-After")
                    ));
                }
                try {
                    return Mono.just(parser.parse(rawBody));
                } catch (Exception error) {
                    return Mono.error(new UpstreamException(502, invalidResponseMessage(endpoint, rawBody), false));
                }
            });
    }

    private PlatformCompletionResult parseChatCompletions(String rawBody) throws Exception {
        JsonNode body = readJson(rawBody);
        JsonNode message = body.path("choices").path(0).path("message");
        return new PlatformCompletionResult(
            message.path("content").asString(""),
            message.path("reasoning_content").asString(""),
            chatToolUses(message.path("tool_calls")),
            usage(body.path("usage"), "prompt_tokens", "completion_tokens")
        );
    }

    private List<PlatformToolUse> chatToolUses(JsonNode toolCalls) {
        if (!toolCalls.isArray()) {
            return List.of();
        }
        List<PlatformToolUse> result = new ArrayList<>();
        int index = 0;
        for (JsonNode toolCall : toolCalls) {
            JsonNode function = toolCall.path("function");
            String name = function.path("name").asString("");
            if (name.isBlank()) {
                continue;
            }
            String rawInput = jsonText(function.path("arguments"), "{}");
            String id = firstNonBlank(toolCall.path("id").asString(null), "call_" + index++);
            result.add(new PlatformToolUse(
                id,
                name,
                parseToolArguments(rawInput),
                "function_call",
                rawInput,
                toolCall.deepCopy()
            ));
        }
        return List.copyOf(result);
    }

    private PlatformCompletionResult parseResponses(String rawBody) throws Exception {
        String trimmed = rawBody == null ? "" : rawBody.trim();
        if (isSseResponse(trimmed)) {
            return parseResponsesStream(rawBody);
        }
        return parseResponsesJson(readJson(rawBody));
    }

    private boolean isSseResponse(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        if (body.startsWith("data:") || body.startsWith("event:") || body.startsWith(":")) {
            return true;
        }
        return body.contains("\ndata:") || body.contains("\nevent:") || body.contains("\rdata:")
            || body.contains("\revent:");
    }

    private JsonNode readJson(String rawBody) throws Exception {
        return rawBody == null || rawBody.isBlank()
            ? objectMapper.createObjectNode()
            : objectMapper.readTree(rawBody);
    }

    private PlatformCompletionResult parseResponsesJson(JsonNode body) {
        String content = body.path("output_text").asString("");
        if (content.isBlank()) {
            content = responseOutputText(body.path("output"));
        }
        return new PlatformCompletionResult(
            content,
            firstNonBlank(
                body.path("reasoning_text").asString(""),
                responseReasoningText(body.path("output"))
            ),
            responseToolUses(body.path("output")),
            usage(body.path("usage"), "input_tokens", "output_tokens")
        );
    }

    private PlatformCompletionResult parseResponsesStream(String rawBody) throws Exception {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        String fallbackContent = "";
        String fallbackReasoningContent = "";
        List<PlatformToolUse> toolUses = new ArrayList<>();
        Map<String, PendingResponseToolUse> pendingToolUses = new LinkedHashMap<>();
        JsonNode usage = null;
        for (String payload : sseDataPayloads(rawBody)) {
            String data = payload.trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            JsonNode event = objectMapper.readTree(data);
            String type = event.path("type").asString("");
            if ("response.output_text.delta".equals(type)) {
                content.append(event.path("delta").asString(""));
            } else if ("response.output_text.done".equals(type)) {
                fallbackContent = firstNonBlank(fallbackContent, event.path("text").asString(""));
            } else if ("response.reasoning_text.delta".equals(type)) {
                reasoningContent.append(event.path("delta").asString(""));
            } else if ("response.reasoning_text.done".equals(type)) {
                fallbackReasoningContent = firstNonBlank(
                    fallbackReasoningContent,
                    event.path("text").asString(""),
                    event.path("reasoning_text").asString("")
                );
            } else if ("response.content_part.done".equals(type)) {
                JsonNode part = event.path("part");
                fallbackContent = firstNonBlank(fallbackContent, contentPartText(part));
                fallbackReasoningContent = firstNonBlank(
                    fallbackReasoningContent,
                    reasoningContentPartText(part)
                );
            } else if ("response.output_item.added".equals(type)) {
                rememberResponseToolUse(pendingToolUses, event.path("item"));
            } else if ("response.function_call_arguments.delta".equals(type)
                || "response.custom_tool_call_input.delta".equals(type)) {
                appendResponseToolUseArguments(pendingToolUses, event);
            } else if ("response.function_call_arguments.done".equals(type)
                || "response.custom_tool_call_input.done".equals(type)) {
                completeResponseToolUseArguments(pendingToolUses, event);
            } else if ("response.output_item.done".equals(type)) {
                JsonNode item = event.path("item");
                fallbackContent = firstNonBlank(fallbackContent, responseOutputItemText(item));
                fallbackReasoningContent = firstNonBlank(
                    fallbackReasoningContent,
                    responseReasoningItemText(item)
                );
                rememberResponseToolUse(pendingToolUses, item);
            } else if ("response.completed".equals(type)) {
                JsonNode response = event.path("response");
                fallbackContent = firstNonBlank(
                    fallbackContent,
                    response.path("output_text").asString(""),
                    responseOutputText(response.path("output"))
                );
                fallbackReasoningContent = firstNonBlank(
                    fallbackReasoningContent,
                    response.path("reasoning_text").asString(""),
                    responseReasoningText(response.path("output"))
                );
                rememberResponseToolUses(pendingToolUses, response.path("output"));
                usage = presentNode(response.path("usage"), usage);
            }
            usage = presentNode(event.path("usage"), usage);
        }
        appendPendingResponseToolUses(toolUses, pendingToolUses);
        return new PlatformCompletionResult(
            content.length() > 0 ? content.toString() : fallbackContent,
            reasoningContent.length() > 0 ? reasoningContent.toString() : fallbackReasoningContent,
            toolUses,
            usage(usage, "input_tokens", "output_tokens")
        );
    }

    private void appendCodexTools(ObjectNode body, JsonNode request) {
        JsonNode tools = request.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            body.set("tools", codexTools(tools));
        }
        if (request.has("tool_choice")) {
            body.set("tool_choice", codexToolChoice(request.path("tool_choice")));
        }
        if (request.has("parallel_tool_calls")) {
            body.set("parallel_tool_calls", request.path("parallel_tool_calls"));
        }
    }

    private void stripCodexUnsupportedFields(ObjectNode body) {
        body.remove(List.of(
            "max_output_tokens",
            "max_completion_tokens",
            "temperature",
            "top_p",
            "frequency_penalty",
            "presence_penalty",
            "user",
            "metadata",
            "prompt_cache_retention",
            "safety_identifier",
            "stream_options"
        ));
    }

    private void applyOpenAiResponsesHeaders(HttpHeaders headers, ProxyAccountEntity account) {
        applyOpenAiHeaders(headers, account);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
    }

    private ArrayNode codexTools(JsonNode tools) {
        ArrayNode converted = objectMapper.createArrayNode();
        for (JsonNode tool : tools) {
            if (isApplyPatchTool(tool)) {
                converted.add(applyPatchFunctionTool(tool));
                continue;
            }
            if (!"function".equals(tool.path("type").asString(""))) {
                converted.add(tool);
                continue;
            }
            JsonNode function = tool.path("function");
            JsonNode source = function.isObject() ? function : tool;
            if (isApplyPatchTool(source)) {
                converted.add(applyPatchFunctionTool(source));
                continue;
            }
            ObjectNode codexTool = converted.addObject();
            codexTool.put("type", "function");
            codexTool.put("name", source.path("name").asString(""));
            if (source.has("description")) {
                codexTool.set("description", source.get("description"));
            }
            if (source.has("parameters")) {
                codexTool.set("parameters", ToolSchemaSanitizer.sanitizeParameters(objectMapper, source.get("parameters")));
            }
            if (source.has("strict")) {
                codexTool.set("strict", source.get("strict"));
            }
        }
        return converted;
    }

    private void normalizeResponsesApplyPatchTool(ObjectNode body) {
        JsonNode tools = body.path("tools");
        if (tools.isArray() && !tools.isEmpty()) {
            ArrayNode converted = objectMapper.createArrayNode();
            boolean changed = false;
            for (JsonNode tool : tools) {
                JsonNode normalized = applyPatchCustomTool(tool);
                changed = changed || normalized != tool;
                converted.add(normalized);
            }
            if (changed) {
                body.set("tools", converted);
            }
        }
        if (body.has("tool_choice")) {
            JsonNode normalizedToolChoice = applyPatchToolChoice(body.path("tool_choice"));
            if (normalizedToolChoice != body.path("tool_choice")) {
                body.set("tool_choice", normalizedToolChoice);
            }
        }
    }

    private JsonNode applyPatchCustomTool(JsonNode tool) {
        JsonNode function = tool.path("function");
        JsonNode source = function.isObject() ? function : tool;
        if (!isApplyPatchTool(source)) {
            return tool;
        }
        return applyPatchFunctionTool(source);
    }

    private ObjectNode applyPatchFunctionTool(JsonNode source) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        tool.put("name", APPLY_PATCH_TOOL_NAME);
        String description = firstNonBlank(
            source.path("description").asString(null),
            "Apply a raw patch. The patch string must start with *** Begin Patch and end with *** End Patch."
        );
        tool.put("description", description);
        ObjectNode parameters = tool.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ObjectNode patch = properties.putObject("patch");
        patch.put("type", "string");
        patch.put("description", "Raw apply_patch payload, including the *** Begin Patch and *** End Patch lines.");
        ArrayNode required = parameters.putArray("required");
        required.add("patch");
        if (source.has("strict")) {
            tool.set("strict", source.get("strict"));
        }
        return tool;
    }

    private boolean isApplyPatchTool(JsonNode source) {
        return APPLY_PATCH_TOOL_NAME.equals(source.path("name").asString(null));
    }

    private JsonNode codexToolChoice(JsonNode toolChoice) {
        JsonNode applyPatchChoice = applyPatchToolChoice(toolChoice);
        if (applyPatchChoice != toolChoice) {
            return applyPatchChoice;
        }
        if (!toolChoice.isObject() || !"function".equals(toolChoice.path("type").asString(""))) {
            return toolChoice;
        }
        String name = toolChoiceName(toolChoice);
        if (name.isBlank()) {
            return toolChoice;
        }
        ObjectNode converted = objectMapper.createObjectNode();
        converted.put("type", "function");
        converted.put("name", name);
        return converted;
    }

    private JsonNode applyPatchToolChoice(JsonNode toolChoice) {
        if (!toolChoice.isObject()) {
            return toolChoice;
        }
        String name = toolChoiceName(toolChoice);
        if (!APPLY_PATCH_TOOL_NAME.equals(name)) {
            return toolChoice;
        }
        ObjectNode converted = objectMapper.createObjectNode();
        converted.put("type", "function");
        converted.put("name", name);
        return converted;
    }

    private String toolChoiceName(JsonNode toolChoice) {
        JsonNode function = toolChoice.path("function");
        return firstNonBlank(function.path("name").asString(null), toolChoice.path("name").asString(null));
    }

    private ArrayNode codexInput(JsonNode request) {
        if (request.has("input")) {
            return codexResponsesInput(request.path("input"));
        }
        return codexChatInput(request);
    }

    private ArrayNode codexChatInput(JsonNode request) {
        ArrayNode input = objectMapper.createArrayNode();
        JsonNode messages = request.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if ("system".equals(message.path("role").asString(""))) {
                    continue;
                }
                appendCodexMessage(input, message);
            }
        }
        if (input.isEmpty()) {
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "input_text");
            text.put("text", "Continue");
        }
        return input;
    }

    private ArrayNode codexResponsesInput(JsonNode sourceInput) {
        ArrayNode input = objectMapper.createArrayNode();
        if (sourceInput.isString()) {
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            addTextPart(content, "input_text", sourceInput.asString(""));
        } else if (sourceInput.isArray()) {
            for (JsonNode item : sourceInput) {
                appendCodexResponsesInputItem(input, item);
            }
        }
        if (input.isEmpty()) {
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            addTextPart(content, "input_text", "Continue");
        }
        return input;
    }

    private void appendCodexMessage(ArrayNode input, JsonNode source) {
        String role = source.path("role").asString("");
        if ("tool".equals(role)) {
            appendCodexFunctionCallOutput(input, source);
            return;
        }
        String textValue = contentText(source.path("content"));
        JsonNode toolCalls = source.path("tool_calls");
        if ("assistant".equals(role) && textValue.isBlank() && toolCalls.isArray() && !toolCalls.isEmpty()) {
            appendCodexFunctionCalls(input, toolCalls);
            return;
        }
        ObjectNode message = input.addObject();
        message.put("type", "message");
        String codexRole = "assistant".equals(role) ? "assistant" : "user";
        message.put("role", codexRole);
        ArrayNode content = message.putArray("content");
        appendCodexContentParts(content, source.path("content"), codexRole);
        if (content.isEmpty()) {
            addTextPart(content, "assistant".equals(codexRole) ? "output_text" : "input_text", textValue);
        }
        if ("assistant".equals(role) && toolCalls.isArray() && !toolCalls.isEmpty()) {
            appendCodexFunctionCalls(input, toolCalls);
        }
    }

    private void appendCodexResponsesInputItem(ArrayNode input, JsonNode source) {
        String type = source.path("type").asString("");
        if ("function_call".equals(type)) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call");
            item.put("call_id", firstNonBlank(source.path("call_id").asString(null), source.path("id").asString(null)));
            item.put("name", source.path("name").asString(""));
            item.put("arguments", jsonText(source.path("arguments"), "{}"));
            item.put("status", source.path("status").asString("completed"));
            return;
        }
        if ("function_call_output".equals(type)) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call_output");
            item.put("call_id", source.path("call_id").asString(""));
            item.put("output", functionCallOutputText(source.path("output")));
            return;
        }
        if ("message".equals(type) || source.has("role") || source.has("content")) {
            String role = source.path("role").asString("user");
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", "assistant".equals(role) ? "assistant" : "user");
            ArrayNode content = message.putArray("content");
            appendCodexContentParts(content, source.path("content"), message.path("role").asString(""));
            if (content.isEmpty()) {
                addTextPart(content, "assistant".equals(message.path("role").asString("")) ? "output_text" : "input_text", "");
            }
            return;
        }
        input.add(source.deepCopy());
    }

    private void appendCodexFunctionCalls(ArrayNode input, JsonNode toolCalls) {
        if (!toolCalls.isArray()) {
            return;
        }
        for (JsonNode toolCall : toolCalls) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call");
            item.put("call_id", toolCall.path("id").asString(toolCall.path("call_id").asString("")));
            JsonNode function = toolCall.path("function");
            item.put("name", firstNonBlank(function.path("name").asString(null), toolCall.path("name").asString(null)));
            JsonNode arguments = function.path("arguments");
            item.put("arguments", arguments.isMissingNode() || arguments.isNull()
                ? "{}"
                : arguments.isString() ? arguments.asString("") : arguments.toString());
            item.put("status", "completed");
        }
    }

    private void appendCodexFunctionCallOutput(ArrayNode input, JsonNode source) {
        ObjectNode item = input.addObject();
        item.put("type", "function_call_output");
        item.put("call_id", firstNonBlank(source.path("tool_call_id").asString(null), source.path("call_id").asString(null)));
        item.put("output", contentText(source.path("content")));
    }

    private void appendCodexContentParts(ArrayNode target, JsonNode content, String role) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return;
        }
        String textType = "assistant".equals(role) ? "output_text" : "input_text";
        if (content.isString()) {
            addTextPart(target, textType, content.asString(""));
            return;
        }
        if (!content.isArray()) {
            addTextPart(target, textType, content.toString());
            return;
        }
        for (JsonNode part : content) {
            appendCodexContentPart(target, part, textType);
        }
    }

    private void appendCodexContentPart(ArrayNode target, JsonNode part, String textType) {
        String type = part.path("type").asString("");
        switch (type) {
            case "text" -> addTextPart(target, textType, part.path("text").asString(""));
            case "input_text", "output_text" -> {
                ObjectNode copy = target.addObject();
                copy.put("type", type);
                copy.put("text", part.path("text").asString(""));
            }
            case "refusal" -> addTextPart(target, "output_text", part.path("refusal").asString(part.path("text").asString("")));
            case "image_url" -> addInputImageFromChat(target, part);
            case "input_image" -> target.add(normalizedInputImage(part));
            case "input_file" -> target.add(part.deepCopy());
            default -> target.add(part.deepCopy());
        }
    }

    private void addInputImageFromChat(ArrayNode target, JsonNode part) {
        JsonNode imageUrl = part.path("image_url");
        ObjectNode inputImage = target.addObject();
        inputImage.put("type", "input_image");
        if (imageUrl.isObject()) {
            if (imageUrl.has("url")) {
                inputImage.set("image_url", imageUrl.get("url"));
            }
            if (imageUrl.has("detail")) {
                inputImage.set("detail", imageUrl.get("detail"));
            }
        } else if (imageUrl.isString()) {
            inputImage.set("image_url", imageUrl);
        }
    }

    private ObjectNode normalizedInputImage(JsonNode part) {
        ObjectNode inputImage = objectMapper.createObjectNode();
        inputImage.put("type", "input_image");
        copyIfPresent(part, inputImage, "image_url");
        copyIfPresent(part, inputImage, "file_id");
        copyIfPresent(part, inputImage, "detail");
        return inputImage;
    }

    private void addTextPart(ArrayNode target, String type, String text) {
        ObjectNode part = target.addObject();
        part.put("type", type);
        part.put("text", text == null ? "" : text);
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field).deepCopy());
        }
    }

    private String codexInstructions(JsonNode request) {
        String explicit = request.path("instructions").asString(null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        StringBuilder builder = new StringBuilder();
        JsonNode messages = request.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if (!"system".equals(message.path("role").asString(""))) {
                    continue;
                }
                String text = contentText(message.path("content"));
                if (!text.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private String contentText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isString()) {
            return content.asString("");
        }
        if (!content.isArray()) {
            return content.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            String text = firstNonBlank(part.path("text").asString(null), part.path("refusal").asString(null));
            if (!text.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private void normalizeChatTokenLimit(ObjectNode body, String model) {
        if (!usesMaxCompletionTokens(model)) {
            return;
        }
        JsonNode maxTokens = body.path("max_tokens");
        JsonNode maxOutputTokens = body.path("max_output_tokens");
        if (!maxTokens.isMissingNode() && !maxTokens.isNull()) {
            body.set("max_completion_tokens", maxTokens);
        } else if (!maxOutputTokens.isMissingNode() && !maxOutputTokens.isNull()) {
            body.set("max_completion_tokens", maxOutputTokens);
        }
        body.remove(List.of("max_tokens", "max_output_tokens"));
    }

    /**
     * The upstream body only gets top-level fields added, replaced or removed, so the (possibly multi-MB)
     * message tree is shared with the request instead of being deep-copied for every call.
     */
    private static ObjectNode copyForUpstream(JsonNode request) {
        return request instanceof ObjectNode object ? JsonNodes.shallowCopy(object) : (ObjectNode) request.deepCopy();
    }

    private void forceNonStreaming(ObjectNode body) {
        body.put("stream", false);
        body.remove("stream_options");
    }

    private String openAiModel(String model, ProxyAccountEntity account) {
        String cleaned = clean(model);
        if (cleaned == null) {
            if (AccountPlatforms.isDeepSeek(account)) {
                return DeepSeekModelCatalog.DEFAULT_MODEL_ID;
            }
            return AccountPlatforms.isCodex(account)
                ? OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL
                : OpenAiAccountTestClient.DEFAULT_OPENAI_TEST_MODEL;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.startsWith("openai-compatible/")) {
            return cleaned.substring("openai-compatible/".length());
        }
        if (AccountPlatforms.isDeepSeek(account)) {
            return DeepSeekModelCatalog.canonicalModelId(cleaned);
        }
        String unprefixed = lower.startsWith("openai/") ? lower.substring("openai/".length()) : lower;
        if (AccountPlatforms.isCodex(account) || isOfficialOpenAiBaseUrl(account)) {
            return unprefixed;
        }
        return unprefixed.contains("/") ? unprefixed : "openai/" + unprefixed;
    }

    private String codexCompatibleModel(String model) {
        String cleaned = clean(model);
        if (cleaned == null) {
            return OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL;
        }
        String normalized = cleaned.toLowerCase(Locale.ROOT);
        if (normalized.contains("codex") || "codex-mini-latest".equals(normalized)) {
            return OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL;
        }
        return normalized;
    }

    private Mono<String> retryWithUnprefixedOpenAiModel(
        Throwable error,
        ProxyAccountEntity account,
        String upstreamModel
    ) {
        if (AccountPlatforms.isCodex(account)
            || AccountPlatforms.isDeepSeek(account)
            || isOfficialOpenAiBaseUrl(account)
            || !isModelNotFound(error)) {
            return Mono.error(error);
        }
        String normalized = clean(upstreamModel);
        if (normalized == null || !normalized.toLowerCase(Locale.ROOT).startsWith("openai/")) {
            return Mono.error(error);
        }
        String retryModel = normalized.substring("openai/".length());
        return retryModel.isBlank() ? Mono.error(error) : Mono.just(retryModel);
    }

    private boolean isModelNotFound(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (!(current instanceof UpstreamException upstream)) {
            return false;
        }
        String message = upstream.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("model_not_found")
            || normalized.contains("no available channel for model")
            || normalized.contains("model does not exist")
            || normalized.contains("does not support model");
    }

    private List<String> sseDataPayloads(String rawBody) {
        List<String> payloads = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : rawBody.split("\\R", -1)) {
            if (line.isEmpty()) {
                flushSseData(payloads, current);
                continue;
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            String data = line.substring("data:".length());
            current.append(data.startsWith(" ") ? data.substring(1) : data);
        }
        flushSseData(payloads, current);
        return payloads;
    }

    private void flushSseData(List<String> payloads, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        payloads.add(current.toString());
        current.setLength(0);
    }

    private String responseOutputText(JsonNode output) {
        if (!output.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : output) {
            String text = responseOutputItemText(item);
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private String responseReasoningText(JsonNode output) {
        if (!output.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : output) {
            String text = responseReasoningItemText(item);
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private List<PlatformToolUse> responseToolUses(JsonNode output) {
        List<PlatformToolUse> toolUses = new ArrayList<>();
        appendResponseToolUses(toolUses, output);
        return toolUses;
    }

    private void appendResponseToolUses(List<PlatformToolUse> toolUses, JsonNode output) {
        if (!output.isArray()) {
            return;
        }
        for (JsonNode item : output) {
            appendResponseToolUse(toolUses, item);
        }
    }

    private void rememberResponseToolUses(Map<String, PendingResponseToolUse> pendingToolUses, JsonNode output) {
        if (!output.isArray()) {
            return;
        }
        for (JsonNode item : output) {
            rememberResponseToolUse(pendingToolUses, item);
        }
    }

    private void appendResponseToolUse(List<PlatformToolUse> toolUses, JsonNode item) {
        String type = item.path("type").asString("");
        if (!isResponseToolCall(type)) {
            return;
        }
        String callId = firstNonBlank(item.path("call_id").asString(null), item.path("id").asString(null));
        String name = item.path("name").asString(null);
        if (callId == null || name == null || name.isBlank()) {
            return;
        }
        if (toolUses.stream().anyMatch(toolUse -> callId.equals(toolUse.id()))) {
            return;
        }
        String effectiveType = responseToolUseType(type, name);
        String rawInput = responseToolUseRawInput(effectiveType, name, responseToolInput(item));
        JsonNode rawItem = responseToolOutputItem(item, effectiveType, rawInput);
        toolUses.add(new PlatformToolUse(
            callId,
            name,
            parseToolArguments(rawInput),
            effectiveType,
            rawInput,
            rawItem
        ));
    }

    private void rememberResponseToolUse(Map<String, PendingResponseToolUse> pendingToolUses, JsonNode item) {
        String type = item.path("type").asString("");
        if (!isResponseToolCall(type)) {
            return;
        }
        String itemId = item.path("id").asString(null);
        String callId = firstNonBlank(item.path("call_id").asString(null), itemId);
        String key = firstNonBlank(itemId, callId);
        if (key.isBlank()) {
            return;
        }
        PendingResponseToolUse pending = pendingToolUses.computeIfAbsent(key, ignored -> new PendingResponseToolUse());
        pending.itemId = firstNonBlank(pending.itemId, itemId);
        pending.callId = firstNonBlank(pending.callId, callId);
        pending.name = firstNonBlank(pending.name, item.path("name").asString(null));
        pending.type = firstNonBlank(pending.type, responseToolUseType(type, pending.name));
        if (item.isObject()) {
            pending.item = item.deepCopy();
        }
        String input = responseToolUseRawInput(pending.type, pending.name, responseToolInput(item));
        if (input != null
            && !input.isBlank()
            && shouldReplacePendingToolInput(pending.type, pending.name, pending.arguments, input)) {
            pending.arguments.setLength(0);
            pending.arguments.append(input);
        }
    }

    private void appendResponseToolUseArguments(Map<String, PendingResponseToolUse> pendingToolUses, JsonNode event) {
        PendingResponseToolUse pending = pendingResponseToolUse(pendingToolUses, event);
        if (pending == null) {
            return;
        }
        String delta = event.path("delta").asString("");
        if (!delta.isEmpty()) {
            pending.arguments.append(delta);
        }
    }

    private void completeResponseToolUseArguments(Map<String, PendingResponseToolUse> pendingToolUses, JsonNode event) {
        PendingResponseToolUse pending = pendingResponseToolUse(pendingToolUses, event);
        if (pending == null) {
            return;
        }
        String arguments = firstNonBlank(event.path("arguments").asString(null), event.path("input").asString(null));
        String type = responseToolUseType(firstNonBlank(pending.type, "function_call"), pending.name);
        String input = responseToolUseRawInput(type, pending.name, arguments);
        if (!input.isBlank() && shouldReplacePendingToolInput(type, pending.name, pending.arguments, input)) {
            pending.arguments.setLength(0);
            pending.arguments.append(input);
        }
    }

    private boolean shouldReplacePendingToolInput(String type, String name, StringBuilder current, String replacement) {
        if (!"custom_tool_call".equals(type) || !APPLY_PATCH_TOOL_NAME.equals(name)) {
            return true;
        }
        if (current == null || current.isEmpty()) {
            return true;
        }
        String currentInput = current.toString();
        if (looksLikeCompletePatch(currentInput) && !looksLikeCompletePatch(replacement)) {
            return false;
        }
        return replacement.length() >= currentInput.length();
    }

    private PendingResponseToolUse pendingResponseToolUse(Map<String, PendingResponseToolUse> pendingToolUses, JsonNode event) {
        String itemId = event.path("item_id").asString(null);
        String callId = event.path("call_id").asString(null);
        String key = firstNonBlank(itemId, callId);
        if (key.isBlank()) {
            return null;
        }
        PendingResponseToolUse pending = pendingToolUses.computeIfAbsent(key, ignored -> new PendingResponseToolUse());
        pending.itemId = firstNonBlank(pending.itemId, itemId);
        pending.callId = firstNonBlank(pending.callId, callId);
        pending.name = firstNonBlank(pending.name, event.path("name").asString(null));
        return pending;
    }

    private void appendPendingResponseToolUses(
        List<PlatformToolUse> toolUses,
        Map<String, PendingResponseToolUse> pendingToolUses
    ) {
        for (PendingResponseToolUse pending : pendingToolUses.values()) {
            String callId = firstNonBlank(pending.callId, pending.itemId);
            if (callId.isBlank() || pending.name == null || pending.name.isBlank()) {
                continue;
            }
            if (toolUses.stream().anyMatch(toolUse -> callId.equals(toolUse.id()))) {
                continue;
            }
            String effectiveType = responseToolUseType(firstNonBlank(pending.type, "function_call"), pending.name);
            String rawInput = responseToolUseRawInput(effectiveType, pending.name, pending.arguments.toString());
            JsonNode rawItem = pendingItemWithInput(pending, rawInput);
            toolUses.add(new PlatformToolUse(
                callId,
                pending.name,
                parseToolArguments(rawInput),
                effectiveType,
                rawInput,
                rawItem
            ));
        }
    }

    private boolean isResponseToolCall(String type) {
        return "function_call".equals(type)
            || "custom_tool_call".equals(type)
            || "mcp_tool_call".equals(type)
            || "tool_call".equals(type)
            || "local_shell_call".equals(type)
            || "tool_search_call".equals(type);
    }

    private String responseToolInput(JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return "{}";
        }
        if (item.has("arguments")) {
            return item.path("arguments").isString() ? item.path("arguments").asString("") : item.path("arguments").toString();
        }
        if (item.has("input")) {
            return item.path("input").isString() ? item.path("input").asString("") : item.path("input").toString();
        }
        return "{}";
    }

    private JsonNode pendingItemWithInput(PendingResponseToolUse pending, String rawInput) {
        if (pending.item == null || !pending.item.isObject()) {
            return null;
        }
        ObjectNode item = (ObjectNode) pending.item.deepCopy();
        String type = responseToolUseType(firstNonBlank(pending.type, item.path("type").asString(null), "function_call"), pending.name);
        item.put("type", type);
        if ("custom_tool_call".equals(type)) {
            item.remove(List.of("arguments", "status"));
            item.put("input", rawInput == null ? "" : rawInput);
        } else {
            item.put("arguments", rawInput == null || rawInput.isBlank() ? "{}" : rawInput);
        }
        return item;
    }

    private JsonNode responseToolOutputItem(JsonNode item, String type, String rawInput) {
        if (item == null || !item.isObject()) {
            return null;
        }
        ObjectNode copy = (ObjectNode) item.deepCopy();
        copy.put("type", type);
        if ("custom_tool_call".equals(type)) {
            copy.remove(List.of("arguments", "status"));
            copy.put("input", rawInput == null ? "" : rawInput);
        } else if ("function_call".equals(type)) {
            copy.remove("input");
            copy.put("arguments", rawInput == null || rawInput.isBlank() ? "{}" : rawInput);
        }
        return copy;
    }

    private String responseToolUseType(String type, String name) {
        if (APPLY_PATCH_TOOL_NAME.equals(name) && "function_call".equals(type)) {
            return "custom_tool_call";
        }
        return type;
    }

    private String responseToolUseRawInput(String type, String name, String rawInput) {
        if (!"custom_tool_call".equals(type) || !APPLY_PATCH_TOOL_NAME.equals(name)) {
            return rawInput;
        }
        return normalizeApplyPatchInput(extractCustomToolInput(rawInput));
    }

    private String extractCustomToolInput(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }
        try {
            JsonNode parsed = objectMapper.readTree(rawInput);
            if (parsed.isString()) {
                return parsed.asString("");
            }
            if (parsed.isObject()) {
                String text = firstNonBlank(
                    parsed.path("patch").asString(null),
                    parsed.path("input").asString(null),
                    parsed.path("text").asString(null)
                );
                if (!text.isBlank()) {
                    return text;
                }
                if (parsed.isEmpty()) {
                    return "";
                }
            }
        } catch (Exception ignored) {
            // The normal custom-tool path is already raw text.
        }
        return rawInput;
    }

    private boolean looksLikeCompletePatch(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        return normalized.endsWith("*** End Patch");
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

    private JsonNode parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode().put("_rawArguments", arguments);
        }
    }

    private String responseOutputItemText(JsonNode item) {
        JsonNode content = item.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            String text = contentPartText(part);
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private String responseReasoningItemText(JsonNode item) {
        String directText = firstNonBlank(
            item.path("reasoning_text").asString(""),
            item.path("reasoning_content").asString("")
        );
        JsonNode content = item.path("content");
        if (!content.isArray()) {
            return directText;
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            String text = reasoningContentPartText(part);
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.length() > 0 ? builder.toString() : directText;
    }

    private String contentPartText(JsonNode part) {
        String type = part.path("type").asString("");
        return "output_text".equals(type) || "text".equals(type) ? part.path("text").asString("") : "";
    }

    private String reasoningContentPartText(JsonNode part) {
        return "reasoning_text".equals(part.path("type").asString(""))
            ? part.path("text").asString("")
            : "";
    }

    private String jsonText(JsonNode value, String fallback) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (value.isString()) {
            return value.asString("");
        }
        return value.toString();
    }

    private String functionCallOutputText(JsonNode output) {
        if (output == null || output.isMissingNode() || output.isNull()) {
            return "";
        }
        if (output.isString()) {
            return output.asString("");
        }
        if (!output.isArray()) {
            return output.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : output) {
            String text = firstNonBlank(part.path("text").asString(null), part.path("output_text").asString(null));
            if (text.isBlank()) {
                text = part.toString();
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private JsonNode presentNode(JsonNode candidate, JsonNode fallback) {
        return candidate == null || candidate.isMissingNode() || candidate.isNull() ? fallback : candidate;
    }

    private PlatformUsage usage(JsonNode usage, String inputField, String outputField) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return PlatformUsage.zero();
        }
        long inputTokens = usage.path(inputField).asLong(0);
        long outputTokens = usage.path(outputField).asLong(0);
        long cacheReadTokens = usage.has("prompt_cache_hit_tokens")
            ? usage.path("prompt_cache_hit_tokens").asLong(0)
            : usage.path(inputField + "_details").path("cached_tokens").asLong(0);
        long cacheMissTokens = usage.path("prompt_cache_miss_tokens").asLong(0);
        if (inputTokens <= 0 && (cacheReadTokens > 0 || cacheMissTokens > 0)) {
            inputTokens = cacheReadTokens + cacheMissTokens;
        }
        long reasoningTokens = usage.has("reasoning_tokens")
            ? usage.path("reasoning_tokens").asLong(0)
            : usage.path(outputField + "_details").path("reasoning_tokens").asLong(0);
        return new PlatformUsage(inputTokens, outputTokens, BigDecimal.ZERO, cacheReadTokens, 0, reasoningTokens);
    }

    private boolean isOfficialOpenAiBaseUrl(ProxyAccountEntity account) {
        String baseUrl = account == null ? null : account.getBaseUrl();
        return baseUrl == null
            || baseUrl.isBlank()
            || baseUrl.replaceAll("/+$", "").equalsIgnoreCase(AccountPlatforms.DEFAULT_OPENAI_BASE_URL);
    }

    private boolean isRecoverableStatus(int statusCode) {
        return statusCode == 402 || statusCode == 429 || statusCode >= 500;
    }

    private String invalidResponseMessage(String endpoint, String rawBody) {
        String body = clean(rawBody);
        if (body != null && body.toLowerCase().startsWith("<!doctype html")) {
            return "OpenAI upstream returned HTML instead of JSON from " + endpoint
                + ". Check the Base URL; OpenAI-compatible API key accounts use the /v1 API path by default.";
        }
        return "OpenAI upstream returned invalid response from " + endpoint + ": " + truncate(rawBody);
    }

    private String truncate(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return "";
        }
        String singleLine = cleaned.replaceAll("\\s+", " ");
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500) + "...";
    }

    private boolean isUnauthorized(Throwable error) {
        return error instanceof UpstreamException upstream && upstream.getStatusCode() == 401;
    }

    private boolean usesMaxCompletionTokens(String model) {
        String normalized = clean(model);
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.startsWith("gpt-6")
            || lower.startsWith("gpt-5")
            || lower.startsWith("o1")
            || lower.startsWith("o3")
            || lower.startsWith("o4");
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static final class PendingResponseToolUse {
        private String itemId;
        private String callId;
        private String name;
        private String type;
        private JsonNode item;
        private final StringBuilder arguments = new StringBuilder();
    }

    @FunctionalInterface
    private interface ResponseParser {
        PlatformCompletionResult parse(String rawBody) throws Exception;
    }
}
