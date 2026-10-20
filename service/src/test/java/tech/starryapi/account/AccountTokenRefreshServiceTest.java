package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.DataManagementSecurityService;
import tech.starryapi.admin.SystemMaintenanceTaskGate;

class AccountTokenRefreshServiceTest {

    /** Applies the targeted credential UPDATE to the entity the (mocked) repository hands out next. */
    private static void stubCredentialUpdate(ProxyAccountRepository repository, ProxyAccountEntity entity) {
        when(repository.updateCredentials(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> {
            entity.setAccessToken(invocation.getArgument(1));
            entity.setRefreshToken(invocation.getArgument(2));
            entity.setIdToken(invocation.getArgument(3));
            entity.setClientId(invocation.getArgument(4));
            if (invocation.getArgument(5) != null) {
                entity.setTokenExpiresAt(invocation.getArgument(5));
            }
            return Mono.just(1);
        });
    }

    @Test
    void scheduledRefreshSkipsRepositoryAndUpstreamWorkDuringMaintenance() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));
        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.create(),
            repository,
            mock(AccountPoolService.class),
            new SystemMaintenanceTaskGate(securityService)
        );

        service.scheduledRefresh();

        verify(repository, never()).findAllByEnabledTrue();
    }

    @Test
    void refreshesOidcTokenAndSavesAccount() {
        AtomicReference<URI> requestUri = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUri.set(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":3600}
                    """)
                .build());
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity account = account();

        when(repository.findByAccountId("kiro-main")).thenReturn(Mono.just(account));
        stubCredentialUpdate(repository, account);
        when(accountPoolService.refreshCache()).thenReturn(Mono.empty());

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        ProxyAccountEntity refreshed = service.refresh(account).block();

        assertThat(refreshed).isNotNull();
        assertThat(requestUri.get()).isEqualTo(URI.create("https://oidc.us-east-1.amazonaws.com/token"));
        assertThat(refreshed.getAccessToken()).isEqualTo("new-access");
        assertThat(refreshed.getRefreshToken()).isEqualTo("new-refresh");
        verify(accountPoolService).refreshCache();
    }

    @Test
    void concurrentRefreshesShareOneUpstreamCallEvenWhenOneCallerCancels() {
        java.util.concurrent.atomic.AtomicInteger upstreamCalls = new java.util.concurrent.atomic.AtomicInteger();
        reactor.core.publisher.Sinks.One<ClientResponse> gate = reactor.core.publisher.Sinks.one();
        ExchangeFunction exchange = request -> {
            upstreamCalls.incrementAndGet();
            return gate.asMono();
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity account = account();
        when(repository.findByAccountId("kiro-main")).thenReturn(Mono.just(account));
        stubCredentialUpdate(repository, account);
        when(accountPoolService.refreshCache()).thenReturn(Mono.empty());
        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(), WebClient.builder().exchangeFunction(exchange).build(), repository, accountPoolService
        );

        reactor.core.Disposable first = service.refresh(account).subscribe();
        AtomicReference<ProxyAccountEntity> second = new AtomicReference<>();
        service.refresh(account).subscribe(second::set);
        first.dispose(); // one caller gives up while the refresh is still running
        AtomicReference<ProxyAccountEntity> third = new AtomicReference<>();
        service.refresh(account).subscribe(third::set);

        // Before the fix the cancelled caller removed the shared refresh, so the third caller started a second one.
        assertThat(upstreamCalls.get()).isEqualTo(1);
        gate.tryEmitValue(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "application/json")
            .body("{\"accessToken\":\"new-access\",\"refreshToken\":\"new-refresh\",\"expiresIn\":3600}")
            .build());
        assertThat(second.get()).isNotNull();
        assertThat(third.get()).isNotNull();
        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    void anAccountRegionThatIsNotAnAwsRegionNeverReceivesTheClientSecret() {
        java.util.concurrent.atomic.AtomicInteger upstreamCalls = new java.util.concurrent.atomic.AtomicInteger();
        ExchangeFunction exchange = request -> {
            upstreamCalls.incrementAndGet();
            return Mono.error(new AssertionError("must not contact " + request.url()));
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        ProxyAccountEntity account = account();
        account.setRegion("attacker.example/x");
        when(repository.findByAccountId("kiro-main")).thenReturn(Mono.just(account));
        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(), WebClient.builder().exchangeFunction(exchange).build(), repository, mock(AccountPoolService.class)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.refresh(account).block())
            .hasMessageContaining("Invalid AWS region");
        assertThat(upstreamCalls.get()).isZero();
    }

    @Test
    void refreshesEligibleAccountsForScheduledRefresh() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "application/json")
            .body("""
                {"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":3600}
                """)
            .build());
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity eligible = account();
        ProxyAccountEntity missingRefresh = account();
        missingRefresh.setAccountId("missing-refresh");
        missingRefresh.setRefreshToken(null);
        ProxyAccountEntity missingCredentials = account();
        missingCredentials.setAccountId("missing-credentials");
        missingCredentials.setClientId(null);
        missingCredentials.setClientSecret(null);

        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(eligible, missingRefresh, missingCredentials));
        when(repository.findByAccountId("kiro-main")).thenReturn(Mono.just(eligible));
        stubCredentialUpdate(repository, eligible);
        when(accountPoolService.refreshCache()).thenReturn(Mono.empty());

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        AccountTokenRefreshService.TokenRefreshSummary summary = service.refreshEligibleAccounts().block();

        assertThat(summary).isNotNull();
        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.refreshed()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(eligible.getAccessToken()).isEqualTo("new-access");
    }

    @Test
    void refreshesCodexTokenAndSavesAccount() {
        Instant expectedExpiry = Instant.parse("2099-01-01T00:00:00Z");
        String refreshedAccessToken = jwtWithExpiration(expectedExpiry);
        AtomicReference<URI> requestUri = new AtomicReference<>();
        AtomicReference<MediaType> requestContentType = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUri.set(request.url());
            requestContentType.set(request.headers().getContentType());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "access_token":"%s",
                      "refresh_token":"codex-new-refresh",
                      "id_token":"codex-id-token"
                    }
                    """.formatted(refreshedAccessToken))
                .build());
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity account = codexAccount();
        account.setClientId("codex-client-id");

        when(repository.findByAccountId("codex-main")).thenReturn(Mono.just(account));
        stubCredentialUpdate(repository, account);
        when(accountPoolService.refreshCache()).thenReturn(Mono.empty());

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        ProxyAccountEntity refreshed = service.refresh(account).block();

        assertThat(refreshed).isNotNull();
        assertThat(requestUri.get()).isEqualTo(URI.create("https://auth.openai.com/oauth/token"));
        assertThat(requestContentType.get()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(refreshed.getAccessToken()).isEqualTo(refreshedAccessToken);
        assertThat(refreshed.getRefreshToken()).isEqualTo("codex-new-refresh");
        assertThat(refreshed.getIdToken()).isEqualTo("codex-id-token");
        assertThat(refreshed.getClientId()).isEqualTo("codex-client-id");
        assertThat(refreshed.getTokenExpiresAt()).isEqualTo(expectedExpiry);
        assertThat(service.canRefresh(account)).isTrue();
    }

    @Test
    void codexRefreshRequiresRotatedRefreshToken() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "application/json")
            .body("""
                {
                  "access_token":"codex-new-access",
                  "expires_in":3600
                }
                """)
            .build());
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity account = codexAccount();

        when(repository.findByAccountId("codex-main")).thenReturn(Mono.just(account));

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        assertThatThrownBy(() -> service.refresh(account).block())
            .hasMessageContaining("does not contain refresh_token");
        assertThat(account.getAccessToken()).isEqualTo("codex-old-access");
        assertThat(account.getRefreshToken()).isEqualTo("codex-old-refresh");
    }

    @Test
    void returnsStoredRotatedCredentialsWhenCallerHasStaleCodexToken() {
        AtomicBoolean called = new AtomicBoolean(false);
        ExchangeFunction exchange = request -> {
            called.set(true);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity stale = codexAccount();
        ProxyAccountEntity rotated = codexAccount();
        rotated.setAccessToken("codex-new-access");
        rotated.setRefreshToken("codex-new-refresh");
        rotated.setTokenExpiresAt(Instant.now().plusSeconds(3600));

        when(repository.findByAccountId("codex-main")).thenReturn(Mono.just(rotated));
        when(accountPoolService.refreshCache()).thenReturn(Mono.empty());

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        ProxyAccountEntity refreshed = service.refresh(stale).block();

        assertThat(refreshed).isSameAs(rotated);
        assertThat(refreshed.getAccessToken()).isEqualTo("codex-new-access");
        assertThat(called).isFalse();
    }

    @Test
    void recoversCodexRefreshWhenOldRefreshTokenWasAlreadyUsed() {
        AtomicBoolean called = new AtomicBoolean(false);
        ExchangeFunction exchange = request -> {
            called.set(true);
            return Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "error": "invalid_grant",
                      "error_description": "Your access token could not be refreshed because your refresh token was already used. Please log out and sign in again."
                    }
                    """)
                .build());
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity stale = codexAccount();
        ProxyAccountEntity current = codexAccount();
        ProxyAccountEntity rotated = codexAccount();
        rotated.setAccessToken("codex-new-access");
        rotated.setRefreshToken("codex-new-refresh");
        rotated.setTokenExpiresAt(Instant.now().plusSeconds(3600));

        when(repository.findByAccountId("codex-main")).thenReturn(Mono.just(current), Mono.just(rotated));
        when(accountPoolService.refreshCache()).thenReturn(Mono.empty());

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        ProxyAccountEntity refreshed = service.refresh(stale).block();

        assertThat(refreshed).isSameAs(rotated);
        assertThat(refreshed.getAccessToken()).isEqualTo("codex-new-access");
        assertThat(called).isTrue();
    }

    @Test
    void scheduledRefreshSkipsCodexAccountWithoutTokenExpiry() {
        AtomicBoolean called = new AtomicBoolean(false);
        ExchangeFunction exchange = request -> {
            called.set(true);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity codex = codexAccount();

        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(codex));

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        AccountTokenRefreshService.TokenRefreshSummary summary = service.refreshEligibleAccounts().block();

        assertThat(summary).isNotNull();
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.eligible()).isZero();
        assertThat(summary.refreshed()).isZero();
        assertThat(summary.failed()).isZero();
        assertThat(called).isFalse();
    }

    @Test
    void scheduledRefreshDisablesCodexAccountOnTerminatedSession() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
            .header("Content-Type", "application/json")
            .body("""
                {
                  "error": {
                    "message": "Your session has ended. Please log in again.",
                    "code": "app_session_terminated"
                  }
                }
                """)
            .build());
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity codex = codexAccount();
        codex.setTokenExpiresAt(Instant.now().minusSeconds(60));

        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(codex));
        when(repository.findByAccountId("codex-main")).thenReturn(Mono.just(codex));
        when(accountPoolService.recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.FATAL),
            eq(403),
            contains("app_session_terminated")
        )).thenReturn(Mono.empty());

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        AccountTokenRefreshService.TokenRefreshSummary summary = service.refreshEligibleAccounts().block();

        assertThat(summary).isNotNull();
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.refreshed()).isZero();
        assertThat(summary.failed()).isEqualTo(1);
        verify(accountPoolService).recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.FATAL),
            eq(403),
            contains("app_session_terminated")
        );
    }

    @Test
    void scheduledRefreshDisablesCodexAccountOnInvalidatedRefreshToken() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED)
            .header("Content-Type", "application/json")
            .body("""
                {
                  "error": {
                    "message": "Your refresh token has been invalidated. Please try signing in again.",
                    "type": "invalid_request_error",
                    "code": "refresh_token_invalidated"
                  }
                }
                """)
            .build());
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity codex = codexAccount();
        codex.setTokenExpiresAt(Instant.now().minusSeconds(60));

        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(codex));
        when(repository.findByAccountId("codex-main")).thenReturn(Mono.just(codex));
        when(accountPoolService.recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.FATAL),
            eq(403),
            contains("refresh_token_invalidated")
        )).thenReturn(Mono.empty());

        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(exchange).build(),
            repository,
            accountPoolService
        );

        AccountTokenRefreshService.TokenRefreshSummary summary = service.refreshEligibleAccounts().block();

        assertThat(summary).isNotNull();
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.refreshed()).isZero();
        assertThat(summary.failed()).isEqualTo(1);
        verify(accountPoolService).recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.FATAL),
            eq(403),
            contains("refresh_token_invalidated")
        );
    }

    @Test
    void reportsWhetherAccountCanRefresh() {
        AccountTokenRefreshService service = new AccountTokenRefreshService(
            new ObjectMapper(),
            WebClient.builder().exchangeFunction(request -> Mono.empty()).build(),
            mock(ProxyAccountRepository.class),
            mock(AccountPoolService.class)
        );
        ProxyAccountEntity oidc = account();
        ProxyAccountEntity missingCredentials = account();
        missingCredentials.setClientId(null);
        missingCredentials.setClientSecret(null);
        ProxyAccountEntity social = account();
        social.setAuthMethod("social");
        social.setClientId(null);
        social.setClientSecret(null);
        ProxyAccountEntity codex = codexAccount();
        codex.setClientId(null);
        codex.setClientSecret(null);

        assertThat(service.canRefresh(oidc)).isTrue();
        assertThat(service.canRefresh(missingCredentials)).isFalse();
        assertThat(service.canRefresh(social)).isTrue();
        assertThat(service.canRefresh(codex)).isTrue();
    }

    private ProxyAccountEntity account() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("kiro-main");
        account.setAccessToken("old-access");
        account.setRefreshToken("old-refresh");
        account.setClientId("client-id");
        account.setClientSecret("client-secret");
        account.setRegion("us-east-1");
        account.setAuthMethod("idc");
        return account;
    }

    private ProxyAccountEntity codexAccount() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("codex-main");
        account.setAccessToken("codex-old-access");
        account.setRefreshToken("codex-old-refresh");
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);
        return account;
    }

    private String jwtWithExpiration(Instant expiresAt) {
        String header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("{\"exp\":" + expiresAt.getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }
}
