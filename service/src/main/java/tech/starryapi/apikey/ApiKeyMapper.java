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
            entity.getUpdatedAt()
        );
    }
}
