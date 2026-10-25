package tech.starryapi.platform.kiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountTokenRefreshService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class KiroUpstreamClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preparesCodeWhispererPayloadWithInternalModelAndEndpointOrigin() {
        ObjectNode payload = payload("claude-haiku-4.5", "ADMIN_TEST");
        ProxyProperties.Endpoint endpoint = endpoint("codewhisperer", "AI_EDITOR");

        ObjectNode prepared = KiroUpstreamClient.preparePayloadForEndpoint(payload, endpoint);

        assertThat(currentUserInput(prepared).path("origin").asString("")).isEqualTo("AI_EDITOR");
        assertThat(currentUserInput(prepared).path("modelId").asString("")).isEqualTo("CLAUDE_SONNET_4_20250514_V1_0");
        assertThat(historyUserInput(prepared).path("origin").asString("")).isEqualTo("AI_EDITOR");
        assertThat(historyUserInput(prepared).path("modelId").asString("")).isEqualTo("CLAUDE_SONNET_4_20250514_V1_0");
        assertThat(currentUserInput(payload).path("origin").asString("")).isEqualTo("ADMIN_TEST");
        assertThat(currentUserInput(payload).path("modelId").asString("")).isEqualTo("claude-haiku-4.5");
    }

    @Test
    void preparesAmazonQCliPayloadWithoutUnsupportedConversationFields() {
        ObjectNode payload = payload("claude-sonnet-4.5", "AI_EDITOR");
        ProxyProperties.Endpoint endpoint = endpoint("amazonq-cli", "CLI");

        ObjectNode prepared = KiroUpstreamClient.preparePayloadForEndpoint(payload, endpoint);

        assertThat(prepared.path("conversationState").has("agentContinuationId")).isFalse();
        assertThat(prepared.path("conversationState").has("agentTaskType")).isFalse();
        assertThat(currentUserInput(prepared).path("origin").asString("")).isEqualTo("CLI");
        assertThat(currentUserInput(prepared).path("modelId").asString("")).isEqualTo("claude-sonnet-4.5");
        assertThat(payload.path("conversationState").has("agentContinuationId")).isTrue();
        assertThat(payload.path("conversationState").has("agentTaskType")).isTrue();
    }

    @Test
    void refreshesInvalidBearerTokenAndRetriesWithNewAccountToken() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> retryAuthorization = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer old-access");
                return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                    .header("Content-Type", "application/json")
                    .body("""
                        {"message":"The bearer token included in the request is invalid.","reason":null}
                        """)
                    .build());
            }
            retryAuthorization.set(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/octet-stream")
                .body("ok")
                .build());
        };
        AwsEventStreamParser parser = mock(AwsEventStreamParser.class);
        AccountTokenRefreshService refreshService = mock(AccountTokenRefreshService.class);
        ProxyAccountEntity account = account("old-access");
        ProxyAccountEntity refreshed = account("new-access");

        when(refreshService.canRefresh(account)).thenReturn(true);
        when(refreshService.refresh(account)).thenReturn(Mono.just(refreshed));
        when(parser.parse(any(byte[].class), eq("auto"), any(byte[].class)))
            .thenReturn(new PlatformCompletionResult("OK", PlatformUsage.zero()));

        KiroUpstreamClient client = new KiroUpstreamClient(
            WebClient.builder().exchangeFunction(exchange).build(),
            properties(endpoint("amazonq", "AI_EDITOR")),
            parser,
            refreshService
        );

        PlatformCompletionResult result = client.complete(account, payload("auto", "AI_EDITOR"), "auto").block();

        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("OK");
        assertThat(calls).hasValue(2);
        assertThat(retryAuthorization).hasValue("Bearer new-access");
    }

    @Test
    void doesNotRefreshInvalidBearerTokenWhenAccountCannotRefresh() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
            .header("Content-Type", "application/json")
            .body("""
                {"message":"The bearer token included in the request is invalid.","reason":null}
                """)
            .build());
        AwsEventStreamParser parser = mock(AwsEventStreamParser.class);
        AccountTokenRefreshService refreshService = mock(AccountTokenRefreshService.class);
        ProxyAccountEntity account = account("old-access");

        when(refreshService.canRefresh(account)).thenReturn(false);

        KiroUpstreamClient client = new KiroUpstreamClient(
            WebClient.builder().exchangeFunction(exchange).build(),
            properties(endpoint("amazonq", "AI_EDITOR")),
            parser,
            refreshService
        );

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
            client.complete(account, payload("auto", "AI_EDITOR"), "auto").block()
        );

        assertThat(failure).isInstanceOf(UpstreamException.class);
        assertThat(failure.getMessage()).contains("bearer token").contains("invalid");
        verify(refreshService, never()).refresh(account);
    }

    @Test
    void anEndpointThatStreamedOutputBeforeFailingStillMarksTheErrorOfTheNextEndpoint() {
        // First endpoint: the stream starts and is cut. Second endpoint: a plain HTTP 500 that never parsed a frame.
        ExchangeFunction exchange = request -> "one.example.com".equals(request.url().getHost())
            ? Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/octet-stream").body("cut").build())
            : Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).body("boom").build());
        AwsEventStreamParser parser = mock(AwsEventStreamParser.class);
        when(parser.parse(any(byte[].class), eq("auto"), any(byte[].class)))
            .thenThrow(new UpstreamException(502, "Kiro upstream event stream was truncated", true, null, true));
        ProxyAccountEntity account = account("access");
        KiroUpstreamClient client = new KiroUpstreamClient(
            WebClient.builder().exchangeFunction(exchange).build(),
            properties(0, endpoint("primary", "AI_EDITOR", "https://one.example.com"),
                endpoint("secondary", "AI_EDITOR", "https://two.example.com")),
            parser,
            mock(AccountTokenRefreshService.class)
        );

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
            client.complete(account, payload("auto", "AI_EDITOR"), "auto").block()
        );

        assertThat(failure).isInstanceOf(UpstreamException.class);
        UpstreamException upstream = (UpstreamException) failure;
        assertThat(upstream.isGenerationStarted()).isTrue();
        assertThat(upstream.getStatusCode()).isEqualTo(500);
        assertThat(upstream.isRecoverable()).isFalse();
        assertThat(upstream.getMessage()).contains("Kiro upstream error 500 on secondary");
    }

    @Test
    void aRetryThatFailsBeforeAnyFrameStillCarriesTheStartOfTheEarlierAttempt() {
        // Attempt 1 streams content and ends in a 503 frame, so it is retried. Attempt 2 meets a 429 frame first.
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "application/octet-stream")
            .body("frames")
            .build());
        AwsEventStreamParser parser = mock(AwsEventStreamParser.class);
        when(parser.parse(any(byte[].class), eq("auto"), any(byte[].class)))
            .thenThrow(new UpstreamException(503, "Kiro upstream exception ServiceUnavailableException", true, null, true))
            .thenThrow(new UpstreamException(429, "Kiro upstream exception ThrottlingException", true, "7", false));
        ProxyAccountEntity account = account("access");
        KiroUpstreamClient client = new KiroUpstreamClient(
            WebClient.builder().exchangeFunction(exchange).build(),
            properties(2, endpoint("primary", "AI_EDITOR", "https://one.example.com")),
            parser,
            mock(AccountTokenRefreshService.class)
        );

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
            client.complete(account, payload("auto", "AI_EDITOR"), "auto").block()
        );

        assertThat(failure).isInstanceOf(UpstreamException.class);
        UpstreamException upstream = (UpstreamException) failure;
        assertThat(upstream.isGenerationStarted()).isTrue();
        assertThat(upstream.getStatusCode()).isEqualTo(429);
        assertThat(upstream.isRecoverable()).isTrue();
        assertThat(UpstreamException.retryAfterSeconds(upstream)).isEqualTo(7);
        assertThat(upstream.getMessage()).contains("ThrottlingException");
        verify(parser, org.mockito.Mockito.times(2)).parse(any(byte[].class), eq("auto"), any(byte[].class));
    }

    @Test
    void aGenerationAfterATokenRefreshIsNotLostWhenTheOriginalErrorIsThrown() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> calls.incrementAndGet() == 1
            ? Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                .header("Content-Type", "application/json")
                .body("{\"message\":\"The bearer token included in the request is invalid.\",\"reason\":null}")
                .build())
            : Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/octet-stream").body("cut").build());
        AwsEventStreamParser parser = mock(AwsEventStreamParser.class);
        AccountTokenRefreshService refreshService = mock(AccountTokenRefreshService.class);
        ProxyAccountEntity account = account("old-access");
        when(refreshService.canRefresh(account)).thenReturn(true);
        when(refreshService.refresh(account)).thenReturn(Mono.just(account("new-access")));
        when(parser.parse(any(byte[].class), eq("auto"), any(byte[].class)))
            .thenThrow(new UpstreamException(502, "Kiro upstream event stream was truncated", true, null, true));
        KiroUpstreamClient client = new KiroUpstreamClient(
            WebClient.builder().exchangeFunction(exchange).build(),
            properties(0, endpoint("primary", "AI_EDITOR", "https://one.example.com")),
            parser,
            refreshService
        );

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
            client.complete(account, payload("auto", "AI_EDITOR"), "auto").block()
        );

        assertThat(failure).isInstanceOf(UpstreamException.class);
        UpstreamException upstream = (UpstreamException) failure;
        assertThat(upstream.getStatusCode()).isEqualTo(403);
        assertThat(upstream.isGenerationStarted()).isTrue();
    }

    @Test
    void anErrorNoAttemptStartedIsThrownAsItIs() {
        UpstreamException original = new UpstreamException(503, "Kiro upstream exception ServiceUnavailableException", true);
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", "application/octet-stream")
            .body("frames")
            .build());
        AwsEventStreamParser parser = mock(AwsEventStreamParser.class);
        when(parser.parse(any(byte[].class), eq("auto"), any(byte[].class))).thenThrow(original);
        ProxyAccountEntity account = account("access");
        KiroUpstreamClient client = new KiroUpstreamClient(
            WebClient.builder().exchangeFunction(exchange).build(),
            properties(0, endpoint("primary", "AI_EDITOR", "https://one.example.com"),
                endpoint("secondary", "AI_EDITOR", "https://two.example.com")),
            parser,
            mock(AccountTokenRefreshService.class)
        );

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
            client.complete(account, payload("auto", "AI_EDITOR"), "auto").block()
        );

        assertThat(failure).isSameAs(original);
        assertThat(((UpstreamException) failure).isGenerationStarted()).isFalse();
    }

    private ObjectNode payload(String modelId, String origin) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode state = root.putObject("conversationState");
        state.put("agentContinuationId", "continuation-1");
        state.put("agentTaskType", "vibe");
        ObjectNode current = state.putObject("currentMessage").putObject("userInputMessage");
        current.put("content", "ping");
        current.put("modelId", modelId);
        current.put("origin", origin);
        ObjectNode history = state.putArray("history").addObject().putObject("userInputMessage");
        history.put("content", "hello");
        history.put("modelId", modelId);
        history.put("origin", origin);
        return root;
    }

    private ProxyProperties.Endpoint endpoint(String name, String origin) {
        ProxyProperties.Endpoint endpoint = new ProxyProperties.Endpoint();
        endpoint.setName(name);
        endpoint.setOrigin(origin);
        endpoint.setUrl("https://example.com");
        return endpoint;
    }

    private ProxyProperties.Endpoint endpoint(String name, String origin, String url) {
        ProxyProperties.Endpoint endpoint = endpoint(name, origin);
        endpoint.setUrl(url);
        return endpoint;
    }

    private ProxyProperties properties(int maxRetries, ProxyProperties.Endpoint... endpoints) {
        ProxyProperties properties = properties(endpoints[0]);
        properties.getKiro().setEndpoints(List.of(endpoints));
        properties.setMaxRetries(maxRetries);
        return properties;
    }

    private ProxyProperties properties(ProxyProperties.Endpoint endpoint) {
        ProxyProperties properties = new ProxyProperties();
        ProxyProperties.Kiro kiro = new ProxyProperties.Kiro();
        kiro.setPreferredEndpoint(endpoint.getName());
        kiro.setEndpoints(List.of(endpoint));
        properties.setKiro(kiro);
        properties.setMaxRetries(0);
        return properties;
    }

    private ProxyAccountEntity account(String accessToken) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("kiro-main");
        account.setAccessToken(accessToken);
        account.setAuthMethod("idc");
        account.setRegion("us-east-1");
        return account;
    }

    private ObjectNode currentUserInput(ObjectNode payload) {
        return (ObjectNode) payload.path("conversationState").path("currentMessage").path("userInputMessage");
    }

    private ObjectNode historyUserInput(ObjectNode payload) {
        return (ObjectNode) payload.path("conversationState").path("history").get(0).path("userInputMessage");
    }
}
