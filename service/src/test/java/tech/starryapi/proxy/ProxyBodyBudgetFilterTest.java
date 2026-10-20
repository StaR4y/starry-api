package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class ProxyBodyBudgetFilterTest {

    private MockServerWebExchange post(String path, long contentLength) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
            .header("Content-Length", String.valueOf(contentLength)).body("x"));
    }

    @Test
    void rejectsRequestsOverTheBudgetWhileOthersAreInFlightAndReleasesOnCompletion() {
        ProxyBodyBudgetFilter filter = new ProxyBodyBudgetFilter(10_000, 1L << 30);
        Sinks.Empty<Void> hold = Sinks.empty();
        AtomicInteger served = new AtomicInteger();
        WebFilterChain slow = exchange -> hold.asMono().doOnSubscribe(s -> served.incrementAndGet());
        WebFilterChain fast = exchange -> Mono.fromRunnable(served::incrementAndGet);

        // 6,000 bytes in flight (still running).
        MockServerWebExchange first = post("/v1/chat/completions", 6_000);
        filter.filter(first, slow).subscribe();
        assertThat(filter.inFlightBytes()).isEqualTo(6_000);

        // 5,000 more would exceed 10,000 -> immediate 503 with Retry-After, chain never runs.
        MockServerWebExchange second = post("/v1/responses", 5_000);
        filter.filter(second, fast).block();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(second.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(served.get()).isEqualTo(1);
        assertThat(filter.inFlightBytes()).isEqualTo(6_000);

        // A request that fits is accepted, and completing the first one frees its bytes.
        MockServerWebExchange third = post("/v1/messages", 3_000);
        filter.filter(third, fast).block();
        assertThat(third.getResponse().getStatusCode()).isNull();
        hold.tryEmitEmpty();
        assertThat(filter.inFlightBytes()).isZero();
    }

    @Test
    void aLoneRequestLargerThanTheWholeBudgetIsStillServed() {
        ProxyBodyBudgetFilter filter = new ProxyBodyBudgetFilter(1_000, 1L << 30);
        MockServerWebExchange exchange = post("/v1/chat/completions", 50_000);
        filter.filter(exchange, e -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(filter.inFlightBytes()).isZero();
    }

    @Test
    void onlyProxyPostEndpointsAreCounted() {
        ProxyBodyBudgetFilter filter = new ProxyBodyBudgetFilter(100, 1L << 30);
        filter.filter(post("/v1/chat/completions", 90), e -> Mono.never()).subscribe();
        MockServerWebExchange admin = post("/admin/users", 1_000_000);
        filter.filter(admin, e -> Mono.empty()).block();
        assertThat(admin.getResponse().getStatusCode()).isNull();
        assertThat(ProxyBodyBudgetFilter.isProxyPath("/v1beta/models/gemini-2.5-pro:generateContent")).isTrue();
        assertThat(ProxyBodyBudgetFilter.isProxyPath("/v1/models")).isFalse();
    }

    @Test
    void autoBudgetIsAQuarterOfTheHeapWithAFloor() {
        ProxyBodyBudgetFilter big = new ProxyBodyBudgetFilter(0, 800L * 1024 * 1024);
        ProxyBodyBudgetFilter tiny = new ProxyBodyBudgetFilter(0, 64L * 1024 * 1024);
        // 200 MiB and the 32 MiB floor: a 150 MiB request fits next to a 40 MiB one for the big heap only.
        big.filter(post("/v1/responses", 150L * 1024 * 1024), e -> Mono.never()).subscribe();
        MockServerWebExchange fits = post("/v1/responses", 40L * 1024 * 1024);
        big.filter(fits, e -> Mono.empty()).block();
        assertThat(fits.getResponse().getStatusCode()).isNull();

        tiny.filter(post("/v1/responses", 20L * 1024 * 1024), e -> Mono.never()).subscribe();
        MockServerWebExchange rejected = post("/v1/responses", 20L * 1024 * 1024);
        tiny.filter(rejected, e -> Mono.empty()).block();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
