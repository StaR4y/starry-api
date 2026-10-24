package tech.starryapi.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.config.ProductionWebClients;

class NewsServiceCacheTest {

    @Test
    void browsingManyDaysDoesNotKeepEveryReportInMemory() {
        AtomicInteger upstreamCalls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            upstreamCalls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK, ProductionWebClients.strategies())
                .header("Content-Type", "application/json")
                .body("{\"date\":\"2026-01-01\",\"sections\":[]}")
                .build());
        });
        NewsService service = new NewsService(builder);

        for (int day = 1; day <= 28; day++) {
            for (int month = 1; month <= 12; month++) {
                service.daily(String.format("2026-%02d-%02d", month, day)).block();
            }
        }

        assertThat(upstreamCalls.get()).isEqualTo(28 * 12);
        assertThat(service.cachedPages()).isLessThanOrEqualTo(64);

        // A page that is still cached is served without another upstream call.
        service.daily("2026-12-28").block();
        assertThat(upstreamCalls.get()).isEqualTo(28 * 12);
    }
}
