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
}
