package tech.starryapi.apikey;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class ApiKeyDtos {

    private ApiKeyDtos() {
    }

    public record CreateApiKeyRequest(
        @NotBlank @Size(max = 128) String name,
        @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.Digits(integer = 14, fraction = 6) BigDecimal creditsLimit,
        @NotBlank @Size(max = 64) String modelProvider,
        String billingSource
    ) {
        public CreateApiKeyRequest(String name, BigDecimal creditsLimit, String modelProvider) {
            this(name, creditsLimit, modelProvider, "BALANCE");
        }
    }

    public record UpdateApiKeyRequest(
        @Size(min = 1, max = 128) String name,
        Boolean enabled,
        @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.Digits(integer = 14, fraction = 6) BigDecimal creditsLimit,
        @Size(max = 64) String modelProvider,
        String billingSource,
        Boolean clearCreditsLimit
    ) {
        public UpdateApiKeyRequest(String name, Boolean enabled, BigDecimal creditsLimit, String modelProvider) {
            this(name, enabled, creditsLimit, modelProvider, null, false);
        }
        public UpdateApiKeyRequest(String name, Boolean enabled, BigDecimal creditsLimit, String modelProvider, String billingSource) {
            this(name, enabled, creditsLimit, modelProvider, billingSource, false);
        }
    }

    public record CreatedApiKeyResponse(
        String keyId,
        String userId,
        String name,
        String key,
        String keyPrefix,
        Boolean enabled,
        BigDecimal creditsLimit,
        String modelProvider,
        String billingSource,
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
        String modelProvider,
        String billingSource,
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
