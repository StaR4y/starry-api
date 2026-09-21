package tech.starryapi.ticket;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
@RequestMapping("/admin/ticket-settings")
public class TicketSettingsController {
    private final TicketSettingsService settings;
    private final AdminAuth auth;
    public TicketSettingsController(TicketSettingsService settings, AdminAuth auth) { this.settings = settings; this.auth = auth; }
    @GetMapping
    public Mono<ApiResponse<TicketDtos.SettingsView>> get(ServerWebExchange exchange) {
        auth.verifyAdmin(exchange); return settings.view().map(ApiResponse::ok);
    }
    @PutMapping
    public Mono<ApiResponse<TicketDtos.SettingsView>> save(ServerWebExchange exchange, @RequestBody TicketDtos.Settings request) {
        var actor = auth.verifyAdmin(exchange);
        return settings.save(request).doOnSuccess(v -> org.slf4j.LoggerFactory.getLogger(getClass()).info(
            "ticket_settings=UPDATED actor_id={} enabled={} mode={}", actor.userId(), v.settings().enabled(), v.settings().mode()))
            .map(ApiResponse::ok);
    }
    @PostMapping("/{id}/retry")
    public Mono<ApiResponse<Void>> retry(ServerWebExchange exchange, @PathVariable String id) {
        var actor = auth.verifyAdmin(exchange);
        return settings.retry(id).doOnSuccess(v -> org.slf4j.LoggerFactory.getLogger(getClass()).info(
            "ticket_notification=RETRY_REQUESTED actor_id={} ticket_id={}", actor.userId(), id)).thenReturn(ApiResponse.ok(null));
    }
}
