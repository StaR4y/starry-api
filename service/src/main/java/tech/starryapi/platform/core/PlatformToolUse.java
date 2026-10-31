package tech.starryapi.platform.core;

import tools.jackson.databind.JsonNode;

/**
 * One call a model made. {@code thoughtSignature} is the opaque signature a Gemini model puts on a call (design 7.6, 7.10):
 * only a cross-protocol source can carry one (an OpenAI-format relay in front of Gemini gives it as the {@code
 * extra_content.google.thought_signature} of the call), every native result leaves it null, and the renderers then write
 * what they always wrote. The client has to give it back with the call in its next request, which is how the relay's model
 * gets it back.
 */
public record PlatformToolUse(
    String id,
    String name,
    JsonNode input,
    String type,
    String rawInput,
    JsonNode rawOutputItem,
    String thoughtSignature
) {
    /** The longest signature that is carried, in characters. Real ones are a few kilobytes; a longer one is not a signature. */
    public static final int MAX_THOUGHT_SIGNATURE_LENGTH = 64 * 1024;

    public PlatformToolUse(String id, String name, JsonNode input) {
        this(id, name, input, "function_call", null, null);
    }

    public PlatformToolUse(String id, String name, JsonNode input, String type, String rawInput, JsonNode rawOutputItem) {
        this(id, name, input, type, rawInput, rawOutputItem, null);
    }

    public PlatformToolUse {
        type = type == null || type.isBlank() ? "function_call" : type;
        thoughtSignature = thoughtSignature == null || thoughtSignature.isBlank() ? null : thoughtSignature;
    }
}
