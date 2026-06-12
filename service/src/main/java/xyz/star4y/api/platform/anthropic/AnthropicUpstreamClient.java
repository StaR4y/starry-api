package xyz.star4y.api.platform.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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
import xyz.star4y.api.platform.core.PlatformToolUse;
import xyz.star4y.api.platform.core.PlatformUsage;
import xyz.star4y.api.platform.core.ToolSchemaSanitizer;
import xyz.star4y.api.platform.upstream.UpstreamException;

@Component
public class AnthropicUpstreamClient {

    private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+(?:beta)?");
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_TEST_MODEL = "claude-sonnet-4.5";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public AnthropicUpstreamClient(ObjectMapper objectMapper, WebClient kiroWebClient) {
        this.objectMapper = objectMapper;
        this.webClient = kiroWebClient;
    }

    public Mono<PlatformCompletionResult> complete(ProxyAccountEntity account, JsonNode request, String model) {
        String upstreamModel = anthropicModel(model);
        ObjectNode body = anthropicMessagesRequest(request, upstreamModel);
        String endpoint = anthropicApi(account.getBaseUrl(), "/messages");
        return webClient.post()
            .uri(endpoint)
            .headers(headers -> applyAnthropicHeaders(headers, account))
            .bodyValue(body)
            .exchangeToMono(response -> handleResponse(response, endpoint));
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
        String role = source.path("role").asText("user");
        if ("system".equals(role)) {
            return;
        }
        if ("tool".equals(role)) {
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode result = content.addObject();
            result.put("type", "tool_result");
            result.put("tool_use_id", firstNonBlank(source.path("tool_call_id").asText(null), source.path("call_id").asText(null)));
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
        if (content.isTextual()) {
            addText(target, content.asText());
            return;
        }
        if (!content.isArray()) {
            addText(target, content.toString());
            return;
        }
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            switch (type) {
                case "text", "input_text", "output_text" -> addText(target, part.path("text").asText(""));
                case "refusal" -> addText(target, firstNonBlank(part.path("refusal").asText(null), part.path("text").asText(null)));
                case "tool_use" -> {
                    if (assistant) {
                        ObjectNode toolUse = target.addObject();
                        toolUse.put("type", "tool_use");
                        toolUse.put("id", part.path("id").asText());
                        toolUse.put("name", part.path("name").asText());
                        toolUse.set("input", objectOrEmpty(part.path("input")));
                    }
                }
                case "tool_result" -> {
                    ObjectNode toolResult = target.addObject();
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", part.path("tool_use_id").asText());
                    toolResult.put("content", contentText(part.path("content")));
                }
                default -> {
                    String text = firstNonBlank(part.path("text").asText(null), part.path("content").asText(null));
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
            String name = firstNonBlank(function.path("name").asText(null), toolCall.path("name").asText(null));
            if (name.isBlank()) {
                continue;
            }
            ObjectNode toolUse = content.addObject();
            toolUse.put("type", "tool_use");
            toolUse.put("id", firstNonBlank(toolCall.path("id").asText(null), toolCall.path("call_id").asText(null), name));
            toolUse.put("name", name);
            toolUse.set("input", parseJsonObject(function.path("arguments")));
        }
    }

    private ArrayNode anthropicTools(JsonNode tools) {
        ArrayNode converted = objectMapper.createArrayNode();
        for (JsonNode tool : tools) {
            JsonNode function = "function".equals(tool.path("type").asText()) ? tool.path("function") : tool;
            String name = function.path("name").asText(tool.path("name").asText(""));
            if (name.isBlank()) {
                continue;
            }
            ObjectNode convertedTool = converted.addObject();
            convertedTool.put("name", name);
            String description = firstNonBlank(function.path("description").asText(null), tool.path("description").asText(null));
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
        if (toolChoice.isTextual()) {
            String value = toolChoice.asText("").trim().toLowerCase(Locale.ROOT);
            ObjectNode converted = objectMapper.createObjectNode();
            converted.put("type", "required".equals(value) ? "any" : firstNonBlank(value, "auto"));
            return converted;
        }
        if (!toolChoice.isObject()) {
            return toolChoice.deepCopy();
        }
        String type = toolChoice.path("type").asText("");
        if ("function".equals(type)) {
            ObjectNode converted = objectMapper.createObjectNode();
            converted.put("type", "tool");
            converted.put("name", toolChoice.path("function").path("name").asText());
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
                        isRecoverableStatus(status.value())
                    ));
                }
                try {
                    return Mono.just(parseResponse(rawBody));
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
            String type = part.path("type").asText("");
            if ("text".equals(type)) {
                content.append(part.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                toolUses.add(new PlatformToolUse(
                    part.path("id").asText(),
                    part.path("name").asText(),
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
        long[] tokens = new long[2];

        for (String payload : sseDataPayloads(rawBody)) {
            String data = payload.trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            JsonNode event = objectMapper.readTree(data);
            String type = event.path("type").asText("");
            if ("message_start".equals(type)) {
                updateUsage(tokens, event.path("message").path("usage"));
            } else if ("message_delta".equals(type)) {
                updateUsage(tokens, event.path("usage"));
            } else if ("content_block_start".equals(type)) {
                rememberToolUseStart(pendingToolUses, event);
            } else if ("content_block_delta".equals(type)) {
                JsonNode delta = event.path("delta");
                String deltaType = delta.path("type").asText("");
                if ("text_delta".equals(deltaType)) {
                    content.append(delta.path("text").asText(""));
                } else if ("input_json_delta".equals(deltaType)) {
                    pendingToolUses.computeIfAbsent(event.path("index").asInt(), ignored -> new PendingToolUse())
                        .arguments
                        .append(delta.path("partial_json").asText(""));
                }
            } else if ("content_block_stop".equals(type)) {
                PendingToolUse pending = pendingToolUses.remove(event.path("index").asInt());
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
            new PlatformUsage(tokens[0], tokens[1], BigDecimal.ZERO, 0, 0, 0)
        );
    }

    private void rememberToolUseStart(Map<Integer, PendingToolUse> pendingToolUses, JsonNode event) {
        JsonNode block = event.path("content_block");
        if (!"tool_use".equals(block.path("type").asText())) {
            return;
        }
        PendingToolUse pending = pendingToolUses.computeIfAbsent(event.path("index").asInt(), ignored -> new PendingToolUse());
        pending.id = block.path("id").asText();
        pending.name = block.path("name").asText();
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
        long input = usage.path("input_tokens").asLong(0);
        long output = usage.path("output_tokens").asLong(0);
        return new PlatformUsage(input, output, BigDecimal.ZERO, 0, 0, 0);
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
    }

    private int maxTokens(JsonNode request) {
        JsonNode tokenLimit = firstExisting(request, "max_tokens", "max_completion_tokens", "max_output_tokens");
        if (tokenLimit.isNumber()) {
            return Math.max(1, tokenLimit.asInt());
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
                if ("system".equals(message.path("role").asText())) {
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
                if (item.isTextual()) {
                    sequences.add(item.asText());
                }
            }
        } else if (stop.isTextual()) {
            sequences.add(stop.asText());
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
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return content.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : content) {
            String text = switch (part.path("type").asText("")) {
                case "text", "input_text", "output_text" -> part.path("text").asText("");
                case "refusal" -> part.path("refusal").asText(part.path("text").asText(""));
                case "tool_result" -> contentText(part.path("content"));
                default -> firstNonBlank(part.path("text").asText(null), part.path("content").asText(null));
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
        String raw = value.isTextual() ? value.asText() : value.toString();
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
            return cleaned.substring("anthropic/".length());
        }
        if (lower.startsWith("anthropic-compatible/")) {
            return cleaned.substring("anthropic-compatible/".length());
        }
        return cleaned;
    }

    private String anthropicApi(String baseUrl, String suffix) {
        String base = clean(baseUrl);
        if (base == null) {
            base = AccountPlatforms.DEFAULT_ANTHROPIC_BASE_URL;
        }
        String normalizedBase = base.replaceAll("/+$", "");
        String normalizedSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
        if (normalizedBase.endsWith(normalizedSuffix)) {
            return normalizedBase;
        }
        if (!endsWithVersionSegment(normalizedBase)) {
            return normalizedBase + "/v1" + normalizedSuffix;
        }
        return normalizedBase + normalizedSuffix;
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
        headers.set("x-api-key", account.getAccessToken());
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
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
