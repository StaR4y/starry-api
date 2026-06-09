package xyz.star4y.api.platform.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class ToolSchemaSanitizer {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "object",
        "array",
        "string",
        "number",
        "integer",
        "boolean",
        "null"
    );

    private ToolSchemaSanitizer() {
    }

    public static ObjectNode sanitizeParameters(ObjectMapper objectMapper, JsonNode schema) {
        ObjectNode sanitized = sanitizeSchema(objectMapper, schema, true);
        if (!sanitized.has("type")) {
            sanitized.put("type", "object");
        }
        if (!sanitized.has("properties")) {
            sanitized.putObject("properties");
        }
        return sanitized;
    }

    private static ObjectNode sanitizeSchema(ObjectMapper objectMapper, JsonNode source, boolean root) {
        ObjectNode sanitized = objectMapper.createObjectNode();
        if (source == null || source.isMissingNode() || source.isNull() || !source.isObject()) {
            if (root) {
                sanitized.put("type", "object");
                sanitized.putObject("properties");
            }
            return sanitized;
        }

        JsonNode type = normalizedType(objectMapper, source.path("type"));
        if (!type.isMissingNode()) {
            sanitized.set("type", type);
        }
        copyText(source, sanitized, "description");
        copyEnum(source, sanitized, "enum");
        copyConstAsEnum(source, sanitized);

        JsonNode properties = source.path("properties");
        if (properties.isObject()) {
            if (!sanitized.has("type")) {
                sanitized.put("type", "object");
            }
            ObjectNode sanitizedProperties = sanitized.putObject("properties");
            properties.fields().forEachRemaining(entry ->
                sanitizedProperties.set(entry.getKey(), sanitizeSchema(objectMapper, entry.getValue(), false))
            );
            copyRequired(source, sanitized, sanitizedProperties);
        }

        JsonNode items = source.path("items");
        if (items.isObject()) {
            if (!sanitized.has("type")) {
                sanitized.put("type", "array");
            }
            sanitized.set("items", sanitizeSchema(objectMapper, items, false));
        } else if (items.isArray() && items.size() > 0) {
            if (!sanitized.has("type")) {
                sanitized.put("type", "array");
            }
            sanitized.set("items", sanitizeSchema(objectMapper, items.get(0), false));
        }

        JsonNode additionalProperties = source.path("additionalProperties");
        if (additionalProperties.isBoolean()) {
            sanitized.set("additionalProperties", additionalProperties.deepCopy());
        } else if (additionalProperties.isObject()) {
            sanitized.set("additionalProperties", sanitizeSchema(objectMapper, additionalProperties, false));
        }

        applyNullable(objectMapper, source, sanitized);
        collapseUnion(objectMapper, source, sanitized);

        if (root && !sanitized.has("properties")) {
            sanitized.putObject("properties");
        }
        return sanitized;
    }

    private static JsonNode normalizedType(ObjectMapper objectMapper, JsonNode type) {
        if (type == null || type.isMissingNode() || type.isNull()) {
            return MissingNode.getInstance();
        }
        if (type.isTextual()) {
            String value = type.asText("").trim().toLowerCase(Locale.ROOT);
            return SUPPORTED_TYPES.contains(value)
                ? objectMapper.getNodeFactory().textNode(value)
                : MissingNode.getInstance();
        }
        if (!type.isArray()) {
            return MissingNode.getInstance();
        }
        ArrayNode values = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : type) {
            if (!item.isTextual()) {
                continue;
            }
            String value = item.asText("").trim().toLowerCase(Locale.ROOT);
            if (SUPPORTED_TYPES.contains(value) && seen.add(value)) {
                values.add(value);
            }
        }
        if (values.size() == 0) {
            return MissingNode.getInstance();
        }
        return values.size() == 1 ? values.get(0) : values;
    }

    private static void copyText(JsonNode source, ObjectNode target, String field) {
        if (source.path(field).isTextual()) {
            target.put(field, source.path(field).asText());
        }
    }

    private static void copyEnum(JsonNode source, ObjectNode target, String field) {
        if (source.path(field).isArray()) {
            target.set(field, source.path(field).deepCopy());
        }
    }

    private static void copyConstAsEnum(JsonNode source, ObjectNode target) {
        if (source.has("const") && !target.has("enum")) {
            ArrayNode values = target.putArray("enum");
            values.add(source.get("const").deepCopy());
        }
    }

    private static void copyRequired(JsonNode source, ObjectNode target, ObjectNode properties) {
        JsonNode required = source.path("required");
        if (!required.isArray()) {
            return;
        }
        ArrayNode sanitizedRequired = target.putArray("required");
        Set<String> seen = new HashSet<>();
        for (JsonNode item : required) {
            if (!item.isTextual()) {
                continue;
            }
            String field = item.asText();
            if (properties.has(field) && seen.add(field)) {
                sanitizedRequired.add(field);
            }
        }
        if (sanitizedRequired.size() == 0) {
            target.remove("required");
        }
    }

    private static void applyNullable(ObjectMapper objectMapper, JsonNode source, ObjectNode target) {
        if (!source.path("nullable").asBoolean(false) || !target.has("type")) {
            return;
        }
        ArrayNode values = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        JsonNode type = target.path("type");
        if (type.isArray()) {
            for (JsonNode item : type) {
                if (item.isTextual() && seen.add(item.asText())) {
                    values.add(item.asText());
                }
            }
        } else if (type.isTextual() && seen.add(type.asText())) {
            values.add(type.asText());
        }
        if (seen.add("null")) {
            values.add("null");
        }
        target.set("type", values);
    }

    private static void collapseUnion(ObjectMapper objectMapper, JsonNode source, ObjectNode target) {
        JsonNode union = source.has("anyOf") ? source.path("anyOf") : source.path("oneOf");
        if (!union.isArray() || union.size() == 0 || target.has("type")) {
            return;
        }
        ArrayNode types = objectMapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        ObjectNode firstSchema = null;
        for (JsonNode option : union) {
            ObjectNode sanitized = sanitizeSchema(objectMapper, option, false);
            JsonNode type = sanitized.path("type");
            if (type.isTextual()) {
                String value = type.asText();
                if (SUPPORTED_TYPES.contains(value) && seen.add(value)) {
                    types.add(value);
                }
            }
            if (firstSchema == null && !"null".equals(type.asText())) {
                firstSchema = sanitized;
            }
        }
        if (types.size() == 0) {
            if (firstSchema != null) {
                target.setAll(firstSchema);
            }
        } else {
            target.set("type", types.size() == 1 ? types.get(0) : types);
        }
    }
}
