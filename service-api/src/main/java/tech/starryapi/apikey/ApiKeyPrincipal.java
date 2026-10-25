package tech.starryapi.apikey;

import java.math.BigDecimal;

/**
 * The caller a request is made as.
 *
 * @param tierPreference the source pool the key's owner prefers (design 6.1), written as a model suffix is: {@code auto},
 *     a pool identifier, {@code L<N>} or {@code <N>}, optionally followed by {@code +}; null for a principal that cannot
 *     have one, which is read as {@code auto}
 */
public record ApiKeyPrincipal(
    String keyId,
    String userId,
    String role,
    String name,
    BigDecimal creditsLimit,
    BigDecimal totalCredits,
    BigDecimal balance,
    String modelProvider,
    boolean hasActiveSubscription,
    String billingSource,
    String tierPreference
) {

    private static final String USER_ROLE = "USER";

    public ApiKeyPrincipal(String keyId, String userId, String role, String name, BigDecimal creditsLimit,
        BigDecimal totalCredits, BigDecimal balance, String modelProvider, boolean hasActiveSubscription,
        String billingSource) {
        this(keyId, userId, role, name, creditsLimit, totalCredits, balance, modelProvider, hasActiveSubscription,
            billingSource, null);
    }

    public ApiKeyPrincipal(String keyId, String userId, String role, String name, BigDecimal creditsLimit,
        BigDecimal totalCredits, BigDecimal balance, String modelProvider, boolean hasActiveSubscription) {
        this(keyId, userId, role, name, creditsLimit, totalCredits, balance, modelProvider, hasActiveSubscription, "BALANCE");
    }

    public boolean usesCodingPlan() { return "CODING_PLAN".equals(billingSource); }

    public ApiKeyPrincipal(String keyId, String name, BigDecimal creditsLimit, BigDecimal totalCredits) {
        this(keyId, null, null, name, creditsLimit, totalCredits, BigDecimal.ZERO, "*", false);
    }

    public ApiKeyPrincipal(
        String keyId,
        String userId,
        String role,
        String name,
        BigDecimal creditsLimit,
        BigDecimal totalCredits,
        BigDecimal balance
    ) {
        this(keyId, userId, role, name, creditsLimit, totalCredits, balance, "*", false);
    }

    public ApiKeyPrincipal(
        String keyId,
        String userId,
        String role,
        String name,
        BigDecimal creditsLimit,
        BigDecimal totalCredits,
        BigDecimal balance,
        String modelProvider
    ) {
        this(keyId, userId, role, name, creditsLimit, totalCredits, balance, modelProvider, false);
    }

    public boolean requiresBalance() {
        return userId != null && USER_ROLE.equalsIgnoreCase(role);
    }
}
