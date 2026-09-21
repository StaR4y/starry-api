package tech.starryapi.stats;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class StatsService {

    private final RequestLogRepository repository;

    public StatsService(RequestLogRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> log(RequestLog event) {
        RequestLogEntity entity = new RequestLogEntity();
        entity.setRequestId(event.requestId());
        entity.setApiKeyId(event.apiKeyId());
        entity.setUserId(event.userId());
        entity.setAccountId(event.accountId());
        entity.setPath(event.path());
        entity.setModel(event.model());
        entity.setStatusCode(event.statusCode());
        entity.setSuccess(event.success());
        entity.setInputTokens(event.inputTokens());
        entity.setOutputTokens(event.outputTokens());
        entity.setCredits(event.credits() == null ? BigDecimal.ZERO : event.credits());
        entity.setLatencyMs(event.latencyMs());
        entity.setErrorMessage(sanitize(event.errorMessage()));
        entity.setCircuitKey(event.circuitKey());
        entity.setCircuitTier(event.circuitTier());
        entity.setUpstreamModel(event.upstreamModel());
        entity.setUpstreamSuccess(event.upstreamSuccess());
        entity.setCircuitEpoch(event.circuitEpoch());
        return repository.save(entity).then();
    }

    private String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return message
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9\\-_.~+/]+=*", "Bearer ***")
            .replaceAll("(?i)(access[_-]?token[\\\"':=\\s]+)[^\\s,}]+", "$1***");
    }

    public record RequestLog(
        String requestId,
        String apiKeyId,
        String userId,
        String accountId,
        String path,
        String model,
        int statusCode,
        boolean success,
        long inputTokens,
        long outputTokens,
        BigDecimal credits,
        long latencyMs,
        String errorMessage,
        String circuitKey,
        String circuitTier,
        String upstreamModel,
        Boolean upstreamSuccess,
        UUID circuitEpoch
    ) {
        public RequestLog(
            String requestId,
            String apiKeyId,
            String userId,
            String accountId,
            String path,
            String model,
            int statusCode,
            boolean success,
            long inputTokens,
            long outputTokens,
            BigDecimal credits,
            long latencyMs,
            String errorMessage
        ) {
            this(
                requestId,
                apiKeyId,
                userId,
                accountId,
                path,
                model,
                statusCode,
                success,
                inputTokens,
                outputTokens,
                credits,
                latencyMs,
                errorMessage,
                null,
                null,
                null,
                null,
                null
            );
        }
    }
}
