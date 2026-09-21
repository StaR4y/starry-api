package tech.starryapi.model;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RestController
@RequestMapping("/system/model-ranking")
public class ModelRankingController {

    private final ModelRankingService modelRankingService;

    public ModelRankingController(ModelRankingService modelRankingService) {
        this.modelRankingService = modelRankingService;
    }

    @GetMapping
    public Mono<ResponseEntity<ApiResponse<ModelRankingDtos.RankingPublicConfig>>> config() {
        return Mono.fromCallable(() -> new ModelRankingDtos.RankingPublicConfig(modelRankingService.enabled()))
            .map(config -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(config)));
    }

    @GetMapping("/{benchmark}")
    public Mono<ResponseEntity<ApiResponse<ModelRankingDtos.RankingSnapshot>>> get(@PathVariable String benchmark) {
        return Mono.fromCallable(() -> modelRankingService.snapshot(benchmark))
            .map(snapshot -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(snapshot)));
    }
}
