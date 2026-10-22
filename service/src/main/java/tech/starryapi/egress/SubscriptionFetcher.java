package tech.starryapi.egress;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

@Component
public class SubscriptionFetcher {
    public Mono<String> fetch(String url) { return fetch(url, 0); }
    private Mono<String> fetch(String url, int redirects) {
        return Mono.fromCallable(() -> validated(url)).subscribeOn(Schedulers.boundedElastic())
            .flatMap(target -> {
                // Pin the checked address while preserving the original Host and TLS SNI.
                var client = HttpClient.newConnection().followRedirect(false)
                    .resolver(pinnedResolver(target))
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .responseTimeout(Duration.ofSeconds(15));
                return WebClient.builder().clientConnector(new ReactorClientHttpConnector(client))
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build()
                    .get().uri(target.uri()).header("User-Agent", "Clash.Meta/StarryAPI")
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status >= 300 && status < 400) {
                            String location = response.headers().asHttpHeaders().getFirst("Location");
                            if (redirects >= 3 || location == null) return response.releaseBody().then(Mono.error(ClashNodes.invalid("Subscription redirect limit exceeded")));
                            return response.releaseBody().then(fetch(target.uri().resolve(location).toString(), redirects + 1));
                        }
                        if (status != 200) return response.releaseBody().then(Mono.error(ClashNodes.invalid("Subscription server returned HTTP " + status)));
                        return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                            .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
                    }).timeout(Duration.ofSeconds(20));
            }).onErrorMap(error -> error instanceof tech.starryapi.common.ApiException ? error
                : ClashNodes.invalid("Subscription download failed; check the URL and server connectivity"));
    }
    static io.netty.resolver.AddressResolverGroup<InetSocketAddress> pinnedResolver(Target target) {
        return new io.netty.resolver.AddressResolverGroup<>() {
            @Override protected io.netty.resolver.AddressResolver<InetSocketAddress> newResolver(io.netty.util.concurrent.EventExecutor executor) {
                return new io.netty.resolver.AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                    @Override protected boolean doIsResolved(InetSocketAddress address) { return false; }
                    private InetSocketAddress pinned() throws UnknownHostException {
                        // Keep the logical hostname for TLS SNI while fixing the already-checked IP bytes.
                        return new InetSocketAddress(InetAddress.getByAddress(target.uri().getHost(), target.address().getAddress()), target.port());
                    }
                    @Override protected void doResolve(InetSocketAddress address, io.netty.util.concurrent.Promise<InetSocketAddress> promise) throws Exception {
                        promise.setSuccess(pinned());
                    }
                    @Override protected void doResolveAll(InetSocketAddress address, io.netty.util.concurrent.Promise<List<InetSocketAddress>> promise) throws Exception {
                        promise.setSuccess(List.of(pinned()));
                    }
                };
            }
        };
    }
    static Target validated(String url) throws Exception {
        URI uri = URI.create(url);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
            || uri.getRawUserInfo() != null || uri.getFragment() != null) throw ClashNodes.invalid("Subscription URL must use HTTP or HTTPS without user information");
        int port = uri.getPort() == -1 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65535) throw ClashNodes.invalid("Invalid subscription port");
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        for (InetAddress address : addresses) if (!publicAddress(address)) throw ClashNodes.invalid("Subscription targets must resolve to public addresses");
        return new Target(uri, addresses[0], port);
    }
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255;
            return a != 0 && a < 224 && a != 127 && !(a == 100 && c >= 64 && c <= 127)
                && !(a == 198 && (c == 18 || c == 19)) && !(a == 192 && c == 0);
        }
        return (b[0] & 0xe0) == 0x20; // Public IPv6 global unicast only; excludes ULA and mapped IPv4.
    }
    record Target(URI uri, InetAddress address, int port) {}
}
