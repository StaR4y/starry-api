package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.proxy.tier.TierSessionBindings.Binding;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a walk has seen on the way, which decides whether the tier that served the request is one its session was pushed
 * down to (design 4.7, invariant I8), and which tiers it may climb back to as a last resort.
 */
class TierWalkSessionTest {

    private static final int CAP = 4;
    private static final String SESSION = "key-1:session-1";
    private static final BillingQuote STANDARD = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final TierSessionBindings bindings = new TierSessionBindings(new ProxyProperties());
    private final DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(
        registry, new ModelPoolKeyResolver(catalog), catalog, accountPool, payments, pricing, bindings);

    /** The accounts of the account cache: one of each kind of tier unless a test says otherwise. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** The ids of the cached accounts that are suspended or out of quota. */
    private final Set<String> outOfService = new HashSet<>();

    @BeforeEach
    void stubTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(STANDARD));
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenAnswer(invocation ->
            !outOfService.contains(invocation.<ProxyAccountEntity>getArgument(0).getAccountId()));
        accounts();
    }

    // ---- a downgrade that is real ----

    @Test
    void aTierThatWasGoneThroughAndFoundEmptyPushesTheSessionDownToTheNextOne() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        walk.passed(level(plan, "official"));

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isTrue();
    }

    @Test
    void everyTierBetweenTheStartAndTheServingOneMustHaveBeenGoneThrough() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        walk.passed(level(plan, "official"));

        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isFalse();
        walk.passed(level(plan, "oauth"));
        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isTrue();
    }

    @Test
    void aCrossingToTheNativeAccountsAfterARelayRefusedTheRequestPushesNobodyDown() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.passed(level(plan, "official"));
        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isTrue();

        // The same walk, left for the request's own sake: the tier below serves it, and the session stays where it was.
        walk.crossedToNative(level(plan, "official"));

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isFalse();
    }

    @Test
    void beingServedByTheTierItStartedFromPushesNobodyDown() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(walk.realDowngradeTo(level(plan, "official"))).isFalse();
    }

    @Test
    void aTierWasNotGoneThroughWhenTheWalkSkippedItToServeTheOwnerOfAChainDirectly() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        // The owner of a response chain is selected before the walk looks at any tier.
        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
    }

    @Test
    void aTierThatRefusedAsOftenAsTheCapAllowsCountsAsGoneThrough() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        for (int i = 0; i < CAP; i++) {
            walk.refused(level(plan, "official"), "official-" + i);
        }

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isTrue();
    }

    @Test
    void aSessionThatStartedInALowerTierIsPushedDownFromThatTierNotFromTheBestOne() {
        TierPlan plan = planBoundTo("oauth", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
        walk.passed(level(plan, "oauth"));
        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isTrue();
    }

    @Test
    void aTierThatWasFoundEmptyAndThenServedAfterTheCacheWasReloadedWasNotGoneThrough() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.passed(level(plan, "official"));

        walk.selected(level(plan, "official"));

        assertThat(walk.wasPassed(level(plan, "official"))).isFalse();
        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
    }

    @Test
    void servedAboveTheStartIsNotADowngrade() {
        TierPlan plan = planBoundTo("kiro", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.passed(level(plan, "kiro"));

        assertThat(walk.realDowngradeTo(level(plan, "official"))).isFalse();
    }

    // ---- tiers the plan left out because nothing in them is available ----

    @Test
    void aTierLeftOutOfThePlanForHavingNothingAvailableIsOneTheSessionIsPushedOutOf() {
        accounts();
        outOfService.add("official-1");
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(tierIds(plan.levels())).containsExactly("oauth", "kiro");
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("official");
        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isTrue();
    }

    @Test
    void aTierWithNoAccountOfTheRouteAtAllIsNotOneTheSessionIsPushedOutOf() {
        cached.clear();
        cached.addAll(List.of(account("oauth-1", "oauth"), account("kiro-1", "idc")));
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(tierIds(plan.levels())).containsExactly("oauth", "kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
    }

    @Test
    void everyTierLeftOutAboveTheServingOneCountsAndTheTiersBetweenMustHaveBeenGoneThrough() {
        accounts();
        outOfService.addAll(List.of("official-1", "oauth-1"));
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(tierIds(plan.levels())).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("official", "oauth");
        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isTrue();
    }

    @Test
    void aTierLeftOutBetweenTheStartAndTheServingTierCountsLikeOneThatWasGoneThrough() {
        accounts();
        outOfService.add("oauth-1");
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(tierIds(plan.levels())).containsExactly("official", "kiro");
        // The best tier was gone through and found empty; the one in the middle was found so before the walk.
        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isFalse();
        walk.passed(level(plan, "official"));
        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isTrue();
    }

    @Test
    void aTierLeftOutBelowTheOnesThatServedCountsForNothing() {
        accounts();
        outOfService.add("kiro-1");
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("kiro");
        assertThat(walk.realDowngradeTo(level(plan, "official"))).isFalse();
    }

    @Test
    void aSessionIsOnlyPushedOutOfTheTiersFromItsOwnDown() {
        accounts();
        outOfService.addAll(List.of("official-1", "oauth-1"));
        TierPlan plan = planBoundTo("oauth", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        // The session is in the second tier, which is out of service now; the first tier is above it and does not matter.
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("oauth");
        assertThat(tierIds(plan.levels())).containsExactly("kiro");
        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isTrue();
    }

    @Test
    void servedByTheTierItIsBoundToWithTheTiersAboveItOutOfServiceIsNoDowngrade() {
        accounts();
        outOfService.add("official-1");
        TierPlan plan = planBoundTo("oauth", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
    }

    @Test
    void aTierLeftOutDoesNotPushTheSessionDownWhenAllRefusalsWereItsOwn() {
        accounts();
        outOfService.add("official-1");
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.refused(level(plan, "oauth"), "oauth-1", true);
        walk.passed(level(plan, "oauth"));

        // oauth-1 is not above the serving tier here (kiro serves), and its refusal is the session's own.
        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isFalse();
    }

    // ---- tiers that never had an account ----

    @Test
    void whenNothingIsAvailableATierWithNoAccountAtAllIsNotKeptInThePlan() {
        cached.clear();
        cached.add(account("kiro-1", "idc"));
        outOfService.add("kiro-1");

        TierPlan plan = plan("1", "1", "0.6");

        // The official and OAuth tiers have no account of the route; only the suspended Kiro account is left to wait for.
        assertThat(tierIds(plan.levels())).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).isEmpty();
        assertThat(plan.reservation()).isEqualTo(level(plan, "kiro").estimate());
    }

    @Test
    void aTierThatNeverHadAnAccountIsNotOneTheSessionIsPushedOutOfWhateverTheCacheReloadFinds() {
        cached.clear();
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        // No tier has an account, so all stay; the walk passes through all of them and, after a reload, a lower tier serves.
        assertThat(tierIds(plan.levels())).containsExactly("official", "oauth", "kiro");
        assertThat(plan.memberless(level(plan, "official"))).isTrue();
        assertThat(plan.memberless(level(plan, "oauth"))).isTrue();
        walk.passed(level(plan, "official"));
        walk.passed(level(plan, "oauth"));

        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isFalse();
        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
    }

    @Test
    void aTierWithAnAccountThatIsOutOfServiceIsNotMemberless() {
        outOfService.addAll(List.of("official-1", "oauth-1", "kiro-1"));

        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(tierIds(plan.levels())).containsExactly("official", "oauth", "kiro");
        assertThat(plan.levels()).noneMatch(plan::memberless);
        walk.passed(level(plan, "official"));
        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isTrue();
    }

    @Test
    void aPlanWhoseOnlyTierHasAnAccountPushesNobodyDownToIt() {
        cached.clear();
        cached.add(account("kiro-1", "idc"));
        outOfService.add("kiro-1");
        TierPlan plan = plan("1", "1", "1");

        // Only the Kiro tier stays (the others have no account to push the session out of).
        assertThat(tierIds(plan.levels())).containsExactly("kiro");
        assertThat(new TierWalk(plan).realDowngradeTo(level(plan, "kiro"))).isFalse();
    }

    // ---- a downgrade that is not ----

    @Test
    void aTierLeftBecauseTheBudgetWasSpentDidNotPushTheSessionDown() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.passed(level(plan, "official"));

        walk.crossedOnBudget();

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
    }

    @Test
    void refusalsThatAreAllTheSessionsOwnParallelRequestsDoNotPushItDown() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.refused(level(plan, "official"), "official-1", true);
        walk.refused(level(plan, "official"), "official-2", true);
        walk.passed(level(plan, "official"));

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isFalse();
    }

    @Test
    void oneRefusalThatIsNotTheSessionsOwnMakesItARealDowngrade() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.refused(level(plan, "official"), "official-1", true);
        walk.refused(level(plan, "official"), "official-2", false);
        walk.passed(level(plan, "official"));

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isTrue();
    }

    @Test
    void aTierWithoutAnyRefusalIsJustEmpty() {
        // Nothing offered by suspended or exhausted accounts is not a refusal at all.
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.passed(level(plan, "official"));

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isTrue();
    }

    @Test
    void whatTheServingTierAndTheOnesBelowItRefusedIsNotWhatPushedTheSessionDown() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.passed(level(plan, "official"));
        walk.refused(level(plan, "oauth"), "oauth-1", true);
        walk.refused(level(plan, "kiro"), "kiro-1", true);

        assertThat(walk.realDowngradeTo(level(plan, "oauth"))).isTrue();
    }

    @Test
    void aRefusalByOthersInAnyTierAboveMakesItRealEvenWhenAnotherTierAboveRefusedOnlyForTheSession() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.refused(level(plan, "official"), "official-1", true);
        walk.passed(level(plan, "official"));
        walk.refused(level(plan, "oauth"), "oauth-1", false);
        walk.passed(level(plan, "oauth"));

        assertThat(walk.realDowngradeTo(level(plan, "kiro"))).isTrue();
    }

    // ---- the climb back ----

    @Test
    void aRequestThatStartedFromTheBestTierHasNothingToClimbTo() {
        TierPlan plan = plan("1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(walk.climbable()).isFalse();
        assertThat(walk.climbLevels()).isEmpty();
    }

    @Test
    void aSessionThatStartedLowerMayClimbToTheTiersAboveWhenTheReservationCoversThem() {
        TierPlan plan = planBoundTo("kiro", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        assertThat(walk.climbable()).isTrue();
        assertThat(tierIds(walk.climbLevels())).containsExactly("official", "oauth");
    }

    @Test
    void aTierTheReservationDoesNotCoverIsNotClimbedTo() {
        // The tier above costs twice as much as the one the session is in, so a user who had enough for the latter is not
        // reserved enough for the former.
        TierPlan plan = planBoundTo("oauth", "2", "1", "1");

        TierWalk walk = new TierWalk(plan);

        assertThat(tierIds(plan.holdLevels())).containsExactly("oauth", "kiro");
        assertThat(walk.climbLevels()).isEmpty();
    }

    @Test
    void aTierThatHasRefusedAsOftenAsTheCapAllowsIsNotClimbedTo() {
        TierPlan plan = planBoundTo("kiro", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        for (int i = 0; i < CAP; i++) {
            walk.refused(level(plan, "official"), "official-" + i);
        }

        assertThat(tierIds(walk.climbLevels())).containsExactly("oauth");
    }

    @Test
    void aClimbIsRealWhenNothingFromTheStartDownRefusedBecauseOfTheSession() {
        TierPlan plan = planBoundTo("kiro", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.passed(level(plan, "kiro"));

        assertThat(walk.realClimb()).isTrue();
    }

    @Test
    void aClimbForcedByTheSessionsOwnParallelRequestsIsNotReal() {
        TierPlan plan = planBoundTo("kiro", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.refused(level(plan, "kiro"), "kiro-1", true);
        walk.passed(level(plan, "kiro"));

        assertThat(walk.realClimb()).isFalse();
    }

    @Test
    void aClimbIsRealWhenOneRefusalFromTheStartDownWasSomeoneElses() {
        TierPlan plan = planBoundTo("oauth", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.refused(level(plan, "oauth"), "oauth-1", true);
        walk.refused(level(plan, "kiro"), "kiro-1", false);

        assertThat(walk.realClimb()).isTrue();
    }

    @Test
    void whatTheTiersAboveTheStartRefusedDoesNotMakeAClimbReal() {
        TierPlan plan = planBoundTo("kiro", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);
        walk.refused(level(plan, "official"), "official-1", false);
        walk.refused(level(plan, "kiro"), "kiro-1", true);

        assertThat(walk.realClimb()).isFalse();
    }

    @Test
    void aRestrictedRequestDoesNotClimb() {
        TierPlan plan = planBoundTo("kiro", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        walk.narrowTo(level(plan, "kiro"));

        assertThat(walk.climbable()).isFalse();
        assertThat(walk.climbLevels()).isEmpty();
    }

    @Test
    void theClimbStartsFromWhereTheSessionStartedEvenAfterTheWalkMovedDown() {
        TierPlan plan = planBoundTo("oauth", "1", "1", "1");
        TierWalk walk = new TierWalk(plan);

        walk.stayAtOrBelow(level(plan, "kiro"));

        assertThat(tierIds(walk.climbLevels())).containsExactly("official");
    }

    // ---- fixtures ----

    private TierPlan plan(String officialFactor, String oauthFactor, String kiroFactor) {
        return planBoundTo(null, officialFactor, oauthFactor, kiroFactor);
    }

    /** The plan of a request whose session is bound to {@code boundTier}, or to none when that is null. */
    private TierPlan planBoundTo(String boundTier, String officialFactor, String oauthFactor, String kiroFactor) {
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("anthropic", true, false, false, true, CAP, true, 7L)),
            List.of(
                tier("official", 1, officialFactor, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("oauth", 2, oauthFactor, AccountAllocationCategory.CLAUDE_OAUTH),
                tier("kiro", 3, kiroFactor, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of()));
        String key = TierSessionKeys.bindingKey("anthropic", "claude-sonnet-4.5", SESSION);
        if (boundTier != null) {
            int number = switch (boundTier) {
                case "official" -> 1;
                case "oauth" -> 2;
                default -> 3;
            };
            bindings.climb(key, new Binding(boundTier, number));
        }
        return planner.plan(new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, body("claude-sonnet-4.5"), null, false,
            "key-1", SESSION)).block();
    }

    /** One account of each of the three kinds of tier, in the cache. */
    private void accounts() {
        cached.clear();
        cached.addAll(List.of(account("official-1", "api-key"), account("oauth-1", "oauth"), account("kiro-1", "idc")));
    }

    private static ProxyAccountEntity account(String accountId, String authMethod) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider("idc".equals(authMethod) ? "BuilderId" : "Anthropic");
        account.setAuthMethod(authMethod);
        account.setEnabled(true);
        return account;
    }

    private static Level level(TierPlan plan, String tierId) {
        return plan.levels().stream().filter(level -> level.tierId().equals(tierId)).findFirst().orElseThrow();
    }

    private static List<String> tierIds(List<Level> levels) {
        return levels.stream().map(Level::tierId).toList();
    }

    private ObjectNode body(String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private static Tier tier(String tierId, int level, String factor, AccountAllocationCategory category) {
        return new Tier("anthropic", tierId, level, tierId, new BigDecimal(factor), EnumSet.of(category), true, true, null);
    }

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
