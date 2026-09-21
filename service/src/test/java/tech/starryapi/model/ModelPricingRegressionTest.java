package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Sinks;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.platform.core.PlatformUsage;

class ModelPricingRegressionTest {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final SystemSettingRepository repository = ModelPricingServiceTestSupport.repository();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final OfficialModelPricingService official = new OfficialModelPricingService(
        WebClient.builder().build(), mapper, new OfficialModelPricingProperties());
    private final ModelPricingService service = new ModelPricingService(repository, mapper, official, catalog);
    private final PlatformUsage millionEach = new PlatformUsage(1_000_000, 1_000_000, BigDecimal.ZERO, 0, 0, 0);

    @Test
    void exactRuleCannotBeBypassedWithPrefixesOrFastSuffix() {
        save(ratio("exact", "gpt-5.5", "2", 100));
        for (String model : List.of("gpt-5.5", "starry/gpt-5.5", "OPENAI/GPT-5.5", "starry/openai/gpt-5.5-fast")) {
            assertThat(service.cost(model, millionEach, false, BigDecimal.ONE).block()).as(model).isEqualByComparingTo("70");
            assertThat(service.billingQuote(model, false, BigDecimal.ONE).block().cost(millionEach))
                .as(model).isEqualByComparingTo("70");
        }
        assertThat(service.billingQuote("gpt-5.5-fast", true, BigDecimal.ONE).block().cost(millionEach))
            .isEqualByComparingTo("140");
    }

    @Test
    void exactRulesWinEqualPriorityButExplicitPriorityRemainsPrimary() {
        var wildcard = ratio("all", "*", "1", 100);
        var exact = ratio("exact", "gpt-5.5", "0.1", 100);
        for (var rules : List.of(List.of(wildcard, exact), List.of(exact, wildcard))) {
            var result = preview("gpt-5.5", rules);
            assertThat(result.matchedRuleIds()).containsExactly("exact", "all");
            assertThat(result.pricing().inputPricePerMillionTokens()).isEqualByComparingTo("0.5");
        }
        assertThat(preview("gpt-5.5", List.of(exact, ratio("all", "*", "2", 10))).matchedRuleIds())
            .containsExactly("all", "exact");
    }

    @Test
    void narrowerWildcardsWinTiesAndDisabledRulesDoNotMatch() {
        var disabled = new ModelDtos.UpdateModelPricingRuleRequest("off", false, "gpt-5.5", BigDecimal.TEN, 0);
        var result = preview("starry/gpt-5.5", List.of(disabled, ratio("all", "*", "1", 100),
            ratio("gpt", "gpt-*", "0.5", 100), ratio("five", "gpt-5*", "0.1", 100)));
        assertThat(result.matchedRuleIds()).containsExactly("five", "gpt", "all");
        assertThat(result.pricing().totalPricePerMillionTokens()).isEqualByComparingTo("1.75");
    }

    @Test
    void providerWildcardDoesNotLeakIntoOtherProviders() {
        var rule = ratio("claude", "anthropic/*", "0.1", 100);
        assertThat(preview("gpt-5.5", List.of(rule)).matchedRuleIds()).isEmpty();
        assertThat(preview("starry/claude-sonnet-4.5", List.of(rule)).matchedRuleIds()).containsExactly("claude");
    }

    @Test
    void customAliasUsesTargetPriceAndAliasSpecificRuleWinsTie() {
        catalog.applyCustomizations(List.of(alias("team-model", "gpt-5.5")));
        save(ratio("target", "gpt-5.5", "2", 100), ratio("alias", "team-model", "0.1", 100));
        var result = preview("starry/team-model", List.of(ratio("target", "gpt-5.5", "2", 100),
            ratio("alias", "team-model", "0.1", 100)));
        assertThat(result.pricingModel()).isEqualTo("gpt-5.5");
        assertThat(result.matchedRuleIds()).containsExactly("alias", "target");
        assertThat(result.pricing().inputPricePerMillionTokens()).isEqualByComparingTo("0.5");
        assertThat(service.billingQuote("starry/team-model", false, BigDecimal.TEN).block().cost(millionEach))
            .isEqualByComparingTo("3.5");
    }

    @Test
    void prefixedAndDisabledCustomAliasesUseTheSamePriceInPreviewAndCatalog() {
        catalog.applyCustomizations(List.of(new ModelCatalogCustomization("openai/team-model", "Team", null,
            "OpenAI", "Custom", "gpt-5.5", null, null, null, null, false)));
        var model = catalog.managedModels().stream().filter(item -> item.id().equals("openai/team-model")).findFirst().orElseThrow();
        var shown = service.applyManagedPricing(List.of(model), BigDecimal.ONE).block().getFirst().pricing();
        assertThat(preview("openai/team-model", List.of()).pricing()).isEqualTo(shown);
        assertThat(shown.inputPricePerMillionTokens()).isEqualByComparingTo("5");
    }

    @Test
    void compatibilityRoutingTargetDoesNotOverrideOfficialModelPrice() {
        assertThat(catalog.targetFor("gpt-5.5")).isPresent();
        assertThat(preview("gpt-5.5", List.of()).basePricing().outputPricePerMillionTokens())
            .isEqualByComparingTo("30");
    }

    @Test
    void aliasCycleAndUnknownModelsAreUnpricedWithoutBreakingCatalog() {
        catalog.applyCustomizations(List.of(alias("cycle-a", "cycle-b"), alias("cycle-b", "cycle-a")));
        for (String model : List.of("cycle-a", "unknown-model", "gpt-999-unknown")) {
            assertThat(preview(model, List.of()).pricing().billingMode()).isEqualTo("unavailable");
            assertThatThrownBy(() -> service.billingQuote(model, false, BigDecimal.ONE).block())
                .isInstanceOf(ApiException.class).hasMessageContaining("Model price is unavailable");
        }
    }

    @Test
    void legacyTotalPriceMatchesDisplayAndQuoteEvenForUnknownModels() {
        var rule = new ModelDtos.UpdateModelPricingRuleRequest("flat", true, "*",
            null, null, new BigDecimal("0.000001"), null, null, null, 100);
        save(rule);
        for (String model : List.of("gpt-5.5", "unknown-model")) {
            var result = preview(model, List.of(rule));
            assertThat(result.source()).isEqualTo("legacy_total");
            assertThat(result.pricing().billingMode()).isEqualTo("total");
            assertThat(result.pricing().totalPricePerMillionTokens()).isEqualByComparingTo("1");
            assertThat(service.billingQuote(model, false, BigDecimal.TEN).block().cost(millionEach))
                .isEqualByComparingTo("2");
        }
    }

    @Test
    void cachedTokenQuoteAgreesWithDisplayedRates() {
        save(ratio("exact", "gpt-5.5", "0.1", 100));
        var usage = new PlatformUsage(3_000_000, 1_000_000, BigDecimal.ZERO, 1_000_000, 1_000_000, 0);
        var quote = service.billingQuote("gpt-5.5", false, BigDecimal.ONE).block();
        assertThat(quote.cost(usage)).isEqualByComparingTo("4.05");
        assertThat(service.cost("starry/gpt-5.5", usage, false, BigDecimal.ONE).block()).isEqualByComparingTo("4.05");
    }

    @Test
    void previewNeverWritesSettingsAndValidatesCanonicalDuplicates() {
        var rule = ratio("exact", "gpt-5.5", "0.1", 100);
        assertThat(preview("gpt-5.5", List.of(rule)).multiplier()).isEqualByComparingTo("0.1");
        assertThatThrownBy(() -> preview("gpt-5.5", List.of(rule, ratio("alias", "starry/gpt-5.5", "2", 100))))
            .isInstanceOf(ApiException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> save(rule, ratio("alias", "openai/gpt-5.5", "2", 100)))
            .isInstanceOf(ApiException.class).hasMessageContaining("Duplicate");
        verify(repository, never()).upsertSetting(anyString(), anyString());
    }

    @Test
    void tinyMultipliersKeepMeaningfulPrecisionAndCannotRoundToZero() {
        var result = preview("gpt-5.5", List.of(ratio("tiny", "*", "0.000001", 100)));
        assertThat(result.pricing().inputPricePerMillionTokens()).isEqualByComparingTo("0.000005");
        assertThatThrownBy(() -> save(ratio("zero", "*", "0.0000001", 100)))
            .isInstanceOf(ApiException.class);
        save(ratio("tiny", "*", "0.000001", 100));
        assertThat(service.billingQuote("gpt-5.5", false, BigDecimal.ONE).block().cost(millionEach))
            .isEqualByComparingTo("0.000035");
    }

    @Test
    void staleRefreshCannotReplaceRulesSavedWhileItWasRunning() {
        save(ratio("exact", "gpt-5.5", "1", 100));
        var staleEntity = repository.findById(ModelPricingService.MODEL_PRICING_RULES).block();
        var stale = Sinks.<SystemSettingEntity>one();
        var current = repository.findById(ModelPricingService.MODEL_PRICING_RULES).block();
        var store = new java.util.concurrent.atomic.AtomicReference<>(current);
        var delayNext = new AtomicBoolean(true);
        when(repository.findById(anyString())).thenAnswer(invocation ->
            delayNext.getAndSet(false) ? stale.asMono() : reactor.core.publisher.Mono.just(store.get()));
        when(repository.upsertSetting(anyString(), anyString())).thenAnswer(invocation -> {
            var next = new SystemSettingEntity();
            next.setSettingValue(invocation.getArgument(1));
            store.set(next);
            return reactor.core.publisher.Mono.just(1);
        });
        var finished = new AtomicBoolean();
        service.refresh().doOnSuccess(ignored -> finished.set(true)).subscribe();
        save(ratio("exact", "gpt-5.5", "0.1", 100));
        stale.tryEmitValue(staleEntity);
        assertThat(finished).isTrue();
        assertThat(service.cost("gpt-5.5", millionEach, false, BigDecimal.ONE).block()).isEqualByComparingTo("3.5");
        assertThat(service.billingQuote("gpt-5.5", false, BigDecimal.ONE).block().cost(millionEach))
            .isEqualByComparingTo("3.5");
    }

    @Test
    void deferredCostUsesRulesAtSubscriptionTime() {
        save(ratio("exact", "gpt-5.5", "1", 100));
        var pending = service.cost("gpt-5.5", millionEach, false, BigDecimal.ONE);
        save(ratio("exact", "gpt-5.5", "0.1", 100));
        assertThat(pending.block()).isEqualByComparingTo("3.5");
    }

    private ModelDtos.ModelPricingPreview preview(String model, List<ModelDtos.UpdateModelPricingRuleRequest> rules) {
        return service.preview(new ModelDtos.ModelPricingPreviewRequest(model, rules), BigDecimal.ONE);
    }

    @Test
    void bareGptShorthandsAreExactIdsAndDoNotPretendToMatchAFamily() {
        for (String pattern : List.of("gpt", "gpt5", "gpt5*")) {
            assertThat(preview("gpt-5.5", List.of(ratio("rule", pattern, "0.1", 100))).matchedRuleIds()).isEmpty();
        }
        assertThat(preview("gpt-5.5", List.of(ratio("rule", "gpt-5*", "0.1", 100))).multiplier())
            .isEqualByComparingTo("0.1");
    }

    @Test
    void providerScopeCoversGptAndReasoningModelsWithAnySupportedPrefix() {
        var rule = scoped("openai", "provider", "openai", null, "0.2", 100);
        var saved = service.update(new ModelDtos.UpdateModelPricingRequest(List.of(rule))).block().rules().getFirst();
        assertThat(saved.scope()).isEqualTo("provider");
        assertThat(saved.provider()).isEqualTo("OpenAI");
        for (String model : List.of("gpt-5.5", "starry/gpt-5.5", "openai/gpt-4o", "o3", "gpt-6")) {
            assertThat(preview(model, List.of(rule)).matchedRuleIds()).as(model).containsExactly("openai");
        }
        assertThat(preview("claude-sonnet-4.5", List.of(rule)).matchedRuleIds()).isEmpty();
        assertThat(service.billingQuote("gpt-5.5", false, BigDecimal.ONE).block().cost(millionEach))
            .isEqualByComparingTo("7");
        assertThat(new ModelPricingService(repository, mapper, official, catalog)
            .cost("gpt-5.5", millionEach, false, BigDecimal.ONE).block()).isEqualByComparingTo("7");
    }

    @Test
    void familyScopeUsesCatalogMembershipAndCanonicalNames() {
        var rule = scoped("five", "family", "OpenAI", "gpt-5", "0.1", 100);
        var saved = service.update(new ModelDtos.UpdateModelPricingRequest(List.of(rule))).block().rules().getFirst();
        assertThat(saved.family()).isEqualTo("GPT-5");
        for (String model : List.of("gpt-5.5", "openai/gpt-5.4-mini", "gpt-5.2")) {
            assertThat(preview(model, List.of(rule)).matchedRuleIds()).as(model).containsExactly("five");
        }
        for (String model : List.of("gpt-4o", "gpt-6", "o3")) {
            assertThat(preview(model, List.of(rule)).matchedRuleIds()).as(model).isEmpty();
        }
    }

    @Test
    void explicitModelThenFamilyThenProviderThenWildcardWinEqualPriority() {
        var all = ratio("all", "*", "1", 100);
        var provider = scoped("provider", "provider", "OpenAI", null, "0.5", 100);
        var family = scoped("family", "family", "OpenAI", "GPT-5", "0.2", 100);
        var exact = ratio("exact", "gpt-5.5", "0.1", 100);
        assertThat(preview("starry/gpt-5.5", List.of(all, provider, family, exact)).matchedRuleIds())
            .containsExactly("exact", "family", "provider", "all");
        assertThat(preview("gpt-5.5", List.of(all, provider, family)).multiplier()).isEqualByComparingTo("0.2");
        assertThat(preview("gpt-5.5", List.of(all, provider)).multiplier()).isEqualByComparingTo("0.5");
        assertThat(preview("gpt-5.5", List.of(family, exact,
            scoped("priority", "provider", "OpenAI", null, "0.8", 10))).matchedRuleIds())
            .containsExactly("priority", "exact", "family");
    }

    @Test
    void providerScopeNeverFollowsACrossProviderRoutingTarget() {
        var anthropic = scoped("anthropic", "provider", "Anthropic", null, "0.1", 100);
        assertThat(catalog.targetFor("gpt-6").orElseThrow()).startsWith("claude-");
        assertThat(preview("gpt-6", List.of(anthropic)).matchedRuleIds()).isEmpty();
        catalog.applyCustomizations(List.of(new ModelCatalogCustomization("acme-model", "ACME", null,
            "ACME", "GPT-5", "gpt-5.5", null, null, null, null, true)));
        assertThat(preview("acme-model", List.of(scoped("openai", "family", "OpenAI", "GPT-5", "0.1", 100)))
            .matchedRuleIds()).isEmpty();
        assertThat(preview("acme-model", List.of(scoped("acme", "family", "ACME", "GPT-5", "0.3", 100)))
            .pricing().inputPricePerMillionTokens()).isEqualByComparingTo("1.5");
    }

    @Test
    void newModelsAutomaticallyInheritScopedRulesAndMetadataChangesInvalidateTheIndex() {
        var rule = scoped("custom", "family", "ACME", "Team", "0.2", 100);
        assertThat(preview("future-model", List.of(rule)).matchedRuleIds()).isEmpty();
        catalog.applyCustomizations(List.of(new ModelCatalogCustomization("future-model", "Future", null,
            "ACME", "Team", "gpt-5.5", null, null, null, null, true)));
        assertThat(preview("starry/future-model", List.of(rule)).matchedRuleIds()).containsExactly("custom");
        catalog.applyCustomizations(List.of(new ModelCatalogCustomization("future-model", "Future", null,
            "Other", "Team", "gpt-5.5", null, null, null, null, true)));
        assertThat(preview("future-model", List.of(rule)).matchedRuleIds()).isEmpty();
    }

    @Test
    void disabledFamilyRuleFallsBackToProviderMultiplier() {
        var disabled = new ModelDtos.UpdateModelPricingRuleRequest("family", false, null, new BigDecimal("0.1"),
            null, null, null, null, null, null, null, null, null, null, 100, "family", "OpenAI", "GPT-5");
        assertThat(preview("gpt-5.5", List.of(disabled, scoped("provider", "provider", "OpenAI", null, "0.5", 100)))
            .multiplier()).isEqualByComparingTo("0.5");
    }

    @Test
    void scopedRulesRejectMissingMetadataAndDuplicateCanonicalScopes() {
        for (var rule : List.of(scoped("bad", "unknown", "OpenAI", null, "1", 100),
            scoped("bad", "provider", null, null, "1", 100), scoped("bad", "family", "OpenAI", null, "1", 100))) {
            assertThatThrownBy(() -> save(rule)).isInstanceOf(ApiException.class).hasMessageContaining("scope");
        }
        assertThatThrownBy(() -> save(scoped("one", "provider", "Gemini", null, "1", 100),
            scoped("two", "provider", "Google", null, "0.1", 100)))
            .isInstanceOf(ApiException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> save(scoped("one", "family", "OpenAI", "GPT-5", "1", 100),
            scoped("two", "family", "openai", "gpt-5", "0.1", 100)))
            .isInstanceOf(ApiException.class).hasMessageContaining("Duplicate");
    }

    private ModelDtos.UpdateModelPricingRuleRequest scoped(String id, String scope, String provider,
                                                           String family, String multiplier, int priority) {
        return new ModelDtos.UpdateModelPricingRuleRequest(id, true, null, new BigDecimal(multiplier),
            null, null, null, null, null, null, null, null, null, null, priority, scope, provider, family);
    }
    private void save(ModelDtos.UpdateModelPricingRuleRequest... rules) {
        service.update(new ModelDtos.UpdateModelPricingRequest(List.of(rules))).block();
    }
    private ModelDtos.UpdateModelPricingRuleRequest ratio(String id, String model, String multiplier, int priority) {
        return new ModelDtos.UpdateModelPricingRuleRequest(id, true, model, new BigDecimal(multiplier), priority);
    }
    private ModelCatalogCustomization alias(String id, String target) {
        return new ModelCatalogCustomization(id, id, null, "OpenAI", "Custom", target, null, null, null, null, true);
    }
}
