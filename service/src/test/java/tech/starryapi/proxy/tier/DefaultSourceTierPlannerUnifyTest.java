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
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.proxy.tier.TierSelectorParser.Source;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the all-endpoints switch of the anthropic vendor makes of a request (design 4.9): a Claude model asked through
 * Chat Completions or Responses, which the facade routes to Kiro alone, is planned over the Anthropic keys and Kiro, as
 * through the Messages endpoint. Everything else is planned as it was: the switch is the one thing that may send a
 * request to accounts its route does not use, so each of its conditions is pinned here from both sides.
 */
class DefaultSourceTierPlannerUnifyTest {

    private static final AccountAllocationCategory OFFICIAL = AccountAllocationCategory.OFFICIAL_API_KEY;
    private static final AccountAllocationCategory KIRO = AccountAllocationCategory.KIRO;
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

    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    private final ProxyAccountEntity officialAnthropic = account("anthropic-official", "Anthropic", "api-key");
    private final ProxyAccountEntity kiro = account("kiro", "BuilderId", "idc");

    @BeforeEach
    void stubTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(STANDARD));
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenReturn(true);
        cached.addAll(List.of(officialAnthropic, kiro));
        loaded(true, true);
    }

    // ---- the request the switch moves ----

    @Test
    void aClaudeModelAskedThroughChatIsPlannedOnTheAnthropicRouteWhenTheSwitchIsOn() {
        TierPlan plan = plan(request("claude-sonnet-4.5", TierEntry.CHAT));

        assertThat(plan.tiered()).isTrue();
        assertThat(plan.unified()).isTrue();
        assertThat(plan.levels()).extracting(Level::tierRef).containsExactly("anthropic:official", "anthropic:kiro");
        assertThat(plan.levels()).allSatisfy(level ->
            assertThat(level.cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE));
        // The request is described to the account pool as the Anthropic request it now is.
        assertThat(plan.poolContext().providerHint()).isEqualTo("Anthropic");
        assertThat(plan.poolContext().circuitEpoch()).isNull();
    }

    @Test
    void theSameGoesForResponses() {
        TierPlan plan = plan(request("claude-sonnet-4.5", TierEntry.RESPONSES));

        assertThat(plan.unified()).isTrue();
        assertThat(plan.levels()).allSatisfy(level ->
            assertThat(level.cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE));
    }

    @Test
    void aClaudeAliasOfTheCatalogIsAClaudeModelToo() {
        assertThat(plan(request("sonnet", TierEntry.CHAT)).unified()).isTrue();
        assertThat(plan(request("claude-opus-4.5", TierEntry.CHAT)).unified()).isTrue();
        assertThat(plan(request("claude-3.5-haiku", TierEntry.RESPONSES)).unified()).isTrue();
    }

    @Test
    void aTiersPriceIsTheSameWhateverTheRouteIs() {
        TierPlan unified = plan(request("claude-sonnet-4.5", TierEntry.CHAT));
        loaded(false, true);
        // The Kiro route has no account of the official pool, so only the Kiro pool is left in its plan.
        TierPlan plain = plan(request("claude-sonnet-4.5", TierEntry.CHAT));

        assertThat(plain.levels()).extracting(Level::tierId).containsExactly("kiro");
        assertThat(unified.levels()).extracting(Level::tierId).containsExactly("official", "kiro");
        assertThat(unified.levels().get(0).quote()).isSameAs(STANDARD);
        assertThat(unified.levels().get(1).quote()).isEqualTo(plain.levels().get(0).quote());
        assertThat(unified.levels().get(1).quote()).isEqualTo(STANDARD.scaled(new BigDecimal("0.6")));
    }

    @Test
    void theReservationIsSizedForWhatTheOfficialKeyIsSentNotForWhatKiroSees() {
        // An inline picture is dropped on the way to Kiro and is sent whole to an Anthropic key.
        ObjectNode withImage = bodyWithInlineImage("claude-sonnet-4.5", 200_000);
        TierPlanRequest request = request("claude-sonnet-4.5", TierEntry.CHAT, withImage);

        TierPlan unified = plan(request);
        loaded(false, true);
        TierPlan plain = plan(request);

        assertThat(plain.levels().get(0).inputBound()).isEqualTo(RequestBounds.kiroInputTokens(withImage));
        assertThat(unified.levels().get(0).inputBound()).isEqualTo(RequestBounds.nativeInputTokens(withImage));
        assertThat(unified.levels().get(0).inputBound()).isGreaterThan(plain.levels().get(0).inputBound());
        assertThat(unified.reservation()).isGreaterThan(plain.reservation());
    }

    @Test
    void anOfficialPoolCanBeChosenOnChatOnlyWhenTheSwitchIsOn() {
        TierPlan pinned = plan(request("claude-sonnet-4.5", TierEntry.CHAT, TierSelectorParser.parse(Source.SUFFIX, "official")));

        assertThat(pinned.unified()).isTrue();
        assertThat(pinned.pinned()).isTrue();
        assertThat(pinned.levels()).extracting(Level::tierId).containsExactly("official");

        // Without the switch the Chat route has no account of that pool: there is nothing to wait for.
        loaded(false, true);
        assertThatThrownBy(() -> plan(request("claude-sonnet-4.5", TierEntry.CHAT, TierSelectorParser.parse(Source.SUFFIX, "official"))))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED");
            });
    }

    // ---- the requests it leaves alone ----

    @Test
    void withTheSwitchOffTheRouteIsKirosAlone() {
        loaded(false, true);

        TierPlan plan = plan(request("claude-sonnet-4.5", TierEntry.CHAT));

        assertThat(plan.tiered()).isTrue();
        assertThat(plan.unified()).isFalse();
        assertThat(plan.levels()).allSatisfy(level -> assertThat(level.cells()).containsExactly(PoolCell.KIRO_COMPATIBLE));
        assertThat(plan.poolContext().providerHint()).isEqualTo("Kiro");
    }

    @Test
    void aReleaseThatCannotRunTheSwitchDoesNotHonourOne() {
        DefaultSourceTierPlanner cannot = new DefaultSourceTierPlanner(
            registry, keyResolver, catalog, accountPool, payments, pricing, TierSessionBindings.disabled(), true, false);

        TierPlan plan = cannot.plan(request("claude-sonnet-4.5", TierEntry.CHAT)).block();

        assertThat(plan.unified()).isFalse();
        assertThat(plan.levels()).allSatisfy(level -> assertThat(level.cells()).containsExactly(PoolCell.KIRO_COMPATIBLE));
    }

    @Test
    void aVendorThatIsSwitchedOffIsUntieredWhateverItsSwitchSays() {
        loaded(true, false);

        assertThat(plan(request("claude-sonnet-4.5", TierEntry.CHAT))).isSameAs(TierPlan.UNTIERED);
    }

    @Test
    void onlyChatAndResponsesAreMoved() {
        // The Gemini endpoint keeps the route it has, and a caller that does not say where the request came in is not moved.
        assertThat(plan(request("claude-sonnet-4.5", TierEntry.GEMINI)).unified()).isFalse();
        assertThat(plan(request("claude-sonnet-4.5", null)).unified()).isFalse();
        assertThat(plan(request("claude-sonnet-4.5", TierEntry.GEMINI)).levels())
            .allSatisfy(level -> assertThat(level.cells()).containsExactly(PoolCell.KIRO_COMPATIBLE));
    }

    @Test
    void aRequestThatIsNotOnTheKiroRouteIsNotMoved() {
        // The Messages endpoint and a model a prefix placed on the Anthropic route are there already.
        TierPlan messages = plan(request("claude-sonnet-4.5", TierEntry.MESSAGES, TierRoute.ANTHROPIC));
        assertThat(messages.unified()).isFalse();
        assertThat(messages.levels()).allSatisfy(level ->
            assertThat(level.cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE));
        TierPlan prefixed = plan(request("claude-sonnet-4.5", TierEntry.CHAT, TierRoute.ANTHROPIC));
        assertThat(prefixed.unified()).isFalse();
        // The route of another vendor's models is not the switch's to change either.
        assertThat(plan(request("claude-sonnet-4.5", TierEntry.CHAT, TierRoute.OPENAI)).unified()).isFalse();
    }

    @Test
    void aGeminiIdIsNotAClaudeModelEvenThoughTheKiroRouteServesItWithOne() {
        // Google's alias to Claude: its vendor is google, and nobody configured that vendor here.
        assertThat(plan(request("gemini-2.5-pro", "Google", TierEntry.CHAT))).isSameAs(TierPlan.UNTIERED);

        // And a google vendor that has the switch on (the validator never stores one) is not moved by it.
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("google", true, true, false, true, 16, true, 7L)),
            List.of(tier("google", "official", 1, "1", OFFICIAL), tier("google", "kiro", 2, "0.6", KIRO)),
            List.<Member>of(), List.of(), List.of()));
        TierPlan google = plan(request("gemini-2.5-pro", "Google", TierEntry.CHAT));
        assertThat(google.tiered()).isTrue();
        assertThat(google.unified()).isFalse();
        assertThat(google.levels()).allSatisfy(level -> assertThat(level.cells()).containsExactly(PoolCell.KIRO_COMPATIBLE));
    }

    @Test
    void aRoutingPresetIsNotAClaudeModel() {
        // auto and simple-task belong to the anthropic family of the catalog and name no model an Anthropic key serves.
        for (String preset : List.of("auto", "simple-task")) {
            TierPlan plan = plan(request(preset, TierEntry.CHAT));

            assertThat(plan.tiered()).as(preset).isTrue();
            assertThat(plan.unified()).as(preset).isFalse();
            assertThat(plan.levels()).as(preset)
                .allSatisfy(level -> assertThat(level.cells()).containsExactly(PoolCell.KIRO_COMPATIBLE));
        }
    }

    @Test
    void aModelTheCatalogDoesNotKnowIsNotMovedEvenWhenTheHintSaysAnthropic() {
        // The hint of the request makes the vendor anthropic; the model is still not one the catalog lists.
        TierPlan plan = plan(request("claude-not-in-the-catalog", "Anthropic", TierEntry.CHAT));

        assertThat(plan.tiered()).isTrue();
        assertThat(plan.unified()).isFalse();
        assertThat(plan.levels()).allSatisfy(level -> assertThat(level.cells()).containsExactly(PoolCell.KIRO_COMPATIBLE));
    }

    @Test
    void anInternalIdOfCodeWhispererIsNotAClaudeModel() {
        assertThat(plan(request("CLAUDE_SONNET_4_20250514_V1_0", "Kiro", TierEntry.CHAT)).unified()).isFalse();
    }

    @Test
    void aVendorNobodyConfiguredIsNotLookedAt() {
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.EMPTY);

        assertThat(plan(request("claude-sonnet-4.5", TierEntry.CHAT))).isSameAs(TierPlan.UNTIERED);

        verifyNoInteractions(payments, pricing, accountPool);
    }

    // ---- fixtures ----

    private TierPlan plan(TierPlanRequest request) {
        return planner.plan(request).block();
    }

    /** The anthropic vendor with two tiers, switched on or off, with the all-endpoints switch on or off. */
    private void loaded(boolean unify, boolean enabled) {
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("anthropic", enabled, unify, false, true, 16, true, 7L)),
            List.of(tier("anthropic", "official", 1, "1", OFFICIAL), tier("anthropic", "kiro", 2, "0.6", KIRO)),
            List.<Member>of(), List.of(), List.of()));
    }

    private TierPlanRequest request(String model, TierEntry entry) {
        return request(model, "Kiro", entry);
    }

    private TierPlanRequest request(String model, String providerHint, TierEntry entry) {
        return new TierPlanRequest("request-1", model, model, providerHint, TierRoute.KIRO, false, false, false, true, false,
            true, body(model), null, false, "key-1", null).withEntry(entry);
    }

    private TierPlanRequest request(String model, TierEntry entry, TierRoute route) {
        return new TierPlanRequest("request-1", model, model, "Kiro", route, false, false, false, true, false, true,
            body(model), null, false, "key-1", null).withEntry(entry);
    }

    private TierPlanRequest request(String model, TierEntry entry, ObjectNode body) {
        return new TierPlanRequest("request-1", model, model, "Kiro", TierRoute.KIRO, false, false, false, true, false, true,
            body, null, false, "key-1", null).withEntry(entry);
    }

    private TierPlanRequest request(String model, TierEntry entry, TierSelectorParser.Selector selector) {
        return request(model, entry).withSelector(selector);
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

    private static Tier tier(String vendor, String tierId, int level, String factor, AccountAllocationCategory category) {
        return new Tier(vendor, tierId, level, tierId, new BigDecimal(factor), EnumSet.of(category), true, true, null);
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
