package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.platform.openai.OpenAiAccountTestClient;

class AccountOpenAiQuotaServiceTest {

    @Test
    void returnsOpenAiOfficialQuotaForCodexAccount() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        OpenAiAccountTestClient client = mock(OpenAiAccountTestClient.class);
        AccountOpenAiQuotaService service = new AccountOpenAiQuotaService(repository, client);
        ProxyAccountEntity account = codexAccount();
        Instant checkedAt = Instant.parse("2026-06-10T01:00:00Z");

        when(repository.findByAccountId("acct-1")).thenReturn(Mono.just(account));
        when(client.quota(account)).thenReturn(Mono.just(new OpenAiAccountTestClient.OpenAiQuotaSnapshot(
            null,
            null,
            new OpenAiAccountTestClient.OpenAiQuotaWindow(
                "5h",
                "5小时窗口",
                30.0,
                70.0,
                1200,
                checkedAt.plusSeconds(1200),
                300,
                checkedAt
            ),
            new OpenAiAccountTestClient.OpenAiQuotaWindow(
                "7d",
                "7天窗口",
                80.0,
                20.0,
                86400,
                checkedAt.plusSeconds(86400),
                10080,
                checkedAt
            ),
            null,
            checkedAt
        )));

        AccountDtos.OpenAiQuotaResponse response = service.quota("acct-1").block();

        assertThat(response).isNotNull();
        assertThat(response.accountId()).isEqualTo("acct-1");
        assertThat(response.email()).isEqualTo("codex@example.com");
        assertThat(response.source()).isEqualTo("chatgpt-codex-response-headers");
        assertThat(response.checkedAt()).isEqualTo(checkedAt);
        assertThat(response.fiveHour().remainingPercent()).isEqualTo(70.0);
        assertThat(response.sevenDay().remainingPercent()).isEqualTo(20.0);
    }

    @Test
    void rejectsNonCodexAccounts() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountOpenAiQuotaService service = new AccountOpenAiQuotaService(repository, mock(OpenAiAccountTestClient.class));
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("acct-1");
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setAccessToken("sk-test");
        when(repository.findByAccountId("acct-1")).thenReturn(Mono.just(account));

        assertThatThrownBy(() -> service.quota("acct-1").block())
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ProxyAccountEntity codexAccount() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("acct-1");
        account.setEmail("codex@example.com");
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);
        account.setAccessToken("access-token");
        return account;
    }
}
