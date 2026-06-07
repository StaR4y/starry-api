package xyz.star4y.api.platform.openai;

import java.util.ArrayList;
import java.util.List;
import xyz.star4y.api.model.ModelCatalogProvider;
import xyz.star4y.api.model.ModelDtos.ModelSpec;
import xyz.star4y.api.platform.catalog.OpenRouterModelIds;

public final class OpenAiModelCatalog {

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
        List<ModelSpec> models = new ArrayList<>(LEGACY_ALIASES);
        models.addAll(OpenRouterModelIds.OPENAI.stream()
            .map(id -> alias(id, displayName(id), family(id), targetModelId(id)))
            .toList());
        return List.copyOf(models);
    }

    private static String displayName(String id) {
        return "OpenRouter " + localModelId(id);
    }

    private static String family(String id) {
        String model = localModelId(id);
        if (model.startsWith("gpt-5")) {
            return "GPT-5";
        }
        if (model.startsWith("gpt-4") || model.startsWith("chatgpt-4")) {
            return "GPT-4";
        }
        if (model.startsWith("gpt-3.5")) {
            return "GPT-3.5";
        }
        if (model.matches("o\\d.*")) {
            return "OpenAI Reasoning";
        }
        if (model.startsWith("gpt-oss")) {
            return "OpenAI OSS";
        }
        if (model.contains("audio")) {
            return "OpenAI Audio";
        }
        if (model.contains("image")) {
            return "OpenAI Image";
        }
        return "OpenAI";
    }

    private static String targetModelId(String id) {
        String model = localModelId(id);
        if (model.contains("-mini") || model.contains("-nano") || model.contains("oss-20b")) {
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
            "OpenAI",
            family,
            ModelCatalogProvider.STARRY_OWNER,
            "OpenAI-compatible alias for the unified Starry API routing layer",
            ModelCatalogProvider.TEXT_AND_IMAGE,
            ModelCatalogProvider.DEFAULT_INPUT_TOKENS,
            ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS,
            targetModelId
        );
    }
}
