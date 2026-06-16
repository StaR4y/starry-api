package tech.starryapi.apikey;

import java.math.BigDecimal;

public record ApiKeyPrincipal(
    String keyId,
    String userId,
    String role,
    String name,
    BigDecimal creditsLimit,
    BigDecimal totalCredits,
    BigDecimal balance
) {

    private static final String USER_ROLE = "USER";

    public ApiKeyPrincipal(String keyId, String name, BigDecimal creditsLimit, BigDecimal totalCredits) {
        this(keyId, null, null, name, creditsLimit, totalCredits, BigDecimal.ZERO);
    }

    public boolean requiresBalance() {
        return userId != null && USER_ROLE.equalsIgnoreCase(role);
    }
}
