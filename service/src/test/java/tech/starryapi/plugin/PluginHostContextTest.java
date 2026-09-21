package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import tech.starryapi.admin.*;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;

class PluginHostContextTest {
    @Test void delegatesLocalApiAndRevokesTemporarySession() {
        AtomicReference<String> authorization = new AtomicReference<>();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            authorization.set(request.requestHeaders().get("Authorization"));
            return response.sendString(Mono.just("{\"ok\":true}"));
        }).bindNow();
        try {
            var host = mock(ApplicationContext.class);
            var users = mock(AdminUserRepository.class);
            var sessions = new AdminSessionService(new ProxyProperties());
            var root = new AdminUserEntity(); root.setUserId("root"); root.setUsername("admin");
            root.setEnabled(true); root.setRole("ADMIN"); root.setFirstLogin(false);
            when(host.getBean(AdminUserRepository.class)).thenReturn(users);
            when(host.getBean(AdminSessionService.class)).thenReturn(sessions);
            when(users.findApplicationRoot()).thenReturn(Mono.just(root));
            var context = context(host, true, server.port());
            var result = context.request("GET", "/status", Map.of(), null).block();
            assertThat(result.status()).isEqualTo(200);
            assertThat(new String(result.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
            assertThat(authorization.get()).startsWith("Bearer adm-");
            assertThatThrownBy(() -> sessions.verify(authorization.get().substring(7))).isInstanceOf(ApiException.class);
        } finally { server.disposeNow(); }
    }

    @Test void rejectsExternalAndProtocolRelativeUris() throws Exception {
        var context = context(mock(ApplicationContext.class), true, 1);
        for (String uri : new String[] {"https://example.test/", "//example.test/", "/\\example.test/"}) {
            assertThatThrownBy(() -> context.request("GET", uri, Map.of(), null).block()).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.requestStream("GET", uri, Map.of(), null).blockLast()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(context.cleanup(java.time.Duration.ofSeconds(1)).complete()).isTrue();
    }

    @Test void disabledModeOrRevokedContextCannotInvokeApi() {
        var host = mock(ApplicationContext.class);
        var disabled = context(host, false, 1);
        assertThatThrownBy(() -> disabled.request("GET", "/status", Map.of(), null).block()).isInstanceOf(IllegalStateException.class);
        var revoked = context(host, true, 1); revoked.revoke();
        assertThatThrownBy(() -> revoked.service(String.class)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(host);
    }

    @Test void publicServicesAreAvailableWithoutDomainAllowlist() {
        var host = mock(ApplicationContext.class); when(host.getBean(String.class)).thenReturn("example service");
        assertThat(context(host, true, 1).service(String.class)).isEqualTo("example service");
    }

    @Test void discoversNamedServicesAndExposesAnImmutableSnapshot() {
        var host = mock(ApplicationContext.class);
        when(host.getBean("first", String.class)).thenReturn("one");
        when(host.getBeansOfType(String.class)).thenReturn(Map.of("first", "one", "second", "two"));
        var context = context(host, true, 1);
        assertThat(context.service("first", String.class)).isEqualTo("one");
        var services = context.services(String.class);
        assertThat(services).containsEntry("second", "two");
        assertThatThrownBy(() -> services.put("third", "three")).isInstanceOf(UnsupportedOperationException.class);
        context.revoke();
        assertThatThrownBy(() -> context.services(String.class)).isInstanceOf(IllegalStateException.class);
    }

    @Test void advertisesCapabilitiesAndRevokesRetainedHelpers() {
        var context = context(mock(ApplicationContext.class), true, 1);
        assertThat(context.sdkVersion()).isEqualTo("1.1");
        assertThat(context.capabilities()).contains("http-stream", "managed-tasks", "json");
        assertThat(context.config(Map.class)).isEmpty();
        var json = context.json();
        var tasks = context.tasks();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        context.manage(() -> closed.set(true));
        context.revoke();
        assertThat(closed).isTrue();
        assertThatThrownBy(() -> json.write(Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tasks.schedule("late", java.time.Duration.ZERO, () -> {})).isInstanceOf(IllegalStateException.class);
    }

    @Test void synchronousRequestConstructionFailureRevokesTemporarySession() {
        var host = mock(ApplicationContext.class);
        var users = mock(AdminUserRepository.class);
        var sessions = spy(new AdminSessionService(new ProxyProperties()));
        var root = new AdminUserEntity(); root.setUserId("root"); root.setUsername("admin");
        root.setEnabled(true); root.setRole("ADMIN"); root.setFirstLogin(false);
        when(host.getBean(AdminUserRepository.class)).thenReturn(users);
        when(host.getBean(AdminSessionService.class)).thenReturn(sessions);
        when(users.findApplicationRoot()).thenReturn(Mono.just(root));
        assertThatThrownBy(() -> context(host, true, 1).request(null, "/status", Map.of(), null).block())
            .isInstanceOf(NullPointerException.class);
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sessions).invalidateSession(token.capture());
        assertThatThrownBy(() -> sessions.verify(token.getValue())).isInstanceOf(ApiException.class);
    }

    private PluginHostContext context(ApplicationContext host, boolean enabled, int port) {
        var store = mock(PluginStore.class);
        when(store.security()).thenReturn(Mono.just(new PluginStore.SecurityState(enabled, null, null, null,
            null, null, null, null, false, 0, null)));
        return new PluginHostContext(new PluginStore.Installed("test-plugin", "1", "", "", "", 1, false, "{}", Instant.now()),
            host, store, new PluginLogs(), Path.of("."), URI.create("http://127.0.0.1:" + port + "/"), new com.fasterxml.jackson.databind.ObjectMapper());
    }
}
