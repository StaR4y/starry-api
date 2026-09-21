package tech.starryapi.model;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class ModelPricingAdminController implements ModelPricingAdminApi {

    private final ModelPricingService service;
    private final OfficialModelPricingService officialModelPricingService;
    private final AdminAuth adminAuth;
    private final tech.starryapi.payment.PaymentSettingsService paymentSettingsService;

    public ModelPricingAdminController(
        ModelPricingService service,
        OfficialModelPricingService officialModelPricingService,
        AdminAuth adminAuth,
        tech.starryapi.payment.PaymentSettingsService paymentSettingsService
    ) {
        this.service = service;
        this.officialModelPricingService = officialModelPricingService;
        this.adminAuth = adminAuth;
        this.paymentSettingsService = paymentSettingsService;
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelPricingResponse>> settings(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.settings()
            .map(response -> new ModelDtos.ModelPricingResponse(
                response.rules(),
                officialModelPricingService.sourceStatus()
            ))
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelPricingResponse>> update(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpdateModelPricingRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.update(request)
            .map(response -> new ModelDtos.ModelPricingResponse(
                response.rules(),
                officialModelPricingService.sourceStatus()
            ))
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelPricingPreview>> preview(
        ServerWebExchange exchange, @Valid @RequestBody ModelDtos.ModelPricingPreviewRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return paymentSettingsService.config()
            .map(config -> service.preview(request, config.apiBillingMultiplier()))
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelPricingSourceStatus>> updateSource(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpdateModelPricingSourceRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return officialModelPricingService.updateSource(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelPricingSourceStatus>> refresh(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return officialModelPricingService.refreshNow().map(ApiResponse::ok);
    }
}
