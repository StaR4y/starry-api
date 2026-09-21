package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.DefaultModelCatalogProvider;

class ModelProviderAccessPolicyTest {

    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();

    @Test
    void allowsModelInApiKeyProvider() {
        ModelProviderAccessPolicy.verify(catalog, principal("OpenAI"), "gpt-4.1", "gpt-4.1");
    }

    @Test
    void rejectsModelFromAnotherProvider() {
        assertThatThrownBy(() -> ModelProviderAccessPolicy.verify(
            catalog,
            principal("OpenAI"),
            "claude-sonnet-4",
            "claude-sonnet-4"
        )).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus().value()).isEqualTo(403);
            assertThat(exception.getCode()).isEqualTo("API_KEY_MODEL_PROVIDER_FORBIDDEN");
        });
    }

    @Test
    void rejectsCrossProviderModelMapping() {
        assertThatThrownBy(() -> ModelProviderAccessPolicy.verify(
            catalog,
            principal("OpenAI"),
            "gpt-4.1",
            "claude-sonnet-4"
        )).isInstanceOfSatisfying(ApiException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("API_KEY_MODEL_PROVIDER_FORBIDDEN")
        );
    }

    @Test
    void rejectsUnknownModelForCategorizedKey() {
        assertThatThrownBy(() -> ModelProviderAccessPolicy.verify(
            catalog,
            principal("OpenAI"),
            "unknown-model",
            "unknown-model"
        )).isInstanceOfSatisfying(ApiException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("MODEL_NOT_AVAILABLE")
        );
    }

    @Test
    void keepsLegacyAllProviderKeysCompatible() {
        ModelProviderAccessPolicy.verify(catalog, principal("*"), "unknown-model", "other-model");
    }

    @Test
    void treatsLegacyGeminiApiKeysAsGoogle() {
        ModelProviderAccessPolicy.verify(catalog, principal("Gemini"), "google/gemini-3.5-flash", "google/gemini-3.5-flash");
        ModelProviderAccessPolicy.verify(catalog, principal("Google"), "gemini-2.5-pro", "gemini-2.5-pro");
    }

    private ApiKeyPrincipal principal(String modelProvider) {
        return new ApiKeyPrincipal(
            "key-1",
            "user-1",
            "USER",
            "test",
            null,
            BigDecimal.ZERO,
            BigDecimal.TEN,
            modelProvider
        );
    }
}
