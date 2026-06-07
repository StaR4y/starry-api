package xyz.star4y.api.platform.core;

import com.fasterxml.jackson.databind.JsonNode;

public record PlatformToolUse(
    String id,
    String name,
    JsonNode input
) {
}
