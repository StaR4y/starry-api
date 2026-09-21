package tech.starryapi.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.admin.TicketMailer;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.ticket.TicketDtos.*;

@EnabledIfEnvironmentVariable(named = "TICKET_DATABASE_TESTS", matches = "true")
@SpringBootTest(classes = TicketDatabaseIntegrationTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Timeout(45)
class TicketDatabaseIntegrationTest {
    @Configuration
    @EnableAutoConfiguration
    @Import({TicketService.class, TicketNotificationDispatcher.class})
    static class Config {
        @Bean TicketSettingsService settings() { return mock(TicketSettingsService.class); }
        @Bean TicketMailer mailer() { return mock(TicketMailer.class); }
        @Bean SystemMaintenanceTaskGate maintenance() { return SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(); }
    }
    @Autowired DatabaseClient db;
    @Autowired TicketService service;
    @Autowired TicketNotificationDispatcher notifications;
    @Autowired TicketSettingsService settings;
    @Autowired TicketMailer mailer;
    AdminPrincipal user;
    AdminPrincipal other;
    AdminPrincipal admin;

    @BeforeEach void setup() {
        assertThat(System.getenv("POSTGRES_DATABASE")).matches("starry_ticket_test(?:_[a-z0-9_]+)?");
        assertThat(System.getenv("POSTGRES_PORT")).isEqualTo("55438");
        db.sql("UPDATE support_tickets SET status = 'CLOSED'").fetch().rowsUpdated().block();
        user = actor("USER"); other = actor("USER"); admin = actor("ADMIN");
        reset(settings, mailer);
        when(settings.get()).thenReturn(Mono.just(new Settings(true, "CONTENT", List.of(), 60, "")));
        when(settings.recipients(any())).thenReturn(Mono.just(List.of("admin@example.test")));
        when(mailer.send(any(), anyList(), any(), anyList())).thenReturn(Mono.empty());
    }

    @Test void userIsolationAppliesToSearchRepliesStatusAndAttachments() {
        var file = file(); var ticket = create(List.of(file));
        assertThat(service.list(other, "", "", 1, 20).block().total()).isZero();
        assertNotFound(() -> service.thread(other, ticket.id(), 0).block());
        assertNotFound(() -> service.reply(other, ticket.id(), reply(ticket.version()), List.of()).block());
        assertNotFound(() -> service.changeStatus(other, ticket.id(), new StatusUpdate("CLOSED", ticket.version())).block());
        assertNotFound(() -> service.download(other, ticket.id(), file.id()).block());
        assertThat(service.download(user, ticket.id(), file.id()).block().bytes()).containsExactly(1, 2, 3);
        assertThat(service.download(admin, ticket.id(), file.id()).block().filename()).isEqualTo("error.log");
        assertNotFound(() -> service.download(user, "missing", file.id()).block());
        assertThat(service.thread(admin, ticket.id(), 0).block().messages().getFirst().attachments()).hasSize(1);
    }

    @Test void concurrentRepliesCannotOverwriteEachOtherAndStaleDraftIsRejected() {
        var ticket = create(List.of());
        var outcomes = Flux.range(0, 6).flatMap(i -> Mono.defer(() -> service.reply(user, ticket.id(), reply(1), List.of()))
            .map(t -> true).onErrorResume(ApiException.class, error -> {
                assertThat(error.getCode()).isEqualTo("TICKET_CONFLICT"); return Mono.just(false);
            }), 6).collectList().block();
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        var actual = service.thread(user, ticket.id(), 0).block();
        assertThat(actual.ticket().version()).isEqualTo(2);
        assertThat(actual.messages()).hasSize(2);
    }

    @Test void failedAttachmentInsertRollsBackTicketAndMessageTogether() {
        var file = file(); var ticket = create(List.of(file));
        assertThatThrownBy(() -> service.reply(user, ticket.id(), reply(1), List.of(file)).block()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.find(user, ticket.id()).block().version()).isEqualTo(1);
        assertThat(service.thread(user, ticket.id(), 0).block().messages()).hasSize(1);
        assertThatThrownBy(() -> create(List.of(file))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.list(user, "", "", 1, 20).block().total()).isEqualTo(1);
    }

    @Test void workflowStopsRemindersUntilUserRepliesAndAllowsReopen() {
        var ticket = create(List.of());
        var answered = service.reply(admin, ticket.id(), reply(1), List.of()).block();
        assertThat(answered.status()).isEqualTo("WAITING_USER");
        assertThat(notifications.dispatchBatch().block()).isZero();
        verifyNoInteractions(mailer);
        var pending = service.reply(user, ticket.id(), reply(2), List.of()).block();
        assertThat(pending.status()).isEqualTo("OPEN");
        assertThat(notifications.dispatchBatch().block()).isEqualTo(1);
        var closed = service.changeStatus(user, ticket.id(), new StatusUpdate("CLOSED", 3)).block();
        assertThatThrownBy(() -> service.reply(user, ticket.id(), reply(4), List.of()).block()).isInstanceOf(ApiException.class);
        assertThat(service.changeStatus(user, ticket.id(), new StatusUpdate("OPEN", closed.version())).block().status()).isEqualTo("OPEN");
        assertThat(service.thread(user, ticket.id(), 0).block().messages()).anyMatch(m -> m.kind().equals("STATUS"));
    }

    @Test void smtpFailurePersistsRetryWithoutLosingTheTicketAndRecoveryAcknowledges() {
        var ticket = create(List.of());
        when(mailer.send(any(), anyList(), any(), anyList())).thenReturn(Mono.error(new IllegalStateException("smtp down")));
        assertThat(notifications.dispatchBatch().block()).isEqualTo(1);
        var state = notification(ticket.id());
        assertThat(state.get("notify_error")).isEqualTo("DELIVERY_FAILED");
        assertThat(state.get("notify_attempts")).isEqualTo(1);
        assertThat(service.find(user, ticket.id()).block()).isNotNull();
        assertThat(notifications.claim().collectList().block()).isEmpty();
        db.sql("UPDATE support_tickets SET notify_at = now() - interval '1 second' WHERE id = :id").bind("id", ticket.id()).fetch().rowsUpdated().block();
        when(mailer.send(any(), anyList(), any(), anyList())).thenReturn(Mono.empty());
        notifications.dispatchBatch().block();
        state = notification(ticket.id());
        assertThat(state.get("notify_error")).isNull(); assertThat(state.get("notified_at")).isNotNull();
        assertThat(state.get("notify_attempts")).isEqualTo(0);
    }

    @Test void leasesPreventDuplicateClaimsAndStaleAcknowledgementCannotConsumeNewReply() {
        var ticket = create(List.of());
        var claim = notifications.claim().single().block();
        assertThat(notifications.claim().collectList().block()).isEmpty();
        service.reply(user, ticket.id(), reply(1), List.of()).block();
        notifications.finish(claim, 3600, "").block();
        var newClaim = notifications.claim().single().block();
        assertThat(newClaim.ticket().version()).isEqualTo(2);
        assertThat(notifications.finish(claim, 3600, "").block()).isFalse();
        db.sql("UPDATE support_tickets SET notify_lease_until = now() - interval '1 second' WHERE id = :id").bind("id", ticket.id()).fetch().rowsUpdated().block();
        var recovered = notifications.claim().single().block();
        assertThat(recovered.token()).isNotEqualTo(newClaim.token());
        assertThat(notifications.finish(newClaim, 3600, "").block()).isFalse();
    }

    @Test void closedOrDisabledNotificationsAreNotDeliveredAfterClaim() {
        var ticket = create(List.of()); var claim = notifications.claim().single().block();
        service.changeStatus(user, ticket.id(), new StatusUpdate("CLOSED", 1)).block();
        notifications.deliver(claim).block(); verifyNoInteractions(mailer);
        when(settings.get()).thenReturn(Mono.just(new Settings(false, "SUMMARY", List.of(), 60, "")));
        create(List.of()); assertThat(notifications.dispatchBatch().block()).isZero();
    }

    @Test void pagingIsStableAndEmailRetainsInitialProblem() {
        var ticket = create(List.of());
        for (int i = 1; i < 36; i++) {
            var actor = actor("ADMIN");
            ticket = service.reply(actor, ticket.id(), new Submission(null, null, "response " + i, (long) i, "IN_PROGRESS"), List.of()).block();
        }
        var newest = service.thread(user, ticket.id(), 0).block();
        assertThat(newest.messages()).hasSize(30); assertThat(newest.hasMore()).isTrue();
        var older = service.thread(user, ticket.id(), newest.nextBefore()).block();
        assertThat(older.messages()).hasSize(6); assertThat(older.hasMore()).isFalse();
        assertThat(newest.messages()).noneMatch(m -> older.messages().stream().anyMatch(old -> old.id() == m.id()));
        assertThat(service.notificationMessages(ticket.id()).block()).hasSize(10).anyMatch(m -> m.body().equals("initial problem"));
    }

    @Test void dailyCreationLimitIsSerializedAcrossConcurrentRequests() {
        var outcomes = Flux.range(0, 24).flatMap(i -> service.create(user, new Submission("问题 " + i, "API", "body", null, null), List.of())
            .map(t -> true).onErrorResume(ApiException.class, error -> Mono.just(false)), 8).collectList().block();
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(20);
        assertThat(service.list(user, "", "", 1, 100).block().total()).isEqualTo(20);
    }

    @Test void reopeningCannotBypassActiveTicketLimit() {
        var historical = create(List.of());
        service.changeStatus(admin, historical.id(), new StatusUpdate("CLOSED", 1)).block();
        db.sql("""
            INSERT INTO support_tickets (id, user_id, username, title, category)
            SELECT :prefix || '-' || i, :user, 'fixture', 'fixture', 'API' FROM generate_series(1, 20) i
            """).bind("prefix", UUID.randomUUID().toString().substring(0, 16)).bind("user", user.userId()).fetch().rowsUpdated().block();
        assertThatThrownBy(() -> service.changeStatus(user, historical.id(), new StatusUpdate("OPEN", 2)).block()).isInstanceOf(ApiException.class);
        assertThat(service.find(user, historical.id()).block().status()).isEqualTo("CLOSED");
    }

    private Ticket create(List<TicketAttachments.Upload> files) { return service.create(user, new Submission("API 调用异常", "API", "initial problem", null, null), files).block(); }
    private Submission reply(long version) { return new Submission(null, null, "more detail", version, null); }
    private AdminPrincipal actor(String role) { String id = UUID.randomUUID().toString(); return new AdminPrincipal(id, "test-" + id.substring(0, 8), role, Instant.now().plusSeconds(3600), false); }
    private TicketAttachments.Upload file() { return new TicketAttachments.Upload(UUID.randomUUID().toString(), "error.log", new byte[]{1, 2, 3}); }
    private java.util.Map<String, Object> notification(String id) { return db.sql("SELECT * FROM support_tickets WHERE id = :id").bind("id", id).fetch().one().block(); }
    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }
}
