package tech.starryapi.admin;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface AdminUserRepository extends ReactiveCrudRepository<AdminUserEntity, Long> {

    @Query("SELECT * FROM admin_users WHERE lower(username) = lower(:username) LIMIT 1")
    Mono<AdminUserEntity> findByUsername(@Param("username") String username);

    @Query("SELECT * FROM admin_users WHERE lower(email) = lower(:email) LIMIT 1")
    Mono<AdminUserEntity> findByEmail(@Param("email") String email);

    Mono<AdminUserEntity> findByUserId(String userId);

    @Query("SELECT * FROM admin_users WHERE lower(affiliate_code) = lower(:affiliateCode) LIMIT 1")
    Mono<AdminUserEntity> findByAffiliateCode(@Param("affiliateCode") String affiliateCode);

    @Query("""
        SELECT *
        FROM admin_users
        WHERE lower(affiliate_code) = lower(:affiliateCode)
          AND enabled = TRUE
        LIMIT 1
        FOR SHARE
        """)
    Mono<AdminUserEntity> findEnabledByAffiliateCodeForShare(@Param("affiliateCode") String affiliateCode);
}
