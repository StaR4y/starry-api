package tech.starryapi.platform.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OpenAiResponseStateStore {

    private static final int MAX_ENTRIES = 512;
    private static final Duration ENTRY_TTL = Duration.ofHours(6);

    private final ObjectMapper objectMapper;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public OpenAiResponseStateStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode expand(JsonNode request) {
        ObjectNode copy = request instanceof ObjectNode object ? object.deepCopy() : objectMapper.createObjectNode();
        String previousResponseId = clean(request.path("previous_response_id").asText(null));
        Entry previous = previousResponseId == null ? null : get(previousResponseId);
        if (previous == null) {
            return copy;
        }

        ArrayNode input = objectMapper.createArrayNode();
        appendAll(input, previous.history);
        appendInput(input, request.path("input"));
        copy.set("input", input);
        copy.remove("previous_response_id");
        copy.put("store", false);
        return copy;
    }

    public void remember(ObjectNode response, JsonNode request) {
        String responseId = clean(response.path("id").asText(null));
        if (responseId == null) {
            return;
        }
        ArrayNode history = objectMapper.createArrayNode();
        appendInput(history, request.path("input"));
        appendAll(history, response.path("output"));
        put(responseId, history);
    }

    private synchronized Entry get(String responseId) {
        pruneExpired();
        return entries.get(responseId);
    }

    private synchronized void put(String responseId, ArrayNode history) {
        pruneExpired();
        entries.remove(responseId);
        entries.put(responseId, new Entry(history.deepCopy(), Instant.now()));
        while (entries.size() > MAX_ENTRIES) {
            Iterator<String> iterator = entries.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
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
        if (input.isTextual()) {
            ObjectNode message = target.addObject();
            message.put("type", "message");
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode text = content.addObject();
            text.put("type", "input_text");
            text.put("text", input.asText());
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

    private synchronized void pruneExpired() {
        Instant cutoff = Instant.now().minus(ENTRY_TTL);
        entries.entrySet().removeIf(entry -> entry.getValue().createdAt.isBefore(cutoff));
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Entry(ArrayNode history, Instant createdAt) {
    }
}
