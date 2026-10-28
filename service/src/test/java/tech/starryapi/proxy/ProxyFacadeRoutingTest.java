package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("GPT-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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

        ArgumentCaptor<AccountPoolRequestContext> poolContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        verify(accountPoolService).selectOpenAiAccountForPool(eq("key-1"), poolContext.capture());
        assertThat(poolContext.getValue().requestToken()).isNotBlank();
        assertThat(poolContext.getValue().requestedModel()).isEqualTo("GPT-5.5");
        assertThat(poolContext.getValue().upstreamModel()).isEqualTo("GPT-5.5");
        assertThat(poolContext.getValue().providerHint()).isEqualTo(AccountPlatforms.OPENAI_PROVIDER);
        verify(accountPoolService, never()).selectKiroAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).complete(eq(account), eq(billingRequest(request, false)), eq("GPT-5.5"));
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void openAiCompatiblePrefixUsesOpenAiAccountPoolAndKeepsMappedModel() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount();
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        JsonNode request = objectMapper.readTree("""
            {
              "model": "deepseek",
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
        when(modelMappingService.apply("deepseek", "key-1")).thenReturn("openai-compatible/deepseek-chat");
        when(accountPoolService.selectOpenAiCompatibleAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("openai-compatible/deepseek-chat")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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

        ArgumentCaptor<AccountPoolRequestContext> poolContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        verify(accountPoolService).selectOpenAiCompatibleAccountForPool(
            eq("key-1"),
            poolContext.capture()
        );
        assertThat(poolContext.getValue().requestedModel()).isEqualTo("deepseek");
        assertThat(poolContext.getValue().upstreamModel()).isEqualTo("openai-compatible/deepseek-chat");
        assertThat(poolContext.getValue().providerHint()).isEqualTo(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        verify(accountPoolService, never()).selectOpenAiAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).selectKiroAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).complete(eq(account), eq(billingRequest(request, false)), eq("openai-compatible/deepseek-chat"));
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void glmCatalogModelsUseOpenAiCompatiblePoolInsteadOfKiro() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        modelCatalog.applyCustomizations(List.of(new tech.starryapi.model.ModelCatalogCustomization(
            "glm5.3",
            "GLM 5.3",
            null,
            "GLM",
            "GLM 5",
            "glm5.3",
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            128000,
            "GLM",
            true,
            null
        )));
        ProxyAccountEntity account = openAiAccount();
        account.setProvider("GLM");
        account.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "glm5.3",
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
        when(modelMappingService.apply("glm5.3", "key-1")).thenReturn("glm5.3");
        when(accountPoolService.selectOpenAiCompatibleAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("glm5.3")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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

        verify(accountPoolService).selectOpenAiCompatibleAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).selectKiroAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).complete(eq(account), eq(billingRequest(request, false)), eq("glm5.3"));
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void deepSeekModelsUseDedicatedAccountPoolAndUpstream() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = deepSeekAccount();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "deepseek-v4-flash",
              "service_tier": "priority",
              "serviceTier": "fast",
              "fast": true,
              "fast_mode": true,
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
        when(modelMappingService.apply("deepseek-v4-flash", "key-1")).thenReturn("deepseek-v4-flash");
        when(accountPoolService.selectDeepSeekOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), any(JsonNode.class), eq("deepseek-v4-flash")))
            .thenReturn(Mono.just(new PlatformCompletionResult(
                "OK",
                new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0)
            )));
        when(accountPoolService.recordSuccess(
            eq("deepseek-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(
            any(ApiKeyPrincipal.class),
            anyString(),
            eq(credits("2")),
            eq(1L),
            eq(1L)
        )).thenReturn(Mono.empty());
        when(accountPoolService.circuitKey(any(AccountPoolRequestContext.class)))
            .thenReturn("deepseek:deepseek-v4");
        when(accountPoolService.circuitTier(eq(account), any(AccountPoolRequestContext.class)))
            .thenReturn("primary");
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

        ArgumentCaptor<AccountPoolRequestContext> selectionContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        verify(accountPoolService).selectDeepSeekOpenAiAccountForPool(
            eq("key-1"),
            selectionContext.capture()
        );
        assertThat(selectionContext.getValue().requestToken()).isNotBlank();
        assertThat(selectionContext.getValue().requestedModel()).isEqualTo("deepseek-v4-flash");
        assertThat(selectionContext.getValue().upstreamModel()).isEqualTo("deepseek-v4-flash");
        assertThat(selectionContext.getValue().providerHint()).isEqualTo(AccountPlatforms.DEEPSEEK_PROVIDER);
        verify(accountPoolService, never()).selectOpenAiAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).selectKiroAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openAiUpstreamClient).complete(eq(account), requestCaptor.capture(), eq("deepseek-v4-flash"));
        assertThat(requestCaptor.getValue().has("service_tier")).isFalse();
        assertThat(requestCaptor.getValue().has("serviceTier")).isFalse();
        assertThat(requestCaptor.getValue().has("fast")).isFalse();
        assertThat(requestCaptor.getValue().has("fast_mode")).isFalse();
        assertThat(request.has("service_tier")).isTrue();
        ArgumentCaptor<StatsService.RequestLog> requestLog = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService).log(requestLog.capture());
        assertThat(requestLog.getValue().circuitKey()).isEqualTo("deepseek:deepseek-v4");
        assertThat(requestLog.getValue().circuitTier()).isEqualTo("primary");
        assertThat(requestLog.getValue().upstreamModel()).isEqualTo("deepseek-v4-flash");
        assertThat(requestLog.getValue().upstreamSuccess()).isTrue();
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void deepSeekRateLimitRetryKeepsContextAndDoesNotTreat429AsQuotaExhaustion() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity primary = deepSeekAccount("deepseek-main");
        ProxyAccountEntity backup = deepSeekAccount("deepseek-backup");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "deepseek-v4-flash",
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
        when(modelMappingService.apply("deepseek-v4-flash", "key-1")).thenReturn("deepseek-v4-flash");
        when(accountPoolService.selectDeepSeekOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(primary));
        when(accountPoolService.selectDeepSeekOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("deepseek-main")),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.complete(eq(primary), any(JsonNode.class), eq("deepseek-v4-flash")))
            .thenReturn(Mono.error(new UpstreamException(429, "rate limited", true)));
        when(openAiUpstreamClient.complete(eq(backup), any(JsonNode.class), eq("deepseek-v4-flash")))
            .thenReturn(Mono.just(new PlatformCompletionResult(
                "OK",
                new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0)
            )));
        when(accountPoolService.recordRequestFailure(
            eq("deepseek-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(429),
            eq("rate limited"),
            eq(false),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("deepseek-main", 1, 429, false)));
        when(accountPoolService.recordSuccess(
            eq("deepseek-backup"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(
            any(ApiKeyPrincipal.class),
            anyString(),
            eq(credits("2")),
            eq(1L),
            eq(1L)
        )).thenReturn(Mono.empty());
        when(accountPoolService.circuitKey(any(AccountPoolRequestContext.class)))
            .thenReturn("deepseek:deepseek-v4");
        when(accountPoolService.circuitTier(
            any(ProxyAccountEntity.class),
            any(AccountPoolRequestContext.class)
        )).thenReturn("primary");
        when(statsService.log(any())).thenReturn(Mono.empty());

        ResponseEntity<?> response = proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).chat(request, exchange).block();

        assertThat(response).isNotNull();
        ArgumentCaptor<AccountPoolRequestContext> initialContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        ArgumentCaptor<AccountPoolRequestContext> retryContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        ArgumentCaptor<AccountPoolRequestContext> failureContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        ArgumentCaptor<AccountPoolRequestContext> successContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        verify(accountPoolService).selectDeepSeekOpenAiAccountForPool(
            eq("key-1"),
            initialContext.capture()
        );
        verify(accountPoolService).selectDeepSeekOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("deepseek-main")),
            retryContext.capture()
        );
        verify(accountPoolService).recordRequestFailure(
            eq("deepseek-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(429),
            eq("rate limited"),
            eq(false),
            failureContext.capture()
        );
        verify(accountPoolService).recordSuccess(
            eq("deepseek-backup"),
            eq(2L),
            successContext.capture()
        );
        AccountPoolRequestContext context = initialContext.getValue();
        assertThat(retryContext.getValue()).isSameAs(context);
        assertThat(failureContext.getValue()).isSameAs(context);
        assertThat(successContext.getValue()).isSameAs(context);
        assertThat(context.requestToken()).isNotBlank();
        assertThat(context.requestedModel()).isEqualTo("deepseek-v4-flash");
        assertThat(context.upstreamModel()).isEqualTo("deepseek-v4-flash");
        assertThat(context.providerHint()).isEqualTo(AccountPlatforms.DEEPSEEK_PROVIDER);
        verify(accountPoolService, never()).selectOpenAiAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, org.mockito.Mockito.times(2)).log(logs.capture());
        assertThat(logs.getAllValues())
            .extracting(
                StatsService.RequestLog::circuitKey,
                StatsService.RequestLog::circuitTier,
                StatsService.RequestLog::upstreamModel
            )
            .containsOnly(org.assertj.core.groups.Tuple.tuple(
                "deepseek:deepseek-v4",
                "primary",
                "deepseek-v4-flash"
            ));
        assertThat(logs.getAllValues())
            .extracting(StatsService.RequestLog::upstreamSuccess)
            .containsExactlyInAnyOrder(null, true);
    }

    @Test
    void clientCancellationReleasesTheModelFamilyProbe() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount("openai-probe");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
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
        when(modelMappingService.apply("gpt-5.5", "key-1")).thenReturn("gpt-5.5");
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), any(JsonNode.class), eq("gpt-5.5")))
            .thenReturn(Mono.never());

        Disposable requestSubscription = proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).chat(request, exchange).subscribe();

        ArgumentCaptor<AccountPoolRequestContext> selectedContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        verify(accountPoolService).selectOpenAiAccountForPool(eq("key-1"), selectedContext.capture());

        requestSubscription.dispose();

        ArgumentCaptor<AccountPoolRequestContext> cancelledContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        verify(accountPoolService).cancelCircuitProbe(cancelledContext.capture());
        assertThat(cancelledContext.getValue()).isSameAs(selectedContext.getValue());
    }

    @Test
    void deepSeekProResponsesFailsBeforeAccountSelection() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "deepseek-v4-pro",
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
        when(modelMappingService.apply("deepseek-v4-pro", "key-1")).thenReturn("deepseek-v4-pro");

        assertThatThrownBy(() -> proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).responses(request, exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("UNSUPPORTED_MODEL_OPERATION");
                assertThat(error.getMessage()).contains("deepseek-v4-flash");
            });

        verify(accountPoolService, never()).selectDeepSeekOpenAiAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).selectDeepSeekOpenAiAccountForPool(
            anyString(),
            anyCollection(),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).recordRequestFailure(
            anyString(),
            any(),
            any(),
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).recordRequestFailure(
            anyString(),
            any(),
            any(),
            anyString(),
            anyBoolean(),
            any(AccountPoolRequestContext.class)
        );
        verifyNoInteractions(openAiUpstreamClient);
    }

    @Test
    void chatAutoRetestsDisabledAccountsOnceWhenNoOpenAiAccountIsAvailable() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        DisabledAccountScheduledTestService disabledAccountScheduledTestService = mock(DisabledAccountScheduledTestService.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/chat/completions")
        );
        Instant now = Instant.now();

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(
                Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available OpenAI upstream account")),
                Mono.just(account)
            );
        when(disabledAccountScheduledTestService.runWhenNoAvailableAccounts(any()))
            .thenReturn(Mono.just(new AccountDtos.TestAccountsResponse(
                1,
                1,
                0,
                0,
                now,
                now,
                List.of(new AccountDtos.TestAccountItemResponse(
                    account.getAccountId(),
                    account.getEmail(),
                    "SUCCESS",
                    "OK",
                    "OK",
                    200,
                    10L,
                    now,
                    "gpt-5"
                ))
            )));
        when(openAiUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        ResponseEntity<?> response = proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            mock(AccountSettingsService.class),
            paymentSettingsService(BigDecimal.ONE),
            mock(AnthropicUpstreamClient.class),
            disabledAccountScheduledTestService
        ).chat(request, exchange).block();

        assertThat(response).isNotNull();
        verify(disabledAccountScheduledTestService).runWhenNoAvailableAccounts(any());
        verify(accountPoolService, org.mockito.Mockito.times(2)).selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).complete(eq(account), eq(billingRequest(request, false)), eq("gpt-5"));
    }

    @Test
    void chatAutoRetestsDisabledAccountsAfterRetryPoolIsExhausted() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        DisabledAccountScheduledTestService disabledAccountScheduledTestService = mock(DisabledAccountScheduledTestService.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount();
        ProxyAccountEntity recovered = openAiAccount("openai-recovered");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/chat/completions")
        );
        Instant now = Instant.now();

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(Mono.just(account));
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("openai-main")),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(
                Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available OpenAI upstream account")),
                Mono.just(recovered)
            );
        when(openAiUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("gpt-5")))
            .thenReturn(Mono.error(new UpstreamException(429, "quota exhausted", true)));
        when(openAiUpstreamClient.complete(eq(recovered), eq(billingRequest(request, false)), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordRequestFailure(
            eq("openai-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(429),
            eq("quota exhausted"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("openai-main", 2, 429, true)));
        when(disabledAccountScheduledTestService.runWhenNoAvailableAccounts(any()))
            .thenReturn(Mono.just(new AccountDtos.TestAccountsResponse(
                1,
                1,
                0,
                0,
                now,
                now,
                List.of(new AccountDtos.TestAccountItemResponse(
                    recovered.getAccountId(),
                    recovered.getEmail(),
                    "SUCCESS",
                    "OK",
                    "OK",
                    200,
                    10L,
                    now,
                    "gpt-5"
                ))
            )));
        when(accountPoolService.recordSuccess(
            eq("openai-recovered"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        ResponseEntity<?> response = proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            mock(AccountSettingsService.class),
            paymentSettingsService(BigDecimal.ONE),
            mock(AnthropicUpstreamClient.class),
            disabledAccountScheduledTestService
        ).chat(request, exchange).block();

        assertThat(response).isNotNull();
        ArgumentCaptor<AccountPoolRequestContext> initialSelections = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        ArgumentCaptor<AccountPoolRequestContext> retrySelection = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        ArgumentCaptor<AccountPoolRequestContext> failureContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        ArgumentCaptor<AccountPoolRequestContext> successContext = ArgumentCaptor.forClass(
            AccountPoolRequestContext.class
        );
        verify(accountPoolService).selectOpenAiAccountForPool(
            eq("key-1"),
            initialSelections.capture()
        );
        verify(accountPoolService, org.mockito.Mockito.times(2)).selectOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("openai-main")),
            retrySelection.capture()
        );
        verify(accountPoolService).recordRequestFailure(
            eq("openai-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(429),
            eq("quota exhausted"),
            failureContext.capture()
        );
        verify(disabledAccountScheduledTestService).runWhenNoAvailableAccounts(any());
        verify(openAiUpstreamClient).complete(eq(account), eq(billingRequest(request, false)), eq("gpt-5"));
        verify(openAiUpstreamClient).complete(eq(recovered), eq(billingRequest(request, false)), eq("gpt-5"));
        verify(accountPoolService).recordSuccess(eq("openai-recovered"), eq(2L), successContext.capture());
        AccountPoolRequestContext requestContext = initialSelections.getValue();
        assertThat(retrySelection.getAllValues())
            .allSatisfy(context -> assertThat(context).isSameAs(requestContext));
        assertThat(failureContext.getValue()).isSameAs(requestContext);
        assertThat(successContext.getValue()).isSameAs(requestContext);
        assertThat(requestContext.requestToken()).isNotBlank();
        assertThat(requestContext.requestedModel()).isEqualTo("gpt-5");
        assertThat(requestContext.upstreamModel()).isEqualTo("gpt-5");
        assertThat(requestContext.providerHint()).isEqualTo(AccountPlatforms.OPENAI_PROVIDER);
    }

    @Test
    void anthropicPrefixedModelsUseAnthropicAccountPoolAndUpstream() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        AnthropicUpstreamClient anthropicUpstreamClient = mock(AnthropicUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = anthropicAccount();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "claude",
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
        when(modelMappingService.apply("claude", "key-1")).thenReturn("anthropic/claude-sonnet-4.5");
        when(accountPoolService.selectAnthropicAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(anthropicUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("anthropic/claude-sonnet-4.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("anthropic-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        proxyFacade(
            apiKeyService,
            accountPoolService,
            mock(AccountAutoSuspendService.class),
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            mock(AccountSettingsService.class),
            paymentSettingsService(BigDecimal.ONE),
            anthropicUpstreamClient
        ).chat(request, exchange).block();

        verify(accountPoolService).selectAnthropicAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(anthropicUpstreamClient).complete(eq(account), eq(billingRequest(request, false)), eq("anthropic/claude-sonnet-4.5"));
        verify(openAiUpstreamClient, never()).complete(any(), any(), any());
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void anthropicMessagesFallbackToKiroWhenNoAnthropicApiKeyAccountExists() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        AnthropicUpstreamClient anthropicUpstreamClient = mock(AnthropicUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = kiroAccount();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "claude-sonnet-4.5",
              "max_tokens": 64,
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/messages")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("claude-sonnet-4.5", "key-1")).thenReturn("claude-sonnet-4.5");
        when(accountPoolService.selectAnthropicAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available Anthropic upstream account")));
        when(accountPoolService.selectKiroAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(kiroUpstreamClient.complete(eq(account), any(ObjectNode.class), eq("claude-sonnet-4.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("kiro-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        proxyFacade(
            apiKeyService,
            accountPoolService,
            mock(AccountAutoSuspendService.class),
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            mock(AccountSettingsService.class),
            paymentSettingsService(BigDecimal.ONE),
            anthropicUpstreamClient
        ).messages(request, exchange).block();

        verify(accountPoolService).selectAnthropicAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).selectKiroAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(kiroUpstreamClient).complete(eq(account), any(ObjectNode.class), eq("claude-sonnet-4.5"));
        verify(anthropicUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void anthropicMessagesFallbackToKiroWhenAnthropicSubtypeCircuitIsOpen() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        AnthropicUpstreamClient anthropicUpstreamClient = mock(AnthropicUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = kiroAccount();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "claude-sonnet-4.5",
              "max_tokens": 64,
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/messages")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("claude-sonnet-4.5", "key-1")).thenReturn("claude-sonnet-4.5");
        when(accountPoolService.selectAnthropicAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.error(new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "ACCOUNT_POOL_CIRCUIT_OPEN",
            "Model-family account pool circuit is open and no Anthropic secondary account is available"
        )));
        when(accountPoolService.selectKiroAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(kiroUpstreamClient.complete(eq(account), any(ObjectNode.class), eq("claude-sonnet-4.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("kiro-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L)))
            .thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        proxyFacade(
            apiKeyService,
            accountPoolService,
            mock(AccountAutoSuspendService.class),
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            mock(AccountSettingsService.class),
            paymentSettingsService(BigDecimal.ONE),
            anthropicUpstreamClient
        ).messages(request, exchange).block();

        verify(accountPoolService).selectAnthropicAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).selectKiroAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(kiroUpstreamClient).complete(eq(account), any(ObjectNode.class), eq("claude-sonnet-4.5"));
        verify(anthropicUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void userApiRequestsConsumeAccountBalanceAfterSuccessfulUsage() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount();
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
            "key-1",
            "user-1",
            "USER",
            "test",
            null,
            BigDecimal.ZERO,
            BigDecimal.valueOf(100)
        );
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/chat/completions")
        );

        when(apiKeyService.authenticate(exchange)).thenReturn(Mono.just(principal));
        when(apiKeyService.ensureBalanceAvailable(principal)).thenReturn(Mono.empty());
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(2, 3, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(5L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(eq(principal), anyString(), eq(credits("5")), eq(2L), eq(3L))).thenReturn(Mono.empty());
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

        verify(apiKeyService).ensureBalanceAvailable(principal);
        verify(apiKeyService).accountUsage(eq(principal), anyString(), eq(credits("5")), eq(2L), eq(3L));
    }

    @Test
    void defaultModelPriceBillingMultiplierIsOne() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        PaymentSettingsService paymentSettingsService = paymentSettingsService(PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount();
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
            "key-1",
            "user-1",
            "USER",
            "test",
            null,
            BigDecimal.ZERO,
            BigDecimal.valueOf(200)
        );
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/chat/completions")
        );
        BigDecimal expectedCredits = new BigDecimal("6000000.000000");

        when(apiKeyService.authenticate(exchange)).thenReturn(Mono.just(principal));
        when(apiKeyService.ensureBalanceAvailable(principal)).thenReturn(Mono.empty());
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(3_000_000, 3_000_000, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(6_000_000L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(eq(principal), anyString(), eq(expectedCredits), eq(3_000_000L), eq(3_000_000L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            paymentSettingsService
        ).chat(request, exchange).block();

        verify(apiKeyService).accountUsage(
            eq(principal),
            anyString(),
            eq(expectedCredits),
            eq(3_000_000L),
            eq(3_000_000L)
        );
    }

    @Test
    void kiroUsageFallsBackToTokenCreditsWhenUpstreamCreditsAreMissing() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = kiroAccount();
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
            "key-1",
            "user-1",
            "USER",
            "test",
            null,
            BigDecimal.ZERO,
            BigDecimal.valueOf(100)
        );
        JsonNode request = objectMapper.readTree("""
            {
              "model": "claude-sonnet-4.5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/chat/completions")
        );

        when(apiKeyService.authenticate(exchange)).thenReturn(Mono.just(principal));
        when(apiKeyService.ensureBalanceAvailable(principal)).thenReturn(Mono.empty());
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("claude-sonnet-4.5", "key-1")).thenReturn("claude-sonnet-4.5");
        when(accountPoolService.selectKiroAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(kiroUpstreamClient.complete(eq(account), any(ObjectNode.class), eq("claude-sonnet-4.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(4, 6, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("kiro-main"),
            eq(10L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(eq(principal), anyString(), eq(credits("10")), eq(4L), eq(6L))).thenReturn(Mono.empty());
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

        verify(apiKeyService).accountUsage(eq(principal), anyString(), eq(credits("10")), eq(4L), eq(6L));
    }

    @Test
    void openAiFastModelUsesPriorityTierAndDoubleTokenCredits() throws Exception {
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
              "model": "starry/gpt-5-fast",
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
        when(modelMappingService.apply("starry/gpt-5-fast", "key-1")).thenReturn("starry/gpt-5-fast");
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), any(JsonNode.class), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(3, 2, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(5L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("10")), eq(3L), eq(2L))).thenReturn(Mono.empty());
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

        ArgumentCaptor<JsonNode> forwardedRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(openAiUpstreamClient).complete(eq(account), forwardedRequest.capture(), eq("gpt-5"));
        assertThat(forwardedRequest.getValue().path("service_tier").asString("")).isEqualTo("priority");
        verify(apiKeyService).accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("10")), eq(3L), eq(2L));
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void openAiFastModelIsRejectedWhenAdminSettingDisabled() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountSettingsService accountSettingsService = mock(AccountSettingsService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        JsonNode request = objectMapper.readTree("""
            {
              "model": "starry/gpt-5-fast",
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
        when(modelMappingService.apply("starry/gpt-5-fast", "key-1")).thenReturn("starry/gpt-5-fast");
        when(accountSettingsService.ensureGptFastModeAllowed()).thenReturn(Mono.error(new ApiException(
            HttpStatus.FORBIDDEN,
            "GPT_FAST_MODE_DISABLED",
            "GPT fast mode is not allowed by administrator"
        )));

        assertThatThrownBy(() -> proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            accountSettingsService
        ).chat(request, exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getCode()).isEqualTo("GPT_FAST_MODE_DISABLED");
                assertThat(error.getMessage()).isEqualTo("GPT fast mode is not allowed by administrator");
            });

        verify(accountSettingsService).ensureGptFastModeAllowed();
        verify(accountPoolService, never()).selectOpenAiAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient, never()).complete(any(), any(), any());
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void chatRetriesAfterQuotaFailureEvenWhenAccountAndRequestLogPersistenceAreUnavailable() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
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
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(primary));
        when(openAiUpstreamClient.complete(eq(primary), eq(billingRequest(request, false)), eq("gpt-5")))
            .thenReturn(Mono.error(new UpstreamException(429, "quota exhausted", true)));
        when(accountPoolService.recordRequestFailure(
            eq("openai-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(429),
            eq("quota exhausted"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.error(new IllegalStateException("database unavailable")));
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("openai-main")),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.complete(eq(backup), eq(billingRequest(request, false)), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-backup"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(accountPoolService.recordCircuitSuccess(
            any(AccountPoolRequestContext.class),
            eq(backup)
        )).thenReturn(73L);
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.error(new IllegalStateException("request log unavailable")));

        ResponseEntity<?> response = proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).chat(request, exchange).block();

        assertThat(response).isNotNull();
        verify(accountPoolService).selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).selectOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("openai-main")),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).recordRequestFailure(
            eq("openai-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(429),
            eq("quota exhausted"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).cancelCircuitProbe(any(AccountPoolRequestContext.class));
        verify(accountAutoSuspendService, never()).scheduleIfNeeded(any());
        verify(accountPoolService).recordSuccess(
            eq("openai-backup"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).recordCircuitSuccess(any(AccountPoolRequestContext.class), eq(backup));
        verify(accountPoolService).markCircuitOutcomePersistenceFailed(
            any(AccountPoolRequestContext.class),
            eq(73L)
        );
        verify(openAiUpstreamClient).complete(eq(primary), eq(billingRequest(request, false)), eq("gpt-5"));
        verify(openAiUpstreamClient).complete(eq(backup), eq(billingRequest(request, false)), eq("gpt-5"));
    }

    @Test
    void settlementRejectionDoesNotPenalizeOrRetryTheSuccessfulUpstreamAccount() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount("openai-main");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/chat/completions")
        );
        ApiException settlementError = new ApiException(
            HttpStatus.PAYMENT_REQUIRED,
            "INSUFFICIENT_BALANCE",
            "Account balance is insufficient for this request"
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(billingRequest(request, false)), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult(
                "OK",
                new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0)
            )));
        when(apiKeyService.accountUsage(
            any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L)
        )).thenReturn(Mono.error(settlementError));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(accountPoolService.recordCircuitSuccess(
            any(AccountPoolRequestContext.class),
            eq(account)
        )).thenReturn(74L);
        when(statsService.log(any())).thenReturn(Mono.empty());

        assertThatThrownBy(() -> proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).chat(request, exchange).block())
            .isSameAs(settlementError);

        verify(openAiUpstreamClient).complete(eq(account), eq(billingRequest(request, false)), eq("gpt-5"));
        verify(accountPoolService).recordSuccess(
            eq("openai-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).recordCircuitSuccess(any(AccountPoolRequestContext.class), eq(account));
        verify(accountPoolService).acknowledgeCircuitOutcome(
            any(AccountPoolRequestContext.class),
            eq(74L)
        );
        verify(accountPoolService, never()).recordRequestFailure(
            anyString(),
            any(AccountPoolService.UpstreamErrorType.class),
            any(),
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        ArgumentCaptor<StatsService.RequestLog> rejectedLog = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService).log(rejectedLog.capture());
        assertThat(rejectedLog.getValue().statusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
        assertThat(rejectedLog.getValue().success()).isFalse();
        assertThat(rejectedLog.getValue().upstreamSuccess()).isTrue();
        assertThat(rejectedLog.getValue().upstreamModel()).isEqualTo("gpt-5");
        verify(accountAutoSuspendService, never()).scheduleIfNeeded(any());
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
              "model": "gpt-5.4",
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
        when(modelMappingService.apply("gpt-5.4", "key-1")).thenReturn("gpt-5.4");
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1:resp_prev"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.completeResponses(eq(account), eq(billingRequest(request, true)), eq("gpt-5.4")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
            .extracting(event -> event.path("object").asString(""))
            .doesNotContain("chat.completion.chunk");
        JsonNode completed = jsonEvents.stream()
            .filter(event -> "response.completed".equals(event.path("type").asString("")))
            .findFirst()
            .orElseThrow();
        assertThat(completed.path("response").path("object").asString("")).isEqualTo("response");
        assertThat(completed.path("response").path("previous_response_id").asString("")).isEqualTo("resp_prev");
        verify(openAiUpstreamClient).completeResponses(eq(account), eq(billingRequest(request, true)), eq("gpt-5.4"));
    }

    @Test
    void codexResponsesClientPrefersCodexAccountPool() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity codex = codexAccount("codex-main");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "stream": false,
              "input": "hi"
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses")
                .header("User-Agent", "codex_cli_rs/0.139.0")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5.5", "key-1")).thenReturn("gpt-5.5");
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("codex-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        verify(accountPoolService).selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).selectOpenAiAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).completeResponses(eq(codex), any(), eq("gpt-5.5"));
    }

    @Test
    void codexResponsesClientFallsBackToOpenAiWhenCodexSubtypeCircuitIsOpen() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity backup = openAiAccount("openai-secondary");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "stream": false,
              "input": "hi"
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses")
                .header("User-Agent", "codex_cli_rs/0.139.0")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5.5", "key-1")).thenReturn("gpt-5.5");
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.error(new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "ACCOUNT_POOL_CIRCUIT_OPEN",
            "Model-family account pool circuit is open and no Codex secondary account is available"
        )));
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.completeResponses(eq(backup), any(), eq("gpt-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-secondary"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L)))
            .thenReturn(Mono.empty());
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
        verify(accountPoolService).selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).selectOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).completeResponses(eq(backup), any(), eq("gpt-5.5"));
    }

    @Test
    void codexOnlyResponsesModelPrefersCodexAccountPoolWithoutCodexHeaders() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity codex = codexAccount("codex-main");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "stream": false,
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
        when(modelMappingService.apply("gpt-5.5", "key-1")).thenReturn("gpt-5.5");
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("codex-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        verify(accountPoolService).selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).selectOpenAiAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).completeResponses(eq(codex), any(), eq("gpt-5.5"));
    }

    @Test
    void codexPreferredResponsesModelFallsBackToOpenAiCompatibleAfterCodexUpstream503() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity codex = codexAccount("codex-main");
        ProxyAccountEntity backup = openAiAccount("openai-backup");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "stream": false,
              "input": "hi"
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses")
                .header("User-Agent", "codex_cli_rs/0.139.0")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5.5", "key-1")).thenReturn("gpt-5.5");
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-5.5")))
            .thenReturn(Mono.error(new UpstreamException(503, "OpenAI upstream error 503: Service temporarily unavailable", true)));
        when(accountPoolService.recordCircuitFailure(
            any(AccountPoolRequestContext.class),
            eq(codex)
        )).thenReturn(75L);
        when(accountPoolService.recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(503),
            eq("OpenAI upstream error 503: Service temporarily unavailable"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("codex-main", 1, 503, false)));
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available Codex upstream account")));
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.completeResponses(eq(backup), any(), eq("gpt-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-backup"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        ResponseEntity<?> response = proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).responses(request, exchange).block();

        assertThat(response).isNotNull();
        verify(accountPoolService).selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).selectCodexOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).selectOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).completeResponses(eq(codex), any(), eq("gpt-5.5"));
        verify(openAiUpstreamClient).completeResponses(eq(backup), any(), eq("gpt-5.5"));
        verify(accountPoolService).recordCircuitFailure(
            any(AccountPoolRequestContext.class),
            eq(codex)
        );
        verify(accountPoolService).acknowledgeCircuitOutcome(
            any(AccountPoolRequestContext.class),
            eq(75L)
        );
    }

    @Test
    void codexResponsesClientFallsBackAfterCodexUpstream503ForOpenAiModel() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity codex = codexAccount("codex-main");
        ProxyAccountEntity backup = openAiAccount("openai-backup");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-4o-mini",
              "stream": false,
              "input": "hi"
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses")
                .header("User-Agent", "codex_cli_rs/0.139.0")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-4o-mini", "key-1")).thenReturn("gpt-4o-mini");
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-4o-mini")))
            .thenReturn(Mono.error(new UpstreamException(503, "OpenAI upstream error 503: Service temporarily unavailable", true)));
        when(accountPoolService.recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(503),
            eq("OpenAI upstream error 503: Service temporarily unavailable"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("codex-main", 1, 503, false)));
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available Codex upstream account")));
        when(accountPoolService.selectOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        ))
            .thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.completeResponses(eq(backup), any(), eq("gpt-4o-mini")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(
            eq("openai-backup"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        ResponseEntity<?> response = proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        ).responses(request, exchange).block();

        assertThat(response).isNotNull();
        verify(accountPoolService).selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).selectCodexOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService).selectOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).completeResponses(eq(codex), any(), eq("gpt-4o-mini"));
        verify(openAiUpstreamClient).completeResponses(eq(backup), any(), eq("gpt-4o-mini"));
    }

    @Test
    void gptChatUsesCodexPoolWhenGptUsesCodexAccountsEnabled() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity codex = codexAccount("codex-main");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-4o",
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
        when(modelMappingService.apply("gpt-4o", "key-1")).thenReturn("gpt-4o");
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.complete(eq(codex), eq(billingRequest(request, false)), eq("gpt-4o")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess(eq("codex-main"), eq(2L), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), eq(credits("2")), eq(1L), eq(1L)))
            .thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            stubbedAccountSettings(new AccountDtos.RoutingPolicySettings(
                true, true, false, true, 1, 3, 3, 2, 2
            ))
        ).chat(request, exchange).block();

        verify(accountPoolService).selectCodexOpenAiAccountForPool(eq("key-1"), any(AccountPoolRequestContext.class));
        verify(accountPoolService, never()).selectOpenAiAccountForPool(anyString(), any(AccountPoolRequestContext.class));
        verify(accountPoolService, never()).selectOpenAiApiKeyAccountForPool(anyString(), any(AccountPoolRequestContext.class));
        verify(accountPoolService, org.mockito.Mockito.atLeastOnce())
            .commitSessionAffinity(
                any(AccountPoolRequestContext.class),
                eq("key-1"),
                eq("codex-main"),
                eq(true)
            );
        verify(openAiUpstreamClient).complete(eq(codex), eq(billingRequest(request, false)), eq("gpt-4o"));
    }

    @Test
    void isolatedCodexPoolDoesNotFallBackToOpenAiApiKeyAfterFailure() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity codex = codexAccount("codex-main");
        JsonNode request = objectMapper.readTree("""
            {
              "model": "gpt-5.5",
              "stream": false,
              "input": "hi"
            }
            """);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses")
                .header("User-Agent", "codex_cli_rs/0.139.0")
        );

        when(apiKeyService.authenticate(exchange))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5.5", "key-1")).thenReturn("gpt-5.5");
        when(accountPoolService.selectCodexOpenAiAccountForPool(
            eq("key-1"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-5.5")))
            .thenReturn(Mono.error(new UpstreamException(503, "OpenAI upstream error 503: unavailable", true)));
        when(accountPoolService.recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(503),
            eq("OpenAI upstream error 503: unavailable"),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("codex-main", 1, 503, false)));

        assertThatThrownBy(() -> proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            stubbedAccountSettings(AccountDtos.RoutingPolicySettings.defaults())
        ).responses(request, exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("UPSTREAM_ERROR"));

        verify(accountPoolService).selectCodexOpenAiAccountForPool(eq("key-1"), any(AccountPoolRequestContext.class));
        verify(accountPoolService, never()).selectCodexOpenAiAccountForPool(
            eq("key-1"),
            eq(List.of("codex-main")),
            any(AccountPoolRequestContext.class)
        );
        verify(accountPoolService, never()).selectOpenAiAccountForPool(
            anyString(),
            anyCollection(),
            any(AccountPoolRequestContext.class)
        );
        verify(openAiUpstreamClient).completeResponses(eq(codex), any(), eq("gpt-5.5"));
    }

    @Test
    void responsesEndpointReplaysStoredPreviousResponseForToolContinuation() throws Exception {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        AccountPoolService accountPoolService = mock(AccountPoolService.class);
        ModelMappingService modelMappingService = mock(ModelMappingService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
        OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        StatsService statsService = mock(StatsService.class);
        DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
        ProxyAccountEntity account = openAiAccount();
        JsonNode firstRequest = objectMapper.readTree("""
            {
              "model": "gpt-5.4",
              "input": "write a file"
            }
            """);
        ObjectNode secondRequest = (ObjectNode) objectMapper.readTree("""
            {
              "model": "gpt-5.4",
              "previous_response_id": "resp_placeholder",
              "input": [
                {
                  "type": "function_call_output",
                  "call_id": "call_write",
                  "output": "created"
                }
              ]
            }
            """);

        MockServerWebExchange firstExchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses")
        );
        MockServerWebExchange secondExchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses")
        );

        when(apiKeyService.authenticate(any()))
            .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5.4", "key-1")).thenReturn("gpt-5.4");
        when(accountPoolService.selectOpenAiAccountForPool(
            anyString(),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.completeResponses(eq(account), any(JsonNode.class), eq("gpt-5.4")))
            .thenReturn(
                Mono.just(new PlatformCompletionResult(
                    "",
                    List.of(new PlatformToolUse(
                        "call_write",
                        "exec_command",
                        objectMapper.readTree("{\"cmd\":\"printf hi > hello.txt\"}")
                    )),
                    new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0)
                )),
                Mono.just(new PlatformCompletionResult("done", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0)))
            );
        when(accountPoolService.recordSuccess(
            eq("openai-main"),
            eq(2L),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), any(BigDecimal.class), eq(1L), eq(1L))).thenReturn(Mono.empty());
        when(statsService.log(any())).thenReturn(Mono.empty());

        ProxyFacade facade = proxyFacade(
            apiKeyService,
            accountPoolService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService
        );

        ResponseEntity<?> firstResponse = facade.responses(firstRequest, firstExchange).block();
        assertThat(firstResponse).isNotNull();
        JsonNode firstBody = (JsonNode) firstResponse.getBody();
        String responseId = firstBody.path("id").asString("");
        secondRequest.put("previous_response_id", responseId);

        facade.responses(secondRequest, secondExchange).block();

        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openAiUpstreamClient, org.mockito.Mockito.times(2))
            .completeResponses(eq(account), requestCaptor.capture(), eq("gpt-5.4"));
        JsonNode replayed = requestCaptor.getAllValues().get(1);
        assertThat(replayed.has("previous_response_id")).isFalse();
        assertThat(replayed.path("store").asBoolean(true)).isFalse();
        JsonNode input = replayed.path("input");
        assertThat(input).hasSize(3);
        assertThat(input.path(0).path("content").path(0).path("text").asString("")).isEqualTo("write a file");
        assertThat(input.path(1).path("type").asString("")).isEqualTo("function_call");
        assertThat(input.path(1).path("call_id").asString("")).isEqualTo("call_write");
        assertThat(input.path(2).path("type").asString("")).isEqualTo("function_call_output");
        assertThat(input.path(2).path("call_id").asString("")).isEqualTo("call_write");
    }

    // ---- failures of the account's outbound route (egress) ----

    @Test
    void anEgressFailureBeforeAnythingIsSentFailsOverToAnotherAccountWithoutTouchingTheAccountsHealth() throws Exception {
        EgressScenario scenario = new EgressScenario(
            tech.starryapi.egress.EgressException.notSent(
                tech.starryapi.egress.EgressFailure.PROXY_CONNECT_FAILED,
                new java.net.ConnectException("refused 127.0.0.1:20001 user:secret")
            )
        );
        scenario.backupSucceeds();

        ResponseEntity<?> response = scenario.facade().chat(scenario.request, scenario.exchange).block();

        assertThat(response).isNotNull();
        verify(scenario.accountPoolService).selectOpenAiAccountForPool(
            eq("key-1"), eq(List.of("openai-main")), any(AccountPoolRequestContext.class)
        );
        verify(scenario.openAiUpstreamClient).complete(eq(scenario.backup), any(), eq("gpt-5"));
        // A broken route is not the account's fault: no failure count, no cooldown or circuit failure.
        verify(scenario.accountPoolService, never()).recordRequestFailure(
            anyString(), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(), any(AccountPoolRequestContext.class)
        );
        verify(scenario.accountPoolService, never()).recordCircuitFailure(any(AccountPoolRequestContext.class), any());
        verify(scenario.accountAutoSuspendService, never()).scheduleIfNeeded(any());
    }

    @Test
    void anEgressFailureWithNoOtherAccountReleasesTheReservationAsUnbilledAndHidesTransportDetails() throws Exception {
        EgressScenario scenario = new EgressScenario(
            tech.starryapi.egress.EgressException.notSent(
                tech.starryapi.egress.EgressFailure.PROXY_CONNECT_FAILED,
                new java.net.ConnectException("refused 127.0.0.1:20001 user:secret")
            )
        );
        scenario.noBackupAccount();

        assertThatThrownBy(() -> scenario.facade().chat(scenario.request, scenario.exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(error.getMessage()).isEqualTo(tech.starryapi.egress.EgressFailure.PROXY_CONNECT_FAILED.message());
            });
        verify(scenario.apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
        verify(scenario.apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
    }

    @Test
    void aDirectConnectFailureWithNoOtherAccountReleasesTheReservationAsUnbilled() throws Exception {
        // No proxy involved: the upstream refused the connection, so nothing was generated or charged.
        EgressScenario scenario = new EgressScenario(new java.net.ConnectException("Connection refused"));
        scenario.noBackupAccount();
        when(scenario.accountPoolService.recordRequestFailure(
            anyString(), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(),
            any(AccountPoolRequestContext.class)
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("openai-primary", 1, 502, false)));

        assertThatThrownBy(() -> scenario.facade().chat(scenario.request, scenario.exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(scenario.apiKeyService).releaseUsage(any(), anyString(), eq(true), anyString());
        verify(scenario.apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
    }

    @Test
    void anEgressFailureAfterTheRequestMayHaveBeenSentStaysOnItsAccountAndStaysBillingUncertain() throws Exception {
        EgressScenario scenario = new EgressScenario(
            tech.starryapi.egress.EgressException.maybeSent(
                tech.starryapi.egress.EgressFailure.PROXY_TRANSPORT_FAILED,
                new java.net.SocketException("Connection reset")
            )
        );

        assertThatThrownBy(() -> scenario.facade().chat(scenario.request, scenario.exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getMessage()).isEqualTo(tech.starryapi.egress.EgressFailure.PROXY_TRANSPORT_FAILED.message()));
        verify(scenario.accountPoolService, never()).selectOpenAiAccountForPool(
            eq("key-1"), eq(List.of("openai-main")), any(AccountPoolRequestContext.class)
        );
        verify(scenario.apiKeyService).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(scenario.accountPoolService, never()).recordRequestFailure(
            anyString(), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(), any(AccountPoolRequestContext.class)
        );
    }

    /** One OpenAI chat request whose first account fails with the given egress error. */
    private final class EgressScenario {
        final ApiKeyService apiKeyService = mock(ApiKeyService.class);
        final AccountPoolService accountPoolService = mock(AccountPoolService.class);
        final AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        final ModelMappingService modelMappingService = mock(ModelMappingService.class);
        final RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        final OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
        final StatsService statsService = mock(StatsService.class);
        final ProxyAccountEntity primary = openAiAccount("openai-main");
        final ProxyAccountEntity backup = openAiAccount("openai-backup");
        final JsonNode request;
        final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));

        EgressScenario(Throwable primaryError) throws Exception {
            request = objectMapper.readTree("{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            when(apiKeyService.authenticate(exchange))
                .thenReturn(Mono.just(new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO)));
            when(rateLimiterService.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
            when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
            when(accountPoolService.selectOpenAiAccountForPool(eq("key-1"), any(AccountPoolRequestContext.class)))
                .thenReturn(Mono.just(primary));
            when(openAiUpstreamClient.complete(eq(primary), any(), eq("gpt-5"))).thenReturn(Mono.error(primaryError));
            when(statsService.log(any())).thenReturn(Mono.empty());
        }

        void backupSucceeds() {
            when(accountPoolService.selectOpenAiAccountForPool(
                eq("key-1"), eq(List.of("openai-main")), any(AccountPoolRequestContext.class)
            )).thenReturn(Mono.just(backup));
            when(openAiUpstreamClient.complete(eq(backup), any(), eq("gpt-5")))
                .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
            when(accountPoolService.recordSuccess(eq("openai-backup"), eq(2L), any(AccountPoolRequestContext.class)))
                .thenReturn(Mono.empty());
            when(accountPoolService.recordCircuitSuccess(any(AccountPoolRequestContext.class), eq(backup))).thenReturn(73L);
            when(apiKeyService.accountUsage(any(ApiKeyPrincipal.class), anyString(), any(), eq(1L), eq(1L))).thenReturn(Mono.empty());
        }

        void noBackupAccount() {
            when(accountPoolService.selectOpenAiAccountForPool(
                eq("key-1"), eq(List.of("openai-main")), any(AccountPoolRequestContext.class)
            )).thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "none")));
        }

        ProxyFacade facade() {
            return proxyFacade(
                apiKeyService, accountPoolService, accountAutoSuspendService, new DefaultModelCatalogProvider(),
                modelMappingService, rateLimiterService, mock(KiroUpstreamClient.class), openAiUpstreamClient, statsService
            );
        }
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
        AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
        return proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            stubbedAccountSettings(),
            paymentSettingsService(BigDecimal.ONE)
        );
    }

    private ProxyFacade proxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService,
        PaymentSettingsService paymentSettingsService
    ) {
        return proxyFacade(
            apiKeyService,
            accountPoolService,
            mock(AccountAutoSuspendService.class),
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            stubbedAccountSettings(),
            paymentSettingsService
        );
    }

    private ProxyFacade proxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService
    ) {
        return proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            stubbedAccountSettings(),
            paymentSettingsService(BigDecimal.ONE)
        );
    }

    private ProxyFacade proxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService,
        AccountSettingsService accountSettingsService
    ) {
        return proxyFacade(
            apiKeyService,
            accountPoolService,
            mock(AccountAutoSuspendService.class),
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            accountSettingsService
        );
    }

    private ProxyFacade proxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService,
        AccountSettingsService accountSettingsService
    ) {
        return proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            accountSettingsService,
            paymentSettingsService(BigDecimal.ONE)
        );
    }

    private ProxyFacade proxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService,
        AccountSettingsService accountSettingsService,
        PaymentSettingsService paymentSettingsService
    ) {
        ensureRoutingPolicy(accountSettingsService);
        return proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            accountSettingsService,
            paymentSettingsService,
            mock(AnthropicUpstreamClient.class)
        );
    }

    private AccountSettingsService stubbedAccountSettings() {
        return stubbedAccountSettings(legacyRoutingPolicy());
    }

    private AccountSettingsService stubbedAccountSettings(AccountDtos.RoutingPolicySettings policy) {
        AccountSettingsService accountSettingsService = mock(AccountSettingsService.class);
        when(accountSettingsService.ensureGptFastModeAllowed()).thenReturn(Mono.empty());
        stubRoutingPolicy(accountSettingsService, policy);
        return accountSettingsService;
    }

    private void stubRoutingPolicy(
        AccountSettingsService accountSettingsService,
        AccountDtos.RoutingPolicySettings policy
    ) {
        when(accountSettingsService.routingPolicy()).thenReturn(Mono.just(policy));
    }

    private void ensureRoutingPolicy(AccountSettingsService accountSettingsService) {
        try {
            if (accountSettingsService.routingPolicy() != null) {
                return;
            }
        } catch (RuntimeException ignored) {
            // Unstubbed mocks can throw; fall through and install the legacy policy.
        }
        stubRoutingPolicy(accountSettingsService, legacyRoutingPolicy());
    }

    /** Existing facade tests keep mixed OpenAI/Codex selection and cross-pool fallback. */
    private AccountDtos.RoutingPolicySettings legacyRoutingPolicy() {
        return new AccountDtos.RoutingPolicySettings(false, false, true, true, 2, 3, 3, 2, 2);
    }

    private ProxyFacade proxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService,
        AccountSettingsService accountSettingsService,
        PaymentSettingsService paymentSettingsService,
        AnthropicUpstreamClient anthropicUpstreamClient
    ) {
        return proxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            kiroUpstreamClient,
            openAiUpstreamClient,
            statsService,
            accountSettingsService,
            paymentSettingsService,
            anthropicUpstreamClient,
            mock(DisabledAccountScheduledTestService.class)
        );
    }

    private ProxyFacade proxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService,
        AccountSettingsService accountSettingsService,
        PaymentSettingsService paymentSettingsService,
        AnthropicUpstreamClient anthropicUpstreamClient,
        DisabledAccountScheduledTestService disabledAccountScheduledTestService
    ) {
        ensureRoutingPolicy(accountSettingsService);
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenReturn(Mono.empty());
        return new ProxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            accountSettingsService,
            disabledAccountScheduledTestService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            kiroUpstreamClient,
            openAiUpstreamClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            new OpenAiResponseFactory(objectMapper),
            new OpenAiResponseStateStore(objectMapper),
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper),
            anthropicUpstreamClient,
            new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper),
            paymentSettingsService,
            modelPricingService(),
            statsService
        );
    }

    private PaymentSettingsService paymentSettingsService(BigDecimal apiBillingMultiplier) {
        PaymentSettingsService service = mock(PaymentSettingsService.class);
        when(service.config()).thenReturn(Mono.just(new PaymentConfig(
            false,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            30,
            3,
            List.of("alipay", "wxpay"),
            BigDecimal.ONE,
            apiBillingMultiplier,
            BigDecimal.ZERO,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "popup",
            false,
            new BigDecimal("0.10"),
            10,
            null,
            null
        )));
        return service;
    }

    private ModelPricingService modelPricingService() {
        ModelPricingService service = mock(ModelPricingService.class);
        when(service.billingQuote(anyString(), anyBoolean(), any())).thenAnswer(invocation -> {
            boolean fastMode = invocation.getArgument(1);
            BigDecimal multiplier = invocation.getArgument(2);
            BigDecimal rate = multiplier == null || multiplier.signum() <= 0
                ? PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER : multiplier;
            if (fastMode) rate = rate.multiply(BigDecimal.valueOf(2));
            return Mono.just(new tech.starryapi.model.BillingQuote(rate, rate, rate, rate, rate));
        });
        when(service.cost(any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
            PlatformUsage usage = invocation.getArgument(1);
            boolean fastMode = invocation.getArgument(2);
            BigDecimal multiplier = invocation.getArgument(3);
            BigDecimal units = usage.totalTokens() > 0
                ? BigDecimal.valueOf(usage.totalTokens())
                : usage.credits() == null ? BigDecimal.ZERO : usage.credits();
            if (fastMode) {
                units = units.multiply(BigDecimal.valueOf(2));
            }
            BigDecimal safeMultiplier = multiplier == null || multiplier.compareTo(BigDecimal.ZERO) <= 0
                ? PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER
                : multiplier;
            return Mono.just(units.multiply(safeMultiplier).setScale(6, RoundingMode.HALF_UP));
        });
        return service;
    }

    private BigDecimal credits(String value) {
        return new BigDecimal(value).setScale(6);
    }

    private JsonNode billingRequest(JsonNode request, boolean responses) {
        var expected = ((tools.jackson.databind.node.ObjectNode) request).deepCopy();
        // A request that names no limit is held to 4,096 tokens on the Chat entry and to the default of the gateway (16,000, no more
        // than the model writes: 64,000 for the gpt-5.4 these tests use) on the Responses entry.
        expected.put(responses ? "max_output_tokens" : "max_tokens", responses ? 16_000L : 4096L);
        return expected;
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

    private ProxyAccountEntity codexAccount(String accountId) {
        ProxyAccountEntity account = openAiAccount(accountId);
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);
        return account;
    }

    private ProxyAccountEntity deepSeekAccount() {
        return deepSeekAccount("deepseek-main");
    }

    private ProxyAccountEntity deepSeekAccount(String accountId) {
        ProxyAccountEntity account = openAiAccount(accountId);
        account.setProvider(AccountPlatforms.DEEPSEEK_PROVIDER);
        account.setBaseUrl(AccountPlatforms.DEFAULT_DEEPSEEK_BASE_URL);
        return account;
    }

    private ProxyAccountEntity anthropicAccount() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("anthropic-main");
        account.setProvider(AccountPlatforms.ANTHROPIC_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setAccessToken("sk-ant-test");
        account.setEnabled(true);
        return account;
    }

    private ProxyAccountEntity kiroAccount() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("kiro-main");
        account.setProvider("BuilderId");
        account.setAccessToken("kiro-token");
        account.setRegion("us-east-1");
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
