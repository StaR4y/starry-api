package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;

class AccountPoolServiceTest {

    @Test
    void importAccountsSavesEveryAccountIntoPool() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findFirstByEmailOrderByCreatedAtAsc(anyString())).thenReturn(Mono.empty());
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
    void importAccountsSkipsExistingAccountWithoutUpsert() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity existing = account("same-account");
        existing.setEmail("same-account@example.com");
        existing.setAccessToken("old-token");
        when(repository.findFirstByEmailOrderByCreatedAtAsc("same-account@example.com")).thenReturn(Mono.just(existing));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        AccountDtos.ImportAccountsResponse response = service.importAccounts(new AccountDtos.ImportAccountsRequest(
            List.of(create("same-account", "new-token")),
            false
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.created()).isZero();
        assertThat(response.updated()).isZero();
        assertThat(response.skipped()).isEqualTo(1);
        assertThat(response.items().get(0).status()).isEqualTo("SKIPPED");
        assertThat(existing.getAccessToken()).isEqualTo("old-token");
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void importAccountsUpdatesExistingAccountByEmailWithUpsert() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity existing = account("internal-account");
        existing.setEmail("owner@example.com");
        existing.setAccessToken("old-token");
        when(repository.findFirstByEmailOrderByCreatedAtAsc("owner@example.com")).thenReturn(Mono.just(existing));
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        AccountDtos.ImportAccountsResponse response = service.importAccounts(new AccountDtos.ImportAccountsRequest(
            List.of(create("source-account-id", "Owner@Example.com", "new-token")),
            true
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.updated()).isEqualTo(1);
        ProxyAccountEntity saved = savedAccount(repository);
        assertThat(saved.getAccountId()).isEqualTo("internal-account");
        assertThat(saved.getEmail()).isEqualTo("owner@example.com");
        assertThat(saved.getAccessToken()).isEqualTo("new-token");
    }

    @Test
    void importAccountsCreatesNewAccountWhenAccountIdCollidesButEmailDiffers() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findFirstByEmailOrderByCreatedAtAsc("new@example.com")).thenReturn(Mono.empty());
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> {
            ProxyAccountEntity entity = invocation.getArgument(0);
            if ("duplicate-account-id".equals(entity.getAccountId())) {
                return Mono.error(new DuplicateKeyException("duplicate account_id"));
            }
            return Mono.just(entity);
        });
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        AccountDtos.ImportAccountsResponse response = service.importAccounts(new AccountDtos.ImportAccountsRequest(
            List.of(create("duplicate-account-id", "new@example.com", "token")),
            false
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.items().get(0).accountId()).isNotEqualTo("duplicate-account-id");
        assertThat(response.items().get(0).email()).isEqualTo("new@example.com");

        ArgumentCaptor<ProxyAccountEntity> captor = ArgumentCaptor.forClass(ProxyAccountEntity.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAccountId()).isEqualTo("duplicate-account-id");
        assertThat(captor.getAllValues().get(1).getAccountId()).isNotEqualTo("duplicate-account-id");
    }

    @Test
    void createRejectsExistingEmail() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity existing = account("existing-account");
        existing.setEmail("owner@example.com");
        when(repository.findFirstByEmailOrderByCreatedAtAsc("owner@example.com")).thenReturn(Mono.just(existing));

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        assertThatThrownBy(() -> service.create(create("new-account", "owner@example.com", "token")).block())
            .isInstanceOf(ApiException.class)
            .hasMessage("Account email already exists");
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void createRegeneratesAccountIdWhenRequestedAccountIdCollides() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findFirstByEmailOrderByCreatedAtAsc("new@example.com")).thenReturn(Mono.empty());
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> {
            ProxyAccountEntity entity = invocation.getArgument(0);
            if ("duplicate-account-id".equals(entity.getAccountId())) {
                return Mono.error(new DuplicateKeyException("duplicate account_id"));
            }
            return Mono.just(entity);
        });
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        ProxyAccountEntity saved = service.create(create("duplicate-account-id", "new@example.com", "token")).block();

        assertThat(saved).isNotNull();
        assertThat(saved.getAccountId()).isNotEqualTo("duplicate-account-id");
        assertThat(saved.getEmail()).isEqualTo("new@example.com");
        verify(repository, times(2)).save(any(ProxyAccountEntity.class));
    }

    @Test
    void selectOpenAiAccountSkipsExcludedAccounts() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity primary = openAiPoolAccount("openai-main");
        ProxyAccountEntity backup = openAiPoolAccount("openai-backup");
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(primary, backup));

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        ProxyAccountEntity selected = service.selectOpenAiAccount("session-1", List.of("openai-main")).block();

        assertThat(selected).isNotNull();
        assertThat(selected.getAccountId()).isEqualTo("openai-backup");
    }

    @Test
    void clearAllDeletesEveryAccountAndRefreshesCache() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.count()).thenReturn(Mono.just(2L));
        when(repository.deleteAll()).thenReturn(Mono.empty());
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        AccountDtos.ClearAccountsResponse response = service.clearAll().block();

        assertThat(response).isNotNull();
        assertThat(response.deleted()).isEqualTo(2L);
        verify(repository).deleteAll();
        verify(repository).findAllByEnabledTrue();
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

    @Test
    void recordRequestFailureMarksQuotaAndDisablesAccount() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity account = account("acc-1");
        when(repository.findByAccountId("acc-1")).thenReturn(Mono.just(account));
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        service.recordRequestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 429, "quota exhausted")
            .block();

        ProxyAccountEntity saved = savedAccount(repository);
        assertThat(saved.getEnabled()).isFalse();
        assertThat(saved.getErrorCount()).isEqualTo(2);
        assertThat(saved.getQuotaExhaustedAt()).isNotNull();
        assertThat(saved.getSuspendedAt()).isNotNull();
        assertThat(saved.getSuspendReason()).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(saved.getSuspendMessage()).isEqualTo("quota exhausted");
    }

    @Test
    void recordRequestFailureDisablesAccountOnAuthFailure() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity account = account("acc-1");
        account.setQuotaExhaustedAt(java.time.Instant.parse("2026-06-08T00:00:00Z"));
        when(repository.findByAccountId("acc-1")).thenReturn(Mono.just(account));
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.empty());

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        service.recordRequestFailure("acc-1", AccountPoolService.UpstreamErrorType.FATAL, 401, "token revoked")
            .block();

        ProxyAccountEntity saved = savedAccount(repository);
        assertThat(saved.getEnabled()).isFalse();
        assertThat(saved.getErrorCount()).isEqualTo(2);
        assertThat(saved.getQuotaExhaustedAt()).isNull();
        assertThat(saved.getSuspendedAt()).isNotNull();
        assertThat(saved.getSuspendReason()).isEqualTo("AUTH_FAILED");
        assertThat(saved.getSuspendMessage()).isEqualTo("token revoked");
    }

    private ProxyAccountEntity savedAccount(ProxyAccountRepository repository) {
        ArgumentCaptor<ProxyAccountEntity> captor = ArgumentCaptor.forClass(ProxyAccountEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private AccountDtos.CreateAccountRequest create(String accountId, String accessToken) {
        return create(accountId, accountId + "@example.com", accessToken);
    }

    private AccountDtos.CreateAccountRequest create(String accountId, String email, String accessToken) {
        return new AccountDtos.CreateAccountRequest(
            accountId,
            email,
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

    private ProxyAccountEntity openAiPoolAccount(String accountId) {
        ProxyAccountEntity account = account(accountId);
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        return account;
    }
}
