package xyz.star4y.api.apikey;

import java.math.BigDecimal;

public record ApiKeyPrincipal(
    String keyId,
    String name,
    BigDecimal creditsLimit,
    BigDecimal totalCredits
) {
}
