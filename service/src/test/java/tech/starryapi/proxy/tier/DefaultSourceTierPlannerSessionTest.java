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
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
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
    void aPlanForATierTheClientNamedIsPartOfTheSessionWithoutKeepingItsTier() {
        loaded(vendor(true), threeTiers("1", "1", "1"));
        bindings.downgrade(BINDING_KEY, new Binding("kiro", 3), Binding::level);

        TierPlan auto = plan(request(SESSION));
        TierPlan pinned = plan(request(SESSION).withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "official")));

        // The automatic request starts where the session fell to; the pinned one is counted as one of the session's
        // requests (it is named, and keyed, like them) but does not read the tier the session is bound to.
        assertThat(auto.startLevel().tierId()).isEqualTo("kiro");
        assertThat(auto.session()).hasValueSatisfying(session -> assertThat(session.keepsTier()).isTrue());
        assertThat(pinned.pinned()).isTrue();
        assertThat(pinned.startLevel().tierId()).isEqualTo("official");
        assertThat(pinned.session()).hasValueSatisfying(session -> {
            assertThat(session.key()).isEqualTo(SESSION);
            assertThat(session.bindingKey()).isEqualTo(BINDING_KEY);
            assertThat(session.keepsTier()).isFalse();
        });
    }

    @Test
    void aPlanForATierTheClientNamedHasNoSessionWhereSessionsAreNotKept() {
        loaded(vendor(false), threeTiers("1", "1", "1"));

        TierPlan pinned = plan(request(SESSION).withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "official")));

        assertThat(pinned.pinned()).isTrue();
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
    void aChainThatCanBeReplayedHasNoUseForAnOwnerAboveTheTierItsSessionIsBoundTo() {
        // The session fell to Codex and a turn of the chain was pinned to the official key, which owns the chain from then
        // on. The turns after it are automatic again, and the session stays where it is (design 4.7): the owner is not
        // tried first, the walk starts at Codex, and the reservation is not sized by the official tier.
        openAiPlan(true);

        TierPlan plan = plan(responsesChain("openai-official", true));

        assertThat(plan.owner()).isEmpty();
        assertThat(plan.startLevel().tierId()).isEqualTo("codex");
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "codex");
        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("codex");
        assertThat(plan.reservation()).isEqualTo(level(plan, "codex").estimate());
        assertThat(plan.reservation()).isLessThan(level(plan, "official").estimate());
    }

    @Test
    void anOwnerInTheTierTheSessionIsBoundToOrBelowItIsStillTriedFirst() {
        openAiPlan(true);

        TierPlan plan = plan(responsesChain("codex", true));

        assertThat(plan.owner()).hasValueSatisfying(owner -> {
            assertThat(owner.accountId()).isEqualTo("codex");
            assertThat(owner.level().tierId()).isEqualTo("codex");
        });
        assertThat(plan.startLevel().tierId()).isEqualTo("codex");
        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("codex");
    }

    @Test
    void aSessionThatIsNotBoundKeepsTheOwnerOfItsChainInTheBestTier() {
        openAiPlan(false);

        TierPlan plan = plan(responsesChain("openai-official", true));

        assertThat(plan.owner()).hasValueSatisfying(owner -> assertThat(owner.level().tierId()).isEqualTo("official"));
        assertThat(plan.startIndex()).isZero();
        assertThat(plan.holdLevels()).isEqualTo(plan.levels());
    }

    @Test
    void aSessionThatIsNotBoundStartsFromTheBestTierWhateverTierTheOwnerOfItsChainIsIn() {
        // A pinned turn left the chain with a Codex owner while the session was never pushed down, or the binding of a
        // session that was has expired (D4). The turns after it are automatic and the best tier has an account, so they are
        // served by it and priced by it (design 4.7).
        openAiPlan(false);

        TierPlan plan = plan(responsesChain("codex", true));

        assertThat(plan.owner()).isEmpty();
        assertThat(plan.startIndex()).isZero();
        assertThat(plan.startLevel().tierId()).isEqualTo("official");
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "codex");
        assertThat(plan.holdLevels()).isEqualTo(plan.levels());
    }

    @Test
    void aChainThatCanBeReplayedHasNoUseForAnOwnerBelowTheTierItsSessionIsBoundTo() {
        // The session is in Codex and a turn of the chain was pinned to a tier below it. That owner is the pinned turn's
        // doing as much as one above would be: the turns after it start in Codex and are priced by Codex.
        openAiPlanWithSpareTier("codex");

        TierPlan plan = plan(responsesChain("openai-spare", true));

        assertThat(plan.owner()).isEmpty();
        assertThat(plan.startLevel().tierId()).isEqualTo("codex");
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "codex", "spare");
        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("codex", "spare");
    }

    @Test
    void anOwnerInTheTierTheWalkMovesToBecauseTheBoundTierHasNothingToOfferIsStillTriedFirst() {
        // The tier the session is bound to has no account to serve with right now, so the walk starts in the next tier
        // that is not above it, and an owner sitting there is where the walk begins.
        openAiPlanWithSpareTier("codex");
        usable.removeIf(account -> account.getAccountId().equals("codex"));

        TierPlan plan = plan(responsesChain("openai-spare", true));

        assertThat(plan.startLevel().tierId()).isEqualTo("spare");
        assertThat(plan.owner()).hasValueSatisfying(owner -> assertThat(owner.level().tierId()).isEqualTo("spare"));
    }

    @Test
    void aVendorThatDoesNotKeepSessionsInTheirTierStartsEveryTurnOfAChainFromTheBestTier() {
        openAiPlan(false, false);

        TierPlan plan = plan(responsesChain("codex", true));

        assertThat(plan.session()).isEmpty();
        assertThat(plan.owner()).isEmpty();
        assertThat(plan.startLevel().tierId()).isEqualTo("official");
    }

    @Test
    void aChainThatCannotLeaveItsOwnerIsServedByTheOwnersTierWhereverTheSessionIsBound() {
        // Only the owner can continue the chain, so it is the one tier of the plan and the session's binding has no say.
        openAiPlan(true);

        TierPlan plan = plan(responsesChain("openai-official", false));

        assertThat(plan.owner()).hasValueSatisfying(owner -> assertThat(owner.level().tierId()).isEqualTo("official"));
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official");
        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("official");
        assertThat(plan.startIndex()).isZero();
    }

    // ---- fixtures ----

    /** Official OpenAI key and Codex account, the tiers of both, and (when {@code bound}) the chain's session bound to Codex. */
    private void openAiPlan(boolean bound) {
        openAiPlan(bound, true);
    }

    private void openAiPlan(boolean bound, boolean sticky) {
        ProxyAccountEntity owner = account("openai-official", "OpenAI", "api-key");
        ProxyAccountEntity codex = account("codex", "OpenAI", "codex");
        cached.addAll(List.of(owner, codex));
        usable.addAll(List.of(owner, codex));
        when(accountPool.cachedAccount("openai-official")).thenReturn(Optional.of(owner));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("openai", true, false, false, sticky, 16, true, 7L)),
            List.of(
                openAiTier("official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                openAiTier("codex", 2, "0.5", AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of()));
        if (bound) {
            bindings.downgrade(TierSessionKeys.bindingKey("openai", "gpt-5.5", "key-1:resp:resp_1"), new Binding("codex", 2),
                Binding::level);
        }
    }

    /**
     * The tiers of {@link #openAiPlan} and a third below them that an official key has been placed in by hand
     * ({@code openai-spare}), with the chain's session bound to {@code boundTier}.
     */
    private void openAiPlanWithSpareTier(String boundTier) {
        ProxyAccountEntity official = account("openai-official", "OpenAI", "api-key");
        ProxyAccountEntity codex = account("codex", "OpenAI", "codex");
        ProxyAccountEntity spare = account("openai-spare", "OpenAI", "api-key");
        cached.addAll(List.of(official, codex, spare));
        usable.addAll(List.of(official, codex, spare));
        for (ProxyAccountEntity account : List.of(official, codex, spare)) {
            when(accountPool.cachedAccount(account.getAccountId())).thenReturn(Optional.of(account));
        }
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 7L)),
            List.of(
                openAiTier("official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                openAiTier("codex", 2, "0.5", AccountAllocationCategory.CODEX),
                new Tier("openai", "spare", 3, "spare", new BigDecimal("0.25"), EnumSet.noneOf(AccountAllocationCategory.class),
                    true, true, null)),
            List.of(new Member("openai", "openai-spare", "spare", null, true, false, false, false, false, true, "NONE", 540, null, null)),
            List.of(), List.of()));
        bindings.downgrade(TierSessionKeys.bindingKey("openai", "gpt-5.5", "key-1:resp:resp_1"),
            new Binding(boundTier, "codex".equals(boundTier) ? 2 : 3), Binding::level);
    }

    /** The turn of a Responses chain whose last response was served by {@code ownerAccountId}. */
    private TierPlanRequest responsesChain(String ownerAccountId, boolean movable) {
        return new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, false, false, true, body(), ownerAccountId, movable, "key-1", "key-1:resp:resp_1");
    }

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
