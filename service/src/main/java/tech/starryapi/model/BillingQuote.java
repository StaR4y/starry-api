package tech.starryapi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import tech.starryapi.platform.core.PlatformUsage;

public record BillingQuote(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite,
                           BigDecimal fallback) {
    /** Upper bound of a source-pool price factor, the same range as source_tiers.price_factor: (0, 1000]. */
    private static final BigDecimal MAX_SCALE = BigDecimal.valueOf(1000);

    /**
     * This quote with all five rates multiplied by a source-pool price factor. The multiplication is exact and
     * unrounded, so {@link #estimate} and {@link #cost} round once at the end exactly as they do for any other
     * quote. A factor of 1 returns this instance, which keeps the price of an untiered request the very same object.
     *
     * @throws IllegalArgumentException if the factor is not in (0, 1000]
     */
    public BillingQuote scaled(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        if (factor.signum() <= 0 || factor.compareTo(MAX_SCALE) > 0) {
            throw new IllegalArgumentException("Price factor must be greater than 0 and at most 1000: " + factor);
        }
        if (factor.compareTo(BigDecimal.ONE) == 0) {
            return this;
        }
        return new BillingQuote(input.multiply(factor), output.multiply(factor), cacheRead.multiply(factor),
            cacheWrite.multiply(factor), fallback.multiply(factor));
    }

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
