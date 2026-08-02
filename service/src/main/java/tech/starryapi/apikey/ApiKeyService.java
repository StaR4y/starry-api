package tech.starryapi.apikey;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.ProxyProperties;

@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "sk-";
    private static final String ADMIN_ROLE = "ADMIN";

    private final ApiKeyRepository repository;
    private final R2dbcEntityTemplate template;
    private final ProxyProperties properties;

    public ApiKeyService(ApiKeyRepository repository, R2dbcEntityTemplate template, ProxyProperties properties) {
        this.repository = repository;
        this.template = template;
        this.properties = properties;
    }

    public Mono<ApiKeyDtos.CreatedApiKeyResponse> create(AdminPrincipal principal, ApiKeyDtos.CreateApiKeyRequest request) {
        String key = KEY_PREFIX + Hashing.randomToken(32);
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(Hashing.shortUuid());
        entity.setUserId(principal.userId());
        entity.setName(request.name());
        entity.setKeyHash(Hashing.sha256(key));
        entity.setKeyValue(key);
        entity.setKeyPrefix(key.substring(0, Math.min(18, key.length())));
        entity.setEnabled(true);
        entity.setCreditsLimit(request.creditsLimit());
        entity.setBillingMinimumCredits(BigDecimal.ZERO);
        entity.setTotalRequests(0L);
        entity.setTotalCredits(BigDecimal.ZERO);
        entity.setTotalInputTokens(0L);
        entity.setTotalOutputTokens(0L);
        return repository.save(entity)
            .map(saved -> new ApiKeyDtos.CreatedApiKeyResponse(
                saved.getKeyId(),
                saved.getUserId(),
                saved.getName(),
                key,
                saved.getKeyPrefix(),
                saved.getEnabled(),
                saved.getCreditsLimit(),
                saved.getCreatedAt()
            ));
    }

    public Mono<ApiKeyEntity> update(AdminPrincipal principal, String keyId, ApiKeyDtos.UpdateApiKeyRequest request) {
        String sql = """
            UPDATE api_keys
            SET name = CASE WHEN :setName THEN :name ELSE name END,
                enabled = CASE WHEN :setEnabled THEN :enabled ELSE enabled END,
                credits_limit = CASE WHEN :setCreditsLimit THEN :creditsLimit ELSE credits_limit END,
                updated_at = clock_timestamp()
            WHERE key_id = :keyId
              AND (:isAdmin OR user_id = :userId)
            RETURNING *
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("setName", request.name() != null)
            .bind("setEnabled", request.enabled() != null)
            .bind("setCreditsLimit", request.creditsLimit() != null)
            .bind("keyId", keyId)
            .bind("isAdmin", isAdmin(principal))
            .bind("userId", principal.userId());
        spec = bindNullable(spec, "name", request.name(), String.class);
        spec = bindNullable(spec, "enabled", request.enabled(), Boolean.class);
        spec = bindNullable(spec, "creditsLimit", request.creditsLimit(), BigDecimal.class);
        return spec
            .map((row, metadata) -> template.getConverter().read(ApiKeyEntity.class, row, metadata))
            .one()
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API key not found")));
    }

    public Mono<Void> delete(AdminPrincipal principal, String keyId) {
        if (isAdmin(principal)) {
            return repository.deleteByKeyId(keyId);
        }
        return repository.deleteByKeyIdAndUserId(keyId, principal.userId());
    }

    public Mono<ApiKeyPrincipal> authenticate(ServerWebExchange exchange) {
        Optional<String> key = extractKey(exchange);
        if (key.isEmpty()) {
            if (properties.isAllowAnonymousProxy()) {
                return Mono.just(new ApiKeyPrincipal("anonymous", null, "ANONYMOUS", "anonymous", null, BigDecimal.ZERO, BigDecimal.ZERO));
            }
            return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing API key"));
        }
        return repository.findByKeyHash(Hashing.sha256(key.get()))
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid API key")))
            .flatMap(entity -> {
                if (Boolean.FALSE.equals(entity.getEnabled())) {
                    return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_DISABLED", "API key is disabled"));
                }
                BigDecimal totalCredits = nonNegative(entity.getTotalCredits());
                BigDecimal billingMinimum = nonNegative(entity.getBillingMinimumCredits());
                if (entity.getCreditsLimit() != null
                    && (totalCredits.compareTo(entity.getCreditsLimit()) >= 0
                        || totalCredits.add(billingMinimum).compareTo(entity.getCreditsLimit()) > 0)) {
                    return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CREDITS_LIMIT_EXCEEDED", "API key credits limit exceeded"));
                }
                return userBillingState(entity.getUserId())
                    .flatMap(user -> {
                        if ("USER".equalsIgnoreCase(user.role())
                            && billingMinimum.signum() > 0
                            && nonNegative(user.balance()).compareTo(billingMinimum) < 0) {
                            return Mono.error(new ApiException(
                                HttpStatus.PAYMENT_REQUIRED,
                                "INSUFFICIENT_BALANCE",
                                "Account balance is below the minimum required by the last usage settlement"
                            ));
                        }
                        return Mono.just(new ApiKeyPrincipal(
                            entity.getKeyId(),
                            entity.getUserId(),
                            user.role(),
                            entity.getName(),
                            entity.getCreditsLimit(),
                            totalCredits,
                            user.balance()
                        ));
                    });
            });
    }

    public Mono<Void> ensureBalanceAvailable(ApiKeyPrincipal principal) {
        if (principal == null || !principal.requiresBalance()) {
            return Mono.empty();
        }
        BigDecimal balance = principal.balance() == null ? BigDecimal.ZERO : principal.balance();
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", "Account balance is insufficient"));
        }
        return Mono.empty();
    }

    public Mono<Void> accountUsage(
        ApiKeyPrincipal principal,
        String requestId,
        BigDecimal credits,
        long inputTokens,
        long outputTokens
    ) {
        if (principal == null || "anonymous".equals(principal.keyId())) {
            return Mono.empty();
        }
        if (requestId == null || requestId.isBlank()) {
            return Mono.error(new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "REQUEST_ID_REQUIRED",
                "Request ID is required for usage accounting"
            ));
        }
        BigDecimal safeCredits = credits == null ? BigDecimal.ZERO : credits;
        if (safeCredits.signum() < 0) {
            safeCredits = BigDecimal.ZERO;
        }
        long safeInputTokens = Math.max(inputTokens, 0);
        long safeOutputTokens = Math.max(outputTokens, 0);
        String usageId = requestId.trim();
        String referenceId = usageId.length() <= 64
            ? usageId
            : Hashing.sha256(usageId).substring(0, 64);
        String keyScopedRequestId = principal.keyId().length() + ":" + principal.keyId() + usageId;
        String requestHash = Hashing.sha256(keyScopedRequestId);
        String sql = """
            WITH locked_key AS MATERIALIZED (
                SELECT api_key.key_id,
                       api_key.user_id,
                       api_key.enabled,
                       api_key.credits_limit,
                       api_key.total_credits
                FROM api_keys AS api_key
                WHERE api_key.key_id = :keyId
                FOR UPDATE OF api_key
            ), locked_billing AS MATERIALIZED (
                SELECT locked_key.key_id,
                       locked_key.user_id,
                       locked_key.enabled AS key_enabled,
                       locked_key.credits_limit,
                       locked_key.total_credits,
                       owner.role,
                       owner.enabled AS owner_enabled,
                       owner.balance
                FROM locked_key
                INNER JOIN admin_users AS owner ON owner.user_id = locked_key.user_id
                FOR UPDATE OF owner
            ), existing_event AS MATERIALIZED (
                SELECT request_hash
                FROM api_usage_events
                WHERE request_hash = :requestHash
            ), decision AS MATERIALIZED (
                SELECT CASE
                    WHEN EXISTS (SELECT 1 FROM existing_event) THEN 'ALREADY_ACCOUNTED'
                    WHEN NOT EXISTS (SELECT 1 FROM locked_key) THEN 'KEY_NOT_FOUND'
                    WHEN NOT (SELECT enabled FROM locked_key) THEN 'KEY_DISABLED'
                    WHEN NOT EXISTS (SELECT 1 FROM locked_billing) THEN 'OWNER_NOT_FOUND'
                    WHEN NOT (SELECT owner_enabled FROM locked_billing) THEN 'OWNER_DISABLED'
                    WHEN (
                        SELECT credits_limit IS NOT NULL
                           AND (total_credits >= credits_limit OR total_credits + :credits > credits_limit)
                        FROM locked_billing
                    ) THEN 'CREDITS_LIMIT_EXCEEDED'
                    WHEN (
                        SELECT role = 'USER' AND (balance <= 0 OR balance < :credits)
                        FROM locked_billing
                    ) THEN 'INSUFFICIENT_BALANCE'
                    ELSE 'ACCOUNT'
                END AS accounting_status
            ), rejected_hold AS (
                UPDATE api_keys AS api_key
                SET billing_minimum_credits = GREATEST(api_key.billing_minimum_credits, :credits),
                    updated_at = CURRENT_TIMESTAMP
                FROM decision
                WHERE api_key.key_id = :keyId
                  AND decision.accounting_status IN ('CREDITS_LIMIT_EXCEEDED', 'INSUFFICIENT_BALANCE')
                RETURNING api_key.key_id
            ), claimed_event AS (
                INSERT INTO api_usage_events (
                    request_hash, request_id, key_id, user_id, usage_date,
                    credits, input_tokens, output_tokens, charge_balance
                )
                SELECT :requestHash,
                       :referenceId,
                       locked_billing.key_id,
                       locked_billing.user_id,
                       (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,
                       :credits,
                       :inputTokens,
                       :outputTokens,
                       locked_billing.role = 'USER'
                FROM locked_billing
                CROSS JOIN decision
                WHERE decision.accounting_status = 'ACCOUNT'
                ON CONFLICT (request_hash) DO NOTHING
                RETURNING request_hash, request_id, key_id, user_id, usage_date,
                          credits, input_tokens, output_tokens, charge_balance
            ), balance_candidate AS MATERIALIZED (
                SELECT claimed_event.request_hash,
                       claimed_event.request_id,
                       locked_billing.user_id,
                       claimed_event.credits,
                       locked_billing.balance AS balance_before,
                       locked_billing.balance - claimed_event.credits AS balance_after
                FROM claimed_event
                INNER JOIN locked_billing ON locked_billing.user_id = claimed_event.user_id
                WHERE claimed_event.charge_balance
            ), ledger_entry AS (
                INSERT INTO balance_ledger (
                    transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                    reference_type, reference_id, idempotency_key, metadata
                )
                SELECT :transactionId,
                       balance_candidate.user_id,
                       'API_USAGE_DEBIT',
                       balance_candidate.balance_after - balance_candidate.balance_before,
                       balance_candidate.balance_before,
                       balance_candidate.balance_after,
                       'API_REQUEST',
                       balance_candidate.request_id,
                       'api-request:' || balance_candidate.request_hash,
                       '{}'::jsonb
                FROM balance_candidate
                WHERE balance_candidate.balance_after <> balance_candidate.balance_before
                RETURNING user_id, balance_after
            ), debited_user AS (
                UPDATE admin_users AS user_account
                SET balance = ledger_entry.balance_after,
                    updated_at = CURRENT_TIMESTAMP
                FROM ledger_entry
                WHERE user_account.user_id = ledger_entry.user_id
                RETURNING user_account.user_id
            ), updated_key AS (
                UPDATE api_keys AS api_key
                SET total_requests = api_key.total_requests + 1,
                    total_credits = api_key.total_credits + claimed_event.credits,
                    total_input_tokens = api_key.total_input_tokens + claimed_event.input_tokens,
                    total_output_tokens = api_key.total_output_tokens + claimed_event.output_tokens,
                    billing_minimum_credits = 0,
                    last_used_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                FROM claimed_event
                WHERE api_key.key_id = claimed_event.key_id
                RETURNING api_key.key_id,
                          claimed_event.usage_date,
                          claimed_event.credits,
                          claimed_event.input_tokens,
                          claimed_event.output_tokens
            ), updated_daily AS (
                INSERT INTO api_key_usage_daily (
                    key_id, usage_date, requests, credits, input_tokens, output_tokens
                )
                SELECT key_id, usage_date, 1, credits, input_tokens, output_tokens
                FROM updated_key
                ON CONFLICT (key_id, usage_date) DO UPDATE
                SET requests = api_key_usage_daily.requests + EXCLUDED.requests,
                    credits = api_key_usage_daily.credits + EXCLUDED.credits,
                    input_tokens = api_key_usage_daily.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = api_key_usage_daily.output_tokens + EXCLUDED.output_tokens,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING key_id
            ), accounting_outcome AS (
                SELECT CASE
                    WHEN decision.accounting_status <> 'ACCOUNT' THEN decision.accounting_status
                    WHEN EXISTS (SELECT 1 FROM claimed_event) THEN 'ACCOUNTED'
                    ELSE 'ALREADY_ACCOUNTED'
                END AS accounting_status,
                (SELECT COUNT(*) FROM rejected_hold) AS rejected_hold_count,
                (SELECT COUNT(*) FROM updated_daily) AS updated_daily_count
                FROM decision
            )
            SELECT accounting_status
            FROM accounting_outcome
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("keyId", principal.keyId())
            .bind("requestHash", requestHash)
            .bind("referenceId", referenceId)
            .bind("credits", safeCredits)
            .bind("inputTokens", safeInputTokens)
            .bind("outputTokens", safeOutputTokens)
            .bind("transactionId", Hashing.shortUuid());
        return spec
            .fetch()
            .one()
            .switchIfEmpty(Mono.error(new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "USAGE_ACCOUNTING_FAILED",
                "Usage accounting did not return an outcome"
            )))
            .flatMap(row -> completeUsageAccounting(String.valueOf(row.get("accounting_status"))));
    }

    private Mono<Void> completeUsageAccounting(String accountingStatus) {
        return switch (accountingStatus) {
            case "ACCOUNTED", "ALREADY_ACCOUNTED" -> Mono.empty();
            case "CREDITS_LIMIT_EXCEEDED" -> Mono.error(new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "CREDITS_LIMIT_EXCEEDED",
                "API key credits limit exceeded"
            ));
            case "INSUFFICIENT_BALANCE" -> Mono.error(new ApiException(
                HttpStatus.PAYMENT_REQUIRED,
                "INSUFFICIENT_BALANCE",
                "Account balance is insufficient for this request"
            ));
            case "KEY_NOT_FOUND", "KEY_DISABLED" -> Mono.error(new ApiException(
                HttpStatus.UNAUTHORIZED,
                "API_KEY_DISABLED",
                "API key is no longer available"
            ));
            case "OWNER_NOT_FOUND", "OWNER_DISABLED" -> Mono.error(new ApiException(
                HttpStatus.UNAUTHORIZED,
                "API_KEY_OWNER_DISABLED",
                "API key owner is no longer available"
            ));
            default -> Mono.error(new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "USAGE_ACCOUNTING_FAILED",
                "Usage accounting returned an unknown outcome"
            ));
        };
    }

    private boolean isAdmin(AdminPrincipal principal) {
        return principal != null && ADMIN_ROLE.equalsIgnoreCase(principal.role());
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static <T> GenericExecuteSpec bindNullable(
        GenericExecuteSpec spec,
        String name,
        T value,
        Class<T> type
    ) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private Mono<UserBillingState> userBillingState(String userId) {
        String sql = """
            SELECT role, balance, enabled
            FROM admin_users
            WHERE user_id = :userId
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("userId", userId)
            .map((row, metadata) -> new UserBillingState(
                row.get("role", String.class),
                row.get("balance", BigDecimal.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class))
            ))
            .one()
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_OWNER_NOT_FOUND", "API key owner does not exist")))
            .flatMap(user -> user.enabled()
                ? Mono.just(user)
                : Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_OWNER_DISABLED", "API key owner is disabled")));
    }

    private Optional<String> extractKey(ServerWebExchange exchange) {
        String xApiKey = exchange.getRequest().getHeaders().getFirst("X-Api-Key");
        if (xApiKey != null && !xApiKey.isBlank()) {
            return Optional.of(xApiKey.trim());
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return Optional.of(authorization.substring("Bearer ".length()).trim());
        }
        return Optional.empty();
    }

    private record UserBillingState(String role, BigDecimal balance, boolean enabled) {
    }
}
