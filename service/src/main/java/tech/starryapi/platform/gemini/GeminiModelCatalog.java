package tech.starryapi.platform.gemini;

import java.util.List;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos.ModelSpec;
import tech.starryapi.platform.catalog.OpenRouterModelIds;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport.AliasProfile;

import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.appendAliases;
import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.compatibleAlias;
import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.localModelId;

public final class GeminiModelCatalog {

    private static final AliasProfile ALIAS_PROFILE = new AliasProfile(
        "Gemini",
        ModelCatalogProvider.STARRY_OWNER,
        "Gemini-compatible alias for the unified Starry API routing layer",
        ModelCatalogProvider.TEXT_AND_IMAGE,
        ModelCatalogProvider.DEFAULT_INPUT_TOKENS,
        ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS
    );
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
        return appendAliases(
            LEGACY_ALIASES,
            OpenRouterModelIds.GOOGLE,
            ALIAS_PROFILE,
            GeminiModelCatalog::family,
            GeminiModelCatalog::targetModelId
        );
    }

    private static String family(String id) {
        return switch (localModelId(id)) {
            case String model when model.startsWith("gemini-3.5") -> "Gemini 3.5";
            case String model when model.startsWith("gemini-3.1") -> "Gemini 3.1";
            case String model when model.startsWith("gemini-3") -> "Gemini 3";
            case String model when model.startsWith("gemini-2.5") -> "Gemini 2.5";
            case String model when model.startsWith("gemma-4") -> "Gemma 4";
            case String model when model.startsWith("gemma-3") -> "Gemma 3";
            case String model when model.startsWith("gemma-2") -> "Gemma 2";
            case String model when model.startsWith("lyria-") -> "Lyria";
            default -> "Google";
        };
    }

    private static String targetModelId(String id) {
        return switch (localModelId(id)) {
            case String model when model.contains("flash") || model.contains("lite") || model.contains("gemma") ->
                ModelCatalogProvider.FAST_TARGET_MODEL_ID;
            default -> ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID;
        };
    }

    private static ModelSpec alias(String id, String name, String family, String targetModelId) {
        return compatibleAlias(id, name, family, targetModelId, ALIAS_PROFILE);
    }
}
