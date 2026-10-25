package tech.starryapi.stats;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
        Instant createdAt,
        String sourceTierRef,
        Integer sourceTierLevel,
        String sourceTierName,
        BigDecimal priceFactor,
        String tierSelection,
        String sourceDispatch,
        String wireModel,
        String usageSource,
        String stopReason,
        String degradedFeatures
    ) {
        /**
         * A row of a request that was not served from source tiers, or a row a user reads: the source columns are
         * left out of the JSON when they are null.
         */
        public RequestLogResponse(
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
            this(requestId, apiKeyId, accountId, path, model, statusCode, success, inputTokens, outputTokens, credits,
                latencyMs, errorMessage, createdAt, null, null, null, null, null, null, null, null, null, null);
        }
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

    public record UsageSummaryResponse(
        Long totalRequests,
        Long successfulRequests,
        Long failedRequests,
        Long totalInputTokens,
        Long totalOutputTokens,
        Long totalTokens,
        BigDecimal totalCredits,
        Instant activeWindowStartedAt,
        Integer activeUserCount,
        List<ActiveUserResponse> activeUsers,
        List<ModelUsageResponse> models
    ) {
    }

    public record ActiveUserResponse(
        String userId,
        String username,
        String displayName,
        String email,
        Long requestCount,
        Long apiKeyCount,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        BigDecimal credits,
        Instant lastActiveAt
    ) {
    }

    public record ModelUsageResponse(
        String model,
        Long requestCount,
        Long successfulRequests,
        Long failedRequests,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        BigDecimal credits,
        Instant lastUsedAt
    ) {
    }

    public record UsageDayResponse(
        String day,
        Long requestCount,
        Long successfulRequests,
        Long failedRequests,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        BigDecimal credits,
        Long peakRequests,
        Long offPeakRequests,
        BigDecimal peakCredits,
        BigDecimal offPeakCredits
    ) {
    }

    public record UsageTrackerResponse(
        Instant from,
        Instant to,
        Long totalRequests,
        Long successfulRequests,
        Long failedRequests,
        Long totalInputTokens,
        Long totalOutputTokens,
        Long totalTokens,
        BigDecimal totalCredits,
        Long peakRequests,
        Long offPeakRequests,
        BigDecimal peakCredits,
        BigDecimal offPeakCredits,
        List<ModelUsageResponse> models,
        List<UsageDayResponse> days
    ) {
    }

    public record RequestLogPageResponse(
        List<RequestLogResponse> items,
        boolean hasMore,
        Instant nextBefore
    ) {
    }
}
