package tech.starryapi.platform.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.ClassPathResource;
import tech.starryapi.config.JacksonConfig;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos.ModelSpec;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport.AliasProfile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

public final class ModelPriceRepoModelIds {

    public static final String SOURCE_URL =
        "https://github.com/Wei-Shaw/model-price-repo";
    public static final String BUNDLED_CATALOG =
        "model-pricing/model_prices_and_context_window.json";

    private static final JsonNode CATALOG = loadCatalog();

    public static final List<String> OPENAI = modelIds(Set.of("openai"), "openai");
    public static final List<String> ANTHROPIC = modelIds(Set.of("anthropic"), "anthropic");
    public static final List<String> GOOGLE = modelIds(
        Set.of("gemini", "vertex_ai-language-models"),
        "google"
    );
    public static final List<String> DEEPSEEK = modelIds(Set.of("deepseek"), null);

    private static final AliasProfile OPENAI_PROFILE = new AliasProfile(
        "OpenAI",
        ModelCatalogProvider.STARRY_OWNER,
        "OpenAI chat model from model-price-repo",
        ModelCatalogProvider.TEXT_AND_IMAGE,
        ModelCatalogProvider.DEFAULT_INPUT_TOKENS,
        ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS
    );
    private static final AliasProfile ANTHROPIC_PROFILE = new AliasProfile(
        "Anthropic",
        ModelCatalogProvider.STARRY_OWNER,
        "Anthropic chat model from model-price-repo",
        ModelCatalogProvider.TEXT_AND_IMAGE,
        ModelCatalogProvider.DEFAULT_INPUT_TOKENS,
        ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS
    );
    private static final AliasProfile GOOGLE_PROFILE = new AliasProfile(
        "Google",
        ModelCatalogProvider.STARRY_OWNER,
        "Google chat model from model-price-repo",
        ModelCatalogProvider.TEXT_AND_IMAGE,
        ModelCatalogProvider.DEFAULT_INPUT_TOKENS,
        ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS
    );
    private static final AliasProfile DEEPSEEK_PROFILE = new AliasProfile(
        "DeepSeek",
        ModelCatalogProvider.STARRY_OWNER,
        "DeepSeek chat model from model-price-repo",
        ModelCatalogProvider.TEXT_ONLY,
        ModelCatalogProvider.DEFAULT_INPUT_TOKENS,
        ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS
    );

    private ModelPriceRepoModelIds() {
    }

    public static List<ModelSpec> syncedSpecs(JsonNode catalog) {
        if (catalog == null || !catalog.isObject()) {
            return List.of();
        }
        List<ModelSpec> models = new ArrayList<>();
        models.addAll(PlatformModelCatalogSupport.appendAliases(
            List.of(),
            modelIds(catalog, Set.of("openai"), "openai"),
            OPENAI_PROFILE,
            ModelPriceRepoModelIds::familyOf,
            id -> routedTarget(id, false)
        ));
        models.addAll(PlatformModelCatalogSupport.appendAliases(
            List.of(),
            modelIds(catalog, Set.of("anthropic"), "anthropic"),
            ANTHROPIC_PROFILE,
            ModelPriceRepoModelIds::familyOf,
            id -> routedTarget(id, true)
        ));
        models.addAll(PlatformModelCatalogSupport.appendAliases(
            List.of(),
            modelIds(catalog, Set.of("gemini", "vertex_ai-language-models"), "google"),
            GOOGLE_PROFILE,
            ModelPriceRepoModelIds::familyOf,
            id -> routedTarget(id, false)
        ));
        models.addAll(PlatformModelCatalogSupport.appendAliases(
            List.of(),
            modelIds(catalog, Set.of("deepseek"), null),
            DEEPSEEK_PROFILE,
            ModelPriceRepoModelIds::familyOf,
            PlatformModelCatalogSupport::localModelId
        ));
        return List.copyOf(models);
    }

    private static List<String> modelIds(Set<String> providers, String providerPrefix) {
        return modelIds(CATALOG, providers, providerPrefix);
    }

    private static List<String> modelIds(JsonNode catalog, Set<String> providers, String providerPrefix) {
        TreeSet<String> ids = new TreeSet<>();
        catalog.properties().forEach(entry -> {
            JsonNode metadata = entry.getValue();
            String provider = metadata.path("litellm_provider").asString("")
                .trim()
                .toLowerCase(Locale.ROOT);
            String mode = metadata.path("mode").asString("chat").trim().toLowerCase(Locale.ROOT);
            if (providers.contains(provider)
                && "chat".equals(mode)
                && hasTokenPrices(metadata)
                && supportsChatEndpoint(metadata)) {
                ids.add(providerId(entry.getKey(), providerPrefix));
            }
        });
        return List.copyOf(ids);
    }

    private static boolean hasTokenPrices(JsonNode metadata) {
        return nonNegativeDecimal(metadata.get("input_cost_per_token")) != null
            && nonNegativeDecimal(metadata.get("output_cost_per_token")) != null;
    }

    private static boolean supportsChatEndpoint(JsonNode metadata) {
        JsonNode endpoints = metadata.get("supported_endpoints");
        if (endpoints == null || !endpoints.isArray() || endpoints.isEmpty()) {
            return true;
        }
        for (JsonNode endpoint : endpoints) {
            String path = endpoint.asString("");
            if ("/v1/chat/completions".equals(path)
                || "/v1/messages".equals(path)
                || "/v1/completions".equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal nonNegativeDecimal(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return null;
        }
        BigDecimal decimal = value.decimalValue();
        return decimal.signum() >= 0 ? decimal : null;
    }

    private static String familyOf(String id) {
        String local = PlatformModelCatalogSupport.localModelId(id);
        if (local.startsWith("gpt-") || local.startsWith("chatgpt-") || local.startsWith("o")) {
            return "GPT";
        }
        if (local.contains("sonnet")) {
            return "Claude Sonnet";
        }
        if (local.contains("haiku")) {
            return "Claude Haiku";
        }
        if (local.contains("opus")) {
            return "Claude Opus";
        }
        if (local.startsWith("gemini") || local.startsWith("gemma") || local.startsWith("lyria")) {
            return PlatformModelCatalogSupport.googleFamily(id);
        }
        if (local.startsWith("deepseek")) {
            return "DeepSeek";
        }
        return "Other";
    }

    private static String routedTarget(String id, boolean anthropic) {
        String local = PlatformModelCatalogSupport.localModelId(id);
        if (anthropic) {
            return local;
        }
        if (local.contains("mini") || local.contains("nano") || local.contains("flash") || local.contains("lite")) {
            return ModelCatalogProvider.FAST_TARGET_MODEL_ID;
        }
        return ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID;
    }

    private static String providerId(String modelId, String providerPrefix) {
        String normalized = modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
        if (providerPrefix == null || normalized.startsWith(providerPrefix + "/")) {
            return normalized;
        }
        return providerPrefix + "/" + normalized;
    }

    private static JsonNode loadCatalog() {
        try (InputStream input = new ClassPathResource(BUNDLED_CATALOG).getInputStream()) {
            JsonNode root = JacksonConfig.newMapper().readTree(input);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Bundled model price catalog must be a JSON object");
            }
            return root;
        } catch (IOException | JacksonException error) {
            throw new IllegalStateException("Could not load bundled model price catalog", error);
        }
    }
}
