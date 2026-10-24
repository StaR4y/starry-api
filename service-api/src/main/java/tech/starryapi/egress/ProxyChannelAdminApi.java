package tech.starryapi.egress;

import jakarta.validation.Valid;
import java.util.List;
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
    @GetMapping("/{id}/accounts") Mono<ApiResponse<List<ProxyChannelDtos.BoundAccount>>> accounts(
        ServerWebExchange exchange, @PathVariable UUID id);
    @PostMapping("/{id}/test") Mono<ApiResponse<ProxyChannelDtos.ProbeResult>> test(ServerWebExchange exchange,
        @PathVariable UUID id);
    @PostMapping("/subscriptions") Mono<ApiResponse<ProxyChannelDtos.SyncResult>> subscribe(ServerWebExchange exchange,
        @Valid @RequestBody ProxyChannelDtos.ImportSubscription request);
    @PatchMapping("/subscriptions/{id}") Mono<ApiResponse<ProxyChannelDtos.Subscription>> renameSubscription(
        ServerWebExchange exchange, @PathVariable UUID id, @Valid @RequestBody ProxyChannelDtos.RenameSubscription request);
    @DeleteMapping("/subscriptions/{id}") Mono<ApiResponse<Void>> deleteSubscription(ServerWebExchange exchange,
        @PathVariable UUID id);
    @PostMapping("/subscriptions/{id}/sync") Mono<ApiResponse<ProxyChannelDtos.SyncResult>> sync(ServerWebExchange exchange,
        @PathVariable UUID id);
}
