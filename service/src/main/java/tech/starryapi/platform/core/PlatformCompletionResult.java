package tech.starryapi.platform.core;

import java.util.List;

public record PlatformCompletionResult(
    String content,
    List<PlatformToolUse> toolUses,
    PlatformUsage usage
) {
    public PlatformCompletionResult(String content, PlatformUsage usage) {
        this(content, List.of(), usage);
    }

    public PlatformCompletionResult {
        content = content == null ? "" : content;
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
        usage = usage == null ? PlatformUsage.zero() : usage;
    }
}
