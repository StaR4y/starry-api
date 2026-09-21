package tech.starryapi.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Configuration
public class WebFluxConfig implements WebFluxConfigurer, WebFilter {

    private static final String DATA_IMPORT_PATH = "/admin/data-transfer/import";
    private static final String MODEL_ICON_UPLOAD_PATH = "/admin/models/icon";
    private static final String APPEARANCE_LOGO_PATH = "/admin/appearance/logo";
    private static final String APPEARANCE_FAVICON_PATH = "/admin/appearance/favicon";
    private static final String APPEARANCE_BACKGROUND_PATH = "/admin/appearance/background";
    private static final long MULTIPART_OVERHEAD_BYTES = 1024 * 1024;

    private final ProxyProperties properties;
    private final long maxDataImportRequestBytes;
    private final long maxModelIconRequestBytes;
    private final long maxAppearanceLogoRequestBytes;
    private final long maxAppearanceBackgroundRequestBytes;
    private final Path modelIconDirectory;
    private final Path brandingDirectory;

    public WebFluxConfig(
        ProxyProperties properties,
        long maxImportBytes,
        String modelIconDirectory,
        long maxModelIconBytes
    ) {
        this(
            properties,
            maxImportBytes,
            modelIconDirectory,
            maxModelIconBytes,
            "build/test-branding",
            maxModelIconBytes,
            16_777_216L
        );
    }

    @Autowired
    public WebFluxConfig(
        ProxyProperties properties,
        @Value("${starry.data-transfer.max-import-bytes:268435456}") long maxImportBytes,
        @Value("${starry.models.icon-dir:${user.home}/.starry-api/model-icons}") String modelIconDirectory,
        @Value("${starry.models.max-icon-bytes:2097152}") long maxModelIconBytes,
        @Value("${starry.appearance.branding-dir:${user.home}/.starry-api/branding}") String brandingDirectory,
        @Value("${starry.appearance.max-logo-bytes:8388608}") long maxLogoBytes,
        @Value("${starry.appearance.max-background-bytes:16777216}") long maxBackgroundBytes
    ) {
        this.properties = properties;
        this.maxDataImportRequestBytes = Math.addExact(maxImportBytes, MULTIPART_OVERHEAD_BYTES);
        this.maxModelIconRequestBytes = Math.addExact(maxModelIconBytes, MULTIPART_OVERHEAD_BYTES);
        this.maxAppearanceLogoRequestBytes = Math.addExact(maxLogoBytes, MULTIPART_OVERHEAD_BYTES);
        this.maxAppearanceBackgroundRequestBytes = Math.addExact(maxBackgroundBytes, MULTIPART_OVERHEAD_BYTES);
        this.modelIconDirectory = Path.of(modelIconDirectory).toAbsolutePath().normalize();
        this.brandingDirectory = Path.of(brandingDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("Retry-After", "X-Request-Id");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = modelIconDirectory.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/assets/model-icons/**")
            .addResourceLocations(location)
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        String brandingLocation = brandingDirectory.toUri().toString();
        if (!brandingLocation.endsWith("/")) {
            brandingLocation += "/";
        }
        registry.addResourceHandler("/assets/branding/**")
            .addResourceLocations(brandingLocation)
            .setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        String requestPath = exchange.getRequest().getPath().value();
        long maxBodyBytes;
        if (DATA_IMPORT_PATH.equals(requestPath)) {
            maxBodyBytes = maxDataImportRequestBytes;
        } else if (MODEL_ICON_UPLOAD_PATH.equals(requestPath)) {
            maxBodyBytes = maxModelIconRequestBytes;
        } else if (APPEARANCE_LOGO_PATH.equals(requestPath) || APPEARANCE_FAVICON_PATH.equals(requestPath)) {
            maxBodyBytes = maxAppearanceLogoRequestBytes;
        } else if (APPEARANCE_BACKGROUND_PATH.equals(requestPath)) {
            maxBodyBytes = maxAppearanceBackgroundRequestBytes;
        } else {
            maxBodyBytes = properties.getMaxRequestBodyBytes();
        }
        if (contentLength > maxBodyBytes) {
            var response = exchange.getResponse();
            response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            String json = "{\"success\":false,\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"上传文件过大\"},\"timestamp\":\""
                + Instant.now()
                + "\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        }
        return chain.filter(exchange);
    }
}
