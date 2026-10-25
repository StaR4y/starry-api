package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public model list and the pricing preview under source tiers (design 5.7, 8): the price of a model is the price it
 * always had multiplied by the factor of the tier a request is first served from, and a list without tiers is untouched.
 */
class ModelPricingTierListingTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final SystemSettingRepository repository = ModelPricingServiceTestSupport.repository();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final OfficialModelPricingService official = new OfficialModelPricingService(
        WebClient.builder().build(), mapper, new OfficialModelPricingProperties());
    private final ModelPricingService service = new ModelPricingService(repository, mapper, official, catalog);

    private ModelDtos.ModelListResponse list(String... ids) {
        List<ModelDtos.ModelListItem> items = new ArrayList<>();
        for (String id : ids) {
            items.addAll(catalog.modelList(1L).data().stream().filter(item -> item.id().equals(id)).toList());
        }
        assertThat(items).as("catalog models " + List.of(ids)).hasSize(ids.length);
        return new ModelDtos.ModelListResponse("list", items, items);
    }

    @Test
    void aListWithoutTiersIsPricedExactlyAsItWasWhetherOrNotAListingIsPassed() {
        ModelDtos.ModelListResponse models = list("claude-sonnet-4.5", "gpt-5.5");

        ModelDtos.ModelListResponse before = service.applyPublicPricing(models, BigDecimal.ONE).block();
        ModelDtos.ModelListResponse none = service.applyPublicPricing(models, BigDecimal.ONE, ModelTierListing.NONE).block();

        assertThat(none).isEqualTo(before);
        assertThat(none.data()).allSatisfy(item -> assertThat(item.sourceTiers()).isNull());
        assertThat(before.data().getFirst().inputPricePerMillionTokens()).isEqualByComparingTo("3");
    }

    @Test
    void aTieredModelIsListedAtTheStandardPriceTimesTheFactorOfItsFirstTier() {
        ModelDtos.ModelListResponse models = list("claude-sonnet-4.5", "gpt-5.5");
        ModelTierListing anthropicAtSixty = item -> "claude-sonnet-4.5".equals(item.id())
            ? Optional.of(new ModelTierListing.Listing(new BigDecimal("0.6"), List.of()))
            : Optional.empty();

        ModelDtos.ModelListResponse priced = service.applyPublicPricing(models, BigDecimal.ONE, anthropicAtSixty).block();

        ModelDtos.ModelListItem claude = priced.data().getFirst();
        assertThat(claude.inputPricePerMillionTokens()).isEqualByComparingTo("1.8");
        assertThat(claude.outputPricePerMillionTokens()).isEqualByComparingTo("9");
        assertThat(claude.cacheReadPricePerMillionTokens()).isEqualByComparingTo("0.18");
        assertThat(claude.sourceTiers()).as("no tiers are named unless the vendor lists them").isNull();
        // The other vendor's model is the very price it had.
        assertThat(priced.data().get(1))
            .isEqualTo(service.applyPublicPricing(list("gpt-5.5"), BigDecimal.ONE).block().data().getFirst());
        assertThat(priced.models()).isEqualTo(priced.data());
    }

    @Test
    void theTiersAreNamedWithTheirOwnScaledPriceWhenTheVendorListsThem() {
        ModelTierListing listed = item -> Optional.of(new ModelTierListing.Listing(BigDecimal.ONE, List.of(
            new ModelTierListing.Tier("official", 1, "官方", BigDecimal.ONE, true),
            new ModelTierListing.Tier("kiro", 2, "kiro反向代理", new BigDecimal("0.6"), false))));

        ModelDtos.ModelListItem claude = service.applyPublicPricing(list("claude-sonnet-4.5"), BigDecimal.ONE, listed)
            .block().data().getFirst();

        assertThat(claude.inputPricePerMillionTokens()).as("the headline is the first tier's price").isEqualByComparingTo("3");
        assertThat(claude.sourceTiers()).extracting(ModelDtos.ModelSourceTier::id).containsExactly("official", "kiro");
        ModelDtos.ModelSourceTier kiro = claude.sourceTiers().get(1);
        assertThat(kiro.level()).isEqualTo(2);
        assertThat(kiro.name()).isEqualTo("kiro反向代理");
        assertThat(kiro.priceFactor()).isEqualByComparingTo("0.6");
        assertThat(kiro.selectable()).isFalse();
        assertThat(kiro.pricing().inputPricePerMillionTokens()).isEqualByComparingTo("1.8");
        assertThat(kiro.pricing().outputPricePerMillionTokens()).isEqualByComparingTo("9");
        assertThat(claude.sourceTiers().getFirst().pricing().inputPricePerMillionTokens()).isEqualByComparingTo("3");
    }

    @Test
    void theRuleThatAppliesIsFoundOnceAndTheFactorMultipliesItsResult() {
        save(rule("claude", "claude-sonnet-4.5", "2"));
        ModelTierListing listed = item -> Optional.of(new ModelTierListing.Listing(new BigDecimal("0.5"), List.of(
            new ModelTierListing.Tier("kiro", 2, "kiro", new BigDecimal("0.5"), true))));

        ModelDtos.ModelListItem claude = service.applyPublicPricing(list("claude-sonnet-4.5"), BigDecimal.ONE, listed)
            .block().data().getFirst();

        // The standard price of this model is 2 x $3 under its rule; the tier takes half of that, not of the default.
        assertThat(claude.inputPricePerMillionTokens()).isEqualByComparingTo("3");
        assertThat(claude.sourceTiers().getFirst().pricing().inputPricePerMillionTokens()).isEqualByComparingTo("3");
    }

    @Test
    void aPreviewWithoutFactorsHasNoFactorPricing() {
        ModelDtos.ModelPricingPreview preview = service.preview(
            new ModelDtos.ModelPricingPreviewRequest("claude-sonnet-4.5", List.of()), BigDecimal.ONE);

        assertThat(preview.factorPricing()).isNull();
        assertThat(preview.pricing().inputPricePerMillionTokens()).isEqualByComparingTo("3");
    }

    @Test
    void aPreviewPricesTheModelAtEachFactorAfterTheRuleThatAppliesAndInTheOrderAsked() {
        ModelDtos.ModelPricingPreview preview = service.preview(new ModelDtos.ModelPricingPreviewRequest(
            "claude-sonnet-4.5", List.of(rule("claude", "claude-sonnet-4.5", "2")),
            List.of(new BigDecimal("1"), new BigDecimal("0.6"), new BigDecimal("12.5"))), BigDecimal.ONE);

        assertThat(preview.pricing().inputPricePerMillionTokens()).isEqualByComparingTo("6");
        assertThat(preview.factorPricing()).extracting(ModelDtos.FactorPricing::factor)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(new BigDecimal("1"), new BigDecimal("0.6"), new BigDecimal("12.5"));
        assertThat(preview.factorPricing().get(0).pricing()).isEqualTo(preview.pricing());
        assertThat(preview.factorPricing().get(1).pricing().inputPricePerMillionTokens()).isEqualByComparingTo("3.6");
        assertThat(preview.factorPricing().get(1).pricing().outputPricePerMillionTokens()).isEqualByComparingTo("18");
        assertThat(preview.factorPricing().get(2).pricing().inputPricePerMillionTokens()).isEqualByComparingTo("75");
    }

    @Test
    void aPreviewOfAPriceThatIsUnavailableStaysUnavailableAtEveryFactor() {
        ModelDtos.ModelPricingPreview preview = service.preview(new ModelDtos.ModelPricingPreviewRequest(
            "no-such-model-anywhere", List.of(), List.of(new BigDecimal("0.5"))), BigDecimal.ONE);

        assertThat(preview.pricing().billingMode()).isEqualTo("unavailable");
        assertThat(preview.factorPricing()).singleElement()
            .satisfies(priced -> assertThat(priced.pricing().billingMode()).isEqualTo("unavailable"));
    }

    @Test
    void aFactorOutsideTheRangeOfATierOrTooManyFactorsAreRefused() {
        for (BigDecimal bad : new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("1000.1")}) {
            assertThatThrownBy(() -> service.preview(new ModelDtos.ModelPricingPreviewRequest(
                "claude-sonnet-4.5", List.of(), List.of(bad)), BigDecimal.ONE))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("MODEL_PRICE_FACTOR_INVALID");
                });
        }
        assertThatThrownBy(() -> service.preview(new ModelDtos.ModelPricingPreviewRequest(
            "claude-sonnet-4.5", List.of(), java.util.Collections.nCopies(10, BigDecimal.ONE)), BigDecimal.ONE))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("MODEL_PRICE_FACTOR_INVALID"));
        assertThat(service.preview(new ModelDtos.ModelPricingPreviewRequest(
            "claude-sonnet-4.5", List.of(), java.util.Collections.nCopies(9, new BigDecimal("1000"))), BigDecimal.ONE)
            .factorPricing()).hasSize(9);
    }

    private ModelDtos.UpdateModelPricingRuleRequest rule(String id, String pattern, String multiplier) {
        return new ModelDtos.UpdateModelPricingRuleRequest(id, true, pattern, new BigDecimal(multiplier), 100);
    }

    private void save(ModelDtos.UpdateModelPricingRuleRequest... rules) {
        service.update(new ModelDtos.UpdateModelPricingRequest(List.of(rules))).block();
    }
}
