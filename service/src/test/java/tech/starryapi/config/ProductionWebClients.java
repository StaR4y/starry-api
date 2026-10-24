package tech.starryapi.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient builders for unit tests that wire the codecs the way production does. A bare {@code WebClient.builder()}
 * builds its own Jackson codec instead of the application's JsonMapper, so request bodies would not be written with
 * {@link JacksonConfig}'s settings. The customizers come from Boot's own auto-configuration driven by the real
 * application.yml, exactly as {@code HttpClientConfig} applies them to {@code upstreamWebClient}.
 */
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
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, CodecsAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class)
            .run(context -> customizers.set(context.getBeanProvider(CodecCustomizer.class).orderedStream().toList()));
        return customizers.get();
    }
}
