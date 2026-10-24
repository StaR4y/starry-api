package tech.starryapi.platform.anthropic;

import java.math.BigDecimal;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.core.ToolSchemaSanitizer;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AnthropicUpstreamClient {

    private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+(?:beta)?");
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String CLAUDE_CODE_CLI_VERSION = "2.1.161";
    private static final String CLAUDE_CODE_USER_AGENT = "claude-cli/" + CLAUDE_CODE_CLI_VERSION + " (external, cli)";
    private static final String CLAUDE_CODE_SYSTEM_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude.";
    private static final String DEFAULT_TEST_MODEL = "claude-sonnet-4-5-20250929";
    private static final String API_KEY_BETA_HEADER =
        "claude-code-20250219,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14";
    private static final Map<String, String> CLAUDE_CODE_API_KEY_HEADERS = Map.ofEntries(
        Map.entry(HttpHeaders.USER_AGENT, CLAUDE_CODE_USER_AGENT),
        Map.entry("X-Stainless-Lang", "js"),
        Map.entry("X-Stainless-Package-Version", "0.94.0"),
        Map.entry("X-Stainless-OS", "Linux"),
        Map.entry("X-Stainless-Arch", "arm64"),
        Map.entry("X-Stainless-Runtime", "node"),
        Map.entry("X-Stainless-Runtime-Version", "v24.3.0"),
        Map.entry("X-Stainless-Retry-Count", "0"),
        Map.entry("X-Stainless-Timeout", "600"),
        Map.entry("X-App", "cli"),
        Map.entry("Anthropic-Dangerous-Direct-Browser-Access", "true")
    );
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public AnthropicUpstreamClient(ObjectMapper objectMapper, WebClient upstreamWebClient) {
        this.objectMapper = objectMapper;
        this.webClient = upstreamWebClient;
    }

    public Mono<PlatformCompletionResult> complete(ProxyAccountEntity account, JsonNode request, String model) {
        String upstreamModel = anthropicModel(model);
        ObjectNode body = anthropicMessagesRequest(request, upstreamModel);
        String endpoint = anthropicApi(account.getBaseUrl(), "/messages");
        return webClient.post()
            .uri(endpoint)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyAnthropicHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, endpoint));
    }

    public Mono<PlatformCompletionResult> testClaudeCode(ProxyAccountEntity account, String model) {
        String upstreamModel = anthropicModel(model);
        ObjectNode body = claudeCodeTestRequest(upstreamModel);
        String endpoint = anthropicApi(account.getBaseUrl(), "/messages");
        return webClient.post()
            .uri(endpoint)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyAnthropicHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, endpoint));
    }

    private ObjectNode claudeCodeTestRequest(String model) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", "hi");
        text.putObject("cache_control").put("type", "ephemeral");

        ArrayNode system = body.putArray("system");
        ObjectNode systemText = system.addObject();
        systemText.put("type", "text");
        systemText.put("text", CLAUDE_CODE_SYSTEM_PROMPT);
        systemText.putObject("cache_control").put("type", "ephemeral");

        body.putObject("metadata").put("user_id", claudeCodeMetadataUserId());
        body.put("max_tokens", 1024);
        body.put("temperature", 1);
        body.put("stream", true);
        return body;
    }

    private String claudeCodeMetadataUserId() {
        byte[] deviceId = new byte[32];
        RANDOM.nextBytes(deviceId);
        ObjectNode userId = objectMapper.createObjectNode();
        userId.put("device_id", HexFormat.of().formatHex(deviceId));
        userId.put("account_uuid", "");
        userId.put("session_id", UUID.randomUUID().toString());
        return userId.toString();
    }

    private ObjectNode anthropicMessagesRequest(JsonNode request, String model) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens(request));
        copyIfPresent(request, body, "temperature");
        copyIfPresent(request, body, "top_p");
        copyIfPresent(request, body, "top_k");
        copyIfPresent(request, body, "stream");
        copyIfPresent(request, body, "metadata");
        copyIfPresent(request, body, "thinking");
        if (request.has("stop_sequences")) {
            body.set("stop_sequences", request.get("stop_sequences").deepCopy());
        } else if (request.has("stop")) {
            body.set("stop_sequences", stopSequences(request.get("stop")));
        }

        String system = systemPrompt(request);
        if (!system.isBlank()) {
            body.put("system", system);
        }

        ArrayNode messages = body.putArray("messages");
        JsonNode sourceMessages = request.path("messages");
        if (sourceMessages.isArray()) {
            for (JsonNode message : sourceMessages) {
                appendAnthropicMessage(messages, message);
            }
        }
        if (messages.isEmpty()) {
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            message.put("content", "Continue");
        }

        if (request.path("tools").isArray() && !request.path("tools").isEmpty()) {
            body.set("tools", anthropicTools(request.path("tools")));
        }
        if (request.has("tool_choice")) {
            body.set("tool_choice", anthropicToolChoice(request.path("tool_choice")));
        }
        return body;
    }

    private void appendAnthropicMessage(ArrayNode messages, JsonNode source) {
        String role = source.path("role").asString("user");
        if ("system".equals(role)) {
            return;
        }
        if ("tool".equals(role)) {
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode result = content.addObject();
            result.put("type", "tool_result");
            result.put("tool_use_id", firstNonBlank(source.path("tool_call_id").asString(null), source.path("call_id").asString(null)));
            result.put("content", contentText(source.path("content")));
            return;
        }

        ObjectNode message = messages.addObject();
        boolean assistant = "assistant".equals(role);
        message.put("role", assistant ? "assistant" : "user");
        ArrayNode content = message.putArray("content");
        appendContentParts(content, source.path("content"), assistant);
        if (assistant) {
            appendToolUses(content, source.path("tool_calls"));
        }
        if (content.isEmpty()) {
            ObjectNode text = content.addObject();
            text.put("type", "text");
            text.put("text", assistant ? " " : "Continue");
        }
    }

    private void appendContentParts(ArrayNode target, JsonNode content, boolean assistant) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return;
        }
        if (content.isString()) {
            addText(target, content.asString(""));
            return;
        }
        if (!content.isArray()) {
            addText(target, content.toString());
            return;
        }
        for (JsonNode part : content) {
            String type = part.path("type").asString("");
            switch (type) {
                case "text", "input_text", "output_text" -> addText(target, part.path("text").asString(""));
                case "refusal" -> addText(target, firstNonBlank(part.path("refusal").asString(null), part.path("text").asString(null)));
                case "tool_use" -> {
                    if (assistant) {
                        ObjectNode toolUse = target.addObject();
                        toolUse.put("type", "tool_use");
                        toolUse.put("id", part.path("id").asString(""));
                        toolUse.put("name", part.path("name").asString(""));
                        toolUse.set("input", objectOrEmpty(part.path("input")));
                    }
                }
                case "tool_result" -> {
                    ObjectNode toolResult = target.addObject();
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", part.path("tool_use_id").asString(""));
                    toolResult.put("content", contentText(part.path("content")));
                }
                default -> {
                    String text = firstNonBlank(part.path("text").asString(null), part.path("content").asString(null));
                    if (!text.isBlank()) {
                        addText(target, text);
                    }
                }
            }
        }
    }

    private void appendToolUses(ArrayNode content, JsonNode toolCalls) {
        if (!toolCalls.isArray()) {
            return;
        }
        for (JsonNode toolCall : toolCalls) {
            JsonNode function = toolCall.path("function");
            String name = firstNonBlank(function.path("name").asString(null), toolCall.path("name").asString(null));
            if (name.isBlank()) {
                continue;
            }
            ObjectNode toolUse = content.addObject();
            toolUse.put("type", "tool_use");
            toolUse.put("id", firstNonBlank(toolCall.path("id").asString(null), toolCall.path("call_id").asString(null), name));
            toolUse.put("name", name);
            toolUse.set("input", parseJsonObject(function.path("arguments")));
        }
    }

    private ArrayNode anthropicTools(JsonNode tools) {
        ArrayNode converted = objectMapper.createArrayNode();
        for (JsonNode tool : tools) {
            JsonNode function = "function".equals(tool.path("type").asString("")) ? tool.path("function") : tool;
            String name = function.path("name").asString(tool.path("name").asString(""));
            if (name.isBlank()) {
                continue;
            }
            ObjectNode convertedTool = converted.addObject();
            convertedTool.put("name", name);
            String description = firstNonBlank(function.path("description").asString(null), tool.path("description").asString(null));
            if (!description.isBlank()) {
                convertedTool.put("description", description);
            }
            JsonNode schema = function.has("parameters") ? function.path("parameters") : function.path("input_schema");
            convertedTool.set("input_schema", ToolSchemaSanitizer.sanitizeParameters(objectMapper, schema));
        }
        return converted;
    }

    private JsonNode anthropicToolChoice(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isMissingNode() || toolChoice.isNull()) {
            return objectMapper.createObjectNode().put("type", "auto");
        }
        if (toolChoice.isString()) {
            String value = toolChoice.asString("").trim().toLowerCase(Locale.ROOT);
            ObjectNode converted = objectMapper.createObjectNode();
            converted.put("type", "required".equals(value) ? "any" : firstNonBlank(value, "auto"));
            return converted;
        }
        if (!toolChoice.isObject()) {
            return toolChoice.deepCopy();
        }
        String type = toolChoice.path("type").asString("");
        if ("function".equals(type)) {
            ObjectNode converted = objectMapper.createObjectNode();
            converted.put("type", "tool");
            converted.put("name", toolChoice.path("function").path("name").asString(""));
            return converted;
        }
        if ("required".equals(type)) {
            ObjectNode converted = objectMapper.createObjectNode();
            converted.put("type", "any");
            return converted;
        }
        return toolChoice.deepCopy();
    }

    private Mono<PlatformCompletionResult> handleResponse(ClientResponse response, String endpoint) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
            .defaultIfEmpty("")
            .flatMap(rawBody -> {
                if (status.isError()) {
                    return Mono.error(new UpstreamException(
                        status.value(),
                        "Anthropic upstream error " + status.value() + ": " + rawBody,
                        isRecoverableStatus(status.value()), response.headers().asHttpHeaders().getFirst("Retry-After")
                    ));
                }
                try {
                    return Mono.just(parseResponse(rawBody));
                } catch (UpstreamException error) {
                    return Mono.error(error);
                } catch (Exception error) {
                    return Mono.error(new UpstreamException(502, invalidResponseMessage(endpoint, rawBody), false));
                }
            });
    }

    private PlatformCompletionResult parseResponse(String rawBody) throws Exception {
        String trimmed = rawBody == null ? "" : rawBody.trim();
        if (trimmed.startsWith("data:") || trimmed.startsWith("event:")) {
            return parseStream(rawBody);
        }
        return parseJson(objectMapper.readTree(trimmed.isBlank() ? "{}" : trimmed));
    }

    private PlatformCompletionResult parseJson(JsonNode body) {
        StringBuilder content = new StringBuilder();
        List<PlatformToolUse> toolUses = new ArrayList<>();
        for (JsonNode part : body.path("content")) {
            String type = part.path("type").asString("");
            if ("text".equals(type)) {
                content.append(part.path("text").asString(""));
            } else if ("tool_use".equals(type)) {
                toolUses.add(new PlatformToolUse(
                    part.path("id").asString(""),
                    part.path("name").asString(""),
                    objectOrEmpty(part.path("input"))
                ));
            }
        }
        return new PlatformCompletionResult(content.toString(), toolUses, usage(body.path("usage")));
    }

    private PlatformCompletionResult parseStream(String rawBody) throws Exception {
        StringBuilder content = new StringBuilder();
        List<PlatformToolUse> toolUses = new ArrayList<>();
        Map<Integer, PendingToolUse> pendingToolUses = new LinkedHashMap<>();
        long[] tokens = new long[4];

        for (String payload : sseDataPayloads(rawBody)) {
            String data = payload.trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            JsonNode event = objectMapper.readTree(data);
            String type = event.path("type").asString("");
            if ("error".equals(type)) {
                // A 200 response can still carry an error event (e.g. overloaded_error). Treating the partial
                // text as a success would bill the user and report the account healthy.
                throw inBandError(event.path("error"));
            }
            if ("message_start".equals(type)) {
                updateUsage(tokens, event.path("message").path("usage"));
            } else if ("message_delta".equals(type)) {
                updateUsage(tokens, event.path("usage"));
            } else if ("content_block_start".equals(type)) {
                rememberToolUseStart(pendingToolUses, event);
            } else if ("content_block_delta".equals(type)) {
                JsonNode delta = event.path("delta");
                String deltaType = delta.path("type").asString("");
                if ("text_delta".equals(deltaType)) {
                    content.append(delta.path("text").asString(""));
                } else if ("input_json_delta".equals(deltaType)) {
                    pendingToolUses.computeIfAbsent(event.path("index").asInt(0), ignored -> new PendingToolUse())
                        .arguments
                        .append(delta.path("partial_json").asString(""));
                }
            } else if ("content_block_stop".equals(type)) {
                PendingToolUse pending = pendingToolUses.remove(event.path("index").asInt(0));
                if (pending != null && !pending.name.isBlank()) {
                    toolUses.add(pending.toToolUse(objectMapper));
                }
            }
        }
        for (PendingToolUse pending : pendingToolUses.values()) {
            if (!pending.name.isBlank()) {
                toolUses.add(pending.toToolUse(objectMapper));
            }
        }
        return new PlatformCompletionResult(
            content.toString(),
            toolUses,
            usage(tokens[0], tokens[1], tokens[2], tokens[3])
        );
    }

    private UpstreamException inBandError(JsonNode error) {
        String errorType = error.path("type").asString("api_error");
        String message = error.path("message").asString("");
        int status = switch (errorType) {
            case "overloaded_error" -> 529;
            case "rate_limit_error" -> 429;
            case "authentication_error" -> 401;
            case "permission_error" -> 403;
            case "not_found_error" -> 404;
            case "request_too_large" -> 413;
            case "invalid_request_error" -> 400;
            case "timeout_error" -> 504;
            default -> 500;
        };
        return new UpstreamException(
            status,
            "Anthropic upstream error " + status + " (" + errorType + "): " + message,
            isRecoverableStatus(status)
        );
    }

    private void rememberToolUseStart(Map<Integer, PendingToolUse> pendingToolUses, JsonNode event) {
        JsonNode block = event.path("content_block");
        if (!"tool_use".equals(block.path("type").asString(""))) {
            return;
        }
        PendingToolUse pending = pendingToolUses.computeIfAbsent(event.path("index").asInt(0), ignored -> new PendingToolUse());
        pending.id = block.path("id").asString("");
        pending.name = block.path("name").asString("");
        if (block.path("input").isObject() && !block.path("input").isEmpty()) {
            pending.input = block.path("input").deepCopy();
        }
    }

    private List<String> sseDataPayloads(String rawBody) {
        List<String> payloads = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : rawBody.split("\\R", -1)) {
            if (line.isBlank()) {
                flushSseData(payloads, data);
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                data.append(value).append('\n');
            }
        }
        flushSseData(payloads, data);
        return payloads;
    }

    private void flushSseData(List<String> payloads, StringBuilder data) {
        if (data.isEmpty()) {
            return;
        }
        payloads.add(data.toString().stripTrailing());
        data.setLength(0);
    }

    private PlatformUsage usage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return PlatformUsage.zero();
        }
        return usage(
            usage.path("input_tokens").asLong(0),
            usage.path("output_tokens").asLong(0),
            usage.path("cache_read_input_tokens").asLong(0),
            usage.path("cache_creation_input_tokens").asLong(0)
        );
    }

    /**
     * Anthropic reports {@code input_tokens} WITHOUT the cached part. The platform convention (and
     * {@code BillingQuote}) is that inputTokens is the total and the cache counters are subsets of it,
     * so add them back; otherwise cache reads and writes would never be billed.
     */
    private static PlatformUsage usage(long uncachedInput, long output, long cacheRead, long cacheWrite) {
        long cachedRead = Math.max(0, cacheRead);
        long cachedWrite = Math.max(0, cacheWrite);
        return new PlatformUsage(
            Math.max(0, uncachedInput) + cachedRead + cachedWrite,
            Math.max(0, output),
            BigDecimal.ZERO,
            cachedRead,
            cachedWrite,
            0
        );
    }

    private void updateUsage(long[] tokens, JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return;
        }
        if (usage.has("input_tokens")) {
            tokens[0] = usage.path("input_tokens").asLong(tokens[0]);
        }
        if (usage.has("output_tokens")) {
            tokens[1] = usage.path("output_tokens").asLong(tokens[1]);
        }
        if (usage.has("cache_read_input_tokens")) {
            tokens[2] = usage.path("cache_read_input_tokens").asLong(tokens[2]);
        }
        if (usage.has("cache_creation_input_tokens")) {
            tokens[3] = usage.path("cache_creation_input_tokens").asLong(tokens[3]);
        }
    }

    private int maxTokens(JsonNode request) {
        JsonNode tokenLimit = firstExisting(request, "max_tokens", "max_completion_tokens", "max_output_tokens");
        if (tokenLimit.isNumber()) {
            return Math.max(1, tokenLimit.asInt(0));
        }
        return 1024;
    }

    private String systemPrompt(JsonNode request) {
        List<String> prompts = new ArrayList<>();
        String direct = contentText(request.path("system"));
        if (!direct.isBlank()) {
            prompts.add(direct);
        }
        JsonNode messages = request.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                if ("system".equals(message.path("role").asString(""))) {
                    String content = contentText(message.path("content"));
                    if (!content.isBlank()) {
                        prompts.add(content);
                    }
                }
            }
        }
        return String.join("\n", prompts);
    }

    private ArrayNode stopSequences(JsonNode stop) {
        ArrayNode sequences = objectMapper.createArrayNode();
        if (stop == null || stop.isMissingNode() || stop.isNull()) {
            return sequences;
        }
        if (stop.isArray()) {
            for (JsonNode item : stop) {
                if (item.isString()) {
                    sequences.add(item.asString(""));
                }
            }
        } else if (stop.isString()) {
            sequences.add(stop.asString(""));
        }
        return sequences;
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.has(field)) {
            target.set(field, source.get(field).deepCopy());
        }
    }

    private JsonNode firstExisting(JsonNode source, String... fields) {
        if (source == null || !source.isObject()) {
            return objectMapper.missingNode();
        }
        for (String field : fields) {
            JsonNode value = source.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return objectMapper.missingNode();
    }

    private void addText(ArrayNode target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ObjectNode part = target.addObject();
        part.put("type", "text");
        part.put("text", text);
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
            String text = switch (part.path("type").asString("")) {
                case "text", "input_text", "output_text" -> part.path("text").asString("");
                case "refusal" -> part.path("refusal").asString(part.path("text").asString(""));
                case "tool_result" -> contentText(part.path("content"));
                default -> firstNonBlank(part.path("text").asString(null), part.path("content").asString(null));
            };
            if (!text.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private JsonNode parseJsonObject(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (value.isObject()) {
            return value.deepCopy();
        }
        String raw = value.isString() ? value.asString("") : value.toString();
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            return objectOrEmpty(parsed);
        } catch (Exception ignored) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("value", raw);
            return wrapper;
        }
    }

    private JsonNode objectOrEmpty(JsonNode value) {
        return value != null && value.isObject() ? value.deepCopy() : objectMapper.createObjectNode();
    }

    private String anthropicModel(String model) {
        String cleaned = firstNonBlank(model, DEFAULT_TEST_MODEL);
        if (cleaned.toLowerCase(Locale.ROOT).startsWith("starry/")) {
            cleaned = cleaned.substring("starry/".length());
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.startsWith("anthropic/")) {
            cleaned = cleaned.substring("anthropic/".length());
            lower = cleaned.toLowerCase(Locale.ROOT);
        } else if (lower.startsWith("anthropic-compatible/")) {
            cleaned = cleaned.substring("anthropic-compatible/".length());
            lower = cleaned.toLowerCase(Locale.ROOT);
        }
        return switch (lower) {
            case "claude-sonnet-4.5", "claude-sonnet-4-5", "claude-sonnet-latest" -> "claude-sonnet-4-5-20250929";
            case "claude-haiku-4.5", "claude-haiku-4-5", "claude-haiku-latest" -> "claude-haiku-4-5-20251001";
            case "claude-opus-4.5", "claude-opus-4-5", "claude-opus-latest" -> "claude-opus-4-5-20251101";
            default -> cleaned;
        };
    }

    private String withBetaQuery(String endpoint) {
        if (endpoint.contains("?")) {
            return endpoint.matches(".*[?&]beta=.*") ? endpoint : endpoint + "&beta=true";
        }
        return endpoint + "?beta=true";
    }

    private String anthropicApi(String baseUrl, String suffix) {
        String base = clean(baseUrl);
        if (base == null) {
            base = AccountPlatforms.DEFAULT_ANTHROPIC_BASE_URL;
        }
        String normalizedBase = base.replaceAll("/+$", "");
        String normalizedSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
        if (normalizedBase.endsWith(normalizedSuffix)) {
            return withBetaQuery(normalizedBase);
        }
        if (!endsWithVersionSegment(normalizedBase)) {
            return withBetaQuery(normalizedBase + "/v1" + normalizedSuffix);
        }
        return withBetaQuery(normalizedBase + normalizedSuffix);
    }

    private boolean endsWithVersionSegment(String baseUrl) {
        try {
            String path = URI.create(baseUrl).getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return false;
            }
            String trimmed = path.replaceAll("/+$", "");
            int lastSlash = trimmed.lastIndexOf('/');
            String lastSegment = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
            return VERSION_SEGMENT.matcher(lastSegment.toLowerCase(Locale.ROOT)).matches();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void applyAnthropicHeaders(HttpHeaders headers, ProxyAccountEntity account) {
        String token = account.getAccessToken();
        headers.set("x-api-key", token);
        headers.set("anthropic-beta", API_KEY_BETA_HEADER);
        CLAUDE_CODE_API_KEY_HEADERS.forEach(headers::set);
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    }

    private boolean isRecoverableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500;
    }

    private String invalidResponseMessage(String endpoint, String rawBody) {
        String body = rawBody == null || rawBody.isBlank() ? "<empty>" : rawBody;
        return "Anthropic upstream returned invalid response from " + endpoint + ": " + body;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class PendingToolUse {
        private String id = "";
        private String name = "";
        private JsonNode input;
        private final StringBuilder arguments = new StringBuilder();

        private PlatformToolUse toToolUse(ObjectMapper objectMapper) {
            JsonNode parsedInput = input;
            if (parsedInput == null || parsedInput.isMissingNode() || parsedInput.isNull() || parsedInput.isEmpty()) {
                String raw = arguments.toString();
                if (!raw.isBlank()) {
                    try {
                        JsonNode parsed = objectMapper.readTree(raw);
                        parsedInput = parsed.isObject() ? parsed : objectMapper.createObjectNode();
                    } catch (Exception ignored) {
                        ObjectNode wrapper = objectMapper.createObjectNode();
                        wrapper.put("value", raw);
                        parsedInput = wrapper;
                    }
                }
            }
            if (parsedInput == null || !parsedInput.isObject()) {
                parsedInput = objectMapper.createObjectNode();
            }
            return new PlatformToolUse(id, name, parsedInput);
        }
    }
}
