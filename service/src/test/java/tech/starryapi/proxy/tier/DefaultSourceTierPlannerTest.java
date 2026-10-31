package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.JsonNodes;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DefaultSourceTierPlannerTest {

    private static final AccountAllocationCategory OFFICIAL = AccountAllocationCategory.OFFICIAL_API_KEY;
    private static final AccountAllocationCategory CODEX = AccountAllocationCategory.CODEX;
    private static final AccountAllocationCategory KIRO = AccountAllocationCategory.KIRO;
    private static final AccountAllocationCategory CLAUDE_OAUTH = AccountAllocationCategory.CLAUDE_OAUTH;
    private static final BigDecimal BILLING_MULTIPLIER = new BigDecimal("1.5");
    /** $3/M in, $15/M out, $0.30/M cache read, $3.75/M cache write: the Sonnet example of the design. */
    private static final BillingQuote STANDARD = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(catalog);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(
        registry, keyResolver, catalog, accountPool, payments, pricing, TierSessionBindings.disabled());

    /** Every account the account cache holds (enabled), whatever its state. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** The accounts of the cache that are available (not suspended, not out of quota). */
    private final List<ProxyAccountEntity> usable = new ArrayList<>();

    private final ProxyAccountEntity officialAnthropic = account("anthropic-official", "Anthropic", "api-key", null);
    private final ProxyAccountEntity thirdPartyAnthropic =
        account("anthropic-gateway", "Anthropic", "api-key", "https://claude-gateway.example/v1");
    private final ProxyAccountEntity claudeOauth = account("claude-oauth", "Anthropic", "oauth", null);
    private final ProxyAccountEntity kiro = account("kiro", "BuilderId", "idc", null);
    private final ProxyAccountEntity officialOpenAi = account("openai-official", "OpenAI", "api-key", null);
    private final ProxyAccountEntity codex = account("codex", "OpenAI", "codex", null);
    private final ProxyAccountEntity relay = account("relay", "OpenAI-compatible", "api-key", "https://relay.example/v1");

    @BeforeEach
    void stubTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig(BILLING_MULTIPLIER)));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(STANDARD));
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenAnswer(invocation -> usable.contains(invocation.<ProxyAccountEntity>getArgument(0)));
        cached.addAll(List.of(officialAnthropic, thirdPartyAnthropic, claudeOauth, kiro, officialOpenAi, codex));
        usable.addAll(cached);
    }

    // ---- no tiers in use ----

    @Test
    void aVendorWithoutAConfigurationIsUntieredAndNothingIsLookedUp() {
        loaded(SourceTierSnapshot.EMPTY);

        assertThat(plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC))).isSameAs(TierPlan.UNTIERED);

        verifyNoInteractions(payments, pricing, accountPool);
    }

    @Test
    void aVendorThatIsSwitchedOffIsUntiered() {
        loaded(snapshot(List.of(vendor("anthropic", false)), anthropicTiers(), List.of()));

        assertThat(plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC))).isSameAs(TierPlan.UNTIERED);

        verifyNoInteractions(payments, pricing, accountPool);
    }

    @Test
    void aVendorWithoutAnEnabledTierIsUntiered() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "官方", "1", false, OFFICIAL),
            tier("anthropic", "kiro", 2, "kiro", "0.6", false, KIRO)
        ), List.of()));

        assertThat(plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC))).isSameAs(TierPlan.UNTIERED);

        verifyNoInteractions(payments, pricing, accountPool);
    }

    @Test
    void aVendorIsLookedUpByTheModelNotByTheRoute() {
        // A GPT model asked through /v1/messages takes the Anthropic route but belongs to the openai vendor.
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));

        assertThat(plan(request("gpt-5.5", TierRoute.ANTHROPIC))).isSameAs(TierPlan.UNTIERED);
    }

    // ---- fail closed (I5) ----

    @Test
    void beforeTheFirstLoadEveryRequestIsRefusedWithoutAPriceBeingLookedUp() {
        SourceTierRegistry neverLoaded = new SourceTierRegistry(
            mock(DatabaseClient.class),
            mock(ReactiveTransactionManager.class),
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction()
        );
        DefaultSourceTierPlanner failClosed = new DefaultSourceTierPlanner(
            neverLoaded, keyResolver, catalog, accountPool, payments, pricing, TierSessionBindings.disabled());

        StepVerifier.create(failClosed.plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC)))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ApiException.class);
                ApiException refusal = (ApiException) error;
                assertThat(refusal.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(refusal.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE");
            })
            .verify();

        verifyNoInteractions(payments, pricing);
    }

    @Test
    void theRefusalIsAnErrorOfTheMonoNotAnExceptionOfTheCall() {
        SourceTierRegistry neverLoaded = new SourceTierRegistry(
            mock(DatabaseClient.class),
            mock(ReactiveTransactionManager.class),
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction()
        );
        DefaultSourceTierPlanner failClosed = new DefaultSourceTierPlanner(
            neverLoaded, keyResolver, catalog, accountPool, payments, pricing, TierSessionBindings.disabled());

        Mono<TierPlan> mono = failClosed.plan(request("gpt-5.5", TierRoute.OPENAI));

        assertThatThrownBy(mono::block).isInstanceOf(ApiException.class);
    }

    // ---- the cells of a route ----

    @Test
    void theCellsAreTheOnesTheRouteWouldPickFrom() {
        assertThat(units(TierRoute.KIRO, r -> r)).containsExactly(PoolCell.KIRO_COMPATIBLE);
        assertThat(units(TierRoute.ANTHROPIC, r -> r)).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE);
        assertThat(units(TierRoute.OPENAI, r -> r)).containsExactly(PoolCell.OFFICIAL_OPENAI);
        assertThat(units(TierRoute.OPENAI, r -> with(r, false, false, false, false)))
            .containsExactly(PoolCell.OFFICIAL_OR_CODEX);
    }

    @Test
    void codexAccountsAreTriedFirstAndOnlyAreOnWhenTheCodexPoolIsIsolated() {
        assertThat(units(TierRoute.OPENAI, r -> with(r, false, false, true, true))).containsExactly(PoolCell.CODEX);
        assertThat(units(TierRoute.OPENAI, r -> with(r, false, false, true, false)))
            .containsExactly(PoolCell.CODEX, PoolCell.OFFICIAL_OR_CODEX);
    }

    @Test
    void deepSeekAndOpenAiCompatibleModelsHaveOneCellAndAreChosenBeforeCodex() {
        assertThat(units(TierRoute.OPENAI, r -> with(r, true, true, true, true))).containsExactly(PoolCell.DEEPSEEK);
        assertThat(units(TierRoute.OPENAI, r -> with(r, false, true, true, false)))
            .containsExactly(PoolCell.OPENAI_COMPATIBLE);
    }

    // ---- tiers, prices and reservation ----

    @Test
    void theOwnersExampleIsPlannedAsTwoTiersWithTheirOwnPrices() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        TierPlanRequest request = request("claude-sonnet-4.5", TierRoute.ANTHROPIC);

        TierPlan plan = plan(request);

        assertThat(plan.tiered()).isTrue();
        assertThat(plan.vendor()).isEqualTo("anthropic");
        assertThat(plan.mode()).isEqualTo(TierPlan.Mode.AUTO);
        assertThat(plan.configVersion()).isEqualTo(7L);
        assertThat(plan.vendorConfig().refusalCap()).isEqualTo(16);
        assertThat(plan.levels()).extracting(Level::tierRef).containsExactly("anthropic:official", "anthropic:kiro");
        assertThat(plan.levels()).extracting(Level::level).containsExactly(1, 2);
        assertThat(plan.levels()).extracting(Level::name).containsExactly("官方", "kiro反向代理");
        assertThat(plan.levels()).allSatisfy(level ->
            assertThat(level.cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE));
        // A factor of 1 leaves the very same quote; any other factor multiplies all five rates.
        assertThat(plan.levels().get(0).quote()).isSameAs(STANDARD);
        assertThat(plan.levels().get(1).quote()).isEqualTo(STANDARD.scaled(new BigDecimal("0.6")));
        assertThat(plan.levels().get(1).quote().input()).isEqualByComparingTo("0.0000018");
    }

    @Test
    void thePriceIsLookedUpOnceUnderTheRequestedModelAndTheBillingMultiplier() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));

        plan(request("claude-sonnet-4.5", "sonnet", TierRoute.ANTHROPIC));

        verify(pricing).billingQuote("sonnet", false, BILLING_MULTIPLIER);
        verify(payments).config();
        verifyNoMoreInteractions(pricing);
    }

    @Test
    void aFastRequestIsPricedAsFast() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        TierPlanRequest request = new TierPlanRequest("request-1", "gpt-5.5-fast", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, false, true, true, body("gpt-5.5"), null, false, null, null);

        plan(request);

        verify(pricing).billingQuote("gpt-5.5-fast", true, BILLING_MULTIPLIER);
    }

    @Test
    void eachTiersEstimateIsItsQuoteTimesTheBoundsAndTheReservationIsTheLargest() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "official", "1", true, OFFICIAL),
            tier("anthropic", "kiro", 2, "kiro", "0.6", true, KIRO, CLAUDE_OAUTH),
            tier("anthropic", "premium", 3, "premium", "2.5", true, AccountAllocationCategory.COMPATIBLE_API_KEY)
        ), List.of()));
        ObjectNode body = body("claude-sonnet-4.5");
        body.put("max_tokens", 8000);
        TierPlanRequest request = new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, body, null, false, null, null);

        TierPlan plan = plan(request);

        long inputBound = JsonNodes.utf8Length(body) + 1024;
        assertThat(plan.outputBound()).isEqualTo(8000);
        for (Level level : plan.levels()) {
            assertThat(level.inputBound()).isEqualTo(inputBound);
            assertThat(level.estimate()).isEqualTo(level.quote().estimate(inputBound, 8000));
        }
        // The dearest tier is the third one, and it is also the one a request could end up in last.
        assertThat(plan.reservation()).isEqualTo(plan.levels().get(2).estimate());
        assertThat(plan.reservation()).isGreaterThan(plan.levels().get(0).estimate());
        assertThat(plan.holdLevels()).isEqualTo(plan.levels());
    }

    @Test
    void anAnonymousRequestIsPlannedWithoutAnyPrice() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        TierPlanRequest request = new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, false, body("claude-sonnet-4.5"), null, false, null, null);

        TierPlan plan = plan(request);

        assertThat(plan.tiered()).isTrue();
        assertThat(plan.levels()).hasSize(2);
        assertThat(plan.levels()).allSatisfy(level -> {
            assertThat(level.quote()).isNull();
            assertThat(level.estimate()).isNull();
        });
        assertThat(plan.reservation()).isNull();
        verifyNoInteractions(payments, pricing);
    }

    @Test
    void anUnavailablePriceFailsThePlanBeforeAnythingIsReserved() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.error(
            new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PRICE_UNAVAILABLE", "no price")));

        StepVerifier.create(planner.plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC)))
            .expectErrorSatisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("MODEL_PRICE_UNAVAILABLE"))
            .verify();
    }

    @Test
    void thePlanCarriesTheHeadersOfEachTier() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        assertThat(plan.levels().get(0).responseHeaders()).containsExactly(
            java.util.Map.entry("X-Starry-Tier", "official"),
            java.util.Map.entry("X-Starry-Tier-Level", "1"),
            java.util.Map.entry("X-Starry-Tier-Name", "%E5%AE%98%E6%96%B9"),
            java.util.Map.entry("X-Starry-Tier-Mode", "auto"),
            java.util.Map.entry("X-Starry-Tier-Status", "served"));
        assertThat(plan.levels().get(1).responseHeaders()).containsExactly(
            java.util.Map.entry("X-Starry-Tier", "kiro"),
            java.util.Map.entry("X-Starry-Tier-Level", "2"),
            java.util.Map.entry("X-Starry-Tier-Name", "kiro%E5%8F%8D%E5%90%91%E4%BB%A3%E7%90%86"),
            java.util.Map.entry("X-Starry-Tier-Mode", "auto"),
            java.util.Map.entry("X-Starry-Tier-Status", "served"));
    }

    @Test
    void theRequestRunsWithAContextThatHasNoCircuitEpoch() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        assertThat(plan.poolContext().circuitEpoch()).isNull();
        assertThat(plan.poolContext().modelPoolKey()).isNull();
        assertThat(plan.poolContext().requestToken()).isEqualTo("request-1");
        assertThat(plan.poolContext().requestedModel()).isEqualTo("claude-sonnet-4.5");
        assertThat(plan.poolContext().upstreamModel()).isEqualTo("claude-sonnet-4.5");
        assertThat(plan.poolContext().providerHint()).isEqualTo("Anthropic");
    }

    @Test
    void aTierThatIsSwitchedOffIsNotPlanned() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "official", "1", true, OFFICIAL),
            tier("anthropic", "spare", 2, "spare", "0.8", false),
            tier("anthropic", "kiro", 3, "kiro", "0.6", true, KIRO, CLAUDE_OAUTH)
        ), List.of()));

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "kiro");
    }

    // ---- which accounts a tier holds ----

    @Test
    void aTierHoldsTheAccountsTheConfigurationPlacesInIt() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of(
            nativeMember("anthropic", "anthropic-gateway", "official"))));
        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));
        Level official = plan.levels().get(0);
        Level kiroTier = plan.levels().get(1);

        assertThat(plan.holds(official, officialAnthropic)).isTrue();
        assertThat(plan.holds(kiroTier, officialAnthropic)).isFalse();
        // A member row beats the category, and the lowest tier takes what no category lists.
        assertThat(plan.holds(official, thirdPartyAnthropic)).isTrue();
        assertThat(plan.holds(kiroTier, claudeOauth)).isTrue();
        assertThat(plan.holds(kiroTier, kiro)).isTrue();
        assertThat(plan.holds(official, null)).isFalse();
    }

    @Test
    void anAccountThatIsARelayForAnotherProtocolIsNoNativeMemberOfAnyTier() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of(
            relayMember("anthropic", "relay", "official"))));
        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        assertThat(plan.holds(plan.levels().get(0), relay)).isFalse();
        assertThat(plan.holds(plan.levels().get(1), relay)).isFalse();
    }

    @Test
    void anExclusiveRelayIsNoMemberOfTheCompatibleCellOfAVendorThatHasNoRowForIt() {
        // The relay serves Claude (its row is in the anthropic vendor). The openai vendor has no row for it, which would
        // put it into its lowest tier through the OpenAI-compatible cell, where GLM-like traffic is served.
        loaded(snapshot(List.of(vendor("anthropic", true), vendor("openai", true)),
            concat(anthropicTiers(), openAiTiers()), List.of(relayMember("anthropic", "relay", "official"))));
        cached.add(relay);
        usable.add(relay);

        TierPlan plan = plan(with(openAiRequest(false, false), false, true, false, false));

        assertThat(plan.levels()).isNotEmpty().allSatisfy(level -> {
            assertThat(level.cells()).containsExactly(PoolCell.OPENAI_COMPATIBLE);
            assertThat(plan.holds(level, relay)).as(level.tierRef()).isFalse();
            assertThat(plan.permits(level, PoolCell.OPENAI_COMPATIBLE, relay)).as(level.tierRef()).isFalse();
        });
    }

    @Test
    void aRelayThatIsNotExclusiveStaysInTheLowestTierOfTheCompatibleCellOfOtherVendors() {
        loaded(snapshot(List.of(vendor("anthropic", true), vendor("openai", true)),
            concat(anthropicTiers(), openAiTiers()), List.of(relayMember("anthropic", "relay", "official", false))));
        cached.add(relay);
        usable.add(relay);

        TierPlan plan = plan(with(openAiRequest(false, false), false, true, false, false));

        Level lowest = plan.levels().getLast();
        assertThat(lowest.tierId()).isEqualTo("codex");
        assertThat(plan.holds(lowest, relay)).isTrue();
        assertThat(plan.permits(lowest, PoolCell.OPENAI_COMPATIBLE, relay)).isTrue();
    }

    @Test
    void anAccountOfAnotherFamilyIsNotHeldByATierOfACategoryItSharesWithOpenAiKeys() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        TierPlan plan = plan(openAiRequest(false, false));

        // An official Anthropic key is in the OFFICIAL_API_KEY category too, but it is no official OpenAI key.
        assertThat(plan.holds(plan.levels().get(0), officialAnthropic)).isFalse();
        assertThat(plan.holds(plan.levels().get(0), officialOpenAi)).isTrue();
    }

    // ---- tiers nothing could serve ----

    @Test
    void aTierWithoutAnAccountThatCouldServeIsLeftOut() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        usable.clear();
        usable.add(kiro);

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.reservation()).isEqualTo(plan.levels().get(0).estimate());
    }

    @Test
    void aTierWhoseAccountsAreAllUnavailableIsRecordedAsHavingBeenGoneThrough() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        usable.clear();
        usable.add(kiro);

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        // The official key is in the cache and out of quota, which is a tier with nothing available.
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("official");
        assertThat(plan.unavailableAbove(plan.levels().get(0))).isTrue();
    }

    @Test
    void aTierWithNoAccountOfTheRoutesCellsAtAllIsLeftOutWithoutBeingRecorded() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        cached.remove(officialAnthropic);
        cached.remove(thirdPartyAnthropic);
        usable.clear();
        usable.add(kiro);

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.unavailableAbove(plan.levels().get(0))).isFalse();
    }

    @Test
    void thePlanClassifiesTheRoutesAccountsOnceAndNeverAsksTheCacheWhetherATierHasOne() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));

        plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        verify(accountPool, org.mockito.Mockito.times(1)).cachedAccounts(any());
        verify(accountPool, org.mockito.Mockito.never()).hasUsableAccount(any());
    }

    @Test
    void anAccountOfAnotherKindOfCellDoesNotKeepATierIn() {
        // OpenCode asks /v1/chat/completions for a Claude model: the route is Kiro, an official Anthropic key is no
        // candidate for it, so the tier of official keys has nothing to serve this request with.
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        usable.clear();
        usable.addAll(List.of(officialAnthropic, kiro));

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.KIRO));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.levels().get(0).cells()).containsExactly(PoolCell.KIRO_COMPATIBLE);
        // The official key is no candidate for this route at all, so the tier is not one a request is pushed out of.
        assertThat(plan.unavailableTiers()).isEmpty();
    }

    @Test
    void whenNoTierHasAnAvailableAccountTheTiersWithAnAccountOfTheRouteAllStay() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        usable.clear();

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.levels()).noneMatch(plan::memberless);
    }

    @Test
    void whenNoTierHasAnAvailableAccountATierWithNoAccountOfTheRouteAtAllDoesNotStay() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        cached.remove(officialAnthropic);
        cached.remove(thirdPartyAnthropic);
        usable.clear();

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        // Only the Kiro tier has an account (unavailable); the tier of official keys has none, so it cannot serve and
        // must not size the reservation (the dearer of the two tiers here).
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.reservation()).isEqualTo(plan.levels().get(0).estimate());
        assertThat(plan.levels()).noneMatch(plan::memberless);
    }

    @Test
    void theReservationOfAPlanWithNothingAvailableIsNotSizedByATierThatHasNoAccount() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        TierPlan withBoth = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));
        cached.remove(officialAnthropic);
        cached.remove(thirdPartyAnthropic);
        usable.clear();

        TierPlan nothingAvailable = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        // The tier of official keys costs the standard price and the Kiro tier 0.6 of it.
        assertThat(withBoth.reservation()).isGreaterThan(nothingAvailable.reservation());
        assertThat(nothingAvailable.reservation()).isEqualTo(
            STANDARD.scaled(new BigDecimal("0.6")).estimate(RequestBounds.nativeInputTokens(body("claude-sonnet-4.5")), 4096));
    }

    @Test
    void whenNoTierHasAnAccountAtAllEveryTierStaysAndIsMarkedAsHavingNone() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        cached.clear();
        usable.clear();

        TierPlan plan = plan(request("claude-sonnet-4.5", TierRoute.ANTHROPIC));

        // A plan needs a tier, so the request goes on to find no account (or one a reload of the cache brings in).
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.levels()).allMatch(plan::memberless);
    }

    // ---- the input bound of a tier ----

    @Test
    void aKiroOnlyRouteDoesNotRaiseTheBoundForAnInlineImage() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        ObjectNode body = bodyWithInlineImage("claude-sonnet-4.5", 200_000);

        TierPlan plan = plan(requestWith(body, TierRoute.KIRO));

        assertThat(plan.levels()).isNotEmpty();
        // Kiro is sent the text only: the base64 counts for nothing and the 1,000,000 floor is not used.
        assertThat(plan.levels().get(0).inputBound()).isLessThan(5_000);
    }

    /**
     * A vendor with tiers and no relays is reserved as it was before there were relays (design 0, I4): an Anthropic key is a cell
     * that is not Kiro, so the route of the two is bounded as an untiered request of it is, and an inline image raises the bound.
     * (Where a relay takes part the route is bounded by its text, which TierPlannerXpTest holds.)
     */
    @Test
    void aRouteWithAccountsThatTakeImagesStillRaisesTheBoundToTheFloor() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        ObjectNode body = bodyWithInlineImage("claude-sonnet-4.5", 200_000);

        TierPlan plan = plan(requestWith(body, TierRoute.ANTHROPIC));

        assertThat(plan.levels()).allSatisfy(level -> assertThat(level.inputBound()).isEqualTo(1_000_000L));
    }

    @Test
    void aRouteWithAnAccountThatIsSentTheWholeBodyStillRaisesTheBoundToTheFloor() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        ObjectNode body = bodyWithInlineImage("gpt-5.5", 200_000);

        TierPlan plan = plan(new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI, false, false, false,
            true, false, true, body, null, false, null, null));

        assertThat(plan.levels()).isNotEmpty();
        assertThat(plan.levels()).allSatisfy(level -> assertThat(level.inputBound()).isEqualTo(1_000_000L));
    }

    @Test
    void aResponseChainThatCannotBeExpandedRaisesEvenTheKiroBoundToTheFloor() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));
        ObjectNode body = body("claude-sonnet-4.5");
        body.put("previous_response_id", "resp_unknown");

        TierPlan plan = plan(requestWith(body, TierRoute.KIRO));

        assertThat(plan.levels().get(0).inputBound()).isEqualTo(1_000_000L);
    }

    // ---- the model key ----

    @Test
    void theModelKeyIsTheCanonicalCatalogId() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers(), List.of()));

        assertThat(modelKeyOf("claude-sonnet-4.5")).isEqualTo("claude-sonnet-4.5");
        // The Claude Code alias and the dated and numbered aliases stand for the model they are mapped to.
        assertThat(modelKeyOf("sonnet")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelKeyOf("claude-sonnet-4.6")).isEqualTo("claude-sonnet-4.5");
        assertThat(modelKeyOf("Starry/Claude-Haiku-4.5")).isEqualTo("claude-haiku-4.5");
        assertThat(modelKeyOf("anthropic/claude-haiku-4.5")).isEqualTo("claude-haiku-4.5");
        // Another model of the same vendor keeps a key of its own.
        assertThat(modelKeyOf("claude-opus-4.5")).isNotEqualTo(modelKeyOf("claude-sonnet-4.5"));
    }

    @Test
    void anAliasThatIsMappedToAnotherVendorKeepsItsOwnKey() {
        // Gemini ids are served by Claude in the catalog, but their key must not become the key of that Claude model.
        loaded(snapshot(List.of(vendor("google", true)), List.of(
            tier("google", "kiro", 1, "kiro", "1", true, KIRO)
        ), List.of()));

        TierPlan plan = plan(request("gemini-2.5-pro", TierRoute.KIRO));

        assertThat(plan.vendor()).isEqualTo("google");
        assertThat(plan.modelKey()).isEqualTo("gemini-2.5-pro");
    }

    // ---- the owner of a response chain ----

    @Test
    void theOwnerOfAChainIsPlannedWithTheTierItSitsIn() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        usable.clear();
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        TierPlan plan = plan(openAiRequest(false, false, "codex", false));

        assertThat(plan.owner()).hasValueSatisfying(owner -> {
            assertThat(owner.accountId()).isEqualTo("codex");
            assertThat(owner.level().tierId()).isEqualTo("codex");
        });
        // Nothing is usable anywhere, which would keep both tiers; the owner's one is kept in any case.
        assertThat(plan.levels()).extracting(Level::tierId).contains("codex");
    }

    @Test
    void theOwnersTierIsNotLeftOutWhenItIsWhereTheWalkStartsAndOtherTiersHaveUsableAccounts() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        ProxyAccountEntity sibling = account("openai-official-2", "OpenAI", "api-key", null);
        cached.add(sibling);
        usable.clear();
        usable.add(sibling);
        usable.add(codex);
        when(accountPool.cachedAccount("openai-official")).thenReturn(Optional.of(officialOpenAi));

        // A chain that can be replayed elsewhere prefers its owner when it sits where the walk starts, and the other
        // tiers may serve it. The owner itself has nothing to offer (it is out of quota), and its tier stays all the same.
        TierPlan plan = plan(openAiRequest(false, false, "openai-official", true));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "codex");
        assertThat(plan.owner()).hasValueSatisfying(owner -> assertThat(owner.level().tierId()).isEqualTo("official"));
    }

    @Test
    void theOwnerOfAChainThatCanBeReplayedIsLeftOutWithItsTierWhenItIsNotWhereTheWalkStarts() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        usable.clear();
        usable.add(officialOpenAi);
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        // The owner is in the second tier, and the first one can serve: the request starts from the first, owner or not.
        TierPlan plan = plan(openAiRequest(false, false, "codex", true));

        assertThat(plan.owner()).isEmpty();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official");
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("codex");
    }

    @Test
    void aChainThatCannotLeaveItsOwnerIsPlannedHeldAndPricedForTheOwnersTierAlone() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));
        when(accountPool.cachedAccount("openai-official")).thenReturn(Optional.of(officialOpenAi));

        TierPlan onCodex = plan(openAiRequest(false, false, "codex", false));
        TierPlan onOfficial = plan(openAiRequest(false, false, "openai-official", false));
        TierPlan replayable = plan(openAiRequest(false, false, "codex", true));

        // No other tier can serve it, so no other tier is planned, held for, or advised; the owner's tier is the whole plan.
        assertThat(onCodex.levels()).extracting(Level::tierId).containsExactly("codex");
        assertThat(onCodex.holdLevels()).extracting(Level::tierId).containsExactly("codex");
        assertThat(onCodex.reservation()).isEqualByComparingTo(onCodex.levels().get(0).estimate());
        assertThat(onCodex.lowBalanceHint()).isNull();
        assertThat(onOfficial.levels()).extracting(Level::tierId).containsExactly("official");
        assertThat(onOfficial.reservation()).isEqualByComparingTo(onOfficial.levels().get(0).estimate());
        // The dearer tier is the one the replayable chain reserves for, whichever account owns it.
        assertThat(replayable.holdLevels()).extracting(Level::tierId).containsExactly("official", "codex");
        assertThat(replayable.reservation()).isGreaterThan(onCodex.reservation());
    }

    @Test
    void anOwnerOutsideTheCellsIsIgnoredWhenTheChainCanBeReplayedAnywhere() {
        // The Codex pool is isolated, so the OpenAI route only takes official keys and the Codex owner is not a candidate.
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        TierPlan plan = plan(openAiRequest(true, false, "codex", true));

        assertThat(plan.tiered()).isTrue();
        assertThat(plan.owner()).isEmpty();
    }

    @Test
    void anOwnerOutsideTheCellsRefusesAChainThatCannotBeReplayed() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        assertOwnerMismatch(openAiRequest(true, false, "codex", false));
    }

    @Test
    void anOwnerInASwitchedOffTierRefusesAChainThatCannotBeReplayed() {
        loaded(snapshot(List.of(vendor("openai", true)), List.of(
            tier("openai", "official", 1, "official", "1", true, OFFICIAL),
            tier("openai", "codex", 2, "codex", "0.5", false, CODEX)
        ), List.of()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        assertOwnerMismatch(openAiRequest(false, false, "codex", false));
        assertThat(plan(openAiRequest(false, false, "codex", true)).owner()).isEmpty();
    }

    @Test
    void anOwnerThatIsNoLongerInTheAccountCacheCountsAsOutsideThePlan() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));

        assertOwnerMismatch(openAiRequest(false, false, "deleted-account", false));
        assertThat(plan(openAiRequest(false, false, "deleted-account", true)).owner()).isEmpty();
    }

    @Test
    void anOwnerThatIsARelayIsNotAnOwnerOfANativeTier() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of(
            relayMember("openai", "relay", "official"))));
        when(accountPool.cachedAccount("relay")).thenReturn(Optional.of(relay));

        // The model is one that compatible keys serve, so the relay is a candidate of the cell: only its row keeps it out.
        assertOwnerMismatch(with(openAiRequest(false, false, "relay", false), false, true, false, false));
    }

    @Test
    void aRelayThatOwnsAChainThatCanBeReplayedIsNoOwnerEitherAndTheChainIsPlannedWithoutOne() {
        // The relay of this vendor, and the exclusive relay of another vendor: neither is a member of a tier of the native cells
        // (design 3, step 4), so the owner of a chain that has its history here is only a preference there is nothing to follow.
        for (SourceTierSnapshot configuration : List.of(
            snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of(relayMember("openai", "relay", "official"))),
            snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of(relayMember("openai", "relay", "official", false))),
            snapshot(List.of(vendor("anthropic", true), vendor("openai", true)), concat(anthropicTiers(), openAiTiers()),
                List.of(relayMember("anthropic", "relay", "official"))))) {
            loaded(configuration);
            when(accountPool.cachedAccount("relay")).thenReturn(Optional.of(relay));
            cached.add(relay);
            usable.add(relay);

            // The model is one that compatible keys serve, so the relay is a candidate of the cell: only its row keeps it out.
            TierPlan plan = plan(with(openAiRequest(false, false, "relay", true), false, true, false, false));

            assertThat(plan.tiered()).isTrue();
            assertThat(plan.owner()).isEmpty();
            assertThat(plan.levels()).isNotEmpty().allSatisfy(level -> assertThat(plan.holds(level, relay)).isFalse());
            cached.remove(relay);
            usable.remove(relay);
        }
    }

    @Test
    void aRelayThatOwnsAChainThatCannotBeReplayedRefusesItWithoutNamingTheRelay() {
        loaded(snapshot(List.of(vendor("anthropic", true), vendor("openai", true)), concat(anthropicTiers(), openAiTiers()),
            List.of(relayMember("anthropic", "relay", "official"))));
        when(accountPool.cachedAccount("relay")).thenReturn(Optional.of(relay));
        cached.add(relay);
        usable.add(relay);

        assertOwnerMismatch(with(openAiRequest(false, false, "relay", false), false, true, false, false));
    }

    @Test
    void aRequestWithoutAChainHasNoOwner() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers(), List.of()));

        assertThat(plan(openAiRequest(false, false)).owner()).isEmpty();
    }

    // ---- fixtures ----

    private void assertOwnerMismatch(TierPlanRequest request) {
        StepVerifier.create(planner.plan(request))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ApiException.class);
                ApiException conflict = (ApiException) error;
                assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(conflict.getCode()).isEqualTo("RESPONSE_OWNER_TIER_MISMATCH");
                assertThat(conflict.getMessage()).doesNotContain("codex").doesNotContain("relay");
            })
            .verify();
        verifyNoInteractions(payments, pricing);
    }

    private String modelKeyOf(String model) {
        return plan(request(model, TierRoute.ANTHROPIC)).modelKey();
    }

    private TierPlan plan(TierPlanRequest request) {
        return planner.plan(request).block();
    }

    private void loaded(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    private List<PoolCell> units(TierRoute route, java.util.function.UnaryOperator<TierPlanRequest> flags) {
        TierPlanRequest base = new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", route, false, false, false,
            true, false, true, body("gpt-5.5"), null, false, null, null);
        return DefaultSourceTierPlanner.nativeUnits(flags.apply(base));
    }

    /** The routing facts of a request: deepSeek, openAiCompatible, codex preferred, codex isolated. */
    private static TierPlanRequest with(TierPlanRequest r, boolean deepSeek, boolean compatible, boolean codexPreferred,
        boolean isolated) {
        return new TierPlanRequest(r.requestId(), r.requestedModel(), r.upstreamModel(), r.providerHint(), r.route(),
            deepSeek, compatible, codexPreferred, isolated, r.fastMode(), r.billed(), r.upstreamRequest(),
            r.responseOwnerAccountId(), r.responseMovable(), null, null);
    }

    private TierPlanRequest request(String model, TierRoute route) {
        return request(model, model, route);
    }

    private TierPlanRequest request(String upstreamModel, String requestedModel, TierRoute route) {
        return new TierPlanRequest("request-1", requestedModel, upstreamModel, "Anthropic", route, false, false, false, true,
            false, true, body(upstreamModel), null, false, null, null);
    }

    private TierPlanRequest requestWith(ObjectNode body, TierRoute route) {
        return new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic", route, false, false,
            false, true, false, true, body, null, false, null, null);
    }

    private TierPlanRequest openAiRequest(boolean isolated, boolean preferCodex) {
        return openAiRequest(isolated, preferCodex, null, false);
    }

    private TierPlanRequest openAiRequest(boolean isolated, boolean preferCodex, String owner, boolean movable) {
        return new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI, false, false, preferCodex,
            isolated, false, true, body("gpt-5.5"), owner, movable, null, null);
    }

    private ObjectNode body(String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private ObjectNode bodyWithInlineImage(String model, int base64Chars) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        var parts = body.putArray("messages").addObject().put("role", "user").putArray("content");
        parts.addObject().put("type", "text").put("text", "what is in this picture");
        parts.addObject().put("type", "image_url").putObject("image_url")
            .put("url", "data:image/png;base64," + "A".repeat(base64Chars));
        body.put("max_tokens", 4096);
        return body;
    }

    private List<Tier> anthropicTiers() {
        return List.of(
            tier("anthropic", "official", 1, "官方", "1", true, OFFICIAL),
            tier("anthropic", "kiro", 2, "kiro反向代理", "0.6", true, KIRO, CLAUDE_OAUTH)
        );
    }

    private List<Tier> openAiTiers() {
        return List.of(
            tier("openai", "official", 1, "official", "1", true, OFFICIAL),
            tier("openai", "codex", 2, "codex", "0.5", true, CODEX)
        );
    }

    private static SourceTierSnapshot snapshot(List<VendorConfig> vendors, List<Tier> tiers, List<Member> members) {
        return SourceTierSnapshot.of(7L, vendors, tiers, members, List.of(), List.of());
    }

    private static VendorConfig vendor(String vendor, boolean enabled) {
        return new VendorConfig(vendor, enabled, false, false, true, 16, true, 7L);
    }

    private static Tier tier(String vendor, String tierId, int level, String name, String factor, boolean enabled,
        AccountAllocationCategory... categories) {
        Set<AccountAllocationCategory> listed = categories.length == 0
            ? EnumSet.noneOf(AccountAllocationCategory.class)
            : EnumSet.copyOf(List.of(categories));
        return new Tier(vendor, tierId, level, name, new BigDecimal(factor), listed, true, enabled, null);
    }

    private static Member nativeMember(String vendor, String accountId, String tierId) {
        return new Member(vendor, accountId, tierId, null, true, false, false, false, false, true, "NONE", 540, null, null);
    }

    private static Member relayMember(String vendor, String accountId, String tierId) {
        return relayMember(vendor, accountId, tierId, true);
    }

    private static Member relayMember(String vendor, String accountId, String tierId, boolean exclusive) {
        return new Member(vendor, accountId, tierId, "OPENAI_CHAT", exclusive, false, false, false, false, true, "NONE", 540,
            true, true);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod, String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setBaseUrl(baseUrl);
        account.setEnabled(true);
        return account;
    }

    private static PaymentConfig paymentConfig(BigDecimal apiBillingMultiplier) {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, apiBillingMultiplier, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
