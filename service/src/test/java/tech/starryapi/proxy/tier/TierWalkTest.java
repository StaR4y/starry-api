package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a tiered request remembers between selections: the tiers still offered (the start, a narrowing, the refusal
 * cap), the accounts it must not be offered again, and the response owner it prefers once.
 */
class TierWalkTest {

    private static final int CAP = 4;
    private static final BillingQuote STANDARD = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(
        registry, new ModelPoolKeyResolver(catalog), catalog, accountPool, payments, pricing, TierSessionBindings.disabled());

    @BeforeEach
    void stubTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(STANDARD));
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("anthropic", true, false, false, true, CAP, true, 7L)),
            List.of(
                tier("official", 1, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("oauth", 2, AccountAllocationCategory.CLAUDE_OAUTH),
                tier("kiro", 3, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of()));
    }

    // ---- where the walk starts ----

    @Test
    void aWalkOffersEveryTierFromTheBestOne() {
        TierWalk walk = new TierWalk(plan());

        assertThat(tierIds(walk.levels())).containsExactly("official", "oauth", "kiro");
        assertThat(tierIds(walk.openLevels())).containsExactly("official", "oauth", "kiro");
    }

    @Test
    void aStartIndexLeavesOutTheTiersAboveIt() {
        TierWalk walk = new TierWalk(plan(), 1);

        assertThat(tierIds(walk.levels())).containsExactly("oauth", "kiro");
    }

    @Test
    void aStartIndexOutsideThePlanIsRefused() {
        TierPlan plan = plan();

        assertThatThrownBy(() -> new TierWalk(plan, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TierWalk(plan, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNarrowedWalkOffersOnlyThatTier() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        walk.narrowTo(plan.levels().get(1));

        assertThat(tierIds(walk.levels())).containsExactly("oauth");
        assertThat(tierIds(walk.openLevels())).containsExactly("oauth");
    }

    // ---- where a retry resumes ----

    @Test
    void aRetryStaysAtTheTierThatFailedAndBelowAndNeverMovesBackUp() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        walk.stayAtOrBelow(plan.levels().get(1));
        assertThat(tierIds(walk.levels())).containsExactly("oauth", "kiro");

        walk.stayAtOrBelow(plan.levels().get(0));
        assertThat(tierIds(walk.levels())).containsExactly("oauth", "kiro");
    }

    @Test
    void movingBelowATierLeavesItOutAndEverythingAboveIt() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        walk.moveBelow(plan.levels().get(0));
        assertThat(tierIds(walk.levels())).containsExactly("oauth", "kiro");

        walk.moveBelow(plan.levels().get(1));
        assertThat(tierIds(walk.levels())).containsExactly("kiro");
        assertThat(walk.narrowed()).isFalse();
    }

    @Test
    void thereIsNothingBelowTheLastTierToMoveTo() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        assertThat(walk.hasTierBelow(plan.levels().get(0))).isTrue();
        assertThat(walk.hasTierBelow(plan.levels().get(1))).isTrue();
        assertThat(walk.hasTierBelow(plan.levels().get(2))).isFalse();
        assertThatThrownBy(() -> walk.moveBelow(plan.levels().get(2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTiersBelowATierAreTheOnesAfterItInThePlanWhateverTheWalkOffers() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);
        walk.moveBelow(plan.levels().get(0));

        assertThat(tierIds(walk.tiersBelow(plan.levels().get(0)))).containsExactly("oauth", "kiro");
        assertThat(tierIds(walk.tiersBelow(plan.levels().get(1)))).containsExactly("kiro");
        assertThat(walk.tiersBelow(plan.levels().get(2))).isEmpty();
    }

    @Test
    void aNarrowedWalkSaysSoAndIgnoresWhereItResumes() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);
        assertThat(walk.narrowed()).isFalse();

        walk.narrowTo(plan.levels().get(0));
        walk.moveBelow(plan.levels().get(0));

        assertThat(walk.narrowed()).isTrue();
        assertThat(tierIds(walk.levels())).containsExactly("official");
    }

    // ---- the refusal cap (D2) ----

    @Test
    void aTierThatRefusedAsOftenAsTheCapIsLeftOutWhileALowerTierRemains() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        for (int i = 1; i < CAP; i++) {
            walk.refused(plan.levels().get(0), "official-" + i);
        }
        assertThat(tierIds(walk.openLevels())).containsExactly("official", "oauth", "kiro");

        walk.refused(plan.levels().get(0), "official-" + CAP);
        assertThat(tierIds(walk.openLevels())).containsExactly("oauth", "kiro");
        // Leaving a tier out does not narrow the walk: it still has the tiers it had.
        assertThat(tierIds(walk.levels())).containsExactly("official", "oauth", "kiro");
    }

    @Test
    void eachTierIsCountedOnItsOwn() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        for (int i = 0; i < CAP - 1; i++) {
            walk.refused(plan.levels().get(0), "official-" + i);
            walk.refused(plan.levels().get(1), "oauth-" + i);
        }

        assertThat(tierIds(walk.openLevels())).containsExactly("official", "oauth", "kiro");
        assertThat(walk.refusals()).isEqualTo(2 * (CAP - 1));
    }

    @Test
    void aMiddleTierIsLeftOutByItsOwnCountOnly() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        for (int i = 0; i < CAP; i++) {
            walk.refused(plan.levels().get(1), "oauth-" + i);
        }

        assertThat(tierIds(walk.openLevels())).containsExactly("official", "kiro");
    }

    @Test
    void theLastTierIsNeverLeftOutByTheCap() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        for (int i = 0; i < 3 * CAP; i++) {
            walk.refused(plan.levels().get(2), "kiro-" + i);
        }

        assertThat(tierIds(walk.openLevels())).containsExactly("official", "oauth", "kiro");
    }

    @Test
    void aTierWithNothingBelowItInTheWalkIsNotCappedEitherWhenTheWalkIsNarrowedOrStartsLate() {
        TierPlan plan = plan();
        TierWalk narrowed = new TierWalk(plan);
        narrowed.narrowTo(plan.levels().get(0));
        TierWalk late = new TierWalk(plan, 2);

        for (int i = 0; i < 2 * CAP; i++) {
            narrowed.refused(plan.levels().get(0), "official-" + i);
            late.refused(plan.levels().get(2), "kiro-" + i);
        }

        assertThat(tierIds(narrowed.openLevels())).containsExactly("official");
        assertThat(tierIds(late.openLevels())).containsExactly("kiro");
    }

    // ---- accounts the request must not be offered again ----

    @Test
    void triedAndRefusedAccountsAreExcludedInTheOrderTheyWereMetAndOnlyOnce() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        walk.attempted("a");
        walk.refused(plan.levels().get(0), "b");
        walk.attempted("a");
        walk.attempted(" ");
        walk.attempted(null);

        assertThat(walk.excluded()).containsExactly("a", "b");
        // Only a refusal counts against a tier; an account that was dispatched to does not.
        assertThat(walk.refusals()).isEqualTo(1);
    }

    @Test
    void onlyAnAccountThatWasDispatchedToCountsAgainstTheRetryBudget() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        walk.refused(plan.levels().get(0), "refused-1");
        walk.refused(plan.levels().get(0), "refused-2");
        assertThat(walk.dispatchedAccounts()).isZero();

        walk.attempted("a");
        walk.attempted("a");
        walk.attempted("b");

        assertThat(walk.dispatchedAccounts()).isEqualTo(2);
        assertThat(walk.excluded()).containsExactly("refused-1", "refused-2", "a", "b");
    }

    @Test
    void theExcludedListIsASnapshotThatLaterSelectionsDoNotChange() {
        TierWalk walk = new TierWalk(plan());
        walk.attempted("a");

        List<String> before = walk.excluded();
        walk.attempted("b");

        assertThat(before).containsExactly("a");
        assertThat(walk.excluded()).containsExactly("a", "b");
    }

    // ---- the response owner ----

    @Test
    void theOwnerOfAResponseChainIsOfferedToTheFirstSelectionOnly() {
        // The owner sits in the tier the walk starts from, which is where a chain that can be replayed keeps its owner.
        ProxyAccountEntity owner = account("official-owner", "OpenAI", "api-key");
        when(accountPool.cachedAccount("official-owner")).thenReturn(Optional.of(owner));
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("openai", true, false, false, true, CAP, true, 7L)),
            List.of(
                openAiTier("official", 1, AccountAllocationCategory.OFFICIAL_API_KEY),
                openAiTier("codex", 2, AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of()));
        TierPlan plan = planner.plan(new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, false, false, true, body("gpt-5.5"), "official-owner", true, null, null)).block();
        TierWalk walk = new TierWalk(plan);

        Optional<TierPlan.Owner> first = walk.takeOwner();
        Optional<TierPlan.Owner> second = walk.takeOwner();

        assertThat(first).hasValueSatisfying(chain -> {
            assertThat(chain.accountId()).isEqualTo("official-owner");
            assertThat(chain.level().tierId()).isEqualTo("official");
        });
        assertThat(second).isEmpty();
    }

    @Test
    void aRequestWithoutAResponseChainHasNoOwner() {
        assertThat(new TierWalk(plan()).takeOwner()).isEmpty();
    }

    // ---- the retest ----

    @Test
    void aRequestHasOneRetestOfTheAccountsThatAreOutOfService() {
        TierWalk walk = new TierWalk(plan());

        assertThat(walk.takeRetest()).isTrue();
        assertThat(walk.takeRetest()).isFalse();
        assertThat(walk.takeRetest()).isFalse();
        // Each request has its own: the one of another walk is not used up by it.
        assertThat(new TierWalk(plan()).takeRetest()).isTrue();
    }

    // ---- a relay that refused the request ----

    @Test
    void aWalkThatCrossedToTheNativeAccountsLeavesTheTierAndTheRelaysBehind() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);
        Level first = plan.levels().get(0);
        Level relayAndNative = new Level(first.tier(), first.tierRef(), List.of(PoolCell.KIRO_COMPATIBLE, PoolCell.XP_OPENAI_CHAT),
            first.inputBound(), first.quote(), first.estimate(), first.responseHeaders());
        assertThat(walk.nativeOnly()).isFalse();
        assertThat(walk.cells(relayAndNative)).containsExactly(PoolCell.KIRO_COMPATIBLE, PoolCell.XP_OPENAI_CHAT);

        walk.crossedToNative(first);

        assertThat(walk.nativeOnly()).isTrue();
        assertThat(tierIds(walk.levels())).containsExactly("oauth", "kiro");
        // The cell of the relays is the one that goes; the rest of a tier is offered as it was.
        assertThat(walk.cells(relayAndNative)).containsExactly(PoolCell.KIRO_COMPATIBLE);
        assertThat(walk.cells(plan.levels().get(2))).isEqualTo(plan.levels().get(2).cells());
    }

    @Test
    void aWalkCannotCrossBelowTheLastTier() {
        TierPlan plan = plan();
        TierWalk walk = new TierWalk(plan);

        assertThatThrownBy(() -> walk.crossedToNative(plan.levels().get(2))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- fixtures ----

    private TierPlan plan() {
        return planner.plan(new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, body("claude-sonnet-4.5"), null, false, null, null)).block();
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

    private static Tier tier(String tierId, int level, AccountAllocationCategory category) {
        return new Tier("anthropic", tierId, level, tierId, BigDecimal.ONE, EnumSet.of(category), true, true, null);
    }

    private static Tier openAiTier(String tierId, int level, AccountAllocationCategory category) {
        Set<AccountAllocationCategory> categories = EnumSet.of(category);
        return new Tier("openai", tierId, level, tierId, BigDecimal.ONE, categories, true, true, null);
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
