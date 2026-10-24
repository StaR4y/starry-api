package tech.starryapi.egress;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ClashNodes {
    private final ObjectMapper mapper;
    private static final Set<String> TYPES = Set.of("http", "socks5", "ss", "ssr", "vmess", "vless",
        "trojan", "hysteria", "hysteria2", "tuic", "snell", "anytls");
    // Do not let subscription nodes read server files, select local interfaces, or chain to DIRECT.
    private static final Set<String> FORBIDDEN = Set.of("dialer-proxy", "interface-name", "routing-mark",
        "certificate", "private-key", "ca", "ca-str", "client-fingerprint-file");

    public ClashNodes(ObjectMapper mapper) { this.mapper = mapper; }

    public JsonNode document(String yaml) {
        if (yaml == null || yaml.length() > 4 * 1024 * 1024) throw invalid("Subscription is empty or too large");
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(20);
            options.setCodePointLimit(4 * 1024 * 1024);
            Object loaded = new Yaml(new SafeConstructor(options)).load(yaml);
            JsonNode result = mapper.valueToTree(loaded);
            if (result == null || !result.isObject()) throw invalid("Expected a Clash YAML object");
            return result;
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw invalid("Invalid Clash YAML"); }
    }

    public Node node(JsonNode raw) {
        if (!raw.isObject()) throw invalid("Proxy node must be an object");
        ObjectNode config = (ObjectNode) raw.deepCopy();
        String name = config.path("name").asString("").trim();
        if (name.isEmpty() || name.length() > 200) throw invalid("Node name must contain 1 to 200 characters");
        String protocol = config.path("type").asString("").toLowerCase(Locale.ROOT);
        if (!protocol.matches("[a-z0-9-]{1,32}")) throw invalid("Invalid proxy protocol");
        String issue = null;
        if (!TYPES.contains(protocol)) issue = "Unsupported node protocol: " + protocol.replaceAll("[^a-z0-9-]", "");
        if (config.path("server").asString("").isBlank() || config.path("server").asString("").length() > 253
            || config.path("port").asInt(0) < 1 || config.path("port").asInt(0) > 65535) issue = "Invalid node server or port";
        if (containsForbidden(config)) issue = "Node contains unsupported chaining, file, or interface options";
        config.remove("name");
        config.put("type", protocol);
        String fingerprint = Hashing.sha256(canonical(config).toString());
        return new Node(name, protocol, config, fingerprint, issue);
    }

    public Node url(String name, String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https", "socks5", "socks5h").contains(scheme) || uri.getHost() == null
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw invalid("Use an HTTP, HTTPS or SOCKS5 proxy URL with an explicit port");
            }
            // socks5 and socks5h both leave the name resolution of the target to the proxy; https is TLS to the proxy itself.
            ObjectNode node = mapper.createObjectNode().put("name", name).put("type", scheme.startsWith("socks") ? "socks5" : "http")
                .put("server", uri.getHost()).put("port", uri.getPort());
            if (scheme.equals("https")) node.put("tls", true);
            if (uri.getRawUserInfo() != null) {
                String[] auth = uri.getRawUserInfo().split(":", 2);
                node.put("username", decode(auth[0]));
                node.put("password", auth.length == 2 ? decode(auth[1]) : "");
            }
            return node(node);
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw invalid("Invalid proxy URL"); }
    }

    private static String decode(String value) { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8); }
    private boolean containsForbidden(JsonNode value) {
        if (value.isObject()) {
            for (var field : value.properties()) { if (FORBIDDEN.contains(field.getKey()) || containsForbidden(field.getValue())) return true; }
        } else if (value.isArray()) { for (JsonNode item : value) if (containsForbidden(item)) return true; }
        return false;
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            var names = new TreeSet<String>(value.propertyNames());
            names.forEach(name -> sorted.set(name, canonical(value.get(name)))); return sorted;
        }
        if (value.isArray()) { var list = mapper.createArrayNode(); value.forEach(item -> list.add(canonical(item))); return list; }
        return value;
    }
    static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "PROXY_CONFIG_INVALID", message); }
    // What a plain HTTP CONNECT, HTTPS CONNECT (tls: true) or SOCKS5 tunnel opened by this application can honour. A
    // node with more (certificate options, custom headers, ...) needs Mihomo, since reaching it directly would
    // silently drop those options.
    private static final Set<String> PLAIN_PROXY_FIELDS = Set.of("type", "server", "port", "username", "password", "udp");
    public static final String DIRECT_PROXY = "DIRECT_PROXY";
    public static final String MIHOMO = "MIHOMO";

    public record Node(String name, String protocol, ObjectNode config, String fingerprint, String issue) {
        /** DIRECT_PROXY when this application connects to the node itself, MIHOMO when it needs a Mihomo listener. */
        public String route() {
            Set<String> names = fieldNames(config);
            // tls: false is the default; tls: true (an https:// proxy) is something this application can do for an HTTP proxy.
            if (!config.path("tls").asBoolean(false) || protocol.equals("http")) names.remove("tls");
            boolean plain = (protocol.equals("http") || protocol.equals("socks5")) && PLAIN_PROXY_FIELDS.containsAll(names);
            return plain ? DIRECT_PROXY : MIHOMO;
        }
        private static Set<String> fieldNames(ObjectNode config) {
            Set<String> names = new HashSet<>();
            names.addAll(config.propertyNames());
            return names;
        }
    }
}
