package xyz.star4y.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.account.AccountPoolService;
import xyz.star4y.api.account.AccountPlatforms;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.apikey.ApiKeyPrincipal;
import xyz.star4y.api.apikey.ApiKeyService;
import xyz.star4y.api.model.DefaultModelCatalogProvider;
import xyz.star4y.api.model.ModelMappingService;
import xyz.star4y.api.platform.anthropic.AnthropicRequestAdapter;
import xyz.star4y.api.platform.anthropic.AnthropicResponseFactory;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.core.PlatformUsage;
import xyz.star4y.api.platform.gemini.GeminiRequestAdapter;
import xyz.star4y.api.platform.gemini.GeminiResponseFactory;
import xyz.star4y.api.platform.kiro.KiroPayloadFactory;
import xyz.star4y.api.platform.kiro.KiroUpstreamClient;
import xyz.star4y.api.platform.openai.OpenAiRequestAdapter;
import xyz.star4y.api.platform.openai.OpenAiResponseFactory;
import xyz.star4y.api.platform.openai.OpenAiUpstreamClient;
import xyz.star4y.api.platform.upstream.UpstreamException;
import xyz.star4y.api.stats.StatsService;

class ProxyFacadeRoutingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void openAiModelsUseOpenAiAccountPoolAndUpstream() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "GPT-5.5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/chat/completions")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("GPT-5.5", "key-1")).thenReturn("GPT-5.5");
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(request), eq("GPT-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-main", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(BigDecimal.ZERO), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).chat(request, exchange).block();

        verify(accountPoolService).selectOpenAiAccount("key-1");
        verify(accountPoolService, never()).selectKiroAccount(any());
        verify(openAiUpstreamClient).complete(eq(account), eq(request), eq("GPT-5.5"));
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void chatRetriesNextOpenAiAccountWhenSelectedAccountReturnsQuotaError() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity primary = openAiAccount("openai-main");
        ProxyAccountEntity backup = openAiAccount("openai-backup");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/chat/completions")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(primary));
        when(openAiUpstreamClient.complete(eq(primary), eq(request), eq("gpt-5")))
            .thenReturn(Mono.error(new UpstreamException(429, "quota exhausted", true)));
        when(accountPoolService.recordRequestFailure(
            "openai-main",
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            429,
            "quota exhausted"
        )).thenReturn(Mono.empty());
        when(accountPoolService.selectOpenAiAccount(eq("key-1"), eq(List.of("openai-main"))))
            .thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.complete(eq(backup), eq(request), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-backup", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(BigDecimal.ZERO), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        ResponseEntity<?> response = proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).chat(request, exchange).block();

        assertThat(response).isNotNull();
        verify(accountPoolService).selectOpenAiAccount("key-1");
        verify(accountPoolService).selectOpenAiAccount(eq("key-1"), eq(List.of("openai-main")));
        verify(accountPoolService).recordRequestFailure(
            "openai-main",
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            429,
            "quota exhausted"
        );
        verify(accountPoolService).recordSuccess("openai-backup", 2L);
        verify(openAiUpstreamClient).complete(eq(primary), eq(request), eq("gpt-5"));
        verify(openAiUpstreamClient).complete(eq(backup), eq(request), eq("gpt-5"));
    }

    @Test
    void responsesStreamUsesResponsesEvents() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.4-mini",
              "stream": true,
              "previous_response_id": "resp_prev",
              "input": "hi"
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5.4-mini", "key-1")).thenReturn("gpt-5.4-mini");
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.completeResponses(eq(account), eq(request), eq("gpt-5.4-mini")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-main", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(BigDecimal.ZERO), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        ResponseEntity<?> response = proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).responses(request, exchange).block();

        assertThat(response).isNotNull();
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> body = (Flux<ServerSentEvent<String>>) response.getBody();
        List<ServerSentEvent<String>> events = body.collectList().block();
        assertThat(events).isNotNull();
        assertThat(events)
            .extracting(ServerSentEvent::event)
            .contains("response.created", "response.output_text.delta", "response.completed");

        List<JsonNode> jsonEvents = events.stream()
            .map(ServerSentEvent::data)
            .filter(data -> data != null && !"[DONE]".equals(data))
            .map(this::readJson)
            .toList();
        assertThat(jsonEvents)
            .extracting(event -> event.path("object").asText())
            .doesNotContain("chat.completion.chunk");
        JsonNode completed = jsonEvents.stream()
            .filter(event -> "response.completed".equals(event.path("type").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(completed.path("response").path("object").asText()).isEqualTo("response");
        assertThat(completed.path("response").path("previous_response_id").asText()).isEqualTo("resp_prev");
        verify(openAiUpstreamClient).completeResponses(eq(account), eq(request), eq("gpt-5.4-mini"));
    }

    private ProxyFacade proxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService
    ) {
        return new ProxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            kiroUpstreamClient,
            openAiUpstreamClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            new OpenAiResponseFactory(objectMapper),
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper),
            new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper),
            statsService
        );
    }

    private ProxyAccountEntity openAiAccount() {
        return openAiAccount("openai-main");
    }

    private ProxyAccountEntity openAiAccount(String accountId) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setAccessToken("sk-test");
        account.setEnabled(true);
        return account;
    }

    private JsonNode readJson(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
