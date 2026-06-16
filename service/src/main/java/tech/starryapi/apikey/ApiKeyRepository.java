package tech.starryapi.apikey;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKeyEntity, Long> {

    Mono<ApiKeyEntity> findByKeyId(String keyId);

    Mono<ApiKeyEntity> findByKeyIdAndUserId(String keyId, String userId);

    Mono<ApiKeyEntity> findByKeyHash(String keyHash);

    Flux<ApiKeyEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);

    Mono<Void> deleteByKeyId(String keyId);

    Mono<Void> deleteByKeyIdAndUserId(String keyId, String userId);
}
