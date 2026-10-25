package tech.starryapi.sourcetier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpStatus;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.admin.DataManagementSecurityService;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

class SourceTierRegistryTest {

    private static final SystemMaintenanceTaskGate OPERATIONAL =
        SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction();
    private static final Consumer<Throwable> UNAVAILABLE = error -> {
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(api.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE");
    };

    // ---- first load ----

    @Test
    void theFirstLoadHappensWhenTheSingletonsAreReadyNotWhenTheApplicationRunners() {
        assertThat(SmartInitializingSingleton.class).isAssignableFrom(SourceTierRegistry.class);
        assertThat(ApplicationRunner.class.isAssignableFrom(SourceTierRegistry.class)).isFalse();

        SourceTierRegistry registry = new SourceTierRegistry(() -> Mono.just(snapshotAt(3)), OPERATIONAL);
        assertThat(registry.loaded()).isFalse();

        registry.afterSingletonsInstantiated();

        assertThat(registry.loaded()).isTrue();
        assertThat(registry.snapshot().configVersion()).isEqualTo(3);
    }

    @Test
    void theConfigurationIsLoadedBeforeTheWebServerLifecycleStarts() {
        AtomicBoolean loadedWhenStarted = new AtomicBoolean();
        AtomicBoolean started = new AtomicBoolean();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("registry", SourceTierRegistry.class,
                () -> new SourceTierRegistry(() -> Mono.just(snapshotAt(1)), OPERATIONAL));
            context.registerBean("server", SmartLifecycle.class, () -> new SmartLifecycle() {
                @Override
                public void start() {
                    loadedWhenStarted.set(context.getBean(SourceTierRegistry.class).loaded());
                    started.set(true);
                }

                @Override
                public void stop() {
                }

                @Override
                public boolean isRunning() {
                    return started.get();
                }

                @Override
                public int getPhase() {
                    return Integer.MAX_VALUE - 1;
                }
            });

            context.refresh();

            assertThat(started).isTrue();
            assertThat(loadedWhenStarted).isTrue();
        }
    }

    @Test
    void aFailedFirstLoadDoesNotStopTheApplicationAndLeavesTheRegistryNotLoaded() {
        AtomicInteger calls = new AtomicInteger();
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            calls.incrementAndGet();
            return Mono.error(new IllegalStateException("database down"));
        }, OPERATIONAL, VirtualTimeScheduler.create());

        registry.afterSingletonsInstantiated();

        assertThat(calls).hasValue(1);
        assertThat(registry.loaded()).isFalse();
    }

    @Test
    void aFailedFirstLoadIsTriedAgainWithinSecondsAndNotLeftToTheThirtySecondRefresh() {
        VirtualTimeScheduler clock = VirtualTimeScheduler.create();
        AtomicInteger calls = new AtomicInteger();
        Supplier<Mono<SourceTierSnapshot>> scripted = script(null, null, snapshotAt(4));
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            calls.incrementAndGet();
            return scripted.get();
        }, OPERATIONAL, clock);

        registry.afterSingletonsInstantiated();

        assertThat(calls).hasValue(1);
        assertThat(registry.loaded()).isFalse();
        // The first retry comes after a second, the next after two, so the third read is made within three seconds.
        clock.advanceTimeBy(Duration.ofMillis(999));
        assertThat(calls).hasValue(1);
        clock.advanceTimeBy(Duration.ofMillis(1));
        assertThat(calls).hasValue(2);
        assertThat(registry.loaded()).isFalse();
        clock.advanceTimeBy(Duration.ofMillis(1_999));
        assertThat(calls).hasValue(2);
        clock.advanceTimeBy(Duration.ofMillis(1));
        assertThat(calls).hasValue(3);
        assertThat(registry.loaded()).isTrue();
        assertThat(registry.snapshot().configVersion()).isEqualTo(4);
    }

    @Test
    void theWaitBetweenTriesOfAFailedFirstLoadGrowsToFiveSecondsAtMost() {
        VirtualTimeScheduler clock = VirtualTimeScheduler.create();
        AtomicInteger calls = new AtomicInteger();
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            calls.incrementAndGet();
            return Mono.error(new IllegalStateException("database down"));
        }, OPERATIONAL, clock);

        registry.afterSingletonsInstantiated();
        // 1 s, 2 s, 4 s and then 5 s each: tries at 1, 3, 7, 12, 17 and 22 seconds.
        clock.advanceTimeBy(Duration.ofSeconds(22));

        assertThat(calls).hasValue(7);
        assertThat(registry.loaded()).isFalse();
    }

    @Test
    void theTriesStopOnceALoadHasWorkedWhoeverMadeIt() {
        VirtualTimeScheduler clock = VirtualTimeScheduler.create();
        AtomicInteger calls = new AtomicInteger();
        Supplier<Mono<SourceTierSnapshot>> scripted = script(null, snapshotAt(6));
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            calls.incrementAndGet();
            return scripted.get();
        }, OPERATIONAL, clock);
        registry.afterSingletonsInstantiated();
        assertThat(registry.loaded()).isFalse();

        // The 30-second refresh (or a change notification) got there first.
        registry.refresh().block();
        assertThat(registry.loaded()).isTrue();
        clock.advanceTimeBy(Duration.ofMinutes(5));

        assertThat(calls).hasValue(2);
    }

    @Test
    void theTriesOfAFailedFirstLoadEndWhenTheApplicationShutsDown() {
        VirtualTimeScheduler clock = VirtualTimeScheduler.create();
        AtomicInteger calls = new AtomicInteger();
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            calls.incrementAndGet();
            return Mono.error(new IllegalStateException("database down"));
        }, OPERATIONAL, clock);
        registry.afterSingletonsInstantiated();

        registry.stopFirstLoadRetry();
        clock.advanceTimeBy(Duration.ofMinutes(1));

        assertThat(calls).hasValue(1);
    }

    @Test
    void aFirstLoadThatWorksStartsNoRetryAtAll() {
        VirtualTimeScheduler clock = VirtualTimeScheduler.create();
        AtomicInteger calls = new AtomicInteger();
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            calls.incrementAndGet();
            return Mono.just(snapshotAt(1));
        }, OPERATIONAL, clock);

        registry.afterSingletonsInstantiated();
        clock.advanceTimeBy(Duration.ofMinutes(1));

        assertThat(calls).hasValue(1);
    }

    @Test
    void beforeTheFirstLoadNothingIsKnownAndEveryAccessorRefusesInsteadOfAnsweringAsIfNothingWereConfigured() {
        SourceTierRegistry registry = new SourceTierRegistry(() -> Mono.error(new IllegalStateException("database down")),
            OPERATIONAL, VirtualTimeScheduler.create());
        registry.afterSingletonsInstantiated();
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("any");

        assertThat(registry.loaded()).isFalse();
        assertThatThrownBy(registry::snapshot).satisfies(UNAVAILABLE);
        assertThatThrownBy(registry::exclusiveXpAccountIds).satisfies(UNAVAILABLE);
        assertThatThrownBy(() -> registry.tierOf("anthropic", account)).satisfies(UNAVAILABLE);
    }

    @Test
    void theAccessorsAnswerAgainOnceALoadHasSucceeded() {
        SourceTierRegistry registry = new SourceTierRegistry(script(null, snapshotAt(5)), OPERATIONAL, VirtualTimeScheduler.create());
        registry.afterSingletonsInstantiated();
        assertThatThrownBy(registry::snapshot).satisfies(UNAVAILABLE);

        registry.refresh().block();

        assertThat(registry.snapshot().configVersion()).isEqualTo(5);
        assertThat(registry.exclusiveXpAccountIds()).isEmpty();
    }

    @Test
    void aLoaderThatThrowsInsteadOfFailingTheMonoIsTreatedTheSame() {
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            throw new IllegalStateException("could not even start");
        }, OPERATIONAL, VirtualTimeScheduler.create());

        registry.afterSingletonsInstantiated();

        assertThat(registry.loaded()).isFalse();
    }

    @Test
    void aLaterRefreshLoadsWhatTheFirstOneMissed() {
        SourceTierRegistry registry = new SourceTierRegistry(script(null, snapshotAt(5)), OPERATIONAL, VirtualTimeScheduler.create());
        registry.afterSingletonsInstantiated();
        assertThat(registry.loaded()).isFalse();

        registry.refresh().block();

        assertThat(registry.loaded()).isTrue();
        assertThat(registry.snapshot().configVersion()).isEqualTo(5);
    }

    @Test
    void emptyTablesGiveAnEmptySnapshotThatCountsAsLoaded() {
        SourceTierRegistry registry = new SourceTierRegistry(
            () -> Mono.just(SourceTierSnapshot.of(0L, List.of(), List.of(), List.of(), List.of(), List.of())), OPERATIONAL);

        registry.afterSingletonsInstantiated();

        assertThat(registry.loaded()).isTrue();
        assertThat(registry.snapshot().isEmpty()).isTrue();
        assertThat(registry.exclusiveXpAccountIds()).isEmpty();
    }

    @Test
    void aReadThatNeverEndsGivesUpAndDoesNotHoldBackTheNextOne() {
        AtomicInteger calls = new AtomicInteger();
        SourceTierRegistry registry = new SourceTierRegistry(
            () -> calls.incrementAndGet() == 1 ? Mono.never() : Mono.just(snapshotAt(9)), OPERATIONAL);

        StepVerifier.withVirtualTime(registry::refresh)
            .thenAwait(Duration.ofSeconds(16))
            .expectError(TimeoutException.class)
            .verify(Duration.ofSeconds(5));
        assertThat(registry.loaded()).isFalse();

        registry.refresh().block(Duration.ofSeconds(5));

        assertThat(calls).hasValue(2);
        assertThat(registry.snapshot().configVersion()).isEqualTo(9);
    }

    // ---- refreshing ----

    @Test
    void aFailedRefreshKeepsTheSnapshotInUse() {
        ScriptedLoader loader = new ScriptedLoader();
        SourceTierRegistry registry = new SourceTierRegistry(loader, OPERATIONAL);
        Disposable first = registry.refresh().subscribe();
        loader.next().tryEmitValue(snapshotAt(1));
        assertThat(first.isDisposed()).isTrue();

        StepVerifier.create(registry.refresh())
            .then(() -> loader.next().tryEmitError(new IllegalStateException("connection reset")))
            .expectErrorMessage("connection reset")
            .verify(Duration.ofSeconds(5));

        assertThat(registry.loaded()).isTrue();
        assertThat(registry.snapshot().configVersion()).isEqualTo(1);
    }

    @Test
    void aSuccessfulRefreshReplacesTheSnapshot() {
        ScriptedLoader loader = new ScriptedLoader();
        SourceTierRegistry registry = new SourceTierRegistry(loader, OPERATIONAL);

        StepVerifier.create(registry.refresh()).then(() -> loader.next().tryEmitValue(snapshotAt(1)))
            .verifyComplete();
        SourceTierSnapshot first = registry.snapshot();
        StepVerifier.create(registry.refresh()).then(() -> loader.next().tryEmitValue(snapshotAt(2)))
            .verifyComplete();

        assertThat(first.configVersion()).isEqualTo(1);
        assertThat(registry.snapshot().configVersion()).isEqualTo(2);
    }

    @Test
    void refreshesThatOverlapShareOneReadAndRunOnceMoreAfterIt() {
        ScriptedLoader loader = new ScriptedLoader();
        SourceTierRegistry registry = new SourceTierRegistry(loader, OPERATIONAL);
        AtomicInteger finished = new AtomicInteger();

        registry.refresh().subscribe(null, error -> { }, finished::incrementAndGet);
        registry.refresh().subscribe(null, error -> { }, finished::incrementAndGet);
        registry.refresh().subscribe(null, error -> { }, finished::incrementAndGet);

        // Three requests, one read in flight; the other two wait for it.
        assertThat(loader.calls).hasValue(1);
        loader.next().tryEmitValue(snapshotAt(1));
        // The read may have started before the change the later requests are about, so it runs once more for them.
        assertThat(loader.calls).hasValue(2);
        assertThat(finished).hasValue(0);
        loader.next().tryEmitValue(snapshotAt(2));

        assertThat(finished).hasValue(3);
        assertThat(loader.calls).hasValue(2);
        assertThat(registry.snapshot().configVersion()).isEqualTo(2);
    }

    @Test
    void aChangeThatLandsDuringAReadIsPickedUpByTheNextRead() {
        // The read in flight has already looked at the database: it will return what was there when it started.
        AtomicInteger database = new AtomicInteger(1);
        ScriptedLoader loader = new ScriptedLoader();
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            int readAtStart = database.get();
            return loader.get().map(ignored -> snapshotAt(readAtStart));
        }, OPERATIONAL);

        registry.refresh().subscribe();
        database.set(2);
        registry.refresh().subscribe();
        loader.next().tryEmitValue(SourceTierSnapshot.EMPTY);
        assertThat(registry.snapshot().configVersion()).isEqualTo(1);
        loader.next().tryEmitValue(SourceTierSnapshot.EMPTY);

        assertThat(registry.snapshot().configVersion()).isEqualTo(2);
    }

    @Test
    void aFailedReadIsForgottenWhenANewerRequestRunsItAgain() {
        ScriptedLoader loader = new ScriptedLoader();
        SourceTierRegistry registry = new SourceTierRegistry(loader, OPERATIONAL);
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();

        registry.refresh().subscribe(null, error -> failed.set(true), () -> completed.set(true));
        registry.refresh().subscribe(null, error -> failed.set(true), () -> completed.set(true));
        loader.next().tryEmitError(new IllegalStateException("stale read failed"));
        loader.next().tryEmitValue(snapshotAt(4));

        assertThat(failed).isFalse();
        assertThat(completed).isTrue();
        assertThat(registry.snapshot().configVersion()).isEqualTo(4);
    }

    @Test
    void aCallerThatGivesUpLeavesTheReadRunningAndLaterCallersJoinIt() {
        ScriptedLoader loader = new ScriptedLoader();
        SourceTierRegistry registry = new SourceTierRegistry(loader, OPERATIONAL);
        Disposable abandoned = registry.refresh().subscribe();
        abandoned.dispose();
        AtomicBoolean completed = new AtomicBoolean();

        registry.refresh().subscribe(null, error -> { }, () -> completed.set(true));

        // The first read is still the one in flight; nobody started a second one in parallel.
        assertThat(loader.calls).hasValue(1);
        loader.next().tryEmitValue(snapshotAt(6));
        // The later request is about a change the first read may have missed, so one more read follows.
        assertThat(loader.calls).hasValue(2);
        assertThat(completed).isFalse();
        loader.next().tryEmitValue(snapshotAt(7));

        assertThat(completed).isTrue();
        assertThat(registry.snapshot().configVersion()).isEqualTo(7);
    }

    @Test
    void theScheduledRefreshReadsUnlessMaintenanceHoldsTheSystem() {
        AtomicInteger calls = new AtomicInteger();
        DataManagementSecurityService security = mock(DataManagementSecurityService.class);
        when(security.isLocked()).thenReturn(Mono.just(true), Mono.just(false));
        SourceTierRegistry registry = new SourceTierRegistry(() -> {
            calls.incrementAndGet();
            return Mono.just(snapshotAt(8));
        }, new SystemMaintenanceTaskGate(security));

        registry.scheduledRefresh();
        assertThat(calls).hasValue(0);
        assertThat(registry.loaded()).isFalse();

        registry.scheduledRefresh();
        assertThat(calls).hasValue(1);
        assertThat(registry.snapshot().configVersion()).isEqualTo(8);
    }

    @Test
    void theScheduledRefreshSwallowsAFailureAndKeepsTheSnapshot() {
        SourceTierRegistry registry = new SourceTierRegistry(script(snapshotAt(2), null), OPERATIONAL);
        registry.scheduledRefresh();
        assertThat(registry.snapshot().configVersion()).isEqualTo(2);

        registry.scheduledRefresh();

        assertThat(registry.loaded()).isTrue();
        assertThat(registry.snapshot().configVersion()).isEqualTo(2);
    }

    @Test
    void onlyTheConfigurationVersionSettingIsManaged() {
        SourceTierRegistry registry = new SourceTierRegistry(() -> Mono.just(snapshotAt(1)), OPERATIONAL);

        assertThat(registry.managesSetting("source_tier_config_version")).isTrue();
        assertThat(registry.managesSetting("account_pool_policy_enabled")).isFalse();
        assertThat(registry.managesSetting(null)).isFalse();
    }

    // ---- lookups ----

    @Test
    void theRegistryAnswersFromTheSnapshotInUse() {
        VendorConfig vendor = new VendorConfig("anthropic", true, false, false, true, 16, true, 1L);
        Tier tier = new Tier("anthropic", "relay", 1, "relay", BigDecimal.ONE,
            Set.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null);
        Member relay = new Member("anthropic", "relay-a", "relay", "OPENAI_CHAT", true, false, false, false, false, true,
            "NONE", 540, true, true);
        SourceTierRegistry registry = new SourceTierRegistry(() -> Mono.just(SourceTierSnapshot.of(
            2L, List.of(vendor), List.of(tier), List.of(relay), List.of(), List.of())), OPERATIONAL);
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("anthropic-official");
        account.setProvider("Anthropic");
        account.setAuthMethod("api-key");

        registry.refresh().block();

        assertThat(registry.tierOf("anthropic", account)).contains(tier);
        assertThat(registry.exclusiveXpAccountIds()).containsExactly("relay-a");
    }

    // ---- fixtures ----

    private static SourceTierSnapshot snapshotAt(long version) {
        return SourceTierSnapshot.of(version, List.of(new VendorConfig("anthropic", false, false, false, true, 16, true, 1L)),
            List.of(), List.of(), List.of(), List.of());
    }

    /** Answers the snapshots in turn, and fails for each null; the last entry repeats. */
    private static Supplier<Mono<SourceTierSnapshot>> script(SourceTierSnapshot... answers) {
        AtomicInteger step = new AtomicInteger();
        return () -> {
            SourceTierSnapshot answer = answers[Math.min(step.getAndIncrement(), answers.length - 1)];
            return answer == null ? Mono.error(new IllegalStateException("database down")) : Mono.just(answer);
        };
    }

    /** A loader whose reads end when the test says so, in the order they were started. */
    private static final class ScriptedLoader implements Supplier<Mono<SourceTierSnapshot>> {
        final AtomicInteger calls = new AtomicInteger();
        final Queue<Sinks.One<SourceTierSnapshot>> pending = new ConcurrentLinkedQueue<>();

        @Override
        public Mono<SourceTierSnapshot> get() {
            calls.incrementAndGet();
            Sinks.One<SourceTierSnapshot> sink = Sinks.one();
            pending.add(sink);
            return sink.asMono();
        }

        Sinks.One<SourceTierSnapshot> next() {
            return pending.remove();
        }
    }
}
