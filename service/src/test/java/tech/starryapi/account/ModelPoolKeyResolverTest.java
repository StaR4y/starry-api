package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos;

class ModelPoolKeyResolverTest {

    private ModelPoolKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ModelPoolKeyResolver(new DefaultModelCatalogProvider());
    }

    @Test
    void resolvesOpenAiFamiliesFromCatalog() {
        assertPool("gpt-6", "openai", "gpt-6", "OpenAI", "GPT-6");
        assertPool("gpt-6-astra", "openai", "gpt-6", "OpenAI", "GPT-6");
        assertPool("gpt-5.2", "openai", "gpt-5", "OpenAI", "GPT-5");
        assertPool("gpt-4o", "openai", "gpt-4", "OpenAI", "GPT-4");
        assertPool("o3", "openai", "openai-reasoning", "OpenAI", "OpenAI Reasoning");
    }

    @Test
    void resolvesAnthropicFamiliesFromCatalog() {
        assertPool("claude-sonnet-4.5", "anthropic", "claude-4", "Anthropic", "Claude 4");
        assertPool("claude-3.5-sonnet", "anthropic", "claude-3", "Anthropic", "Claude 3");
    }

    @Test
    void resolvesGoogleAndDeepSeekFamiliesFromCatalog() {
        assertPool("gemini-2.5-pro", "google", "gemini-2.5", "Google", "Gemini 2.5");
        assertPool("deepseek-v4-pro", "deepseek", "deepseek-v4", "DeepSeek", "DeepSeek V4");
    }

    @Test
    void mappedUpstreamModelTakesPriorityOverRequestedModel() {
        ModelPoolKey pool = resolver.resolve("gpt-5.2", "deepseek-v4-flash", "OpenAI");

        assertThat(pool.key()).isEqualTo("deepseek:deepseek-v4");
    }

    @Test
    void requestedModelIsUsedWhenUpstreamModelIsNotInCatalog() {
        ModelPoolKey pool = resolver.resolve("gpt-4o", "gateway-private-model", "Kiro");

        assertThat(pool.key()).isEqualTo("openai:gpt-4");
    }

    @Test
    void stripsCaseWrappersVendorPrefixesAndFastSuffix() {
        ModelPoolKey openAi = resolver.resolve(null, "  STARRY/OPENAI/GPT-5.2-FAST  ", null);
        ModelPoolKey deepSeek = resolver.resolve(null, "openai-compatible/DEEPSEEK-V4-PRO", null);
        ModelPoolKey anthropic = resolver.resolve(null, "ANTHROPIC/CLAUDE-SONNET-4.5", null);

        assertThat(openAi.key()).isEqualTo("openai:gpt-5");
        assertThat(deepSeek.key()).isEqualTo("deepseek:deepseek-v4");
        assertThat(anthropic.key()).isEqualTo("anthropic:claude-4");
    }

    @Test
    void unknownModelsCollapseIntoControlledProviderFallback() {
        ModelPoolKey first = resolver.resolve("customer-model-a", "private-model-a", "OPENAI");
        ModelPoolKey second = resolver.resolve("customer-model-b", "private-model-b", "openai");
        ModelPoolKey arbitraryProvider = resolver.resolve(null, "private-model-c", "customer-controlled-provider");

        assertThat(first).isEqualTo(second);
        assertThat(first.key()).isEqualTo("openai:other");
        assertThat(first.toString()).doesNotContain("private-model", "customer-model");
        assertThat(arbitraryProvider.key()).isEqualTo("unknown:other");
    }

    @Test
    void controlledModelPrefixSuppliesFallbackProviderWhenHintIsMissing() {
        ModelPoolKey pool = resolver.resolve(null, "google/not-yet-catalogued", null);

        assertThat(pool.key()).isEqualTo("google:other");
    }

    @Test
    void explicitModelPrefixTakesPriorityOverTheRouteHint() {
        ModelPoolKey pool = resolver.resolve(null, "google/not-yet-catalogued", "Kiro");

        assertThat(pool.key()).isEqualTo("google:other");
    }

    @Test
    void explicitVendorPrefixCannotBorrowAnotherVendorsLocalModelId() {
        assertThat(resolver.resolve(null, "google/gpt-5.2", "OpenAI").key())
            .isEqualTo("google:other");
        assertThat(resolver.resolve(null, "openai/deepseek-v4-pro", "DeepSeek").key())
            .isEqualTo("openai:other");
        assertThat(resolver.resolve(null, "openai-compatible/deepseek-v4-pro", "OpenAI-compatible").key())
            .isEqualTo("deepseek:deepseek-v4");
    }

    @Test
    void exposesSortedKnownPoolsAndStableRepresentativeModels() {
        ModelPoolKey deepSeek = resolver.resolve(null, "deepseek-v4-flash", null);

        assertThat(resolver.knownPools()).isSorted().doesNotHaveDuplicates().contains(deepSeek);
        assertThat(resolver.representativeModel(deepSeek)).isPresent();
        assertThat(resolver.resolve(null, resolver.representativeModel(deepSeek).orElseThrow(), null))
            .isEqualTo(deepSeek);
        assertThat(resolver.representativeModel(new ModelPoolKey("deepseek", "other", "DeepSeek", "Other")))
            .isEmpty();
    }

    @Test
    void stablePoolKeyCanBeUsedAsMapKeyAcrossEquivalentModelForms() {
        ModelPoolKey direct = resolver.resolve(null, "gpt-5.2", null);
        ModelPoolKey wrapped = resolver.resolve(null, "starry/openai/gpt-5.2-fast", null);
        ModelPoolKey relabeled = new ModelPoolKey("openai", "gpt-5", "OpenAI API", "GPT Five");

        assertThat(Map.of(direct, "state")).containsEntry(wrapped, "state");
        assertThat(Map.of(direct, "state")).containsEntry(relabeled, "state");
    }

    @Test
    void atomicallyRebuildsTheLookupIndexWhenTheRuntimeCatalogChanges() {
        MutableCatalog catalog = new MutableCatalog(List.of(model("team-model", "Acme AI", "Alpha")));
        ModelPoolKeyResolver dynamicResolver = new ModelPoolKeyResolver(catalog);

        assertThat(dynamicResolver.resolve("team-model", "team-model", null).key())
            .isEqualTo("acme-ai:alpha");

        catalog.replace(List.of(model("team-model", "Acme AI", "Beta")));

        ModelPoolKey refreshed = dynamicResolver.resolve("team-model", "team-model", null);
        assertThat(refreshed.key()).isEqualTo("acme-ai:beta");
        assertThat(dynamicResolver.knownPools())
            .extracting(ModelPoolKey::key)
            .contains("acme-ai:beta")
            .doesNotContain("acme-ai:alpha");
        assertThat(dynamicResolver.representativeModel(refreshed)).contains("team-model");
    }

    @Test
    void longCatalogLabelsProduceBoundedCollisionResistantKeys() {
        String sharedPrefix = "Provider ".repeat(12);
        String firstFamily = "Long family ".repeat(12) + "A";
        String secondFamily = "Long family ".repeat(12) + "B";
        MutableCatalog catalog = new MutableCatalog(List.of(
            model("model-a", sharedPrefix, firstFamily),
            model("model-b", sharedPrefix, secondFamily)
        ));
        ModelPoolKeyResolver dynamicResolver = new ModelPoolKeyResolver(catalog);

        ModelPoolKey first = dynamicResolver.resolve("model-a", "model-a", null);
        ModelPoolKey second = dynamicResolver.resolve("model-b", "model-b", null);

        assertThat(first.key()).hasSizeLessThanOrEqualTo(127);
        assertThat(second.key()).hasSizeLessThanOrEqualTo(127);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.provider()).hasSizeLessThanOrEqualTo(ModelPoolKey.MAX_COMPONENT_LENGTH);
        assertThat(first.family()).hasSizeLessThanOrEqualTo(ModelPoolKey.MAX_COMPONENT_LENGTH);
    }

    private void assertPool(
        String model,
        String provider,
        String family,
        String providerLabel,
        String familyLabel
    ) {
        ModelPoolKey pool = resolver.resolve(model, model, null);
        assertThat(pool.provider()).isEqualTo(provider);
        assertThat(pool.family()).isEqualTo(family);
        assertThat(pool.providerLabel()).isEqualTo(providerLabel);
        assertThat(pool.familyLabel()).isEqualTo(familyLabel);
        assertThat(pool.key()).isEqualTo(provider + ":" + family);
    }

    private static ModelDtos.ModelSpec model(String id, String provider, String family) {
        return new ModelDtos.ModelSpec(
            id,
            id,
            provider,
            family,
            "test",
            "test model",
            ModelCatalogProvider.TEXT_ONLY,
            128_000,
            8_000,
            id
        );
    }

    private static final class MutableCatalog implements ModelCatalogProvider {

        private final AtomicReference<List<ModelDtos.ModelSpec>> models;

        private MutableCatalog(List<ModelDtos.ModelSpec> models) {
            this.models = new AtomicReference<>(List.copyOf(models));
        }

        private void replace(List<ModelDtos.ModelSpec> replacement) {
            models.set(List.copyOf(replacement));
        }

        @Override
        public List<ModelDtos.ModelSpec> models() {
            return models.get();
        }

        @Override
        public Optional<String> targetFor(String modelId) {
            return Optional.empty();
        }
    }
}
