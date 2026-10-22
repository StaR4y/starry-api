package tech.starryapi.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class HttpClientConfig {

    /**
     * The one outbound HTTP client: model upstreams of every platform, the payment provider, Turnstile and the
     * ranking and pricing feeds all share its connection pool.
     */
    @Bean
    WebClient upstreamWebClient(ProxyProperties properties, ObjectProvider<CodecCustomizer> codecCustomizers) {
        ConnectionProvider provider = ConnectionProvider.builder("starry-upstream")
            .maxConnections(500)
            .pendingAcquireMaxCount(2_000)
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            // Upstreams and their CDNs close idle keep-alive connections after as little as 5-30 s. Reusing one that
            // is already closed fails the request with "connection reset", so retire idle connections early and
            // check for dead ones in the background rather than only when a request picks one up.
            .maxIdleTime(Duration.ofSeconds(20))
            .evictInBackground(Duration.ofSeconds(15))
            .maxLifeTime(Duration.ofMinutes(10))
            .build();

        HttpClient httpClient = HttpClient.create(provider)
            .compress(true)
            .responseTimeout(properties.getRequestTimeout())
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);

        // A WebClient built by hand does not get Boot's codec setup. Apply the same customizers Boot applies to its own
        // WebClient.Builder, otherwise the JSON codec is Spring Framework 7's Jackson 3 default and cannot read or
        // write the Jackson 2 JsonNode/ObjectNode types this code passes around.
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> {
                codecCustomizers.orderedStream().forEach(customizer -> customizer.customize(configurer));
                configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
            })
            .build();

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(strategies)
            .build();
    }
}
