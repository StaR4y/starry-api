package xyz.star4y.api.platform.openai;

import java.util.List;
import xyz.star4y.api.model.ModelCatalogProvider;
import xyz.star4y.api.model.ModelDtos.ModelSpec;
import xyz.star4y.api.platform.catalog.OpenRouterModelIds;
import xyz.star4y.api.platform.catalog.PlatformModelCatalogSupport.AliasProfile;

import static xyz.star4y.api.platform.catalog.PlatformModelCatalogSupport.appendAliases;
import static xyz.star4y.api.platform.catalog.PlatformModelCatalogSupport.compatibleAlias;
import static xyz.star4y.api.platform.catalog.PlatformModelCatalogSupport.localModelId;

public final class OpenAiModelCatalog {

    private static final AliasProfile ALIAS_PROFILE = new AliasProfile(
        "OpenAI",
        ModelCatalogProvider.STARRY_OWNER,
        "OpenAI-compatible alias for the unified Starry API routing layer",
        ModelCatalogProvider.TEXT_AND_IMAGE,
        ModelCatalogProvider.DEFAULT_INPUT_TOKENS,
        ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS
    );
    private static final List<ModelSpec> LEGACY_ALIASES = List.of(
        alias("gpt-5.2", "GPT-5.2", "GPT-5", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-5.2-pro", "GPT-5.2 pro", "GPT-5", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-5.1", "GPT-5.1", "GPT-5", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-5.1-chat-latest", "GPT-5.1 Chat", "GPT-5", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-5", "GPT-5", "GPT-5", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-5-mini", "GPT-5 mini", "GPT-5", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("gpt-5-nano", "GPT-5 nano", "GPT-5", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("gpt-4.1", "GPT-4.1", "GPT-4", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-4.1-mini", "GPT-4.1 mini", "GPT-4", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("gpt-4.1-nano", "GPT-4.1 nano", "GPT-4", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("gpt-4o", "GPT-4o", "GPT-4", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-4o-mini", "GPT-4o mini", "GPT-4", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("chatgpt-4o", "ChatGPT-4o", "GPT-4", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-4", "GPT-4", "GPT-4", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-4-turbo", "GPT-4 Turbo", "GPT-4", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("gpt-3.5-turbo", "GPT-3.5 Turbo", "GPT-3.5", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("o3", "o3", "OpenAI Reasoning", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("o3-mini", "o3-mini", "OpenAI Reasoning", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("o3-pro", "o3-pro", "OpenAI Reasoning", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("o4-mini", "o4-mini", "OpenAI Reasoning", ModelCatalogProvider.FAST_TARGET_MODEL_ID),
        alias("o1", "o1", "OpenAI Reasoning", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("o1-pro", "o1-pro", "OpenAI Reasoning", ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID),
        alias("o1-mini", "o1-mini", "OpenAI Reasoning", ModelCatalogProvider.FAST_TARGET_MODEL_ID)
    );
    private static final List<ModelSpec> MODELS = buildModels();

    private OpenAiModelCatalog() {
    }

    public static List<ModelSpec> models() {
        return MODELS;
    }

    private static List<ModelSpec> buildModels() {
        return appendAliases(
            LEGACY_ALIASES,
            OpenRouterModelIds.OPENAI,
            ALIAS_PROFILE,
            OpenAiModelCatalog::family,
            OpenAiModelCatalog::targetModelId
        );
    }

    private static String family(String id) {
        return switch (localModelId(id)) {
            case String model when model.startsWith("gpt-5") -> "GPT-5";
            case String model when model.startsWith("gpt-4") || model.startsWith("chatgpt-4") -> "GPT-4";
            case String model when model.startsWith("gpt-3.5") -> "GPT-3.5";
            case String model when model.matches("o\\d.*") -> "OpenAI Reasoning";
            case String model when model.startsWith("gpt-oss") -> "OpenAI OSS";
            case String model when model.contains("audio") -> "OpenAI Audio";
            case String model when model.contains("image") -> "OpenAI Image";
            default -> "OpenAI";
        };
    }

    private static String targetModelId(String id) {
        return switch (localModelId(id)) {
            case String model when model.contains("-mini") || model.contains("-nano") || model.contains("oss-20b") ->
                ModelCatalogProvider.FAST_TARGET_MODEL_ID;
            default -> ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID;
        };
    }

    private static ModelSpec alias(String id, String name, String family, String targetModelId) {
        return compatibleAlias(id, name, family, targetModelId, ALIAS_PROFILE);
    }
}
