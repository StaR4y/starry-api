package tech.starryapi.admin;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SystemSettingRepository extends ReactiveCrudRepository<SystemSettingEntity, String> {

    @Query("""
        WITH acquired AS MATERIALIZED (
            SELECT pg_catalog.pg_advisory_xact_lock(
                pg_catalog.hashtextextended(:lockName, 0)
            )
        )
        SELECT TRUE
        FROM acquired
        """)
    Mono<Boolean> acquireTransactionLock(@Param("lockName") String lockName);

    @Query("""
        WITH acquired AS MATERIALIZED (
            SELECT pg_catalog.pg_advisory_xact_lock_shared(
                pg_catalog.hashtextextended(:lockName, 0)
            )
        )
        SELECT settings.*
        FROM acquired
        CROSS JOIN system_settings AS settings
        WHERE settings.setting_key = ANY(CAST(:settingKeys AS VARCHAR[]))
        FOR SHARE OF settings
        """)
    Flux<SystemSettingEntity> findAllByIdWithSharedTransactionLock(
        @Param("lockName") String lockName,
        @Param("settingKeys") String[] settingKeys
    );

    @Modifying
    @Query("""
        INSERT INTO system_settings (setting_key, setting_value)
        VALUES (:settingKey, :settingValue)
        ON CONFLICT (setting_key) DO UPDATE
        SET setting_value = EXCLUDED.setting_value,
            updated_at = CURRENT_TIMESTAMP
        """)
    Mono<Integer> upsertSetting(
        @Param("settingKey") String settingKey,
        @Param("settingValue") String settingValue
    );

    @Query("""
        INSERT INTO system_settings AS stored (setting_key, setting_value)
        VALUES (:settingKey, :candidateValue)
        ON CONFLICT (setting_key) DO UPDATE
        SET setting_value = stored.setting_value
        RETURNING setting_value
        """)
    Mono<String> getOrCreateSetting(
        @Param("settingKey") String settingKey,
        @Param("candidateValue") String candidateValue
    );

    @Modifying
    @Query("""
        INSERT INTO system_settings (setting_key, setting_value)
        SELECT input.setting_key, input.setting_value
        FROM UNNEST(
            CAST(:settingKeys AS VARCHAR[]),
            CAST(:settingValues AS TEXT[])
        ) AS input(setting_key, setting_value)
        ON CONFLICT (setting_key) DO UPDATE
        SET setting_value = EXCLUDED.setting_value,
            updated_at = CURRENT_TIMESTAMP
        """)
    Mono<Integer> upsertSettings(
        @Param("settingKeys") String[] settingKeys,
        @Param("settingValues") String[] settingValues
    );
}
