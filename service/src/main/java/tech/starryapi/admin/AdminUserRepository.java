package tech.starryapi.admin;

import org.springframework.data.r2dbc.repository.Modifying;
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

    @Query("""
        SELECT users.*
        FROM system_settings AS settings
        JOIN admin_users AS users
          ON users.user_id = settings.setting_value
        WHERE settings.setting_key = 'application_root_user_id'
          AND settings.setting_value <> ''
        LIMIT 1
        """)
    Mono<AdminUserEntity> findApplicationRoot();

    @Query("""
        SELECT COALESCE(users.email, '') AS email
        FROM system_settings AS settings
        JOIN admin_users AS users
          ON users.user_id = settings.setting_value
        WHERE settings.setting_key = 'application_root_user_id'
          AND settings.setting_value <> ''
        LIMIT 1
        """)
    Mono<String> findApplicationRootEmail();

    @Query("""
        WITH acquired AS MATERIALIZED (
            SELECT pg_catalog.pg_advisory_xact_lock(
                pg_catalog.hashtextextended('starry_api.application_root_bootstrap', 0)
            )
        )
        SELECT settings.setting_value
        FROM acquired
        CROSS JOIN system_settings AS settings
        WHERE settings.setting_key = 'application_root_user_id'
        """)
    Mono<String> findApplicationRootUserIdWithLock();

    @Query("""
        SELECT *
        FROM admin_users
        WHERE lower(username) = 'admin'
        LIMIT 1
        """)
    Mono<AdminUserEntity> findDefaultAdminCandidate();

    @Modifying
    @Query("""
        UPDATE system_settings
        SET setting_value = :userId,
            updated_at = CURRENT_TIMESTAMP
        WHERE setting_key = 'application_root_user_id'
          AND setting_value = ''
        """)
    Mono<Integer> assignApplicationRoot(@Param("userId") String userId);

    /** A new deployment asks its Root, once, to agree to the licence and the disclaimer (see LegalConsentService). */
    @Modifying
    @Query("""
        INSERT INTO system_settings (setting_key, setting_value)
        VALUES ('legal_consent_required', 'true')
        ON CONFLICT (setting_key) DO NOTHING
        """)
    Mono<Integer> requireLegalConsent();

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
