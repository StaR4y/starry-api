package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RegistrationSettingsServiceTest {

    @Test
    void updateStoresTurnstileSettingsAndKeepsExistingSecretWhenBlank() {
        Map<String, String> store = new HashMap<>();
        RegistrationSettingsService service = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );

        AdminDtos.RegistrationSettingsResponse saved = service.update(new AdminDtos.UpdateRegistrationSettingsRequest(
            true,
            true,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            "0x4AAAA-site",
            "0x4AAAA-secret"
        )).block();

        assertThat(saved).isNotNull();
        assertThat(saved.turnstileEnabled()).isTrue();
        assertThat(saved.turnstileSiteKey()).isEqualTo("0x4AAAA-site");
        assertThat(saved.turnstileSecretConfigured()).isTrue();
        assertThat(store.get(RegistrationSettingsService.TURNSTILE_ENABLED)).isEqualTo("true");
        assertThat(store.get(RegistrationSettingsService.TURNSTILE_SITE_KEY)).isEqualTo("0x4AAAA-site");
        assertThat(store.get(RegistrationSettingsService.TURNSTILE_SECRET_KEY)).isEqualTo("0x4AAAA-secret");

        AdminDtos.RegistrationSettingsResponse updated = service.update(new AdminDtos.UpdateRegistrationSettingsRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            "0x4AAAA-site-2",
            ""
        )).block();

        assertThat(updated).isNotNull();
        assertThat(updated.turnstileEnabled()).isTrue();
        assertThat(updated.turnstileSiteKey()).isEqualTo("0x4AAAA-site-2");
        assertThat(updated.turnstileSecretConfigured()).isTrue();
        assertThat(store.get(RegistrationSettingsService.TURNSTILE_SECRET_KEY)).isEqualTo("0x4AAAA-secret");
    }

    private SystemSettingRepository mockRepository(Map<String, String> store) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return Mono.justOrEmpty(store.get(key)).map(value -> setting(key, value));
        });
        when(repository.upsertSetting(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            store.put(key, value);
            return Mono.just(1);
        });
        when(repository.save(any(SystemSettingEntity.class))).thenAnswer(invocation -> {
            SystemSettingEntity entity = invocation.getArgument(0);
            store.put(entity.getSettingKey(), entity.getSettingValue());
            return Mono.just(entity);
        });
        return repository;
    }

    private SystemSettingEntity setting(String key, String value) {
        SystemSettingEntity entity = new SystemSettingEntity();
        entity.setSettingKey(key);
        entity.setSettingValue(value);
        return entity;
    }
}
