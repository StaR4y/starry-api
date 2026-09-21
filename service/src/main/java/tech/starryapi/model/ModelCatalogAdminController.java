package tech.starryapi.model;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.payment.PaymentSettingsService;

@RestController
public class ModelCatalogAdminController implements ModelCatalogAdminApi {

    private final ModelCatalogManagementService service;
    private final ModelIconStorageService iconStorageService;
    private final ModelPricingService modelPricingService;
    private final PaymentSettingsService paymentSettingsService;
    private final AdminAuth adminAuth;

    public ModelCatalogAdminController(
        ModelCatalogManagementService service,
        ModelIconStorageService iconStorageService,
        ModelPricingService modelPricingService,
        PaymentSettingsService paymentSettingsService,
        AdminAuth adminAuth
    ) {
        this.service = service;
        this.iconStorageService = iconStorageService;
        this.modelPricingService = modelPricingService;
        this.paymentSettingsService = paymentSettingsService;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<List<ModelDtos.ManagedModelResponse>>> list(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.list()
            .zipWith(paymentSettingsService.config())
            .flatMap(tuple -> modelPricingService.applyManagedPricing(
                tuple.getT1(),
                tuple.getT2().apiBillingMultiplier()
            ))
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ManagedModelResponse>> upsert(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpsertManagedModelRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.upsert(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelIconUploadResponse>> uploadIcon(
        ServerWebExchange exchange,
        Mono<FilePart> file
    ) {
        adminAuth.verifyAdmin(exchange);
        return iconStorageService.store(file).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @RequestParam String modelId) {
        adminAuth.verifyAdmin(exchange);
        return service.delete(modelId).thenReturn(ApiResponse.ok(null));
    }

    @Override
    public Mono<ApiResponse<Void>> deleteVendor(ServerWebExchange exchange, @RequestParam String provider) {
        adminAuth.verifyAdmin(exchange);
        return service.deleteVendor(provider).thenReturn(ApiResponse.ok(null));
    }

    @Override
    public Mono<ApiResponse<Void>> reorder(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.ReorderManagedModelsRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.reorder(request).thenReturn(ApiResponse.ok(null));
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelCatalogDisplaySettings>> getDisplay(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.displaySettings().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.ModelCatalogDisplaySettings>> updateDisplay(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpdateModelCatalogDisplayRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.updateDisplay(request).map(ApiResponse::ok);
    }
}
