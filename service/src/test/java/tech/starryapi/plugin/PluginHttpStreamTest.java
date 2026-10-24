package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.netty.buffer.Unpooled;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import tech.starryapi.admin.AdminSessionService;
import tech.starryapi.admin.AdminUserEntity;
import tech.starryapi.admin.AdminUserRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.plugin.sdk.PluginHttpEvent;
import tools.jackson.databind.ObjectMapper;

@Timeout(15)
class PluginHttpStreamTest {
    ApplicationContext host;
    PluginStore store;
    PluginLogs logs;
    AdminSessionService sessions;
    DisposableServer server;
    PluginHostContext context;
    AtomicReference<String> authorization;

    @BeforeEach void setup() {
        host = mock(ApplicationContext.class);
        store = mock(PluginStore.class);
        logs = new PluginLogs();
        sessions = spy(new AdminSessionService(new ProxyProperties()));
        authorization = new AtomicReference<>();
        var users = mock(AdminUserRepository.class);
        var root = new AdminUserEntity(); root.setUserId("root"); root.setUsername("admin");
        root.setEnabled(true); root.setRole("ADMIN"); root.setFirstLogin(false);
        when(host.getBean(AdminUserRepository.class)).thenReturn(users);
        when(host.getBean(AdminSessionService.class)).thenReturn(sessions);
        when(users.findApplicationRoot()).thenReturn(Mono.just(root));
        when(store.security()).thenReturn(Mono.just(new PluginStore.SecurityState(true, null, null, null,
            null, null, null, null, false, 0, null)));
    }

    @AfterEach void cleanup() {
        if (context != null) context.revoke();
        if (server != null) server.disposeNow();
    }

    @Test void emitsHeadAndFirstChunkBeforeResponseCompletes() {
        var next = Sinks.<String>one();
        server = bind((request, response) -> response.header("Content-Type", "text/event-stream")
            .send(Flux.concat(Mono.just("data: first\n\n"), next.asMono())
                .map(text -> Unpooled.copiedBuffer(text, StandardCharsets.UTF_8)), ignored -> true));
        context = context(server.port());
        StepVerifier.create(context.requestStream("GET", "/events?token=private-query", Map.of(), null))
            .assertNext(event -> assertThat(((PluginHttpEvent.Head) event).status()).isEqualTo(200))
            .assertNext(event -> assertThat(text(event)).isEqualTo("data: first\n\n"))
            .then(() -> next.tryEmitValue("data: second\n\n"))
            .assertNext(event -> assertThat(text(event)).isEqualTo("data: second\n\n"))
            .verifyComplete();
        assertSessionRevoked();
        assertThat(logs.list("test-plugin", 0)).noneMatch(e -> e.message().contains("private-query"));
    }

    @Test void streamingDoesNotUseTheBufferedEightMiBLimit() {
        byte[] bytes = new byte[9 * 1024 * 1024];
        server = bind((request, response) -> response.sendByteArray(Mono.just(bytes)));
        context = context(server.port());
        var length = context.requestStream("GET", "/large", Map.of(), null).ofType(PluginHttpEvent.Data.class)
            .map(chunk -> (long) chunk.bytes().length).reduce(0L, Long::sum).block(Duration.ofSeconds(8));
        assertThat(length).isEqualTo(bytes.length);
        assertSessionRevoked();
    }

    @Test void emptyAndErrorResponsesPreserveStatusWithoutThrowing() {
        server = bind((request, response) -> request.uri().equals("/empty")
            ? response.status(204).send() : response.status(429).sendString(Mono.just("limited")));
        context = context(server.port());
        StepVerifier.create(context.requestStream("GET", "/empty", Map.of(), null))
            .assertNext(event -> assertThat(((PluginHttpEvent.Head) event).status()).isEqualTo(204)).verifyComplete();
        StepVerifier.create(context.requestStream("GET", "/limited", Map.of(), null))
            .assertNext(event -> assertThat(((PluginHttpEvent.Head) event).status()).isEqualTo(429))
            .assertNext(event -> assertThat(text(event)).isEqualTo("limited")).verifyComplete();
        assertSessionRevoked();
    }

    @Test void subscriberCancellationRevokesItsTemporarySession() {
        streamingServer();
        StepVerifier.create(context.requestStream("GET", "/events", Map.of(), null))
            .expectNextCount(1).thenCancel().verify();
        assertSessionRevoked();
    }

    @Test void contextRevocationTerminatesActiveStreamAndFutureSubscriptions() {
        streamingServer();
        var stream = context.requestStream("GET", "/events", Map.of(), null);
        StepVerifier.create(stream).expectNextCount(1).then(context::deactivate)
            .thenConsumeWhile(event -> event instanceof PluginHttpEvent.Data)
            .expectError(IllegalStateException.class).verify();
        assertSessionRevoked();
        StepVerifier.create(stream).expectError(IllegalStateException.class).verify();
        verify(sessions, times(1)).create(any());
    }

    @Test void idleTimeoutRevokesTemporarySession() {
        streamingServer();
        StepVerifier.withVirtualTime(() -> context.requestStream("GET", "/events", Map.of(), null))
            .expectNextCount(2).thenAwait(Duration.ofSeconds(61))
            .expectError(java.util.concurrent.TimeoutException.class).verify();
        assertSessionRevoked();
    }

    @Test void revocationAlsoTerminatesAnUnfinishedBufferedRequest() {
        var received = new CountDownLatch(1);
        server = bind((request, response) -> { received.countDown(); return response.sendString(Mono.never()); });
        context = context(server.port());
        StepVerifier.create(context.request("GET", "/waiting", Map.of(), null))
            .then(() -> {
                try { assertThat(received.await(3, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                context.deactivate();
            }).expectError(IllegalStateException.class).verify();
        assertSessionRevoked();
    }

    @Test void explicitApiKeyOverridesRootAuthorization() {
        server = bind((request, response) -> response.send());
        context = context(server.port());
        context.requestStream("GET", "/proxy", Map.of("Authorization", "Bearer custom-key"), null).blockLast(Duration.ofSeconds(3));
        assertThat(authorization.get()).isEqualTo("Bearer custom-key");
        verify(sessions).invalidateSession(anyString());
    }

    @Test void synchronousConstructionFailureCannotLeakSession() {
        context = context(1);
        StepVerifier.create(context.requestStream(null, "/status", Map.of(), null)).expectError(NullPointerException.class).verify();
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sessions).invalidateSession(token.capture());
        assertThatThrownBy(() -> sessions.verify(token.getValue())).isInstanceOf(ApiException.class);
    }

    @Test void cleanupDrainsSubscribedRequestsAndRevokesRootSession() throws Exception {
        streamingServer();
        var head = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var subscription = context.requestStream("GET", "/events", Map.of(), null)
            .doFinally(signal -> ended.countDown()).subscribe(event -> head.countDown(), ignored -> {});
        try {
            assertThat(head.await(3, TimeUnit.SECONDS)).isTrue();
            var report = context.cleanup(Duration.ofSeconds(2));
            assertThat(report.pendingRequests()).isZero();
            assertThat(report.complete()).isTrue();
            assertThat(ended.await(3, TimeUnit.SECONDS)).isTrue();
            assertSessionRevoked();
            StepVerifier.create(context.requestStream("GET", "/events", Map.of(), null))
                .expectError(IllegalStateException.class).verify();
        } finally { subscription.dispose(); }
    }

    private DisposableServer bind(java.util.function.BiFunction<reactor.netty.http.server.HttpServerRequest,
        reactor.netty.http.server.HttpServerResponse, org.reactivestreams.Publisher<Void>> handler) {
        return HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                authorization.set(request.requestHeaders().get("Authorization"));
                return handler.apply(request, response);
            }).bindNow();
    }

    private void streamingServer() {
        server = bind((request, response) -> response
            .send(Flux.concat(Mono.just(Unpooled.copiedBuffer("first", StandardCharsets.UTF_8)), Mono.never()), ignored -> true));
        context = context(server.port());
    }

    private PluginHostContext context(int port) {
        return new PluginHostContext(new PluginStore.Installed("test-plugin", "1", "", "", "", 1, false, "{}", Instant.now()),
            host, store, logs, Path.of("."), URI.create("http://127.0.0.1:" + port + "/"), new ObjectMapper());
    }

    private void assertSessionRevoked() {
        assertThat(authorization.get()).startsWith("Bearer adm-");
        assertThatThrownBy(() -> sessions.verify(authorization.get().substring(7))).isInstanceOf(ApiException.class);
    }

    private static String text(PluginHttpEvent event) {
        return new String(((PluginHttpEvent.Data) event).bytes(), StandardCharsets.UTF_8);
    }
}
