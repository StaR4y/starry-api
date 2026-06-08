package xyz.star4y.api.admin;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface SystemSettingRepository extends ReactiveCrudRepository<SystemSettingEntity, String> {

    @Modifying
    @Query("""
        INSERT INTO system_settings (setting_key, setting_value)
        VALUES (:settingKey, :settingValue)
        ON DUPLICATE KEY UPDATE
            setting_value = :settingValue,
            updated_at = CURRENT_TIMESTAMP(3)
        """)
    Mono<Integer> upsertSetting(
        @Param("settingKey") String settingKey,
        @Param("settingValue") String settingValue
    );
}
