package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** V27 on a database that already holds V25/V26 data: ports are kept where a listener is needed and released elsewhere. */
class EgressMigrationUpgradePostgreSqlTest extends PostgreSqlIntegrationTestSupport {

    @Test void upgradingKeepsTheListenerPortsOfMihomoNodesAndReleasesTheOthers() throws Exception {
        // Each -c is its own transaction, which CREATE DATABASE needs.
        exec("psql", "-U", "starry_api_bootstrap", "-d", "starry_api", "-v", "ON_ERROR_STOP=1",
            "-c", "CREATE DATABASE upgrade_check OWNER starry_api_owner",
            "-c", "REVOKE ALL ON DATABASE upgrade_check FROM PUBLIC",
            "-c", "GRANT CONNECT ON DATABASE upgrade_check TO starry_api_migration, starry_api_runtime");
        exec("psql", "-U", "starry_api_bootstrap", "-d", "upgrade_check", "-v", "ON_ERROR_STOP=1", "-c", """
            CREATE SCHEMA starry_api AUTHORIZATION starry_api_owner;
            REVOKE ALL ON SCHEMA starry_api FROM PUBLIC;
            GRANT USAGE ON SCHEMA starry_api TO starry_api_runtime;
            REVOKE ALL ON SCHEMA public FROM PUBLIC;
            """);
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/upgrade_check");

        migrate(url, "26");
        try (Connection connection = owner(url)) {
            // A subscription whose nodes were imported by V25/V26: ports come from the identity, in insertion order.
            connection.createStatement().execute("""
                INSERT INTO starry_api.proxy_subscriptions(subscription_id,name,subscription_url)
                VALUES('00000000-0000-0000-0000-0000000000aa','legacy','https://sub.example/legacy')
                """);
            insertNode(connection, "vless", "vless", "{\"type\":\"vless\",\"server\":\"v.example\",\"port\":443}", true, null);
            insertNode(connection, "plain-http", "http", "{\"type\":\"http\",\"server\":\"h.example\",\"port\":3128,\"username\":\"u\",\"password\":\"p\"}", true, null);
            insertNode(connection, "http-headers", "http", "{\"type\":\"http\",\"server\":\"h2.example\",\"port\":3128,\"headers\":{\"X-A\":\"b\"}}", true, null);
            insertNode(connection, "https-proxy", "http", "{\"type\":\"http\",\"server\":\"h3.example\",\"port\":8443,\"tls\":true}", true, null);
            insertNode(connection, "plain-socks", "socks5", "{\"type\":\"socks5\",\"server\":\"s.example\",\"port\":1080,\"udp\":true}", true, null);
            insertNode(connection, "socks-tls", "socks5", "{\"type\":\"socks5\",\"server\":\"s2.example\",\"port\":1080,\"tls\":true}", true, null);
            insertNode(connection, "gone", "vless", "{\"type\":\"vless\",\"server\":\"g.example\",\"port\":443}", false, null);
            insertNode(connection, "odd", "wireguard", "{\"type\":\"wireguard\",\"server\":\"w.example\",\"port\":51820}", true, "Unsupported node protocol: wireguard");
            // Every row has a port today, including the ones no listener will ever serve.
            assertThat(portsByName(connection)).hasSize(8).doesNotContainValue(null);
        }

        migrate(url, null);

        try (Connection connection = owner(url)) {
            Map<String, String> routes = new LinkedHashMap<>();
            try (var rows = connection.createStatement().executeQuery("SELECT name,route FROM starry_api.proxy_channels ORDER BY name")) {
                while (rows.next()) routes.put(rows.getString(1), rows.getString(2));
            }
            assertThat(routes).containsEntry("plain-http", "DIRECT_PROXY").containsEntry("plain-socks", "DIRECT_PROXY")
                .containsEntry("https-proxy", "DIRECT_PROXY")
                .containsEntry("vless", "MIHOMO").containsEntry("http-headers", "MIHOMO").containsEntry("socks-tls", "MIHOMO")
                .containsEntry("gone", "MIHOMO").containsEntry("odd", "MIHOMO");
            var ports = portsByName(connection);
            // Only nodes that are present, without issue and in need of Mihomo keep a port - the one they already had.
            assertThat(ports.get("vless")).isEqualTo(20000);
            assertThat(ports.get("http-headers")).isEqualTo(20002);
            assertThat(ports.get("socks-tls")).isEqualTo(20005);
            assertThat(ports.get("plain-http")).isNull();
            assertThat(ports.get("plain-socks")).isNull();
            assertThat(ports.get("https-proxy")).isNull();
            assertThat(ports.get("gone")).isNull();
            assertThat(ports.get("odd")).isNull();
            // The identity is gone and the freed ports are handed out again, lowest first.
            connection.createStatement().execute("""
                INSERT INTO starry_api.proxy_channels(channel_id,name,proxy_name,protocol,node_config,config_fingerprint)
                VALUES(gen_random_uuid(),'after','node-after','vless','{"type":"vless"}'::jsonb,repeat('1',64))
                """);
            assertThat(portsByName(connection).get("after")).isEqualTo(20001);
            try (var sequences = connection.createStatement().executeQuery(
                "SELECT count(*) FROM pg_class WHERE relname = 'proxy_channels_listener_port_seq'")) {
                sequences.next();
                assertThat(sequences.getInt(1)).isZero();
            }
            // The runtime role may not call the allocation function directly; the trigger runs it.
            try (var privilege = connection.createStatement().executeQuery(
                "SELECT has_function_privilege('starry_api_runtime','starry_api.assign_proxy_channel_listener_port()','EXECUTE')")) {
                privilege.next();
                assertThat(privilege.getBoolean(1)).isFalse();
            }
        }
    }

    private void insertNode(Connection connection, String name, String protocol, String config, boolean present, String issue) throws Exception {
        try (var statement = connection.prepareStatement("""
            INSERT INTO starry_api.proxy_channels(channel_id,subscription_id,name,proxy_name,protocol,node_config,config_fingerprint,source_present,config_issue)
            VALUES(gen_random_uuid(),'00000000-0000-0000-0000-0000000000aa',?,?,?,?::jsonb,md5(?)||md5(?),?,?)
            """)) {
            statement.setString(1, name);
            statement.setString(2, "node-" + name);
            statement.setString(3, protocol);
            statement.setString(4, config);
            statement.setString(5, name);
            statement.setString(6, name);
            statement.setBoolean(7, present);
            statement.setString(8, issue);
            statement.executeUpdate();
        }
    }

    private Map<String, Integer> portsByName(Connection connection) throws Exception {
        Map<String, Integer> ports = new LinkedHashMap<>();
        try (var rows = connection.createStatement().executeQuery("SELECT name,listener_port FROM starry_api.proxy_channels ORDER BY name")) {
            while (rows.next()) {
                int port = rows.getInt(2);
                boolean missing = rows.wasNull();
                ports.put(rows.getString(1), missing ? null : port);
            }
        }
        return ports;
    }

    private void migrate(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, MIGRATION_USERNAME, MIGRATION_PASSWORD)
            .locations("classpath:db/migration").schemas("starry_api").defaultSchema("starry_api").createSchemas(false)
            .initSql("SET ROLE starry_api_owner");
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private Connection owner(String url) throws Exception {
        Connection connection = DriverManager.getConnection(url, MIGRATION_USERNAME, MIGRATION_PASSWORD);
        connection.createStatement().execute("SET ROLE starry_api_owner");
        return connection;
    }

    private static void exec(String... command) throws Exception {
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
    }
}
