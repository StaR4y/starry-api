package tech.starryapi.egress;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Pooled HTTP clients, one connection pool per resolved route. The pool only reuses sockets: the route is decided
 * for every request before a client is asked for, and a route whose proxy, credentials or (for Mihomo) node
 * version changed has another key and so never sees the sockets of the old one. A pool that is evicted or unused
 * for a while is disposed; connections that are still in use are closed when their request ends.
 *
 * <p>Disposal waits {@link #DISPOSE_GRACE}: the size bound may evict an entry right after it was handed out (the
 * cache can reject a new entry in favour of a more frequently used one). Disposing at once would fail the requests
 * still waiting for a connection of that pool, and a request that acquires afterwards would silently open a new pool
 * inside the disposed provider that nothing disposes again.
 */
final class RoutedClients implements AutoCloseable {
    // An idle pool holds no sockets, so the bound is only there to cap bookkeeping; sites have hundreds of accounts
    // with a proxy each.
    static final int MAX_ROUTES = 2000;
    static final Duration UNUSED_ROUTE_TTL = Duration.ofMinutes(5);
    static final Duration DISPOSE_GRACE = Duration.ofSeconds(30);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final Cache<String, Pooled> cache;

    RoutedClients(int maxRoutes, Duration unusedRouteTtl, Duration disposeGrace) {
        this.cache = Caffeine.newBuilder().maximumSize(maxRoutes).expireAfterAccess(unusedRouteTtl)
            .removalListener((String key, Pooled pooled, RemovalCause cause) -> {
                if (pooled == null) return;
                // close() disposes at once; an eviction gives requests that already hold the client time to start.
                Mono<Void> dispose = pooled.provider().disposeLater();
                (cause == RemovalCause.EXPLICIT || disposeGrace.isZero() ? dispose : Mono.delay(disposeGrace).then(dispose))
                    .subscribe(null, ignored -> { });
            }).build();
    }

    /** The client of a route; {@code configure} runs once per pool, so its lambdas keep one identity. */
    HttpClient get(String key, UnaryOperator<HttpClient> configure) {
        return cache.get(key, ignored -> {
            // Like the shared upstream pool: modest limits and short-lived idle connections, because proxies and
            // targets close idle keep-alive connections early and a dead one would fail the next request.
            ConnectionProvider provider = ConnectionProvider.builder("starry-egress-" + SEQUENCE.incrementAndGet())
                .maxConnections(200)
                .pendingAcquireMaxCount(400)
                .pendingAcquireTimeout(Duration.ofSeconds(10))
                .maxIdleTime(Duration.ofSeconds(20))
                .evictInBackground(Duration.ofSeconds(15))
                .maxLifeTime(Duration.ofMinutes(10))
                .build();
            return new Pooled(provider, configure.apply(HttpClient.create(provider)));
        }).client();
    }

    long size() { cache.cleanUp(); return cache.estimatedSize(); }

    @Override public void close() { cache.invalidateAll(); cache.cleanUp(); }

    private record Pooled(ConnectionProvider provider, HttpClient client) {}
}
