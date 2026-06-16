package tech.starryapi.stats;

import java.util.List;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class AdminStatsController implements AdminStatsApi {

    private final RequestLogRepository requestLogRepository;
    private final PerformanceMetricService performanceMetricService;
    private final AdminAuth adminAuth;

    public AdminStatsController(
        RequestLogRepository requestLogRepository,
        PerformanceMetricService performanceMetricService,
        AdminAuth adminAuth
    ) {
        this.requestLogRepository = requestLogRepository;
        this.performanceMetricService = performanceMetricService;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<List<StatsDtos.RequestLogResponse>>> logs(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return requestLogRepository.findTop100ByOrderByCreatedAtDesc()
            .map(RequestLogMapper::toResponse)
            .collectList()
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<List<StatsDtos.PerformanceMetricResponse>>> performance(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return performanceMetricService.history()
            .map(ApiResponse::ok);
    }
}
