package tech.starryapi.plugin;

import java.util.List;
import java.util.Locale;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class PluginWidgetController {
    private final PluginRuntime runtime;
    private final AdminAuth adminAuth;

    public PluginWidgetController(PluginRuntime runtime, AdminAuth adminAuth) {
        this.runtime = runtime;
        this.adminAuth = adminAuth;
    }

    @GetMapping("/system/plugin-widgets")
    public Mono<ApiResponse<List<PluginDtos.Widget>>> widgets(ServerWebExchange exchange) {
        adminAuth.verify(exchange);
        return Mono.just(ApiResponse.ok(runtime.widgets()));
    }

    @GetMapping("/plugin-assets/{pluginId}/{*path}")
    public Mono<ResponseEntity<Resource>> asset(
        @PathVariable String pluginId,
        @PathVariable("path") String path
    ) {
        Resource resource = runtime.widgetAsset(pluginId, path);
        if (resource == null || !resource.exists()) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.just(ResponseEntity.ok()
            .contentType(mediaType(path))
            .cacheControl(CacheControl.noStore())
            .body(resource));
    }

    private static MediaType mediaType(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        }
        if (lower.endsWith(".css")) {
            return MediaType.parseMediaType("text/css");
        }
        if (lower.endsWith(".js")) {
            return MediaType.parseMediaType("text/javascript");
        }
        if (lower.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (lower.endsWith(".mp3")) {
            return MediaType.parseMediaType("audio/mpeg");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
