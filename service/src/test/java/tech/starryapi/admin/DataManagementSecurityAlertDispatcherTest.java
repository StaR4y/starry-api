package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class DataManagementSecurityAlertDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-08-10T02:00:00Z");
    private static final String WORKER_ID = "test-worker";

    @Test
    void productionConstructorIsTheExplicitSpringInjectionPoint() throws Exception {
        var constructor = DataManagementSecurityAlertDispatcher.class.getConstructor(
            DataManagementSecurityAlertOutboxStore.class,
            DataManagementSecurityAlertMailer.class
        );

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void springContextCanAssembleTheProductionDispatcher() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                DataManagementSecurityAlertOutboxStore.class,
                () -> mock(DataManagementSecurityAlertOutboxStore.class)
            );
            context.registerBean(
                DataManagementSecurityAlertMailer.class,
                () -> mock(DataManagementSecurityAlertMailer.class)
            );
            context.register(DataManagementSecurityAlertDispatcher.class);
            context.refresh();

            assertThat(context.getBean(DataManagementSecurityAlertDispatcher.class)).isNotNull();
        }
    }

    @Test
    void successfulDeliveryAcknowledgesClaim() {
        DataManagementSecurityAlertOutboxStore store = mock(DataManagementSecurityAlertOutboxStore.class);
        DataManagementSecurityAlertMailer mailer = mock(DataManagementSecurityAlertMailer.class);
        var alert = claimedAlert(1);
        when(store.claim(WORKER_ID, 20)).thenReturn(Flux.just(alert));
        when(mailer.send(alert.audit()))
            .thenReturn(Mono.just(DataManagementSecurityAlertMailer.DeliveryOutcome.sent()));
        when(store.markSent(11L, WORKER_ID)).thenReturn(Mono.just(true));

        Integer dispatched = dispatcher(store, mailer, Duration.ofSeconds(1)).dispatchBatch().block();

        assertThat(dispatched).isEqualTo(1);
        verify(store).markSent(11L, WORKER_ID);
        verify(store, never()).reschedule(any(Long.class), any(), any(), any());
    }

    @Test
    void unavailableSmtpIsRetriedWithExponentialBackoff() {
        DataManagementSecurityAlertOutboxStore store = mock(DataManagementSecurityAlertOutboxStore.class);
        DataManagementSecurityAlertMailer mailer = mock(DataManagementSecurityAlertMailer.class);
        var alert = claimedAlert(3);
        when(store.claim(WORKER_ID, 20)).thenReturn(Flux.just(alert));
        when(mailer.send(alert.audit())).thenReturn(Mono.just(
            DataManagementSecurityAlertMailer.DeliveryOutcome.notSent("SMTP_NOT_CONFIGURED")
        ));
        when(store.reschedule(eq(11L), eq(WORKER_ID), any(Instant.class), eq("SMTP_NOT_CONFIGURED")))
            .thenReturn(Mono.just(true));

        dispatcher(store, mailer, Duration.ofSeconds(1)).dispatchBatch().block();

        ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
        verify(store).reschedule(
            eq(11L),
            eq(WORKER_ID),
            nextAttempt.capture(),
            eq("SMTP_NOT_CONFIGURED")
        );
        assertThat(nextAttempt.getValue()).isAfterOrEqualTo(NOW.plusSeconds(120));
        assertThat(nextAttempt.getValue()).isBeforeOrEqualTo(NOW.plusSeconds(144));
        verify(store, never()).markSent(11L, WORKER_ID);
    }

    @Test
    void missingRecipientRemainsInOutboxForLaterRootEmailBackfill() {
        DataManagementSecurityAlertOutboxStore store = mock(DataManagementSecurityAlertOutboxStore.class);
        DataManagementSecurityAlertMailer mailer = mock(DataManagementSecurityAlertMailer.class);
        var alert = claimedAlert(1);
        when(store.claim(WORKER_ID, 20)).thenReturn(Flux.just(alert));
        when(mailer.send(alert.audit())).thenReturn(Mono.just(
            DataManagementSecurityAlertMailer.DeliveryOutcome.notSent("ALERT_RECIPIENT_MISSING")
        ));
        when(store.reschedule(eq(11L), eq(WORKER_ID), any(Instant.class), eq("ALERT_RECIPIENT_MISSING")))
            .thenReturn(Mono.just(true));

        dispatcher(store, mailer, Duration.ofSeconds(1)).dispatchBatch().block();

        verify(store).reschedule(
            eq(11L),
            eq(WORKER_ID),
            any(Instant.class),
            eq("ALERT_RECIPIENT_MISSING")
        );
        verify(store, never()).markSent(11L, WORKER_ID);
    }

    @Test
    void deliveryTimeoutReleasesLeaseForRetry() {
        DataManagementSecurityAlertOutboxStore store = mock(DataManagementSecurityAlertOutboxStore.class);
        DataManagementSecurityAlertMailer mailer = mock(DataManagementSecurityAlertMailer.class);
        var alert = claimedAlert(1);
        when(store.claim(WORKER_ID, 20)).thenReturn(Flux.just(alert));
        when(mailer.send(alert.audit())).thenReturn(Mono.never());
        when(store.reschedule(eq(11L), eq(WORKER_ID), any(Instant.class), eq("DELIVERY_TIMEOUT")))
            .thenReturn(Mono.just(true));

        dispatcher(store, mailer, Duration.ofMillis(20)).dispatchBatch().block();

        verify(store).reschedule(
            eq(11L),
            eq(WORKER_ID),
            any(Instant.class),
            eq("DELIVERY_TIMEOUT")
        );
        verify(store, never()).markSent(11L, WORKER_ID);
    }

    @Test
    void retryBackoffIsCappedForLongLivedSecurityAlerts() {
        assertThat(DataManagementSecurityAlertDispatcher.retryBackoff(1, "event-1"))
            .isBetween(Duration.ofSeconds(30), Duration.ofSeconds(36));
        assertThat(DataManagementSecurityAlertDispatcher.retryBackoff(2, "event-1"))
            .isBetween(Duration.ofSeconds(60), Duration.ofSeconds(72));
        assertThat(DataManagementSecurityAlertDispatcher.retryBackoff(10_000, "event-1"))
            .isEqualTo(Duration.ofHours(6));
    }

    private DataManagementSecurityAlertDispatcher dispatcher(
        DataManagementSecurityAlertOutboxStore store,
        DataManagementSecurityAlertMailer mailer,
        Duration timeout
    ) {
        return new DataManagementSecurityAlertDispatcher(
            store,
            mailer,
            Clock.fixed(NOW, ZoneOffset.UTC),
            timeout,
            WORKER_ID
        );
    }

    private DataManagementSecurityAlertOutboxStore.ClaimedAlert claimedAlert(int attemptCount) {
        DataManagementSecurityAuditEntity audit = new DataManagementSecurityAuditEntity();
        audit.setId(7L);
        audit.setEventId("event-1");
        audit.setEventType("UNLOCK_FAILED");
        audit.setSeverity("WARNING");
        audit.setOccurredAt(NOW.minusSeconds(10));
        audit.setAlertRecipientEmail("root@example.com");
        audit.setEndpoint("/admin/data-transfer/security/unlock");
        audit.setRequestId("request-1");
        audit.setActorIsRoot(false);
        audit.setConsecutiveFailures(attemptCount);
        audit.setLocked(false);
        return new DataManagementSecurityAlertOutboxStore.ClaimedAlert(
            11L,
            "event-1",
            7L,
            attemptCount,
            audit
        );
    }
}
