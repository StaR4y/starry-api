package tech.starryapi.egress;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import org.junit.jupiter.api.Test;

class ClashNodesTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ClashNodes parser = new ClashNodes(json);

    @Test void canonicalIdentityIgnoresNodeNameAndFieldOrder() {
        var a = parser.node(parser.document("name: A\ntype: http\nserver: example.com\nport: 8080\n"));
        var b = parser.node(parser.document("port: 8080\nserver: example.com\nname: B\ntype: http\n"));
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(a.config().has("name")).isFalse();
    }

    @Test void rejectsFileReadsChainingAndUnsafeYaml() {
        for (String option : new String[]{"dialer-proxy: DIRECT", "ca: /etc/passwd", "interface-name: eth0"}) {
            assertThat(parser.node(parser.document("name: A\ntype: http\nserver: example.com\nport: 80\n" + option)).issue()).isNotNull();
        }
        assertThatThrownBy(() -> parser.document("!!java.net.URL [https://example.com]")).hasMessageContaining("Invalid Clash YAML");
        assertThatThrownBy(() -> parser.document("proxies: []\nproxies: []")).hasMessageContaining("Invalid Clash YAML");
        assertThatThrownBy(() -> parser.url("A", "ftp://example.com:80")).hasMessageContaining("proxy URL");
        assertThatThrownBy(() -> parser.url("A", "https://example.com")).hasMessageContaining("explicit port");
        assertThat(parser.url("A", "socks5h://user%2Bname:pass%3Aword@127.0.0.1:9000").config().path("password").asText()).isEqualTo("pass:word");
    }

    @Test void onlyPlainHttpHttpsAndSocks5NodesAreReachedDirectly() {
        assertThat(parser.url("A", "http://user:pass@example.com:8080").route()).isEqualTo(ClashNodes.DIRECT_PROXY);
        assertThat(parser.url("A", "socks5://example.com:1080").route()).isEqualTo(ClashNodes.DIRECT_PROXY);
        assertThat(parser.url("A", "socks5h://example.com:1080").route()).isEqualTo(ClashNodes.DIRECT_PROXY);
        assertThat(parser.node(parser.document("name: A\ntype: socks5\nserver: example.com\nport: 1080\nusername: u\npassword: p\nudp: true\n")).route())
            .isEqualTo(ClashNodes.DIRECT_PROXY);
        // An https:// proxy is an HTTP proxy with TLS to the proxy itself.
        var https = parser.url("A", "https://user:pass@example.com:8443");
        assertThat(https.protocol()).isEqualTo("http");
        assertThat(https.config().path("tls").asBoolean()).isTrue();
        assertThat(https.route()).isEqualTo(ClashNodes.DIRECT_PROXY);
        assertThat(parser.url("A", "socks5h://example.com:1080").protocol()).isEqualTo("socks5");
        assertThat(parser.node(parser.document("name: A\ntype: http\nserver: example.com\nport: 8080\ntls: false\n")).route())
            .isEqualTo(ClashNodes.DIRECT_PROXY);
        // Options a CONNECT or SOCKS5 tunnel opened by this application cannot honour need Mihomo.
        for (String option : new String[] {"headers: {X-Node: a}", "skip-cert-verify: true", "sni: node.example", "ip-version: ipv4", "fingerprint: abc"}) {
            assertThat(parser.node(parser.document("name: A\ntype: http\nserver: example.com\nport: 8080\ntls: true\n" + option)).route())
                .as(option).isEqualTo(ClashNodes.MIHOMO);
        }
        assertThat(parser.node(parser.document("name: A\ntype: socks5\nserver: example.com\nport: 1080\ntls: true\n")).route())
            .as("socks5 over tls").isEqualTo(ClashNodes.MIHOMO);
        for (String type : new String[] {"vless", "vmess", "trojan", "ss", "hysteria2", "tuic"}) {
            assertThat(parser.node(parser.document("name: A\ntype: " + type + "\nserver: example.com\nport: 443\n")).route())
                .as(type).isEqualTo(ClashNodes.MIHOMO);
        }
    }

    @Test void subscriptionFetchRejectsPrivateTargetsAndPinsValidatedIp() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1"})
            assertThat(SubscriptionFetcher.publicAddress(InetAddress.getByName(ip))).isFalse();
        assertThatThrownBy(() -> SubscriptionFetcher.validated("http://127.0.0.1/config")).hasMessageContaining("public addresses");
        var target = new SubscriptionFetcher.Target(URI.create("https://unresolvable.invalid/config"), InetAddress.getByName("1.1.1.1"), 443);
        try (var group = SubscriptionFetcher.pinnedResolver(target)) {
            var resolved = group.getResolver(io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE)
                .resolve(InetSocketAddress.createUnresolved("unresolvable.invalid", 443)).get();
            assertThat(resolved.getAddress().getHostAddress()).isEqualTo("1.1.1.1");
            assertThat(resolved.getHostString()).isEqualTo("unresolvable.invalid");
        }
    }

    @Test void runtimeUsesFixedNodeListenersAndRejectDefault() {
        var props = new EgressProperties();
        var runtime = new MihomoRuntime(null, json, props);
        var id = java.util.UUID.randomUUID();
        var node = parser.url("A", "http://example.com:8080");
        var config = runtime.configuration(java.util.List.of(new MihomoRuntime.Route(id, 20000, node.config())));
        assertThat(config.path("rules").get(0).asText()).isEqualTo("MATCH,REJECT");
        assertThat(config.path("listeners").get(0).path("proxy").asText()).isEqualTo("node-" + id);
        assertThat(config.path("listeners").get(0).path("listen").asText()).isEqualTo("127.0.0.1");
        assertThat(config.path("proxies").get(0).path("name").asText()).isEqualTo("node-" + id);
    }
}
