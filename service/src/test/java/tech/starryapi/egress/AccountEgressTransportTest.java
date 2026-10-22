package tech.starryapi.egress;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import tech.starryapi.config.ProxyProperties;

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
