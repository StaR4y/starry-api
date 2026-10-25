package tech.starryapi.sourcetier;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class SourceTierAdminController implements SourceTierAdminApi {

    private final SourceTierAdminService service;
    private final SourceTierStatusService statusService;
    private final SourceTierChoices choices;
    private final AdminAuth adminAuth;

    public SourceTierAdminController(
        SourceTierAdminService service,
        SourceTierStatusService statusService,
        SourceTierChoices choices,
        AdminAuth adminAuth
    ) {
        this.service = service;
        this.statusService = statusService;
        this.choices = choices;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<SourceTierDtos.Overview>> overview(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.overview().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<SourceTierDtos.Status>> status(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return statusService.status().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<SourceTierDtos.Placements>> placements(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return statusService.placements().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<SourceTierDtos.MyTiers>> mine(ServerWebExchange exchange, String provider) {
        return choices.forProvider(adminAuth.verify(exchange), provider).map(ApiResponse::ok);
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
