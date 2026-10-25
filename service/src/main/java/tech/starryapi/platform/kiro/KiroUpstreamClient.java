package tech.starryapi.platform.kiro;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tech.starryapi.account.AccountTokenRefreshService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class KiroUpstreamClient {

    private static final String KIRO_VERSION = "0.12.155";
    private static final String AWS_SDK_VERSION = "1.0.34";
    private static final String AWS_STREAMING_API_VERSION = "1.0.34";
    private static final String CODEWHISPERER_DEFAULT_MODEL_ID = "CLAUDE_SONNET_4_20250514_V1_0";

    private final WebClient webClient;
    private final ProxyProperties properties;
    private final AwsEventStreamParser parser;
    private final AccountTokenRefreshService tokenRefreshService;
    private final Cache<String, List<KiroModel>> codeWhispererModelCache;
    private final AtomicLong modelCacheGeneration = new AtomicLong();

    public KiroUpstreamClient(
        WebClient upstreamWebClient,
        ProxyProperties properties,
        AwsEventStreamParser parser,
        AccountTokenRefreshService tokenRefreshService
    ) {
        this.webClient = upstreamWebClient;
        this.properties = properties;
        this.parser = parser;
        this.tokenRefreshService = tokenRefreshService;
        this.codeWhispererModelCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(512)
            .build();
    }

    public Mono<PlatformCompletionResult> complete(ProxyAccountEntity account, ObjectNode payload, String model) {
        return Mono.deferContextual(context -> {
            // Every endpoint and every retry parses its own response, and the error that is finally thrown belongs to the
            // last of them. This flag outlives the attempts so that it can still say an earlier one produced output; the
            // caller's own, if it gave one, also covers the error that is not an UpstreamException and cannot say it.
            AtomicBoolean generationStarted = context.getOrDefault(UpstreamException.GENERATION_STARTED_CONTEXT_KEY, new AtomicBoolean());
            return Mono.defer(() -> callFirstEndpoint(account, payload, model, generationStarted))
                .timeout(properties.getRequestTimeout())
                .retryWhen(Retry.backoff(properties.getMaxRetries(), Duration.ofMillis(250))
                    .maxBackoff(Duration.ofSeconds(2))
                    .filter(error -> error instanceof UpstreamException upstream && upstream.isRecoverable()
                        // Quota exhaustion (402) and rate limits (429) do not clear within a few hundred milliseconds. Retrying
                        // the same account just multiplies the load on an account that is already refusing us; the proxy
                        // moves on to another account instead.
                        && !isRateOrQuotaFailure(upstream))
                    .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorMap(
                    error -> generationStarted.get() && error instanceof UpstreamException upstream,
                    error -> ((UpstreamException) error).withGenerationStarted()
                );
        });
    }

    public void invalidateAllCaches() {
        modelCacheGeneration.incrementAndGet();
        codeWhispererModelCache.invalidateAll();
    }

    private Mono<PlatformCompletionResult> callFirstEndpoint(
        ProxyAccountEntity account,
        ObjectNode payload,
        String model,
        AtomicBoolean generationStarted
    ) {
        List<ProxyProperties.Endpoint> endpoints = sortedEndpoints();
        if (endpoints.isEmpty()) {
            return Mono.error(new UpstreamException(503, "No upstream endpoints configured", true));
        }
        return callEndpoint(account, payload, model, endpoints, 0, false, generationStarted);
    }

    private Mono<PlatformCompletionResult> callEndpoint(
        ProxyAccountEntity account,
        ObjectNode payload,
        String model,
        List<ProxyProperties.Endpoint> endpoints,
        int index,
        boolean refreshed,
        AtomicBoolean generationStarted
    ) {
        ProxyProperties.Endpoint endpoint = endpoints.get(index);
        return preparePayloadForEndpoint(account, payload, endpoint)
            .flatMap(endpointPayload -> {
                byte[] requestBytes = serialize(endpointPayload);
                return webClient.post()
                    .uri(endpoint.getUrl())
                    .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
                    .headers(headers -> applyHeaders(headers, account))
                    .bodyValue(requestBytes)
                    .exchangeToMono(response -> handleResponse(response, requestBytes, model, endpoint));
            })
            // Noted before the fallbacks below, which replace or swallow this error.
            .doOnError(error -> {
                if (error instanceof UpstreamException upstream && upstream.isGenerationStarted()) {
                    generationStarted.set(true);
                }
            })
            .onErrorResume(error -> {
                if (!refreshed && isInvalidBearerToken(error) && tokenRefreshService.canRefresh(account)) {
                    return tokenRefreshService.refresh(account)
                        .flatMap(refreshedAccount -> callFirstEndpoint(refreshedAccount, payload, model, true, generationStarted))
                        .onErrorResume(refreshError -> Mono.error(error));
                }
                if (index + 1 < endpoints.size() && isRecoverable(error)) {
                    return callEndpoint(account, payload, model, endpoints, index + 1, refreshed, generationStarted);
                }
                return Mono.error(error);
            });
    }

    private Mono<PlatformCompletionResult> callFirstEndpoint(
        ProxyAccountEntity account,
        ObjectNode payload,
        String model,
        boolean refreshed,
        AtomicBoolean generationStarted
    ) {
        List<ProxyProperties.Endpoint> endpoints = sortedEndpoints();
        if (endpoints.isEmpty()) {
            return Mono.error(new UpstreamException(503, "No upstream endpoints configured", true));
        }
        return callEndpoint(account, payload, model, endpoints, 0, refreshed, generationStarted);
    }

    private Mono<ObjectNode> preparePayloadForEndpoint(
        ProxyAccountEntity account,
        ObjectNode payload,
        ProxyProperties.Endpoint endpoint
    ) {
        ObjectNode copy = preparePayloadBase(payload, endpoint);
        if (!isCodeWhispererEndpoint(endpoint)) {
            return Mono.just(copy);
        }
        String requestedModelId = payloadModelId(copy);
        String profileArn = copy.path("profileArn").asString("");
        return resolveCodeWhispererModelId(account, requestedModelId, profileArn)
            .map(modelId -> {
                applyPayloadModelId(copy, modelId);
                return copy;
            });
    }

    static ObjectNode preparePayloadForEndpoint(ObjectNode payload, ProxyProperties.Endpoint endpoint) {
        ObjectNode copy = preparePayloadBase(payload, endpoint);
        if (isCodeWhispererEndpoint(endpoint)) {
            applyPayloadModelId(copy, codeWhispererModelId(payloadModelId(copy)));
        }
        return copy;
    }

    private static ObjectNode preparePayloadBase(ObjectNode payload, ProxyProperties.Endpoint endpoint) {
        ObjectNode copy = payload.deepCopy();
        String origin = endpoint.getOrigin();
        if (origin != null && !origin.isBlank()) {
            applyPayloadOrigin(copy, origin);
        }
        if (isAmazonQCliEndpoint(endpoint)) {
            removeAmazonQCliUnsupportedFields(copy);
        }
        return copy;
    }

    private Mono<PlatformCompletionResult> handleResponse(
        ClientResponse response,
        byte[] requestBytes,
        String model,
        ProxyProperties.Endpoint endpoint
    ) {
        HttpStatusCode status = response.statusCode();
        if (status.isError()) {
            return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(new UpstreamException(
                    status.value(),
                    "Kiro upstream error " + status.value() + " on " + endpoint.getName() + ": " + body,
                    isRecoverableStatus(status.value()), response.headers().asHttpHeaders().getFirst("Retry-After")
                )));
        }
        return response.bodyToMono(byte[].class)
            .map(bytes -> parser.parse(bytes, model, requestBytes));
    }

    private void applyHeaders(HttpHeaders headers, ProxyAccountEntity account) {
        String machineId = machineId(account);
        boolean idc = "idc".equalsIgnoreCase(account.getAuthMethod());
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + account.getAccessToken());
        headers.set("x-amzn-kiro-agent-mode", idc ? "vibe" : "spec");
        headers.set("user-agent", idc ? cliUserAgent() : kiroUserAgent(machineId));
        headers.set("x-amz-user-agent", idc ? cliAmzUserAgent() : kiroAmzUserAgent(machineId));
        headers.set("amz-sdk-invocation-id", UUID.randomUUID().toString());
        headers.set("amz-sdk-request", "attempt=1; max=3");
    }

    private void applyModelListHeaders(HttpHeaders headers, ProxyAccountEntity account) {
        String machineId = machineId(account);
        headers.setBearerAuth(account.getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.USER_AGENT, kiroUserAgent(machineId));
        headers.set("x-amz-user-agent", kiroAmzUserAgent(machineId));
        headers.set("x-amzn-codewhisperer-optout", "true");
    }

    private List<ProxyProperties.Endpoint> sortedEndpoints() {
        String preferred = properties.getKiro().getPreferredEndpoint();
        if ("amazonq-cli".equalsIgnoreCase(preferred)) {
            return properties.getKiro().getEndpoints().stream()
                .filter(KiroUpstreamClient::isAmazonQCliEndpoint)
                .toList();
        }
        return properties.getKiro().getEndpoints().stream()
            .filter(endpoint -> !isAmazonQCliEndpoint(endpoint))
            .sorted(Comparator.comparing(endpoint -> preferred != null && preferred.equalsIgnoreCase(endpoint.getName()) ? 0 : 1))
            .toList();
    }

    private boolean isRecoverable(Throwable error) {
        return error instanceof UpstreamException upstream
            && (upstream.isRecoverable() || isInvalidModelId(upstream));
    }

    private static boolean isRateOrQuotaFailure(UpstreamException error) {
        return error.getStatusCode() == 402 || error.getStatusCode() == 429;
    }

    private boolean isRecoverableStatus(int status) {
        return status == 402 || status == 429;
    }

    private boolean isInvalidModelId(UpstreamException error) {
        return error.getStatusCode() == 400
            && error.getMessage() != null
            && error.getMessage().contains("INVALID_MODEL_ID");
    }

    private boolean isInvalidBearerToken(Throwable error) {
        if (!(error instanceof UpstreamException upstream) || upstream.getStatusCode() != 403) {
            return false;
        }
        String message = upstream.getMessage();
        return message != null
            && message.toLowerCase(Locale.ROOT).contains("bearer token")
            && message.toLowerCase(Locale.ROOT).contains("invalid");
    }

    private byte[] serialize(ObjectNode payload) {
        return tech.starryapi.common.JsonNodes.toUtf8Bytes(payload);
    }

    private Mono<String> resolveCodeWhispererModelId(
        ProxyAccountEntity account,
        String requestedModelId,
        String profileArn
    ) {
        String requested = requestedModelId == null ? "" : requestedModelId.trim();
        if (isCodeWhispererModelId(requested)) {
            return Mono.just(requested);
        }
        if (requested.isBlank()) {
            return Mono.just(CODEWHISPERER_DEFAULT_MODEL_ID);
        }

        String cacheKey = modelCacheKey(account, profileArn);
        List<KiroModel> cached = codeWhispererModelCache.getIfPresent(cacheKey);
        long generation = modelCacheGeneration.get();
        Mono<List<KiroModel>> models = cached == null
            ? fetchCodeWhispererModels(account, profileArn)
                .doOnNext(list -> {
                    if (!list.isEmpty() && modelCacheGeneration.get() == generation) {
                        codeWhispererModelCache.put(cacheKey, list);
                    }
                })
            : Mono.just(cached);

        return models
            .map(list -> codeWhispererModelIdFromList(list, requested))
            .defaultIfEmpty(CODEWHISPERER_DEFAULT_MODEL_ID)
            .onErrorReturn(CODEWHISPERER_DEFAULT_MODEL_ID);
    }

    private String codeWhispererModelIdFromList(List<KiroModel> models, String requestedModelId) {
        return models.stream()
            .filter(model -> matchesRequestedModel(model, requestedModelId))
            .findFirst()
            .map(KiroModel::modelId)
            .or(() -> models.stream()
                .filter(model -> modelTokens(model.modelId() + " " + model.modelName()).contains("sonnet"))
                .findFirst()
                .map(KiroModel::modelId))
            .or(() -> models.stream().findFirst().map(KiroModel::modelId))
            .orElse(CODEWHISPERER_DEFAULT_MODEL_ID);
    }

    private Mono<List<KiroModel>> fetchCodeWhispererModels(ProxyAccountEntity account, String profileArn) {
        return fetchCodeWhispererModelPage(account, profileArn, null, new ArrayList<>());
    }

    private Mono<List<KiroModel>> fetchCodeWhispererModelPage(
        ProxyAccountEntity account,
        String profileArn,
        String nextToken,
        List<KiroModel> models
    ) {
        String url = modelListUrl(account.getRegion(), profileArn, nextToken);
        return webClient.get()
            .uri(url)
            .attribute(tech.starryapi.egress.AccountEgressTransport.ACCOUNT, account)
            .headers(headers -> applyModelListHeaders(headers, account))
            .exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    return response.releaseBody().thenReturn(models);
                }
                return response.bodyToMono(JsonNode.class)
                    .defaultIfEmpty(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode())
                    .flatMap(body -> {
                        JsonNode items = body.path("models");
                        if (items.isArray()) {
                            for (JsonNode item : items) {
                                String modelId = item.path("modelId").asString("");
                                if (!modelId.isBlank()) {
                                    models.add(new KiroModel(modelId, item.path("modelName").asString("")));
                                }
                            }
                        }
                        String token = body.path("nextToken").asString("");
                        if (token.isBlank()) {
                            return Mono.just(models);
                        }
                        return fetchCodeWhispererModelPage(account, profileArn, token, models);
                    });
            });
    }

    private String modelListUrl(String region, String profileArn, String nextToken) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(qServiceEndpoint(region))
            .path("/ListAvailableModels")
            .queryParam("origin", "AI_EDITOR")
            .queryParam("maxResults", "50")
            .queryParam("profileArn", profileArn);
        if (nextToken != null && !nextToken.isBlank()) {
            builder.queryParam("nextToken", nextToken);
        }
        return builder.build().encode().toUriString();
    }

    private static void applyPayloadOrigin(ObjectNode payload, String origin) {
        updateUserInputMessages(payload, userInput -> userInput.put("origin", origin));
    }

    private static void applyPayloadModelId(ObjectNode payload, String modelId) {
        updateUserInputMessages(payload, userInput -> userInput.put("modelId", modelId));
    }

    private static void updateUserInputMessages(ObjectNode payload, Consumer<ObjectNode> updater) {
        JsonNode conversationState = payload.path("conversationState");
        JsonNode currentUserInput = conversationState.path("currentMessage").path("userInputMessage");
        if (currentUserInput instanceof ObjectNode userInput) {
            updater.accept(userInput);
        }
        JsonNode history = conversationState.path("history");
        if (!history.isArray()) {
            return;
        }
        for (JsonNode message : history) {
            JsonNode historyUserInput = message.path("userInputMessage");
            if (historyUserInput instanceof ObjectNode userInput) {
                updater.accept(userInput);
            }
        }
    }

    private static void removeAmazonQCliUnsupportedFields(ObjectNode payload) {
        JsonNode conversationState = payload.path("conversationState");
        if (conversationState instanceof ObjectNode state) {
            state.remove(List.of("agentContinuationId", "agentTaskType"));
        }
    }

    private static String payloadModelId(ObjectNode payload) {
        JsonNode currentModelId = payload.path("conversationState")
            .path("currentMessage")
            .path("userInputMessage")
            .path("modelId");
        if (currentModelId.isString() && !currentModelId.asString("").isBlank()) {
            return currentModelId.asString("");
        }
        JsonNode history = payload.path("conversationState").path("history");
        if (history.isArray()) {
            for (JsonNode message : history) {
                JsonNode historyModelId = message.path("userInputMessage").path("modelId");
                if (historyModelId.isString() && !historyModelId.asString("").isBlank()) {
                    return historyModelId.asString("");
                }
            }
        }
        return null;
    }

    private static String codeWhispererModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return CODEWHISPERER_DEFAULT_MODEL_ID;
        }
        String trimmed = modelId.trim();
        if (isCodeWhispererModelId(trimmed)) {
            return trimmed;
        }
        return CODEWHISPERER_DEFAULT_MODEL_ID;
    }

    private static boolean isCodeWhispererModelId(String modelId) {
        return modelId.matches("[A-Z0-9_]+") && modelId.contains("_");
    }

    private static String normalizeModelKey(String modelId) {
        return modelId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static boolean matchesRequestedModel(KiroModel model, String requestedModelId) {
        String requestedKey = normalizeModelKey(requestedModelId);
        String modelIdKey = normalizeModelKey(model.modelId());
        if (modelIdKey.equals(requestedKey) || modelIdKey.contains(requestedKey)) {
            return true;
        }
        String modelNameKey = normalizeModelKey(model.modelName());
        if (modelNameKey.contains(requestedKey)) {
            return true;
        }

        List<String> tokens = modelTokens(requestedModelId).stream()
            .filter(token -> !"latest".equals(token) && !"model".equals(token))
            .toList();
        if (tokens.isEmpty()) {
            return false;
        }
        List<String> candidateTokens = modelTokens(model.modelId() + " " + model.modelName());
        if (!tokens.stream().allMatch(candidateTokens::contains)) {
            return false;
        }
        for (String family : List.of("opus", "sonnet", "haiku")) {
            if (tokens.contains(family) && !candidateTokens.contains(family)) {
                return false;
            }
            if (!tokens.contains(family) && candidateTokens.contains(family)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> modelTokens(String value) {
        return List.of(value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).stream()
            .filter(token -> !token.isBlank())
            .toList();
    }

    private static boolean isCodeWhispererEndpoint(ProxyProperties.Endpoint endpoint) {
        return "codewhisperer".equalsIgnoreCase(endpoint.getName());
    }

    private static boolean isAmazonQCliEndpoint(ProxyProperties.Endpoint endpoint) {
        return "amazonq-cli".equalsIgnoreCase(endpoint.getName());
    }

    private String machineId(ProxyAccountEntity account) {
        if (account.getMachineId() != null && !account.getMachineId().isBlank()) {
            return account.getMachineId();
        }
        return Hashing.sha256("kiro-device-" + account.getAccountId());
    }

    private String modelCacheKey(ProxyAccountEntity account, String profileArn) {
        return account.getAccountId() + ":" + firstNonBlank(account.getRegion(), "us-east-1") + ":" + firstNonBlank(profileArn, "");
    }

    private String qServiceEndpoint(String region) {
        return firstNonBlank(region, "us-east-1").startsWith("eu-")
            ? "https://q.eu-central-1.amazonaws.com"
            : "https://q.us-east-1.amazonaws.com";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String kiroUserAgent(String machineId) {
        String os = System.getProperty("os.name", "linux").toLowerCase().contains("mac") ? "macos" : "linux";
        String release = System.getProperty("os.version", "0.0.0");
        String javaVersion = System.getProperty("java.version", "17");
        return "aws-sdk-js/%s ua/2.1 os/%s#%s lang/js md/nodejs#%s api/codewhispererstreaming#%s m/E KiroIDE-%s-%s"
            .formatted(AWS_SDK_VERSION, os, release, javaVersion, AWS_STREAMING_API_VERSION, KIRO_VERSION, machineId);
    }

    private String kiroAmzUserAgent(String machineId) {
        return "aws-sdk-js/%s KiroIDE %s %s".formatted(AWS_SDK_VERSION, KIRO_VERSION, machineId);
    }

    private String cliUserAgent() {
        return "aws-sdk-rust/1.3.9 os/macos lang/rust/1.87.0";
    }

    private String cliAmzUserAgent() {
        return "aws-sdk-rust/1.3.9 ua/2.1 api/ssooidc/1.88.0 os/macos lang/rust/1.87.0 m/E app/AmazonQ-For-CLI";
    }

    private record KiroModel(String modelId, String modelName) {
    }
}
