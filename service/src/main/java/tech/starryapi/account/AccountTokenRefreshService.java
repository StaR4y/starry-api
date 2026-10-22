package tech.starryapi.account;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.Hashing;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AccountTokenRefreshService {

    private static final Logger log = LoggerFactory.getLogger(AccountTokenRefreshService.class);
    private static final int SCHEDULED_REFRESH_CONCURRENCY = 4;
    private static final String KIRO_AUTH_ENDPOINT = "https://prod.us-east-1.auth.desktop.kiro.dev";
    private static final Duration CODEX_REFRESH_WINDOW = Duration.ofHours(2);
    private static final String KIRO_VERSION = "0.12.155";
    private static final String AWS_SDK_VERSION = "1.0.34";
    private static final String AWS_STREAMING_API_VERSION = "1.0.34";

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final ProxyAccountRepository repository;
    private final AccountPoolService accountPoolService;
    private final SystemMaintenanceTaskGate maintenanceTaskGate;
    private final Map<String, Mono<ProxyAccountEntity>> inFlightRefreshes = new ConcurrentHashMap<>();

    public AccountTokenRefreshService(
        ObjectMapper objectMapper,
        WebClient upstreamWebClient,
        ProxyAccountRepository repository,
        AccountPoolService accountPoolService
    ) {
        this(
            objectMapper,
            upstreamWebClient,
            repository,
            accountPoolService,
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction()
        );
    }

    @Autowired
    public AccountTokenRefreshService(
        ObjectMapper objectMapper,
        WebClient upstreamWebClient,
        ProxyAccountRepository repository,
        AccountPoolService accountPoolService,
        SystemMaintenanceTaskGate maintenanceTaskGate
    ) {
        this.objectMapper = objectMapper;
        this.webClient = upstreamWebClient;
        this.repository = repository;
        this.accountPoolService = accountPoolService;
        this.maintenanceTaskGate = maintenanceTaskGate;
    }

    public Mono<ProxyAccountEntity> refresh(ProxyAccountEntity account) {
        String accountId = clean(account.getAccountId());
        if (accountId == null) {
            return Mono.error(refreshError("Account ID is empty"));
        }
        // Single flight per account: concurrent callers share one refresh. Several callers refreshing the same
        // single-use refresh token would make all but the first fail with "refresh token already used", and the
        // scheduled job treats that as a dead credential. The entry is removed when the refresh itself finishes
        // (doFinally sits BEFORE cache(), so one caller cancelling cannot drop it while it is still running) and
        // only if it is still this refresh that is registered.
        return inFlightRefreshes.computeIfAbsent(accountId, ignored -> {
            java.util.concurrent.atomic.AtomicReference<Mono<ProxyAccountEntity>> self = new java.util.concurrent.atomic.AtomicReference<>();
            Mono<ProxyAccountEntity> shared = refreshInternal(account)
                .doFinally(signal -> inFlightRefreshes.remove(accountId, self.get()))
                .cache();
            self.set(shared);
            return shared;
        });
    }

    @Scheduled(
        fixedDelayString = "${starry.proxy.token-refresh-interval-ms:3600000}",
        initialDelayString = "${starry.proxy.token-refresh-initial-delay-ms:60000}"
    )
    public void scheduledRefresh() {
        maintenanceTaskGate.whenOperational(this::refreshEligibleAccounts)
            .subscribe(
                summary -> log.info(
                    "Scheduled upstream token refresh completed: total={}, eligible={}, refreshed={}, failed={}",
                    summary.total(),
                    summary.eligible(),
                    summary.refreshed(),
                    summary.failed()
                ),
                error -> log.warn("Scheduled upstream token refresh failed", error)
            );
    }

    public Mono<TokenRefreshSummary> refreshEligibleAccounts() {
        return repository.findAllByEnabledTrue()
            .collectList()
            .flatMap(accounts -> {
                List<ProxyAccountEntity> eligible = accounts.stream()
                    .filter(this::shouldRefreshScheduled)
                    .toList();
                if (eligible.isEmpty()) {
                    return Mono.just(new TokenRefreshSummary(accounts.size(), 0, 0, 0));
                }
                return Flux.fromIterable(eligible)
                    .flatMap(account -> refresh(account)
                        .thenReturn(Boolean.TRUE)
                        .onErrorResume(error -> {
                            log.warn(
                                "Scheduled upstream token refresh failed for account {}: {}",
                                account.getAccountId(),
                                error.getMessage()
                            );
                            return recordScheduledRefreshFailure(account, error);
                        }), SCHEDULED_REFRESH_CONCURRENCY)
                    .collectList()
                    .map(results -> {
                        int refreshed = (int) results.stream().filter(Boolean::booleanValue).count();
                        return new TokenRefreshSummary(accounts.size(), eligible.size(), refreshed, eligible.size() - refreshed);
                    });
            });
    }

    private Mono<ProxyAccountEntity> refreshInternal(ProxyAccountEntity account) {
        String accountId = clean(account.getAccountId());
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(refreshError("Account not found while loading latest token")))
            .flatMap(latest -> {
                if (hasNewerStoredAccessToken(account, latest)) {
                    return accountPoolService.refreshCache().thenReturn(latest);
                }

                String refreshToken = clean(latest.getRefreshToken());
                if (refreshToken == null) {
                    return Mono.error(refreshError("Missing refreshToken"));
                }
                Mono<TokenRefreshResult> refreshed = refreshToken(latest, refreshToken);
                return refreshed
                    .flatMap(result -> saveRefreshedToken(latest, result))
                    .onErrorResume(error -> recoverFromPossibleRotatedToken(account, refreshToken, error));
            });
    }

    public boolean canRefresh(ProxyAccountEntity account) {
        if (clean(account.getRefreshToken()) == null) {
            return false;
        }
        if (AccountPlatforms.isCodex(account)) {
            return true;
        }
        if ("social".equalsIgnoreCase(account.getAuthMethod())) {
            return true;
        }
        return clean(account.getClientId()) != null && clean(account.getClientSecret()) != null;
    }

    private boolean shouldRefreshScheduled(ProxyAccountEntity account) {
        if (!canRefresh(account)) {
            return false;
        }
        if (!AccountPlatforms.isCodex(account)) {
            return true;
        }
        Instant expiresAt = account.getTokenExpiresAt();
        return expiresAt != null && !expiresAt.isAfter(Instant.now().plus(CODEX_REFRESH_WINDOW));
    }

    private Mono<TokenRefreshResult> refreshCodexToken(ProxyAccountEntity account, String refreshToken) {
        String clientId = firstNonBlank(account.getClientId(), OpenAiOAuthConstants.CLIENT_ID);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("client_id", clientId);
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);

        return webClient.post()
            .uri(OpenAiOAuthConstants.TOKEN_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(text -> Mono.error(refreshError(
                            "Codex token refresh failed " + response.statusCode().value() + ": " + text
                        )));
                }
                return response.bodyToMono(JsonNode.class).map(json -> codexTokenResult(json, clientId));
            });
    }

    private static final java.util.regex.Pattern AWS_REGION = java.util.regex.Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d{1,2}$");

    private Mono<TokenRefreshResult> refreshOidcToken(ProxyAccountEntity account, String refreshToken) {
        // The host name is built from the account's region and receives the client secret and refresh token, so a
        // value such as "evil.example/x" must never be accepted.
        if (!AWS_REGION.matcher(region(account)).matches()) {
            return Mono.error(refreshError("Invalid AWS region on the account"));
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("clientId", account.getClientId());
        body.put("clientSecret", account.getClientSecret());
        body.put("refreshToken", refreshToken);
        body.put("grantType", "refresh_token");

        return webClient.post()
            .uri("https://oidc." + region(account) + ".amazonaws.com/token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(text -> Mono.error(refreshError("OIDC refresh failed " + response.statusCode().value() + ": " + text)));
                }
                return response.bodyToMono(JsonNode.class).map(json -> tokenResult(json, refreshToken, account.getClientId()));
            });
    }

    private Mono<TokenRefreshResult> refreshSocialToken(ProxyAccountEntity account, String refreshToken) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("refreshToken", refreshToken);

        return webClient.post()
            .uri(KIRO_AUTH_ENDPOINT + "/refreshToken")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.USER_AGENT, kiroUserAgent(machineId(account)))
            .bodyValue(body)
            .exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(text -> Mono.error(refreshError("Social token refresh failed " + response.statusCode().value() + ": " + text)));
                }
                return response.bodyToMono(JsonNode.class).map(json -> tokenResult(json, refreshToken, account.getClientId()));
            });
    }

    private Mono<TokenRefreshResult> refreshToken(ProxyAccountEntity account, String refreshToken) {
        if (AccountPlatforms.isCodex(account)) {
            return refreshCodexToken(account, refreshToken);
        }
        boolean social = "social".equalsIgnoreCase(account.getAuthMethod());
        if (!social && (clean(account.getClientId()) == null || clean(account.getClientSecret()) == null)) {
            return Mono.error(refreshError("Missing OIDC refresh credentials: clientId/clientSecret"));
        }
        return social ? refreshSocialToken(account, refreshToken) : refreshOidcToken(account, refreshToken);
    }

    private Mono<ProxyAccountEntity> saveRefreshedToken(ProxyAccountEntity account, TokenRefreshResult result) {
        if (result.accessToken() == null || result.accessToken().isBlank()) {
            return Mono.error(refreshError("Refresh response does not contain accessToken"));
        }
        if (AccountPlatforms.isCodex(account) && clean(result.refreshToken()) == null) {
            return Mono.error(refreshError("Codex refresh response does not contain refresh_token; refusing to keep an already-used refresh token"));
        }
        String accountId = account.getAccountId();
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(refreshError("Account not found while saving refreshed token")))
            .flatMap(entity -> repository.updateCredentials(
                    accountId,
                    result.accessToken(),
                    firstNonBlank(result.refreshToken(), entity.getRefreshToken()),
                    firstNonBlank(result.idToken(), entity.getIdToken()),
                    firstNonBlank(result.clientId(), entity.getClientId()),
                    result.tokenExpiresAt()
                )
                .then(repository.findByAccountId(accountId)))
            .flatMap(saved -> accountPoolService.refreshCache().thenReturn(saved));
    }

    private Mono<Boolean> recordScheduledRefreshFailure(ProxyAccountEntity account, Throwable error) {
        if (!isNonRetryableRefreshError(error) || account.getAccountId() == null || account.getAccountId().isBlank()) {
            return Mono.just(Boolean.FALSE);
        }
        String message = rootFailure(error).getMessage();
        return accountPoolService.recordRequestFailure(
                account.getAccountId(),
                AccountPoolService.UpstreamErrorType.FATAL,
                403,
                message
            )
            .onErrorResume(recordError -> {
                log.warn("Failed to disable account {} after token refresh failure", account.getAccountId(), recordError);
                return Mono.empty();
            })
            .thenReturn(Boolean.FALSE);
    }

    private Mono<ProxyAccountEntity> recoverFromPossibleRotatedToken(
        ProxyAccountEntity requested,
        String attemptedRefreshToken,
        Throwable error
    ) {
        if (!isRefreshTokenReuseError(error)) {
            return Mono.error(error);
        }
        return loadRotatedAccount(requested, attemptedRefreshToken)
            .switchIfEmpty(Mono.delay(Duration.ofMillis(300)).then(loadRotatedAccount(requested, attemptedRefreshToken)))
            .switchIfEmpty(Mono.error(error))
            .flatMap(latest -> accountPoolService.refreshCache().thenReturn(latest));
    }

    private Mono<ProxyAccountEntity> loadRotatedAccount(ProxyAccountEntity requested, String attemptedRefreshToken) {
        String accountId = clean(requested.getAccountId());
        if (accountId == null) {
            return Mono.empty();
        }
        return repository.findByAccountId(accountId)
            .filter(latest -> !same(clean(latest.getRefreshToken()), attemptedRefreshToken))
            .filter(latest -> hasNewerStoredAccessToken(requested, latest));
    }

    private boolean hasNewerStoredAccessToken(ProxyAccountEntity requested, ProxyAccountEntity latest) {
        String latestAccessToken = clean(latest.getAccessToken());
        if (latestAccessToken == null) {
            return false;
        }
        if (!same(clean(requested.getAccessToken()), latestAccessToken)) {
            return true;
        }
        Instant requestedExpiry = requested.getTokenExpiresAt();
        Instant latestExpiry = latest.getTokenExpiresAt();
        return latestExpiry != null
            && (requestedExpiry == null || latestExpiry.isAfter(requestedExpiry))
            && latestExpiry.isAfter(Instant.now().plusSeconds(30));
    }

    private boolean isNonRetryableRefreshError(Throwable error) {
        String message = rootFailure(error).getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("invalid_grant")
            || normalized.contains("refresh_token_reused")
            || normalized.contains("refresh_token_invalidated")
            || (normalized.contains("refresh token") && normalized.contains("already used"))
            || (normalized.contains("refresh token") && normalized.contains("invalidated"))
            || normalized.contains("invalid_client")
            || normalized.contains("unauthorized_client")
            || normalized.contains("access_denied")
            || normalized.contains("app_session_terminated")
            || normalized.contains("token_revoked")
            || normalized.contains("session has ended")
            || normalized.contains("invalidated oauth token")
            || normalized.contains("no refresh token");
    }

    private boolean isRefreshTokenReuseError(Throwable error) {
        String message = rootFailure(error).getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("refresh_token_reused")
            || (normalized.contains("refresh token") && normalized.contains("already used"));
    }

    private Throwable rootFailure(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? error : current;
    }

    private TokenRefreshResult tokenResult(JsonNode json, String fallbackRefreshToken, String fallbackClientId) {
        return new TokenRefreshResult(
            firstNonBlank(json.path("accessToken").asString(null), json.path("access_token").asString(null)),
            firstNonBlank(json.path("refreshToken").asString(null), json.path("refresh_token").asString(null), fallbackRefreshToken),
            firstNonBlank(json.path("idToken").asString(null), json.path("id_token").asString(null)),
            firstNonBlank(json.path("clientId").asString(null), json.path("client_id").asString(null), fallbackClientId),
            tokenExpiresAt(json)
        );
    }

    private TokenRefreshResult codexTokenResult(JsonNode json, String clientId) {
        TokenRefreshResult result = tokenResult(json, null, clientId);
        Instant expiresAt = result.tokenExpiresAt();
        if (expiresAt == null) {
            expiresAt = OpenAiOAuthTokenClaims.tokenExpirationOrNull(objectMapper, result.accessToken());
        }
        return new TokenRefreshResult(
            result.accessToken(),
            result.refreshToken(),
            result.idToken(),
            result.clientId(),
            expiresAt
        );
    }

    private Instant tokenExpiresAt(JsonNode json) {
        Instant absolute = firstInstant(json, "tokenExpiresAt", "token_expires_at", "expiresAt", "expires_at");
        if (absolute != null) {
            return absolute;
        }
        Long expiresIn = firstLong(json, "expiresIn", "expires_in");
        return expiresIn == null ? null : Instant.now().plusSeconds(expiresIn);
    }

    private Instant firstInstant(JsonNode source, String... names) {
        if (source == null || !source.isObject()) {
            return null;
        }
        for (String name : names) {
            Instant value = instant(source.path(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Long firstLong(JsonNode source, String... names) {
        if (source == null || !source.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = source.path(name);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.canConvertToLong()) {
                return value.asLong(0L);
            }
            String text = value.isString() ? clean(value.asString("")) : clean(value.asString(null));
            if (text == null) {
                continue;
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // Ignore malformed optional expiry fields.
            }
        }
        return null;
    }

    private Instant instant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            if (node.canConvertToLong()) {
                return epochInstant(node.asLong(0L));
            }
            String value = node.isString() ? clean(node.asString("")) : clean(node.asString(null));
            if (value == null) {
                return null;
            }
            try {
                return epochInstant(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                return Instant.parse(value);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Instant epochInstant(long value) {
        return value > 9_999_999_999L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
    }

    private UpstreamException refreshError(String message) {
        return new UpstreamException(403, "Automatic token refresh failed: " + message, false);
    }

    private String region(ProxyAccountEntity account) {
        return firstNonBlank(account.getRegion(), "us-east-1");
    }

    private String machineId(ProxyAccountEntity account) {
        return firstNonBlank(account.getMachineId(), Hashing.sha256("kiro-device-" + account.getAccountId()));
    }

    private String kiroUserAgent(String machineId) {
        String os = System.getProperty("os.name", "linux").toLowerCase().contains("mac") ? "macos" : "linux";
        String release = System.getProperty("os.version", "0.0.0");
        String javaVersion = System.getProperty("java.version", "17");
        return "aws-sdk-js/%s ua/2.1 os/%s#%s lang/js md/nodejs#%s api/codewhispererstreaming#%s m/E KiroIDE-%s-%s"
            .formatted(AWS_SDK_VERSION, os, release, javaVersion, AWS_STREAMING_API_VERSION, KIRO_VERSION, machineId);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned != null) {
                return cleaned;
            }
        }
        return null;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private record TokenRefreshResult(
        String accessToken,
        String refreshToken,
        String idToken,
        String clientId,
        Instant tokenExpiresAt
    ) {
    }

    public record TokenRefreshSummary(int total, int eligible, int refreshed, int failed) {
    }
}
