package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
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
        assertThat(properties.getResponsesDefaultOutputTokens()).isEqualTo(16_000L);
        assertThat(properties.getKiro().getPreferredEndpoint()).isEqualTo("codewhisperer");
    }

    /** The output limit of a Responses request that names none is the operator's to set (D14), 16,000 unless they do. */
    @Test
    void readsTheDefaultOutputLimitOfAResponsesRequest() throws IOException {
        assertThat(bind(Map.of("STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS", "32000")).getResponsesDefaultOutputTokens()).isEqualTo(32_000L);
        assertThat(bind(Map.of("STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS", "4096")).getResponsesDefaultOutputTokens()).isEqualTo(4_096L);
        assertThat(bind(Map.of("STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS", "1000000")).getResponsesDefaultOutputTokens()).isEqualTo(1_000_000L);
    }

    /** A value that is no limit a client could ask for, or below the 4,096 the gateway gave before the default was raised, stops the service at startup. */
    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "1024", "4095", "1000001", "64k", ""})
    void refusesADefaultOutputLimitOutsideTheRange(String value) {
        assertThatThrownBy(() -> bind(Map.of("STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS", value)))
            .isInstanceOf(BindException.class)
            .hasStackTraceContaining("responses-default-output-tokens");
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
