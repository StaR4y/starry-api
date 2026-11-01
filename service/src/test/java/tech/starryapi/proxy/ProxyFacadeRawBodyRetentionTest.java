package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Messages and Gemini bodies are converted into a Chat body before the reactive chain is built. A body with large
 * base64 images must be collectable as soon as that is done, not held until the upstream call (minutes, at worst)
 * finishes. The converters here are the real ones; Mockito mocks would keep the last call's arguments alive themselves.
 */
class ProxyFacadeRawBodyRetentionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ServerWebExchange exchange = mock(ServerWebExchange.class);

    @Test
    void aMessagesBodyIsNotKeptAliveWhileTheRequestIsPending() throws InterruptedException {
        ProxyFacade facade = facade();
        List<Disposable> subscriptions = new ArrayList<>();

        WeakReference<JsonNode> body = pendingMessages(facade, subscriptions);

        assertThat(collected(body)).as("raw Messages body collected").isTrue();
        subscriptions.forEach(Disposable::dispose);
    }

    @Test
    void aGeminiBodyIsNotKeptAliveWhileTheRequestIsPending() throws InterruptedException {
        ProxyFacade facade = facade();
        List<Disposable> subscriptions = new ArrayList<>();

        WeakReference<JsonNode> body = pendingGemini(facade, subscriptions);

        assertThat(collected(body)).as("raw Gemini body collected").isTrue();
        subscriptions.forEach(Disposable::dispose);
    }

    private WeakReference<JsonNode> pendingMessages(ProxyFacade facade, List<Disposable> subscriptions) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.put("max_tokens", 16);
        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", "x".repeat(4 * 1024 * 1024));
        WeakReference<JsonNode> reference = new WeakReference<>(body);
        subscriptions.add(facade.messages(body, exchange).subscribe(ignored -> { }, ignored -> { }));
        return reference;
    }

    private WeakReference<JsonNode> pendingGemini(ProxyFacade facade, List<Disposable> subscriptions) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", "x".repeat(4 * 1024 * 1024));
        WeakReference<JsonNode> reference = new WeakReference<>(body);
        subscriptions.add(facade.geminiGenerateContent("gemini-2.5-pro", body, exchange)
            .subscribe(ignored -> { }, ignored -> { }));
        return reference;
    }

    private static boolean collected(WeakReference<JsonNode> reference) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(100);
        }
        return reference.get() == null;
    }

    private ProxyFacade facade() {
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        // Authentication never answers, so the request stays pending with its chain assembled.
        when(apiKeyService.authenticate(any())).thenReturn(Mono.never());
        ModelCatalogProvider modelCatalog = mock(ModelCatalogProvider.class);
        return new ProxyFacade(
            apiKeyService,
            mock(AccountPoolService.class),
            mock(AccountAutoSuspendService.class),
            mock(AccountSettingsService.class),
            mock(DisabledAccountScheduledTestService.class),
            modelCatalog,
            mock(ModelMappingService.class),
            mock(RateLimiterService.class),
            mock(KiroPayloadFactory.class),
            mock(KiroUpstreamClient.class),
            mock(OpenAiUpstreamClient.class),
            mock(OpenAiRequestAdapter.class),
            mock(OpenAiResponseFactory.class),
            mock(OpenAiResponseStateStore.class),
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            mock(AnthropicResponseFactory.class),
            mock(AnthropicUpstreamClient.class),
            new GeminiRequestAdapter(objectMapper),
            mock(GeminiResponseFactory.class),
            mock(PaymentSettingsService.class),
            mock(ModelPricingService.class),
            mock(StatsService.class)
        );
    }
}
