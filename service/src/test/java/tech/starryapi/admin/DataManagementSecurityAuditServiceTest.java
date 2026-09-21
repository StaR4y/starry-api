package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tech.starryapi.common.Hashing;

class DataManagementSecurityAuditServiceTest {

    @Test
    void persistsSanitizedActorAndRequestSnapshotWithAtomicOutboxIntent() {
        DataManagementSecurityAuditStore auditStore = mock(DataManagementSecurityAuditStore.class);
        when(auditStore.persist(any(DataManagementSecurityAuditEntity.class)))
            .thenReturn(Mono.just(new DataManagementSecurityAuditStore.PersistResult(7L, true, true)));

        DataManagementSecurityIncident incident = incident(
            "incident-1",
            DataManagementSecurityIncident.Type.UNLOCK_FAILED,
            "user-2",
            "operator",
            1,
            false
        );

        service(auditStore).handle(incident).block();

        ArgumentCaptor<DataManagementSecurityAuditEntity> auditCaptor =
            ArgumentCaptor.forClass(DataManagementSecurityAuditEntity.class);
        verify(auditStore).persist(auditCaptor.capture());
        DataManagementSecurityAuditEntity audit = auditCaptor.getValue();
        assertThat(audit.getActorUserId()).isEqualTo("user-2");
        assertThat(audit.getActorUsername()).isEqualTo("operator");
        assertThat(audit.getActorEmail()).isEqualTo("operator@example.com");
        assertThat(audit.getActorDisplayName()).isEqualTo("Operations");
        assertThat(audit.getActorRole()).isEqualTo("ADMIN");
        assertThat(audit.getActorEnabled()).isTrue();
        assertThat(audit.getActorCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:02Z"));
        assertThat(audit.getActorLastLoginAt()).isEqualTo(Instant.parse("2026-08-01T00:00:02Z"));
        assertThat(audit.getActorIsRoot()).isFalse();
        assertThat(audit.getRootUserId()).isEqualTo("root-1");
        assertThat(audit.getAlertRecipientEmail()).isEqualTo("root@example.com");
        assertThat(audit.getRemoteAddress()).isEqualTo("192.0.2.10");
        assertThat(audit.getForwardedAddress()).isEqualTo("198.51.100.9, 192.0.2.10");
        assertThat(audit.getUserAgent()).isEqualTo(
            DataManagementSecuritySanitizer.fingerprint("security-test-agent")
        );
        assertThat(audit.getSessionFingerprint()).isEqualTo(Hashing.sha256("raw-session-value"));
        assertThat(audit.getEndpoint()).isEqualTo("/admin/data-transfer/security/unlock");
        assertThat(audit.getRequestId()).isEqualTo(
            DataManagementSecuritySanitizer.fingerprint("request-1")
        );
        assertThat(audit.getConsecutiveFailures()).isEqualTo(1);
        assertThat(audit.getLocked()).isFalse();
        assertThat(audit.getDetail())
            .isEqualTo("Data management security key verification failed")
            .doesNotContain("dmsk-secret", "must-never-be-stored");
    }

    @Test
    void duplicateEventIsAnIdempotentSuccessfulPersistence() {
        DataManagementSecurityAuditStore auditStore = mock(DataManagementSecurityAuditStore.class);
        when(auditStore.persist(any(DataManagementSecurityAuditEntity.class)))
            .thenReturn(Mono.just(new DataManagementSecurityAuditStore.PersistResult(7L, false, false)));

        service(auditStore)
            .handle(incident(
                "incident-duplicate",
                DataManagementSecurityIncident.Type.UNLOCK_FAILED,
                "user-2",
                "operator",
                1,
                false
            ))
            .block();

        verify(auditStore).persist(any(DataManagementSecurityAuditEntity.class));
    }

    @Test
    void auditPersistenceFailureIsLoggedAndPropagated() {
        DataManagementSecurityAuditStore auditStore = mock(DataManagementSecurityAuditStore.class);
        when(auditStore.persist(any(DataManagementSecurityAuditEntity.class)))
            .thenReturn(Mono.error(new IllegalStateException("audit database unavailable")));

        assertThatThrownBy(() -> service(auditStore)
            .handle(incident(
                "incident-failure",
                DataManagementSecurityIncident.Type.UNLOCK_FAILED,
                "user-2",
                "operator",
                1,
                false
            ))
            .block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("audit database unavailable");
    }

    @Test
    void marksTheInitialAdministratorAsRootFromTheEventSnapshot() {
        DataManagementSecurityAuditStore auditStore = mock(DataManagementSecurityAuditStore.class);
        when(auditStore.persist(any(DataManagementSecurityAuditEntity.class)))
            .thenReturn(Mono.just(new DataManagementSecurityAuditStore.PersistResult(8L, true, true)));

        service(auditStore).handle(incident(
            "incident-root",
            DataManagementSecurityIncident.Type.SECURITY_LOCKED,
            "root-1",
            "admin",
            5,
            true
        )).block();

        ArgumentCaptor<DataManagementSecurityAuditEntity> captor =
            ArgumentCaptor.forClass(DataManagementSecurityAuditEntity.class);
        verify(auditStore).persist(captor.capture());
        assertThat(captor.getValue().getActorIsRoot()).isTrue();
    }

    @Test
    void doesNotBackfillMissingEventTimeIdentityOrRecipientValues() {
        DataManagementSecurityAuditStore auditStore = mock(DataManagementSecurityAuditStore.class);
        when(auditStore.persist(any(DataManagementSecurityAuditEntity.class)))
            .thenReturn(Mono.just(new DataManagementSecurityAuditStore.PersistResult(10L, true, true)));
        DataManagementSecurityIncident incident = new DataManagementSecurityIncident(
            "incident-missing-snapshot",
            DataManagementSecurityIncident.Type.UNLOCK_FAILED,
            DataManagementSecurityIncident.Severity.WARNING,
            Instant.parse("2026-08-10T01:02:03Z"),
            "user-2",
            "operator",
            null,
            null,
            "ADMIN",
            true,
            null,
            null,
            null,
            null,
            "192.0.2.10",
            null,
            null,
            Hashing.sha256("session"),
            "/admin/data-transfer/security/unlock",
            "request-missing-snapshot",
            1,
            false,
            "constant detail"
        );

        service(auditStore).handle(incident).block();

        ArgumentCaptor<DataManagementSecurityAuditEntity> captor =
            ArgumentCaptor.forClass(DataManagementSecurityAuditEntity.class);
        verify(auditStore).persist(captor.capture());
        assertThat(captor.getValue().getActorEmail()).isNull();
        assertThat(captor.getValue().getActorDisplayName()).isNull();
        assertThat(captor.getValue().getRootUserId()).isNull();
        assertThat(captor.getValue().getAlertRecipientEmail()).isNull();
        assertThat(captor.getValue().getActorIsRoot()).isFalse();
    }

    @Test
    void recoveryEventsHaveNoOutboxRecipientWhenRootDoesNotExist() {
        DataManagementSecurityAuditStore auditStore = mock(DataManagementSecurityAuditStore.class);
        when(auditStore.persist(any(DataManagementSecurityAuditEntity.class)))
            .thenReturn(Mono.just(new DataManagementSecurityAuditStore.PersistResult(9L, true, false)));

        service(auditStore).handle(new DataManagementSecurityIncident(
            "recovery-1",
            DataManagementSecurityIncident.Type.RECOVERY_SUCCEEDED,
            DataManagementSecurityIncident.Severity.INFO,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "startup",
            "recovery-1",
            5,
            false,
            "recovery key must not be stored"
        )).block();

        ArgumentCaptor<DataManagementSecurityAuditEntity> captor =
            ArgumentCaptor.forClass(DataManagementSecurityAuditEntity.class);
        verify(auditStore).persist(captor.capture());
        assertThat(captor.getValue().getAlertRecipientEmail()).isNull();
        assertThat(captor.getValue().getDetail()).doesNotContain("recovery key must not be stored");
    }

    private DataManagementSecurityIncident incident(
        String eventId,
        DataManagementSecurityIncident.Type type,
        String userId,
        String username,
        int failures,
        boolean locked
    ) {
        return new DataManagementSecurityIncident(
            eventId,
            type,
            locked
                ? DataManagementSecurityIncident.Severity.CRITICAL
                : DataManagementSecurityIncident.Severity.WARNING,
            Instant.parse("2026-08-10T01:02:03Z"),
            userId,
            username,
            "root-1".equals(userId) ? "root@example.com" : "operator@example.com",
            "root-1".equals(userId) ? "Root" : "Operations",
            "ADMIN",
            true,
            Instant.parse("2026-01-01T00:00:02Z"),
            Instant.parse("2026-08-01T00:00:02Z"),
            "root-1",
            "root@example.com",
            "192.0.2.10",
            "198.51.100.9, 192.0.2.10",
            "security-test-agent",
            "raw-session-value",
            "/admin/data-transfer/security/unlock",
            "request-1",
            failures,
            locked,
            "securityKey=dmsk-secret password=must-never-be-stored"
        );
    }

    private DataManagementSecurityAuditService service(DataManagementSecurityAuditStore auditStore) {
        return new DataManagementSecurityAuditService(auditStore);
    }

}
