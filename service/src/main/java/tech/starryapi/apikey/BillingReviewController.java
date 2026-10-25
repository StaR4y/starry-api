package tech.starryapi.apikey;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class BillingReviewController {
    private final DatabaseClient db;
    private final AdminAuth auth;
    public BillingReviewController(DatabaseClient db, AdminAuth auth) { this.db=db; this.auth=auth; }

    // A request served from source tiers is reserved for the dearest tier it could have been served from. Its tier columns
    // and the plan in its pricing snapshot (selected only for such a request) tell which tier to settle it by; a request
    // that was not served from tiers has none of them and comes out as before.
    @GetMapping("/admin/billing/reviews")
    public Mono<ApiResponse<List<Map<String,Object>>>> reviews(ServerWebExchange exchange) {
        auth.verifyAdmin(exchange);
        return db.sql("SELECT request_hash, request_id, user_id, key_id, billing_source, reserved_credits, actual_credits, reason, created_at, served_tier_ref, served_tier_level, served_price_factor, last_dispatched_tier_ref, last_dispatched_at, CASE WHEN jsonb_typeof(pricing -> 'tierPlan') = 'object' THEN pricing::text END AS tier_pricing FROM billing_reservations WHERE status='REVIEW_REQUIRED' ORDER BY created_at LIMIT 200")
            .fetch().all().map(BillingReviewTiers::describe).collectList().map(ApiResponse::ok);
    }

    @PostMapping("/admin/billing/reviews/{hash}/resolve")
    public Mono<ApiResponse<Void>> resolve(ServerWebExchange exchange, @PathVariable String hash, @Valid @RequestBody Resolution request) {
        var actor=auth.verifyAdmin(exchange);
        var sql=db.sql("SELECT starry_api.resolve_billing_review(:hash,:actor,:action,:reason,:credits) AS result")
            .bind("hash",hash).bind("actor",actor.userId()).bind("action",request.action()).bind("reason",request.reason());
        sql=request.credits()==null?sql.bindNull("credits",BigDecimal.class):sql.bind("credits",request.credits());
        return sql.map((row,metadata)->row.get("result",String.class)).one().flatMap(ApiKeyService::billingOutcome)
            .thenReturn(ApiResponse.ok(null));
    }

    public record Resolution(@NotBlank String action, @NotBlank @Size(max=512) String reason, BigDecimal credits) {}
}
