package tech.starryapi.proxy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.CodexOnlyModels;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.JsonNodes;
import tech.starryapi.common.Hashing;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.deepseek.DeepSeekModelCatalog;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.RelayErrorException;
import tech.starryapi.platform.xproto.RelayUsageNormalizer;
import tech.starryapi.platform.xproto.ResponsesChatConverter;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.proxy.tier.RequestBounds;
import tech.starryapi.proxy.tier.TierBilling;
import tech.starryapi.proxy.tier.TierDispatch;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.proxy.tier.TierEntryFacts;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierRetryPolicy;
import tech.starryapi.proxy.tier.TierRoute;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSelection;
import tech.starryapi.proxy.tier.TierSelectorParser;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.TierSessionKeys;
import tech.starryapi.proxy.tier.TierWalk;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ProxyFacade {

    static final int MAX_MODEL_ID_LENGTH = 256;
    /** How many accounts may refuse admission before a request stops looking: per dispatch untiered, per request tiered. */
    private static final int MAX_ADMISSION_REFUSALS = 64;
    /** What a client is told to wait when the tier it chose has no account to offer: about the age of the account cache. */
    private static final String TIER_UNAVAILABLE_RETRY_AFTER_SECONDS = "5";
    /** What a dispatch to a relay leaves of the lease of its reservation for what follows the answer (design 4.6). */
    private static final Duration RELAY_LEASE_MARGIN = Duration.ofSeconds(60);
    /** A relay is not asked when less than this is left of the time it may take: it could not answer in it. */
    private static final Duration RELAY_LEAST_TIME_LIMIT = Duration.ofSeconds(30);
    /** The reasons admission refuses an account that a session's own parallel requests can also cause. */
    private static final java.util.Set<String> SESSION_CONCURRENCY_REFUSALS =
        java.util.Set.of("CONCURRENCY_LIMIT", "INFLIGHT_TOKEN_LIMIT", "SESSION_LIMIT");
    private static final Logger log = LoggerFactory.getLogger(ProxyFacade.class);

    /**
     * What a Responses chain that cannot leave its owner is told when the owner cannot serve it: the 503
     * NO_AVAILABLE_ACCOUNT of an untiered request, with the same text. It is told apart from a pool that has nothing to
     * offer by its type, not by its text, because a request that named its pool is answered differently for the two
     * ({@link #servedByTiers}): it is not the pool's fault, and waiting a few seconds does not help.
     */
    static final class ResponseOwnerUnavailableException extends ApiException {
        ResponseOwnerUnavailableException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "The account that owns the OpenAI response is unavailable");
        }
    }

    enum ResponseMode {
        OPENAI_CHAT,
        OPENAI_RESPONSES,
        ANTHROPIC_MESSAGES,
        GEMINI_GENERATE_CONTENT
    }

    enum UpstreamRoute {
        KIRO,
        OPENAI,
        ANTHROPIC
    }

    private final ApiKeyService apiKeyService;
    private final AccountPoolService accountPoolService;
    private final AccountAutoSuspendService accountAutoSuspendService;
    private final AccountSettingsService accountSettingsService;
    private final DisabledAccountScheduledTestService disabledAccountScheduledTestService;
    private final ModelCatalogProvider modelCatalog;
    private final ModelMappingService modelMappingService;
    private final RateLimiterService rateLimiterService;
    private final KiroPayloadFactory payloadFactory;
    private final KiroUpstreamClient upstreamClient;
    private final OpenAiUpstreamClient openAiUpstreamClient;
    private final OpenAiRequestAdapter openAiRequestAdapter;
    private final OpenAiResponseFactory openAiResponseFactory;
    private final OpenAiResponseStateStore openAiResponseStateStore;
    private final AnthropicRequestAdapter anthropicRequestAdapter;
    private final AnthropicResponseFactory anthropicResponseFactory;
    private final AnthropicUpstreamClient anthropicUpstreamClient;
    private final GeminiRequestAdapter geminiRequestAdapter;
    private final GeminiResponseFactory geminiResponseFactory;
    private final PaymentSettingsService paymentSettingsService;
    private final ModelPricingService modelPricingService;
    private final StatsService statsService;
    private AccountAdmissionStore admissionStore;
    private OpenAiResponseOwnerStore openAiResponseOwnerStore;
    private TierRuntime tierRuntime;
    private XpDispatcher xpDispatcher;
    private ResponsesChatConverter responsesChatConverter;

    public ProxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        AccountSettingsService accountSettingsService,
        DisabledAccountScheduledTestService disabledAccountScheduledTestService,
        ModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroPayloadFactory payloadFactory,
        KiroUpstreamClient upstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        OpenAiRequestAdapter openAiRequestAdapter,
        OpenAiResponseFactory openAiResponseFactory,
        OpenAiResponseStateStore openAiResponseStateStore,
        AnthropicRequestAdapter anthropicRequestAdapter,
        AnthropicResponseFactory anthropicResponseFactory,
        AnthropicUpstreamClient anthropicUpstreamClient,
        GeminiRequestAdapter geminiRequestAdapter,
        GeminiResponseFactory geminiResponseFactory,
        PaymentSettingsService paymentSettingsService,
        ModelPricingService modelPricingService,
        StatsService statsService
    ) {
        this.apiKeyService = apiKeyService;
        this.accountPoolService = accountPoolService;
        this.accountAutoSuspendService = accountAutoSuspendService;
        this.accountSettingsService = accountSettingsService;
        this.disabledAccountScheduledTestService = disabledAccountScheduledTestService;
        this.modelCatalog = modelCatalog;
        this.modelMappingService = modelMappingService;
        this.rateLimiterService = rateLimiterService;
        this.payloadFactory = payloadFactory;
        this.upstreamClient = upstreamClient;
        this.openAiUpstreamClient = openAiUpstreamClient;
        this.openAiRequestAdapter = openAiRequestAdapter;
        this.openAiResponseFactory = openAiResponseFactory;
        this.openAiResponseStateStore = openAiResponseStateStore;
        this.anthropicRequestAdapter = anthropicRequestAdapter;
        this.anthropicResponseFactory = anthropicResponseFactory;
        this.anthropicUpstreamClient = anthropicUpstreamClient;
        this.geminiRequestAdapter = geminiRequestAdapter;
        this.geminiResponseFactory = geminiResponseFactory;
        this.paymentSettingsService = paymentSettingsService;
        this.modelPricingService = modelPricingService;
        this.statsService = statsService;
        this.admissionStore = AccountAdmissionStore.permissive();
        this.openAiResponseOwnerStore = null;
        this.tierRuntime = TierRuntime.disabled();
    }

    public ProxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        AccountSettingsService accountSettingsService,
        DisabledAccountScheduledTestService disabledAccountScheduledTestService,
        ModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroPayloadFactory payloadFactory,
        KiroUpstreamClient upstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        OpenAiRequestAdapter openAiRequestAdapter,
        OpenAiResponseFactory openAiResponseFactory,
        OpenAiResponseStateStore openAiResponseStateStore,
        AnthropicRequestAdapter anthropicRequestAdapter,
        AnthropicResponseFactory anthropicResponseFactory,
        AnthropicUpstreamClient anthropicUpstreamClient,
        GeminiRequestAdapter geminiRequestAdapter,
        GeminiResponseFactory geminiResponseFactory,
        PaymentSettingsService paymentSettingsService,
        ModelPricingService modelPricingService,
        StatsService statsService,
        AccountAdmissionStore admissionStore,
        OpenAiResponseOwnerStore openAiResponseOwnerStore
    ) {
        this(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            accountSettingsService,
            disabledAccountScheduledTestService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            payloadFactory,
            upstreamClient,
            openAiUpstreamClient,
            openAiRequestAdapter,
            openAiResponseFactory,
            openAiResponseStateStore,
            anthropicRequestAdapter,
            anthropicResponseFactory,
            anthropicUpstreamClient,
            geminiRequestAdapter,
            geminiResponseFactory,
            paymentSettingsService,
            modelPricingService,
            statsService
        );
        this.admissionStore = admissionStore;
        this.openAiResponseOwnerStore = openAiResponseOwnerStore;
    }

    public ProxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        AccountSettingsService accountSettingsService,
        DisabledAccountScheduledTestService disabledAccountScheduledTestService,
        ModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroPayloadFactory payloadFactory,
        KiroUpstreamClient upstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        OpenAiRequestAdapter openAiRequestAdapter,
        OpenAiResponseFactory openAiResponseFactory,
        OpenAiResponseStateStore openAiResponseStateStore,
        AnthropicRequestAdapter anthropicRequestAdapter,
        AnthropicResponseFactory anthropicResponseFactory,
        AnthropicUpstreamClient anthropicUpstreamClient,
        GeminiRequestAdapter geminiRequestAdapter,
        GeminiResponseFactory geminiResponseFactory,
        PaymentSettingsService paymentSettingsService,
        ModelPricingService modelPricingService,
        StatsService statsService,
        AccountAdmissionStore admissionStore,
        OpenAiResponseOwnerStore openAiResponseOwnerStore,
        TierRuntime tierRuntime
    ) {
        this(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            accountSettingsService,
            disabledAccountScheduledTestService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            payloadFactory,
            upstreamClient,
            openAiUpstreamClient,
            openAiRequestAdapter,
            openAiResponseFactory,
            openAiResponseStateStore,
            anthropicRequestAdapter,
            anthropicResponseFactory,
            anthropicUpstreamClient,
            geminiRequestAdapter,
            geminiResponseFactory,
            paymentSettingsService,
            modelPricingService,
            statsService,
            admissionStore,
            openAiResponseOwnerStore
        );
        this.tierRuntime = tierRuntime;
    }

    ProxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        AccountSettingsService accountSettingsService,
        DisabledAccountScheduledTestService disabledAccountScheduledTestService,
        ModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroPayloadFactory payloadFactory,
        KiroUpstreamClient upstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        OpenAiRequestAdapter openAiRequestAdapter,
        OpenAiResponseFactory openAiResponseFactory,
        OpenAiResponseStateStore openAiResponseStateStore,
        AnthropicRequestAdapter anthropicRequestAdapter,
        AnthropicResponseFactory anthropicResponseFactory,
        AnthropicUpstreamClient anthropicUpstreamClient,
        GeminiRequestAdapter geminiRequestAdapter,
        GeminiResponseFactory geminiResponseFactory,
        PaymentSettingsService paymentSettingsService,
        ModelPricingService modelPricingService,
        StatsService statsService,
        AccountAdmissionStore admissionStore,
        OpenAiResponseOwnerStore openAiResponseOwnerStore,
        TierRuntime tierRuntime,
        XpDispatcher xpDispatcher
    ) {
        this(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            accountSettingsService,
            disabledAccountScheduledTestService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            payloadFactory,
            upstreamClient,
            openAiUpstreamClient,
            openAiRequestAdapter,
            openAiResponseFactory,
            openAiResponseStateStore,
            anthropicRequestAdapter,
            anthropicResponseFactory,
            anthropicUpstreamClient,
            geminiRequestAdapter,
            geminiResponseFactory,
            paymentSettingsService,
            modelPricingService,
            statsService,
            admissionStore,
            openAiResponseOwnerStore,
            tierRuntime
        );
        this.xpDispatcher = xpDispatcher;
    }

    // Spring builds the facade with this one; a second constructor marked for injection fails startup. The dispatcher
    // of the relays is only used by a tiered request whose plan holds a relay, and the converter only by a Responses
    // request that a relay may serve; a facade built without them has none, and no relay serves its requests.
    @Autowired
    ProxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        AccountSettingsService accountSettingsService,
        DisabledAccountScheduledTestService disabledAccountScheduledTestService,
        ModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroPayloadFactory payloadFactory,
        KiroUpstreamClient upstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        OpenAiRequestAdapter openAiRequestAdapter,
        OpenAiResponseFactory openAiResponseFactory,
        OpenAiResponseStateStore openAiResponseStateStore,
        AnthropicRequestAdapter anthropicRequestAdapter,
        AnthropicResponseFactory anthropicResponseFactory,
        AnthropicUpstreamClient anthropicUpstreamClient,
        GeminiRequestAdapter geminiRequestAdapter,
        GeminiResponseFactory geminiResponseFactory,
        PaymentSettingsService paymentSettingsService,
        ModelPricingService modelPricingService,
        StatsService statsService,
        AccountAdmissionStore admissionStore,
        OpenAiResponseOwnerStore openAiResponseOwnerStore,
        TierRuntime tierRuntime,
        XpDispatcher xpDispatcher,
        ResponsesChatConverter responsesChatConverter
    ) {
        this(
            apiKeyService,
            accountPoolService,
            accountAutoSuspendService,
            accountSettingsService,
            disabledAccountScheduledTestService,
            modelCatalog,
            modelMappingService,
            rateLimiterService,
            payloadFactory,
            upstreamClient,
            openAiUpstreamClient,
            openAiRequestAdapter,
            openAiResponseFactory,
            openAiResponseStateStore,
            anthropicRequestAdapter,
            anthropicResponseFactory,
            anthropicUpstreamClient,
            geminiRequestAdapter,
            geminiResponseFactory,
            paymentSettingsService,
            modelPricingService,
            statsService,
            admissionStore,
            openAiResponseOwnerStore,
            tierRuntime,
            xpDispatcher
        );
        this.responsesChatConverter = responsesChatConverter;
    }

    TierRuntime tierRuntime() {
        return tierRuntime;
    }

    public Mono<ResponseEntity<?>> chat(JsonNode request, ServerWebExchange exchange) {
        return authenticateAndLimit(exchange)
            .zipWith(routingPolicy())
            .flatMap(tuple -> invokeChat(
                request,
                exchange,
                tuple.getT1(),
                "/v1/chat/completions",
                ResponseMode.OPENAI_CHAT,
                tuple.getT2(),
                // The chain holds this body anyway, so what the relays need of it is read once the caller is known and not
                // for a request that is turned away at the door.
                entryFacts(TierEntry.CHAT, request)
            ));
    }

    public Mono<String> completeAnonymousChat(JsonNode request, ServerWebExchange exchange) {
        if (request == null || !request.isObject()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "Request body is required"));
        }
        ObjectNode copy = ((ObjectNode) request).deepCopy();
        copy.put("stream", false);
        ApiKeyPrincipal principal = new ApiKeyPrincipal(
            "anonymous",
            "whale-assistant",
            "ADMIN",
            "whale-assistant",
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "*"
        );
        TierEntryFacts entryFacts = entryFacts(TierEntry.CHAT, copy);
        return routingPolicy()
            .flatMap(policy -> invokeChat(
                copy,
                exchange,
                principal,
                "/v1/chat/completions",
                ResponseMode.OPENAI_CHAT,
                policy,
                entryFacts
            ))
            .map(ProxyFacade::assistantText);
    }

    static String assistantText(ResponseEntity<?> response) {
        Object body = response == null ? null : response.getBody();
        if (body instanceof JsonNode node) {
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            if (content.isString() && !content.asString("").isBlank()) {
                return content.asString("");
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "ASSISTANT_EMPTY_REPLY", "助手没有返回内容");
    }

    public Mono<ResponseEntity<?>> responses(JsonNode request, ServerWebExchange exchange) {
        return authenticateAndLimit(exchange)
            .zipWith(routingPolicy())
            .flatMap(tuple -> invokeResponses(request, exchange, tuple.getT1(), tuple.getT2()));
    }

    public Mono<ResponseEntity<?>> messages(JsonNode request, ServerWebExchange exchange) {
        // The conversion drops what a relay has to be judged by; it is read from the original, which is then let go.
        TierEntryFacts entryFacts = entryFacts(TierEntry.MESSAGES, request);
        ObjectNode chat = anthropicRequestAdapter.messagesToChat(request);
        return authenticateAndLimit(exchange)
            .zipWith(routingPolicy())
            .flatMap(tuple -> invokeChat(
                chat,
                exchange,
                tuple.getT1(),
                "/v1/messages",
                ResponseMode.ANTHROPIC_MESSAGES,
                tuple.getT2(),
                entryFacts
            ));
    }

    public Mono<ResponseEntity<?>> geminiGenerateContent(String model, JsonNode request, ServerWebExchange exchange) {
        ObjectNode chat = geminiRequestAdapter.generateContentToChat(model, request);
        String path = geminiPath(model);
        return authenticateAndLimit(exchange)
            .zipWith(routingPolicy())
            .flatMap(tuple -> invokeChat(
                chat,
                exchange,
                tuple.getT1(),
                path,
                ResponseMode.GEMINI_GENERATE_CONTENT,
                tuple.getT2(),
                null
            ));
    }

    /**
     * What the relays that may serve a request need to know of the body as the client sent it; null when the planner has
     * no use for it (no relay could be given a request of the entry: none is registered in a tier that is switched on, or
     * this release offers no pair for the entry, or no gate of the entry is open to anyone), which is every request of a
     * deployment without relays. Messages takes it before the body is converted and the reactive chain built (the chain
     * must not keep a body with large images alive), Chat once the caller is authenticated, since its chain holds the body
     * all along, and Responses once the chain it continues is expanded ({@link #responsesEntryFacts}). A body that cannot be
     * read is no reason to fail the request: it goes on without relays.
     */
    private TierEntryFacts entryFacts(TierEntry entry, JsonNode body) {
        if (body == null || !body.isObject() || !tierRuntime.planner().wantsEntryFacts(entry)) {
            return null;
        }
        try {
            return TierEntryFacts.of(entry, body);
        } catch (RuntimeException failure) {
            log.warn("The {} request could not be read for relays and goes on without them: {}", entry, failure.toString());
            return null;
        }
    }

    /**
     * What the relays that may serve a Responses request need to know of it: the features, read from the request as the
     * gateway expanded it (a {@code previous_response_id} still in it names a chain this node holds no history of), and the
     * conversion that makes the body the plan is sized over, which is the Responses one the native accounts are sent, into
     * the Chat body a relay is given (design 7.6). The conversion is only made when a relay is looked at. Null when the
     * planner has no use for the facts, which is every request of a deployment without a relay open to the entry, and for
     * a facade that was built without the converter.
     */
    private TierEntryFacts responsesEntryFacts(JsonNode expandedRequest) {
        if (responsesChatConverter == null) {
            return null;
        }
        TierEntryFacts facts = entryFacts(TierEntry.RESPONSES, expandedRequest);
        return facts == null ? null : facts.withChatConversion(responsesChatConverter::toChat);
    }

    private static final String GEMINI_PATH_PREFIX = "/v1beta/models/";
    private static final String GEMINI_PATH_SUFFIX = ":generateContent";

    private static String geminiPath(String model) {
        return GEMINI_PATH_PREFIX + model + GEMINI_PATH_SUFFIX;
    }

    /**
     * The Gemini endpoint of a request whose model had a tier suffix, without it: the path carries the model as the client
     * wrote it, and everything after the entry (the endpoint scope of the admission, the failure evidence, the request log)
     * sees the plain id only, like the model, the price and the reservation do. A path that is not one {@link #geminiPath}
     * built is returned as it is.
     */
    private static String withoutTierSuffix(String path) {
        if (!path.startsWith(GEMINI_PATH_PREFIX) || !path.endsWith(GEMINI_PATH_SUFFIX)
            || path.length() < GEMINI_PATH_PREFIX.length() + GEMINI_PATH_SUFFIX.length()) {
            return path;
        }
        String model = path.substring(GEMINI_PATH_PREFIX.length(), path.length() - GEMINI_PATH_SUFFIX.length());
        int at = model.lastIndexOf('@');
        return at < 0 ? path : geminiPath(model.substring(0, at));
    }

    private Mono<AccountDtos.RoutingPolicySettings> routingPolicy() {
        Mono<AccountDtos.RoutingPolicySettings> loaded = accountSettingsService.routingPolicy();
        if (loaded == null) {
            return Mono.just(AccountDtos.RoutingPolicySettings.defaults());
        }
        return loaded.defaultIfEmpty(AccountDtos.RoutingPolicySettings.defaults());
    }

    // entryFacts is what the relays of the tiers need to know of the body as the client sent it (TierEntryFacts), or null
    // when none is needed. Messages and Gemini convert the body before this point, and capturing it in the reactive chain
    // would keep a body with large base64 images alive for the whole upstream call, so the entry derives what is needed from
    // the original before the chain is built, and only that is captured. The Gemini endpoint has no pair a relay could
    // serve yet and takes none.
    private Mono<ResponseEntity<?>> invokeChat(
        JsonNode clientRequest,
        ServerWebExchange exchange,
        ApiKeyPrincipal principal,
        String entryPath,
        ResponseMode responseMode,
        AccountDtos.RoutingPolicySettings routingPolicy,
        TierEntryFacts entryFacts
    ) {
        validateChatRequest(clientRequest);
        Instant start = Instant.now();
        String requestId = UUID.randomUUID().toString();
        ClientTierChoice tierChoice = clientTierChoice(clientRequest, exchange, principal, responseMode, routingPolicy);
        // Only the Gemini path has the model in it. A request that had no suffix taken off keeps the path it came with.
        String path = tierChoice.suffixTaken() && responseMode == ResponseMode.GEMINI_GENERATE_CONTENT
            ? withoutTierSuffix(entryPath)
            : entryPath;
        JsonNode request = tierChoice.request();
        String requestedModel = request.path("model").asString(modelCatalog.defaultTargetModelId());
        String mappedModel = tierChoice.mappedModel();
        ModelProviderAccessPolicy.verify(modelCatalog, principal, requestedModel, mappedModel);
        RouteFacts routeFacts = routeFacts(request, requestedModel, mappedModel, responseMode, exchange, routingPolicy);
        boolean requestedFastMode = routeFacts.requestedFastMode();
        String upstreamModel = routeFacts.upstreamModel();
        String sessionHint = sessionHint(exchange, request, principal.keyId(), responseMode, null);
        boolean stream = responseMode != ResponseMode.GEMINI_GENERATE_CONTENT && request.path("stream").asBoolean(false);
        UpstreamRoute route = routeFacts.route();
        boolean preferDeepSeekAccounts = routeFacts.preferDeepSeekAccounts();
        boolean preferCodexAccounts = routeFacts.preferCodexAccounts();
        boolean fastMode = requestedFastMode && route == UpstreamRoute.OPENAI && !preferDeepSeekAccounts;
        JsonNode upstreamRequest = boundedBillingRequest(preferDeepSeekAccounts
            ? deepSeekRequest(request)
            : fastMode ? openAiFastRequest(request) : request, false);
        AccountPoolRequestContext poolContext = poolContext(
            requestId,
            requestedModel,
            upstreamModel,
            route,
            preferDeepSeekAccounts
        );
        Mono<TierPlan> tierPlan = tierRuntime.planner().plan(tierPlanRequest(
            principal,
            poolContext,
            requestedModel,
            route,
            preferDeepSeekAccounts,
            preferCodexAccounts,
            fastMode,
            routingPolicy,
            upstreamRequest,
            null,
            false
        ).withSessionName(TierSessionKeys.named(sessionHint, codexSessionName(exchange, principal.keyId())))
            .withSelector(tierChoice.selector())
            .withEntry(tierEntry(responseMode))
            .withEntryFacts(entryFacts));

        return requireFastModeAllowed(fastMode).then(tierPlan).flatMap(plan -> {
            if (plan.tiered()) {
                return servedByTiers(plan, exchange, serveTieredChat(new TieredRequest(
                    plan,
                    principal,
                    requestId,
                    path,
                    requestedModel,
                    upstreamModel,
                    sessionHint,
                    stream,
                    servedRoute(plan, route),
                    fastMode,
                    preferCodexAccounts,
                    preferDeepSeekAccounts,
                    routingPolicy,
                    upstreamRequest,
                    request,
                    start,
                    true,
                    true
                ), responseMode));
            }
            return withSelectorStatus(plan, withBilling(principal, requestId, requestedModel, upstreamRequest, fastMode, Mono.defer(() -> attemptChat(
                upstreamRequest,
                request,
                principal,
                path,
                responseMode,
                requestedModel,
                upstreamModel,
                sessionHint,
                stream,
                route,
                fastMode,
                preferCodexAccounts,
                preferDeepSeekAccounts,
                poolContext,
                routingPolicy,
                requestId,
                start,
                List.of(),
                new AtomicBoolean(false)
            ))));
        });
    }

    private Mono<ResponseEntity<?>> attemptChat(
        JsonNode upstreamRequest,
        JsonNode responseRequest,
        ApiKeyPrincipal principal,
        String path,
        ResponseMode responseMode,
        String requestedModel,
        String mappedModel,
        String sessionHint,
        boolean stream,
        UpstreamRoute route,
        boolean fastMode,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        AccountPoolRequestContext poolContext,
        AccountDtos.RoutingPolicySettings routingPolicy,
        String requestId,
        Instant start,
        List<String> attemptedAccountIds,
        AtomicBoolean autoRetestAttempted
    ) {
        return selectAccountWithAdmission(
            route,
            sessionHint,
            attemptedAccountIds,
            preferCodexAccounts,
            preferDeepSeekAccounts,
            poolContext,
            routingPolicy,
            autoRetestAttempted,
            null,
            false,
            requestId,
            upstreamRequest,
            path
        )
            .flatMap(attempt -> withAdmissionLease(attempt, guardedAttempt -> {
                ProxyAccountEntity account = attempt.account();
                return markDispatched(attempt).then(apiKeyService.markUsageDispatched(principal, requestId)).then(captureUpstreamCall(Mono.defer(
                    () -> callWithAccount(route, account, upstreamRequest, mappedModel, sessionHint)
                ))).flatMap(outcome -> {
                    if (outcome.failure() == null) {
                        PlatformCompletionResult result = outcome.result();
                        bindSession(sessionHint, account.getAccountId(), routingPolicy, poolContext);
                        Mono<Void> accounting = recordSuccess(
                            requestId,
                            principal,
                            account,
                            path,
                            requestedModel,
                            result,
                            route,
                            fastMode,
                            guardedAttempt,
                            poolContext,
                            start
                        );
                        return accounting.then(Mono.fromSupplier(() -> stream
                            ? streamResponse(responseMode, responseRequest, mappedModel, result)
                            : jsonResponse(responseMode, responseRequest, mappedModel, result)));
                    }

                    Throwable error = outcome.failure();
                    UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(error, account);
                    List<String> nextAttempted = attemptedWith(account, attemptedAccountIds);
                    Mono<Void> failureRecord = recordFailure(
                        requestId,
                        principal,
                        account,
                        path,
                        requestedModel,
                        error,
                        classification,
                        guardedAttempt,
                        poolContext,
                        start
                    );
                    if (!shouldRetryWithAnotherAccount(
                        classification,
                        attemptedAccountIds,
                        nextAttempted,
                        true,
                        route,
                        preferCodexAccounts,
                        preferDeepSeekAccounts,
                        routingPolicy
                    )) {
                        return failureRecord.then(Mono.<ResponseEntity<?>>error(mapError(error)));
                    }
                    return failureRecord.then(attemptChat(
                        upstreamRequest,
                        responseRequest,
                        principal,
                        path,
                        responseMode,
                        requestedModel,
                        mappedModel,
                        sessionHint,
                        stream,
                        route,
                        fastMode,
                        preferCodexAccounts,
                        preferDeepSeekAccounts,
                        poolContext,
                        routingPolicy,
                        requestId,
                        start,
                        nextAttempted,
                        autoRetestAttempted
                    ).onErrorResume(retryError -> isNoAvailableAccount(retryError)
                        ? Mono.<ResponseEntity<?>>error(mapError(error))
                        : Mono.<ResponseEntity<?>>error(retryError)));
                });
            }))
            .doOnCancel(() -> accountPoolService.cancelCircuitProbe(poolContext))
            // A probe granted to this request must also be released when it fails before an outcome is recorded
            // (no admission capacity, dispatch error, expired lease). Otherwise the half-open circuit waits for the
            // probe lease to expire and then reopens, discarding the successes of the other probes.
            .doOnError(error -> accountPoolService.cancelCircuitProbe(poolContext));
    }

    private Mono<ResponseEntity<?>> invokeResponses(
        JsonNode clientRequest,
        ServerWebExchange exchange,
        ApiKeyPrincipal principal,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        validateResponsesRequest(clientRequest);
        Instant start = Instant.now();
        String requestId = UUID.randomUUID().toString();
        ClientTierChoice tierChoice = clientTierChoice(clientRequest, exchange, principal, ResponseMode.OPENAI_RESPONSES, routingPolicy);
        JsonNode request = tierChoice.request();
        String path = "/v1/responses";
        String requestedModel = request.path("model").asString(modelCatalog.defaultTargetModelId());
        String mappedModel = tierChoice.mappedModel();
        ModelProviderAccessPolicy.verify(modelCatalog, principal, requestedModel, mappedModel);
        RouteFacts routeFacts = routeFacts(request, requestedModel, mappedModel, ResponseMode.OPENAI_RESPONSES, exchange, routingPolicy);
        boolean requestedFastMode = routeFacts.requestedFastMode();
        String upstreamModel = routeFacts.upstreamModel();
        String previousResponseId = firstNonBlank(request.path("previous_response_id").asString(null));
        String sessionHint = sessionHint(
            exchange,
            request,
            principal.keyId(),
            ResponseMode.OPENAI_RESPONSES,
            previousResponseId
        );
        boolean stream = request.path("stream").asBoolean(false);
        UpstreamRoute route = routeFacts.route();
        boolean preferDeepSeekAccounts = routeFacts.preferDeepSeekAccounts();
        boolean fastMode = requestedFastMode && route == UpstreamRoute.OPENAI && !preferDeepSeekAccounts;
        boolean preferCodexAccounts = routeFacts.preferCodexAccounts();
        JsonNode expandedRequest = openAiResponseStateStore.expand(request);
        JsonNode upstreamRequest = boundedBillingRequest(preferDeepSeekAccounts
            ? deepSeekRequest(expandedRequest)
            : fastMode ? openAiFastRequest(expandedRequest) : expandedRequest, true);
        AccountPoolRequestContext poolContext = poolContext(
            requestId,
            requestedModel,
            upstreamModel,
            route,
            preferDeepSeekAccounts
        );
        // A response chain is told apart by the response it continues, not by the session hint, which falls back to that
        // id and so changes with every turn; a chain with neither is told by what its turns begin with. Every turn of a
        // chain is the session of its first, whatever model it asks for: the node knows which session a response was
        // made under (followTierToNextTurn).
        String tierSessionName = tierRuntime.bindings().sessionOf(TierSessionKeys.ofResponses(
            principal.keyId(),
            sessionHint(exchange, request, principal.keyId(), ResponseMode.OPENAI_RESPONSES, null),
            codexSessionName(exchange, principal.keyId()),
            previousResponseId
        ));

        return requireDeepSeekResponsesSupported(preferDeepSeekAccounts, upstreamModel)
            .then(requireFastModeAllowed(fastMode))
            .then(responseAffinity(previousResponseId, route))
            .flatMap(responseAffinity -> tierRuntime.planner().plan(tierPlanRequest(
                principal,
                poolContext,
                requestedModel,
                route,
                preferDeepSeekAccounts,
                preferCodexAccounts,
                fastMode,
                routingPolicy,
                upstreamRequest,
                responseAffinity.accountId(),
                responseAffinity.movable()
            ).withSessionName(tierSessionName).withSelector(tierChoice.selector()).withEntry(TierEntry.RESPONSES)
                // The chain holds the body all along, so it is read here, once the chain the request continues is expanded.
                .withEntryFacts(responsesEntryFacts(expandedRequest))).flatMap(plan -> {
                if (plan.tiered()) {
                    return servedByTiers(plan, exchange, serveTieredResponses(new TieredRequest(
                        plan,
                        principal,
                        requestId,
                        path,
                        requestedModel,
                        upstreamModel,
                        sessionHint,
                        stream,
                        servedRoute(plan, route),
                        fastMode,
                        preferCodexAccounts,
                        preferDeepSeekAccounts,
                        routingPolicy,
                        upstreamRequest,
                        request,
                        start,
                        !responseAffinity.responseChain() || responseAffinity.movable(),
                        responseAffinity.movable(),
                        tierSessionName
                    )));
                }
                return withSelectorStatus(plan, withBilling(principal, requestId, requestedModel, upstreamRequest, fastMode, Mono.defer(() -> attemptResponses(
                    upstreamRequest,
                    request,
                    principal,
                    path,
                    requestedModel,
                    upstreamModel,
                    sessionHint,
                    stream,
                    route,
                    fastMode,
                    preferCodexAccounts,
                    preferDeepSeekAccounts,
                    responseAffinity,
                    poolContext,
                    routingPolicy,
                    requestId,
                    start,
                    List.of(),
                    new AtomicBoolean(false),
                    tierSessionName
                ))));
            }));
    }

    /** The session the Codex CLI names in its own header, as the tier sessions call it; null when the request has none. */
    private static String codexSessionName(ServerWebExchange exchange, String keyId) {
        return TierSessionKeys.ofCodexSession(
            keyId,
            exchange.getRequest().getHeaders().getFirst(TierSessionKeys.CODEX_SESSION_HEADER)
        );
    }

    private static TierEntry tierEntry(ResponseMode responseMode) {
        return switch (responseMode) {
            case OPENAI_CHAT -> TierEntry.CHAT;
            case OPENAI_RESPONSES -> TierEntry.RESPONSES;
            case ANTHROPIC_MESSAGES -> TierEntry.MESSAGES;
            case GEMINI_GENERATE_CONTENT -> TierEntry.GEMINI;
        };
    }

    /**
     * The route a tiered request is dispatched on. It is the route the facade chose, unless the all-endpoints switch of
     * its vendor moved it onto the Anthropic route (design 4.9): everything the request does from selection to
     * settlement, and the retries it is allowed, then follow that route, as a request of the Messages endpoint's do.
     */
    private static UpstreamRoute servedRoute(TierPlan plan, UpstreamRoute route) {
        return plan.unified() ? UpstreamRoute.ANTHROPIC : route;
    }

    private TierPlanRequest tierPlanRequest(
        ApiKeyPrincipal principal,
        AccountPoolRequestContext poolContext,
        String requestedModel,
        UpstreamRoute route,
        boolean preferDeepSeekAccounts,
        boolean preferCodexAccounts,
        boolean fastMode,
        AccountDtos.RoutingPolicySettings routingPolicy,
        JsonNode upstreamRequest,
        String responseOwnerAccountId,
        boolean responseMovable
    ) {
        return new TierPlanRequest(
            poolContext.requestToken(),
            requestedModel,
            poolContext.upstreamModel(),
            tierProviderHint(route, poolContext.upstreamModel(), preferDeepSeekAccounts, preferCodexAccounts),
            TierRoute.valueOf(route.name()),
            preferDeepSeekAccounts,
            route == UpstreamRoute.OPENAI && isOpenAiCompatibleModel(poolContext.upstreamModel()),
            preferCodexAccounts,
            routingPolicy.isolateCodexPoolEnabled(),
            fastMode,
            !"anonymous".equals(principal.keyId()),
            upstreamRequest,
            responseOwnerAccountId,
            responseMovable,
            principal.keyId(),
            null
        ).withKeyHoldsPreference(tiedToOneProvider(principal))
            .withSuffixTakenOff(spelling -> !modelMappingService.hasAtSource(spelling, principal.keyId()));
    }

    /**
     * The request without the tier selector that was on the end of its model, the tier the client named by the channel
     * that wins (design 6.1), and the model the key's mapping makes of the model the request is left with. {@code
     * suffixTaken} says that a suffix was taken off the model, which the Gemini endpoint (it carries the model in its
     * path) must follow.
     */
    private record ClientTierChoice(JsonNode request, TierSelectorParser.Selector selector, String mappedModel, boolean suffixTaken) {
    }

    /**
     * Reads what the client said about the tier it wants, before anything else looks at the request: the suffix is taken
     * off the model id here, once, so that model mapping, the access check, the price and the logs only ever see the
     * plain id. Whether an {@code @} is a suffix is decided by the vendor that will serve the model, after the key's model
     * mapping and the route have had their say, and an id that a mapping rule of the key is written around (a Vertex-style
     * "claude-sonnet-4@20250514", or a pattern such as {@code claude-sonnet-4@*}) is left whole. The suffix wins over the
     * {@value TierSelectorParser#REQUEST_HEADER} header, and the header over the preference of the key. A key that is not
     * tied to one provider (an old key, {@code *}) has no preference to follow: the tiers are named per vendor, and such a
     * key may call any of them. The anonymous caller (the console's own assistant, or a deployment that allows
     * unauthenticated proxying) may not choose a tier at all: what it names is read like anyone's, so that a suffix never
     * reaches the upstream as part of the model, and then dropped as a hint that cannot be followed.
     *
     * <p>The key's mapping is applied here, once, and its answer is the model the request goes on with: a rule that picks
     * among several targets at random (a {@code loadbalance} rule) must not be asked again, or the vendor that decided
     * whether the {@code @} is a suffix could be another one than the vendor that serves the request.
     */
    private ClientTierChoice clientTierChoice(
        JsonNode request,
        ServerWebExchange exchange,
        ApiKeyPrincipal principal,
        ResponseMode responseMode,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        String keyId = principal.keyId();
        String model = request.path("model").asString(modelCatalog.defaultTargetModelId());
        // The suffix is decided by the vendor the model goes to, which the mapping may change.
        TierSelectorParser.Stripped stripped = model.indexOf('@') < 0 || modelMappingService.hasAtSource(model, keyId)
            ? TierSelectorParser.Stripped.plain(model)
            : tierRuntime.planner().stripSelector(model, base -> {
                String mapped = modelMappingService.apply(base, keyId);
                return tierDestination(request, base, mapped, responseMode, exchange, routingPolicy);
            });
        JsonNode plain = request;
        String mappedModel;
        if (stripped.selector() != null) {
            ObjectNode copy = JsonNodes.shallowCopy((ObjectNode) request);
            copy.put("model", stripped.model());
            plain = copy;
            mappedModel = stripped.destination().mappedModel();
        } else {
            mappedModel = modelMappingService.apply(model, keyId);
        }
        TierSelectorParser.Selector header = TierSelectorParser.fromHeader(
            exchange.getRequest().getHeaders().getFirst(TierSelectorParser.REQUEST_HEADER));
        TierSelectorParser.Selector preference = tiedToOneProvider(principal)
            ? TierSelectorParser.fromPreference(principal.tierPreference())
            : null;
        TierSelectorParser.Selector chosen = TierSelectorParser.choose(stripped.selector(), header, preference);
        return new ClientTierChoice(plain, "anonymous".equals(keyId) ? TierSelectorParser.unchoosable(chosen) : chosen, mappedModel,
            stripped.selector() != null);
    }

    /**
     * Where a request goes: the model as the upstream is asked for it, the route that takes it, and which kinds of
     * account that route prefers. Worked out once for a request, from its model after the key's mapping, and the same
     * way for the model in front of a tier suffix before the rest of the request is read ({@link #tierDestination}).
     */
    private record RouteFacts(
        boolean requestedFastMode,
        String upstreamModel,
        UpstreamRoute route,
        boolean preferDeepSeekAccounts,
        boolean preferCodexAccounts
    ) {
    }

    private RouteFacts routeFacts(
        JsonNode request,
        String requestedModel,
        String mappedModel,
        ResponseMode responseMode,
        ServerWebExchange exchange,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        boolean requestedFastMode = isFastRequested(request, requestedModel, mappedModel);
        String upstreamModel = upstreamModelFor(mappedModel, requestedFastMode);
        UpstreamRoute route = routeFor(upstreamModel, responseMode);
        boolean preferDeepSeekAccounts = isDeepSeekRouteModel(route, upstreamModel);
        // GPT Responses also go to Codex seats for the models only Codex accounts serve.
        boolean preferCodexAccounts = responseMode == ResponseMode.OPENAI_RESPONSES
            ? preferCodexAccountsForResponses(route, preferDeepSeekAccounts, requestedModel, mappedModel, upstreamModel, exchange, routingPolicy)
            : preferCodexAccounts(route, preferDeepSeekAccounts, upstreamModel, exchange, routingPolicy);
        return new RouteFacts(requestedFastMode, upstreamModel, route, preferDeepSeekAccounts, preferCodexAccounts);
    }

    /**
     * Where a request for {@code requestedModel}, which the key's mapping made {@code mappedModel}, goes: the route
     * takes it, and the plan names its vendor by the provider hint of the accounts that serve it.
     */
    private TierRuntime.Destination tierDestination(
        JsonNode request,
        String requestedModel,
        String mappedModel,
        ResponseMode responseMode,
        ServerWebExchange exchange,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        RouteFacts facts = routeFacts(request, requestedModel, mappedModel, responseMode, exchange, routingPolicy);
        return new TierRuntime.Destination(mappedModel, facts.upstreamModel(), tierProviderHint(
            facts.route(), facts.upstreamModel(), facts.preferDeepSeekAccounts(), facts.preferCodexAccounts()));
    }

    /**
     * The provider hint the tier plan names its vendor by, for a model the catalog does not list: the vendor of the
     * accounts that will serve the request. It is the pool context's hint ({@link #providerHint}), except where the
     * route's name for itself is not the vendor of the accounts: the Kiro route is also what a model of Codex only
     * (such as {@code codex-mini-latest}, which does not start with {@code gpt}) is sent by, to Codex seats, and what a
     * Claude model newer than the catalog is served by Kiro accounts on, which sit in the tiers of the anthropic vendor.
     * The pool context keeps the hint of the route; its pool keys are frozen for requests of vendors without tiers.
     */
    private String tierProviderHint(UpstreamRoute route, String upstreamModel, boolean deepSeekRoute, boolean preferCodexAccounts) {
        if (route != UpstreamRoute.OPENAI && preferCodexAccounts) {
            return AccountPlatforms.OPENAI_PROVIDER;
        }
        if (route == UpstreamRoute.KIRO && routeModel(upstreamModel).startsWith("claude")) {
            return AccountPlatforms.ANTHROPIC_PROVIDER;
        }
        return providerHint(route, upstreamModel, deepSeekRoute);
    }

    /** Whether the key is limited to the models of one provider, as the access policy reads the provider of a key. */
    private static boolean tiedToOneProvider(ApiKeyPrincipal principal) {
        String provider = principal.modelProvider();
        return provider != null && !provider.isBlank() && !"*".equals(provider);
    }

    /**
     * The response of an untiered request, which says that the tier the client named was not used when its vendor
     * keeps a tier configuration that is switched off (design 6.3). Any other untiered response is returned as it is.
     */
    private Mono<ResponseEntity<?>> withSelectorStatus(TierPlan plan, Mono<ResponseEntity<?>> served) {
        if (!plan.selectorIgnored()) {
            return served;
        }
        return served.map(response -> ResponseEntity.status(response.getStatusCode())
            .headers(response.getHeaders())
            .header(TierResponseHeaders.STATUS, TierResponseHeaders.IGNORED)
            .body(response.getBody()));
    }

    /**
     * What the client is answered when a tiered request fails. A request that named its tier gets 503
     * SOURCE_TIER_UNAVAILABLE with a Retry-After when that tier has no account to offer, where an automatic one has the
     * "no available account" of an untiered request. The conversion is outside the billing scope on purpose: the release
     * of the reservation has already judged the original error, which is not one of the definitive failures. A Responses
     * chain that cannot leave its owner is answered with what is wrong with the owner, as an automatic request is: it is
     * not the pool that has nothing to offer, and retrying soon does not help when the owner is out of quota.
     */
    private Mono<ResponseEntity<?>> servedByTiers(TierPlan plan, ServerWebExchange exchange, Mono<ResponseEntity<?>> served) {
        if (!plan.pinned()) {
            return served;
        }
        return served.onErrorResume(error -> isNoAvailableAccount(error) && !isResponseOwnerUnavailable(error), error -> {
            exchange.getResponse().getHeaders().set("Retry-After", TIER_UNAVAILABLE_RETRY_AFTER_SECONDS);
            return Mono.error(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SOURCE_TIER_UNAVAILABLE",
                "The tier you chose has no account available right now"
            ));
        });
    }

    private Mono<Void> requireFastModeAllowed(boolean fastMode) {
        return fastMode ? accountSettingsService.ensureGptFastModeAllowed() : Mono.empty();
    }

    private JsonNode boundedBillingRequest(JsonNode request, boolean responses) {
        // Only top-level token limits change here, so share the (possibly multi-MB) children instead of deep-copying them.
        ObjectNode bounded = JsonNodes.shallowCopy((ObjectNode) request);
        long limit = 0;
        for (String field : java.util.List.of("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            JsonNode value = request.get(field);
            if (value == null) continue;
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0 || value.longValue() > 1_000_000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MAX_OUTPUT_TOKENS_INVALID", "Maximum output tokens must be an integer between 1 and 1000000");
            }
            limit = Math.max(limit, value.longValue());
        }
        if (limit == 0) limit = 4096;
        bounded.put(responses ? "max_output_tokens" : bounded.has("max_completion_tokens") ? "max_completion_tokens" : "max_tokens", limit);
        return bounded;
    }

    private static final Object BILLING_UNCERTAINTY = new Object();
    private static final Object BILLING_SETTLED = new Object();
    /**
     * Whether the request is in the hands of a relay (design 7.8, invariant I7): from the moment a cross-protocol attempt
     * is dispatched until the settlement of its answer has returned, and not while a native account has the request.
     */
    private static final Object XP_IN_FLIGHT = new Object();

    private <T> Mono<T> withBilling(ApiKeyPrincipal principal, String requestId, String model, JsonNode request,
                                    boolean fastMode, Mono<T> operation) {
        return withBilling(principal, requestId, model, request, fastMode, TierPlan.UNTIERED, operation);
    }

    // A tiered plan brings its own reservation and prices (TierBilling): it reserves the most any tier it may be served
    // from would need and is charged the price of the tier that served. What it shares with an untiered request is how the
    // reservation is held and released; the release counts a failure as unbilled by tierDefinitive, which also takes the
    // 402 of a source that has no quota left.
    private <T> Mono<T> withBilling(ApiKeyPrincipal principal, String requestId, String model, JsonNode request,
                                    boolean fastMode, TierPlan plan, Mono<T> operation) {
        if ("anonymous".equals(principal.keyId())) return operation;
        if (plan.tiered()) {
            return Mono.defer(() -> {
                TierBilling billing = TierBilling.of(plan, model);
                return reservedFor(principal, requestId, billing.reservation(), billing.pricing(), operation,
                    context -> context.put(TierBilling.class, billing), this::tierDefinitive, error -> lowBalanceAdvice(plan, error));
            });
        }
        long estimatedInput = RequestBounds.nativeInputTokens(request);
        long outputBound = RequestBounds.outputTokens(request);
        return paymentSettingsService.config().flatMap(config -> modelPricingService.billingQuote(model, fastMode, config.apiBillingMultiplier()))
            .flatMap(quote -> {
                ObjectNode pricing = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                pricing.put("model", model).put("input", quote.input()).put("output", quote.output())
                    .put("cacheRead", quote.cacheRead()).put("cacheWrite", quote.cacheWrite()).put("fallback", quote.fallback());
                return reservedFor(principal, requestId, quote.estimate(estimatedInput, outputBound), pricing.toString(), operation,
                    context -> context.put(tech.starryapi.model.BillingQuote.class, quote), this::definitiveBillingFailure, null);
            });
    }

    /**
     * The refusal of the reservation of an automatic tiered request for too little balance, with the tiers that would
     * have reserved less added to what the client is told (design 5.6): the reservation covers the dearest tier the
     * request may be served from, which a user who could pay for a cheaper one may avoid by naming it.
     */
    private static Throwable lowBalanceAdvice(TierPlan plan, Throwable error) {
        if (plan.lowBalanceHint() != null && error instanceof ApiException api && "INSUFFICIENT_BALANCE".equals(api.getCode())) {
            return new ApiException(api.getStatus(), api.getCode(), api.getMessage() + plan.lowBalanceHint(), api.getDetails());
        }
        return error;
    }

    /**
     * Holds a reservation of {@code credits} for as long as {@code operation} runs and releases it unless the usage was
     * settled.
     *
     * <p>A relay is asked once, and what becomes of that attempt is the operator's whenever it does not end in a
     * settlement (design 7.8, invariant I7): while {@link #XP_IN_FLIGHT} stands, a client that gives up and any failure
     * release the reservation for good, where an upstream account that was asked and did not answer leaves it for review.
     * An earlier native attempt that may have been billed keeps the reservation in review all the same: the attempt at
     * the relay does not prove that one free.
     *
     * @param billing puts what the settlement needs to price the usage into the operation's context
     * @param definitiveFailure whether a failure is known not to have been billed, so that the reservation is released
     *     rather than left for review
     * @param reservationFailure what the client is told when the reservation itself is refused; null to tell it as it is
     */
    private <T> Mono<T> reservedFor(
        ApiKeyPrincipal principal,
        String requestId,
        BigDecimal credits,
        String pricing,
        Mono<T> operation,
        UnaryOperator<Context> billing,
        Predicate<Throwable> definitiveFailure,
        UnaryOperator<Throwable> reservationFailure
    ) {
        AtomicBoolean uncertainFailure = new AtomicBoolean();
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean relayHasRequest = new AtomicBoolean();
        Mono<Void> reservation = apiKeyService.reserveUsage(principal, requestId, credits, pricing);
        return Mono.usingWhen(
            (reservationFailure == null ? reservation : reservation.onErrorMap(reservationFailure)).thenReturn(requestId),
            ignored -> operation.contextWrite(context -> billing.apply(context)
                .put(BILLING_UNCERTAINTY, uncertainFailure)
                .put(BILLING_SETTLED, settled)
                .put(XP_IN_FLIGHT, relayHasRequest)),
            // Once the usage has been settled the reservation is closed; releasing it again would only take the
            // user and key row locks to find that out. Release only when nothing was charged.
            ignored -> settled.get()
                ? Mono.empty()
                : apiKeyService.releaseUsage(principal, requestId, true, "Completed without charge"),
            (ignored, error) -> apiKeyService.releaseUsage(principal, requestId,
                !uncertainFailure.get() && (relayHasRequest.get() || definitiveFailure.test(error)), "Request failed"),
            // Settled already, the release finds the reservation closed and changes nothing.
            ignored -> relayHasRequest.get() && !uncertainFailure.get()
                ? apiKeyService.releaseUsage(principal, requestId, true, "Client disconnected; cross-protocol attempt released")
                : apiKeyService.releaseUsage(principal, requestId, false, "Client disconnected after dispatch")
        );
    }

    /**
     * Whether a failed request is known not to have been billed by the upstream, so the reservation can simply be
     * released. Anything uncertain (a response timeout after the request was sent, a reset mid-request) stays
     * flagged for manual review, because the upstream may still have generated and charged for it.
     */
    private boolean definitiveBillingFailure(Throwable error) {
        Throwable root = rootFailure(error);
        if (root instanceof ApiException api) {
            return java.util.Set.of(400, 401, 403, 404, 422, 429).contains(api.getStatus().value());
        }
        UpstreamException upstream = findUpstreamException(error);
        if (upstream != null) {
            int status = upstream.getStatusCode();
            if (java.util.Set.of(400, 401, 403, 404, 422, 429).contains(status)) {
                return true;
            }
            // A genuine provider error response (overloaded, unavailable, internal error) is not billed. A 502 that
            // we synthesise for a 200 body we could not parse is not recoverable and stays uncertain.
            return upstream.isRecoverable() && java.util.Set.of(500, 502, 503, 529).contains(status);
        }
        return UpstreamFailureClassifier.neverReachedUpstream(error);
    }

    /**
     * {@link #definitiveBillingFailure} for a tiered request, which also counts the 402 of a source that has no quota
     * left: it is a refusal to serve, never a generation. Untiered, the same 402 stays billing-uncertain as it always
     * was, and the first of them locks the key's user into review; correcting that for every request is its own change.
     */
    boolean tierDefinitive(Throwable error) {
        if (definitiveBillingFailure(error)) {
            return true;
        }
        UpstreamException upstream = findUpstreamException(error);
        return upstream != null && upstream.getStatusCode() == 402;
    }

    private static UpstreamException findUpstreamException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UpstreamException upstream) {
                return upstream;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    private Mono<Void> requireDeepSeekResponsesSupported(boolean deepSeekRoute, String model) {
        if (!deepSeekRoute || DeepSeekModelCatalog.supportsResponses(model)) {
            return Mono.empty();
        }
        return Mono.error(new ApiException(
            HttpStatus.BAD_REQUEST,
            "UNSUPPORTED_MODEL_OPERATION",
            "DeepSeek Responses API currently supports only " + DeepSeekModelCatalog.FLASH_MODEL_ID
        ));
    }

    private Mono<ResponseEntity<?>> attemptResponses(
        JsonNode upstreamRequest,
        JsonNode responseRequest,
        ApiKeyPrincipal principal,
        String path,
        String requestedModel,
        String mappedModel,
        String sessionHint,
        boolean stream,
        UpstreamRoute route,
        boolean fastMode,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        ResponseAffinity responseAffinity,
        AccountPoolRequestContext poolContext,
        AccountDtos.RoutingPolicySettings routingPolicy,
        String requestId,
        Instant start,
        List<String> attemptedAccountIds,
        AtomicBoolean autoRetestAttempted,
        String chainSession
    ) {
        String responseOwner = attemptedAccountIds.isEmpty() ? responseAffinity.accountId() : null;
        return selectAccountWithAdmission(
            route,
            sessionHint,
            attemptedAccountIds,
            preferCodexAccounts,
            preferDeepSeekAccounts,
            poolContext,
            routingPolicy,
            autoRetestAttempted,
            responseOwner,
            responseAffinity.movable(),
            requestId,
            upstreamRequest,
            path
        )
            .flatMap(attempt -> withAdmissionLease(attempt, guardedAttempt -> {
                ProxyAccountEntity account = attempt.account();
                return markDispatched(attempt).then(apiKeyService.markUsageDispatched(principal, requestId)).then(captureUpstreamCall(Mono.defer(
                    () -> callResponsesWithAccount(route, account, upstreamRequest, mappedModel, sessionHint)
                ))).flatMap(outcome -> {
                    if (outcome.failure() == null) {
                        PlatformCompletionResult result = outcome.result();
                        bindSession(sessionHint, account.getAccountId(), routingPolicy, poolContext);
                        Mono<Void> accounting = recordSuccess(
                            requestId,
                            principal,
                            account,
                            path,
                            requestedModel,
                            result,
                            route,
                            fastMode,
                            guardedAttempt,
                            poolContext,
                            start
                        );
                        return accounting.then(Mono.defer(() -> {
                            PreparedResponse response = stream
                                ? prepareResponsesStreamResponse(responseRequest, upstreamRequest, mappedModel, result, account)
                                : prepareResponsesJsonResponse(responseRequest, upstreamRequest, mappedModel, result, account);
                            // A turn of a chain that no tier planned is the chain's all the same: a model of a vendor without tiers
                            // must not cut the chain's session (and what it holds) in two for the turns after it. Where no vendor has
                            // tiers there is no session to keep whole, and nothing is written.
                            if (tierRuntime.planner().tiersInUse()) {
                                followChain(principal, response.responseId(), chainSession);
                            }
                            return rememberResponseOwner(response, account).thenReturn(response.response());
                        }));
                    }

                    Throwable error = outcome.failure();
                    UpstreamFailureClassifier.Classification classification = UpstreamFailureClassifier.classify(error, account);
                    List<String> nextAttempted = attemptedWith(account, attemptedAccountIds);
                    Mono<Void> failureRecord = recordFailure(
                        requestId,
                        principal,
                        account,
                        path,
                        requestedModel,
                        error,
                        classification,
                        guardedAttempt,
                        poolContext,
                        start
                    );
                    boolean responseMovable = !responseAffinity.responseChain() || responseAffinity.movable();
                    if (!shouldRetryWithAnotherAccount(
                        classification,
                        attemptedAccountIds,
                        nextAttempted,
                        responseMovable,
                        route,
                        preferCodexAccounts,
                        preferDeepSeekAccounts,
                        routingPolicy
                    )) {
                        return failureRecord.then(Mono.<ResponseEntity<?>>error(mapError(error)));
                    }
                    return failureRecord.then(attemptResponses(
                        upstreamRequest,
                        responseRequest,
                        principal,
                        path,
                        requestedModel,
                        mappedModel,
                        sessionHint,
                        stream,
                        route,
                        fastMode,
                        preferCodexAccounts,
                        preferDeepSeekAccounts,
                        responseAffinity,
                        poolContext,
                        routingPolicy,
                        requestId,
                        start,
                        nextAttempted,
                        autoRetestAttempted,
                        chainSession
                    ).onErrorResume(retryError -> isNoAvailableAccount(retryError)
                        ? Mono.<ResponseEntity<?>>error(mapError(error))
                        : Mono.<ResponseEntity<?>>error(retryError)));
                });
            }))
            .doOnCancel(() -> accountPoolService.cancelCircuitProbe(poolContext))
            // A probe granted to this request must also be released when it fails before an outcome is recorded
            // (no admission capacity, dispatch error, expired lease). Otherwise the half-open circuit waits for the
            // probe lease to expire and then reopens, discarding the successes of the other probes.
            .doOnError(error -> accountPoolService.cancelCircuitProbe(poolContext));
    }

    /**
     * Codex tokens and official OpenAI API keys stay in separate pools when isolation is on.
     * GPT traffic may still enter the Codex pool via {@code gptUsesCodexAccounts}, but it never
     * falls back onto {@code sk-} keys after a Codex miss.
     */
    private Mono<ProxyAccountEntity> selectAccount(
        UpstreamRoute route,
        String sessionHint,
        List<String> attemptedAccountIds,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        AccountPoolRequestContext poolContext,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        boolean isolateCodex = routingPolicy.isolateCodexPoolEnabled();
        if (preferDeepSeekAccounts) {
            return attemptedAccountIds == null || attemptedAccountIds.isEmpty()
                ? accountPoolService.selectDeepSeekOpenAiAccountForPool(sessionHint, poolContext)
                : accountPoolService.selectDeepSeekOpenAiAccountForPool(sessionHint, attemptedAccountIds, poolContext);
        }
        if (route == UpstreamRoute.OPENAI
            && poolContext != null
            && isOpenAiCompatibleModel(poolContext.upstreamModel())) {
            return selectOpenAiCompatiblePool(sessionHint, attemptedAccountIds, poolContext);
        }
        if (preferCodexAccounts) {
            Mono<ProxyAccountEntity> codexSelection = attemptedAccountIds == null || attemptedAccountIds.isEmpty()
                ? accountPoolService.selectCodexOpenAiAccountForPool(sessionHint, poolContext)
                : accountPoolService.selectCodexOpenAiAccountForPool(sessionHint, attemptedAccountIds, poolContext);
            if (codexSelection == null) {
                return Mono.error(new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "NO_AVAILABLE_ACCOUNT",
                    "No available Codex upstream account"
                ));
            }
            if (isolateCodex) {
                return codexSelection;
            }
            return codexSelection.onErrorResume(error -> isSubtypePoolUnavailable(error)
                ? selectOpenAiPool(sessionHint, attemptedAccountIds, poolContext, false)
                : Mono.error(error));
        }
        if (route == UpstreamRoute.OPENAI) {
            return selectOpenAiPool(sessionHint, attemptedAccountIds, poolContext, isolateCodex);
        }
        if (route == UpstreamRoute.ANTHROPIC) {
            Mono<ProxyAccountEntity> anthropic = attemptedAccountIds == null || attemptedAccountIds.isEmpty()
                ? accountPoolService.selectAnthropicAccountForPool(sessionHint, poolContext)
                : accountPoolService.selectAnthropicAccountForPool(sessionHint, attemptedAccountIds, poolContext);
            return anthropic.onErrorResume(error -> isSubtypePoolUnavailable(error)
                ? selectKiroPool(sessionHint, attemptedAccountIds, poolContext)
                : Mono.error(error));
        }
        return selectKiroPool(sessionHint, attemptedAccountIds, poolContext);
    }

    private Mono<ProxyAccountEntity> selectOpenAiPool(
        String sessionHint,
        List<String> attemptedAccountIds,
        AccountPoolRequestContext poolContext,
        boolean apiKeyOnly
    ) {
        Mono<ProxyAccountEntity> selected;
        if (attemptedAccountIds == null || attemptedAccountIds.isEmpty()) {
            selected = apiKeyOnly
                ? accountPoolService.selectOpenAiApiKeyAccountForPool(sessionHint, poolContext)
                : accountPoolService.selectOpenAiAccountForPool(sessionHint, poolContext);
        } else {
            selected = apiKeyOnly
                ? accountPoolService.selectOpenAiApiKeyAccountForPool(sessionHint, attemptedAccountIds, poolContext)
                : accountPoolService.selectOpenAiAccountForPool(sessionHint, attemptedAccountIds, poolContext);
        }
        return selected == null
            ? Mono.error(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "NO_AVAILABLE_ACCOUNT",
                "No available OpenAI upstream account"
            ))
            : selected;
    }

    private Mono<ProxyAccountEntity> selectOpenAiCompatiblePool(
        String sessionHint,
        List<String> attemptedAccountIds,
        AccountPoolRequestContext poolContext
    ) {
        return attemptedAccountIds == null || attemptedAccountIds.isEmpty()
            ? accountPoolService.selectOpenAiCompatibleAccountForPool(sessionHint, poolContext)
            : accountPoolService.selectOpenAiCompatibleAccountForPool(sessionHint, attemptedAccountIds, poolContext);
    }

    private Mono<ProxyAccountEntity> selectKiroPool(
        String sessionHint,
        List<String> attemptedAccountIds,
        AccountPoolRequestContext poolContext
    ) {
        return attemptedAccountIds == null || attemptedAccountIds.isEmpty()
            ? accountPoolService.selectKiroAccountForPool(sessionHint, poolContext)
            : accountPoolService.selectKiroAccountForPool(sessionHint, attemptedAccountIds, poolContext);
    }

    private Mono<ProxyAccountEntity> selectAccountWithAutoRetest(
        UpstreamRoute route,
        String sessionHint,
        List<String> attemptedAccountIds,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        AccountPoolRequestContext poolContext,
        AccountDtos.RoutingPolicySettings routingPolicy,
        AtomicBoolean autoRetestAttempted
    ) {
        Mono<ProxyAccountEntity> selected = selectAccount(
            route,
            sessionHint,
            attemptedAccountIds,
            preferCodexAccounts,
            preferDeepSeekAccounts,
            poolContext,
            routingPolicy
        );
        if (selected == null) {
            return Mono.error(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "NO_AVAILABLE_ACCOUNT",
                "No available upstream account"
            ));
        }
        return selected
            .onErrorResume(error -> {
                if (!shouldAutoRetestUnavailableAccounts(error, autoRetestAttempted)) {
                    return Mono.error(error);
                }
                var retest = disabledAccountScheduledTestService.runWhenNoAvailableAccounts(
                    accountFilterFor(route, preferDeepSeekAccounts, preferCodexAccounts, routingPolicy, poolContext)
                );
                if (retest == null) {
                    return Mono.error(error);
                }
                return retest.doOnNext(response -> log.info(
                        "Auto disabled account retest after no available account completed: route={}, total={}, success={}, failed={}, skipped={}",
                        route,
                        response.total(),
                        response.success(),
                        response.failed(),
                        response.skipped()
                    ))
                    .filter(response -> response.success() != null && response.success() > 0)
                    .flatMap(response -> selectAccount(
                        route,
                        sessionHint,
                        attemptedAccountIds,
                        preferCodexAccounts,
                        preferDeepSeekAccounts,
                        poolContext,
                        routingPolicy
                    ))
                    .switchIfEmpty(Mono.error(error))
                    .onErrorResume(retestError -> {
                        if (!isNoAvailableAccount(retestError)) {
                            log.warn("Auto disabled account retest failed after no available account", retestError);
                        }
                        return Mono.error(error);
                    });
            });
    }

    private Mono<AccountAttempt> selectAccountWithAdmission(
        UpstreamRoute route,
        String sessionHint,
        List<String> attemptedAccountIds,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        AccountPoolRequestContext poolContext,
        AccountDtos.RoutingPolicySettings routingPolicy,
        AtomicBoolean autoRetestAttempted,
        String responseOwnerAccountId,
        boolean allowResponseOwnerFallback,
        String requestId,
        JsonNode request,
        String endpointScope
    ) {
        return selectAccountWithAdmission(
            route,
            sessionHint,
            attemptedAccountIds,
            preferCodexAccounts,
            preferDeepSeekAccounts,
            poolContext,
            routingPolicy,
            autoRetestAttempted,
            responseOwnerAccountId,
            allowResponseOwnerFallback,
            requestId,
            request,
            endpointScope,
            List.of(),
            estimatedInputTokens(request)
        );
    }

    private Mono<AccountAttempt> selectAccountWithAdmission(
        UpstreamRoute route,
        String sessionHint,
        List<String> attemptedAccountIds,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        AccountPoolRequestContext poolContext,
        AccountDtos.RoutingPolicySettings routingPolicy,
        AtomicBoolean autoRetestAttempted,
        String responseOwnerAccountId,
        boolean allowResponseOwnerFallback,
        String requestId,
        JsonNode request,
        String endpointScope,
        List<String> admissionRejectedAccountIds,
        long estimatedInput
    ) {
        if (admissionRejectedAccountIds.size() >= MAX_ADMISSION_REFUSALS) {
            return Mono.error(admissionExhausted());
        }
        List<String> excluded = mergedAccountIds(attemptedAccountIds, admissionRejectedAccountIds);
        Mono<ProxyAccountEntity> selection;
        if (responseOwnerAccountId != null && route == UpstreamRoute.OPENAI) {
            selection = accountPoolService.selectOpenAiAccountByIdForPool(responseOwnerAccountId, poolContext);
            if (allowResponseOwnerFallback) {
                selection = selection.onErrorResume(error -> isSubtypePoolUnavailable(error)
                    ? selectAccountWithAutoRetest(
                        route,
                        sessionHint,
                        excluded,
                        preferCodexAccounts,
                        preferDeepSeekAccounts,
                        poolContext,
                        routingPolicy,
                        autoRetestAttempted
                    )
                    : Mono.error(error));
            }
        } else {
            selection = selectAccountWithAutoRetest(
                route,
                sessionHint,
                excluded,
                preferCodexAccounts,
                preferDeepSeekAccounts,
                poolContext,
                routingPolicy,
                autoRetestAttempted
            );
        }

        return selection.flatMap(account -> admissionStore.acquire(
                admissionRequest(account, requestId, sessionHint, request, estimatedInput, poolContext, endpointScope))
            .flatMap(decision -> {
                if (decision.accepted() && decision.leaseId() != null) {
                    // Bind after admission so later requests in the same session keep this account
                    // even if the first upstream call has not succeeded yet.
                    if (routingPolicy.bindSessionOnAdmissionEnabled()) {
                        bindSession(sessionHint, account.getAccountId(), routingPolicy, poolContext);
                    }
                    return Mono.just(new AccountAttempt(account, decision.leaseId()));
                }
                if (responseOwnerAccountId != null && !allowResponseOwnerFallback) {
                    return Mono.error(new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "RESPONSE_OWNER_UNAVAILABLE",
                        "The account that owns the OpenAI response has no admission capacity"
                    ));
                }
                List<String> nextRejected = new ArrayList<>(admissionRejectedAccountIds);
                nextRejected.add(account.getAccountId());
                return selectAccountWithAdmission(
                    route,
                    sessionHint,
                    attemptedAccountIds,
                    preferCodexAccounts,
                    preferDeepSeekAccounts,
                    poolContext,
                    routingPolicy,
                    autoRetestAttempted,
                    null,
                    allowResponseOwnerFallback,
                    requestId,
                    request,
                    endpointScope,
                    List.copyOf(nextRejected),
                    estimatedInput
                );
            }));
    }

    private AccountAdmissionStore.AdmissionRequest admissionRequest(
        ProxyAccountEntity account,
        String requestId,
        String sessionHint,
        JsonNode request,
        long estimatedInput,
        AccountPoolRequestContext poolContext,
        String endpointScope
    ) {
        return admissionRequest(
            account, requestId, sessionHint, request, estimatedInput, poolContext, TierDispatch.nativeDispatch(), endpointScope);
    }

    /**
     * The admission of an attempt. A relay's cooldowns and limits are kept by the model of the mapping that serves it and
     * by an endpoint scope of its own, not by the model of the pool context or the path of the client's request (design
     * 4.4, 7.5): every alias of a model then shares the relay's "model not served" answer, and a relay's endpoint is not
     * taken for a native one of the same name.
     */
    private AccountAdmissionStore.AdmissionRequest admissionRequest(
        ProxyAccountEntity account,
        String requestId,
        String sessionHint,
        JsonNode request,
        long estimatedInput,
        AccountPoolRequestContext poolContext,
        TierDispatch dispatch,
        String endpointScope
    ) {
        return new AccountAdmissionStore.AdmissionRequest(
            account.getAccountId(),
            requestId,
            null,
            Hashing.sha256(reliableSessionHint(sessionHint) ? sessionHint : "request:" + requestId),
            AccountPlatforms.isAnthropicApiKey(account),
            estimatedInput,
            estimatedOutputTokens(request),
            Duration.ofMinutes(10),
            dispatch.modelScope() != null ? dispatch.modelScope() : poolContext == null ? null : poolContext.upstreamModel(),
            dispatch.endpointScope() != null ? dispatch.endpointScope() : endpointScope
        );
    }

    private ApiException admissionExhausted() {
        return new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "ACCOUNT_ADMISSION_EXHAUSTED",
            "No upstream account has admission capacity"
        );
    }

    /**
     * The tiered counterpart of {@link #selectAccountWithAdmission}: selects an account of the plan's tiers and takes
     * its admission lease (design 4.3 and 4.4). An account that admission refuses stays out of the rest of the request
     * and counts against its tier, so that a tier which keeps refusing is left to the next one; and the request as a
     * whole stops looking after as many refusals as one untiered dispatch puts up with.
     *
     * @param allowResponseOwnerFallback whether the request may leave the owner of its response chain when that owner
     *     cannot serve it
     */
    Mono<AccountAttempt> selectTieredAccountWithAdmission(
        TierWalk walk,
        String sessionHint,
        AccountDtos.RoutingPolicySettings routingPolicy,
        boolean allowResponseOwnerFallback,
        String requestId,
        JsonNode request,
        String endpointScope
    ) {
        return selectTieredAccountWithAdmission(
            walk,
            sessionHint,
            routingPolicy,
            allowResponseOwnerFallback,
            requestId,
            request,
            endpointScope,
            estimatedInputTokens(request)
        );
    }

    private Mono<AccountAttempt> selectTieredAccountWithAdmission(
        TierWalk walk,
        String sessionHint,
        AccountDtos.RoutingPolicySettings routingPolicy,
        boolean allowResponseOwnerFallback,
        String requestId,
        JsonNode request,
        String endpointScope,
        long estimatedInput
    ) {
        return Mono.defer(() -> {
            if (walk.refusals() >= MAX_ADMISSION_REFUSALS) {
                return Mono.error(admissionExhausted());
            }
            TierPlan plan = walk.plan();
            String ownerAccountId = plan.owner().map(TierPlan.Owner::accountId).orElse(null);
            return selectTiered(walk, sessionHint, allowResponseOwnerFallback)
                .flatMap(selection -> {
                    ProxyAccountEntity account = selection.account();
                    TierDispatch dispatch = selection.dispatch();
                    // A relay is sent the body made for it, which is what its in-flight tokens are counted from.
                    long admittedInput = dispatch.kind() == TierDispatch.Kind.NATIVE
                        ? estimatedInput
                        : estimatedInputTokens(dispatch.wireBody());
                    return admissionStore.acquire(admissionRequest(
                            account, requestId, sessionHint, request, admittedInput, plan.poolContext(), dispatch, endpointScope))
                        .flatMap(decision -> {
                            if (decision.accepted() && decision.leaseId() != null) {
                                // Bind after admission, as an untiered request does, but within the tier: later requests of
                                // the session find this account again only while it is still offered from this tier.
                                if (routingPolicy.bindSessionOnAdmissionEnabled()) {
                                    bindTieredSession(plan, selection.level(), sessionHint, account.getAccountId(), routingPolicy);
                                }
                                return Mono.just(new AccountAttempt(selection, decision.leaseId()));
                            }
                            if (!allowResponseOwnerFallback && account.getAccountId().equals(ownerAccountId)) {
                                return Mono.error(new ApiException(
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    "RESPONSE_OWNER_UNAVAILABLE",
                                    "The account that owns the OpenAI response has no admission capacity"
                                ));
                            }
                            walk.refused(selection.level(), account.getAccountId(),
                                refusedByOwnSession(plan, selection.level(), decision.rejectionReason()));
                            return selectTieredAccountWithAdmission(
                                walk,
                                sessionHint,
                                routingPolicy,
                                allowResponseOwnerFallback,
                                requestId,
                                request,
                                endpointScope,
                                estimatedInput
                            );
                        });
                });
        });
    }

    /**
     * Selects the account of the next dispatch of a tiered request (design 4.3). The owner of the response chain the
     * request continues comes first, if it is available. Then the tiers in order, and in each the cells in the order
     * the route tries them, take the first account that is available and that the request has not tried: a tier or a
     * cell with nothing to offer costs a look at the account cache and nothing more. Only when no tier has anything is
     * the cache reloaded, once, and the tiers walked again; and a request whose session was pushed down to a lower tier
     * then climbs back to a tier above it, as a last resort, rather than fail. If that finds nothing either, the accounts
     * of the tiers the request can still be served from that are out of service are tested once (design 4.10), and the
     * tiers are walked a last time when any of them came back.
     */
    Mono<TierSelection> selectTiered(TierWalk walk, String sessionHint, boolean allowResponseOwnerFallback) {
        return Mono.defer(() -> selectResponseOwner(walk, allowResponseOwnerFallback)
            .switchIfEmpty(Mono.defer(() -> walkTiers(walk, sessionHint)))
            .switchIfEmpty(Mono.defer(() -> accountPoolService.refreshCacheIfStale()
                .then(Mono.defer(() -> walkTiers(walk, sessionHint)))))
            .switchIfEmpty(Mono.defer(() -> climbTiers(walk, sessionHint)))
            .switchIfEmpty(Mono.defer(() -> retestTiers(walk, sessionHint)))
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "NO_AVAILABLE_ACCOUNT",
                "No available upstream account"
            ))));
    }

    /**
     * The owner of the response chain, from the cells of its tier, when the request still has one to prefer. A chain
     * that can be replayed elsewhere goes on without its owner; one that cannot is left unserved, as an untiered request
     * does.
     */
    private Mono<TierSelection> selectResponseOwner(TierWalk walk, boolean allowResponseOwnerFallback) {
        return walk.takeOwner().map(owner -> {
            TierPlan plan = walk.plan();
            Level level = owner.level();
            List<String> excluded = walk.excluded();
            Mono<TierSelection> selected = Flux.fromIterable(walk.cells(level))
                .concatMap(cell -> selectInCell(plan, level, cell, null, cell.predicate(excluded)
                    .and(account -> owner.accountId().equals(account.getAccountId()))
                    .and(account -> plan.holds(level, cell, account))))
                .next();
            return allowResponseOwnerFallback
                ? selected
                : selected.switchIfEmpty(Mono.error(ResponseOwnerUnavailableException::new));
        }).orElseGet(Mono::empty);
    }

    private Mono<TierSelection> walkTiers(TierWalk walk, String sessionHint) {
        TierPlan plan = walk.plan();
        List<String> excluded = walk.excluded();
        return Flux.fromIterable(walk.openLevels())
            .concatMap(level -> Flux.fromIterable(walk.cells(level))
                .concatMap(cell -> selectInCell(plan, level, cell, sessionHint,
                    cell.predicate(excluded).and(account -> plan.holds(level, cell, account))))
                // A tier that has nothing to offer is one the request went through; if a lower tier then serves it, whether
                // that is a downgrade of its session depends on having been through every tier above.
                .switchIfEmpty(Mono.fromRunnable(() -> walk.passed(level))))
            .next()
            .doOnNext(selection -> walk.selected(selection.level()));
    }

    /**
     * The last resort of a request whose session was pushed down to a lower tier: the tiers above its start that the
     * reservation covers, in order, for an account to serve it. Nothing is climbed to by a request that was restricted
     * to one tier, and a tier the reservation does not cover could cost more than was reserved.
     */
    private Mono<TierSelection> climbTiers(TierWalk walk, String sessionHint) {
        TierPlan plan = walk.plan();
        List<String> excluded = walk.excluded();
        return Flux.fromIterable(walk.climbLevels())
            .concatMap(level -> Flux.fromIterable(walk.cells(level))
                .concatMap(cell -> selectInCell(plan, level, cell, sessionHint,
                    cell.predicate(excluded).and(account -> plan.holds(level, cell, account)), true)))
            .next();
    }

    /**
     * The auto retest of a request that found no account (design 4.10), the tiered counterpart of what
     * {@link #selectAccountWithAutoRetest} does for the untiered route: once per request, when a whole round of the tiers
     * is empty, the accounts that were disabled, suspended or out of quota are tested again, as the setting allows, and
     * when one of them is back the tiers are walked once more. Only the accounts the request could be served from are
     * tested ({@link #retestFilter}), not the whole pool of a route.
     */
    private Mono<TierSelection> retestTiers(TierWalk walk, String sessionHint) {
        if (!walk.takeRetest()) {
            return Mono.empty();
        }
        var retest = disabledAccountScheduledTestService.runWhenNoAvailableAccounts(retestFilter(walk));
        if (retest == null) {
            return Mono.empty();
        }
        return retest.doOnNext(response -> log.info(
                "Auto disabled account retest after no available account completed: vendor={}, total={}, success={}, failed={}, skipped={}",
                walk.plan().vendor(),
                response.total(),
                response.success(),
                response.failed(),
                response.skipped()
            ))
            .onErrorResume(retestError -> {
                log.warn("Auto disabled account retest failed after no available account", retestError);
                return Mono.empty();
            })
            .filter(response -> response.success() != null && response.success() > 0)
            .flatMap(response -> walkTiers(walk, sessionHint)
                .switchIfEmpty(Mono.defer(() -> climbTiers(walk, sessionHint))));
    }

    /**
     * The accounts a retest is worth its upstream calls for: those of the cells of the tiers the request can still be
     * served from (the ones its walk offers and the ones it may climb back to) that the tier holds, which is what
     * {@link TierPlan#permits} says about an account the walk selects. A tier the request has left, was restricted
     * away from or has been refused by too often is not tested for it. The accounts of the tiers the plan left out
     * are tested too ({@link TierPlan#leftOut}): this request cannot be served by them, but the tier would never come
     * back if the only retest there is left them out. This replaces {@link #accountFilterFor}, which knows the pool of
     * the untiered route, not its tiers.
     */
    private Predicate<ProxyAccountEntity> retestFilter(TierWalk walk) {
        TierPlan plan = walk.plan();
        List<Level> levels = new ArrayList<>(walk.openLevels());
        levels.addAll(walk.climbLevels());
        // The tiers the plan left out (nothing in them could serve, or every account is disabled) are the ones that most
        // need their accounts tested: without it they never come back. A request restricted to one tier is not tested for
        // the others, and one that left the relays behind has none of theirs tested.
        boolean leftOutToo = !walk.narrowed();
        boolean relaysToo = leftOutToo && !walk.nativeOnly();
        return account -> levels.stream()
            .anyMatch(level -> walk.cells(level).stream().anyMatch(cell -> plan.permits(level, cell, account)))
            || (leftOutToo && plan.leftOut(account))
            || (relaysToo && plan.leftOutRelay(account));
    }

    /**
     * Whether the tier still has an account the request could be sent to: one of the tier's cells that selection can
     * pick right now, which is what the walk would find (cached and enabled, available, and let through by the
     * allocation lifecycle: not disabled, not cooling down or quarantined), that the request has neither tried nor been
     * refused by. {@code failedAccountId} is the account that has just failed and is not yet among the ones tried.
     */
    private Mono<Boolean> hasUntriedAccount(TierWalk walk, Level level, String failedAccountId) {
        TierPlan plan = walk.plan();
        Set<String> excluded = new HashSet<>(walk.excluded());
        excluded.add(failedAccountId);
        return accountPoolService.hasSelectableAccount(account -> !excluded.contains(account.getAccountId())
            && walk.cells(level).stream().anyMatch(cell -> plan.permits(level, cell, account)));
    }

    /** One cell of one tier; empty when it has no account to offer. */
    private Mono<TierSelection> selectInCell(
        TierPlan plan,
        Level level,
        PoolCell cell,
        String sessionHint,
        Predicate<ProxyAccountEntity> filter
    ) {
        return selectInCell(plan, level, cell, sessionHint, filter, false);
    }

    private Mono<TierSelection> selectInCell(
        TierPlan plan,
        Level level,
        PoolCell cell,
        String sessionHint,
        Predicate<ProxyAccountEntity> filter,
        boolean climbed
    ) {
        return accountPoolService.selectInScope(
                sessionHint,
                filter,
                "No available upstream account",
                plan.poolContext(),
                plan.affinityScope(level),
                false
            )
            .map(account -> tierSelection(plan, level, cell, account, climbed))
            .onErrorResume(this::isNoAvailableAccount, error -> Mono.empty());
    }

    /**
     * Writes where in the plan the account was found and checks it against the plan. The check can only fail through a
     * mistake in selection; it fails before the account is dispatched, with the reservation untouched and refunded in
     * full when it is released.
     */
    private TierSelection tierSelection(TierPlan plan, Level level, PoolCell cell, ProxyAccountEntity account, boolean climbed) {
        if (!plan.permits(level, cell, account)) {
            log.error("Tiered selection returned account {} for tier {} cell {}, which the plan does not allow",
                account.getAccountId(), level.tierRef(), cell);
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "SOURCE_TIER_SELECTION_INVALID",
                "The selected account does not belong to the source tier that was chosen for it"
            );
        }
        // A relay is sent what the plan built for it; every other account is sent the request as its route sends it.
        TierDispatch dispatch = cell == PoolCell.XP_OPENAI_CHAT
            ? level.relay(account.getAccountId()).orElseThrow().dispatch()
            : TierDispatch.nativeDispatch();
        return new TierSelection(account, level, cell, dispatch, plan.mode(), climbed);
    }

    private void bindTieredSession(
        TierPlan plan,
        Level level,
        String sessionHint,
        String accountId,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        accountPoolService.commitSessionAffinity(
            plan.poolContext(),
            plan.affinityScope(level),
            sessionHint,
            accountId,
            routingPolicy.bindSessionByApiKeyEnabled()
        );
    }

    /**
     * Serves a request whose plan is tiered, chat-shaped: reserves the most any of the plan's tiers would need, then
     * dispatches over the tiers until an account answers or the retry rules end the request, and charges the price of the
     * tier that answered.
     */
    Mono<ResponseEntity<?>> serveTieredChat(TieredRequest run, ResponseMode responseMode) {
        TierWalk walk = new TierWalk(run.plan());
        TierRetryPolicy retry = new TierRetryPolicy(walk, run.start(), run.requestMovable());
        TieredEndpoint endpoint = new TieredEndpoint(
            account -> callWithAccount(run.route(), account, run.upstreamRequest(), run.upstreamModel(), run.sessionHint()),
            (result, selection) -> Mono.<ResponseEntity<?>>fromSupplier(() -> run.stream()
                ? streamResponse(responseMode, run.responseRequest(), run.upstreamModel(), result)
                : jsonResponse(responseMode, run.responseRequest(), run.upstreamModel(), result)),
            true
        );
        return withBilling(run.principal(), run.requestId(), run.requestedModel(), run.upstreamRequest(), run.fastMode(),
            run.plan(), Mono.defer(() -> attemptTiered(run, walk, retry, endpoint)));
    }

    /**
     * {@link #serveTieredChat} for the Responses endpoint, whose answer is remembered with the account that gave it. The
     * answer of a relay is remembered with no owner (design 4.8): the relay holds none of the chain and speaks no Responses
     * API, and a relay recorded as the owner would have the chain sent to it by a route that knows no tiers, which asks the
     * owner of a chain for it. The next turn is replayed from the history this node holds, on whichever source the tiers
     * choose.
     */
    Mono<ResponseEntity<?>> serveTieredResponses(TieredRequest run) {
        TierWalk walk = new TierWalk(run.plan());
        TierRetryPolicy retry = new TierRetryPolicy(walk, run.start(), run.requestMovable());
        // The history a client replays can hold what a relay answered a turn before. An OpenAI account is not sent the
        // reasoning (it is the gateway's own rendering, which no OpenAI model can read back); the accounts of the other routes
        // (Kiro, Anthropic keys) are sent the custom tool calls as the function calls their conversion can replay, with the
        // argument their own declaration of the tool has (the editing tool is a custom tool call from a relay, and was lost
        // from what they were asked). A vendor without relays is sent its request as it always was.
        JsonNode nativeRequest = !run.plan().vendorHasRelays()
            ? run.upstreamRequest()
            : run.route() == UpstreamRoute.OPENAI
                ? OpenAiResponseFactory.withoutRenderedReasoning(run.upstreamRequest())
                : OpenAiResponseFactory.withCustomCallsAsFunctionCalls(run.upstreamRequest());
        TieredEndpoint endpoint = new TieredEndpoint(
            account -> callResponsesWithAccount(run.route(), account, nativeRequest, run.upstreamModel(), run.sessionHint()),
            (result, selection) -> Mono.defer(() -> {
                ProxyAccountEntity account = selection.account();
                boolean byRelay = selection.dispatch().kind() != TierDispatch.Kind.NATIVE;
                ProxyAccountEntity owner = byRelay ? null : account;
                PlatformCompletionResult answer = byRelay ? responsesAnswerOfRelay(result, run.responseRequest()) : result;
                PreparedResponse response = run.stream()
                    ? prepareResponsesStreamResponse(run.responseRequest(), run.upstreamRequest(), run.upstreamModel(), answer, owner)
                    : prepareResponsesJsonResponse(run.responseRequest(), run.upstreamRequest(), run.upstreamModel(), answer, owner);
                followTierToNextTurn(run, response.responseId());
                return byRelay
                    ? Mono.just(response.response())
                    : rememberResponseOwner(response, account).thenReturn(response.response());
            }),
            true
        );
        return withBilling(run.principal(), run.requestId(), run.requestedModel(), run.upstreamRequest(), run.fastMode(),
            run.plan(), Mono.defer(() -> attemptTiered(run, walk, retry, endpoint)));
    }

    /**
     * What an answer of a relay is rendered as on the Responses endpoint, where {@code incomplete} is an error to the
     * clients that matter: Codex takes {@code response.incomplete} for a failure of the stream and sends the same request
     * again, five more times, each of which a relay answers and is paid for, and it has run the calls of the answer by then,
     * as it runs a call when the call arrives. What reaches this method is an answer that can be told at all: one that the
     * relay's filter stopped, or that the gateway's own bound cut off when it lost a call or has nothing to show, was held
     * back already ({@code XpDispatcher}), and nothing was charged for it.
     *
     * <ul>
     *   <li>An answer the relay broke off at the limit of a client that named none was broken off at the gateway's default
     *       (the bound the reservation is sized from, which the relay is told it may write). That is no limit of the
     *       client's to report, and the native answers, which carry no stop reason (Kiro's), are finished ones: it is
     *       rendered as one, with the text and the calls it came to. An answer that was cut inside a call is no finished
     *       one and is not among these. (Kiro is held to the same bound, and a cut inside a call comes out of it as a finished
     *       answer whose call has the arguments as they stood; the gateway does not make that of a relay's answer.)
     *   <li>An answer that is reported as {@code incomplete} (the limit the client wrote was reached) carries no tool call:
     *       the client may retry it, and a call that was run once must not be run again.
     * </ul>
     *
     * The request log and the metrics keep the stop reason of the relay; this is only how the answer is told.
     */
    static PlatformCompletionResult responsesAnswerOfRelay(PlatformCompletionResult answer, JsonNode clientRequest) {
        PlatformCompletionResult.StopReason stop = answer.stopReason();
        if (stop == PlatformCompletionResult.StopReason.MAX_TOKENS && !namesAnOutputLimit(clientRequest)) {
            return new PlatformCompletionResult(answer.content(), answer.reasoningContent(), answer.toolUses(), answer.usage(),
                null, answer.usageSource(), answer.degraded());
        }
        if ((stop == PlatformCompletionResult.StopReason.MAX_TOKENS || stop == PlatformCompletionResult.StopReason.CONTENT_FILTER)
            && !answer.toolUses().isEmpty()) {
            return new PlatformCompletionResult(answer.content(), answer.reasoningContent(), List.of(), answer.usage(),
                stop, answer.usageSource(), answer.degraded());
        }
        return answer;
    }

    /** Whether the client asked for an output limit of its own, in any of the fields {@link #boundedBillingRequest} reads. */
    private static boolean namesAnOutputLimit(JsonNode request) {
        return request != null && (request.has("max_output_tokens") || request.has("max_completion_tokens") || request.has("max_tokens"));
    }

    /**
     * One dispatch of a tiered request and, when it fails and {@link TierRetryPolicy} allows, the ones after it: what
     * {@link #attemptChat} and {@link #attemptResponses} do for the untiered route, over the tiers of the plan. The
     * account comes from the plan and through admission. A failure is recorded, and the retry policy (not the untiered
     * count of accounts) decides where the request goes next; when it has nowhere to go, the error of the failed
     * attempt is returned, except for a relay's refusal of its own key, which is never told to the client. No circuit
     * probe is taken or cancelled, since a tiered request has no circuit epoch.
     */
    private Mono<ResponseEntity<?>> attemptTiered(TieredRequest run, TierWalk walk, TierRetryPolicy retry, TieredEndpoint endpoint) {
        TierPlan plan = run.plan();
        return selectTieredAccountWithAdmission(
                walk,
                run.sessionHint(),
                run.routingPolicy(),
                run.ownerMayBeLeft(),
                run.requestId(),
                run.upstreamRequest(),
                run.path()
            )
            .flatMap(attempt -> withAdmissionLease(attempt, guardedAttempt -> {
                ProxyAccountEntity account = attempt.account();
                // An upstream client that retries on its own says here that one of its attempts produced output, which the
                // error it finally throws may not (a retry that cannot connect after a stream was cut off).
                AtomicBoolean generationStarted = new AtomicBoolean();
                TierSelection selection = attempt.selection();
                return inFlight(plan, selection.level(), handedToRelay(selection)
                    .then(markDispatched(attempt))
                    .then(markDispatchedTiered(run, selection, retry))
                    .flatMap(lease -> captureUpstreamCall(
                        Mono.defer(() -> holdingLease(run, selection, lease, callTiered(run, endpoint, selection, lease)))
                            .contextWrite(context -> context.put(UpstreamException.GENERATION_STARTED_CONTEXT_KEY, generationStarted)))))
                    .flatMap(outcome -> {
                        if (outcome.failure() == null) {
                            PlatformCompletionResult result = outcome.result();
                            bindTieredSession(plan, attempt.selection().level(), run.sessionHint(), account.getAccountId(), run.routingPolicy());
                            bindTierLevel(plan, walk, attempt.selection());
                            return recordSuccess(
                                run.requestId(),
                                run.principal(),
                                account,
                                run.path(),
                                run.requestedModel(),
                                result,
                                run.route(),
                                run.fastMode(),
                                guardedAttempt,
                                plan.poolContext(),
                                run.start()
                            ).then(Mono.defer(() -> endpoint.respond().apply(result, selection))
                                .map(response -> withTierHeaders(response, attempt.selection().level())));
                        }

                        Throwable error = outcome.failure();
                        // A native dispatch is classified as it always was; a relay's answer by what a relay may say (design 7.8).
                        UpstreamFailureClassifier.Classification classification =
                            UpstreamFailureClassifier.classify(error, account, selection.dispatch());
                        rememberRelayFailure(plan, selection, classification);
                        Mono<Void> failureRecord = recordFailure(
                            run.requestId(),
                            run.principal(),
                            account,
                            run.path(),
                            run.requestedModel(),
                            error,
                            classification,
                            guardedAttempt,
                            plan.poolContext(),
                            run.start()
                        );
                        // A relay that was interrupted may be generating the answer: no other source is asked, whatever else is said.
                        boolean retryable = !classification.interrupted() && classification.retryable()
                            && classification.crossAccountRetryAllowed();
                        // Whether the tier has another account to offer is only of use to a request that goes on at all.
                        Mono<Boolean> accountsLeft = retryable && run.requestMovable()
                            ? hasUntriedAccount(walk, selection.level(), account.getAccountId())
                            : Mono.just(false);
                        return accountsLeft.<ResponseEntity<?>>flatMap(tierHasAccountsLeft -> {
                            TierRetryPolicy.FailedAttempt failed = new TierRetryPolicy.FailedAttempt(
                                selection.level(),
                                account.getAccountId(),
                                error,
                                tierDefinitive(error),
                                retryable,
                                maxDistinctAccounts(
                                    classification, run.route(), run.preferCodexAccounts(), run.preferDeepSeekAccounts(), run.routingPolicy()),
                                generationStarted.get(),
                                tierHasAccountsLeft
                            );
                            // A relay that refused the request itself is not asked again; the request may cross once to a native
                            // account. An interruption is not retried at all, and nothing is rechecked or climbed back to after it.
                            TierRetryPolicy.Outcome next = classification.crossTierOnly()
                                ? retry.onRelayRefusal(failed)
                                : retry.onFailure(failed);
                            // A relay's refusal is told to the client as it is only when the request has nowhere native left to
                            // go: where accounts exist for it that are busy or out of quota for now, the client is told to try
                            // again, as its next request is, and is not made to shorten what an account would take as it is. That
                            // holds whether the request has no tier to cross to or may not cross now (it is too old, its lease
                            // is spent, its budget is): what the identical request is told does not depend on that. A stop for
                            // a failure that may have started a generation or been billed is the failure's, and is returned.
                            boolean tryAgain = classification.crossTierOnly()
                                && plan.retriesAfterRelayRefusal(classification.contextExceeded());
                            // A relay that turned its own key down is a failure of that account, which the operator mends (D5): it
                            // goes on to the next account or tier like a busy one, and the client is never told the relay's 401 or
                            // 403. Where it cannot go on (the budget is spent, the lease is) or nothing is left, it is told what
                            // a request nothing can serve is told; an earlier failure of the request keeps its own answer.
                            boolean keyRefused = classification.keyRefused();
                            if (next == TierRetryPolicy.Outcome.STOP) {
                                return failureRecord.then(Mono.<ResponseEntity<?>>error(keyRefused ? noAvailableAccount() : mapError(error)));
                            }
                            if (next == TierRetryPolicy.Outcome.NOWHERE_TO_CROSS || next == TierRetryPolicy.Outcome.MAY_NOT_CROSS) {
                                return failureRecord.then(Mono.<ResponseEntity<?>>error(tryAgain ? new TryAgainAfterRefusal() : mapError(error)));
                            }
                            // A retry that finds no account, or no admission capacity, returns the error that sent it there.
                            // The 503 of exhausted admission is not a definitive failure: it would send the reservation of a
                            // request that was already dispatched to review. After the refusal of a relay the reservation is
                            // released for good all the same (the relay has the request until a native account is asked, and
                            // none was), so the retry's own error can be returned for a client that is to try again. That
                            // answer is given on purpose, whether the refusal crossed to a native account that was busy or
                            // stopped short of it, and is marked as such so that the attempts before this one keep it.
                            return failureRecord.then(attemptTiered(run, walk, retry, endpoint)
                                .onErrorResume(retryError -> {
                                    if (isTryAgainAfterRefusal(retryError) || !(isNoAvailableAccount(retryError) || isAdmissionExhausted(retryError))) {
                                        return Mono.<ResponseEntity<?>>error(retryError);
                                    }
                                    if (tryAgain) {
                                        return Mono.<ResponseEntity<?>>error(new TryAgainAfterRefusal((ApiException) rootFailure(retryError)));
                                    }
                                    return Mono.<ResponseEntity<?>>error(keyRefused ? retryError : mapError(error));
                                }));
                        });
                    });
            }));
    }

    /**
     * What this node keeps of a relay's failure beyond the request (design 7.8). A relay that broke off an answer is kept
     * from the plans of the same session for a couple of minutes, so that the client's own retry of the 502 is not given to
     * the relay that may still be generating the first answer; a relay that refused a prompt for its length is not sent a
     * prompt that large again. Both only shape the plans of later requests, as the other structural reasons do; neither
     * writes the tier binding of the session, since the tier below that then serves it is not one the session was pushed
     * down to (design 4.7).
     */
    private void rememberRelayFailure(TierPlan plan, TierSelection selection, UpstreamFailureClassifier.Classification classification) {
        if (selection.dispatch().kind() == TierDispatch.Kind.NATIVE) {
            return;
        }
        String accountId = selection.account().getAccountId();
        if (classification.interrupted()) {
            tierRuntime.bindings().excludeRelay(plan.vendor(), plan.relaySession(), accountId);
        } else if (classification.contextExceeded()) {
            selection.level().relay(accountId)
                .ifPresent(relay -> tierRuntime.learnedLimits().learn(relay.mapping(), relay.estimatedInput()));
        }
    }

    /**
     * The response with the headers that name the tier that served it (design 6.4). They were worked out with the plan
     * and are only put in here, after the settlement: building a response must not fail for a request that was charged.
     */
    private static ResponseEntity<?> withTierHeaders(ResponseEntity<?> response, Level served) {
        return ResponseEntity.status(response.getStatusCode())
            .headers(response.getHeaders())
            .headers(headers -> served.responseHeaders().forEach(headers::set))
            .body(response.getBody());
    }

    /**
     * What the request log keeps of the source of an attempt (design 5.7), or null for an attempt of an untiered request,
     * whose row stays what it always was. A native dispatch has nothing to add to the tier, the factor and how the tier
     * was chosen; the other columns describe a source that is reached through a conversion: how it was dispatched, the
     * name the relay was asked for, and, for an attempt that was answered, where its usage came from, why it stopped and
     * what of the request it could not carry. What the body left out is known when it fails too.
     *
     * @param result what the attempt was answered with; null for an attempt that failed
     */
    private static StatsService.SourceColumns sourceColumns(TierSelection tier, PlatformCompletionResult result) {
        return sourceColumns(tier, result, null);
    }

    /**
     * As above, and a relay's failure that the gateway knows the kind of (it may have generated: INTERRUPTED; it refused
     * the converted request: CONVERSION_REJECTED; and the others of {@link UpstreamFailureClassifier.RelayFailure}) is
     * written where an answer writes why it stopped. The status page counts those kinds for the upgrade of a pair
     * (design 7.3, 7.14); a failure of any other kind leaves the column empty.
     *
     * @param failure how the attempt failed; null for an attempt that was answered
     */
    private static StatsService.SourceColumns sourceColumns(
        TierSelection tier,
        PlatformCompletionResult result,
        UpstreamFailureClassifier.Classification failure
    ) {
        if (tier == null) {
            return null;
        }
        Level level = tier.level();
        TierDispatch dispatch = tier.dispatch();
        boolean relay = dispatch.kind() != TierDispatch.Kind.NATIVE;
        return new StatsService.SourceColumns(
            level.tierRef(),
            level.level(),
            level.name(),
            level.factor(),
            tier.mode().name(),
            relay ? dispatch.kind().name() : null,
            relay ? dispatch.wireModel() : null,
            relay && result != null && result.usageSource() != null ? result.usageSource().name() : null,
            relay ? stopped(result, failure) : null,
            relay ? joined(result != null && result.degraded() != null ? result.degraded() : dispatch.degraded()) : null
        );
    }

    private static String stopped(PlatformCompletionResult result, UpstreamFailureClassifier.Classification failure) {
        if (result != null && result.stopReason() != null) {
            return result.stopReason().name();
        }
        return failure != null && failure.relayFailure() != null ? failure.relayFailure().name() : null;
    }

    private static String joined(List<String> names) {
        return names.isEmpty() ? null : String.join(",", names);
    }

    /** The name an attempt goes by in the metadata of its admission: the route, or the kind of relay it was sent to. */
    private static String routeName(UpstreamRoute route, TierSelection tier) {
        return tier != null && tier.dispatch().kind() != TierDispatch.Kind.NATIVE ? tier.dispatch().kind().name() : route.name();
    }

    /**
     * Marks the reservation dispatched to the tier of the attempt and renews its lease for the time the dispatch may
     * take (design 4.6). The retry policy is told what the lease leaves, so that no dispatch is made which could outlive
     * the reservation.
     *
     * @return the lease the dispatch holds; empty for a request without a reservation
     */
    private Mono<Optional<ApiKeyService.DispatchLease>> markDispatchedTiered(
        TieredRequest run,
        TierSelection selection,
        TierRetryPolicy retry
    ) {
        String tierRef = selection.level().tierRef();
        TierDispatch dispatch = selection.dispatch();
        // A relay's dispatch is bounded by a time limit of its own, which its lease has to outlast.
        Mono<ApiKeyService.DispatchLease> marked = dispatch.kind() == TierDispatch.Kind.NATIVE
            ? apiKeyService.markUsageDispatchedTiered(run.principal(), run.requestId(), tierRef)
            : apiKeyService.markUsageDispatchedTiered(run.principal(), run.requestId(), tierRef,
                Duration.ofSeconds(dispatch.member().maxDurationSeconds()));
        return marked
            .doOnNext(lease -> retry.leaseGranted(lease.window(), lease.untilCap()))
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    /**
     * Notes that the request is handed to a relay, when the selected account is one, before anything is marked dispatched
     * for it: a request that is given up or fails from then on is released for good (design 7.8, invariant I7). The note
     * stands until the settlement of the answer has returned or a native account is asked ({@link #callTiered}); nothing
     * is noted for a request without a reservation, which has nothing to release.
     */
    private static Mono<Void> handedToRelay(TierSelection selection) {
        if (selection.dispatch().kind() == TierDispatch.Kind.NATIVE) {
            return Mono.empty();
        }
        return Mono.deferContextual(context -> {
            noteRelayHasRequest(context, true);
            return Mono.empty();
        });
    }

    private static void noteRelayHasRequest(ContextView context, boolean inFlight) {
        if (context.hasKey(XP_IN_FLIGHT)) {
            context.<AtomicBoolean>get(XP_IN_FLIGHT).set(inFlight);
        }
    }

    /**
     * The call that sends the request to the account a tier selected: the upstream client of the route for every account
     * but a relay, whose dispatcher is given the limits the tier reserved for it (design 5.8, 7.5) and the time its lease
     * can still cover (design 4.6). Whatever the dispatcher fails with is already a failure the rest of the request can
     * reason about; this adds none.
     */
    private Mono<PlatformCompletionResult> callTiered(
        TieredRequest run,
        TieredEndpoint endpoint,
        TierSelection selection,
        Optional<ApiKeyService.DispatchLease> lease
    ) {
        TierDispatch dispatch = selection.dispatch();
        if (dispatch.kind() == TierDispatch.Kind.NATIVE) {
            // From here an upstream account has the request, whose billing is not known until it answers: a client that
            // gives up leaves the reservation for review, as it always did, even after a relay was asked before it.
            return Mono.deferContextual(context -> {
                noteRelayHasRequest(context, false);
                return endpoint.call().apply(selection.account());
            });
        }
        TierPlan.Relay relay = selection.level().relay(selection.account().getAccountId()).orElse(null);
        if (!endpoint.relays() || xpDispatcher == null || relay == null) {
            // Not reachable: a plan holds relays only for the entries that have a pair, and only a facade built by Spring
            // meets one. Nothing was sent, so it is a failure of the kind that another source may answer and that is released.
            log.error("A relay was selected for tier {} where no relay can serve the request", selection.level().tierRef());
            return Mono.error(new UpstreamException(502, "The source cannot be asked for this request", true));
        }
        Duration timeLimit = relayTimeLimit(dispatch.member(), lease);
        if (timeLimit == null) {
            // The reservation cannot be leased for much longer: nothing was sent, and another source may answer.
            return Mono.error(new UpstreamException(502, "The source was not asked: too little time is left for the request", true));
        }
        // The relay is charged for no more than it was told it may write, which can be less than the bound the tier reserved
        // for (a request that names a small limit is still reserved for the default one).
        long forwarded = dispatch.forwardedOutputLimit();
        long outputLimit = forwarded > 0 ? Math.min(run.plan().outputBound(), forwarded) : run.plan().outputBound();
        // A Responses request that names no limit of its own (Codex never does) has the default one forced onto it.
        boolean defaultOutputLimit = dispatch.pair().entry() == PairRegistry.Entry.RESPONSES && !namesAnOutputLimit(run.responseRequest());
        return xpDispatcher.dispatch(
            selection.account(),
            dispatch,
            new RelayUsageNormalizer.Limits(relay.inputBound(), outputLimit),
            timeLimit,
            defaultOutputLimit
        );
    }

    /**
     * How long one dispatch to a relay may take (design 4.6): what the member is set to, and no more than the lease of
     * the reservation can still be extended by, less a minute for what follows the answer. Null when that is under 30
     * seconds, which is too little to be worth asking.
     *
     * @param lease the lease the dispatch holds; empty for a request without a reservation, which has no cap
     */
    static Duration relayTimeLimit(SourceTierSnapshot.Member member, Optional<ApiKeyService.DispatchLease> lease) {
        Duration limit = Duration.ofSeconds(member.maxDurationSeconds());
        if (lease.isPresent()) {
            Duration left = lease.get().untilCap().minus(RELAY_LEASE_MARGIN);
            limit = left.compareTo(limit) < 0 ? left : limit;
        }
        return limit.compareTo(RELAY_LEAST_TIME_LIMIT) < 0 ? null : limit;
    }

    /**
     * Keeps the lease on the reservation renewed while a dispatch runs (design 4.6). The lease covers what Kiro allows
     * itself, each of its tries bounded by the request timeout, and what a relay is given, a time limit that its lease was
     * made for; the other upstream clients are only bounded by the time
     * between two reads of the response, so a stream that keeps trickling in can run for longer than any lease. It is
     * not cut off for that: the answer would be thrown away and the reservation left for review for good. Its lease is
     * renewed instead, a quarter of the window at a time, until the hour past which a reservation cannot be leased. A
     * dispatch that goes on beyond that, or whose node stops renewing (a failed renewal is tried again at the next
     * tick, and two in a row still leave a quarter of the window to spare), has its reservation turned into a review by
     * the sweeper; the settlement at the end of the stream clears that review, as it does for an untiered request that
     * runs past its lease. The renewal ends with the dispatch, however it ends.
     */
    private <T> Mono<T> holdingLease(
        TieredRequest run,
        TierSelection selection,
        Optional<ApiKeyService.DispatchLease> lease,
        Mono<T> call
    ) {
        if (lease.isEmpty() || selection.cell() == PoolCell.KIRO_COMPATIBLE
            || selection.dispatch().kind() != TierDispatch.Kind.NATIVE) {
            return call;
        }
        Duration period = lease.get().renewalPeriod();
        long renewals = lease.get().renewalsUntilCap();
        if (renewals <= 0) {
            return call;
        }
        return Mono.defer(() -> {
            Disposable renewing = Flux.interval(period)
                .onBackpressureDrop()
                .take(renewals)
                .concatMap(tick -> Mono.defer(() -> apiKeyService.renewDispatchLease(run.principal(), run.requestId()))
                    .onErrorResume(error -> {
                        log.warn("The billing lease of request {} could not be renewed; it is tried again at the next tick", run.requestId(), error);
                        return Mono.just(true);
                    }))
                // A reservation that is no longer dispatched (a review, a settlement, a release) has nothing to renew.
                .takeWhile(renewed -> renewed)
                .subscribe(renewed -> {}, error -> log.warn("Renewing the billing lease of request {} stopped", run.requestId(), error));
            return call.doFinally(signal -> renewing.dispose());
        });
    }

    /**
     * Counts the dispatch in the session's tier while it runs, so that the refusal of a parallel request of the same
     * session (see {@link #refusedByOwnSession}) can be told from a tier that is busy with other users.
     */
    private <T> Mono<T> inFlight(TierPlan plan, Level level, Mono<T> dispatch) {
        TierPlan.Session session = plan.session().orElse(null);
        if (session == null) {
            return dispatch;
        }
        TierSessionBindings bindings = tierRuntime.bindings();
        return Mono.defer(() -> {
            AtomicBoolean ended = new AtomicBoolean();
            // Ended when the outcome is known, before the rest of the request goes on: a retry of this request is not
            // refused by the attempt it follows.
            Runnable end = () -> {
                if (ended.compareAndSet(false, true)) {
                    bindings.dispatchEnded(session.key(), level.tierRef());
                }
            };
            bindings.dispatchStarted(session.key(), level.tierRef());
            return dispatch.doOnNext(outcome -> end.run()).doOnTerminate(end).doOnCancel(end);
        });
    }

    /**
     * Whether the account refused because of requests of the session itself: it is at its limit of concurrent requests,
     * tokens or sessions and the session has a request being served by this tier. A session that runs requests in
     * parallel fills a small tier by itself, which does not mean the tier is busy with others.
     */
    private boolean refusedByOwnSession(TierPlan plan, Level level, String rejectionReason) {
        TierPlan.Session session = plan.session().orElse(null);
        return session != null
            && rejectionReason != null
            && SESSION_CONCURRENCY_REFUSALS.contains(rejectionReason)
            && tierRuntime.bindings().hasInFlight(session.key(), level.tierRef());
    }

    /**
     * Keeps the session to the tier that served it, when that tier is one it was pushed down to (design 4.7, invariant
     * I8): only a request that went through every tier above its start and found them all without an account, for
     * reasons other than its own budget and its own session's parallel requests, writes anything. A request that
     * climbed back to a better tier as a last resort moves the session up with it, unless what left it nowhere else
     * was the session's own parallel requests. Anything else leaves the session where it is, and only notes that it is
     * still being served.
     *
     * <p>A request that named its tier is a request of the session all the same (design 4.7): it leaves the tier the
     * session is bound to as it is, and notes that the session is being served, so that a session which keeps working
     * with pinned requests is not idle and does not go back to the best tier in the middle of its work. That holds on
     * every endpoint, as does the response chain's own hand-over of the binding ({@link #followTierToNextTurn}).
     */
    private void bindTierLevel(TierPlan plan, TierWalk walk, TierSelection served) {
        TierPlan.Session session = plan.session().orElse(null);
        if (session == null) {
            return;
        }
        TierSessionBindings bindings = tierRuntime.bindings();
        if (!session.keepsTier()) {
            bindings.serve(session.bindingKey());
            return;
        }
        Level level = served.level();
        TierSessionBindings.Binding binding = new TierSessionBindings.Binding(level.tierId(), level.level());
        if (served.climbed() && walk.realClimb()) {
            log.debug("A session of vendor {} climbed back to tier {}", plan.vendor(), level.tierRef());
            bindings.climb(session.bindingKey(), binding);
        } else if (!served.climbed() && walk.realDowngradeTo(level)) {
            log.debug("A session of vendor {} was pushed down to tier {}", plan.vendor(), level.tierRef());
            bindings.downgrade(session.bindingKey(), binding, plan::levelOf);
        } else {
            bindings.serve(session.bindingKey());
        }
    }

    /**
     * The next turn of a response chain names its session by the response it continues, so the node keeps that the response
     * this turn produced belongs to the session of this turn. The next turn is then the same session, and so the same tier
     * (design 4.7) for every model the chain has asked for, and the relays that broke off an answer of it a moment ago
     * (design 7.8) stay out of the plans of its turns, whatever those turns were: it is the retry of this one, and the turns
     * after it, that must not be given to a relay that may still be generating. The untiered path hands the chain on as well
     * ({@link #followChain}) where some vendor has tiers in use, and a turn that was planned in tiers without a session of its
     * own is recorded under the name the request was given before planning.
     */
    private void followTierToNextTurn(TieredRequest run, String responseId) {
        TierPlan plan = run.plan();
        TierPlan.Session session = plan.session().orElse(null);
        // A plan that names no session (the vendor keeps none in a tier and no relay is open to the key) still has the name the
        // chain gave the request: the turn is the chain's all the same.
        String name = session != null ? session.key() : plan.relaySession() != null ? plan.relaySession() : run.chainSession();
        followChain(run.principal(), responseId, name);
    }

    /**
     * Records that the response a turn of a chain produced belongs to the session {@code name}, which is the chain's.
     * Every turn does this, whichever source served it and whether or not any tier planned it: a turn of a vendor without
     * tiers, or of one that keeps no sessions, would otherwise end the chain's name at its response id, and the turn after it
     * would be a new session (a session that fell to a lower tier would go back to the better one without having been idle,
     * and a relay that broke off an answer would be asked again while it may still be generating). Only the node's chain map
     * is written; routing, prices, logs and the bytes sent are the request's own. A deployment where no vendor has tiers in
     * use ({@link TierRuntime.Planner#tiersInUse}) has no session to keep whole, and the untiered path does not call this.
     *
     * @param name the session of this turn; null when it has none (no chain, no name, nothing to tell it by)
     */
    private void followChain(ApiKeyPrincipal principal, String responseId, String name) {
        if (responseId != null && !responseId.isBlank() && name != null) {
            tierRuntime.bindings().follow(TierSessionKeys.ofResponse(principal.keyId(), responseId), name);
        }
    }

    /**
     * What a client is answered when a relay refused its request and the accounts that would take it are only unavailable for
     * now (design 7.8): the answer of a selection that found no account, which is what clients retry and what a pinned request
     * is told as SOURCE_TIER_UNAVAILABLE. It is a type of its own so that an earlier attempt, to whose error a retry that finds
     * no account gives way, does not take the place of an answer that was given on purpose.
     */
    private static final class TryAgainAfterRefusal extends ApiException {
        TryAgainAfterRefusal() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available upstream account");
        }

        /** The answer of a retry that found no account, which is told as that retry told it. */
        TryAgainAfterRefusal(ApiException retry) {
            super(retry.getStatus(), retry.getCode(), retry.getMessage(), retry.getDetails());
        }
    }

    private boolean isTryAgainAfterRefusal(Throwable error) {
        return rootFailure(error) instanceof TryAgainAfterRefusal;
    }

    /** What a request is answered when no account can be asked for it: the selection's own error, as a failure of a request puts it. */
    private static ApiException noAvailableAccount() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available upstream account");
    }

    private boolean isAdmissionExhausted(Throwable error) {
        Throwable failure = rootFailure(error);
        return failure instanceof ApiException api && "ACCOUNT_ADMISSION_EXHAUSTED".equals(api.getCode());
    }

    private Mono<Void> markDispatched(AccountAttempt attempt) {
        return admissionStore.markDispatched(attempt.leaseId())
            .onErrorResume(error -> admissionStore.releaseUnsent(attempt.leaseId())
                .doOnSuccess(ignored -> attempt.markLeaseFinalized())
                .onErrorResume(releaseError -> {
                    error.addSuppressed(releaseError);
                    return Mono.empty();
                })
                .then(Mono.error(error)));
    }

    private <T> Mono<T> withAdmissionLease(
        AccountAttempt attempt,
        Function<AccountAttempt, Mono<T>> operation
    ) {
        return Mono.usingWhen(
            Mono.just(attempt),
            operation,
            completed -> abandonOpenLease(completed, "PIPELINE_COMPLETED_WITHOUT_RECONCILIATION"),
            (failed, error) -> abandonOpenLease(failed, "PIPELINE_ERROR"),
            cancelled -> abandonOpenLease(cancelled, "CLIENT_CANCELLED")
        );
    }

    private Mono<Void> reconcileAttempt(
        String operation,
        AccountAttempt attempt,
        AccountAdmissionStore.AdmissionResult result
    ) {
        return admissionStore.reconcile(result)
            .doOnSuccess(ignored -> attempt.markLeaseFinalized())
            .onErrorResume(error -> {
                log.warn("Could not {} after proxy completion", operation, error);
                return abandonOpenLease(attempt, "RECONCILIATION_FAILED");
            });
    }

    private Mono<Void> abandonOpenLease(AccountAttempt attempt, String reason) {
        if (attempt == null || attempt.leaseFinalized()) {
            return Mono.empty();
        }
        return admissionStore.abandon(attempt.leaseId(), reason)
            .doOnSuccess(ignored -> attempt.markLeaseFinalized())
            .onErrorResume(error -> {
                log.warn("Could not abandon account admission lease {} reason={}", attempt.leaseId(), reason, error);
                return Mono.empty();
            });
    }

    private List<String> mergedAccountIds(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        if (first != null) {
            first.stream().filter(id -> id != null && !id.isBlank()).forEach(merged::add);
        }
        if (second != null) {
            second.stream().filter(id -> id != null && !id.isBlank() && !merged.contains(id)).forEach(merged::add);
        }
        return List.copyOf(merged);
    }

    private long estimatedInputTokens(JsonNode request) {
        if (request == null) {
            return 0;
        }
        return Math.max(1, Math.min(1_000_000, (JsonNodes.charLength(request) + 3L) / 4L));
    }

    private long estimatedOutputTokens(JsonNode request) {
        long configured = request == null ? 0 : Math.max(
            request.path("max_output_tokens").asLong(0),
            request.path("max_tokens").asLong(0)
        );
        return Math.max(1, Math.min(1_000_000, configured > 0 ? configured : 4096));
    }

    private boolean shouldAutoRetestUnavailableAccounts(Throwable error, AtomicBoolean autoRetestAttempted) {
        return isNoAvailableAccount(error)
            && autoRetestAttempted != null
            && autoRetestAttempted.compareAndSet(false, true);
    }

    private java.util.function.Predicate<ProxyAccountEntity> accountFilterFor(
        UpstreamRoute route,
        boolean preferDeepSeekAccounts,
        boolean preferCodexAccounts,
        AccountDtos.RoutingPolicySettings routingPolicy,
        AccountPoolRequestContext poolContext
    ) {
        if (route == UpstreamRoute.OPENAI && preferDeepSeekAccounts) {
            return AccountPlatforms::isDeepSeek;
        }
        if (route == UpstreamRoute.OPENAI
            && poolContext != null
            && isOpenAiCompatibleModel(poolContext.upstreamModel())) {
            // The exclusive relay of another protocol is not part of this pool (design 7.5), so it is not tested for it either.
            return account -> AccountPlatforms.isOpenAiCompatibleApiKey(account) && !accountPoolService.isExclusiveRelay(account);
        }
        if (preferCodexAccounts) {
            return AccountPlatforms::isCodex;
        }
        return switch (route) {
            case OPENAI -> routingPolicy.isolateCodexPoolEnabled()
                ? AccountPlatforms::isOfficialOpenAiApiKey
                : account -> AccountPlatforms.isOfficialOpenAiApiKey(account) || AccountPlatforms.isCodex(account);
            case ANTHROPIC -> account -> AccountPlatforms.isAnthropicApiKey(account)
                || AccountPlatforms.isKiroCompatible(account);
            case KIRO -> AccountPlatforms::isKiroCompatible;
        };
    }

    private Mono<PlatformCompletionResult> callWithAccount(
        UpstreamRoute route,
        ProxyAccountEntity account,
        JsonNode request,
        String mappedModel,
        String sessionHint
    ) {
        if (route == UpstreamRoute.OPENAI) {
            return openAiUpstreamClient.complete(account, request, mappedModel);
        }
        if (route == UpstreamRoute.ANTHROPIC && AccountPlatforms.isAnthropicApiKey(account)) {
            return anthropicUpstreamClient.complete(account, request, mappedModel);
        }
        String kiroModel = route == UpstreamRoute.ANTHROPIC ? stripAnthropicPrefix(mappedModel) : mappedModel;
        ObjectNode payload = payloadFactory.fromOpenAiChat(request, account, kiroModel, "AI_EDITOR", sessionHint);
        return upstreamClient.complete(account, payload, kiroModel);
    }

    private Mono<PlatformCompletionResult> callResponsesWithAccount(
        UpstreamRoute route,
        ProxyAccountEntity account,
        JsonNode request,
        String mappedModel,
        String sessionHint
    ) {
        if (route == UpstreamRoute.OPENAI) {
            return openAiUpstreamClient.completeResponses(account, request, mappedModel);
        }
        ObjectNode chat = openAiRequestAdapter.responsesToChat(request);
        if (route == UpstreamRoute.ANTHROPIC && AccountPlatforms.isAnthropicApiKey(account)) {
            return anthropicUpstreamClient.complete(account, chat, mappedModel);
        }
        String kiroModel = route == UpstreamRoute.ANTHROPIC ? stripAnthropicPrefix(mappedModel) : mappedModel;
        ObjectNode payload = payloadFactory.fromOpenAiChat(chat, account, kiroModel, "AI_EDITOR", sessionHint);
        return upstreamClient.complete(account, payload, kiroModel);
    }

    private Mono<UpstreamCallOutcome> captureUpstreamCall(Mono<PlatformCompletionResult> upstreamCall) {
        return upstreamCall
            .map(result -> new UpstreamCallOutcome(result, null))
            .onErrorResume(error -> Mono.just(new UpstreamCallOutcome(null, error)));
    }

    private UpstreamRoute routeFor(String model, ResponseMode responseMode) {
        String normalized = routeModel(model);
        if (normalized.startsWith("anthropic/") || normalized.startsWith("anthropic-compatible/")) {
            return UpstreamRoute.ANTHROPIC;
        }
        if (responseMode == ResponseMode.ANTHROPIC_MESSAGES) {
            return UpstreamRoute.ANTHROPIC;
        }
        if (normalized.startsWith("openai/")
            || normalized.startsWith("openai-compatible/")
            || normalized.startsWith("deepseek")
            || normalized.startsWith("gpt")
            || normalized.startsWith("chatgpt")
            || normalized.matches("o\\d.*")
            || isOpenAiCompatibleModel(normalized)) {
            return UpstreamRoute.OPENAI;
        }
        return UpstreamRoute.KIRO;
    }

    private boolean isDeepSeekRouteModel(UpstreamRoute route, String model) {
        String normalized = routeModel(model);
        return route == UpstreamRoute.OPENAI
            && !normalized.startsWith("openai-compatible/")
            && DeepSeekModelCatalog.canonicalModelId(normalized).startsWith("deepseek-");
    }

    private String stripAnthropicPrefix(String model) {
        if (model == null) {
            return null;
        }
        String trimmed = model.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("anthropic/")) {
            return trimmed.substring("anthropic/".length());
        }
        if (normalized.startsWith("anthropic-compatible/")) {
            return trimmed.substring("anthropic-compatible/".length());
        }
        return trimmed;
    }

    private String routeModel(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        return normalized.startsWith("starry/") ? normalized.substring("starry/".length()) : normalized;
    }

    private boolean isCodexResponsesClient(ServerWebExchange exchange) {
        return isCodexClientHeader(exchange.getRequest().getHeaders().getFirst("User-Agent"))
            || isCodexClientHeader(exchange.getRequest().getHeaders().getFirst("originator"));
    }

    private boolean isCodexClientHeader(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("codex_")
            || normalized.startsWith("codex ")
            || normalized.contains("codex_cli_rs/")
            || normalized.contains("codex_vscode/")
            || normalized.contains("codex_app/")
            || normalized.contains("codex_chatgpt_desktop/")
            || normalized.contains("codex_atlas/")
            || normalized.contains("codex_exec/")
            || normalized.contains("codex_sdk_ts/");
    }

    private boolean isCodexOnlyResponsesModel(String model) {
        return CodexOnlyModels.isCodexOnly(model);
    }

    private boolean isFastRequested(JsonNode request, String requestedModel, String mappedModel) {
        return isPriorityTier(request.path("service_tier").asString(null))
            || isPriorityTier(request.path("serviceTier").asString(null))
            || request.path("fast").asBoolean(false)
            || request.path("fast_mode").asBoolean(false)
            || isFastOpenAiModel(requestedModel)
            || isFastOpenAiModel(mappedModel);
    }

    private boolean isPriorityTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return false;
        }
        String normalized = tier.trim().toLowerCase();
        return "priority".equals(normalized) || "fast".equals(normalized);
    }

    private boolean isFastOpenAiModel(String model) {
        String normalized = routeModel(model);
        if (normalized.startsWith("openai/")) {
            normalized = normalized.substring("openai/".length());
        }
        return normalized.endsWith("-fast")
            && (normalized.startsWith("gpt") || normalized.startsWith("chatgpt") || normalized.matches("o\\d.*"));
    }

    private String upstreamModelFor(String model, boolean requestedFastMode) {
        String normalized = stripStarryPrefix(model);
        return requestedFastMode && isFastOpenAiModel(normalized) ? stripFastSuffix(normalized) : normalized;
    }

    private String stripStarryPrefix(String model) {
        if (model == null) {
            return null;
        }
        String trimmed = model.trim();
        return trimmed.toLowerCase().startsWith("starry/") ? trimmed.substring("starry/".length()) : trimmed;
    }

    private String stripFastSuffix(String model) {
        if (model == null) {
            return null;
        }
        return model.toLowerCase().endsWith("-fast") ? model.substring(0, model.length() - "-fast".length()) : model;
    }

    private JsonNode openAiFastRequest(JsonNode request) {
        if (request instanceof ObjectNode object) {
            ObjectNode copy = JsonNodes.shallowCopy(object);
            copy.put("service_tier", "priority");
            copy.remove("serviceTier");
            copy.remove("fast");
            copy.remove("fast_mode");
            return copy;
        }
        return request;
    }

    private JsonNode deepSeekRequest(JsonNode request) {
        if (request instanceof ObjectNode object) {
            ObjectNode copy = JsonNodes.shallowCopy(object);
            copy.remove(List.of("service_tier", "serviceTier", "fast", "fast_mode"));
            return copy;
        }
        return request;
    }

    private String providerHint(UpstreamRoute route, String upstreamModel, boolean deepSeekRoute) {
        return deepSeekRoute
            ? AccountPlatforms.DEEPSEEK_PROVIDER
            : route == UpstreamRoute.OPENAI && isOpenAiCompatibleModel(upstreamModel)
                ? AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER
            : switch (route) {
                case OPENAI -> AccountPlatforms.OPENAI_PROVIDER;
                case ANTHROPIC -> AccountPlatforms.ANTHROPIC_PROVIDER;
                case KIRO -> "Kiro";
            };
    }

    private AccountPoolRequestContext poolContext(
        String requestId,
        String requestedModel,
        String upstreamModel,
        UpstreamRoute route,
        boolean deepSeekRoute
    ) {
        String providerHint = providerHint(route, upstreamModel, deepSeekRoute);
        UUID circuitEpoch = accountPoolService.captureCircuitEpoch();
        return new AccountPoolRequestContext(
            requestId,
            requestedModel,
            upstreamModel,
            providerHint,
            circuitEpoch,
            accountPoolService.captureCircuitPool(
                requestedModel,
                upstreamModel,
                providerHint,
                circuitEpoch
            )
        );
    }

    private Mono<ApiKeyPrincipal> authenticateAndLimit(ServerWebExchange exchange) {
        return apiKeyService.authenticate(exchange)
            .flatMap(principal -> {
                RateLimiterService.RateLimitResult limit = rateLimiterService.check(principal.keyId());
                if (!limit.allowed()) {
                    exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(limit.retryAfterMillis() / 1000));
                    return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Rate limit exceeded"));
                }
                return principal.requiresBalance()
                    ? apiKeyService.ensureBalanceAvailable(principal).thenReturn(principal)
                    : Mono.just(principal);
            });
    }

    private Mono<Void> recordSuccess(
        String requestId,
        ApiKeyPrincipal principal,
        ProxyAccountEntity account,
        String path,
        String model,
        PlatformCompletionResult result,
        UpstreamRoute route,
        boolean fastMode,
        AccountAttempt attempt,
        AccountPoolRequestContext poolContext,
        Instant start
    ) {
        PlatformUsage usage = result.usage();
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        long attemptLatencyMs = Duration.between(attempt.startedAt(), Instant.now()).toMillis();
        Mono<Void> admissionReconciliation = reconcileAttempt(
            "reconcile successful account admission",
            attempt,
            new AccountAdmissionStore.AdmissionResult(
                attempt.leaseId(),
                true,
                usage.inputTokens(),
                usage.outputTokens(),
                attemptLatencyMs,
                200,
                AccountFailureScope.UNKNOWN,
                Duration.ZERO,
                false,
                Map.of("model", model, "route", routeName(route, attempt.selection()))
            )
        );
        long circuitOutcomeId = accountPoolService.recordCircuitSuccess(poolContext, account);
        AtomicBoolean circuitOutcomeResolved = new AtomicBoolean(circuitOutcomeId <= 0);
        // A tiered attempt is charged the price of the tier it was selected from and settled under that tier; there is no
        // other price to fall back to, so a context without the plan's billing fails the settlement.
        TierSelection tier = attempt.selection();
        Mono<BigDecimal> billedCredits = "anonymous".equals(principal.keyId())
            ? Mono.just(BigDecimal.ZERO)
            : tier != null
                ? Mono.deferContextual(context -> {
                    TierBilling billing = context.<TierBilling>get(TierBilling.class);
                    // A relay's usage is what the relay said, and is never charged above what its tier reserved.
                    return Mono.just(tier.dispatch().kind() == TierDispatch.Kind.NATIVE
                        ? billing.cost(tier.level(), usage)
                        : billing.relayCost(tier.level(), usage));
                })
                : Mono.deferContextual(context -> context.hasKey(tech.starryapi.model.BillingQuote.class)
                    ? Mono.just(context.get(tech.starryapi.model.BillingQuote.class).cost(usage))
                    : paymentSettingsService.config().flatMap(config -> modelPricingService.cost(model, usage, fastMode, config.apiBillingMultiplier())));
        Mono<Void> completion = billedCredits
            .flatMap(credits -> {
                Mono<Void> settlement = tier == null
                    ? apiKeyService.accountUsage(
                        principal,
                        requestId,
                        credits,
                        usage.inputTokens(),
                        usage.outputTokens()
                    )
                    : apiKeyService.accountUsageServed(
                        principal,
                        requestId,
                        credits,
                        usage.inputTokens(),
                        usage.outputTokens(),
                        tier.level().tierRef(),
                        tier.level().level(),
                        tier.level().factor()
                    );
                Mono<Void> keyAccounting = settlement.then(Mono.deferContextual(context -> {
                    if (context.hasKey(BILLING_SETTLED)) {
                        context.<AtomicBoolean>get(BILLING_SETTLED).set(true);
                    }
                    // The settlement has returned: nothing is left of a relay's attempt to release.
                    noteRelayHasRequest(context, false);
                    return Mono.<Void>empty();
                }));
                return keyAccounting
                    .onErrorResume(error -> recordAccountingFailure(
                        requestId,
                        principal,
                        account,
                        path,
                        model,
                        usage,
                        credits,
                        error,
                        circuitOutcomeId,
                        circuitOutcomeResolved,
                        poolContext,
                        start,
                        tier
                    )
                        .then(bestEffort("record upstream account success", Mono.defer(
                            () -> accountPoolService.recordSuccess(
                                account.getAccountId(),
                                usage.totalTokens(),
                                poolContext
                            )
                        )))
                        .then(Mono.error(error)))
                    .then(Mono.whenDelayError(
                        bestEffort("record upstream account success", Mono.defer(
                            () -> accountPoolService.recordSuccess(
                                account.getAccountId(),
                                usage.totalTokens(),
                                poolContext
                            )
                        )),
                        bestEffort("write successful request log", Mono.defer(() -> trackCircuitLog(
                            statsService.log(new StatsService.RequestLog(
                                requestId,
                                principal.keyId(),
                                principal.userId(),
                                account.getAccountId(),
                                path,
                                model,
                                200,
                                true,
                                usage.inputTokens(),
                                usage.outputTokens(),
                                credits,
                                latencyMs,
                                null,
                                accountPoolService.circuitKey(poolContext),
                                accountPoolService.circuitTier(account, poolContext),
                                poolContext.upstreamModel(),
                                true,
                                accountPoolService.circuitEpoch(poolContext)
                            ).withSource(sourceColumns(tier, result))),
                            poolContext,
                            circuitOutcomeId,
                            circuitOutcomeResolved
                        )))
                    ).then());
            });
        return Mono.whenDelayError(admissionReconciliation, completion)
            .then()
            .doOnError(error -> markCircuitOutcomePersistenceFailed(
                poolContext,
                circuitOutcomeId,
                circuitOutcomeResolved
            ))
            .doOnCancel(() -> markCircuitOutcomePersistenceFailed(
                poolContext,
                circuitOutcomeId,
                circuitOutcomeResolved
            ));
    }

    private Mono<Void> recordAccountingFailure(
        String requestId,
        ApiKeyPrincipal principal,
        ProxyAccountEntity account,
        String path,
        String model,
        PlatformUsage usage,
        BigDecimal credits,
        Throwable error,
        long circuitOutcomeId,
        AtomicBoolean circuitOutcomeResolved,
        AccountPoolRequestContext poolContext,
        Instant start,
        TierSelection tier
    ) {
        Throwable failure = rootFailure(error);
        int statusCode = failure instanceof ApiException api
            ? api.getStatus().value()
            : HttpStatus.INTERNAL_SERVER_ERROR.value();
        return bestEffort("write rejected usage settlement log", Mono.defer(() -> trackCircuitLog(
            statsService.log(new StatsService.RequestLog(
                requestId,
                principal.keyId(),
                principal.userId(),
                account.getAccountId(),
                path,
                model,
                statusCode,
                false,
                usage.inputTokens(),
                usage.outputTokens(),
                credits,
                Duration.between(start, Instant.now()).toMillis(),
                safeFailureMessage(failure),
                accountPoolService.circuitKey(poolContext),
                accountPoolService.circuitTier(account, poolContext),
                poolContext.upstreamModel(),
                true,
                accountPoolService.circuitEpoch(poolContext)
            ).withSource(sourceColumns(tier, null))),
            poolContext,
            circuitOutcomeId,
            circuitOutcomeResolved
        )));
    }

    private Mono<Void> trackCircuitLog(
        Mono<Void> logTask,
        AccountPoolRequestContext poolContext,
        long circuitOutcomeId,
        AtomicBoolean circuitOutcomeResolved
    ) {
        if (circuitOutcomeId <= 0) {
            return logTask;
        }
        return logTask
            .doOnSuccess(ignored -> acknowledgeCircuitOutcome(
                poolContext,
                circuitOutcomeId,
                circuitOutcomeResolved
            ))
            .doOnError(error -> markCircuitOutcomePersistenceFailed(
                poolContext,
                circuitOutcomeId,
                circuitOutcomeResolved
            ))
            .doOnCancel(() -> markCircuitOutcomePersistenceFailed(
                poolContext,
                circuitOutcomeId,
                circuitOutcomeResolved
            ));
    }

    private void acknowledgeCircuitOutcome(
        AccountPoolRequestContext poolContext,
        long circuitOutcomeId,
        AtomicBoolean resolved
    ) {
        if (resolved.compareAndSet(false, true)) {
            accountPoolService.acknowledgeCircuitOutcome(poolContext, circuitOutcomeId);
        }
    }

    private void markCircuitOutcomePersistenceFailed(
        AccountPoolRequestContext poolContext,
        long circuitOutcomeId,
        AtomicBoolean resolved
    ) {
        if (resolved.compareAndSet(false, true)) {
            accountPoolService.markCircuitOutcomePersistenceFailed(poolContext, circuitOutcomeId);
        }
    }

    private Mono<Void> bestEffort(String operation, Mono<Void> task) {
        return task.onErrorResume(error -> {
            log.warn("Could not {} after proxy completion", operation, error);
            return Mono.empty();
        });
    }

    private Mono<Void> recordFailure(
        String requestId,
        ApiKeyPrincipal principal,
        ProxyAccountEntity account,
        String path,
        String model,
        Throwable error,
        UpstreamFailureClassifier.Classification classification,
        AccountAttempt attempt,
        AccountPoolRequestContext poolContext,
        Instant start
    ) {
        Throwable failure = classification.failure();
        Integer statusCode = classification.statusCode();
        AccountPoolService.UpstreamErrorType type = classification.type();
        String failureMessage = safeFailureMessage(failure);
        long circuitOutcomeId;
        if (classification.circuitFailure()) {
            circuitOutcomeId = accountPoolService.recordCircuitFailure(poolContext, account);
        } else {
            accountPoolService.cancelCircuitProbe(poolContext);
            circuitOutcomeId = 0;
        }
        log.warn(
            "Proxy upstream request failed path={} model={} accountId={} provider={} authMethod={} status={} error={}",
            path,
            model,
            account.getAccountId(),
            account.getProvider(),
            account.getAuthMethod(),
            statusCode,
            failureMessage
        );
        Mono<AccountPoolService.RequestFailureRecord> failureUpdate;
        if (!updatesAccountWideHealth(classification.scope())) {
            failureUpdate = Mono.empty();
        } else if (AccountPlatforms.isDeepSeek(account)) {
            failureUpdate = accountPoolService.recordRequestFailure(
                account.getAccountId(),
                type,
                statusCode,
                failureMessage,
                false,
                poolContext
            );
        } else {
            failureUpdate = accountPoolService.recordRequestFailure(
                account.getAccountId(),
                type,
                statusCode,
                failureMessage,
                poolContext
            );
        }
        Mono<Void> accountStats = bestEffort("record upstream account failure", failureUpdate
            .doOnNext(accountAutoSuspendService::scheduleIfNeeded)
            .then());
        // The cooldown of a model or an endpoint is kept under the keys the attempt was admitted by: a relay's are the
        // mapping's model and the relay's own endpoint, not the model of the pool context and the path of the client.
        TierDispatch dispatch = attempt.selection() == null ? TierDispatch.nativeDispatch() : attempt.selection().dispatch();
        // A relay whose answer the gateway held back did what it was asked: the admission store is told of an answer, which
        // lets its error rate decay, and not of a failure of the account, which would raise it and could cool the relay down
        // for every session (the request itself is the failure, and is logged as one below).
        boolean answered = classification.withheld();
        Mono<Void> admissionReconciliation = reconcileAttempt(
            "reconcile failed account admission",
            attempt,
            new AccountAdmissionStore.AdmissionResult(
                attempt.leaseId(),
                answered,
                0,
                0,
                Duration.between(attempt.startedAt(), Instant.now()).toMillis(),
                answered ? Integer.valueOf(200) : statusCode,
                answered ? AccountFailureScope.UNKNOWN : classification.scope(),
                classification.cooldown(),
                classification.permanentCredentialFailure(),
                Map.of(
                    "message", failureMessage,
                    "model", model,
                    "upstreamModel", dispatch.modelScope() != null ? dispatch.modelScope() : poolContext.upstreamModel(),
                    "endpoint", dispatch.endpointScope() != null ? dispatch.endpointScope() : path,
                    "provider", String.valueOf(account.getProvider()),
                    "cooldownSeconds", classification.cooldown().toSeconds(),
                    "retryAfterSeconds", UpstreamException.retryAfterSeconds(classification.failure())
                )
            )
        );
        AtomicBoolean circuitOutcomeResolved = new AtomicBoolean(circuitOutcomeId <= 0);
        Mono<Void> log = bestEffort("write failed request log", Mono.defer(() -> trackCircuitLog(
            statsService.log(new StatsService.RequestLog(
                requestId,
                principal.keyId(),
                principal.userId(),
                account.getAccountId(),
                path,
                model,
                statusCode == null ? 500 : statusCode,
                false,
                0,
                0,
                BigDecimal.ZERO,
                Duration.between(start, Instant.now()).toMillis(),
                failureMessage,
                accountPoolService.circuitKey(poolContext),
                accountPoolService.circuitTier(account, poolContext),
                poolContext.upstreamModel(),
                classification.circuitFailure() ? false : null,
                accountPoolService.circuitEpoch(poolContext)
            ).withSource(sourceColumns(attempt.selection(), null, classification))),
            poolContext,
            circuitOutcomeId,
            circuitOutcomeResolved
        )));
        return Mono.deferContextual(context -> {
            // A later request rejection cannot prove that an earlier timed-out retry was free.
            boolean definitive = attempt.selection() != null ? tierDefinitive(error) : definitiveBillingFailure(error);
            if (context.hasKey(BILLING_UNCERTAINTY) && !definitive) {
                context.<AtomicBoolean>get(BILLING_UNCERTAINTY).set(true);
            }
            return Mono.whenDelayError(admissionReconciliation, accountStats, log).then();
        });
    }

    private boolean updatesAccountWideHealth(AccountFailureScope scope) {
        return scope != AccountFailureScope.REQUEST
            && scope != AccountFailureScope.CAPACITY_MODEL
            && scope != AccountFailureScope.CAPACITY_ENDPOINT;
    }

    private List<String> attemptedWith(ProxyAccountEntity account, List<String> attemptedAccountIds) {
        String accountId = account.getAccountId();
        if (accountId == null || accountId.isBlank() || attemptedAccountIds.contains(accountId)) {
            return attemptedAccountIds;
        }
        List<String> next = new ArrayList<>(attemptedAccountIds);
        next.add(accountId);
        return List.copyOf(next);
    }

    /**
     * Cross-account retry only applies after dispatch. Admission rejects try another account
     * without counting as a retry. Codex defaults to one account so a 429 stays on that token.
     */
    private boolean shouldRetryWithAnotherAccount(
        UpstreamFailureClassifier.Classification classification,
        List<String> attemptedAccountIds,
        List<String> nextAttemptedAccountIds,
        boolean requestMovable,
        UpstreamRoute route,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        int maxDistinctAccounts = maxDistinctAccounts(
            classification, route, preferCodexAccounts, preferDeepSeekAccounts, routingPolicy);
        return nextAttemptedAccountIds.size() > attemptedAccountIds.size()
            && nextAttemptedAccountIds.size() < maxDistinctAccounts
            && requestMovable
            && classification.retryable()
            && classification.crossAccountRetryAllowed();
    }

    /** How many different accounts a request may be dispatched to after a failure of this kind on this route. */
    private int maxDistinctAccounts(
        UpstreamFailureClassifier.Classification classification,
        UpstreamRoute route,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        int scopeLimit = switch (classification.scope()) {
            case TRANSPORT, PROXY_EDGE, REGIONAL_POLICY, UNKNOWN, CREDENTIAL -> 2;
            default -> Integer.valueOf(529).equals(classification.statusCode()) ? 2 : 3;
        };
        int routeLimit = routingPolicy.maxRetryAccounts(preferDeepSeekAccounts, preferCodexAccounts, route.name());
        return Math.min(scopeLimit, routeLimit);
    }

    private boolean isResponseOwnerUnavailable(Throwable error) {
        Throwable failure = rootFailure(error);
        return failure instanceof ResponseOwnerUnavailableException;
    }

    private boolean isNoAvailableAccount(Throwable error) {
        Throwable failure = rootFailure(error);
        return failure instanceof ApiException api && "NO_AVAILABLE_ACCOUNT".equals(api.getCode());
    }

    private boolean isSubtypePoolUnavailable(Throwable error) {
        Throwable failure = rootFailure(error);
        return failure instanceof ApiException api
            && ("NO_AVAILABLE_ACCOUNT".equals(api.getCode())
                || "ACCOUNT_POOL_CIRCUIT_OPEN".equals(api.getCode()));
    }

    private Throwable mapError(Throwable error) {
        Throwable failure = rootFailure(error);
        return switch (failure) {
            case ApiException api -> api;
            case UpstreamException upstream -> {
                HttpStatus status = mapUpstreamStatusCode(upstream.getStatusCode());
                // The words clients look for to shorten what they send; the relay's own are not passed on (design 7.8, 7.11).
                String message = RelayErrorException.contextExceeded(upstream)
                    ? RelayErrorException.PROMPT_TOO_LONG
                    : safeFailureMessage(upstream);
                yield new ApiException(status, "UPSTREAM_ERROR", message).initCause(upstream);
            }
            // Keep the egress failure as the cause: whether the request was sent decides how it is billed.
            case tech.starryapi.egress.EgressException egress ->
                new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", safeFailureMessage(egress)).initCause(egress);
            default -> new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", safeFailureMessage(failure)).initCause(failure);
        };
    }

    private String safeFailureMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure == null ? "Unknown upstream error" : failure.getClass().getSimpleName();
        }
        String singleLine = message.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 800 ? singleLine : singleLine.substring(0, 800) + "...";
    }

    private HttpStatus mapUpstreamStatusCode(int upstreamStatusCode) {
        return switch (upstreamStatusCode) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 402, 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 408 -> HttpStatus.REQUEST_TIMEOUT;
            case 422 -> HttpStatus.UNPROCESSABLE_CONTENT;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> upstreamStatusCode >= 500 && upstreamStatusCode < 600
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.BAD_REQUEST;
        };
    }

    private Throwable rootFailure(Throwable error) {
        // Stops at an egress failure: its cause is a transport detail that must not reach clients or classification.
        return tech.starryapi.egress.EgressException.rootCause(error);
    }

    private ResponseEntity<?> jsonResponse(ResponseMode mode, JsonNode request, String mappedModel, PlatformCompletionResult result) {
        ObjectNode body = switch (mode) {
            case OPENAI_RESPONSES -> openAiResponseFactory.responses(mappedModel, result, request.path("previous_response_id").asString(null));
            case ANTHROPIC_MESSAGES -> anthropicResponseFactory.message(mappedModel, result);
            case GEMINI_GENERATE_CONTENT -> geminiResponseFactory.generateContent(mappedModel, result);
            case OPENAI_CHAT -> openAiResponseFactory.chatCompletion(mappedModel, result);
        };
        return ResponseEntity.ok(body);
    }

    /** @param owner the account that gave the answer and owns the chain it starts; null when no account does (a relay) */
    private PreparedResponse prepareResponsesJsonResponse(
        JsonNode request,
        JsonNode stateRequest,
        String mappedModel,
        PlatformCompletionResult result,
        ProxyAccountEntity owner
    ) {
        ObjectNode body = openAiResponseFactory.responses(
            mappedModel,
            result,
            request.path("previous_response_id").asString(null)
        );
        rememberResponse(body, stateRequest, owner);
        return new PreparedResponse(ResponseEntity.ok(body), body.path("id").asString(null));
    }

    /** @param owner as for {@link #prepareResponsesJsonResponse} */
    private PreparedResponse prepareResponsesStreamResponse(
        JsonNode request,
        JsonNode stateRequest,
        String mappedModel,
        PlatformCompletionResult result,
        ProxyAccountEntity owner
    ) {
        ObjectNode body = openAiResponseFactory.responses(
            mappedModel,
            result,
            request.path("previous_response_id").asString(null)
        );
        rememberResponse(body, stateRequest, owner);
        ResponseEntity<Flux<ServerSentEvent<String>>> response = ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(openAiResponseFactory.responsesStreamEvents(body));
        return new PreparedResponse(response, body.path("id").asString(null));
    }

    /** An answer no account gave (a relay's) is kept without the reasoning the gateway wrote for it, see {@link OpenAiResponseStateStore#rememberRelayed}. */
    private void rememberResponse(ObjectNode body, JsonNode stateRequest, ProxyAccountEntity owner) {
        if (owner == null) {
            openAiResponseStateStore.rememberRelayed(body, stateRequest);
        } else {
            openAiResponseStateStore.remember(body, stateRequest, owner.getAccountId());
        }
    }

    private Mono<Void> rememberResponseOwner(PreparedResponse response, ProxyAccountEntity account) {
        if (openAiResponseOwnerStore == null) {
            return Mono.empty();
        }
        return openAiResponseOwnerStore.remember(response.responseId(), account.getAccountId());
    }

    private Mono<ResponseAffinity> responseAffinity(String previousResponseId, UpstreamRoute route) {
        boolean responseChain = previousResponseId != null;
        boolean movable = responseChain && openAiResponseStateStore.canExpand(previousResponseId);
        if (!responseChain || route != UpstreamRoute.OPENAI) {
            return Mono.just(new ResponseAffinity(null, responseChain, movable));
        }
        if (openAiResponseOwnerStore == null) {
            return Mono.just(new ResponseAffinity(null, true, movable));
        }
        String localOwner = openAiResponseStateStore.ownerAccountId(previousResponseId);
        if (localOwner != null) {
            return Mono.just(new ResponseAffinity(localOwner, true, movable));
        }
        return openAiResponseOwnerStore.findOwner(previousResponseId)
            .map(owner -> new ResponseAffinity(owner, true, movable))
            .defaultIfEmpty(new ResponseAffinity(null, true, movable));
    }

    private ResponseEntity<Flux<ServerSentEvent<String>>> streamResponse(
        ResponseMode mode,
        JsonNode request,
        String mappedModel,
        PlatformCompletionResult result
    ) {
        if (mode == ResponseMode.ANTHROPIC_MESSAGES) {
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(anthropicResponseFactory.messageStream(mappedModel, result));
        }
        if (mode == ResponseMode.OPENAI_RESPONSES) {
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(openAiResponseFactory.responsesStreamEvents(
                    mappedModel,
                    result,
                    request.path("previous_response_id").asString(null)
                ));
        }
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(openAiResponseFactory.chatStreamEvents(mappedModel, result));
    }

    private void validateChatRequest(JsonNode request) {
        validateModelField(request);
        if (!request.path("messages").isArray() || request.path("messages").isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGES_REQUIRED", "messages must be a non-empty array");
        }
    }

    private void validateResponsesRequest(JsonNode request) {
        validateModelField(request);
        if (!request.has("input")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INPUT_REQUIRED", "input is required");
        }
    }

    private void validateModelField(JsonNode request) {
        if (!request.hasNonNull("model")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_REQUIRED", "model is required");
        }
        if (!request.path("model").isString()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_INVALID", "model must be a string");
        }
        if (request.path("model").asString("").length() > MAX_MODEL_ID_LENGTH) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_TOO_LONG",
                "model must be at most " + MAX_MODEL_ID_LENGTH + " characters"
            );
        }
    }

    private String sessionHint(
        ServerWebExchange exchange,
        JsonNode body,
        String keyId,
        ResponseMode responseMode,
        String previousResponseId
    ) {
        String platformHeader = responseMode == ResponseMode.ANTHROPIC_MESSAGES
            ? exchange.getRequest().getHeaders().getFirst("x-claude-code-session-id")
            : exchange.getRequest().getHeaders().getFirst("x-opencode-session");
        String header = firstNonBlank(
            platformHeader,
            exchange.getRequest().getHeaders().getFirst("x-session-affinity"),
            exchange.getRequest().getHeaders().getFirst("x-conversation-id")
        );
        String bodyHint = firstNonBlank(
            responseMode == ResponseMode.ANTHROPIC_MESSAGES
                ? SessionIdentifiers.anthropic(body.path("metadata"))
                : null,
            body.path("conversation_id").asString(null),
            body.path("conversationId").asString(null),
            body.path("thread_id").asString(null),
            body.path("session_id").asString(null),
            previousResponseId
        );
        String hint = firstNonBlank(header, bodyHint);
        return hint == null ? keyId : keyId + ":" + hint;
    }

    /**
     * GPT Chat/Responses use Codex seats when the operator enables that route, or when the
     * inbound client/model is already Codex-specific. openai-compatible/ and DeepSeek stay out.
     */
    private boolean preferCodexAccounts(
        UpstreamRoute route,
        boolean preferDeepSeekAccounts,
        String upstreamModel,
        ServerWebExchange exchange,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        if (route != UpstreamRoute.OPENAI || preferDeepSeekAccounts || isOpenAiCompatibleModel(upstreamModel)) {
            return false;
        }
        return routingPolicy.gptUsesCodexAccountsEnabled()
            || isCodexResponsesClient(exchange);
    }

    /** Whether a Responses request goes to Codex seats: as above, and for the models only Codex accounts serve. */
    private boolean preferCodexAccountsForResponses(
        UpstreamRoute route,
        boolean preferDeepSeekAccounts,
        String requestedModel,
        String mappedModel,
        String upstreamModel,
        ServerWebExchange exchange,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        return preferCodexAccounts(route, preferDeepSeekAccounts, upstreamModel, exchange, routingPolicy)
            || isCodexOnlyResponsesModel(requestedModel)
            || isCodexOnlyResponsesModel(mappedModel);
    }

    private boolean isOpenAiCompatibleModel(String model) {
        String normalized = routeModel(model);
        if (normalized.startsWith("openai-compatible/")) {
            return true;
        }
        String local = localModelId(normalized);
        if (local.startsWith("glm") || local.startsWith("chatglm")) {
            return true;
        }
        return catalogSpec(normalized)
            .filter(this::isCustomOpenAiCompatibleSpec)
            .isPresent();
    }

    private Optional<ModelDtos.ModelSpec> catalogSpec(String model) {
        String normalized = routeModel(model);
        String compact = compactModelId(normalized);
        return modelCatalog.models().stream()
            .filter(spec -> {
                String id = routeModel(spec.id());
                return id.equals(normalized) || compactModelId(id).equals(compact);
            })
            .findFirst();
    }

    private boolean isCustomOpenAiCompatibleSpec(ModelDtos.ModelSpec spec) {
        if (spec == null) {
            return false;
        }
        String format = spec.requestFormat();
        if (format != null && !format.isBlank() && !"OPENAI_CHAT_COMPLETIONS".equalsIgnoreCase(format)) {
            return false;
        }
        return AccountPlatforms.isCustomOpenAiCompatibleProvider(
            spec.provider(),
            AccountPlatforms.OPENAI_API_KEY_AUTH
        );
    }

    private static String localModelId(String model) {
        int slash = model.lastIndexOf('/');
        return slash >= 0 ? model.substring(slash + 1) : model;
    }

    private static String compactModelId(String model) {
        return localModelId(model).replace("-", "").replace("_", "");
    }

    private void bindSession(
        String sessionHint,
        String accountId,
        AccountDtos.RoutingPolicySettings routingPolicy,
        AccountPoolRequestContext poolContext
    ) {
        accountPoolService.commitSessionAffinity(
            poolContext,
            sessionHint,
            accountId,
            routingPolicy.bindSessionByApiKeyEnabled()
        );
    }

    private boolean reliableSessionHint(String sessionHint) {
        return sessionHint != null && sessionHint.indexOf(':') > 0;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record UpstreamCallOutcome(PlatformCompletionResult result, Throwable failure) {
    }

    /**
     * What a tiered request keeps the same from its first dispatch to its last: the values the endpoint has settled by
     * the time an untiered request starts its attempts, and the plan.
     *
     * @param upstreamModel the model after mapping, which the upstream client and the response are given
     * @param upstreamRequest the body that is dispatched, with its output limit forced
     * @param responseRequest the client's request, which the response is shaped by
     * @param requestMovable whether the request may be sent to another account after a failure
     * @param ownerMayBeLeft whether the request may leave the account that owns its response chain
     * @param chainSession the session a Responses request belongs to by the name its chain gives it, which the facade has
     *     worked out before planning ({@code TierSessionBindings#sessionOf}); null on the other endpoints, and for a request
     *     that continues no chain and names no session. The response that is produced is recorded under it when the plan names
     *     no session of its own ({@link #followTierToNextTurn}).
     */
    record TieredRequest(
        TierPlan plan,
        ApiKeyPrincipal principal,
        String requestId,
        String path,
        String requestedModel,
        String upstreamModel,
        String sessionHint,
        boolean stream,
        UpstreamRoute route,
        boolean fastMode,
        boolean preferCodexAccounts,
        boolean preferDeepSeekAccounts,
        AccountDtos.RoutingPolicySettings routingPolicy,
        JsonNode upstreamRequest,
        JsonNode responseRequest,
        Instant start,
        boolean requestMovable,
        boolean ownerMayBeLeft,
        String chainSession
    ) {

        /** A request that belongs to no response chain: the endpoints without one, and a request that continues none. */
        TieredRequest(
            TierPlan plan,
            ApiKeyPrincipal principal,
            String requestId,
            String path,
            String requestedModel,
            String upstreamModel,
            String sessionHint,
            boolean stream,
            UpstreamRoute route,
            boolean fastMode,
            boolean preferCodexAccounts,
            boolean preferDeepSeekAccounts,
            AccountDtos.RoutingPolicySettings routingPolicy,
            JsonNode upstreamRequest,
            JsonNode responseRequest,
            Instant start,
            boolean requestMovable,
            boolean ownerMayBeLeft
        ) {
            this(plan, principal, requestId, path, requestedModel, upstreamModel, sessionHint, stream, route, fastMode,
                preferCodexAccounts, preferDeepSeekAccounts, routingPolicy, upstreamRequest, responseRequest, start,
                requestMovable, ownerMayBeLeft, null);
        }
    }

    /**
     * What differs between the endpoints in the dispatch loop of a tiered request.
     *
     * @param respond makes the response of the answer given by the selection (the account and how it was asked)
     * @param relays whether the endpoint can be served by a relay: the Chat Completions, Messages and Responses entries
     *     are, through the pairs of the registry; a plan of the Gemini entry never holds one
     */
    private record TieredEndpoint(
        Function<ProxyAccountEntity, Mono<PlatformCompletionResult>> call,
        BiFunction<PlatformCompletionResult, TierSelection, Mono<ResponseEntity<?>>> respond,
        boolean relays
    ) {
    }

    static final class AccountAttempt {

        private final ProxyAccountEntity account;
        private final UUID leaseId;
        private final TierSelection selection;
        private final Instant startedAt;
        private final AtomicBoolean leaseFinalized = new AtomicBoolean();

        private AccountAttempt(ProxyAccountEntity account, UUID leaseId) {
            this(account, leaseId, null);
        }

        private AccountAttempt(TierSelection selection, UUID leaseId) {
            this(selection.account(), leaseId, selection);
        }

        private AccountAttempt(ProxyAccountEntity account, UUID leaseId, TierSelection selection) {
            this.account = account;
            this.leaseId = leaseId;
            this.selection = selection;
            this.startedAt = Instant.now();
        }

        ProxyAccountEntity account() {
            return account;
        }

        UUID leaseId() {
            return leaseId;
        }

        /** Where in the plan the account was selected, fixed from then on; null for an untiered request. */
        TierSelection selection() {
            return selection;
        }

        private Instant startedAt() {
            return startedAt;
        }

        private boolean leaseFinalized() {
            return leaseFinalized.get();
        }

        private void markLeaseFinalized() {
            leaseFinalized.set(true);
        }
    }

    private record ResponseAffinity(String accountId, boolean responseChain, boolean movable) {
    }

    private record PreparedResponse(ResponseEntity<?> response, String responseId) {
    }
}
