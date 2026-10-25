package tech.starryapi.apikey;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class ApiKeyDtos {

    private ApiKeyDtos() {
    }

    /**
     * {@code tierPreference} is the source pool the key asks for when a request names none (design 6.1): {@code auto}
     * (the default), a pool identifier, {@code L<N>} or {@code <N>}, optionally followed by {@code +} for "that pool or
     * any below". It is only checked for its form, and its length with it, by the service (API_KEY_TIER_PREFERENCE_INVALID,
     * so that a value that is too long is refused like every other malformed one and not by the bean validation); a pool
     * that does not exist or cannot be chosen is ignored by the requests that meet it.
     */
    public record CreateApiKeyRequest(
        @NotBlank @Size(max = 128) String name,
        @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.Digits(integer = 14, fraction = 6) BigDecimal creditsLimit,
        @NotBlank @Size(max = 64) String modelProvider,
        String billingSource,
        String tierPreference
    ) {
        public CreateApiKeyRequest(String name, BigDecimal creditsLimit, String modelProvider, String billingSource) {
            this(name, creditsLimit, modelProvider, billingSource, null);
        }

        public CreateApiKeyRequest(String name, BigDecimal creditsLimit, String modelProvider) {
            this(name, creditsLimit, modelProvider, "BALANCE");
        }
    }

    /**
     * {@code tierPreference} as in {@link CreateApiKeyRequest}; omitted it leaves the preference as it is, {@code auto}
     * clears it. A {@code modelProvider} that differs from the key's own resets the preference to {@code auto} unless the
     * request gives one: a preference names a pool of one vendor and does not follow the key to another.
     */
    public record UpdateApiKeyRequest(
        @Size(min = 1, max = 128) String name,
        Boolean enabled,
        @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.Digits(integer = 14, fraction = 6) BigDecimal creditsLimit,
        @Size(max = 64) String modelProvider,
        String billingSource,
        Boolean clearCreditsLimit,
        String tierPreference
    ) {
        public UpdateApiKeyRequest(String name, Boolean enabled, BigDecimal creditsLimit, String modelProvider) {
            this(name, enabled, creditsLimit, modelProvider, null, false);
        }
        public UpdateApiKeyRequest(String name, Boolean enabled, BigDecimal creditsLimit, String modelProvider, String billingSource) {
            this(name, enabled, creditsLimit, modelProvider, billingSource, false);
        }
        public UpdateApiKeyRequest(
            String name,
            Boolean enabled,
            BigDecimal creditsLimit,
            String modelProvider,
            String billingSource,
            Boolean clearCreditsLimit
        ) {
            this(name, enabled, creditsLimit, modelProvider, billingSource, clearCreditsLimit, null);
        }
    }

    /** {@code tierPreference} is left out for a key that has none ({@code auto}), as in {@link ApiKeyResponse}. */
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
        Instant createdAt,
        String tierPreference
    ) {
        /** A key without a source pool preference, which the response leaves out. */
        public CreatedApiKeyResponse(
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
            this(keyId, userId, name, key, keyPrefix, enabled, creditsLimit, modelProvider, billingSource, createdAt, null);
        }
    }

    /** {@code tierPreference} is left out for a key that has none ({@code auto}), as every other field that is not set. */
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
        Instant updatedAt,
        String tierPreference
    ) {
        /** A key without a source pool preference, which the response leaves out. */
        public ApiKeyResponse(
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
            this(keyId, userId, name, key, keyPrefix, enabled, creditsLimit, modelProvider, billingSource, totalRequests,
                totalCredits, totalInputTokens, totalOutputTokens, lastUsedAt, createdAt, updatedAt, null);
        }
    }
}
