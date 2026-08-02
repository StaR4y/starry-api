package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

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

    @Test
    void registrationPolicyAllowsAValidEmptyWhitelist() {
        Map<String, String> store = new HashMap<>();
        store.put(RegistrationSettingsService.REGISTRATION_EMAIL_SUFFIX_WHITELIST, "[]");
        RegistrationSettingsService service = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );

        RegistrationSettingsService.RegistrationPolicy policy = service.registrationPolicy().block();

        assertThat(policy).isNotNull();
        assertThat(policy.registrationEmailSuffixWhitelist()).isEmpty();
    }

    @Test
    void registrationPolicyFailsClosedForMalformedWhitelistJson() {
        Map<String, String> store = new HashMap<>();
        store.put(RegistrationSettingsService.REGISTRATION_EMAIL_SUFFIX_WHITELIST, "{broken-json]");
        RegistrationSettingsService service = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> service.registrationPolicy().block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("whitelist configuration is invalid");
    }

    @Test
    void registrationPolicyFailsClosedForInvalidWhitelistEntry() {
        Map<String, String> store = new HashMap<>();
        store.put(RegistrationSettingsService.REGISTRATION_EMAIL_SUFFIX_WHITELIST, "[\"not-a-domain\"]");
        RegistrationSettingsService service = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> service.registrationPolicy().block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("whitelist configuration is invalid");
    }

    @Test
    void registrationPolicyFailsClosedForMalformedSecurityBoolean() {
        Map<String, String> store = new HashMap<>();
        store.put(RegistrationSettingsService.REGISTRATION_ENABLED, "true");
        store.put(RegistrationSettingsService.EMAIL_VERIFY_ENABLED, "tru");
        RegistrationSettingsService service = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> service.registrationPolicy().block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("policy configuration is invalid");
    }

    @Test
    void registrationPolicyFingerprintChangesWhenEnabledTurnstileCredentialsRotate() {
        Map<String, String> store = new HashMap<>();
        store.put(RegistrationSettingsService.TURNSTILE_ENABLED, "true");
        store.put(RegistrationSettingsService.TURNSTILE_SITE_KEY, "site-a");
        store.put(RegistrationSettingsService.TURNSTILE_SECRET_KEY, "secret-a");
        RegistrationSettingsService service = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );

        String before = service.registrationPolicy().block().turnstileConfigFingerprint();
        store.put(RegistrationSettingsService.TURNSTILE_SECRET_KEY, "secret-b");
        String after = service.registrationPolicy().block().turnstileConfigFingerprint();

        assertThat(before).isNotBlank();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void registrationPolicyFingerprintPreservesTurnstileCredentialBoundaries() {
        Map<String, String> store = new HashMap<>();
        store.put(RegistrationSettingsService.TURNSTILE_ENABLED, "true");
        store.put(RegistrationSettingsService.TURNSTILE_SITE_KEY, "site\nsecret");
        store.put(RegistrationSettingsService.TURNSTILE_SECRET_KEY, "key");
        RegistrationSettingsService service = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );

        String first = service.registrationPolicy().block().turnstileConfigFingerprint();
        store.put(RegistrationSettingsService.TURNSTILE_SITE_KEY, "site");
        store.put(RegistrationSettingsService.TURNSTILE_SECRET_KEY, "secret\nkey");
        String second = service.registrationPolicy().block().turnstileConfigFingerprint();

        assertThat(second).isNotEqualTo(first);
    }

    private SystemSettingRepository mockRepository(Map<String, String> store) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.acquireTransactionLock(anyString())).thenReturn(Mono.just(true));
        when(repository.findAllById(any(Iterable.class))).thenAnswer(invocation -> {
            Iterable<String> keys = invocation.getArgument(0);
            return Flux.fromIterable(keys)
                .filter(store::containsKey)
                .map(key -> setting(key, store.get(key)));
        });
        when(repository.upsertSetting(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            store.put(key, value);
            return Mono.just(1);
        });
        when(repository.upsertSettings(any(String[].class), any(String[].class))).thenAnswer(invocation -> {
            String[] keys = invocation.getArgument(0);
            String[] values = invocation.getArgument(1);
            for (int index = 0; index < keys.length; index++) {
                store.put(keys[index], values[index]);
            }
            return Mono.just(keys.length);
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
