package tech.starryapi.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
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
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.stats.StatsService;

@Service
public class ProxyFacade {

    static final int MAX_MODEL_ID_LENGTH = 256;
    private static final Logger log = LoggerFactory.getLogger(ProxyFacade.class);

    private enum ResponseMode {
        OPENAI_CHAT,
        OPENAI_RESPONSES,
        ANTHROPIC_MESSAGES,
        GEMINI_GENERATE_CONTENT
    }

    private enum UpstreamRoute {
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
    }

    @Autowired
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

    public Mono<ResponseEntity<?>> chat(JsonNode request, ServerWebExchange exchange) {
        return authenticateAndLimit(exchange)
            .zipWith(routingPolicy())
            .flatMap(tuple -> invokeChat(
                request,
                exchange,
                tuple.getT1(),
                "/v1/chat/completions",
                ResponseMode.OPENAI_CHAT,
                tuple.getT2()
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
        return routingPolicy()
            .flatMap(policy -> invokeChat(
                copy,
                exchange,
                principal,
                "/v1/chat/completions",
                ResponseMode.OPENAI_CHAT,
                policy
            ))
            .map(ProxyFacade::assistantText);
    }

    static String assistantText(ResponseEntity<?> response) {
        Object body = response == null ? null : response.getBody();
        if (body instanceof JsonNode node) {
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                return content.asText();
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
        ObjectNode chat = anthropicRequestAdapter.messagesToChat(request);
        return authenticateAndLimit(exchange)
            .zipWith(routingPolicy())
            .flatMap(tuple -> invokeChat(
                chat,
                exchange,
                tuple.getT1(),
                "/v1/messages",
                ResponseMode.ANTHROPIC_MESSAGES,
                tuple.getT2()
            ));
    }

    public Mono<ResponseEntity<?>> geminiGenerateContent(String model, JsonNode request, ServerWebExchange exchange) {
        ObjectNode chat = geminiRequestAdapter.generateContentToChat(model, request);
        String path = "/v1beta/models/" + model + ":generateContent";
        return authenticateAndLimit(exchange)
            .zipWith(routingPolicy())
            .flatMap(tuple -> invokeChat(
                chat,
                exchange,
                tuple.getT1(),
                path,
                ResponseMode.GEMINI_GENERATE_CONTENT,
                tuple.getT2()
            ));
    }

    private Mono<AccountDtos.RoutingPolicySettings> routingPolicy() {
        Mono<AccountDtos.RoutingPolicySettings> loaded = accountSettingsService.routingPolicy();
        if (loaded == null) {
            return Mono.just(AccountDtos.RoutingPolicySettings.defaults());
        }
        return loaded.defaultIfEmpty(AccountDtos.RoutingPolicySettings.defaults());
    }

    private Mono<ResponseEntity<?>> invokeChat(
        JsonNode request,
        ServerWebExchange exchange,
        ApiKeyPrincipal principal,
        String path,
        ResponseMode responseMode,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        validateChatRequest(request);
        Instant start = Instant.now();
        String requestId = UUID.randomUUID().toString();
        String requestedModel = request.path("model").asText(modelCatalog.defaultTargetModelId());
        String mappedModel = modelMappingService.apply(requestedModel, principal.keyId());
        ModelProviderAccessPolicy.verify(modelCatalog, principal, requestedModel, mappedModel);
        boolean requestedFastMode = isFastRequested(request, requestedModel, mappedModel);
        String upstreamModel = upstreamModelFor(mappedModel, requestedFastMode);
        String sessionHint = sessionHint(exchange, request, principal.keyId(), responseMode, null);
        boolean stream = responseMode != ResponseMode.GEMINI_GENERATE_CONTENT && request.path("stream").asBoolean(false);
        UpstreamRoute route = routeFor(upstreamModel, responseMode);
        boolean preferDeepSeekAccounts = isDeepSeekRouteModel(route, upstreamModel);
        boolean preferCodexAccounts = preferCodexAccounts(route, preferDeepSeekAccounts, upstreamModel, exchange, routingPolicy);
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

        return requireFastModeAllowed(fastMode).then(withBilling(principal, requestId, requestedModel, upstreamRequest, fastMode, Mono.defer(() -> attemptChat(
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
        JsonNode request,
        ServerWebExchange exchange,
        ApiKeyPrincipal principal,
        AccountDtos.RoutingPolicySettings routingPolicy
    ) {
        validateResponsesRequest(request);
        Instant start = Instant.now();
        String requestId = UUID.randomUUID().toString();
        String path = "/v1/responses";
        String requestedModel = request.path("model").asText(modelCatalog.defaultTargetModelId());
        String mappedModel = modelMappingService.apply(requestedModel, principal.keyId());
        ModelProviderAccessPolicy.verify(modelCatalog, principal, requestedModel, mappedModel);
        boolean requestedFastMode = isFastRequested(request, requestedModel, mappedModel);
        String upstreamModel = upstreamModelFor(mappedModel, requestedFastMode);
        String previousResponseId = firstNonBlank(request.path("previous_response_id").asText(null));
        String sessionHint = sessionHint(
            exchange,
            request,
            principal.keyId(),
            ResponseMode.OPENAI_RESPONSES,
            previousResponseId
        );
        boolean stream = request.path("stream").asBoolean(false);
        UpstreamRoute route = routeFor(upstreamModel, ResponseMode.OPENAI_RESPONSES);
        boolean preferDeepSeekAccounts = isDeepSeekRouteModel(route, upstreamModel);
        boolean fastMode = requestedFastMode && route == UpstreamRoute.OPENAI && !preferDeepSeekAccounts;
        boolean preferCodexAccounts = preferCodexAccounts(
            route,
            preferDeepSeekAccounts,
            upstreamModel,
            exchange,
            routingPolicy
        ) || isCodexOnlyResponsesModel(requestedModel)
            || isCodexOnlyResponsesModel(mappedModel);
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

        return requireDeepSeekResponsesSupported(preferDeepSeekAccounts, upstreamModel)
            .then(requireFastModeAllowed(fastMode))
            .then(responseAffinity(previousResponseId, route))
            .flatMap(responseAffinity -> withBilling(principal, requestId, requestedModel, upstreamRequest, fastMode, Mono.defer(() -> attemptResponses(
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
                    new AtomicBoolean(false)
                ))));
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

    private <T> Mono<T> withBilling(ApiKeyPrincipal principal, String requestId, String model, JsonNode request,
                                    boolean fastMode, Mono<T> operation) {
        if ("anonymous".equals(principal.keyId())) return operation;
        long inputBound = JsonNodes.utf8Length(request) + 1024L;
        // Server-side response chains and remote media can hide input tokens from the local request.
        if (request.hasNonNull("previous_response_id") || containsRemoteBillingInput(request)) {
            inputBound = Math.max(inputBound, 1_000_000L);
        }
        long outputBound = Math.max(request.path("max_completion_tokens").asLong(0),
            Math.max(request.path("max_tokens").asLong(0), request.path("max_output_tokens").asLong(4096)));
        long estimatedInput = inputBound;
        return paymentSettingsService.config().flatMap(config -> modelPricingService.billingQuote(model, fastMode, config.apiBillingMultiplier()))
            .flatMap(quote -> {
                AtomicBoolean uncertainFailure = new AtomicBoolean();
                AtomicBoolean settled = new AtomicBoolean();
                ObjectNode pricing = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                pricing.put("model", model).put("input", quote.input()).put("output", quote.output())
                    .put("cacheRead", quote.cacheRead()).put("cacheWrite", quote.cacheWrite()).put("fallback", quote.fallback());
                return Mono.usingWhen(
                    apiKeyService.reserveUsage(principal, requestId, quote.estimate(estimatedInput, outputBound), pricing.toString()).thenReturn(requestId),
                    ignored -> operation.contextWrite(context -> context.put(tech.starryapi.model.BillingQuote.class, quote)
                        .put(BILLING_UNCERTAINTY, uncertainFailure)
                        .put(BILLING_SETTLED, settled)),
                    // Once the usage has been settled the reservation is closed; releasing it again would only take the
                    // user and key row locks to find that out. Release only when nothing was charged.
                    ignored -> settled.get()
                        ? Mono.empty()
                        : apiKeyService.releaseUsage(principal, requestId, true, "Completed without charge"),
                    (ignored, error) -> apiKeyService.releaseUsage(principal, requestId,
                        !uncertainFailure.get() && definitiveBillingFailure(error), "Request failed"),
                    ignored -> apiKeyService.releaseUsage(principal, requestId, false, "Client disconnected after dispatch")
                );
            });
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

    private boolean containsRemoteBillingInput(JsonNode node) {
        if (node.isObject() && (node.hasNonNull("image_url") || node.hasNonNull("file_id")
            || node.hasNonNull("file_url") || java.util.Set.of("input_image", "input_file", "input_audio", "image", "document")
                .contains(node.path("type").asText()))) return true;
        if (node.isContainerNode()) {
            for (JsonNode child : node) if (containsRemoteBillingInput(child)) return true;
        }
        return false;
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
        AtomicBoolean autoRetestAttempted
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
        if (admissionRejectedAccountIds.size() >= 64) {
            return Mono.error(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ACCOUNT_ADMISSION_EXHAUSTED",
                "No upstream account has admission capacity"
            ));
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

        return selection.flatMap(account -> admissionStore.acquire(new AccountAdmissionStore.AdmissionRequest(
                account.getAccountId(),
                requestId,
                null,
                Hashing.sha256(reliableSessionHint(sessionHint) ? sessionHint : "request:" + requestId),
                AccountPlatforms.isAnthropicApiKey(account),
                estimatedInput,
                estimatedOutputTokens(request),
                Duration.ofMinutes(10),
                poolContext == null ? null : poolContext.upstreamModel(),
                endpointScope
            ))
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
            return AccountPlatforms::isOpenAiCompatibleApiKey;
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
        String normalized = routeModel(model);
        if (normalized.startsWith("openai/")) {
            normalized = normalized.substring("openai/".length());
        } else if (normalized.startsWith("openai-compatible/")) {
            normalized = normalized.substring("openai-compatible/".length());
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return normalized.contains("codex")
            || "codex-mini-latest".equals(normalized)
            || normalized.matches("gpt-5\\.5.*");
    }

    private boolean isFastRequested(JsonNode request, String requestedModel, String mappedModel) {
        return isPriorityTier(request.path("service_tier").asText(null))
            || isPriorityTier(request.path("serviceTier").asText(null))
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

    private AccountPoolRequestContext poolContext(
        String requestId,
        String requestedModel,
        String upstreamModel,
        UpstreamRoute route,
        boolean deepSeekRoute
    ) {
        String providerHint = deepSeekRoute
            ? AccountPlatforms.DEEPSEEK_PROVIDER
            : route == UpstreamRoute.OPENAI && isOpenAiCompatibleModel(upstreamModel)
                ? AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER
            : switch (route) {
                case OPENAI -> AccountPlatforms.OPENAI_PROVIDER;
                case ANTHROPIC -> AccountPlatforms.ANTHROPIC_PROVIDER;
                case KIRO -> "Kiro";
            };
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
                Map.of("model", model, "route", route.name())
            )
        );
        long circuitOutcomeId = accountPoolService.recordCircuitSuccess(poolContext, account);
        AtomicBoolean circuitOutcomeResolved = new AtomicBoolean(circuitOutcomeId <= 0);
        Mono<BigDecimal> billedCredits = "anonymous".equals(principal.keyId())
            ? Mono.just(BigDecimal.ZERO)
            : Mono.deferContextual(context -> context.hasKey(tech.starryapi.model.BillingQuote.class)
                ? Mono.just(context.get(tech.starryapi.model.BillingQuote.class).cost(usage))
                : paymentSettingsService.config().flatMap(config -> modelPricingService.cost(model, usage, fastMode, config.apiBillingMultiplier())));
        Mono<Void> completion = billedCredits
            .flatMap(credits -> {
                Mono<Void> keyAccounting = apiKeyService.accountUsage(
                    principal,
                    requestId,
                    credits,
                    usage.inputTokens(),
                    usage.outputTokens()
                ).then(Mono.deferContextual(context -> {
                    if (context.hasKey(BILLING_SETTLED)) {
                        context.<AtomicBoolean>get(BILLING_SETTLED).set(true);
                    }
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
                        start
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
                            )),
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
        Instant start
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
            )),
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
        Mono<Void> admissionReconciliation = reconcileAttempt(
            "reconcile failed account admission",
            attempt,
            new AccountAdmissionStore.AdmissionResult(
                attempt.leaseId(),
                false,
                0,
                0,
                Duration.between(attempt.startedAt(), Instant.now()).toMillis(),
                statusCode,
                classification.scope(),
                classification.cooldown(),
                classification.permanentCredentialFailure(),
                Map.of(
                    "message", failureMessage,
                    "model", model,
                    "upstreamModel", poolContext.upstreamModel(),
                    "endpoint", path,
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
            )),
            poolContext,
            circuitOutcomeId,
            circuitOutcomeResolved
        )));
        return Mono.deferContextual(context -> {
            // A later request rejection cannot prove that an earlier timed-out retry was free.
            if (context.hasKey(BILLING_UNCERTAINTY) && !definitiveBillingFailure(error)) {
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
        int scopeLimit = switch (classification.scope()) {
            case TRANSPORT, PROXY_EDGE, REGIONAL_POLICY, UNKNOWN, CREDENTIAL -> 2;
            default -> Integer.valueOf(529).equals(classification.statusCode()) ? 2 : 3;
        };
        int routeLimit = routingPolicy.maxRetryAccounts(preferDeepSeekAccounts, preferCodexAccounts, route.name());
        int maxDistinctAccounts = Math.min(scopeLimit, routeLimit);
        return nextAttemptedAccountIds.size() > attemptedAccountIds.size()
            && nextAttemptedAccountIds.size() < maxDistinctAccounts
            && requestMovable
            && classification.retryable()
            && classification.crossAccountRetryAllowed();
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
                yield new ApiException(status, "UPSTREAM_ERROR", safeFailureMessage(upstream)).initCause(upstream);
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
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
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
            case OPENAI_RESPONSES -> openAiResponseFactory.responses(mappedModel, result, request.path("previous_response_id").asText(null));
            case ANTHROPIC_MESSAGES -> anthropicResponseFactory.message(mappedModel, result);
            case GEMINI_GENERATE_CONTENT -> geminiResponseFactory.generateContent(mappedModel, result);
            case OPENAI_CHAT -> openAiResponseFactory.chatCompletion(mappedModel, result);
        };
        return ResponseEntity.ok(body);
    }

    private PreparedResponse prepareResponsesJsonResponse(
        JsonNode request,
        JsonNode stateRequest,
        String mappedModel,
        PlatformCompletionResult result,
        ProxyAccountEntity account
    ) {
        ObjectNode body = openAiResponseFactory.responses(
            mappedModel,
            result,
            request.path("previous_response_id").asText(null)
        );
        openAiResponseStateStore.remember(body, stateRequest, account.getAccountId());
        return new PreparedResponse(ResponseEntity.ok(body), body.path("id").asText(null));
    }

    private PreparedResponse prepareResponsesStreamResponse(
        JsonNode request,
        JsonNode stateRequest,
        String mappedModel,
        PlatformCompletionResult result,
        ProxyAccountEntity account
    ) {
        ObjectNode body = openAiResponseFactory.responses(
            mappedModel,
            result,
            request.path("previous_response_id").asText(null)
        );
        openAiResponseStateStore.remember(body, stateRequest, account.getAccountId());
        ResponseEntity<Flux<ServerSentEvent<String>>> response = ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(openAiResponseFactory.responsesStreamEvents(body));
        return new PreparedResponse(response, body.path("id").asText(null));
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
                    request.path("previous_response_id").asText(null)
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
        if (!request.path("model").isTextual()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_INVALID", "model must be a string");
        }
        if (request.path("model").asText().length() > MAX_MODEL_ID_LENGTH) {
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
            body.path("conversation_id").asText(null),
            body.path("conversationId").asText(null),
            body.path("thread_id").asText(null),
            body.path("session_id").asText(null),
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

    private static final class AccountAttempt {

        private final ProxyAccountEntity account;
        private final UUID leaseId;
        private final Instant startedAt;
        private final AtomicBoolean leaseFinalized = new AtomicBoolean();

        private AccountAttempt(ProxyAccountEntity account, UUID leaseId) {
            this.account = account;
            this.leaseId = leaseId;
            this.startedAt = Instant.now();
        }

        private ProxyAccountEntity account() {
            return account;
        }

        private UUID leaseId() {
            return leaseId;
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
