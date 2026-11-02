package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** AGPL-3.0, section 13: the console links to the source of the running version, which an operator may point elsewhere. */
class SourceCodePropertiesTest {

    @Test
    void pointsAtTheUpstreamProjectUnlessTheOperatorSaysOtherwise() throws IOException {
        assertThat(bind(Map.of()).effectiveUrl()).isEqualTo("https://github.com/StaR4y/starry-api");
        assertThat(new SourceCodeProperties().effectiveUrl()).isEqualTo(SourceCodeProperties.DEFAULT_URL);
    }

    @Test
    void readsTheOperatorsAddressFromTheEnvironmentVariable() throws IOException {
        assertThat(bind(Map.of("STARRY_SOURCE_URL", "https://git.example.com/me/starry-api-fork")).effectiveUrl())
            .isEqualTo("https://git.example.com/me/starry-api-fork");
        assertThat(bind(Map.of("STARRY_SOURCE_URL", "  http://git.internal:3000/team/starry-api?ref=prod  ")).effectiveUrl())
            .isEqualTo("http://git.internal:3000/team/starry-api?ref=prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "javascript:alert(1)",
        "data:text/html,<script>alert(1)</script>",
        "ftp://files.example.com/starry-api.tar.gz",
        "file:///srv/starry-api",
        "git.example.com/me/starry-api",
        "//git.example.com/me/starry-api",
        "https:///no-host",
        "https://user:secret@git.example.com/me/starry-api",
        "https://git.example.com/a b",
        "https://git.example.com/\"onmouseover=\"alert(1)",
        "https://git.example.com/<script>",
    })
    void usesTheUpstreamAddressWhenTheSettingCannotBeLinkedTo(String value) throws IOException {
        assertThat(bind(Map.of("STARRY_SOURCE_URL", value)).effectiveUrl()).isEqualTo(SourceCodeProperties.DEFAULT_URL);
    }

    @Test
    void refusesAnAddressThatIsTooLongToBeAnAddress() {
        SourceCodeProperties properties = new SourceCodeProperties();
        properties.setUrl("https://git.example.com/" + "a".repeat(2100));

        assertThat(properties.effectiveUrl()).isEqualTo(SourceCodeProperties.DEFAULT_URL);
    }

    private static SourceCodeProperties bind(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // Replace the real process environment so a developer's own STARRY_* variables cannot leak in.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bindOrCreate("starry.source", SourceCodeProperties.class);
    }
}
