package tech.starryapi.egress;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.config.ProxyProperties;
import tools.jackson.databind.ObjectMapper;

class AccountEgressTransportTest {
    private final AccountEgressTransport transport = new AccountEgressTransport(null, null,
        new EgressProperties(), new ProxyProperties(), new ClashNodes(new ObjectMapper()));

    @Test void authenticatedHttpConnectUsesTheSelectedExitAndRemoteTarget() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var observed = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    String connect = headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    String request = headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nexit-a");
                    observed.complete(connect + request);
                } catch (Exception error) { observed.completeExceptionally(error); }
            });
            assertThat(fetch("http://user:pass@127.0.0.1:" + proxy.getLocalPort())).isEqualTo("exit-a");
            assertThat(observed.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .contains("CONNECT target.invalid:80", "proxy-authorization: Basic dXNlcjpwYXNz", "Authorization: Bearer upstream-test");
        }
    }

    @Test void authenticatedSocks5DoesNotResolveTheUpstreamLocally() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var observed = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    assertThat(input.readUnsignedByte()).isEqualTo(5);
                    input.readNBytes(input.readUnsignedByte());
                    socket.getOutputStream().write(new byte[]{5, 2});
                    assertThat(input.readUnsignedByte()).isEqualTo(1);
                    String user = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    String password = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    socket.getOutputStream().write(new byte[]{1, 0});
                    assertThat(input.readNBytes(4)).containsExactly(5, 1, 0, 3);
                    String target = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    input.readUnsignedShort();
                    socket.getOutputStream().write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                    headers(input);
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nexit-b");
                    observed.complete(user + ":" + password + "@" + target);
                } catch (Throwable error) { observed.completeExceptionally(error); }
            });
            assertThat(fetch("socks5://user:pass@127.0.0.1:" + proxy.getLocalPort())).isEqualTo("exit-b");
            assertThat(observed.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("user:pass@target.invalid");
        }
    }

    // ---- failures of the selected route: what was sent, what is retried, and never a direct connection ----

    private static final UUID CHANNEL = UUID.randomUUID();

    @Test void aMissingOrInactiveNodeNeverSendsAnythingAndNeverGoesDirect() {
        var routes = mock(ChannelRoutes.class);
        when(routes.resolve(CHANNEL)).thenReturn(Mono.error(new EgressException(EgressFailure.NODE_NOT_ACTIVE)));
        var direct = new AtomicBoolean();
        var error = failureOf(clientFor(routes, new ProxyProperties(), direct));
        assertThat(error.failure()).isEqualTo(EgressFailure.NODE_NOT_ACTIVE);
        assertThat(error.requestMayHaveBeenSent()).isFalse();
        assertThat(direct).isFalse();
    }

    @Test void aFailedRouteLookupIsKeptAsTheCauseAndStillNotSent() {
        var routes = mock(ChannelRoutes.class);
        when(routes.resolve(CHANNEL)).thenReturn(Mono.error(new IllegalStateException("database unavailable")));
        var error = failureOf(clientFor(routes, new ProxyProperties(), new AtomicBoolean()));
        assertThat(error.failure()).isEqualTo(EgressFailure.ACCOUNT_ROUTE_UNAVAILABLE);
        assertThat(error.requestMayHaveBeenSent()).isFalse();
        assertThat(error).hasRootCauseMessage("database unavailable");
    }

    @Test void aListenerThatRefusesTheConnectionKeepsTheCauseAndIsNotSent() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { closedPort = socket.getLocalPort(); }
        var error = failureOf(clientForPort(closedPort, new AtomicBoolean()));
        assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_CONNECT_FAILED);
        assertThat(error.requestMayHaveBeenSent()).isFalse();
        assertThat(error.getCause()).isNotNull();
        assertThat(EgressException.failedBeforeRequest(error.getCause())).isTrue();
    }

    @Test void aProxyThatRejectsTheTunnelHandshakeIsNotSent() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
                } catch (Exception ignored) { }
            });
            var error = failureOf(clientForPort(proxy.getLocalPort(), new AtomicBoolean()));
            assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_CONNECT_FAILED);
            assertThat(error.requestMayHaveBeenSent()).isFalse();
            assertThat(error.getCause()).isNotNull();
        }
    }

    @Test void aTunnelThatBreaksDuringTheTlsHandshakeIsNotSent() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    write(socket, "this is not a TLS server\r\n");
                } catch (Exception ignored) { }
            });
            var error = failureOf(clientForPort(proxy.getLocalPort(), new AtomicBoolean()), "https://target.invalid/test");
            assertThat(error.requestMayHaveBeenSent()).isFalse();
            assertThat(error.getCause()).isNotNull();
        }
    }

    @Test void aResetAfterTheRequestWasWrittenMayHaveBeenProcessed() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    headers(socket.getInputStream());
                } catch (Exception ignored) { }
            });
            var error = failureOf(clientForPort(proxy.getLocalPort(), new AtomicBoolean()));
            assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_TRANSPORT_FAILED);
            assertThat(error.requestMayHaveBeenSent()).isTrue();
            assertThat(error.getCause()).isNotNull();
        }
    }

    @Test void aResponseTimeoutThroughTheProxyMayHaveBeenProcessed() throws Exception {
        var properties = new ProxyProperties();
        properties.setRequestTimeout(Duration.ofMillis(400));
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var hold = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    headers(socket.getInputStream());
                    hold.get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) { }
            });
            var routes = mock(ChannelRoutes.class);
            when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(proxy.getLocalPort())));
            var error = failureOf(clientFor(routes, properties, new AtomicBoolean()));
            hold.complete(null);
            assertThat(error.failure()).isEqualTo(EgressFailure.PROXY_TRANSPORT_FAILED);
            assertThat(error.requestMayHaveBeenSent()).isTrue();
        }
    }

    @Test void anUnusableLegacyProxyFailsClosedInsteadOfBeingIgnored() {
        var error = catchThrowableOfType(EgressException.class, () ->
            transport.resolve(new AccountEgressTransport.Settings(null, "ftp://not-supported:21", "")).block());
        assertThat(error.failure()).isEqualTo(EgressFailure.LEGACY_PROXY_INVALID);
        assertThat(error.requestMayHaveBeenSent()).isFalse();
        assertThat(error.getCause()).isNotNull();
    }

    // ---- a plain HTTP or SOCKS5 channel is connected to directly, with the credentials of its stored node ----

    @Test void aDirectHttpChannelUsesItsStoredCredentials() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var observed = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    String connect = headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 Connection established\r\n\r\n");
                    headers(socket.getInputStream());
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nexit-c");
                    observed.complete(connect);
                } catch (Exception error) { observed.completeExceptionally(error); }
            });
            var node = new ObjectMapper().createObjectNode().put("type", "http").put("server", "127.0.0.1")
                .put("port", proxy.getLocalPort()).put("username", "user").put("password", "pass");
            assertThat(bodyThrough(direct(node))).isEqualTo("exit-c");
            assertThat(observed.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .contains("CONNECT target.invalid:80", "proxy-authorization: Basic dXNlcjpwYXNz");
        }
    }

    @Test void aDirectSocks5ChannelUsesItsStoredCredentials() throws Exception {
        try (var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            var observed = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    input.readUnsignedByte();
                    input.readNBytes(input.readUnsignedByte());
                    socket.getOutputStream().write(new byte[]{5, 2});
                    input.readUnsignedByte();
                    String user = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    String password = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    socket.getOutputStream().write(new byte[]{1, 0});
                    input.readNBytes(4);
                    String target = new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.UTF_8);
                    input.readUnsignedShort();
                    socket.getOutputStream().write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                    headers(input);
                    write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nexit-d");
                    observed.complete(user + ":" + password + "@" + target);
                } catch (Throwable error) { observed.completeExceptionally(error); }
            });
            var node = new ObjectMapper().createObjectNode().put("type", "socks5").put("server", "127.0.0.1")
                .put("port", proxy.getLocalPort()).put("username", "user").put("password", "pass");
            assertThat(bodyThrough(direct(node))).isEqualTo("exit-d");
            assertThat(observed.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("user:pass@target.invalid");
        }
    }

    @Test void theBridgeGetsAProxyUrlWithEncodedCredentials() {
        var node = new ObjectMapper().createObjectNode().put("type", "socks5").put("server", "node.example").put("port", 1080)
            .put("username", "us er").put("password", "p@ss:/+");
        assertThat(ChannelRoutes.url(node)).isEqualTo("socks5h://us%20er:p%40ss%3A%2F%2B@node.example:1080");
        assertThat(ChannelRoutes.url(new ObjectMapper().createObjectNode().put("type", "http").put("server", "127.0.0.1").put("port", 20001)))
            .isEqualTo("http://127.0.0.1:20001");
    }

    private String bodyThrough(ChannelRoutes.Resolved resolved) {
        var routes = mock(ChannelRoutes.class);
        when(routes.resolve(CHANNEL)).thenReturn(Mono.just(resolved));
        var proxyProperties = new ProxyProperties();
        proxyProperties.setRequestTimeout(Duration.ofSeconds(5));
        return clientFor(routes, proxyProperties, new AtomicBoolean()).get().uri("http://target.invalid/test")
            .attribute(AccountEgressTransport.CHANNEL, CHANNEL).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(8));
    }

    // ---- the TLS bridge ----

    @Test void anUnreachableBridgeIsNotSentButAnAmbiguousBridgeFailureMayHaveBeen() throws Exception {
        var properties = new EgressProperties();
        properties.setTlsEnabled(true);
        properties.setTlsSecret("bridge-secret");
        int closedPort;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { closedPort = socket.getLocalPort(); }
        properties.setTlsUrl("http://127.0.0.1:" + closedPort);
        var unreachable = bridgeFailure(properties);
        assertThat(unreachable.failure()).isEqualTo(EgressFailure.TLS_COMPONENT_UNAVAILABLE);
        assertThat(unreachable.requestMayHaveBeenSent()).isFalse();
        assertThat(unreachable.getCause()).isNotNull();

        for (var answer : new String[][] {
            {"401 Unauthorized", "authentication", "false"}, {"400 Bad Request", "invalid-route", "false"},
            {"502 Bad Gateway", "transport", "true"}, {"502 Bad Gateway", "something-new", "true"}, {"404 Not Found", null, "true"}}) {
            try (var bridge = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
                Thread.ofVirtual().start(() -> {
                    try (var socket = bridge.accept()) {
                        socket.setSoTimeout(5000);
                        headers(socket.getInputStream());
                        write(socket, "HTTP/1.1 " + answer[0] + "\r\n" + (answer[1] == null ? "" : "X-Starry-Egress-Error: " + answer[1] + "\r\n")
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                    } catch (Exception ignored) { }
                });
                properties.setTlsUrl("http://127.0.0.1:" + bridge.getLocalPort());
                var failure = bridgeFailure(properties);
                assertThat(failure.requestMayHaveBeenSent()).as(String.join("/", java.util.Objects.toString(answer[1]), answer[0]))
                    .isEqualTo(Boolean.parseBoolean(answer[2]));
            }
        }
    }

    @Test void anUnavailableTlsProfileOrMissingBridgeKeyIsNeverSent() {
        var properties = new EgressProperties();
        properties.setTlsSecret("bridge-secret");
        var disabled = bridgeFailure(properties);
        assertThat(disabled.failure()).isEqualTo(EgressFailure.TLS_PROFILE_UNAVAILABLE);
        assertThat(disabled.requestMayHaveBeenSent()).isFalse();
        properties.setTlsEnabled(true);
        properties.setTlsSecret("");
        var unauthenticated = bridgeFailure(properties);
        assertThat(unauthenticated.failure()).isEqualTo(EgressFailure.TLS_COMPONENT_UNAVAILABLE);
        assertThat(unauthenticated.requestMayHaveBeenSent()).isFalse();
    }

    private EgressException bridgeFailure(EgressProperties properties) {
        var bridgeTransport = new AccountEgressTransport(null, null, properties, new ProxyProperties(), new ClashNodes(new ObjectMapper()));
        var request = ClientRequest.create(HttpMethod.POST, URI.create("https://api.anthropic.com/v1/messages")).build();
        return catchThrowableOfType(EgressException.class, () ->
            bridgeTransport.fingerprintExchange(request, "", "chrome136").block(Duration.ofSeconds(8)));
    }

    private static ChannelRoutes.Resolved listener(int port) { return listener(port, "node-v1"); }

    /** A Mihomo listener; {@code version} stands for the channel, listener port and fingerprint behind it. */
    private static ChannelRoutes.Resolved listener(int port, String version) {
        var proxy = new ObjectMapper().createObjectNode().put("type", "http").put("server", "127.0.0.1").put("port", port);
        return new ChannelRoutes.Resolved(ClashNodes.MIHOMO, proxy, ChannelRoutes.key(proxy, version));
    }

    private static ChannelRoutes.Resolved direct(tools.jackson.databind.node.ObjectNode proxy) {
        return new ChannelRoutes.Resolved(ClashNodes.DIRECT_PROXY, proxy, ChannelRoutes.key(proxy, null));
    }

    private WebClient clientFor(ChannelRoutes routes, ProxyProperties proxyProperties, AtomicBoolean direct) {
        var routed = new AccountEgressTransport(null, routes, new EgressProperties(), proxyProperties, new ClashNodes(new ObjectMapper()));
        return WebClient.builder().filter(routed.filter()).exchangeFunction(request -> {
            direct.set(true); return Mono.error(new AssertionError("Must never use direct transport"));
        }).build();
    }

    private WebClient clientForPort(int port, AtomicBoolean direct) {
        var routes = mock(ChannelRoutes.class);
        when(routes.resolve(CHANNEL)).thenReturn(Mono.just(listener(port)));
        var proxyProperties = new ProxyProperties();
        proxyProperties.setRequestTimeout(Duration.ofSeconds(5));
        return clientFor(routes, proxyProperties, direct);
    }

    private EgressException failureOf(WebClient client) { return failureOf(client, "http://target.invalid/test"); }

    private EgressException failureOf(WebClient client, String url) {
        return catchThrowableOfType(EgressException.class, () -> client.get().uri(url)
            .attribute(AccountEgressTransport.CHANNEL, CHANNEL).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10)));
    }

    private String fetch(String proxy) {
        return transport.exchangeForProxy(proxy).exchange(ClientRequest.create(HttpMethod.GET, URI.create("http://target.invalid/test"))
            .header("Authorization", "Bearer upstream-test").build())
            .flatMap(response -> response.bodyToMono(String.class)).block(Duration.ofSeconds(8));
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
}
