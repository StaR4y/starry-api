package tech.starryapi.codingplan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.codingplan.CodingPlanDtos.*;

@Service
public class CodingPlanSubscriptionService {
    private static final String SELECT = """
        SELECT s.*, owner.username, v.name AS plan_name, v.allowed_providers,
            CASE WHEN s.status <> 'ACTIVE' THEN s.status
                 WHEN s.ends_at <= clock_timestamp() THEN 'EXPIRED'
                 WHEN s.starts_at > clock_timestamp() THEN 'SCHEDULED'
                 WHEN s.used_credits >= s.quota_credits THEN 'EXHAUSTED'
                 ELSE 'ACTIVE' END AS effective_status
        FROM coding_plan_subscriptions s
        LEFT JOIN admin_users owner ON owner.user_id = s.user_id
        JOIN coding_plan_versions v ON v.plan_id = s.plan_id AND v.version = s.plan_version
        """;
    private final DatabaseClient db;
    private final ObjectMapper mapper;

    public CodingPlanSubscriptionService(R2dbcEntityTemplate template, ObjectMapper mapper) {
        this.db = template.getDatabaseClient();
        this.mapper = mapper;
    }

    public Mono<PurchaseCodingPlanResponse> purchase(AdminPrincipal principal, String planId) {
        return purchase(principal, planId, null, Hashing.shortUuid());
    }

    public Mono<PurchaseCodingPlanResponse> purchase(AdminPrincipal principal, String planId, Integer version, String key) {
        requireKey(key);
        return activate(principal.userId(), planId, version, "BALANCE_PURCHASE", null, key, principal.userId())
            .flatMap(result -> find(result.subscriptionId()).map(s -> new PurchaseCodingPlanResponse(s, result.balanceAfter())));
    }

    public Mono<CodingPlanSubscriptionResponse> grant(String userId, String planId, AdminPrincipal actor) {
        return grant(userId, planId, actor, Hashing.shortUuid());
    }

    public Mono<CodingPlanSubscriptionResponse> grant(String userId, String planId, AdminPrincipal actor, String key) {
        requireKey(key);
        return activate(userId, planId, null, "ADMIN_GRANT", actor.userId(), "grant:" + key, actor.userId())
            .flatMap(result -> find(result.subscriptionId()));
    }

    private Mono<ActivationOutcome> activate(String userId, String planId, Integer version, String source,
                                             String reference, String key, String actor) {
        var sql = db.sql("""
            SELECT starry_api.activate_coding_plan(:userId, :planId, :version, :source, :reference, :key, :actor)::text AS result
            """).bind("userId", userId).bind("planId", planId.trim()).bind("source", source)
            .bind("key", key).bind("actor", actor);
        sql = version == null ? sql.bindNull("version", Integer.class) : sql.bind("version", version);
        sql = reference == null ? sql.bindNull("reference", String.class) : sql.bind("reference", reference);
        return sql.map((row, metadata) -> {
            try {
                var result = mapper.readTree(row.get("result", String.class));
                if (result.has("error")) {
                    String error = result.path("error").asText();
                    throw new ApiException(error.equals("INSUFFICIENT_BALANCE") ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.CONFLICT,
                        error, "套餐开通失败，请检查余额、套餐版本或重复提交的内容");
                }
                return new ActivationOutcome(result.path("subscriptionId").asText(), result.path("balance").decimalValue());
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Invalid activation result", error);
            }
        }).one();
    }

    public Mono<CodingPlanSubscriptionResponse> updateStatus(String id, String status) {
        return updateStatus(id, status, "system", "Lifecycle update");
    }

    public Mono<CodingPlanSubscriptionResponse> updateStatus(String id, String status, String actor, String reason) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "REVOKED", "CANCELLED").contains(normalized) || reason == null || reason.isBlank() || reason.length() > 512) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "SUBSCRIPTION_UPDATE_INVALID", "请选择状态并填写操作原因"));
        }
        return db.sql("SELECT starry_api.change_coding_plan_status(:id, :status, :actor, :reason) AS result")
            .bind("id", id).bind("status", normalized).bind("actor", actor).bind("reason", reason)
            .map((row, metadata) -> row.get("result", String.class)).one()
            .flatMap(result -> "UPDATED".equals(result) ? find(id) : Mono.error(new ApiException(
                HttpStatus.CONFLICT, result, "SUBSCRIPTION_TERM_OVERLAP".equals(result)
                    ? "续期已生效，恢复此订阅会造成周期重叠，请先处理后续订阅"
                    : "订阅不存在或已过期，无法执行该操作")));
    }

    public Flux<CodingPlanSubscriptionResponse> listByUser(String userId) { return listAll(userId); }

    public Flux<CodingPlanSubscriptionResponse> listAll(String userId) {
        return page(userId, "", "", 1, 200).flatMapMany(result -> Flux.fromIterable(result.items()));
    }

    public Mono<SubscriptionPage> page(String userId, String search, String status, int page, int pageSize) {
        int safePage = Math.max(1, Math.min(page, 1000000));
        int size = Math.max(1, Math.min(pageSize, 200));
        String condition = """
            WHERE (:userId = '' OR user_id = :userId)
              AND (:search = '' OR concat_ws(' ', username, user_id, plan_name) ILIKE '%' || :search || '%')
              AND (:status = '' OR effective_status = :status)
            """;
        var count = db.sql("SELECT count(*) AS total FROM (" + SELECT + ") q " + condition)
            .bind("userId", clean(userId)).bind("search", clean(search)).bind("status", clean(status))
            .map((row, metadata) -> row.get("total", Long.class)).one();
        var rows = db.sql("SELECT * FROM (" + SELECT + ") q " + condition + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
            .bind("userId", clean(userId)).bind("search", clean(search)).bind("status", clean(status))
            .bind("limit", size).bind("offset", (safePage - 1) * size).map((row, metadata) -> response(row)).all().collectList();
        return Mono.zip(rows, count).map(tuple -> new SubscriptionPage(tuple.getT1(), tuple.getT2(), safePage, size));
    }

    public Flux<SubscriptionLedgerEntry> ledger(String id, String userId, long before) {
        return db.sql("""
            SELECT l.* FROM subscription_ledger l JOIN coding_plan_subscriptions s USING(subscription_id)
            WHERE l.subscription_id = :id AND (:userId = '' OR s.user_id = :userId)
              AND (:before = 0 OR l.id < :before) ORDER BY l.id DESC LIMIT 100
            """).bind("id", id).bind("userId", clean(userId)).bind("before", Math.max(0, before))
            .map((row, metadata) -> new SubscriptionLedgerEntry(row.get("id", Long.class), row.get("entry_type", String.class),
                row.get("amount", BigDecimal.class), row.get("reserved_delta", BigDecimal.class),
                row.get("reference_id", String.class), row.get("actor_id", String.class), row.get("reason", String.class),
                row.get("created_at", Instant.class))).all();
    }

    public Mono<SubscriptionSummary> summary(String userId, String provider) {
        return db.sql("""
            WITH scoped AS (
                SELECT s.*, s.status = 'ACTIVE' AND s.starts_at <= CURRENT_TIMESTAMP
                    AND s.ends_at > CURRENT_TIMESTAMP AND s.used_credits < s.quota_credits AS usable
                FROM coding_plan_subscriptions s JOIN coding_plan_versions v
                    ON v.plan_id = s.plan_id AND v.version = s.plan_version
                WHERE s.user_id = :user AND (:provider = '' OR v.allowed_providers IS NULL
                    OR v.allowed_providers @> jsonb_build_array(CAST(:provider AS text)))
            ) SELECT coalesce(sum(quota_credits-used_credits-reserved_credits) FILTER (WHERE usable),0) AS available,
                coalesce(sum(reserved_credits),0) AS reserved,
                min(ends_at) FILTER (WHERE usable) AS expiry, count(*) FILTER (WHERE usable) AS active
            FROM scoped
            """).bind("user", userId).bind("provider", clean(provider))
            .map((row, metadata) -> new SubscriptionSummary(row.get("available", BigDecimal.class).toPlainString(),
                row.get("reserved", BigDecimal.class).toPlainString(), row.get("expiry", Instant.class), row.get("active", Long.class)))
            .one();
    }

    private Mono<CodingPlanSubscriptionResponse> find(String id) {
        return db.sql(SELECT + " WHERE s.subscription_id = :id").bind("id", id)
            .map((row, metadata) -> response(row)).one()
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "CODING_PLAN_SUBSCRIPTION_NOT_FOUND", "Subscription not found")));
    }

    private CodingPlanSubscriptionResponse response(io.r2dbc.spi.Row row) {
        BigDecimal quota = row.get("quota_credits", BigDecimal.class), used = row.get("used_credits", BigDecimal.class);
        BigDecimal reserved = row.get("reserved_credits", BigDecimal.class);
        return new CodingPlanSubscriptionResponse(row.get("subscription_id", String.class), row.get("user_id", String.class),
            row.get("username", String.class), row.get("plan_id", String.class), row.get("plan_name", String.class),
            row.get("plan_version", Integer.class), row.get("effective_status", String.class), row.get("starts_at", Instant.class),
            row.get("ends_at", Instant.class), quota, used, quota.subtract(used), reserved, quota.subtract(used).subtract(reserved),
            providers(row.get("allowed_providers", String.class)), row.get("source", String.class), row.get("source_ref_id", String.class),
            row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
    }

    private List<String> providers(String json) {
        if (json == null) return List.of();
        try { return mapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception error) { throw new IllegalStateException("Invalid subscription provider snapshot", error); }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "A nonempty Idempotency-Key of at most 100 characters is required");
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    record ActivationOutcome(String subscriptionId, BigDecimal balanceAfter) {}
}
