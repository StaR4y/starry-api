package tech.starryapi.platform.xproto;

import java.util.EnumMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a Gemini {@code generateContent} request in one spelling, for the code that reads it for a relay ({@link
 * RequestFeatureDetector}, {@link ChatWireSanitizer#entryDigest} and {@link GeminiChatConverter}), so that none of them has to
 * know there are two (design 7.6, GEMINI). The API takes the names of its fields in camelCase and in snake_case, and the
 * clients use both: the JavaScript SDK writes camelCase throughout, and the Python SDK (google-genai) writes the top level of
 * the request in camelCase and the messages inside it in snake_case ({@code inline_data}, {@code mime_type}, {@code
 * parameters_json_schema}, {@code thinking_budget}); the examples of the REST reference use snake_case everywhere. A reader of one
 * spelling loses the other without a word: a system prompt, an image, the arguments a function takes, a thinking level. The
 * entry's own conversion for the native tiers ({@code GeminiRequestAdapter}) reads camelCase only and is left as it is, since
 * the requests of Kiro and the Anthropic keys must not change (design 0, I4).
 *
 * <p>The names are renamed to camelCase where, and only where, the message they belong to has a field of that name; the
 * values of the fields that hold arbitrary JSON ({@code args}, {@code response}, the schemas, {@code safetySettings}) are
 * not looked into, since a property called {@code min_items} of a function's argument is the client's and not Gemini's. The
 * spelling the API gives is kept when both are written. Where the API has a repeated field and the client wrote one value
 * ({@code contents}, {@code parts}, {@code tools}, {@code functionDeclarations}, {@code stopSequences}), it is a list of one.
 * The request is not changed; the result is a new tree that shares the values it does not look into with it, and so is
 * for reading, not for editing.
 */
public final class GeminiRequestNormalizer {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    /** The messages of the API that are looked into; every other value is taken as it is. */
    private enum Kind {
        REQUEST, CONTENT, PART, BLOB, FILE, CALL, ANSWER, TOOL, DECLARATION, TOOL_CONFIG, CALLING_CONFIG, GENERATION, THINKING,
        RESPONSE_FORMAT, TEXT_FORMAT
    }

    /** A field of a message: the message it holds, if it holds one, and whether it is a repeated field. */
    private record Field(Kind kind, boolean list) {
    }

    private static final Field VALUE = new Field(null, false);
    private static final Field VALUES = new Field(null, true);

    private static Field one(Kind kind) {
        return new Field(kind, false);
    }

    private static Field many(Kind kind) {
        return new Field(kind, true);
    }

    private static final Map<Kind, Map<String, Field>> FIELDS = new EnumMap<>(Kind.class);

    static {
        FIELDS.put(Kind.REQUEST, Map.ofEntries(
            Map.entry("contents", many(Kind.CONTENT)),
            Map.entry("systemInstruction", one(Kind.CONTENT)),
            Map.entry("tools", many(Kind.TOOL)),
            Map.entry("toolConfig", one(Kind.TOOL_CONFIG)),
            Map.entry("generationConfig", one(Kind.GENERATION)),
            Map.entry("thinkingConfig", one(Kind.THINKING)),
            Map.entry("safetySettings", VALUE),
            Map.entry("cachedContent", VALUE),
            Map.entry("continuationToken", VALUE),
            Map.entry("serviceTier", VALUE)));
        FIELDS.put(Kind.CONTENT, Map.of("role", VALUE, "parts", many(Kind.PART)));
        FIELDS.put(Kind.PART, Map.ofEntries(
            Map.entry("text", VALUE),
            Map.entry("thought", VALUE),
            Map.entry("thoughtSignature", VALUE),
            Map.entry("inlineData", one(Kind.BLOB)),
            Map.entry("fileData", one(Kind.FILE)),
            Map.entry("functionCall", one(Kind.CALL)),
            Map.entry("functionResponse", one(Kind.ANSWER)),
            Map.entry("executableCode", VALUE),
            Map.entry("codeExecutionResult", VALUE),
            Map.entry("toolCall", VALUE),
            Map.entry("toolResponse", VALUE),
            Map.entry("videoMetadata", VALUE)));
        FIELDS.put(Kind.BLOB, Map.of("mimeType", VALUE, "data", VALUE, "displayName", VALUE));
        FIELDS.put(Kind.FILE, Map.of("mimeType", VALUE, "fileUri", VALUE, "displayName", VALUE));
        FIELDS.put(Kind.CALL, Map.of("id", VALUE, "name", VALUE, "args", VALUE, "willContinue", VALUE, "partialArgs", VALUE));
        FIELDS.put(Kind.ANSWER, Map.of("id", VALUE, "name", VALUE, "response", VALUE, "parts", many(Kind.PART),
            "willContinue", VALUE, "scheduling", VALUE));
        FIELDS.put(Kind.TOOL, Map.ofEntries(
            Map.entry("functionDeclarations", many(Kind.DECLARATION)),
            Map.entry("googleSearch", VALUE),
            Map.entry("googleSearchRetrieval", VALUE),
            Map.entry("codeExecution", VALUE),
            Map.entry("urlContext", VALUE),
            Map.entry("computerUse", VALUE),
            Map.entry("retrieval", VALUE),
            Map.entry("fileSearch", VALUE),
            Map.entry("googleMaps", VALUE),
            Map.entry("mcpServers", VALUE)));
        FIELDS.put(Kind.DECLARATION, Map.of("name", VALUE, "description", VALUE, "parameters", VALUE,
            "parametersJsonSchema", VALUE, "response", VALUE, "responseJsonSchema", VALUE, "behavior", VALUE));
        FIELDS.put(Kind.TOOL_CONFIG, Map.of("functionCallingConfig", one(Kind.CALLING_CONFIG), "retrievalConfig", VALUE));
        FIELDS.put(Kind.CALLING_CONFIG, Map.of("mode", VALUE, "allowedFunctionNames", VALUES));
        FIELDS.put(Kind.GENERATION, Map.ofEntries(
            Map.entry("temperature", VALUE),
            Map.entry("topP", VALUE),
            Map.entry("topK", VALUE),
            Map.entry("candidateCount", VALUE),
            Map.entry("maxOutputTokens", VALUE),
            Map.entry("stopSequences", VALUES),
            Map.entry("responseMimeType", VALUE),
            Map.entry("responseSchema", VALUE),
            Map.entry("responseJsonSchema", VALUE),
            Map.entry("responseFormat", one(Kind.RESPONSE_FORMAT)),
            Map.entry("presencePenalty", VALUE),
            Map.entry("frequencyPenalty", VALUE),
            Map.entry("seed", VALUE),
            Map.entry("responseLogprobs", VALUE),
            Map.entry("logprobs", VALUE),
            Map.entry("responseModalities", VALUES),
            Map.entry("speechConfig", VALUE),
            Map.entry("thinkingConfig", one(Kind.THINKING)),
            Map.entry("mediaResolution", VALUE)));
        FIELDS.put(Kind.THINKING, Map.of("includeThoughts", VALUE, "thinkingBudget", VALUE, "thinkingLevel", VALUE));
        FIELDS.put(Kind.RESPONSE_FORMAT, Map.of("text", one(Kind.TEXT_FORMAT)));
        FIELDS.put(Kind.TEXT_FORMAT, Map.of("mimeType", VALUE, "schema", VALUE));
    }

    private GeminiRequestNormalizer() {
    }

    /**
     * @param request the {@code generateContent} request as the client sent it
     * @return the same request with the names of the fields of the API in camelCase; the request itself when it is not an
     *     object
     */
    public static JsonNode normalize(JsonNode request) {
        return request != null && request.isObject() ? object(request, Kind.REQUEST) : request;
    }

    private static ObjectNode object(JsonNode source, Kind kind) {
        Map<String, Field> known = FIELDS.get(kind);
        ObjectNode copy = FACTORY.objectNode();
        for (Map.Entry<String, JsonNode> field : source.properties()) {
            String key = field.getKey();
            String name = known.containsKey(key) ? key : camelCase(key);
            if (!known.containsKey(name)) {
                copy.set(key, field.getValue());
            } else if (name.equals(key) || !source.has(name)) {
                copy.set(name, value(field.getValue(), known.get(name)));
            }
        }
        return copy;
    }

    private static JsonNode value(JsonNode value, Field field) {
        if (field.list()) {
            if (value.isArray()) {
                if (field.kind() == null) {
                    return value;
                }
                ArrayNode copy = FACTORY.arrayNode();
                value.forEach(item -> copy.add(item.isObject() ? object(item, field.kind()) : item));
                return copy;
            }
            if (value.isObject() && field.kind() != null) {
                return FACTORY.arrayNode().add(object(value, field.kind()));
            }
            // A client wrote the one string of a list of strings.
            return value.isString() && field.kind() == null ? FACTORY.arrayNode().add(value) : value;
        }
        return field.kind() != null && value.isObject() ? object(value, field.kind()) : value;
    }

    /** {@code inline_data} as {@code inlineData}; a name with no underscore, or a leading or trailing one, as it is. */
    private static String camelCase(String name) {
        if (name.indexOf('_') <= 0 || name.endsWith("_")) {
            return name;
        }
        StringBuilder camel = new StringBuilder(name.length());
        boolean upper = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }
}
