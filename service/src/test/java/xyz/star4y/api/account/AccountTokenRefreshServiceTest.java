package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
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

class AccountTokenRefreshServiceTest {

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
        when(repository.save(account)).thenReturn(Mono.just(account));
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
        when(repository.save(eligible)).thenReturn(Mono.just(eligible));
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
        AtomicReference<URI> requestUri = new AtomicReference<>();
        AtomicReference<MediaType> requestContentType = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUri.set(request.url());
            requestContentType.set(request.headers().getContentType());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "access_token":"codex-new-access",
                      "refresh_token":"codex-new-refresh",
                      "id_token":"codex-id-token",
                      "expires_in":3600
                    }
                    """)
                .build());
        };
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ProxyAccountEntity account = codexAccount();
        account.setClientId("codex-client-id");

        when(repository.findByAccountId("codex-main")).thenReturn(Mono.just(account));
        when(repository.save(account)).thenReturn(Mono.just(account));
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
        assertThat(requestContentType.get()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(refreshed.getAccessToken()).isEqualTo("codex-new-access");
        assertThat(refreshed.getRefreshToken()).isEqualTo("codex-new-refresh");
        assertThat(refreshed.getIdToken()).isEqualTo("codex-id-token");
        assertThat(refreshed.getClientId()).isEqualTo("codex-client-id");
        assertThat(refreshed.getTokenExpiresAt()).isAfterOrEqualTo(Instant.now().plusSeconds(3500));
        assertThat(service.canRefresh(account)).isTrue();
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
}
