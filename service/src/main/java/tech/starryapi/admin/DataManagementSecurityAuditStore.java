package tech.starryapi.admin;

import java.time.Instant;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class DataManagementSecurityAuditStore {

    private static final String PERSIST_EVENT_SQL = """
        WITH inserted_audit AS (
            INSERT INTO starry_security.data_management_security_audit_events (
                event_id,
                event_type,
                severity,
                occurred_at,
                actor_user_id,
                actor_username,
                actor_email,
                actor_display_name,
                actor_role,
                actor_enabled,
                actor_created_at,
                actor_last_login_at,
                actor_is_root,
                root_user_id,
                alert_recipient_email,
                remote_address,
                forwarded_address,
                user_agent,
                session_fingerprint,
                endpoint,
                request_id,
                consecutive_failures,
                locked,
                detail
            ) VALUES (
                :eventId,
                :eventType,
                :severity,
                :occurredAt,
                :actorUserId,
                :actorUsername,
                :actorEmail,
                :actorDisplayName,
                :actorRole,
                :actorEnabled,
                :actorCreatedAt,
                :actorLastLoginAt,
                :actorIsRoot,
                :rootUserId,
                :alertRecipientEmail,
                :remoteAddress,
                :forwardedAddress,
                :userAgent,
                :sessionFingerprint,
                :endpoint,
                :requestId,
                :consecutiveFailures,
                :locked,
                :detail
            )
            ON CONFLICT (event_id) DO NOTHING
            RETURNING id, event_id, event_type, alert_recipient_email
        ), selected_audit AS (
            SELECT id, event_id, event_type, alert_recipient_email, TRUE AS inserted
            FROM inserted_audit
            UNION ALL
            SELECT existing.id,
                   existing.event_id,
                   existing.event_type,
                   existing.alert_recipient_email,
                   FALSE AS inserted
            FROM starry_security.data_management_security_audit_events AS existing
            WHERE existing.event_id = :eventId
              AND NOT EXISTS (SELECT 1 FROM inserted_audit)
        ), enqueued_alert AS (
            INSERT INTO starry_security.data_management_security_alert_outbox (
                event_id
            )
            SELECT event_id
            FROM selected_audit
            WHERE event_type IN ('UNLOCK_FAILED', 'SECURITY_LOCKED')
            ON CONFLICT (event_id) DO NOTHING
            RETURNING event_id
        )
        SELECT selected_audit.id,
               selected_audit.inserted,
               EXISTS (
                   SELECT 1
                   FROM enqueued_alert
                   WHERE enqueued_alert.event_id = selected_audit.event_id
               ) AS outbox_enqueued
        FROM selected_audit
        LIMIT 1
        """;

    private final DatabaseClient databaseClient;

    public DataManagementSecurityAuditStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<PersistResult> persist(DataManagementSecurityAuditEntity audit) {
        GenericExecuteSpec spec = databaseClient.sql(PERSIST_EVENT_SQL);
        spec = bind(spec, "eventId", audit.getEventId(), String.class);
        spec = bind(spec, "eventType", audit.getEventType(), String.class);
        spec = bind(spec, "severity", audit.getSeverity(), String.class);
        spec = bind(spec, "occurredAt", audit.getOccurredAt(), Instant.class);
        spec = bind(spec, "actorUserId", audit.getActorUserId(), String.class);
        spec = bind(spec, "actorUsername", audit.getActorUsername(), String.class);
        spec = bind(spec, "actorEmail", audit.getActorEmail(), String.class);
        spec = bind(spec, "actorDisplayName", audit.getActorDisplayName(), String.class);
        spec = bind(spec, "actorRole", audit.getActorRole(), String.class);
        spec = bind(spec, "actorEnabled", audit.getActorEnabled(), Boolean.class);
        spec = bind(spec, "actorCreatedAt", audit.getActorCreatedAt(), Instant.class);
        spec = bind(spec, "actorLastLoginAt", audit.getActorLastLoginAt(), Instant.class);
        spec = bind(spec, "actorIsRoot", audit.getActorIsRoot(), Boolean.class);
        spec = bind(spec, "rootUserId", audit.getRootUserId(), String.class);
        spec = bind(spec, "alertRecipientEmail", audit.getAlertRecipientEmail(), String.class);
        spec = bind(spec, "remoteAddress", audit.getRemoteAddress(), String.class);
        spec = bind(spec, "forwardedAddress", audit.getForwardedAddress(), String.class);
        spec = bind(spec, "userAgent", audit.getUserAgent(), String.class);
        spec = bind(spec, "sessionFingerprint", audit.getSessionFingerprint(), String.class);
        spec = bind(spec, "endpoint", audit.getEndpoint(), String.class);
        spec = bind(spec, "requestId", audit.getRequestId(), String.class);
        spec = bind(spec, "consecutiveFailures", audit.getConsecutiveFailures(), Integer.class);
        spec = bind(spec, "locked", audit.getLocked(), Boolean.class);
        spec = bind(spec, "detail", audit.getDetail(), String.class);
        return spec.map((row, metadata) -> new PersistResult(
                row.get("id", Long.class),
                Boolean.TRUE.equals(row.get("inserted", Boolean.class)),
                Boolean.TRUE.equals(row.get("outbox_enqueued", Boolean.class))
            ))
            .one()
            .switchIfEmpty(Mono.defer(() -> findExisting(audit.getEventId())));
    }

    private Mono<PersistResult> findExisting(String eventId) {
        return databaseClient.sql("""
                SELECT id
                FROM starry_security.data_management_security_audit_events
                WHERE event_id = :eventId
                """)
            .bind("eventId", eventId)
            .map((row, metadata) -> new PersistResult(row.get("id", Long.class), false, false))
            .one()
            .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                "Security audit persistence returned no durable event"
            )));
    }

    private GenericExecuteSpec bind(
        GenericExecuteSpec spec,
        String name,
        Object value,
        Class<?> type
    ) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    public record PersistResult(Long auditId, boolean inserted, boolean outboxEnqueued) {
    }
}
