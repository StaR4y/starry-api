package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.admin.SystemSettingEntity;
import xyz.star4y.api.admin.SystemSettingRepository;
import xyz.star4y.api.common.ApiException;

class AccountSettingsServiceTest {

    @Test
    void settingsDefaultsGptFastModeToDisabled() {
        SystemSettingRepository repository = mockRepository(new HashMap<>());

        AccountDtos.AccountSettingsResponse response = new AccountSettingsService(repository)
            .settings()
            .block();

        assertThat(response).isNotNull();
        assertThat(response.gptFastModeEnabled()).isFalse();
        assertThat(response.disabledAccountScheduledTest().enabled()).isFalse();
        assertThat(response.disabledAccountScheduledTest().scheduleMode()).isEqualTo("interval");
        assertThat(response.disabledAccountScheduledTest().intervalMinutes()).isEqualTo(60);
    }

    @Test
    void updateStoresGptFastModeEnabled() {
        Map<String, String> store = new HashMap<>();
        SystemSettingRepository repository = mockRepository(store);

        AccountDtos.AccountSettingsResponse response = new AccountSettingsService(repository)
            .update(new AccountDtos.UpdateAccountSettingsRequest(true, null))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.gptFastModeEnabled()).isTrue();
        assertThat(store.get(AccountSettingsService.GPT_FAST_MODE_ENABLED)).isEqualTo("true");
    }

    @Test
    void updateStoresDisabledAccountScheduledTestSettings() {
        Map<String, String> store = new HashMap<>();
        SystemSettingRepository repository = mockRepository(store);

        AccountDtos.DisabledAccountScheduledTestSettings request = new AccountDtos.DisabledAccountScheduledTestSettings(
            true,
            "cron",
            15,
            "0 */15 * * * *",
            "Asia/Shanghai",
            1,
            4,
            50,
            "claude-sonnet-4-5-20250929",
            "Reply OK",
            true,
            true,
            true,
            true,
            30,
            java.util.List.of("Anthropic", "OpenAI-compatible")
        );

        AccountDtos.AccountSettingsResponse response = new AccountSettingsService(repository)
            .update(new AccountDtos.UpdateAccountSettingsRequest(null, request))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.disabledAccountScheduledTest().enabled()).isTrue();
        assertThat(response.disabledAccountScheduledTest().scheduleMode()).isEqualTo("cron");
        assertThat(response.disabledAccountScheduledTest().providers()).containsExactly("Anthropic", "OpenAI-compatible");
        assertThat(store.get(AccountSettingsService.DISABLED_TEST_ENABLED)).isEqualTo("true");
        assertThat(store.get(AccountSettingsService.DISABLED_TEST_CRON_EXPRESSION)).isEqualTo("0 */15 * * * *");
        assertThat(store.get(AccountSettingsService.DISABLED_TEST_PROVIDERS)).isEqualTo("Anthropic,OpenAI-compatible");
    }

    @Test
    void ensureGptFastModeAllowedRejectsWhenDisabled() {
        SystemSettingRepository repository = mockRepository(new HashMap<>());
        when(repository.findById(AccountSettingsService.GPT_FAST_MODE_ENABLED)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> new AccountSettingsService(repository)
            .ensureGptFastModeAllowed()
            .block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getCode()).isEqualTo("GPT_FAST_MODE_DISABLED");
            });
    }

    private SystemSettingRepository mockRepository(Map<String, String> store) {
        SystemSettingRepository repository = org.mockito.Mockito.mock(SystemSettingRepository.class);
        when(repository.findAllById(any(Iterable.class))).thenAnswer(invocation -> {
            Iterable<String> keys = invocation.getArgument(0);
            return Flux.fromIterable(keys)
                .filter(store::containsKey)
                .map(key -> setting(key, store.get(key)));
        });
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return Mono.justOrEmpty(store.get(key)).map(value -> setting(key, value));
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
