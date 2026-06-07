package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.config.ProxyProperties;

class AccountPoolServiceTest {

    @Test
    void importAccountsSavesEveryAccountIntoPool() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findByAccountId(anyString())).thenReturn(Mono.empty());
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        AccountDtos.ImportAccountsResponse response = service.importAccounts(new AccountDtos.ImportAccountsRequest(
            List.of(create("json-1", "token-1"), create("json-2", "token-2")),
            true
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.created()).isEqualTo(2);
        assertThat(response.failed()).isZero();

        ArgumentCaptor<ProxyAccountEntity> captor = ArgumentCaptor.forClass(ProxyAccountEntity.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ProxyAccountEntity::getAccountId)
            .containsExactlyInAnyOrder("json-1", "json-2");
    }

    @Test
    void recordTestFailureDisablesAccountWhenUpdatingStatus() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity account = account("acc-1");
        when(repository.findByAccountId("acc-1")).thenReturn(Mono.just(account));
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        service.recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 503, "upstream failed", true)
            .block();

        ProxyAccountEntity saved = savedAccount(repository);
        assertThat(saved.getEnabled()).isFalse();
        assertThat(saved.getErrorCount()).isEqualTo(2);
        assertThat(saved.getSuspendedAt()).isNotNull();
        assertThat(saved.getSuspendReason()).isEqualTo("ACCOUNT_TEST_FAILED");
        assertThat(saved.getSuspendMessage()).isEqualTo("upstream failed");
        assertThat(saved.getQuotaExhaustedAt()).isNull();
    }

    @Test
    void recordTestFailureMarksQuotaAndDisablesAccountWhenUpdatingStatus() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity account = account("acc-1");
        when(repository.findByAccountId("acc-1")).thenReturn(Mono.just(account));
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        service.recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 429, "quota", true)
            .block();

        ProxyAccountEntity saved = savedAccount(repository);
        assertThat(saved.getEnabled()).isFalse();
        assertThat(saved.getQuotaExhaustedAt()).isNotNull();
        assertThat(saved.getSuspendedAt()).isNotNull();
        assertThat(saved.getSuspendReason()).isEqualTo("QUOTA_EXHAUSTED");
    }

    private ProxyAccountEntity savedAccount(ProxyAccountRepository repository) {
        ArgumentCaptor<ProxyAccountEntity> captor = ArgumentCaptor.forClass(ProxyAccountEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private AccountDtos.CreateAccountRequest create(String accountId, String accessToken) {
        return new AccountDtos.CreateAccountRequest(
            accountId,
            accountId + "@example.com",
            accessToken,
            null,
            null,
            null,
            null,
            "api-key",
            AccountPlatforms.OPENAI_PROVIDER,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private ProxyAccountEntity account(String accountId) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setEnabled(true);
        account.setErrorCount(1);
        return account;
    }
}
