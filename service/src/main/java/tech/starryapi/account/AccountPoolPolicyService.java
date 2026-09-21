package tech.starryapi.account;

import io.r2dbc.spi.Row;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AccountPoolPolicyService implements ApplicationRunner {

    static final String PRIMARY_TIER = "primary";
    static final String SECONDARY_TIER = "secondary";

    private final AccountSettingsService settingsService;
    private final DatabaseClient databaseClient;
    private final ModelPoolKeyResolver keyResolver;
    private final Clock clock;

    private volatile PolicyRuntime runtimeState;
    private volatile Mono<Void> inFlightSettingsRefresh;
    private long settingsRefreshGeneration;
    private long settingsRefreshRequestGeneration;

    @Autowired
    public AccountPoolPolicyService(
        AccountSettingsService settingsService,
        DatabaseClient databaseClient,
        ModelPoolKeyResolver keyResolver
    ) {
        this(settingsService, databaseClient, keyResolver, Clock.systemUTC());
    }

    AccountPoolPolicyService(
        AccountSettingsService settingsService,
        DatabaseClient databaseClient,
        ModelPoolKeyResolver keyResolver,
        Clock clock
    ) {
        this.settingsService = settingsService;
        this.databaseClient = databaseClient;
        this.keyResolver = keyResolver;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.runtimeState = newRuntime(settingsService.defaultPoolPolicySettings());
    }

    public Mono<PoolEvaluation> evaluate() {
        return refreshSettings().then(Mono.fromSupplier(this::currentEvaluation));
    }

    @Override
    public void run(ApplicationArguments args) {
        refreshSettings().block();
    }

    public synchronized Mono<Void> refreshSettings() {
        settingsRefreshGeneration++;
        settingsRefreshRequestGeneration++;
        Mono<Void> current = inFlightSettingsRefresh;
        if (current != null) {
            return current;
        }
        Mono<Void> created = Mono.defer(this::runSettingsRefreshPass).cache();
        inFlightSettingsRefresh = created;
        return created;
    }

    private Mono<Void> runSettingsRefreshPass() {
        long generation;
        long requestGeneration;
        synchronized (this) {
            generation = settingsRefreshGeneration;
            requestGeneration = settingsRefreshRequestGeneration;
        }
        return settingsService.poolPolicySettings()
            .flatMap(settings -> reconcile(settings, generation).then())
            .then(Mono.defer(() -> continueOrFinishRefresh(requestGeneration)))
            .onErrorResume(error -> Mono.defer(() -> continueOrFailRefresh(requestGeneration, error)));
    }

    private synchronized Mono<Void> continueOrFinishRefresh(long completedRequestGeneration) {
        if (settingsRefreshRequestGeneration > completedRequestGeneration) {
            return runSettingsRefreshPass();
        }
        inFlightSettingsRefresh = null;
        return Mono.empty();
    }

    private synchronized Mono<Void> continueOrFailRefresh(long failedRequestGeneration, Throwable error) {
        if (settingsRefreshRequestGeneration > failedRequestGeneration) {
            return runSettingsRefreshPass();
        }
        inFlightSettingsRefresh = null;
        return Mono.error(error);
    }

    public boolean managesSetting(String settingKey) {
        return settingKey != null && settingKey.startsWith("account_pool_policy_");
    }

    public Mono<AccountDtos.AccountPoolStatusResponse> status(boolean retestRunning) {
        return status(retestRunning, new AccountPoolService.PoolAccountInventory(0, 0, List.of()));
    }

    public Mono<AccountDtos.AccountPoolStatusResponse> status(
        boolean retestRunning,
        Supplier<Mono<AccountPoolService.PoolAccountInventory>> inventoryLoader
    ) {
        return Mono.defer(() -> {
            PolicyRuntime initial = runtimeState;
            if (!enabled(initial.settings())) {
                return Mono.just(disabledStatus(initial.settings(), retestRunning));
            }
            Mono<AccountPoolService.PoolAccountInventory> inventory = inventoryLoader == null
                ? Mono.just(AccountPoolService.PoolAccountInventory.empty())
                : Mono.defer(inventoryLoader);
            return inventory.defaultIfEmpty(AccountPoolService.PoolAccountInventory.empty())
                .map(value -> {
                    PolicyRuntime current = runtimeState;
                    return enabled(current.settings())
                        ? status(current, retestRunning, value)
                        : disabledStatus(current.settings(), retestRunning);
                });
        });
    }

    public Mono<AccountDtos.AccountPoolStatusResponse> status(
        boolean retestRunning,
        AccountPoolService.PoolAccountInventory inventory
    ) {
        return Mono.fromSupplier(() -> {
            PolicyRuntime current = runtimeState;
            return enabled(current.settings())
                ? status(current, retestRunning, inventory)
                : disabledStatus(current.settings(), retestRunning);
        });
    }

    public synchronized void replaceSettings(AccountDtos.PoolPolicySettings settings) {
        if (settings == null) {
            return;
        }
        settingsRefreshGeneration++;
        applySettings(settings);
    }

    private PolicyRuntime applySettings(AccountDtos.PoolPolicySettings settings) {
        PolicyRuntime previous = runtimeState;
        boolean wasEnabled = enabled(previous.settings());
        boolean isEnabled = enabled(settings);
        UUID previousEpoch = previous.settings() == null ? null : previous.settings().circuitEpoch();
        if (wasEnabled != isEnabled || !Objects.equals(previousEpoch, settings.circuitEpoch())) {
            PolicyRuntime replacement = newRuntime(settings);
            runtimeState = replacement;
            return replacement;
        }
        PolicyRuntime replacement = new PolicyRuntime(
            settings,
            previous.circuitBreaker(),
            previous.observedPools()
        );
        runtimeState = replacement;
        return replacement;
    }

    public boolean isEnabled() {
        return enabled(runtimeState.settings());
    }

    public UUID currentCircuitEpoch() {
        AccountDtos.PoolPolicySettings settings = runtimeState.settings();
        return enabled(settings) ? settings.circuitEpoch() : null;
    }

    public ModelPoolKey poolKey(AccountPoolRequestContext context) {
        return poolKey(runtimeState, context);
    }

    public ModelPoolKey capturePoolKey(
        String requestedModel,
        String upstreamModel,
        String providerHint,
        UUID circuitEpoch
    ) {
        PolicyRuntime runtime = runtimeState;
        if (!enabled(runtime.settings())
            || circuitEpoch == null
            || !Objects.equals(circuitEpoch, runtime.settings().circuitEpoch())) {
            return null;
        }
        ModelPoolKey key = keyResolver.resolve(requestedModel, upstreamModel, providerHint);
        return key;
    }

    public boolean isActive(AccountPoolRequestContext context) {
        return matchesCurrentEpoch(runtimeState, context);
    }

    public String circuitKey(AccountPoolRequestContext context) {
        PolicyRuntime runtime = runtimeState;
        return matchesCurrentEpoch(runtime, context) ? poolKey(runtime, context).key() : null;
    }

    private ModelPoolKey poolKey(PolicyRuntime runtime, AccountPoolRequestContext context) {
        ModelPoolKey key = context != null && context.modelPoolKey() != null
            ? context.modelPoolKey()
            : keyResolver.resolve(
            context == null ? null : context.requestedModel(),
            context == null ? null : context.upstreamModel(),
            context == null ? null : context.providerHint()
        );
        return key;
    }

    public String affinityKey(AccountPoolRequestContext context, String sessionHint) {
        PolicyRuntime runtime = runtimeState;
        if (!matchesCurrentEpoch(runtime, context) || sessionHint == null || sessionHint.isBlank()) {
            return sessionHint;
        }
        return poolKey(runtime, context).key() + ':' + sessionHint;
    }

    public List<ProxyAccountEntity> applyTierPolicy(List<ProxyAccountEntity> accounts) {
        return applyTierPolicy(accounts, null);
    }

    public List<ProxyAccountEntity> applyTierPolicy(
        List<ProxyAccountEntity> accounts,
        AccountPoolRequestContext context
    ) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        PolicyRuntime runtime = runtimeState;
        AccountDtos.PoolPolicySettings settings = runtime.settings();
        if (!matchesCurrentEpoch(runtime, context)) {
            return accounts;
        }
        ModelPoolKey key = poolKey(runtime, context);
        String requestToken = context == null ? null : context.requestToken();
        AccountPoolCircuitBreaker.Decision decision;
        try {
            decision = runtime.circuitBreaker().select(key, requestToken, settings);
        } finally {
            remember(runtime, key);
        }
        if (decision.activeTier() == AccountPoolCircuitBreaker.ActiveTier.ALL) {
            return accounts;
        }
        String preferredTier = decision.activeTier() == AccountPoolCircuitBreaker.ActiveTier.SECONDARY
            ? SECONDARY_TIER
            : PRIMARY_TIER;
        List<ProxyAccountEntity> preferred = accounts.stream()
            .filter(account -> preferredTier.equals(normalizePoolTier(account.getPoolTier())))
            .toList();
        if (!preferred.isEmpty()) {
            return preferred;
        }
        if (decision.probe()) {
            runtime.circuitBreaker().cancelProbe(key, requestToken);
            return accounts.stream()
                .filter(account -> SECONDARY_TIER.equals(normalizePoolTier(account.getPoolTier())))
                .toList();
        }
        return decision.activeTier() == AccountPoolCircuitBreaker.ActiveTier.PRIMARY ? accounts : List.of();
    }

    public void cancelSelection(AccountPoolRequestContext context) {
        PolicyRuntime runtime = runtimeState;
        if (context == null || !matchesCurrentEpoch(runtime, context)) {
            return;
        }
        runtime.circuitBreaker().cancelProbe(poolKey(runtime, context), context.requestToken());
    }

    public long recordSuccess(AccountPoolRequestContext context, ProxyAccountEntity account) {
        PolicyRuntime runtime = runtimeState;
        if (!matchesCurrentEpoch(runtime, context) || account == null) {
            return 0;
        }
        ModelPoolKey key = poolKey(runtime, context);
        try {
            return runtime.circuitBreaker().recordSuccess(
                key,
                context.requestToken(),
                account.getPoolTier(),
                runtime.settings()
            );
        } finally {
            remember(runtime, key);
        }
    }

    public long recordFailure(AccountPoolRequestContext context, ProxyAccountEntity account) {
        PolicyRuntime runtime = runtimeState;
        if (!matchesCurrentEpoch(runtime, context) || account == null) {
            return 0;
        }
        ModelPoolKey key = poolKey(runtime, context);
        try {
            return runtime.circuitBreaker().recordFailure(
                key,
                context.requestToken(),
                account.getPoolTier(),
                runtime.settings()
            );
        } finally {
            remember(runtime, key);
        }
    }

    public void acknowledgeOutcome(AccountPoolRequestContext context, long outcomeId) {
        PolicyRuntime runtime = runtimeState;
        if (outcomeId <= 0 || !matchesCurrentEpoch(runtime, context)) {
            return;
        }
        runtime.circuitBreaker().acknowledge(poolKey(runtime, context), outcomeId);
    }

    public void markOutcomePersistenceFailed(AccountPoolRequestContext context, long outcomeId) {
        PolicyRuntime runtime = runtimeState;
        if (outcomeId <= 0 || !matchesCurrentEpoch(runtime, context)) {
            return;
        }
        runtime.circuitBreaker().persistenceFailed(poolKey(runtime, context), outcomeId);
    }

    public int effectiveWeight(ProxyAccountEntity account) {
        int base = normalizeWeight(account == null ? null : account.getPoolWeight());
        AccountDtos.PoolPolicySettings settings = runtimeState.settings();
        if (account == null
            || !Boolean.TRUE.equals(settings.enabled())
            || !Boolean.TRUE.equals(settings.autoWeightEnabled())) {
            return base;
        }
        int errors = Math.max(0, account.getErrorCount() == null ? 0 : account.getErrorCount());
        double multiplier = 1.0 / (1.0 + errors);
        if (account.getLastUsedAt() != null
            && account.getLastUsedAt().isAfter(clock.instant().minus(Duration.ofMinutes(5)))) {
            multiplier *= errors > 0 ? 0.75 : 1.05;
        }
        return Math.max(1, (int) Math.round(base * multiplier));
    }

    public synchronized void invalidateAllCaches() {
        settingsRefreshGeneration++;
    }

    private boolean matchesCurrentEpoch(PolicyRuntime runtime, AccountPoolRequestContext context) {
        AccountDtos.PoolPolicySettings settings = runtime.settings();
        if (!enabled(settings)) {
            return false;
        }
        return context == null || Objects.equals(context.circuitEpoch(), settings.circuitEpoch());
    }

    private AccountDtos.AccountPoolStatusResponse status(
        PolicyRuntime runtime,
        boolean retestRunning,
        AccountPoolService.PoolAccountInventory inventory
    ) {
        AccountDtos.PoolPolicySettings settings = runtime.settings();
        AccountPoolService.PoolAccountInventory safeInventory = inventory == null
            ? new AccountPoolService.PoolAccountInventory(0, 0, List.of())
            : inventory;
        Instant now = clock.instant();
        Instant windowStartedAt = effectiveWindowStart(settings, now);
        List<AccountPoolCircuitBreaker.Snapshot> snapshots = circuitSnapshots(runtime);
        Set<ModelPoolKey> pools = snapshots.stream()
            .map(AccountPoolCircuitBreaker.Snapshot::key)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, AccountCounts> accountCounts = accountCountsByProvider(pools, safeInventory.groups());
        List<AccountDtos.ModelPoolCircuitStatusResponse> modelPools = snapshots.stream()
            .map(snapshot -> modelPoolStatus(snapshot, accountCounts, windowStartedAt))
            .toList();

        long primaryRequests = modelPools.stream().mapToLong(AccountDtos.ModelPoolCircuitStatusResponse::primaryRequests).sum();
        long primaryFailures = modelPools.stream().mapToLong(AccountDtos.ModelPoolCircuitStatusResponse::primaryFailures).sum();
        double primaryFailureRate = primaryRequests <= 0
            ? 0.0
            : roundPercent(primaryFailures * 100.0 / primaryRequests);
        boolean anyOpen = modelPools.stream().anyMatch(pool -> !"CLOSED".equals(pool.state()));
        String activeTier = aggregateActiveTier(settings, modelPools);
        Instant lastProbeAt = modelPools.stream()
            .map(AccountDtos.ModelPoolCircuitStatusResponse::lastOutcomeAt)
            .filter(value -> value != null)
            .max(Instant::compareTo)
            .orElse(null);
        long halfOpenCompleted = modelPools.stream()
            .mapToLong(AccountDtos.ModelPoolCircuitStatusResponse::halfOpenCompleted)
            .sum();
        long halfOpenSucceeded = modelPools.stream()
            .mapToLong(AccountDtos.ModelPoolCircuitStatusResponse::halfOpenSucceeded)
            .sum();
        double recoveryRate = halfOpenCompleted <= 0
            ? 0.0
            : roundPercent(halfOpenSucceeded * 100.0 / halfOpenCompleted);

        return new AccountDtos.AccountPoolStatusResponse(
            settings.enabled(),
            safeInventory.accountCount(),
            safeInventory.availableAccountCount(),
            activeTier,
            anyOpen,
            primaryFailureRate,
            primaryRequests,
            primaryFailures,
            windowStartedAt,
            now,
            lastProbeAt,
            roundPercent(Math.max(0.0, 100.0 - recoveryRate)),
            recoveryRate,
            retestRunning,
            settings,
            List.of(),
            modelPools
        );
    }

    private AccountDtos.AccountPoolStatusResponse disabledStatus(
        AccountDtos.PoolPolicySettings settings,
        boolean retestRunning
    ) {
        return new AccountDtos.AccountPoolStatusResponse(
            false,
            0L,
            0L,
            "all",
            false,
            0.0,
            0L,
            0L,
            null,
            null,
            null,
            0.0,
            0.0,
            retestRunning,
            settings,
            List.of(),
            List.of()
        );
    }

    private AccountDtos.ModelPoolCircuitStatusResponse modelPoolStatus(
        AccountPoolCircuitBreaker.Snapshot snapshot,
        Map<String, AccountCounts> accountCounts,
        Instant windowStartedAt
    ) {
        ModelPoolKey key = snapshot.key();
        AccountCounts counts = accountCounts.getOrDefault(key.provider(), AccountCounts.EMPTY);
        return new AccountDtos.ModelPoolCircuitStatusResponse(
            key.key(),
            key.providerLabel(),
            key.familyLabel(),
            keyResolver.representativeModel(key).orElse(null),
            snapshot.state().name(),
            snapshot.activeTier().name().toLowerCase(Locale.ROOT),
            snapshot.primary().requests(),
            snapshot.primary().failures(),
            snapshot.primary().failureRatePercent(),
            snapshot.secondary().requests(),
            snapshot.secondary().failures(),
            snapshot.secondary().failureRatePercent(),
            counts.primary(),
            counts.secondary(),
            windowStartedAt,
            snapshot.lastTransitionAt(),
            snapshot.openedAt(),
            snapshot.nextProbeAt(),
            snapshot.lastOutcomeAt(),
            snapshot.halfOpenInFlight(),
            snapshot.halfOpenCompleted(),
            snapshot.halfOpenSucceeded()
        );
    }

    private Map<String, AccountCounts> accountCountsByProvider(
        Collection<ModelPoolKey> pools,
        Collection<AccountPoolService.PoolAccountGroup> groups
    ) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        Set<String> providers = new LinkedHashSet<>();
        pools.forEach(pool -> providers.add(pool.provider()));
        Map<String, AccountCounts> counts = new HashMap<>();
        for (AccountPoolService.PoolAccountGroup group : groups) {
            if (group == null || group.accountCount() <= 0) {
                continue;
            }
            ProxyAccountEntity account = new ProxyAccountEntity();
            account.setProvider(group.provider());
            account.setAuthMethod(group.authMethod());
            account.setBaseUrl(group.baseUrl());
            boolean secondary = SECONDARY_TIER.equals(normalizePoolTier(group.poolTier()));
            int groupCount = safeAccountCount(group.accountCount());
            for (String provider : providers) {
                if (supportsPoolProvider(account, provider)) {
                    counts.merge(provider, AccountCounts.of(secondary, groupCount), AccountCounts::plus);
                }
            }
        }
        return Map.copyOf(counts);
    }

    private int safeAccountCount(long count) {
        return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, count);
    }

    private boolean supportsPoolProvider(ProxyAccountEntity account, String provider) {
        return switch (provider) {
            case "deepseek" -> AccountPlatforms.isDeepSeek(account);
            case "openai", "openai-compatible" -> AccountPlatforms.isOpenAi(account);
            case "anthropic" -> AccountPlatforms.isAnthropicApiKey(account)
                || AccountPlatforms.isKiroCompatible(account);
            case "google", "kiro", "unknown" -> AccountPlatforms.isKiroCompatible(account);
            default -> true;
        };
    }

    private String aggregateActiveTier(
        AccountDtos.PoolPolicySettings settings,
        List<AccountDtos.ModelPoolCircuitStatusResponse> pools
    ) {
        if (!Boolean.TRUE.equals(settings.enabled())) {
            return "all";
        }
        Set<String> tiers = pools.stream()
            .map(AccountDtos.ModelPoolCircuitStatusResponse::activeTier)
            .collect(java.util.stream.Collectors.toSet());
        return tiers.size() <= 1 ? tiers.stream().findFirst().orElse(PRIMARY_TIER) : "mixed";
    }

    private Flux<CircuitWindowMetrics> recentMetrics(Instant since, Instant until, UUID circuitEpoch) {
        String sql = """
            SELECT
                circuit_key,
                COUNT(*) FILTER (WHERE circuit_tier = 'primary') AS primary_requests,
                COUNT(*) FILTER (WHERE circuit_tier = 'primary' AND NOT upstream_success) AS primary_failures,
                COUNT(*) FILTER (WHERE circuit_tier = 'secondary') AS secondary_requests,
                COUNT(*) FILTER (WHERE circuit_tier = 'secondary' AND NOT upstream_success) AS secondary_failures
            FROM request_logs
            WHERE circuit_epoch = :circuitEpoch
              AND created_at >= :since
              AND created_at < :until
              AND circuit_key IS NOT NULL
              AND upstream_success IS NOT NULL
            GROUP BY circuit_key
            """;
        return databaseClient.sql(sql)
            .bind("circuitEpoch", circuitEpoch)
            .bind("since", since)
            .bind("until", until)
            .map((row, metadata) -> new CircuitWindowMetrics(
                stringValue(row, "circuit_key"),
                longValue(row, "primary_requests"),
                longValue(row, "primary_failures"),
                longValue(row, "secondary_requests"),
                longValue(row, "secondary_failures")
            ))
            .all();
    }

    private Mono<PoolEvaluation> reconcile(
        AccountDtos.PoolPolicySettings settings,
        long expectedGeneration
    ) {
        if (!Boolean.TRUE.equals(settings.enabled())) {
            return Mono.fromSupplier(() -> applyDisabledSettings(settings, expectedGeneration));
        }
        if (settings.circuitEpoch() == null) {
            return Mono.error(new IllegalStateException("Enabled account pool policy is missing its circuit epoch"));
        }
        Instant evaluatedAt = clock.instant();
        Instant since = effectiveWindowStart(settings, evaluatedAt);
        PolicyRuntime queryRuntime = runtimeState;
        AccountPoolCircuitBreaker queryCircuitBreaker = queryRuntime.circuitBreaker();
        long acknowledgedThrough = sameEpoch(queryRuntime.settings(), settings)
            ? queryCircuitBreaker.beginExternalEvaluation()
            : 0L;
        return recentMetrics(since, evaluatedAt, settings.circuitEpoch())
            .collectList()
            .map(metricsByPool -> applyMetrics(
                settings,
                metricsByPool,
                evaluatedAt,
                acknowledgedThrough,
                queryCircuitBreaker,
                expectedGeneration
            ));
    }

    private synchronized PoolEvaluation applyDisabledSettings(
        AccountDtos.PoolPolicySettings settings,
        long expectedGeneration
    ) {
        if (settingsRefreshGeneration != expectedGeneration) {
            return currentEvaluation();
        }
        applySettings(settings);
        return new PoolEvaluation(settings, false, false, new PoolMetrics(0, 0, 0.0));
    }

    private synchronized PoolEvaluation applyMetrics(
        AccountDtos.PoolPolicySettings settings,
        List<CircuitWindowMetrics> metricsByPool,
        Instant evaluatedAt,
        long acknowledgedThrough,
        AccountPoolCircuitBreaker queryCircuitBreaker,
        long expectedGeneration
    ) {
        if (settingsRefreshGeneration != expectedGeneration) {
            return currentEvaluation();
        }
        PolicyRuntime runtime = applySettings(settings);
        long effectiveAcknowledgedThrough = runtime.circuitBreaker() == queryCircuitBreaker
            ? acknowledgedThrough
            : 0L;
        Set<String> evaluatedKeys = new LinkedHashSet<>();
        for (CircuitWindowMetrics metrics : metricsByPool) {
            ModelPoolKey key = keyFor(runtime, metrics.circuitKey());
            evaluatedKeys.add(key.key());
            evaluateCircuit(runtime, key, metrics, effectiveAcknowledgedThrough, evaluatedAt, settings);
        }
        for (ModelPoolKey key : List.copyOf(runtime.observedPools().values())) {
            if (evaluatedKeys.add(key.key())) {
                evaluateCircuit(
                    runtime,
                    key,
                    new CircuitWindowMetrics(key.key(), 0, 0, 0, 0),
                    effectiveAcknowledgedThrough,
                    evaluatedAt,
                    settings
                );
            }
        }
        return evaluation(runtime);
    }

    private synchronized PoolEvaluation currentEvaluation() {
        PolicyRuntime runtime = runtimeState;
        AccountDtos.PoolPolicySettings settings = runtime.settings();
        return enabled(settings)
            ? evaluation(runtime)
            : new PoolEvaluation(settings, false, false, new PoolMetrics(0, 0, 0.0));
    }

    private Instant effectiveWindowStart(AccountDtos.PoolPolicySettings settings, Instant evaluatedAt) {
        Instant windowStart = evaluatedAt.minus(Duration.ofMinutes(settings.windowMinutes()));
        Instant activationStartedAt = settings.activationStartedAt();
        return activationStartedAt != null && activationStartedAt.isAfter(windowStart)
            ? activationStartedAt
            : windowStart;
    }

    private PoolEvaluation evaluation(PolicyRuntime runtime) {
        AccountDtos.PoolPolicySettings settings = runtime.settings();
        List<AccountPoolCircuitBreaker.Snapshot> snapshots = circuitSnapshots(runtime);
        boolean anyOpen = snapshots.stream().anyMatch(snapshot -> snapshot.state() != AccountPoolCircuitBreaker.State.CLOSED);
        long requests = snapshots.stream().mapToLong(snapshot -> snapshot.primary().requests()).sum();
        long failures = snapshots.stream().mapToLong(snapshot -> snapshot.primary().failures()).sum();
        double rate = requests <= 0 ? 0.0 : roundPercent(failures * 100.0 / requests);
        return new PoolEvaluation(settings, false, anyOpen, new PoolMetrics(requests, failures, rate));
    }

    private List<AccountPoolCircuitBreaker.Snapshot> circuitSnapshots(PolicyRuntime runtime) {
        List<AccountPoolCircuitBreaker.Snapshot> snapshots = runtime.circuitBreaker().snapshots(
            runtime.settings(),
            keyResolver.knownPools()
        );
        Set<String> retainedKeys = snapshots.stream()
            .map(snapshot -> snapshot.key().key())
            .collect(java.util.stream.Collectors.toSet());
        runtime.observedPools().keySet().removeIf(key -> !retainedKeys.contains(key));
        runtime.circuitBreaker().activeKeys().forEach(key -> remember(runtime, key));
        return snapshots;
    }

    private ModelPoolKey keyFor(PolicyRuntime runtime, String serializedKey) {
        if (serializedKey == null || serializedKey.isBlank()) {
            return poolKey(runtime, null);
        }
        ModelPoolKey observed = runtime.observedPools().get(serializedKey);
        if (observed != null) {
            return observed;
        }
        for (ModelPoolKey known : keyResolver.knownPools()) {
            if (serializedKey.equals(known.key())) {
                return known;
            }
        }
        String[] parts = serializedKey.split(":", 2);
        if (parts.length == 2) {
            try {
                ModelPoolKey restored = "other".equals(parts[1])
                    ? keyResolver.resolve(null, null, parts[0])
                    : new ModelPoolKey(parts[0], parts[1], parts[0], parts[1]);
                return restored;
            } catch (IllegalArgumentException ignored) {
                // Malformed historical dimensions are isolated in the bounded unknown pool.
            }
        }
        return poolKey(runtime, null);
    }

    private PolicyRuntime newRuntime(AccountDtos.PoolPolicySettings settings) {
        AccountPoolCircuitBreaker circuitBreaker = new AccountPoolCircuitBreaker(clock);
        if (enabled(settings)) {
            // Reserve generation zero before publication so post-switch outcomes cannot be
            // mistaken for rows covered by an evaluation that started on the previous epoch.
            circuitBreaker.beginExternalEvaluation();
        }
        ConcurrentHashMap<String, ModelPoolKey> observedPools = new ConcurrentHashMap<>();
        return new PolicyRuntime(settings, circuitBreaker, observedPools);
    }

    private boolean sameEpoch(
        AccountDtos.PoolPolicySettings left,
        AccountDtos.PoolPolicySettings right
    ) {
        return enabled(left)
            && enabled(right)
            && Objects.equals(left.circuitEpoch(), right.circuitEpoch());
    }

    private void evaluateCircuit(
        PolicyRuntime runtime,
        ModelPoolKey key,
        CircuitWindowMetrics metrics,
        long acknowledgedThrough,
        Instant evaluatedAt,
        AccountDtos.PoolPolicySettings settings
    ) {
        try {
            runtime.circuitBreaker().evaluate(
                key,
                metrics.primaryRequests(),
                metrics.primaryFailures(),
                metrics.secondaryRequests(),
                metrics.secondaryFailures(),
                acknowledgedThrough,
                evaluatedAt,
                settings
            );
        } finally {
            remember(runtime, key);
        }
    }

    private void remember(PolicyRuntime runtime, ModelPoolKey key) {
        runtime.observedPools().putIfAbsent(key.key(), key);
    }

    private boolean enabled(AccountDtos.PoolPolicySettings settings) {
        return settings != null
            && Boolean.TRUE.equals(settings.enabled())
            && settings.circuitEpoch() != null;
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

    private static String stringValue(Row row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
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

    private record CircuitWindowMetrics(
        String circuitKey,
        long primaryRequests,
        long primaryFailures,
        long secondaryRequests,
        long secondaryFailures
    ) {
    }

    private record AccountCounts(int primary, int secondary) {

        private static final AccountCounts EMPTY = new AccountCounts(0, 0);

        private static AccountCounts of(boolean secondary, int count) {
            return secondary ? new AccountCounts(0, count) : new AccountCounts(count, 0);
        }

        private AccountCounts plus(AccountCounts other) {
            return new AccountCounts(
                saturatedCount(primary, other.primary),
                saturatedCount(secondary, other.secondary)
            );
        }

        private static int saturatedCount(int left, int right) {
            long total = (long) left + right;
            return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        }
    }

    private record PolicyRuntime(
        AccountDtos.PoolPolicySettings settings,
        AccountPoolCircuitBreaker circuitBreaker,
        ConcurrentHashMap<String, ModelPoolKey> observedPools
    ) {
    }
}
