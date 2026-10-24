package tech.starryapi.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;
import tech.starryapi.egress.ClashNodes.Node;

@Service
public class ProxyChannelService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProxyChannelService.class);
    private final DatabaseClient db;
    private final ObjectMapper mapper;
    private final ClashNodes parser;
    private final SubscriptionFetcher fetcher;
    private final EgressProperties properties;
    private final MihomoRuntime runtime;
    private final ChannelRoutes routes;
    private final org.springframework.transaction.reactive.TransactionalOperator transactions;

    public ProxyChannelService(DatabaseClient db, ObjectMapper mapper, ClashNodes parser,
                               SubscriptionFetcher fetcher, EgressProperties properties, MihomoRuntime runtime,
                               ChannelRoutes routes,
                               org.springframework.transaction.ReactiveTransactionManager transactionManager) {
        this.db = db; this.mapper = mapper; this.parser = parser; this.fetcher = fetcher; this.properties = properties;
        this.runtime = runtime; this.routes = routes;
        this.transactions = org.springframework.transaction.reactive.TransactionalOperator.create(transactionManager);
    }

    private static final String CHANNEL_SELECT = """
        SELECT c.channel_id, c.subscription_id, s.name AS subscription_name, c.name, c.protocol, c.route, c.enabled,
               c.runtime_status, c.runtime_error, c.config_issue, c.source_present, c.last_tested_at, c.last_test_result,
               c.last_test_exit_ip, c.last_test_latency_ms, c.last_test_error_code,
               count(a.account_id) AS bound_accounts, c.updated_at
        FROM proxy_channels c
        LEFT JOIN proxy_subscriptions s ON s.subscription_id = c.subscription_id
        LEFT JOIN proxy_accounts a ON a.proxy_channel_id = c.channel_id
        """;
    private static final String SUBSCRIPTION_SELECT = """
        SELECT s.subscription_id, s.name, s.status, s.last_synced_at, s.last_error, s.subscription_url NOT LIKE 'inline:%' AS refreshable, count(c.channel_id) AS node_count
        FROM proxy_subscriptions s LEFT JOIN proxy_channels c ON c.subscription_id=s.subscription_id
        """;

    public Mono<ProxyChannelDtos.Catalog> catalog() {
        Mono<List<ProxyChannelDtos.Channel>> channels = db.sql(CHANNEL_SELECT + " GROUP BY c.channel_id, s.subscription_id ORDER BY c.updated_at DESC")
            .map((r,m) -> channelRow(r)).all().collectList();
        Mono<List<ProxyChannelDtos.Subscription>> subscriptions = db.sql(SUBSCRIPTION_SELECT + " GROUP BY s.subscription_id ORDER BY s.updated_at DESC")
            .map((r,m) -> subscriptionRow(r)).all().collectList();
        return channels.zipWith(subscriptions).map(pair -> new ProxyChannelDtos.Catalog(pair.getT1(), pair.getT2(),
            properties.isMihomoEnabled(), properties.isTlsEnabled(), properties.getTlsProfiles(), runtimeHealth()));
    }

    private ProxyChannelDtos.RuntimeHealth runtimeHealth() {
        MihomoRuntime.Health health = runtime.health();
        return new ProxyChannelDtos.RuntimeHealth(health.controllerReachable(), health.lastPollAt(), health.lastActivatedAt(),
            health.activeNodes(), health.lastFailure() == null ? null : health.lastFailure().name(),
            health.lastFailure() == null ? null : health.lastFailure().message(), health.lastFailureAt());
    }

    private ProxyChannelDtos.Channel channelRow(io.r2dbc.spi.Readable r) {
        String testError = r.get("last_test_error_code", String.class);
        UUID id = r.get("channel_id", UUID.class);
        boolean enabled = Boolean.TRUE.equals(r.get("enabled", Boolean.class));
        String route = r.get("route", String.class);
        boolean usable = Boolean.TRUE.equals(r.get("source_present", Boolean.class)) && r.get("config_issue", String.class) == null;
        // A node reached directly depends on nothing but its own state. For a Mihomo node the stored status is what
        // the last activation wrote, and this instance's own runtime decides ACTIVE vs PENDING.
        String status = !enabled ? "DISABLED"
            : ClashNodes.DIRECT_PROXY.equals(route) ? (usable ? "ACTIVE" : "ERROR")
            : "ERROR".equals(r.get("runtime_status", String.class)) ? "ERROR"
            : runtime.active(id) ? "ACTIVE" : "PENDING";
        return new ProxyChannelDtos.Channel(id, r.get("subscription_id", UUID.class),
            r.get("subscription_name", String.class), r.get("name", String.class), r.get("protocol", String.class),
            route, enabled, status, r.get("runtime_error", String.class), number(r.get("bound_accounts")), instant(r.get("updated_at")),
            r.get("config_issue", String.class), Boolean.TRUE.equals(r.get("source_present", Boolean.class)),
            instant(r.get("last_tested_at")), r.get("last_test_result", String.class), r.get("last_test_exit_ip", String.class),
            r.get("last_test_latency_ms", Number.class) == null ? null : r.get("last_test_latency_ms", Number.class).longValue(),
            testError, failureMessage(testError));
    }

    private static ProxyChannelDtos.Subscription subscriptionRow(io.r2dbc.spi.Readable r) {
        return new ProxyChannelDtos.Subscription(r.get("subscription_id", UUID.class), r.get("name", String.class),
            r.get("status", String.class), instant(r.get("last_synced_at")), r.get("last_error", String.class),
            number(r.get("node_count")), Boolean.TRUE.equals(r.get("refreshable", Boolean.class)));
    }

    /** The fixed text of a stored failure code; a stored code is never free text, so nothing else is ever shown. */
    private static String failureMessage(String code) {
        if (code == null) return null;
        try { return EgressFailure.valueOf(code).message(); } catch (IllegalArgumentException unknown) { return null; }
    }

    public Mono<ProxyChannelDtos.Channel> create(ProxyChannelDtos.CreateChannel request) {
        if ((blank(request.proxyUrl()) == null) == (blank(request.clashYaml()) == null)) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PROXY_CONFIG_REQUIRED", "Provide exactly one proxy URL or Clash node"));
        }
        Node node;
        try {
            if (blank(request.proxyUrl()) == null) {
                JsonNode document = parser.document(request.clashYaml());
                if (document.has("proxies")) {
                    if (!document.path("proxies").isArray() || document.path("proxies").size() != 1) throw ClashNodes.invalid("Provide exactly one node, or import a subscription");
                    document = document.path("proxies").get(0);
                }
                node = parser.node(document);
            } else node = parser.url(request.name(), request.proxyUrl());
        } catch (ApiException error) { return Mono.error(error); }
        if (node.issue() != null) return Mono.error(ClashNodes.invalid(node.issue()));
        return insertChannel(null, request.name().trim(), node).onErrorMap(ProxyChannelService::portRangeFull, ProxyChannelService::portRangeFullError)
            .flatMap(id -> refreshRuntime().then(channel(id)));
    }

    public Mono<ProxyChannelDtos.SyncResult> subscribe(ProxyChannelDtos.ImportSubscription request) {
        String url = blank(request.url());
        if ((url == null) == (blank(request.yaml()) == null)) return Mono.error(ClashNodes.invalid("Provide exactly one subscription URL or YAML"));
        UUID id = UUID.randomUUID();
        Mono<String> source = url == null ? Mono.just(request.yaml()) : fetcher.fetch(url);
        DatabaseClient.GenericExecuteSpec insert = db.sql("INSERT INTO proxy_subscriptions(subscription_id,name,subscription_url) VALUES(:id,:name,:url)")
            .bind("id", id).bind("name", request.name().trim());
        insert = insert.bind("url", url == null ? "inline:" + id : url);
        return insert.fetch().rowsUpdated().then(lockedSync(id, source)
            .onErrorResume(error -> markSubscriptionError(id, error).then(Mono.error(error))));
    }

    public Mono<ProxyChannelDtos.SyncResult> sync(UUID subscriptionId) {
        return db.sql("SELECT subscription_url FROM proxy_subscriptions WHERE subscription_id=:id").bind("id", subscriptionId)
            .map((r,m) -> r.get("subscription_url", String.class)).one().switchIfEmpty(Mono.error(notFound("Subscription")))
            .flatMap(url -> url.startsWith("inline:") ? Mono.error(ClashNodes.invalid("Inline subscription cannot be refreshed"))
                : lockedSync(subscriptionId, fetcher.fetch(url))
                    .onErrorResume(error -> markSubscriptionError(subscriptionId, error).then(Mono.error(error))));
    }

    private Mono<ProxyChannelDtos.SyncResult> lockedSync(UUID id, Mono<String> source) {
        // Hold the source row lock through download so concurrent refreshes cannot overwrite newer data.
        return db.sql("SELECT subscription_id FROM proxy_subscriptions WHERE subscription_id=:id FOR UPDATE")
            .bind("id", id).fetch().one().switchIfEmpty(Mono.error(notFound("Subscription")))
            .then(source.flatMap(yaml -> syncParsed(id, yaml))).timeout(java.time.Duration.ofSeconds(60))
            .flux().as(transactions::transactional).collectList()
            .onErrorMap(ProxyChannelService::portRangeFull, ProxyChannelService::portRangeFullError)
            .flatMap(results -> refreshRuntime().thenReturn(results.getFirst()));
    }

    private Mono<Void> refreshRuntime() {
        // Persisted nodes remain visible with an ERROR state if the optional runtime is down.
        return runtime.reconcile().onErrorResume(EgressException.class, error -> Mono.empty());
    }

    private Mono<JsonNode> expandedDocument(String yaml) {
        return Mono.fromCallable(() -> parser.document(yaml)).subscribeOn(Schedulers.boundedElastic()).flatMap(doc -> {
            var nodes = mapper.createArrayNode();
            if (doc.has("proxies")) {
                if (!doc.path("proxies").isArray()) return Mono.error(ClashNodes.invalid("Expected a proxies array"));
                nodes.addAll((com.fasterxml.jackson.databind.node.ArrayNode) doc.path("proxies"));
            }
            JsonNode providers = doc.path("proxy-providers");
            if (!providers.isMissingNode() && (!providers.isObject() || providers.size() > 16))
                return Mono.error(ClashNodes.invalid("At most 16 HTTP or inline proxy providers are supported"));
            List<JsonNode> sources = new ArrayList<>();
            providers.elements().forEachRemaining(sources::add);
            return reactor.core.publisher.Flux.fromIterable(sources).concatMap(provider -> {
                String type = provider.path("type").asText();
                if (type.equals("inline")) return Mono.just(provider.path("payload"));
                if (!type.equals("http")) return Mono.error(ClashNodes.invalid("Only HTTP and inline proxy providers are supported"));
                return fetcher.fetch(provider.path("url").asText()).map(parser::document).map(data -> data.path("proxies"));
            }).doOnNext(list -> {
                if (!list.isArray()) throw ClashNodes.invalid("Provider contains no proxies array");
                nodes.addAll((com.fasterxml.jackson.databind.node.ArrayNode) list);
                if (nodes.size() > 500 || nodes.toString().length() > 4 * 1024 * 1024)
                    throw ClashNodes.invalid("Subscription exceeds 500 nodes or 4 MiB of node configuration");
            }).then(Mono.fromSupplier(() -> mapper.createObjectNode().set("proxies", nodes)));
        });
    }

    private Mono<ProxyChannelDtos.SyncResult> syncParsed(UUID subscriptionId, String yaml) {
        return expandedDocument(yaml).map(doc -> {
            List<Node> nodes = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            JsonNode list = doc.path("proxies");
            if (!list.isArray() || list.isEmpty()) throw ClashNodes.invalid("Subscription contains no proxies");
            if (list.size() > 500) throw ClashNodes.invalid("Subscription contains more than 500 proxy nodes");
            list.forEach(raw -> {
                try {
                    Node node = parser.node(raw);
                    nodes.add(node);
                    if (node.issue() != null) issues.add(node.name() + ": " + node.issue());
                } catch (ApiException error) {
                    String name = raw.path("name").asText("Unnamed node").replaceAll("[\\p{Cntrl}]", "");
                    issues.add(name.substring(0, Math.min(100, name.length())) + ": unsupported node format");
                }
            });
            if (nodes.stream().noneMatch(node -> node.issue() == null)) {
                throw ClashNodes.invalid("Subscription has no supported proxy nodes; the previous version was kept");
            }
            Map<String, Node> unique = new LinkedHashMap<>();
            nodes.forEach(node -> unique.putIfAbsent(node.fingerprint(), node));
            return new ParsedNodes(List.copyOf(unique.values()), issues);
        }).flatMap(parsed -> FluxHelper.persist(db, subscriptionId, parsed));
    }

    public Mono<ProxyChannelDtos.Channel> update(UUID id, ProxyChannelDtos.UpdateChannel request) {
        // A renamed node keeps its name through later subscription refreshes.
        DatabaseClient.GenericExecuteSpec update = db.sql("UPDATE proxy_channels SET name=COALESCE(:name,name), name_customized=name_customized OR (CAST(:name AS varchar) IS NOT NULL AND CAST(:name AS varchar)<>name), enabled=COALESCE(:enabled,enabled), runtime_status=CASE WHEN COALESCE(:enabled,enabled) IS FALSE THEN 'DISABLED' WHEN NOT source_present OR config_issue IS NOT NULL THEN 'ERROR' ELSE 'PENDING' END WHERE channel_id=:id").bind("id", id);
        update = nullable(update, "enabled", request.enabled(), Boolean.class);
        update = nullable(update, "name", blank(request.name()), String.class);
        return update.fetch().rowsUpdated().flatMap(count -> count == 0 ? Mono.error(notFound("Channel"))
            : refreshRuntime().then(channel(id)));
    }

    public Mono<Void> delete(UUID id) {
        return requireChannel(id).then(Mono.defer(() -> boundAccounts("c.channel_id", id)))
            .flatMap(accounts -> accounts.isEmpty()
                ? db.sql("DELETE FROM proxy_channels WHERE channel_id=:id").bind("id", id).fetch().rowsUpdated().then()
                : Mono.<Void>error(inUse(accounts)))
            // An account may be bound between the check and the delete; the foreign key still refuses it.
            .onErrorResume(org.springframework.dao.DataIntegrityViolationException.class,
                e -> boundAccounts("c.channel_id", id).flatMap(accounts -> Mono.<Void>error(inUse(accounts))))
            .then(Mono.defer(this::refreshRuntime));
    }

    public Mono<List<ProxyChannelDtos.BoundAccount>> accounts(UUID id) {
        return requireChannel(id).then(Mono.defer(() -> boundAccounts("c.channel_id", id)));
    }

    public Mono<ProxyChannelDtos.Subscription> renameSubscription(UUID id, ProxyChannelDtos.RenameSubscription request) {
        return db.sql("UPDATE proxy_subscriptions SET name=:name WHERE subscription_id=:id")
            .bind("id", id).bind("name", request.name().trim()).fetch().rowsUpdated()
            .flatMap(count -> count == 0 ? Mono.<ProxyChannelDtos.Subscription>error(notFound("Subscription"))
                : db.sql(SUBSCRIPTION_SELECT + " WHERE s.subscription_id=:id GROUP BY s.subscription_id").bind("id", id)
                    .map((r, m) -> subscriptionRow(r)).one());
    }

    /** Removes a subscription and its nodes; refused with the bound accounts while any node is still in use. */
    public Mono<Void> deleteSubscription(UUID id) {
        // The row lock also keeps a running refresh from re-inserting nodes behind the delete.
        Mono<Void> delete = db.sql("SELECT subscription_id FROM proxy_subscriptions WHERE subscription_id=:id FOR UPDATE")
            .bind("id", id).fetch().one().switchIfEmpty(Mono.error(notFound("Subscription")))
            .then(Mono.defer(() -> boundAccounts("c.subscription_id", id)))
            .flatMap(accounts -> accounts.isEmpty() ? Mono.<Void>empty() : Mono.<Void>error(inUse(accounts)))
            .then(Mono.defer(() -> db.sql("DELETE FROM proxy_channels WHERE subscription_id=:id").bind("id", id).fetch().rowsUpdated()))
            .then(Mono.defer(() -> db.sql("DELETE FROM proxy_subscriptions WHERE subscription_id=:id").bind("id", id).fetch().rowsUpdated()))
            .then();
        return transactions.transactional(delete)
            .onErrorResume(org.springframework.dao.DataIntegrityViolationException.class,
                e -> boundAccounts("c.subscription_id", id).flatMap(accounts -> Mono.<Void>error(inUse(accounts))))
            .then(Mono.defer(this::refreshRuntime));
    }

    /** Whether a channel can carry requests right now: used before binding a login or an account to it. */
    public Mono<Void> requireUsable(UUID id) {
        return db.sql("SELECT enabled AND source_present AND config_issue IS NULL AS usable, route FROM proxy_channels WHERE channel_id=:id")
            .bind("id", id).map((r, m) -> r.get("usable", Boolean.class) != null && r.get("usable", Boolean.class)
                ? r.get("route", String.class) : "").one()
            .switchIfEmpty(Mono.error(notFound("Channel")))
            .flatMap(route -> route.isEmpty() ? Mono.<Void>error(new ApiException(HttpStatus.BAD_REQUEST, "PROXY_CHANNEL_UNAVAILABLE",
                    EgressFailure.NODE_UNAVAILABLE.message()))
                : ClashNodes.MIHOMO.equals(route) && !properties.isMihomoEnabled() ? Mono.<Void>error(new ApiException(HttpStatus.BAD_REQUEST,
                    "PROXY_RUNTIME_DISABLED", EgressFailure.RUNTIME_DISABLED.message()))
                : Mono.<Void>empty());
    }

    private Mono<Void> requireChannel(UUID id) {
        return db.sql("SELECT channel_id FROM proxy_channels WHERE channel_id=:id").bind("id", id).fetch().one()
            .switchIfEmpty(Mono.error(notFound("Channel"))).then();
    }

    /** {@code scope} is a fixed column of the query (never user input): c.channel_id or c.subscription_id. */
    private Mono<List<ProxyChannelDtos.BoundAccount>> boundAccounts(String scope, UUID id) {
        return db.sql("SELECT a.account_id, a.email, a.provider, a.auth_method, a.enabled, c.channel_id, c.name AS channel_name "
                + "FROM proxy_accounts a JOIN proxy_channels c ON c.channel_id=a.proxy_channel_id WHERE " + scope
                + "=:id ORDER BY lower(COALESCE(a.email,a.account_id)), a.account_id")
            .bind("id", id).map((r, m) -> new ProxyChannelDtos.BoundAccount(r.get("account_id", String.class),
                r.get("email", String.class), r.get("provider", String.class), r.get("auth_method", String.class),
                Boolean.TRUE.equals(r.get("enabled", Boolean.class)), r.get("channel_id", UUID.class),
                r.get("channel_name", String.class))).all().collectList();
    }

    private static ApiException inUse(List<ProxyChannelDtos.BoundAccount> accounts) {
        return new ApiException(HttpStatus.CONFLICT, "PROXY_NODE_IN_USE", "Unbind accounts before deleting this node", accounts);
    }

    // The listener port trigger raises configuration_limit_exceeded when all 10000 ports belong to routable nodes.
    private static boolean portRangeFull(Throwable error) {
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
            if (error instanceof io.r2dbc.spi.R2dbcException r2dbc && "53400".equals(r2dbc.getSqlState())) return true;
        }
        return false;
    }

    private static ApiException portRangeFullError(Throwable error) {
        return new ApiException(HttpStatus.CONFLICT, "PROXY_PORT_RANGE_FULL",
            "All proxy listener ports (20000-29999) are in use; remove unused nodes or subscriptions first");
    }

    public Mono<ProxyChannelDtos.ProbeResult> test(UUID id) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            // The probe takes the same path as the account requests of this node.
            return routes.resolve(id).flatMap(route -> {
            reactor.netty.http.client.HttpClient http = ChannelRoutes.through(reactor.netty.http.client.HttpClient.newConnection(), route.proxy(), ChannelRoutes.defaultProxyTls())
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(properties.getProbeTimeout());
            WebClient client = WebClient.builder().clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(http)).build();
            return client.get().uri(properties.getProbeUrl()).retrieve().bodyToMono(JsonNode.class)
                .timeout(properties.getProbeTimeout())
                .switchIfEmpty(Mono.error(new EgressException(EgressFailure.PROBE_INVALID_RESPONSE)))
                .map(body -> {
                    String ip = body.path("ip").asText("");
                    if (!io.netty.util.NetUtil.isValidIpV4Address(ip) && !io.netty.util.NetUtil.isValidIpV6Address(ip))
                        throw new EgressException(EgressFailure.PROBE_INVALID_RESPONSE);
                    return new ProxyChannelDtos.ProbeResult(true, ip,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), null, null);
                });
            }).onErrorResume(error -> {
                EgressFailure failure = EgressFailure.classify(error, EgressFailure.PROBE_FAILED);
                long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                log.warn("Exit probe failed: channel={}, code={}, elapsedMs={}", id, failure, elapsed);
                return Mono.just(new ProxyChannelDtos.ProbeResult(false, null, elapsed, failure.message(), failure.name()));
            });
        })
            .flatMap(result -> {
                // Only the fixed failure code is stored; the text shown later is derived from it.
                DatabaseClient.GenericExecuteSpec save = db.sql("UPDATE proxy_channels SET last_tested_at=clock_timestamp(),last_test_result=:result,last_test_exit_ip=:ip,last_test_latency_ms=:latency,last_test_error_code=:code WHERE channel_id=:id")
                    .bind("id", id).bind("result", result.success() ? "SUCCESS" : "FAILED")
                    .bind("latency", (int) Math.min(Math.max(result.latencyMs(), 0), Integer.MAX_VALUE));
                save = nullable(save, "ip", result.exitIp(), String.class);
                save = nullable(save, "code", result.errorCode(), String.class);
                return save.fetch().rowsUpdated().thenReturn(result);
            });
    }

    public Mono<ProxyChannelDtos.Channel> channel(UUID id) {
        return db.sql(CHANNEL_SELECT + " WHERE c.channel_id=:id GROUP BY c.channel_id, s.subscription_id")
            .bind("id", id).map((r,m) -> channelRow(r)).one().switchIfEmpty(Mono.error(notFound("Channel")));
    }
    private static <T> DatabaseClient.GenericExecuteSpec nullable(DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
    private Mono<Void> markSubscriptionError(UUID id, Throwable e) { return db.sql("UPDATE proxy_subscriptions SET status='ERROR',last_error=:error,updated_at=clock_timestamp() WHERE subscription_id=:id").bind("id",id).bind("error",safeError(e)).fetch().rowsUpdated().then(); }
    private static String safeError(Throwable e) {
        String message = e instanceof ApiException || e instanceof EgressException ? e.getMessage() : "Subscription import failed";
        message = message.replaceAll("https?://\\S+", "[subscription URL]").replaceAll("(?i)(password|uuid|token|authorization)\\s*[:=]\\s*[^, }]+", "$1=[redacted]");
        return message.substring(0, Math.min(512, message.length()));
    }
    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static Instant instant(Object value) { return value instanceof Instant i ? i : value instanceof java.time.OffsetDateTime o ? o.toInstant() : null; }
    private static ApiException notFound(String name) { return new ApiException(HttpStatus.NOT_FOUND, "PROXY_RESOURCE_NOT_FOUND", name + " not found"); }
    private Mono<UUID> insertChannel(UUID subscriptionId, String name, Node node) {
        UUID id = UUID.randomUUID();
        String proxyName = "node-" + id;
        DatabaseClient.GenericExecuteSpec spec = db.sql("INSERT INTO proxy_channels(channel_id,subscription_id,name,proxy_name,protocol,route,node_config,runtime_status,config_fingerprint,config_issue) VALUES(:id,:sub,:name,:proxy,:protocol,:route,:config::jsonb,'PENDING',:fingerprint,:issue)")
            .bind("id", id).bind("name", name).bind("proxy", proxyName).bind("protocol", node.protocol()).bind("route", node.route())
            .bind("config", node.config().toString()).bind("fingerprint", node.fingerprint());
        spec = nullable(spec, "issue", node.issue(), String.class);
        spec = subscriptionId == null ? spec.bindNull("sub", UUID.class) : spec.bind("sub", subscriptionId);
        return spec.fetch().rowsUpdated().thenReturn(id).onErrorMap(DuplicateKeyException.class,
            e -> new ApiException(HttpStatus.CONFLICT, "PROXY_NODE_EXISTS", "This node already exists in the subscription"));
    }

    static final class FluxHelper {
        static Mono<ProxyChannelDtos.SyncResult> persist(DatabaseClient db, UUID subscriptionId, ParsedNodes parsed) {
            return reactor.core.publisher.Flux.fromIterable(parsed.nodes()).concatMap(node -> upsert(db, subscriptionId, node))
                .collectList().flatMap(ids -> db.sql("UPDATE proxy_channels SET source_present=FALSE,runtime_status='ERROR',runtime_error='Node is no longer present in the latest subscription' WHERE subscription_id=:id AND channel_id <> ALL(:ids)")
                .bind("id",subscriptionId).bind("ids",ids.toArray(UUID[]::new)).fetch().rowsUpdated()
                .then(db.sql("UPDATE proxy_subscriptions SET status='ACTIVE',last_synced_at=clock_timestamp(),last_error=NULL,updated_at=clock_timestamp() WHERE subscription_id=:id").bind("id",subscriptionId).fetch().rowsUpdated())
                .thenReturn(new ProxyChannelDtos.SyncResult(subscriptionId, parsed.nodes().size(), parsed.issues().size(), parsed.issues())));
        }

        private static Mono<UUID> upsert(DatabaseClient db, UUID subscriptionId, Node node) {
            // Refreshes hold the subscription lock. Update first so unchanged nodes do not consume listener ports.
            var update = db.sql("""
                UPDATE proxy_channels SET name=CASE WHEN name_customized THEN name ELSE :name END,node_config=:config::jsonb,protocol=:protocol,route=:route,
                    source_present=TRUE,config_issue=:issue,runtime_error=:issue,
                    runtime_status=CASE WHEN NOT enabled THEN 'DISABLED' WHEN CAST(:issue AS text) IS NULL THEN 'PENDING' ELSE 'ERROR' END
                WHERE subscription_id=:sub AND config_fingerprint=:fingerprint RETURNING channel_id
                """);
            return bindNode(update, subscriptionId, node).map((row, meta) -> row.get("channel_id", UUID.class)).one()
                .switchIfEmpty(Mono.defer(() -> {
                    UUID id = UUID.randomUUID();
                    var insert = db.sql("""
                        INSERT INTO proxy_channels(channel_id,subscription_id,name,proxy_name,protocol,route,node_config,
                            runtime_status,runtime_error,config_fingerprint,source_present,config_issue)
                        VALUES(:id,:sub,:name,:proxy,:protocol,:route,:config::jsonb,
                            CASE WHEN CAST(:issue AS text) IS NULL THEN 'PENDING' ELSE 'ERROR' END,:issue,:fingerprint,TRUE,:issue)
                        RETURNING channel_id
                        """).bind("id", id).bind("proxy", "node-" + id);
                    return bindNode(insert, subscriptionId, node).map((row, meta) -> row.get("channel_id", UUID.class)).one();
                }));
        }

        private static DatabaseClient.GenericExecuteSpec bindNode(DatabaseClient.GenericExecuteSpec spec, UUID subscriptionId, Node node) {
            return nullable(spec.bind("sub", subscriptionId).bind("name", node.name()).bind("protocol", node.protocol()).bind("route", node.route())
                .bind("config", node.config().toString()).bind("fingerprint", node.fingerprint()), "issue", node.issue(), String.class);
        }
    }
    private record ParsedNodes(List<Node> nodes, List<String> issues) {}
}
