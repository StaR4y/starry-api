package tech.starryapi.codingplan;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin")
public interface CodingPlanAdminApi {

    @GetMapping("/coding-plans")
    Mono<ApiResponse<List<CodingPlanDtos.CodingPlanResponse>>> plans(ServerWebExchange exchange);

    @PostMapping("/coding-plans")
    Mono<ApiResponse<CodingPlanDtos.CodingPlanResponse>> createPlan(
        ServerWebExchange exchange,
        @Valid @RequestBody CodingPlanDtos.CreateCodingPlanRequest request
    );

    @PatchMapping("/coding-plans/{planId}")
    Mono<ApiResponse<CodingPlanDtos.CodingPlanResponse>> updatePlan(
        ServerWebExchange exchange,
        @PathVariable String planId,
        @Valid @RequestBody CodingPlanDtos.UpdateCodingPlanRequest request
    );

    @GetMapping("/coding-plan-subscriptions")
    Mono<ApiResponse<List<CodingPlanDtos.CodingPlanSubscriptionResponse>>> subscriptions(
        ServerWebExchange exchange,
        @RequestParam(required = false) String userId
    );

    @PostMapping("/coding-plan-subscriptions")
    Mono<ApiResponse<CodingPlanDtos.CodingPlanSubscriptionResponse>> grantSubscription(
        ServerWebExchange exchange,
        @Valid @RequestBody CodingPlanDtos.AdminGrantSubscriptionRequest request
    );

    @PatchMapping("/coding-plan-subscriptions/{subscriptionId}")
    Mono<ApiResponse<CodingPlanDtos.CodingPlanSubscriptionResponse>> updateSubscription(
        ServerWebExchange exchange,
        @PathVariable String subscriptionId,
        @Valid @RequestBody CodingPlanDtos.AdminUpdateSubscriptionRequest request
    );
}
