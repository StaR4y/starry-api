package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
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
import tech.starryapi.admin.*;
import tech.starryapi.common.ApiException;

@EnabledIfEnvironmentVariable(named = "PLUGIN_DATABASE_TESTS", matches = "true")
@SpringBootTest(classes = PluginSecurityIntegrationTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Timeout(45)
class PluginSecurityIntegrationTest {
    @Configuration
    @EnableAutoConfiguration
    @Import({PluginStore.class, PluginSecurityService.class, PasswordHasher.class})
    static class Config {
        @Bean AdminUserRepository users() { return mock(AdminUserRepository.class); }
        @Bean PluginVerificationMailer mailer() { return mock(PluginVerificationMailer.class); }
    }
    @Autowired DatabaseClient db;
    @Autowired PluginStore store;
    @Autowired PluginSecurityService security;
    @Autowired PasswordHasher hasher;
    @Autowired AdminUserRepository users;
    @Autowired PluginVerificationMailer mailer;
    final AdminPrincipal actor = new AdminPrincipal("test-root", "admin", "ADMIN", Instant.now().plusSeconds(3600), false);
    final String password = "test-only-root-password";
    AdminUserEntity root;
    final AtomicReference<String> code = new AtomicReference<>();

    @BeforeEach void setup() {
        assertThat(System.getenv("POSTGRES_DATABASE")).startsWith("starry_plugin_test");
        db.sql("""
            UPDATE plugin_security_state SET developer_enabled=FALSE,challenge_id=NULL,code_hash=NULL,
            code_salt=NULL,session_hash=NULL,identity_hash=NULL,expires_at=NULL,sent_at=NULL,
            delivered=FALSE,failures=0,locked_until=NULL WHERE id=1
            """).fetch().rowsUpdated().block();
        reset(users, mailer);
        root = new AdminUserEntity(); root.setUserId(actor.userId()); root.setRole("ADMIN");
        root.setEnabled(true); root.setFirstLogin(false); root.setEmail("root@example.test");
        root.setPasswordSalt(hasher.newSalt()); root.setPasswordHash(hasher.hash(password, root.getPasswordSalt()));
        when(users.findApplicationRoot()).thenAnswer(invocation -> Mono.just(root));
        when(mailer.send(anyString(), anyString())).thenAnswer(invocation -> { code.set(invocation.getArgument(1)); return Mono.empty(); });
    }

    @Test void defaultIsDisabledAndRootPlusEmailCanEnable() {
        assertThat(store.security().block().enabled()).isFalse();
        var challenge = security.send(actor, "session-one", password).block();
        assertThat(code.get()).matches("[0-9]{6}");
        assertThat(challenge.email()).isEqualTo("r***@example.test");
        var state = store.security().block();
        assertThat(state.codeHash()).doesNotContain(code.get());
        enable(challenge, "session-one", code.get());
        assertThat(store.security().block().enabled()).isTrue();
        assertThat(store.security().block().codeHash()).isNull();
        assertThat(store.audits("", 0).collectList().block()).noneMatch(a -> a.detail().contains(password) || a.detail().contains(code.get()));
    }

    @Test void ordinaryAdminAndEnvironmentTokenCannotEnable() {
        for (String id : new String[] {"other-admin", "env-admin"}) {
            var other = new AdminPrincipal(id, "admin", "ADMIN", null, false);
            assertThatThrownBy(() -> security.send(other, "session", password).block()).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(mailer);
    }

    @Test void incorrectPasswordNeverSendsEmail() {
        assertThatThrownBy(() -> security.send(actor, "session", "incorrect").block()).isInstanceOf(ApiException.class);
        assertThat(store.security().block().failures()).isEqualTo(1);
        verifyNoInteractions(mailer);
    }

    @Test void fiveFailuresRemainLockedEvenAfterDisablingMode() {
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> security.send(actor, "session", "incorrect").block()).isInstanceOf(ApiException.class);
        security.disable(actor).block();
        assertThatThrownBy(() -> security.send(actor, "session", password).block())
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PLUGIN_VERIFICATION_LOCKED"));
        assertThat(store.security().block().failures()).isEqualTo(5);
    }

    @Test void challengeCannotBeReplayedOrMovedToAnotherSession() {
        var challenge = security.send(actor, "session-one", password).block();
        assertThatThrownBy(() -> enable(challenge, "session-two", code.get())).isInstanceOf(ApiException.class);
        enable(challenge, "session-one", code.get());
        security.disable(actor).block();
        assertThatThrownBy(() -> enable(challenge, "session-one", code.get())).isInstanceOf(ApiException.class);
        assertThat(store.security().block().enabled()).isFalse();
    }

    @Test void codeExpiresAndWrongCodeCountsTowardsLockout() {
        var challenge = security.send(actor, "session-one", password).block();
        String wrong = code.get().equals("000000") ? "111111" : "000000";
        assertThatThrownBy(() -> enable(challenge, "session-one", wrong)).isInstanceOf(ApiException.class);
        db.sql("UPDATE plugin_security_state SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second'").fetch().rowsUpdated().block();
        assertThatThrownBy(() -> enable(challenge, "session-one", code.get())).isInstanceOf(ApiException.class);
        assertThat(store.security().block().failures()).isEqualTo(2);
    }

    @Test void rootPasswordAndEmailChangesInvalidateChallenge() {
        var challenge = security.send(actor, "session-one", password).block();
        root.setEmail("changed@example.test");
        assertThatThrownBy(() -> enable(challenge, "session-one", code.get())).isInstanceOf(ApiException.class);
        root.setEmail("root@example.test"); root.setPasswordHash(hasher.hash("new-password", root.getPasswordSalt()));
        assertThatThrownBy(() -> enable(challenge, "session-one", code.get())).isInstanceOf(ApiException.class);
    }

    @Test void mailFailureNeverCreatesUsableChallenge() {
        when(mailer.send(anyString(), anyString())).thenReturn(Mono.error(new IllegalStateException("smtp unavailable")));
        assertThatThrownBy(() -> security.send(actor, "session-one", password).block()).isInstanceOf(IllegalStateException.class);
        assertThat(store.security().block().delivered()).isFalse();
        assertThat(store.security().block().enabled()).isFalse();
    }

    @Test void resendInvalidatesPreviousChallenge() {
        var first = security.send(actor, "session-one", password).block();
        String original = code.get();
        db.sql("UPDATE plugin_security_state SET sent_at=CURRENT_TIMESTAMP - INTERVAL '61 seconds'").fetch().rowsUpdated().block();
        var second = security.send(actor, "session-one", password).block();
        assertThatThrownBy(() -> enable(first, "session-one", original)).isInstanceOf(ApiException.class);
        enable(second, "session-one", code.get());
    }

    @Test void concurrentSendsCannotBypassCooldown() {
        var results = Flux.range(0, 8).flatMap(i -> security.send(actor, "session-one", password).map(c -> true).onErrorReturn(false), 8).collectList().block();
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        verify(mailer, times(1)).send(anyString(), anyString());
    }

    @Test void concurrentVerificationConsumesChallengeExactlyOnce() {
        var challenge = security.send(actor, "session-one", password).block();
        var input = new PluginDtos.Enable(password, challenge.id(), code.get(), true);
        var results = Flux.range(0, 6).flatMap(i -> security.enable(actor, "session-one", input)
            .thenReturn(true).onErrorReturn(false), 6).collectList().block();
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    }

    @Test void missingRiskAcceptanceIsRejected() {
        var challenge = security.send(actor, "session-one", password).block();
        assertThatThrownBy(() -> security.enable(actor, "session-one",
            new PluginDtos.Enable(password, challenge.id(), code.get(), false)).block()).isInstanceOf(ApiException.class);
        assertThat(store.security().block().enabled()).isFalse();
    }

    private void enable(PluginDtos.Challenge challenge, String session, String value) {
        security.enable(actor, session, new PluginDtos.Enable(password, challenge.id(), value, true)).block();
    }
}
