package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.sourcetier.SourceTierModelListing;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /v1/models}: untouched while no tiers are in use, priced under the first tier of a tiered vendor otherwise. */
class ModelCatalogControllerTest {

    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final ModelPricingService pricing = new ModelPricingService(
        ModelPricingServiceTestSupport.repository(),
        JsonMapper.builder().findAndAddModules().build(),
        new OfficialModelPricingService(WebClient.builder().build(), JsonMapper.builder().build(), new OfficialModelPricingProperties()),
        catalog);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final SourceTierModelListing tiers = mock(SourceTierModelListing.class);
    private final ModelCatalogController controller = new ModelCatalogController(catalog, pricing, payments, tiers);

    private ModelDtos.ModelListResponse listed() {
        when(payments.config()).thenReturn(Mono.just(new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
            30, 3, List.of("alipay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null)));
        return controller.models().block().getBody();
    }

    @Test
    void whenNoTiersAreInUseTheListIsThePricedCatalogAndNoModelHasSourceTiers() {
        when(tiers.current()).thenReturn(ModelTierListing.NONE);

        ModelDtos.ModelListResponse response = listed();

        ModelDtos.ModelListResponse before = pricing.applyPublicPricing(catalog.modelList(0L), BigDecimal.ONE).block();
        assertThat(response.data()).hasSameSizeAs(before.data());
        for (int i = 0; i < before.data().size(); i++) {
            assertThat(response.data().get(i)).usingRecursiveComparison().ignoringFields("created")
                .isEqualTo(before.data().get(i));
        }
        assertThat(response.data()).allSatisfy(item -> assertThat(item.sourceTiers()).isNull());
    }

    @Test
    void aTieredVendorsModelsAreListedAtTheirFirstTierAndTheOtherVendorsAreNot() {
        when(tiers.current()).thenReturn(item -> item.id().startsWith("claude-")
            ? Optional.of(new ModelTierListing.Listing(new BigDecimal("0.5"), List.of(
                new ModelTierListing.Tier("kiro", 2, "kiro", new BigDecimal("0.5"), true))))
            : Optional.empty());

        ModelDtos.ModelListResponse response = listed();

        ModelDtos.ModelListItem claude = response.data().stream().filter(item -> item.id().equals("claude-sonnet-4.5")).findFirst().orElseThrow();
        ModelDtos.ModelListItem gpt = response.data().stream().filter(item -> item.id().equals("gpt-5.4")).findFirst().orElseThrow();
        assertThat(claude.inputPricePerMillionTokens()).isEqualByComparingTo("1.5");
        assertThat(claude.sourceTiers()).singleElement().satisfies(tier -> assertThat(tier.id()).isEqualTo("kiro"));
        assertThat(gpt.sourceTiers()).isNull();
        assertThat(gpt.inputPricePerMillionTokens()).isEqualByComparingTo(
            pricing.applyPublicPricing(catalog.modelList(0L), BigDecimal.ONE).block().data().stream()
                .filter(item -> item.id().equals("gpt-5.4")).findFirst().orElseThrow().inputPricePerMillionTokens());
    }
}
