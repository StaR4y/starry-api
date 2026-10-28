package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.model.DefaultModelCatalogProvider;
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
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Feature;
import tech.starryapi.platform.xproto.ResponsesChatConverter;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.proxy.tier.TierEntryFacts;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the entries hand the planner of the request as the client sent it, for the relays that may serve it (design 3,
 * step 5b): the Messages entry reads it before it converts the body, the Chat entry reads its own, and a deployment whose
 * planner has no use for it reads nothing. Whatever is read holds nothing of the body.
 */
class ProxyFacadeEntryFactsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiResponseStateStore stateStore = new OpenAiResponseStateStore(objectMapper);
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AccountSettingsService accountSettings = mock(AccountSettingsService.class);
    private final ModelMappingService modelMappingService = mock(ModelMappingService.class);
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    @BeforeEach
    void stubTheCollaborators() {
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenReturn(Mono.just(AccountDtos.RoutingPolicySettings.defaults()));
    }

    @Test
    void aMessagesRequestIsReadBeforeItIsConvertedAndTheFactsCarryTheDigestOfThePartsTheSanitizerReads() {
        Capturing planner = new Capturing(true);
        ObjectNode body = messagesBody();
        body.putObject("thinking").put("type", "enabled").put("budget_tokens", 8000);
        body.withArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search");

        planner.run(facade -> facade.messages(body, exchange("/v1/messages")));

        TierEntryFacts facts = planner.seen().entryFacts();
        assertThat(facts).isNotNull();
        assertThat(facts.detected().entry()).isEqualTo(Entry.MESSAGES);
        // The conversion would have turned the web search tool into a function; the original says what it was.
        assertThat(facts.detected().has(Feature.WEB_SEARCH)).isTrue();
        assertThat(facts.entryDigest().path("thinking").path("budget_tokens").intValue()).isEqualTo(8000);
        assertThat(planner.seen().entry()).isEqualTo(TierEntry.MESSAGES);
    }

    @Test
    void aChatRequestIsReadAsItIs() {
        Capturing planner = new Capturing(true);
        ObjectNode body = chatBody();
        body.put("n", 2);

        planner.run(facade -> facade.chat(body, exchange("/v1/chat/completions")));

        TierEntryFacts facts = planner.seen().entryFacts();
        assertThat(facts.detected().entry()).isEqualTo(Entry.CHAT);
        assertThat(facts.detected().has(Feature.N_OVER_ONE)).isTrue();
        assertThat(facts.entryDigest()).isNull();
    }

    @Test
    void theAssistantOfTheConsoleIsReadLikeAnyChatRequest() {
        Capturing planner = new Capturing(true);

        planner.run(facade -> facade.completeAnonymousChat(chatBody(), exchange("/v1/chat/completions")));

        assertThat(planner.seen().entryFacts().detected().entry()).isEqualTo(Entry.CHAT);
    }

    @Test
    void aChatRequestThatIsTurnedAwayAtTheDoorIsNotReadForRelays() {
        Capturing planner = new Capturing(true);
        when(apiKeyService.authenticate(any())).thenReturn(Mono.error(
            new tech.starryapi.common.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid API key")));

        List<Throwable> errors = new ArrayList<>();
        planner.facade().chat(chatBody(), exchange("/v1/chat/completions")).subscribe(ignored -> { }, errors::add);

        // Whoever it was, the work of reading the whole body is not done for a caller that has no key.
        assertThat(errors).singleElement().isInstanceOf(tech.starryapi.common.ApiException.class);
        assertThat(planner.seen()).isNull();
        assertThat(planner.asked).isEmpty();
    }

    @Test
    void aChatRequestIsReadOnceItsCallerIsKnown() {
        Capturing planner = new Capturing(true);

        planner.run(facade -> facade.chat(chatBody(), exchange("/v1/chat/completions")));

        assertThat(planner.asked).containsExactly(TierEntry.CHAT);
        assertThat(planner.seen().entryFacts()).isNotNull();
    }

    @Test
    void aResponsesRequestIsReadAsTheGatewayExpandedItAndCarriesTheConversionThatMakesTheChatBodyOfARelay() {
        Capturing planner = new Capturing(true);
        stateStore.remember(response("resp_known", "all done"), responsesBody("claude-sonnet-4.5", "first question"));
        ObjectNode body = responsesBody("claude-sonnet-4.5", "next question");
        body.put("previous_response_id", "resp_known");
        body.put("store", true);

        planner.runWithTheConverter(facade -> facade.responses(body, exchange("/v1/responses")));

        TierPlanRequest seen = planner.seen();
        assertThat(seen.entry()).isEqualTo(TierEntry.RESPONSES);
        TierEntryFacts facts = seen.entryFacts();
        assertThat(facts.detected().entry()).isEqualTo(Entry.RESPONSES);
        // The chain was expanded here: the history is in the body that is planned over, and the relay is not kept from it.
        assertThat(facts.detected().has(Feature.PREVIOUS_RESPONSE_ID)).isFalse();
        assertThat(seen.upstreamRequest().has("previous_response_id")).isFalse();
        assertThat(seen.upstreamRequest().path("input")).hasSize(3);
        // The plan is made over the body the native accounts are sent, with the output limit forced (the default of the gateway,
        // 16,000 tokens, since the request names none and this model writes 64,000) ...
        assertThat(seen.upstreamRequest().has("messages")).isFalse();
        assertThat(seen.upstreamRequest().path("max_output_tokens").asInt()).isEqualTo(16_000);
        // ... and the relay is given the conversion of that body.
        JsonNode chat = facts.chatConversion().apply(seen.upstreamRequest());
        assertThat(chat.path("max_tokens").asInt()).isEqualTo(16_000);
        assertThat(chat.path("messages")).extracting(message -> message.path("role").asString())
            .containsExactly("user", "assistant", "user");
        assertThat(chat.path("messages").get(1).path("content").asString()).isEqualTo("all done");
        assertThat(planner.asked).containsExactly(TierEntry.RESPONSES);
    }

    @Test
    void aResponsesChainThisNodeCouldNotExpandIsReadWithTheChainStillNamedInIt() {
        Capturing planner = new Capturing(true);
        ObjectNode body = responsesBody("claude-sonnet-4.5", "next question");
        body.put("previous_response_id", "resp_from_another_node");

        planner.runWithTheConverter(facade -> facade.responses(body, exchange("/v1/responses")));

        assertThat(planner.seen().entryFacts().detected().has(Feature.PREVIOUS_RESPONSE_ID)).isTrue();
        assertThat(planner.seen().upstreamRequest().path("previous_response_id").asString()).isEqualTo("resp_from_another_node");
    }

    @Test
    void aResponsesRequestIsNotReadWhenThePlannerHasNoUseForItOrTheFacadeCannotConvertIt() {
        Capturing noUse = new Capturing(false);
        noUse.runWithTheConverter(facade -> facade.responses(responsesBody("claude-sonnet-4.5", "hi"), exchange("/v1/responses")));
        assertThat(noUse.seen().entryFacts()).isNull();

        // A facade built the way the other tests build it has no converter, and the relays are not offered its requests.
        Capturing unconverted = new Capturing(true);
        unconverted.run(facade -> facade.responses(responsesBody("claude-sonnet-4.5", "hi"), exchange("/v1/responses")));
        assertThat(unconverted.seen().entryFacts()).isNull();
        assertThat(unconverted.asked).isEmpty();
    }

    @Test
    void aGeminiRequestIsNotReadSinceNoPairServesIt() {
        Capturing planner = new Capturing(true);
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", "hi");

        planner.run(facade -> facade.geminiGenerateContent("gemini-2.5-pro", body, exchange("/v1beta/models/gemini-2.5-pro:generateContent")));

        assertThat(planner.seen().entryFacts()).isNull();
    }

    @Test
    void nothingIsReadWhenThePlannerHasNoUseForIt() {
        Capturing messages = new Capturing(false);
        messages.run(facade -> facade.messages(messagesBody(), exchange("/v1/messages")));
        assertThat(messages.seen().entryFacts()).isNull();

        Capturing chat = new Capturing(false);
        chat.run(facade -> facade.chat(chatBody(), exchange("/v1/chat/completions")));
        assertThat(chat.seen().entryFacts()).isNull();
    }

    @Test
    void aMessagesBodyThatWasReadIsNotKeptAliveWhileTheRequestIsPending() throws InterruptedException {
        Capturing planner = new Capturing(true);
        ProxyFacade facade = planner.facade();
        List<Disposable> subscriptions = new ArrayList<>();

        WeakReference<JsonNode> reference = pendingMessages(facade, subscriptions);

        assertThat(planner.seen()).as("the planner was asked").isNotNull();
        assertThat(planner.seen().entryFacts()).isNotNull();
        assertThat(collected(reference)).as("raw Messages body collected").isTrue();
        subscriptions.forEach(Disposable::dispose);
    }

    private WeakReference<JsonNode> pendingMessages(ProxyFacade facade, List<Disposable> subscriptions) {
        ObjectNode body = messagesBody();
        body.set("system", objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("type", "text")
            .put("text", "x".repeat(4 * 1024 * 1024))));
        body.putObject("thinking").put("type", "adaptive");
        body.putObject("output_config").put("effort", "high");
        WeakReference<JsonNode> reference = new WeakReference<>(body);
        subscriptions.add(facade.messages(body, exchange("/v1/messages")).subscribe(ignored -> { }, ignored -> { }));
        return reference;
    }

    private static boolean collected(WeakReference<JsonNode> reference) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(100);
        }
        return reference.get() == null;
    }

    private ObjectNode responsesBody(String model, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("input", text);
        return body;
    }

    /** The response a client was given, as the store remembers it. */
    private ObjectNode response(String id, String text) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", id);
        ObjectNode message = response.putArray("output").addObject();
        message.put("type", "message").put("role", "assistant");
        message.putArray("content").addObject().put("type", "output_text").put("text", text);
        return response;
    }

    private ObjectNode messagesBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5").put("max_tokens", 16);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return body;
    }

    private ObjectNode chatBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return body;
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path));
    }

    /** A planner that keeps the request it was asked to plan and ends the request there, whether it wants the facts or not. */
    private final class Capturing implements TierRuntime.Planner {

        private final boolean wants;
        private final AtomicReference<TierPlanRequest> seen = new AtomicReference<>();
        /** The entries the facade asked about, in order. */
        private final List<TierEntry> asked = new java.util.concurrent.CopyOnWriteArrayList<>();

        Capturing(boolean wants) {
            this.wants = wants;
        }

        @Override
        public Mono<TierPlan> plan(TierPlanRequest request) {
            seen.set(request);
            return Mono.never();
        }

        @Override
        public boolean wantsEntryFacts(TierEntry entry) {
            asked.add(entry);
            return wants && entry != TierEntry.GEMINI;
        }

        TierPlanRequest seen() {
            return seen.get();
        }

        ProxyFacade facade() {
            return new ProxyFacade(
                apiKeyService,
                mock(AccountPoolService.class),
                mock(AccountAutoSuspendService.class),
                accountSettings,
                mock(DisabledAccountScheduledTestService.class),
                modelCatalog,
                modelMappingService,
                rateLimiter,
                new KiroPayloadFactory(objectMapper, modelCatalog),
                mock(KiroUpstreamClient.class),
                mock(OpenAiUpstreamClient.class),
                new OpenAiRequestAdapter(objectMapper, modelCatalog),
                new OpenAiResponseFactory(objectMapper),
                stateStore,
                new AnthropicRequestAdapter(objectMapper, modelCatalog),
                new AnthropicResponseFactory(objectMapper),
                mock(AnthropicUpstreamClient.class),
                new GeminiRequestAdapter(objectMapper),
                new GeminiResponseFactory(objectMapper),
                mock(PaymentSettingsService.class),
                mock(ModelPricingService.class),
                mock(StatsService.class),
                AccountAdmissionStore.permissive(),
                mock(OpenAiResponseOwnerStore.class),
                new TierRuntime(this)
            );
        }

        /** The facade as Spring builds it, with the dispatcher of the relays and the converter of the Responses entry. */
        ProxyFacade facadeWithTheConverter() {
            return new ProxyFacade(
                apiKeyService,
                mock(AccountPoolService.class),
                mock(AccountAutoSuspendService.class),
                accountSettings,
                mock(DisabledAccountScheduledTestService.class),
                modelCatalog,
                modelMappingService,
                rateLimiter,
                new KiroPayloadFactory(objectMapper, modelCatalog),
                mock(KiroUpstreamClient.class),
                mock(OpenAiUpstreamClient.class),
                new OpenAiRequestAdapter(objectMapper, modelCatalog),
                new OpenAiResponseFactory(objectMapper),
                stateStore,
                new AnthropicRequestAdapter(objectMapper, modelCatalog),
                new AnthropicResponseFactory(objectMapper),
                mock(AnthropicUpstreamClient.class),
                new GeminiRequestAdapter(objectMapper),
                new GeminiResponseFactory(objectMapper),
                mock(PaymentSettingsService.class),
                mock(ModelPricingService.class),
                mock(StatsService.class),
                AccountAdmissionStore.permissive(),
                mock(OpenAiResponseOwnerStore.class),
                new TierRuntime(this),
                mock(XpDispatcher.class),
                new ResponsesChatConverter(objectMapper)
            );
        }

        void runWithTheConverter(java.util.function.Function<ProxyFacade, Mono<?>> call) {
            run(facadeWithTheConverter(), call);
        }

        /** Subscribes to the request and waits until the planner has seen it; the planner never answers. */
        void run(java.util.function.Function<ProxyFacade, Mono<?>> call) {
            run(facade(), call);
        }

        private void run(ProxyFacade facade, java.util.function.Function<ProxyFacade, Mono<?>> call) {
            Disposable subscription = call.apply(facade).subscribe(ignored -> { }, ignored -> { });
            try {
                for (int attempt = 0; attempt < 100 && seen.get() == null; attempt++) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                subscription.dispose();
            }
            assertThat(seen.get()).as("the planner was asked").isNotNull();
        }
    }
}
