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
import java.util.List;
import java.util.Optional;
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
 * Where a request of a session that was pushed down to a lower tier starts, and what its reservation covers (design 3
 * step 9, 4.7 and 5.2): the tier the session is bound to and the tiers below it, plus the tiers above that would not
 * reserve more.
 */
class DefaultSourceTierPlannerSessionTest {

    private static final String SESSION = "key-1:session-1";
    private static final String BINDING_KEY = TierSessionKeys.bindingKey("anthropic", "claude-sonnet-4.5", SESSION);
    private static final BillingQuote STANDARD = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final ProxyProperties properties = new ProxyProperties();
    private final TierSessionBindings bindings = new TierSessionBindings(properties);
    private final DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(
        registry, new ModelPoolKeyResolver(catalog), catalog, accountPool, payments, pricing, bindings);

    /** Every account the account cache holds (enabled), whatever its state. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** The accounts of the cache that are available (not suspended, not out of quota). */
    private final List<ProxyAccountEntity> usable = new ArrayList<>();
    private final ProxyAccountEntity official = account("official-1", "Anthropic", "api-key");
    private final ProxyAccountEntity oauth = account("oauth-1", "Anthropic", "oauth");
    private final ProxyAccountEntity kiro = account("kiro-1", "BuilderId", "idc");

    @BeforeEach
    void stubTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(STANDARD));
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenAnswer(invocation -> usable.contains(invocation.<ProxyAccountEntity>getArgument(0)));
        cached.addAll(List.of(official, oauth, kiro));
        usable.addAll(cached);
    }

    // ---- the session ----

    @Test
    void aRequestThatNamesItsSessionHasOneUnderThatName() {
        loaded(vendor(true), threeTiers("1", "1", "1"));

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.session()).hasValueSatisfying(session -> {
            assertThat(session.key()).isEqualTo(SESSION);
            assertThat(session.bindingKey()).isEqualTo(BINDING_KEY);
        });
    }

    @Test
    void aRequestThatNamesNoSessionIsToldByItsBeginning() {
        loaded(vendor(true), threeTiers("1", "1", "1"));

        TierPlan plan = plan(request(null));

        assertThat(plan.session()).hasValueSatisfying(session -> {
            assertThat(session.key()).isEqualTo(TierSessionKeys.fingerprint("key-1", plan.modelKey(), body()));
            assertThat(session.bindingKey()).isEqualTo(TierSessionKeys.bindingKey("anthropic", plan.modelKey(), session.key()));
        });
    }

    @Test
    void aRequestThatCannotBeToldFromOthersHasNoSession() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        ObjectNode silent = mapper.createObjectNode();
        silent.put("model", "claude-sonnet-4.5").putArray("messages").addObject().put("role", "assistant").put("content", "x");

        TierPlan plan = plan(requestWith(silent, null));

        assertThat(plan.session()).isEmpty();
        assertThat(plan.startIndex()).isZero();
    }

    @Test
    void aRequestWithoutAKeyHasNoSession() {
        loaded(vendor(true), threeTiers("1", "1", "1"));

        TierPlan plan = plan(new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, body(), null, false, null, null));

        assertThat(plan.session()).isEmpty();
    }

    @Test
    void theSessionIsPerModel() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        TierPlan sonnet = plan(request(SESSION));
        ObjectNode opusBody = body();
        opusBody.put("model", "claude-opus-4.5");

        TierPlan opus = plan(new TierPlanRequest("request-1", "claude-opus-4.5", "claude-opus-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, opusBody, null, false, "key-1", SESSION));

        assertThat(sonnet.session().orElseThrow().bindingKey()).isNotEqualTo(opus.session().orElseThrow().bindingKey());
    }

    @Test
    void aVendorThatDoesNotKeepSessionsInTheirTierHasNoSession() {
        loaded(new VendorConfig("anthropic", true, false, false, false, 16, true, 7L), threeTiers("1", "1", "1"));
        bindings.downgrade(BINDING_KEY, new Binding("kiro", 3), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.session()).isEmpty();
        assertThat(plan.startIndex()).isZero();
        assertThat(plan.holdLevels()).isEqualTo(plan.levels());
    }

    @Test
    void withSessionAffinityOffThereIsNoSession() {
        properties.setSessionAffinityEnabled(false);
        loaded(vendor(true), threeTiers("1", "1", "1"));

        assertThat(plan(request(SESSION)).session()).isEmpty();
    }

    @Test
    void aPlanForATierTheClientNamedKeepsNoSession() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        TierPlan auto = plan(request(SESSION));
        SourceTierSnapshot snapshot = registry.snapshot();

        TierPlan pinned = new TierPlan("anthropic", auto.modelKey(), TierPlan.Mode.PINNED, snapshot,
            snapshot.vendor("anthropic").orElseThrow(), auto.levels(), auto.holdLevels(), List.of(), java.util.Set.of(), 0, auto.session().orElseThrow(),
            null, auto.outputBound(), auto.standardQuote(), auto.poolContext());

        assertThat(auto.session()).isPresent();
        assertThat(pinned.session()).isEmpty();
    }

    @Test
    void aPlannerWithoutBindingsKeepsNoSession() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        DefaultSourceTierPlanner without = new DefaultSourceTierPlanner(
            registry, new ModelPoolKeyResolver(catalog), catalog, accountPool, payments, pricing, TierSessionBindings.disabled());

        assertThat(without.plan(request(SESSION)).block().session()).isEmpty();
    }

    // ---- where the request starts ----

    @Test
    void aSessionWithoutABindingStartsFromTheBestTierAndHoldsThemAll() {
        loaded(vendor(true), threeTiers("1", "0.6", "0.3"));

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.startIndex()).isZero();
        assertThat(plan.startLevel().tierId()).isEqualTo("official");
        assertThat(plan.holdLevels()).isEqualTo(plan.levels());
    }

    @Test
    void aSessionBoundToATierStartsFromIt() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        bindings.downgrade(BINDING_KEY, new Binding("oauth", 2), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.startIndex()).isEqualTo(1);
        assertThat(plan.startLevel().tierId()).isEqualTo("oauth");
    }

    @Test
    void theBindingIsFollowedByTheTiersIdWhenTheTiersAreReordered() {
        // Kiro was the third tier when the session was bound to it; it is the first now.
        loaded(vendor(true), List.of(
            tier("kiro", 1, "1", AccountAllocationCategory.KIRO),
            tier("official", 2, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
            tier("oauth", 3, "1", AccountAllocationCategory.CLAUDE_OAUTH)));
        bindings.downgrade(BINDING_KEY, new Binding("kiro", 3), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.startLevel().tierId()).isEqualTo("kiro");
        assertThat(plan.startIndex()).isZero();
    }

    @Test
    void theTiersLeftOutForHavingNothingAvailableAreOnlyThoseFromTheBoundTierDown() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        usable.remove(official);
        usable.remove(kiro);
        bindings.downgrade(BINDING_KEY, new Binding("oauth", 2), Binding::level);

        TierPlan plan = plan(request(SESSION));

        // The official tier is above the session's, which it was never going to be served from.
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("oauth");
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("kiro");
    }

    @Test
    void aBoundTierThatIsOutOfServiceItselfIsOneTheSessionIsPushedOutOf() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        usable.remove(oauth);
        bindings.downgrade(BINDING_KEY, new Binding("oauth", 2), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "kiro");
        assertThat(plan.startLevel().tierId()).isEqualTo("kiro");
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("oauth");
        assertThat(plan.unavailableAbove(level(plan, "kiro"))).isTrue();
    }

    @Test
    void theBoundTierIsPlacedByTheOrderOfNowAndNotByTheLevelItWasBoundAt() {
        // Bound as the third tier, which is the second now; the first of the new order is out of service.
        loaded(vendor(true), List.of(
            tier("official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
            tier("kiro", 2, "1", AccountAllocationCategory.KIRO),
            tier("oauth", 3, "1", AccountAllocationCategory.CLAUDE_OAUTH)));
        usable.remove(official);
        usable.remove(oauth);
        bindings.downgrade(BINDING_KEY, new Binding("kiro", 3), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.unavailableTiers()).extracting(Tier::tierId).containsExactly("oauth");
        assertThat(plan.levelOf(new Binding("kiro", 3))).isEqualTo(2);
        assertThat(plan.levelOf(new Binding("gone", 4))).isEqualTo(4);
    }

    @Test
    void aBoundTierThatIsNotInThePlanIsReplacedByTheFirstTierThatIsNotAboveIt() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        usable.remove(oauth);
        bindings.downgrade(BINDING_KEY, new Binding("oauth", 2), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "kiro");
        assertThat(plan.startLevel().tierId()).isEqualTo("kiro");
        // The binding is not rewritten for it: the session comes back to its tier when the tier has an account again.
        assertThat(bindings.find(BINDING_KEY)).contains(new Binding("oauth", 2));
    }

    @Test
    void aBoundTierWithNothingBelowItInThePlanSendsTheRequestToTheBestTier() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        usable.remove(kiro);
        bindings.downgrade(BINDING_KEY, new Binding("kiro", 3), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "oauth");
        assertThat(plan.startIndex()).isZero();
    }

    @Test
    void aBindingToATierThatNoLongerExistsFallsToTheTierAtItsLevelOrBelow() {
        loaded(vendor(true), List.of(
            tier("official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
            tier("kiro", 3, "1", AccountAllocationCategory.KIRO)));
        bindings.downgrade(BINDING_KEY, new Binding("oauth", 2), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.startLevel().tierId()).isEqualTo("kiro");
    }

    // ---- what is reserved ----

    @Test
    void aSessionInACheaperTierDoesNotReserveForTheDearerTiersAbove() {
        // A user with enough for the cheaper tier must not be refused for the dearer one the session is no longer in.
        loaded(vendor(true), threeTiers("1", "0.6", "0.3"));
        bindings.downgrade(BINDING_KEY, new Binding("oauth", 2), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("oauth", "kiro");
        assertThat(plan.reservation()).isEqualTo(level(plan, "oauth").estimate());
        assertThat(plan.reservation()).isLessThan(level(plan, "official").estimate());
    }

    @Test
    void aTierAboveThatReservesNoMoreIsHeldToo() {
        loaded(vendor(true), threeTiers("0.5", "1", "1"));
        bindings.downgrade(BINDING_KEY, new Binding("kiro", 3), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("official", "oauth", "kiro");
        assertThat(plan.reservation()).isEqualTo(level(plan, "oauth").estimate());
    }

    @Test
    void theReservationOfASessionInALowerTierIsTheLargestEstimateOfWhatItHolds() {
        loaded(vendor(true), threeTiers("2", "1", "0.5"));
        bindings.downgrade(BINDING_KEY, new Binding("oauth", 2), Binding::level);

        TierPlan plan = plan(request(SESSION));

        assertThat(plan.reservation()).isEqualTo(level(plan, "oauth").estimate());
    }

    @Test
    void anUnbilledRequestHoldsEveryTierWhateverItsStart() {
        loaded(vendor(true), threeTiers("1", "0.6", "0.3"));
        bindings.downgrade(BINDING_KEY, new Binding("oauth", 2), Binding::level);

        TierPlan plan = plan(new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, false, body(), null, false, "key-1", SESSION));

        assertThat(plan.startIndex()).isEqualTo(1);
        assertThat(plan.holdLevels()).isEqualTo(plan.levels());
        assertThat(plan.reservation()).isNull();
    }

    @Test
    void theOwnerOfAResponseChainStaysCoveredWhereverTheSessionStarts() {
        // The owner is tried first and billed at its tier, so the reservation must cover that tier even when the session
        // is bound to a cheaper one.
        ProxyAccountEntity owner = account("openai-official", "OpenAI", "api-key");
        ProxyAccountEntity codex = account("codex", "OpenAI", "codex");
        cached.addAll(List.of(owner, codex));
        usable.addAll(List.of(owner, codex));
        when(accountPool.cachedAccount("openai-official")).thenReturn(Optional.of(owner));
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 7L)),
            List.of(
                openAiTier("official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                openAiTier("codex", 2, "0.5", AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of()));
        String key = TierSessionKeys.bindingKey("openai", "gpt-5.5", "key-1:resp:resp_1");
        bindings.downgrade(key, new Binding("codex", 2), Binding::level);
        TierPlanRequest chain = new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, false, false, true, body(), "openai-official", true, "key-1", "key-1:resp:resp_1");

        TierPlan plan = plan(chain);

        assertThat(plan.startLevel().tierId()).isEqualTo("codex");
        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("official", "codex");
        assertThat(plan.reservation()).isEqualTo(level(plan, "official").estimate());
    }

    // ---- fixtures ----

    private TierPlan plan(TierPlanRequest request) {
        return planner.plan(request).block();
    }

    private TierPlanRequest request(String sessionName) {
        return requestWith(body(), sessionName);
    }

    private TierPlanRequest requestWith(ObjectNode body, String sessionName) {
        return new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic", TierRoute.ANTHROPIC,
            false, false, false, true, false, true, body, null, false, "key-1", sessionName);
    }

    private ObjectNode body() {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private void loaded(VendorConfig vendor, List<Tier> tiers) {
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L, List.of(vendor), tiers, List.of(), List.of(), List.of()));
    }

    private static Level level(TierPlan plan, String tierId) {
        return plan.levels().stream().filter(level -> level.tierId().equals(tierId)).findFirst().orElseThrow();
    }

    private static VendorConfig vendor(boolean sticky) {
        return new VendorConfig("anthropic", true, false, false, sticky, 16, true, 7L);
    }

    private List<Tier> threeTiers(String officialFactor, String oauthFactor, String kiroFactor) {
        return List.of(
            tier("official", 1, officialFactor, AccountAllocationCategory.OFFICIAL_API_KEY),
            tier("oauth", 2, oauthFactor, AccountAllocationCategory.CLAUDE_OAUTH),
            tier("kiro", 3, kiroFactor, AccountAllocationCategory.KIRO));
    }

    private static Tier tier(String tierId, int level, String factor, AccountAllocationCategory category) {
        return new Tier("anthropic", tierId, level, tierId, new BigDecimal(factor), EnumSet.of(category), true, true, null);
    }

    private static Tier openAiTier(String tierId, int level, String factor, AccountAllocationCategory category) {
        return new Tier("openai", tierId, level, tierId, new BigDecimal(factor), EnumSet.of(category), true, true, null);
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
