package tech.starryapi.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class PluginModuleSettingsController {

    private final PluginModuleSettingsService settings;
    private final AdminAuth auth;

    public PluginModuleSettingsController(PluginModuleSettingsService settings, AdminAuth auth) {
        this.settings = settings;
        this.auth = auth;
    }

    @GetMapping("/system/plugin-settings")
    public Mono<ApiResponse<List<PluginDtos.SettingPage>>> pages(ServerWebExchange exchange) {
        auth.verifyAdmin(exchange);
        return Mono.just(ApiResponse.ok(settings.pages()));
    }

    @GetMapping("/admin/plugin-modules/{pluginId}/settings")
    public Mono<ApiResponse<PluginDtos.ModuleSettings>> get(ServerWebExchange exchange, @PathVariable String pluginId) {
        auth.verifyAdmin(exchange);
        return settings.view(pluginId).map(ApiResponse::ok);
    }

    @PutMapping("/admin/plugin-modules/{pluginId}/settings")
    public Mono<ApiResponse<PluginDtos.ModuleSettings>> save(
        ServerWebExchange exchange,
        @PathVariable String pluginId,
        @RequestBody JsonNode body
    ) {
        var actor = auth.verifyAdmin(exchange);
        return settings.save(pluginId, body, actor.userId()).map(ApiResponse::ok);
    }
}
