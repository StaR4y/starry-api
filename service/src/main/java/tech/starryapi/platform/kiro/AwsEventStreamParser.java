package tech.starryapi.platform.kiro;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AwsEventStreamParser {

    private final ObjectMapper objectMapper;

    public AwsEventStreamParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PlatformCompletionResult parse(byte[] bytes, String fallbackModel, byte[] requestPayload) {
        StringBuilder content = new StringBuilder();
        ToolUseAccumulator toolUses = new ToolUseAccumulator();
        UsageAccumulator usage = new UsageAccumulator(estimateTokens(requestPayload), 0, BigDecimal.ZERO, 0, 0, 0);
        int offset = 0;
        while (offset + 16 <= bytes.length) {
            int totalLength = readInt(bytes, offset);
            int headersLength = readInt(bytes, offset + 4);
            if (totalLength <= 16 || offset + totalLength > bytes.length) {
                break;
            }
            int payloadStart = offset + 12 + headersLength;
            int payloadEnd = offset + totalLength - 4;
            String messageType = extractHeader(bytes, offset + 12, headersLength, ":message-type");
            if ("exception".equals(messageType) || "error".equals(messageType)) {
                throw frameError(bytes, offset, headersLength, payloadStart, payloadEnd, messageType);
            }
            String eventType = extractEventType(bytes, offset + 12, headersLength);
            if (payloadStart >= 12 && payloadStart < payloadEnd && payloadEnd <= bytes.length) {
                parsePayload(bytes, payloadStart, payloadEnd, content, toolUses, usage, fallbackModel, eventType);
            }
            offset += totalLength;
        }
        if (offset > 0 && offset < bytes.length) {
            // At least one complete frame was read and then the stream stopped mid-frame. Returning what we
            // have would bill a partial answer as a success.
            throw new UpstreamException(502, "Kiro upstream event stream was truncated", true);
        }
        toolUses.finish();
        if (content.isEmpty()) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            JsonNode maybeJson = readJsonOrNull(text);
            if (maybeJson != null) {
                extractContent(maybeJson, content);
                extractUsage(maybeJson, usage, fallbackModel);
            }
        }
        if (usage.outputTokens == 0 && !content.isEmpty()) {
            usage.outputTokens = estimateTokens(content.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (usage.outputTokens == 0 && !toolUses.items().isEmpty()) {
            usage.outputTokens = Math.max(1, toolUses.items().size());
        }
        return new PlatformCompletionResult(content.toString(), toolUses.items(), usage.toUsage());
    }

    private UpstreamException frameError(
        byte[] bytes,
        int offset,
        int headersLength,
        int payloadStart,
        int payloadEnd,
        String messageType
    ) {
        String kind = firstNonBlank(
            extractHeader(bytes, offset + 12, headersLength, ":exception-type"),
            extractHeader(bytes, offset + 12, headersLength, ":error-code")
        );
        String detail = payloadStart >= 12 && payloadStart < payloadEnd && payloadEnd <= bytes.length
            ? new String(bytes, payloadStart, Math.min(payloadEnd - payloadStart, 512), StandardCharsets.UTF_8)
            : "";
        String name = kind == null ? messageType : kind;
        int status = switch (name) {
            case "ThrottlingException", "ServiceQuotaExceededException" -> 429;
            case "ValidationException" -> 400;
            case "AccessDeniedException" -> 403;
            case "ServiceUnavailableException" -> 503;
            default -> 500;
        };
        boolean recoverable = status >= 500 || status == 429;
        return new UpstreamException(status, "Kiro upstream " + messageType + " " + name + ": " + detail, recoverable);
    }

    private void parsePayload(
        byte[] bytes,
        int payloadStart,
        int payloadEnd,
        StringBuilder content,
        ToolUseAccumulator toolUses,
        UsageAccumulator usage,
        String fallbackModel,
        String eventType
    ) {
        String payloadText = new String(bytes, payloadStart, payloadEnd - payloadStart, StandardCharsets.UTF_8);
        JsonNode json = readJsonOrNull(payloadText);
        if (json == null) {
            return;
        }
        extractContent(json, content, eventType);
        extractToolUse(json, toolUses, eventType);
        extractUsage(json, usage, fallbackModel, eventType);
    }

    private void extractContent(JsonNode event, StringBuilder content) {
        extractContent(event, content, "");
    }

    private void extractContent(JsonNode event, StringBuilder content, String eventType) {
        JsonNode assistant = firstExisting(event, "assistantResponseEvent", "codeEvent");
        if (assistant == null && isEventType(eventType, "assistantResponseEvent", "codeEvent")) {
            assistant = event;
        }
        if (assistant != null && assistant.has("content")) {
            content.append(assistant.path("content").asString(""));
        }
    }

    private void extractToolUse(JsonNode event, ToolUseAccumulator toolUses, String eventType) {
        JsonNode toolUse = firstExisting(event, "toolUseEvent");
        if (toolUse == null && isEventType(eventType, "toolUseEvent")) {
            toolUse = event;
        }
        if (toolUse == null) {
            return;
        }
        String id = firstNonBlank(toolUse.path("toolUseId").asString(null), toolUse.path("id").asString(null));
        String name = firstNonBlank(toolUse.path("name").asString(null), toolUse.path("toolName").asString(null));
        if (id != null && name != null) {
            toolUses.start(id, name);
        }
        JsonNode input = toolUse.path("input");
        if (!input.isMissingNode() && !input.isNull()) {
            toolUses.appendInput(input);
        }
        if (toolUse.path("stop").asBoolean(false)) {
            toolUses.finish();
        }
    }

    private void extractUsage(JsonNode event, UsageAccumulator usage, String fallbackModel) {
        extractUsage(event, usage, fallbackModel, "");
    }

    private void extractUsage(JsonNode event, UsageAccumulator usage, String fallbackModel, String eventType) {
        JsonNode metadata = firstExisting(event, "messageMetadataEvent", "metadataEvent");
        if (metadata == null && isEventType(eventType, "messageMetadataEvent", "metadataEvent")) {
            metadata = event;
        }
        if (metadata != null) {
            JsonNode tokenUsage = metadata.path("tokenUsage");
            if (!tokenUsage.isMissingNode()) {
                long uncached = tokenUsage.path("uncachedInputTokens").asLong(0);
                long cacheRead = tokenUsage.path("cacheReadInputTokens").asLong(0);
                long cacheWrite = tokenUsage.path("cacheWriteInputTokens").asLong(0);
                long input = uncached + cacheRead + cacheWrite;
                if (input > 0) usage.inputTokens = input;
                if (tokenUsage.has("outputTokens")) usage.outputTokens = tokenUsage.path("outputTokens").asLong(0);
                usage.cacheReadTokens = cacheRead;
                usage.cacheWriteTokens = cacheWrite;
                return;
            }
            if (metadata.has("inputTokens")) usage.inputTokens = metadata.path("inputTokens").asLong(0L);
            if (metadata.has("outputTokens")) usage.outputTokens = metadata.path("outputTokens").asLong(0L);
        }
        JsonNode usageEvent = firstExisting(event, "usageEvent", "usage");
        if (usageEvent == null && isEventType(eventType, "usageEvent", "usage")) {
            usageEvent = event;
        }
        if (usageEvent != null) {
            if (usageEvent.has("inputTokens")) usage.inputTokens = usageEvent.path("inputTokens").asLong(0L);
            if (usageEvent.has("outputTokens")) usage.outputTokens = usageEvent.path("outputTokens").asLong(0L);
        }
        JsonNode metering = event.path("meteringEvent");
        if (!metering.isMissingNode() && metering.has("usage")) {
            usage.credits = usage.credits.add(metering.path("usage").decimalValue(BigDecimal.ZERO));
        }
        JsonNode contextUsage = event.path("contextUsageEvent");
        if (!contextUsage.isMissingNode() && usage.inputTokens == 0 && contextUsage.has("contextUsagePercentage")) {
            usage.inputTokens = Math.round(contextWindow(fallbackModel) * contextUsage.path("contextUsagePercentage").asDouble(0.0) / 100.0);
        }
    }

    private boolean isEventType(String eventType, String... candidates) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        for (String candidate : candidates) {
            if (eventType.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private JsonNode firstExisting(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String extractEventType(byte[] bytes, int headersStart, int headersLength) {
        return extractHeader(bytes, headersStart, headersLength, ":event-type");
    }

    private String extractHeader(byte[] bytes, int headersStart, int headersLength, String headerName) {
        int offset = headersStart;
        int end = headersStart + headersLength;
        while (offset < end) {
            int nameLength = Byte.toUnsignedInt(bytes[offset++]);
            if (offset + nameLength > end) {
                break;
            }
            String name = new String(bytes, offset, nameLength, StandardCharsets.UTF_8);
            offset += nameLength;
            if (offset >= end) {
                break;
            }
            int valueType = Byte.toUnsignedInt(bytes[offset++]);
            if (valueType == 7) {
                if (offset + 2 > end) {
                    break;
                }
                int valueLength = Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort());
                offset += 2;
                if (offset + valueLength > end) {
                    break;
                }
                String value = new String(bytes, offset, valueLength, StandardCharsets.UTF_8);
                offset += valueLength;
                if (headerName.equals(name)) {
                    return value;
                }
                continue;
            }
            offset += headerValueSize(bytes, offset, end, valueType);
        }
        return "";
    }

    private int headerValueSize(byte[] bytes, int offset, int end, int valueType) {
        return switch (valueType) {
            case 0, 1 -> 0;
            case 2 -> 1;
            case 3 -> 2;
            case 4 -> 4;
            case 5, 8 -> 8;
            case 9 -> 16;
            case 6 -> {
                if (offset + 2 > end) {
                    yield end - offset;
                }
                int valueLength = Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort());
                yield Math.min(2 + valueLength, end - offset);
            }
            default -> end - offset;
        };
    }

    private JsonNode readJsonOrNull(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private int readInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private long estimateTokens(byte[] bytes) {
        return Math.max(1, Math.round(bytes.length / 3.5));
    }

    private long contextWindow(String model) {
        if (model != null && model.toLowerCase().contains("haiku")) {
            return 200_000;
        }
        return 200_000;
    }

    private final class ToolUseAccumulator {
        private final List<PlatformToolUse> items = new ArrayList<>();
        private String id;
        private String name;
        private StringBuilder input = new StringBuilder();

        private void start(String nextId, String nextName) {
            if (id != null && !id.equals(nextId)) {
                finish();
            }
            id = nextId;
            name = nextName;
        }

        private void appendInput(JsonNode inputNode) {
            if (id == null) {
                return;
            }
            if (inputNode.isString()) {
                input.append(inputNode.asString(""));
                return;
            }
            input = new StringBuilder(inputNode.toString());
        }

        private void finish() {
            if (id == null || name == null) {
                return;
            }
            items.add(new PlatformToolUse(id, name, parseInput(input.toString())));
            id = null;
            name = null;
            input = new StringBuilder();
        }

        private List<PlatformToolUse> items() {
            return List.copyOf(items);
        }

        private JsonNode parseInput(String text) {
            if (text == null || text.isBlank()) {
                return objectMapper.createObjectNode();
            }
            JsonNode parsed = readJsonOrNull(text);
            if (parsed != null) {
                return parsed;
            }
            return objectMapper.createObjectNode().put("_partialInput", text);
        }
    }

    private static final class UsageAccumulator {
        private long inputTokens;
        private long outputTokens;
        private BigDecimal credits;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private long reasoningTokens;

        private UsageAccumulator(
            long inputTokens,
            long outputTokens,
            BigDecimal credits,
            long cacheReadTokens,
            long cacheWriteTokens,
            long reasoningTokens
        ) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.credits = credits;
            this.cacheReadTokens = cacheReadTokens;
            this.cacheWriteTokens = cacheWriteTokens;
            this.reasoningTokens = reasoningTokens;
        }

        private PlatformUsage toUsage() {
            return new PlatformUsage(inputTokens, outputTokens, credits, cacheReadTokens, cacheWriteTokens, reasoningTokens);
        }
    }
}
