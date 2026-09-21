package tech.starryapi.apikey;

import java.math.BigDecimal;

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
    String billingSource
) {

    private static final String USER_ROLE = "USER";

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
