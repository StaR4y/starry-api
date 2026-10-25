package tech.starryapi.sourcetier;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class SourceTierAdminController implements SourceTierAdminApi {

    private final SourceTierAdminService service;
    private final AdminAuth adminAuth;

    public SourceTierAdminController(SourceTierAdminService service, AdminAuth adminAuth) {
        this.service = service;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<SourceTierDtos.Overview>> overview(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.overview().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<SourceTierDtos.Vendor>> replace(
        ServerWebExchange exchange, String vendor, SourceTierDtos.ReplaceVendor request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.replace(vendor, request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<SourceTierDtos.Vendor>> upsertMembers(
        ServerWebExchange exchange, String vendor, SourceTierDtos.UpsertMembers request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.upsertMembers(vendor, request).map(ApiResponse::ok);
    }
}
