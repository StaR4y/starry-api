package tech.starryapi.egress;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin/proxy-channels")
public interface ProxyChannelAdminApi {
    @GetMapping Mono<ApiResponse<ProxyChannelDtos.Catalog>> catalog(ServerWebExchange exchange);
    @PostMapping Mono<ApiResponse<ProxyChannelDtos.Channel>> create(ServerWebExchange exchange,
        @Valid @RequestBody ProxyChannelDtos.CreateChannel request);
    @PatchMapping("/{id}") Mono<ApiResponse<ProxyChannelDtos.Channel>> update(ServerWebExchange exchange,
        @PathVariable UUID id, @Valid @RequestBody ProxyChannelDtos.UpdateChannel request);
    @DeleteMapping("/{id}") Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @PathVariable UUID id);
    @PostMapping("/{id}/test") Mono<ApiResponse<ProxyChannelDtos.ProbeResult>> test(ServerWebExchange exchange,
        @PathVariable UUID id);
    @PostMapping("/subscriptions") Mono<ApiResponse<ProxyChannelDtos.SyncResult>> subscribe(ServerWebExchange exchange,
        @Valid @RequestBody ProxyChannelDtos.ImportSubscription request);
    @PostMapping("/subscriptions/{id}/sync") Mono<ApiResponse<ProxyChannelDtos.SyncResult>> sync(ServerWebExchange exchange,
        @PathVariable UUID id);
}
