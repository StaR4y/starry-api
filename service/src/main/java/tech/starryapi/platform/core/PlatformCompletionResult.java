package tech.starryapi.platform.core;

import java.util.List;

public record PlatformCompletionResult(
    String content,
    String reasoningContent,
    List<PlatformToolUse> toolUses,
    PlatformUsage usage
) {
    public PlatformCompletionResult(String content, PlatformUsage usage) {
        this(content, "", List.of(), usage);
    }

    public PlatformCompletionResult(String content, List<PlatformToolUse> toolUses, PlatformUsage usage) {
        this(content, "", toolUses, usage);
    }

    public PlatformCompletionResult {
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
        usage = usage == null ? PlatformUsage.zero() : usage;
    }
}
