package xyz.star4y.api.platform.catalog;

import java.util.List;
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
        return Stream.concat(
            baseModels.stream(),
            aliasIds.stream().map(id -> compatibleAlias(
                id,
                "OpenRouter " + localModelId(id),
                family.apply(id),
                targetModelId.apply(id),
                profile
            ))
        ).toList();
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
}
