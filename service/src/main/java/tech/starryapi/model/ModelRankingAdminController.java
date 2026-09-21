package tech.starryapi.model;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
@RequestMapping("/admin/model-ranking")
public class ModelRankingAdminController {

    private final ModelRankingService modelRankingService;
    private final AdminAuth adminAuth;

    public ModelRankingAdminController(ModelRankingService modelRankingService, AdminAuth adminAuth) {
        this.modelRankingService = modelRankingService;
        this.adminAuth = adminAuth;
    }

    @GetMapping
    public Mono<ApiResponse<ModelRankingDtos.RankingSettingsResponse>> get(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return Mono.fromCallable(modelRankingService::adminSettings).map(ApiResponse::ok);
    }

    @PatchMapping
    public Mono<ApiResponse<ModelRankingDtos.RankingSettingsResponse>> update(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelRankingDtos.UpdateRankingSettingsRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return modelRankingService.updateSettings(request).map(ApiResponse::ok);
    }

    @PostMapping("/refresh")
    public Mono<ApiResponse<ModelRankingDtos.RankingSettingsResponse>> refresh(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return modelRankingService.refreshNow().map(ApiResponse::ok);
    }
}
