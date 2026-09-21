package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.admin.AdminUserEntity;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.GlobalExceptionHandler;

class PluginAdminControllerTest {
    PluginSecurityService security;
    PluginRuntime runtime;
    PluginStore store;
    WebTestClient client;
    @BeforeEach void setup() {
        AdminAuth auth = mock(AdminAuth.class);
        when(auth.verifyAdmin(any())).thenReturn(new AdminPrincipal("other-admin", "other", "ADMIN", null, false));
        security = mock(PluginSecurityService.class); runtime = mock(PluginRuntime.class);
        store = mock(PluginStore.class);
        client = WebTestClient.bindToController(new PluginAdminController(auth, security, runtime,
            store, new PluginLogs())).controllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void nonRootCannotExecuteLifecyclePublisher() {
        when(security.root(any())).thenReturn(Mono.error(new ApiException(HttpStatus.FORBIDDEN, "PLUGIN_ROOT_REQUIRED", "Root required")));
        AtomicBoolean executed = new AtomicBoolean();
        when(runtime.action(anyString(), anyString(), anyString())).thenReturn(Mono.fromRunnable(() -> executed.set(true)));
        client.post().uri("/admin/plugins/example/actions/start").exchange().expectStatus().isForbidden();
        assertThat(executed).isFalse();
    }

    @Test void statusDoesNotExposePluginsToNonRoot() {
        when(security.root(any())).thenReturn(Mono.error(new ApiException(HttpStatus.FORBIDDEN, "PLUGIN_ROOT_REQUIRED", "Root required")));
        client.get().uri("/admin/plugins").exchange().expectStatus().isForbidden()
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.error.code").isEqualTo("PLUGIN_ROOT_REQUIRED");
        verifyNoInteractions(runtime, store);
    }

    @Test void rootReceivesConcreteRestartDiagnostics() {
        var user = new AdminUserEntity(); user.setEmail("root@example.test");
        when(security.root(any())).thenReturn(Mono.just(user));
        when(store.security()).thenReturn(Mono.just(new PluginStore.SecurityState(true, null, null, null,
            null, null, null, null, false, 0, null)));
        when(runtime.list()).thenReturn(Mono.just(List.of()));
        when(runtime.restartRequired()).thenReturn(true);
        when(runtime.restartReasons()).thenReturn(List.of(new PluginDtos.RestartReason("example-plugin", "STOP",
            "STOP_FAILED", "Stop callback failed", Instant.now())));
        client.get().uri("/admin/plugins").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.restartRequired").isEqualTo(true)
            .jsonPath("$.data.restartReasons[0].pluginId").isEqualTo("example-plugin")
            .jsonPath("$.data.restartReasons[0].code").isEqualTo("STOP_FAILED");
    }

    @Test void verificationRejectsNonSixDigitCodesAtHttpBoundary() {
        client.post().uri("/admin/plugins/developer-mode").header("Content-Type", "application/json")
            .bodyValue("""
                {"password":"not-a-real-password","challengeId":"challenge","code":"12345","acceptRisk":true}
                """).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        verify(security, never()).enable(any(), anyString(), any());
    }

    @Test void requestObjectsNeverRenderSecrets() {
        assertThat(new PluginDtos.Enable("secret", "challenge", "123456", true).toString()).doesNotContain("secret", "123456");
        assertThat(new PluginDtos.Configure("secret-config").toString()).doesNotContain("secret-config");
        assertThat(new PluginDtos.Debug("health", "secret-input").toString()).doesNotContain("secret-input");
    }
}
