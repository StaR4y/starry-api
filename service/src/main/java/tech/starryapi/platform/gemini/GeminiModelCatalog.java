package tech.starryapi.platform.gemini;

import java.util.List;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos.ModelSpec;
import tech.starryapi.platform.catalog.ModelPriceRepoModelIds;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport.AliasProfile;

import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.appendAliases;
import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.compatibleAlias;
import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.googleFamily;
import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.localModelId;

public final class GeminiModelCatalog {

    private static final AliasProfile ALIAS_PROFILE = new AliasProfile(
        "Google",
        ModelCatalogProvider.STARRY_OWNER,
        "Google model alias for the unified Starry API routing layer",
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
            ModelPriceRepoModelIds.GOOGLE,
            ALIAS_PROFILE,
            GeminiModelCatalog::family,
            GeminiModelCatalog::targetModelId
        );
    }

    private static String family(String id) {
        return googleFamily(id);
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
