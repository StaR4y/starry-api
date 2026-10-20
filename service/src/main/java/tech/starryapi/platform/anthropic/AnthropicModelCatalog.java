package tech.starryapi.platform.anthropic;

import java.util.List;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos.ModelSpec;
import tech.starryapi.platform.catalog.ModelPriceRepoModelIds;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport.AliasProfile;

import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.appendAliases;
import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.compatibleAlias;
import static tech.starryapi.platform.catalog.PlatformModelCatalogSupport.localModelId;

public final class AnthropicModelCatalog {

    public static final String DEFAULT_MODEL_ID = ModelCatalogProvider.DEFAULT_TARGET_MODEL_ID;
    public static final String HAIKU_MODEL_ID = ModelCatalogProvider.FAST_TARGET_MODEL_ID;

    public static final int DEFAULT_INPUT_TOKENS = ModelCatalogProvider.DEFAULT_INPUT_TOKENS;
    public static final int DEFAULT_OUTPUT_TOKENS = ModelCatalogProvider.DEFAULT_OUTPUT_TOKENS;
    public static final List<String> TEXT_AND_IMAGE = ModelCatalogProvider.TEXT_AND_IMAGE;
    public static final List<String> TEXT_ONLY = ModelCatalogProvider.TEXT_ONLY;

    private static final AliasProfile ALIAS_PROFILE = new AliasProfile(
        "Anthropic",
        ModelCatalogProvider.STARRY_OWNER,
        "Anthropic-compatible alias for the unified Starry API routing layer",
        TEXT_AND_IMAGE,
        DEFAULT_INPUT_TOKENS,
        DEFAULT_OUTPUT_TOKENS
    );
    private static final List<ModelSpec> UPSTREAM_MODELS = List.of(
        model("auto", "Auto", "Routing Preset", "Auto select best model", "auto"),
        model(DEFAULT_MODEL_ID, "Claude Sonnet 4.5", "Claude 4", "The latest Claude Sonnet model", DEFAULT_MODEL_ID),
        model("claude-sonnet-4", "Claude Sonnet 4", "Claude 4", "Hybrid reasoning and coding", "claude-sonnet-4"),
        model("claude-4-sonnet", "Claude Sonnet 4", "Claude 4", "Claude 4 Sonnet compatible alias", "claude-sonnet-4"),
        model("claude-4-sonnet-20250514", "Claude Sonnet 4", "Claude 4", "Claude 4 Sonnet dated alias", "claude-sonnet-4"),
        model("claude-sonnet-4-20250514", "Claude Sonnet 4", "Claude 4", "Claude Sonnet 4 dated alias", "claude-sonnet-4"),
        model("claude-sonnet-4.6", "Claude Sonnet 4.6", "Claude 4", "Claude Sonnet 4.6 compatible alias", DEFAULT_MODEL_ID),
        model("claude-sonnet-4-6", "Claude Sonnet 4.6", "Claude 4", "Claude Sonnet 4.6 compatible alias", DEFAULT_MODEL_ID),
        model("claude-sonnet-5", "Claude Sonnet 5", "Claude 5", "Claude Sonnet 5 compatible alias", DEFAULT_MODEL_ID),
        model("claude-sonnet-latest", "Claude Sonnet Latest", "Claude 4", "Latest Claude Sonnet compatible alias", DEFAULT_MODEL_ID),
        model("sonnet", "Claude Sonnet", "Claude 4", "Claude Code sonnet alias", DEFAULT_MODEL_ID),
        model(HAIKU_MODEL_ID, "Claude Haiku 4.5", "Claude 4", "The latest Claude Haiku model", HAIKU_MODEL_ID),
        model("claude-haiku-4-5-20251001", "Claude Haiku 4.5", "Claude 4", "Claude Haiku dated compatible alias", HAIKU_MODEL_ID),
        model("claude-haiku-latest", "Claude Haiku Latest", "Claude 4", "Latest Claude Haiku compatible alias", HAIKU_MODEL_ID),
        model("haiku", "Claude Haiku", "Claude 4", "Claude Code haiku alias", HAIKU_MODEL_ID),
        model("claude-opus-4", "Claude Opus 4", "Claude 4", "Claude Opus 4 compatible alias", "claude-opus-4.5"),
        model("claude-4-opus", "Claude Opus 4", "Claude 4", "Claude 4 Opus compatible alias", "claude-opus-4.5"),
        model("claude-4-opus-20250514", "Claude Opus 4", "Claude 4", "Claude 4 Opus dated alias", "claude-opus-4.5"),
        model("claude-opus-4-20250514", "Claude Opus 4", "Claude 4", "Claude Opus 4 dated alias", "claude-opus-4.5"),
        model("claude-opus-4.1", "Claude Opus 4.1", "Claude 4", "Claude Opus 4.1 compatible alias", "claude-opus-4.5"),
        model("claude-opus-4-1", "Claude Opus 4.1", "Claude 4", "Claude Opus 4.1 compatible alias", "claude-opus-4.5"),
        model("claude-opus-4.5", "Claude Opus 4.5", "Claude 4", "The most powerful model", "claude-opus-4.5"),
        model("claude-opus-4.6", "Claude Opus 4.6", "Claude 4", "Claude Opus 4.6 compatible alias", "claude-opus-4.5"),
        model("claude-opus-4-6", "Claude Opus 4.6", "Claude 4", "Claude Opus 4.6 compatible alias", "claude-opus-4.5"),
        model("claude-opus-4.7", "Claude Opus 4.7", "Claude 4", "Claude Opus 4.7 compatible alias", "claude-opus-4.5"),
        model("claude-opus-4-7", "Claude Opus 4.7", "Claude 4", "Claude Opus 4.7 compatible alias", "claude-opus-4.5"),
        model("claude-opus-4.8", "Claude Opus 4.8", "Claude 4", "Claude Opus 4.8 compatible alias", "claude-opus-4.5"),
        model("claude-opus-4-8", "Claude Opus 4.8", "Claude 4", "Claude Opus 4.8 compatible alias", "claude-opus-4.5"),
        model("claude-opus-5", "Claude Opus 5", "Claude 5", "Claude Opus 5 compatible alias", "claude-opus-4.5"),
        model("claude-opus-latest", "Claude Opus Latest", "Claude 4", "Latest Claude Opus compatible alias", DEFAULT_MODEL_ID),
        model("opus", "Claude Opus", "Claude 4", "Claude Code opus alias", DEFAULT_MODEL_ID),
        model("claude-3-opus", "Claude 3 Opus", "Claude 3", "Claude 3 Opus compatible alias", DEFAULT_MODEL_ID),
        model("claude-3-sonnet", "Claude 3 Sonnet", "Claude 3", "Claude 3 Sonnet compatible alias", DEFAULT_MODEL_ID),
        model("claude-3-haiku", "Claude 3 Haiku", "Claude 3", "Claude 3 Haiku compatible alias", HAIKU_MODEL_ID),
        model("claude-3.5-sonnet", "Claude 3.5 Sonnet", "Claude 3", "Claude 3.5 Sonnet compatible alias", DEFAULT_MODEL_ID),
        model("claude-3.5-sonnet-latest", "Claude 3.5 Sonnet Latest", "Claude 3", "Claude 3.5 Sonnet compatible alias", DEFAULT_MODEL_ID),
        model("claude-3.5-haiku", "Claude 3.5 Haiku", "Claude 3", "Claude 3.5 Haiku compatible alias", HAIKU_MODEL_ID),
        model("claude-3.7-sonnet", "Claude 3.7 Sonnet", "Claude 3", "Claude 3.7 Sonnet (hidden)", "claude-3.7-sonnet"),
        model("claude-3.7-sonnet-latest", "Claude 3.7 Sonnet Latest", "Claude 3", "Claude 3.7 Sonnet compatible alias", DEFAULT_MODEL_ID),
        new ModelSpec(
            "simple-task",
            "Simple Task",
            "Anthropic",
            "Routing Preset",
            ModelCatalogProvider.ANTHROPIC_OWNER,
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
        return appendAliases(
            UPSTREAM_MODELS,
            ModelPriceRepoModelIds.ANTHROPIC,
            ALIAS_PROFILE,
            AnthropicModelCatalog::family,
            AnthropicModelCatalog::targetModelId
        );
    }

    private static String family(String id) {
        String model = localModelId(id).toLowerCase();
        if (model.contains("claude-3") || model.contains("3.")) {
            return "Claude 3";
        }
        if (model.contains("claude-5")
            || model.contains("fable-5")
            || model.contains("mythos-5")
            || (model.endsWith("-5") && !model.contains("4-5") && !model.contains("4.5"))) {
            return "Claude 5";
        }
        return "Claude 4";
    }

    private static String targetModelId(String id) {
        return switch (localModelId(id).toLowerCase()) {
            case String model when HAIKU_MODEL_ID.equals(model) || model.contains("haiku") -> HAIKU_MODEL_ID;
            case "claude-sonnet-4", "claude-4-sonnet", "claude-4-sonnet-20250514", "claude-sonnet-4-20250514" ->
                "claude-sonnet-4";
            case String model when model.contains("opus") && !model.contains("3") -> "claude-opus-4.5";
            default -> DEFAULT_MODEL_ID;
        };
    }

    private static ModelSpec alias(String id, String name, String family, String targetModelId) {
        return compatibleAlias(id, name, family, targetModelId, ALIAS_PROFILE);
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
            ModelCatalogProvider.ANTHROPIC_OWNER,
            description,
            TEXT_AND_IMAGE,
            DEFAULT_INPUT_TOKENS,
            DEFAULT_OUTPUT_TOKENS,
            targetModelId
        );
    }
}
