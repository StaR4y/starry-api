package xyz.star4y.api.apikey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.common.Hashing;
import xyz.star4y.api.config.ProxyProperties;

@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "sk-";

    private final ApiKeyRepository repository;
    private final R2dbcEntityTemplate template;
    private final ProxyProperties properties;

    public ApiKeyService(ApiKeyRepository repository, R2dbcEntityTemplate template, ProxyProperties properties) {
        this.repository = repository;
        this.template = template;
        this.properties = properties;
    }

    public Mono<ApiKeyDtos.CreatedApiKeyResponse> create(ApiKeyDtos.CreateApiKeyRequest request) {
        String key = KEY_PREFIX + Hashing.randomToken(32);
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId(Hashing.shortUuid());
        entity.setName(request.name());
        entity.setKeyHash(Hashing.sha256(key));
        entity.setKeyPrefix(key.substring(0, Math.min(18, key.length())));
        entity.setEnabled(true);
        entity.setCreditsLimit(request.creditsLimit());
        entity.setTotalRequests(0L);
        entity.setTotalCredits(BigDecimal.ZERO);
        entity.setTotalInputTokens(0L);
        entity.setTotalOutputTokens(0L);
        return repository.save(entity)
            .map(saved -> new ApiKeyDtos.CreatedApiKeyResponse(
                saved.getKeyId(),
                saved.getName(),
                key,
                saved.getKeyPrefix(),
                saved.getEnabled(),
                saved.getCreditsLimit(),
                saved.getCreatedAt()
            ));
    }

    public Mono<ApiKeyEntity> update(String keyId, ApiKeyDtos.UpdateApiKeyRequest request) {
        return repository.findByKeyId(keyId)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API key not found")))
            .flatMap(entity -> {
                if (request.name() != null) entity.setName(request.name());
                if (request.enabled() != null) entity.setEnabled(request.enabled());
                if (request.creditsLimit() != null) entity.setCreditsLimit(request.creditsLimit());
                return repository.save(entity);
            });
    }

    public Mono<Void> delete(String keyId) {
        return repository.deleteByKeyId(keyId);
    }

    public Mono<ApiKeyPrincipal> authenticate(ServerWebExchange exchange) {
        Optional<String> key = extractKey(exchange);
        if (key.isEmpty()) {
            if (properties.isAllowAnonymousProxy()) {
                return Mono.just(new ApiKeyPrincipal("anonymous", "anonymous", null, BigDecimal.ZERO));
            }
            return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing API key"));
        }
        return repository.findByKeyHash(Hashing.sha256(key.get()))
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid API key")))
            .flatMap(entity -> {
                if (Boolean.FALSE.equals(entity.getEnabled())) {
                    return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "API_KEY_DISABLED", "API key is disabled"));
                }
                if (entity.getCreditsLimit() != null
                    && entity.getTotalCredits() != null
                    && entity.getTotalCredits().compareTo(entity.getCreditsLimit()) >= 0) {
                    return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CREDITS_LIMIT_EXCEEDED", "API key credits limit exceeded"));
                }
                return Mono.just(new ApiKeyPrincipal(
                    entity.getKeyId(),
                    entity.getName(),
                    entity.getCreditsLimit(),
                    entity.getTotalCredits()
                ));
            });
    }

    public Mono<Void> recordUsage(String keyId, BigDecimal credits, long inputTokens, long outputTokens) {
        if ("anonymous".equals(keyId)) {
            return Mono.empty();
        }
        BigDecimal safeCredits = credits == null ? BigDecimal.ZERO : credits;
        String updateKey = """
            UPDATE api_keys
            SET total_requests = total_requests + 1,
                total_credits = total_credits + :credits,
                total_input_tokens = total_input_tokens + :inputTokens,
                total_output_tokens = total_output_tokens + :outputTokens,
                last_used_at = CURRENT_TIMESTAMP(3)
            WHERE key_id = :keyId
            """;
        String upsertDaily = """
            INSERT INTO api_key_usage_daily (key_id, usage_date, requests, credits, input_tokens, output_tokens)
            VALUES (:keyId, :usageDate, 1, :credits, :inputTokens, :outputTokens)
            ON DUPLICATE KEY UPDATE
                requests = requests + 1,
                credits = credits + VALUES(credits),
                input_tokens = input_tokens + VALUES(input_tokens),
                output_tokens = output_tokens + VALUES(output_tokens)
            """;
        Mono<Void> keyUpdate = template.getDatabaseClient().sql(updateKey)
            .bind("credits", safeCredits)
            .bind("inputTokens", inputTokens)
            .bind("outputTokens", outputTokens)
            .bind("keyId", keyId)
            .fetch()
            .rowsUpdated()
            .then();
        Mono<Void> dailyUpdate = template.getDatabaseClient().sql(upsertDaily)
            .bind("keyId", keyId)
            .bind("usageDate", LocalDate.now())
            .bind("credits", safeCredits)
            .bind("inputTokens", inputTokens)
            .bind("outputTokens", outputTokens)
            .fetch()
            .rowsUpdated()
            .then();
        return keyUpdate.then(dailyUpdate);
    }

    private Optional<String> extractKey(ServerWebExchange exchange) {
        String xApiKey = exchange.getRequest().getHeaders().getFirst("X-Api-Key");
        if (xApiKey != null && !xApiKey.isBlank()) {
            return Optional.of(xApiKey.trim());
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return Optional.of(authorization.substring("Bearer ".length()).trim());
        }
        return Optional.empty();
    }
}
