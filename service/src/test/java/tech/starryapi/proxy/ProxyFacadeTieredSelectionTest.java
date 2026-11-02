package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionRequest;
import tech.starryapi.account.allocation.AccountAllocationCategory;
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
import tech.starryapi.proxy.tier.TierDispatch;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
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
 * How a tiered request picks its account (design 4.2 to 4.4): the tiers in order and each tier's cells in the route's
 * order, a tier that has nothing to offer or that keeps refusing handing over to the next, the counts kept for the
 * whole request, and the owner of a response chain chosen from its own tier.
 *
 * <p>The account pool is a stand-in that applies the filter it is given to a list of accounts, so what the walk asks
 * for (the cell's rule, the tier's members, the accounts already met) decides what it gets.
 */
class ProxyFacadeTieredSelectionTest {

    private static final String SESSION = "key-1:session-1";
    private static final String ENDPOINT = "/v1/chat/completions";
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final AccountAdmissionStore admissionStore = mock(AccountAdmissionStore.class);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final RoutingPolicySettings policy = RoutingPolicySettings.defaults();

    /** The accounts of the pool's cache, in the order it offers them. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** Accounts the pool does not offer: suspended, out of quota, cooling down. */
    private final Set<String> unavailable = new HashSet<>();
    /** Accounts admission refuses. */
    private final Set<String> busy = new HashSet<>();
    private final List<PoolCall> poolCalls = new ArrayList<>();
    private final List<AdmissionRequest> admissions = new ArrayList<>();
    private int cacheReloads;
    private Runnable onReload = () -> { };
    /** When set, the pool offers this account whatever it was asked for. */
    private ProxyAccountEntity offeredWhatever;

    private final ProxyFacade facade = new ProxyFacade(
        mock(ApiKeyService.class),
        accountPool,
        mock(AccountAutoSuspendService.class),
        mock(AccountSettingsService.class),
        mock(DisabledAccountScheduledTestService.class),
        modelCatalog,
        mock(ModelMappingService.class),
        mock(RateLimiterService.class),
        new KiroPayloadFactory(objectMapper, modelCatalog),
        mock(KiroUpstreamClient.class),
        mock(OpenAiUpstreamClient.class),
        new OpenAiRequestAdapter(objectMapper, modelCatalog),
        new OpenAiResponseFactory(objectMapper),
        new OpenAiResponseStateStore(objectMapper),
        new AnthropicRequestAdapter(objectMapper, modelCatalog),
        new AnthropicResponseFactory(objectMapper),
        mock(AnthropicUpstreamClient.class),
        new GeminiRequestAdapter(objectMapper),
        new GeminiResponseFactory(objectMapper),
        payments,
        pricing,
        mock(StatsService.class),
        admissionStore,
        mock(OpenAiResponseOwnerStore.class),
        TierRuntime.disabled()
    );

    private record PoolCall(String hint, String scope, boolean refreshOnEmpty, String found) {
    }

    @BeforeEach
    void standInForThePoolAndAdmission() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        // Every tier has an account somewhere as far as planning goes; what is offered is decided at selection.
        when(accountPool.cachedAccount(anyString())).thenAnswer(invocation ->
            cached.stream().filter(account -> account.getAccountId().equals(invocation.getArgument(0))).findFirst());
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenReturn(true);
        when(accountPool.selectInScope(any(), any(), anyString(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(1);
            Optional<ProxyAccountEntity> found = offeredWhatever != null
                ? Optional.of(offeredWhatever)
                : cached.stream().filter(account -> !unavailable.contains(account.getAccountId())).filter(filter).findFirst();
            poolCalls.add(new PoolCall(invocation.getArgument(0), invocation.getArgument(4), invocation.getArgument(5),
                found.map(ProxyAccountEntity::getAccountId).orElse(null)));
            return found.<Mono<ProxyAccountEntity>>map(Mono::just).orElseGet(() -> Mono.error(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", invocation.getArgument(2))));
        });
        when(accountPool.refreshCacheIfStale()).thenAnswer(invocation -> Mono.fromRunnable(() -> {
            cacheReloads++;
            onReload.run();
        }));
        when(admissionStore.acquire(any())).thenAnswer(invocation -> {
            AdmissionRequest request = invocation.getArgument(0);
            admissions.add(request);
            return Mono.just(busy.contains(request.accountId())
                ? new AdmissionDecision(false, null, "ACCOUNT_BUSY", null, null)
                : new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600)));
        });
    }

    // ---- the walk over tiers and cells ----

    @Test
    void theBestTierIsOfferedFirstAndNothingBelowItIsLookedAtWhileItHasAnAccount() {
        loaded(anthropicTiers(16));
        add(officialKey("official-1"), kiro("kiro-1"));
        TierWalk walk = new TierWalk(anthropicPlan());

        ProxyFacade.AccountAttempt attempt = select(walk);

        assertThat(attempt.account().getAccountId()).isEqualTo("official-1");
        assertThat(attempt.selection().level().tierId()).isEqualTo("official");
        assertThat(attempt.selection().level().level()).isEqualTo(1);
        assertThat(attempt.selection().cell()).isEqualTo(PoolCell.ANTHROPIC_KEY);
        assertThat(attempt.selection().dispatch()).isEqualTo(TierDispatch.nativeDispatch());
        assertThat(attempt.leaseId()).isNotNull();
        // One look, at the first cell of the first tier, within the scope of that tier and model; no cache reload.
        assertThat(poolCalls).containsExactly(new PoolCall(SESSION, "tier:anthropic:official|claude-sonnet-4.5", false, "official-1"));
        assertThat(cacheReloads).isZero();
    }

    @Test
    void aTierWithNothingAvailableHandsOverToTheNextOneThroughItsCellsInTheRoutesOrder() {
        loaded(anthropicTiers(16));
        add(officialKey("official-1"), kiro("kiro-1"));
        unavailable.add("official-1");
        TierWalk walk = new TierWalk(anthropicPlan());

        ProxyFacade.AccountAttempt attempt = select(walk);

        assertThat(attempt.account().getAccountId()).isEqualTo("kiro-1");
        assertThat(attempt.selection().level().tierId()).isEqualTo("kiro");
        assertThat(attempt.selection().cell()).isEqualTo(PoolCell.KIRO_COMPATIBLE);
        // The first tier is asked for both its cells (official keys, then Kiro accounts), the second for both too.
        assertThat(poolCalls).extracting(PoolCall::scope).containsExactly(
            "tier:anthropic:official|claude-sonnet-4.5", "tier:anthropic:official|claude-sonnet-4.5",
            "tier:anthropic:kiro|claude-sonnet-4.5", "tier:anthropic:kiro|claude-sonnet-4.5");
        assertThat(cacheReloads).isZero();
    }

    @Test
    void aCellThatNoAccountCanBelongToDoesNotReloadTheAccountCache() {
        loaded(anthropicTiers(16));
        // Nothing of the first tier exists at all; only the second tier has an account.
        add(kiro("kiro-1"));
        TierWalk walk = new TierWalk(anthropicPlan());

        ProxyFacade.AccountAttempt attempt = select(walk);

        assertThat(attempt.selection().level().tierId()).isEqualTo("kiro");
        assertThat(poolCalls).allSatisfy(call -> assertThat(call.refreshOnEmpty()).isFalse());
        assertThat(cacheReloads).isZero();
    }

    @Test
    void anEmptyRoundReloadsTheCacheOnceAndWalksTheTiersAgain() {
        loaded(anthropicTiers(16));
        onReload = () -> add(kiro("kiro-new"));
        TierWalk walk = new TierWalk(anthropicPlan());

        ProxyFacade.AccountAttempt attempt = select(walk);

        assertThat(attempt.account().getAccountId()).isEqualTo("kiro-new");
        assertThat(cacheReloads).isEqualTo(1);
        // Four cells in the round that found nothing, four in the round after the reload.
        assertThat(poolCalls).hasSize(8);
        assertThat(poolCalls.subList(0, 4)).allSatisfy(call -> assertThat(call.found()).isNull());
        assertThat(poolCalls).allSatisfy(call -> assertThat(call.refreshOnEmpty()).isFalse());
    }

    @Test
    void whenTheReloadChangesNothingTheRequestHasNoAvailableAccount() {
        loaded(anthropicTiers(16));
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
            });

        assertThat(cacheReloads).isEqualTo(1);
        assertThat(poolCalls).hasSize(8);
        verify(admissionStore, never()).acquire(any());
    }

    @Test
    void accountsTheRequestHasMetAreNotOfferedAgain() {
        loaded(anthropicTiers(16));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        TierWalk walk = new TierWalk(anthropicPlan());
        walk.attempted("official-1");

        assertThat(select(walk).account().getAccountId()).isEqualTo("official-2");

        walk.attempted("official-2");
        assertThat(select(walk).account().getAccountId()).isEqualTo("kiro-1");
    }

    @Test
    void aWalkThatStartsLaterNeverLooksAtTheTiersAboveItAndANarrowedWalkOnlyAtItsTier() {
        loaded(anthropicTiers(16));
        add(officialKey("official-1"), kiro("kiro-1"));
        TierPlan plan = anthropicPlan();

        assertThat(select(new TierWalk(plan, 1)).account().getAccountId()).isEqualTo("kiro-1");
        assertThat(poolCalls).extracting(PoolCall::scope).containsOnly("tier:anthropic:kiro|claude-sonnet-4.5");

        poolCalls.clear();
        TierWalk narrowed = new TierWalk(plan);
        narrowed.narrowTo(plan.levels().get(1));
        assertThat(select(narrowed).account().getAccountId()).isEqualTo("kiro-1");
        assertThat(poolCalls).extracting(PoolCall::scope).containsOnly("tier:anthropic:kiro|claude-sonnet-4.5");
    }

    @Test
    void withCodexIsolationOffACodexTokenIsServedFromTheOpenAiCellOfItsTier() {
        loaded(openAiTiers(16));
        add(codex("codex-1"));
        TierWalk walk = new TierWalk(openAiPlan(false, null, false));

        ProxyFacade.AccountAttempt attempt = select(walk);

        assertThat(attempt.account().getAccountId()).isEqualTo("codex-1");
        assertThat(attempt.selection().level().tierId()).isEqualTo("codex");
        assertThat(attempt.selection().cell()).isEqualTo(PoolCell.OFFICIAL_OR_CODEX);
    }

    // ---- refusals ----

    @Test
    void sixteenRefusalsInTheFirstTierSendTheRequestToTheSecondTier() {
        loaded(anthropicTiers(16));
        for (int i = 0; i < 20; i++) {
            add(officialKey("official-" + i));
            busy.add("official-" + i);
        }
        add(kiro("kiro-1"));
        TierWalk walk = new TierWalk(anthropicPlan());

        ProxyFacade.AccountAttempt attempt = select(walk);

        assertThat(attempt.account().getAccountId()).isEqualTo("kiro-1");
        assertThat(attempt.selection().level().tierId()).isEqualTo("kiro");
        assertThat(admissions).extracting(AdmissionRequest::accountId).hasSize(17)
            .startsWith("official-0", "official-1").containsSequence("official-14", "official-15", "kiro-1");
        assertThat(walk.refusals()).isEqualTo(16);
    }

    @Test
    void theCapIsTheVendorsAndNotAlwaysSixteen() {
        loaded(anthropicTiers(4));
        for (int i = 0; i < 20; i++) {
            add(officialKey("official-" + i));
            busy.add("official-" + i);
        }
        add(kiro("kiro-1"));

        ProxyFacade.AccountAttempt attempt = select(new TierWalk(anthropicPlan()));

        assertThat(attempt.account().getAccountId()).isEqualTo("kiro-1");
        assertThat(admissions).hasSize(5);
    }

    @Test
    void theRefusalsAreCountedForTheWholeRequestAndNotPerDispatch() {
        loaded(anthropicTiers(16));
        for (int i = 0; i < 20; i++) {
            add(officialKey("official-" + i));
        }
        add(kiro("kiro-1"));
        TierWalk walk = new TierWalk(anthropicPlan());
        // The first dispatch gets past ten refusals and is then sent to official-10, where it fails.
        for (int i = 0; i < 10; i++) {
            busy.add("official-" + i);
        }
        assertThat(select(walk).account().getAccountId()).isEqualTo("official-10");
        walk.attempted("official-10");

        // The second dispatch meets six more refusals: sixteen in all, which closes the first tier although it has
        // accounts left (official-17 is free), because the request is not starting from nothing.
        for (int i = 11; i < 17; i++) {
            busy.add("official-" + i);
        }
        ProxyFacade.AccountAttempt second = select(walk);

        assertThat(second.account().getAccountId()).isEqualTo("kiro-1");
        assertThat(walk.refusals()).isEqualTo(16);
        assertThat(admissions).extracting(AdmissionRequest::accountId)
            .doesNotContain("official-17")
            .endsWith("official-16", "kiro-1");
        // And the accounts that refused are not asked again.
        assertThat(admissions.stream().map(AdmissionRequest::accountId).filter("official-0"::equals)).hasSize(1);
    }

    @Test
    void aSingleTierIsOnlyLimitedByTheRequestWideSixtyFour() {
        loaded(singleTier());
        for (int i = 0; i < 70; i++) {
            add(officialKey("official-" + i));
            busy.add("official-" + i);
        }
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("ACCOUNT_ADMISSION_EXHAUSTED");
            });

        // Far more than sixteen refusals were put up with; sixty-four is where the request gives up.
        assertThat(admissions).hasSize(64);
        assertThat(walk.refusals()).isEqualTo(64);
    }

    @Test
    void theLastTierOfSeveralIsOnlyLimitedByTheRequestWideSixtyFourToo() {
        loaded(anthropicTiers(16));
        for (int i = 0; i < 70; i++) {
            add(kiro("kiro-" + i));
            busy.add("kiro-" + i);
        }
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("ACCOUNT_ADMISSION_EXHAUSTED"));

        assertThat(admissions).hasSize(64);
    }

    @Test
    void onceTheRequestIsExhaustedNothingIsSelectedOrAdmittedAnyMore() {
        loaded(singleTier());
        for (int i = 0; i < 70; i++) {
            add(officialKey("official-" + i));
            busy.add("official-" + i);
        }
        TierWalk walk = new TierWalk(anthropicPlan());
        assertThatThrownBy(() -> select(walk)).isInstanceOf(ApiException.class);
        poolCalls.clear();
        admissions.clear();

        assertThatThrownBy(() -> select(walk))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("ACCOUNT_ADMISSION_EXHAUSTED"));

        assertThat(poolCalls).isEmpty();
        assertThat(admissions).isEmpty();
    }

    // ---- admission and the session ----

    @Test
    void admissionIsAskedForTheAccountWithTheSameFactsAsAnUntieredRequest() {
        loaded(anthropicTiers(16));
        add(officialKey("official-1"));
        TierPlan plan = anthropicPlan();

        select(new TierWalk(plan));

        assertThat(admissions).singleElement().satisfies(admission -> {
            assertThat(admission.accountId()).isEqualTo("official-1");
            assertThat(admission.requestId()).isEqualTo("request-1");
            assertThat(admission.expectedStateVersion()).isNull();
            assertThat(admission.enforceSessionLimit()).isTrue();
            assertThat(admission.modelScope()).isEqualTo(plan.poolContext().upstreamModel()).isEqualTo("claude-sonnet-4.5");
            assertThat(admission.endpointScope()).isEqualTo(ENDPOINT);
        });
    }

    @Test
    void anAdmittedAccountIsBoundToTheSessionWithinItsTierWhenThePolicyBindsOnAdmission() {
        loaded(anthropicTiers(16));
        add(officialKey("official-1"));
        TierPlan plan = anthropicPlan();
        RoutingPolicySettings binding = new RoutingPolicySettings(true, false, true, true, 1, 3, 3, 2, 2);

        facade.selectTieredAccountWithAdmission(new TierWalk(plan), SESSION, binding, false, "request-1", requestBody(), ENDPOINT).block();

        verify(accountPool).commitSessionAffinity(plan.poolContext(), "tier:anthropic:official|claude-sonnet-4.5", SESSION, "official-1", true);
    }

    @Test
    void nothingIsBoundWhenThePolicyDoesNotBindOnAdmission() {
        loaded(anthropicTiers(16));
        add(officialKey("official-1"));

        select(new TierWalk(anthropicPlan()));

        verify(accountPool, never()).commitSessionAffinity(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    // ---- the plan is checked as the account is selected ----

    @Test
    void anAccountThePlanDoesNotAllowEndsTheRequestBeforeAdmissionOrDispatch() {
        loaded(anthropicTiers(16));
        // A pool that hands out a Codex token for an Anthropic request: no cell of the plan takes it.
        offeredWhatever = codex("codex-1");
        TierWalk walk = new TierWalk(anthropicPlan());

        assertThatThrownBy(() -> select(walk))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_SELECTION_INVALID");
                // Nothing about the tiers or the account is told to the client.
                assertThat(error.getMessage()).doesNotContain("codex-1").doesNotContain("official");
            });

        verify(admissionStore, never()).acquire(any());
    }

    @Test
    void anAccountOfAnotherTierThanTheOneItWasSelectedForIsNotAllowedEither() {
        loaded(anthropicTiers(16));
        // Asked for the second tier, the pool answers with an official key: a kind its cell takes, but the first tier's.
        offeredWhatever = officialKey("official-1");
        TierWalk walk = new TierWalk(anthropicPlan(), 1);

        assertThatThrownBy(() -> select(walk))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_SELECTION_INVALID"));

        verify(admissionStore, never()).acquire(any());
    }

    // ---- the owner of a response chain ----

    @Test
    void theOwnerInTheSecondTierIsSelectedWithoutTheFirstTierBeingLookedAt() {
        loaded(openAiTiers(16));
        add(officialOpenAi("official-1"), codex("codex-owner"));
        TierWalk walk = new TierWalk(openAiPlan(false, "codex-owner", false));

        ProxyFacade.AccountAttempt attempt = select(walk, false);

        assertThat(attempt.account().getAccountId()).isEqualTo("codex-owner");
        assertThat(attempt.selection().level().tierId()).isEqualTo("codex");
        assertThat(poolCalls).singleElement().satisfies(call -> {
            assertThat(call.scope()).isEqualTo("tier:openai:codex|gpt-5.5");
            // The owner is asked for by account, not by the session.
            assertThat(call.hint()).isNull();
            assertThat(call.refreshOnEmpty()).isFalse();
        });
    }

    @Test
    void theOwnerIsPreferredOnlyOnTheFirstSelectionOfTheRequest() {
        loaded(openAiTiers(16));
        add(officialOpenAi("official-owner"), officialOpenAi("official-1"), codex("codex-2"));
        TierWalk walk = new TierWalk(openAiPlan(false, "official-owner", true));
        assertThat(select(walk, true).account().getAccountId()).isEqualTo("official-owner");
        walk.attempted("official-owner");

        // The retry walks the tiers from the best one, as any dispatch after the first.
        assertThat(select(walk, true).account().getAccountId()).isEqualTo("official-1");
    }

    @Test
    void theOwnerOfAChainThatCanBeReplayedIsNotPreferredOutsideTheTierTheWalkStartsFrom() {
        loaded(openAiTiers(16));
        add(officialOpenAi("official-1"), codex("codex-owner"));
        TierWalk walk = new TierWalk(openAiPlan(false, "codex-owner", true));

        // A pinned turn left the owner in the second tier; the automatic turn starts from the best tier, owner or not.
        ProxyFacade.AccountAttempt attempt = select(walk, true);

        assertThat(walk.plan().owner()).isEmpty();
        assertThat(attempt.account().getAccountId()).isEqualTo("official-1");
        assertThat(attempt.selection().level().tierId()).isEqualTo("official");
        assertThat(admissions).extracting(AdmissionRequest::accountId).containsExactly("official-1");
    }

    @Test
    void anOwnerThatIsUnavailableIsLeftForTheTiersWhenTheChainCanBeReplayed() {
        loaded(openAiTiers(16));
        add(officialOpenAi("official-owner"), officialOpenAi("official-1"), codex("codex-1"));
        TierWalk walk = new TierWalk(openAiPlan(false, "official-owner", true));
        unavailable.add("official-owner");

        ProxyFacade.AccountAttempt attempt = select(walk, true);

        assertThat(attempt.account().getAccountId()).isEqualTo("official-1");
        assertThat(attempt.selection().level().tierId()).isEqualTo("official");
    }

    @Test
    void anOwnerThatIsUnavailableEndsARequestWhoseChainCannotMove() {
        loaded(openAiTiers(16));
        add(officialOpenAi("official-1"), codex("codex-owner"));
        TierWalk walk = new TierWalk(openAiPlan(false, "codex-owner", false));
        unavailable.add("codex-owner");

        assertThatThrownBy(() -> select(walk, false))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
                assertThat(error.getMessage()).isEqualTo("The account that owns the OpenAI response is unavailable");
                // The facade tells it from an empty pool by its type, not by its text.
                assertThat(error).isInstanceOf(ProxyFacade.ResponseOwnerUnavailableException.class);
            });

        // The first tier was never looked at and the cache was not reloaded for it.
        assertThat(poolCalls).extracting(PoolCall::scope).containsOnly("tier:openai:codex|gpt-5.5");
        assertThat(cacheReloads).isZero();
        verify(admissionStore, never()).acquire(any());
    }

    @Test
    void anOwnerThatAdmissionRefusesEndsARequestWhoseChainCannotMove() {
        loaded(openAiTiers(16));
        add(officialOpenAi("official-1"), codex("codex-owner"));
        TierWalk walk = new TierWalk(openAiPlan(false, "codex-owner", false));
        busy.add("codex-owner");

        assertThatThrownBy(() -> select(walk, false))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("RESPONSE_OWNER_UNAVAILABLE");
            });

        assertThat(admissions).extracting(AdmissionRequest::accountId).containsExactly("codex-owner");
    }

    @Test
    void anOwnerThatAdmissionRefusesIsLeftForTheTiersWhenTheChainCanBeReplayed() {
        loaded(openAiTiers(16));
        add(officialOpenAi("official-owner"), officialOpenAi("official-1"), codex("codex-1"));
        TierWalk walk = new TierWalk(openAiPlan(false, "official-owner", true));
        busy.add("official-owner");

        ProxyFacade.AccountAttempt attempt = select(walk, true);

        assertThat(attempt.account().getAccountId()).isEqualTo("official-1");
        assertThat(admissions).extracting(AdmissionRequest::accountId).containsExactly("official-owner", "official-1");
        assertThat(walk.refusals()).isEqualTo(1);
    }

    // ---- fixtures ----

    private ProxyFacade.AccountAttempt select(TierWalk walk) {
        return select(walk, false);
    }

    private ProxyFacade.AccountAttempt select(TierWalk walk, boolean ownerMayBeLeft) {
        return facade.selectTieredAccountWithAdmission(walk, SESSION, policy, ownerMayBeLeft, "request-1", requestBody(), ENDPOINT).block();
    }

    private void add(ProxyAccountEntity... accounts) {
        cached.addAll(List.of(accounts));
    }

    private void loaded(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    private TierPlan anthropicPlan() {
        return plan(new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, requestBody(), null, false, null, null));
    }

    private TierPlan openAiPlan(boolean isolateCodex, String owner, boolean movable) {
        return plan(new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, isolateCodex, false, true, requestBody(), owner, movable, null, null));
    }

    private TierPlan plan(TierPlanRequest request) {
        ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(modelCatalog);
        return new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, accountPool, payments, pricing, TierSessionBindings.disabled())
            .plan(request).block();
    }

    private JsonNode requestBody() {
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

    private static SourceTierSnapshot singleTier() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(tier("anthropic", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY)),
            List.of(), List.of(), List.of());
    }

    private static SourceTierSnapshot openAiTiers(int refusalCap) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, true, refusalCap, true, 5L)),
            List.of(
                tier("openai", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, "0.5", AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of());
    }

    private static Tier tier(String vendor, String tierId, int level, String factor, AccountAllocationCategory... categories) {
        Set<AccountAllocationCategory> listed = EnumSet.copyOf(List.of(categories));
        return new Tier(vendor, tierId, level, tierId, new BigDecimal(factor), listed, true, true, null);
    }

    private static ProxyAccountEntity officialKey(String accountId) {
        return account(accountId, "Anthropic", "api-key");
    }

    private static ProxyAccountEntity kiro(String accountId) {
        return account(accountId, "BuilderId", "idc");
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
