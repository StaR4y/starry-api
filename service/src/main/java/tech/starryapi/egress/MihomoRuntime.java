package tech.starryapi.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Component
public class MihomoRuntime {
    private static final Logger log = LoggerFactory.getLogger(MihomoRuntime.class);
    private final DatabaseClient db;
    private final ObjectMapper mapper;
    private final EgressProperties properties;
    private final WebClient controller;
    private final AtomicBoolean polling = new AtomicBoolean();
    // At most one activation runs and one more waits. Everyone who asks while one waits joins it, because it will
    // read the database after their change was committed; nobody queues an activation of their own.
    private Activation running;
    private Activation queued;
    private volatile String activeConfig = "";
    private volatile Map<UUID, Route> active = Map.of();
    // The outstanding failure. After a failed activation the request path fails fast until retryNotBefore instead of
    // making every request wait for another activation attempt; the poller and administrator changes still retry.
    private volatile Failure lastFailure;
    private volatile long retryNotBefore;
    private volatile Boolean controllerReachable;
    private volatile Instant lastPollAt;
    private volatile Instant lastActivatedAt;

    public MihomoRuntime(DatabaseClient db, ObjectMapper mapper, EgressProperties properties) {
        this.db = db;
        this.mapper = mapper;
        this.properties = properties;
        if (properties.isMihomoEnabled() && properties.getControllerSecret().isBlank()) {
            throw new IllegalArgumentException("Egress controller secret is required when Mihomo is enabled");
        }
        this.controller = WebClient.builder().baseUrl(properties.getControllerUrl())
            .defaultHeaders(h -> h.setBearerAuth(properties.getControllerSecret())).build();
    }

    public Mono<Route> route(UUID id) {
        if (!properties.isMihomoEnabled()) return Mono.error(new EgressException(EgressFailure.RUNTIME_DISABLED));
        return find(id)
            .switchIfEmpty(Mono.error(new EgressException(EgressFailure.NODE_UNAVAILABLE)))
            .flatMap(this::ensureActive);
    }

    /** The route, once this instance's Mihomo has loaded exactly the node configuration that was just read. */
    public Mono<Route> ensureActive(Route requested) {
        if (!properties.isMihomoEnabled()) return Mono.error(new EgressException(EgressFailure.RUNTIME_DISABLED));
        Route loaded = active.get(requested.id());
        if (requested.equals(loaded)) return Mono.just(loaded);
        EgressFailure blocked = failFastReason();
        if (blocked != null) return Mono.error(new EgressException(blocked));
        return activation(false).then(Mono.defer(() -> {
            Route activated = active.get(requested.id());
            return requested.equals(activated) ? Mono.just(activated)
                : Mono.error(new EgressException(EgressFailure.NODE_NOT_ACTIVE));
        }));
    }

    /** Applies the committed channels now. Used by administrator changes and the poller, so never skipped by the backoff. */
    public Mono<Void> reconcile() { return Mono.defer(() -> activation(true)); }

    private Mono<Void> activation(boolean forced) {
        if (!properties.isMihomoEnabled()) return Mono.empty();
        Activation joined;
        synchronized (this) {
            if (queued == null) queued = new Activation();
            queued.forced |= forced;
            joined = queued;
            if (running == null) startQueued();
        }
        return joined.done.asMono();
    }

    // Called with the lock held. The database snapshot is read when the activation starts, not when it was requested.
    private void startQueued() {
        Activation next = queued;
        queued = null;
        running = next;
        Mono.defer(() -> {
            EgressFailure blocked = next.forced ? null : failFastReason();
            return blocked != null ? Mono.<Void>error(new EgressException(blocked)) : activate();
        }).subscribe(ignored -> {}, error -> finish(next, error), () -> finish(next, null));
    }

    private void finish(Activation finished, Throwable error) {
        synchronized (this) {
            running = null;
            if (queued != null) startQueued();
        }
        if (error == null) finished.done.tryEmitEmpty(); else finished.done.tryEmitError(error);
    }

    private EgressFailure failFastReason() {
        Failure failure = lastFailure;
        return failure != null && System.nanoTime() - retryNotBefore < 0 ? failure.code() : null;
    }

    private void failed(EgressFailure failure) {
        lastFailure = new Failure(failure, Instant.now());
        retryNotBefore = System.nanoTime() + properties.getActivationFailureBackoff().toNanos();
    }

    private Mono<Void> activate() {
        return rows().flatMap(rows -> {
            String config = configuration(rows).toString(); // JSON is valid YAML, with no string interpolation.
            if (config.equals(activeConfig)) return Mono.empty();
            return controller.put().uri("/configs?force=true").bodyValue(Map.of("payload", config))
                .exchangeToMono(response -> response.releaseBody().then(response.statusCode().is2xxSuccessful()
                    ? Mono.<Void>empty() : Mono.<Void>error(new EgressException(
                        response.statusCode().value() == 401 || response.statusCode().value() == 403
                            ? EgressFailure.CONTROLLER_AUTH_FAILED : EgressFailure.CONFIG_REJECTED))))
                .timeout(Duration.ofSeconds(20))
                .onErrorMap(error -> error instanceof EgressException ? error
                    : new EgressException(EgressFailure.CONTROLLER_UNREACHABLE))
                .then(markActive(rows))
                .doOnSuccess(ignored -> {
                    active = rows.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Route::id, r -> r));
                    activeConfig = config;
                    lastActivatedAt = Instant.now();
                    lastFailure = null;
                    log.info("Activated egress configuration: nodes={}", rows.size());
                })
                .onErrorResume(error -> {
                    active = Map.of();
                    activeConfig = "";
                    EgressFailure failure = EgressFailure.classify(error, EgressFailure.RUNTIME_FAILED);
                    failed(failure);
                    return markFailed(failure).then(Mono.error(new EgressException(failure)));
                });
        });
    }

    ObjectNode configuration(List<Route> rows) {
        ObjectNode config = mapper.createObjectNode();
        config.put("allow-lan", false).put("mode", "rule").put("log-level", "silent")
            .put("external-controller", properties.getControllerBind()).put("secret", properties.getControllerSecret());
        if (!properties.getInterfaceName().isBlank()) config.put("interface-name", properties.getInterfaceName());
        config.putObject("profile").put("store-selected", false).put("store-fake-ip", false);
        config.putArray("rules").add("MATCH,REJECT");
        var nodes = config.putArray("proxies");
        var listeners = config.putArray("listeners");
        for (Route route : rows) {
            String internalName = "node-" + route.id();
            nodes.add(((ObjectNode) route.config().deepCopy()).put("name", internalName));
            listeners.addObject().put("name", internalName).put("type", "http")
                .put("listen", properties.getListenerBind()).put("port", route.port()).put("proxy", internalName);
        }
        return config;
    }

    // Persistence, separate from the activation logic so that it can be exercised on its own. Only nodes that need
    // Mihomo are here; plain HTTP and SOCKS5 nodes are reached directly and are not part of its configuration.
    Mono<List<Route>> rows() {
        return db.sql("SELECT channel_id,listener_port,node_config::text AS config FROM proxy_channels WHERE route='MIHOMO' AND enabled AND source_present AND config_issue IS NULL ORDER BY channel_id")
            .map((r, m) -> readRoute(r)).all().collectList();
    }

    Mono<Route> find(UUID id) {
        return db.sql("SELECT channel_id,listener_port,node_config::text AS config FROM proxy_channels WHERE channel_id=:id AND route='MIHOMO' AND enabled AND source_present AND config_issue IS NULL")
            .bind("id", id).map((r, m) -> readRoute(r)).one();
    }

    Mono<Void> markActive(List<Route> rows) {
        return reactor.core.publisher.Flux.fromIterable(rows).concatMap(route ->
            db.sql("UPDATE proxy_channels SET runtime_status='ACTIVE',runtime_error=NULL WHERE channel_id=:id AND node_config=:config::jsonb AND route='MIHOMO' AND enabled AND source_present AND config_issue IS NULL")
                .bind("id", route.id()).bind("config", route.config().toString()).fetch().rowsUpdated()).then();
    }

    Mono<Void> markFailed(EgressFailure failure) {
        return db.sql("UPDATE proxy_channels SET runtime_status='ERROR',runtime_error=:error WHERE route='MIHOMO' AND enabled AND source_present AND config_issue IS NULL")
            .bind("error", failure.message()).fetch().rowsUpdated().then();
    }

    private Route readRoute(io.r2dbc.spi.Row row) {
        try { return new Route(row.get("channel_id", UUID.class), row.get("listener_port", Integer.class), mapper.readTree(row.get("config", String.class))); }
        catch (Exception error) { throw EgressException.notSent(EgressFailure.NODE_UNAVAILABLE, error); }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 5000)
    public void poll() {
        if (!properties.isMihomoEnabled() || !polling.compareAndSet(false, true)) return;
        // Re-apply after a component restart, which starts with no account listeners.
        controller.get().uri("/proxies").retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(5))
            .doOnNext(state -> {
                controllerReachable = true;
                lastPollAt = Instant.now();
                if (active.keySet().stream().anyMatch(id -> !state.path("proxies").has("node-" + id))) activeConfig = "";
            })
            .then(Mono.defer(this::reconcile))
            .doFinally(signal -> polling.set(false))
            .subscribe(ignored -> {}, error -> {
                activeConfig = "";
                active = Map.of();
                if (!(error instanceof EgressException)) {
                    // The controller itself could not be queried; an EgressException was already recorded by the activation.
                    // A controller that answers 401/403 is running but has another secret.
                    boolean refused = error instanceof WebClientResponseException response
                        && (response.getStatusCode().value() == 401 || response.getStatusCode().value() == 403);
                    controllerReachable = refused;
                    lastPollAt = Instant.now();
                    failed(refused ? EgressFailure.CONTROLLER_AUTH_FAILED : EgressFailure.CONTROLLER_UNREACHABLE);
                }
                log.warn("Proxy runtime reconciliation failed: {}", error instanceof EgressException ? error.getMessage() : "runtime unavailable");
            });
    }

    public boolean active(UUID id) { return active.containsKey(id); }

    /** What the administrator needs to judge the runtime; failures are the fixed diagnostics only. */
    public Health health() {
        Failure failure = lastFailure;
        return new Health(controllerReachable, lastPollAt, lastActivatedAt, active.size(),
            failure == null ? null : failure.code(), failure == null ? null : failure.at());
    }

    public record Route(UUID id, int port, JsonNode config) {}
    public record Health(Boolean controllerReachable, Instant lastPollAt, Instant lastActivatedAt,
                         int activeNodes, EgressFailure lastFailure, Instant lastFailureAt) {}
    private record Failure(EgressFailure code, Instant at) {}
    private static final class Activation {
        final Sinks.Empty<Void> done = Sinks.empty();
        boolean forced;
    }
}
