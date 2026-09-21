package tech.starryapi.platform.deepseek;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tech.starryapi.model.ModelDtos;

class DeepSeekModelCatalogTest {

    @Test
    void exposesRepositoryModelIdsAndLimits() {
        assertThat(DeepSeekModelCatalog.models())
            .extracting(ModelDtos.ModelSpec::id)
            .containsExactly(
                "deepseek-chat",
                "deepseek-reasoner",
                "deepseek-v4-flash",
                "deepseek-v4-pro"
            );
        assertThat(DeepSeekModelCatalog.models())
            .allSatisfy(model -> {
                assertThat(model.provider()).isEqualTo("DeepSeek");
                assertThat(model.maxInputTokens()).isEqualTo(1_000_000);
                assertThat(model.maxOutputTokens()).isEqualTo(384_000);
                assertThat(model.targetModelId()).isEqualTo(model.id());
            });
    }

    @Test
    void exposesOfficialDefaultPricesPerMillionTokens() {
        DeepSeekModelCatalog.DefaultPricing flash = DeepSeekModelCatalog
            .defaultPricing("deepseek/deepseek-v4-flash")
            .orElseThrow();
        DeepSeekModelCatalog.DefaultPricing pro = DeepSeekModelCatalog
            .defaultPricing("starry/deepseek-v4-pro")
            .orElseThrow();

        assertThat(flash.inputPricePerMillionTokens()).isEqualByComparingTo(new BigDecimal("0.14"));
        assertThat(flash.outputPricePerMillionTokens()).isEqualByComparingTo(new BigDecimal("0.28"));
        assertThat(flash.cacheReadPricePerMillionTokens()).isEqualByComparingTo(new BigDecimal("0.0028"));
        assertThat(pro.inputPricePerMillionTokens()).isEqualByComparingTo(new BigDecimal("0.435"));
        assertThat(pro.outputPricePerMillionTokens()).isEqualByComparingTo(new BigDecimal("0.87"));
        assertThat(pro.cacheReadPricePerMillionTokens()).isEqualByComparingTo(new BigDecimal("0.003625"));
    }

    @Test
    void responsesCapabilityAndRoutingPrefixesAreCanonicalized() {
        assertThat(DeepSeekModelCatalog.canonicalModelId("openai-compatible/deepseek-v4-flash"))
            .isEqualTo("deepseek-v4-flash");
        assertThat(DeepSeekModelCatalog.supportsResponses("deepseek/deepseek-v4-flash")).isTrue();
        assertThat(DeepSeekModelCatalog.supportsResponses("deepseek-v4-pro")).isFalse();
    }
}
