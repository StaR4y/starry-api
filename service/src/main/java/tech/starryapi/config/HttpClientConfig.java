package tech.starryapi.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class HttpClientConfig {

    @Bean
    WebClient kiroWebClient(ProxyProperties properties) {
        ConnectionProvider provider = ConnectionProvider.builder("kiro-upstream")
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

        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(strategies)
            .build();
    }
}
