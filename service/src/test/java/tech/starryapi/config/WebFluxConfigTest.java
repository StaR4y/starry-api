package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class WebFluxConfigTest {

    @Test
    void allowsPutRequestsForAdminModelUpdates() {
        ProxyProperties properties = new ProxyProperties();
        WebFluxConfig config = new WebFluxConfig(properties, 100, "build/test-model-icons", 20);
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        config.addCorsMappings(registry);

        assertThat(registry.allowedMethods("/**")).contains("PUT");
    }

    @Test
    void appliesTheProxyLimitToRegularRequests() {
        ProxyProperties properties = new ProxyProperties();
        properties.setMaxRequestBodyBytes(10);
        WebFluxConfig config = new WebFluxConfig(properties, 100, "build/test-model-icons", 20);
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
        WebFluxConfig config = new WebFluxConfig(properties, 100, "build/test-model-icons", 20);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/admin/data-transfer/import", 101);
        org.mockito.Mockito.when(chain.filter(exchange)).thenReturn(Mono.empty());

        config.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(exchange);
    }

    @Test
    void usesTheDedicatedLimitForModelIconUploads() {
        ProxyProperties properties = new ProxyProperties();
        properties.setMaxRequestBodyBytes(10);
        WebFluxConfig config = new WebFluxConfig(properties, 100, "build/test-model-icons", 20);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/admin/models/icon", 21);
        org.mockito.Mockito.when(chain.filter(exchange)).thenReturn(Mono.empty());

        config.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(exchange);
    }

    @Test
    void usesTheDedicatedLimitForAppearanceBackgroundUploads() {
        ProxyProperties properties = new ProxyProperties();
        properties.setMaxRequestBodyBytes(10);
        WebFluxConfig config = new WebFluxConfig(properties, 100, "build/test-model-icons", 20);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/admin/appearance/background", 50);
        org.mockito.Mockito.when(chain.filter(exchange)).thenReturn(Mono.empty());

        config.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(exchange);
    }

    @Test
    void usesTheDedicatedLimitForAppearanceLogoUploads() {
        ProxyProperties properties = new ProxyProperties();
        properties.setMaxRequestBodyBytes(10);
        WebFluxConfig config = new WebFluxConfig(
            properties,
            100,
            "build/test-model-icons",
            20,
            "build/test-branding",
            40,
            80
        );
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/admin/appearance/logo", 41);
        org.mockito.Mockito.when(chain.filter(exchange)).thenReturn(Mono.empty());

        config.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(exchange);
    }

    @Test
    void rejectsOversizedAppearanceLogoUploadsWithJson() {
        ProxyProperties properties = new ProxyProperties();
        properties.setMaxRequestBodyBytes(10);
        WebFluxConfig config = new WebFluxConfig(
            properties,
            100,
            "build/test-model-icons",
            20,
            "build/test-branding",
            40,
            80
        );
        WebFilterChain chain = mock(WebFilterChain.class);
        long tooLarge = 40L + 1024 * 1024 + 1;
        MockServerWebExchange exchange = exchange("/admin/appearance/logo", tooLarge);

        config.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(chain, never()).filter(exchange);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    private MockServerWebExchange exchange(String path, long contentLength) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
            .header("Content-Length", Long.toString(contentLength))
            .build());
    }

    private static final class InspectableCorsRegistry extends CorsRegistry {

        private List<String> allowedMethods(String pathPattern) {
            return getCorsConfigurations().get(pathPattern).getAllowedMethods();
        }
    }
}
