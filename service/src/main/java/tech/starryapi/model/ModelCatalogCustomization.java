package tech.starryapi.model;

public record ModelCatalogCustomization(
    String id,
    String name,
    String icon,
    String provider,
    String family,
    String targetModelId,
    String requestFormat,
    String endpoint,
    Integer contextWindow,
    String description,
    Boolean enabled,
    String badgeColor
) {
    ModelCatalogCustomization(
        String id,
        String name,
        String icon,
        String provider,
        String family,
        String targetModelId,
        String requestFormat,
        String endpoint,
        Integer contextWindow,
        String description,
        Boolean enabled
    ) {
        this(
            id,
            name,
            icon,
            provider,
            family,
            targetModelId,
            requestFormat,
            endpoint,
            contextWindow,
            description,
            enabled,
            null
        );
    }

    ModelCatalogCustomization withBadgeColor(String badgeColor) {
        return new ModelCatalogCustomization(
            id,
            name,
            icon,
            provider,
            family,
            targetModelId,
            requestFormat,
            endpoint,
            contextWindow,
            description,
            enabled,
            badgeColor
        );
    }
}
