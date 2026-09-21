package tech.starryapi.codingplan;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/payment")
public interface CodingPlanApi {

    @GetMapping("/coding-plans")
    Mono<ApiResponse<List<CodingPlanDtos.CodingPlanResponse>>> plans(ServerWebExchange exchange);

    @PostMapping("/coding-plans/{planId}/purchase")
    Mono<ApiResponse<CodingPlanDtos.PurchaseCodingPlanResponse>> purchase(
        ServerWebExchange exchange,
        @PathVariable String planId,
        @Valid @RequestBody CodingPlanDtos.PurchaseCodingPlanRequest request
    );

    @GetMapping("/coding-plan-subscriptions")
    Mono<ApiResponse<List<CodingPlanDtos.CodingPlanSubscriptionResponse>>> subscriptions(
        ServerWebExchange exchange
    );
}
