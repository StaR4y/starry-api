package tech.starryapi.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface ModelCatalogProvider {

    String DEFAULT_TARGET_MODEL_ID = "claude-sonnet-4.5";
    String FAST_TARGET_MODEL_ID = "claude-haiku-4.5";
    String STARRY_OWNER = "starryapi";
    String UPSTREAM_OWNER = "kiro-api";
    int DEFAULT_INPUT_TOKENS = 200_000;
    int DEFAULT_OUTPUT_TOKENS = 64_000;
    List<String> TEXT_AND_IMAGE = List.of("TEXT", "IMAGE");
    List<String> TEXT_ONLY = List.of("TEXT");

    List<ModelDtos.ModelSpec> models();

    Optional<String> targetFor(String modelId);

    default String defaultTargetModelId() {
        return DEFAULT_TARGET_MODEL_ID;
    }

    default String fastTargetModelId() {
        return FAST_TARGET_MODEL_ID;
    }

    default String resolveTargetModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return defaultTargetModelId();
        }
        String trimmed = modelId.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        Optional<String> mapped = targetFor(lower);
        if (mapped.isPresent()) {
            return mapped.get();
        }
        if (lower.startsWith("openai/") || lower.startsWith("google/") || lower.startsWith("anthropic/")) {
            return resolvePrefixedOpenRouterTarget(lower);
        }
        if (lower.startsWith("claude") || lower.startsWith("gpt") || lower.startsWith("gemini") || lower.equals("simple-task")) {
            return lower.startsWith("gpt") || lower.startsWith("gemini") ? defaultTargetModelId() : trimmed;
        }
        if (lower.matches("o\\d.*")) {
            return defaultTargetModelId();
        }
        return trimmed;
    }

    private String resolvePrefixedOpenRouterTarget(String modelId) {
        String local = modelId.substring(modelId.indexOf('/') + 1);
        if (modelId.startsWith("anthropic/")) {
            return local.contains("haiku") ? fastTargetModelId() : defaultTargetModelId();
        }
        if (local.contains("-mini")
            || local.contains("-nano")
            || local.contains("flash")
            || local.contains("lite")
            || local.contains("gemma")
            || local.contains("oss-20b")) {
            return fastTargetModelId();
        }
        return defaultTargetModelId();
    }

    default ModelDtos.ModelListResponse modelList(long created) {
        List<ModelDtos.ModelListItem> items = models().stream()
            .map(spec -> modelListItem(spec, created))
            .toList();
        return new ModelDtos.ModelListResponse(
            "list",
            items,
            items
        );
    }

    private ModelDtos.ModelListItem modelListItem(ModelDtos.ModelSpec spec, long created) {
        return new ModelDtos.ModelListItem(
            spec.id(),
            "model",
            created,
            spec.ownedBy(),
            spec.provider(),
            spec.family(),
            spec.targetModelId(),
            spec.name(),
            spec.name(),
            spec.description(),
            spec.supportedInputTypes(),
            spec.maxInputTokens(),
            spec.maxOutputTokens(),
            spec.maxInputTokens(),
            spec.maxOutputTokens(),
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
