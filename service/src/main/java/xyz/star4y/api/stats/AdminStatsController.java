package xyz.star4y.api.stats;

import java.util.List;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;

@RestController
public class AdminStatsController implements AdminStatsApi {

    private final RequestLogRepository requestLogRepository;
    private final AdminAuth adminAuth;

    public AdminStatsController(RequestLogRepository requestLogRepository, AdminAuth adminAuth) {
        this.requestLogRepository = requestLogRepository;
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
}
