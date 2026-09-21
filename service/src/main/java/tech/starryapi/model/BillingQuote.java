package tech.starryapi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import tech.starryapi.platform.core.PlatformUsage;

public record BillingQuote(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite,
                           BigDecimal fallback) {
    public BigDecimal estimate(long inputTokens, long maxOutputTokens) {
        return input.max(cacheRead).max(cacheWrite).multiply(BigDecimal.valueOf(inputTokens))
            .add(output.multiply(BigDecimal.valueOf(maxOutputTokens))).setScale(6, RoundingMode.CEILING);
    }

    public BigDecimal cost(PlatformUsage usage) {
        if (usage.totalTokens() <= 0) {
            return fallback.multiply(usage.credits() == null ? BigDecimal.ZERO : usage.credits()).max(BigDecimal.ZERO)
                .setScale(6, RoundingMode.HALF_UP);
        }
        long uncached = Math.max(0, usage.inputTokens() - usage.cacheReadTokens() - usage.cacheWriteTokens());
        return input.multiply(BigDecimal.valueOf(uncached))
            .add(output.multiply(BigDecimal.valueOf(Math.max(0, usage.outputTokens()))))
            .add(cacheRead.multiply(BigDecimal.valueOf(Math.max(0, usage.cacheReadTokens()))))
            .add(cacheWrite.multiply(BigDecimal.valueOf(Math.max(0, usage.cacheWriteTokens()))))
            .setScale(6, RoundingMode.HALF_UP);
    }
}
