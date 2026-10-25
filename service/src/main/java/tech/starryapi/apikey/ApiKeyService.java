package tech.starryapi.apikey;

import java.math.BigDecimal;
import java.time.Duration;
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
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport;
import tech.starryapi.proxy.tier.TierSelectorParser;

@Service
public class ApiKeyService {

    private static final java.util.regex.Pattern PROVIDER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9._ -]+");
    private static final String KEY_PREFIX = "sk-";
    private static final String ADMIN_ROLE = "ADMIN";
    /** What a reservation is leased for when it is made (reserve_api_usage), and the least a dispatch renews it to. */
    private static final Duration BASE_LEASE = Duration.ofMinutes(15);
    private static final Duration LEASE_SLACK = Duration.ofSeconds(60);

    private final ApiKeyRepository repository;
    private final R2dbcEntityTemplate template;
    private final ProxyProperties properties;
    private final ModelCatalogProvider modelCatalog;

    public ApiKeyService(
        ApiKeyRepository repository,
        R2dbcEntityTemplate template,
        ProxyProperties properties,
        ModelCatalogProvider modelCatalog
    ) {
        this.repository = repository;
        this.template = template;
        this.properties = properties;
        this.modelCatalog = modelCatalog;
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
        entity.setModelProvider(normalizeModelProvider(request.modelProvider()));
        entity.setBillingSource(normalizeBillingSource(request.billingSource()));
        entity.setTierPreference(normalizeTierPreference(request.tierPreference()));
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
                saved.getModelProvider(),
                saved.getBillingSource(),
                saved.getCreatedAt(),
                ApiKeyMapper.tierPreference(saved.getTierPreference())
            ));
    }

    public Mono<ApiKeyEntity> update(AdminPrincipal principal, String keyId, ApiKeyDtos.UpdateApiKeyRequest request) {
        String sql = """
            UPDATE api_keys
            SET name = CASE WHEN :setName THEN :name ELSE name END,
                enabled = CASE WHEN :setEnabled THEN :enabled ELSE enabled END,
                credits_limit = CASE WHEN :clearCreditsLimit THEN NULL WHEN :setCreditsLimit THEN :creditsLimit ELSE credits_limit END,
                model_provider = CASE WHEN :setModelProvider THEN :modelProvider ELSE model_provider END,
                billing_source = CASE WHEN :setBillingSource THEN :billingSource ELSE billing_source END,
                tier_preference = CASE
                    WHEN :setTierPreference THEN :tierPreference
                    WHEN :setModelProvider AND lower(model_provider) IS DISTINCT FROM lower(:modelProvider) THEN 'auto'
                    ELSE tier_preference
                END,
                updated_at = clock_timestamp()
            WHERE key_id = :keyId
              AND (:isAdmin OR user_id = :userId)
              AND (NOT :requireProvider OR model_provider <> '*')
            RETURNING *
            """;
        // A preference names a pool of one vendor, so the key has to be tied to one provider (design 6.1): the request
        // gives the provider, or the key already has it. The provider the request gives is never "*", which is refused.
        // And it stays with that vendor: a request that moves the key to another provider without giving a preference
        // clears it (the right-hand sides of an UPDATE read the old row), since a name or a level that meant a pool of the
        // one vendor would otherwise start to pin the key to whatever the other happens to have under it.
        String tierPreference = normalizeTierPreference(request.tierPreference());
        boolean requireProvider = request.tierPreference() != null && !"auto".equals(tierPreference) && request.modelProvider() == null;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("setName", request.name() != null)
            .bind("setEnabled", request.enabled() != null)
            .bind("setCreditsLimit", request.creditsLimit() != null)
            .bind("clearCreditsLimit", Boolean.TRUE.equals(request.clearCreditsLimit()))
            .bind("setModelProvider", request.modelProvider() != null)
            .bind("setBillingSource", request.billingSource() != null)
            .bind("billingSource", normalizeBillingSource(request.billingSource()))
            .bind("setTierPreference", request.tierPreference() != null)
            .bind("tierPreference", tierPreference)
            .bind("requireProvider", requireProvider)
            .bind("keyId", keyId)
            .bind("isAdmin", isAdmin(principal))
            .bind("userId", principal.userId());
        spec = bindNullable(spec, "name", request.name(), String.class);
        spec = bindNullable(spec, "enabled", request.enabled(), Boolean.class);
        spec = bindNullable(spec, "creditsLimit", request.creditsLimit(), BigDecimal.class);
        spec = bindNullable(
            spec,
            "modelProvider",
            request.modelProvider() == null ? null : normalizeModelProvider(request.modelProvider()),
            String.class
        );
        return spec
            .map((row, metadata) -> template.getConverter().read(ApiKeyEntity.class, row, metadata))
            .one()
            .switchIfEmpty(Mono.defer(() -> requireProvider ? notUpdatedForWantOfProvider(principal, keyId) : keyNotFound()))
            .onErrorMap(ApiKeyService::mapBillingConstraint);
    }

    private static Mono<ApiKeyEntity> keyNotFound() {
        return Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API key not found"));
    }

    /**
     * Why an update that wanted a preference for a key of the request's own provider changed nothing: there is no such
     * key of the caller's (404), or there is, and it is a legacy key that any provider may call, which no pool of one
     * vendor can be preferred by.
     */
    private Mono<ApiKeyEntity> notUpdatedForWantOfProvider(AdminPrincipal principal, String keyId) {
        Mono<ApiKeyEntity> own = isAdmin(principal)
            ? repository.findByKeyId(keyId)
            : repository.findByKeyIdAndUserId(keyId, principal.userId());
        return own.flatMap(found -> Mono.<ApiKeyEntity>error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "API_KEY_TIER_PREFERENCE_INVALID",
                "A tier preference needs a key tied to one model provider; choose the provider first"
            )))
            .switchIfEmpty(keyNotFound());
    }

    public Mono<Void> delete(AdminPrincipal principal, String keyId) {
        return (isAdmin(principal) ? repository.deleteByKeyId(keyId)
            : repository.deleteByKeyIdAndUserId(keyId, principal.userId()))
            .onErrorMap(ApiKeyService::mapBillingConstraint);
    }

    private static Throwable mapBillingConstraint(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.r2dbc.spi.R2dbcException databaseError
                && databaseError.getMessage().contains("API_KEY_BILLING_BUSY")) {
                return new ApiException(HttpStatus.CONFLICT, "API_KEY_BILLING_BUSY", "API 正在计费，请等请求结算或待核对费用处理后再修改或删除");
            }
        }
        return error;
    }

    public Mono<ApiKeyPrincipal> authenticate(ServerWebExchange exchange) {
        Optional<String> key = extractKey(exchange);
        if (key.isEmpty()) {
            if (properties.isAllowAnonymousProxy()) {
                return Mono.just(new ApiKeyPrincipal(
                    "anonymous",
                    null,
                    "ANONYMOUS",
                    "anonymous",
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "*"
                ));
            }
            return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing API key"));
        }
        return repository.findByKeyHash(Hashing.sha256(key.get()))
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid API key")))
            .flatMap(entity -> {
                if (Boolean.FALSE.equals(entity.getEnabled())) {
                    return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_DISABLED", "API key is disabled"));
                }
                BigDecimal totalCredits = nonNegative(entity.getTotalCredits());
                BigDecimal billingMinimum = nonNegative(entity.getReservedCredits());
                if (entity.getCreditsLimit() != null
                    && (totalCredits.compareTo(entity.getCreditsLimit()) >= 0
                        || totalCredits.add(billingMinimum).compareTo(entity.getCreditsLimit()) > 0)) {
                    return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CREDITS_LIMIT_EXCEEDED", "API key credits limit exceeded"));
                }
                return userBillingState(entity.getUserId(), entity.getModelProvider())
                    .flatMap(user -> {
                        return Mono.just(new ApiKeyPrincipal(
                            entity.getKeyId(),
                            entity.getUserId(),
                            user.role(),
                            entity.getName(),
                            entity.getCreditsLimit(),
                            totalCredits,
                            user.balance(),
                            entity.getModelProvider(),
                            user.hasActiveSubscription(),
                            entity.getBillingSource(),
                            entity.getTierPreference()
                        ));
                    });
            });
    }

    public Mono<Void> ensureBalanceAvailable(ApiKeyPrincipal principal) {
        if (principal == null || !principal.requiresBalance()) {
            return Mono.empty();
        }
        BigDecimal balance = principal.balance() == null ? BigDecimal.ZERO : principal.balance();
        if (principal.usesCodingPlan()) {
            return Mono.empty(); // The locked reservation selects eligible terms, including queued renewals.
        }
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE", "Account balance is insufficient"));
        }
        return Mono.empty();
    }

    public Mono<Void> reserveUsage(ApiKeyPrincipal principal, String requestId, BigDecimal credits, String pricing) {
        if (principal == null || "anonymous".equals(principal.keyId())) return Mono.empty();
        return template.getDatabaseClient().sql("""
            SELECT starry_api.reserve_api_usage(:keyId, :requestHash, :requestId, :credits, CAST(:pricing AS jsonb)) AS status
            """).bind("keyId", principal.keyId()).bind("requestHash", usageHash(principal, requestId))
            .bind("requestId", requestId).bind("credits", credits).bind("pricing", pricing)
            .map((row, metadata) -> row.get("status", String.class)).one()
            .flatMap(ApiKeyService::billingOutcome);
    }

    public Mono<Void> markUsageDispatched(ApiKeyPrincipal principal, String requestId) {
        if (principal == null || "anonymous".equals(principal.keyId())) return Mono.empty();
        return template.getDatabaseClient().sql("""
            UPDATE billing_reservations SET status = 'DISPATCHED', updated_at = clock_timestamp()
            WHERE request_hash = :hash AND status IN ('RESERVED', 'DISPATCHED') AND expires_at > clock_timestamp()
            """).bind("hash", usageHash(principal, requestId)).fetch().rowsUpdated()
            .flatMap(count -> count == 1 ? Mono.empty() : Mono.error(new ApiException(
                HttpStatus.CONFLICT, "BILLING_RESERVATION_EXPIRED", "Billing reservation expired before dispatch")));
    }

    /**
     * {@link #markUsageDispatched} for a request served from source tiers (design 4.6): it also records the tier the
     * attempt went to, so a reservation that is settled later by an administrator, or whose lease ran out, can still be
     * attributed, and it renews the lease. Each dispatch may take as long as the upstream client retries
     * ({@link #leaseWindow}); the lease is renewed to cover it and never shortened, and never runs past an hour after the
     * reservation was made, which no request is allowed to outlive. A dispatch that may take longer renews it again while
     * it runs ({@link #renewDispatchLease}). The sweeper turns an expired lease into a review.
     *
     * @return the lease; empty for the anonymous caller, who has no reservation
     */
    public Mono<DispatchLease> markUsageDispatchedTiered(ApiKeyPrincipal principal, String requestId, String tierRef) {
        if (principal == null || "anonymous".equals(principal.keyId())) return Mono.empty();
        Duration window = leaseWindow();
        return template.getDatabaseClient().sql("""
            UPDATE billing_reservations
            SET status = 'DISPATCHED',
                last_dispatched_tier_ref = :tierRef,
                last_dispatched_at = clock_timestamp(),
                expires_at = LEAST(created_at + INTERVAL '60 minutes',
                                   GREATEST(expires_at, clock_timestamp() + make_interval(secs => CAST(:windowSeconds AS double precision)))),
                updated_at = clock_timestamp()
            WHERE request_hash = :hash AND status IN ('RESERVED', 'DISPATCHED') AND expires_at > clock_timestamp()
            RETURNING CAST(FLOOR(EXTRACT(EPOCH FROM (created_at + INTERVAL '60 minutes' - clock_timestamp()))) AS BIGINT) AS seconds_to_cap
            """).bind("hash", usageHash(principal, requestId)).bind("tierRef", tierRef).bind("windowSeconds", window.toSeconds())
            .map((row, metadata) -> row.get("seconds_to_cap", Long.class)).one()
            .map(secondsToCap -> new DispatchLease(window, Duration.ofSeconds(Math.max(0, secondsToCap))))
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.CONFLICT, "BILLING_RESERVATION_EXPIRED", "Billing reservation expired before dispatch")));
    }

    /**
     * How long a lease on a dispatch of a tiered request lasts from the time it is made or renewed: the upstream client's
     * request timeout for each of its tries, and a minute over, but at least what a reservation is leased for to begin
     * with. A cross-protocol dispatch adds its own time limit to this when it exists.
     */
    Duration leaseWindow() {
        Duration longestDispatch = properties.getRequestTimeout()
            .multipliedBy(Math.max(0, properties.getMaxRetries()) + 1L)
            .plus(LEASE_SLACK);
        return longestDispatch.compareTo(BASE_LEASE) > 0 ? longestDispatch : BASE_LEASE;
    }

    /**
     * Renews the lease of a reservation whose dispatch is still running (design 4.6), to the window from now: never
     * shortened, and never past an hour after the reservation was made, as {@link #markUsageDispatchedTiered} does. Only
     * a reservation that is still dispatched is renewed. One that the sweeper already turned into a review, or that was
     * settled or released, is left as it is. A lease that has run out is renewed all the same while the sweeper has not
     * got to it, which is best effort: the sweeper selects the expired reservations and releases them in a second
     * statement that does not look at the expiry again, so one it has already picked up can still become a review. The
     * settlement at the end of the stream clears that review.
     *
     * @return whether the lease was renewed; false when the reservation is no longer dispatched, or for the anonymous
     *     caller, who has none
     */
    public Mono<Boolean> renewDispatchLease(ApiKeyPrincipal principal, String requestId) {
        if (principal == null || "anonymous".equals(principal.keyId())) return Mono.just(false);
        return template.getDatabaseClient().sql("""
            UPDATE billing_reservations
            SET expires_at = LEAST(created_at + INTERVAL '60 minutes',
                                   GREATEST(expires_at, clock_timestamp() + make_interval(secs => CAST(:windowSeconds AS double precision)))),
                updated_at = clock_timestamp()
            WHERE request_hash = :hash AND status = 'DISPATCHED'
            """).bind("hash", usageHash(principal, requestId)).bind("windowSeconds", leaseWindow().toSeconds())
            .fetch().rowsUpdated().map(count -> count == 1);
    }

    /**
     * The lease a dispatch holds.
     *
     * @param window how long the dispatch may run, which the lease was renewed to cover
     * @param untilCap how long remains until the hour past which a reservation can no longer be leased
     */
    public record DispatchLease(Duration window, Duration untilCap) {

        private static final Duration LEAST_PERIOD = Duration.ofSeconds(1);
        private static final int RENEWALS_PER_WINDOW = 4;

        /**
         * How often the lease of a running dispatch is renewed: a quarter of the window. A renewal that succeeds leaves the
         * lease a window from then, so two renewals in a row can fail and the third still comes a quarter of the window
         * before the lease runs out (at a third of the window it would come at the moment it runs out).
         */
        public Duration renewalPeriod() {
            Duration period = window.dividedBy(RENEWALS_PER_WINDOW);
            return period.compareTo(LEAST_PERIOD) < 0 ? LEAST_PERIOD : period;
        }

        /**
         * How many renewals can still extend the lease: those that fall within the time left until the hour past which a
         * reservation cannot be leased. The last of them takes the lease to that hour.
         */
        public long renewalsUntilCap() {
            return untilCap.dividedBy(renewalPeriod());
        }
    }

    public Mono<Void> releaseUsage(ApiKeyPrincipal principal, String requestId, boolean definitive, String reason) {
        if (principal == null || "anonymous".equals(principal.keyId())) return Mono.empty();
        return template.getDatabaseClient().sql("""
            SELECT starry_api.release_api_reservation(:hash, :definitive, :reason)
            """).bind("hash", usageHash(principal, requestId)).bind("definitive", definitive)
            .bind("reason", reason).fetch().one().then();
    }

    public Mono<Void> accountUsage(ApiKeyPrincipal principal, String requestId, BigDecimal credits, long inputTokens, long outputTokens) {
        if (principal == null || "anonymous".equals(principal.keyId())) return Mono.empty();
        if (requestId == null || requestId.isBlank()) {
            return Mono.error(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REQUEST_ID_REQUIRED", "Request ID is required for usage accounting"));
        }
        return template.getDatabaseClient().sql("""
            SELECT starry_api.settle_api_usage(:requestHash, :credits, :inputTokens, :outputTokens) AS accounting_status
            """).bind("requestHash", usageHash(principal, requestId))
            .bind("credits", nonNegative(credits)).bind("inputTokens", Math.max(0, inputTokens))
            .bind("outputTokens", Math.max(0, outputTokens)).fetch().one()
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "USAGE_ACCOUNTING_FAILED", "Usage accounting did not return an outcome")))
            .flatMap(row -> billingOutcome(String.valueOf(row.get("accounting_status"))));
    }

    /**
     * {@link #accountUsage} for a request served from source tiers (design 5.3): the tier that served, its level and its
     * price factor are written to the reservation in the same transaction as the charge. Settling again with the same
     * tier is the same outcome; with another it is a conflict.
     */
    public Mono<Void> accountUsageServed(
        ApiKeyPrincipal principal,
        String requestId,
        BigDecimal credits,
        long inputTokens,
        long outputTokens,
        String tierRef,
        int level,
        BigDecimal priceFactor
    ) {
        if (principal == null || "anonymous".equals(principal.keyId())) return Mono.empty();
        if (requestId == null || requestId.isBlank()) {
            return Mono.error(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REQUEST_ID_REQUIRED", "Request ID is required for usage accounting"));
        }
        // The level is a Java int on purpose: R2DBC binds it as int4, which the function takes (INTEGER) and a SMALLINT
        // parameter would not resolve.
        return template.getDatabaseClient().sql("""
            SELECT starry_api.settle_api_usage_served(:requestHash, :credits, :inputTokens, :outputTokens, :tierRef, :level, :priceFactor) AS accounting_status
            """).bind("requestHash", usageHash(principal, requestId))
            .bind("credits", nonNegative(credits)).bind("inputTokens", Math.max(0, inputTokens))
            .bind("outputTokens", Math.max(0, outputTokens)).bind("tierRef", tierRef).bind("level", level)
            .bind("priceFactor", priceFactor).fetch().one()
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "USAGE_ACCOUNTING_FAILED", "Usage accounting did not return an outcome")))
            .flatMap(row -> billingOutcome(String.valueOf(row.get("accounting_status"))));
    }

    static String usageHash(ApiKeyPrincipal principal, String requestId) {
        return Hashing.sha256(principal.keyId().length() + ":" + principal.keyId() + requestId.trim());
    }

    public static Mono<Void> billingOutcome(String status) {
        return switch (status) {
            case "RESERVED", "ACCOUNTED", "ALREADY_ACCOUNTED" -> Mono.empty();
            case "INSUFFICIENT_BALANCE" -> Mono.error(new ApiException(HttpStatus.PAYMENT_REQUIRED, status, "余额不足以支付本次请求的预估费用"));
            case "CODING_PLAN_QUOTA_EXCEEDED" -> Mono.error(new ApiException(HttpStatus.PAYMENT_REQUIRED, status, "Coding Plan 可用额度不足或不覆盖此模型分类"));
            case "CREDITS_LIMIT_EXCEEDED" -> Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS, status, "API key credits limit exceeded"));
            case "KEY_NOT_FOUND", "KEY_DISABLED" -> Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_DISABLED", "API key is no longer available"));
            case "OWNER_NOT_FOUND", "OWNER_DISABLED" -> Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_OWNER_DISABLED", "API key owner is no longer available"));
            case "BILLING_REVIEW_REQUIRED" -> Mono.error(new ApiException(HttpStatus.CONFLICT, status, "存在待核对的请求费用，请联系管理员处理"));
            default -> Mono.error(new ApiException(HttpStatus.CONFLICT, status, "Billing request could not be completed"));
        };
    }

    private boolean isAdmin(AdminPrincipal principal) {
        return principal != null && ADMIN_ROLE.equalsIgnoreCase(principal.role());
    }

    private String normalizeModelProvider(String modelProvider) {
        String normalized = PlatformModelCatalogSupport.canonicalCatalogProvider(modelProvider);
        if (normalized == null || normalized.isEmpty() || "*".equals(normalized)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "API_KEY_MODEL_PROVIDER_REQUIRED",
                "API key model provider is required"
            );
        }
        return modelCatalog.models().stream()
            .map(model -> model.provider())
            .filter(provider -> PlatformModelCatalogSupport.sameCatalogProvider(provider, normalized))
            .map(PlatformModelCatalogSupport::canonicalCatalogProvider)
            .findFirst()
            .orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "API_KEY_MODEL_PROVIDER_INVALID",
                "API key model provider does not exist in the model catalog"
            ));
    }

    private String normalizeBillingSource(String source) {
        String value = source == null ? "BALANCE" : source.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("BALANCE", "CODING_PLAN").contains(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BILLING_SOURCE_INVALID", "Choose BALANCE or CODING_PLAN");
        }
        return value;
    }

    /** The preference as it is stored; omitted or blank is {@code auto}. */
    private static String normalizeTierPreference(String preference) {
        return TierSelectorParser.storedPreference(preference).orElseThrow(() -> new ApiException(
            HttpStatus.BAD_REQUEST,
            "API_KEY_TIER_PREFERENCE_INVALID",
            "Choose auto, a tier identifier, L<level> or <level> (1 to 9), optionally followed by +"
        ));
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

    private Mono<UserBillingState> userBillingState(String userId, String modelProvider) {
        String providerJson = providerJson(modelProvider);
        String sql = """
            SELECT role,
                   balance,
                   enabled,
                   EXISTS (
                       SELECT 1
                       FROM coding_plan_subscriptions AS sub
                       INNER JOIN coding_plan_versions AS plan ON plan.plan_id = sub.plan_id AND plan.version = sub.plan_version
                       WHERE sub.user_id = admin_users.user_id
                         AND sub.status = 'ACTIVE'
                         AND sub.starts_at <= CURRENT_TIMESTAMP
                         AND sub.ends_at > CURRENT_TIMESTAMP
                         AND sub.quota_credits > sub.used_credits
                         AND (plan.allowed_providers IS NULL
                              OR (CAST(:providerJson AS jsonb) IS NOT NULL
                                  AND plan.allowed_providers @> CAST(:providerJson AS jsonb)))
                   ) AS has_active_subscription
            FROM admin_users
            WHERE user_id = :userId
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("userId", userId);
        spec = bindNullable(spec, "providerJson", providerJson, String.class);
        return spec
            .map((row, metadata) -> new UserBillingState(
                row.get("role", String.class),
                row.get("balance", BigDecimal.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                Boolean.TRUE.equals(row.get("has_active_subscription", Boolean.class))
            ))
            .one()
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_OWNER_NOT_FOUND", "API key owner does not exist")))
            .flatMap(user -> user.enabled()
                ? Mono.just(user)
                : Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_OWNER_DISABLED", "API key owner is disabled")));
    }

    private static String providerJson(String modelProvider) {
        String normalized = PlatformModelCatalogSupport.canonicalCatalogProvider(modelProvider);
        if (normalized == null) {
            normalized = "";
        } else {
            normalized = normalized.trim();
        }
        if (normalized.isEmpty() || "*".equals(normalized) || !PROVIDER_NAME.matcher(normalized).matches()) {
            return null;
        }
        return "[\"" + normalized + "\"]";
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

    private record UserBillingState(String role, BigDecimal balance, boolean enabled, boolean hasActiveSubscription) {
    }
}
