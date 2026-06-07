package xyz.star4y.api.proxy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
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
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("openai-main");
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setAccessToken("sk-test");
        account.setEnabled(true);
        return account;
    }
}
