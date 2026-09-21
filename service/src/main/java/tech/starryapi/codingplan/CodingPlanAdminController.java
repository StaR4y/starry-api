package tech.starryapi.codingplan;

import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.codingplan.CodingPlanDtos.AdminGrantSubscriptionRequest;
import tech.starryapi.codingplan.CodingPlanDtos.AdminUpdateSubscriptionRequest;
import tech.starryapi.codingplan.CodingPlanDtos.CodingPlanResponse;
import tech.starryapi.codingplan.CodingPlanDtos.CodingPlanSubscriptionResponse;
import tech.starryapi.codingplan.CodingPlanDtos.CreateCodingPlanRequest;
import tech.starryapi.codingplan.CodingPlanDtos.UpdateCodingPlanRequest;

@RestController
public class CodingPlanAdminController implements CodingPlanAdminApi {

    private final CodingPlanService planService;
    private final CodingPlanSubscriptionService subscriptionService;
    private final AdminAuth adminAuth;

    public CodingPlanAdminController(
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
        adminAuth.verifyAdmin(exchange);
        return planService.list(false).collectList().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<CodingPlanResponse>> createPlan(
        ServerWebExchange exchange,
        @RequestBody CreateCodingPlanRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return planService.create(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<CodingPlanResponse>> updatePlan(
        ServerWebExchange exchange,
        @PathVariable String planId,
        @RequestBody UpdateCodingPlanRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return planService.update(planId, request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<List<CodingPlanSubscriptionResponse>>> subscriptions(
        ServerWebExchange exchange,
        @RequestParam(required = false) String userId
    ) {
        adminAuth.verifyAdmin(exchange);
        return subscriptionService.listAll(userId).collectList().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<CodingPlanSubscriptionResponse>> grantSubscription(
        ServerWebExchange exchange,
        @RequestBody AdminGrantSubscriptionRequest request
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return subscriptionService.grant(request.userId(), request.planId(), principal,
            exchange.getRequest().getHeaders().getFirst("Idempotency-Key")).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<CodingPlanSubscriptionResponse>> updateSubscription(
        ServerWebExchange exchange,
        @PathVariable String subscriptionId,
        @RequestBody AdminUpdateSubscriptionRequest request
    ) {
        AdminPrincipal actor = adminAuth.verifyAdmin(exchange);
        return subscriptionService.updateStatus(subscriptionId, request.status(), actor.userId(), request.reason()).map(ApiResponse::ok);
    }

    @org.springframework.web.bind.annotation.GetMapping("/coding-plan-subscriptions/page")
    public Mono<ApiResponse<CodingPlanDtos.SubscriptionPage>> page(ServerWebExchange exchange,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(defaultValue = "") String search, @RequestParam(defaultValue = "") String status) {
        adminAuth.verifyAdmin(exchange);
        return subscriptionService.page(null, search, status, page, pageSize).map(ApiResponse::ok);
    }

    @org.springframework.web.bind.annotation.GetMapping("/coding-plan-subscriptions/{id}/ledger")
    public Mono<ApiResponse<List<CodingPlanDtos.SubscriptionLedgerEntry>>> ledger(ServerWebExchange exchange,
        @PathVariable String id, @RequestParam(defaultValue = "0") long before) {
        adminAuth.verifyAdmin(exchange);
        return subscriptionService.ledger(id, null, before).collectList().map(ApiResponse::ok);
    }
}
