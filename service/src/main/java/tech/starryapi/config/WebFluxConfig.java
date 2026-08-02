package tech.starryapi.config;

import org.springframework.beans.factory.annotation.Value;
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

    private static final String DATA_IMPORT_PATH = "/admin/data-transfer/import";
    private static final long MULTIPART_OVERHEAD_BYTES = 1024 * 1024;

    private final ProxyProperties properties;
    private final long maxDataImportRequestBytes;

    public WebFluxConfig(
        ProxyProperties properties,
        @Value("${starry.data-transfer.max-import-bytes:268435456}") long maxImportBytes
    ) {
        this.properties = properties;
        this.maxDataImportRequestBytes = Math.addExact(maxImportBytes, MULTIPART_OVERHEAD_BYTES);
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
        long maxBodyBytes = DATA_IMPORT_PATH.equals(exchange.getRequest().getPath().value())
            ? maxDataImportRequestBytes
            : properties.getMaxRequestBodyBytes();
        if (contentLength > maxBodyBytes) {
            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
