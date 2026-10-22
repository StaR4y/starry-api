package tech.starryapi.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
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
import reactor.core.publisher.Mono;

@Component
public class MihomoRuntime {
    private static final Logger log = LoggerFactory.getLogger(MihomoRuntime.class);
    private final DatabaseClient db;
    private final ObjectMapper mapper;
    private final EgressProperties properties;
    private final WebClient controller;
    private final AtomicBoolean polling = new AtomicBoolean();
    private Mono<Void> pending;
    private volatile String activeConfig = "";
    private volatile Map<UUID, Route> active = Map.of();

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
        if (!properties.isMihomoEnabled()) return Mono.error(new EgressException("Proxy runtime is disabled"));
        return db.sql("SELECT channel_id,listener_port,node_config::text AS config FROM proxy_channels WHERE channel_id=:id AND enabled AND source_present AND config_issue IS NULL")
            .bind("id", id).map((r, m) -> readRoute(r)).one()
            .switchIfEmpty(Mono.error(new EgressException("Selected proxy node is disabled, missing or unsupported")))
            .flatMap(requested -> {
            Route loaded = active.get(id);
            if (requested.equals(loaded)) return Mono.just(loaded);
            return reconcile().then(Mono.defer(() -> {
                Route activated = active.get(id);
                return requested.equals(activated) ? Mono.just(activated)
                    : Mono.error(new EgressException("Selected proxy node is not active"));
            }));
        });
    }

    public Mono<Void> reconcile() { return Mono.defer(this::enqueueActivation); }

    // Serialize reloads and read the committed database snapshot only when this activation starts.
    private synchronized Mono<Void> enqueueActivation() {
        if (!properties.isMihomoEnabled()) return Mono.empty();
        Mono<Void> previous = pending == null ? Mono.empty() : pending.onErrorResume(error -> Mono.empty());
        @SuppressWarnings("unchecked") Mono<Void>[] current = new Mono[1];
        current[0] = previous.then(Mono.defer(this::activate)).doFinally(signal -> clearPending(current[0])).cache();
        pending = current[0];
        return pending;
    }

    private synchronized void clearPending(Mono<Void> completed) { if (pending == completed) pending = null; }

    private Mono<Void> activate() {
        return rows().flatMap(rows -> {
            String config = configuration(rows).toString(); // JSON is valid YAML, with no string interpolation.
            if (config.equals(activeConfig)) return Mono.empty();
            return controller.put().uri("/configs?force=true").bodyValue(Map.of("payload", config))
                .exchangeToMono(response -> response.releaseBody().then(response.statusCode().is2xxSuccessful()
                    ? Mono.<Void>empty() : Mono.<Void>error(new EgressException("Proxy runtime rejected configuration (HTTP " + response.statusCode().value() + ")"))))
                .timeout(Duration.ofSeconds(20))
                .thenMany(reactor.core.publisher.Flux.fromIterable(rows).concatMap(route ->
                    db.sql("UPDATE proxy_channels SET runtime_status='ACTIVE',runtime_error=NULL WHERE channel_id=:id AND node_config=:config::jsonb AND enabled AND source_present AND config_issue IS NULL")
                        .bind("id", route.id()).bind("config", route.config().toString()).fetch().rowsUpdated()))
                .then()
                .doOnSuccess(ignored -> {
                    active = rows.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Route::id, r -> r));
                    activeConfig = config;
                    log.info("Activated egress configuration: nodes={}", rows.size());
                })
                .onErrorResume(error -> {
                    active = Map.of();
                    activeConfig = "";
                    return db.sql("UPDATE proxy_channels SET runtime_status='ERROR',runtime_error='Proxy runtime could not activate the configuration' WHERE enabled AND source_present AND config_issue IS NULL")
                        .fetch().rowsUpdated().then(Mono.error(new EgressException("Proxy runtime could not activate the configuration")));
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

    private Mono<List<Route>> rows() {
        return db.sql("SELECT channel_id,listener_port,node_config::text AS config FROM proxy_channels WHERE enabled AND source_present AND config_issue IS NULL ORDER BY channel_id")
            .map((r, m) -> readRoute(r)).all().collectList();
    }

    private Route readRoute(io.r2dbc.spi.Row row) {
        try { return new Route(row.get("channel_id", UUID.class), row.get("listener_port", Integer.class), mapper.readTree(row.get("config", String.class))); }
        catch (Exception error) { throw new EgressException("Invalid stored proxy configuration"); }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 5000)
    public void poll() {
        if (!properties.isMihomoEnabled() || !polling.compareAndSet(false, true)) return;
        // Re-apply after a component restart, which starts with no account listeners.
        controller.get().uri("/proxies").retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(5))
            .doOnNext(state -> { if (active.keySet().stream().anyMatch(id -> !state.path("proxies").has("node-" + id))) activeConfig = ""; })
            .then(Mono.defer(this::reconcile))
            .doFinally(signal -> polling.set(false))
            .subscribe(ignored -> {}, error -> { activeConfig = ""; active = Map.of(); log.warn("Proxy runtime reconciliation failed: {}", error instanceof EgressException ? error.getMessage() : "runtime unavailable"); });
    }

    public boolean active(UUID id) { return active.containsKey(id); }
    public record Route(UUID id, int port, JsonNode config) {}
}
