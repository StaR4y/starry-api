package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

        assertThat(service.canRefresh(oidc)).isTrue();
        assertThat(service.canRefresh(missingCredentials)).isFalse();
        assertThat(service.canRefresh(social)).isTrue();
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
}
