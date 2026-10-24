package tech.starryapi.egress;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.config.ProxyProperties;

/** Connection reuse per route (A9) and TLS to an https:// proxy plus remote name resolution through SOCKS5 (A10). */
class AccountEgressPoolingTest {
    private static final UUID CHANNEL = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();

    // ---- A9: pooled connections ----

    @Test void twoRequestsOverTheSameRouteReuseOneConnection() throws Exception {
        try (var proxy = new KeepAliveProxy(new ServerSocket(0, 20, InetAddress.getLoopbackAddress()))) {
            var routes = mock(ChannelRoutes.class);
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(proxy.port(), "v1")));
            var transport = transport(routes);
            var client = client(transport);

            assertThat(get(client)).isEqualTo("response-1");
            assertThat(get(client)).isEqualTo("response-2");

            assertThat(proxy.connections).hasValue(1);
            assertThat(transport.pooledRoutes()).isEqualTo(1);
            transport.shutdown();
        }
    }

    @Test void aChangedRouteGetsANewPoolEvenAtTheSameAddress() throws Exception {
        try (var proxy = new KeepAliveProxy(new ServerSocket(0, 20, InetAddress.getLoopbackAddress()))) {
            var routes = mock(ChannelRoutes.class);
            var transport = transport(routes);
            var client = client(transport);
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(proxy.port(), "v1")));
            get(client);
            // The node behind the listener changed (another fingerprint): its pool must not see the old sockets.
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(proxy.port(), "v2")));
            get(client);
            assertThat(proxy.connections).hasValue(2);
            assertThat(transport.pooledRoutes()).isEqualTo(2);
            // Other credentials at the same address are another route as well.
            var withLogin = json.createObjectNode().put("type", "http").put("server", "127.0.0.1").put("port", proxy.port())
                .put("username", "user").put("password", "pass");
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(new ChannelRoutes.Resolved(ClashNodes.DIRECT_PROXY, withLogin, ChannelRoutes.key(withLogin, null))));
            get(client);
            assertThat(proxy.connections).hasValue(3);
            // ...while the first route still has its own live connection.
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(proxy.port(), "v1")));
            get(client);
            assertThat(proxy.connections).hasValue(3);
            transport.shutdown();
        }
    }

    @Test void theBindingIsResolvedBeforeEveryDispatchEvenWhenAConnectionIsIdle() throws Exception {
        try (var proxy = new KeepAliveProxy(new ServerSocket(0, 20, InetAddress.getLoopbackAddress()))) {
            var routes = mock(ChannelRoutes.class);
            var transport = transport(routes);
            var client = client(transport);
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(proxy.port(), "v1")));
            get(client);
            // The node is disabled now: the idle connection must not be used, and nothing is sent anywhere.
            when(routes.resolve(CHANNEL)).thenReturn(Mono.error(new EgressException(EgressFailure.NODE_UNAVAILABLE)));
            assertThatThrownBy(() -> get(client)).isInstanceOfSatisfying(EgressException.class, error -> {
                assertThat(error.failure()).isEqualTo(EgressFailure.NODE_UNAVAILABLE);
                assertThat(error.requestMayHaveBeenSent()).isFalse();
            });
            assertThat(proxy.requests).hasValue(1);
            assertThat(proxy.connections).hasValue(1);
            transport.shutdown();
        }
    }

    @Test void aRouteThatKeepsFailingToConnectIsSkippedUntilItIsTriedAgain() throws Exception {
        try (var proxy = new KeepAliveProxy(new ServerSocket(0, 20, InetAddress.getLoopbackAddress()))) {
            proxy.refuseTunnels = true;
            var routes = mock(ChannelRoutes.class);
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(proxy.port(), "v1")));
            var transport = transport(routes);
            var clock = new java.util.concurrent.atomic.AtomicLong();
            transport.breaker = new RouteBreaker(2, Duration.ofSeconds(15), clock::get);
            var client = client(transport);

            // The node behind the proxy is down: the proxy answers CONNECT with an error, twice.
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> get(client)).isInstanceOfSatisfying(EgressException.class, error -> {
                    assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_CONNECT_FAILED);
                    assertThat(error.requestMayHaveBeenSent()).isFalse();
                });
            }
            assertThat(proxy.connections).hasValue(2);

            // Now requests fail at once, without dialing, and may still move to another account.
            assertThatThrownBy(() -> get(client)).isInstanceOfSatisfying(EgressException.class, error -> {
                assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_ROUTE_SUSPENDED);
                assertThat(error.requestMayHaveBeenSent()).isFalse();
            });
            assertThat(proxy.connections).hasValue(2);

            // The node recovered; after the pause the route is tried again and stays in use.
            proxy.refuseTunnels = false;
            clock.addAndGet(Duration.ofSeconds(15).toNanos());
            assertThat(get(client)).startsWith("response-");
            assertThat(get(client)).startsWith("response-");
            transport.shutdown();
        }
    }

    @Test void aTunnelThatClosesRightAfterConnectBeforeTheTlsHandshakeIsNotSentAndCountsAgainstTheRoute() throws Exception {
        // Mihomo answers CONNECT with 200 before it dials the node; a dead node then closes the tunnel. Upstreams are
        // HTTPS, so that happens during the TLS handshake, before anything of the request was written.
        try (var proxy = new KeepAliveProxy(new ServerSocket(0, 20, InetAddress.getLoopbackAddress()))) {
            proxy.closeAfterConnect = true;
            var routes = mock(ChannelRoutes.class);
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(proxy.port(), "v1")));
            var transport = transport(routes);
            var clock = new java.util.concurrent.atomic.AtomicLong();
            transport.breaker = new RouteBreaker(2, Duration.ofSeconds(15), clock::get);
            var client = client(transport);

            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> client.get().uri("https://target.invalid/test").attribute(AccountEgressTransport.CHANNEL, CHANNEL)
                        .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10)))
                    .isInstanceOfSatisfying(EgressException.class, error -> {
                        assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_CONNECT_FAILED);
                        assertThat(error.requestMayHaveBeenSent()).isFalse();
                    });
            }
            // Reactor Netty may reconnect once by itself when a connection closes before anything was sent, so the
            // two failed requests made two or more connections; a suspended request makes none.
            int dialled = proxy.connections.get();
            assertThat(dialled).isGreaterThanOrEqualTo(2);
            assertThatThrownBy(() -> client.get().uri("https://target.invalid/test").attribute(AccountEgressTransport.CHANNEL, CHANNEL)
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(EgressException.class, error ->
                    assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_ROUTE_SUSPENDED));
            assertThat(proxy.connections).hasValue(dialled);
            transport.shutdown();
        }
    }

    @Test void evictedPoolsAreDisposedButARequestInFlightSurvivesIt() throws Exception {
        try (var idle = new KeepAliveProxy(new ServerSocket(0, 20, InetAddress.getLoopbackAddress()), false);
             var slow = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             var clients = new RoutedClients(1, Duration.ofMinutes(5), Duration.ZERO)) {
            var first = clients.get("first", client -> client.noSSL().host("127.0.0.1").port(idle.port()));
            assertThat(first.get().uri("/").responseContent().aggregate().asString().block(Duration.ofSeconds(5))).isEqualTo("response-1");
            await().atMost(Duration.ofSeconds(5)).until(() -> idle.connections.get() == 1);

            // A request is still being answered when its pool is pushed out of the cache.
            var release = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                try (var socket = slow.accept()) {
                    headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nab");
                    release.await(5, TimeUnit.SECONDS);
                    write(socket, "cdef");
                } catch (Exception ignored) { }
            });
            var inFlight = clients.get("second", client -> client.noSSL().host("127.0.0.1").port(slow.getLocalPort()))
                .get().uri("/").responseContent().aggregate().asString().toFuture();
            Thread.sleep(300);
            clients.get("third", client -> client);
            clients.size();

            // Disposing the evicted pool closes its idle connection...
            await().atMost(Duration.ofSeconds(10)).until(() -> idle.closed.get() == 1);
            // ...but not the exchange that was in flight.
            release.countDown();
            assertThat(inFlight.get(5, TimeUnit.SECONDS)).isEqualTo("abcdef");
        }
    }

    // ---- A10: https:// proxies ----

    @Test void anHttpsProxyIsReachedOverTlsVerifiedAgainstTheTrustStoreAndTheNameOfTheNode(@TempDir Path directory) throws Exception {
        var keys = keystore(directory.resolve("good.p12"), "dns:localhost,ip:127.0.0.1");
        try (var proxy = new KeepAliveProxy(tlsServer(keys))) {
            var node = json.createObjectNode().put("type", "http").put("server", "localhost").put("port", proxy.port())
                .put("tls", true).put("username", "user").put("password", "pass");
            var routes = mock(ChannelRoutes.class);
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(direct(node)));
            var transport = transport(routes);
            transport.proxyTls = trusting(keys);
            var client = client(transport);

            assertThat(get(client)).isEqualTo("response-1");
            assertThat(get(client)).isEqualTo("response-2");

            assertThat(proxy.firstRequest.get(5, TimeUnit.SECONDS)).contains("CONNECT target.invalid:80", "proxy-authorization: Basic dXNlcjpwYXNz");
            assertThat(proxy.connections).hasValue(1);
            transport.shutdown();
        }
    }

    @Test void anHttpsProxyWithAnUntrustedOrMismatchedCertificateIsNeverUsedAndNothingIsSent(@TempDir Path directory) throws Exception {
        var otherName = keystore(directory.resolve("other.p12"), "dns:other.example");
        try (var proxy = new KeepAliveProxy(tlsServer(otherName))) {
            var node = json.createObjectNode().put("type", "http").put("server", "localhost").put("port", proxy.port()).put("tls", true);
            var routes = mock(ChannelRoutes.class);
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(direct(node)));

            // The certificate is trusted but belongs to another name.
            var mismatched = transport(routes);
            mismatched.proxyTls = trusting(otherName);
            assertThatThrownBy(() -> get(client(mismatched))).isInstanceOfSatisfying(EgressException.class, error -> {
                assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_CONNECT_FAILED);
                assertThat(error.requestMayHaveBeenSent()).isFalse();
                assertThat(error.getCause()).isNotNull();
            });
            // The certificate is not trusted at all (the default trust store).
            var untrusted = transport(routes);
            assertThatThrownBy(() -> get(client(untrusted))).isInstanceOfSatisfying(EgressException.class,
                error -> assertThat(error.requestMayHaveBeenSent()).isFalse());
            assertThat(proxy.requests).hasValue(0);
        }
    }

    @Test void socks5AndSocks5hBothLeaveTheNameResolutionToTheProxy() throws Exception {
        for (String scheme : new String[] {"socks5", "socks5h"}) {
            try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
                var target = new CompletableFuture<String>();
                Thread.ofVirtual().start(() -> {
                    try (var socket = proxy.accept()) {
                        socket.setSoTimeout(5000);
                        var input = new DataInputStream(socket.getInputStream());
                        input.readUnsignedByte();
                        input.readNBytes(input.readUnsignedByte());
                        socket.getOutputStream().write(new byte[]{5, 0});
                        // CONNECT with an address type: 3 is a domain name that the proxy must resolve.
                        int[] header = {input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte()};
                        String name = header[3] == 3 ? new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8) : "resolved locally";
                        input.readUnsignedShort();
                        socket.getOutputStream().write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                        headers(input);
                        write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok");
                        target.complete(name);
                    } catch (Throwable error) { target.completeExceptionally(error); }
                });
                var transport = transport(mock(ChannelRoutes.class));
                var exchange = transport.exchangeForProxy(scheme + "://127.0.0.1:" + proxy.getLocalPort());
                var body = exchange.exchange(org.springframework.web.reactive.function.client.ClientRequest
                    .create(org.springframework.http.HttpMethod.GET, URI.create("http://sensitive-name.invalid/x")).build())
                    .flatMap(response -> response.bodyToMono(String.class)).block(Duration.ofSeconds(8));
                assertThat(body).as(scheme).isEqualTo("ok");
                assertThat(target.get(5, TimeUnit.SECONDS)).as(scheme).isEqualTo("sensitive-name.invalid");
                transport.shutdown();
            }
        }
    }

    @Test void theBridgeIsToldToResolveNamesRemotelyAndToUseTlsForAnHttpsProxy() {
        var socks = json.createObjectNode().put("type", "socks5").put("server", "node.example").put("port", 1080);
        assertThat(ChannelRoutes.url(socks)).startsWith("socks5h://");
        var https = json.createObjectNode().put("type", "http").put("server", "node.example").put("port", 8443).put("tls", true);
        assertThat(ChannelRoutes.url(https)).isEqualTo("https://node.example:8443");
        var parser = new ClashNodes(json);
        assertThat(ChannelRoutes.url(parser.url("A", "socks5://u:p@node.example:1080").config())).isEqualTo("socks5h://u:p@node.example:1080");
        assertThat(ChannelRoutes.url(parser.url("A", "https://u:p@node.example:8443").config())).isEqualTo("https://u:p@node.example:8443");
    }

    // ---- fixtures ----

    private AccountEgressTransport transport(ChannelRoutes routes) {
        var proxyProperties = new ProxyProperties();
        proxyProperties.setRequestTimeout(Duration.ofSeconds(5));
        return new AccountEgressTransport(null, routes, new EgressProperties(), proxyProperties, new ClashNodes(json));
    }

    private WebClient client(AccountEgressTransport transport) {
        return WebClient.builder().filter(transport.filter())
            .exchangeFunction(request -> Mono.error(new AssertionError("Must never use direct transport"))).build();
    }

    private String get(WebClient client) {
        return client.get().uri("http://target.invalid/test").attribute(AccountEgressTransport.CHANNEL, CHANNEL)
            .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
    }

    private ChannelRoutes.Resolved listener(int port, String version) {
        ObjectNode proxy = json.createObjectNode().put("type", "http").put("server", "127.0.0.1").put("port", port);
        return new ChannelRoutes.Resolved(ClashNodes.MIHOMO, proxy, ChannelRoutes.key(proxy, version));
    }

    private ChannelRoutes.Resolved direct(ObjectNode proxy) {
        return new ChannelRoutes.Resolved(ClashNodes.DIRECT_PROXY, proxy, ChannelRoutes.key(proxy, null));
    }

    /** Keep-alive HTTP proxy: counts connections and requests; the first connect request is kept for inspection. */
    private static final class KeepAliveProxy implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final CompletableFuture<String> firstRequest = new CompletableFuture<>();

        private final boolean tunnel;
        /** Answers CONNECT with 502, like a proxy whose upstream node is unreachable. */
        volatile boolean refuseTunnels;
        /** Answers CONNECT with 200 and then closes, like Mihomo when the node behind a listener is dead. */
        volatile boolean closeAfterConnect;

        KeepAliveProxy(ServerSocket server) { this(server, true); }

        /** {@code tunnel} false is a plain keep-alive HTTP server that expects no CONNECT. */
        KeepAliveProxy(ServerSocket server, boolean tunnel) {
            this.server = server;
            this.tunnel = tunnel;
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        Thread.ofVirtual().start(() -> serve(socket));
                    } catch (Exception ignored) { return; }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        private void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(30000);
                connections.incrementAndGet();
                if (tunnel) {
                    firstRequest.complete(headers(socket.getInputStream()));
                    if (refuseTunnels) {
                        write(socket, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
                        return;
                    }
                    write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    if (closeAfterConnect) return;
                }
                while (true) {
                    headers(socket.getInputStream());
                    String body = "response-" + requests.incrementAndGet();
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
                }
            } catch (Exception endOfConnection) {
                closed.incrementAndGet();
            }
        }

        @Override public void close() throws IOException { server.close(); }
    }

    private static String headers(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() < 16384) {
            int next = input.read();
            if (next < 0) throw new EOFException();
            bytes.write(next);
            if (bytes.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) return bytes.toString(StandardCharsets.ISO_8859_1);
        }
        throw new IOException("Oversized test headers");
    }

    private static void write(Socket socket, String value) throws IOException {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static void write(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static KeyStore keystore(Path file, String subjectAlternativeNames) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "proxy", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=test-proxy", "-ext", "san=" + subjectAlternativeNames, "-storetype", "PKCS12",
            "-keystore", file.toString(), "-storepass", "changeit").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        var keys = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(file.toFile())) { keys.load(in, "changeit".toCharArray()); }
        return keys;
    }

    private static ServerSocket tlsServer(KeyStore keys) throws Exception {
        var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, "changeit".toCharArray());
        var context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context.getServerSocketFactory().createServerSocket(0, 20, InetAddress.getLoopbackAddress());
    }

    private static SslContext trusting(KeyStore keys) throws Exception {
        return SslContextBuilder.forClient().trustManager((X509Certificate) keys.getCertificate("proxy")).build();
    }
}
