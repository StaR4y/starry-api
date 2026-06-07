package xyz.star4y.api.platform.core;

import java.math.BigDecimal;

public record PlatformUsage(
    long inputTokens,
    long outputTokens,
    BigDecimal credits,
    long cacheReadTokens,
    long cacheWriteTokens,
    long reasoningTokens
) {

    public static PlatformUsage zero() {
        return new PlatformUsage(0, 0, BigDecimal.ZERO, 0, 0, 0);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
