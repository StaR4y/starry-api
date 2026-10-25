package tech.starryapi.platform.core;

import java.util.List;

/**
 * One finished completion. {@code stopReason}, {@code usageSource} and {@code degraded} are filled only by a
 * cross-protocol source, which has to say how it stopped, how its usage was obtained and what it could not carry.
 * Every native result leaves them null, and the response factories then render exactly what they rendered before the
 * three components existed.
 *
 * @param stopReason  why generation ended, or null when the platform derives it from the tool uses as it always has
 * @param usageSource whether {@code usage} was reported by the source, estimated or clamped, or null when not tracked
 * @param degraded    names of request features the source dropped, or null when not tracked (empty means none dropped)
 */
public record PlatformCompletionResult(
    String content,
    String reasoningContent,
    List<PlatformToolUse> toolUses,
    PlatformUsage usage,
    StopReason stopReason,
    UsageSource usageSource,
    List<String> degraded
) {
    public PlatformCompletionResult(String content, PlatformUsage usage) {
        this(content, "", List.of(), usage);
    }

    public PlatformCompletionResult(String content, List<PlatformToolUse> toolUses, PlatformUsage usage) {
        this(content, "", toolUses, usage);
    }

    public PlatformCompletionResult(
        String content,
        String reasoningContent,
        List<PlatformToolUse> toolUses,
        PlatformUsage usage
    ) {
        this(content, reasoningContent, toolUses, usage, null, null, null);
    }

    public PlatformCompletionResult {
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
        usage = usage == null ? PlatformUsage.zero() : usage;
        degraded = degraded == null ? null : List.copyOf(degraded);
    }

    /**
     * END_TURN and TOOL_USE render as the platform always rendered a finished turn. MAX_TOKENS and CONTENT_FILTER
     * are the two endings a client has to be told about. There is no stop-sequence value: an OpenAI-format relay
     * does not report which stop sequence ended a turn.
     */
    public enum StopReason {
        END_TURN,
        TOOL_USE,
        MAX_TOKENS,
        CONTENT_FILTER
    }

    public enum UsageSource {
        REPORTED,
        ESTIMATED,
        CLAMPED
    }
}
