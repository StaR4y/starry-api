package xyz.star4y.api.platform.anthropic;

import java.util.ArrayList;
import java.util.List;
import xyz.star4y.api.model.ModelCatalogProvider;
import xyz.star4y.api.model.ModelDtos.ModelSpec;
import xyz.star4y.api.platform.catalog.OpenRouterModelIds;

public final class AnthropicModelCatalog {

    public static final String DEFAULT_MODEL_ID = ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID;
    public static final String HAIKU_MODEL_ID = ModelCatalogProvider.FAST_TARGET_MODEL_ID;

    public static final int DEFAULT_INPUT_TOKENS = ModelCatalogProvider.DEFAULT_INPUT_TOKENS;
    public static final int DEFAULT_OUTPUT_TOKENS = ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS;
    public static final List<String> TEXT_AND_IMAGE = ModelCatalogProvider.TEXT_AND_IMAGE;
    public static final List<String> TEXT_ONLY = ModelCatalogProvider.TEXT_ONLY;

    private static final List<ModelSpec> UPSTREAM_MODELS = List.of(
        model("auto", "Auto", "Routing Preset", "Auto select best model", "auto"),
        model(DEFAULT_MODEL_ID, "Claude Sonnet 4.5", "Claude 4", "The latest Claude Sonnet model", DEFAULT_MODEL_ID),
        model("claude-sonnet-4", "Claude Sonnet 4", "Claude 4", "Hybrid reasoning and coding", "claude-sonnet-4"),
        model(HAIKU_MODEL_ID, "Claude Haiku 4.5", "Claude 4", "The latest Claude Haiku model", HAIKU_MODEL_ID),
        model("claude-opus-4.5", "Claude Opus 4.5", "Claude 4", "The most powerful model", "claude-opus-4.5"),
        model("claude-3.7-sonnet", "Claude 3.7 Sonnet", "Claude 3", "Claude 3.7 Sonnet (hidden)", "claude-3.7-sonnet"),
        new ModelSpec(
            "simple-task",
            "Simple Task",
            "Anthropic",
            "Routing Preset",
            ModelCatalogProvider.UPSTREAM_OWNER,
            "Fast routing preset for lightweight tasks",
            TEXT_ONLY,
            DEFAULT_INPUT_TOKENS,
            4_096,
            HAIKU_MODEL_ID
        ),
        model(
            "CLAUDE_SONNET_4_20250514_V1_0",
            "Claude Sonnet 4 (CW)",
            "CodeWhisperer Internal",
            "Claude Sonnet 4 (CodeWhisperer internal ID)",
            "CLAUDE_SONNET_4_20250514_V1_0"
        ),
        model(
            "CLAUDE_HAIKU_4_5_20251001_V1_0",
            "Claude Haiku 4.5 (CW)",
            "CodeWhisperer Internal",
            "Claude Haiku 4.5 (CodeWhisperer internal ID)",
            "CLAUDE_HAIKU_4_5_20251001_V1_0"
        ),
        model(
            "CLAUDE_3_7_SONNET_20250219_V1_0",
            "Claude 3.7 Sonnet (CW)",
            "CodeWhisperer Internal",
            "Claude 3.7 Sonnet (CodeWhisperer internal ID)",
            "CLAUDE_3_7_SONNET_20250219_V1_0"
        )
    );
    private static final List<ModelSpec> MODELS = buildModels();

    private AnthropicModelCatalog() {
    }

    public static List<ModelSpec> models() {
        return MODELS;
    }

    private static List<ModelSpec> buildModels() {
        List<ModelSpec> models = new ArrayList<>(UPSTREAM_MODELS);
        models.addAll(OpenRouterModelIds.ANTHROPIC.stream()
            .map(id -> alias(id, displayName(id), family(id), targetModelId(id)))
            .toList());
        return List.copyOf(models);
    }

    private static String displayName(String id) {
        return "OpenRouter " + localModelId(id);
    }

    private static String family(String id) {
        String model = localModelId(id);
        if (model.contains("3.")) {
            return "Claude 3";
        }
        return "Claude 4";
    }

    private static String targetModelId(String id) {
        String model = localModelId(id);
        if (HAIKU_MODEL_ID.equals(model) || model.contains("haiku")) {
            return HAIKU_MODEL_ID;
        }
        if ("claude-sonnet-4".equals(model) || "claude-opus-4.5".equals(model)) {
            return model;
        }
        return DEFAULT_MODEL_ID;
    }

    private static String localModelId(String id) {
        int slash = id.indexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static ModelSpec alias(String id, String name, String family, String targetModelId) {
        return new ModelSpec(
            id,
            name + " compatible alias",
            "Anthropic",
            family,
            ModelCatalogProvider.STARRY_OWNER,
            "Anthropic-compatible alias for the unified Starry API routing layer",
            TEXT_AND_IMAGE,
            DEFAULT_INPUT_TOKENS,
            DEFAULT_OUTPUT_TOKENS,
            targetModelId
        );
    }

    private static ModelSpec model(
        String id,
        String name,
        String family,
        String description,
        String targetModelId
    ) {
        return new ModelSpec(
            id,
            name,
            "Anthropic",
            family,
            ModelCatalogProvider.UPSTREAM_OWNER,
            description,
            TEXT_AND_IMAGE,
            DEFAULT_INPUT_TOKENS,
            DEFAULT_OUTPUT_TOKENS,
            targetModelId
        );
    }
}
