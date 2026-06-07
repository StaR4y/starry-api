package xyz.star4y.api.platform.gemini;

import java.util.ArrayList;
import java.util.List;
import xyz.star4y.api.model.ModelCatalogProvider;
import xyz.star4y.api.model.ModelDtos.ModelSpec;
import xyz.star4y.api.platform.catalog.OpenRouterModelIds;

public final class GeminiModelCatalog {

    private static final List<ModelSpec> LEGACY_ALIASES = List.of(
        alias("gemini-2.5-pro", "Gemini 2.5 Pro", "Gemini 2.5", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gemini-2.5-flash", "Gemini 2.5 Flash", "Gemini 2.5", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Gemini 2.5", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("gemini-2.0-flash", "Gemini 2.0 Flash", "Gemini 2.0", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("gemini-1.5-pro", "Gemini 1.5 Pro", "Gemini 1.5", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gemini-1.5-flash", "Gemini 1.5 Flash", "Gemini 1.5", ModelCatalogProvider.FAST_TARGET_MODEL_ID)
    );
    private static final List<ModelSpec> MODELS = buildModels();

    private GeminiModelCatalog() {
    }

    public static List<ModelSpec> models() {
        return MODELS;
    }

    private static List<ModelSpec> buildModels() {
        List<ModelSpec> models = new ArrayList<>(LEGACY_ALIASES);
        models.addAll(OpenRouterModelIds.GOOGLE.stream()
            .map(id -> alias(id, displayName(id), family(id), targetModelId(id)))
            .toList());
        return List.copyOf(models);
    }

    private static String displayName(String id) {
        return "OpenRouter " + localModelId(id);
    }

    private static String family(String id) {
        String model = localModelId(id);
        if (model.startsWith("gemini-3.5")) {
            return "Gemini 3.5";
        }
        if (model.startsWith("gemini-3.1")) {
            return "Gemini 3.1";
        }
        if (model.startsWith("gemini-3")) {
            return "Gemini 3";
        }
        if (model.startsWith("gemini-2.5")) {
            return "Gemini 2.5";
        }
        if (model.startsWith("gemma-4")) {
            return "Gemma 4";
        }
        if (model.startsWith("gemma-3")) {
            return "Gemma 3";
        }
        if (model.startsWith("gemma-2")) {
            return "Gemma 2";
        }
        if (model.startsWith("lyria-")) {
            return "Lyria";
        }
        return "Google";
    }

    private static String targetModelId(String id) {
        String model = localModelId(id);
        if (model.contains("flash") || model.contains("lite") || model.contains("gemma")) {
            return ModelCatalogProvider.FAST_TARGET_MODEL_ID;
        }
        return ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID;
    }

    private static String localModelId(String id) {
        int slash = id.indexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static ModelSpec alias(String id, String name, String family, String targetModelId) {
        return new ModelSpec(
            id,
            name + " compatible alias",
            "Gemini",
            family,
            ModelCatalogProvider.STARRY_OWNER,
            "Gemini-compatible alias for the unified Starry API routing layer",
            ModelCatalogProvider.TEXT_AND_IMAGE,
            ModelCatalogProvider.DEFAULT_INPUT_TOKENS,
            ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS,
            targetModelId
        );
    }
}
