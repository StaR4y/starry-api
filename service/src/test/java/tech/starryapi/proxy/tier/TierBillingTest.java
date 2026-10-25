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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a tiered request reserves and the price it is settled at (design 5.2, 5.3): the largest estimate of the tiers it
 * may be served from, a snapshot that names them, and the price of exactly the tier that served and no other.
 */
class TierBillingTest {

    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));
    /** 8,000 tokens in and 1,000 out: $0.039 at the standard price. */
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);

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
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
    }

    // ---- what a billing is made of ----

    @Test
    void anUntieredPlanHasNoTieredBilling() {
        assertThatThrownBy(() -> TierBilling.of(TierPlan.UNTIERED, "claude-sonnet-4.5")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePlanOfARequestThatIsNotBilledCarriesNoPriceToReserveBy() {
        TierPlan unbilled = plan("1", "0.6", false);

        assertThat(unbilled.tiered()).isTrue();
        assertThat(unbilled.reservation()).isNull();
        assertThatThrownBy(() -> TierBilling.of(unbilled, "claude-sonnet-4.5")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theReservationIsTheLargestEstimateOfTheTiersThePlanHolds() {
        TierPlan cheapThenDear = plan("0.5", "1.5", true);
        TierPlan dearThenCheap = plan("1.5", "0.5", true);

        BigDecimal dearest = QUOTE.scaled(new BigDecimal("1.5")).estimate(cheapThenDear.levels().getFirst().inputBound(), 4096);
        assertThat(TierBilling.of(cheapThenDear, "claude-sonnet-4.5").reservation()).isEqualByComparingTo(dearest);
        assertThat(TierBilling.of(dearThenCheap, "claude-sonnet-4.5").reservation()).isEqualByComparingTo(dearest);
    }

    // ---- the snapshot ----

    @Test
    void theSnapshotHoldsTheStandardPriceAndEveryTierWithNumbersTheDatabaseTriggerCanRead() throws Exception {
        TierPlan plan = plan("1", "0.6", true);

        JsonNode snapshot = mapper.readTree(TierBilling.of(plan, "claude-sonnet-4.5").pricing());

        assertThat(snapshot.path("model").asString()).isEqualTo("claude-sonnet-4.5");
        assertThat(snapshot.path("input").decimalValue()).isEqualByComparingTo("0.000003");
        assertThat(snapshot.path("fallback").decimalValue()).isEqualByComparingTo("0.000003");
        JsonNode tiers = snapshot.path("tierPlan");
        assertThat(tiers.path("vendor").asString()).isEqualTo("anthropic");
        assertThat(tiers.path("configVersion").asLong()).isEqualTo(7L);
        assertThat(tiers.path("mode").asString()).isEqualTo("auto");
        assertThat(tiers.path("startTierRef").asString()).isEqualTo("anthropic:official");
        assertThat(tiers.path("holdLevels")).extracting(JsonNode::asString).containsExactly("anthropic:official", "anthropic:kiro");
        JsonNode second = tiers.path("levels").path(1);
        assertThat(second.path("tierRef").asString()).isEqualTo("anthropic:kiro");
        assertThat(second.path("level").asInt()).isEqualTo(2);
        assertThat(second.path("name").asString()).isEqualTo("kiro");
        // The usage-event trigger reads the factor as a number, not as text.
        assertThat(second.path("factor").isNumber()).isTrue();
        assertThat(second.path("factor").decimalValue()).isEqualByComparingTo("0.6");
        assertThat(second.path("estimate").isNumber()).isTrue();
        assertThat(second.path("dispatchKinds")).extracting(JsonNode::asString).containsExactly("NATIVE");
    }

    @Test
    void aTierThatCanBeReachedThroughARelayNamesThatKindOfDispatchToo() throws Exception {
        TierPlan plan = plan("1", "0.6", true);
        Level relayAndNative = new Level(plan.levels().getFirst().tier(), "anthropic:official",
            List.of(PoolCell.ANTHROPIC_KEY, PoolCell.XP_OPENAI_CHAT), 1_000L, QUOTE, QUOTE.estimate(1_000L, 4096), java.util.Map.of());
        TierPlan withRelay = new TierPlan("anthropic", "claude-sonnet-4.5", TierPlan.Mode.AUTO, SourceTierSnapshot.EMPTY,
            new VendorConfig("anthropic", true, false, false, true, 16, true, 7L), List.of(relayAndNative), List.of(relayAndNative),
            List.of(), java.util.Set.of(), 0, null, null, 4096, QUOTE, new AccountPoolRequestContext("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic", null, null));

        JsonNode level = mapper.readTree(TierBilling.of(withRelay, "claude-sonnet-4.5").pricing()).path("tierPlan").path("levels").path(0);

        assertThat(level.path("dispatchKinds")).extracting(JsonNode::asString).containsExactly("NATIVE", "OPENAI_CHAT");
    }

    @Test
    void aRequestTheClientPinnedIsRecordedAsPinned() throws Exception {
        TierPlan plan = plan("1", "0.6", true);
        TierPlan pinned = new TierPlan("anthropic", "claude-sonnet-4.5", TierPlan.Mode.PINNED, SourceTierSnapshot.EMPTY,
            new VendorConfig("anthropic", true, false, false, true, 16, true, 7L), List.of(plan.levels().get(1)),
            List.of(plan.levels().get(1)), List.of(), java.util.Set.of(), 0, null, null, 4096, QUOTE, plan.poolContext());

        assertThat(pinned.pinned()).isTrue();
        assertThat(plan.pinned()).isFalse();
        JsonNode snapshot = mapper.readTree(TierBilling.of(pinned, "claude-sonnet-4.5").pricing());
        assertThat(snapshot.path("tierPlan").path("mode").asString()).isEqualTo("pinned");
        assertThat(snapshot.path("tierPlan").path("startTierRef").asString()).isEqualTo("anthropic:kiro");
        assertThat(snapshot.path("tierPlan").path("holdLevels")).extracting(JsonNode::asString).containsExactly("anthropic:kiro");
    }

    // ---- the price of the tier that served ----

    @Test
    void theCostIsThePriceOfTheTierThatServed() {
        TierPlan plan = plan("1", "0.6", true);
        TierBilling billing = TierBilling.of(plan, "claude-sonnet-4.5");

        // The example of the design: 8,000 in and 1,000 out, $0.039 at the standard price.
        assertThat(billing.cost(plan.levels().get(0), USAGE)).isEqualByComparingTo("0.039");
        assertThat(billing.cost(plan.levels().get(1), USAGE)).isEqualByComparingTo("0.0234");
    }

    @Test
    void aTierThePlanDidNotPriceIsAnErrorNotTheLargestQuote() {
        TierPlan plan = plan("1", "0.6", true);
        TierPlan another = plan("1", "3", true);
        Level foreign = new Level(another.levels().get(1).tier(), "anthropic:relay", List.of(PoolCell.KIRO_COMPATIBLE),
            1_000L, QUOTE.scaled(new BigDecimal("3")), BigDecimal.ONE, java.util.Map.of());

        assertThatThrownBy(() -> TierBilling.of(plan, "claude-sonnet-4.5").cost(foreign, USAGE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("anthropic:relay");
    }

    @Test
    void theCostOfATierComesFromThePlansQuoteNotFromTheLevelThatWasHandedIn() {
        TierPlan plan = plan("1", "0.6", true);
        Level served = plan.levels().get(1);
        // A level with the right tier and a wrong (dearer) price: the plan's own quote decides.
        Level tampered = new Level(served.tier(), served.tierRef(), served.cells(), served.inputBound(), QUOTE, served.estimate(),
            served.responseHeaders());

        assertThat(TierBilling.of(plan, "claude-sonnet-4.5").cost(tampered, USAGE)).isEqualByComparingTo("0.0234");
    }

    // ---- fixtures ----

    private TierPlan plan(String firstFactor, String secondFactor, boolean billed) {
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 7L)),
            List.of(
                tier("official", 1, firstFactor, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("kiro", 2, secondFactor, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of()));
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return planner.plan(new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, billed, body, null, false, null, null)).block();
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
