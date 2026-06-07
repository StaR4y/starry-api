package xyz.star4y.api.config;

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
            .maxIdleTime(Duration.ofSeconds(45))
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
