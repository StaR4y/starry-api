package xyz.star4y.api.stats;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiResponse;

@RequestMapping("/admin")
public interface AdminStatsApi {

    @GetMapping("/logs")
    Mono<ApiResponse<List<StatsDtos.RequestLogResponse>>> logs(ServerWebExchange exchange);

    @GetMapping("/performance")
    Mono<ApiResponse<List<StatsDtos.PerformanceMetricResponse>>> performance(ServerWebExchange exchange);
}
