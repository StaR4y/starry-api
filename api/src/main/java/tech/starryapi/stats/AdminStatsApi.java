package tech.starryapi.stats;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin")
public interface AdminStatsApi {

    @GetMapping("/logs")
    Mono<ApiResponse<List<StatsDtos.RequestLogResponse>>> logs(ServerWebExchange exchange);

    @GetMapping("/performance")
    Mono<ApiResponse<List<StatsDtos.PerformanceMetricResponse>>> performance(ServerWebExchange exchange);

    @GetMapping("/usage-summary")
    Mono<ApiResponse<StatsDtos.UsageSummaryResponse>> usageSummary(ServerWebExchange exchange);

    @GetMapping("/usage-summary/me")
    Mono<ApiResponse<StatsDtos.UsageSummaryResponse>> myUsageSummary(ServerWebExchange exchange);
}
