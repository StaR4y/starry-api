package xyz.star4y.api.account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import xyz.star4y.api.admin.SystemSettingEntity;
import xyz.star4y.api.admin.SystemSettingRepository;
import xyz.star4y.api.common.ApiException;

@Service
public class AccountSettingsService {

    static final String GPT_FAST_MODE_ENABLED = "gpt_fast_mode_enabled";

    private final SystemSettingRepository repository;

    public AccountSettingsService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    public Mono<AccountDtos.AccountSettingsResponse> settings() {
        return gptFastModeEnabled()
            .map(AccountDtos.AccountSettingsResponse::new);
    }

    public Mono<AccountDtos.AccountSettingsResponse> update(AccountDtos.UpdateAccountSettingsRequest request) {
        return gptFastModeEnabled()
            .flatMap(current -> {
                boolean enabled = request == null || request.gptFastModeEnabled() == null
                    ? current
                    : Boolean.TRUE.equals(request.gptFastModeEnabled());
                return set(GPT_FAST_MODE_ENABLED, Boolean.toString(enabled))
                    .thenReturn(new AccountDtos.AccountSettingsResponse(enabled));
            });
    }

    public Mono<Boolean> gptFastModeEnabled() {
        return value(GPT_FAST_MODE_ENABLED, "false").map(Boolean::parseBoolean);
    }

    public Mono<Void> ensureGptFastModeAllowed() {
        return gptFastModeEnabled()
            .flatMap(enabled -> Boolean.TRUE.equals(enabled)
                ? Mono.empty()
                : Mono.error(new ApiException(
                    HttpStatus.FORBIDDEN,
                    "GPT_FAST_MODE_DISABLED",
                    "GPT fast mode is not allowed by administrator"
                )));
    }

    private Mono<String> value(String key, String fallback) {
        return repository.findById(key)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty(fallback);
    }

    private Mono<SystemSettingEntity> set(String key, String value) {
        return repository.findById(key)
            .defaultIfEmpty(newSetting(key))
            .flatMap(entity -> {
                entity.setSettingValue(value == null ? "" : value);
                return repository.save(entity);
            });
    }

    private SystemSettingEntity newSetting(String key) {
        SystemSettingEntity entity = new SystemSettingEntity();
        entity.setSettingKey(key);
        entity.setSettingValue("");
        return entity;
    }
}
