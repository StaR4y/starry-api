package xyz.star4y.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.Exceptions;
import xyz.star4y.api.model.DefaultModelCatalogProvider;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.openai.OpenAiAccountTestClient;
import xyz.star4y.api.platform.kiro.KiroPayloadFactory;
import xyz.star4y.api.platform.kiro.KiroUpstreamClient;
import xyz.star4y.api.platform.core.PlatformUsage;
import xyz.star4y.api.platform.upstream.UpstreamException;

class AccountTestServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();

    @Test
    void testsAccountsWithLightweightUpstreamCall() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = account("kiro-main", "token");

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(upstreamClient.complete(any(ProxyAccountEntity.class), any(ObjectNode.class), eq("simple-task")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", PlatformUsage.zero())));
        when(accountPoolService.recordTestSuccess(eq("kiro-main"), eq(0L), eq(false))).thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "simple-task", "ping", 1, false))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.success()).isEqualTo(1);
        assertThat(response.items()).singleElement()
            .satisfies(item -> {
                assertThat(item.accountId()).isEqualTo("kiro-main");
                assertThat(item.status()).isEqualTo("SUCCESS");
                assertThat(item.statusCode()).isEqualTo(200);
            });

        ArgumentCaptor<ObjectNode> payloadCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(upstreamClient).complete(eq(account), payloadCaptor.capture(), eq("simple-task"));
        assertThat(payloadCaptor.getValue()
            .path("conversationState")
            .path("currentMessage")
            .path("userInputMessage")
            .path("origin")
            .asText()).isEqualTo("AI_EDITOR");
    }

    @Test
    void skipsAccountsWithoutAccessToken() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);

        when(repository.findAll()).thenReturn(Flux.just(account("empty", "")));

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, null, null, 1, false))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.skipped()).isEqualTo(1);
        assertThat(response.items()).singleElement()
            .satisfies(item -> {
                assertThat(item.status()).isEqualTo("SKIPPED");
                assertThat(item.message()).isEqualTo("Access token is empty");
            });
        verifyNoInteractions(upstreamClient, openAiAccountTestClient, accountPoolService);
    }

    @Test
    void testsOpenAiAccountsWithOpenAiTestClient() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = openAiAccount("openai-main", "sk-test");

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(openAiAccountTestClient.test(eq(account), eq("gpt-5.4-mini"), eq("ping")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", PlatformUsage.zero())));
        when(accountPoolService.recordTestSuccess(eq("openai-main"), eq(0L), eq(false))).thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "auto", "ping", 1, false))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.success()).isEqualTo(1);
        assertThat(response.items()).singleElement()
            .satisfies(item -> {
                assertThat(item.accountId()).isEqualTo("openai-main");
                assertThat(item.model()).isEqualTo("gpt-5.4-mini");
                assertThat(item.status()).isEqualTo("SUCCESS");
            });
        verify(openAiAccountTestClient).test(eq(account), eq("gpt-5.4-mini"), eq("ping"));
        verifyNoInteractions(upstreamClient);
    }

    @Test
    void updatesAccountStatusWhenRequestedAfterSuccessfulTest() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = account("kiro-main", "token");

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(upstreamClient.complete(any(ProxyAccountEntity.class), any(ObjectNode.class), eq("simple-task")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", PlatformUsage.zero())));
        when(accountPoolService.recordTestSuccess(eq("kiro-main"), eq(0L), eq(true))).thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "simple-task", "ping", 1, true))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.success()).isEqualTo(1);
        verify(accountPoolService).recordTestSuccess(eq("kiro-main"), eq(0L), eq(true));
    }

    @Test
    void testsDisabledAccountWhenUpdatingStatusAndRestoresOnSuccess() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = account("kiro-main", "token");
        account.setEnabled(false);
        account.setSuspendedAt(Instant.parse("2026-06-07T07:00:00Z"));
        account.setQuotaExhaustedAt(Instant.parse("2026-06-07T07:00:00Z"));

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(upstreamClient.complete(any(ProxyAccountEntity.class), any(ObjectNode.class), eq("simple-task")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", PlatformUsage.zero())));
        when(accountPoolService.recordTestSuccess(eq("kiro-main"), eq(0L), eq(true))).thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "simple-task", "ping", 1, true))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.success()).isEqualTo(1);
        verify(upstreamClient).complete(eq(account), any(ObjectNode.class), eq("simple-task"));
        verify(accountPoolService).recordTestSuccess(eq("kiro-main"), eq(0L), eq(true));
    }

    @Test
    void skipsDisabledAccountWhenStatusUpdateIsDisabled() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = account("kiro-main", "token");
        account.setEnabled(false);

        when(repository.findAll()).thenReturn(Flux.just(account));

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "simple-task", "ping", 1, false))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.skipped()).isEqualTo(1);
        assertThat(response.items()).singleElement()
            .satisfies(item -> assertThat(item.message()).isEqualTo("Account is disabled"));
        verifyNoInteractions(upstreamClient, openAiAccountTestClient, accountPoolService);
    }

    @Test
    void stripsOpenRouterOpenAiPrefixForOfficialOpenAiAccountTests() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = openAiAccount("openai-main", "sk-test");

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(openAiAccountTestClient.test(eq(account), eq("gpt-5"), eq("ping")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", PlatformUsage.zero())));
        when(accountPoolService.recordTestSuccess(eq("openai-main"), eq(0L), eq(false))).thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "openai/gpt-5", "ping", 1, false))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.success()).isEqualTo(1);
        assertThat(response.items()).singleElement()
            .satisfies(item -> assertThat(item.model()).isEqualTo("gpt-5"));
        verify(openAiAccountTestClient).test(eq(account), eq("gpt-5"), eq("ping"));
    }

    @Test
    void exposesTestModelOptionsByAccountType() {
        AccountTestService service = new AccountTestService(
            objectMapper,
            mock(ProxyAccountRepository.class),
            mock(AccountPoolService.class),
            new KiroPayloadFactory(objectMapper, modelCatalog),
            mock(KiroUpstreamClient.class),
            mock(OpenAiAccountTestClient.class)
        );

        AccountDtos.TestModelOptionsResponse options = service.modelOptions();

        assertThat(options.kiro()).extracting(AccountDtos.TestModelOption::value)
            .containsExactly("auto", "claude-sonnet-4.5", "simple-task", "claude-haiku-4.5", "claude-sonnet-4", "claude-opus-4.5");
        assertThat(options.openAi())
            .extracting(AccountDtos.TestModelOption::value)
            .containsExactlyElementsOf(options.codex().stream().map(AccountDtos.TestModelOption::value).toList());
        assertThat(options.openAi()).extracting(AccountDtos.TestModelOption::value)
            .containsExactly(
                "auto",
                "gpt-5.5",
                "gpt-5.4",
                "gpt-5.4-mini",
                "gpt-5.4-nano",
                "gpt-5.4-pro",
                "gpt-5.3-chat",
                "gpt-5.2",
                "gpt-5.2-chat",
                "gpt-5.2-pro",
                "gpt-5.1",
                "gpt-5.1-chat",
                "gpt-5",
                "gpt-5-mini",
                "gpt-5-nano",
                "gpt-5-pro",
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4o",
                "gpt-4o-mini"
            );
        assertThat(options.google()).extracting(AccountDtos.TestModelOption::value)
            .hasSize(27)
            .contains("auto", "google/gemini-2.5-pro", "google/gemini-3.5-flash", "google/gemma-4-31b-it:free");
        assertThat(options.anthropic()).extracting(AccountDtos.TestModelOption::value)
            .hasSizeGreaterThanOrEqualTo(27)
            .contains(
                "auto",
                "anthropic/claude-sonnet-4.5",
                "anthropic/claude-sonnet-4-6",
                "anthropic/claude-haiku-4-5-20251001",
                "anthropic/claude-opus-4.8-fast",
                "anthropic/claude-opus-latest"
            );
        assertThat(options.codex()).extracting(AccountDtos.TestModelOption::value)
            .containsExactly(
                "auto",
                "gpt-5.5",
                "gpt-5.4",
                "gpt-5.4-mini",
                "gpt-5.4-nano",
                "gpt-5.4-pro",
                "gpt-5.3-chat",
                "gpt-5.2",
                "gpt-5.2-chat",
                "gpt-5.2-pro",
                "gpt-5.1",
                "gpt-5.1-chat",
                "gpt-5",
                "gpt-5-mini",
                "gpt-5-nano",
                "gpt-5-pro",
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4o",
                "gpt-4o-mini"
            );
    }

    @Test
    void testsCodexAccountsWithCodexDefaultModel() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = openAiAccount("codex-main", "codex-token");
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(openAiAccountTestClient.test(eq(account), eq("gpt-5.4-mini"), eq("ping")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", PlatformUsage.zero())));
        when(accountPoolService.recordTestSuccess(eq("codex-main"), eq(0L), eq(false))).thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "auto", "ping", 1, false))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.success()).isEqualTo(1);
        assertThat(response.items()).singleElement()
            .satisfies(item -> assertThat(item.model()).isEqualTo("gpt-5.4-mini"));
        verify(openAiAccountTestClient).test(eq(account), eq("gpt-5.4-mini"), eq("ping"));
        verifyNoInteractions(upstreamClient);
    }

    @Test
    void normalizesLegacyCodexModelForChatGptAccountTests() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = openAiAccount("codex-main", "codex-token");
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(openAiAccountTestClient.test(eq(account), eq("gpt-5.4-mini"), eq("ping")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", PlatformUsage.zero())));
        when(accountPoolService.recordTestSuccess(eq("codex-main"), eq(0L), eq(false))).thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "gpt-5-codex", "ping", 1, false))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.success()).isEqualTo(1);
        assertThat(response.items()).singleElement()
            .satisfies(item -> assertThat(item.model()).isEqualTo("gpt-5.4-mini"));
        verify(openAiAccountTestClient).test(eq(account), eq("gpt-5.4-mini"), eq("ping"));
        verifyNoInteractions(upstreamClient);
    }

    @Test
    void reportsRootUpstreamErrorWhenRetryWrapperIsPresent() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = account("kiro-main", "token");
        UpstreamException root = new UpstreamException(429, "Kiro upstream error 429 on amazonq: quota", true);

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(upstreamClient.complete(any(ProxyAccountEntity.class), any(ObjectNode.class), eq("auto")))
            .thenReturn(Mono.error(Exceptions.retryExhausted("Retries exhausted: 2/2", root)));
        when(accountPoolService.recordTestFailure(
            eq("kiro-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(429),
            eq("Kiro upstream error 429 on amazonq: quota"),
            eq(false)
        ))
            .thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "auto", "ping", 1, false))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.items()).singleElement()
            .satisfies(item -> {
                assertThat(item.statusCode()).isEqualTo(429);
                assertThat(item.detail()).isEqualTo("Kiro upstream error 429 on amazonq: quota");
            });
    }

    @Test
    void updatesAccountStatusByDefaultAfterFailedTest() {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        KiroUpstreamClient upstreamClient = mock(KiroUpstreamClient.class);
        OpenAiAccountTestClient openAiAccountTestClient = mock(OpenAiAccountTestClient.class);
        ProxyAccountEntity account = account("kiro-main", "token");
        UpstreamException root = new UpstreamException(503, "temporary unavailable", true);

        when(repository.findAll()).thenReturn(Flux.just(account));
        when(upstreamClient.complete(any(ProxyAccountEntity.class), any(ObjectNode.class), eq("auto")))
            .thenReturn(Mono.error(root));
        when(accountPoolService.recordTestFailure(
            eq("kiro-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(503),
            eq("temporary unavailable"),
            eq(true)
        ))
            .thenReturn(Mono.empty());

        AccountTestService service = new AccountTestService(
            objectMapper,
            repository,
            accountPoolService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            upstreamClient,
            openAiAccountTestClient
        );

        AccountDtos.TestAccountsResponse response = service
            .test(new AccountDtos.TestAccountsRequest(null, true, "auto", "ping", 1, null))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.failed()).isEqualTo(1);
        verify(accountPoolService).recordTestFailure(
            eq("kiro-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(503),
            eq("temporary unavailable"),
            eq(true)
        );
    }

    private ProxyAccountEntity account(String accountId, String accessToken) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setEmail(accountId + "@example.com");
        account.setAccessToken(accessToken);
        account.setEnabled(true);
        account.setQuotaUsed(0L);
        account.setQuotaLimit(1000L);
        account.setRegion("us-east-1");
        account.setAuthMethod("idc");
        return account;
    }

    private ProxyAccountEntity openAiAccount(String accountId, String accessToken) {
        ProxyAccountEntity account = account(accountId, accessToken);
        account.setRegion(null);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setBaseUrl(AccountPlatforms.DEFAULT_OPENAI_BASE_URL);
        return account;
    }
}
