package tech.starryapi.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DataManagementSecurityAlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DataManagementSecurityAlertDispatcher.class);
    private static final int BATCH_SIZE = 20;
    private static final int CONCURRENCY = 4;
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);

    private final DataManagementSecurityAlertOutboxStore outboxStore;
    private final DataManagementSecurityAlertMailer alertMailer;
    private final Clock clock;
    private final Duration deliveryTimeout;
    private final String workerId;
    private final AtomicBoolean dispatchRunning = new AtomicBoolean();

    @Autowired
    public DataManagementSecurityAlertDispatcher(
        DataManagementSecurityAlertOutboxStore outboxStore,
        DataManagementSecurityAlertMailer alertMailer
    ) {
        this(
            outboxStore,
            alertMailer,
            Clock.systemUTC(),
            DELIVERY_TIMEOUT,
            "security-alert-" + UUID.randomUUID()
        );
    }

    DataManagementSecurityAlertDispatcher(
        DataManagementSecurityAlertOutboxStore outboxStore,
        DataManagementSecurityAlertMailer alertMailer,
        Clock clock,
        Duration deliveryTimeout,
        String workerId
    ) {
        this.outboxStore = outboxStore;
        this.alertMailer = alertMailer;
        this.clock = clock;
        this.deliveryTimeout = deliveryTimeout;
        this.workerId = workerId;
    }

    @Scheduled(
        fixedDelayString = "${starry.data-management.alert-outbox-poll-interval:PT5S}",
        initialDelayString = "${starry.data-management.alert-outbox-initial-delay:PT5S}"
    )
    public void scheduledDispatch() {
        if (!dispatchRunning.compareAndSet(false, true)) {
            return;
        }
        dispatchBatch()
            .doOnError(error -> log.error(
                "security_alert_dispatch=BATCH_FAILED worker_id={} error_type={}",
                workerId,
                error.getClass().getSimpleName()
            ))
            .onErrorReturn(0)
            .doFinally(signal -> dispatchRunning.set(false))
            .subscribe();
    }

    Mono<Integer> dispatchBatch() {
        return outboxStore.claim(workerId, BATCH_SIZE)
            .flatMap(this::deliver, CONCURRENCY)
            .count()
            .map(Long::intValue);
    }

    private Mono<Boolean> deliver(DataManagementSecurityAlertOutboxStore.ClaimedAlert alert) {
        return alertMailer.send(alert.audit())
            .timeout(deliveryTimeout)
            .switchIfEmpty(Mono.just(DataManagementSecurityAlertMailer.DeliveryOutcome.failed(
                "EMPTY_DELIVERY_OUTCOME"
            )))
            .flatMap(outcome -> outcome.status() == DataManagementSecurityAlertMailer.Status.SENT
                ? acknowledge(alert)
                : retry(alert, outcome.reason(), null))
            .onErrorResume(error -> retry(
                alert,
                error instanceof TimeoutException ? "DELIVERY_TIMEOUT" : "DELIVERY_EXCEPTION",
                error
            ));
    }

    private Mono<Boolean> acknowledge(DataManagementSecurityAlertOutboxStore.ClaimedAlert alert) {
        return outboxStore.markSent(alert.outboxId(), workerId)
            .doOnNext(updated -> {
                if (updated) {
                    log.info(
                        "security_alert_dispatch=DELIVERED event_id={} outbox_id={} attempt={}",
                        alert.eventId(),
                        alert.outboxId(),
                        alert.attemptCount()
                    );
                } else {
                    log.warn(
                        "security_alert_dispatch=ACK_REJECTED event_id={} outbox_id={} attempt={} reason=LEASE_LOST",
                        alert.eventId(),
                        alert.outboxId(),
                        alert.attemptCount()
                    );
                }
            });
    }

    private Mono<Boolean> retry(
        DataManagementSecurityAlertOutboxStore.ClaimedAlert alert,
        String reason,
        Throwable error
    ) {
        String safeReason = safeReason(reason);
        Duration delay = retryBackoff(alert.attemptCount(), alert.eventId());
        Instant nextAttemptAt = clock.instant().plus(delay);
        return outboxStore.reschedule(
                alert.outboxId(),
                workerId,
                nextAttemptAt,
                safeReason
            )
            .doOnNext(updated -> log.warn(
                "security_alert_dispatch={} event_id={} outbox_id={} attempt={} reason={}"
                    + " next_attempt_at={} error_type={}",
                updated ? "RETRY_SCHEDULED" : "RETRY_REJECTED",
                alert.eventId(),
                alert.outboxId(),
                alert.attemptCount(),
                safeReason,
                nextAttemptAt,
                error == null ? "none" : error.getClass().getSimpleName()
            ));
    }

    static Duration retryBackoff(int attemptCount, String eventId) {
        int exponent = Math.min(10, Math.max(0, attemptCount - 1));
        long baseSeconds = Math.min(
            MAX_BACKOFF.toSeconds(),
            INITIAL_BACKOFF.toSeconds() * (1L << exponent)
        );
        int jitterPercent = Math.floorMod(eventId == null ? 0 : eventId.hashCode(), 21);
        long jitterSeconds = baseSeconds * jitterPercent / 100;
        return Duration.ofSeconds(Math.min(MAX_BACKOFF.toSeconds(), baseSeconds + jitterSeconds));
    }

    private static String safeReason(String reason) {
        if (reason == null || !reason.matches("[A-Z0-9_]{1,128}")) {
            return "UNCLASSIFIED_DELIVERY_FAILURE";
        }
        return reason;
    }
}
