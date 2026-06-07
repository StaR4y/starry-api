package xyz.star4y.api.admin;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface SystemSettingRepository extends ReactiveCrudRepository<SystemSettingEntity, String> {
}
