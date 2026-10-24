package tech.starryapi.egress;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class MihomoRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicReference<String> lastPayload = new AtomicReference<>();
    private volatile int putStatus = 200;
    private volatile long putDelayMillis;
    private volatile String proxiesBody = "{\"proxies\":{}}";
    private volatile int proxiesStatus = 200;
    private HttpServer controller;
    private EgressProperties properties;
    private TestRuntime runtime;

    @BeforeEach void startController() throws Exception {
        controller = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        controller.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        controller.createContext("/configs", exchange -> {
            puts.incrementAndGet();
            lastPayload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(putDelayMillis); } catch (InterruptedException ignored) { }
            exchange.sendResponseHeaders(putStatus, -1);
            exchange.close();
        });
        controller.createContext("/proxies", exchange -> {
            byte[] body = proxiesBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(proxiesStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        controller.start();
        properties = new EgressProperties();
        properties.setMihomoEnabled(true);
        properties.setControllerSecret("controller-secret");
        properties.setControllerUrl("http://127.0.0.1:" + controller.getAddress().getPort());
        properties.setActivationFailureBackoff(Duration.ofSeconds(30));
        runtime = new TestRuntime(properties);
    }

    @AfterEach void stopController() { controller.stop(0); }

    @Test void concurrentRequestsShareOneActivationAndThenFailFastDuringTheBackoff() {
        MihomoRuntime.Route route = runtime.add(20001);
        putStatus = 500;
        putDelayMillis = 300;

        long started = System.nanoTime();
        var outcomes = concurrentRoutes(route, 20);

        assertThat(outcomes).hasSize(20).allSatisfy(error -> assertThat(error)
            .isInstanceOfSatisfying(EgressException.class, egress -> {
                assertThat(egress.failure()).isEqualTo(EgressFailure.CONFIG_REJECTED);
                assertThat(egress.requestMayHaveBeenSent()).isFalse();
            }));
        // Twenty requests used to queue twenty activations behind each other; now they share the one that was running.
        assertThat(puts).hasValue(1);
        assertThat(runtime.snapshots).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

        // Within the backoff the request path does not even look at the controller.
        assertThat(concurrentRoutes(route, 10)).hasSize(10);
        assertThat(puts).hasValue(1);
        assertThat(runtime.snapshots).hasValue(1);
        assertThat(runtime.health().lastFailure()).isEqualTo(EgressFailure.CONFIG_REJECTED);
        assertThat(runtime.health().lastFailureAt()).isNotNull();
    }

    @Test void anUnreachableControllerFailsRequestsFastWithoutAnAttemptPerRequest() {
        controller.stop(0);
        MihomoRuntime.Route route = runtime.add(20001);

        var outcomes = concurrentRoutes(route, 20);

        assertThat(outcomes).hasSize(20).allSatisfy(error -> assertThat(((EgressException) error).failure())
            .isEqualTo(EgressFailure.CONTROLLER_UNREACHABLE));
        assertThat(runtime.snapshots).hasValue(1);
        assertThat(runtime.markedFailed).containsExactly(EgressFailure.CONTROLLER_UNREACHABLE);
    }

    @Test void afterTheBackoffTheNextRequestActivatesAgainAndTheFailureClears() throws Exception {
        properties.setActivationFailureBackoff(Duration.ofMillis(250));
        MihomoRuntime.Route route = runtime.add(20001);
        putStatus = 500;
        assertThat(concurrentRoutes(route, 3)).hasSize(3);
        assertThat(puts).hasValue(1);

        putStatus = 200;
        Thread.sleep(300);
        assertThat(runtime.route(route.id()).block(Duration.ofSeconds(5))).isEqualTo(route);

        assertThat(puts).hasValue(2);
        assertThat(runtime.health().lastFailure()).isNull();
        assertThat(runtime.health().activeNodes()).isEqualTo(1);
        assertThat(runtime.health().lastActivatedAt()).isNotNull();
        assertThat(lastPayload.get()).contains("node-" + route.id()).contains("20001");
    }

    @Test void administratorChangesAndThePollerRetryEvenDuringTheBackoff() {
        MihomoRuntime.Route route = runtime.add(20001);
        putStatus = 500;
        assertThat(concurrentRoutes(route, 1)).hasSize(1);
        assertThat(puts).hasValue(1);

        putStatus = 200;
        runtime.reconcile().block(Duration.ofSeconds(5));

        assertThat(puts).hasValue(2);
        assertThat(runtime.health().lastFailure()).isNull();
        assertThat(runtime.route(route.id()).block(Duration.ofSeconds(5))).isEqualTo(route);
        assertThat(puts).as("an active node needs no further activation").hasValue(2);
    }

    @Test void anActivationRequestedWhileOneRunsStillReadsTheDatabaseAfterTheChange() {
        MihomoRuntime.Route first = runtime.add(20001);
        putDelayMillis = 400;
        var running = runtime.reconcile().subscribeOn(Schedulers.boundedElastic()).toFuture();
        await().atMost(Duration.ofSeconds(5)).until(() -> puts.get() == 1);

        // Committed while the first activation is already past its snapshot: callers must not be satisfied by it.
        MihomoRuntime.Route second = runtime.add(20002);
        var joined = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (int i = 0; i < 10; i++) joined.add(runtime.reconcile().toFuture());
        joined.forEach(future -> future.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join());
        running.join();

        assertThat(puts).hasValue(2);
        assertThat(runtime.snapshots).as("one snapshot per activation, not one per caller").hasValue(2);
        assertThat(lastPayload.get()).contains(first.id().toString()).contains(second.id().toString());
    }

    @Test void anUnchangedConfigurationIsNotPushedAgain() {
        runtime.add(20001);
        runtime.reconcile().block(Duration.ofSeconds(5));
        runtime.reconcile().block(Duration.ofSeconds(5));
        assertThat(puts).hasValue(1);
    }

    @Test void theRuntimeReportsPollsActivationsAndFailuresWithoutTransportDetails() {
        MihomoRuntime.Route route = runtime.add(20001);
        proxiesBody = "{\"proxies\":{\"node-" + route.id() + "\":{}}}";
        assertThat(runtime.health().controllerReachable()).isNull();

        runtime.poll();
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.health().lastActivatedAt() != null);
        var healthy = runtime.health();
        assertThat(healthy.controllerReachable()).isTrue();
        assertThat(healthy.lastPollAt()).isNotNull();
        assertThat(healthy.activeNodes()).isEqualTo(1);
        assertThat(healthy.lastFailure()).isNull();

        controller.stop(0);
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            runtime.poll();
            return Boolean.FALSE.equals(runtime.health().controllerReachable());
        });
        var down = runtime.health();
        assertThat(down.lastFailure()).isEqualTo(EgressFailure.CONTROLLER_UNREACHABLE);
        assertThat(down.activeNodes()).isZero();
        assertThat(down.lastFailureAt()).isNotNull();
    }

    @Test void aControllerThatRejectsTheSecretIsReportedAsReachableWithAnAuthenticationFailure() {
        proxiesStatus = 401;
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            runtime.poll();
            return runtime.health().lastFailure() != null;
        });
        var health = runtime.health();
        assertThat(health.controllerReachable()).isTrue();
        assertThat(health.lastFailure()).isEqualTo(EgressFailure.CONTROLLER_AUTH_FAILED);
    }

    @Test void aDisabledRuntimeNeverTouchesTheController() {
        properties.setMihomoEnabled(false);
        MihomoRuntime.Route route = runtime.add(20001);
        assertThatThrownBy(() -> runtime.route(route.id()).block(Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(EgressException.class, error -> assertThat(error.failure()).isEqualTo(EgressFailure.RUNTIME_DISABLED));
        runtime.reconcile().block(Duration.ofSeconds(5));
        assertThat(puts).hasValue(0);
        assertThat(runtime.snapshots).hasValue(0);
    }

    private List<Throwable> concurrentRoutes(MihomoRuntime.Route route, int callers) {
        return Flux.range(0, callers).flatMap(i -> runtime.route(route.id()).map(ignored -> (Throwable) new AssertionError("must fail"))
                .onErrorResume(Mono::just).subscribeOn(Schedulers.boundedElastic()), callers)
            .collectList().block(Duration.ofSeconds(20));
    }

    /** The persistence seams are replaced by memory, so that only the activation logic is under test. */
    private final class TestRuntime extends MihomoRuntime {
        final AtomicInteger snapshots = new AtomicInteger();
        final List<EgressFailure> markedFailed = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<MihomoRuntime.Route> routes = new java.util.concurrent.CopyOnWriteArrayList<>();

        TestRuntime(EgressProperties properties) { super(null, json, properties); }

        MihomoRuntime.Route add(int port) {
            MihomoRuntime.Route route = new MihomoRuntime.Route(UUID.randomUUID(), port, json.createObjectNode().put("type", "vless").put("server", "node" + port + ".example"));
            routes.add(route);
            return route;
        }

        @Override Mono<List<MihomoRuntime.Route>> rows() { return Mono.fromSupplier(() -> { snapshots.incrementAndGet(); return List.copyOf(routes); }); }
        @Override Mono<MihomoRuntime.Route> find(UUID id) { return Mono.justOrEmpty(routes.stream().filter(route -> route.id().equals(id)).findFirst()); }
        @Override Mono<Void> markActive(List<MihomoRuntime.Route> active) { return Mono.empty(); }
        @Override Mono<Void> markFailed(EgressFailure failure) { markedFailed.add(failure); return Mono.empty(); }
    }
}
