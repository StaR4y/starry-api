package xyz.star4y.api.apikey;

final class ApiKeyMapper {

    private ApiKeyMapper() {
    }

    static ApiKeyDtos.ApiKeyResponse toResponse(ApiKeyEntity entity) {
        return new ApiKeyDtos.ApiKeyResponse(
            entity.getKeyId(),
            entity.getName(),
            entity.getKeyPrefix(),
            entity.getEnabled(),
            entity.getCreditsLimit(),
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
