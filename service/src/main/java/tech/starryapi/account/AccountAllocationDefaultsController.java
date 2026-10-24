package tech.starryapi.account;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class AccountAllocationDefaultsController implements AccountAllocationDefaultsAdminApi {

    private final AccountAllocationDefaultsService service;
    private final AdminAuth adminAuth;

    public AccountAllocationDefaultsController(AccountAllocationDefaultsService service, AdminAuth adminAuth) {
        this.service = service;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<AllocationDefaultsDtos.Defaults>> get(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.get().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AllocationDefaultsDtos.Defaults>> update(
        ServerWebExchange exchange, AllocationDefaultsDtos.UpdateDefaults request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.update(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AllocationDefaultsDtos.ResolveResponse>> resolve(
        ServerWebExchange exchange, AllocationDefaultsDtos.ResolveRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.resolve(request).map(ApiResponse::ok);
    }
}
