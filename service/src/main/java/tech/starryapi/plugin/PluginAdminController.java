package tech.starryapi.plugin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.common.Hashing;

@RestController
@RequestMapping("/admin/plugins")
public class PluginAdminController {
    private final AdminAuth auth;
    private final PluginSecurityService security;
    private final PluginRuntime runtime;
    private final PluginStore store;
    private final PluginLogs logs;

    public PluginAdminController(AdminAuth auth, PluginSecurityService security, PluginRuntime runtime,
                                 PluginStore store, PluginLogs logs) {
        this.auth = auth;
        this.security = security;
        this.runtime = runtime;
        this.store = store;
        this.logs = logs;
    }

    private AdminPrincipal actor(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
        return auth.verifyAdmin(exchange);
    }

    @GetMapping
    public Mono<ApiResponse<PluginDtos.Status>> status(ServerWebExchange exchange) {
        var actor = actor(exchange);
        return security.root(actor).flatMap(user -> store.security().zipWith(runtime.list())
                .map(result -> new PluginDtos.Status(true, result.getT1().enabled(), runtime.disabled(),
                    runtime.restartRequired(), PluginSecurityService.maskEmail(user.getEmail()), "1", result.getT2(), runtime.restartReasons())))
            .map(ApiResponse::ok);
    }

    @PostMapping("/verification")
    public Mono<ApiResponse<PluginDtos.Challenge>> code(ServerWebExchange exchange, @Valid @RequestBody PluginDtos.SendCode request) {
        return security.send(actor(exchange), session(exchange), request.password()).map(ApiResponse::ok);
    }

    @PostMapping("/developer-mode")
    public Mono<ApiResponse<Boolean>> enable(ServerWebExchange exchange, @Valid @RequestBody PluginDtos.Enable request) {
        return security.enable(actor(exchange), session(exchange), request).thenReturn(ApiResponse.ok(true));
    }

    @DeleteMapping("/developer-mode")
    public Mono<ApiResponse<Boolean>> disable(ServerWebExchange exchange) {
        var actor = actor(exchange);
        return security.disable(actor).then(runtime.stopAll(actor.userId())).thenReturn(ApiResponse.ok(true));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<Boolean>> upload(ServerWebExchange exchange, @RequestPart("file") Mono<FilePart> file) {
        var actor = actor(exchange);
        return security.root(actor).then(runtime.requireMode()).then(file)
            .flatMap(part -> runtime.install(part, actor.userId())).thenReturn(ApiResponse.ok(true));
    }

    @PostMapping("/{id}/actions/{action}")
    public Mono<ApiResponse<Boolean>> action(ServerWebExchange exchange, @PathVariable String id, @PathVariable String action) {
        var actor = actor(exchange);
        return security.root(actor).then(runtime.action(id, action, actor.userId())).thenReturn(ApiResponse.ok(true));
    }

    @GetMapping("/{id}/config")
    public Mono<ApiResponse<String>> config(ServerWebExchange exchange, @PathVariable String id) {
        return security.root(actor(exchange)).then(runtime.configuration(id)).map(ApiResponse::ok);
    }

    @PutMapping("/{id}/config")
    public Mono<ApiResponse<Boolean>> configure(ServerWebExchange exchange, @PathVariable String id,
                                               @Valid @RequestBody PluginDtos.Configure request) {
        var actor = actor(exchange);
        return security.root(actor).then(runtime.configure(id, request.json(), actor.userId())).thenReturn(ApiResponse.ok(true));
    }

    @PostMapping("/{id}/debug")
    public Mono<ApiResponse<PluginDtos.DebugResult>> debug(ServerWebExchange exchange, @PathVariable String id,
                                                         @Valid @RequestBody PluginDtos.Debug request) {
        var actor = actor(exchange);
        return security.root(actor).then(runtime.debug(id, request, actor.userId())).map(ApiResponse::ok);
    }

    @GetMapping("/audit")
    public Mono<ApiResponse<List<PluginDtos.Audit>>> audit(ServerWebExchange exchange,
        @RequestParam(defaultValue = "") String plugin, @RequestParam(defaultValue = "0") long before) {
        return security.root(actor(exchange)).then(store.audits(plugin, Math.max(0, before)).collectList()).map(ApiResponse::ok);
    }

    @GetMapping("/logs")
    public Mono<ApiResponse<List<PluginDtos.Log>>> logs(ServerWebExchange exchange,
        @RequestParam(defaultValue = "") String plugin, @RequestParam(defaultValue = "0") long after) {
        return security.root(actor(exchange)).then(Mono.fromSupplier(() -> logs.list(plugin, Math.max(0, after)))).map(ApiResponse::ok);
    }

    private static String session(ServerWebExchange exchange) {
        String token = exchange.getRequest().getHeaders().getFirst("X-Admin-Token");
        if (token == null) token = exchange.getRequest().getHeaders().getFirst("Authorization");
        return Hashing.sha256(token == null ? "" : token);
    }
}
