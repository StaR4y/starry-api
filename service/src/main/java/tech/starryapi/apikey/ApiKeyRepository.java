package tech.starryapi.apikey;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ApiKeyRepository extends ReactiveCrudRepository<ApiKeyEntity, Long> {

    Mono<ApiKeyEntity> findByKeyId(String keyId);

    Mono<ApiKeyEntity> findByKeyIdAndUserId(String keyId, String userId);

    /**
     * Runs for every proxied request. Written as explicit SQL because a derived query makes Spring Data build its
     * select list on each call, and for every multi-word column it throws and swallows a
     * PropertyReferenceException with a full stack trace (about eight per lookup).
     */
    @Query("SELECT * FROM api_keys WHERE key_hash = :keyHash")
    Mono<ApiKeyEntity> findByKeyHash(@Param("keyHash") String keyHash);

    Flux<ApiKeyEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);

    Mono<Void> deleteByKeyId(String keyId);

    Mono<Void> deleteByKeyIdAndUserId(String keyId, String userId);
}
