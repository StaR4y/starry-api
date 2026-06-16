package tech.starryapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Configuration
public class WebFluxConfig implements WebFluxConfigurer, WebFilter {

    private final ProxyProperties properties;

    public WebFluxConfig(ProxyProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("Retry-After", "X-Request-Id");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength > properties.getMaxRequestBodyBytes()) {
            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
