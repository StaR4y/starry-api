package tech.starryapi.stats;

final class RequestLogMapper {

    private RequestLogMapper() {
    }

    static StatsDtos.RequestLogResponse toResponse(RequestLogEntity entity) {
        return new StatsDtos.RequestLogResponse(
            entity.getRequestId(),
            entity.getApiKeyId(),
            entity.getAccountId(),
            entity.getPath(),
            entity.getModel(),
            entity.getStatusCode(),
            entity.getSuccess(),
            entity.getInputTokens(),
            entity.getOutputTokens(),
            entity.getCredits(),
            entity.getLatencyMs(),
            entity.getErrorMessage(),
            entity.getCreatedAt()
        );
    }
}
