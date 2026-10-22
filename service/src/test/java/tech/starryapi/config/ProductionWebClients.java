package tech.starryapi.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient builders for unit tests that wire the codecs the way production does. A bare {@code WebClient.builder()}
 * uses Spring Framework 7's Jackson 3 JSON codec, which cannot handle the Jackson 2 {@code JsonNode} types the
 * application sends and receives. The customizers come from Boot's own auto-configuration driven by the real
 * application.yml, so removing {@code spring.http.codecs.preferred-json-mapper} there fails the tests that use this.
 */
@SuppressWarnings("removal") // Jackson2AutoConfiguration is the Boot 4 bridge for the application's Jackson 2 ObjectMapper
public final class ProductionWebClients {

    private static final List<CodecCustomizer> CODEC_CUSTOMIZERS = codecCustomizers();

    private ProductionWebClients() {
    }

    public static WebClient.Builder builder() {
        return WebClient.builder().exchangeStrategies(strategies());
    }

    /** For {@code ClientResponse.create(status, strategies)}: canned responses decode bodies with the same codecs. */
    public static ExchangeStrategies strategies() {
        return ExchangeStrategies.builder()
            .codecs(configurer -> CODEC_CUSTOMIZERS.forEach(customizer -> customizer.customize(configurer)))
            .build();
    }

    private static List<CodecCustomizer> codecCustomizers() {
        AtomicReference<List<CodecCustomizer>> customizers = new AtomicReference<>();
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class, CodecsAutoConfiguration.class))
            .run(context -> customizers.set(context.getBeanProvider(CodecCustomizer.class).orderedStream().toList()));
        return customizers.get();
    }
}
