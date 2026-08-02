package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class WebFluxConfigTest {

    @Test
    void appliesTheProxyLimitToRegularRequests() {
        ProxyProperties properties = new ProxyProperties();
        properties.setMaxRequestBodyBytes(10);
        WebFluxConfig config = new WebFluxConfig(properties, 100);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/v1/chat/completions", 11);

        config.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(chain, never()).filter(exchange);
    }

    @Test
    void usesTheDedicatedLimitForDatabaseImports() {
        ProxyProperties properties = new ProxyProperties();
        properties.setMaxRequestBodyBytes(10);
        WebFluxConfig config = new WebFluxConfig(properties, 100);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/admin/data-transfer/import", 101);
        org.mockito.Mockito.when(chain.filter(exchange)).thenReturn(Mono.empty());

        config.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(exchange);
    }

    private MockServerWebExchange exchange(String path, long contentLength) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
            .header("Content-Length", Long.toString(contentLength))
            .build());
    }
}
