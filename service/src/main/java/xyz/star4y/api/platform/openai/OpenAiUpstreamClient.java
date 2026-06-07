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
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.core.PlatformUsage;
import xyz.star4y.api.platform.upstream.UpstreamException;

@Component
public class OpenAiUpstreamClient {

    private static final String CODEX_USER_AGENT = "codex-tui/0.135.0";
    private static final String CODEX_ORIGINATOR = "codex-tui";
    private static final String DEFAULT_CODEX_INSTRUCTIONS = "You are Codex. Answer the user request concisely.";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public OpenAiUpstreamClient(ObjectMapper objectMapper, WebClient kiroWebClient) {
        this.objectMapper = objectMapper;
        this.webClient = kiroWebClient;
    }

    public Mono<PlatformCompletionResult> complete(ProxyAccountEntity account, JsonNode request, String model) {
        if (AccountPlatforms.isCodex(account)) {
            return completeCodex(account, request, openAiModel(model, account));
        }
        return completeChatCompletions(account, request, openAiModel(model, account));
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
        body.put("instructions", codexInstructions(request));
        body.put("store", false);
        body.put("stream", true);
        body.set("input", codexInput(request));

        return webClient.post()
            .uri(endpoint(account.getBaseUrl(), AccountPlatforms.DEFAULT_CODEX_BASE_URL, "/responses"))
            .headers(headers -> applyCodexHeaders(headers, account))
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
        return new PlatformCompletionResult(content, usage(body.path("usage"), "input_tokens", "output_tokens"));
    }

    private PlatformCompletionResult parseResponsesStream(String rawBody) throws Exception {
        StringBuilder content = new StringBuilder();
        String fallbackContent = "";
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
                fallbackContent = firstNonBlank(fallbackContent, responseOutputItemText(event.path("item")));
            } else if ("response.completed".equals(type)) {
                JsonNode response = event.path("response");
                fallbackContent = firstNonBlank(
                    fallbackContent,
                    response.path("output_text").asText(""),
                    responseOutputText(response.path("output"))
                );
                usage = presentNode(response.path("usage"), usage);
            }
            usage = presentNode(event.path("usage"), usage);
        }
        return new PlatformCompletionResult(
            content.length() > 0 ? content.toString() : fallbackContent,
            usage(usage, "input_tokens", "output_tokens")
        );
    }

    private ArrayNode codexInput(JsonNode request) {
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
        ObjectNode text = content.addObject();
        text.put("type", "assistant".equals(codexRole) ? "output_text" : "input_text");
        text.put("text", textValue);
        if ("assistant".equals(role) && toolCalls.isArray() && !toolCalls.isEmpty()) {
            appendCodexFunctionCalls(input, toolCalls);
        }
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
        return builder.isEmpty() ? DEFAULT_CODEX_INSTRUCTIONS : builder.toString();
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
