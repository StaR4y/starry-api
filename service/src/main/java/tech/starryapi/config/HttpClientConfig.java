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
     * Unbound service requests share this pool. Account attributes select their own isolated egress transport.
     */
    @Bean
    WebClient upstreamWebClient(ProxyProperties properties, tech.starryapi.egress.AccountEgressTransport egress,
                                ObjectProvider<CodecCustomizer> codecCustomizers) {
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

        return WebClient.builder()
            .filter(egress.filter())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(upstreamStrategies(codecCustomizers))
            .build();
    }

    /**
     * Codecs for upstream calls, shared by this client and the account egress transport (which writes and reads the
     * same requests over its own connections). A WebClient built by hand does not get Boot's codec setup, so the
     * customizers Boot applies to its own WebClient.Builder are applied here: the JSON codec then uses the
     * application's JsonMapper (JacksonConfig and spring.jackson.*) like the WebFlux server does, instead of a default
     * Jackson mapper Spring Framework would create on its own.
     */
    public static ExchangeStrategies upstreamStrategies(ObjectProvider<CodecCustomizer> codecCustomizers) {
        return ExchangeStrategies.builder()
            .codecs(configurer -> {
                codecCustomizers.orderedStream().forEach(customizer -> customizer.customize(configurer));
                configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
            })
            .build();
    }
}
