package tech.starryapi.apikey;

import tech.starryapi.platform.catalog.PlatformModelCatalogSupport;

final class ApiKeyMapper {

    private ApiKeyMapper() {
    }

    static ApiKeyDtos.ApiKeyResponse toResponse(ApiKeyEntity entity) {
        String modelProvider = PlatformModelCatalogSupport.canonicalCatalogProvider(entity.getModelProvider());
        if (modelProvider == null || modelProvider.isBlank()) {
            modelProvider = entity.getModelProvider();
        }
        return new ApiKeyDtos.ApiKeyResponse(
            entity.getKeyId(),
            entity.getUserId(),
            entity.getName(),
            entity.getKeyValue(),
            entity.getKeyPrefix(),
            entity.getEnabled(),
            entity.getCreditsLimit(),
            modelProvider,
            entity.getBillingSource(),
            entity.getTotalRequests(),
            entity.getTotalCredits(),
            entity.getTotalInputTokens(),
            entity.getTotalOutputTokens(),
            entity.getLastUsedAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            // Left out for the key that has none, so a deployment that never used the preference lists its keys as before.
            tierPreference(entity.getTierPreference())
        );
    }

    /** The preference as the responses give it: left out for a key that has none. */
    static String tierPreference(String stored) {
        return stored == null || stored.isBlank() || "auto".equals(stored) ? null : stored;
    }
}
