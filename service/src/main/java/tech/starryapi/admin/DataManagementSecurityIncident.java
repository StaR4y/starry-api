package tech.starryapi.admin;

import java.time.Instant;

public record DataManagementSecurityIncident(
    String eventId,
    Type type,
    Severity severity,
    Instant occurredAt,
    String userId,
    String username,
    String actorEmail,
    String actorDisplayName,
    String actorRole,
    Boolean actorEnabled,
    Instant actorCreatedAt,
    Instant actorLastLoginAt,
    String rootUserId,
    String rootEmail,
    String remoteAddress,
    String forwardedFor,
    String userAgent,
    String sessionFingerprint,
    String endpoint,
    String requestId,
    int consecutiveFailures,
    boolean locked,
    String detail
) {

    public enum Type {
        UNLOCK_FAILED,
        SECURITY_LOCKED,
        RECOVERY_FAILED,
        RECOVERY_SUCCEEDED
    }

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }
}
