package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultModelCatalogProviderTest {

    @Test
    void exposesRepositoryPricedChatModelsAsBuiltInIds() {
        DefaultModelCatalogProvider provider = new DefaultModelCatalogProvider();

        assertThat(provider.models()).extracting(ModelDtos.ModelSpec::id)
            .contains(
                "openai/gpt-5.4-2026-03-05",
                "gpt-5.5",
                "gpt-6",
                "gpt-6-astra",
                "claude-4-sonnet",
                "claude-opus-4.8",
                "anthropic/claude-3-haiku-20240307",
                "google/gemini-3.7-flash",
                "deepseek-chat",
                "deepseek-reasoner"
            )
            .doesNotContain(
                "openai/gpt-5.3-chat",
                "anthropic/claude-opus-4.8-fast",
                "google/gemma-4-31b-it:free",
                "google/gemini-3.1-flash-live-preview"
            );
        assertThat(provider.models())
            .filteredOn(model -> "google/gemini-3.5-flash".equals(model.id()))
            .singleElement()
            .extracting(ModelDtos.ModelSpec::provider)
            .isEqualTo("Google");
        assertThat(provider.models())
            .filteredOn(model -> model.provider() != null && model.provider().equalsIgnoreCase("Gemini"))
            .isEmpty();
    }

    @Test
    void mergesBuiltInOverridesAndAddsCustomModels() {
        DefaultModelCatalogProvider provider = new DefaultModelCatalogProvider();

        provider.applyCustomizations(List.of(
            new ModelCatalogCustomization(
                "auto",
                "Automatic",
                "https://cdn.example.com/auto.png",
                "Starry",
                "Router",
                "claude-haiku-4.5",
                "OPENAI_CHAT_COMPLETIONS",
                "/openai/chat/completions",
                128_000,
                "Custom automatic router",
                true
            ),
            new ModelCatalogCustomization(
                "team-model",
                "Team Model",
                null,
                "Internal",
                "Team",
                "claude-sonnet-4.5",
                "OPENAI_CHAT_COMPLETIONS",
                "https://gateway.example.com/v1/chat/completions",
                300_000,
                null,
                true
            )
        ));

        ModelDtos.ModelSpec overridden = model(provider, "auto");
        assertThat(overridden.name()).isEqualTo("Automatic");
        assertThat(overridden.icon()).isEqualTo("https://cdn.example.com/auto.png");
        assertThat(overridden.endpoint()).isEqualTo("/openai/chat/completions");
        assertThat(overridden.maxInputTokens()).isEqualTo(128_000);
        assertThat(provider.resolveTargetModel("auto")).isEqualTo("claude-haiku-4.5");

        ModelDtos.ModelSpec custom = model(provider, "team-model");
        assertThat(custom.name()).isEqualTo("Team Model");
        assertThat(custom.requestFormat()).isEqualTo("OPENAI_CHAT_COMPLETIONS");
        assertThat(custom.maxInputTokens()).isEqualTo(300_000);
        assertThat(provider.resolveTargetModel("team-model")).isEqualTo("claude-sonnet-4.5");
    }

    @Test
    void appliesAdministratorDisplayOrderToPublicAndManagedCatalogs() {
        DefaultModelCatalogProvider provider = new DefaultModelCatalogProvider();
        provider.applyCustomizations(
            List.of(new ModelCatalogCustomization(
                "team-model",
                "Team Model",
                null,
                "Internal",
                "Team",
                "claude-sonnet-4.5",
                "OPENAI_CHAT_COMPLETIONS",
                "/v1/chat/completions",
                200_000,
                null,
                true
            )),
            List.of("team-model", "auto")
        );

        assertThat(provider.models()).extracting(ModelDtos.ModelSpec::id)
            .startsWith("team-model", "auto");
        assertThat(provider.managedModels()).extracting(ModelDtos.ManagedModelResponse::id)
            .startsWith("team-model", "auto");
    }

    @Test
    void syncedBuiltInsFillGapsWithoutReplacingExistingIds() {
        DefaultModelCatalogProvider provider = new DefaultModelCatalogProvider();
        provider.applyCustomizations(List.of(new ModelCatalogCustomization(
            "auto",
            "Auto",
            null,
            null,
            null,
            null,
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            null,
            null,
            false
        )));
        provider.applySyncedBuiltIns(List.of(
            new ModelDtos.ModelSpec(
                "auto",
                "Should Not Replace",
                "OpenAI",
                "GPT",
                "starryapi",
                "synced",
                List.of("TEXT"),
                128_000,
                32_000,
                "auto"
            ),
            new ModelDtos.ModelSpec(
                "synced-chat",
                "Synced Chat",
                "OpenAI",
                "GPT",
                "starryapi",
                "synced",
                List.of("TEXT"),
                128_000,
                32_000,
                "synced-chat"
            )
        ));

        assertThat(provider.models()).noneMatch(model -> model.id().equals("auto"));
        assertThat(provider.models()).anyMatch(model -> model.id().equals("synced-chat"));
        assertThat(provider.managedModels())
            .filteredOn(model -> model.id().equals("auto"))
            .singleElement()
            .extracting(ModelDtos.ManagedModelResponse::enabled)
            .isEqualTo(false);
    }

    @Test
    void keepsDisabledBuiltInVisibleOnlyToAdministrators() {
        DefaultModelCatalogProvider provider = new DefaultModelCatalogProvider();

        provider.applyCustomizations(List.of(new ModelCatalogCustomization(
            "auto",
            "Auto",
            null,
            null,
            null,
            null,
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            null,
            null,
            false
        )));

        assertThat(provider.models()).noneMatch(model -> model.id().equals("auto"));
        assertThat(provider.managedModels())
            .filteredOn(model -> model.id().equals("auto"))
            .singleElement()
            .extracting(ModelDtos.ManagedModelResponse::enabled)
            .isEqualTo(false);
    }

    private ModelDtos.ModelSpec model(DefaultModelCatalogProvider provider, String id) {
        return provider.models().stream()
            .filter(model -> model.id().equals(id))
            .findFirst()
            .orElseThrow();
    }
}
