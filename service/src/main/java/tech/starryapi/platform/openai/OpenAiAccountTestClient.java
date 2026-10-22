package tech.starryapi.platform.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.upstream.UpstreamException;

@Component
public class OpenAiAccountTestClient {

    public static final String DEFAULT_OPENAI_TEST_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_CODEX_TEST_MODEL = "gpt-5.5";

    private static final String CODEX_USER_AGENT = "codex_cli_rs/0.139.0";
    private static final String CODEX_ORIGINATOR = "codex_cli_rs";
    private static final String CODEX_TEST_INSTRUCTIONS = "You are Codex. Reply to the account health check concisely.";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final AccountTokenRefreshService tokenRefreshService;

    public OpenAiAccountTestClient(
        ObjectMapper objectMapper,
        WebClient upstreamWebClient,
        AccountTokenRefreshService tokenRefreshService
    ) {
        this.objectMapper = objectMapper;
        this.webClient = upstreamWebClient;
        this.tokenRefreshService = tokenRefreshService;
    }

    public Mono<PlatformCompletionResult> test(ProxyAccountEntity account, String model, String prompt) {
        if (AccountPlatforms.isCodex(account)) {
            return testCodex(account, model, prompt);
        }
        return testChatCompletions(account, model, prompt);
    }

    public Mono<OpenAiQuotaSnapshot> quota(ProxyAccountEntity account) {
        ObjectNode body = codexBody(DEFAULT_CODEX_TEST_MODEL, "hi");
        return quotaCodexOnce(account, body)
            .onErrorResume(error -> {
                if (isUnauthorized(error) && tokenRefreshService.canRefresh(account)) {
                    return tokenRefreshService.refresh(account)
                        .flatMap(refreshedAccount -> quotaCodexOnce(refreshedAccount, body));
                }
                return Mono.error(error);
            });
    }

    private Mono<PlatformCompletionResult> testChatCompletions(ProxyAccountEntity account, String model, String prompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        if (usesMaxCompletionTokens(model)) {
            body.put("max_completion_tokens", 24);
        } else {
            body.put("max_tokens", 24);
        }
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        String endpoint = OpenAiEndpoints.openAiApi(account.getBaseUrl(), "/chat/completions");
        return webClient.post()
            .uri(endpoint)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyOpenAiHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, this::parseChatCompletions, endpoint));
    }

    private Mono<PlatformCompletionResult> testCodex(ProxyAccountEntity account, String model, String prompt) {
        ObjectNode body = codexBody(model, prompt);
        return testCodexOnce(account, body)
            .onErrorResume(error -> {
                if (isUnauthorized(error) && tokenRefreshService.canRefresh(account)) {
                    return tokenRefreshService.refresh(account)
                        .flatMap(refreshedAccount -> testCodexOnce(refreshedAccount, body));
                }
                return Mono.error(error);
            });
    }

    private ObjectNode codexBody(String model, String prompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("instructions", CODEX_TEST_INSTRUCTIONS);
        body.put("store", false);
        body.put("stream", true);
        ArrayNode input = body.putArray("input");
        ObjectNode user = input.addObject();
        user.put("type", "message");
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "input_text");
        text.put("text", prompt);
        return body;
    }

    private Mono<PlatformCompletionResult> testCodexOnce(ProxyAccountEntity account, ObjectNode body) {
        String endpoint = OpenAiEndpoints.codexApi(account.getBaseUrl(), "/responses");
        return webClient.post()
            .uri(endpoint)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyCodexHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, this::parseResponses, endpoint));
    }

    private Mono<OpenAiQuotaSnapshot> quotaCodexOnce(ProxyAccountEntity account, ObjectNode body) {
        String endpoint = OpenAiEndpoints.codexApi(account.getBaseUrl(), "/responses");
        return webClient.post()
            .uri(endpoint)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyCodexHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleQuotaResponse(response, endpoint));
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
        headers.set("OpenAI-Beta", "responses=experimental");
        headers.set("Originator", CODEX_ORIGINATOR);
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
                        "OpenAI account test error " + status.value() + ": " + rawBody,
                        isRecoverableStatus(status.value())
                    ));
                }
                try {
                    return Mono.just(parser.parse(rawBody));
                } catch (Exception error) {
                    return Mono.error(new UpstreamException(502, invalidResponseMessage(endpoint, rawBody), false));
                }
            });
    }

    private Mono<OpenAiQuotaSnapshot> handleQuotaResponse(ClientResponse response, String endpoint) {
        HttpStatusCode status = response.statusCode();
        Instant checkedAt = Instant.now();
        OpenAiQuotaSnapshot snapshot = quotaSnapshot(response.headers().asHttpHeaders(), checkedAt);
        return response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .flatMap(rawBody -> {
                if (snapshot != null) {
                    return Mono.just(snapshot);
                }
                if (status.isError()) {
                    return Mono.error(new UpstreamException(
                        status.value(),
                        "OpenAI quota probe error " + status.value() + ": " + rawBody,
                        isRecoverableStatus(status.value())
                    ));
                }
                return Mono.error(new UpstreamException(
                    502,
                    "OpenAI quota probe returned no x-codex quota headers from " + endpoint,
                    false
                ));
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

    private boolean isRecoverableStatus(int statusCode) {
        return statusCode == 402 || statusCode == 429 || statusCode >= 500;
    }

    private String invalidResponseMessage(String endpoint, String rawBody) {
        String body = clean(rawBody);
        if (body != null && body.toLowerCase().startsWith("<!doctype html")) {
            return "OpenAI account test returned HTML instead of JSON from " + endpoint
                + ". Check the Base URL; OpenAI-compatible API key accounts use the /v1 API path by default.";
        }
        return "OpenAI account test returned invalid response from " + endpoint + ": " + truncate(rawBody);
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
        String lower = normalized.toLowerCase();
        return lower.startsWith("gpt-6")
            || lower.startsWith("gpt-5")
            || lower.startsWith("o1")
            || lower.startsWith("o3")
            || lower.startsWith("o4");
    }

    private OpenAiQuotaSnapshot quotaSnapshot(HttpHeaders headers, Instant checkedAt) {
        OpenAiQuotaWindow primary = quotaWindow("primary", "primary", headers, "x-codex-primary", checkedAt);
        OpenAiQuotaWindow secondary = quotaWindow("secondary", "secondary", headers, "x-codex-secondary", checkedAt);
        Double primaryOverSecondary = parseDouble(headers.getFirst("x-codex-primary-over-secondary-limit-percent"));
        if (primary == null && secondary == null && primaryOverSecondary == null) {
            return null;
        }
        NormalizedQuotaWindows normalized = normalizeQuotaWindows(primary, secondary);
        return new OpenAiQuotaSnapshot(
            primary,
            secondary,
            normalized.fiveHour(),
            normalized.sevenDay(),
            primaryOverSecondary,
            checkedAt
        );
    }

    private OpenAiQuotaWindow quotaWindow(String key, String label, HttpHeaders headers, String prefix, Instant checkedAt) {
        Double usedPercent = parseDouble(headers.getFirst(prefix + "-used-percent"));
        Integer resetAfterSeconds = parseInt(headers.getFirst(prefix + "-reset-after-seconds"));
        Integer windowMinutes = parseInt(headers.getFirst(prefix + "-window-minutes"));
        if (usedPercent == null && resetAfterSeconds == null && windowMinutes == null) {
            return null;
        }
        return window(key, label, usedPercent, resetAfterSeconds, windowMinutes, checkedAt);
    }

    private NormalizedQuotaWindows normalizeQuotaWindows(OpenAiQuotaWindow primary, OpenAiQuotaWindow secondary) {
        if (primary == null && secondary == null) {
            return new NormalizedQuotaWindows(null, null);
        }
        if (primary != null && secondary != null && primary.windowMinutes() != null && secondary.windowMinutes() != null) {
            return primary.windowMinutes() <= secondary.windowMinutes()
                ? new NormalizedQuotaWindows(normalizeWindow("5h", "5小时窗口", primary), normalizeWindow("7d", "7天窗口", secondary))
                : new NormalizedQuotaWindows(normalizeWindow("5h", "5小时窗口", secondary), normalizeWindow("7d", "7天窗口", primary));
        }
        OpenAiQuotaWindow fiveHour = classifyWindow(primary, true);
        OpenAiQuotaWindow sevenDay = classifyWindow(primary, false);
        OpenAiQuotaWindow secondaryFiveHour = classifyWindow(secondary, true);
        OpenAiQuotaWindow secondarySevenDay = classifyWindow(secondary, false);
        return new NormalizedQuotaWindows(
            fiveHour != null ? fiveHour : secondaryFiveHour,
            sevenDay != null ? sevenDay : secondarySevenDay
        );
    }

    private OpenAiQuotaWindow classifyWindow(OpenAiQuotaWindow window, boolean fiveHour) {
        if (window == null || window.windowMinutes() == null) {
            return null;
        }
        boolean isFiveHour = window.windowMinutes() <= 360;
        if (fiveHour != isFiveHour) {
            return null;
        }
        return normalizeWindow(fiveHour ? "5h" : "7d", fiveHour ? "5小时窗口" : "7天窗口", window);
    }

    private OpenAiQuotaWindow normalizeWindow(String key, String label, OpenAiQuotaWindow window) {
        return window(key, label, window.usedPercent(), window.resetAfterSeconds(), window.windowMinutes(), window.checkedAt());
    }

    private OpenAiQuotaWindow window(
        String key,
        String label,
        Double usedPercent,
        Integer resetAfterSeconds,
        Integer windowMinutes,
        Instant checkedAt
    ) {
        Double remainingPercent = usedPercent == null ? null : Math.max(0.0, 100.0 - usedPercent);
        Instant resetAt = resetAfterSeconds == null ? null : checkedAt.plusSeconds(Math.max(0, resetAfterSeconds));
        return new OpenAiQuotaWindow(key, label, usedPercent, remainingPercent, resetAfterSeconds, resetAt, windowMinutes, checkedAt);
    }

    private Double parseDouble(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    public record OpenAiQuotaSnapshot(
        OpenAiQuotaWindow primary,
        OpenAiQuotaWindow secondary,
        OpenAiQuotaWindow fiveHour,
        OpenAiQuotaWindow sevenDay,
        Double primaryOverSecondaryLimitPercent,
        Instant checkedAt
    ) {
    }

    public record OpenAiQuotaWindow(
        String key,
        String label,
        Double usedPercent,
        Double remainingPercent,
        Integer resetAfterSeconds,
        Instant resetAt,
        Integer windowMinutes,
        Instant checkedAt
    ) {
    }

    private record NormalizedQuotaWindows(
        OpenAiQuotaWindow fiveHour,
        OpenAiQuotaWindow sevenDay
    ) {
    }
}
