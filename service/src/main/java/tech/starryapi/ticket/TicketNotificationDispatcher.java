package tech.starryapi.ticket;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.admin.TicketMailer;
import tech.starryapi.common.ApiException;
import tech.starryapi.ticket.TicketDtos.Ticket;

@Service
public class TicketNotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TicketNotificationDispatcher.class);
    private final DatabaseClient db;
    private final TicketSettingsService settings;
    private final TicketService tickets;
    private final TicketMailer mailer;
    private final SystemMaintenanceTaskGate maintenance;
    private final AtomicBoolean running = new AtomicBoolean();
    public TicketNotificationDispatcher(DatabaseClient db, TicketSettingsService settings, TicketService tickets,
                                        TicketMailer mailer, SystemMaintenanceTaskGate maintenance) {
        this.db = db; this.settings = settings; this.tickets = tickets; this.mailer = mailer; this.maintenance = maintenance;
    }

    @Scheduled(fixedDelayString = "${starry.tickets.notification-poll-interval:PT30S}", initialDelayString = "${starry.tickets.notification-initial-delay:PT30S}")
    public void scheduledDispatch() {
        if (!running.compareAndSet(false, true)) return;
        maintenance.whenOperational(this::dispatchBatch)
            .doOnError(e -> log.warn("ticket_notification=BATCH_FAILED error_type={}", e.getClass().getSimpleName()))
            .onErrorReturn(0L).doFinally(signal -> running.set(false)).subscribe();
    }

    public Mono<Long> dispatchBatch() {
        return settings.get().flatMap(config -> config.enabled() ? claim().concatMap(this::deliver).count() : Mono.just(0L));
    }

    Flux<Claim> claim() {
        String token = UUID.randomUUID().toString();
        // The claim is durable and atomic across instances; crashed workers are retried after the lease expires.
        return db.sql("""
            UPDATE support_tickets t SET notify_token = :token, notify_lease_until = now() + interval '5 minutes',
              notify_attempts = notify_attempts + 1
            WHERE id IN (SELECT id FROM support_tickets WHERE status IN ('OPEN', 'IN_PROGRESS') AND notify_at <= now()
              AND (notify_lease_until IS NULL OR notify_lease_until < now()) ORDER BY notify_at LIMIT 5 FOR UPDATE SKIP LOCKED)
            RETURNING t.*
            """).bind("token", token).map((r, m) -> new Claim(TicketService.ticket(r), token, r.get("notify_attempts", Integer.class))).all();
    }

    Mono<Boolean> deliver(Claim claim) {
        return settings.get().flatMap(config -> {
            if (!config.enabled()) return release(claim);
            return db.sql("SELECT count(*) AS n FROM support_tickets WHERE id = :id AND version = :version AND notify_token = :token AND status IN ('OPEN', 'IN_PROGRESS')")
                .bind("id", claim.ticket().id()).bind("version", claim.ticket().version()).bind("token", claim.token())
                .map(r -> r.get("n", Long.class) > 0).one().flatMap(current -> {
                    if (!current) return release(claim);
                    var messages = "CONTENT".equals(config.mode()) ? tickets.notificationMessages(claim.ticket().id())
                        : Mono.just(List.<TicketDtos.Message>of());
                    return Mono.zip(settings.recipients(config), messages).flatMap(v -> mailer.send(claim.ticket(), v.getT2(), config, v.getT1()))
                        .timeout(Duration.ofSeconds(45))
                        .then(finish(claim, config.reminderMinutes() * 60L, ""))
                        .onErrorResume(error -> {
                            String reason = error instanceof ApiException api ? api.getCode() : "DELIVERY_FAILED";
                            log.warn("ticket_notification=RETRY ticket_id={} attempt={} reason={}", claim.ticket().id(), claim.attempts(), reason);
                            return finish(claim, retrySeconds(claim.attempts()), reason);
                        });
                });
        });
    }

    Mono<Boolean> finish(Claim claim, long seconds, String error) {
        return db.sql("""
            UPDATE support_tickets SET notify_token = NULL, notify_lease_until = NULL,
              notify_at = CASE WHEN version = :version THEN now() + (:seconds * interval '1 second') ELSE notify_at END,
              notified_at = CASE WHEN :error = '' THEN now() ELSE notified_at END,
              notify_error = CASE WHEN version = :version THEN NULLIF(:error, '') ELSE notify_error END,
              notify_attempts = CASE WHEN :error = '' AND version = :version THEN 0 ELSE notify_attempts END
            WHERE id = :id AND notify_token = :token
            """).bind("version", claim.ticket().version()).bind("seconds", seconds).bind("error", error)
            .bind("id", claim.ticket().id()).bind("token", claim.token()).fetch().rowsUpdated().map(n -> n > 0)
            .doOnNext(updated -> { if (updated && error.isEmpty()) log.info("ticket_notification=SENT ticket_id={} version={}", claim.ticket().id(), claim.ticket().version()); });
    }

    private Mono<Boolean> release(Claim claim) {
        return db.sql("UPDATE support_tickets SET notify_token = NULL, notify_lease_until = NULL WHERE id = :id AND notify_token = :token")
            .bind("id", claim.ticket().id()).bind("token", claim.token()).fetch().rowsUpdated().map(n -> n > 0);
    }
    static long retrySeconds(int attempt) { return Math.min(21600, 30L << Math.min(10, Math.max(0, attempt - 1))); }
    record Claim(Ticket ticket, String token, int attempts) { }
}
