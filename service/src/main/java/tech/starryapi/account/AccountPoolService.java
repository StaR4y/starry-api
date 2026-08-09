package tech.starryapi.account;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.ProxyProperties;

@Service
public class AccountPoolService {

    private static final Logger log = LoggerFactory.getLogger(AccountPoolService.class);
    private static final Duration CACHE_MISS_REFRESH_INTERVAL = Duration.ofSeconds(5);
    private static final int CACHE_UPDATE_SKIPPED = -1;
    private static final int CACHE_CONTENT_CHANGED = 0;
    private static final int CACHE_TOPOLOGY_CHANGED = 1;
    private static final Comparator<ProxyAccountEntity> ACCOUNT_TOPOLOGY_ORDER = Comparator
        .comparing(ProxyAccountEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(ProxyAccountEntity::getAccountId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ProxyAccountRepository repository;
    private final R2dbcEntityTemplate template;
    private final ProxyProperties properties;
    private final AccountPoolPolicyService poolPolicyService;
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final AtomicLong cacheVersion = new AtomicLong();
    private final AtomicLong refreshGeneration = new AtomicLong();
    private final ConcurrentHashMap<String, CachedAccountEntry> accountCache = new ConcurrentHashMap<>();
    private final Cache<String, String> affinity;
    private volatile List<String> cachedAccountIds = List.of();
    private volatile Instant lastCacheRefreshAt = Instant.EPOCH;
    private volatile long lastCacheClearVersion;
    private volatile Mono<Void> inFlightCacheRefresh;
    private long inFlightCacheRefreshGeneration;

    public AccountPoolService(
        ProxyAccountRepository repository,
        R2dbcEntityTemplate template,
        ProxyProperties properties
    ) {
        this(repository, template, properties, null);
    }

    @Autowired
    public AccountPoolService(
        ProxyAccountRepository repository,
        R2dbcEntityTemplate template,
        ProxyProperties properties,
        AccountPoolPolicyService poolPolicyService
    ) {
        this.repository = repository;
        this.template = template;
        this.properties = properties;
        this.poolPolicyService = poolPolicyService;
        this.affinity = Caffeine.newBuilder()
            .maximumSize(Math.max(1, properties.getSessionAffinityMaximumSize()))
            .expireAfterAccess(positiveAffinityTtl(properties.getSessionAffinityTtl()))
            .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCache() {
        refreshCache().subscribe(null, error -> log.warn("Failed to warm up account cache", error));
    }

    @Scheduled(fixedDelay = 30_000)
    public void scheduledRefresh() {
        refreshCache().subscribe(null, error -> log.warn("Failed to refresh account cache", error));
    }

    public Mono<List<ProxyAccountEntity>> list() {
        return repository.findAll().collectList();
    }

    public Mono<ProxyAccountEntity> create(AccountDtos.CreateAccountRequest request) {
        return insertImportedAccount(request, false, 0)
            .map(AccountWriteResult::account)
            .switchIfEmpty(Mono.error(accountEmailExists()))
            .doOnNext(this::updateCachedAccount);
    }

    public Mono<AccountDtos.ImportAccountsResponse> importAccounts(AccountDtos.ImportAccountsRequest request) {
        List<AccountDtos.CreateAccountRequest> accounts = request.accounts();
        if (accounts == null || accounts.isEmpty()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ACCOUNT_IMPORT", "Account import list is empty"));
        }
        boolean upsert = Boolean.TRUE.equals(request.upsert());
        return Flux.range(0, accounts.size())
            .concatMap(index -> importOne(index, accounts.get(index), upsert))
            .collectList()
            .map(this::summarizeImport);
    }

    public Mono<ProxyAccountEntity> update(String accountId, AccountDtos.UpdateAccountRequest request) {
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found")))
            .flatMap(entity -> updateManagedFields(entity, request))
            .onErrorMap(DuplicateKeyException.class, error -> accountEmailExists())
            .doOnNext(this::updateCachedAccount);
    }

    public Mono<Void> delete(String accountId) {
        return repository.deleteByAccountId(accountId)
            .doOnSuccess(ignored -> invalidateCachedAccount(accountId));
    }

    public Mono<AccountDtos.ClearAccountsResponse> clearAll() {
        String sql = """
            WITH deleted_accounts AS (
                DELETE FROM proxy_accounts
                RETURNING account_id
            )
            SELECT COUNT(*)::bigint AS deleted_count
            FROM deleted_accounts
            """;
        return template.getDatabaseClient().sql(sql)
            .map((row, metadata) -> row.get("deleted_count", Long.class))
            .one()
            .defaultIfEmpty(0L)
            .doOnNext(ignored -> clearSelectionState())
            .map(AccountDtos.ClearAccountsResponse::new);
    }

    public Mono<ProxyAccountEntity> suspend(String accountId, String reason, String message) {
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found")))
            .flatMap(entity -> {
                entity.setSuspendedAt(Instant.now());
                entity.setSuspendReason(reason);
                entity.setSuspendMessage(message);
                entity.setEnabled(false);
                return repository.save(entity);
            })
            .doOnNext(this::updateCachedAccount);
    }

    public Mono<ProxyAccountEntity> resetState(String accountId) {
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found")))
            .flatMap(entity -> {
                entity.setEnabled(true);
                entity.setErrorCount(0);
                entity.setQuotaExhaustedAt(null);
                entity.setSuspendedAt(null);
                entity.setSuspendReason(null);
                entity.setSuspendMessage(null);
                return repository.save(entity);
            })
            .doOnNext(this::updateCachedAccount);
    }

    public Mono<ProxyAccountEntity> selectAccount(String sessionHint) {
        return selectAccount(sessionHint, account -> true, "No available upstream account");
    }

    public Mono<ProxyAccountEntity> selectKiroAccount(String sessionHint) {
        return selectAccount(sessionHint, AccountPlatforms::isKiroCompatible, "No available Kiro upstream account");
    }

    public Mono<ProxyAccountEntity> selectKiroAccount(String sessionHint, Collection<String> excludedAccountIds) {
        return selectAccount(
            sessionHint,
            account -> AccountPlatforms.isKiroCompatible(account) && !isExcluded(account, excludedAccountIds),
            "No available Kiro upstream account"
        );
    }

    public Mono<ProxyAccountEntity> selectOpenAiAccount(String sessionHint) {
        return selectAccount(sessionHint, AccountPlatforms::isOpenAi, "No available OpenAI upstream account");
    }

    public Mono<ProxyAccountEntity> selectOpenAiAccount(String sessionHint, Collection<String> excludedAccountIds) {
        return selectAccount(
            sessionHint,
            account -> AccountPlatforms.isOpenAi(account) && !isExcluded(account, excludedAccountIds),
            "No available OpenAI upstream account"
        );
    }

    public Mono<ProxyAccountEntity> selectAnthropicAccount(String sessionHint) {
        return selectAccount(sessionHint, AccountPlatforms::isAnthropicApiKey, "No available Anthropic upstream account");
    }

    public Mono<ProxyAccountEntity> selectAnthropicAccount(String sessionHint, Collection<String> excludedAccountIds) {
        return selectAccount(
            sessionHint,
            account -> AccountPlatforms.isAnthropicApiKey(account) && !isExcluded(account, excludedAccountIds),
            "No available Anthropic upstream account"
        );
    }

    public Mono<ProxyAccountEntity> selectCodexOpenAiAccount(String sessionHint) {
        return selectAccount(sessionHint, AccountPlatforms::isCodex, "No available Codex upstream account");
    }

    public Mono<ProxyAccountEntity> selectCodexOpenAiAccount(String sessionHint, Collection<String> excludedAccountIds) {
        return selectAccount(
            sessionHint,
            account -> AccountPlatforms.isCodex(account) && !isExcluded(account, excludedAccountIds),
            "No available Codex upstream account"
        );
    }

    public Mono<ProxyAccountEntity> selectDeepSeekOpenAiAccount(String sessionHint) {
        return selectAccount(
            sessionHint,
            AccountPlatforms::isDeepSeek,
            "No available DeepSeek upstream account",
            false
        );
    }

    public Mono<ProxyAccountEntity> selectDeepSeekOpenAiAccount(String sessionHint, Collection<String> excludedAccountIds) {
        return selectAccount(
            sessionHint,
            account -> AccountPlatforms.isDeepSeek(account) && !isExcluded(account, excludedAccountIds),
            "No available DeepSeek upstream account",
            false
        );
    }

    private Mono<ProxyAccountEntity> selectAccount(
        String sessionHint,
        Predicate<ProxyAccountEntity> filter,
        String unavailableMessage
    ) {
        return selectAccount(sessionHint, filter, unavailableMessage, true);
    }

    private Mono<ProxyAccountEntity> selectAccount(
        String sessionHint,
        Predicate<ProxyAccountEntity> filter,
        String unavailableMessage,
        boolean allowCooldownFallback
    ) {
        List<ProxyAccountEntity> accounts = applyTierPolicy(cachedAccountsSnapshot(filter));
        if (accounts.isEmpty()) {
            return refreshCacheIfStale()
                .then(Mono.defer(() -> Mono.justOrEmpty(selectFrom(
                    applyTierPolicy(cachedAccountsSnapshot(filter)),
                    sessionHint,
                    allowCooldownFallback
                ))))
                .switchIfEmpty(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", unavailableMessage)));
        }
        return Mono.justOrEmpty(selectFrom(accounts, sessionHint, allowCooldownFallback))
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", unavailableMessage)));
    }

    private boolean isExcluded(ProxyAccountEntity account, Collection<String> excludedAccountIds) {
        return account != null
            && account.getAccountId() != null
            && excludedAccountIds != null
            && excludedAccountIds.contains(account.getAccountId());
    }

    public Mono<Void> recordSuccess(String accountId, long tokens) {
        long safeTokens = Math.max(tokens, 0);
        Instant totalQuotaResetAt = nextTotalQuotaResetAt();
        String sql = """
            UPDATE proxy_accounts
            SET request_count = request_count + 1,
                error_count = 0,
                quota_used = CASE
                    WHEN quota_reset_at IS NOT NULL AND quota_reset_at <= CURRENT_TIMESTAMP
                        THEN :tokens
                    ELSE COALESCE(quota_used, 0) + :tokens
                END,
                quota_reset_at = CASE
                    WHEN quota_reset_at IS NOT NULL AND quota_reset_at <= CURRENT_TIMESTAMP
                        THEN :totalQuotaResetAt
                    ELSE quota_reset_at
                END,
                quota_5h_used = CASE
                    WHEN quota_5h_reset_at IS NULL OR quota_5h_reset_at <= CURRENT_TIMESTAMP
                        THEN :tokens
                    ELSE COALESCE(quota_5h_used, 0) + :tokens
                END,
                quota_5h_reset_at = CASE
                    WHEN quota_5h_reset_at IS NULL OR quota_5h_reset_at <= CURRENT_TIMESTAMP
                        THEN CURRENT_TIMESTAMP + INTERVAL '5 hours'
                    ELSE quota_5h_reset_at
                END,
                quota_7d_used = CASE
                    WHEN quota_7d_reset_at IS NULL OR quota_7d_reset_at <= CURRENT_TIMESTAMP
                        THEN :tokens
                    ELSE COALESCE(quota_7d_used, 0) + :tokens
                END,
                quota_7d_reset_at = CASE
                    WHEN quota_7d_reset_at IS NULL OR quota_7d_reset_at <= CURRENT_TIMESTAMP
                        THEN CURRENT_TIMESTAMP + INTERVAL '7 days'
                    ELSE quota_7d_reset_at
                END,
                last_used_at = CURRENT_TIMESTAMP,
                updated_at = clock_timestamp()
            WHERE account_id = :accountId
            RETURNING *
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("tokens", safeTokens)
            .bind("totalQuotaResetAt", totalQuotaResetAt)
            .bind("accountId", accountId)
            .map((row, metadata) -> template.getConverter().read(ProxyAccountEntity.class, row, metadata))
            .one()
            .doOnNext(this::updateCachedAccount)
            .then();
    }

    public Mono<Void> recordTestSuccess(String accountId, long tokens, boolean updateStatus) {
        if (!updateStatus) {
            return recordSuccess(accountId, tokens);
        }
        long safeTokens = Math.max(tokens, 0);
        Instant totalQuotaResetAt = nextTotalQuotaResetAt();
        String sql = """
            UPDATE proxy_accounts
            SET request_count = request_count + 1,
                error_count = 0,
                quota_used = CASE
                    WHEN quota_reset_at IS NOT NULL AND quota_reset_at <= CURRENT_TIMESTAMP
                        THEN :tokens
                    ELSE COALESCE(quota_used, 0) + :tokens
                END,
                quota_reset_at = CASE
                    WHEN quota_reset_at IS NOT NULL AND quota_reset_at <= CURRENT_TIMESTAMP
                        THEN :totalQuotaResetAt
                    ELSE quota_reset_at
                END,
                quota_5h_used = CASE
                    WHEN quota_5h_reset_at IS NULL OR quota_5h_reset_at <= CURRENT_TIMESTAMP
                        THEN :tokens
                    ELSE COALESCE(quota_5h_used, 0) + :tokens
                END,
                quota_5h_reset_at = CASE
                    WHEN quota_5h_reset_at IS NULL OR quota_5h_reset_at <= CURRENT_TIMESTAMP
                        THEN CURRENT_TIMESTAMP + INTERVAL '5 hours'
                    ELSE quota_5h_reset_at
                END,
                quota_7d_used = CASE
                    WHEN quota_7d_reset_at IS NULL OR quota_7d_reset_at <= CURRENT_TIMESTAMP
                        THEN :tokens
                    ELSE COALESCE(quota_7d_used, 0) + :tokens
                END,
                quota_7d_reset_at = CASE
                    WHEN quota_7d_reset_at IS NULL OR quota_7d_reset_at <= CURRENT_TIMESTAMP
                        THEN CURRENT_TIMESTAMP + INTERVAL '7 days'
                    ELSE quota_7d_reset_at
                END,
                enabled = TRUE,
                quota_exhausted_at = NULL,
                suspended_at = NULL,
                suspend_reason = NULL,
                suspend_message = NULL,
                last_used_at = CURRENT_TIMESTAMP,
                updated_at = clock_timestamp()
            WHERE account_id = :accountId
            RETURNING *
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("tokens", safeTokens)
            .bind("totalQuotaResetAt", totalQuotaResetAt)
            .bind("accountId", accountId)
            .map((row, metadata) -> template.getConverter().read(ProxyAccountEntity.class, row, metadata))
            .one()
            .doOnNext(this::updateCachedAccount)
            .then();
    }

    public Mono<Void> recordError(String accountId, UpstreamErrorType type, Integer statusCode) {
        return recordError(accountId, type, statusCode, true);
    }

    public Mono<Void> recordError(
        String accountId,
        UpstreamErrorType type,
        Integer statusCode,
        boolean rateLimitIsQuota
    ) {
        if (type == UpstreamErrorType.FATAL) {
            return Mono.empty();
        }
        String sql = """
            UPDATE proxy_accounts
            SET error_count = COALESCE(error_count, 0) + 1,
                quota_exhausted_at = CASE WHEN :quotaError THEN CURRENT_TIMESTAMP ELSE quota_exhausted_at END,
                last_used_at = CURRENT_TIMESTAMP,
                updated_at = clock_timestamp()
            WHERE account_id = :accountId
            RETURNING *
            """;
        boolean quotaError = isQuotaStatus(statusCode, rateLimitIsQuota);
        return template.getDatabaseClient().sql(sql)
            .bind("quotaError", quotaError)
            .bind("accountId", accountId)
            .map((row, metadata) -> template.getConverter().read(ProxyAccountEntity.class, row, metadata))
            .one()
            .doOnNext(this::updateCachedAccount)
            .then();
    }

    public Mono<RequestFailureRecord> recordRequestFailure(
        String accountId,
        UpstreamErrorType type,
        Integer statusCode,
        String message
    ) {
        return recordRequestFailure(accountId, type, statusCode, message, true);
    }

    public Mono<RequestFailureRecord> recordRequestFailure(
        String accountId,
        UpstreamErrorType type,
        Integer statusCode,
        String message,
        boolean rateLimitIsQuota
    ) {
        Instant now = Instant.now();
        boolean quotaFailure = isQuotaStatus(statusCode, rateLimitIsQuota);
        boolean authFailure = isAuthStatus(statusCode);
        boolean suspended = quotaFailure || authFailure;
        String sql = """
            UPDATE proxy_accounts
            SET error_count = COALESCE(error_count, 0) + 1,
                last_used_at = :now,
                enabled = CASE WHEN :suspended THEN FALSE ELSE enabled END,
                quota_exhausted_at = CASE
                    WHEN :quotaFailure THEN :now
                    WHEN :authFailure THEN NULL
                    ELSE quota_exhausted_at
                END,
                suspended_at = CASE WHEN :suspended THEN :now ELSE suspended_at END,
                suspend_reason = CASE
                    WHEN :quotaFailure THEN 'QUOTA_EXHAUSTED'
                    WHEN :authFailure THEN 'AUTH_FAILED'
                    ELSE suspend_reason
                END,
                suspend_message = CASE WHEN :suspended THEN :message ELSE suspend_message END,
                updated_at = clock_timestamp()
            WHERE account_id = :accountId
            RETURNING *
            """;
        var spec = template.getDatabaseClient().sql(sql)
            .bind("now", now)
            .bind("suspended", suspended)
            .bind("quotaFailure", quotaFailure)
            .bind("authFailure", authFailure)
            .bind("accountId", accountId);
        spec = message == null ? spec.bindNull("message", String.class) : spec.bind("message", message);
        return spec
            .map((row, metadata) -> template.getConverter().read(ProxyAccountEntity.class, row, metadata))
            .one()
            .doOnNext(this::updateCachedAccount)
            .map(updated -> new RequestFailureRecord(
                updated.getAccountId(),
                Optional.ofNullable(updated.getErrorCount()).orElse(0),
                statusCode,
                suspended
            ));
    }

    public Mono<Void> recordTestFailure(
        String accountId,
        UpstreamErrorType type,
        Integer statusCode,
        String message,
        boolean updateStatus
    ) {
        return recordTestFailure(accountId, type, statusCode, message, updateStatus, true);
    }

    public Mono<Void> recordTestFailure(
        String accountId,
        UpstreamErrorType type,
        Integer statusCode,
        String message,
        boolean updateStatus,
        boolean rateLimitIsQuota
    ) {
        if (!updateStatus || (Integer.valueOf(429).equals(statusCode) && !rateLimitIsQuota)) {
            return recordError(accountId, type, statusCode, rateLimitIsQuota);
        }
        Instant now = Instant.now();
        boolean quotaFailure = isQuotaStatus(statusCode, rateLimitIsQuota);
        boolean authFailure = isAuthStatus(statusCode);
        String reason = quotaFailure ? "QUOTA_EXHAUSTED" : authFailure ? "AUTH_FAILED" : "ACCOUNT_TEST_FAILED";
        String sql = """
            UPDATE proxy_accounts
            SET error_count = COALESCE(error_count, 0) + 1,
                last_used_at = :now,
                enabled = FALSE,
                quota_exhausted_at = CASE WHEN :quotaFailure THEN :now ELSE NULL END,
                suspended_at = :now,
                suspend_reason = :reason,
                suspend_message = :message,
                updated_at = clock_timestamp()
            WHERE account_id = :accountId
            RETURNING *
            """;
        var spec = template.getDatabaseClient().sql(sql)
            .bind("now", now)
            .bind("quotaFailure", quotaFailure)
            .bind("reason", reason)
            .bind("accountId", accountId);
        spec = message == null ? spec.bindNull("message", String.class) : spec.bind("message", message);
        return spec
            .map((row, metadata) -> template.getConverter().read(ProxyAccountEntity.class, row, metadata))
            .one()
            .doOnNext(this::updateCachedAccount)
            .then();
    }

    private boolean isQuotaStatus(Integer statusCode, boolean rateLimitIsQuota) {
        return statusCode != null && (statusCode == 402 || (statusCode == 429 && rateLimitIsQuota));
    }

    private boolean isAuthStatus(Integer statusCode) {
        return statusCode != null && (statusCode == 401 || statusCode == 403);
    }

    public Mono<Void> refreshCache() {
        return Mono.defer(this::singleFlightCacheRefresh);
    }

    public synchronized void invalidateAllCaches() {
        refreshGeneration.incrementAndGet();
        inFlightCacheRefresh = null;
        lastCacheRefreshAt = Instant.EPOCH;
        clearSelectionState();
    }

    private synchronized Mono<Void> singleFlightCacheRefresh() {
        Mono<Void> current = inFlightCacheRefresh;
        if (current != null) {
            return current;
        }
        long generation = refreshGeneration.incrementAndGet();
        Mono<Void> created = Mono.defer(() -> {
            long refreshVersion = cacheVersion.incrementAndGet();
            return repository.findAllByEnabledTrue()
                .collectList()
                .doOnNext(accounts -> mergeCacheSnapshot(accounts, refreshVersion))
                .then()
                .doOnSuccess(ignored -> lastCacheRefreshAt = Instant.now());
        })
            .doFinally(signal -> clearInFlightCacheRefresh(generation))
            .cache();
        inFlightCacheRefreshGeneration = generation;
        inFlightCacheRefresh = created;
        return created;
    }

    private Mono<Void> refreshCacheIfStale() {
        return Mono.defer(() -> {
            if (lastCacheRefreshAt.isAfter(Instant.now().minus(CACHE_MISS_REFRESH_INTERVAL))) {
                return Mono.empty();
            }
            return refreshCache();
        });
    }

    private synchronized void clearInFlightCacheRefresh(long generation) {
        if (inFlightCacheRefreshGeneration == generation) {
            inFlightCacheRefresh = null;
        }
    }

    private synchronized void mergeCacheSnapshot(List<ProxyAccountEntity> accounts, long refreshVersion) {
        if (refreshVersion < lastCacheClearVersion) {
            return;
        }

        Map<String, ProxyAccountEntity> snapshot = new HashMap<>();
        for (ProxyAccountEntity account : accounts) {
            if (account == null || account.getAccountId() == null || !Boolean.TRUE.equals(account.getEnabled())) {
                continue;
            }
            snapshot.merge(account.getAccountId(), account, AccountPoolService::newerAccount);
        }

        snapshot.forEach((accountId, account) -> accountCache.compute(accountId, (ignored, current) -> {
            if (current != null && current.version() > refreshVersion) {
                return current;
            }
            return CachedAccountEntry.active(account, refreshVersion);
        }));
        accountCache.forEach((accountId, ignored) -> {
            if (snapshot.containsKey(accountId)) {
                return;
            }
            accountCache.computeIfPresent(accountId, (key, current) ->
                current.version() > refreshVersion ? current : null
            );
        });
        rebuildAccountTopology();
    }

    private void updateCachedAccount(ProxyAccountEntity updated) {
        if (updated == null || updated.getAccountId() == null) {
            return;
        }
        String accountId = updated.getAccountId();
        long version = cacheVersion.incrementAndGet();
        boolean enabled = Boolean.TRUE.equals(updated.getEnabled());
        int cacheChange = replaceCachedEntry(
            accountId,
            enabled ? CachedAccountEntry.active(updated, version) : CachedAccountEntry.tombstone(version)
        );
        if (cacheChange == CACHE_TOPOLOGY_CHANGED) {
            rebuildAccountTopology();
        }
        if (cacheChange != CACHE_UPDATE_SKIPPED && !enabled) {
            invalidateAffinityForAccount(accountId);
        }
    }

    private void invalidateCachedAccount(String accountId) {
        if (accountId == null) {
            return;
        }
        long version = cacheVersion.incrementAndGet();
        int cacheChange = replaceCachedEntry(accountId, CachedAccountEntry.tombstone(version));
        if (cacheChange == CACHE_TOPOLOGY_CHANGED) {
            rebuildAccountTopology();
        }
        if (cacheChange != CACHE_UPDATE_SKIPPED) {
            invalidateAffinityForAccount(accountId);
        }
    }

    private int replaceCachedEntry(String accountId, CachedAccountEntry replacement) {
        while (true) {
            CachedAccountEntry current = accountCache.get(accountId);
            if (current != null && current.version() > replacement.version()) {
                return CACHE_UPDATE_SKIPPED;
            }
            if (current == null) {
                if (accountCache.putIfAbsent(accountId, replacement) == null) {
                    return replacement.active() ? CACHE_TOPOLOGY_CHANGED : CACHE_CONTENT_CHANGED;
                }
                continue;
            }
            if (accountCache.replace(accountId, current, replacement)) {
                return current.active() == replacement.active() ? CACHE_CONTENT_CHANGED : CACHE_TOPOLOGY_CHANGED;
            }
        }
    }

    private List<ProxyAccountEntity> cachedAccountsSnapshot(Predicate<ProxyAccountEntity> filter) {
        List<String> accountIds = cachedAccountIds;
        List<ProxyAccountEntity> accounts = new ArrayList<>(accountIds.size());
        for (String accountId : accountIds) {
            CachedAccountEntry entry = accountCache.get(accountId);
            if (entry != null && entry.active() && filter.test(entry.account())) {
                accounts.add(entry.account());
            }
        }
        return accounts;
    }

    private synchronized void rebuildAccountTopology() {
        cachedAccountIds = accountCache.values().stream()
            .filter(CachedAccountEntry::active)
            .map(CachedAccountEntry::account)
            .sorted(ACCOUNT_TOPOLOGY_ORDER)
            .map(ProxyAccountEntity::getAccountId)
            .toList();
    }

    private void invalidateAffinityForAccount(String accountId) {
        affinity.asMap().entrySet().removeIf(entry -> accountId.equals(entry.getValue()));
    }

    private ProxyAccountEntity selectFrom(
        List<ProxyAccountEntity> accounts,
        String sessionHint,
        boolean allowCooldownFallback
    ) {
        if (properties.isSessionAffinityEnabled() && sessionHint != null && !sessionHint.isBlank()) {
            String affinityAccountId = affinity.getIfPresent(sessionHint);
            if (affinityAccountId != null) {
                for (ProxyAccountEntity account : accounts) {
                    if (affinityAccountId.equals(account.getAccountId()) && isAvailable(account)) {
                        return account;
                    }
                }
                affinity.invalidate(sessionHint);
            }
        }

        List<ProxyAccountEntity> available = new ArrayList<>(accounts.stream().filter(this::isAvailable).toList());
        if (available.isEmpty() && allowCooldownFallback) {
            available = accounts.stream()
                .filter(account -> !isQuotaExhausted(account))
                .min(Comparator.comparing(account -> Optional.ofNullable(account.getLastUsedAt()).orElse(Instant.EPOCH)))
                .map(List::of)
                .orElse(List.of());
        }
        if (available.isEmpty()) {
            return null;
        }

        ProxyAccountEntity selected;
        if (properties.getAccountSelectionStrategy() == ProxyProperties.AccountSelectionStrategy.STICKY) {
            selected = available.get(0);
        } else if (shouldUseWeights(available)) {
            selected = weightedPick(available);
        } else {
            int index = Math.floorMod(nextIndex.getAndIncrement(), available.size());
            selected = available.get(index);
        }
        if (properties.isSessionAffinityEnabled() && sessionHint != null && !sessionHint.isBlank()) {
            affinity.put(sessionHint, selected.getAccountId());
        }
        return selected;
    }

    private boolean shouldUseWeights(List<ProxyAccountEntity> accounts) {
        return accounts.stream().anyMatch(account -> effectiveWeight(account) != 100);
    }

    private ProxyAccountEntity weightedPick(List<ProxyAccountEntity> accounts) {
        int total = accounts.stream()
            .mapToInt(this::effectiveWeight)
            .sum();
        if (total <= 0) {
            return accounts.get(Math.floorMod(nextIndex.getAndIncrement(), accounts.size()));
        }
        int cursor = ThreadLocalRandom.current().nextInt(total);
        int seen = 0;
        for (ProxyAccountEntity account : accounts) {
            seen += effectiveWeight(account);
            if (cursor < seen) {
                return account;
            }
        }
        return accounts.get(accounts.size() - 1);
    }

    private List<ProxyAccountEntity> applyTierPolicy(List<ProxyAccountEntity> accounts) {
        return poolPolicyService == null ? accounts : poolPolicyService.applyTierPolicy(accounts);
    }

    private int effectiveWeight(ProxyAccountEntity account) {
        return poolPolicyService == null
            ? AccountPoolPolicyService.normalizeWeight(account == null ? null : account.getPoolWeight())
            : poolPolicyService.effectiveWeight(account);
    }

    private boolean isAvailable(ProxyAccountEntity account) {
        if (Boolean.FALSE.equals(account.getEnabled())) {
            return false;
        }
        if (account.getSuspendedAt() != null) {
            return false;
        }
        if (isQuotaExhausted(account)) {
            return false;
        }
        int errors = Optional.ofNullable(account.getErrorCount()).orElse(0);
        if (errors <= 0 || account.getLastUsedAt() == null) {
            return true;
        }
        Duration cooldown = properties.getBaseCooldown().multipliedBy(Math.min(1L << Math.min(errors - 1, 10), 1440));
        boolean cooledDown = account.getLastUsedAt().plus(cooldown).isBefore(Instant.now());
        return cooledDown || ThreadLocalRandom.current().nextDouble() < properties.getProbabilisticRetryChance();
    }

    private boolean isQuotaExhausted(ProxyAccountEntity account) {
        Instant now = Instant.now();
        boolean totalQuotaWindowActive = account.getQuotaResetAt() == null || account.getQuotaResetAt().isAfter(now);
        if (totalQuotaWindowActive && account.getQuotaExhaustedAt() != null) {
            return true;
        }
        Long used = account.getQuotaUsed();
        Long limit = account.getQuotaLimit();
        if (totalQuotaWindowActive && limit != null && limit > 0 && used != null && used >= limit) {
            return true;
        }
        return isWindowQuotaExhausted(account.getQuota5hUsed(), account.getQuota5hLimit(), account.getQuota5hResetAt(), now)
            || isWindowQuotaExhausted(account.getQuota7dUsed(), account.getQuota7dLimit(), account.getQuota7dResetAt(), now);
    }

    private boolean isWindowQuotaExhausted(Long used, Long limit, Instant resetAt, Instant now) {
        if (limit == null || limit <= 0 || used == null || used < limit) {
            return false;
        }
        return resetAt == null || resetAt.isAfter(now);
    }

    private Instant nextTotalQuotaResetAt() {
        Duration reset = Optional.ofNullable(properties.getQuotaReset()).orElse(Duration.ofHours(1));
        return Instant.now().plus(reset);
    }

    private synchronized void clearSelectionState() {
        long clearVersion = cacheVersion.incrementAndGet();
        lastCacheClearVersion = clearVersion;
        accountCache.clear();
        cachedAccountIds = List.of();
        affinity.invalidateAll();
        nextIndex.set(0);
    }

    long affinityEntryCount() {
        affinity.cleanUp();
        return affinity.estimatedSize();
    }

    private static Duration positiveAffinityTtl(Duration configuredTtl) {
        if (configuredTtl == null || configuredTtl.isZero() || configuredTtl.isNegative()) {
            return Duration.ofMinutes(10);
        }
        return configuredTtl;
    }

    private static ProxyAccountEntity newerAccount(ProxyAccountEntity first, ProxyAccountEntity second) {
        Instant firstUpdatedAt = first.getUpdatedAt();
        Instant secondUpdatedAt = second.getUpdatedAt();
        if (firstUpdatedAt == null) {
            return second;
        }
        if (secondUpdatedAt == null) {
            return first;
        }
        return secondUpdatedAt.isAfter(firstUpdatedAt) ? second : first;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Mono<AccountDtos.ImportAccountItemResponse> importOne(
        int index,
        AccountDtos.CreateAccountRequest request,
        boolean upsert
    ) {
        if (request == null) {
            return Mono.just(importFailure(index, null, null, "Account item is null"));
        }
        if (request.accessToken() == null || request.accessToken().isBlank()) {
            return Mono.just(importFailure(index, request.accountId(), request.email(), "accessToken / apiKey / credentials.accessToken is required"));
        }

        return insertImportedAccount(request, upsert, 0)
            .doOnNext(result -> updateCachedAccount(result.account()))
            .map(result -> result.inserted()
                ? importSuccess(index, "CREATED", importCreatedMessage(request, result.account()), result.account())
                : importSuccess(index, "UPDATED", "Account updated", result.account()))
            .switchIfEmpty(Mono.defer(() -> findImportedEmail(request)
                .map(existing -> importSuccess(index, "SKIPPED", "Account email already exists", existing))
                .switchIfEmpty(Mono.error(new ApiException(
                    HttpStatus.CONFLICT,
                    "ACCOUNT_EMAIL_EXISTS",
                    "Account email already exists"
                )))))
            .onErrorResume(error -> Mono.just(importFailure(index, request.accountId(), request.email(), error.getMessage())));
    }

    private Mono<AccountWriteResult> insertImportedAccount(
        AccountDtos.CreateAccountRequest request,
        boolean upsert,
        int attempt
    ) {
        ProxyAccountEntity entity = newAccount(request);
        if (attempt > 0) {
            entity.setAccountId(Hashing.shortUuid());
        }
        String conflictAction = upsert
            ? """
                DO UPDATE SET
                    email = EXCLUDED.email,
                    access_token = EXCLUDED.access_token,
                    refresh_token = EXCLUDED.refresh_token,
                    client_id = EXCLUDED.client_id,
                    client_secret = EXCLUDED.client_secret,
                    region = EXCLUDED.region,
                    auth_method = EXCLUDED.auth_method,
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    upstream_account_id = EXCLUDED.upstream_account_id,
                    id_token = EXCLUDED.id_token,
                    token_expires_at = EXCLUDED.token_expires_at,
                    profile_arn = EXCLUDED.profile_arn,
                    machine_id = EXCLUDED.machine_id,
                    proxy_url = EXCLUDED.proxy_url,
                    pool_tier = EXCLUDED.pool_tier,
                    pool_weight = EXCLUDED.pool_weight,
                    quota_limit = EXCLUDED.quota_limit,
                    quota_5h_limit = EXCLUDED.quota_5h_limit,
                    quota_7d_limit = EXCLUDED.quota_7d_limit,
                    updated_at = clock_timestamp()
                """
            : "DO NOTHING";
        String sql = """
            INSERT INTO proxy_accounts AS target (
                account_id, email, access_token, refresh_token, client_id, client_secret,
                region, auth_method, provider, base_url, upstream_account_id, id_token,
                token_expires_at, profile_arn, machine_id, proxy_url, pool_tier, pool_weight,
                quota_limit, quota_5h_limit, quota_7d_limit
            ) VALUES (
                :accountId, :email, :accessToken, :refreshToken, :clientId, :clientSecret,
                :region, :authMethod, :provider, :baseUrl, :upstreamAccountId, :idToken,
                :tokenExpiresAt, :profileArn, :machineId, :proxyUrl, :poolTier, :poolWeight,
                :quotaLimit, :quota5hLimit, :quota7dLimit
            )
            ON CONFLICT (lower(email)) WHERE email IS NOT NULL
            """ + conflictAction + """
            RETURNING target.*, (target.xmax = 0) AS inserted
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("accountId", entity.getAccountId())
            .bind("accessToken", entity.getAccessToken())
            .bind("poolTier", entity.getPoolTier())
            .bind("poolWeight", entity.getPoolWeight());
        spec = bindNullable(spec, "email", entity.getEmail(), String.class);
        spec = bindNullable(spec, "refreshToken", entity.getRefreshToken(), String.class);
        spec = bindNullable(spec, "clientId", entity.getClientId(), String.class);
        spec = bindNullable(spec, "clientSecret", entity.getClientSecret(), String.class);
        spec = bindNullable(spec, "region", entity.getRegion(), String.class);
        spec = bindNullable(spec, "authMethod", entity.getAuthMethod(), String.class);
        spec = bindNullable(spec, "provider", entity.getProvider(), String.class);
        spec = bindNullable(spec, "baseUrl", entity.getBaseUrl(), String.class);
        spec = bindNullable(spec, "upstreamAccountId", entity.getUpstreamAccountId(), String.class);
        spec = bindNullable(spec, "idToken", entity.getIdToken(), String.class);
        spec = bindNullable(spec, "tokenExpiresAt", entity.getTokenExpiresAt(), Instant.class);
        spec = bindNullable(spec, "profileArn", entity.getProfileArn(), String.class);
        spec = bindNullable(spec, "machineId", entity.getMachineId(), String.class);
        spec = bindNullable(spec, "proxyUrl", entity.getProxyUrl(), String.class);
        spec = bindNullable(spec, "quotaLimit", entity.getQuotaLimit(), Long.class);
        spec = bindNullable(spec, "quota5hLimit", entity.getQuota5hLimit(), Long.class);
        spec = bindNullable(spec, "quota7dLimit", entity.getQuota7dLimit(), Long.class);
        return spec
            .map((row, metadata) -> new AccountWriteResult(
                template.getConverter().read(ProxyAccountEntity.class, row, metadata),
                Boolean.TRUE.equals(row.get("inserted", Boolean.class))
            ))
            .one()
            .onErrorResume(DuplicateKeyException.class, error -> {
                if (attempt >= 5) {
                    return Mono.error(new ApiException(
                        HttpStatus.CONFLICT,
                        "ACCOUNT_ID_COLLISION",
                        "Unable to allocate a unique account ID"
                    ));
                }
                return insertImportedAccount(request, upsert, attempt + 1);
            });
    }

    private Mono<ProxyAccountEntity> findImportedEmail(AccountDtos.CreateAccountRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        return normalizedEmail == null
            ? Mono.empty()
            : repository.findFirstByEmailOrderByCreatedAtAsc(normalizedEmail);
    }

    private ProxyAccountEntity newAccount(AccountDtos.CreateAccountRequest request) {
        ProxyAccountEntity entity = new ProxyAccountEntity();
        entity.setAccountId(Optional.ofNullable(request.accountId()).filter(s -> !s.isBlank()).orElseGet(Hashing::shortUuid));
        entity.setEnabled(true);
        entity.setRequestCount(0L);
        entity.setErrorCount(0);
        entity.setQuotaUsed(0L);
        entity.setQuota5hUsed(0L);
        entity.setQuota7dUsed(0L);
        applyImportedFields(entity, request);
        return entity;
    }

    private void applyImportedFields(ProxyAccountEntity entity, AccountDtos.CreateAccountRequest request) {
        String normalizedAuthMethod = AccountPlatforms.normalizeAuthMethod(request.authMethod(), request.provider());
        String normalizedProvider = AccountPlatforms.normalizeProvider(request.provider(), normalizedAuthMethod);
        entity.setEmail(normalizeEmail(request.email()));
        entity.setAccessToken(request.accessToken());
        entity.setRefreshToken(request.refreshToken());
        entity.setClientId(request.clientId());
        entity.setClientSecret(request.clientSecret());
        boolean regionlessApiKey = AccountPlatforms.isOpenAi(normalizedProvider, normalizedAuthMethod)
            || AccountPlatforms.isDeepSeek(normalizedProvider)
            || AccountPlatforms.isGoogleApiKey(normalizedProvider, normalizedAuthMethod)
            || AccountPlatforms.isAnthropicApiKey(normalizedProvider, normalizedAuthMethod);
        entity.setRegion(defaultString(request.region(), regionlessApiKey ? null : AccountPlatforms.DEFAULT_KIRO_REGION));
        entity.setAuthMethod(normalizedAuthMethod);
        entity.setProvider(normalizedProvider);
        entity.setBaseUrl(AccountPlatforms.defaultBaseUrl(normalizedProvider, normalizedAuthMethod, request.baseUrl()));
        entity.setUpstreamAccountId(request.upstreamAccountId());
        entity.setIdToken(request.idToken());
        entity.setTokenExpiresAt(request.tokenExpiresAt());
        entity.setProfileArn(request.profileArn());
        entity.setMachineId(request.machineId());
        entity.setProxyUrl(request.proxyUrl());
        entity.setPoolTier(normalizePoolTier(request.poolTier()));
        entity.setPoolWeight(AccountPoolPolicyService.normalizeWeight(request.poolWeight()));
        entity.setQuotaLimit(request.quotaLimit());
        entity.setQuota5hLimit(request.quota5hLimit());
        entity.setQuota7dLimit(request.quota7dLimit());
    }

    private Mono<ProxyAccountEntity> updateManagedFields(
        ProxyAccountEntity current,
        AccountDtos.UpdateAccountRequest request
    ) {
        Map<String, SqlValue> updates = new LinkedHashMap<>();
        putIfPresent(updates, "email", request.email(), normalizeEmail(request.email()), String.class);
        putIfPresent(updates, "access_token", request.accessToken(), request.accessToken(), String.class);
        putIfPresent(updates, "refresh_token", request.refreshToken(), request.refreshToken(), String.class);
        putIfPresent(updates, "client_id", request.clientId(), request.clientId(), String.class);
        putIfPresent(updates, "client_secret", request.clientSecret(), request.clientSecret(), String.class);
        putIfPresent(updates, "region", request.region(), request.region(), String.class);

        String provider = current.getProvider();
        String authMethod = current.getAuthMethod();
        String baseUrl = current.getBaseUrl();
        if (request.authMethod() != null || request.provider() != null) {
            String currentAuthMethod = AccountPlatforms.normalizeAuthMethod(authMethod, provider);
            String currentProvider = AccountPlatforms.normalizeProvider(provider, currentAuthMethod);
            String requestedProvider = request.provider() == null ? provider : request.provider();
            String requestedAuthMethod = request.authMethod() == null ? authMethod : request.authMethod();
            authMethod = AccountPlatforms.normalizeAuthMethod(requestedAuthMethod, requestedProvider);
            provider = AccountPlatforms.normalizeProvider(requestedProvider, authMethod);
            boolean providerChanged = !AccountPlatforms.key(currentProvider).equals(AccountPlatforms.key(provider));
            boolean endpointFamilyChanged = !Objects.equals(
                AccountPlatforms.defaultBaseUrl(currentProvider, currentAuthMethod, null),
                AccountPlatforms.defaultBaseUrl(provider, authMethod, null)
            );
            baseUrl = request.baseUrl() == null && (providerChanged || endpointFamilyChanged)
                ? AccountPlatforms.defaultBaseUrl(provider, authMethod, null)
                : AccountPlatforms.defaultBaseUrl(provider, authMethod, baseUrl);
            updates.put("auth_method", new SqlValue(authMethod, String.class));
            updates.put("provider", new SqlValue(provider, String.class));
            updates.put("base_url", new SqlValue(baseUrl, String.class));
        }
        if (request.baseUrl() != null) {
            updates.put("base_url", new SqlValue(
                AccountPlatforms.defaultBaseUrl(provider, authMethod, request.baseUrl()),
                String.class
            ));
        }

        putIfPresent(updates, "upstream_account_id", request.upstreamAccountId(), request.upstreamAccountId(), String.class);
        putIfPresent(updates, "id_token", request.idToken(), request.idToken(), String.class);
        putIfPresent(updates, "token_expires_at", request.tokenExpiresAt(), request.tokenExpiresAt(), Instant.class);
        putIfPresent(updates, "profile_arn", request.profileArn(), request.profileArn(), String.class);
        putIfPresent(updates, "machine_id", request.machineId(), request.machineId(), String.class);
        putIfPresent(updates, "proxy_url", request.proxyUrl(), request.proxyUrl(), String.class);
        putIfPresent(updates, "pool_tier", request.poolTier(), normalizePoolTier(request.poolTier()), String.class);
        putIfPresent(
            updates,
            "pool_weight",
            request.poolWeight(),
            AccountPoolPolicyService.normalizeWeight(request.poolWeight()),
            Integer.class
        );
        putIfPresent(updates, "enabled", request.enabled(), request.enabled(), Boolean.class);
        putIfPresent(updates, "quota_used", request.quotaUsed(), request.quotaUsed(), Long.class);
        putIfPresent(updates, "quota_limit", request.quotaLimit(), request.quotaLimit(), Long.class);
        putIfPresent(updates, "quota_reset_at", request.quotaResetAt(), request.quotaResetAt(), Instant.class);
        putIfPresent(updates, "quota_5h_used", request.quota5hUsed(), request.quota5hUsed(), Long.class);
        putIfPresent(updates, "quota_5h_limit", request.quota5hLimit(), request.quota5hLimit(), Long.class);
        putIfPresent(updates, "quota_5h_reset_at", request.quota5hResetAt(), request.quota5hResetAt(), Instant.class);
        putIfPresent(updates, "quota_7d_used", request.quota7dUsed(), request.quota7dUsed(), Long.class);
        putIfPresent(updates, "quota_7d_limit", request.quota7dLimit(), request.quota7dLimit(), Long.class);
        putIfPresent(updates, "quota_7d_reset_at", request.quota7dResetAt(), request.quota7dResetAt(), Instant.class);

        List<Map.Entry<String, SqlValue>> entries = List.copyOf(updates.entrySet());
        List<String> assignments = new ArrayList<>(entries.size() + 1);
        for (int index = 0; index < entries.size(); index++) {
            assignments.add(entries.get(index).getKey() + " = :value" + index);
        }
        assignments.add("updated_at = clock_timestamp()");
        String sql = "UPDATE proxy_accounts SET " + String.join(", ", assignments)
            + " WHERE account_id = :accountId RETURNING *";
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("accountId", current.getAccountId());
        for (int index = 0; index < entries.size(); index++) {
            SqlValue value = entries.get(index).getValue();
            spec = value.value() == null
                ? spec.bindNull("value" + index, value.type())
                : spec.bind("value" + index, value.value());
        }
        return spec
            .map((row, metadata) -> template.getConverter().read(ProxyAccountEntity.class, row, metadata))
            .one()
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found")));
    }

    private static void putIfPresent(
        Map<String, SqlValue> updates,
        String column,
        Object requestedValue,
        Object persistedValue,
        Class<?> type
    ) {
        if (requestedValue != null) {
            updates.put(column, new SqlValue(persistedValue, type));
        }
    }

    private static <T> GenericExecuteSpec bindNullable(
        GenericExecuteSpec spec,
        String name,
        T value,
        Class<T> type
    ) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private ApiException accountEmailExists() {
        return new ApiException(HttpStatus.CONFLICT, "ACCOUNT_EMAIL_EXISTS", "Account email already exists");
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePoolTier(String value) {
        return AccountPoolPolicyService.normalizePoolTier(value);
    }

    private String importCreatedMessage(AccountDtos.CreateAccountRequest request, ProxyAccountEntity saved) {
        String requestedAccountId = Optional.ofNullable(request.accountId()).filter(s -> !s.isBlank()).orElse(null);
        if (requestedAccountId != null && !Objects.equals(requestedAccountId, saved.getAccountId())) {
            return "Account created with generated accountId";
        }
        return "Account created";
    }

    private AccountDtos.ImportAccountsResponse summarizeImport(List<AccountDtos.ImportAccountItemResponse> items) {
        List<AccountDtos.ImportAccountItemResponse> ordered = items.stream()
            .sorted(Comparator.comparing(AccountDtos.ImportAccountItemResponse::index))
            .toList();
        int created = countStatus(ordered, "CREATED");
        int updated = countStatus(ordered, "UPDATED");
        int skipped = countStatus(ordered, "SKIPPED");
        int failed = countStatus(ordered, "FAILED");
        return new AccountDtos.ImportAccountsResponse(ordered.size(), created, updated, skipped, failed, ordered);
    }

    private int countStatus(List<AccountDtos.ImportAccountItemResponse> items, String status) {
        return (int) items.stream().filter(item -> status.equals(item.status())).count();
    }

    private AccountDtos.ImportAccountItemResponse importSuccess(
        int index,
        String status,
        String message,
        ProxyAccountEntity entity
    ) {
        return new AccountDtos.ImportAccountItemResponse(
            index,
            entity.getAccountId(),
            entity.getEmail(),
            status,
            message,
            AccountMapper.toResponse(entity)
        );
    }

    private AccountDtos.ImportAccountItemResponse importFailure(int index, String accountId, String email, String message) {
        return new AccountDtos.ImportAccountItemResponse(index, accountId, email, "FAILED", message, null);
    }

    public enum UpstreamErrorType {
        FATAL,
        RECOVERABLE
    }

    public record RequestFailureRecord(
        String accountId,
        int consecutiveFailures,
        Integer statusCode,
        boolean suspended
    ) {
    }

    static record AccountWriteResult(ProxyAccountEntity account, boolean inserted) {
    }

    private record SqlValue(Object value, Class<?> type) {
    }

    private record CachedAccountEntry(ProxyAccountEntity account, long version) {
        static CachedAccountEntry active(ProxyAccountEntity account, long version) {
            return new CachedAccountEntry(account, version);
        }

        static CachedAccountEntry tombstone(long version) {
            return new CachedAccountEntry(null, version);
        }

        boolean active() {
            return account != null;
        }
    }
}
