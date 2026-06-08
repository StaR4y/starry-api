package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import xyz.star4y.api.admin.SystemSettingEntity;
import xyz.star4y.api.admin.SystemSettingRepository;
import xyz.star4y.api.common.ApiException;

class AccountSettingsServiceTest {

    @Test
    void settingsDefaultsGptFastModeToDisabled() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findById(AccountSettingsService.GPT_FAST_MODE_ENABLED)).thenReturn(Mono.empty());

        AccountDtos.AccountSettingsResponse response = new AccountSettingsService(repository)
            .settings()
            .block();

        assertThat(response).isNotNull();
        assertThat(response.gptFastModeEnabled()).isFalse();
    }

    @Test
    void updateStoresGptFastModeEnabled() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        ArgumentCaptor<SystemSettingEntity> setting = ArgumentCaptor.forClass(SystemSettingEntity.class);
        when(repository.findById(AccountSettingsService.GPT_FAST_MODE_ENABLED)).thenReturn(Mono.empty());
        when(repository.save(any(SystemSettingEntity.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        AccountDtos.AccountSettingsResponse response = new AccountSettingsService(repository)
            .update(new AccountDtos.UpdateAccountSettingsRequest(true))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.gptFastModeEnabled()).isTrue();
        verify(repository).save(setting.capture());
        assertThat(setting.getValue().getSettingKey()).isEqualTo(AccountSettingsService.GPT_FAST_MODE_ENABLED);
        assertThat(setting.getValue().getSettingValue()).isEqualTo("true");
    }

    @Test
    void ensureGptFastModeAllowedRejectsWhenDisabled() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findById(AccountSettingsService.GPT_FAST_MODE_ENABLED)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> new AccountSettingsService(repository)
            .ensureGptFastModeAllowed()
            .block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getCode()).isEqualTo("GPT_FAST_MODE_DISABLED");
            });
    }
}
