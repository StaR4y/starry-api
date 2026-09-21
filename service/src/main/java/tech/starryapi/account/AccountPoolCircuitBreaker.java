package tech.starryapi.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class AccountPoolCircuitBreaker {

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    enum ActiveTier {
        ALL,
        PRIMARY,
        SECONDARY
    }

    private static final int MAX_HALF_OPEN_PROBES = 64;
    private static final Duration MIN_PROBE_LEASE = Duration.ofMinutes(2);
    private static final Duration MAX_PROBE_LEASE = Duration.ofMinutes(10);

    private final Clock clock;
    private final ConcurrentHashMap<ModelPoolKey, Circuit> circuits = new ConcurrentHashMap<>();
    private final AtomicLong outcomeSequence = new AtomicLong();
    private final AtomicLong acknowledgmentGeneration = new AtomicLong();

    AccountPoolCircuitBreaker(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    Decision select(
        ModelPoolKey key,
        String requestToken,
        AccountDtos.PoolPolicySettings settings
    ) {
        if (!enabled(settings)) {
            return new Decision(ActiveTier.ALL, false);
        }
        Instant now = clock.instant();
        while (true) {
            Circuit circuit = circuits.computeIfAbsent(key, ignored -> new Circuit());
            synchronized (circuit) {
                if (circuit.retired) {
                    circuits.remove(key, circuit);
                    continue;
                }
                circuit.prune(now, window(settings));
                advanceOpenCircuit(circuit, now, settings);
                if (circuit.state == State.CLOSED) {
                    return new Decision(ActiveTier.PRIMARY, false);
                }
                if (circuit.state == State.OPEN) {
                    return new Decision(ActiveTier.SECONDARY, false);
                }

                expireProbeLeases(circuit, now, settings);
                if (circuit.state == State.OPEN) {
                    return new Decision(ActiveTier.SECONDARY, false);
                }
                String token = usableToken(requestToken);
                if (token == null) {
                    return new Decision(ActiveTier.SECONDARY, false);
                }
                if (circuit.activeProbes.containsKey(token)) {
                    return new Decision(ActiveTier.PRIMARY, true);
                }
                if (circuit.probeRequestTokens.contains(token)) {
                    return new Decision(ActiveTier.SECONDARY, false);
                }
                int limit = probeLimit(settings);
                if (circuit.halfOpenAttempts >= limit || circuit.activeProbes.size() >= limit) {
                    return new Decision(ActiveTier.SECONDARY, false);
                }
                circuit.halfOpenAttempts++;
                circuit.probeRequestTokens.add(token);
                circuit.activeProbes.put(token, now.plus(probeLease(settings)));
                return new Decision(ActiveTier.PRIMARY, true);
            }
        }
    }

    void cancelProbe(ModelPoolKey key, String requestToken) {
        Circuit circuit = circuits.get(key);
        if (circuit == null) {
            return;
        }
        synchronized (circuit) {
            String token = usableToken(requestToken);
            if (circuit.state == State.HALF_OPEN && circuit.activeProbes.remove(token) != null) {
                circuit.probeRequestTokens.remove(token);
                circuit.halfOpenAttempts = Math.max(circuit.halfOpenCompleted, circuit.halfOpenAttempts - 1);
            }
        }
    }

    long recordSuccess(
        ModelPoolKey key,
        String requestToken,
        String accountTier,
        AccountDtos.PoolPolicySettings settings
    ) {
        return record(key, requestToken, accountTier, true, settings);
    }

    long recordFailure(
        ModelPoolKey key,
        String requestToken,
        String accountTier,
        AccountDtos.PoolPolicySettings settings
    ) {
        return record(key, requestToken, accountTier, false, settings);
    }

    void acknowledge(ModelPoolKey key, long outcomeId) {
        if (outcomeId <= 0) {
            return;
        }
        Circuit circuit = circuits.get(key);
        if (circuit == null) {
            return;
        }
        synchronized (circuit) {
            RollingMetrics metrics = circuit.primary.contains(outcomeId)
                ? circuit.primary
                : circuit.secondary.contains(outcomeId) ? circuit.secondary : null;
            if (metrics != null) {
                metrics.acknowledge(outcomeId, acknowledgmentGeneration.get());
            }
        }
    }

    void persistenceFailed(ModelPoolKey key, long outcomeId) {
        if (outcomeId <= 0) {
            return;
        }
        Circuit circuit = circuits.get(key);
        if (circuit == null) {
            return;
        }
        synchronized (circuit) {
            RollingMetrics metrics = circuit.primary.contains(outcomeId)
                ? circuit.primary
                : circuit.secondary.contains(outcomeId) ? circuit.secondary : null;
            if (metrics != null) {
                metrics.persistenceFailed(outcomeId);
            }
        }
    }

    void evaluate(
        ModelPoolKey key,
        long primaryRequests,
        long primaryFailures,
        long secondaryRequests,
        long secondaryFailures,
        long acknowledgedThrough,
        Instant evaluatedAt,
        AccountDtos.PoolPolicySettings settings
    ) {
        if (!enabled(settings)) {
            return;
        }
        Instant now = evaluatedAt == null ? clock.instant() : evaluatedAt;
        while (true) {
            Circuit circuit = circuits.computeIfAbsent(key, ignored -> new Circuit());
            synchronized (circuit) {
                if (circuit.retired) {
                    circuits.remove(key, circuit);
                    continue;
                }
                circuit.primary.discardAcknowledgedThrough(acknowledgedThrough);
                circuit.secondary.discardAcknowledgedThrough(acknowledgedThrough);
                circuit.externalPrimary = Metrics.of(primaryRequests, primaryFailures);
                circuit.externalSecondary = Metrics.of(secondaryRequests, secondaryFailures);
                circuit.externalEvaluatedAt = now;
                circuit.prune(now, window(settings));
                advanceOpenCircuit(circuit, now, settings);
                if (circuit.state != State.CLOSED || inRecoveryGracePeriod(circuit, now, settings)) {
                    return;
                }
                if (shouldOpen(combinedMetrics(circuit.externalPrimary, circuit.primary.metrics()), settings)) {
                    open(circuit, now, settings);
                }
                return;
            }
        }
    }

    long beginExternalEvaluation() {
        return acknowledgmentGeneration.getAndIncrement();
    }

    List<Snapshot> snapshots(
        AccountDtos.PoolPolicySettings settings,
        Collection<ModelPoolKey> knownPools
    ) {
        Set<ModelPoolKey> currentKnownPools = new HashSet<>();
        if (knownPools != null) {
            currentKnownPools.addAll(knownPools);
        }
        Set<ModelPoolKey> keys = new LinkedHashSet<>();
        keys.addAll(currentKnownPools);
        keys.addAll(circuits.keySet());
        Instant now = clock.instant();
        List<Snapshot> snapshots = new ArrayList<>(keys.size());
        for (ModelPoolKey key : keys) {
            Circuit circuit = circuits.get(key);
            if (circuit == null) {
                snapshots.add(emptySnapshot(key, settings));
                continue;
            }
            boolean evict = false;
            synchronized (circuit) {
                circuit.prune(now, window(settings));
                if (enabled(settings)) {
                    advanceOpenCircuit(circuit, now, settings);
                    expireProbeLeases(circuit, now, settings);
                }
                evict = circuit.retired || !currentKnownPools.contains(key) && circuit.evictable();
                if (!evict) {
                    Metrics primary = combinedMetrics(circuit.externalPrimary, circuit.primary.metrics());
                    Metrics secondary = combinedMetrics(circuit.externalSecondary, circuit.secondary.metrics());
                    State state = enabled(settings) ? circuit.state : State.CLOSED;
                    ActiveTier activeTier = !enabled(settings)
                        ? ActiveTier.ALL
                        : state == State.CLOSED ? ActiveTier.PRIMARY : ActiveTier.SECONDARY;
                    snapshots.add(new Snapshot(
                        key,
                        state,
                        activeTier,
                        primary,
                        secondary,
                        circuit.lastTransitionAt,
                        circuit.openedAt,
                        circuit.nextProbeAt,
                        circuit.lastOutcomeAt,
                        circuit.activeProbes.size(),
                        circuit.halfOpenCompleted,
                        circuit.halfOpenSucceeded
                    ));
                } else {
                    circuit.retired = true;
                }
            }
            if (evict) {
                circuits.remove(key, circuit);
            }
        }
        snapshots.sort(Comparator.comparing(snapshot -> snapshot.key().key()));
        return List.copyOf(snapshots);
    }

    private Snapshot emptySnapshot(
        ModelPoolKey key,
        AccountDtos.PoolPolicySettings settings
    ) {
        return new Snapshot(
            key,
            State.CLOSED,
            enabled(settings) ? ActiveTier.PRIMARY : ActiveTier.ALL,
            Metrics.EMPTY,
            Metrics.EMPTY,
            null,
            null,
            null,
            null,
            0,
            0,
            0
        );
    }

    void reset() {
        circuits.clear();
    }

    List<ModelPoolKey> activeKeys() {
        return List.copyOf(circuits.keySet());
    }

    StorageStats storageStats(ModelPoolKey key) {
        Circuit circuit = circuits.get(key);
        if (circuit == null) {
            return new StorageStats(0, 0, 0);
        }
        synchronized (circuit) {
            return circuit.primary.storageStats().plus(circuit.secondary.storageStats());
        }
    }

    private long record(
        ModelPoolKey key,
        String requestToken,
        String accountTier,
        boolean success,
        AccountDtos.PoolPolicySettings settings
    ) {
        if (!enabled(settings)) {
            return 0;
        }
        Instant now = clock.instant();
        long outcomeId = outcomeSequence.incrementAndGet();
        boolean primaryTier = AccountPoolPolicyService.PRIMARY_TIER.equals(
            AccountPoolPolicyService.normalizePoolTier(accountTier)
        );
        while (true) {
            Circuit circuit = circuits.computeIfAbsent(key, ignored -> new Circuit());
            synchronized (circuit) {
                if (circuit.retired) {
                    circuits.remove(key, circuit);
                    continue;
                }
                circuit.prune(now, window(settings));
                circuit.lastOutcomeAt = now;
                (primaryTier ? circuit.primary : circuit.secondary).record(
                    now,
                    !success,
                    outcomeId
                );

                boolean completedProbe = circuit.state == State.HALF_OPEN
                    && circuit.activeProbes.remove(usableToken(requestToken)) != null;
                if (completedProbe) {
                    circuit.halfOpenCompleted++;
                    if (success) {
                        circuit.halfOpenSucceeded++;
                    }
                    finishHalfOpenIfPossible(circuit, now, settings);
                    return outcomeId;
                }
                if (circuit.state == State.CLOSED && primaryTier
                    && !inRecoveryGracePeriod(circuit, now, settings)
                    && shouldOpen(combinedMetrics(circuit.externalPrimary, circuit.primary.metrics()), settings)) {
                    open(circuit, now, settings);
                }
                return outcomeId;
            }
        }
    }

    private void advanceOpenCircuit(
        Circuit circuit,
        Instant now,
        AccountDtos.PoolPolicySettings settings
    ) {
        if (circuit.state != State.OPEN || circuit.nextProbeAt == null || now.isBefore(circuit.nextProbeAt)) {
            return;
        }
        circuit.state = State.HALF_OPEN;
        circuit.lastTransitionAt = now;
        circuit.activeProbes.clear();
        circuit.probeRequestTokens.clear();
        circuit.halfOpenAttempts = 0;
        circuit.halfOpenCompleted = 0;
        circuit.halfOpenSucceeded = 0;
    }

    private void expireProbeLeases(
        Circuit circuit,
        Instant now,
        AccountDtos.PoolPolicySettings settings
    ) {
        if (circuit.state != State.HALF_OPEN || circuit.activeProbes.isEmpty()) {
            return;
        }
        boolean expired = circuit.activeProbes.values().removeIf(expiry -> !expiry.isAfter(now));
        if (expired) {
            open(circuit, now, settings);
        }
    }

    private void finishHalfOpenIfPossible(
        Circuit circuit,
        Instant now,
        AccountDtos.PoolPolicySettings settings
    ) {
        int limit = probeLimit(settings);
        int requiredSuccesses = (int) Math.ceil(limit * recoveryThreshold(settings) / 100.0);
        int remaining = Math.max(0, limit - circuit.halfOpenCompleted);
        if (circuit.halfOpenSucceeded + remaining < requiredSuccesses) {
            open(circuit, now, settings);
            return;
        }
        if (circuit.halfOpenCompleted < limit || !circuit.activeProbes.isEmpty()) {
            return;
        }
        double recoveryRate = percent(circuit.halfOpenSucceeded, circuit.halfOpenCompleted);
        if (recoveryRate >= recoveryThreshold(settings)) {
            close(circuit, now);
        } else {
            open(circuit, now, settings);
        }
    }

    private void open(
        Circuit circuit,
        Instant now,
        AccountDtos.PoolPolicySettings settings
    ) {
        circuit.state = State.OPEN;
        circuit.openedAt = now;
        circuit.nextProbeAt = now.plus(cooldown(settings));
        circuit.lastTransitionAt = now;
        circuit.activeProbes.clear();
        circuit.probeRequestTokens.clear();
        circuit.halfOpenAttempts = 0;
        circuit.halfOpenCompleted = 0;
        circuit.halfOpenSucceeded = 0;
    }

    private void close(Circuit circuit, Instant now) {
        circuit.state = State.CLOSED;
        circuit.openedAt = null;
        circuit.nextProbeAt = null;
        circuit.lastTransitionAt = now;
        circuit.lastRecoveredAt = now;
        circuit.activeProbes.clear();
        circuit.probeRequestTokens.clear();
        circuit.halfOpenAttempts = 0;
        circuit.halfOpenCompleted = 0;
        circuit.halfOpenSucceeded = 0;
        circuit.primary.clear();
        circuit.secondary.clear();
        circuit.externalPrimary = Metrics.EMPTY;
        circuit.externalSecondary = Metrics.EMPTY;
        circuit.externalEvaluatedAt = null;
    }

    private boolean shouldOpen(Metrics metrics, AccountDtos.PoolPolicySettings settings) {
        return metrics.requests() >= minSampleSize(settings)
            && metrics.failureRatePercent() >= failoverThreshold(settings);
    }

    private boolean inRecoveryGracePeriod(
        Circuit circuit,
        Instant now,
        AccountDtos.PoolPolicySettings settings
    ) {
        return circuit.lastRecoveredAt != null
            && circuit.lastRecoveredAt.plus(window(settings)).isAfter(now);
    }

    private Metrics combinedMetrics(Metrics external, Metrics local) {
        return Metrics.of(
            saturatedAdd(external.requests(), local.requests()),
            saturatedAdd(external.failures(), local.failures())
        );
    }

    private long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(Math.max(0, left), Math.max(0, right));
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private String usableToken(String requestToken) {
        return requestToken == null || requestToken.isBlank() ? null : requestToken;
    }

    private boolean enabled(AccountDtos.PoolPolicySettings settings) {
        return settings != null && Boolean.TRUE.equals(settings.enabled());
    }

    private Duration window(AccountDtos.PoolPolicySettings settings) {
        int minutes = settings == null || settings.windowMinutes() == null ? 5 : settings.windowMinutes();
        return Duration.ofMinutes(Math.max(1, minutes));
    }

    private Duration cooldown(AccountDtos.PoolPolicySettings settings) {
        int minutes = settings == null || settings.retestIntervalMinutes() == null
            ? 5
            : settings.retestIntervalMinutes();
        return Duration.ofMinutes(Math.max(1, minutes));
    }

    private Duration probeLease(AccountDtos.PoolPolicySettings settings) {
        Duration configured = cooldown(settings).multipliedBy(2);
        if (configured.compareTo(MIN_PROBE_LEASE) < 0) {
            return MIN_PROBE_LEASE;
        }
        return configured.compareTo(MAX_PROBE_LEASE) > 0 ? MAX_PROBE_LEASE : configured;
    }

    private int minSampleSize(AccountDtos.PoolPolicySettings settings) {
        int samples = settings == null || settings.minSampleSize() == null ? 10 : settings.minSampleSize();
        return Math.max(1, samples);
    }

    private int probeLimit(AccountDtos.PoolPolicySettings settings) {
        int probes = settings == null || settings.retestMaxConcurrency() == null
            ? 4
            : settings.retestMaxConcurrency();
        return Math.max(1, Math.min(MAX_HALF_OPEN_PROBES, probes));
    }

    private double failoverThreshold(AccountDtos.PoolPolicySettings settings) {
        double value = settings == null || settings.failoverThresholdPercent() == null
            ? 70.0
            : settings.failoverThresholdPercent();
        return Math.max(0.0, Math.min(100.0, value));
    }

    private double recoveryThreshold(AccountDtos.PoolPolicySettings settings) {
        double value = settings == null || settings.recoveryThresholdPercent() == null
            ? 70.0
            : settings.recoveryThresholdPercent();
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static double percent(long successesOrFailures, long requests) {
        if (requests <= 0) {
            return 0.0;
        }
        return AccountPoolPolicyService.roundPercent(successesOrFailures * 100.0 / requests);
    }

    record Decision(ActiveTier activeTier, boolean probe) {
    }

    record Snapshot(
        ModelPoolKey key,
        State state,
        ActiveTier activeTier,
        Metrics primary,
        Metrics secondary,
        Instant lastTransitionAt,
        Instant openedAt,
        Instant nextProbeAt,
        Instant lastOutcomeAt,
        int halfOpenInFlight,
        int halfOpenCompleted,
        int halfOpenSucceeded
    ) {
    }

    record Metrics(long requests, long failures, double failureRatePercent) {

        private static final Metrics EMPTY = new Metrics(0, 0, 0.0);

        static Metrics of(long requests, long failures) {
            long safeRequests = Math.max(0, requests);
            long safeFailures = Math.max(0, Math.min(safeRequests, failures));
            return new Metrics(safeRequests, safeFailures, percent(safeFailures, safeRequests));
        }
    }

    record StorageStats(int pendingOutcomes, int acknowledgedBuckets, int localOnlyBuckets) {

        private StorageStats plus(StorageStats other) {
            return new StorageStats(
                pendingOutcomes + other.pendingOutcomes,
                acknowledgedBuckets + other.acknowledgedBuckets,
                localOnlyBuckets + other.localOnlyBuckets
            );
        }
    }

    private static final class Circuit {

        private final RollingMetrics primary = new RollingMetrics();
        private final RollingMetrics secondary = new RollingMetrics();
        private final Map<String, Instant> activeProbes = new HashMap<>();
        private final Set<String> probeRequestTokens = new HashSet<>();
        private State state = State.CLOSED;
        private Metrics externalPrimary = Metrics.EMPTY;
        private Metrics externalSecondary = Metrics.EMPTY;
        private Instant externalEvaluatedAt;
        private Instant lastTransitionAt;
        private Instant openedAt;
        private Instant nextProbeAt;
        private Instant lastOutcomeAt;
        private Instant lastRecoveredAt;
        private int halfOpenAttempts;
        private int halfOpenCompleted;
        private int halfOpenSucceeded;
        private boolean retired;

        private void prune(Instant now, Duration window) {
            primary.prune(now, window);
            secondary.prune(now, window);
            if (externalEvaluatedAt != null && externalEvaluatedAt.plus(window).isBefore(now)) {
                externalPrimary = Metrics.EMPTY;
                externalSecondary = Metrics.EMPTY;
                externalEvaluatedAt = null;
            }
        }

        private boolean evictable() {
            return activeProbes.isEmpty()
                && primary.metrics().requests() == 0
                && secondary.metrics().requests() == 0
                && externalPrimary.requests() == 0
                && externalSecondary.requests() == 0;
        }
    }

    private static final class RollingMetrics {

        private final Map<Long, PendingOutcome> pendingOutcomes = new HashMap<>();
        private final Map<AcknowledgedBucketKey, MetricBucket> acknowledgedBuckets = new HashMap<>();
        private final Map<Long, MetricBucket> localOnlyBuckets = new HashMap<>();
        private long requests;
        private long failures;

        private void record(Instant now, boolean failure, long outcomeId) {
            long epochMinute = Math.floorDiv(now.getEpochSecond(), 60);
            pendingOutcomes.put(outcomeId, new PendingOutcome(epochMinute, failure));
            requests++;
            if (failure) {
                failures++;
            }
        }

        private void prune(Instant now, Duration window) {
            long currentMinute = Math.floorDiv(now.getEpochSecond(), 60);
            long retainedMinutes = Math.max(1, window.toMinutes());
            long oldestMinute = currentMinute - retainedMinutes + 1;
            pendingOutcomes.values().removeIf(outcome -> removeIfExpired(outcome, oldestMinute));
            acknowledgedBuckets.entrySet().removeIf(entry -> removeIfExpired(entry.getValue(), oldestMinute));
            localOnlyBuckets.entrySet().removeIf(entry -> removeIfExpired(entry.getValue(), oldestMinute));
        }

        private Metrics metrics() {
            return Metrics.of(requests, failures);
        }

        private boolean contains(long outcomeId) {
            return pendingOutcomes.containsKey(outcomeId);
        }

        private void acknowledge(long outcomeId, long generation) {
            PendingOutcome outcome = pendingOutcomes.remove(outcomeId);
            if (outcome == null) {
                return;
            }
            AcknowledgedBucketKey key = new AcknowledgedBucketKey(outcome.epochMinute, generation);
            acknowledgedBuckets.computeIfAbsent(
                key,
                ignored -> new MetricBucket(outcome.epochMinute)
            ).add(outcome.failure);
        }

        private void persistenceFailed(long outcomeId) {
            PendingOutcome outcome = pendingOutcomes.remove(outcomeId);
            if (outcome == null) {
                return;
            }
            localOnlyBuckets.computeIfAbsent(
                outcome.epochMinute,
                ignored -> new MetricBucket(outcome.epochMinute)
            ).add(outcome.failure);
        }

        private void discardAcknowledgedThrough(long acknowledgedThrough) {
            acknowledgedBuckets.entrySet().removeIf(entry -> {
                if (entry.getKey().generation > acknowledgedThrough) {
                    return false;
                }
                removeFromMetrics(entry.getValue());
                return true;
            });
        }

        private boolean removeIfExpired(MetricCounts outcome, long oldestMinute) {
            if (outcome.epochMinute() >= oldestMinute) {
                return false;
            }
            removeFromMetrics(outcome);
            return true;
        }

        private void removeFromMetrics(MetricCounts counts) {
            requests = Math.max(0, requests - counts.requests());
            failures = Math.max(0, failures - counts.failures());
        }

        private void clear() {
            pendingOutcomes.clear();
            acknowledgedBuckets.clear();
            localOnlyBuckets.clear();
            requests = 0;
            failures = 0;
        }

        private StorageStats storageStats() {
            return new StorageStats(
                pendingOutcomes.size(),
                acknowledgedBuckets.size(),
                localOnlyBuckets.size()
            );
        }
    }

    private interface MetricCounts {

        long epochMinute();

        long requests();

        long failures();
    }

    private record PendingOutcome(long epochMinute, boolean failure) implements MetricCounts {

        @Override
        public long requests() {
            return 1;
        }

        @Override
        public long failures() {
            return failure ? 1 : 0;
        }
    }

    private record AcknowledgedBucketKey(long epochMinute, long generation) {
    }

    private static final class MetricBucket implements MetricCounts {

        private final long epochMinute;
        private long requests;
        private long failures;

        private MetricBucket(long epochMinute) {
            this.epochMinute = epochMinute;
        }

        private void add(boolean failure) {
            requests++;
            if (failure) {
                failures++;
            }
        }

        @Override
        public long epochMinute() {
            return epochMinute;
        }

        @Override
        public long requests() {
            return requests;
        }

        @Override
        public long failures() {
            return failures;
        }
    }
}
