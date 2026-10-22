package tech.starryapi.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.egress.ClashNodes.Node;

@Service
public class ProxyChannelService {
    private final DatabaseClient db;
    private final ObjectMapper mapper;
    private final ClashNodes parser;
    private final SubscriptionFetcher fetcher;
    private final EgressProperties properties;

    public ProxyChannelService(DatabaseClient db, ObjectMapper mapper, ClashNodes parser,
                               SubscriptionFetcher fetcher, EgressProperties properties) {
        this.db = db; this.mapper = mapper; this.parser = parser; this.fetcher = fetcher; this.properties = properties;
    }

    public Mono<ProxyChannelDtos.Catalog> catalog() {
        Mono<List<ProxyChannelDtos.Channel>> channels = db.sql("""
            SELECT c.channel_id, c.subscription_id, c.name, c.protocol, c.enabled, c.runtime_status, c.runtime_error,
                   count(a.account_id) AS bound_accounts, c.updated_at
            FROM proxy_channels c LEFT JOIN proxy_accounts a ON a.proxy_channel_id = c.channel_id
            GROUP BY c.channel_id ORDER BY c.updated_at DESC
            """).map((r,m) -> new ProxyChannelDtos.Channel(r.get("channel_id", UUID.class), r.get("subscription_id", UUID.class),
                r.get("name", String.class), r.get("protocol", String.class), Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                r.get("runtime_status", String.class), r.get("runtime_error", String.class), number(r.get("bound_accounts")),
                instant(r.get("updated_at")))).all().collectList();
        Mono<List<ProxyChannelDtos.Subscription>> subscriptions = db.sql("""
            SELECT s.subscription_id, s.name, s.status, s.last_synced_at, s.last_error, count(c.channel_id) AS node_count
            FROM proxy_subscriptions s LEFT JOIN proxy_channels c ON c.subscription_id=s.subscription_id
            GROUP BY s.subscription_id ORDER BY s.updated_at DESC
            """).map((r,m) -> new ProxyChannelDtos.Subscription(r.get("subscription_id", UUID.class), r.get("name", String.class),
                r.get("status", String.class), instant(r.get("last_synced_at")), r.get("last_error", String.class), number(r.get("node_count")))).all().collectList();
        return channels.zipWith(subscriptions).map(pair -> new ProxyChannelDtos.Catalog(pair.getT1(), pair.getT2(),
            properties.isMihomoEnabled(), properties.isTlsEnabled(), properties.getTlsProfiles()));
    }

    public Mono<ProxyChannelDtos.Channel> create(ProxyChannelDtos.CreateChannel request) {
        if ((blank(request.proxyUrl()) == null) == (blank(request.clashYaml()) == null)) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PROXY_CONFIG_REQUIRED", "Provide exactly one proxy URL or Clash node"));
        }
        Node node;
        try {
            node = blank(request.proxyUrl()) != null ? parser.node(parser.document(request.clashYaml()).path("proxies").path(0))
                : parser.url(request.name(), request.proxyUrl());
        } catch (ApiException error) { return Mono.error(error); }
        if (node.issue() != null) return Mono.error(ClashNodes.invalid(node.issue()));
        return insertChannel(null, request.name().trim(), node).flatMap(id -> channel(id));
    }

    public Mono<ProxyChannelDtos.SyncResult> subscribe(ProxyChannelDtos.ImportSubscription request) {
        String url = blank(request.url());
        if (url == null && blank(request.yaml()) == null) return Mono.error(ClashNodes.invalid("Provide a subscription URL or YAML"));
        UUID id = UUID.randomUUID();
        Mono<String> source = url == null ? Mono.just(request.yaml()) : fetcher.fetch(url);
        return db.sql("INSERT INTO proxy_subscriptions(subscription_id,name,subscription_url) VALUES(:id,:name,:url)")
            .bind("id", id).bind("name", request.name().trim()).bind("url", url == null ? "inline:" + id : url)
            .fetch().rowsUpdated().then(source.flatMap(yaml -> syncParsed(id, yaml)).onErrorResume(error -> markSubscriptionError(id, error).then(Mono.error(error))));
    }

    public Mono<ProxyChannelDtos.SyncResult> sync(UUID subscriptionId) {
        return db.sql("SELECT subscription_url FROM proxy_subscriptions WHERE subscription_id=:id").bind("id", subscriptionId)
            .map((r,m) -> r.get("subscription_url", String.class)).one().switchIfEmpty(Mono.error(notFound("Subscription")))
            .flatMap(url -> url.startsWith("inline:") ? Mono.error(ClashNodes.invalid("Inline subscription cannot be refreshed")) : fetcher.fetch(url).flatMap(yaml -> syncParsed(subscriptionId, yaml)));
    }

    private Mono<ProxyChannelDtos.SyncResult> syncParsed(UUID subscriptionId, String yaml) {
        return Mono.fromCallable(() -> {
            JsonNode doc = parser.document(yaml);
            List<Node> nodes = new ArrayList<>();
            JsonNode list = doc.path("proxies");
            if (!list.isArray() || list.isEmpty()) throw ClashNodes.invalid("Subscription contains no proxies");
            list.forEach(raw -> { try { nodes.add(parser.node(raw)); } catch (ApiException ignored) {} });
            return nodes;
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(nodes -> FluxHelper.persist(db, subscriptionId, nodes));
    }

    public Mono<ProxyChannelDtos.Channel> update(UUID id, ProxyChannelDtos.UpdateChannel request) {
        return db.sql("UPDATE proxy_channels SET name=COALESCE(:name,name), enabled=COALESCE(:enabled,enabled), runtime_status=CASE WHEN :enabled IS FALSE THEN 'DISABLED' ELSE runtime_status END WHERE channel_id=:id")
            .bind("id", id).bind("name", request.name()).bind("enabled", request.enabled()).fetch().rowsUpdated().flatMap(count -> count == 0 ? Mono.error(notFound("Channel")) : channel(id));
    }
    public Mono<Void> delete(UUID id) { return db.sql("DELETE FROM proxy_channels WHERE channel_id=:id").bind("id", id).fetch().rowsUpdated().flatMap(c -> c == 0 ? Mono.error(notFound("Channel")) : Mono.empty()); }
    public Mono<ProxyChannelDtos.ProbeResult> test(UUID id) { return channel(id).map(c -> c.runtimeStatus().equals("ACTIVE") ? new ProxyChannelDtos.ProbeResult(true, null, 0, null) : new ProxyChannelDtos.ProbeResult(false, null, 0, "Channel runtime is not active")); }

    public Mono<ProxyChannelDtos.Channel> channel(UUID id) {
        return db.sql("SELECT c.channel_id,c.subscription_id,c.name,c.protocol,c.enabled,c.runtime_status,c.runtime_error,count(a.account_id) bound_accounts,c.updated_at FROM proxy_channels c LEFT JOIN proxy_accounts a ON a.proxy_channel_id=c.channel_id WHERE c.channel_id=:id GROUP BY c.channel_id")
            .bind("id", id).map((r,m) -> new ProxyChannelDtos.Channel(r.get("channel_id",UUID.class),r.get("subscription_id",UUID.class),r.get("name",String.class),r.get("protocol",String.class),Boolean.TRUE.equals(r.get("enabled",Boolean.class)),r.get("runtime_status",String.class),r.get("runtime_error",String.class),number(r.get("bound_accounts")),instant(r.get("updated_at")))).one().switchIfEmpty(Mono.error(notFound("Channel")));
    }
    private Mono<Void> markSubscriptionError(UUID id, Throwable e) { return db.sql("UPDATE proxy_subscriptions SET status='ERROR',last_error=:error,updated_at=clock_timestamp() WHERE subscription_id=:id").bind("id",id).bind("error",safeError(e)).fetch().rowsUpdated().then(); }
    private static String safeError(Throwable e) { return e == null ? "Unknown error" : e.getMessage() == null ? "Import failed" : e.getMessage().replaceAll("https?://\\S+", "[url]").substring(0, Math.min(512, e.getMessage().length())); }
    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static Instant instant(Object value) { return value instanceof Instant i ? i : value instanceof java.time.OffsetDateTime o ? o.toInstant() : null; }
    private static ApiException notFound(String name) { return new ApiException(HttpStatus.NOT_FOUND, "PROXY_RESOURCE_NOT_FOUND", name + " not found"); }
    private Mono<UUID> insertChannel(UUID subscriptionId, String name, Node node) { UUID id=UUID.randomUUID(); return db.sql("INSERT INTO proxy_channels(channel_id,subscription_id,name,proxy_name,protocol,node_config, runtime_status,config_fingerprint) VALUES(:id,:sub,:name,:proxy,:protocol,:config::jsonb,'PENDING',:fingerprint)").bind("id",id).bind("sub",subscriptionId).bind("name",name).bind("proxy",name).bind("protocol",node.protocol()).bind("config",node.config().toString()).bind("fingerprint",node.fingerprint()).fetch().rowsUpdated().thenReturn(id).onErrorMap(DuplicateKeyException.class,e -> new ApiException(HttpStatus.CONFLICT,"PROXY_NODE_EXISTS","A proxy node with this name already exists")); }

    static final class FluxHelper {
        static Mono<ProxyChannelDtos.SyncResult> persist(DatabaseClient db, UUID subscriptionId, List<Node> nodes) {
            return reactor.core.publisher.Flux.fromIterable(nodes).concatMap(node -> {
                UUID id=UUID.randomUUID(); return db.sql("INSERT INTO proxy_channels(channel_id,subscription_id,name,proxy_name,protocol,node_config,runtime_status,config_fingerprint) VALUES(:id,:sub,:name,:proxy,:protocol,:config::jsonb,'PENDING',:fingerprint) ON CONFLICT (proxy_name) DO UPDATE SET subscription_id=EXCLUDED.subscription_id,node_config=EXCLUDED.node_config,protocol=EXCLUDED.protocol,config_fingerprint=EXCLUDED.config_fingerprint,updated_at=clock_timestamp()")
                    .bind("id",id).bind("sub",subscriptionId).bind("name",node.name()).bind("proxy",node.name()).bind("protocol",node.protocol()).bind("config",node.config().toString()).bind("fingerprint",node.fingerprint()).fetch().rowsUpdated();
            }).count().flatMap(count -> db.sql("UPDATE proxy_subscriptions SET status='ACTIVE',last_synced_at=clock_timestamp(),last_error=NULL,updated_at=clock_timestamp() WHERE subscription_id=:id").bind("id",subscriptionId).fetch().rowsUpdated().thenReturn(new ProxyChannelDtos.SyncResult(subscriptionId,count.intValue(),0,List.of())));
        }
    }
}
