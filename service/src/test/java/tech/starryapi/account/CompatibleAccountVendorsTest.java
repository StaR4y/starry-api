package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.model.ModelDtos;

class CompatibleAccountVendorsTest {

    @Test
    void groupsUserAddedOpenAiCompatibleVendorsAndExposesConfiguredModelIds() {
        List<AccountDtos.CompatibleVendorModels> vendors = CompatibleAccountVendors.from(List.of(
            model("gpt-4o", "OpenAI", true, true, "gpt-4o", null, null),
            model("or-kimi", "OpenRouter", false, true, "moonshotai/kimi-k2", "/assets/model-icons/or.png", "#4D6BFE"),
            model("or-llama", "OpenRouter", false, true, "meta-llama/llama-3.3-70b", null, "#4D6BFE"),
            model("tg-free", "Together AI", false, true, "tg-free", null, "#0EA5E9"),
            model("hidden", "Together AI", false, false, "hidden-upstream", null, "#0EA5E9")
        ));

        assertThat(vendors).extracting(AccountDtos.CompatibleVendorModels::name)
            .containsExactly("OpenRouter", "Together AI");
        assertThat(CompatibleAccountVendors.find(vendors, "openrouter").icon())
            .isEqualTo("/assets/model-icons/or.png");
        assertThat(CompatibleAccountVendors.find(vendors, "OpenRouter").color()).isEqualTo("#4D6BFE");
        assertThat(CompatibleAccountVendors.find(vendors, "OpenRouter").models())
            .extracting(AccountDtos.TestModelOption::value)
            .containsExactly("auto", "moonshotai/kimi-k2", "meta-llama/llama-3.3-70b");
        assertThat(CompatibleAccountVendors.defaultTestModel(vendors, "OpenRouter"))
            .isEqualTo("moonshotai/kimi-k2");
        assertThat(CompatibleAccountVendors.find(vendors, "Together AI").models())
            .extracting(AccountDtos.TestModelOption::value)
            .containsExactly("auto", "tg-free");
    }

    private ModelDtos.ManagedModelResponse model(
        String id,
        String provider,
        boolean builtIn,
        boolean enabled,
        String targetModelId,
        String icon,
        String badgeColor
    ) {
        return new ModelDtos.ManagedModelResponse(
            id,
            id,
            icon,
            provider,
            "Custom",
            targetModelId,
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            128000,
            null,
            enabled,
            builtIn,
            badgeColor,
            null
        );
    }
}
