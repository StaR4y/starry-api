package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountPoolCircuitBreakerTest {

    private static final Instant START = Instant.parse("2026-08-10T00:00:00Z");
    private static final ModelPoolKey OPENAI_GPT = new ModelPoolKey(
        "openai",
        "gpt",
        "OpenAI",
        "GPT"
    );
    private static final ModelPoolKey OPENAI_REASONING = new ModelPoolKey(
        "openai",
        "o-series",
        "OpenAI",
        "O series"
    );
    private static final ModelPoolKey DEEPSEEK_V4 = new ModelPoolKey(
        "deepseek",
        "deepseek-v4",
        "DeepSeek",
        "DeepSeek V4"
    );

    private MutableClock clock;
    private AccountPoolCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        circuitBreaker = new AccountPoolCircuitBreaker(clock);
    }

    @Test
    void failuresOpenOnlyTheMatchingProviderAndModelFamily() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);

        openCircuit(OPENAI_GPT, settings);

        assertThat(snapshot(OPENAI_GPT, settings).state())
            .isEqualTo(AccountPoolCircuitBreaker.State.OPEN);
        assertThat(snapshot(OPENAI_REASONING, settings).state())
            .isEqualTo(AccountPoolCircuitBreaker.State.CLOSED);
        assertThat(snapshot(DEEPSEEK_V4, settings).state())
            .isEqualTo(AccountPoolCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.select(OPENAI_GPT, "gpt-request", settings).activeTier())
            .isEqualTo(AccountPoolCircuitBreaker.ActiveTier.SECONDARY);
        assertThat(circuitBreaker.select(OPENAI_REASONING, "reasoning-request", settings).activeTier())
            .isEqualTo(AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
        assertThat(circuitBreaker.select(DEEPSEEK_V4, "deepseek-request", settings).activeTier())
            .isEqualTo(AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
    }

    @Test
    void openCircuitUsesOnlySecondaryUntilTheFullCooldownExpires() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        openCircuit(OPENAI_GPT, settings);

        clock.advance(Duration.ofMinutes(5).minusNanos(1));

        AccountPoolCircuitBreaker.Decision beforeCooldown = circuitBreaker.select(
            OPENAI_GPT,
            "before-cooldown",
            settings
        );
        assertThat(beforeCooldown.activeTier()).isEqualTo(AccountPoolCircuitBreaker.ActiveTier.SECONDARY);
        assertThat(beforeCooldown.probe()).isFalse();
        assertThat(snapshot(OPENAI_GPT, settings).state()).isEqualTo(AccountPoolCircuitBreaker.State.OPEN);

        clock.advance(Duration.ofNanos(1));

        AccountPoolCircuitBreaker.Decision atCooldown = circuitBreaker.select(
            OPENAI_GPT,
            "at-cooldown",
            settings
        );
        assertThat(atCooldown.activeTier()).isEqualTo(AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
        assertThat(atCooldown.probe()).isTrue();
        assertThat(snapshot(OPENAI_GPT, settings).state()).isEqualTo(AccountPoolCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenAllowsOnlyTheConfiguredNumberOfProbes() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));

        AccountPoolCircuitBreaker.Decision first = circuitBreaker.select(OPENAI_GPT, "probe-1", settings);
        AccountPoolCircuitBreaker.Decision second = circuitBreaker.select(OPENAI_GPT, "probe-2", settings);

        assertThat(first.probe()).isTrue();
        assertThat(first.activeTier()).isEqualTo(AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
        assertThat(second.probe()).isFalse();
        assertThat(second.activeTier()).isEqualTo(AccountPoolCircuitBreaker.ActiveTier.SECONDARY);
        assertThat(snapshot(OPENAI_GPT, settings).halfOpenInFlight()).isEqualTo(1);
    }

    @Test
    void halfOpenDoesNotProbeWithoutAStableRequestToken() {
        AccountDtos.PoolPolicySettings settings = settings(true, 2);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));

        AccountPoolCircuitBreaker.Decision missing = circuitBreaker.select(OPENAI_GPT, null, settings);
        AccountPoolCircuitBreaker.Decision blank = circuitBreaker.select(OPENAI_GPT, "  ", settings);

        assertThat(missing)
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.SECONDARY, false));
        assertThat(blank)
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.SECONDARY, false));
        assertThat(snapshot(OPENAI_GPT, settings).halfOpenInFlight()).isZero();
    }

    @Test
    void concurrentSelectionsCannotExceedProbeConcurrency() throws Exception {
        int probeConcurrency = 3;
        int taskCount = 24;
        AccountDtos.PoolPolicySettings settings = settings(true, probeConcurrency);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AccountPoolCircuitBreaker.Decision>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < taskCount; index++) {
                int requestIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return circuitBreaker.select(OPENAI_GPT, "parallel-" + requestIndex, settings);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<AccountPoolCircuitBreaker.Decision> decisions = new ArrayList<>(taskCount);
            for (Future<AccountPoolCircuitBreaker.Decision> future : futures) {
                decisions.add(future.get(5, TimeUnit.SECONDS));
            }

            assertThat(decisions).filteredOn(AccountPoolCircuitBreaker.Decision::probe)
                .hasSize(probeConcurrency)
                .allMatch(decision -> decision.activeTier() == AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
            assertThat(decisions).filteredOn(decision -> !decision.probe())
                .hasSize(taskCount - probeConcurrency)
                .allMatch(decision -> decision.activeTier() == AccountPoolCircuitBreaker.ActiveTier.SECONDARY);
            assertThat(snapshot(OPENAI_GPT, settings).halfOpenInFlight()).isEqualTo(probeConcurrency);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void repeatedSelectionForTheSameRequestReusesOneProbeLeaseAcrossRetries() {
        AccountDtos.PoolPolicySettings settings = settings(true, 2);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));

        AccountPoolCircuitBreaker.Decision initial = circuitBreaker.select(
            OPENAI_GPT,
            "same-client-request",
            settings
        );
        AccountPoolCircuitBreaker.Decision retry = circuitBreaker.select(
            OPENAI_GPT,
            "same-client-request",
            settings
        );
        AccountPoolCircuitBreaker.Decision independent = circuitBreaker.select(
            OPENAI_GPT,
            "independent-request",
            settings
        );
        AccountPoolCircuitBreaker.Decision overLimit = circuitBreaker.select(
            OPENAI_GPT,
            "over-limit-request",
            settings
        );

        assertThat(initial)
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.PRIMARY, true));
        assertThat(retry).isEqualTo(initial);
        assertThat(independent).isEqualTo(initial);
        assertThat(overLimit)
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.SECONDARY, false));
        assertThat(snapshot(OPENAI_GPT, settings).halfOpenInFlight()).isEqualTo(2);
    }

    @Test
    void successfulProbeClosesCircuitAndRestoresPrimaryTier() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));
        AccountPoolCircuitBreaker.Decision probe = circuitBreaker.select(OPENAI_GPT, "success-probe", settings);

        circuitBreaker.recordSuccess(OPENAI_GPT, "success-probe", "primary", settings);

        assertThat(probe.probe()).isTrue();
        AccountPoolCircuitBreaker.Snapshot recovered = snapshot(OPENAI_GPT, settings);
        assertThat(recovered.state()).isEqualTo(AccountPoolCircuitBreaker.State.CLOSED);
        assertThat(recovered.activeTier()).isEqualTo(AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
        assertThat(recovered.openedAt()).isNull();
        assertThat(recovered.nextProbeAt()).isNull();
        assertThat(circuitBreaker.select(OPENAI_GPT, "after-recovery", settings))
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.PRIMARY, false));
    }

    @Test
    void failedProbeReopensCircuitWithAFreshCooldown() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));
        assertThat(circuitBreaker.select(OPENAI_GPT, "failed-probe", settings).probe()).isTrue();
        clock.advance(Duration.ofSeconds(30));
        Instant failedAt = clock.instant();

        circuitBreaker.recordFailure(OPENAI_GPT, "failed-probe", "primary", settings);

        AccountPoolCircuitBreaker.Snapshot reopened = snapshot(OPENAI_GPT, settings);
        assertThat(reopened.state()).isEqualTo(AccountPoolCircuitBreaker.State.OPEN);
        assertThat(reopened.openedAt()).isEqualTo(failedAt);
        assertThat(reopened.nextProbeAt()).isEqualTo(failedAt.plus(Duration.ofMinutes(5)));
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        assertThat(circuitBreaker.select(OPENAI_GPT, "too-early", settings).activeTier())
            .isEqualTo(AccountPoolCircuitBreaker.ActiveTier.SECONDARY);
    }

    @Test
    void cancellingProbeReleasesCapacityForAnotherRequest() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));
        assertThat(circuitBreaker.select(OPENAI_GPT, "cancelled", settings).probe()).isTrue();
        assertThat(circuitBreaker.select(OPENAI_GPT, "blocked", settings).probe()).isFalse();

        circuitBreaker.cancelProbe(OPENAI_GPT, "cancelled");

        AccountPoolCircuitBreaker.Decision replacement = circuitBreaker.select(
            OPENAI_GPT,
            "replacement",
            settings
        );
        assertThat(replacement.probe()).isTrue();
        assertThat(snapshot(OPENAI_GPT, settings).halfOpenInFlight()).isEqualTo(1);
    }

    @Test
    void expiredProbeLeaseReleasesCapacityAndStartsAFreshCooldown() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));
        assertThat(circuitBreaker.select(OPENAI_GPT, "abandoned-probe", settings).probe()).isTrue();

        clock.advance(Duration.ofMinutes(10).minusNanos(1));

        AccountPoolCircuitBreaker.Snapshot beforeExpiry = snapshot(OPENAI_GPT, settings);
        assertThat(beforeExpiry.state()).isEqualTo(AccountPoolCircuitBreaker.State.HALF_OPEN);
        assertThat(beforeExpiry.halfOpenInFlight()).isEqualTo(1);

        clock.advance(Duration.ofNanos(1));
        Instant expiredAt = clock.instant();

        AccountPoolCircuitBreaker.Snapshot expired = snapshot(OPENAI_GPT, settings);
        assertThat(expired.state()).isEqualTo(AccountPoolCircuitBreaker.State.OPEN);
        assertThat(expired.halfOpenInFlight()).isZero();
        assertThat(expired.openedAt()).isEqualTo(expiredAt);
        assertThat(expired.nextProbeAt()).isEqualTo(expiredAt.plus(Duration.ofMinutes(5)));
        assertThat(circuitBreaker.select(OPENAI_GPT, "during-fresh-cooldown", settings))
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.SECONDARY, false));
    }

    @Test
    void failedHalfOpenRequestCannotConsumeAnotherProbeSlotDuringAccountRetry() {
        AccountDtos.PoolPolicySettings settings = settings(true, 3, 50.0);
        openCircuit(OPENAI_GPT, settings);
        clock.advance(Duration.ofMinutes(5));
        String requestToken = "one-client-request";
        assertThat(circuitBreaker.select(OPENAI_GPT, requestToken, settings).probe()).isTrue();

        circuitBreaker.recordFailure(OPENAI_GPT, requestToken, "primary", settings);

        AccountPoolCircuitBreaker.Decision retry = circuitBreaker.select(OPENAI_GPT, requestToken, settings);
        AccountPoolCircuitBreaker.Decision anotherRequest = circuitBreaker.select(
            OPENAI_GPT,
            "another-client-request",
            settings
        );
        assertThat(retry)
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.SECONDARY, false));
        assertThat(anotherRequest)
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.PRIMARY, true));
        assertThat(snapshot(OPENAI_GPT, settings).halfOpenInFlight()).isEqualTo(1);
    }

    @Test
    void disabledPolicyBypassesCircuitAndDoesNotRetainFailures() {
        AccountDtos.PoolPolicySettings disabled = settings(false, 1);

        for (int index = 0; index < 10; index++) {
            AccountPoolCircuitBreaker.Decision decision = circuitBreaker.select(
                OPENAI_GPT,
                "disabled-" + index,
                disabled
            );
            assertThat(decision.activeTier()).isEqualTo(AccountPoolCircuitBreaker.ActiveTier.ALL);
            assertThat(decision.probe()).isFalse();
            circuitBreaker.recordFailure(OPENAI_GPT, "disabled-" + index, "primary", disabled);
        }

        AccountPoolCircuitBreaker.Snapshot disabledSnapshot = snapshot(OPENAI_GPT, disabled);
        assertThat(disabledSnapshot.state()).isEqualTo(AccountPoolCircuitBreaker.State.CLOSED);
        assertThat(disabledSnapshot.activeTier()).isEqualTo(AccountPoolCircuitBreaker.ActiveTier.ALL);
        assertThat(disabledSnapshot.primary().requests()).isZero();
        assertThat(circuitBreaker.select(OPENAI_GPT, "enabled-again", settings(true, 1)))
            .isEqualTo(new AccountPoolCircuitBreaker.Decision(AccountPoolCircuitBreaker.ActiveTier.PRIMARY, false));
    }

    @Test
    void externallyEvaluatedMetricsOpenOnlyTheirMatchingPool() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);

        circuitBreaker.evaluate(
            OPENAI_REASONING,
            10,
            8,
            4,
            1,
            circuitBreaker.beginExternalEvaluation(),
            clock.instant(),
            settings
        );

        assertThat(snapshot(OPENAI_REASONING, settings).state())
            .isEqualTo(AccountPoolCircuitBreaker.State.OPEN);
        assertThat(snapshot(OPENAI_GPT, settings).state())
            .isEqualTo(AccountPoolCircuitBreaker.State.CLOSED);
        assertThat(snapshot(DEEPSEEK_V4, settings).state())
            .isEqualTo(AccountPoolCircuitBreaker.State.CLOSED);
    }

    @Test
    void deepSeekServerFailuresDoNotTripOpenAiPools() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);

        for (int index = 0; index < 3; index++) {
            String requestToken = "deepseek-503-" + index;
            assertThat(circuitBreaker.select(DEEPSEEK_V4, requestToken, settings).activeTier())
                .isEqualTo(AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
            circuitBreaker.recordFailure(DEEPSEEK_V4, requestToken, "primary", settings);
        }

        assertThat(snapshot(DEEPSEEK_V4, settings).state())
            .isEqualTo(AccountPoolCircuitBreaker.State.OPEN);
        assertThat(snapshot(DEEPSEEK_V4, settings).primary().failureRatePercent()).isEqualTo(100.0);
        assertThat(snapshot(OPENAI_GPT, settings).state())
            .isEqualTo(AccountPoolCircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.select(OPENAI_GPT, "openai-unaffected", settings).activeTier())
            .isEqualTo(AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
    }

    @Test
    void sharedBaselineAndPostEvaluationLocalDeltaAreMergedWithoutFalseOpening() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        long coveredGeneration = circuitBreaker.beginExternalEvaluation();
        circuitBreaker.evaluate(
            OPENAI_GPT,
            100,
            0,
            0,
            0,
            coveredGeneration,
            clock.instant(),
            settings
        );

        for (int index = 0; index < 10; index++) {
            circuitBreaker.recordFailure(OPENAI_GPT, "new-failure-" + index, "primary", settings);
        }

        AccountPoolCircuitBreaker.Snapshot snapshot = snapshot(OPENAI_GPT, settings);
        assertThat(snapshot.primary().requests()).isEqualTo(110);
        assertThat(snapshot.primary().failures()).isEqualTo(10);
        assertThat(snapshot.state()).isEqualTo(AccountPoolCircuitBreaker.State.CLOSED);
    }

    @Test
    void refreshedSharedBaselineReplacesCoveredLocalOutcomesInsteadOfDoubleCountingThem() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        long success = circuitBreaker.recordSuccess(OPENAI_GPT, "covered-success", "primary", settings);
        long failure = circuitBreaker.recordFailure(OPENAI_GPT, "covered-failure", "primary", settings);
        circuitBreaker.acknowledge(OPENAI_GPT, success);
        circuitBreaker.acknowledge(OPENAI_GPT, failure);

        long acknowledgedThrough = circuitBreaker.beginExternalEvaluation();
        circuitBreaker.evaluate(
            OPENAI_GPT,
            102,
            2,
            0,
            0,
            acknowledgedThrough,
            clock.instant(),
            settings
        );

        AccountPoolCircuitBreaker.Snapshot snapshot = snapshot(OPENAI_GPT, settings);
        assertThat(snapshot.primary().requests()).isEqualTo(102);
        assertThat(snapshot.primary().failures()).isEqualTo(2);
    }

    @Test
    void outcomeAcknowledgedDuringDatabaseQueryIsRetainedUntilTheNextBaselineCoversIt() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        long outcome = circuitBreaker.recordFailure(
            OPENAI_GPT,
            "commits-during-query",
            "primary",
            settings
        );

        long firstAcknowledgmentWatermark = circuitBreaker.beginExternalEvaluation();
        circuitBreaker.acknowledge(OPENAI_GPT, outcome);
        circuitBreaker.evaluate(
            OPENAI_GPT,
            0,
            0,
            0,
            0,
            firstAcknowledgmentWatermark,
            clock.instant(),
            settings
        );

        AccountPoolCircuitBreaker.Snapshot afterConcurrentCommit = snapshot(OPENAI_GPT, settings);
        assertThat(afterConcurrentCommit.primary().requests()).isEqualTo(1);
        assertThat(afterConcurrentCommit.primary().failures()).isEqualTo(1);

        long secondAcknowledgmentWatermark = circuitBreaker.beginExternalEvaluation();
        circuitBreaker.evaluate(
            OPENAI_GPT,
            1,
            1,
            0,
            0,
            secondAcknowledgmentWatermark,
            clock.instant(),
            settings
        );

        AccountPoolCircuitBreaker.Snapshot afterCoveredBaseline = snapshot(OPENAI_GPT, settings);
        assertThat(afterCoveredBaseline.primary().requests()).isEqualTo(1);
        assertThat(afterCoveredBaseline.primary().failures()).isEqualTo(1);
    }

    @Test
    void acknowledgedAndPersistenceFailedOutcomesAreAggregatedWithoutPerRequestWindowStorage() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        int outcomesPerClass = 10_000;

        for (int index = 0; index < outcomesPerClass; index++) {
            long acknowledged = circuitBreaker.recordSuccess(
                OPENAI_GPT,
                "acknowledged-" + index,
                "primary",
                settings
            );
            circuitBreaker.acknowledge(OPENAI_GPT, acknowledged);

            long localOnly = circuitBreaker.recordSuccess(
                OPENAI_GPT,
                "local-only-" + index,
                "primary",
                settings
            );
            circuitBreaker.persistenceFailed(OPENAI_GPT, localOnly);
        }

        AccountPoolCircuitBreaker.StorageStats storage = circuitBreaker.storageStats(OPENAI_GPT);
        assertThat(storage.pendingOutcomes()).isZero();
        assertThat(storage.acknowledgedBuckets()).isEqualTo(1);
        assertThat(storage.localOnlyBuckets()).isEqualTo(1);
        assertThat(snapshot(OPENAI_GPT, settings).primary().requests())
            .isEqualTo(2L * outcomesPerClass);

        long acknowledgedThrough = circuitBreaker.beginExternalEvaluation();
        circuitBreaker.evaluate(
            OPENAI_GPT,
            outcomesPerClass,
            0,
            0,
            0,
            acknowledgedThrough,
            clock.instant(),
            settings
        );

        AccountPoolCircuitBreaker.StorageStats reconciledStorage = circuitBreaker.storageStats(OPENAI_GPT);
        assertThat(reconciledStorage.pendingOutcomes()).isZero();
        assertThat(reconciledStorage.acknowledgedBuckets()).isZero();
        assertThat(reconciledStorage.localOnlyBuckets()).isEqualTo(1);
        assertThat(snapshot(OPENAI_GPT, settings).primary().requests())
            .isEqualTo(2L * outcomesPerClass);
    }

    @Test
    void removedCatalogPoolIsRetainedOnlyUntilItsMetricsLeaveTheWindow() {
        AccountDtos.PoolPolicySettings settings = settings(true, 1);
        circuitBreaker.recordSuccess(OPENAI_GPT, "last-request", "primary", settings);

        assertThat(circuitBreaker.snapshots(settings, List.of()))
            .extracting(snapshot -> snapshot.key().key())
            .containsExactly(OPENAI_GPT.key());

        clock.advance(Duration.ofMinutes(5));

        assertThat(circuitBreaker.snapshots(settings, List.of())).isEmpty();
        assertThat(circuitBreaker.storageStats(OPENAI_GPT))
            .isEqualTo(new AccountPoolCircuitBreaker.StorageStats(0, 0, 0));
    }

    private void openCircuit(ModelPoolKey key, AccountDtos.PoolPolicySettings settings) {
        for (int index = 0; index < 3; index++) {
            String requestToken = "failure-" + key.key() + "-" + index;
            AccountPoolCircuitBreaker.Decision decision = circuitBreaker.select(key, requestToken, settings);
            assertThat(decision.activeTier()).isEqualTo(AccountPoolCircuitBreaker.ActiveTier.PRIMARY);
            circuitBreaker.recordFailure(key, requestToken, "primary", settings);
        }
        assertThat(snapshot(key, settings).state()).isEqualTo(AccountPoolCircuitBreaker.State.OPEN);
    }

    private AccountPoolCircuitBreaker.Snapshot snapshot(
        ModelPoolKey key,
        AccountDtos.PoolPolicySettings settings
    ) {
        return circuitBreaker.snapshots(settings, List.of(key)).stream()
            .filter(value -> value.key().equals(key))
            .findFirst()
            .orElseThrow();
    }

    private AccountDtos.PoolPolicySettings settings(boolean enabled, int probeConcurrency) {
        return settings(enabled, probeConcurrency, 100.0);
    }

    private AccountDtos.PoolPolicySettings settings(
        boolean enabled,
        int probeConcurrency,
        double recoveryThresholdPercent
    ) {
        return new AccountDtos.PoolPolicySettings(
            enabled,
            5,
            3,
            50.0,
            70.0,
            recoveryThresholdPercent,
            5,
            probeConcurrency,
            true
        );
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableClock(Instant initial) {
            this(new AtomicReference<>(initial), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(current, requestedZone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        private void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }
    }
}
