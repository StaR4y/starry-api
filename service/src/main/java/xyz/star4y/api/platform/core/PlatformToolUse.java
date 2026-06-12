package xyz.star4y.api.platform.core;

import com.fasterxml.jackson.databind.JsonNode;

public record PlatformToolUse(
    String id,
    String name,
    JsonNode input,
    String type,
    String rawInput,
    JsonNode rawOutputItem
) {
    public PlatformToolUse(String id, String name, JsonNode input) {
        this(id, name, input, "function_call", null, null);
    }

    public PlatformToolUse {
        type = type == null || type.isBlank() ? "function_call" : type;
    }
}
