package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos;

class AccountPoolPolicyServiceTest {

    private static final AccountDtos.PoolPolicySettings DISABLED = settings(false);
    private static final AccountDtos.PoolPolicySettings ENABLED = settings(true);

    private AccountSettingsService settingsService;
    private DatabaseClient databaseClient;
    private AccountPoolPolicyService service;
    private ProxyAccountEntity primary;
    private ProxyAccountEntity secondary;

    @BeforeEach
    void setUp() {
        settingsService = mock(AccountSettingsService.class);
        databaseClient = mock(DatabaseClient.class);
        when(settingsService.defaultPoolPolicySettings()).thenReturn(DISABLED);
        ModelPoolKeyResolver resolver = new ModelPoolKeyResolver(new DefaultModelCatalogProvider());
        service = new AccountPoolPolicyService(
            settingsService,
            databaseClient,
            resolver,
            Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );
        primary = account("openai-primary", AccountPlatforms.OPENAI_PROVIDER, "primary", 4);
        secondary = account("openai-secondary", AccountPlatforms.OPENAI_PROVIDER, "secondary", 0);
    }

    @Test
    void circuitFailureSwitchesOnlyTheMatchingModelFamilyToSecondary() {
        service.replaceSettings(ENABLED);
        AccountPoolRequestContext gpt5 = context("gpt-5", "gpt-5.2");
        AccountPoolRequestContext gpt4 = context("gpt-4", "gpt-4o");

        service.recordFailure(gpt5, primary);
        service.recordFailure(gpt5, primary);

        assertThat(service.applyTierPolicy(List.of(primary, secondary), gpt5))
            .extracting(ProxyAccountEntity::getAccountId)
            .containsExactly("openai-secondary");
        assertThat(service.applyTierPolicy(List.of(primary, secondary), gpt4))
            .extracting(ProxyAccountEntity::getAccountId)
            .containsExactly("openai-primary");
    }

    @Test
    void disablingPolicyImmediatelyClearsCircuitsAndBypassesTieringAndAutoWeight() {
        service.replaceSettings(ENABLED);
        AccountPoolRequestContext context = context("gpt-5", "gpt-5.2");
        service.recordFailure(context, primary);
        service.recordFailure(context, primary);
        assertThat(service.effectiveWeight(primary)).isLessThan(100);

        service.replaceSettings(DISABLED);

        assertThat(service.applyTierPolicy(List.of(primary, secondary), context))
            .containsExactly(primary, secondary);
        assertThat(service.effectiveWeight(primary)).isEqualTo(100);
    }

    @Test
    void disabledPolicyPreservesTheOriginalAffinityKey() {
        AccountPoolRequestContext context = context("request-1", "gpt-5.2");

        assertThat(service.affinityKey(context, "session-1")).isEqualTo("session-1");

        service.replaceSettings(ENABLED);

        assertThat(service.affinityKey(context, "session-1")).isEqualTo("session-1");
        assertThat(service.affinityKey(context("request-2", "gpt-5.2"), "session-1"))
            .isEqualTo("openai:gpt-5:session-1");
    }

    @Test
    void failedRefreshPreservesLastKnownSettingsAndOpenCircuits() {
        service.replaceSettings(ENABLED);
        AccountPoolRequestContext context = context("gpt-5", "gpt-5.2");
        service.recordFailure(context, primary);
        service.recordFailure(context, primary);
        assertThat(service.applyTierPolicy(List.of(primary, secondary), context)).containsExactly(secondary);
        when(settingsService.poolPolicySettings()).thenReturn(Mono.error(new IllegalStateException("database unavailable")));

        service.invalidateAllCaches();
        assertThatThrownBy(() -> service.refreshSettings().block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("database unavailable");

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.applyTierPolicy(List.of(primary, secondary), context)).containsExactly(secondary);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void staleRefreshCannotOverwriteANewerExplicitSetting() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec fetch = mock(RowsFetchSpec.class);
        Sinks.Empty<Void> queryGate = Sinks.empty();
        when(settingsService.poolPolicySettings()).thenReturn(Mono.just(ENABLED));
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn(fetch);
        when(fetch.all()).thenReturn(queryGate.asMono().thenMany(Flux.empty()));
        Mono<Void> staleRefresh = service.refreshSettings();
        staleRefresh.subscribe();

        service.replaceSettings(DISABLED);
        queryGate.tryEmitEmpty();
        staleRefresh.block();

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void statusExposesEveryKnownProviderFamilyWithProviderScopedAccountCounts() {
        service.replaceSettings(ENABLED);
        ProxyAccountEntity deepSeek = account(
            "deepseek-primary",
            AccountPlatforms.DEEPSEEK_PROVIDER,
            "primary",
            0
        );
        ProxyAccountEntity disabled = account("disabled", AccountPlatforms.OPENAI_PROVIDER, "primary", 0);
        disabled.setEnabled(false);
        ProxyAccountEntity suspended = account("suspended", AccountPlatforms.OPENAI_PROVIDER, "secondary", 0);
        suspended.setSuspendedAt(Instant.parse("2026-08-09T00:00:00Z"));
        ProxyAccountEntity exhausted = account("exhausted", AccountPlatforms.OPENAI_PROVIDER, "secondary", 0);
        exhausted.setQuotaUsed(100L);
        exhausted.setQuotaLimit(100L);
        exhausted.setQuotaResetAt(Instant.parse("2026-08-10T01:00:00Z"));

        AccountPoolService.PoolAccountInventory inventory = new AccountPoolService.PoolAccountInventory(
            6,
            3,
            List.of(
                new AccountPoolService.PoolAccountGroup(
                    primary.getProvider(),
                    primary.getAuthMethod(),
                    primary.getBaseUrl(),
                    primary.getPoolTier(),
                    1
                ),
                new AccountPoolService.PoolAccountGroup(
                    secondary.getProvider(),
                    secondary.getAuthMethod(),
                    secondary.getBaseUrl(),
                    secondary.getPoolTier(),
                    1
                ),
                new AccountPoolService.PoolAccountGroup(
                    deepSeek.getProvider(),
                    deepSeek.getAuthMethod(),
                    deepSeek.getBaseUrl(),
                    deepSeek.getPoolTier(),
                    1
                )
            )
        );

        AccountDtos.AccountPoolStatusResponse status = service.status(false, inventory).block();

        assertThat(status).isNotNull();
        assertThat(status.accountCount()).isEqualTo(6);
        assertThat(status.availableAccountCount()).isEqualTo(3);
        assertThat(status.modelPools()).isNotEmpty();
        assertThat(status.modelPools())
            .filteredOn(pool -> "openai:gpt-5".equals(pool.key()))
            .singleElement()
            .satisfies(pool -> {
                assertThat(pool.provider()).isEqualTo("OpenAI");
                assertThat(pool.modelFamily()).isEqualTo("GPT-5");
                assertThat(pool.primaryAccountCount()).isEqualTo(1);
                assertThat(pool.secondaryAccountCount()).isEqualTo(1);
            });
        assertThat(status.modelPools())
            .filteredOn(pool -> "deepseek:deepseek-v4".equals(pool.key()))
            .singleElement()
            .satisfies(pool -> {
                assertThat(pool.primaryAccountCount()).isEqualTo(1);
                assertThat(pool.secondaryAccountCount()).isZero();
            });
    }

    @Test
    void disabledStatusSkipsInventoryAndOmitsCircuitDetails() {
        AtomicBoolean inventoryRequested = new AtomicBoolean(false);

        AccountDtos.AccountPoolStatusResponse status = service.status(false, () -> {
            inventoryRequested.set(true);
            return Mono.just(new AccountPoolService.PoolAccountInventory(99, 88, List.of()));
        }).block();

        assertThat(status).isNotNull();
        assertThat(status.enabled()).isFalse();
        assertThat(status.accountCount()).isZero();
        assertThat(status.availableAccountCount()).isZero();
        assertThat(status.modelPools()).isEmpty();
        assertThat(status.failureRanking()).isEmpty();
        assertThat(inventoryRequested).isFalse();
    }

    @Test
    void statusRechecksTheEpochAfterTheAsynchronousInventoryQuery() {
        service.replaceSettings(ENABLED);
        Sinks.One<AccountPoolService.PoolAccountInventory> inventory = Sinks.one();
        CompletableFuture<AccountDtos.AccountPoolStatusResponse> response = service
            .status(false, inventory::asMono)
            .toFuture();

        service.replaceSettings(DISABLED);
        inventory.tryEmitValue(new AccountPoolService.PoolAccountInventory(10, 9, List.of()));

        AccountDtos.AccountPoolStatusResponse status = response.join();
        assertThat(status.enabled()).isFalse();
        assertThat(status.accountCount()).isZero();
        assertThat(status.modelPools()).isEmpty();
    }

    @Test
    void aCatalogRefreshCannotMoveAnInFlightRequestOutcomeToAnotherFamily() {
        MutableCatalog catalog = new MutableCatalog(List.of(model("team-model", "Acme AI", "Alpha")));
        ModelPoolKeyResolver resolver = new ModelPoolKeyResolver(catalog);
        AccountPoolPolicyService dynamicService = new AccountPoolPolicyService(
            settingsService,
            databaseClient,
            resolver,
            Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );
        dynamicService.replaceSettings(ENABLED);
        UUID epoch = dynamicService.currentCircuitEpoch();
        ModelPoolKey alpha = dynamicService.capturePoolKey("team-model", "team-model", "Acme AI", epoch);
        AccountPoolRequestContext inFlight = new AccountPoolRequestContext(
            "alpha-request",
            "team-model",
            "team-model",
            "Acme AI",
            epoch,
            alpha
        );

        catalog.replace(List.of(model("team-model", "Acme AI", "Beta")));
        dynamicService.recordFailure(inFlight, primary);
        dynamicService.recordFailure(inFlight, primary);

        assertThat(dynamicService.applyTierPolicy(List.of(primary, secondary), inFlight))
            .containsExactly(secondary);
        ModelPoolKey beta = dynamicService.capturePoolKey("team-model", "team-model", "Acme AI", epoch);
        AccountPoolRequestContext nextRequest = new AccountPoolRequestContext(
            "beta-request",
            "team-model",
            "team-model",
            "Acme AI",
            epoch,
            beta
        );
        assertThat(beta.key()).isEqualTo("acme-ai:beta");
        assertThat(dynamicService.applyTierPolicy(List.of(primary, secondary), nextRequest))
            .containsExactly(primary);

        AccountDtos.AccountPoolStatusResponse status = dynamicService
            .status(false, AccountPoolService.PoolAccountInventory.empty())
            .block();
        assertThat(status.modelPools())
            .extracting(AccountDtos.ModelPoolCircuitStatusResponse::key)
            .contains("acme-ai:alpha", "acme-ai:beta");
    }

    @Test
    void circuitCreatedAfterSnapshotEnumerationIsReRegisteredAfterCleanup() throws Exception {
        MutableCatalog catalog = new MutableCatalog(List.of(model("team-model", "Acme AI", "Alpha Prime")));
        BlockingClock clock = new BlockingClock(Instant.parse("2026-08-10T00:00:00Z"));
        AccountPoolPolicyService dynamicService = new AccountPoolPolicyService(
            settingsService,
            databaseClient,
            new ModelPoolKeyResolver(catalog),
            clock
        );
        dynamicService.replaceSettings(ENABLED);
        UUID epoch = dynamicService.currentCircuitEpoch();
        ModelPoolKey alpha = dynamicService.capturePoolKey("team-model", "team-model", "Acme AI", epoch);
        AccountPoolRequestContext request = new AccountPoolRequestContext(
            "alpha-request",
            "team-model",
            "team-model",
            "Acme AI",
            epoch,
            alpha
        );
        catalog.replace(List.of());
        CompletableFuture<AccountDtos.AccountPoolStatusResponse> status = CompletableFuture.supplyAsync(() -> {
            clock.blockCurrentThreadOnCall(2);
            return dynamicService.status(false, AccountPoolService.PoolAccountInventory.empty()).block();
        });
        assertThat(clock.awaitBlocked()).isTrue();
        try {
            dynamicService.recordFailure(request, primary);
        } finally {
            clock.release();
        }
        status.join();

        assertThat(observedPools(dynamicService).get(alpha.key()))
            .isEqualTo(alpha)
            .extracting(ModelPoolKey::providerLabel, ModelPoolKey::familyLabel)
            .containsExactly("Acme AI", "Alpha Prime");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reconciliationUsesTheExplicitUpstreamOutcomeMarkerForItsCoveringIndexWindow() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec fetch = mock(RowsFetchSpec.class);
        AtomicReference<String> capturedSql = new AtomicReference<>();
        when(settingsService.poolPolicySettings()).thenReturn(Mono.just(ENABLED));
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            capturedSql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn(fetch);
        when(fetch.all()).thenReturn(Flux.empty());

        AccountPoolPolicyService.PoolEvaluation evaluation = service.evaluate().block();

        assertThat(evaluation).isNotNull();
        assertThat(capturedSql.get()).contains("AND upstream_success IS NOT NULL");
        assertThat(capturedSql.get()).contains("WHERE circuit_epoch = :circuitEpoch");
        assertThat(capturedSql.get()).doesNotContain("status_code >= 500");
        verify(spec).bind("circuitEpoch", ENABLED.circuitEpoch());
        verify(databaseClient).sql(anyString());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reconciliationStartsAtTheCurrentActivationBoundary() {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec fetch = mock(RowsFetchSpec.class);
        Instant activationStartedAt = Instant.parse("2026-08-09T23:59:30Z");
        UUID epoch = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AccountDtos.PoolPolicySettings activated = new AccountDtos.PoolPolicySettings(
            true,
            5,
            2,
            50.0,
            50.0,
            70.0,
            5,
            1,
            true,
            epoch,
            activationStartedAt
        );
        when(settingsService.poolPolicySettings()).thenReturn(Mono.just(activated));
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn(fetch);
        when(fetch.all()).thenReturn(Flux.empty());

        service.evaluate().block();

        verify(spec).bind("circuitEpoch", epoch);
        verify(spec).bind("since", activationStartedAt);
    }

    @Test
    void outcomesFromAnOlderActivationEpochCannotMutateTheCurrentCircuit() {
        UUID oldEpoch = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID currentEpoch = UUID.fromString("22222222-2222-2222-2222-222222222222");
        AccountDtos.PoolPolicySettings first = settings(true, oldEpoch);
        AccountDtos.PoolPolicySettings second = settings(true, currentEpoch);
        service.replaceSettings(first);
        AccountPoolRequestContext stale = new AccountPoolRequestContext(
            "stale",
            "gpt-5.2",
            "gpt-5.2",
            AccountPlatforms.OPENAI_PROVIDER,
            oldEpoch
        );

        service.replaceSettings(second);
        service.recordFailure(stale, primary);
        service.recordFailure(stale, primary);

        AccountPoolRequestContext current = new AccountPoolRequestContext(
            "current",
            "gpt-5.2",
            "gpt-5.2",
            AccountPlatforms.OPENAI_PROVIDER,
            currentEpoch
        );
        assertThat(service.applyTierPolicy(List.of(primary, secondary), current))
            .containsExactly(primary);
    }

    private AccountPoolRequestContext context(String requestToken, String model) {
        return new AccountPoolRequestContext(
            requestToken,
            model,
            model,
            AccountPlatforms.OPENAI_PROVIDER,
            service.currentCircuitEpoch()
        );
    }

    private ProxyAccountEntity account(String accountId, String provider, String tier, int errors) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setPoolTier(tier);
        account.setPoolWeight(100);
        account.setErrorCount(errors);
        account.setEnabled(true);
        return account;
    }

    private static AccountDtos.PoolPolicySettings settings(boolean enabled) {
        return new AccountDtos.PoolPolicySettings(
            enabled,
            5,
            2,
            50.0,
            50.0,
            70.0,
            5,
            1,
            true
        );
    }

    private static AccountDtos.PoolPolicySettings settings(boolean enabled, UUID epoch) {
        AccountDtos.PoolPolicySettings base = settings(enabled);
        return new AccountDtos.PoolPolicySettings(
            base.enabled(),
            base.windowMinutes(),
            base.minSampleSize(),
            base.retestThresholdPercent(),
            base.failoverThresholdPercent(),
            base.recoveryThresholdPercent(),
            base.retestIntervalMinutes(),
            base.retestMaxConcurrency(),
            base.autoWeightEnabled(),
            epoch,
            Instant.parse("2026-08-09T23:55:00Z")
        );
    }

    private static ModelDtos.ModelSpec model(String id, String provider, String family) {
        return new ModelDtos.ModelSpec(
            id,
            id,
            provider,
            family,
            "test",
            "test model",
            ModelCatalogProvider.TEXT_ONLY,
            128_000,
            8_000,
            id
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPoolKey> observedPools(AccountPoolPolicyService policyService)
        throws ReflectiveOperationException {
        Field runtimeState = AccountPoolPolicyService.class.getDeclaredField("runtimeState");
        runtimeState.setAccessible(true);
        Object runtime = runtimeState.get(policyService);
        Field observedPools = runtime.getClass().getDeclaredField("observedPools");
        observedPools.setAccessible(true);
        return (Map<String, ModelPoolKey>) observedPools.get(runtime);
    }

    private static final class MutableCatalog implements ModelCatalogProvider {

        private final AtomicReference<List<ModelDtos.ModelSpec>> models;

        private MutableCatalog(List<ModelDtos.ModelSpec> models) {
            this.models = new AtomicReference<>(List.copyOf(models));
        }

        private void replace(List<ModelDtos.ModelSpec> replacement) {
            models.set(List.copyOf(replacement));
        }

        @Override
        public List<ModelDtos.ModelSpec> models() {
            return models.get();
        }

        @Override
        public Optional<String> targetFor(String modelId) {
            return Optional.empty();
        }
    }

    private static final class BlockingClock extends Clock {

        private final Clock delegate;
        private final AtomicReference<Thread> targetThread = new AtomicReference<>();
        private final AtomicInteger targetCalls = new AtomicInteger();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile int blockOnCall = Integer.MAX_VALUE;

        private BlockingClock(Instant instant) {
            this.delegate = Clock.fixed(instant, ZoneOffset.UTC);
        }

        private void blockCurrentThreadOnCall(int call) {
            targetThread.set(Thread.currentThread());
            blockOnCall = call;
        }

        private boolean awaitBlocked() throws InterruptedException {
            return blocked.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return delegate.withZone(zone);
        }

        @Override
        public Instant instant() {
            if (Thread.currentThread() == targetThread.get()
                && targetCalls.incrementAndGet() == blockOnCall) {
                blocked.countDown();
                try {
                    if (!released.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the status snapshot");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding the status snapshot", error);
                }
            }
            return delegate.instant();
        }
    }
}
