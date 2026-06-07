package xyz.star4y.api.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.account.AccountPoolService;
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
        ObjectNode chat = openAiRequestAdapter.responsesToChat(request);
        return authenticateAndLimit(exchange)
            .flatMap(principal -> invokeChat(chat, exchange, principal, "/v1/responses", ResponseMode.OPENAI_RESPONSES));
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
        String sessionHint = sessionHint(exchange, request, principal.keyId());
        boolean stream = responseMode != ResponseMode.GEMINI_GENERATE_CONTENT && request.path("stream").asBoolean(false);
        UpstreamRoute route = routeFor(mappedModel);

        return selectAccount(route, sessionHint)
            .flatMap(account -> callWithAccount(route, account, request, mappedModel, sessionHint)
                .flatMap(result -> {
                    Mono<Void> accounting = recordSuccess(requestId, principal, account, path, requestedModel, result, start);
                    ResponseEntity<?> response = stream
                        ? streamResponse(responseMode, mappedModel, result)
                        : jsonResponse(responseMode, request, mappedModel, result);
                    return accounting.thenReturn(response);
                })
                .onErrorResume(error -> recordFailure(requestId, principal, account, path, requestedModel, error, start)
                    .then(Mono.error(mapError(error)))));
    }

    private Mono<ProxyAccountEntity> selectAccount(UpstreamRoute route, String sessionHint) {
        return switch (route) {
            case OPENAI -> accountPoolService.selectOpenAiAccount(sessionHint);
            case KIRO -> accountPoolService.selectKiroAccount(sessionHint);
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

    private UpstreamRoute routeFor(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        if (normalized.startsWith("openai/")
            || normalized.startsWith("gpt")
            || normalized.startsWith("chatgpt")
            || normalized.matches("o\\d.*")) {
            return UpstreamRoute.OPENAI;
        }
        return UpstreamRoute.KIRO;
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
        Instant start
    ) {
        PlatformUsage usage = result.usage();
        Mono<Void> accountStats = accountPoolService.recordSuccess(account.getAccountId(), usage.totalTokens());
        Mono<Void> keyStats = apiKeyService.recordUsage(principal.keyId(), usage.credits(), usage.inputTokens(), usage.outputTokens());
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
            usage.credits(),
            Duration.between(start, Instant.now()).toMillis(),
            null
        ));
        return Mono.whenDelayError(accountStats, keyStats, log).then();
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
        AccountPoolService.UpstreamErrorType type = statusCode != null && (statusCode == 402 || statusCode == 403 || statusCode == 429)
            ? AccountPoolService.UpstreamErrorType.RECOVERABLE
            : AccountPoolService.UpstreamErrorType.FATAL;
        Mono<Void> accountStats = accountPoolService.recordError(account.getAccountId(), type, statusCode);
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

    private ResponseEntity<Flux<ServerSentEvent<String>>> streamResponse(ResponseMode mode, String mappedModel, PlatformCompletionResult result) {
        if (mode == ResponseMode.ANTHROPIC_MESSAGES) {
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(anthropicResponseFactory.messageStream(mappedModel, result));
        }
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(openAiResponseFactory.chatStreamEvents(mappedModel, result));
    }

    private void validateChatRequest(JsonNode request) {
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
        if (!request.path("messages").isArray() || request.path("messages").isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGES_REQUIRED", "messages must be a non-empty array");
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
