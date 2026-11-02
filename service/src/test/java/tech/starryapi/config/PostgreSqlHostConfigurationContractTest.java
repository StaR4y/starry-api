package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PostgreSqlHostConfigurationContractTest {

    private static final Pattern POSTGRES_HOST_PLACEHOLDER =
        Pattern.compile("\\$\\{POSTGRES_HOST:([^}]+)}");
    private static final Pattern START_SCRIPT_POSTGRES_HOST = Pattern.compile(
        "(?m)^export POSTGRES_HOST=\"\\$\\{POSTGRES_HOST:-([^}]+)}\"$"
    );
    private static final Pattern START_SCRIPT_SERVER_ADDRESS = Pattern.compile(
        "(?m)^export SERVER_ADDRESS=\"\\$\\{SERVER_ADDRESS:-([^}]+)}\"$"
    );

    @Test
    void usesLocalhostForNonContainerDatabaseConnections() throws IOException {
        Map<String, Object> application = loadApplicationConfiguration();
        Map<String, Object> spring = mapAt(application, "spring");

        String r2dbcUrl = stringAt(mapAt(spring, "r2dbc"), "url");
        String flywayUrl = stringAt(mapAt(spring, "flyway"), "url");
        String startScript = Files.readString(repositoryRoot().resolve("scripts/start-proxy.sh"));

        assertThat(postgresHostDefault(r2dbcUrl)).isEqualTo("localhost");
        assertThat(postgresHostDefault(flywayUrl)).isEqualTo("localhost");
        assertThat(startScriptPostgresHostDefault(startScript)).isEqualTo("localhost");
    }

    @Test
    void bindsDirectRunsToLoopbackAndContainersToAllInterfaces() throws IOException {
        Map<String, Object> application = loadApplicationConfiguration();
        String serverAddress = stringAt(mapAt(application, "server"), "address");
        String startScript = Files.readString(repositoryRoot().resolve("scripts/start-proxy.sh"));
        String dockerfile = Files.readString(repositoryRoot().resolve("Dockerfile"));

        assertThat(serverAddress).isEqualTo("${SERVER_ADDRESS:127.0.0.1}");
        assertThat(startScriptServerAddressDefault(startScript)).isEqualTo("127.0.0.1");
        assertThat(stringAt(composeServiceEnvironment(), "SERVER_ADDRESS")).isEqualTo("0.0.0.0");
        assertThat(dockerfile).contains("ENV SERVER_ADDRESS=0.0.0.0");
    }

    @Test
    void keepsTheComposeServiceOnThePostgresNetworkAlias() throws IOException {
        assertThat(stringAt(composeServiceEnvironment(), "POSTGRES_HOST")).isEqualTo("postgres");
    }

    @Test
    void persistsDataManagementSecurityStateInTheDeployment() throws IOException {
        String compose = Files.readString(repositoryRoot().resolve("docker-compose.yml"));
        String installer = Files.readString(repositoryRoot().resolve("scripts/install.sh"));

        assertThat(compose)
            .contains("- ./data/security:/app/security")
            .contains("STARRY_DATA_MANAGEMENT_SECURITY_FILE: /app/security/data-management.json")
            .contains("STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED:")
            .contains("STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE: /app/security/recovery.key");
        assertThat(installer)
            .contains("\"$INSTALL_DIR/data/security\"")
            .contains("root_cmd chmod 0700 \"$INSTALL_DIR/data/security\"");
    }

    @Test
    void packagesTheRecoveryToolInTheImage() throws IOException {
        assertThat(Files.readString(repositoryRoot().resolve("Dockerfile")))
            .contains("apt-get install -y --no-install-recommends ca-certificates curl jq")
            .contains("COPY --chmod=0555 scripts/recover-data-management.sh /app/bin/recover-data-management");
    }

    private Map<String, Object> loadApplicationConfiguration() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).as("application.yml must be available on the test classpath").isNotNull();
            return new Yaml().load(input);
        }
    }

    private String postgresHostDefault(String databaseUrl) {
        Matcher matcher = POSTGRES_HOST_PLACEHOLDER.matcher(databaseUrl);
        assertThat(matcher.find())
            .as("database URL must declare a POSTGRES_HOST default: %s", databaseUrl)
            .isTrue();
        return matcher.group(1);
    }

    private String startScriptPostgresHostDefault(String startScript) {
        Matcher matcher = START_SCRIPT_POSTGRES_HOST.matcher(startScript);
        assertThat(matcher.find())
            .as("start-proxy.sh must export POSTGRES_HOST with a default")
            .isTrue();
        return matcher.group(1);
    }

    private String startScriptServerAddressDefault(String startScript) {
        Matcher matcher = START_SCRIPT_SERVER_ADDRESS.matcher(startScript);
        assertThat(matcher.find())
            .as("start-proxy.sh must export SERVER_ADDRESS with a default")
            .isTrue();
        return matcher.group(1);
    }

    private Map<String, Object> composeServiceEnvironment() throws IOException {
        Path composeFile = repositoryRoot().resolve("docker-compose.yml");
        Map<String, Object> compose;
        try (InputStream input = Files.newInputStream(composeFile)) {
            compose = new Yaml().load(input);
        }
        return mapAt(mapAt(mapAt(compose, "services"), "service"), "environment");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAt(Map<String, Object> source, String key) {
        assertThat(source).containsKey(key);
        return (Map<String, Object>) source.get(key);
    }

    private String stringAt(Map<String, Object> source, String key) {
        assertThat(source).containsKey(key);
        return source.get(key).toString();
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                && Files.isDirectory(candidate.resolve("service"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from " + Path.of("").toAbsolutePath());
    }
}
