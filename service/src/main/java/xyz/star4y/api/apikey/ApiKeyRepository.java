package xyz.star4y.api.apikey;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKeyEntity, Long> {

    Mono<ApiKeyEntity> findByKeyId(String keyId);

    Mono<ApiKeyEntity> findByKeyHash(String keyHash);

    Mono<Void> deleteByKeyId(String keyId);
}
