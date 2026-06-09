package xyz.star4y.api.model;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;

@RestController
public class ModelPricingAdminController implements ModelPricingAdminApi {

    private final ModelPricingService service;
    private final AdminAuth adminAuth;

    public ModelPricingAdminController(ModelPricingService service, AdminAuth adminAuth) {
        this.service = service;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelPricingResponse>> settings(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.settings().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelPricingResponse>> update(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpdateModelPricingRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.update(request).map(ApiResponse::ok);
    }
}
