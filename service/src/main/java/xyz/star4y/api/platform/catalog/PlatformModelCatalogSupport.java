package xyz.star4y.api.platform.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import xyz.star4y.api.model.ModelDtos.ModelSpec;

public final class PlatformModelCatalogSupport {

    private PlatformModelCatalogSupport() {
    }

    public record AliasProfile(
        String provider,
        String owner,
        String description,
        List<String> supportedInputTypes,
        int maxInputTokens,
        int maxOutputTokens
    ) {
        public AliasProfile {
            supportedInputTypes = supportedInputTypes == null ? List.of() : List.copyOf(supportedInputTypes);
        }
    }

    public static List<ModelSpec> appendAliases(
        List<ModelSpec> baseModels,
        List<String> aliasIds,
        AliasProfile profile,
        Function<String, String> family,
        Function<String, String> targetModelId
    ) {
        Map<String, ModelSpec> modelsById = new LinkedHashMap<>();
        Stream.concat(
            baseModels.stream(),
            aliasIds.stream().map(id -> {
                String aliasId = canonicalOpenRouterId(id, profile);
                return compatibleAlias(
                    aliasId,
                    "OpenRouter " + localModelId(aliasId),
                    family.apply(aliasId),
                    targetModelId.apply(aliasId),
                    profile
                );
            })
        ).forEach(model -> modelsById.putIfAbsent(model.id().toLowerCase(Locale.ROOT), model));
        return List.copyOf(modelsById.values());
    }

    public static ModelSpec compatibleAlias(
        String id,
        String name,
        String family,
        String targetModelId,
        AliasProfile profile
    ) {
        return new ModelSpec(
            id,
            name + " compatible alias",
            profile.provider(),
            family,
            profile.owner(),
            profile.description(),
            profile.supportedInputTypes(),
            profile.maxInputTokens(),
            profile.maxOutputTokens(),
            targetModelId
        );
    }

    public static String localModelId(String id) {
        int slash = id.indexOf('/');
        return switch (slash) {
            case -1 -> id;
            default -> id.substring(slash + 1);
        };
    }

    private static String canonicalOpenRouterId(String id, AliasProfile profile) {
        if (id == null || id.isBlank() || id.contains("/")) {
            return id;
        }
        return openRouterPrefix(profile.provider()) + "/" + id;
    }

    private static String openRouterPrefix(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "gemini", "google" -> "google";
            case "anthropic", "claude" -> "anthropic";
            default -> "openai";
        };
    }
}
