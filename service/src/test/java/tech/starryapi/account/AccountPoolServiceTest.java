package tech.starryapi.account;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;

class AccountPoolServiceTest {

    @Test
    void importAccountsInsertsEveryAccountWithPostgreSqlReturning() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity first = account("json-1");
        first.setEmail("json-1@example.com");
        ProxyAccountEntity second = account("json-2");
        second.setEmail("json-2@example.com");
        AccountInsertHarness harness = accountInsertService(
            repository,
            List.of(
                Mono.just(new AccountPoolService.AccountWriteResult(first, true)),
                Mono.just(new AccountPoolService.AccountWriteResult(second, true))
            )
        );

        AccountDtos.ImportAccountsResponse response = harness.service().importAccounts(new AccountDtos.ImportAccountsRequest(
            List.of(create("json-1", "token-1"), create("json-2", "token-2")),
            true
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.created()).isEqualTo(2);
        assertThat(response.failed()).isZero();
        assertThat(harness.sql()).contains(
            "INSERT INTO proxy_accounts AS target",
            "ON CONFLICT (lower(email)) WHERE email IS NOT NULL",
            "DO UPDATE SET",
            "RETURNING target.*"
        );
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void importAccountsSkipsExistingAccountWithoutUpsert() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity existing = account("same-account");
        existing.setEmail("same-account@example.com");
        existing.setAccessToken("old-token");
        when(repository.findFirstByEmailOrderByCreatedAtAsc("same-account@example.com")).thenReturn(Mono.just(existing));
        AccountInsertHarness harness = accountInsertService(repository, List.of(Mono.empty()));

        AccountDtos.ImportAccountsResponse response = harness.service().importAccounts(new AccountDtos.ImportAccountsRequest(
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
    void importUpsertPreservesConcurrentDisabledCountersAndQuotaState() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity existing = account("internal-account");
        existing.setEmail("owner@example.com");
        existing.setAccessToken("new-token");
        existing.setEnabled(false);
        existing.setRequestCount(51L);
        existing.setErrorCount(7);
        existing.setQuotaUsed(89L);
        AccountInsertHarness harness = accountInsertService(
            repository,
            List.of(Mono.just(new AccountPoolService.AccountWriteResult(existing, false)))
        );

        AccountDtos.ImportAccountsResponse response = harness.service().importAccounts(new AccountDtos.ImportAccountsRequest(
            List.of(create("source-account-id", "Owner@Example.com", "new-token")),
            true
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.updated()).isEqualTo(1);
        assertThat(response.items().get(0).account().enabled()).isFalse();
        assertThat(response.items().get(0).account().requestCount()).isEqualTo(51L);
        assertThat(response.items().get(0).account().errorCount()).isEqualTo(7);
        assertThat(response.items().get(0).account().quotaUsed()).isEqualTo(89L);
        String updateClause = harness.sql().substring(
            harness.sql().indexOf("DO UPDATE SET"),
            harness.sql().indexOf("RETURNING")
        );
        assertThat(updateClause)
            .doesNotContain(
                "enabled =",
                "request_count =",
                "error_count =",
                "quota_used =",
                "quota_exhausted_at =",
                "suspended_at =",
                "last_used_at ="
            );
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void importAccountsCreatesNewAccountWhenAccountIdCollidesButEmailDiffers() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity inserted = account("generated-account-id");
        inserted.setEmail("new@example.com");
        AccountInsertHarness harness = accountInsertService(repository, List.of(
            Mono.error(new DuplicateKeyException("duplicate account_id")),
            Mono.just(new AccountPoolService.AccountWriteResult(inserted, true))
        ));

        AccountDtos.ImportAccountsResponse response = harness.service().importAccounts(new AccountDtos.ImportAccountsRequest(
            List.of(create("duplicate-account-id", "new@example.com", "token")),
            false
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.items().get(0).accountId()).isNotEqualTo("duplicate-account-id");
        assertThat(response.items().get(0).email()).isEqualTo("new@example.com");
        verify(harness.databaseClient(), times(2)).sql(anyString());
    }

    @Test
    void createRejectsExistingEmail() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity existing = account("existing-account");
        existing.setEmail("owner@example.com");
        AccountInsertHarness harness = accountInsertService(repository, List.of(Mono.empty()));

        assertThatThrownBy(() -> harness.service().create(create("new-account", "owner@example.com", "token")).block())
            .isInstanceOf(ApiException.class)
            .hasMessage("Account email already exists");
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void createRegeneratesAccountIdWhenRequestedAccountIdCollides() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity inserted = account("generated-account-id");
        inserted.setEmail("new@example.com");
        AccountInsertHarness harness = accountInsertService(repository, List.of(
            Mono.error(new DuplicateKeyException("duplicate account_id")),
            Mono.just(new AccountPoolService.AccountWriteResult(inserted, true))
        ));

        ProxyAccountEntity saved = harness.service().create(create("duplicate-account-id", "new@example.com", "token")).block();

        assertThat(saved).isNotNull();
        assertThat(saved.getAccountId()).isNotEqualTo("duplicate-account-id");
        assertThat(saved.getEmail()).isEqualTo("new@example.com");
        verify(harness.databaseClient(), times(2)).sql(anyString());
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
    void singleQuotaExhaustedAccountIsNotSelected() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity exhausted = account("acc-exhausted");
        exhausted.setQuotaUsed(100L);
        exhausted.setQuotaLimit(100L);
        exhausted.setQuotaResetAt(Instant.now().plusSeconds(300));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(exhausted));
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThatThrownBy(() -> service.selectAccount(null).block())
            .isInstanceOf(ApiException.class)
            .hasMessage("No available upstream account");
    }

    @Test
    void clearAllCountsDeletedRowsInOneAtomicStatementWithoutReloadingCache() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountCountHarness harness = accountCountService(repository, 2L);

        AccountDtos.ClearAccountsResponse response = harness.service().clearAll().block();

        assertThat(response).isNotNull();
        assertThat(response.deleted()).isEqualTo(2L);
        assertThat(harness.sql()).contains(
            "DELETE FROM proxy_accounts",
            "RETURNING account_id",
            "COUNT(*)::bigint AS deleted_count"
        );
        verify(repository, never()).deleteAll();
        verify(repository, never()).count();
        verify(repository, never()).findAllByEnabledTrue();
    }

    @Test
    void managementUpdateDoesNotWriteBackConcurrentHotState() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity stale = account("acc-1");
        stale.setEnabled(true);
        stale.setRequestCount(1L);
        stale.setQuotaUsed(2L);
        when(repository.findByAccountId("acc-1")).thenReturn(Mono.just(stale));
        ProxyAccountEntity finalState = account("acc-1");
        finalState.setAccessToken("new-token");
        finalState.setEnabled(false);
        finalState.setRequestCount(91L);
        finalState.setErrorCount(8);
        finalState.setQuotaUsed(77L);
        SqlAccountPoolHarness harness = sqlService(repository, finalState);

        ProxyAccountEntity result = harness.service().update("acc-1", updateAccessToken("new-token")).block();

        assertThat(result).isSameAs(finalState);
        String setClause = harness.sql().substring(harness.sql().indexOf("SET"), harness.sql().indexOf("WHERE"));
        assertThat(setClause)
            .contains("access_token =", "updated_at = clock_timestamp()")
            .doesNotContain(
                "enabled =",
                "request_count =",
                "error_count =",
                "quota_used =",
                "quota_exhausted_at =",
                "suspended_at =",
                "last_used_at ="
            );
        assertThat(result.getEnabled()).isFalse();
        assertThat(result.getRequestCount()).isEqualTo(91L);
        assertThat(result.getQuotaUsed()).isEqualTo(77L);
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void concurrentRefreshesShareOneRepositoryQuery() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        Sinks.Many<ProxyAccountEntity> query = Sinks.many().unicast().onBackpressureBuffer();
        when(repository.findAllByEnabledTrue()).thenReturn(query.asFlux());
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        CompletableFuture<Void> first = service.refreshCache().toFuture();
        CompletableFuture<Void> second = service.refreshCache().toFuture();

        verify(repository).findAllByEnabledTrue();
        assertThat(query.tryEmitNext(account("acc-1")).isSuccess()).isTrue();
        assertThat(query.tryEmitComplete().isSuccess()).isTrue();
        CompletableFuture.allOf(first, second).join();

        assertThat(service.selectAccount(null).block()).extracting(ProxyAccountEntity::getAccountId).isEqualTo("acc-1");
    }

    @Test
    void refreshSnapshotCannotRestoreAccountDisabledWhileQueryWasInFlight() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        Sinks.Many<ProxyAccountEntity> query = Sinks.many().unicast().onBackpressureBuffer();
        when(repository.findAllByEnabledTrue()).thenReturn(query.asFlux());
        ProxyAccountEntity disabled = failedAccount("AUTH_FAILED", "revoked", false);
        disabled.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        disabled.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        SqlAccountPoolHarness harness = sqlService(repository, disabled);
        CompletableFuture<Void> refresh = harness.service().refreshCache().toFuture();

        harness.service()
            .recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.FATAL, 401, "revoked", true)
            .block();
        ProxyAccountEntity staleEnabled = openAiPoolAccount("acc-1");
        staleEnabled.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(query.tryEmitNext(staleEnabled).isSuccess()).isTrue();
        assertThat(query.tryEmitComplete().isSuccess()).isTrue();
        refresh.join();

        assertThatThrownBy(() -> harness.service().selectOpenAiAccount(null).block())
            .isInstanceOf(ApiException.class)
            .hasMessage("No available OpenAI upstream account");
        verify(repository).findAllByEnabledTrue();
    }

    @Test
    void hotAccountUpdateKeepsStableTopologyWithoutFullRefresh() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity second = account("acc-2");
        second.setId(2L);
        ProxyAccountEntity first = account("acc-1");
        first.setId(1L);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(second, first));
        ProxyAccountEntity updatedSecond = account("acc-2");
        updatedSecond.setId(2L);
        updatedSecond.setRequestCount(10L);
        SqlAccountPoolHarness harness = sqlService(repository, updatedSecond);
        ProxyProperties properties = new ProxyProperties();
        properties.setSessionAffinityEnabled(false);
        AccountPoolService service = new AccountPoolService(repository, harness.template(), properties);
        service.refreshCache().block();

        assertThat(service.selectAccount(null).block()).extracting(ProxyAccountEntity::getAccountId).isEqualTo("acc-1");
        assertThat(service.selectAccount(null).block()).extracting(ProxyAccountEntity::getAccountId).isEqualTo("acc-2");
        service.recordSuccess("acc-2", 5).block();

        assertThat(service.selectAccount(null).block()).extracting(ProxyAccountEntity::getAccountId).isEqualTo("acc-1");
        verify(repository).findAllByEnabledTrue();
    }

    @Test
    void sessionAffinityCacheHonorsConfiguredMaximumSize() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity first = account("acc-1");
        first.setId(1L);
        ProxyAccountEntity second = account("acc-2");
        second.setId(2L);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(first, second));
        ProxyProperties properties = new ProxyProperties();
        properties.setSessionAffinityMaximumSize(2);
        AccountPoolService service = new AccountPoolService(repository, null, properties);
        service.refreshCache().block();

        service.selectAccount("session-1").block();
        service.selectAccount("session-2").block();
        service.selectAccount("session-3").block();

        assertThat(service.affinityEntryCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void recordTestFailureDisablesAccountWhenUpdatingStatus() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = failedAccount("ACCOUNT_TEST_FAILED", "upstream failed", false);
        SqlAccountPoolHarness harness = sqlService(repository, updated);

        harness.service().recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 503, "upstream failed", true)
            .block();

        assertThat(harness.sql()).contains("error_count = COALESCE(error_count, 0) + 1", "RETURNING *");
        verify(harness.spec()).bind("reason", "ACCOUNT_TEST_FAILED");
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void recordTestFailureMarksQuotaAndDisablesAccountWhenUpdatingStatus() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = failedAccount("QUOTA_EXHAUSTED", "quota", true);
        SqlAccountPoolHarness harness = sqlService(repository, updated);

        harness.service().recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 429, "quota", true)
            .block();

        verify(harness.spec()).bind("quotaFailure", true);
        verify(harness.spec()).bind("reason", "QUOTA_EXHAUSTED");
        assertThat(harness.sql()).contains("enabled = FALSE", "RETURNING *");
    }

    @Test
    void recordRequestFailureMarksQuotaAndDisablesAccount() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = failedAccount("QUOTA_EXHAUSTED", "quota exhausted", true);
        SqlAccountPoolHarness harness = sqlService(repository, updated);

        AccountPoolService.RequestFailureRecord record = harness.service()
            .recordRequestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 429, "quota exhausted")
            .block();

        assertThat(record).isNotNull();
        assertThat(record.consecutiveFailures()).isEqualTo(2);
        assertThat(record.suspended()).isTrue();
        verify(harness.spec()).bind("quotaFailure", true);
        assertThat(harness.sql()).contains("enabled = CASE WHEN :suspended THEN FALSE", "RETURNING *");
    }

    @Test
    void recordRequestFailureDisablesAccountOnAuthFailure() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = failedAccount("AUTH_FAILED", "token revoked", false);
        SqlAccountPoolHarness harness = sqlService(repository, updated);

        AccountPoolService.RequestFailureRecord record = harness.service()
            .recordRequestFailure("acc-1", AccountPoolService.UpstreamErrorType.FATAL, 401, "token revoked")
            .block();

        assertThat(record).isNotNull();
        assertThat(record.consecutiveFailures()).isEqualTo(2);
        assertThat(record.suspended()).isTrue();
        verify(harness.spec()).bind("authFailure", true);
        assertThat(harness.sql()).contains("WHEN :authFailure THEN NULL", "RETURNING *");
    }

    @SuppressWarnings("unchecked")
    private AccountInsertHarness accountInsertService(
        ProxyAccountRepository repository,
        List<Mono<AccountPoolService.AccountWriteResult>> results
    ) {
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<AccountPoolService.AccountWriteResult> fetch = mock(RowsFetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicInteger resultIndex = new AtomicInteger();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.bindNull(anyString(), any())).thenReturn(spec);
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, AccountPoolService.AccountWriteResult>>any()))
            .thenReturn(fetch);
        when(fetch.one()).thenAnswer(invocation -> results.get(resultIndex.getAndIncrement()));
        return new AccountInsertHarness(
            new AccountPoolService(repository, template, new ProxyProperties()),
            databaseClient,
            sql
        );
    }

    @SuppressWarnings("unchecked")
    private AccountCountHarness accountCountService(ProxyAccountRepository repository, long count) {
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<Long> fetch = mock(RowsFetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, Long>>any())).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(count));
        return new AccountCountHarness(new AccountPoolService(repository, template, new ProxyProperties()), sql);
    }

    @SuppressWarnings("unchecked")
    private SqlAccountPoolHarness sqlService(ProxyAccountRepository repository, ProxyAccountEntity returned) {
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<ProxyAccountEntity> fetch = mock(RowsFetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.bindNull(anyString(), any())).thenReturn(spec);
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, ProxyAccountEntity>>any()))
            .thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(returned));
        return new SqlAccountPoolHarness(
            new AccountPoolService(repository, template, new ProxyProperties()),
            template,
            spec,
            sql
        );
    }

    private ProxyAccountEntity failedAccount(String reason, String message, boolean quotaFailure) {
        ProxyAccountEntity updated = account("acc-1");
        updated.setEnabled(false);
        updated.setErrorCount(2);
        updated.setSuspendedAt(java.time.Instant.now());
        updated.setSuspendReason(reason);
        updated.setSuspendMessage(message);
        updated.setQuotaExhaustedAt(quotaFailure ? java.time.Instant.now() : null);
        updated.setUpdatedAt(java.time.Instant.now());
        return updated;
    }

    private record SqlAccountPoolHarness(
        AccountPoolService service,
        R2dbcEntityTemplate template,
        DatabaseClient.GenericExecuteSpec spec,
        AtomicReference<String> capturedSql
    ) {
        String sql() {
            return capturedSql.get();
        }
    }

    private record AccountInsertHarness(
        AccountPoolService service,
        DatabaseClient databaseClient,
        AtomicReference<String> capturedSql
    ) {
        String sql() {
            return capturedSql.get();
        }
    }

    private record AccountCountHarness(
        AccountPoolService service,
        AtomicReference<String> capturedSql
    ) {
        String sql() {
            return capturedSql.get();
        }
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

    private AccountDtos.UpdateAccountRequest updateAccessToken(String accessToken) {
        return new AccountDtos.UpdateAccountRequest(
            null,
            accessToken,
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
