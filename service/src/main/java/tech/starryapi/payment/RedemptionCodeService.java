package tech.starryapi.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.payment.PaymentDtos.CreateRedemptionCodeRequest;
import tech.starryapi.payment.PaymentDtos.CreateRedemptionCodesResponse;
import tech.starryapi.payment.PaymentDtos.RedeemCodeRequest;
import tech.starryapi.payment.PaymentDtos.RedeemCodeResponse;
import tech.starryapi.payment.PaymentDtos.RedemptionCodeResponse;

@Service
public class RedemptionCodeService {

    private static final String USER_ROLE = "USER";
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_PART_LENGTH = 4;
    private static final int CODE_PARTS = 3;
    private static final int DEFAULT_COUNT = 1;
    private static final int MAX_COUNT = 100;

    private final RedemptionCodeRepository repository;
    private final R2dbcEntityTemplate template;
    private final SecureRandom random = new SecureRandom();

    public RedemptionCodeService(RedemptionCodeRepository repository, R2dbcEntityTemplate template) {
        this.repository = repository;
        this.template = template;
    }

    public Flux<RedemptionCodeResponse> list() {
        return repository.findTop200ByOrderByCreatedAtDesc().map(RedemptionCodeMapper::toResponse);
    }

    public Mono<CreateRedemptionCodesResponse> create(AdminPrincipal principal, CreateRedemptionCodeRequest request) {
        String planId = clean(request == null ? null : request.planId());
        boolean hasPlan = !planId.isEmpty();
        boolean hasBalance = request != null && request.balance() != null;
        if (hasPlan == hasBalance) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REDEMPTION_CODE_PAYLOAD",
                "Redemption code requires exactly one of a positive balance or a coding plan"
            ));
        }
        BigDecimal balance = hasPlan ? null : validateBalance(request.balance());
        int count = validateCount(request == null ? null : request.count());
        Instant expiresAt = request == null ? null : request.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REDEMPTION_CODE_EXPIRY", "Expiration time must be in the future"));
        }
        if (!hasPlan) {
            return Flux.range(0, count)
                .concatMap(index -> saveNewCode(principal, null, balance, expiresAt))
                .collectList()
                .map(codes -> new CreateRedemptionCodesResponse(codes.size(), codes));
        }
        return requireEnabledPlan(planId)
            .flatMapMany(verifiedPlanId -> Flux.range(0, count)
                .concatMap(index -> saveNewCode(principal, verifiedPlanId, null, expiresAt)))
            .collectList()
            .map(codes -> new CreateRedemptionCodesResponse(codes.size(), codes));
    }

    private Mono<String> requireEnabledPlan(String planId) {
        String sql = """
            SELECT plan_id FROM coding_plans
            WHERE plan_id = :planId AND enabled = TRUE
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("planId", planId)
            .map((row, metadata) -> row.get("plan_id", String.class))
            .one()
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "REDEMPTION_CODE_PLAN_UNAVAILABLE",
                "Coding plan does not exist or is disabled"
            )));
    }

    public Mono<RedemptionCodeResponse> revoke(String codeId) {
        String sql = """
            UPDATE redemption_codes
            SET enabled = FALSE,
                updated_at = clock_timestamp()
            WHERE code_id = :codeId
            RETURNING *
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("codeId", clean(codeId))
            .map((row, metadata) -> template.getConverter().read(RedemptionCodeEntity.class, row, metadata))
            .one()
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "REDEMPTION_CODE_NOT_FOUND",
                "Redemption code not found"
            )))
            .map(RedemptionCodeMapper::toResponse);
    }

    public Mono<RedeemCodeResponse> redeem(AdminPrincipal principal, RedeemCodeRequest request) {
        if (!USER_ROLE.equalsIgnoreCase(principal.role())) {
            return Mono.error(new ApiException(HttpStatus.FORBIDDEN, "USER_ROLE_REQUIRED", "Redemption codes are only available to ordinary users"));
        }
        String code = normalizeCode(request == null ? null : request.code());
        if (code.isBlank()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "REDEMPTION_CODE_REQUIRED", "Redemption code is required"));
        }
        Instant now = Instant.now();
        return redeemOnce(principal.userId(), code, now)
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REDEMPTION_CODE",
                "Redemption code is invalid, expired, or already used"
            )));
    }

    private Mono<RedemptionCodeResponse> saveNewCode(
        AdminPrincipal principal,
        String planId,
        BigDecimal balance,
        Instant expiresAt
    ) {
        return Mono.defer(() -> repository.save(newCode(principal, planId, balance, expiresAt)))
            .retryWhen(Retry.max(5).filter(DuplicateKeyException.class::isInstance))
            .map(RedemptionCodeMapper::toResponse);
    }

    private RedemptionCodeEntity newCode(AdminPrincipal principal, String planId, BigDecimal balance, Instant expiresAt) {
        RedemptionCodeEntity entity = new RedemptionCodeEntity();
        Instant now = Instant.now();
        entity.setCodeId(Hashing.shortUuid());
        entity.setCode(generateCode(planId != null));
        entity.setBalance(balance);
        entity.setPlanId(planId);
        entity.setEnabled(true);
        entity.setUsed(false);
        entity.setExpiresAt(expiresAt);
        entity.setCreatedByUserId(clean(principal.userId()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private Mono<RedeemCodeResponse> redeemOnce(String userId, String code, Instant now) {
        String transactionId = Hashing.shortUuid();
        String sql = """
            WITH candidate AS MATERIALIZED (
                SELECT r.id,
                       r.code_id,
                       r.balance AS redeemed_balance,
                       r.plan_id,
                       u.user_id,
                       u.username,
                       u.balance AS balance_before,
                       u.balance + r.balance AS balance_after
                FROM redemption_codes AS r
                INNER JOIN admin_users AS u ON u.user_id = :userId
                WHERE r.code = :code
                  AND r.enabled = TRUE
                  AND r.used = FALSE
                  AND (r.expires_at IS NULL OR r.expires_at > :now)
                  AND u.role = :userRole
                  AND u.enabled = TRUE
                FOR UPDATE OF r, u
            ), ledger_entry AS (
                INSERT INTO balance_ledger (
                    transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                    reference_type, reference_id, idempotency_key, metadata
                )
                SELECT :transactionId,
                       candidate.user_id,
                       'REDEMPTION_CREDIT',
                       candidate.redeemed_balance,
                       candidate.balance_before,
                       candidate.balance_after,
                       'REDEMPTION_CODE',
                       candidate.code_id,
                       'redemption-code:' || candidate.code_id,
                       '{}'::jsonb
                FROM candidate
                WHERE candidate.plan_id IS NULL
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING user_id, reference_id, amount, balance_after
            ), credited_user AS (
                UPDATE admin_users AS u
                SET balance = ledger_entry.balance_after,
                    updated_at = :now
                FROM ledger_entry
                WHERE u.user_id = ledger_entry.user_id
                RETURNING ledger_entry.reference_id,
                          ledger_entry.amount AS redeemed_balance,
                          u.balance AS current_balance
            ), activated_subscription AS MATERIALIZED (
                SELECT result->>'subscriptionId' AS subscription_id
                FROM candidate
                JOIN redemption_codes code ON code.code_id = candidate.code_id
                CROSS JOIN LATERAL (
                    SELECT starry_api.activate_coding_plan(candidate.user_id, candidate.plan_id,
                        code.plan_version, 'REDEMPTION_CODE', candidate.code_id,
                        'redemption:' || candidate.code_id, candidate.user_id) AS result
                    WHERE candidate.plan_id IS NOT NULL
                ) activation
                WHERE result ? 'subscriptionId'
            )
            UPDATE redemption_codes AS r
            SET enabled = FALSE,
                used = TRUE,
                used_by_user_id = candidate.user_id,
                used_by_username = candidate.username,
                used_at = :now,
                updated_at = :now
            FROM candidate
            WHERE r.id = candidate.id
              AND r.enabled = TRUE
              AND r.used = FALSE
              AND (
                  (candidate.plan_id IS NULL AND (SELECT COUNT(*) FROM credited_user) = 1)
                  OR (candidate.plan_id IS NOT NULL AND (SELECT COUNT(*) FROM activated_subscription) = 1)
              )
            RETURNING r.code_id,
                      r.balance AS redeemed_balance,
                      COALESCE(
                          (SELECT current_balance FROM credited_user),
                          candidate.balance_before
                      ) AS current_balance,
                      (SELECT subscription_id FROM activated_subscription) AS subscription_id
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("transactionId", transactionId)
            .bind("code", code)
            .bind("now", now)
            .bind("userId", clean(userId))
            .bind("userRole", USER_ROLE)
            .map((row, metadata) -> new RedeemCodeResponse(
                row.get("code_id", String.class),
                row.get("redeemed_balance", BigDecimal.class),
                row.get("current_balance", BigDecimal.class),
                row.get("subscription_id", String.class),
                now
            ))
            .one();
    }

    private BigDecimal validateBalance(BigDecimal value) {
        BigDecimal balance = (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
        if (balance.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REDEMPTION_CODE_BALANCE", "Redemption code balance must be positive");
        }
        return balance;
    }

    private int validateCount(Integer value) {
        int count = value == null ? DEFAULT_COUNT : value;
        if (count < 1 || count > MAX_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REDEMPTION_CODE_COUNT", "Redemption code count must be between 1 and 100");
        }
        return count;
    }

    private String generateCode(boolean forPlan) {
        StringBuilder builder = new StringBuilder(forPlan ? "PLN" : "BAL");
        for (int part = 0; part < CODE_PARTS; part++) {
            builder.append('-');
            for (int index = 0; index < CODE_PART_LENGTH; index++) {
                builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
        }
        return builder.toString();
    }

    private String normalizeCode(String value) {
        return clean(value).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
