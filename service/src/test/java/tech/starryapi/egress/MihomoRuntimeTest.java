package tech.starryapi.egress;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
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
import tools.jackson.databind.ObjectMapper;

class MihomoRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicReference<String> lastPayload = new AtomicReference<>();
    private volatile int putStatus = 200;
    private volatile long putDelayMillis;
    private volatile String proxiesBody = "{\"proxies\":{}}";
    private volatile int proxiesStatus = 200;
    /** A node server the fake controller refuses, answering like Mihomo with the index of the entry. */
    private volatile String rejectedServer;
    private HttpServer controller;
    private EgressProperties properties;
    private MemoryStore store;
    private TestRuntime runtime;

    @BeforeEach void startController() throws Exception {
        controller = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        controller.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        controller.createContext("/configs", exchange -> {
            puts.incrementAndGet();
            lastPayload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(putDelayMillis); } catch (InterruptedException ignored) { }
            var proxies = json.readTree(json.readTree(lastPayload.get()).path("payload").asString("")).path("proxies");
            for (int i = 0; rejectedServer != null && i < proxies.size(); i++) {
                if (rejectedServer.equals(proxies.get(i).path("server").asString(""))) {
                    byte[] body = ("{\"message\":\"proxy " + i + ": '' has unset fields: uuid\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }
            }
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
        // Switched on the way deployments from before the console switch did it: the initial value, no stored choice.
        properties.setMihomoEnabled(true);
        properties.setControllerSecret("controller-secret");
        properties.setControllerUrl("http://127.0.0.1:" + controller.getAddress().getPort());
        properties.setActivationFailureBackoff(Duration.ofSeconds(30));
        store = new MemoryStore();
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

    @Test void aNodeMihomoRejectsIsSetAsideAndTheOthersStillActivate() {
        MihomoRuntime.Route first = runtime.add(20001);
        MihomoRuntime.Route bad = runtime.add(20002);
        MihomoRuntime.Route third = runtime.add(20003);
        rejectedServer = "node20002.example";

        assertThat(runtime.route(first.id()).block(Duration.ofSeconds(5))).isEqualTo(first);
        assertThat(runtime.route(third.id()).block(Duration.ofSeconds(5))).isEqualTo(third);
        assertThat(runtime.rejected).containsExactly(bad.id());
        assertThat(runtime.markedFailed).isEmpty();
        assertThat(lastPayload.get()).doesNotContain(bad.id().toString());
        assertThat(runtime.health().lastFailure()).isNull();
        // The rejected node is no longer offered (its row now carries the configuration issue).
        assertThatThrownBy(() -> runtime.route(bad.id()).block(Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(EgressException.class, error -> assertThat(error.failure()).isEqualTo(EgressFailure.NODE_UNAVAILABLE));
    }

    @Test void aRejectionThatNamesNoNodeStillFailsTheWholeActivation() {
        MihomoRuntime.Route route = runtime.add(20001);
        putStatus = 400;
        assertThatThrownBy(() -> runtime.route(route.id()).block(Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(EgressException.class, error -> assertThat(error.failure()).isEqualTo(EgressFailure.CONFIG_REJECTED));
        assertThat(runtime.rejected).isEmpty();
        assertThat(runtime.markedFailed).containsExactly(EgressFailure.CONFIG_REJECTED);
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

    // ---- the switch: available (deployed) and enabled (switched on by an administrator) ----

    @Test void mihomoIsNotAvailableWithoutAControllerSecretWhateverIsStoredOrConfigured() {
        properties.setControllerSecret("");
        store.value = true;
        runtime.loadChoice();
        MihomoRuntime.Route route = runtime.add(20001);

        assertThat(runtime.available()).isFalse();
        assertThat(runtime.enabled()).isFalse();
        assertThatThrownBy(() -> runtime.route(route.id()).block(Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(EgressException.class, error -> assertThat(error.failure()).isEqualTo(EgressFailure.RUNTIME_DISABLED));
        runtime.reconcile().block(Duration.ofSeconds(5));
        int readsBeforePoll = store.reads.get();
        runtime.poll();
        assertThat(puts).hasValue(0);
        assertThat(store.reads).as("a deployment without Mihomo has nothing to poll").hasValue(readsBeforePoll);
        // A blank secret is no secret, and neither is a missing one.
        properties.setControllerSecret("   ");
        assertThat(runtime.available()).isFalse();
        properties.setControllerSecret(null);
        assertThat(runtime.available()).isFalse();
    }

    @Test void theConfiguredValueIsTheInitialSwitchUntilAnAdministratorHasChosen() {
        assertThat(runtime.available()).isTrue();
        assertThat(runtime.enabled()).as("the old environment switch on, no stored choice").isTrue();
        properties.setMihomoEnabled(false);
        assertThat(runtime.enabled()).as("the old environment switch off, no stored choice").isFalse();
        // Reading a store in which nobody has used the switch yet changes nothing.
        runtime.loadChoice();
        assertThat(runtime.enabled()).isFalse();
    }

    @Test void aStoredChoiceWinsOverTheConfiguredValueInBothDirections() {
        store.value = false;
        runtime.loadChoice();
        assertThat(runtime.enabled()).as("stored off beats the old switch on").isFalse();

        properties.setMihomoEnabled(false);
        store.value = true;
        runtime.loadChoice();
        assertThat(runtime.enabled()).as("stored on beats the old switch off").isTrue();
    }

    @Test void startingWithoutADatabaseAnswerFallsBackToTheConfiguredValue() {
        store.failReads = true;
        runtime.loadChoice();
        assertThat(runtime.enabled()).isTrue();
        properties.setMihomoEnabled(false);
        assertThat(runtime.enabled()).isFalse();
    }

    @Test void switchingOffFailsRoutesAsNotSentAndLeavesTheControllerAlone() {
        MihomoRuntime.Route route = runtime.add(20001);
        assertThat(runtime.route(route.id()).block(Duration.ofSeconds(5))).isEqualTo(route);
        assertThat(puts).hasValue(1);
        assertThat(runtime.health().activeNodes()).isEqualTo(1);

        runtime.setEnabled(false).block(Duration.ofSeconds(5));

        assertThat(store.value).isFalse();
        assertThat(runtime.enabled()).isFalse();
        assertThat(runtime.available()).isTrue();
        assertThatThrownBy(() -> runtime.route(route.id()).block(Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(EgressException.class, error -> {
                assertThat(error.failure()).isEqualTo(EgressFailure.RUNTIME_DISABLED);
                assertThat(error.requestMayHaveBeenSent()).as("not sent: the request fails over to another account").isFalse();
            });
        assertThatThrownBy(() -> runtime.ensureActive(route).block(Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(EgressException.class, error -> assertThat(error.failure()).isEqualTo(EgressFailure.RUNTIME_DISABLED));
        runtime.reconcile().block(Duration.ofSeconds(5));
        runtime.poll();
        assertThat(puts).as("nothing is sent to the component while it is switched off").hasValue(1);
        assertThat(runtime.active(route.id())).as("what was active is forgotten").isFalse();
        assertThat(runtime.health().activeNodes()).isZero();
    }

    @Test void switchingOnLoadsTheConfigurationAgainEvenThoughItDidNotChange() {
        MihomoRuntime.Route route = runtime.add(20001);
        assertThat(runtime.route(route.id()).block(Duration.ofSeconds(5))).isEqualTo(route);
        runtime.setEnabled(false).block(Duration.ofSeconds(5));
        assertThat(puts).hasValue(1);

        runtime.setEnabled(true).block(Duration.ofSeconds(5));

        assertThat(store.value).isTrue();
        assertThat(runtime.enabled()).isTrue();
        assertThat(puts).as("the Mihomo process may have changed while it was switched off").hasValue(2);
        assertThat(runtime.health().activeNodes()).isEqualTo(1);
        assertThat(runtime.active(route.id())).isTrue();
        assertThat(runtime.route(route.id()).block(Duration.ofSeconds(5))).isEqualTo(route);
        assertThat(puts).hasValue(2);
    }

    @Test void switchingOnWhenTheComponentIsDownStillSucceedsAndReportsTheFailureAsHealth() {
        properties.setMihomoEnabled(false);
        controller.stop(0);
        runtime.add(20001);

        runtime.setEnabled(true).block(Duration.ofSeconds(10));

        assertThat(runtime.enabled()).isTrue();
        assertThat(store.value).isTrue();
        assertThat(runtime.health().lastFailure()).isEqualTo(EgressFailure.CONTROLLER_UNREACHABLE);
    }

    @Test void aSwitchThatCannotBeStoredChangesNothing() {
        store.failWrites = true;
        assertThatThrownBy(() -> runtime.setEnabled(false).block(Duration.ofSeconds(5))).hasMessageContaining("database down");
        assertThat(runtime.enabled()).isTrue();
    }

    @Test void thePollTakesOverAChangeMadeOnAnotherInstance() {
        properties.setMihomoEnabled(false);
        store.value = true;
        MihomoRuntime.Route route = runtime.add(20001);
        proxiesBody = "{\"proxies\":{\"node-" + route.id() + "\":{}}}";
        assertThat(runtime.enabled()).as("not read yet").isFalse();

        runtime.poll();
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.enabled() && runtime.health().activeNodes() == 1);
        assertThat(puts).hasValue(1);

        store.value = false;
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            runtime.poll();
            return !runtime.enabled();
        });
        assertThat(runtime.active(route.id())).isFalse();
        assertThat(puts).hasValue(1);

        store.value = true;
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            runtime.poll();
            return runtime.enabled() && runtime.active(route.id());
        });
        assertThat(puts).as("switched on again: loaded again").hasValue(2);
    }

    @Test void aDatabaseFailureInThePollKeepsTheCachedChoiceAndIsNotAControllerFailure() {
        properties.setMihomoEnabled(false);
        store.value = true;
        runtime.loadChoice();
        assertThat(runtime.enabled()).isTrue();
        MihomoRuntime.Route route = runtime.add(20001);
        proxiesBody = "{\"proxies\":{\"node-" + route.id() + "\":{}}}";
        store.failReads = true;

        await().atMost(Duration.ofSeconds(5)).until(() -> {
            runtime.poll();
            return runtime.health().activeNodes() == 1;
        });

        assertThat(store.reads.get()).isGreaterThan(1);
        assertThat(runtime.enabled()).isTrue();
        assertThat(runtime.health().lastFailure()).isNull();
        assertThat(runtime.health().controllerReachable()).isTrue();
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
        final List<UUID> rejected = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<MihomoRuntime.Route> routes = new java.util.concurrent.CopyOnWriteArrayList<>();

        TestRuntime(EgressProperties properties) { super(null, store, json, properties); }

        MihomoRuntime.Route add(int port) {
            MihomoRuntime.Route route = new MihomoRuntime.Route(UUID.randomUUID(), port, json.createObjectNode().put("type", "vless").put("server", "node" + port + ".example"));
            routes.add(route);
            return route;
        }

        @Override Mono<List<MihomoRuntime.Route>> rows() { return Mono.fromSupplier(() -> { snapshots.incrementAndGet(); return List.copyOf(routes); }); }
        @Override Mono<MihomoRuntime.Route> find(UUID id) { return Mono.justOrEmpty(routes.stream().filter(route -> route.id().equals(id)).findFirst()); }
        @Override Mono<Void> markActive(List<MihomoRuntime.Route> active) { return Mono.empty(); }
        @Override Mono<Void> markFailed(EgressFailure failure) { markedFailed.add(failure); return Mono.empty(); }
        @Override Mono<Void> markRejected(MihomoRuntime.Route route) {
            return Mono.fromRunnable(() -> { rejected.add(route.id()); routes.remove(route); });
        }
    }

    /** The setting row, kept in memory; "no value" is a switch nobody has used yet. */
    private static final class MemoryStore extends MihomoSwitchStore {
        volatile Boolean value;
        volatile boolean failReads;
        volatile boolean failWrites;
        final AtomicInteger reads = new AtomicInteger();

        MemoryStore() { super(null); }

        @Override public Mono<Boolean> read() {
            return Mono.defer(() -> {
                reads.incrementAndGet();
                return failReads ? Mono.<Boolean>error(new IllegalStateException("database down")) : Mono.justOrEmpty(value);
            });
        }

        @Override public Mono<Void> write(boolean enabled) {
            return Mono.defer(() -> failWrites ? Mono.<Void>error(new IllegalStateException("database down"))
                : Mono.fromRunnable(() -> value = enabled));
        }
    }
}
