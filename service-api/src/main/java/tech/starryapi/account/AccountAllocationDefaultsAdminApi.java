package tech.starryapi.account;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin/accounts/allocation-defaults")
public interface AccountAllocationDefaultsAdminApi {

    @GetMapping
    Mono<ApiResponse<AllocationDefaultsDtos.Defaults>> get(ServerWebExchange exchange);

    @PutMapping
    Mono<ApiResponse<AllocationDefaultsDtos.Defaults>> update(
        ServerWebExchange exchange,
        @Valid @RequestBody AllocationDefaultsDtos.UpdateDefaults request
    );

    @PostMapping("/resolve")
    Mono<ApiResponse<AllocationDefaultsDtos.ResolveResponse>> resolve(
        ServerWebExchange exchange,
        @Valid @RequestBody AllocationDefaultsDtos.ResolveRequest request
    );
}
