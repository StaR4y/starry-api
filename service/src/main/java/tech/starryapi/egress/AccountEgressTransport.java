package tech.starryapi.egress;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFunctions;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.config.ProxyProperties;

@Component
public class AccountEgressTransport {
    public static final String ACCOUNT = AccountEgressTransport.class.getName() + ".account";
    private final DatabaseClient db;
    private final MihomoRuntime runtime;
    private final EgressProperties properties;
    private final ProxyProperties proxyProperties;
    private final ClashNodes parser;

    public AccountEgressTransport(DatabaseClient db, MihomoRuntime runtime, EgressProperties properties,
                                  ProxyProperties proxyProperties, ClashNodes parser) {
        this.db = db; this.runtime = runtime; this.properties = properties;
        this.proxyProperties = proxyProperties; this.parser = parser;
    }

    public ExchangeFilterFunction filter() {
        return (request, next) -> {
            Object value = request.attributes().get(ACCOUNT);
            if (!(value instanceof ProxyAccountEntity account)) return next.exchange(request);
            return settings(account).flatMap(settings -> {
                Mono<String> proxy = settings.channelId() == null
                    ? Mono.just(settings.legacyProxy())
                    : runtime.route(settings.channelId()).map(route -> "http://" + properties.getListenerHost() + ":" + route.port());
                return proxy.flatMap(url -> {
                    if (!settings.tlsProfile().isBlank()) return fingerprintExchange(request, url, settings.tlsProfile());
                    if (url.isBlank()) return next.exchange(request);
                    return exchangeForProxy(url).exchange(request)
                        .onErrorMap(error -> new EgressException("Selected proxy connection failed", false));
                });
            });
        };
    }

    private Mono<Settings> settings(ProxyAccountEntity account) {
        // Read the current binding before dispatch; a stale account cache must not bypass a disabled node.
        return db.sql("SELECT a.proxy_channel_id,COALESCE(a.proxy_url,'') legacy_proxy,CASE WHEN lower(a.provider) IN ('anthropic','claude') THEN COALESCE(p.tls_profile,'') ELSE '' END tls_profile FROM proxy_accounts a JOIN account_allocation_policies p USING(account_id) WHERE a.account_id=:id")
            .bind("id", account.getAccountId())
            .map((r,m) -> new Settings(r.get("proxy_channel_id", UUID.class), r.get("legacy_proxy", String.class), r.get("tls_profile", String.class)))
            .one().switchIfEmpty(Mono.error(new EgressException("Account route no longer exists")))
            .onErrorMap(error -> error instanceof EgressException ? error : new EgressException("Account route could not be loaded"));
    }

    private Mono<org.springframework.web.reactive.function.client.ClientResponse> fingerprintExchange(ClientRequest request, String proxy, String profile) {
        if (!properties.isTlsEnabled() || !properties.getTlsProfiles().contains(profile)) {
            return Mono.error(new EgressException("Requested TLS profile is unavailable"));
        }
        if (properties.getTlsSecret().isBlank()) return Mono.error(new EgressException("TLS component authentication is not configured"));
        ClientRequest bridge = ClientRequest.from(request).url(URI.create(properties.getTlsUrl() + "/forward"))
            .headers(headers -> {
                headers.remove(HttpHeaders.HOST);
                headers.remove(HttpHeaders.CONTENT_LENGTH);
                headers.set("X-Starry-Target", request.url().toString());
                headers.set("X-Starry-Proxy", proxy);
                headers.set("X-Starry-Tls-Profile", profile);
                headers.set("X-Starry-Bridge-Key", properties.getTlsSecret());
            }).build();
        return exchange(HttpClient.newConnection()).exchange(bridge)
            .flatMap(response -> response.headers().header("X-Starry-Egress-Error").isEmpty()
                    && response.headers().header("X-Starry-Bridge-Response").contains("1")
                ? Mono.just(response.mutate().headers(h -> h.remove("X-Starry-Bridge-Response")).build())
                : response.releaseBody().then(Mono.error(new EgressException("TLS component could not send the request", false))))
            .onErrorMap(error -> error instanceof EgressException ? error : new EgressException("TLS component is unavailable", false));
    }

    ExchangeFunction exchangeForProxy(String url) {
        ClashNodes.Node node = parser.url("legacy", url);
        var config = node.config();
        HttpClient client = HttpClient.newConnection().proxy(spec -> {
            var proxy = spec.type(node.protocol().equals("socks5") ? ProxyProvider.Proxy.SOCKS5 : ProxyProvider.Proxy.HTTP)
                .host(config.path("server").asText()).port(config.path("port").asInt());
            if (config.has("username")) proxy.username(config.path("username").asText()).password(ignored -> config.path("password").asText());
            proxy.connectTimeoutMillis(10000);
        });
        return exchange(client);
    }

    private ExchangeFunction exchange(HttpClient client) {
        // Short-lived routed connections isolate node changes and avoid unbounded per-account idle pools.
        client = client.followRedirect(false).compress(true).responseTimeout(proxyProperties.getRequestTimeout())
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
        return ExchangeFunctions.create(new ReactorClientHttpConnector(client), ExchangeStrategies.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build());
    }
    private record Settings(UUID channelId, String legacyProxy, String tlsProfile) {}
}
