package tech.starryapi.egress;

import io.netty.handler.ssl.SslContext;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFunctions;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.config.HttpClientConfig;
import tech.starryapi.config.ProxyProperties;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AccountEgressTransport {
    public static final String ACCOUNT = AccountEgressTransport.class.getName() + ".account";
    /** For calls that belong to no account yet (a Codex login): a channel id, with the same no-direct-fallback rule. */
    public static final String CHANNEL = AccountEgressTransport.class.getName() + ".channel";
    private final DatabaseClient db;
    private final ChannelRoutes routes;
    private final EgressProperties properties;
    private final ProxyProperties proxyProperties;
    private final ClashNodes parser;
    // Reuses sockets only. The route of every request is resolved again right before dispatch, so a disabled or
    // changed node is noticed immediately; this cache never decides where a request goes.
    private final RoutedClients clients = new RoutedClients(
        RoutedClients.MAX_ROUTES, RoutedClients.UNUSED_ROUTE_TTL, RoutedClients.DISPOSE_GRACE);
    // Account traffic only: the administrator's exit probe dials on its own, so a suspended node can still be tested.
    RouteBreaker breaker = new RouteBreaker(RouteBreaker.FAILURES_TO_SUSPEND, RouteBreaker.SUSPENSION, System::nanoTime);
    // Trust for TLS to an https:// proxy: the JDK trust store, replaceable in tests.
    volatile SslContext proxyTls;

    // Requests that take this route are encoded and decoded here, so with the same codecs as the shared upstream client.
    private final ExchangeStrategies strategies;

    @Autowired
    public AccountEgressTransport(DatabaseClient db, ChannelRoutes routes, EgressProperties properties,
                                  ProxyProperties proxyProperties, ClashNodes parser,
                                  ObjectProvider<CodecCustomizer> codecCustomizers) {
        this(db, routes, properties, proxyProperties, parser, HttpClientConfig.upstreamStrategies(codecCustomizers));
    }

    /** With Spring's default codecs, for tests that run without an application context. */
    public AccountEgressTransport(DatabaseClient db, ChannelRoutes routes, EgressProperties properties,
                                  ProxyProperties proxyProperties, ClashNodes parser) {
        this(db, routes, properties, proxyProperties, parser,
            ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build());
    }

    private AccountEgressTransport(DatabaseClient db, ChannelRoutes routes, EgressProperties properties,
                                   ProxyProperties proxyProperties, ClashNodes parser, ExchangeStrategies strategies) {
        this.db = db; this.routes = routes; this.properties = properties;
        this.proxyProperties = proxyProperties; this.parser = parser; this.strategies = strategies;
    }

    public ExchangeFilterFunction filter() {
        return (request, next) -> {
            Object account = request.attributes().get(ACCOUNT);
            Object channel = request.attributes().get(CHANNEL);
            Mono<Settings> lookup;
            if (account instanceof ProxyAccountEntity entity) lookup = settings(entity);
            else if (channel instanceof UUID id) lookup = Mono.just(new Settings(id, "", ""));
            else return next.exchange(request);
            // Whatever goes wrong before a route is known happens before anything is sent.
            return lookup.flatMap(this::resolve)
                .onErrorMap(error -> !(error instanceof EgressException),
                    error -> EgressException.notSent(EgressFailure.ACCOUNT_ROUTE_UNAVAILABLE, error))
                .flatMap(route -> dispatch(request, next, route));
        };
    }

    Mono<Route> resolve(Settings settings) {
        if (settings.channelId() != null) {
            return routes.resolve(settings.channelId())
                .map(resolved -> new Route(ChannelRoutes.url(resolved.proxy()), resolved.proxy(), resolved.key(), settings.tlsProfile()));
        }
        String legacy = settings.legacyProxy().trim();
        if (legacy.isEmpty()) return Mono.just(new Route("", null, "", settings.tlsProfile()));
        try {
            ObjectNode proxy = parser.url("legacy", legacy).config();
            return Mono.just(new Route(ChannelRoutes.url(proxy), proxy, ChannelRoutes.key(proxy, null), settings.tlsProfile()));
        } catch (RuntimeException error) { return Mono.error(EgressException.notSent(EgressFailure.LEGACY_PROXY_INVALID, error)); }
    }

    private Mono<ClientResponse> dispatch(ClientRequest request, ExchangeFunction next, Route route) {
        if (!route.tlsProfile().isBlank()) return fingerprintExchange(request, route.url(), route.tlsProfile());
        if (route.proxy() == null) return next.exchange(request);
        if (!breaker.tryAcquire(route.key())) {
            return Mono.error(EgressException.notSent(EgressFailure.PROXY_ROUTE_SUSPENDED, null));
        }
        AtomicBoolean started = new AtomicBoolean();
        // Never fall back to a direct connection: a failed route fails the request.
        return exchangeFor(route.proxy(), route.key(), started).exchange(request)
            // Any answer, whatever its status, came through the route.
            .doOnNext(response -> breaker.succeeded(route.key()))
            .onErrorMap(error -> {
                EgressException failure = EgressException.transport(error, started.get(),
                    EgressFailure.PROXY_CONNECT_FAILED, EgressFailure.PROXY_TRANSPORT_FAILED);
                if (!failure.requestMayHaveBeenSent() && !EgressException.poolExhausted(error)) breaker.failed(route.key());
                return failure;
            });
    }

    private Mono<Settings> settings(ProxyAccountEntity account) {
        // Read the current binding before dispatch; a stale account cache must not bypass a disabled node.
        return db.sql("SELECT a.proxy_channel_id,COALESCE(a.proxy_url,'') legacy_proxy,CASE WHEN lower(a.provider) IN ('anthropic','claude') THEN COALESCE(p.tls_profile,'') ELSE '' END tls_profile FROM proxy_accounts a JOIN account_allocation_policies p USING(account_id) WHERE a.account_id=:id")
            .bind("id", account.getAccountId())
            .map((r,m) -> new Settings(r.get("proxy_channel_id", UUID.class), r.get("legacy_proxy", String.class), r.get("tls_profile", String.class)))
            .one().switchIfEmpty(Mono.error(EgressException.notSent(EgressFailure.ACCOUNT_ROUTE_UNAVAILABLE, null)));
    }

    Mono<ClientResponse> fingerprintExchange(ClientRequest request, String proxy, String profile) {
        if (!properties.isTlsEnabled() || !properties.getTlsProfiles().contains(profile)) {
            return Mono.error(EgressException.notSent(EgressFailure.TLS_PROFILE_UNAVAILABLE, null));
        }
        if (properties.getTlsSecret().isBlank()) return Mono.error(EgressException.notSent(EgressFailure.TLS_COMPONENT_UNAVAILABLE, null));
        ClientRequest bridge = ClientRequest.from(request).url(URI.create(properties.getTlsUrl() + "/forward"))
            .headers(headers -> {
                headers.remove(HttpHeaders.HOST);
                headers.remove(HttpHeaders.CONTENT_LENGTH);
                headers.set("X-Starry-Target", request.url().toString());
                headers.set("X-Starry-Proxy", proxy);
                headers.set("X-Starry-Tls-Profile", profile);
                headers.set("X-Starry-Bridge-Key", properties.getTlsSecret());
            }).build();
        AtomicBoolean started = new AtomicBoolean();
        return exchange(clients.get("bridge|" + properties.getTlsUrl(), client -> client), started).exchange(bridge)
            .onErrorMap(error -> EgressException.transport(error, started.get(),
                EgressFailure.TLS_COMPONENT_UNAVAILABLE, EgressFailure.TLS_COMPONENT_FAILED))
            .flatMap(response -> {
                var error = response.headers().header("X-Starry-Egress-Error");
                if (error.isEmpty() && response.headers().header("X-Starry-Bridge-Response").contains("1")) {
                    return Mono.just(response.mutate().headers(h -> h.remove("X-Starry-Bridge-Response")).build());
                }
                // The bridge decides authentication and routing errors before forwarding anything; any other
                // answer ("transport", or a reply from something that is not the bridge) proves nothing.
                boolean refused = error.contains("authentication") || error.contains("invalid-route");
                return response.releaseBody().then(Mono.error(refused
                    ? EgressException.notSent(EgressFailure.TLS_COMPONENT_REJECTED, null)
                    : EgressException.maybeSent(EgressFailure.TLS_COMPONENT_FAILED, null)));
            });
    }

    ExchangeFunction exchangeForProxy(String url) {
        ObjectNode proxy = parser.url("legacy", url).config();
        return exchangeFor(proxy, ChannelRoutes.key(proxy, null), new AtomicBoolean());
    }

    private ExchangeFunction exchangeFor(ObjectNode proxy, String key, AtomicBoolean started) {
        return exchange(clients.get(key, client -> ChannelRoutes.through(client, proxy, proxyTlsContext())), started);
    }

    private SslContext proxyTlsContext() {
        SslContext context = proxyTls;
        if (context == null) proxyTls = context = ChannelRoutes.defaultProxyTls();
        return context;
    }

    /** Connections of the cached client are reused; only what belongs to this one request is set up here. */
    private ExchangeFunction exchange(HttpClient pooled, AtomicBoolean started) {
        // doOnRequest runs once the connection (proxy and TLS handshakes included) is ready and the request is
        // about to be written, so a failure before it proves the request never left.
        HttpClient client = pooled.followRedirect(false).compress(true).responseTimeout(proxyProperties.getRequestTimeout())
            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .doOnRequest((httpRequest, connection) -> started.set(true));
        return ExchangeFunctions.create(new ReactorClientHttpConnector(client), strategies);
    }

    long pooledRoutes() { return clients.size(); }

    @jakarta.annotation.PreDestroy
    void shutdown() { clients.close(); }

    record Settings(UUID channelId, String legacyProxy, String tlsProfile) {}
    /** {@code proxy} is null for a direct connection (an account without a binding); {@code url} is the same for the TLS component. */
    record Route(String url, ObjectNode proxy, String key, String tlsProfile) {}
}
