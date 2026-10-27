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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tech.starryapi.account.allocation.AccountCandidateRanker;
import tech.starryapi.admin.DataManagementSecurityService;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;

class AccountPoolServiceTest {

    @Test
    void scheduledRefreshSkipsRepositoryDuringMaintenance() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));
        AccountPoolService service = new AccountPoolService(
            repository,
            null,
            new ProxyProperties(),
            null,
            new SystemMaintenanceTaskGate(securityService)
        );

        service.scheduledRefresh();

        verifyNoInteractions(repository);
    }

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
            "DO UPDATE SET"
        );
        assertThat(harness.sql()).containsPattern("updated_at = clock_timestamp\\(\\)\\s+RETURNING target\\.\\*");
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void createSeparatesDoNothingFromPostgreSqlReturning() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity inserted = account("new-account");
        inserted.setEmail("new-account@example.com");
        AccountInsertHarness harness = accountInsertService(
            repository,
            List.of(Mono.just(new AccountPoolService.AccountWriteResult(inserted, true)))
        );

        ProxyAccountEntity saved = harness.service()
            .create(create("new-account", "new-account@example.com", "token"))
            .block();

        assertThat(saved).isSameAs(inserted);
        assertThat(harness.sql()).containsPattern("DO NOTHING\\s+RETURNING target\\.\\*");
        assertThat(harness.sql()).doesNotContain("DO NOTHINGRETURNING");
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
    void cachedAccountFindsAnEnabledAccountWhateverItsStateAndNothingElse() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity healthy = openAiPoolAccount("openai-main");
        ProxyAccountEntity suspended = openAiPoolAccount("openai-suspended");
        suspended.setSuspendedAt(Instant.parse("2026-01-01T00:00:00Z"));
        ProxyAccountEntity disabled = openAiPoolAccount("openai-disabled");
        disabled.setEnabled(false);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(healthy, suspended, disabled));
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThat(service.cachedAccount("openai-main")).containsSame(healthy);
        // An account that is suspended is still cached; it is the selection that skips it.
        assertThat(service.cachedAccount("openai-suspended")).containsSame(suspended);
        assertThat(service.cachedAccount("openai-disabled")).isEmpty();
        assertThat(service.cachedAccount("never-existed")).isEmpty();
        assertThat(service.cachedAccount(null)).isEmpty();
    }

    @Test
    void theStatusPageSeesTheEnabledAccountsOfTheCacheAndWhetherEachIsUsable() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity healthy = openAiPoolAccount("openai-main");
        ProxyAccountEntity suspended = openAiPoolAccount("openai-suspended");
        suspended.setSuspendedAt(Instant.parse("2026-01-01T00:00:00Z"));
        ProxyAccountEntity exhausted = openAiPoolAccount("openai-exhausted");
        exhausted.setQuotaUsed(100L);
        exhausted.setQuotaLimit(100L);
        ProxyAccountEntity disabled = openAiPoolAccount("openai-disabled");
        disabled.setEnabled(false);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(healthy, suspended, exhausted, disabled));
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThat(service.cachedAccounts()).extracting(ProxyAccountEntity::getAccountId)
            .containsExactlyInAnyOrder("openai-main", "openai-suspended", "openai-exhausted");
        assertThat(service.isUsable(healthy)).isTrue();
        assertThat(service.isUsable(suspended)).isFalse();
        assertThat(service.isUsable(exhausted)).isFalse();
        assertThat(service.isUsable(null)).isFalse();
        // The same view narrowed by a filter: still whatever their state, still only the enabled ones.
        assertThat(service.cachedAccounts(account -> !"openai-main".equals(account.getAccountId())))
            .extracting(ProxyAccountEntity::getAccountId)
            .containsExactlyInAnyOrder("openai-suspended", "openai-exhausted");
        assertThat(service.cachedAccounts(null)).hasSize(3);
    }

    @Test
    void theAccountsThatAreSwitchedOffAreReadFromTheTableOnceAndKeptUntilTheCacheMoves() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity healthy = openAiPoolAccount("openai-main");
        ProxyAccountEntity suspended = openAiPoolAccount("openai-suspended");
        suspended.setEnabled(false);
        AtomicInteger reads = new AtomicInteger();
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(healthy));
        when(repository.deleteByAccountId(anyString())).thenReturn(Mono.empty());
        when(repository.findSwitchedOffByKind()).thenAnswer(invocation -> {
            reads.incrementAndGet();
            return Flux.just(suspended);
        });
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThat(service.switchedOffAccounts().block()).extracting(ProxyAccountEntity::getAccountId)
            .containsExactly("openai-suspended");
        assertThat(service.switchedOffAccounts().block()).hasSize(1);
        // They are not in the cache the selection reads, and reading them does not put them there.
        assertThat(service.cachedAccount("openai-suspended")).isEmpty();
        assertThat(reads).hasValue(1);

        // A reload of the cache, or a change this node makes to an account, drops what was read.
        service.refreshCache().block();
        service.switchedOffAccounts().block();
        assertThat(reads).hasValue(2);
        service.delete("openai-main").block();
        service.switchedOffAccounts().block();
        assertThat(reads).hasValue(3);
    }

    @Test
    void aTableThatCannotBeReadSaysNothingIsSwitchedOffAndTheNextCallTriesAgain() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AtomicInteger reads = new AtomicInteger();
        when(repository.findSwitchedOffByKind()).thenAnswer(invocation -> {
            reads.incrementAndGet();
            return Flux.error(new IllegalStateException("database is down"));
        });
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        assertThat(service.switchedOffAccounts().block()).isEmpty();
        assertThat(service.switchedOffAccounts().block()).isEmpty();

        assertThat(reads).hasValue(2);
    }

    @Test
    void anAccountIsSelectableOnlyWhenItIsAvailableAndTheAllocationLifecycleLetsItBeScheduled() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity ready = openAiPoolAccount("openai-ready");
        ProxyAccountEntity cooling = openAiPoolAccount("openai-cooling");
        ProxyAccountEntity suspended = openAiPoolAccount("openai-suspended");
        suspended.setSuspendedAt(Instant.parse("2026-01-01T00:00:00Z"));
        ProxyAccountEntity exhausted = openAiPoolAccount("openai-exhausted");
        exhausted.setQuotaUsed(100L);
        exhausted.setQuotaLimit(100L);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(ready, cooling, suspended, exhausted));
        AccountCandidateRanker ranker = mock(AccountCandidateRanker.class);
        List<List<String>> askedAbout = new java.util.ArrayList<>();
        when(ranker.schedulable(any())).thenAnswer(invocation -> {
            List<ProxyAccountEntity> candidates = invocation.getArgument(0);
            askedAbout.add(candidates.stream().map(ProxyAccountEntity::getAccountId).toList());
            // The allocation tables keep the cooling account from being scheduled.
            return Mono.just(candidates.stream().filter(account -> !"openai-cooling".equals(account.getAccountId())).toList());
        });
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties(), null,
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(), ranker);
        service.refreshCache().block();

        assertThat(service.hasSelectableAccount(account -> true).block()).isTrue();
        assertThat(service.hasSelectableAccount(account -> "openai-ready".equals(account.getAccountId())).block()).isTrue();
        // Usable by the cache's own rules, but not schedulable: the lifecycle is the layer that holds it back.
        assertThat(service.isUsable(cooling)).isTrue();
        assertThat(service.hasSelectableAccount(account -> "openai-cooling".equals(account.getAccountId())).block()).isFalse();
        assertThat(service.hasSelectableAccount(account -> "openai-suspended".equals(account.getAccountId())).block()).isFalse();
        assertThat(service.hasSelectableAccount(account -> "openai-exhausted".equals(account.getAccountId())).block()).isFalse();
        assertThat(service.hasSelectableAccount(account -> false).block()).isFalse();
        // The lifecycle is only asked about the accounts that passed the cheaper layers.
        assertThat(askedAbout).hasSize(3);
        assertThat(askedAbout.get(0)).containsExactlyInAnyOrder("openai-ready", "openai-cooling");
        assertThat(askedAbout.get(1)).containsExactly("openai-ready");
        assertThat(askedAbout.get(2)).containsExactly("openai-cooling");
    }

    @Test
    void withoutAnAllocationLifecycleAnAvailableAccountInTheCacheIsSelectable() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity ready = openAiPoolAccount("openai-ready");
        ProxyAccountEntity suspended = openAiPoolAccount("openai-suspended");
        suspended.setSuspendedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(ready, suspended));
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThat(service.hasSelectableAccount(account -> "openai-ready".equals(account.getAccountId())).block()).isTrue();
        assertThat(service.hasSelectableAccount(account -> "openai-suspended".equals(account.getAccountId())).block()).isFalse();
        assertThat(service.hasSelectableAccount(null).block()).isTrue();
    }

    @Test
    void openAiAndDeepSeekAccountPoolsRemainIsolated() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity openAi = openAiPoolAccount("openai-main");
        ProxyAccountEntity deepSeek = deepSeekPoolAccount("deepseek-main");
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(deepSeek, openAi));

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThat(service.selectOpenAiAccount("session-1").block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openai-main");
        assertThat(service.selectDeepSeekOpenAiAccount("session-1").block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("deepseek-main");
    }

    @Test
    void selectOpenAiApiKeyAccountSkipsCodexTokens() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity apiKey = openAiPoolAccount("openai-key");
        ProxyAccountEntity codex = codexPoolAccount("codex-token");
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(codex, apiKey));

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThat(service.selectOpenAiApiKeyAccountForPool("session-1", null).block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openai-key");
        assertThat(service.selectCodexOpenAiAccount("session-1").block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("codex-token");
    }

    @Test
    void officialCompatibleCodexAndDeepSeekPoolsRemainDistinct() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity official = openAiPoolAccount("openai-official");
        ProxyAccountEntity compatible = openAiPoolAccount("openai-compatible");
        compatible.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        compatible.setBaseUrl("https://gateway.example/v1");
        ProxyAccountEntity codex = codexPoolAccount("codex-token");
        ProxyAccountEntity deepSeek = deepSeekPoolAccount("deepseek-key");
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(
            compatible,
            codex,
            deepSeek,
            official
        ));

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThat(service.selectOpenAiApiKeyAccountForPool("session-1", null).block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openai-official");
        assertThat(service.selectOpenAiCompatibleAccountForPool("session-1", null).block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openai-compatible");
        assertThat(service.selectCodexOpenAiAccountForPool("session-1", null).block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("codex-token");
        assertThat(service.selectDeepSeekOpenAiAccountForPool("session-1", null).block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("deepseek-key");
    }

    @Test
    void customCatalogVendorJoinsOpenAiCompatiblePool() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity official = openAiPoolAccount("openai-official");
        ProxyAccountEntity vendor = openAiPoolAccount("openrouter-key");
        vendor.setProvider("OpenRouter");
        vendor.setBaseUrl("https://openrouter.ai/api/v1");
        vendor.setBadgeColor("#4D6BFE");
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(official, vendor));

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThat(service.selectOpenAiApiKeyAccountForPool("session-1", null).block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openai-official");
        assertThat(service.selectOpenAiCompatibleAccountForPool("session-1", null).block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openrouter-key");
    }

    @Test
    void contextScopedAffinityUsesTheSameKeyForCommitAndReadWithoutOscillation() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity first = openAiPoolAccount("openai-main");
        first.setId(1L);
        ProxyAccountEntity second = openAiPoolAccount("openai-backup");
        second.setId(2L);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(first, second));
        AccountPoolPolicyService policyService = mock(AccountPoolPolicyService.class);
        AccountPoolRequestContext context = requestContext("request-affinity");
        when(policyService.affinityKey(context, "key-1:conversation-1"))
            .thenReturn("openai:gpt-5:key-1:conversation-1");
        when(policyService.applyTierPolicy(any(), org.mockito.ArgumentMatchers.eq(context)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        ProxyProperties properties = new ProxyProperties();
        properties.setSessionAffinityEnabled(true);
        AccountPoolService service = new AccountPoolService(repository, null, properties, policyService);
        service.refreshCache().block();

        service.commitSessionAffinity(context, "key-1:conversation-1", "openai-backup", false);
        service.commitSessionAffinity(context, "key-1:conversation-1", "openai-main", false);

        assertThat(service.selectOpenAiAccountForPool("key-1:conversation-1", context).block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openai-backup");
        verify(policyService, times(3)).affinityKey(context, "key-1:conversation-1");
    }

    @Test
    void commitSessionAffinityCanBindBareApiKey() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity first = openAiPoolAccount("openai-main");
        first.setId(1L);
        ProxyAccountEntity second = openAiPoolAccount("openai-backup");
        second.setId(2L);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(first, second));
        ProxyProperties properties = new ProxyProperties();
        properties.setSessionAffinityEnabled(true);
        AccountPoolService service = new AccountPoolService(repository, null, properties);
        service.refreshCache().block();

        service.commitSessionAffinity("key-1", "openai-backup", false);
        assertThat(service.selectOpenAiAccount("key-1").block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openai-main");

        service.commitSessionAffinity("key-1", "openai-backup", true);
        assertThat(service.selectOpenAiAccount("key-1").block())
            .extracting(ProxyAccountEntity::getAccountId)
            .isEqualTo("openai-backup");
    }

    @Test
    void selectInScopeKeepsABindingToTheScopeItWasMadeIn() {
        AccountPoolService service = scopedPool(true);
        service.commitSessionAffinity(null, "tier:openai:official|gpt-5", "key-1:session-1", "openai-backup", false);

        assertThat(selectedIn(service, "tier:openai:official|gpt-5")).isEqualTo("openai-backup");
        // Another tier, another model or no scope at all is not steered by it, nor is a select*ForPool.
        assertThat(selectedIn(service, "tier:openai:codex|gpt-5")).isEqualTo("openai-main");
        assertThat(selectedIn(service, "tier:openai:official|gpt-5.5")).isEqualTo("openai-main");
        assertThat(selectedIn(service, null)).isEqualTo("openai-main");
        assertThat(service.selectOpenAiAccount("key-1:session-1").block().getAccountId()).isEqualTo("openai-main");
    }

    @Test
    void aBindingMadeWithoutAScopeIsNotFoundInOne() {
        AccountPoolService service = scopedPool(true);
        service.commitSessionAffinity(null, "key-1:session-1", "openai-backup", false);

        assertThat(selectedIn(service, null)).isEqualTo("openai-backup");
        assertThat(selectedIn(service, "tier:openai:official|gpt-5")).isEqualTo("openai-main");
    }

    @Test
    void aBindingInAScopeFollowsTheRulesOfAnyOtherBinding() {
        AccountPoolService service = scopedPool(true);

        // A session that is only an API key id is bound when the policy says so, and not otherwise.
        service.commitSessionAffinity(null, "tier:openai:official|gpt-5", "key-1", "openai-backup", false);
        assertThat(service.selectInScope("key-1", account -> true, "none", null, "tier:openai:official|gpt-5", false)
            .block().getAccountId()).isEqualTo("openai-main");
        service.commitSessionAffinity(null, "tier:openai:official|gpt-5", "key-1", "openai-backup", true);
        assertThat(service.selectInScope("key-1", account -> true, "none", null, "tier:openai:official|gpt-5", false)
            .block().getAccountId()).isEqualTo("openai-backup");

        // With session affinity off nothing is bound.
        AccountPoolService unbound = scopedPool(false);
        unbound.commitSessionAffinity(null, "tier:openai:official|gpt-5", "key-1:session-1", "openai-backup", true);
        assertThat(selectedIn(unbound, "tier:openai:official|gpt-5")).isEqualTo("openai-main");
    }

    @Test
    void aBoundAccountThatTheFilterLeavesOutIsNotFollowed() {
        AccountPoolService service = scopedPool(true);
        service.commitSessionAffinity(null, "tier:openai:official|gpt-5", "key-1:session-1", "openai-backup", false);

        // The filter of a tiered selection leaves out the accounts the request has met, the bound one among them.
        assertThat(service.selectInScope("key-1:session-1", account -> !"openai-backup".equals(account.getAccountId()),
            "none", null, "tier:openai:official|gpt-5", false).block().getAccountId()).isEqualTo("openai-main");
    }

    @Test
    void selectInScopeReloadsTheCacheForAnEmptyFilterOnlyWhenAskedTo() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(openAiPoolAccount("openai-main")));
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        assertThatThrownBy(() -> service.selectInScope("session-1", account -> true, "nothing in this cell", null, "scope", false).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
                assertThat(error.getMessage()).isEqualTo("nothing in this cell");
            });
        verify(repository, never()).findAllByEnabledTrue();

        assertThat(service.selectInScope("session-1", account -> true, "nothing in this cell", null, "scope", true)
            .block().getAccountId()).isEqualTo("openai-main");
        verify(repository).findAllByEnabledTrue();
    }

    @Test
    void selectInScopeSkipsAnAccountThatIsNotAvailableWithoutReloading() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity suspended = openAiPoolAccount("openai-suspended");
        suspended.setSuspendedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(suspended));
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThatThrownBy(() -> service.selectInScope("session-1", account -> true, "none", null, "scope", true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));
        // The cache has an account that matches, only it is not available; that is no reason to reload it.
        verify(repository).findAllByEnabledTrue();
    }

    @Test
    void refreshCacheIfStaleReloadsOnlyOnceWithinTheMissInterval() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(openAiPoolAccount("openai-main")));
        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());

        service.refreshCacheIfStale().block();
        service.refreshCacheIfStale().block();

        verify(repository, times(1)).findAllByEnabledTrue();
        assertThat(service.cachedAccount("openai-main")).isPresent();
    }

    @Test
    void deepSeekAccountSelectionDoesNotFallBackToOpenAi() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(openAiPoolAccount("openai-main")));

        AccountPoolService service = new AccountPoolService(repository, null, new ProxyProperties());
        service.refreshCache().block();

        assertThatThrownBy(() -> service.selectDeepSeekOpenAiAccount("session-1").block())
            .isInstanceOf(ApiException.class)
            .hasMessage("No available DeepSeek upstream account");
    }

    @Test
    void deepSeekAccountSelectionRespectsRateLimitCooldown() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity deepSeek = deepSeekPoolAccount("deepseek-main");
        deepSeek.setErrorCount(1);
        deepSeek.setLastUsedAt(Instant.now());
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(deepSeek));
        ProxyProperties properties = new ProxyProperties();
        properties.setBaseCooldown(java.time.Duration.ofHours(1));
        properties.setProbabilisticRetryChance(0);
        AccountPoolService service = new AccountPoolService(repository, null, properties);
        service.refreshCache().block();

        assertThatThrownBy(() -> service.selectDeepSeekOpenAiAccount("session-1").block())
            .isInstanceOf(ApiException.class)
            .hasMessage("No available DeepSeek upstream account");
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
    @SuppressWarnings("unchecked")
    void poolStatusInventoryUsesOneCredentialFreeAggregationQuery() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<Object> fetch = mock(RowsFetchSpec.class);
        RowMetadata metadata = mock(RowMetadata.class);
        Row openAiPrimary = inventoryRow(6L, 3L, "OpenAI", "api-key", "https://api.openai.com/v1", "primary", 2L);
        Row deepSeekSecondary = inventoryRow(6L, 3L, "DeepSeek", "api-key", "https://api.deepseek.com/v1", "secondary", 1L);
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<BiFunction<Row, RowMetadata, Object>> mapper = new AtomicReference<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, Object>>any()))
            .thenAnswer(invocation -> {
                mapper.set(invocation.getArgument(0));
                return fetch;
            });
        when(fetch.all()).thenAnswer(invocation -> Flux.just(
            mapper.get().apply(openAiPrimary, metadata),
            mapper.get().apply(deepSeekSecondary, metadata)
        ));
        AccountPoolService service = new AccountPoolService(repository, template, new ProxyProperties());

        AccountPoolService.PoolAccountInventory inventory = service.poolAccountInventory().block();

        assertThat(inventory).isNotNull();
        assertThat(inventory.accountCount()).isEqualTo(6);
        assertThat(inventory.availableAccountCount()).isEqualTo(3);
        assertThat(inventory.groups())
            .containsExactly(
                new AccountPoolService.PoolAccountGroup(
                    "OpenAI", "api-key", "https://api.openai.com/v1", "primary", 2
                ),
                new AccountPoolService.PoolAccountGroup(
                    "DeepSeek", "api-key", "https://api.deepseek.com/v1", "secondary", 1
                )
            );
        assertThat(sql.get())
            .contains("WITH account_inventory AS MATERIALIZED", "COUNT(*) FILTER (WHERE available)")
            .doesNotContain(
                "access_token",
                "refresh_token",
                "id_token",
                "client_secret",
                "proxy_url"
            );
        verify(repository, never()).findAll();
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
    void changingProviderResetsImplicitBaseUrlToNewProviderDefault() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity current = deepSeekPoolAccount("acc-1");
        current.setBaseUrl(AccountPlatforms.DEFAULT_DEEPSEEK_BASE_URL);
        when(repository.findByAccountId("acc-1")).thenReturn(Mono.just(current));
        SqlAccountPoolHarness harness = sqlService(repository, openAiPoolAccount("acc-1"));

        harness.service().update("acc-1", updateProvider(AccountPlatforms.OPENAI_PROVIDER)).block();

        verify(harness.spec()).bind("value0", AccountPlatforms.OPENAI_API_KEY_AUTH);
        verify(harness.spec()).bind("value1", AccountPlatforms.OPENAI_PROVIDER);
        verify(harness.spec()).bind("value2", AccountPlatforms.DEFAULT_OPENAI_BASE_URL);
    }

    @Test
    void changingProviderToDeepSeekResetsImplicitBaseUrlToOfficialDefault() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity current = openAiPoolAccount("acc-1");
        current.setBaseUrl(AccountPlatforms.DEFAULT_OPENAI_BASE_URL);
        when(repository.findByAccountId("acc-1")).thenReturn(Mono.just(current));
        SqlAccountPoolHarness harness = sqlService(repository, deepSeekPoolAccount("acc-1"));

        harness.service().update("acc-1", updateProvider(AccountPlatforms.DEEPSEEK_PROVIDER)).block();

        verify(harness.spec()).bind("value0", AccountPlatforms.OPENAI_API_KEY_AUTH);
        verify(harness.spec()).bind("value1", AccountPlatforms.DEEPSEEK_PROVIDER);
        verify(harness.spec()).bind("value2", AccountPlatforms.DEFAULT_DEEPSEEK_BASE_URL);
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
            .recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.FATAL, 401, "token revoked", true)
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
    void disabledCircuitDoesNotEmitCircuitLogDimensions() {
        AccountPoolPolicyService policyService = mock(AccountPoolPolicyService.class);
        when(policyService.isEnabled()).thenReturn(false);
        AccountPoolService service = new AccountPoolService(
            mock(ProxyAccountRepository.class),
            null,
            new ProxyProperties(),
            policyService
        );

        assertThat(service.circuitKey(requestContext("request-disabled"))).isNull();
        assertThat(service.circuitTier(openAiPoolAccount("acc-1"))).isNull();

        verify(policyService, never()).poolKey(any());
    }

    @Test
    void recordTestFailureKeepsAccountEnabledForTransientFailure() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = failedAccount("ACCOUNT_TEST_FAILED", "upstream failed", false);
        SqlAccountPoolHarness harness = sqlService(repository, updated);

        harness.service().recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 503, "upstream failed", true)
            .block();

        assertThat(harness.sql())
            .contains("error_count = COALESCE(error_count, 0) + 1", "RETURNING *")
            .doesNotContain("enabled = FALSE");
        verify(harness.spec()).bind("permanentCredentialFailure", false);
        verify(repository, never()).save(any(ProxyAccountEntity.class));
    }

    @Test
    void recordTestFailureDisablesAccountWhenTheTextOfAnAuthFailureSaysTheCredentialIsGone() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        SqlAccountPoolHarness harness = sqlService(repository, failedAccount("AUTH_FAILED", "token_invalidated", false));

        harness.service().recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.FATAL, 401, "OpenAI account test error 401: token_invalidated", true)
            .block();

        verify(harness.spec()).bind("permanentCredentialFailure", true);
    }

    @Test
    void recordTestFailureKeepsAnAccountEnabledWhenTheCallerSaysTheWordsOfItsAuthFailureAreNotProof() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        SqlAccountPoolHarness harness = sqlService(repository, failedAccount("ACCOUNT_TEST_FAILED", "token_invalidated", false));

        // A relay of another protocol (D5): its raw answer says the token is gone, and the error is still counted.
        harness.service().recordTestFailure(
            "acc-1", AccountPoolService.UpstreamErrorType.FATAL, 401, "OpenAI account test error 401: token_invalidated", true, false, false
        ).block();

        verify(harness.spec()).bind("permanentCredentialFailure", false);
        assertThat(harness.sql()).contains("error_count = COALESCE(error_count, 0) + 1");
    }

    @Test
    void recordTestRateLimitDoesNotMarkQuotaOrDisableAccountByDefault() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = failedAccount("QUOTA_EXHAUSTED", "quota", true);
        SqlAccountPoolHarness harness = sqlService(repository, updated);

        harness.service().recordTestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 429, "quota", true)
            .block();

        verify(harness.spec()).bind("quotaError", false);
        assertThat(harness.sql()).doesNotContain("enabled = FALSE");
    }

    @Test
    void recordRequestRateLimitDoesNotDisableAccountWithoutConfirmedQuotaEvidence() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = failedAccount("QUOTA_EXHAUSTED", "quota exhausted", true);
        SqlAccountPoolHarness harness = sqlService(repository, updated);

        AccountPoolService.RequestFailureRecord record = harness.service()
            .recordRequestFailure("acc-1", AccountPoolService.UpstreamErrorType.RECOVERABLE, 429, "quota exhausted")
            .block();

        assertThat(record).isNotNull();
        assertThat(record.consecutiveFailures()).isEqualTo(2);
        assertThat(record.suspended()).isFalse();
        verify(harness.spec()).bind("quotaFailure", false);
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

    @Test
    void enabledCircuitScopesServerFailureToModelPoolWithoutIncrementingAccountErrors() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = account("acc-1");
        updated.setPoolTier(AccountPoolPolicyService.PRIMARY_TIER);
        AccountPoolPolicyService policyService = mock(AccountPoolPolicyService.class);
        when(policyService.isEnabled()).thenReturn(true);
        SqlAccountPoolHarness harness = sqlService(repository, updated, policyService);
        AccountPoolRequestContext context = requestContext("request-1");
        when(policyService.isActive(context)).thenReturn(true);

        AccountPoolService.RequestFailureRecord record = harness.service()
            .recordRequestFailure(
                "acc-1",
                AccountPoolService.UpstreamErrorType.RECOVERABLE,
                503,
                "upstream unavailable",
                context
            )
            .block();

        assertThat(record).isNotNull();
        assertThat(record.consecutiveFailures()).isEqualTo(1);
        verify(harness.spec()).bind("circuitScopedFailure", true);
        assertThat(harness.sql()).contains(
            "WHEN :circuitScopedFailure",
            "LOWER(COALESCE(pool_tier, 'primary')) NOT IN ('secondary', '2', 'level2')",
            "THEN COALESCE(error_count, 0)",
            "ELSE COALESCE(error_count, 0) + 1"
        );
        verify(policyService, never()).recordFailure(any(), any());

        harness.service().recordCircuitFailure(context, updated);

        verify(policyService).recordFailure(context, updated);
    }

    @Test
    void enabledCircuitStillPenalizesFailingSecondaryAccounts() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = account("secondary-1");
        updated.setPoolTier(AccountPoolPolicyService.SECONDARY_TIER);
        updated.setErrorCount(2);
        AccountPoolPolicyService policyService = mock(AccountPoolPolicyService.class);
        when(policyService.isEnabled()).thenReturn(true);
        SqlAccountPoolHarness harness = sqlService(repository, updated, policyService);
        AccountPoolRequestContext context = requestContext("secondary-failure");
        when(policyService.isActive(context)).thenReturn(true);

        AccountPoolService.RequestFailureRecord record = harness.service()
            .recordRequestFailure(
                "secondary-1",
                AccountPoolService.UpstreamErrorType.RECOVERABLE,
                503,
                "secondary unavailable",
                context
            )
            .block();

        assertThat(record).isNotNull();
        assertThat(record.consecutiveFailures()).isEqualTo(2);
        assertThat(harness.sql()).contains(
            "LOWER(COALESCE(pool_tier, 'primary')) NOT IN ('secondary', '2', 'level2')",
            "ELSE COALESCE(error_count, 0) + 1"
        );
        verify(policyService, never()).recordFailure(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402, 403, 429})
    void transientAuthenticationQuotaAndRateLimitFailuresDoNotDisableAccounts(int statusCode) {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = failedAccount(
            "UPSTREAM_ACCOUNT_FAILURE",
            "account failure",
            statusCode == 402 || statusCode == 429
        );
        AccountPoolPolicyService policyService = mock(AccountPoolPolicyService.class);
        when(policyService.isEnabled()).thenReturn(true);
        SqlAccountPoolHarness harness = sqlService(repository, updated, policyService);
        AccountPoolRequestContext context = requestContext("request-" + statusCode);
        when(policyService.isActive(context)).thenReturn(true);

        AccountPoolService.RequestFailureRecord record = harness.service()
            .recordRequestFailure(
                "acc-1",
                AccountPoolService.UpstreamErrorType.RECOVERABLE,
                statusCode,
                "account failure",
                context
            )
            .block();

        assertThat(record).isNotNull();
        assertThat(record.consecutiveFailures()).isEqualTo(2);
        assertThat(record.suspended()).isFalse();
        verify(harness.spec()).bind("circuitScopedFailure", false);
        assertThat(harness.sql()).contains("ELSE COALESCE(error_count, 0) + 1");
        verify(policyService, never()).recordFailure(any(), any());
        verify(policyService, never()).cancelSelection(any());
    }

    @Test
    void accountFailurePersistenceDoesNotOwnHalfOpenProbeLifecycle() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = account("acc-1");
        AccountPoolPolicyService policyService = mock(AccountPoolPolicyService.class);
        when(policyService.isEnabled()).thenReturn(true);
        SqlAccountPoolHarness harness = sqlService(repository, updated, policyService);
        AccountPoolRequestContext context = requestContext("invalid-response");
        when(policyService.isActive(context)).thenReturn(true);
        harness.service().recordRequestFailure(
            "acc-1",
            AccountPoolService.UpstreamErrorType.FATAL,
            502,
            "invalid upstream response",
            context
        ).block();

        verify(harness.spec()).bind("circuitScopedFailure", false);
        verify(policyService, never()).recordFailure(any(), any());
        verify(policyService, never()).cancelSelection(any());
    }

    @Test
    void circuitFailureClassificationIncludesTransportAndAnthropicConcurrencyFailuresOnlyWhenRecoverable() {
        assertThat(AccountPoolService.isCircuitBreakerFailure(
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            null
        )).isTrue();
        assertThat(AccountPoolService.isCircuitBreakerFailure(
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            408
        )).isTrue();
        assertThat(AccountPoolService.isCircuitBreakerFailure(
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            409
        )).isTrue();
        assertThat(AccountPoolService.isCircuitBreakerFailure(
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            503
        )).isTrue();
        assertThat(AccountPoolService.isCircuitBreakerFailure(
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            429
        )).isFalse();
        assertThat(AccountPoolService.isCircuitBreakerFailure(
            AccountPoolService.UpstreamErrorType.FATAL,
            502
        )).isFalse();
    }

    @Test
    void disabledCircuitKeepsServerFailureAccountScoped() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity updated = account("acc-1");
        updated.setErrorCount(2);
        AccountPoolPolicyService policyService = mock(AccountPoolPolicyService.class);
        when(policyService.isEnabled()).thenReturn(false);
        SqlAccountPoolHarness harness = sqlService(repository, updated, policyService);
        AccountPoolRequestContext context = requestContext("request-disabled");

        AccountPoolService.RequestFailureRecord record = harness.service()
            .recordRequestFailure(
                "acc-1",
                AccountPoolService.UpstreamErrorType.RECOVERABLE,
                503,
                "upstream unavailable",
                context
            )
            .block();

        assertThat(record).isNotNull();
        assertThat(record.consecutiveFailures()).isEqualTo(2);
        assertThat(record.suspended()).isFalse();
        verify(harness.spec()).bind("circuitScopedFailure", false);
        assertThat(harness.sql()).contains("ELSE COALESCE(error_count, 0) + 1");
        verify(policyService, never()).recordFailure(any(), any());
        verify(policyService, never()).cancelSelection(any());
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
        return sqlService(repository, returned, null);
    }

    @SuppressWarnings("unchecked")
    private SqlAccountPoolHarness sqlService(
        ProxyAccountRepository repository,
        ProxyAccountEntity returned,
        AccountPoolPolicyService policyService
    ) {
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
            new AccountPoolService(repository, template, new ProxyProperties(), policyService),
            template,
            spec,
            sql
        );
    }

    private AccountPoolRequestContext requestContext(String requestToken) {
        return new AccountPoolRequestContext(
            requestToken,
            "gpt-5",
            "gpt-5",
            AccountPlatforms.OPENAI_PROVIDER
        );
    }

    private Row inventoryRow(
        long accountCount,
        long availableAccountCount,
        String provider,
        String authMethod,
        String baseUrl,
        String poolTier,
        long groupCount
    ) {
        Row row = mock(Row.class);
        when(row.get("account_count")).thenReturn(accountCount);
        when(row.get("available_account_count")).thenReturn(availableAccountCount);
        when(row.get("provider")).thenReturn(provider);
        when(row.get("auth_method")).thenReturn(authMethod);
        when(row.get("base_url")).thenReturn(baseUrl);
        when(row.get("pool_tier")).thenReturn(poolTier);
        when(row.get("group_count")).thenReturn(groupCount);
        return row;
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

    private AccountDtos.UpdateAccountRequest updateProvider(String provider) {
        return new AccountDtos.UpdateAccountRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            provider,
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

    /** A pool of two OpenAI keys that always offers the first one unless a binding says otherwise. */
    private AccountPoolService scopedPool(boolean sessionAffinity) {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity first = openAiPoolAccount("openai-main");
        first.setId(1L);
        ProxyAccountEntity second = openAiPoolAccount("openai-backup");
        second.setId(2L);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(first, second));
        ProxyProperties properties = new ProxyProperties();
        properties.setSessionAffinityEnabled(sessionAffinity);
        properties.setAccountSelectionStrategy(ProxyProperties.AccountSelectionStrategy.STICKY);
        AccountPoolService service = new AccountPoolService(repository, null, properties);
        service.refreshCache().block();
        return service;
    }

    private String selectedIn(AccountPoolService service, String scope) {
        return service.selectInScope("key-1:session-1", account -> true, "none", null, scope, false).block().getAccountId();
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

    private ProxyAccountEntity codexPoolAccount(String accountId) {
        ProxyAccountEntity account = account(accountId);
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);
        return account;
    }

    private ProxyAccountEntity deepSeekPoolAccount(String accountId) {
        ProxyAccountEntity account = account(accountId);
        account.setProvider(AccountPlatforms.DEEPSEEK_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        return account;
    }
}
