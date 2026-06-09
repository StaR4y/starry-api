package xyz.star4y.api.platform.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import xyz.star4y.api.account.AccountPlatforms;
import xyz.star4y.api.account.AccountTokenRefreshService;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.core.PlatformToolUse;
import xyz.star4y.api.platform.core.PlatformUsage;
import xyz.star4y.api.platform.core.ToolSchemaSanitizer;
import xyz.star4y.api.platform.upstream.UpstreamException;

@Component
public class OpenAiUpstreamClient {

    private static final String CODEX_USER_AGENT = "codex-tui/0.135.0";
    private static final String CODEX_ORIGINATOR = "codex-tui";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final AccountTokenRefreshService tokenRefreshService;

    public OpenAiUpstreamClient(
        ObjectMapper objectMapper,
        WebClient kiroWebClient,
        AccountTokenRefreshService tokenRefreshService
    ) {
        this.objectMapper = objectMapper;
        this.webClient = kiroWebClient;
        this.tokenRefreshService = tokenRefreshService;
    }

    public Mono<PlatformCompletionResult> complete(ProxyAccountEntity account, JsonNode request, String model) {
        if (AccountPlatforms.isCodex(account)) {
            return completeCodex(account, request, openAiModel(model, account));
        }
        return completeChatCompletions(account, request, openAiModel(model, account));
    }

    public Mono<PlatformCompletionResult> completeResponses(ProxyAccountEntity account, JsonNode request, String model) {
        if (AccountPlatforms.isCodex(account)) {
            return completeCodex(account, request, openAiModel(model, account));
        }
        return completeResponsesApi(account, request, openAiModel(model, account));
    }

    private Mono<PlatformCompletionResult> completeChatCompletions(
        ProxyAccountEntity account,
        JsonNode request,
        String model
    ) {
        ObjectNode body = request.deepCopy();
        body.put("model", model);
        normalizeChatTokenLimit(body, model);

        return webClient.post()
            .uri(endpoint(account.getBaseUrl(), AccountPlatforms.DEFAULT_OPENAI_BASE_URL, "/chat/completions"))
            .headers(headers -> applyOpenAiHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, this::parseChatCompletions));
    }

    private Mono<PlatformCompletionResult> completeCodex(
        ProxyAccountEntity account,
        JsonNode request,
        String model
    ) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", codexCompatibleModel(model));
        String instructions = codexInstructions(request);
        if (instructions != null) {
            body.put("instructions", instructions);
        }
        body.put("store", false);
        body.put("stream", true);
        body.set("input", codexInput(request));
        if (request.hasNonNull("service_tier")) {
            body.set("service_tier", request.get("service_tier"));
        }
        appendCodexTools(body, request);

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
        return webClient.post()
            .uri(endpoint(account.getBaseUrl(), AccountPlatforms.DEFAULT_CODEX_BASE_URL, "/responses"))
            .headers(headers -> applyCodexHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, this::parseResponses));
    }

    private Mono<PlatformCompletionResult> completeResponsesApi(
        ProxyAccountEntity account,
        JsonNode request,
        String model
    ) {
        ObjectNode body = request.deepCopy();
        body.put("model", model);

        return webClient.post()
            .uri(endpoint(account.getBaseUrl(), AccountPlatforms.DEFAULT_OPENAI_BASE_URL, "/responses"))
            .headers(headers -> applyOpenAiResponsesHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, this::parseResponses));
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
        if (account.getUpstreamAccountId() != null && !account.getUpstreamAccountId().isBlank()) {
            headers.set("Chatgpt-Account-Id", account.getUpstreamAccountId());
        }
    }

    private Mono<PlatformCompletionResult> handleResponse(
        ClientResponse response,
        ResponseParser parser
    ) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .flatMap(rawBody -> {
                if (status.isError()) {
                    return Mono.error(new UpstreamException(
                        status.value(),
                        "OpenAI upstream error " + status.value() + ": " + rawBody,
                        isRecoverableStatus(status.value())
                    ));
                }
                try {
                    return Mono.just(parser.parse(rawBody));
                } catch (Exception error) {
                    return Mono.error(new UpstreamException(502, "OpenAI upstream returned invalid response: " + rawBody, false));
                }
            });
    }

    private PlatformCompletionResult parseChatCompletions(String rawBody) throws Exception {
        JsonNode body = readJson(rawBody);
        String content = body.path("choices").path(0).path("message").path("content").asText("");
        return new PlatformCompletionResult(content, usage(body.path("usage"), "prompt_tokens", "completion_tokens"));
    }

    private PlatformCompletionResult parseResponses(String rawBody) throws Exception {
        String trimmed = rawBody == null ? "" : rawBody.trim();
        if (trimmed.startsWith("data:") || trimmed.startsWith("event:")) {
            return parseResponsesStream(rawBody);
        }
        return parseResponsesJson(readJson(rawBody));
    }

    private JsonNode readJson(String rawBody) throws Exception {
        return rawBody == null || rawBody.isBlank()
            ? objectMapper.createObjectNode()
            : objectMapper.readTree(rawBody);
    }

    private PlatformCompletionResult parseResponsesJson(JsonNode body) {
        String content = body.path("output_text").asText("");
        if (content.isBlank()) {
            content = responseOutputText(body.path("output"));
        }
        return new PlatformCompletionResult(
            content,
            responseToolUses(body.path("output")),
            usage(body.path("usage"), "input_tokens", "output_tokens")
        );
    }

    private PlatformCompletionResult parseResponsesStream(String rawBody) throws Exception {
        StringBuilder content = new StringBuilder();
        String fallbackContent = "";
        List<PlatformToolUse> toolUses = new ArrayList<>();
        JsonNode usage = null;
        for (String payload : sseDataPayloads(rawBody)) {
            String data = payload.trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            JsonNode event = objectMapper.readTree(data);
            String type = event.path("type").asText("");
            if ("response.output_text.delta".equals(type)) {
                content.append(event.path("delta").asText(""));
            } else if ("response.output_text.done".equals(type)) {
                fallbackContent = firstNonBlank(fallbackContent, event.path("text").asText(""));
            } else if ("response.content_part.done".equals(type)) {
                fallbackContent = firstNonBlank(fallbackContent, contentPartText(event.path("part")));
            } else if ("response.output_item.done".equals(type)) {
                JsonNode item = event.path("item");
                fallbackContent = firstNonBlank(fallbackContent, responseOutputItemText(item));
                appendResponseToolUse(toolUses, item);
            } else if ("response.completed".equals(type)) {
                JsonNode response = event.path("response");
                fallbackContent = firstNonBlank(
                    fallbackContent,
                    response.path("output_text").asText(""),
                    responseOutputText(response.path("output"))
                );
                appendResponseToolUses(toolUses, response.path("output"));
                usage = presentNode(response.path("usage"), usage);
            }
            usage = presentNode(event.path("usage"), usage);
        }
        return new PlatformCompletionResult(
            content.length() > 0 ? content.toString() : fallbackContent,
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

    private void applyOpenAiResponsesHeaders(HttpHeaders headers, ProxyAccountEntity account) {
        applyOpenAiHeaders(headers, account);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
    }

    private ArrayNode codexTools(JsonNode tools) {
        ArrayNode converted = objectMapper.createArrayNode();
        for (JsonNode tool : tools) {
            if (!"function".equals(tool.path("type").asText())) {
                converted.add(tool);
                continue;
            }
            JsonNode function = tool.path("function");
            if (function.isMissingNode() || function.isNull()) {
                converted.add(tool);
                continue;
            }
            ObjectNode codexTool = converted.addObject();
            codexTool.put("type", "function");
            codexTool.put("name", function.path("name").asText());
            if (function.has("description")) {
                codexTool.set("description", function.get("description"));
            }
            if (function.has("parameters")) {
                codexTool.set("parameters", ToolSchemaSanitizer.sanitizeParameters(objectMapper, function.get("parameters")));
            }
            if (function.has("strict")) {
                codexTool.set("strict", function.get("strict"));
            }
        }
        return converted;
    }

    private JsonNode codexToolChoice(JsonNode toolChoice) {
        if (!toolChoice.isObject() || !"function".equals(toolChoice.path("type").asText())) {
            return toolChoice;
        }
        JsonNode function = toolChoice.path("function");
        if (function.isMissingNode() || function.isNull()) {
            return toolChoice;
        }
        ObjectNode converted = objectMapper.createObjectNode();
        converted.put("type", "function");
        converted.put("name", function.path("name").asText());
        return converted;
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
                if ("system".equals(message.path("role").asText())) {
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
        if (sourceInput.isTextual()) {
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            addTextPart(content, "input_text", sourceInput.asText());
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
        String role = source.path("role").asText();
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
        String type = source.path("type").asText("");
        if ("function_call".equals(type)) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call");
            item.put("call_id", firstNonBlank(source.path("call_id").asText(null), source.path("id").asText(null)));
            item.put("name", source.path("name").asText());
            item.put("arguments", source.path("arguments").asText("{}"));
            item.put("status", source.path("status").asText("completed"));
            return;
        }
        if ("function_call_output".equals(type)) {
            ObjectNode item = input.addObject();
            item.put("type", "function_call_output");
            item.put("call_id", source.path("call_id").asText());
            item.set("output", source.has("output") ? source.get("output").deepCopy() : objectMapper.getNodeFactory().textNode(""));
            return;
        }
        if ("message".equals(type) || source.has("role") || source.has("content")) {
            String role = source.path("role").asText("user");
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", "assistant".equals(role) ? "assistant" : "user");
            ArrayNode content = message.putArray("content");
            appendCodexContentParts(content, source.path("content"), message.path("role").asText());
            if (content.isEmpty()) {
                addTextPart(content, "assistant".equals(message.path("role").asText()) ? "output_text" : "input_text", "");
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
            item.put("call_id", toolCall.path("id").asText(toolCall.path("call_id").asText()));
            JsonNode function = toolCall.path("function");
            item.put("name", firstNonBlank(function.path("name").asText(null), toolCall.path("name").asText(null)));
            JsonNode arguments = function.path("arguments");
            item.put("arguments", arguments.isMissingNode() || arguments.isNull()
                ? "{}"
                : arguments.isTextual() ? arguments.asText() : arguments.toString());
            item.put("status", "completed");
        }
    }

    private void appendCodexFunctionCallOutput(ArrayNode input, JsonNode source) {
        ObjectNode item = input.addObject();
        item.put("type", "function_call_output");
        item.put("call_id", firstNonBlank(source.path("tool_call_id").asText(null), source.path("call_id").asText(null)));
        item.put("output", contentText(source.path("content")));
    }

    private void appendCodexContentParts(ArrayNode target, JsonNode content, String role) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return;
        }
        String textType = "assistant".equals(role) ? "output_text" : "input_text";
        if (content.isTextual()) {
            addTextPart(target, textType, content.asText());
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
        String type = part.path("type").asText("");
        switch (type) {
            case "text" -> addTextPart(target, textType, part.path("text").asText(""));
            case "input_text", "output_text" -> {
                ObjectNode copy = target.addObject();
                copy.put("type", type);
                copy.put("text", part.path("text").asText(""));
            }
            case "refusal" -> addTextPart(target, "output_text", part.path("refusal").asText(part.path("text").asText("")));
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
        } else if (imageUrl.isTextual()) {
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
        String explicit = request.path("instructions").asText(null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        StringBuilder builder = new StringBuilder();
        JsonNode messages = request.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if (!"system".equals(message.path("role").asText())) {
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
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return content.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            String text = firstNonBlank(part.path("text").asText(null), part.path("refusal").asText(null));
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

    private String openAiModel(String model, ProxyAccountEntity account) {
        String cleaned = clean(model);
        if (cleaned == null) {
            return AccountPlatforms.isCodex(account)
                ? OpenAiAccountTestClient.DEFAULT_CODEX_TEST_MODEL
                : OpenAiAccountTestClient.DEFAULT_OPENAI_TEST_MODEL;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        String unprefixed = lower.startsWith("openai/") ? lower.substring("openai/".length()) : lower;
        if (AccountPlatforms.isCodex(account) || isOfficialOpenAiBaseUrl(account)) {
            return unprefixed;
        }
        return lower.contains("/") ? lower : "openai/" + lower;
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

    private void appendResponseToolUse(List<PlatformToolUse> toolUses, JsonNode item) {
        if (!"function_call".equals(item.path("type").asText())) {
            return;
        }
        String callId = firstNonBlank(item.path("call_id").asText(null), item.path("id").asText(null));
        String name = item.path("name").asText(null);
        if (callId == null || name == null || name.isBlank()) {
            return;
        }
        if (toolUses.stream().anyMatch(toolUse -> callId.equals(toolUse.id()))) {
            return;
        }
        toolUses.add(new PlatformToolUse(callId, name, parseToolArguments(item.path("arguments").asText("{}"))));
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

    private String contentPartText(JsonNode part) {
        String type = part.path("type").asText("");
        return "output_text".equals(type) || "text".equals(type) ? part.path("text").asText("") : "";
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
        long cacheReadTokens = usage.path(inputField + "_details").path("cached_tokens").asLong(0);
        long reasoningTokens = usage.path(outputField + "_details").path("reasoning_tokens").asLong(0);
        return new PlatformUsage(inputTokens, outputTokens, BigDecimal.ZERO, cacheReadTokens, 0, reasoningTokens);
    }

    private String endpoint(String baseUrl, String fallback, String suffix) {
        String base = clean(baseUrl);
        if (base == null) {
            base = fallback;
        }
        if (base.endsWith(suffix)) {
            return base;
        }
        return base.replaceAll("/+$", "") + suffix;
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

    private boolean isUnauthorized(Throwable error) {
        return error instanceof UpstreamException upstream && upstream.getStatusCode() == 401;
    }

    private boolean usesMaxCompletionTokens(String model) {
        String normalized = clean(model);
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.startsWith("gpt-5") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4");
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

    @FunctionalInterface
    private interface ResponseParser {
        PlatformCompletionResult parse(String rawBody) throws Exception;
    }
}
