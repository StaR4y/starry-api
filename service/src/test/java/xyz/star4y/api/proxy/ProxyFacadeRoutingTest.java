package xyz.star4y.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.account.AccountAutoSuspendService;
import xyz.star4y.api.account.AccountPoolService;
import xyz.star4y.api.account.AccountPlatforms;
import xyz.star4y.api.account.AccountSettingsService;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.apikey.ApiKeyPrincipal;
import xyz.star4y.api.apikey.ApiKeyService;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.model.DefaultModelCatalogProvider;
import xyz.star4y.api.model.ModelMappingService;
import xyz.star4y.api.model.ModelPricingService;
import xyz.star4y.api.platform.anthropic.AnthropicRequestAdapter;
import xyz.star4y.api.platform.anthropic.AnthropicResponseFactory;
import xyz.star4y.api.platform.anthropic.AnthropicUpstreamClient;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.core.PlatformToolUse;
import xyz.star4y.api.platform.core.PlatformUsage;
import xyz.star4y.api.platform.gemini.GeminiRequestAdapter;
import xyz.star4y.api.platform.gemini.GeminiResponseFactory;
import xyz.star4y.api.platform.kiro.KiroPayloadFactory;
import xyz.star4y.api.platform.kiro.KiroUpstreamClient;
import xyz.star4y.api.platform.openai.OpenAiRequestAdapter;
import xyz.star4y.api.platform.openai.OpenAiResponseFactory;
import xyz.star4y.api.platform.openai.OpenAiResponseStateStore;
import xyz.star4y.api.platform.openai.OpenAiUpstreamClient;
import xyz.star4y.api.platform.upstream.UpstreamException;
import xyz.star4y.api.payment.PaymentSettingsService;
import xyz.star4y.api.payment.PaymentSettingsService.PaymentConfig;
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
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(request), eq("openai-compatible/deepseek-chat")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-main", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        verify(openAiUpstreamClient).complete(eq(account), eq(request), eq("openai-compatible/deepseek-chat"));
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
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
        when(accountPoolService.selectAnthropicAccount("key-1")).thenReturn(Mono.just(account));
        when(anthropicUpstreamClient.complete(eq(account), eq(request), eq("anthropic/claude-sonnet-4.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("anthropic-main", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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

        verify(accountPoolService).selectAnthropicAccount("key-1");
        verify(anthropicUpstreamClient).complete(eq(account), eq(request), eq("anthropic/claude-sonnet-4.5"));
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
        when(accountPoolService.selectAnthropicAccount("key-1"))
            .thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available Anthropic upstream account")));
        when(accountPoolService.selectKiroAccount("key-1")).thenReturn(Mono.just(account));
        when(kiroUpstreamClient.complete(eq(account), any(ObjectNode.class), eq("claude-sonnet-4.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("kiro-main", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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

        verify(accountPoolService).selectAnthropicAccount("key-1");
        verify(accountPoolService).selectKiroAccount("key-1");
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
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(request), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(2, 3, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-main", 5L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("5")), eq(2L), eq(3L))).thenReturn(Mono.empty());
        when(apiKeyService.consumeBalance(eq(principal), eq(credits("5")))).thenReturn(Mono.empty());
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
        verify(apiKeyService).consumeBalance(principal, credits("5"));
    }

    @Test
    void defaultApiBillingMultiplierMakesOneHundredBalanceCoverSixMillionTokens() throws Exception {
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
        BigDecimal expectedCredits = new BigDecimal("100.000000");

        when(apiKeyService.authenticate(exchange)).thenReturn(Mono.just(principal));
        when(apiKeyService.ensureBalanceAvailable(principal)).thenReturn(Mono.empty());
        when(rateLimiterService.check("key-1"))
            .thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply("gpt-5", "key-1")).thenReturn("gpt-5");
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), eq(request), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(3_000_000, 3_000_000, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-main", 6_000_000L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(expectedCredits), eq(3_000_000L), eq(3_000_000L))).thenReturn(Mono.empty());
        when(apiKeyService.consumeBalance(eq(principal), eq(expectedCredits))).thenReturn(Mono.empty());
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

        verify(apiKeyService).recordUsage("key-1", expectedCredits, 3_000_000L, 3_000_000L);
        verify(apiKeyService).consumeBalance(principal, expectedCredits);
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
        when(accountPoolService.selectKiroAccount("key-1")).thenReturn(Mono.just(account));
        when(kiroUpstreamClient.complete(eq(account), any(ObjectNode.class), eq("claude-sonnet-4.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(4, 6, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("kiro-main", 10L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("10")), eq(4L), eq(6L))).thenReturn(Mono.empty());
        when(apiKeyService.consumeBalance(eq(principal), eq(credits("10")))).thenReturn(Mono.empty());
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

        verify(apiKeyService).recordUsage("key-1", credits("10"), 4L, 6L);
        verify(apiKeyService).consumeBalance(principal, credits("10"));
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
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.complete(eq(account), any(JsonNode.class), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(3, 2, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-main", 5L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("10")), eq(3L), eq(2L))).thenReturn(Mono.empty());
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
        assertThat(forwardedRequest.getValue().path("service_tier").asText()).isEqualTo("priority");
        verify(apiKeyService).recordUsage("key-1", credits("10"), 3L, 2L);
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
        verify(accountPoolService, never()).selectOpenAiAccount(any(String.class));
        verify(openAiUpstreamClient, never()).complete(any(), any(), any());
        verify(kiroUpstreamClient, never()).complete(any(), any(), any());
    }

    @Test
    void chatRetriesNextOpenAiAccountWhenSelectedAccountReturnsQuotaError() throws Exception {
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
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(primary));
        when(openAiUpstreamClient.complete(eq(primary), eq(request), eq("gpt-5")))
            .thenReturn(Mono.error(new UpstreamException(429, "quota exhausted", true)));
        when(accountPoolService.recordRequestFailure(
            "openai-main",
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            429,
            "quota exhausted"
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("openai-main", 2, 429, true)));
        when(accountPoolService.selectOpenAiAccount(eq("key-1"), eq(List.of("openai-main"))))
            .thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.complete(eq(backup), eq(request), eq("gpt-5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-backup", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        verify(accountPoolService).selectOpenAiAccount("key-1");
        verify(accountPoolService).selectOpenAiAccount(eq("key-1"), eq(List.of("openai-main")));
        verify(accountPoolService).recordRequestFailure(
            "openai-main",
            AccountPoolService.UpstreamErrorType.RECOVERABLE,
            429,
            "quota exhausted"
        );
        verify(accountAutoSuspendService).scheduleIfNeeded(new AccountPoolService.RequestFailureRecord("openai-main", 2, 429, true));
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
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        when(accountPoolService.selectCodexOpenAiAccount("key-1")).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("codex-main", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        verify(accountPoolService).selectCodexOpenAiAccount("key-1");
        verify(accountPoolService, never()).selectOpenAiAccount(any(String.class));
        verify(openAiUpstreamClient).completeResponses(eq(codex), any(), eq("gpt-5.5"));
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
        when(accountPoolService.selectCodexOpenAiAccount("key-1")).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("codex-main", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        verify(accountPoolService).selectCodexOpenAiAccount("key-1");
        verify(accountPoolService, never()).selectOpenAiAccount(any(String.class));
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
        when(accountPoolService.selectCodexOpenAiAccount("key-1")).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-5.5")))
            .thenReturn(Mono.error(new UpstreamException(503, "OpenAI upstream error 503: Service temporarily unavailable", true)));
        when(accountPoolService.recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(503),
            eq("OpenAI upstream error 503: Service temporarily unavailable")
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("codex-main", 1, 503, false)));
        when(accountPoolService.selectCodexOpenAiAccount(eq("key-1"), eq(List.of("codex-main"))))
            .thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available Codex upstream account")));
        when(accountPoolService.selectOpenAiAccount(eq("key-1"), eq(List.of("codex-main"))))
            .thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.completeResponses(eq(backup), any(), eq("gpt-5.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-backup", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        verify(accountPoolService).selectCodexOpenAiAccount("key-1");
        verify(accountPoolService).selectCodexOpenAiAccount(eq("key-1"), eq(List.of("codex-main")));
        verify(accountPoolService).selectOpenAiAccount(eq("key-1"), eq(List.of("codex-main")));
        verify(openAiUpstreamClient).completeResponses(eq(codex), any(), eq("gpt-5.5"));
        verify(openAiUpstreamClient).completeResponses(eq(backup), any(), eq("gpt-5.5"));
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
        when(accountPoolService.selectCodexOpenAiAccount("key-1")).thenReturn(Mono.just(codex));
        when(openAiUpstreamClient.completeResponses(eq(codex), any(), eq("gpt-4o-mini")))
            .thenReturn(Mono.error(new UpstreamException(503, "OpenAI upstream error 503: Service temporarily unavailable", true)));
        when(accountPoolService.recordRequestFailure(
            eq("codex-main"),
            eq(AccountPoolService.UpstreamErrorType.RECOVERABLE),
            eq(503),
            eq("OpenAI upstream error 503: Service temporarily unavailable")
        )).thenReturn(Mono.just(new AccountPoolService.RequestFailureRecord("codex-main", 1, 503, false)));
        when(accountPoolService.selectCodexOpenAiAccount(eq("key-1"), eq(List.of("codex-main"))))
            .thenReturn(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available Codex upstream account")));
        when(accountPoolService.selectOpenAiAccount(eq("key-1"), eq(List.of("codex-main"))))
            .thenReturn(Mono.just(backup));
        when(openAiUpstreamClient.completeResponses(eq(backup), any(), eq("gpt-4o-mini")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0))));
        when(accountPoolService.recordSuccess("openai-backup", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), eq(credits("2")), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        verify(accountPoolService).selectCodexOpenAiAccount("key-1");
        verify(accountPoolService).selectCodexOpenAiAccount(eq("key-1"), eq(List.of("codex-main")));
        verify(accountPoolService).selectOpenAiAccount(eq("key-1"), eq(List.of("codex-main")));
        verify(openAiUpstreamClient).completeResponses(eq(codex), any(), eq("gpt-4o-mini"));
        verify(openAiUpstreamClient).completeResponses(eq(backup), any(), eq("gpt-4o-mini"));
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
              "model": "gpt-5.4-mini",
              "input": "write a file"
            }
            """);
        ObjectNode secondRequest = (ObjectNode) objectMapper.readTree("""
            {
              "model": "gpt-5.4-mini",
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
        when(modelMappingService.apply("gpt-5.4-mini", "key-1")).thenReturn("gpt-5.4-mini");
        when(accountPoolService.selectOpenAiAccount("key-1")).thenReturn(Mono.just(account));
        when(openAiUpstreamClient.completeResponses(eq(account), any(JsonNode.class), eq("gpt-5.4-mini")))
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
        when(accountPoolService.recordSuccess("openai-main", 2L)).thenReturn(Mono.empty());
        when(apiKeyService.recordUsage(eq("key-1"), any(BigDecimal.class), eq(1L), eq(1L))).thenReturn(Mono.empty());
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
        String responseId = firstBody.path("id").asText();
        secondRequest.put("previous_response_id", responseId);

        facade.responses(secondRequest, secondExchange).block();

        ArgumentCaptor<JsonNode> requestCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(openAiUpstreamClient, org.mockito.Mockito.times(2))
            .completeResponses(eq(account), requestCaptor.capture(), eq("gpt-5.4-mini"));
        JsonNode replayed = requestCaptor.getAllValues().get(1);
        assertThat(replayed.has("previous_response_id")).isFalse();
        assertThat(replayed.path("store").asBoolean(true)).isFalse();
        JsonNode input = replayed.path("input");
        assertThat(input).hasSize(3);
        assertThat(input.path(0).path("content").path(0).path("text").asText()).isEqualTo("write a file");
        assertThat(input.path(1).path("type").asText()).isEqualTo("function_call");
        assertThat(input.path(1).path("call_id").asText()).isEqualTo("call_write");
        assertThat(input.path(2).path("type").asText()).isEqualTo("function_call_output");
        assertThat(input.path(2).path("call_id").asText()).isEqualTo("call_write");
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
        AccountSettingsService accountSettingsService = mock(AccountSettingsService.class);
        when(accountSettingsService.ensureGptFastModeAllowed()).thenReturn(Mono.empty());
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
        DefaultModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroUpstreamClient kiroUpstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        StatsService statsService,
        PaymentSettingsService paymentSettingsService
    ) {
        AccountSettingsService accountSettingsService = mock(AccountSettingsService.class);
        when(accountSettingsService.ensureGptFastModeAllowed()).thenReturn(Mono.empty());
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
            accountSettingsService,
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
        AccountSettingsService accountSettingsService = mock(AccountSettingsService.class);
        when(accountSettingsService.ensureGptFastModeAllowed()).thenReturn(Mono.empty());
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
        return new ProxyFacade(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            accountSettingsService,
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
            "popup"
        )));
        return service;
    }

    private ModelPricingService modelPricingService() {
        ModelPricingService service = mock(ModelPricingService.class);
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
