package tech.starryapi.apikey;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;

public final class ApiKeyDtos {

    private ApiKeyDtos() {
    }

    public record CreateApiKeyRequest(
        @NotBlank String name,
        BigDecimal creditsLimit
    ) {
    }

    public record UpdateApiKeyRequest(
        String name,
        Boolean enabled,
        BigDecimal creditsLimit
    ) {
    }

    public record CreatedApiKeyResponse(
        String keyId,
        String userId,
        String name,
        String key,
        String keyPrefix,
        Boolean enabled,
        BigDecimal creditsLimit,
        Instant createdAt
    ) {
    }

    public record ApiKeyResponse(
        String keyId,
        String userId,
        String name,
        String key,
        String keyPrefix,
        Boolean enabled,
        BigDecimal creditsLimit,
        Long totalRequests,
        BigDecimal totalCredits,
        Long totalInputTokens,
        Long totalOutputTokens,
        Instant lastUsedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
