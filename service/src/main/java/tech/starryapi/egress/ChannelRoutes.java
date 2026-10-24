package tech.starryapi.egress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import tech.starryapi.common.Hashing;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/**
 * How requests of a proxy channel reach their exit. A plain HTTP CONNECT or SOCKS5 node is connected to directly by
 * this application and works without Mihomo; every other protocol needs a Mihomo listener. Neither ever falls back
 * to a direct connection to the target.
 */
@Component
public class ChannelRoutes {
    private final DatabaseClient db;
    private final MihomoRuntime runtime;
    private final ObjectMapper mapper;
    private final EgressProperties properties;

    public ChannelRoutes(DatabaseClient db, MihomoRuntime runtime, ObjectMapper mapper, EgressProperties properties) {
        this.db = db; this.runtime = runtime; this.mapper = mapper; this.properties = properties;
    }

    /**
     * {@code proxy} is the HTTP or SOCKS5 proxy to connect to: the node itself, or this site's Mihomo listener.
     * {@code key} identifies the route for connection reuse: what is connected to, and for a Mihomo listener also which
     * node version is behind it.
     */
    public record Resolved(String route, ObjectNode proxy, String key) {}

    public Mono<Resolved> resolve(UUID id) {
        return db.sql("SELECT route,config_fingerprint,listener_port,node_config::text AS config FROM proxy_channels WHERE channel_id=:id AND enabled AND source_present AND config_issue IS NULL")
            .bind("id", id).map((r, m) -> new Stored(r.get("route", String.class), r.get("config_fingerprint", String.class),
                r.get("listener_port", Integer.class), r.get("config", String.class))).one()
            .switchIfEmpty(Mono.error(new EgressException(EgressFailure.NODE_UNAVAILABLE)))
            .flatMap(stored -> {
                JsonNode config;
                try { config = mapper.readTree(stored.config()); }
                catch (Exception error) { return Mono.error(EgressException.notSent(EgressFailure.NODE_UNAVAILABLE, error)); }
                if (ClashNodes.DIRECT_PROXY.equals(stored.route())) {
                    ObjectNode proxy = (ObjectNode) config;
                    return Mono.just(new Resolved(ClashNodes.DIRECT_PROXY, proxy, key(proxy, null)));
                }
                if (stored.port() == null) return Mono.error(new EgressException(EgressFailure.NODE_UNAVAILABLE));
                return runtime.ensureActive(new MihomoRuntime.Route(id, stored.port(), config)).map(route -> {
                    ObjectNode listener = listener(route.port());
                    return new Resolved(ClashNodes.MIHOMO, listener, key(listener, id + ":" + route.port() + ":" + stored.fingerprint()));
                });
            });
    }

    /** Connections may be shared by exactly the requests that reach the same proxy with the same credentials. */
    static String key(ObjectNode proxy, String nodeVersion) {
        return proxy.path("type").asText() + (proxy.path("tls").asBoolean(false) ? "+tls" : "") + "|"
            + proxy.path("server").asText() + ":" + proxy.path("port").asInt() + "|"
            + Hashing.sha256(proxy.path("username").asText("") + "\0" + proxy.path("password").asText(""))
            + (nodeVersion == null ? "" : "|" + nodeVersion);
    }

    private ObjectNode listener(int port) {
        return mapper.createObjectNode().put("type", "http").put("server", properties.getListenerHost()).put("port", port);
    }

    /**
     * Sends the client's connections through the proxy, with the node's own credentials. The target host name goes to the
     * proxy unresolved (SOCKS5 included), so the proxy resolves it. With {@code tls: true} the connection to the proxy
     * itself is TLS, verified against {@code proxyTls} (the JDK trust store unless a test says otherwise).
     */
    static HttpClient through(HttpClient client, ObjectNode proxy, SslContext proxyTls) {
        HttpClient routed = client.proxy(spec -> {
            var configured = spec.type("socks5".equals(proxy.path("type").asText()) ? ProxyProvider.Proxy.SOCKS5 : ProxyProvider.Proxy.HTTP)
                .host(proxy.path("server").asText()).port(proxy.path("port").asInt());
            if (proxy.has("username")) configured.username(proxy.path("username").asText()).password(ignored -> proxy.path("password").asText());
            configured.connectTimeoutMillis(10000);
        });
        if (!proxy.path("tls").asBoolean(false)) return routed;
        String host = proxy.path("server").asText();
        int port = proxy.path("port").asInt();
        // Reactor Netty adds the proxy handler (and the HTTP codec it uses for CONNECT) first; the TLS to the proxy has
        // to sit in front of both, and the TLS to the target (if any) behind them.
        return routed.doOnChannelInit((observer, channel, remote) -> {
            SslHandler handler = proxyTls.newHandler(channel.alloc(), host, port);
            SSLParameters parameters = handler.engine().getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            handler.engine().setSSLParameters(parameters);
            channel.pipeline().addFirst("starry.proxyTls", handler);
        });
    }

    static SslContext defaultProxyTls() {
        try { return SslContextBuilder.forClient().build(); }
        catch (SSLException error) { throw new IllegalStateException("TLS client could not be initialised", error); }
    }

    /** The proxy as a URL for the TLS component, which makes the connection to the target itself. */
    static String url(ObjectNode proxy) {
        boolean socks = "socks5".equals(proxy.path("type").asText());
        String credentials = proxy.has("username")
            ? encode(proxy.path("username").asText()) + ":" + encode(proxy.path("password").asText("")) + "@" : "";
        // socks5h: the proxy resolves the target name, as it does when this application connects.
        return (socks ? "socks5h://" : proxy.path("tls").asBoolean(false) ? "https://" : "http://") + credentials + proxy.path("server").asText() + ":" + proxy.path("port").asInt();
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    private record Stored(String route, String fingerprint, Integer port, String config) {}
}
