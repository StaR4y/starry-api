package tech.starryapi.account;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.egress.AccountEgressTransport;
import tech.starryapi.egress.EgressException;
import tech.starryapi.egress.ProxyChannelService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OpenAiOAuthLoginService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiOAuthLoginService.class);
    private static final Duration AUTHORIZATION_TTL = Duration.ofMinutes(15);
    private static final Duration SESSION_TTL = Duration.ofMinutes(20);
    private static final Duration UPSTREAM_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_ACTIVE_LOGINS_PER_ADMIN = 3;
    private static final int MAX_LOGIN_SESSIONS = 128;
    private static final int MIN_POLL_INTERVAL_SECONDS = 1;
    private static final int MAX_POLL_INTERVAL_SECONDS = 30;

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final ProxyAccountRepository repository;
    private final AccountPoolService accountPoolService;
    private final ProxyChannelService proxyChannels;
    private final String authBaseUrl;
    private final String clientId;
    private final Clock clock;
    private final Cache<String, LoginSession> sessions;
    private final Map<String, Mono<AccountDtos.ChatGptOAuthStatusResponse>> inFlightPolls =
        new ConcurrentHashMap<>();
    private final Object loginLimitMonitor = new Object();
    private final Map<String, Integer> startingLoginsByOwner = new HashMap<>();

    @Autowired
    public OpenAiOAuthLoginService(
        ObjectMapper objectMapper,
        WebClient upstreamWebClient,
        ProxyAccountRepository repository,
        AccountPoolService accountPoolService,
        ProxyChannelService proxyChannels
    ) {
        this(
            objectMapper,
            upstreamWebClient,
            repository,
            accountPoolService,
            proxyChannels,
            OpenAiOAuthConstants.AUTH_BASE_URL,
            OpenAiOAuthConstants.CLIENT_ID,
            Clock.systemUTC()
        );
    }

    OpenAiOAuthLoginService(
        ObjectMapper objectMapper,
        WebClient webClient,
        ProxyAccountRepository repository,
        AccountPoolService accountPoolService,
        ProxyChannelService proxyChannels,
        String authBaseUrl,
        String clientId,
        Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.webClient = webClient;
        this.repository = repository;
        this.accountPoolService = accountPoolService;
        this.proxyChannels = proxyChannels;
        this.authBaseUrl = authBaseUrl.replaceAll("/+$", "");
        this.clientId = clientId;
        this.clock = clock;
        this.sessions = Caffeine.newBuilder()
            .maximumSize(MAX_LOGIN_SESSIONS)
            .expireAfterWrite(SESSION_TTL)
            .build();
    }

    public Mono<AccountDtos.ChatGptOAuthStartResponse> start(
        String adminUserId,
        AccountDtos.ChatGptOAuthStartRequest request
    ) {
        String owner = requireOwner(adminUserId);
        String targetAccountId = clean(request == null ? null : request.accountId());
        UUID proxyChannelId = request == null ? null : request.proxyChannelId();
        return validateTarget(targetAccountId)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(target -> validateChannel(proxyChannelId)
                .then(Mono.defer(() -> {
                    // The whole login uses one exit: the chosen channel, else the reauthorized account's own binding.
                    LoginEgress egress = new LoginEgress(proxyChannelId, proxyChannelId == null ? target.orElse(null) : null);
                    StartReservation reservation = reserveLoginSlot(owner);
                    return requestDeviceCode(owner, targetAccountId, proxyChannelId, egress)
                        .doFinally(signal -> reservation.release());
                })));
    }

    private Mono<Void> validateChannel(UUID proxyChannelId) {
        return proxyChannelId == null ? Mono.empty() : proxyChannels.requireUsable(proxyChannelId);
    }

    public Mono<AccountDtos.ChatGptOAuthStatusResponse> poll(String adminUserId, String loginId) {
        String owner = requireOwner(adminUserId);
        LoginSession session = requireSession(owner, loginId);
        Instant now = clock.instant();
        synchronized (session) {
            expireIfNecessary(session, now);
            if (session.state != LoginState.PENDING) {
                return Mono.just(statusResponse(session));
            }
            if (now.isBefore(session.nextPollAt)) {
                return Mono.just(statusResponse(session));
            }
            session.nextPollAt = now.plusSeconds(session.pollIntervalSeconds);
        }
        return inFlightPolls.computeIfAbsent(session.loginId, ignored -> Mono.defer(() -> pollUpstream(session))
            .doFinally(signal -> inFlightPolls.remove(session.loginId))
            .cache());
    }

    public AccountDtos.ChatGptOAuthStatusResponse cancel(String adminUserId, String loginId) {
        LoginSession session = requireSession(requireOwner(adminUserId), loginId);
        synchronized (session) {
            if (session.state == LoginState.COMPLETING) {
                throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CHATGPT_OAUTH_COMPLETING",
                    "ChatGPT authorization is being completed and can no longer be cancelled"
                );
            }
            if (session.state == LoginState.PENDING) {
                session.state = LoginState.CANCELLED;
                session.message = "授权已取消";
                session.deviceAuthId = null;
            }
            return statusResponse(session);
        }
    }

    private Mono<ProxyAccountEntity> validateTarget(String accountId) {
        if (accountId == null) {
            return Mono.empty();
        }
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "ACCOUNT_NOT_FOUND",
                "Account not found"
            )))
            .flatMap(account -> {
                if (!AccountPlatforms.isCodex(account)) {
                    return Mono.<ProxyAccountEntity>error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "CHATGPT_OAUTH_ACCOUNT_UNSUPPORTED",
                        "Only Codex OAuth accounts can be reauthorized"
                    ));
                }
                return Mono.just(account);
            });
    }

    private Mono<AccountDtos.ChatGptOAuthStartResponse> requestDeviceCode(
        String owner,
        String targetAccountId,
        UUID proxyChannelId,
        LoginEgress egress
    ) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("client_id", clientId);
        return webClient.post()
            .uri(authBaseUrl + "/api/accounts/deviceauth/usercode")
            .attributes(egress::apply)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(response -> {
                if (response.statusCode().value() == 404) {
                    return response.releaseBody().then(Mono.error(new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "CHATGPT_DEVICE_AUTH_UNAVAILABLE",
                        "OpenAI device authorization is not available for this account or region"
                    )));
                }
                if (response.statusCode().isError()) {
                    return response.releaseBody().then(Mono.error(authUnavailable(
                        "OpenAI device authorization returned " + response.statusCode().value()
                    )));
                }
                return response.bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(authProtocolError("OpenAI returned an empty device authorization response")));
            })
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .map(json -> createSession(owner, targetAccountId, proxyChannelId, egress, json))
            .onErrorMap(error -> !(error instanceof ApiException), error -> authUnavailable(
                routeUnavailable(error, "Unable to reach OpenAI device authorization")
            ));
    }

    private AccountDtos.ChatGptOAuthStartResponse createSession(
        String owner,
        String targetAccountId,
        UUID proxyChannelId,
        LoginEgress egress,
        JsonNode json
    ) {
        String deviceAuthId = clean(json.path("device_auth_id").asString(null));
        String userCode = clean(json.path("user_code").asString(null));
        if (userCode == null) {
            userCode = clean(json.path("usercode").asString(null));
        }
        if (deviceAuthId == null || userCode == null) {
            throw authProtocolError("OpenAI device authorization response is missing required fields");
        }
        int interval = pollInterval(json.path("interval"));
        Instant now = clock.instant();
        String loginId = "cgo-" + Hashing.randomToken(16);
        LoginSession session = new LoginSession(
            loginId,
            owner,
            targetAccountId,
            deviceAuthId,
            userCode,
            now.plus(AUTHORIZATION_TTL),
            interval,
            now,
            proxyChannelId,
            egress
        );
        sessions.put(loginId, session);
        return new AccountDtos.ChatGptOAuthStartResponse(
            loginId,
            authBaseUrl + "/codex/device",
            userCode,
            session.expiresAt,
            interval
        );
    }

    private Mono<AccountDtos.ChatGptOAuthStatusResponse> pollUpstream(LoginSession session) {
        DevicePollRequest pollRequest;
        synchronized (session) {
            expireIfNecessary(session, clock.instant());
            if (session.state != LoginState.PENDING) {
                return Mono.just(statusResponse(session));
            }
            if (session.deviceAuthId == null) {
                session.state = LoginState.FAILED;
                session.message = "授权会话状态异常，请重新发起";
                return Mono.just(statusResponse(session));
            }
            pollRequest = new DevicePollRequest(session.deviceAuthId, session.userCode);
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("device_auth_id", pollRequest.deviceAuthId);
        body.put("user_code", pollRequest.userCode);
        return webClient.post()
            .uri(authBaseUrl + "/api/accounts/deviceauth/token")
            .attributes(session.egress::apply)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (response.statusCode().is2xxSuccessful()) {
                    return response.bodyToMono(JsonNode.class)
                        .switchIfEmpty(Mono.error(authProtocolError("OpenAI returned an empty authorization response")));
                }
                if (status == 403 || status == 404) {
                    noteWaiting(session);
                    return response.releaseBody().then(Mono.empty());
                }
                if (status == 429 || response.statusCode().is5xxServerError()) {
                    noteTransientFailure(session);
                    return response.releaseBody().then(Mono.empty());
                }
                return response.releaseBody().then(Mono.error(authProtocolError(
                    "OpenAI device authorization failed with status " + status
                )));
            })
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .flatMap(json -> completeAuthorization(session, json))
            .switchIfEmpty(Mono.fromSupplier(() -> pendingStatus(session)))
            .onErrorResume(error -> {
                if (!(error instanceof ApiException)) {
                    EgressException egress = EgressException.find(error);
                    // The device code stays valid, so a route that fails now may still recover before it expires.
                    noteTransientFailure(session, egress == null ? null : routeUnavailable(error, null));
                    return Mono.just(pendingStatus(session));
                }
                return Mono.just(failedStatus(session, error));
            });
    }

    private Mono<AccountDtos.ChatGptOAuthStatusResponse> completeAuthorization(
        LoginSession session,
        JsonNode json
    ) {
        String authorizationCode = clean(json.path("authorization_code").asString(null));
        String codeVerifier = clean(json.path("code_verifier").asString(null));
        String codeChallenge = clean(json.path("code_challenge").asString(null));
        if (authorizationCode == null || codeVerifier == null || codeChallenge == null) {
            return Mono.error(authProtocolError("OpenAI authorization response is missing required fields"));
        }
        if (!validPkcePair(codeVerifier, codeChallenge)) {
            return Mono.error(authProtocolError("OpenAI authorization returned an invalid PKCE pair"));
        }
        synchronized (session) {
            expireIfNecessary(session, clock.instant());
            if (session.state != LoginState.PENDING) {
                return Mono.just(statusResponse(session));
            }
            session.state = LoginState.COMPLETING;
            session.message = "正在保存授权账号";
        }
        return exchangeTokens(authorizationCode, codeVerifier, session.egress)
            .flatMap(tokens -> saveAuthorizedAccount(session, tokens))
            .map(account -> succeededStatus(session, account))
            .onErrorResume(error -> Mono.just(failedStatus(session, error)));
    }

    private Mono<OAuthTokens> exchangeTokens(String authorizationCode, String codeVerifier, LoginEgress egress) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        form.add("redirect_uri", authBaseUrl + "/deviceauth/callback");
        form.add("client_id", clientId);
        form.add("code_verifier", codeVerifier);
        return webClient.post()
            .uri(authBaseUrl + "/oauth/token")
            .attributes(egress::apply)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(form)
            .exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    return response.releaseBody().then(Mono.error(authProtocolError(
                        "OpenAI token exchange failed with status " + response.statusCode().value()
                    )));
                }
                return response.bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(authProtocolError("OpenAI returned an empty token response")));
            })
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .onErrorMap(error -> EgressException.find(error) != null, error -> authUnavailable(
                routeUnavailable(error, "Unable to reach OpenAI token exchange")
            ))
            .map(json -> new OAuthTokens(
                clean(json.path("access_token").asString(null)),
                clean(json.path("refresh_token").asString(null)),
                clean(json.path("id_token").asString(null))
            ))
            .map(tokens -> {
                if (tokens.accessToken == null || tokens.refreshToken == null || tokens.idToken == null) {
                    throw authProtocolError("OpenAI token response is missing required tokens");
                }
                return tokens;
            });
    }

    private Mono<ProxyAccountEntity> saveAuthorizedAccount(LoginSession session, OAuthTokens tokens) {
        OpenAiOAuthTokenClaims.Identity identity = OpenAiOAuthTokenClaims.parse(
            objectMapper,
            tokens.idToken,
            tokens.accessToken,
            clock
        );
        String stableAccountId = stableAccountId(identity);
        if (session.targetAccountId != null) {
            return repository.findByAccountId(session.targetAccountId)
                .switchIfEmpty(Mono.error(() -> new ApiException(
                    HttpStatus.NOT_FOUND,
                    "ACCOUNT_NOT_FOUND",
                    "Account was removed while authorization was in progress"
                )))
                .flatMap(existing -> updateExisting(existing, identity, tokens, session.proxyChannelId, true));
        }

        return repository.findByAccountId(stableAccountId)
            .flatMap(account -> updateExisting(account, identity, tokens, session.proxyChannelId, true))
            .switchIfEmpty(Mono.defer(() -> findByEmail(identity)
                .flatMap(account -> updateExisting(account, identity, tokens, session.proxyChannelId, true))
                .switchIfEmpty(Mono.defer(() -> createAccount(stableAccountId, identity, tokens, session.proxyChannelId)))))
            .onErrorResume(DuplicateKeyException.class, error -> findAndUpdateAfterConflict(
                stableAccountId,
                identity,
                tokens,
                session.proxyChannelId
            ));
    }

    private Mono<ProxyAccountEntity> findAndUpdateAfterConflict(
        String stableAccountId,
        OpenAiOAuthTokenClaims.Identity identity,
        OAuthTokens tokens,
        UUID proxyChannelId
    ) {
        return repository.findByAccountId(stableAccountId)
            .switchIfEmpty(findByEmail(identity))
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.CONFLICT,
                "CHATGPT_OAUTH_ACCOUNT_CONFLICT",
                "An account with the same ChatGPT identity already exists"
            )))
            .flatMap(account -> updateExisting(account, identity, tokens, proxyChannelId, true));
    }

    private Mono<ProxyAccountEntity> findByEmail(OpenAiOAuthTokenClaims.Identity identity) {
        String email = normalizedEmail(identity.email());
        return email == null ? Mono.empty() : repository.findFirstByEmailOrderByCreatedAtAsc(email);
    }

    private Mono<ProxyAccountEntity> createAccount(
        String accountId,
        OpenAiOAuthTokenClaims.Identity identity,
        OAuthTokens tokens,
        UUID proxyChannelId
    ) {
        Instant now = clock.instant();
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setEmail(normalizedEmail(identity.email()));
        account.setAccessToken(tokens.accessToken);
        account.setRefreshToken(tokens.refreshToken);
        account.setClientId(clientId);
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setBaseUrl(AccountPlatforms.DEFAULT_CODEX_BASE_URL);
        account.setProxyChannelId(proxyChannelId);
        account.setUpstreamAccountId(identity.upstreamAccountId());
        account.setIdToken(tokens.idToken);
        account.setTokenExpiresAt(identity.expiresAt());
        account.setPoolTier("primary");
        account.setPoolWeight(100);
        account.setEnabled(true);
        account.setRequestCount(0L);
        account.setErrorCount(0);
        account.setQuotaUsed(0L);
        account.setQuota5hUsed(0L);
        account.setQuota7dUsed(0L);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return repository.save(account)
            .flatMap(saved -> accountPoolService.refreshCache().thenReturn(saved));
    }

    private Mono<ProxyAccountEntity> updateExisting(
        ProxyAccountEntity account,
        OpenAiOAuthTokenClaims.Identity identity,
        OAuthTokens tokens,
        UUID proxyChannelId,
        boolean requireSameIdentity
    ) {
        if (!AccountPlatforms.isCodex(account)) {
            return Mono.error(new ApiException(
                HttpStatus.CONFLICT,
                "CHATGPT_OAUTH_ACCOUNT_CONFLICT",
                "The matching account is not a Codex OAuth account"
            ));
        }
        if (requireSameIdentity && !sameIdentity(account, identity)) {
            return Mono.error(new ApiException(
                HttpStatus.CONFLICT,
                "CHATGPT_OAUTH_ACCOUNT_MISMATCH",
                "The authorized ChatGPT identity does not match the selected account"
            ));
        }
        if (identity.email() != null) {
            account.setEmail(normalizedEmail(identity.email()));
        }
        account.setAccessToken(tokens.accessToken);
        account.setRefreshToken(tokens.refreshToken);
        account.setClientId(clientId);
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setBaseUrl(AccountPlatforms.DEFAULT_CODEX_BASE_URL);
        if (proxyChannelId != null) {
            account.setProxyChannelId(proxyChannelId);
        }
        if (identity.upstreamAccountId() != null) {
            account.setUpstreamAccountId(identity.upstreamAccountId());
        }
        account.setIdToken(tokens.idToken);
        account.setTokenExpiresAt(identity.expiresAt());
        account.setEnabled(true);
        account.setErrorCount(0);
        account.setSuspendedAt(null);
        account.setSuspendReason(null);
        account.setSuspendMessage(null);
        account.setUpdatedAt(clock.instant());
        return repository.save(account)
            .flatMap(saved -> accountPoolService.refreshCache().thenReturn(saved))
            .onErrorMap(DuplicateKeyException.class, error -> new ApiException(
                HttpStatus.CONFLICT,
                "ACCOUNT_EMAIL_EXISTS",
                "Account email already exists"
            ));
    }

    private boolean sameIdentity(
        ProxyAccountEntity account,
        OpenAiOAuthTokenClaims.Identity identity
    ) {
        try {
            OpenAiOAuthTokenClaims.Identity current = OpenAiOAuthTokenClaims.parseStoredIdentity(
                objectMapper,
                account.getIdToken()
            );
            if (current.userId() != null && identity.userId() != null) {
                return current.userId().equals(identity.userId())
                    && compatibleWorkspace(current.upstreamAccountId(), identity.upstreamAccountId());
            }
        } catch (ApiException ignored) {
            // Older imported records may not carry complete JWTs; compare stored identity fields.
        }
        String currentWorkspace = clean(account.getUpstreamAccountId());
        String nextWorkspace = clean(identity.upstreamAccountId());
        if (currentWorkspace != null && nextWorkspace != null && !currentWorkspace.equals(nextWorkspace)) {
            return false;
        }
        String currentEmail = normalizedEmail(account.getEmail());
        String nextEmail = normalizedEmail(identity.email());
        return currentEmail != null && nextEmail != null && currentEmail.equals(nextEmail);
    }

    private boolean compatibleWorkspace(String current, String next) {
        String currentWorkspace = clean(current);
        String nextWorkspace = clean(next);
        return currentWorkspace == null || nextWorkspace == null || currentWorkspace.equals(nextWorkspace);
    }

    private String stableAccountId(OpenAiOAuthTokenClaims.Identity identity) {
        String identityPart = identity.userId() == null ? normalizedEmail(identity.email()) : identity.userId();
        String workspace = identity.upstreamAccountId() == null ? "personal" : identity.upstreamAccountId();
        return "codex-" + Hashing.sha256(workspace + "\n" + identityPart).substring(0, 32);
    }

    private AccountDtos.ChatGptOAuthStatusResponse succeededStatus(
        LoginSession session,
        ProxyAccountEntity account
    ) {
        synchronized (session) {
            if (session.state != LoginState.COMPLETING) {
                return statusResponse(session);
            }
            session.state = LoginState.SUCCEEDED;
            session.message = session.targetAccountId == null ? "ChatGPT 账号已添加" : "ChatGPT 账号已重新授权";
            session.account = AccountMapper.toResponse(account);
            session.deviceAuthId = null;
            return statusResponse(session);
        }
    }

    private AccountDtos.ChatGptOAuthStatusResponse failedStatus(LoginSession session, Throwable error) {
        String message = error instanceof ApiException apiException
            ? apiException.getMessage()
            : "ChatGPT authorization could not be completed";
        AccountDtos.ChatGptOAuthStatusResponse response;
        synchronized (session) {
            if (isTerminal(session.state)) {
                return statusResponse(session);
            }
            session.state = LoginState.FAILED;
            session.message = message;
            session.deviceAuthId = null;
            response = statusResponse(session);
        }
        log.warn("ChatGPT OAuth login {} failed: {}", session.loginId, message);
        return response;
    }

    private AccountDtos.ChatGptOAuthStatusResponse pendingStatus(LoginSession session) {
        synchronized (session) {
            expireIfNecessary(session, clock.instant());
            if (session.state == LoginState.PENDING && session.message == null) {
                session.message = "等待在 OpenAI 页面完成授权";
            }
            return statusResponse(session);
        }
    }

    private boolean expireIfNecessary(LoginSession session, Instant now) {
        if (session.state == LoginState.PENDING && !now.isBefore(session.expiresAt)) {
            session.state = LoginState.EXPIRED;
            session.message = "授权码已过期，请重新发起授权";
            session.deviceAuthId = null;
            return true;
        }
        return session.state == LoginState.EXPIRED;
    }

    private AccountDtos.ChatGptOAuthStatusResponse statusResponse(LoginSession session) {
        int retryAfter = 0;
        if (session.state == LoginState.PENDING) {
            long millis = Math.max(0, Duration.between(clock.instant(), session.nextPollAt).toMillis());
            retryAfter = (int) Math.max(1, (millis + 999) / 1000);
        }
        return new AccountDtos.ChatGptOAuthStatusResponse(
            session.loginId,
            session.state.name(),
            session.message,
            session.state == LoginState.PENDING ? retryAfter : null,
            session.account
        );
    }

    private LoginSession requireSession(String owner, String loginId) {
        String normalizedId = clean(loginId);
        LoginSession session = normalizedId == null ? null : sessions.getIfPresent(normalizedId);
        if (session == null || !MessageDigest.isEqual(
            session.owner.getBytes(StandardCharsets.UTF_8),
            owner.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new ApiException(
                HttpStatus.NOT_FOUND,
                "CHATGPT_OAUTH_LOGIN_NOT_FOUND",
                "ChatGPT authorization session was not found or has expired"
            );
        }
        return session;
    }

    private StartReservation reserveLoginSlot(String owner) {
        synchronized (loginLimitMonitor) {
            sessions.cleanUp();
            long active = startingLoginsByOwner.getOrDefault(owner, 0);
            for (LoginSession session : sessions.asMap().values()) {
                if (session.owner.equals(owner) && activeSession(session)) {
                    active++;
                }
            }
            if (active >= MAX_ACTIVE_LOGINS_PER_ADMIN) {
                throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "CHATGPT_OAUTH_TOO_MANY_LOGINS",
                    "Too many active ChatGPT authorization sessions"
                );
            }
            startingLoginsByOwner.merge(owner, 1, Integer::sum);
            return new StartReservation(owner);
        }
    }

    private boolean activeSession(LoginSession session) {
        synchronized (session) {
            expireIfNecessary(session, clock.instant());
            return session.state == LoginState.PENDING || session.state == LoginState.COMPLETING;
        }
    }

    private void releaseLoginSlot(String owner) {
        synchronized (loginLimitMonitor) {
            Integer active = startingLoginsByOwner.get(owner);
            if (active == null || active <= 1) {
                startingLoginsByOwner.remove(owner);
            } else {
                startingLoginsByOwner.put(owner, active - 1);
            }
        }
    }

    private boolean isTerminal(LoginState state) {
        return state == LoginState.SUCCEEDED
            || state == LoginState.FAILED
            || state == LoginState.EXPIRED
            || state == LoginState.CANCELLED;
    }

    private void noteTransientFailure(LoginSession session) {
        noteTransientFailure(session, null);
    }

    private void noteTransientFailure(LoginSession session, String routeProblem) {
        synchronized (session) {
            if (session.state == LoginState.PENDING) {
                session.message = routeProblem == null ? "OpenAI 授权服务暂时不可用，正在重试" : routeProblem;
            }
        }
    }

    /** The fixed text of an egress failure; transport messages can carry proxy credentials and are never shown. */
    private String routeUnavailable(Throwable error, String fallback) {
        EgressException egress = EgressException.find(error);
        return egress == null ? fallback : "The selected outbound proxy is unavailable: " + egress.getMessage();
    }

    private void noteWaiting(LoginSession session) {
        synchronized (session) {
            if (session.state == LoginState.PENDING) {
                session.message = "等待在 OpenAI 页面完成授权";
            }
        }
    }

    private int pollInterval(JsonNode value) {
        int interval = 5;
        try {
            interval = value.isNumber() ? value.asInt(0) : Integer.parseInt(value.asString("5").trim());
        } catch (RuntimeException ignored) {
            // Keep the conservative default when the optional interval is malformed.
        }
        return Math.max(MIN_POLL_INTERVAL_SECONDS, Math.min(MAX_POLL_INTERVAL_SECONDS, interval));
    }

    private boolean validPkcePair(String verifier, String challenge) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII)
            );
        } catch (Exception error) {
            return false;
        }
    }

    private String requireOwner(String value) {
        String owner = clean(value);
        if (owner == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Missing administrator identity");
        }
        return owner;
    }

    private String normalizedEmail(String value) {
        String email = clean(value);
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private ApiException authUnavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "CHATGPT_OAUTH_UNAVAILABLE", message);
    }

    private ApiException authProtocolError(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "CHATGPT_OAUTH_PROTOCOL_ERROR", message);
    }

    private enum LoginState {
        PENDING,
        COMPLETING,
        SUCCEEDED,
        FAILED,
        EXPIRED,
        CANCELLED
    }

    private static final class LoginSession {
        private final String loginId;
        private final String owner;
        private final String targetAccountId;
        private String deviceAuthId;
        private final String userCode;
        private final Instant expiresAt;
        private final int pollIntervalSeconds;
        private Instant nextPollAt;
        private LoginState state = LoginState.PENDING;
        private String message;
        private AccountDtos.AccountResponse account;
        private final UUID proxyChannelId;
        private final LoginEgress egress;

        private LoginSession(
            String loginId,
            String owner,
            String targetAccountId,
            String deviceAuthId,
            String userCode,
            Instant expiresAt,
            int pollIntervalSeconds,
            Instant nextPollAt,
            UUID proxyChannelId,
            LoginEgress egress
        ) {
            this.proxyChannelId = proxyChannelId;
            this.egress = egress;
            this.loginId = loginId;
            this.owner = owner;
            this.targetAccountId = targetAccountId;
            this.deviceAuthId = deviceAuthId;
            this.userCode = userCode;
            this.expiresAt = expiresAt;
            this.pollIntervalSeconds = pollIntervalSeconds;
            this.nextPollAt = nextPollAt;
        }
    }

    private final class StartReservation {
        private final String owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private StartReservation(String owner) {
            this.owner = owner;
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                releaseLoginSlot(owner);
            }
        }
    }

    private record DevicePollRequest(String deviceAuthId, String userCode) {
    }

    /** The exit of one login: an explicit channel, or the reauthorized account's own binding; none means direct. */
    private record LoginEgress(UUID channelId, ProxyAccountEntity account) {
        void apply(Map<String, Object> attributes) {
            if (channelId != null) {
                attributes.put(AccountEgressTransport.CHANNEL, channelId);
            } else if (account != null) {
                attributes.put(AccountEgressTransport.ACCOUNT, account);
            }
        }
    }

    private record OAuthTokens(String accessToken, String refreshToken, String idToken) {
    }
}
