package tech.starryapi.platform.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import tech.starryapi.model.ModelDtos.ModelSpec;

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
            aliasIds.stream().flatMap(id -> aliasIdsFor(id, profile).stream().map(aliasId ->
                compatibleAlias(
                    aliasId,
                    localModelId(aliasId),
                    family.apply(aliasId),
                    targetModelId.apply(aliasId),
                    profile
                )
            ))
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

    public static String canonicalCatalogProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String trimmed = provider.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if ("gemini".equalsIgnoreCase(trimmed)) {
            return "Google";
        }
        return trimmed;
    }

    public static boolean sameCatalogProvider(String left, String right) {
        String canonicalLeft = canonicalCatalogProvider(left);
        String canonicalRight = canonicalCatalogProvider(right);
        if (canonicalLeft == null || canonicalRight == null) {
            return canonicalLeft == null && canonicalRight == null;
        }
        return canonicalLeft.equalsIgnoreCase(canonicalRight);
    }

    public static String googleFamily(String id) {
        String local = localModelId(id);
        String geminiVersion = numberedGeminiFamily(local);
        if (geminiVersion != null) {
            return geminiVersion;
        }
        return switch (local) {
            case String model when model.startsWith("gemma-4") -> "Gemma 4";
            case String model when model.startsWith("gemma-3") -> "Gemma 3";
            case String model when model.startsWith("gemma-2") -> "Gemma 2";
            case String model when model.startsWith("lyria-") -> "Lyria";
            default -> "Google";
        };
    }

    private static String numberedGeminiFamily(String local) {
        if (local == null || !local.startsWith("gemini-")) {
            return null;
        }
        String rest = local.substring("gemini-".length());
        int end = 0;
        while (end < rest.length()) {
            char next = rest.charAt(end);
            if (next != '.' && (next < '0' || next > '9')) {
                break;
            }
            end++;
        }
        if (end == 0) {
            return null;
        }
        String version = rest.substring(0, end);
        if (version.endsWith(".")) {
            version = version.substring(0, version.length() - 1);
        }
        return version.isEmpty() ? null : "Gemini " + version;
    }

    private static List<String> aliasIdsFor(String id, AliasProfile profile) {
        String local = localModelId(id);
        String prefixed = canonicalProviderId(id, profile);
        if (prefixed == null || prefixed.isBlank() || prefixed.equals(local)) {
            return List.of(local);
        }
        return List.of(local, prefixed);
    }

    private static String canonicalProviderId(String id, AliasProfile profile) {
        if (id == null || id.isBlank() || id.contains("/")) {
            return id;
        }
        return providerPrefix(profile.provider()) + "/" + id;
    }

    private static String providerPrefix(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "gemini", "google" -> "google";
            case "anthropic", "claude" -> "anthropic";
            default -> "openai";
        };
    }
}
