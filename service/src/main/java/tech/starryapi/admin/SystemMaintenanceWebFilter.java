package tech.starryapi.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@Component
public class SystemMaintenanceWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SystemMaintenanceWebFilter.class);
    private static final String MAINTENANCE_CODE = "SYSTEM_MAINTENANCE";
    private static final String MAINTENANCE_MESSAGE = "System is unavailable while security maintenance is active";
    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/system/status",
        "/system/appearance",
        "/system/model-catalog-display",
        "/system/model-ranking",
        "/health",
        "/v1/health",
        "/actuator/health"
    );
    private static final Set<String> STATIC_PATHS = Set.of(
        "/",
        "/index.html",
        "/favicon.ico",
        "/favicon.svg",
        "/logo.svg",
        "/manifest.json",
        "/robots.txt",
        "/apple-touch-icon.png"
    );

    private final DataManagementSecurityService securityService;
    private final ObjectMapper objectMapper;

    public SystemMaintenanceWebFilter(
        DataManagementSecurityService securityService,
        ObjectMapper objectMapper
    ) {
        this.securityService = securityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return securityService.isLocked()
            .onErrorResume(error -> {
                log.error("Unable to read data management lock snapshot; entering fail-closed maintenance mode", error);
                return Mono.just(true);
            })
            .flatMap(locked -> !Boolean.TRUE.equals(locked) || isPublicPath(exchange)
                ? chain.filter(exchange)
                : maintenanceResponse(exchange));
    }

    private boolean isPublicPath(ServerWebExchange exchange) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (rawPath == null || rawPath.isBlank() || hasUnsafeEncoding(rawPath)) {
            return false;
        }
        if (PUBLIC_PATHS.contains(rawPath) || STATIC_PATHS.contains(rawPath)) {
            return true;
        }
        return rawPath.startsWith("/assets/")
            || rawPath.startsWith("/plugin-assets/")
            || rawPath.startsWith("/actuator/health/")
            || rawPath.startsWith("/system/model-ranking/");
    }

    private boolean hasUnsafeEncoding(String rawPath) {
        String normalized = rawPath.toLowerCase(Locale.ROOT);
        return rawPath.indexOf('\\') >= 0
            || normalized.contains("..")
            || normalized.contains("%25")
            || normalized.contains("%2e")
            || normalized.contains("%2f")
            || normalized.contains("%5c");
    }

    private Mono<Void> maintenanceResponse(ServerWebExchange exchange) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ApiResponse.error(MAINTENANCE_CODE, MAINTENANCE_MESSAGE));
        } catch (Exception error) {
            log.error("Unable to serialize maintenance response", error);
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }

        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
        exchange.getResponse().getHeaders().set("X-Content-Type-Options", "nosniff");
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
