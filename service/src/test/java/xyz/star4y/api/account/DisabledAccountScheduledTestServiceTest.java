package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiException;

class DisabledAccountScheduledTestServiceTest {

    @Test
    void tickSkipsWhenScheduleIsDisabled() {
        AccountSettingsService settingsService = mock(AccountSettingsService.class);
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountTestService accountTestService = mock(AccountTestService.class);
        when(settingsService.disabledAccountScheduledTestSettings()).thenReturn(Mono.just(settings(false)));

        DisabledAccountScheduledTestService service = new DisabledAccountScheduledTestService(
            settingsService,
            repository,
            accountTestService
        );

        service.tick().block();

        verify(repository, never()).findAll();
        verify(accountTestService, never()).test(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runNowTestsOnlyMatchingDisabledAccounts() {
        AccountSettingsService settingsService = mock(AccountSettingsService.class);
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountTestService accountTestService = mock(AccountTestService.class);
        when(settingsService.disabledAccountScheduledTestSettings()).thenReturn(Mono.just(settings(true)));
        when(repository.findAll()).thenReturn(Flux.just(
            account("anthropic-disabled", false, "Anthropic"),
            account("anthropic-enabled", true, "Anthropic"),
            account("openai-disabled", false, "OpenAI")
        ));
        when(accountTestService.test(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(new AccountDtos.TestAccountsResponse(
                1,
                1,
                0,
                0,
                Instant.now(),
                Instant.now(),
                List.of()
            )));

        DisabledAccountScheduledTestService service = new DisabledAccountScheduledTestService(
            settingsService,
            repository,
            accountTestService
        );

        service.runNow().block();

        verify(accountTestService).test(argThat(request ->
            request.accountIds().equals(List.of("anthropic-disabled"))
                && Boolean.FALSE.equals(request.onlyEnabled())
                && Boolean.TRUE.equals(request.updateStatus())
                && "auto".equals(request.model())
                && request.maxConcurrency() == 2
        ));
    }

    @Test
    void runNowRejectsWhenAlreadyRunning() {
        AccountSettingsService settingsService = mock(AccountSettingsService.class);
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountTestService accountTestService = mock(AccountTestService.class);
        when(settingsService.disabledAccountScheduledTestSettings()).thenReturn(Mono.just(settings(true)));
        when(repository.findAll()).thenReturn(Flux.just(account("acc-1", false, "Anthropic")));
        when(accountTestService.test(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.never());

        DisabledAccountScheduledTestService service = new DisabledAccountScheduledTestService(
            settingsService,
            repository,
            accountTestService
        );
        Disposable subscription = service.runNow().subscribe();
        try {
            assertThatThrownBy(() -> service.runNow().block())
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getCode()).isEqualTo("DISABLED_ACCOUNT_TEST_RUNNING");
                });
        } finally {
            subscription.dispose();
        }
    }

    private AccountDtos.DisabledAccountScheduledTestSettings settings(boolean enabled) {
        return new AccountDtos.DisabledAccountScheduledTestSettings(
            enabled,
            "interval",
            60,
            "0 0 * * * *",
            "Asia/Shanghai",
            0,
            2,
            10,
            "auto",
            "Reply OK",
            true,
            true,
            false,
            false,
            0,
            List.of("Anthropic")
        );
    }

    private ProxyAccountEntity account(String accountId, boolean enabled, String provider) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setEnabled(enabled);
        account.setProvider(provider);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setUpdatedAt(Instant.EPOCH);
        return account;
    }
}
