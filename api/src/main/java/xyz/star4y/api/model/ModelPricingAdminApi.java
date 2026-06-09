package xyz.star4y.api.model;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiResponse;

@RequestMapping("/admin/model-pricing")
public interface ModelPricingAdminApi {

    @GetMapping
    Mono<ApiResponse<ModelDtos.ModelPricingResponse>> settings(ServerWebExchange exchange);

    @PutMapping
    Mono<ApiResponse<ModelDtos.ModelPricingResponse>> update(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpdateModelPricingRequest request
    );
}
