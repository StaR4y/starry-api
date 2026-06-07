package xyz.star4y.api.account;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.common.Hashing;
import xyz.star4y.api.config.ProxyProperties;

@Service
public class AccountPoolService {

    private static final Logger log = LoggerFactory.getLogger(AccountPoolService.class);

    private final ProxyAccountRepository repository;
    private final R2dbcEntityTemplate template;
    private final ProxyProperties properties;
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final Map<String, AffinityEntry> affinity = new ConcurrentHashMap<>();
    private volatile List<ProxyAccountEntity> cachedAccounts = List.of();
    private static final int IMPORT_CONCURRENCY = 8;

    public AccountPoolService(
        ProxyAccountRepository repository,
        R2dbcEntityTemplate template,
        ProxyProperties properties
    ) {
        this.repository = repository;
        this.template = template;
        this.properties = properties;
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
        return repository.save(newAccount(request))
            .onErrorMap(
                DuplicateKeyException.class,
                error -> new ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "Account ID already exists")
            )
            .flatMap(saved -> refreshCache().thenReturn(saved));
    }

    public Mono<AccountDtos.ImportAccountsResponse> importAccounts(AccountDtos.ImportAccountsRequest request) {
        List<AccountDtos.CreateAccountRequest> accounts = request.accounts();
        if (accounts == null || accounts.isEmpty()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ACCOUNT_IMPORT", "Account import list is empty"));
        }
        boolean upsert = Boolean.TRUE.equals(request.upsert());
        return Flux.range(0, accounts.size())
            .flatMap(index -> importOne(index, accounts.get(index), upsert), IMPORT_CONCURRENCY)
            .collectList()
            .map(this::summarizeImport)
            .flatMap(response -> refreshCache().thenReturn(response));
    }

    public Mono<ProxyAccountEntity> update(String accountId, AccountDtos.UpdateAccountRequest request) {
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found")))
            .flatMap(entity -> {
                if (request.email() != null) entity.setEmail(request.email());
                if (request.accessToken() != null) entity.setAccessToken(request.accessToken());
                if (request.refreshToken() != null) entity.setRefreshToken(request.refreshToken());
                if (request.clientId() != null) entity.setClientId(request.clientId());
                if (request.clientSecret() != null) entity.setClientSecret(request.clientSecret());
                if (request.region() != null) entity.setRegion(request.region());
                if (request.authMethod() != null || request.provider() != null) {
                    String requestedProvider = request.provider() == null ? entity.getProvider() : request.provider();
                    String requestedAuthMethod = request.authMethod() == null ? entity.getAuthMethod() : request.authMethod();
                    String normalizedAuthMethod = AccountPlatforms.normalizeAuthMethod(requestedAuthMethod, requestedProvider);
                    String normalizedProvider = AccountPlatforms.normalizeProvider(requestedProvider, normalizedAuthMethod);
                    entity.setAuthMethod(normalizedAuthMethod);
                    entity.setProvider(normalizedProvider);
                    entity.setBaseUrl(AccountPlatforms.defaultBaseUrl(normalizedProvider, normalizedAuthMethod, entity.getBaseUrl()));
                }
                if (request.baseUrl() != null) {
                    entity.setBaseUrl(AccountPlatforms.defaultBaseUrl(entity.getProvider(), entity.getAuthMethod(), request.baseUrl()));
                }
                if (request.upstreamAccountId() != null) entity.setUpstreamAccountId(request.upstreamAccountId());
                if (request.idToken() != null) entity.setIdToken(request.idToken());
                if (request.profileArn() != null) entity.setProfileArn(request.profileArn());
                if (request.machineId() != null) entity.setMachineId(request.machineId());
                if (request.proxyUrl() != null) entity.setProxyUrl(request.proxyUrl());
                if (request.enabled() != null) entity.setEnabled(request.enabled());
                if (request.quotaUsed() != null) entity.setQuotaUsed(request.quotaUsed());
                if (request.quotaLimit() != null) entity.setQuotaLimit(request.quotaLimit());
                if (request.quotaResetAt() != null) entity.setQuotaResetAt(request.quotaResetAt());
                return repository.save(entity);
            })
            .flatMap(saved -> refreshCache().thenReturn(saved));
    }

    public Mono<Void> delete(String accountId) {
        return repository.deleteByAccountId(accountId).then(refreshCache());
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
            .flatMap(saved -> refreshCache().thenReturn(saved));
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
            .flatMap(saved -> refreshCache().thenReturn(saved));
    }

    public Mono<ProxyAccountEntity> selectAccount(String sessionHint) {
        return selectAccount(sessionHint, account -> true, "No available upstream account");
    }

    public Mono<ProxyAccountEntity> selectKiroAccount(String sessionHint) {
        return selectAccount(sessionHint, AccountPlatforms::isKiroCompatible, "No available Kiro upstream account");
    }

    private Mono<ProxyAccountEntity> selectAccount(
        String sessionHint,
        Predicate<ProxyAccountEntity> filter,
        String unavailableMessage
    ) {
        List<ProxyAccountEntity> accounts = cachedAccounts.stream().filter(filter).toList();
        if (accounts.isEmpty()) {
            return refreshCache()
                .then(Mono.defer(() -> Mono.justOrEmpty(selectFrom(cachedAccounts.stream().filter(filter).toList(), sessionHint))))
                .switchIfEmpty(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", unavailableMessage)));
        }
        return Mono.justOrEmpty(selectFrom(accounts, sessionHint))
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", unavailableMessage)));
    }

    public Mono<Void> recordSuccess(String accountId, long tokens) {
        String sql = """
            UPDATE proxy_accounts
            SET request_count = request_count + 1,
                error_count = 0,
                last_used_at = CURRENT_TIMESTAMP(3)
            WHERE account_id = :accountId
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("accountId", accountId)
            .fetch()
            .rowsUpdated()
            .then(refreshCache());
    }

    public Mono<Void> recordTestSuccess(String accountId, long tokens, boolean updateStatus) {
        if (!updateStatus) {
            return recordSuccess(accountId, tokens);
        }
        String sql = """
            UPDATE proxy_accounts
            SET request_count = request_count + 1,
                error_count = 0,
                enabled = TRUE,
                quota_exhausted_at = NULL,
                suspended_at = NULL,
                suspend_reason = NULL,
                suspend_message = NULL,
                last_used_at = CURRENT_TIMESTAMP(3)
            WHERE account_id = :accountId
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("accountId", accountId)
            .fetch()
            .rowsUpdated()
            .then(refreshCache());
    }

    public Mono<Void> recordError(String accountId, UpstreamErrorType type, Integer statusCode) {
        if (type == UpstreamErrorType.FATAL) {
            return Mono.empty();
        }
        String sql = """
            UPDATE proxy_accounts
            SET error_count = error_count + 1,
                quota_exhausted_at = CASE WHEN :quotaError THEN CURRENT_TIMESTAMP(3) ELSE quota_exhausted_at END,
                last_used_at = CURRENT_TIMESTAMP(3)
            WHERE account_id = :accountId
            """;
        boolean quotaError = statusCode != null && (statusCode == 402 || statusCode == 429);
        return template.getDatabaseClient().sql(sql)
            .bind("quotaError", quotaError)
            .bind("accountId", accountId)
            .fetch()
            .rowsUpdated()
            .then(refreshCache());
    }

    public Mono<Void> recordTestFailure(
        String accountId,
        UpstreamErrorType type,
        Integer statusCode,
        String message,
        boolean updateStatus
    ) {
        if (!updateStatus) {
            return recordError(accountId, type, statusCode);
        }
        Instant now = Instant.now();
        return repository.findByAccountId(accountId)
            .flatMap(entity -> {
                entity.setErrorCount(Optional.ofNullable(entity.getErrorCount()).orElse(0) + 1);
                entity.setLastUsedAt(now);
                if (isQuotaStatus(statusCode)) {
                    entity.setQuotaExhaustedAt(now);
                    suspendForTestFailure(entity, "QUOTA_EXHAUSTED", message, now);
                } else if (isAuthStatus(statusCode)) {
                    suspendForTestFailure(entity, "AUTH_FAILED", message, now);
                    entity.setQuotaExhaustedAt(null);
                } else {
                    suspendForTestFailure(entity, "ACCOUNT_TEST_FAILED", message, now);
                    entity.setQuotaExhaustedAt(null);
                }
                return repository.save(entity);
            })
            .then(refreshCache());
    }

    private void suspendForTestFailure(ProxyAccountEntity entity, String reason, String message, Instant now) {
        entity.setEnabled(false);
        entity.setSuspendedAt(now);
        entity.setSuspendReason(reason);
        entity.setSuspendMessage(message);
    }

    private boolean isQuotaStatus(Integer statusCode) {
        return statusCode != null && (statusCode == 402 || statusCode == 429);
    }

    private boolean isAuthStatus(Integer statusCode) {
        return statusCode != null && (statusCode == 401 || statusCode == 403);
    }

    public Mono<Void> refreshCache() {
        return repository.findAllByEnabledTrue()
            .collectList()
            .doOnNext(accounts -> cachedAccounts = List.copyOf(accounts))
            .then();
    }

    private ProxyAccountEntity selectFrom(List<ProxyAccountEntity> accounts, String sessionHint) {
        cleanupAffinity();
        if (accounts.size() == 1) {
            return accounts.get(0);
        }
        if (properties.isSessionAffinityEnabled() && sessionHint != null && !sessionHint.isBlank()) {
            AffinityEntry entry = affinity.get(sessionHint);
            if (entry != null && !entry.expired(properties.getSessionAffinityTtl())) {
                for (ProxyAccountEntity account : accounts) {
                    if (entry.accountId().equals(account.getAccountId()) && isAvailable(account)) {
                        entry.touch();
                        return account;
                    }
                }
            }
        }

        List<ProxyAccountEntity> available = new ArrayList<>(accounts.stream().filter(this::isAvailable).toList());
        if (available.isEmpty()) {
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
        } else {
            int index = Math.floorMod(nextIndex.getAndIncrement(), available.size());
            selected = available.get(index);
        }
        if (properties.isSessionAffinityEnabled() && sessionHint != null && !sessionHint.isBlank()) {
            affinity.put(sessionHint, new AffinityEntry(selected.getAccountId(), Instant.now()));
        }
        return selected;
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
        if (account.getQuotaResetAt() != null && !account.getQuotaResetAt().isAfter(now)) {
            return false;
        }
        if (account.getQuotaExhaustedAt() != null) {
            return true;
        }
        Long used = account.getQuotaUsed();
        Long limit = account.getQuotaLimit();
        return limit != null && limit > 0 && used != null && used >= limit;
    }

    private void cleanupAffinity() {
        Duration ttl = properties.getSessionAffinityTtl();
        affinity.entrySet().removeIf(entry -> entry.getValue().expired(ttl));
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

        String requestedAccountId = Optional.ofNullable(request.accountId()).filter(s -> !s.isBlank()).orElse(null);
        if (requestedAccountId == null) {
            return repository.save(newAccount(request))
                .map(saved -> importSuccess(index, "CREATED", "Account created", saved))
                .onErrorResume(error -> Mono.just(importFailure(index, null, request.email(), error.getMessage())));
        }

        return repository.findByAccountId(requestedAccountId)
            .flatMap(existing -> {
                if (!upsert) {
                    return Mono.just(importSuccess(index, "SKIPPED", "Account already exists", existing));
                }
                applyImportedFields(existing, request);
                return repository.save(existing).map(saved -> importSuccess(index, "UPDATED", "Account updated", saved));
            })
            .switchIfEmpty(Mono.defer(() -> repository.save(newAccount(request))
                .map(saved -> importSuccess(index, "CREATED", "Account created", saved))))
            .onErrorResume(error -> Mono.just(importFailure(index, requestedAccountId, request.email(), error.getMessage())));
    }

    private ProxyAccountEntity newAccount(AccountDtos.CreateAccountRequest request) {
        ProxyAccountEntity entity = new ProxyAccountEntity();
        entity.setAccountId(Optional.ofNullable(request.accountId()).filter(s -> !s.isBlank()).orElseGet(Hashing::shortUuid));
        entity.setEnabled(true);
        entity.setRequestCount(0L);
        entity.setErrorCount(0);
        entity.setQuotaUsed(0L);
        applyImportedFields(entity, request);
        return entity;
    }

    private void applyImportedFields(ProxyAccountEntity entity, AccountDtos.CreateAccountRequest request) {
        String normalizedAuthMethod = AccountPlatforms.normalizeAuthMethod(request.authMethod(), request.provider());
        String normalizedProvider = AccountPlatforms.normalizeProvider(request.provider(), normalizedAuthMethod);
        entity.setEmail(request.email());
        entity.setAccessToken(request.accessToken());
        entity.setRefreshToken(request.refreshToken());
        entity.setClientId(request.clientId());
        entity.setClientSecret(request.clientSecret());
        entity.setRegion(defaultString(request.region(), AccountPlatforms.isOpenAi(normalizedProvider, normalizedAuthMethod) ? null : AccountPlatforms.DEFAULT_KIRO_REGION));
        entity.setAuthMethod(normalizedAuthMethod);
        entity.setProvider(normalizedProvider);
        entity.setBaseUrl(AccountPlatforms.defaultBaseUrl(normalizedProvider, normalizedAuthMethod, request.baseUrl()));
        entity.setUpstreamAccountId(request.upstreamAccountId());
        entity.setIdToken(request.idToken());
        entity.setProfileArn(request.profileArn());
        entity.setMachineId(request.machineId());
        entity.setProxyUrl(request.proxyUrl());
        entity.setQuotaLimit(request.quotaLimit());
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

    private record AffinityEntry(String accountId, Instant lastSeenAt) {
        boolean expired(Duration ttl) {
            return lastSeenAt.plus(ttl).isBefore(Instant.now());
        }

        void touch() {
            // Records are immutable; replacement is not needed for read-heavy affinity.
        }
    }
}
