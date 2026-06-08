package xyz.star4y.api.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.account.AccountAutoSuspendService;
import xyz.star4y.api.account.AccountPoolService;
import xyz.star4y.api.account.AccountSettingsService;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.apikey.ApiKeyPrincipal;
import xyz.star4y.api.apikey.ApiKeyService;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.model.ModelCatalogProvider;
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

@Service
public class ProxyFacade {

    static final int MAX_MODEL_ID_LENGTH = 256;

    private enum ResponseMode {
        OPENAI_CHAT,
        OPENAI_RESPONSES,
        ANTHROPIC_MESSAGES,
        GEMINI_GENERATE_CONTENT
    }

    private enum UpstreamRoute {
        KIRO,
        OPENAI
    }

    private final ApiKeyService apiKeyService;
    private final AccountPoolService accountPoolService;
    private final AccountAutoSuspendService accountAutoSuspendService;
    private final AccountSettingsService accountSettingsService;
    private final ModelCatalogProvider modelCatalog;
    private final ModelMappingService modelMappingService;
    private final RateLimiterService rateLimiterService;
    private final KiroPayloadFactory payloadFactory;
    private final KiroUpstreamClient upstreamClient;
    private final OpenAiUpstreamClient openAiUpstreamClient;
    private final OpenAiRequestAdapter openAiRequestAdapter;
    private final OpenAiResponseFactory openAiResponseFactory;
    private final AnthropicRequestAdapter anthropicRequestAdapter;
    private final AnthropicResponseFactory anthropicResponseFactory;
    private final GeminiRequestAdapter geminiRequestAdapter;
    private final GeminiResponseFactory geminiResponseFactory;
    private final StatsService statsService;

    public ProxyFacade(
        ApiKeyService apiKeyService,
        AccountPoolService accountPoolService,
        AccountAutoSuspendService accountAutoSuspendService,
        AccountSettingsService accountSettingsService,
        ModelCatalogProvider modelCatalog,
        ModelMappingService modelMappingService,
        RateLimiterService rateLimiterService,
        KiroPayloadFactory payloadFactory,
        KiroUpstreamClient upstreamClient,
        OpenAiUpstreamClient openAiUpstreamClient,
        OpenAiRequestAdapter openAiRequestAdapter,
        OpenAiResponseFactory openAiResponseFactory,
        AnthropicRequestAdapter anthropicRequestAdapter,
        AnthropicResponseFactory anthropicResponseFactory,
        GeminiRequestAdapter geminiRequestAdapter,
        GeminiResponseFactory geminiResponseFactory,
        StatsService statsService
    ) {
        this.apiKeyService = apiKeyService;
        this.accountPoolService = accountPoolService;
        this.accountAutoSuspendService = accountAutoSuspendService;
        this.accountSettingsService = accountSettingsService;
        this.modelCatalog = modelCatalog;
        this.modelMappingService = modelMappingService;
        this.rateLimiterService = rateLimiterService;
        this.payloadFactory = payloadFactory;
        this.upstreamClient = upstreamClient;
        this.openAiUpstreamClient = openAiUpstreamClient;
        this.openAiRequestAdapter = openAiRequestAdapter;
        this.openAiResponseFactory = openAiResponseFactory;
        this.anthropicRequestAdapter = anthropicRequestAdapter;
        this.anthropicResponseFactory = anthropicResponseFactory;
        this.geminiRequestAdapter = geminiRequestAdapter;
        this.geminiResponseFactory = geminiResponseFactory;
        this.statsService = statsService;
    }

    public Mono<ResponseEntity<?>> chat(JsonNode request, ServerWebExchange exchange) {
        return authenticateAndLimit(exchange)
            .flatMap(principal -> invokeChat(request, exchange, principal, "/v1/chat/completions", ResponseMode.OPENAI_CHAT));
    }

    public Mono<ResponseEntity<?>> responses(JsonNode request, ServerWebExchange exchange) {
        return authenticateAndLimit(exchange)
            .flatMap(principal -> invokeResponses(request, exchange, principal));
    }

    public Mono<ResponseEntity<?>> messages(JsonNode request, ServerWebExchange exchange) {
        ObjectNode chat = anthropicRequestAdapter.messagesToChat(request);
        return authenticateAndLimit(exchange)
            .flatMap(principal -> invokeChat(chat, exchange, principal, "/v1/messages", ResponseMode.ANTHROPIC_MESSAGES));
    }

    public Mono<ResponseEntity<?>> geminiGenerateContent(String model, JsonNode request, ServerWebExchange exchange) {
        ObjectNode chat = geminiRequestAdapter.generateContentToChat(model, request);
        String path = "/v1beta/models/" + model + ":generateContent";
        return authenticateAndLimit(exchange)
            .flatMap(principal -> invokeChat(chat, exchange, principal, path, ResponseMode.GEMINI_GENERATE_CONTENT));
    }

    private Mono<ResponseEntity<?>> invokeChat(
        JsonNode request,
        ServerWebExchange exchange,
        ApiKeyPrincipal principal,
        String path,
        ResponseMode responseMode
    ) {
        validateChatRequest(request);
        Instant start = Instant.now();
        String requestId = UUID.randomUUID().toString();
        String requestedModel = request.path("model").asText(modelCatalog.defaultTargetModelId());
        String mappedModel = modelMappingService.apply(requestedModel, principal.keyId());
        boolean requestedFastMode = isFastRequested(request, requestedModel, mappedModel);
        String upstreamModel = upstreamModelFor(mappedModel, requestedFastMode);
        String sessionHint = sessionHint(exchange, request, principal.keyId());
        boolean stream = responseMode != ResponseMode.GEMINI_GENERATE_CONTENT && request.path("stream").asBoolean(false);
        UpstreamRoute route = routeFor(upstreamModel);
        boolean fastMode = requestedFastMode && route == UpstreamRoute.OPENAI;
        JsonNode upstreamRequest = fastMode ? openAiFastRequest(request) : request;

        return requireFastModeAllowed(fastMode).then(Mono.defer(() -> attemptChat(
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
            requestId,
            start,
            List.of()
        )));
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
        String requestId,
        Instant start,
        List<String> attemptedAccountIds
    ) {
        return selectAccount(route, sessionHint, attemptedAccountIds)
            .flatMap(account -> callWithAccount(route, account, upstreamRequest, mappedModel, sessionHint)
                .flatMap(result -> {
                    Mono<Void> accounting = recordSuccess(requestId, principal, account, path, requestedModel, result, route, fastMode, start);
                    ResponseEntity<?> response = stream
                        ? streamResponse(responseMode, responseRequest, mappedModel, result)
                        : jsonResponse(responseMode, responseRequest, mappedModel, result);
                    return accounting.then(Mono.<ResponseEntity<?>>just(response));
                })
                .onErrorResume(error -> {
                    List<String> nextAttempted = attemptedWith(account, attemptedAccountIds);
                    Mono<Void> failureRecord = recordFailure(requestId, principal, account, path, requestedModel, error, start);
                    if (!shouldRetryWithAnotherAccount(error, attemptedAccountIds, nextAttempted)) {
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
                        requestId,
                        start,
                        nextAttempted
                    ).onErrorResume(retryError -> isNoAvailableAccount(retryError)
                        ? Mono.<ResponseEntity<?>>error(mapError(error))
                        : Mono.<ResponseEntity<?>>error(retryError)));
                }));
    }

    private Mono<ResponseEntity<?>> invokeResponses(
        JsonNode request,
        ServerWebExchange exchange,
        ApiKeyPrincipal principal
    ) {
        validateResponsesRequest(request);
        Instant start = Instant.now();
        String requestId = UUID.randomUUID().toString();
        String path = "/v1/responses";
        String requestedModel = request.path("model").asText(modelCatalog.defaultTargetModelId());
        String mappedModel = modelMappingService.apply(requestedModel, principal.keyId());
        boolean requestedFastMode = isFastRequested(request, requestedModel, mappedModel);
        String upstreamModel = upstreamModelFor(mappedModel, requestedFastMode);
        String sessionHint = sessionHint(exchange, request, principal.keyId());
        boolean stream = request.path("stream").asBoolean(false);
        UpstreamRoute route = routeFor(upstreamModel);
        boolean fastMode = requestedFastMode && route == UpstreamRoute.OPENAI;
        JsonNode upstreamRequest = fastMode ? openAiFastRequest(request) : request;

        return requireFastModeAllowed(fastMode).then(Mono.defer(() -> attemptResponses(
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
            requestId,
            start,
            List.of()
        )));
    }

    private Mono<Void> requireFastModeAllowed(boolean fastMode) {
        return fastMode ? accountSettingsService.ensureGptFastModeAllowed() : Mono.empty();
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
        String requestId,
        Instant start,
        List<String> attemptedAccountIds
    ) {
        return selectAccount(route, sessionHint, attemptedAccountIds)
            .flatMap(account -> callResponsesWithAccount(route, account, upstreamRequest, mappedModel, sessionHint)
                .flatMap(result -> {
                    Mono<Void> accounting = recordSuccess(requestId, principal, account, path, requestedModel, result, route, fastMode, start);
                    ResponseEntity<?> response = stream
                        ? streamResponse(ResponseMode.OPENAI_RESPONSES, responseRequest, mappedModel, result)
                        : jsonResponse(ResponseMode.OPENAI_RESPONSES, responseRequest, mappedModel, result);
                    return accounting.then(Mono.<ResponseEntity<?>>just(response));
                })
                .onErrorResume(error -> {
                    List<String> nextAttempted = attemptedWith(account, attemptedAccountIds);
                    Mono<Void> failureRecord = recordFailure(requestId, principal, account, path, requestedModel, error, start);
                    if (!shouldRetryWithAnotherAccount(error, attemptedAccountIds, nextAttempted)) {
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
                        requestId,
                        start,
                        nextAttempted
                    ).onErrorResume(retryError -> isNoAvailableAccount(retryError)
                        ? Mono.<ResponseEntity<?>>error(mapError(error))
                        : Mono.<ResponseEntity<?>>error(retryError)));
                }));
    }

    private Mono<ProxyAccountEntity> selectAccount(UpstreamRoute route, String sessionHint) {
        return switch (route) {
            case OPENAI -> accountPoolService.selectOpenAiAccount(sessionHint);
            case KIRO -> accountPoolService.selectKiroAccount(sessionHint);
        };
    }

    private Mono<ProxyAccountEntity> selectAccount(UpstreamRoute route, String sessionHint, List<String> attemptedAccountIds) {
        if (attemptedAccountIds == null || attemptedAccountIds.isEmpty()) {
            return selectAccount(route, sessionHint);
        }
        return switch (route) {
            case OPENAI -> accountPoolService.selectOpenAiAccount(sessionHint, attemptedAccountIds);
            case KIRO -> accountPoolService.selectKiroAccount(sessionHint, attemptedAccountIds);
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
        ObjectNode payload = payloadFactory.fromOpenAiChat(request, account, mappedModel, "AI_EDITOR", sessionHint);
        return upstreamClient.complete(account, payload, mappedModel);
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
        ObjectNode payload = payloadFactory.fromOpenAiChat(chat, account, mappedModel, "AI_EDITOR", sessionHint);
        return upstreamClient.complete(account, payload, mappedModel);
    }

    private UpstreamRoute routeFor(String model) {
        String normalized = routeModel(model);
        if (normalized.startsWith("openai/")
            || normalized.startsWith("gpt")
            || normalized.startsWith("chatgpt")
            || normalized.matches("o\\d.*")) {
            return UpstreamRoute.OPENAI;
        }
        return UpstreamRoute.KIRO;
    }

    private String routeModel(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        return normalized.startsWith("starry/") ? normalized.substring("starry/".length()) : normalized;
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
            ObjectNode copy = object.deepCopy();
            copy.put("service_tier", "priority");
            copy.remove("serviceTier");
            copy.remove("fast");
            copy.remove("fast_mode");
            return copy;
        }
        return request;
    }

    private Mono<ApiKeyPrincipal> authenticateAndLimit(ServerWebExchange exchange) {
        return apiKeyService.authenticate(exchange)
            .flatMap(principal -> {
                RateLimiterService.RateLimitResult limit = rateLimiterService.check(principal.keyId());
                if (!limit.allowed()) {
                    exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(limit.retryAfterMillis() / 1000));
                    return Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Rate limit exceeded"));
                }
                return Mono.just(principal);
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
        Instant start
    ) {
        PlatformUsage usage = result.usage();
        BigDecimal credits = billableCredits(usage, route, fastMode);
        Mono<Void> accountStats = accountPoolService.recordSuccess(account.getAccountId(), usage.totalTokens());
        Mono<Void> keyStats = apiKeyService.recordUsage(principal.keyId(), credits, usage.inputTokens(), usage.outputTokens());
        Mono<Void> log = statsService.log(new StatsService.RequestLog(
            requestId,
            principal.keyId(),
            account.getAccountId(),
            path,
            model,
            200,
            true,
            usage.inputTokens(),
            usage.outputTokens(),
            credits,
            Duration.between(start, Instant.now()).toMillis(),
            null
        ));
        return Mono.whenDelayError(accountStats, keyStats, log).then();
    }

    private BigDecimal billableCredits(PlatformUsage usage, UpstreamRoute route, boolean fastMode) {
        BigDecimal credits = usage.credits() == null ? BigDecimal.ZERO : usage.credits();
        if (route == UpstreamRoute.OPENAI && credits.compareTo(BigDecimal.ZERO) <= 0) {
            credits = BigDecimal.valueOf(usage.totalTokens());
        }
        return fastMode ? credits.multiply(BigDecimal.valueOf(2)) : credits;
    }

    private Mono<Void> recordFailure(
        String requestId,
        ApiKeyPrincipal principal,
        ProxyAccountEntity account,
        String path,
        String model,
        Throwable error,
        Instant start
    ) {
        Throwable failure = rootFailure(error);
        Integer statusCode = failure instanceof UpstreamException upstream ? upstream.getStatusCode() : null;
        AccountPoolService.UpstreamErrorType type = statusCode != null
            && (statusCode == 401 || statusCode == 402 || statusCode == 403 || statusCode == 429 || statusCode >= 500)
            ? AccountPoolService.UpstreamErrorType.RECOVERABLE
            : AccountPoolService.UpstreamErrorType.FATAL;
        Mono<Void> accountStats = accountPoolService.recordRequestFailure(account.getAccountId(), type, statusCode, failure.getMessage())
            .doOnNext(accountAutoSuspendService::scheduleIfNeeded)
            .then();
        Mono<Void> log = statsService.log(new StatsService.RequestLog(
            requestId,
            principal.keyId(),
            account.getAccountId(),
            path,
            model,
            statusCode == null ? 500 : statusCode,
            false,
            0,
            0,
            BigDecimal.ZERO,
            Duration.between(start, Instant.now()).toMillis(),
            failure.getMessage()
        ));
        return Mono.whenDelayError(accountStats, log).then();
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

    private boolean shouldRetryWithAnotherAccount(
        Throwable error,
        List<String> attemptedAccountIds,
        List<String> nextAttemptedAccountIds
    ) {
        return nextAttemptedAccountIds.size() > attemptedAccountIds.size()
            && isRetryableUpstreamError(error);
    }

    private boolean isRetryableUpstreamError(Throwable error) {
        Throwable failure = rootFailure(error);
        if (!(failure instanceof UpstreamException upstream)) {
            return false;
        }
        int statusCode = upstream.getStatusCode();
        return upstream.isRecoverable()
            || statusCode == 401
            || statusCode == 402
            || statusCode == 403
            || statusCode == 429
            || statusCode >= 500;
    }

    private boolean isNoAvailableAccount(Throwable error) {
        Throwable failure = rootFailure(error);
        return failure instanceof ApiException api && "NO_AVAILABLE_ACCOUNT".equals(api.getCode());
    }

    private Throwable mapError(Throwable error) {
        Throwable failure = rootFailure(error);
        return switch (failure) {
            case ApiException api -> api;
            case UpstreamException upstream -> {
                HttpStatus status = upstream.getStatusCode() == 429 || upstream.getStatusCode() == 402
                    ? HttpStatus.TOO_MANY_REQUESTS
                    : HttpStatus.BAD_GATEWAY;
                yield new ApiException(status, "UPSTREAM_ERROR", upstream.getMessage());
            }
            default -> new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", failure.getMessage());
        };
    }

    private Throwable rootFailure(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
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

    private String sessionHint(ServerWebExchange exchange, JsonNode body, String keyId) {
        String header = firstNonBlank(
            exchange.getRequest().getHeaders().getFirst("x-claude-code-session-id"),
            exchange.getRequest().getHeaders().getFirst("x-opencode-session"),
            exchange.getRequest().getHeaders().getFirst("x-session-affinity"),
            exchange.getRequest().getHeaders().getFirst("x-conversation-id")
        );
        String bodyHint = firstNonBlank(
            body.path("conversation_id").asText(null),
            body.path("conversationId").asText(null),
            body.path("thread_id").asText(null),
            body.path("session_id").asText(null)
        );
        String hint = firstNonBlank(header, bodyHint);
        return hint == null ? keyId : keyId + ":" + hint;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
