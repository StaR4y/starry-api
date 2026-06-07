package xyz.star4y.api.platform.kiro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import xyz.star4y.api.account.AccountTokenRefreshService;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.config.ProxyProperties;
import xyz.star4y.api.platform.kiro.AwsEventStreamParser;
import xyz.star4y.api.platform.core.PlatformCompletionResult;
import xyz.star4y.api.platform.kiro.KiroUpstreamClient;
import xyz.star4y.api.platform.core.PlatformUsage;
import xyz.star4y.api.platform.upstream.UpstreamException;

class KiroUpstreamClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preparesCodeWhispererPayloadWithInternalModelAndEndpointOrigin() {
        ObjectNode payload = payload("claude-haiku-4.5", "ADMIN_TEST");
        ProxyProperties.Endpoint endpoint = endpoint("codewhisperer", "AI_EDITOR");

        ObjectNode prepared = KiroUpstreamClient.preparePayloadForEndpoint(payload, endpoint);

        assertThat(currentUserInput(prepared).path("origin").asText()).isEqualTo("AI_EDITOR");
        assertThat(currentUserInput(prepared).path("modelId").asText()).isEqualTo("CLAUDE_SONNET_4_20250514_V1_0");
        assertThat(historyUserInput(prepared).path("origin").asText()).isEqualTo("AI_EDITOR");
        assertThat(historyUserInput(prepared).path("modelId").asText()).isEqualTo("CLAUDE_SONNET_4_20250514_V1_0");
        assertThat(currentUserInput(payload).path("origin").asText()).isEqualTo("ADMIN_TEST");
        assertThat(currentUserInput(payload).path("modelId").asText()).isEqualTo("claude-haiku-4.5");
    }

    @Test
    void preparesAmazonQCliPayloadWithoutUnsupportedConversationFields() {
        ObjectNode payload = payload("claude-sonnet-4.5", "AI_EDITOR");
        ProxyProperties.Endpoint endpoint = endpoint("amazonq-cli", "CLI");

        ObjectNode prepared = KiroUpstreamClient.preparePayloadForEndpoint(payload, endpoint);

        assertThat(prepared.path("conversationState").has("agentContinuationId")).isFalse();
        assertThat(prepared.path("conversationState").has("agentTaskType")).isFalse();
        assertThat(currentUserInput(prepared).path("origin").asText()).isEqualTo("CLI");
        assertThat(currentUserInput(prepared).path("modelId").asText()).isEqualTo("claude-sonnet-4.5");
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

    private ProxyProperties properties(ProxyProperties.Endpoint endpoint) {
        ProxyProperties properties = new ProxyProperties();
        ProxyProperties.Upstream upstream = new ProxyProperties.Upstream();
        upstream.setPreferredEndpoint(endpoint.getName());
        upstream.setEndpoints(List.of(endpoint));
        properties.setUpstream(upstream);
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
