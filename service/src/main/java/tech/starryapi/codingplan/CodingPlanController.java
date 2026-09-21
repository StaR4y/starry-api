package tech.starryapi.codingplan;

import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.codingplan.CodingPlanDtos.CodingPlanResponse;
import tech.starryapi.codingplan.CodingPlanDtos.CodingPlanSubscriptionResponse;
import tech.starryapi.codingplan.CodingPlanDtos.PurchaseCodingPlanRequest;
import tech.starryapi.codingplan.CodingPlanDtos.PurchaseCodingPlanResponse;

@RestController
public class CodingPlanController implements CodingPlanApi {

    private final CodingPlanService planService;
    private final CodingPlanSubscriptionService subscriptionService;
    private final AdminAuth adminAuth;

    public CodingPlanController(
        CodingPlanService planService,
        CodingPlanSubscriptionService subscriptionService,
        AdminAuth adminAuth
    ) {
        this.planService = planService;
        this.subscriptionService = subscriptionService;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<List<CodingPlanResponse>>> plans(ServerWebExchange exchange) {
        adminAuth.verify(exchange);
        return planService.list(true).collectList().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<PurchaseCodingPlanResponse>> purchase(
        ServerWebExchange exchange,
        @PathVariable String planId,
        @RequestBody PurchaseCodingPlanRequest request
    ) {
        return subscriptionService.purchase(adminAuth.verify(exchange), planId, request.version(),
            exchange.getRequest().getHeaders().getFirst("Idempotency-Key")).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<List<CodingPlanSubscriptionResponse>>> subscriptions(ServerWebExchange exchange) {
        return subscriptionService.listByUser(adminAuth.verify(exchange).userId())
            .collectList()
            .map(ApiResponse::ok);
    }

    @org.springframework.web.bind.annotation.GetMapping("/coding-plan-subscriptions/page")
    public Mono<ApiResponse<CodingPlanDtos.SubscriptionPage>> page(ServerWebExchange exchange,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int pageSize,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String status) {
        return subscriptionService.page(adminAuth.verify(exchange).userId(), "", status, page, pageSize).map(ApiResponse::ok);
    }

    @org.springframework.web.bind.annotation.GetMapping("/coding-plan-subscriptions/{id}/ledger")
    public Mono<ApiResponse<List<CodingPlanDtos.SubscriptionLedgerEntry>>> ledger(ServerWebExchange exchange,
        @PathVariable String id, @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long before) {
        return subscriptionService.ledger(id, adminAuth.verify(exchange).userId(), before).collectList().map(ApiResponse::ok);
    }

    @org.springframework.web.bind.annotation.GetMapping("/coding-plan-subscriptions/summary")
    public Mono<ApiResponse<CodingPlanDtos.SubscriptionSummary>> summary(ServerWebExchange exchange,
        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String provider) {
        return subscriptionService.summary(adminAuth.verify(exchange).userId(), provider).map(ApiResponse::ok);
    }
}
