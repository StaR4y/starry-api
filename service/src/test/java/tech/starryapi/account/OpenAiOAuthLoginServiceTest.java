package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tech.starryapi.common.ApiException;
import tech.starryapi.egress.AccountEgressTransport;
import tech.starryapi.egress.EgressException;
import tech.starryapi.egress.EgressFailure;
import tech.starryapi.egress.ProxyChannelService;

class OpenAiOAuthLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String AUTH_BASE_URL = "https://auth.example.test";
    private static final String CLIENT_ID = "test-codex-client";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completesDeviceAuthorizationAndCreatesCodexAccount() throws Exception {
        String verifier = "test-verifier-value";
        String challenge = pkceChallenge(verifier);
        String idToken = identityJwt("user-123", "user@example.com", "workspace-123");
        String accessToken = accessJwt();
        List<String> requestPaths = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            requestPaths.add(request.url().getPath());
            return switch (request.url().getPath()) {
                case "/api/accounts/deviceauth/usercode" -> json(HttpStatus.OK, """
                    {"device_auth_id":"device-123","user_code":"ABCD-EFGH","interval":"1"}
                    """);
                case "/api/accounts/deviceauth/token" -> json(HttpStatus.OK, """
                    {
                      "authorization_code":"authorization-code",
                      "code_verifier":"%s",
                      "code_challenge":"%s"
                    }
                    """.formatted(verifier, challenge));
                case "/oauth/token" -> json(HttpStatus.OK, """
                    {
                      "access_token":"%s",
                      "refresh_token":"refresh-token",
                      "id_token":"%s"
                    }
                    """.formatted(accessToken, idToken));
                default -> Mono.error(new AssertionError("Unexpected request " + request.url()));
            };
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        when(repository.findByAccountId(anyString())).thenReturn(Mono.empty());
        when(repository.findFirstByEmailOrderByCreatedAtAsc("user@example.com")).thenReturn(Mono.empty());
        when(repository.save(any(ProxyAccountEntity.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(accountPoolService.refreshCache()).thenReturn(Mono.empty());
        OpenAiOAuthLoginService service = service(exchange, repository, accountPoolService);

        AccountDtos.ChatGptOAuthStartResponse started = service.start(
            "admin-1",
            new AccountDtos.ChatGptOAuthStartRequest(null)
        ).block();
        AccountDtos.ChatGptOAuthStatusResponse completed = service.poll(
            "admin-1",
            started.loginId()
        ).block();

        assertThat(started.userCode()).isEqualTo("ABCD-EFGH");
        assertThat(started.verificationUrl()).isEqualTo(AUTH_BASE_URL + "/codex/device");
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.account().email()).isEqualTo("user@example.com");
        assertThat(requestPaths).containsExactly(
            "/api/accounts/deviceauth/usercode",
            "/api/accounts/deviceauth/token",
            "/oauth/token"
        );

        ArgumentCaptor<ProxyAccountEntity> captor = ArgumentCaptor.forClass(ProxyAccountEntity.class);
        verify(repository).save(captor.capture());
        ProxyAccountEntity created = captor.getValue();
        assertThat(created.getAccountId()).startsWith("codex-");
        assertThat(created.getAccessToken()).isEqualTo(accessToken);
        assertThat(created.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(created.getIdToken()).isEqualTo(idToken);
        assertThat(created.getClientId()).isEqualTo(CLIENT_ID);
        assertThat(created.getAuthMethod()).isEqualTo("codex");
        assertThat(created.getProvider()).isEqualTo("OpenAI");
        assertThat(created.getBaseUrl()).isEqualTo("https://chatgpt.com/backend-api/codex");
        assertThat(created.getUpstreamAccountId()).isEqualTo("workspace-123");
        assertThat(created.getTokenExpiresAt()).isAfter(NOW);
    }

    @Test
    void reportsPendingUntilBrowserAuthorizationCompletes() {
        ExchangeFunction exchange = request -> switch (request.url().getPath()) {
            case "/api/accounts/deviceauth/usercode" -> json(HttpStatus.OK, """
                {"device_auth_id":"device-123","user_code":"ABCD-EFGH","interval":"1"}
                """);
            case "/api/accounts/deviceauth/token" -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
            default -> Mono.error(new AssertionError("Unexpected request " + request.url()));
        };
        OpenAiOAuthLoginService service = service(
            exchange,
            mock(ProxyAccountRepository.class),
            mock(AccountPoolService.class)
        );

        AccountDtos.ChatGptOAuthStartResponse started = service.start(
            "admin-1",
            new AccountDtos.ChatGptOAuthStartRequest(null)
        ).block();
        AccountDtos.ChatGptOAuthStatusResponse status = service.poll("admin-1", started.loginId()).block();

        assertThat(status.status()).isEqualTo("PENDING");
        assertThat(status.message()).contains("等待");
        assertThat(status.retryAfterSeconds()).isPositive();
        assertThat(status.account()).isNull();
    }

    @Test
    void authorizationSessionIsBoundToStartingAdministrator() {
        ExchangeFunction exchange = request -> json(HttpStatus.OK, """
            {"device_auth_id":"device-123","user_code":"ABCD-EFGH","interval":"1"}
            """);
        OpenAiOAuthLoginService service = service(
            exchange,
            mock(ProxyAccountRepository.class),
            mock(AccountPoolService.class)
        );
        AccountDtos.ChatGptOAuthStartResponse started = service.start(
            "admin-1",
            new AccountDtos.ChatGptOAuthStartRequest(null)
        ).block();

        assertThatThrownBy(() -> service.poll("admin-2", started.loginId()).block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void reauthorizationRejectsDifferentChatGptIdentity() throws Exception {
        String verifier = "test-verifier-value";
        String challenge = pkceChallenge(verifier);
        String nextIdToken = identityJwt("user-new", "new@example.com", "workspace-123");
        String nextAccessToken = accessJwt();
        ExchangeFunction exchange = request -> switch (request.url().getPath()) {
            case "/api/accounts/deviceauth/usercode" -> json(HttpStatus.OK, """
                {"device_auth_id":"device-123","user_code":"ABCD-EFGH","interval":"1"}
                """);
            case "/api/accounts/deviceauth/token" -> json(HttpStatus.OK, """
                {
                  "authorization_code":"authorization-code",
                  "code_verifier":"%s",
                  "code_challenge":"%s"
                }
                """.formatted(verifier, challenge));
            case "/oauth/token" -> json(HttpStatus.OK, """
                {
                  "access_token":"%s",
                  "refresh_token":"refresh-token",
                  "id_token":"%s"
                }
                """.formatted(nextAccessToken, nextIdToken));
            default -> Mono.error(new AssertionError("Unexpected request " + request.url()));
        };
        ProxyAccountEntity existing = new ProxyAccountEntity();
        existing.setAccountId("codex-existing");
        existing.setProvider("OpenAI");
        existing.setAuthMethod("codex");
        existing.setEmail("old@example.com");
        existing.setUpstreamAccountId("workspace-123");
        existing.setIdToken(identityJwt("user-old", "old@example.com", "workspace-123"));
        existing.setAccessToken(accessJwt());
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findByAccountId("codex-existing")).thenReturn(Mono.just(existing));
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        OpenAiOAuthLoginService service = service(exchange, repository, accountPoolService);

        AccountDtos.ChatGptOAuthStartResponse started = service.start(
            "admin-1",
            new AccountDtos.ChatGptOAuthStartRequest("codex-existing")
        ).block();
        AccountDtos.ChatGptOAuthStatusResponse status = service.poll("admin-1", started.loginId()).block();

        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.message()).contains("does not match");
        verify(repository, never()).save(any());
        verify(accountPoolService, never()).create(any());
    }

    @Test
    void cancellationWinsWhenDevicePollCompletesLate() throws Exception {
        String verifier = "test-verifier-value";
        String challenge = pkceChallenge(verifier);
        Sinks.One<ClientResponse> devicePollResponse = Sinks.one();
        AtomicInteger tokenExchangeRequests = new AtomicInteger();
        ExchangeFunction exchange = request -> switch (request.url().getPath()) {
            case "/api/accounts/deviceauth/usercode" -> json(HttpStatus.OK, """
                {"device_auth_id":"device-123","user_code":"ABCD-EFGH","interval":"1"}
                """);
            case "/api/accounts/deviceauth/token" -> devicePollResponse.asMono();
            case "/oauth/token" -> {
                tokenExchangeRequests.incrementAndGet();
                yield Mono.error(new AssertionError("Cancelled login must not exchange tokens"));
            }
            default -> Mono.error(new AssertionError("Unexpected request " + request.url()));
        };
        OpenAiOAuthLoginService service = service(
            exchange,
            mock(ProxyAccountRepository.class),
            mock(AccountPoolService.class)
        );
        AccountDtos.ChatGptOAuthStartResponse started = service.start(
            "admin-1",
            new AccountDtos.ChatGptOAuthStartRequest(null)
        ).block();

        CompletableFuture<AccountDtos.ChatGptOAuthStatusResponse> pendingPoll = service.poll(
            "admin-1",
            started.loginId()
        ).toFuture();
        AccountDtos.ChatGptOAuthStatusResponse cancelled = service.cancel("admin-1", started.loginId());
        devicePollResponse.tryEmitValue(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body("""
                {
                  "authorization_code":"authorization-code",
                  "code_verifier":"%s",
                  "code_challenge":"%s"
                }
                """.formatted(verifier, challenge))
            .build());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(pendingPoll.get(1, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
        assertThat(tokenExchangeRequests).hasValue(0);
    }

    @Test
    void concurrentStartsCannotExceedPerAdministratorLimit() {
        AtomicInteger deviceCodeRequests = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            deviceCodeRequests.incrementAndGet();
            return Mono.never();
        };
        OpenAiOAuthLoginService service = service(
            exchange,
            mock(ProxyAccountRepository.class),
            mock(AccountPoolService.class)
        );
        List<Disposable> pendingStarts = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                pendingStarts.add(service.start(
                    "admin-1",
                    new AccountDtos.ChatGptOAuthStartRequest(null)
                ).subscribe());
            }

            assertThatThrownBy(() -> service.start(
                "admin-1",
                new AccountDtos.ChatGptOAuthStartRequest(null)
            ).block())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many active");
            assertThat(deviceCodeRequests).hasValue(3);
        } finally {
            pendingStarts.forEach(Disposable::dispose);
        }
    }

    @Test
    void reauthorizationRejectsLegacyAccountWithoutVerifiableIdentity() throws Exception {
        String verifier = "test-verifier-value";
        String challenge = pkceChallenge(verifier);
        String idToken = identityJwt("user-new", "new@example.com", "workspace-123");
        String accessToken = accessJwt();
        ExchangeFunction exchange = request -> switch (request.url().getPath()) {
            case "/api/accounts/deviceauth/usercode" -> json(HttpStatus.OK, """
                {"device_auth_id":"device-123","user_code":"ABCD-EFGH","interval":"1"}
                """);
            case "/api/accounts/deviceauth/token" -> json(HttpStatus.OK, """
                {
                  "authorization_code":"authorization-code",
                  "code_verifier":"%s",
                  "code_challenge":"%s"
                }
                """.formatted(verifier, challenge));
            case "/oauth/token" -> json(HttpStatus.OK, """
                {
                  "access_token":"%s",
                  "refresh_token":"refresh-token",
                  "id_token":"%s"
                }
                """.formatted(accessToken, idToken));
            default -> Mono.error(new AssertionError("Unexpected request " + request.url()));
        };
        ProxyAccountEntity existing = new ProxyAccountEntity();
        existing.setAccountId("codex-legacy");
        existing.setProvider("OpenAI");
        existing.setAuthMethod("codex");
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findByAccountId("codex-legacy")).thenReturn(Mono.just(existing));
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        OpenAiOAuthLoginService service = service(exchange, repository, accountPoolService);

        AccountDtos.ChatGptOAuthStartResponse started = service.start(
            "admin-1",
            new AccountDtos.ChatGptOAuthStartRequest("codex-legacy")
        ).block();
        AccountDtos.ChatGptOAuthStatusResponse status = service.poll("admin-1", started.loginId()).block();

        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.message()).contains("does not match");
        verify(repository, never()).save(any());
        verify(accountPoolService, never()).create(any());
    }

    @Test
    void newAuthorizationDoesNotOverwriteDifferentIdentityWithSameEmail() throws Exception {
        String verifier = "test-verifier-value";
        String challenge = pkceChallenge(verifier);
        String idToken = identityJwt("user-new", "shared@example.com", "workspace-new");
        String accessToken = accessJwt();
        ExchangeFunction exchange = request -> switch (request.url().getPath()) {
            case "/api/accounts/deviceauth/usercode" -> json(HttpStatus.OK, """
                {"device_auth_id":"device-123","user_code":"ABCD-EFGH","interval":"1"}
                """);
            case "/api/accounts/deviceauth/token" -> json(HttpStatus.OK, """
                {
                  "authorization_code":"authorization-code",
                  "code_verifier":"%s",
                  "code_challenge":"%s"
                }
                """.formatted(verifier, challenge));
            case "/oauth/token" -> json(HttpStatus.OK, """
                {
                  "access_token":"%s",
                  "refresh_token":"refresh-token",
                  "id_token":"%s"
                }
                """.formatted(accessToken, idToken));
            default -> Mono.error(new AssertionError("Unexpected request " + request.url()));
        };
        ProxyAccountEntity existing = new ProxyAccountEntity();
        existing.setAccountId("codex-existing");
        existing.setProvider("OpenAI");
        existing.setAuthMethod("codex");
        existing.setEmail("shared@example.com");
        existing.setUpstreamAccountId("workspace-old");
        existing.setIdToken(identityJwt("user-old", "shared@example.com", "workspace-old"));
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findByAccountId(anyString())).thenReturn(Mono.empty());
        when(repository.findFirstByEmailOrderByCreatedAtAsc("shared@example.com"))
            .thenReturn(Mono.just(existing));
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        OpenAiOAuthLoginService service = service(exchange, repository, accountPoolService);

        AccountDtos.ChatGptOAuthStartResponse started = service.start(
            "admin-1",
            new AccountDtos.ChatGptOAuthStartRequest(null)
        ).block();
        AccountDtos.ChatGptOAuthStatusResponse status = service.poll("admin-1", started.loginId()).block();

        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.message()).contains("does not match");
        verify(repository, never()).save(any());
        verify(accountPoolService, never()).create(any());
    }

    // ---- egress: the whole login uses the chosen exit ----

    @Test
    void routesEveryCallOfTheLoginThroughTheChosenChannelAndBindsTheNewAccount() throws Exception {
        UUID channel = UUID.randomUUID();
        ProxyChannelService channels = mock(ProxyChannelService.class);
        when(channels.requireUsable(channel)).thenReturn(Mono.empty());
        List<String> calls = new ArrayList<>();
        ProxyAccountRepository repository = emptyRepository();
        OpenAiOAuthLoginService service = service(
            loginExchange(request -> calls.add(request.url().getPath() + " channel=" + request.attribute(AccountEgressTransport.CHANNEL).orElse(null)
                + " account=" + request.attribute(AccountEgressTransport.ACCOUNT).orElse(null)), null),
            repository, pool(), channels);

        AccountDtos.ChatGptOAuthStartResponse started = service.start("admin-1", new AccountDtos.ChatGptOAuthStartRequest(null, channel)).block();
        AccountDtos.ChatGptOAuthStatusResponse completed = service.poll("admin-1", started.loginId()).block();

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(calls).containsExactly(
            "/api/accounts/deviceauth/usercode channel=" + channel + " account=null",
            "/api/accounts/deviceauth/token channel=" + channel + " account=null",
            "/oauth/token channel=" + channel + " account=null");
        ArgumentCaptor<ProxyAccountEntity> saved = ArgumentCaptor.forClass(ProxyAccountEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProxyChannelId()).isEqualTo(channel);
    }

    @Test
    void anUnusableChannelIsRejectedBeforeOpenAiIsContacted() throws Exception {
        UUID channel = UUID.randomUUID();
        ProxyChannelService channels = mock(ProxyChannelService.class);
        when(channels.requireUsable(channel)).thenReturn(Mono.error(
            new ApiException(HttpStatus.BAD_REQUEST, "PROXY_CHANNEL_UNAVAILABLE", "The selected node is disabled")));
        List<String> calls = new ArrayList<>();
        OpenAiOAuthLoginService service = service(loginExchange(request -> calls.add(request.url().getPath()), null),
            emptyRepository(), mock(AccountPoolService.class), channels);

        assertThatThrownBy(() -> service.start("admin-1", new AccountDtos.ChatGptOAuthStartRequest(null, channel)).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("PROXY_CHANNEL_UNAVAILABLE"));
        assertThat(calls).isEmpty();
    }

    @Test
    void withoutAChannelNothingIsRoutedAndNothingIsBound() throws Exception {
        List<String> calls = new ArrayList<>();
        ProxyAccountRepository repository = emptyRepository();
        OpenAiOAuthLoginService service = service(
            loginExchange(request -> calls.add(request.attribute(AccountEgressTransport.CHANNEL).isPresent()
                + "/" + request.attribute(AccountEgressTransport.ACCOUNT).isPresent()), null),
            repository, pool());

        AccountDtos.ChatGptOAuthStartResponse started = service.start("admin-1", new AccountDtos.ChatGptOAuthStartRequest(null)).block();
        service.poll("admin-1", started.loginId()).block();

        assertThat(calls).containsExactly("false/false", "false/false", "false/false");
        ArgumentCaptor<ProxyAccountEntity> saved = ArgumentCaptor.forClass(ProxyAccountEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProxyChannelId()).isNull();
    }

    @Test
    void reauthorizationUsesTheAccountsOwnBindingUnlessAChannelIsChosenAndOnlyThenRebinds() throws Exception {
        UUID bound = UUID.randomUUID();
        UUID chosen = UUID.randomUUID();
        ProxyChannelService channels = mock(ProxyChannelService.class);
        when(channels.requireUsable(chosen)).thenReturn(Mono.empty());
        for (UUID explicit : new UUID[] {null, chosen}) {
            ProxyAccountEntity existing = new ProxyAccountEntity();
            existing.setAccountId("codex-existing");
            existing.setProvider("OpenAI");
            existing.setAuthMethod("codex");
            existing.setEmail("user@example.com");
            existing.setUpstreamAccountId("workspace-123");
            existing.setIdToken(identityJwt("user-123", "user@example.com", "workspace-123"));
            existing.setAccessToken(accessJwt());
            existing.setProxyChannelId(bound);
            ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
            when(repository.findByAccountId("codex-existing")).thenReturn(Mono.just(existing));
            when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            AccountPoolService pool = mock(AccountPoolService.class);
            when(pool.refreshCache()).thenReturn(Mono.empty());
            List<Object> accountAttributes = new ArrayList<>();
            List<Object> channelAttributes = new ArrayList<>();
            OpenAiOAuthLoginService service = service(loginExchange(request -> {
                accountAttributes.add(request.attribute(AccountEgressTransport.ACCOUNT).orElse(null));
                channelAttributes.add(request.attribute(AccountEgressTransport.CHANNEL).orElse(null));
            }, identityJwt("user-123", "user@example.com", "workspace-123")), repository, pool, channels);

            AccountDtos.ChatGptOAuthStartResponse started = service.start("admin-1", new AccountDtos.ChatGptOAuthStartRequest("codex-existing", explicit)).block();
            assertThat(service.poll("admin-1", started.loginId()).block().status()).isEqualTo("SUCCEEDED");

            if (explicit == null) {
                // The account's own binding is resolved by the transport from the account itself.
                assertThat(accountAttributes).hasSize(3).allSatisfy(attribute -> assertThat(attribute).isSameAs(existing));
                assertThat(channelAttributes).containsOnlyNulls();
                assertThat(existing.getProxyChannelId()).isEqualTo(bound);
            } else {
                assertThat(channelAttributes).containsExactly(chosen, chosen, chosen);
                assertThat(accountAttributes).containsOnlyNulls();
                assertThat(existing.getProxyChannelId()).isEqualTo(chosen);
            }
        }
    }

    @Test
    void aBrokenRouteKeepsTheDevicePollPendingAndFailsTheTokenExchangeWithoutLeakingTransportDetails() throws Exception {
        UUID channel = UUID.randomUUID();
        ProxyChannelService channels = mock(ProxyChannelService.class);
        when(channels.requireUsable(channel)).thenReturn(Mono.empty());
        EgressException brokenRoute = EgressException.notSent(EgressFailure.PROXY_CONNECT_FAILED,
            new java.net.ConnectException("refused user:secret@127.0.0.1:20001"));
        String verifier = "test-verifier-value";
        String challenge = pkceChallenge(verifier);
        AtomicInteger polls = new AtomicInteger();
        ExchangeFunction exchange = request -> switch (request.url().getPath()) {
            case "/api/accounts/deviceauth/usercode" -> json(HttpStatus.OK, "{\"device_auth_id\":\"device-123\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"1\"}");
            case "/api/accounts/deviceauth/token" -> polls.incrementAndGet() == 1 ? Mono.error(brokenRoute) : json(HttpStatus.OK, """
                {"authorization_code":"authorization-code","code_verifier":"%s","code_challenge":"%s"}
                """.formatted(verifier, challenge));
            case "/oauth/token" -> Mono.error(brokenRoute);
            default -> Mono.error(new AssertionError("Unexpected request " + request.url()));
        };
        MutableClock clock = new MutableClock(NOW);
        OpenAiOAuthLoginService service = new OpenAiOAuthLoginService(objectMapper, WebClient.builder().exchangeFunction(exchange).build(),
            emptyRepository(), mock(AccountPoolService.class), channels, AUTH_BASE_URL, CLIENT_ID, clock);

        AccountDtos.ChatGptOAuthStartResponse started = service.start("admin-1", new AccountDtos.ChatGptOAuthStartRequest(null, channel)).block();
        AccountDtos.ChatGptOAuthStatusResponse pending = service.poll("admin-1", started.loginId()).block();
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.message()).contains(EgressFailure.PROXY_CONNECT_FAILED.message()).doesNotContain("secret").doesNotContain("127.0.0.1");

        clock.advance(java.time.Duration.ofSeconds(5));
        AccountDtos.ChatGptOAuthStatusResponse failed = service.poll("admin-1", started.loginId()).block();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.message()).contains(EgressFailure.PROXY_CONNECT_FAILED.message()).doesNotContain("secret").doesNotContain("127.0.0.1");
    }

    @Test
    void theStartRequestAcceptsAnOptionalProxyChannel() throws Exception {
        UUID channel = UUID.randomUUID();
        assertThat(objectMapper.readValue("{\"accountId\":\"a\",\"proxyChannelId\":\"" + channel + "\"}", AccountDtos.ChatGptOAuthStartRequest.class))
            .isEqualTo(new AccountDtos.ChatGptOAuthStartRequest("a", channel));
        assertThat(objectMapper.readValue("{}", AccountDtos.ChatGptOAuthStartRequest.class).proxyChannelId()).isNull();
        assertThat(objectMapper.readValue("{\"proxyChannelId\":null}", AccountDtos.ChatGptOAuthStartRequest.class).proxyChannelId()).isNull();
    }

    private AccountPoolService pool() {
        AccountPoolService pool = mock(AccountPoolService.class);
        when(pool.refreshCache()).thenReturn(Mono.empty());
        return pool;
    }

    private ProxyAccountRepository emptyRepository() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findByAccountId(anyString())).thenReturn(Mono.empty());
        when(repository.findFirstByEmailOrderByCreatedAtAsc(anyString())).thenReturn(Mono.empty());
        when(repository.save(any(ProxyAccountEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        return repository;
    }

    /** The three calls of a successful device login; {@code observer} sees each request. */
    private ExchangeFunction loginExchange(java.util.function.Consumer<org.springframework.web.reactive.function.client.ClientRequest> observer, String idToken) throws Exception {
        String verifier = "test-verifier-value";
        String challenge = pkceChallenge(verifier);
        String token = idToken == null ? identityJwt("user-123", "user@example.com", "workspace-123") : idToken;
        return request -> {
            observer.accept(request);
            return switch (request.url().getPath()) {
                case "/api/accounts/deviceauth/usercode" -> json(HttpStatus.OK, "{\"device_auth_id\":\"device-123\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"1\"}");
                case "/api/accounts/deviceauth/token" -> json(HttpStatus.OK, """
                    {"authorization_code":"authorization-code","code_verifier":"%s","code_challenge":"%s"}
                    """.formatted(verifier, challenge));
                case "/oauth/token" -> json(HttpStatus.OK, """
                    {"access_token":"%s","refresh_token":"refresh-token","id_token":"%s"}
                    """.formatted(accessJwt(), token));
                default -> Mono.error(new AssertionError("Unexpected request " + request.url()));
            };
        };
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(java.time.Duration duration) { now = now.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private OpenAiOAuthLoginService service(
        ExchangeFunction exchange,
        ProxyAccountRepository repository,
        AccountPoolService accountPoolService
    ) {
        return service(exchange, repository, accountPoolService, mock(ProxyChannelService.class));
    }

    private OpenAiOAuthLoginService service(
        ExchangeFunction exchange,
        ProxyAccountRepository repository,
        AccountPoolService accountPoolService,
        ProxyChannelService channels
    ) {
        return new OpenAiOAuthLoginService(
            objectMapper,
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService,
            channels,
            AUTH_BASE_URL,
            CLIENT_ID,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private Mono<ClientResponse> json(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
    }

    private String identityJwt(String userId, String email, String workspaceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sub", userId);
        payload.put("email", email);
        payload.put("exp", NOW.plusSeconds(3600).getEpochSecond());
        ObjectNode auth = payload.putObject("https://api.openai.com/auth");
        auth.put("chatgpt_user_id", userId);
        auth.put("chatgpt_account_id", workspaceId);
        return jwt(payload);
    }

    private String accessJwt() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("exp", NOW.plusSeconds(3600).getEpochSecond());
        return jwt(payload);
    }

    private String jwt(ObjectNode payload) {
        String header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".signature";
    }

    private String pkceChallenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

}
