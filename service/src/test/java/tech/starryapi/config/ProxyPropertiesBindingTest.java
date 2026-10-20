package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The gateway settings moved from KIRO_* to STARRY_* names; existing .env files must keep working. */
class ProxyPropertiesBindingTest {

    @Test
    void readsTheStarryNames() throws IOException {
        ProxyProperties properties = bind(Map.of(
            "STARRY_WEBUI_URL", "https://new.example",
            "STARRY_REQUEST_TIMEOUT", "45s",
            "STARRY_KIRO_PREFERRED_ENDPOINT", "amazonq"
        ));

        assertThat(properties.getWebuiUrl()).isEqualTo("https://new.example");
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.getKiro().getPreferredEndpoint()).isEqualTo("amazonq");
        assertThat(properties.getKiro().getEndpoints()).extracting(ProxyProperties.Endpoint::getName)
            .containsExactly("codewhisperer", "amazonq", "amazonq-cli");
    }

    @Test
    void stillReadsTheLegacyKiroNames() throws IOException {
        ProxyProperties properties = bind(Map.of(
            "KIRO_WEBUI_URL", "https://legacy.example",
            "KIRO_ADMIN_SESSION_TTL", "2h",
            "KIRO_PREFERRED_ENDPOINT", "amazonq-cli"
        ));

        assertThat(properties.getWebuiUrl()).isEqualTo("https://legacy.example");
        assertThat(properties.getAdminSessionTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getKiro().getPreferredEndpoint()).isEqualTo("amazonq-cli");
    }

    @Test
    void prefersTheStarryNameWhenBothAreSet() throws IOException {
        ProxyProperties properties = bind(Map.of("STARRY_MAX_RETRIES", "5", "KIRO_MAX_RETRIES", "1"));

        assertThat(properties.getMaxRetries()).isEqualTo(5);
    }

    @Test
    void fallsBackToTheDefaults() throws IOException {
        ProxyProperties properties = bind(Map.of());

        assertThat(properties.getWebuiUrl()).isEmpty();
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(properties.getKiro().getPreferredEndpoint()).isEqualTo("codewhisperer");
    }

    private static ProxyProperties bind(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // Replace the real process environment so a developer's own STARRY_* / KIRO_* variables cannot leak in.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bindOrCreate("starry.proxy", ProxyProperties.class);
    }
}
