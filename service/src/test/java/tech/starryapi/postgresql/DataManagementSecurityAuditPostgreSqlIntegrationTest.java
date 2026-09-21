package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import tech.starryapi.admin.DataManagementSecurityAlertOutboxStore;
import tech.starryapi.admin.DataManagementSecurityAuditEntity;
import tech.starryapi.admin.DataManagementSecurityAuditStore;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.lazy-initialization=true",
        "spring.jmx.enabled=false",
        "spring.task.scheduling.enabled=false",
        "starry.stats.aggregation-enabled=false"
    }
)
@Timeout(value = 3, unit = java.util.concurrent.TimeUnit.MINUTES)
class DataManagementSecurityAuditPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private DataManagementSecurityAuditStore auditStore;

    @Autowired
    private DataManagementSecurityAlertOutboxStore outboxStore;

    @Autowired
    private DatabaseClient databaseClient;

    @Test
    void migrationCreatesAtomicIdempotentOutboxAndLeastPrivilegeAuditStorage() {
        DataManagementSecurityAuditEntity audit = audit("integration-audit-" + System.nanoTime());

        DataManagementSecurityAuditStore.PersistResult first = auditStore.persist(audit).block();
        audit.setAlertRecipientEmail("changed@example.com");
        DataManagementSecurityAuditStore.PersistResult duplicate = auditStore.persist(audit).block();

        assertThat(first).isNotNull();
        assertThat(first.auditId()).isNotNull();
        assertThat(first.inserted()).isTrue();
        assertThat(first.outboxEnqueued()).isTrue();
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.auditId()).isEqualTo(first.auditId());
        assertThat(duplicate.inserted()).isFalse();
        assertThat(duplicate.outboxEnqueued()).isFalse();

        Map<String, Object> state = databaseClient.sql("""
                SELECT EXISTS (
                           SELECT 1
                           FROM starry_api.flyway_schema_history
                           WHERE version = '4' AND success
                       ) AS migration_applied,
                       pg_catalog.to_regclass(
                           'starry_api.data_management_security_audit_events'
                       ) IS NULL AS old_audit_removed,
                       pg_catalog.has_table_privilege(
                           current_user,
                           'starry_security.data_management_security_audit_events',
                           'SELECT'
                       ) AS audit_can_select,
                       pg_catalog.has_table_privilege(
                           current_user,
                           'starry_security.data_management_security_audit_events',
                           'INSERT'
                       ) AS audit_can_insert,
                       NOT pg_catalog.has_table_privilege(
                           current_user,
                           'starry_security.data_management_security_audit_events',
                           'UPDATE'
                       ) AS audit_cannot_update,
                       NOT pg_catalog.has_table_privilege(
                           current_user,
                           'starry_security.data_management_security_audit_events',
                           'DELETE'
                       ) AS audit_cannot_delete,
                       pg_catalog.has_table_privilege(
                           current_user,
                           'starry_security.data_management_security_alert_outbox',
                           'SELECT'
                       ) AS outbox_can_select,
                       pg_catalog.has_any_column_privilege(
                           current_user,
                           'starry_security.data_management_security_alert_outbox',
                           'INSERT'
                       ) AS outbox_can_insert,
                       pg_catalog.has_any_column_privilege(
                           current_user,
                           'starry_security.data_management_security_alert_outbox',
                           'UPDATE'
                       ) AS outbox_can_update,
                       NOT pg_catalog.has_table_privilege(
                           current_user,
                           'starry_security.data_management_security_alert_outbox',
                           'DELETE'
                       ) AS outbox_cannot_delete,
                       NOT EXISTS (
                           SELECT 1
                           FROM information_schema.columns
                           WHERE table_schema = 'starry_security'
                             AND table_name IN (
                                 'data_management_security_audit_events',
                                 'data_management_security_alert_outbox'
                             )
                             AND column_name IN (
                                 'password',
                                 'password_hash',
                                 'password_salt',
                                 'security_key',
                                 'security_key_hash',
                                 'security_key_verifier',
                                 'key_hash',
                                 'submitted_value',
                                 'session_token',
                                 'access_token'
                             )
                       ) AS no_secret_columns
                """)
            .fetch()
            .one()
            .block();
        assertThat(state)
            .containsEntry("migration_applied", true)
            .containsEntry("old_audit_removed", true)
            .containsEntry("audit_can_select", true)
            .containsEntry("audit_can_insert", true)
            .containsEntry("audit_cannot_update", true)
            .containsEntry("audit_cannot_delete", true)
            .containsEntry("outbox_can_select", true)
            .containsEntry("outbox_can_insert", true)
            .containsEntry("outbox_can_update", true)
            .containsEntry("outbox_cannot_delete", true)
            .containsEntry("no_secret_columns", true);

        Map<String, Object> rows = databaseClient.sql("""
                SELECT (SELECT count(*)
                        FROM starry_security.data_management_security_audit_events
                        WHERE event_id = :eventId) AS audit_count,
                       (SELECT count(*)
                        FROM starry_security.data_management_security_alert_outbox
                        WHERE event_id = :eventId) AS outbox_count,
                       (SELECT alert_recipient_email
                        FROM starry_security.data_management_security_audit_events
                        WHERE event_id = :eventId) AS recipient
                """)
            .bind("eventId", audit.getEventId())
            .fetch()
            .one()
            .block();
        assertThat(((Number) rows.get("audit_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) rows.get("outbox_count")).longValue()).isEqualTo(1L);
        assertThat(rows.get("recipient")).isEqualTo("root@example.com");

        assertThatThrownBy(() -> executeAsDatabaseOwner("""
            UPDATE starry_security.data_management_security_audit_events
            SET detail = 'mutation must fail'
            WHERE id = %d
            """.formatted(first.auditId())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owner cleanup");
    }

    @Test
    void activeLeasePreventsDuplicateClaimUntilReleased() {
        DataManagementSecurityAuditEntity audit = audit("integration-claim-" + System.nanoTime());
        auditStore.persist(audit).block();

        var firstClaim = outboxStore.claim("worker-one", 100)
            .filter(claimed -> audit.getEventId().equals(claimed.eventId()))
            .single()
            .block();
        var duplicateClaim = outboxStore.claim("worker-two", 100)
            .filter(claimed -> audit.getEventId().equals(claimed.eventId()))
            .collectList()
            .block();

        assertThat(firstClaim).isNotNull();
        assertThat(firstClaim.attemptCount()).isEqualTo(1);
        assertThat(duplicateClaim).isEmpty();
        assertThat(outboxStore.reschedule(
            firstClaim.outboxId(),
            "worker-one",
            Instant.now(),
            "SMTP_NOT_CONFIGURED"
        ).block()).isTrue();
    }

    @Test
    void missingSnapshotRecipientStillCreatesRetryableOutboxEntry() {
        DataManagementSecurityAuditEntity audit = audit("integration-missing-recipient-" + System.nanoTime());
        audit.setAlertRecipientEmail(null);

        DataManagementSecurityAuditStore.PersistResult persisted = auditStore.persist(audit).block();

        assertThat(persisted).isNotNull();
        assertThat(persisted.inserted()).isTrue();
        assertThat(persisted.outboxEnqueued()).isTrue();
        Map<String, Object> rows = databaseClient.sql("""
                SELECT (SELECT count(*)
                        FROM starry_security.data_management_security_audit_events
                        WHERE event_id = :eventId) AS audit_count,
                       (SELECT count(*)
                        FROM starry_security.data_management_security_alert_outbox
                        WHERE event_id = :eventId) AS outbox_count
                """)
            .bind("eventId", audit.getEventId())
            .fetch()
            .one()
            .block();
        assertThat(((Number) rows.get("audit_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) rows.get("outbox_count")).longValue()).isEqualTo(1L);
    }

    private DataManagementSecurityAuditEntity audit(String eventId) {
        DataManagementSecurityAuditEntity audit = new DataManagementSecurityAuditEntity();
        audit.setEventId(eventId);
        audit.setEventType("UNLOCK_FAILED");
        audit.setSeverity("WARNING");
        audit.setOccurredAt(Instant.now());
        audit.setActorUserId("admin-1");
        audit.setActorUsername("admin");
        audit.setActorRole("ADMIN");
        audit.setActorEnabled(true);
        audit.setActorIsRoot(true);
        audit.setRootUserId("admin-1");
        audit.setAlertRecipientEmail("root@example.com");
        audit.setRemoteAddress("192.0.2.10");
        audit.setSessionFingerprint("a".repeat(64));
        audit.setEndpoint("/admin/data-transfer/security/unlock");
        audit.setRequestId("integration-request");
        audit.setConsecutiveFailures(1);
        audit.setLocked(false);
        audit.setDetail("Data management security key verification failed");
        return audit;
    }
}
