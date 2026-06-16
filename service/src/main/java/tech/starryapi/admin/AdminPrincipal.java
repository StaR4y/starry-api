package tech.starryapi.admin;

import java.time.Instant;

public record AdminPrincipal(
    String userId,
    String username,
    String role,
    Instant expiresAt,
    boolean firstLogin
) {
}
