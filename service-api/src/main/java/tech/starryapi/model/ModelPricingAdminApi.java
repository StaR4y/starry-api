package tech.starryapi.model;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin/model-pricing")
public interface ModelPricingAdminApi {

    @GetMapping
    Mono<ApiResponse<ModelDtos.ModelPricingResponse>> settings(ServerWebExchange exchange);

    @RequestMapping(method = {RequestMethod.POST, RequestMethod.PUT})
    Mono<ApiResponse<ModelDtos.ModelPricingResponse>> update(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpdateModelPricingRequest request
    );

    @PostMapping("/preview")
    Mono<ApiResponse<ModelDtos.ModelPricingPreview>> preview(
        ServerWebExchange exchange, @Valid @RequestBody ModelDtos.ModelPricingPreviewRequest request
    );

    @PatchMapping("/source")
    Mono<ApiResponse<ModelDtos.ModelPricingSourceStatus>> updateSource(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpdateModelPricingSourceRequest request
    );

    @PostMapping("/refresh")
    Mono<ApiResponse<ModelDtos.ModelPricingSourceStatus>> refresh(ServerWebExchange exchange);
}
