package tech.starryapi.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AdminUserRepository extends ReactiveCrudRepository<AdminUserEntity, Long> {

    Mono<AdminUserEntity> findByUsername(String username);

    Mono<AdminUserEntity> findByEmail(String email);

    Mono<AdminUserEntity> findByUserId(String userId);

    Mono<AdminUserEntity> findByAffiliateCode(String affiliateCode);
}
