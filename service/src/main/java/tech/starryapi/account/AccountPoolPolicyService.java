package tech.starryapi.account;

import io.r2dbc.spi.Row;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AccountPoolPolicyService {

    static final String PRIMARY_TIER = "primary";
    static final String SECONDARY_TIER = "secondary";

    private final AccountSettingsService settingsService;
    private final DatabaseClient databaseClient;

    private volatile boolean primaryDisabled = false;
    private volatile Instant lastEvaluatedAt;
    private volatile Instant lastRetestAt;
    private volatile double lastRetestFailureRatePercent = 0.0;
    private volatile double lastRetestRecoveryRatePercent = 0.0;
    private volatile long primaryRequests = 0;
    private volatile long primaryFailures = 0;
    private volatile double primaryFailureRatePercent = 0.0;
    private volatile AccountDtos.PoolPolicySettings cachedSettings;

    public AccountPoolPolicyService(AccountSettingsService settingsService, DatabaseClient databaseClient) {
        this.settingsService = settingsService;
        this.databaseClient = databaseClient;
        this.cachedSettings = settingsService.defaultPoolPolicySettings();
    }

    public Mono<PoolEvaluation> evaluate() {
        return settingsService.poolPolicySettings()
            .doOnNext(settings -> cachedSettings = settings)
            .flatMap(settings -> recentPrimaryMetrics(settings)
                .map(metrics -> evaluate(settings, metrics)));
    }

    public Mono<AccountDtos.AccountPoolStatusResponse> status(boolean retestRunning) {
        return settingsService.poolPolicySettings()
            .doOnNext(settings -> cachedSettings = settings)
            .flatMap(settings -> {
                Instant windowStartedAt = Instant.now().minus(Duration.ofMinutes(settings.windowMinutes()));
                return failureRanking(windowStartedAt, settings)
                    .collectList()
                    .map(ranking -> new AccountDtos.AccountPoolStatusResponse(
                        settings.enabled(),
                        activeTier(),
                        primaryDisabled,
                        primaryFailureRatePercent,
                        primaryRequests,
                        primaryFailures,
                        windowStartedAt,
                        lastEvaluatedAt,
                        lastRetestAt,
                        lastRetestFailureRatePercent,
                        lastRetestRecoveryRatePercent,
                        retestRunning,
                        settings,
                        ranking
                    ));
            });
    }

    public List<ProxyAccountEntity> applyTierPolicy(List<ProxyAccountEntity> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        if (!Boolean.TRUE.equals(cachedSettings.enabled())) {
            return accounts;
        }
        String preferredTier = primaryDisabled ? SECONDARY_TIER : PRIMARY_TIER;
        List<ProxyAccountEntity> preferred = accounts.stream()
            .filter(account -> preferredTier.equals(normalizePoolTier(account.getPoolTier())))
            .toList();
        if (primaryDisabled || !preferred.isEmpty()) {
            return preferred;
        }
        return accounts;
    }

    public int effectiveWeight(ProxyAccountEntity account) {
        int base = normalizeWeight(account == null ? null : account.getPoolWeight());
        if (account == null || !Boolean.TRUE.equals(cachedSettings.autoWeightEnabled())) {
            return base;
        }
        int errors = Math.max(0, account.getErrorCount() == null ? 0 : account.getErrorCount());
        double multiplier = 1.0 / (1.0 + errors);
        if (account.getLastUsedAt() != null && account.getLastUsedAt().isAfter(Instant.now().minus(Duration.ofMinutes(5)))) {
            multiplier *= errors > 0 ? 0.75 : 1.05;
        }
        return Math.max(1, (int) Math.round(base * multiplier));
    }

    public void markRetestStarted() {
        lastRetestAt = Instant.now();
    }

    public void markRetestCompleted(double retestRecoveryRatePercent, long retestSamples) {
        lastRetestAt = Instant.now();
        lastRetestRecoveryRatePercent = roundPercent(retestRecoveryRatePercent);
        lastRetestFailureRatePercent = roundPercent(Math.max(0.0, 100.0 - retestRecoveryRatePercent));
        AccountDtos.PoolPolicySettings settings = cachedSettings;
        if (retestSamples <= 0 || !Boolean.TRUE.equals(settings.enabled())) {
            return;
        }
        if (primaryDisabled && retestRecoveryRatePercent >= settings.recoveryThresholdPercent()) {
            primaryDisabled = false;
            return;
        }
        if (!primaryDisabled && lastRetestFailureRatePercent >= settings.failoverThresholdPercent()) {
            primaryDisabled = true;
        }
    }

    public void invalidateAllCaches() {
        cachedSettings = settingsService.defaultPoolPolicySettings();
        primaryDisabled = false;
        lastEvaluatedAt = null;
        lastRetestAt = null;
        lastRetestFailureRatePercent = 0.0;
        lastRetestRecoveryRatePercent = 0.0;
        primaryRequests = 0;
        primaryFailures = 0;
        primaryFailureRatePercent = 0.0;
    }

    private PoolEvaluation evaluate(AccountDtos.PoolPolicySettings settings, PoolMetrics metrics) {
        lastEvaluatedAt = Instant.now();
        primaryRequests = metrics.requests();
        primaryFailures = metrics.failures();
        primaryFailureRatePercent = metrics.failureRatePercent();

        if (!Boolean.TRUE.equals(settings.enabled()) || metrics.requests() < settings.minSampleSize()) {
            return new PoolEvaluation(settings, false, primaryDisabled, metrics);
        }

        boolean retestRecommended = false;
        if (primaryDisabled) {
            retestRecommended = retestDue(settings);
        } else if (metrics.failureRatePercent() >= settings.failoverThresholdPercent()) {
            primaryDisabled = true;
            retestRecommended = true;
        } else if (metrics.failureRatePercent() >= settings.retestThresholdPercent()) {
            retestRecommended = true;
        }
        return new PoolEvaluation(settings, retestRecommended, primaryDisabled, metrics);
    }

    private boolean retestDue(AccountDtos.PoolPolicySettings settings) {
        return lastRetestAt == null
            || lastRetestAt.plus(Duration.ofMinutes(settings.retestIntervalMinutes())).isBefore(Instant.now());
    }

    private Mono<PoolMetrics> recentPrimaryMetrics(AccountDtos.PoolPolicySettings settings) {
        Instant since = Instant.now().minus(Duration.ofMinutes(settings.windowMinutes()));
        String sql = """
            SELECT
                COUNT(*) AS requests,
                COUNT(*) FILTER (WHERE NOT l.success) AS failures
            FROM request_logs l
            INNER JOIN proxy_accounts a ON a.account_id = l.account_id
            WHERE l.created_at >= :since
              AND COALESCE(NULLIF(a.pool_tier, ''), 'primary') = 'primary'
            """;
        return databaseClient.sql(sql)
            .bind("since", since)
            .map((row, metadata) -> poolMetrics(row))
            .one()
            .defaultIfEmpty(new PoolMetrics(0, 0, 0.0));
    }

    private Flux<AccountDtos.AccountFailureRankingResponse> failureRanking(
        Instant since,
        AccountDtos.PoolPolicySettings settings
    ) {
        String sql = """
            WITH account_metrics AS (
                SELECT
                    a.account_id,
                    MAX(a.email) AS email,
                    MAX(a.provider) AS provider,
                    MAX(a.auth_method) AS auth_method,
                    MAX(COALESCE(NULLIF(a.pool_tier, ''), 'primary')) AS pool_tier,
                    COUNT(l.id) AS requests,
                    COUNT(l.id) FILTER (WHERE NOT l.success) AS failures,
                    COALESCE(MAX(a.error_count), 0) AS error_count,
                    MAX(a.pool_weight) AS pool_weight,
                    MAX(l.created_at) FILTER (WHERE NOT l.success) AS last_failure_at
                FROM proxy_accounts a
                LEFT JOIN request_logs l ON l.account_id = a.account_id AND l.created_at >= :since
                GROUP BY a.account_id
            )
            SELECT *
            FROM account_metrics
            WHERE requests > 0 OR error_count > 0
            ORDER BY
                failures::numeric / NULLIF(requests, 0) DESC NULLS LAST,
                failures DESC,
                requests DESC
            LIMIT 20
            """;
        return databaseClient.sql(sql)
            .bind("since", since)
            .map((row, metadata) -> failureRanking(row, settings))
            .all();
    }

    private AccountDtos.AccountFailureRankingResponse failureRanking(Row row, AccountDtos.PoolPolicySettings settings) {
        long requests = longValue(row, "requests");
        long failures = longValue(row, "failures");
        int weight = normalizeWeight(intValue(row, "pool_weight"));
        int errors = intValue(row, "error_count");
        double rate = requests <= 0 ? 0.0 : failures * 100.0 / requests;
        return new AccountDtos.AccountFailureRankingResponse(
            stringValue(row, "account_id"),
            stringValue(row, "email"),
            stringValue(row, "provider"),
            stringValue(row, "auth_method"),
            normalizePoolTier(stringValue(row, "pool_tier")),
            requests,
            failures,
            roundPercent(rate),
            errors,
            weight,
            Boolean.TRUE.equals(settings.autoWeightEnabled()) ? Math.max(1, (int) Math.round(weight / (1.0 + Math.max(0, errors)))) : weight,
            instantValue(row, "last_failure_at")
        );
    }

    private PoolMetrics poolMetrics(Row row) {
        long requests = longValue(row, "requests");
        long failures = longValue(row, "failures");
        double rate = requests <= 0 ? 0.0 : failures * 100.0 / requests;
        return new PoolMetrics(requests, failures, roundPercent(rate));
    }

    private String activeTier() {
        if (!Boolean.TRUE.equals(cachedSettings.enabled())) {
            return "all";
        }
        return primaryDisabled ? SECONDARY_TIER : PRIMARY_TIER;
    }

    static String normalizePoolTier(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return SECONDARY_TIER.equals(normalized) || "2".equals(normalized) || "level2".equals(normalized)
            ? SECONDARY_TIER
            : PRIMARY_TIER;
    }

    static int normalizeWeight(Integer value) {
        int weight = value == null ? 100 : value;
        return Math.max(1, Math.min(10000, weight));
    }

    static double roundPercent(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static long longValue(Row row, String column) {
        Object value = row.get(column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return 0L;
    }

    private static int intValue(Row row, String column) {
        Object value = row.get(column);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return 0;
    }

    private static String stringValue(Row row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    private static Instant instantValue(Row row, String column) {
        Object value = row.get(column);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        return null;
    }

    public record PoolEvaluation(
        AccountDtos.PoolPolicySettings settings,
        boolean retestRecommended,
        boolean primaryDisabled,
        PoolMetrics metrics
    ) {
    }

    public record PoolMetrics(long requests, long failures, double failureRatePercent) {
    }
}
