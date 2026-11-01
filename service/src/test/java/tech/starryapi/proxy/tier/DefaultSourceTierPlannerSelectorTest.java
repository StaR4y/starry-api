package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.proxy.tier.TierRuntime.Destination;
import tech.starryapi.proxy.tier.TierSelectorParser.Kind;
import tech.starryapi.proxy.tier.TierSelectorParser.Source;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the planner does with the tier a client names (design 6): the suffix is taken off the model only where it is a
 * selector, the tiers the plan holds shrink to the one named (or that one and the ones below), the demands of the suffix
 * that cannot be met are answered before anything is priced, and the hints of the header and the preference that cannot
 * be followed are dropped.
 */
class DefaultSourceTierPlannerSelectorTest {

    private static final AccountAllocationCategory OFFICIAL = AccountAllocationCategory.OFFICIAL_API_KEY;
    private static final AccountAllocationCategory CODEX = AccountAllocationCategory.CODEX;
    private static final AccountAllocationCategory KIRO = AccountAllocationCategory.KIRO;
    private static final AccountAllocationCategory CLAUDE_OAUTH = AccountAllocationCategory.CLAUDE_OAUTH;
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
    private final TierSessionBindings bindings = new TierSessionBindings(new ProxyProperties());
    private final DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(
        registry, keyResolver, catalog, accountPool, payments, pricing, bindings);

    /** Every account the account cache holds (enabled), whatever its state. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** The accounts of the cache that are available (not suspended, not out of quota). */
    private final List<ProxyAccountEntity> usable = new ArrayList<>();
    /** The accounts that are switched off: in no cache, but in the table. */
    private final List<ProxyAccountEntity> switchedOff = new ArrayList<>();

    private final ProxyAccountEntity officialAnthropic = account("anthropic-official", "Anthropic", "api-key");
    private final ProxyAccountEntity claudeOauth = account("claude-oauth", "Anthropic", "oauth");
    private final ProxyAccountEntity kiro = account("kiro", "BuilderId", "idc");
    private final ProxyAccountEntity officialOpenAi = account("openai-official", "OpenAI", "api-key");
    private final ProxyAccountEntity codex = account("codex", "OpenAI", "codex");

    @BeforeEach
    void stubTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(STANDARD));
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenAnswer(invocation -> usable.contains(invocation.<ProxyAccountEntity>getArgument(0)));
        when(accountPool.switchedOffAccounts()).thenAnswer(invocation -> Mono.just(List.copyOf(switchedOff)));
        cached.addAll(List.of(officialAnthropic, claudeOauth, kiro, officialOpenAi, codex));
        usable.addAll(cached);
    }

    // ---- the suffix is taken off a model that has one ----

    @Test
    void anIdWithoutAnAtIsLeftAloneWithoutLookingAtTheConfiguration() {
        TierSelectorParser.Stripped stripped = planner.stripSelector("claude-sonnet-4.5", messagesRoute());

        assertThat(stripped.model()).isEqualTo("claude-sonnet-4.5");
        assertThat(stripped.selector()).isNull();
        verifyNoInteractions(registry);
    }

    @Test
    void aCatalogModelOfAConfiguredVendorLosesItsSuffix() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierSelectorParser.Stripped stripped = planner.stripSelector("claude-sonnet-4.5@kiro", messagesRoute());

        assertThat(stripped.model()).isEqualTo("claude-sonnet-4.5");
        assertThat(stripped.selector().kind()).isEqualTo(Kind.TIER);
        assertThat(stripped.selector().tierId()).isEqualTo("kiro");
        assertThat(stripped.selector().source()).isEqualTo(Source.SUFFIX);
    }

    @Test
    void theDestinationIsAskedOnceAndHandedBackWithTheSelectorSoTheMappingIsNeverDrawnAgain() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        Function<String, Destination> mapping = id -> new Destination("mapped-" + asked.incrementAndGet(), "claude-sonnet-4.5", "Anthropic");

        TierSelectorParser.Stripped stripped = planner.stripSelector("claude-sonnet-4.5@kiro", mapping);

        assertThat(asked).hasValue(1);
        assertThat(stripped.destination().mappedModel()).isEqualTo("mapped-1");
        // An @ that is no selector hands back nothing to go on with: the caller maps the whole id.
        assertThat(planner.stripSelector("my-own-model@nope", mapping).destination()).isNull();
        assertThat(planner.stripSelector("claude-sonnet-4.5", mapping).destination()).isNull();
    }

    @Test
    void aClientAliasIsACatalogModelAndTheModelKeepsItsOwnSpelling() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        assertThat(planner.stripSelector("sonnet@L2", messagesRoute()).model()).isEqualTo("sonnet");
        assertThat(planner.stripSelector("Claude-Opus-4.5@kiro+", messagesRoute()).model()).isEqualTo("Claude-Opus-4.5");
        assertThat(planner.stripSelector("starry/claude-sonnet-4.5@kiro", messagesRoute()).model())
            .isEqualTo("starry/claude-sonnet-4.5");
    }

    @Test
    void aVendorThatIsConfiguredButSwitchedOffStillLosesTheSuffix() {
        loaded(snapshot(List.of(vendor("anthropic", false)), anthropicTiers()));

        TierSelectorParser.Stripped stripped = planner.stripSelector("claude-sonnet-4.5@kiro", messagesRoute());

        assertThat(stripped.model()).isEqualTo("claude-sonnet-4.5");
        assertThat(stripped.selector()).isNotNull();
    }

    @Test
    void aVendorNobodyConfiguredKeepsTheIdAsItIs() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierSelectorParser.Stripped stripped = planner.stripSelector("gpt-5.5@kiro", openAiRoute());

        assertThat(stripped.model()).isEqualTo("gpt-5.5@kiro");
        assertThat(stripped.selector()).isNull();
    }

    @Test
    void anIdThatIsNotACatalogModelLosesItsAtOnlyWhenItNamesATierOfTheVendorThatServesIt() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        // The vendor is the one the destination names, and it has a tier called kiro.
        assertThat(planner.stripSelector("my-own-model@kiro", messagesRoute()).model()).isEqualTo("my-own-model");
        assertThat(planner.stripSelector("my-own-model@kiro", messagesRoute()).selector().tierId()).isEqualTo("kiro");
        // Where the reference is no tier of it, or the vendor is another one, the @ stays in the id.
        assertThat(planner.stripSelector("my-own-model@nope", messagesRoute()).selector()).isNull();
        assertThat(planner.stripSelector("my-own-model@kiro", openAiRoute()).selector()).isNull();
        // Only the last @ is the selector's: what is left of it is the id, a Vertex-style one here, and it stays whole.
        TierSelectorParser.Stripped vertex = planner.stripSelector("claude-sonnet-4@20250514@kiro", messagesRoute());
        assertThat(vertex.model()).isEqualTo("claude-sonnet-4@20250514");
        assertThat(vertex.selector().tierId()).isEqualTo("kiro");
        assertThat(planner.stripSelector("claude-sonnet-4.5@x@kiro", messagesRoute()).model()).isEqualTo("claude-sonnet-4.5@x");
        // There is no model in front of the @ for a tier to serve.
        assertThat(planner.stripSelector("@kiro", messagesRoute()).selector()).isNull();
        assertThat(planner.stripSelector("@kiro", messagesRoute()).model()).isEqualTo("@kiro");
    }

    @Test
    void aVendorWhoseConfigurationHoldsNoTierDoesNotKnowSelectorsEither() {
        loaded(snapshot(List.of(vendor("anthropic", true), vendor("openai", true)), openAiTiers()));

        // The vendor row stays when its tiers are all deleted, and there is nothing a selector could name.
        assertThat(planner.stripSelector("claude-sonnet-4.5@kiro", messagesRoute()).model()).isEqualTo("claude-sonnet-4.5@kiro");
        assertThat(planner.stripSelector("claude-sonnet-4.5@kiro", messagesRoute()).selector()).isNull();
        assertThat(planner.stripSelector("gpt-5.5@codex", openAiRoute()).selector()).isNotNull();
    }

    // ---- the suffix of a model the catalog does not list, and of one the key's mapping sends elsewhere ----

    /** Where the facade sends the id: the Responses route of OpenAI models, the Messages route of Claude models. */
    private static Function<String, Destination> openAiRoute() {
        return id -> new Destination(id, "OpenAI");
    }

    private static Function<String, Destination> messagesRoute() {
        return id -> new Destination(id, "Anthropic");
    }

    @Test
    void aModelTheCatalogDoesNotListLosesTheSuffixWhenItNamesATierOfTheVendorThatServesIt() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        // The Codex-only models are served by Codex accounts all the same, and the price repository lists none of them.
        assertThat(catalog.models()).noneMatch(spec -> spec.id().equalsIgnoreCase("gpt-5.1-codex-max"));

        for (String reference : new String[] {"codex", "codex+", "L2", "2", "auto"}) {
            TierSelectorParser.Stripped stripped = planner.stripSelector("gpt-5.1-codex-max@" + reference, openAiRoute());

            assertThat(stripped.model()).as(reference).isEqualTo("gpt-5.1-codex-max");
            assertThat(stripped.selector()).as(reference).isNotNull();
            assertThat(stripped.selector().source()).isEqualTo(Source.SUFFIX);
        }
    }

    @Test
    void anAtThatNamesNoTierOfThatVendorStaysPartOfTheIdOfAModelTheCatalogDoesNotList() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));

        // Where the catalog has no say, only a tier of the vendor makes an @ a selector: a Vertex-style date or a
        // level the vendor does not have is part of the id.
        for (String reference : new String[] {"nope", "20250514", "L9", "", "a b"}) {
            TierSelectorParser.Stripped stripped = planner.stripSelector("gpt-5.1-codex-max@" + reference, openAiRoute());

            assertThat(stripped.model()).as(reference).isEqualTo("gpt-5.1-codex-max@" + reference);
            assertThat(stripped.selector()).as(reference).isNull();
        }
    }

    @Test
    void aDatedClaudeIdNewerThanTheCatalogLosesTheSuffixOnTheRouteThatServesItByTheVendor() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        assertThat(catalog.models()).noneMatch(spec -> spec.id().equalsIgnoreCase("claude-opus-4-8-20260115")
            || spec.id().equalsIgnoreCase("claude-opus-9-9"));

        // The Messages endpoint serves it on the Anthropic route, so the vendor is anthropic.
        assertThat(planner.stripSelector("claude-opus-4-8-20260115@kiro", messagesRoute()).model())
            .isEqualTo("claude-opus-4-8-20260115");
        assertThat(planner.stripSelector("claude-opus-9-9@20260115", messagesRoute()).selector()).isNull();
        // A caller that names the Kiro route's own provider for it (the vendor "kiro", which nobody configures) leaves the id
        // alone (I4); the facade names the vendor of the accounts that serve a Claude id instead.
        assertThat(planner.stripSelector("claude-opus-4-8-20260115@kiro", id -> new Destination(id, "Kiro")).selector()).isNull();
    }

    @Test
    void aProviderPrefixedSpellingOfACatalogModelLosesTheSuffixToo() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));

        for (String model : new String[] {"openai/gpt-5.5", "openai/gpt-5.4", "starry/gpt-5.5"}) {
            TierSelectorParser.Stripped stripped = planner.stripSelector(model + "@codex", openAiRoute());

            assertThat(stripped.model()).as(model).isEqualTo(model);
            assertThat(stripped.selector()).as(model).isNotNull();
        }
    }

    @Test
    void theVendorThatServesTheModelDecidesAndNotTheVendorItWasAskedAs() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        // The key maps this Claude model to a GPT model, and nobody configured tiers for OpenAI: the model is served as
        // it was before tiers existed, and so is its id.
        Function<String, Destination> mapped = id -> new Destination("gpt-5.4", "OpenAI");

        TierSelectorParser.Stripped stripped = planner.stripSelector("claude-sonnet-4.5@kiro", mapped);

        assertThat(stripped.model()).isEqualTo("claude-sonnet-4.5@kiro");
        assertThat(stripped.selector()).isNull();
        // Without the mapping it is a Claude model of a configured vendor, as ever.
        assertThat(planner.stripSelector("claude-sonnet-4.5@kiro", messagesRoute()).selector()).isNotNull();
    }

    @Test
    void aModelMappedToAConfiguredVendorTakesThatVendorsTiers() {
        loaded(snapshot(List.of(vendor("anthropic", true), vendor("openai", true)),
            List.of(anthropicTiers(), openAiTiers()).stream().flatMap(List::stream).toList()));
        Function<String, Destination> mapped = id -> new Destination("gpt-5.4", "OpenAI");

        // A catalog id is a selector whatever it names; the vendor that serves it then answers for a name it does not have.
        assertThat(planner.stripSelector("claude-sonnet-4.5@codex", mapped).selector().tierId()).isEqualTo("codex");
        assertThat(planner.stripSelector("claude-sonnet-4.5@nope", mapped).selector()).isNotNull();
        // An id the catalog does not list is a selector only for a tier of that vendor: Kiro is not OpenAI's.
        Function<String, Destination> alias = id -> new Destination("gpt-5.4", "OpenAI");
        assertThat(planner.stripSelector("my-alias@codex", alias).model()).isEqualTo("my-alias");
        assertThat(planner.stripSelector("my-alias@kiro", alias).selector()).isNull();
    }

    @Test
    void aReferenceThatIsNotInTheGrammarIsTakenOffAndLeftForTheRequestToBeRefusedFor() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierSelectorParser.Stripped empty = planner.stripSelector("claude-sonnet-4.5@", messagesRoute());
        TierSelectorParser.Stripped odd = planner.stripSelector("claude-sonnet-4.5@ki ro", messagesRoute());

        assertThat(empty.model()).isEqualTo("claude-sonnet-4.5");
        assertThat(empty.selector().kind()).isEqualTo(Kind.INVALID);
        assertThat(odd.model()).isEqualTo("claude-sonnet-4.5");
        assertThat(odd.selector().kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void nothingIsTakenOffBeforeTheConfigurationHasLoaded() {
        when(registry.snapshot()).thenThrow(
            new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TIER_CONFIG_UNAVAILABLE", "not loaded"));

        TierSelectorParser.Stripped stripped = planner.stripSelector("claude-sonnet-4.5@kiro", messagesRoute());

        assertThat(stripped.model()).isEqualTo("claude-sonnet-4.5@kiro");
        assertThat(stripped.selector()).isNull();
    }

    @Test
    void nothingIsTakenOffWhenTheReleaseCannotSelect() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        DefaultSourceTierPlanner without = planner(false);

        assertThat(without.stripSelector("claude-sonnet-4.5@kiro", messagesRoute()).model()).isEqualTo("claude-sonnet-4.5@kiro");
        assertThat(without.stripSelector("claude-sonnet-4.5@kiro", messagesRoute()).selector()).isNull();
    }

    @Test
    void aPlannerThatCannotSelectIgnoresTheSelectorOfAHeaderToo() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan plan = planner(false).plan(request(header("kiro"))).block();

        assertThat(plan.pinned()).isFalse();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "kiro", "oauth");
        assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "served");
    }

    // ---- a tier the client named holds the plan ----

    @Test
    void aSuffixThatNamesATierPinsTheRequestToItAndReservesForItAlone() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan plan = plan(request(suffix("kiro")));

        assertThat(plan.pinned()).isTrue();
        assertThat(plan.mode()).isEqualTo(TierPlan.Mode.PINNED);
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.holdLevels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.startIndex()).isZero();
        assertThat(plan.session()).hasValueSatisfying(session -> assertThat(session.keepsTier()).isFalse());
        Level kiroLevel = plan.levels().get(0);
        BillingQuote kiroPrice = STANDARD.scaled(new BigDecimal("0.6"));
        assertThat(kiroLevel.quote().cost(new tech.starryapi.platform.core.PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0)))
            .isEqualByComparingTo("0.023400");
        assertThat(plan.reservation()).isEqualByComparingTo(kiroPrice.estimate(kiroLevel.inputBound(), plan.outputBound()));
        assertThat(kiroLevel.responseHeaders()).containsEntry(TierResponseHeaders.TIER, "kiro")
            .containsEntry(TierResponseHeaders.LEVEL, "2")
            .containsEntry(TierResponseHeaders.MODE, "pinned")
            .containsEntry(TierResponseHeaders.STATUS, "served");
    }

    @Test
    void thePinnedReservationIsThatOfTheDearerTierWhenTheClientPinsTheDearerOne() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan auto = plan(request(null));
        TierPlan pinned = plan(request(suffix("official")));

        assertThat(pinned.levels()).extracting(Level::tierId).containsExactly("official");
        // A client may name a tier that costs more than another; it is charged and reserved for that tier.
        assertThat(pinned.reservation()).isEqualByComparingTo(auto.levels().get(0).estimate());
        assertThat(plan(request(suffix("kiro"))).reservation()).isLessThan(pinned.reservation());
    }

    @Test
    void aPinnedPlanHoldsTheTierAndThoseBelowItWhenThePlusIsThere() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan official = plan(request(suffix("official+")));
        TierPlan kiroOrBelow = plan(request(suffix("kiro+")));

        assertThat(official.levels()).extracting(Level::tierId).containsExactly("official", "kiro", "oauth");
        assertThat(official.holdLevels()).extracting(Level::tierId).containsExactly("official", "kiro", "oauth");
        assertThat(official.pinned()).isTrue();
        assertThat(kiroOrBelow.levels()).extracting(Level::tierId).containsExactly("kiro", "oauth");
        assertThat(kiroOrBelow.holdLevels()).extracting(Level::tierId).containsExactly("kiro", "oauth");
        assertThat(kiroOrBelow.startIndex()).isZero();
        assertThat(kiroOrBelow.levels()).allSatisfy(level ->
            assertThat(level.responseHeaders()).containsEntry(TierResponseHeaders.MODE, "pinned"));
    }

    @Test
    void aTierIsNamedByItsLevelWithOrWithoutTheL() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        assertThat(plan(request(suffix("L2"))).levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan(request(suffix("2"))).levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan(request(suffix("l1"))).levels()).extracting(Level::tierId).containsExactly("official");
        assertThat(plan(request(suffix("L1+"))).levels()).extracting(Level::tierId).containsExactly("official", "kiro", "oauth");
        assertThat(plan(request(suffix("L2+"))).levels()).extracting(Level::tierId).containsExactly("kiro", "oauth");
    }

    @Test
    void aLevelIsTheLevelTheTierWasGivenAndNotItsPlaceAmongTheTiers() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "1", true, true, OFFICIAL),
            tier("anthropic", "kiro", 4, "0.6", true, true, KIRO)
        )));

        assertThat(plan(request(suffix("L4"))).levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThatThrownBy(() -> plan(request(suffix("L2")))).isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("SOURCE_TIER_UNKNOWN"));
    }

    @Test
    void theNamedTierMustBeSelectableButTheTiersBelowItNeedNotBe() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        // "oauth" is the tier the administrator keeps to itself, so it cannot be named, with a plus or without.
        assertRefused(request(suffix("oauth+")), HttpStatus.FORBIDDEN, "SOURCE_TIER_NOT_SELECTABLE");
        // "kiro or below" may still be served from it.
        assertThat(plan(request(suffix("kiro+"))).levels()).extracting(Level::tierId).containsExactly("kiro", "oauth");
    }

    @Test
    void aPinnedPlanDoesNotReadTheTierTheSessionIsBoundToButStaysPartOfTheSession() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        String session = "key-1:session-1";
        bindings.downgrade(TierSessionKeys.bindingKey("anthropic", "claude-sonnet-4.5", session),
            new TierSessionBindings.Binding("kiro", 2), TierSessionBindings.Binding::level);

        TierPlan auto = plan(request(null).withSessionName(session));
        TierPlan pinned = plan(request(suffix("official")).withSessionName(session));

        assertThat(auto.startLevel().tierId()).isEqualTo("kiro");
        assertThat(pinned.startIndex()).isZero();
        assertThat(pinned.startLevel().tierId()).isEqualTo("official");
        // It is still one of the requests of the session (the plan counts its dispatches and follows its response chain),
        // but it neither reads nor writes the tier the session is bound to.
        assertThat(auto.session()).hasValueSatisfying(own -> assertThat(own.keepsTier()).isTrue());
        assertThat(pinned.session()).hasValueSatisfying(own -> {
            assertThat(own.key()).isEqualTo(session);
            assertThat(own.bindingKey()).isEqualTo(auto.session().orElseThrow().bindingKey());
            assertThat(own.keepsTier()).isFalse();
        });
    }

    @Test
    void theSuffixBeatsTheHeaderAndAnAutoSuffixClearsIt() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan suffixWins = plan(request(TierSelectorParser.choose(suffix("official"), header("kiro"), null)));
        TierPlan autoSuffix = plan(request(TierSelectorParser.choose(suffix("auto"), header("kiro"), null)));

        assertThat(suffixWins.levels()).extracting(Level::tierId).containsExactly("official");
        assertThat(autoSuffix.pinned()).isFalse();
        assertThat(autoSuffix.levels()).extracting(Level::tierId).containsExactly("official", "kiro", "oauth");
        assertThat(autoSuffix.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "served");
    }

    // ---- what the suffix demands and cannot have is refused before anything is priced ----

    @Test
    void aSuffixThatNamesNoTierIsRefused() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        assertRefused(request(suffix("nope")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_UNKNOWN");
        assertRefused(request(suffix("L9")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_UNKNOWN");
        assertRefused(request(suffix("9+")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_UNKNOWN");
        assertRefused(request(suffix("")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_UNKNOWN");
        assertRefused(request(suffix("not a tier")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_UNKNOWN");
    }

    @Test
    void aSuffixThatNamesATierThatIsSwitchedOffIsRefusedAsNotOffered() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "1", true, true, OFFICIAL),
            tier("anthropic", "retired", 2, "0.6", true, false, KIRO)
        )));

        assertRefused(request(suffix("retired")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
        assertRefused(request(suffix("L2")), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
    }

    @Test
    void aSuffixThatNamesATierTheAdministratorKeepsToItselfIsForbidden() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        assertRefused(request(suffix("oauth")), HttpStatus.FORBIDDEN, "SOURCE_TIER_NOT_SELECTABLE");
        assertRefused(request(suffix("L3")), HttpStatus.FORBIDDEN, "SOURCE_TIER_NOT_SELECTABLE");
    }

    @Test
    void theRefusalsCallATierATierAsTheConsoleAndTheDocumentationDo() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "1", true, true, OFFICIAL),
            tier("anthropic", "retired", 2, "0.6", true, false, KIRO),
            tier("anthropic", "oauth", 3, "0.3", false, true, CLAUDE_OAUTH)
        )));

        assertThat(refusalOf(request(suffix("nope")))).isEqualTo("The tier \"nope\" does not exist");
        assertThat(refusalOf(request(suffix("retired")))).isEqualTo("The tier \"retired\" is not offered at the moment");
        assertThat(refusalOf(request(suffix("oauth")))).isEqualTo("The tier \"oauth\" cannot be chosen by clients");
    }

    @Test
    void theRefusalDoesNotEchoMoreOfTheReferenceThanTheClientShouldSee() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        assertThatThrownBy(() -> plan(request(suffix("x".repeat(500)))))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getMessage()).hasSizeLessThan(120));
    }

    @Test
    void aSuffixThatIsNotUsedByTheHeaderOfTheSameRequestDoesNotHideItsFailure() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        // The suffix decides, so a header that would have worked is not tried when the suffix fails.
        assertRefused(request(TierSelectorParser.choose(suffix("nope"), header("kiro"), null)),
            HttpStatus.BAD_REQUEST, "SOURCE_TIER_UNKNOWN");
    }

    // ---- what the header and the preference cannot have is ignored ----

    @Test
    void aHeaderThatNamesAUsableTierPinsTheRequestLikeASuffix() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan plan = plan(request(header("kiro")));

        assertThat(plan.pinned()).isTrue();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "served");
    }

    @Test
    void aHeaderThatCannotBeFollowedServesTheRequestInTheTiersOrderAndSaysItWasIgnored() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "1", true, true, OFFICIAL),
            tier("anthropic", "kiro", 2, "0.6", true, true, KIRO),
            tier("anthropic", "oauth", 3, "0.3", false, true, CLAUDE_OAUTH),
            tier("anthropic", "retired", 4, "0.2", true, false, AccountAllocationCategory.COMPATIBLE_API_KEY)
        )));

        for (String reference : new String[] {"nope", "L9", "oauth", "retired", "not a tier", "+", "oauth+"}) {
            TierPlan plan = plan(request(header(reference)));

            assertThat(plan.pinned()).as(reference).isFalse();
            assertThat(plan.mode()).as(reference).isEqualTo(TierPlan.Mode.AUTO);
            assertThat(plan.levels()).as(reference).extracting(Level::tierId).containsExactly("official", "kiro", "oauth");
            assertThat(plan.levels()).as(reference).allSatisfy(level -> assertThat(level.responseHeaders())
                .containsEntry(TierResponseHeaders.MODE, "auto")
                .containsEntry(TierResponseHeaders.STATUS, "ignored"));
        }
    }

    @Test
    void anAutoHeaderIsHonouredNotIgnored() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan plan = plan(request(header("auto")));

        assertThat(plan.pinned()).isFalse();
        assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "served");
    }

    @Test
    void aPreferenceIsReadLikeAHeader() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        assertThat(plan(request(TierSelectorParser.parse(Source.PREFERENCE, "kiro"))).levels())
            .extracting(Level::tierId).containsExactly("kiro");
        TierPlan ignored = plan(request(TierSelectorParser.parse(Source.PREFERENCE, "oauth")));
        assertThat(ignored.pinned()).isFalse();
        assertThat(ignored.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "ignored");
    }

    @Test
    void aHintForATierTheRouteHasNothingInIsDroppedWhereTheSuffixIsRefused() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        // The Chat endpoint sends a Claude model to the Kiro route; the official tier has no Kiro account. The suffix is
        // the demand of one request and is refused. The header is what a client configuration says for every request it
        // makes, and the preference is what the key says for every client: both give way.
        assertRefused(request(suffix("official"), TierRoute.KIRO), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
        for (TierSelectorParser.Selector hint : List.of(header("official"), TierSelectorParser.parse(Source.PREFERENCE, "official"))) {
            TierPlan plan = plan(request(hint, TierRoute.KIRO));

            assertThat(plan.pinned()).as(hint.source().name()).isFalse();
            assertThat(plan.mode()).isEqualTo(TierPlan.Mode.AUTO);
            assertThat(plan.levels()).extracting(Level::tierId).doesNotContain("official").contains("kiro");
            assertThat(plan.levels()).allSatisfy(level -> assertThat(level.responseHeaders())
                .containsEntry(TierResponseHeaders.STATUS, "ignored"));
        }
    }

    @Test
    void aHeaderThatTheRouteCanFollowIsStillFollowed() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan plan = plan(request(header("kiro"), TierRoute.KIRO));

        assertThat(plan.pinned()).isTrue();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "served");
    }

    @Test
    void aHintThatWouldLeaveTheRangeWithoutAnyAccountIsDroppedToo() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        cached.clear();
        usable.clear();
        cached.add(officialAnthropic);
        usable.add(officialAnthropic);

        // The one account left is an official key, which the Kiro route does not take; "kiro+" has nothing in its range.
        assertRefused(request(suffix("kiro+"), TierRoute.KIRO), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
        for (TierSelectorParser.Selector hint : List.of(header("kiro+"), TierSelectorParser.parse(Source.PREFERENCE, "kiro+"))) {
            TierPlan plan = plan(request(hint, TierRoute.KIRO));

            assertThat(plan.pinned()).as(hint.source().name()).isFalse();
            assertThat(plan.levels()).isNotEmpty().allSatisfy(level -> assertThat(level.responseHeaders())
                .containsEntry(TierResponseHeaders.STATUS, "ignored"));
        }
    }

    @Test
    void aPreferenceThatIsFollowedIsNotDropped() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan plan = plan(request(TierSelectorParser.parse(Source.PREFERENCE, "kiro+")));

        assertThat(plan.pinned()).isTrue();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro", "oauth");
        assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "served");
    }

    @Test
    void aHintThatAChainCannotFollowLetsTheOwnerContinueItWhereTheSuffixIsRefused() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        assertRefused(openAiRequest(suffix("official"), "codex", false), HttpStatus.CONFLICT, "RESPONSE_OWNER_TIER_MISMATCH");
        for (TierSelectorParser.Selector hint : List.of(header("official"), TierSelectorParser.parse(Source.PREFERENCE, "official"))) {
            TierPlan plan = plan(openAiRequest(hint, "codex", false));

            assertThat(plan.pinned()).as(hint.source().name()).isFalse();
            assertThat(plan.owner()).hasValueSatisfying(owner -> {
                assertThat(owner.accountId()).isEqualTo("codex");
                assertThat(owner.level().tierId()).isEqualTo("codex");
            });
            // The chain cannot leave its owner, so the owner's tier is the whole plan.
            assertThat(plan.levels()).extracting(Level::tierId).containsExactly("codex");
            assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "ignored");
        }
    }

    @Test
    void aChainWhoseOwnerNoTierHoldsIsRefusedForAHintAsForAnAutomaticRequest() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("deleted")).thenReturn(Optional.empty());

        for (TierSelectorParser.Selector hint : List.of(header("official"), TierSelectorParser.parse(Source.PREFERENCE, "official"))) {
            assertThatThrownBy(() -> plan(openAiRequest(hint, "deleted", false)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getMessage()).contains("not part of the tiers that serve this request");
                });
        }
    }

    // ---- vendors that serve from no tier ----

    @Test
    void aVendorNobodyConfiguredDoesNotKnowSelectorsAndAnswersNothingAboutThem() {
        loaded(SourceTierSnapshot.EMPTY);

        assertThat(plan(request(header("kiro")))).isSameAs(TierPlan.UNTIERED);
        assertThat(plan(request(TierSelectorParser.parse(Source.PREFERENCE, "kiro")))).isSameAs(TierPlan.UNTIERED);
        verifyNoInteractions(payments, pricing);
    }

    @Test
    void aSuffixThatReachesAVendorNobodyConfiguredWasTakenOffForAnotherVendorAndIsReportedAsIgnored() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        // The model of the request goes to OpenAI (say, by the key's model mapping), which has no tiers; the suffix was
        // taken off the model for the vendor the model was asked for.
        TierPlan plan = plan(openAiRequest(suffix("kiro"), null, false));

        assertThat(plan).isSameAs(TierPlan.UNTIERED_IGNORING_SELECTOR);
        assertThat(plan(openAiRequest(suffix("auto"), null, false))).isSameAs(TierPlan.UNTIERED);
        verifyNoInteractions(payments, pricing);
    }

    @Test
    void aVendorThatIsSwitchedOffDropsTheSelectorAndSaysItDidWhenOneWasGiven() {
        loaded(snapshot(List.of(vendor("anthropic", false)), anthropicTiers()));

        TierPlan named = plan(request(suffix("kiro")));
        TierPlan byHeader = plan(request(header("nope")));
        TierPlan none = plan(request(null));
        TierPlan auto = plan(request(suffix("auto")));

        assertThat(named).isSameAs(TierPlan.UNTIERED_IGNORING_SELECTOR);
        assertThat(named.tiered()).isFalse();
        assertThat(named.selectorIgnored()).isTrue();
        assertThat(byHeader).isSameAs(TierPlan.UNTIERED_IGNORING_SELECTOR);
        assertThat(none).isSameAs(TierPlan.UNTIERED);
        assertThat(none.selectorIgnored()).isFalse();
        assertThat(auto).isSameAs(TierPlan.UNTIERED);
        verifyNoInteractions(payments, pricing);
    }

    @Test
    void aVendorWhoseTiersWereAllDeletedIsAVendorNobodyConfigured() {
        // The vendor row stays when its tiers are deleted, enabled or not.
        for (boolean enabled : new boolean[] {true, false}) {
            loaded(snapshot(List.of(vendor("anthropic", enabled)), List.of()));

            assertThat(plan(request(header("kiro")))).as("header").isSameAs(TierPlan.UNTIERED);
            assertThat(plan(request(TierSelectorParser.parse(Source.PREFERENCE, "kiro")))).as("preference").isSameAs(TierPlan.UNTIERED);
            // A suffix that was taken off while the vendor still had tiers is told about.
            assertThat(plan(request(suffix("kiro")))).as("suffix").isSameAs(TierPlan.UNTIERED_IGNORING_SELECTOR);
        }
        verifyNoInteractions(payments, pricing);
    }

    @Test
    void aVendorWithoutAnEnabledTierIsSwitchedOffAsFarAsSelectorsGo() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "1", true, false, OFFICIAL))));

        assertThat(plan(request(suffix("official")))).isSameAs(TierPlan.UNTIERED_IGNORING_SELECTOR);
    }

    // ---- a tier that has nothing the route can use ----

    @Test
    void aTierWithNoAccountOfTheRoutesKindsIsNotOffered() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        // The Chat endpoint sends a Claude model to the Kiro route; the official tier has no Kiro account.
        assertRefused(request(suffix("official"), TierRoute.KIRO), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
        assertRefused(request(suffix("L1"), TierRoute.KIRO), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
    }

    @Test
    void aTierWhoseAccountsAreAllDisabledIsNotOfferedEither() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        cached.remove(kiro);
        usable.remove(kiro);

        assertRefused(request(suffix("kiro"), TierRoute.KIRO), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
    }

    @Test
    void aTierWhoseAccountsCannotServeRightNowIsStillPlannedForTheSelectionToFind() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        usable.remove(kiro);

        TierPlan plan = plan(request(suffix("kiro")));

        assertThat(plan.pinned()).isTrue();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("kiro");
    }

    @Test
    void aHintForATierWhoseAccountsAreAllSwitchedOffStaysPinnedWhereTheSuffixIsRefused() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        // The health tests suspended the only Kiro account: it is in no cache, and the table has it.
        cached.remove(kiro);
        usable.remove(kiro);
        switchedOff.add(kiro);

        assertRefused(request(suffix("kiro"), TierRoute.KIRO), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
        for (TierSelectorParser.Selector hint : List.of(header("kiro"), TierSelectorParser.parse(Source.PREFERENCE, "kiro"),
            header("L2"))) {
            TierPlan plan = plan(request(hint, TierRoute.KIRO));

            // The pool exists for this route and cannot serve for now: the request is kept to it, so that the selection finds
            // nothing, and the client is told so, instead of being served by another pool at another price.
            assertThat(plan.pinned()).as(hint.text()).isTrue();
            assertThat(plan.levels()).as(hint.text()).extracting(Level::tierId).containsExactly("kiro");
            assertThat(plan.memberless(plan.levels().get(0))).isTrue();
            assertThat(plan.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "served");
            assertThat(plan.reservation()).isEqualByComparingTo(plan.levels().get(0).estimate());
        }
    }

    @Test
    void aSwitchedOffAccountOfAnotherTierOrOfAnotherKindDoesNotKeepAHintThatNamesNothingOfTheRoute() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        // The Kiro route holds no official key, switched off or not, and the suspended Kiro account is in another tier.
        cached.remove(kiro);
        usable.remove(kiro);
        switchedOff.addAll(List.of(kiro, account("suspended-official", "Anthropic", "api-key")));

        TierPlan official = plan(request(header("official"), TierRoute.KIRO));

        assertThat(official.pinned()).isFalse();
        assertThat(official.levels()).extracting(Level::tierId).doesNotContain("official");
        assertThat(official.levels().get(0).responseHeaders()).containsEntry(TierResponseHeaders.STATUS, "ignored");
        // On the Messages route the official key is of the route; the tiers hold nothing of it in the cache, but the
        // suspended key is the pool's, and the pool is kept.
        cached.remove(officialAnthropic);
        usable.remove(officialAnthropic);
        switchedOff.add(officialAnthropic);
        assertThat(plan(request(header("official"))).pinned()).isTrue();
    }

    @Test
    void aTableThatCannotBeReadLeavesAHintToBeDroppedAsItAlwaysWas() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        cached.remove(kiro);
        usable.remove(kiro);
        when(accountPool.switchedOffAccounts()).thenReturn(null);

        assertThat(plan(request(header("kiro"), TierRoute.KIRO)).pinned()).isFalse();
    }

    @Test
    void theTiersBelowAreEnoughWhenTheOneNamedWithAPlusHasNoAccountAtAll() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        cached.remove(kiro);
        usable.remove(kiro);

        TierPlan plan = plan(request(suffix("kiro+")));

        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("oauth");
    }

    @Test
    void thePinnedRequestIsNotOfferedWhenNoTierOfTheRangeHasAnAccount() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        // The one account left is an official key, which the Kiro route does not take.
        cached.clear();
        usable.clear();
        cached.add(officialAnthropic);
        usable.add(officialAnthropic);

        assertRefused(request(suffix("kiro+"), TierRoute.KIRO), HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED");
    }

    @Test
    void theAccountsOfTheTiersLeftOutOnPurposeAreNotRetestedForAPinnedRequest() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlan pinned = plan(request(suffix("official")));
        TierPlan auto = plan(request(null));

        // The Kiro account sits in a tier the client kept out; a request restricted to one tier has no use for its recovery.
        assertThat(pinned.leftOut(kiro)).isFalse();
        assertThat(auto.leftOut(kiro)).isFalse();
        // Nor has a request that named "kiro or below" any for the tier above.
        assertThat(plan(request(suffix("kiro+"))).leftOut(officialAnthropic)).isFalse();
    }

    @Test
    void aTierOfTheNamedRangeThatTheAvailabilityLeftOutIsStillRetested() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));
        // Kiro is out of quota: the plan keeps only the OAuth tier of the range, and the Kiro account is how the tier returns.
        usable.remove(kiro);

        TierPlan pinned = plan(request(suffix("kiro+")));

        assertThat(pinned.levels()).extracting(Level::tierId).containsExactly("oauth");
        assertThat(pinned.leftOut(kiro)).isTrue();
        assertThat(pinned.leftOut(officialAnthropic)).isFalse();
    }

    // ---- a response chain and the tier the client named ----

    @Test
    void aChainThatCannotBeReplayedIsRefusedWhenItsOwnerIsOutsideThePinnedTiers() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        assertThatThrownBy(() -> plan(openAiRequest(suffix("official"), "codex", false)))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("RESPONSE_OWNER_TIER_MISMATCH");
                assertThat(error.getMessage()).contains("not part of the tier you chose");
            });
        verifyNoInteractions(payments, pricing);
    }

    @Test
    void aChainThatCanBeReplayedLeavesItsOwnerWhenTheClientNamedAnotherTier() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        TierPlan plan = plan(openAiRequest(suffix("official"), "codex", true));

        assertThat(plan.owner()).isEmpty();
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official");
    }

    @Test
    void anOwnerInsideThePinnedTiersIsKept() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        TierPlan plan = plan(openAiRequest(suffix("codex"), "codex", false));

        assertThat(plan.owner()).hasValueSatisfying(owner -> {
            assertThat(owner.accountId()).isEqualTo("codex");
            assertThat(owner.level().tierId()).isEqualTo("codex");
        });
        assertThat(plan.pinned()).isTrue();
    }

    @Test
    void aPlusPinnedChainThatCanBeReplayedHasNoUseForAnOwnerBelowTheTierItStartsFrom() {
        // A turn of the chain fell below the named tier (or was pinned there) and left its account as the owner. The "+"
        // range lets the lower tiers serve only when the named one cannot, so the owner is not what the plan is tried
        // against and is not what keeps the chain in that tier: the walk starts from the named tier, as it does without a
        // chain.
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        for (TierSelectorParser.Selector pin : List.of(suffix("official+"), suffix("1+"), header("official+"),
            TierSelectorParser.parse(Source.PREFERENCE, "official+"))) {
            TierPlan plan = plan(openAiRequest(pin, "codex", true));

            assertThat(plan.pinned()).as(pin.source().name()).isTrue();
            assertThat(plan.owner()).as(pin.source().name()).isEmpty();
            assertThat(plan.startLevel().tierId()).isEqualTo("official");
            assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "codex");
        }
    }

    @Test
    void aPlusPinnedChainThatCanBeReplayedTriesAnOwnerInTheTierItStartsFromFirst() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("openai-official")).thenReturn(Optional.of(officialOpenAi));

        TierPlan plan = plan(openAiRequest(suffix("official+"), "openai-official", true));

        assertThat(plan.owner()).hasValueSatisfying(owner -> {
            assertThat(owner.accountId()).isEqualTo("openai-official");
            assertThat(owner.level().tierId()).isEqualTo("official");
        });
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("official", "codex");
    }

    @Test
    void aPlusPinnedChainThatCanBeReplayedKeepsAnOwnerBelowTheNamedTierWhenTheNamedTierHasNothingToOffer() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));
        usable.remove(officialOpenAi);

        TierPlan plan = plan(openAiRequest(suffix("official+"), "codex", true));

        assertThat(plan.startLevel().tierId()).isEqualTo("codex");
        assertThat(plan.owner()).hasValueSatisfying(owner -> assertThat(owner.level().tierId()).isEqualTo("codex"));
    }

    @Test
    void aPlusPinnedChainThatCannotLeaveItsOwnerIsServedByTheOwnersTierAlone() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        TierPlan plan = plan(openAiRequest(suffix("official+"), "codex", false));

        assertThat(plan.owner()).hasValueSatisfying(owner -> assertThat(owner.level().tierId()).isEqualTo("codex"));
        assertThat(plan.levels()).extracting(Level::tierId).containsExactly("codex");
    }

    @Test
    void anOwnerOfAnAutomaticPlanStillAnswersWithTheOldMessage() {
        loaded(snapshot(List.of(vendor("openai", true)), openAiTiers()));
        when(accountPool.cachedAccount("deleted")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> plan(openAiRequest(null, "deleted", false)))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getMessage()).contains("not part of the tiers that serve this request"));
    }

    // ---- what a user who cannot pay is told ----

    @Test
    void anAutomaticPlanSaysWhichTiersACheaperReservationCouldBeHadFrom() {
        loaded(snapshot(List.of(publicVendor("anthropic")), anthropicTiers()));

        String hint = plan(request(null)).lowBalanceHint();

        // The OAuth tier is cheaper still, but the client may not name it.
        assertThat(hint).contains("claude-sonnet-4.5@kiro").contains("系数 0.6").doesNotContain("oauth")
            .contains(TierSelectorParser.REQUEST_HEADER);
        // No tier here holds relays alone, so every tier is reserved for the same output and one that reserves less is a cheaper one:
        // the advice is worded as it was before D15.
        assertThat(hint).startsWith("；可以改选更便宜的号池（").doesNotContain("预留更少");
        // The setting of the key is called what its form, the docs and the list of keys call it.
        assertThat(hint).contains("在 API Key 的设置里选择号源偏好").doesNotContainPattern("选择号源(?!偏好)");
    }

    @Test
    void theAdviceThatCannotWriteTheSuffixStillNamesTheSettingOfTheKeyByItsName() {
        loaded(snapshot(List.of(publicVendor("anthropic")), anthropicTiers()));

        String hint = plan(request(null).withSuffixTakenOff(spelling -> false)).lowBalanceHint();

        assertThat(hint).contains(TierSelectorParser.REQUEST_HEADER).contains("或在 API Key 的设置里选择号源偏好")
            .doesNotContain("后缀").doesNotContain("claude-sonnet-4.5@");
        assertThat(hint).doesNotContainPattern("选择号源(?!偏好)");
    }

    @Test
    void aPlanThatStartsLowerOnlyNamesTheTiersCheaperThanWhatItReserves() {
        loaded(snapshot(List.of(publicVendor("anthropic")), List.of(
            tier("anthropic", "official", 1, "1", true, true, OFFICIAL),
            tier("anthropic", "kiro", 2, "0.6", true, true, KIRO),
            tier("anthropic", "oauth", 3, "0.3", true, true, CLAUDE_OAUTH)
        )));
        String session = "key-1:session-1";
        bindings.downgrade(TierSessionKeys.bindingKey("anthropic", "claude-sonnet-4.5", session),
            new TierSessionBindings.Binding("kiro", 2), TierSessionBindings.Binding::level);

        String hint = plan(request(null).withSessionName(session)).lowBalanceHint();

        assertThat(hint).contains("claude-sonnet-4.5@oauth").doesNotContain("@kiro").doesNotContain("@official");
    }

    @Test
    void aVendorThatKeepsItsPoolNamesToItselfGetsAnAdviceThatNamesNone() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        String hint = plan(request(null)).lowBalanceHint();

        // The key form offers no choice of tiers either, so there is nothing to point at, and the names stay private.
        assertThat(hint).isNotNull().doesNotContain("@").doesNotContain("kiro").doesNotContain("official")
            .doesNotContain("系数").doesNotContain(TierSelectorParser.REQUEST_HEADER).doesNotContain("API Key");
        // Without a tier of relays alone the dearest tier is the one reserved for, as it always was said.
        assertThat(hint).isEqualTo("；预估费用按可能服务本次请求的最贵号池计算，实际按服务它的号池计价");
    }

    @Test
    void theAdviceWritesTheSuffixOnTheModelTheRequestWroteWhenTheKeyMapsIt() {
        loaded(snapshot(List.of(publicVendor("anthropic")), anthropicTiers()));
        // "my-sonnet" is the key's own name for a Claude model. The suffix is taken off it (see the facade tests), so it
        // is the spelling to advise, and not the catalog id it stands for.
        TierPlanRequest alias = new TierPlanRequest("request-1", "my-sonnet", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, body("claude-sonnet-4.5"), null, false, "key-1", null, null);

        assertThat(plan(alias).lowBalanceHint()).contains("my-sonnet@kiro").doesNotContain("claude-sonnet-4.5@");
    }

    @Test
    void aPlannerThatCannotSelectGivesNoAdviceToSelect() {
        loaded(snapshot(List.of(publicVendor("anthropic")), anthropicTiers()));

        assertThat(planner(false).plan(request(null)).block().lowBalanceHint()).isNull();
    }

    @Test
    void thereIsNothingToAddWhenNoSelectableTierReservesLess() {
        loaded(snapshot(List.of(vendor("anthropic", true)), List.of(
            tier("anthropic", "official", 1, "1", true, true, OFFICIAL),
            tier("anthropic", "oauth", 2, "0.3", false, true, CLAUDE_OAUTH))));

        assertThat(plan(request(null)).lowBalanceHint()).isNull();
    }

    @Test
    void aRequestThatNamedItsTierHasNoSuchAdvice() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        assertThat(plan(request(suffix("official"))).lowBalanceHint()).isNull();
        assertThat(plan(request(suffix("official+"))).lowBalanceHint()).isNull();
    }

    @Test
    void aRequestThatIsNotBilledHasNoSuchAdvice() {
        loaded(snapshot(List.of(vendor("anthropic", true)), anthropicTiers()));

        TierPlanRequest anonymous = new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, false, body("claude-sonnet-4.5"), null, false, null, null);

        assertThat(plan(anonymous).lowBalanceHint()).isNull();
    }

    @Test
    void aKeyThatCannotHoldAPreferenceIsNotPointedAtItsSettings() {
        loaded(snapshot(List.of(publicVendor("anthropic")), anthropicTiers()));

        String tied = plan(request(null)).lowBalanceHint();
        String old = plan(request(null).withKeyHoldsPreference(false)).lowBalanceHint();

        assertThat(tied).contains("claude-sonnet-4.5@kiro").contains(TierSelectorParser.REQUEST_HEADER).contains("API Key");
        // The suffix and the header work for it all the same.
        assertThat(old).contains("claude-sonnet-4.5@kiro").contains("系数 0.6").contains(TierSelectorParser.REQUEST_HEADER)
            .doesNotContain("API Key").doesNotContain("设置里");
    }

    @Test
    void aChainThatCannotLeaveItsOwnerIsNotAdvisedToMoveToACheaperPool() {
        loaded(snapshot(List.of(publicVendor("openai")), openAiTiers()));
        when(accountPool.cachedAccount("openai-official")).thenReturn(Optional.of(officialOpenAi));
        when(accountPool.cachedAccount("codex")).thenReturn(Optional.of(codex));

        // Only the owner's pool can continue the chain: a cheaper pool is no way out of a balance that is too low.
        assertThat(plan(openAiRequest(null, "openai-official", false)).lowBalanceHint()).isNull();
        assertThat(plan(openAiRequest(null, "codex", false)).lowBalanceHint()).isNull();
        assertThat(plan(openAiRequest(null, "openai-official", true)).lowBalanceHint()).contains("gpt-5.5@codex");
        assertThat(plan(openAiRequest(null, null, false)).lowBalanceHint()).contains("gpt-5.5@codex");
    }

    // ---- fixtures ----

    private String refusalOf(TierPlanRequest request) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(ApiException.class, () -> plan(request)).getMessage();
    }

    private void assertRefused(TierPlanRequest request, HttpStatus status, String code) {
        assertThatThrownBy(() -> plan(request))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(status);
                assertThat(error.getCode()).isEqualTo(code);
            });
        // Nothing is priced for a request that is refused.
        verifyNoInteractions(payments, pricing);
    }

    private TierPlan plan(TierPlanRequest request) {
        return planner.plan(request).block();
    }

    private DefaultSourceTierPlanner planner(boolean selectors) {
        return new DefaultSourceTierPlanner(registry, keyResolver, catalog, accountPool, payments, pricing, bindings, selectors);
    }

    private void loaded(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    private static TierSelectorParser.Selector suffix(String reference) {
        return TierSelectorParser.parse(Source.SUFFIX, reference);
    }

    private static TierSelectorParser.Selector header(String value) {
        return TierSelectorParser.fromHeader(value);
    }

    private TierPlanRequest request(TierSelectorParser.Selector selector) {
        return request(selector, TierRoute.ANTHROPIC);
    }

    private TierPlanRequest request(TierSelectorParser.Selector selector, TierRoute route) {
        return new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic", route, false, false,
            false, true, false, true, body("claude-sonnet-4.5"), null, false, "key-1", null, selector);
    }

    private TierPlanRequest openAiRequest(TierSelectorParser.Selector selector, String owner, boolean movable) {
        // Codex tokens and official keys are one pool here, as when the administrator switched the isolation off.
        return new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI, false, false, false,
            false, false, true, body("gpt-5.5"), owner, movable, "key-1", null, selector);
    }

    private ObjectNode body(String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private List<Tier> anthropicTiers() {
        return List.of(
            tier("anthropic", "official", 1, "1", true, true, OFFICIAL),
            tier("anthropic", "kiro", 2, "0.6", true, true, KIRO),
            tier("anthropic", "oauth", 3, "0.3", false, true, CLAUDE_OAUTH)
        );
    }

    private List<Tier> openAiTiers() {
        return List.of(
            tier("openai", "official", 1, "1", true, true, OFFICIAL),
            tier("openai", "codex", 2, "0.5", true, true, CODEX)
        );
    }

    private static SourceTierSnapshot snapshot(List<VendorConfig> vendors, List<Tier> tiers) {
        return SourceTierSnapshot.of(7L, vendors, tiers, List.<Member>of(), List.of(), List.of());
    }

    private static VendorConfig vendor(String vendor, boolean enabled) {
        return new VendorConfig(vendor, enabled, false, false, true, 16, true, 7L);
    }

    /** A vendor that lists its pool names to every user. */
    private static VendorConfig publicVendor(String vendor) {
        return new VendorConfig(vendor, true, false, true, true, 16, true, 7L);
    }

    private static Tier tier(String vendor, String tierId, int level, String factor, boolean selectable, boolean enabled,
        AccountAllocationCategory category) {
        return new Tier(vendor, tierId, level, tierId, new BigDecimal(factor), EnumSet.of(category), selectable, enabled, null);
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
