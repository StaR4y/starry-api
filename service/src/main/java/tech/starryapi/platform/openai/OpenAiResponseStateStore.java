package tech.starryapi.platform.openai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the conversation history behind recent Responses API calls so that a later request carrying
 * {@code previous_response_id} can be expanded locally.
 *
 * <p>Every Responses request is remembered, whether or not a client ever chains from it, so the store has to
 * be bounded by bytes and not only by entry count: a Codex-style client sends the whole context each time,
 * and 512 retained contexts of a megabyte each would pin half a gigabyte of heap. Histories are therefore
 * kept as compressed JSON bytes (not as JSON trees), capped per entry and in total, and evicted oldest first.
 */
@Component
public class OpenAiResponseStateStore {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponseStateStore.class);
    private static final Duration ENTRY_TTL = Duration.ofHours(6);
    private static final int MAX_ENTRIES = 512;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final long DEFAULT_MAX_ENTRY_BYTES = 8L * 1024 * 1024;
    /** Rough per-entry bookkeeping cost on top of the compressed payload. */
    private static final long ENTRY_OVERHEAD_BYTES = 256;

    private final ObjectMapper objectMapper;
    private final long maxTotalBytes;
    private final long maxEntryBytes;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long totalBytes;

    public OpenAiResponseStateStore(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_ENTRY_BYTES);
    }

    @Autowired
    public OpenAiResponseStateStore(
        ObjectMapper objectMapper,
        @Value("${starry.responses.state-max-total-bytes:67108864}") long maxTotalBytes,
        @Value("${starry.responses.state-max-entry-bytes:8388608}") long maxEntryBytes
    ) {
        this.objectMapper = objectMapper;
        this.maxTotalBytes = Math.max(1, maxTotalBytes);
        this.maxEntryBytes = Math.max(1, maxEntryBytes);
    }

    public ObjectNode expand(JsonNode request) {
        ObjectNode copy = request instanceof ObjectNode object ? object.deepCopy() : objectMapper.createObjectNode();
        String previousResponseId = clean(request.path("previous_response_id").asString(null));
        Entry previous = previousResponseId == null ? null : get(previousResponseId);
        if (previous == null) {
            return copy;
        }
        JsonNode history = inflate(previous);
        if (history == null) {
            return copy;
        }

        ArrayNode input = objectMapper.createArrayNode();
        appendAll(input, history);
        appendInput(input, request.path("input"));
        copy.set("input", input);
        copy.remove("previous_response_id");
        copy.put("store", false);
        return copy;
    }

    public void remember(ObjectNode response, JsonNode request) {
        remember(response, request, null);
    }

    public void remember(ObjectNode response, JsonNode request, String accountId) {
        remember(response, request, accountId, false);
    }

    /**
     * {@link #remember(ObjectNode, JsonNode, String)} for an answer no OpenAI account gave (a relay's, which has no owner):
     * the reasoning items the gateway wrote for it ({@link OpenAiResponseFactory#isRenderedReasoning}) are left out of the
     * history. The client is given them, but they say nothing the next source can use, and an OpenAI account that is sent
     * one back with {@code store: false} may refuse the request, which is a failure no other tier is asked about.
     */
    public void rememberRelayed(ObjectNode response, JsonNode request) {
        remember(response, request, null, true);
    }

    private void remember(ObjectNode response, JsonNode request, String accountId, boolean withoutRenderedReasoning) {
        String responseId = clean(response.path("id").asString(null));
        if (responseId == null) {
            return;
        }
        ArrayNode history = objectMapper.createArrayNode();
        appendInput(history, request.path("input"));
        appendAll(history, response.path("output"));
        if (withoutRenderedReasoning) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (OpenAiResponseFactory.isRenderedReasoning(history.get(i))) {
                    history.remove(i);
                }
            }
        }
        byte[] packed = deflate(history);
        if (packed == null) {
            return;
        }
        put(responseId, new Entry(packed, clean(accountId), Instant.now()));
    }

    public String ownerAccountId(String responseId) {
        Entry entry = clean(responseId) == null ? null : get(responseId);
        return entry == null ? null : entry.accountId;
    }

    public boolean canExpand(String responseId) {
        return clean(responseId) != null && get(responseId) != null;
    }

    /** Number of bytes currently retained, for tests and diagnostics. */
    public synchronized long retainedBytes() {
        return totalBytes;
    }

    public synchronized int size() {
        return entries.size();
    }

    private byte[] deflate(ArrayNode history) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 * 1024);
             DeflaterOutputStream out = new DeflaterOutputStream(bytes, deflater)) {
            objectMapper.writeValue(out, history);
            out.finish();
            if (bytes.size() > maxEntryBytes) {
                log.debug("Not remembering a Responses history of {} compressed bytes (limit {})", bytes.size(), maxEntryBytes);
                return null;
            }
            return bytes.toByteArray();
        } catch (IOException | JacksonException error) {
            log.debug("Unable to remember a Responses history: {}", error.toString());
            return null;
        } finally {
            deflater.end();
        }
    }

    private JsonNode inflate(Entry entry) {
        try (InflaterInputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(entry.packed))) {
            return objectMapper.readTree(in);
        } catch (IOException | JacksonException error) {
            log.debug("Unable to read a remembered Responses history: {}", error.toString());
            return null;
        }
    }

    private synchronized Entry get(String responseId) {
        pruneExpired();
        return entries.get(responseId);
    }

    private synchronized void put(String responseId, Entry entry) {
        pruneExpired();
        Entry replaced = entries.remove(responseId);
        if (replaced != null) {
            totalBytes -= replaced.weight();
        }
        entries.put(responseId, entry);
        totalBytes += entry.weight();
        while ((entries.size() > MAX_ENTRIES || totalBytes > maxTotalBytes) && entries.size() > 1) {
            Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
            Map.Entry<String, Entry> eldest = iterator.next();
            totalBytes -= eldest.getValue().weight();
            iterator.remove();
        }
    }

    private void appendInput(ArrayNode target, JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return;
        }
        if (input.isArray()) {
            appendAll(target, input);
            return;
        }
        if (input.isString()) {
            ObjectNode message = target.addObject();
            message.put("type", "message");
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "input_text");
            text.put("text", input.asString(""));
            return;
        }
        target.add(input.deepCopy());
    }

    private void appendAll(ArrayNode target, JsonNode items) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            target.add(item.deepCopy());
        }
    }

    private void pruneExpired() {
        Instant cutoff = Instant.now().minus(ENTRY_TTL);
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.createdAt.isBefore(cutoff)) {
                totalBytes -= entry.weight();
                iterator.remove();
            }
        }
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Entry(byte[] packed, String accountId, Instant createdAt) {
        long weight() {
            return packed.length + ENTRY_OVERHEAD_BYTES;
        }
    }
}
