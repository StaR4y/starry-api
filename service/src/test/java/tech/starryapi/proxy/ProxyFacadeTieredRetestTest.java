package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionRequest;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierRoute;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierWalk;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The automatic retest of a tiered request that found no account (design 4.10): once per request, after a whole round
 * of the tiers came up empty, and only for the accounts the plan could serve the request from. The retest service is a
 * stand-in that tests the out-of-service accounts the filter it is given takes, so which accounts get tested is what
 * the request asked for, and that restores the ones a test says recover.
 */
class ProxyFacadeTieredRetestTest {

    private static final String SESSION = "key-1:session-1";
    private static final String ENDPOINT = "/v1/chat/completions";
    private static final String REQUEST_ID = "request-1";
    private static final PlatformCompletionResult ANSWER =
        new PlatformCompletionResult("OK", new PlatformUsage(4, 6, BigDecimal.ZERO, 0, 0, 0));
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final AccountAdmissionStore admissionStore = mock(AccountAdmissionStore.class);
    private final DisabledAccountScheduledTestService retestService = mock(DisabledAccountScheduledTestService.class);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final AccountSettingsService accountSettings = mock(AccountSettingsService.class);
    private final ModelMappingService modelMapping = mock(ModelMappingService.class);
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
    private final AnthropicUpstreamClient anthropicClient = mock(AnthropicUpstreamClient.class);
    private final KiroUpstreamClient kiroClient = mock(KiroUpstreamClient.class);
    private final OpenAiUpstreamClient openAiClient = mock(OpenAiUpstreamClient.class);
    private final OpenAiResponseOwnerStore ownerStore = mock(OpenAiResponseOwnerStore.class);
    private final StatsService statsService = mock(StatsService.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);
    private final RoutingPolicySettings policy = RoutingPolicySettings.defaults();

    /** The accounts of the pool's cache, in the order it offers them. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** The accounts the planner finds in the pool's cache; every tier has one unless a test takes it out. */
    private final List<ProxyAccountEntity> planning = new ArrayList<>();
    /** The accounts of {@link #planning} that are suspended or out of quota, which leaves their tier out of a plan. */
    private final Set<String> unusablePlanning = new HashSet<>();
    /** Accounts the pool does not offer because they are disabled, suspended or out of quota: what a retest is for. */
    private final List<ProxyAccountEntity> outOfService = new ArrayList<>();
    /** The out-of-service accounts that a test brings back. */
    private final Set<String> recovers = new HashSet<>();
    /** Accounts admission refuses. */
    private final Set<String> busy = new HashSet<>();
    private final List<String> tested = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> dispatched = new ArrayList<>();
    private int poolCalls;
    private int retests;
    /** What the retest setting does: false is the setting off, which the service answers with nothing. */
    private boolean retestSettingOn = true;
    private RuntimeException retestFailure;

    private final ProxyFacade facade = new ProxyFacade(
        apiKeyService,
        accountPool,
        mock(AccountAutoSuspendService.class),
        accountSettings,
        retestService,
        modelCatalog,
        modelMapping,
        rateLimiter,
        new KiroPayloadFactory(objectMapper, modelCatalog),
        kiroClient,
        openAiClient,
        new OpenAiRequestAdapter(objectMapper, modelCatalog),
        new OpenAiResponseFactory(objectMapper),
        new OpenAiResponseStateStore(objectMapper),
        new AnthropicRequestAdapter(objectMapper, modelCatalog),
        new AnthropicResponseFactory(objectMapper),
        anthropicClient,
        new GeminiRequestAdapter(objectMapper),
        new GeminiResponseFactory(objectMapper),
        payments,
        pricing,
        statsService,
        admissionStore,
        ownerStore,
        TierRuntime.disabled()
    );

    @BeforeEach
    void standInForTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString()))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59))));
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.empty());
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenReturn(Mono.just(policy));
        when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(accountPool.recordSuccess(anyString(), anyLong(), any())).thenReturn(Mono.empty());
        when(accountPool.cachedAccount(anyString())).thenAnswer(invocation ->
            cached.stream().filter(account -> account.getAccountId().equals(invocation.getArgument(0))).findFirst());
        // Every tier has an account somewhere as far as planning goes, so that no tier is left out of a plan (an account
        // that is out of service is one a retest brings back); what is offered is decided at selection.
        planning.addAll(List.of(
            officialKey("planning-official"), kiro("planning-kiro"), officialOpenAi("planning-openai"), codex("planning-codex")));
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return planning.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenAnswer(invocation ->
            !unusablePlanning.contains(invocation.<ProxyAccountEntity>getArgument(0).getAccountId()));
        when(accountPool.selectInScope(any(), any(), anyString(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(1);
            poolCalls++;
            Optional<ProxyAccountEntity> found = cached.stream().filter(filter).findFirst();
            return found.<Mono<ProxyAccountEntity>>map(Mono::just).orElseGet(() -> Mono.error(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", invocation.getArgument(2))));
        });
        when(accountPool.refreshCacheIfStale()).thenAnswer(invocation -> Mono.fromRunnable(() -> events.add("reload")));
        when(admissionStore.acquire(any())).thenAnswer(invocation -> {
            AdmissionRequest request = invocation.getArgument(0);
            return Mono.just(busy.contains(request.accountId())
                ? new AdmissionDecision(false, null, "ACCOUNT_BUSY", null, null)
                : new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600)));
        });
        when(admissionStore.markDispatched(any())).thenReturn(Mono.empty());
        when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
        when(admissionStore.releaseUnsent(any())).thenReturn(Mono.empty());
        when(admissionStore.abandon(any(), anyString())).thenReturn(Mono.empty());
        when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0)));
        when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0)));
        when(retestService.runWhenNoAvailableAccounts(any())).thenAnswer(invocation -> {
            retests++;
            events.add("retest");
            if (retestFailure != null) {
                return Mono.error(retestFailure);
            }
            if (!retestSettingOn) {
                return Mono.empty();
            }
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            List<ProxyAccountEntity> targets = outOfService.stream().filter(filter).toList();
            List<ProxyAccountEntity> back = targets.stream().filter(account -> recovers.contains(account.getAccountId())).toList();
            targets.forEach(account -> tested.add(account.getAccountId()));
            outOfService.removeAll(back);
            cached.addAll(back);
            return Mono.just(new AccountDtos.TestAccountsResponse(
                targets.size(), back.size(), targets.size() - back.size(), 0, Instant.now(), Instant.now(), List.of()));
        });
    }

    // ---- when the retest is made ----

    @Test
    void aRoundThatFindsNothingReloadsTheCacheThenTestsTheAccountsOutOfServiceAndWalksTheTiersAgain() {
        loaded(anthropicTiers(16));
        outOfService(officialKey("official-1"));
        recovers.add("official-1");
        TierWalk walk = new TierWalk(anthropicPlan());

        ProxyFacade.AccountAttempt attempt = select(walk);

        assertThat(attempt.account().getAccountId()).isEqualTo("official-1");
        assertThat(attempt.selection().level().tierId()).isEqualTo("official");
        assertThat(tested).containsExactly("official-1");
        // The cache is reloaded first, as for any request; the retest is the last thing tried.
        assertThat(events).containsExactly("reload", "retest");
        // Four cells in the round that found nothing, four after the reload, and the best tier's first cell now has one.
        assertThat(poolCalls).isEqualTo(9);
        assertThat(walk.refusals()).isZero();
    }

    @Test
    void anAccountFoundWithoutItNeverTestsAnything() {
        loaded(anthropicTiers(16));
        add(kiro("kiro-1"));
        outOfService(officialKey("official-1"));
        recovers.add("official-1");

        assertThat(select(new TierWalk(anthropicPlan())).account().getAccountId()).isEqualTo("kiro-1");

        verify(retestService, never()).runWhenNoAvailableAccounts(any());
    }

    @Test
    void theRetestIsMadeOncePerRequest() {
        loaded(anthropicTiers(16));
        outOfService(officialKey("official-1"));
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);
        assertThat(retests).isEqualTo(1);
        // A later selection of the same request (a retry after a failed dispatch) is not tested for again.
        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);

        assertThat(retests).isEqualTo(1);
        assertThat(tested).containsExactly("official-1");
    }

    @Test
    void aRetestThatBringsBackNothingLeavesTheRequestWithoutAnAccountAndTheTiersAreNotWalkedAgain() {
        loaded(anthropicTiers(16));
        outOfService(officialKey("official-1"), kiro("kiro-1"));
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
            });

        assertThat(tested).containsExactly("official-1", "kiro-1");
        assertThat(poolCalls).isEqualTo(8);
        verify(admissionStore, never()).acquire(any());
    }

    @Test
    void withTheSettingOffThereIsNothingToTestAndNothingToWalkAgain() {
        loaded(anthropicTiers(16));
        outOfService(officialKey("official-1"));
        recovers.add("official-1");
        retestSettingOn = false;

        assertThatThrownBy(() -> select(new TierWalk(anthropicPlan())))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));

        assertThat(retests).isEqualTo(1);
        assertThat(tested).isEmpty();
        assertThat(poolCalls).isEqualTo(8);
    }

    @Test
    void aRetestThatFailsLeavesTheRequestWithNoAvailableAccountNotWithTheFailure() {
        loaded(anthropicTiers(16));
        outOfService(officialKey("official-1"));
        retestFailure = new IllegalStateException("database unavailable");

        assertThatThrownBy(() -> select(new TierWalk(anthropicPlan())))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
            });

        assertThat(poolCalls).isEqualTo(8);
    }

    // ---- which accounts are tested ----

    @Test
    void onlyTheAccountsOfTheTiersThePlanHoldsAreTested() {
        loaded(anthropicTiers(16));
        outOfService(
            officialKey("official-1"), kiro("kiro-1"),
            // On other routes: not what an Anthropic request can be sent to, so not worth a test call for it.
            codex("codex-1"), officialOpenAi("openai-1"), account("deepseek-1", "DeepSeek", "api-key"),
            account("google-1", "Google", "api-key"));
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);

        assertThat(tested).containsExactlyInAnyOrder("official-1", "kiro-1");
    }

    @Test
    void anAccountOfATierThatIsSwitchedOffIsNotTestedEither() {
        // The Kiro accounts belong to the second tier, which is off: they serve none of the vendor's tiered requests.
        loaded(SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("anthropic", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                new Tier("anthropic", "kiro", 2, "kiro", new BigDecimal("0.6"), EnumSet.of(AccountAllocationCategory.KIRO),
                    true, false, null)),
            List.of(), List.of(), List.of()));
        outOfService(officialKey("official-1"), kiro("kiro-1"));
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);

        assertThat(tested).containsExactly("official-1");
    }

    @Test
    void theAccountsOfATierThePlanLeftOutForHavingNothingToServeWithAreTestedToo() {
        loaded(anthropicTiers(16));
        // The official tier's account is suspended as far as the planner can tell, which leaves the tier out of the plan.
        unusablePlanning.add("planning-official");
        outOfService(officialKey("official-1"), kiro("kiro-1"));
        recovers.add("official-1");
        TierPlan plan = anthropicPlan();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("official");
        TierWalk walk = new TierWalk(plan);

        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);

        // This request is planned without the tier and cannot be served by it, but without the test the tier would not
        // come back for the ones after it.
        assertThat(tested).containsExactlyInAnyOrder("official-1", "kiro-1");
        assertThat(retests).isEqualTo(1);
    }

    @Test
    void theAccountsOfATierWhoseAccountsAreAllDisabledAreTestedToo() {
        loaded(anthropicTiers(16));
        // Nothing of the official tier is in the cache (every account of it is disabled), so the plan has no such tier.
        planning.removeIf(account -> account.getAccountId().equals("planning-official"));
        outOfService(officialKey("official-1"));
        recovers.add("official-1");
        TierPlan plan = anthropicPlan();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).isEmpty();

        assertThatThrownBy(() -> select(new TierWalk(plan))).isInstanceOf(ApiException.class);

        assertThat(tested).containsExactly("official-1");
    }

    @Test
    void anAccountOfAnotherRouteIsNotTestedForATierThePlanLeftOut() {
        loaded(anthropicTiers(16));
        unusablePlanning.add("planning-official");
        outOfService(officialKey("official-1"), codex("codex-1"), officialOpenAi("openai-1"));
        TierPlan plan = anthropicPlan();

        assertThatThrownBy(() -> select(new TierWalk(plan))).isInstanceOf(ApiException.class);

        assertThat(tested).containsExactly("official-1");
    }

    @Test
    void aRequestRestrictedToOneTierDoesNotTestTheTiersThePlanLeftOut() {
        loaded(anthropicTiers(16));
        unusablePlanning.add("planning-official");
        outOfService(officialKey("official-1"), kiro("kiro-1"));
        TierPlan plan = anthropicPlan();
        TierWalk walk = new TierWalk(plan);
        walk.narrowTo(plan.levels().get(0));

        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);

        assertThat(tested).containsExactly("kiro-1");
    }

    @Test
    void aRequestRestrictedToOneTierTestsOnlyThatTier() {
        loaded(anthropicTiers(16));
        outOfService(officialKey("official-1"), kiro("kiro-1"));
        TierPlan plan = anthropicPlan();
        TierWalk walk = new TierWalk(plan);
        walk.narrowTo(plan.levels().get(1));

        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);

        assertThat(tested).containsExactly("kiro-1");
    }

    @Test
    void aTierThatHasRefusedTheRequestAsOftenAsTheVendorAllowsIsNotTestedForIt() {
        loaded(anthropicTiers(1));
        add(officialKey("official-busy"));
        busy.add("official-busy");
        outOfService(officialKey("official-1"), kiro("kiro-1"));
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);

        assertThat(walk.refusals()).isEqualTo(1);
        // The best tier is closed to this request, so an official key coming back would not serve it.
        assertThat(tested).containsExactly("kiro-1");
    }

    @Test
    void aRequestStartingBelowTheBestTierAlsoTestsTheTiersItMayClimbBackTo() {
        loaded(anthropicTiers(16));
        outOfService(officialKey("official-1"), kiro("kiro-1"));
        recovers.add("official-1");
        TierWalk walk = new TierWalk(anthropicPlan(), 1);

        ProxyFacade.AccountAttempt attempt = select(walk);

        assertThat(tested).containsExactlyInAnyOrder("official-1", "kiro-1");
        assertThat(attempt.account().getAccountId()).isEqualTo("official-1");
        assertThat(attempt.selection().climbed()).isTrue();
    }

    @Test
    void whetherCodexTokensAreTestedFollowsTheRoutesCodexIsolation() {
        loaded(openAiTiers());
        outOfService(officialOpenAi("openai-1"), codex("codex-1"), kiro("kiro-1"));

        // Isolation off: Codex tokens and official keys are one pool, and the second tier holds the tokens.
        assertThatThrownBy(() -> select(new TierWalk(openAiPlan(false, null, false)), false)).isInstanceOf(ApiException.class);
        assertThat(tested).containsExactlyInAnyOrder("openai-1", "codex-1");

        tested.clear();
        // Isolation on: the route only sends to official keys, so a Codex token is not worth a test.
        assertThatThrownBy(() -> select(new TierWalk(openAiPlan(true, null, false)), false)).isInstanceOf(ApiException.class);
        assertThat(tested).containsExactly("openai-1");
    }

    // ---- the owner of a response chain ----

    @Test
    void aChainThatCannotLeaveItsOwnerIsNotTestedForWhenTheOwnerIsGone() {
        loaded(openAiTiers());
        add(officialOpenAi("openai-1"), codex("codex-owner"));
        outOfService(codex("codex-2"));
        recovers.add("codex-2");
        TierWalk walk = new TierWalk(openAiPlan(false, "codex-owner", false));
        // The owner was suspended after the request was planned, and the chain cannot be replayed on another account.
        cached.removeIf(account -> account.getAccountId().equals("codex-owner"));

        assertThatThrownBy(() -> select(walk, false))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
                assertThat(error.getMessage()).isEqualTo("The account that owns the OpenAI response is unavailable");
            });

        verify(retestService, never()).runWhenNoAvailableAccounts(any());
    }

    @Test
    void aChainThatMayLeaveItsOwnerIsServedByAnotherAccountOfTheTiersAfterTheRetest() {
        loaded(openAiTiers());
        add(codex("codex-owner"));
        outOfService(officialOpenAi("openai-1"));
        recovers.add("openai-1");
        TierWalk walk = new TierWalk(openAiPlan(false, "codex-owner", true));
        cached.removeIf(account -> account.getAccountId().equals("codex-owner"));

        ProxyFacade.AccountAttempt attempt = select(walk, true);

        assertThat(attempt.account().getAccountId()).isEqualTo("openai-1");
        assertThat(attempt.selection().level().tierId()).isEqualTo("official");
        assertThat(retests).isEqualTo(1);
    }

    // ---- through the dispatch loop ----

    @Test
    void aRequestIsServedByAnAccountTheRetestBroughtBackAndPaysTheTierThatServed() {
        loaded(anthropicTiers(16));
        outOfService(kiro("kiro-1"));
        recovers.add("kiro-1");

        ResponseEntity<?> response = facade
            .serveTieredChat(run(anthropicPlan(), chatBody()), ProxyFacade.ResponseMode.OPENAI_CHAT).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(dispatched).containsExactly("kiro-1");
        assertThat(retests).isEqualTo(1);
        verify(apiKeyService).accountUsageServed(eq(principal), eq(REQUEST_ID), any(), eq(4L), eq(6L), eq("anthropic:kiro"), eq(2),
            any());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void aRequestThatEvenTheRetestCannotHelpEndsWithNoAvailableAccountAndItsReservationIsReleased() {
        loaded(anthropicTiers(16));
        outOfService(officialKey("official-1"));

        assertThatThrownBy(() -> facade
            .serveTieredChat(run(anthropicPlan(), chatBody()), ProxyFacade.ResponseMode.OPENAI_CHAT).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));

        assertThat(dispatched).isEmpty();
        assertThat(retests).isEqualTo(1);
        // Nothing was dispatched, so the reservation is still RESERVED and the release hands all of it back; the 503 is
        // not one of the failures that count as unbilled, exactly as for an untiered request.
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(false), anyString());
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString());
    }

    // ---- fixtures ----

    private ProxyFacade.AccountAttempt select(TierWalk walk) {
        return select(walk, false);
    }

    private ProxyFacade.AccountAttempt select(TierWalk walk, boolean ownerMayBeLeft) {
        return facade.selectTieredAccountWithAdmission(walk, SESSION, policy, ownerMayBeLeft, REQUEST_ID, chatBody(), ENDPOINT).block();
    }

    private ProxyFacade.TieredRequest run(TierPlan plan, JsonNode body) {
        String model = body.path("model").asString();
        return new ProxyFacade.TieredRequest(plan, principal, REQUEST_ID, ENDPOINT, model, model, SESSION, false,
            ProxyFacade.UpstreamRoute.ANTHROPIC, false, false, false, policy, body, body, Instant.now(), true, true);
    }

    private Mono<PlatformCompletionResult> dispatch(ProxyAccountEntity account) {
        dispatched.add(account.getAccountId());
        return Mono.just(ANSWER);
    }

    private void add(ProxyAccountEntity... accounts) {
        cached.addAll(List.of(accounts));
    }

    private void outOfService(ProxyAccountEntity... accounts) {
        for (ProxyAccountEntity account : accounts) {
            account.setEnabled(false);
            outOfService.add(account);
        }
    }

    private void loaded(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    private TierPlan anthropicPlan() {
        return plan(new TierPlanRequest(REQUEST_ID, "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, chatBody(), null, false, null, null));
    }

    private TierPlan openAiPlan(boolean isolateCodex, String owner, boolean movable) {
        return plan(new TierPlanRequest(REQUEST_ID, "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, isolateCodex, false, true, chatBody(), owner, movable, null, null));
    }

    private TierPlan plan(TierPlanRequest request) {
        ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(modelCatalog);
        return new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, accountPool, payments, pricing, TierSessionBindings.disabled())
            .plan(request).block();
    }

    private JsonNode chatBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private static SourceTierSnapshot anthropicTiers(int refusalCap) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, refusalCap, true, 5L)),
            List.of(
                tier("anthropic", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("anthropic", "kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)),
            List.of(), List.of(), List.of());
    }

    private static SourceTierSnapshot openAiTiers() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, "0.5", AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of());
    }

    private static Tier tier(String vendor, String tierId, int level, String factor, AccountAllocationCategory... categories) {
        return new Tier(vendor, tierId, level, tierId, new BigDecimal(factor), EnumSet.copyOf(List.of(categories)), true, true, null);
    }

    private static ProxyAccountEntity officialKey(String accountId) {
        return account(accountId, "Anthropic", "api-key");
    }

    private static ProxyAccountEntity kiro(String accountId) {
        ProxyAccountEntity account = account(accountId, "BuilderId", "idc");
        account.setAccessToken("kiro-token");
        account.setRegion("us-east-1");
        return account;
    }

    private static ProxyAccountEntity officialOpenAi(String accountId) {
        return account(accountId, "OpenAI", "api-key");
    }

    private static ProxyAccountEntity codex(String accountId) {
        return account(accountId, "OpenAI", "codex");
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setEnabled(true);
        return account;
    }

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
