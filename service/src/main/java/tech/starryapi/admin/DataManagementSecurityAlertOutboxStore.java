package tech.starryapi.admin;

import io.r2dbc.spi.Row;
import java.time.Instant;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class DataManagementSecurityAlertOutboxStore {

    private static final String CLAIM_SQL = """
        WITH candidates AS (
            SELECT id
            FROM starry_security.data_management_security_alert_outbox
            WHERE sent_at IS NULL
              AND next_attempt_at <= CURRENT_TIMESTAMP
              AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
            ORDER BY next_attempt_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        ), claimed AS (
            UPDATE starry_security.data_management_security_alert_outbox AS outbox
            SET lease_owner = :workerId,
                lease_until = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
                attempt_count = outbox.attempt_count + 1,
                updated_at = CURRENT_TIMESTAMP
            FROM candidates
            WHERE outbox.id = candidates.id
            RETURNING outbox.id,
                      outbox.event_id,
                      outbox.attempt_count
        )
        SELECT claimed.id AS outbox_id,
               claimed.event_id AS outbox_event_id,
               audit.id AS audit_id,
               claimed.attempt_count,
               audit.id AS audit_record_id,
               audit.event_id AS audit_event_id,
               audit.event_type,
               audit.severity,
               audit.occurred_at,
               audit.actor_user_id,
               audit.actor_username,
               audit.actor_email,
               audit.actor_display_name,
               audit.actor_role,
               audit.actor_enabled,
               audit.actor_created_at,
               audit.actor_last_login_at,
               audit.actor_is_root,
               audit.root_user_id,
               audit.alert_recipient_email,
               audit.remote_address,
               audit.forwarded_address,
               audit.user_agent,
               audit.session_fingerprint,
               audit.endpoint,
               audit.request_id,
               audit.consecutive_failures,
               audit.locked,
               audit.detail,
               audit.created_at
        FROM claimed
        JOIN starry_security.data_management_security_audit_events AS audit
          ON audit.event_id = claimed.event_id
        ORDER BY claimed.id
        """;

    private final DatabaseClient databaseClient;

    public DataManagementSecurityAlertOutboxStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Flux<ClaimedAlert> claim(String workerId, int batchSize) {
        int safeBatchSize = Math.max(1, Math.min(100, batchSize));
        return databaseClient.sql(CLAIM_SQL)
            .bind("workerId", workerId)
            .bind("batchSize", safeBatchSize)
            .map((row, metadata) -> new ClaimedAlert(
                row.get("outbox_id", Long.class),
                row.get("outbox_event_id", String.class),
                row.get("audit_id", Long.class),
                number(row, "attempt_count"),
                audit(row)
            ))
            .all();
    }

    public Mono<Boolean> markSent(long outboxId, String workerId) {
        return databaseClient.sql("""
                UPDATE starry_security.data_management_security_alert_outbox
                SET sent_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :outboxId
                  AND lease_owner = :workerId
                  AND sent_at IS NULL
                RETURNING id
                """)
            .bind("outboxId", outboxId)
            .bind("workerId", workerId)
            .map((row, metadata) -> true)
            .one()
            .defaultIfEmpty(false);
    }

    public Mono<Boolean> reschedule(
        long outboxId,
        String workerId,
        Instant nextAttemptAt,
        String reason
    ) {
        return databaseClient.sql("""
                UPDATE starry_security.data_management_security_alert_outbox
                SET next_attempt_at = :nextAttemptAt,
                    lease_owner = NULL,
                    lease_until = NULL,
                    last_error = :reason,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :outboxId
                  AND lease_owner = :workerId
                  AND sent_at IS NULL
                RETURNING id
                """)
            .bind("nextAttemptAt", nextAttemptAt)
            .bind("reason", safeReason(reason))
            .bind("outboxId", outboxId)
            .bind("workerId", workerId)
            .map((row, metadata) -> true)
            .one()
            .defaultIfEmpty(false);
    }

    private static DataManagementSecurityAuditEntity audit(Row row) {
        DataManagementSecurityAuditEntity audit = new DataManagementSecurityAuditEntity();
        audit.setId(row.get("audit_record_id", Long.class));
        audit.setEventId(row.get("audit_event_id", String.class));
        audit.setEventType(row.get("event_type", String.class));
        audit.setSeverity(row.get("severity", String.class));
        audit.setOccurredAt(row.get("occurred_at", Instant.class));
        audit.setActorUserId(row.get("actor_user_id", String.class));
        audit.setActorUsername(row.get("actor_username", String.class));
        audit.setActorEmail(row.get("actor_email", String.class));
        audit.setActorDisplayName(row.get("actor_display_name", String.class));
        audit.setActorRole(row.get("actor_role", String.class));
        audit.setActorEnabled(row.get("actor_enabled", Boolean.class));
        audit.setActorCreatedAt(row.get("actor_created_at", Instant.class));
        audit.setActorLastLoginAt(row.get("actor_last_login_at", Instant.class));
        audit.setActorIsRoot(row.get("actor_is_root", Boolean.class));
        audit.setRootUserId(row.get("root_user_id", String.class));
        audit.setAlertRecipientEmail(row.get("alert_recipient_email", String.class));
        audit.setRemoteAddress(row.get("remote_address", String.class));
        audit.setForwardedAddress(row.get("forwarded_address", String.class));
        audit.setUserAgent(row.get("user_agent", String.class));
        audit.setSessionFingerprint(row.get("session_fingerprint", String.class));
        audit.setEndpoint(row.get("endpoint", String.class));
        audit.setRequestId(row.get("request_id", String.class));
        audit.setConsecutiveFailures(number(row, "consecutive_failures"));
        audit.setLocked(row.get("locked", Boolean.class));
        audit.setDetail(row.get("detail", String.class));
        audit.setCreatedAt(row.get("created_at", Instant.class));
        return audit;
    }

    private static int number(Row row, String column) {
        Object value = row.get(column);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String safeReason(String reason) {
        String normalized = reason == null ? "UNKNOWN" : reason.trim();
        if (!normalized.matches("[A-Z0-9_]{1,128}")) {
            return "UNCLASSIFIED_DELIVERY_FAILURE";
        }
        return normalized;
    }

    public record ClaimedAlert(
        long outboxId,
        String eventId,
        long auditId,
        int attemptCount,
        DataManagementSecurityAuditEntity audit
    ) {
    }
}
