package xyz.star4y.api.stats;

import java.math.BigDecimal;
import java.time.Instant;

public final class StatsDtos {

    private StatsDtos() {
    }

    public record RequestLogResponse(
        String requestId,
        String apiKeyId,
        String accountId,
        String path,
        String model,
        Integer statusCode,
        Boolean success,
        Long inputTokens,
        Long outputTokens,
        BigDecimal credits,
        Long latencyMs,
        String errorMessage,
        Instant createdAt
    ) {
    }

    public record PerformanceMetricResponse(
        Double systemCpuLoadPercent,
        Double processCpuLoadPercent,
        Long heapUsedBytes,
        Long heapCommittedBytes,
        Long heapMaxBytes,
        Long nonHeapUsedBytes,
        Long systemMemoryTotalBytes,
        Long systemMemoryFreeBytes,
        Double systemMemoryUsedPercent,
        Long networkRxBytes,
        Long networkTxBytes,
        Double networkRxBytesPerSecond,
        Double networkTxBytesPerSecond,
        Instant sampledAt
    ) {
    }
}
