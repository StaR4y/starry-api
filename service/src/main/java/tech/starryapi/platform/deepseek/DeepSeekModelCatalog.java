package tech.starryapi.platform.deepseek;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos.ModelSpec;
import tech.starryapi.platform.catalog.ModelPriceRepoModelIds;

/** Official DeepSeek API models and their default per-million-token prices. */
public final class DeepSeekModelCatalog {

    public static final String FLASH_MODEL_ID = "deepseek-v4-flash";
    public static final String PRO_MODEL_ID = "deepseek-v4-pro";
    public static final String DEFAULT_MODEL_ID = FLASH_MODEL_ID;

    private static final int INPUT_TOKENS = 1_000_000;
    private static final int OUTPUT_TOKENS = 384_000;

    private static final DefaultPricing FLASH_PRICING = new DefaultPricing(
        new BigDecimal("0.14"),
        new BigDecimal("0.28"),
        new BigDecimal("0.0028")
    );
    private static final DefaultPricing PRO_PRICING = new DefaultPricing(
        new BigDecimal("0.435"),
        new BigDecimal("0.87"),
        new BigDecimal("0.003625")
    );

    private static final List<ModelSpec> MODELS = buildModels();

    private DeepSeekModelCatalog() {
    }

    public static List<ModelSpec> models() {
        return MODELS;
    }

    public static Optional<DefaultPricing> defaultPricing(String modelId) {
        return switch (canonicalModelId(modelId)) {
            case FLASH_MODEL_ID -> Optional.of(FLASH_PRICING);
            case PRO_MODEL_ID -> Optional.of(PRO_PRICING);
            default -> Optional.empty();
        };
    }

    public static boolean supportsResponses(String modelId) {
        return FLASH_MODEL_ID.equals(canonicalModelId(modelId));
    }

    public static String canonicalModelId(String modelId) {
        if (modelId == null) {
            return "";
        }
        String normalized = modelId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("starry/")) {
            normalized = normalized.substring("starry/".length());
        }
        if (normalized.startsWith("openai-compatible/")) {
            normalized = normalized.substring("openai-compatible/".length());
        }
        if (normalized.startsWith("deepseek/")) {
            normalized = normalized.substring("deepseek/".length());
        }
        return normalized;
    }

    private static List<ModelSpec> buildModels() {
        Map<String, ModelSpec> models = new LinkedHashMap<>();
        ModelPriceRepoModelIds.DEEPSEEK.forEach(id -> models.put(id, model(
            id,
            displayName(id),
            description(id)
        )));
        return List.copyOf(models.values());
    }

    private static String displayName(String id) {
        return switch (id) {
            case FLASH_MODEL_ID -> "DeepSeek V4 Flash";
            case PRO_MODEL_ID -> "DeepSeek V4 Pro";
            case "deepseek-chat" -> "DeepSeek Chat";
            case "deepseek-reasoner" -> "DeepSeek Reasoner";
            default -> id;
        };
    }

    private static String description(String id) {
        return switch (id) {
            case FLASH_MODEL_ID -> "DeepSeek-V4-Flash with thinking and non-thinking modes";
            case PRO_MODEL_ID -> "DeepSeek-V4-Pro with thinking enabled by default";
            default -> "Official DeepSeek chat model from model-price-repo";
        };
    }

    private static ModelSpec model(String id, String name, String description) {
        return new ModelSpec(
            id,
            name,
            "DeepSeek",
            id.startsWith("deepseek-v4-") ? "DeepSeek V4" : "DeepSeek",
            ModelCatalogProvider.STARRY_OWNER,
            description,
            ModelCatalogProvider.TEXT_ONLY,
            INPUT_TOKENS,
            OUTPUT_TOKENS,
            id
        );
    }

    public record DefaultPricing(
        BigDecimal inputPricePerMillionTokens,
        BigDecimal outputPricePerMillionTokens,
        BigDecimal cacheReadPricePerMillionTokens
    ) {
        public BigDecimal cacheWritePricePerMillionTokens() {
            return inputPricePerMillionTokens;
        }
    }
}
