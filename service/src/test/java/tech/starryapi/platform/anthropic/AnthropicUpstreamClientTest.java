package tech.starryapi.platform.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AnthropicUpstreamClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completeSendsNativeAnthropicMessagesRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<List<String>> apiKey = new AtomicReference<>();
        AtomicReference<List<String>> authorization = new AtomicReference<>();
        AtomicReference<List<String>> beta = new AtomicReference<>();
        AtomicReference<List<String>> userAgent = new AtomicReference<>();
        AtomicReference<List<String>> version = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().get("x-api-key"));
            authorization.set(exchange.getRequestHeaders().get(HttpHeaders.AUTHORIZATION));
            beta.set(exchange.getRequestHeaders().get("anthropic-beta"));
            userAgent.set(exchange.getRequestHeaders().get(HttpHeaders.USER_AGENT));
            version.set(exchange.getRequestHeaders().get("anthropic-version"));
            byte[] response = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "OK"}],
                  "usage": {"input_tokens": 3, "output_tokens": 2}
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, ProductionWebClients.builder().build());
            ProxyAccountEntity account = anthropicAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "anthropic/claude-sonnet-4.5",
                  "max_tokens": 64,
                  "temperature": 0.2,
                  "messages": [
                    {"role": "system", "content": "Use short replies."},
                    {"role": "user", "content": [{"type": "text", "text": "ping"}]}
                  ]
                }
                """);

            PlatformCompletionResult result = client.complete(account, request, "anthropic/claude-sonnet-4.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(result.usage().inputTokens()).isEqualTo(3);
            assertThat(result.usage().outputTokens()).isEqualTo(2);
            assertThat(apiKey.get()).containsExactly("sk-ant-test");
            assertThat(authorization.get()).isNull();
            assertThat(beta.get()).containsExactly("claude-code-20250219,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14");
            assertThat(userAgent.get()).containsExactly("claude-cli/2.1.161 (external, cli)");
            assertThat(version.get()).containsExactly("2023-06-01");
            assertThat(query.get()).isEqualTo("beta=true");

            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asString("")).isEqualTo("claude-sonnet-4-5-20250929");
            assertThat(body.path("max_tokens").asInt(0)).isEqualTo(64);
            assertThat(body.path("system").asString("")).isEqualTo("Use short replies.");
            assertThat(body.path("messages").path(0).path("role").asString("")).isEqualTo("user");
            assertThat(body.path("messages").path(0).path("content").path(0).path("type").asString("")).isEqualTo("text");
            assertThat(body.path("messages").path(0).path("content").path(0).path("text").asString("")).isEqualTo("ping");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testClaudeCodeSendsSub2ApiStylePayload() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<List<String>> apiKey = new AtomicReference<>();
        AtomicReference<List<String>> authorization = new AtomicReference<>();
        AtomicReference<List<String>> beta = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().get("x-api-key"));
            authorization.set(exchange.getRequestHeaders().get(HttpHeaders.AUTHORIZATION));
            beta.set(exchange.getRequestHeaders().get("anthropic-beta"));
            byte[] response = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "OK"}],
                  "usage": {"input_tokens": 3, "output_tokens": 2}
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, ProductionWebClients.builder().build());
            ProxyAccountEntity account = anthropicAccount("http://127.0.0.1:" + server.getAddress().getPort());

            PlatformCompletionResult result = client.testClaudeCode(account, "claude-sonnet-4.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(apiKey.get()).containsExactly("sk-ant-test");
            assertThat(authorization.get()).isNull();
            assertThat(beta.get()).containsExactly("claude-code-20250219,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14");
            assertThat(query.get()).isEqualTo("beta=true");

            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asString("")).isEqualTo("claude-sonnet-4-5-20250929");
            assertThat(body.path("max_tokens").asInt(0)).isEqualTo(1024);
            assertThat(body.path("temperature").asInt(0)).isEqualTo(1);
            assertThat(body.path("stream").asBoolean(false)).isTrue();
            assertThat(body.path("messages").path(0).path("content").path(0).path("text").asString("")).isEqualTo("hi");
            assertThat(body.path("messages").path(0).path("content").path(0).path("cache_control").path("type").asString("")).isEqualTo("ephemeral");
            assertThat(body.path("system").path(0).path("text").asString("")).isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
            assertThat(body.path("system").path(0).path("cache_control").path("type").asString("")).isEqualTo("ephemeral");
            JsonNode userId = objectMapper.readTree(body.path("metadata").path("user_id").asString(""));
            assertThat(userId.path("device_id").asString("")).hasSize(64);
            assertThat(userId.path("account_uuid").asString("")).isEmpty();
            assertThat(userId.path("session_id").asString("")).isNotBlank();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void completeParsesToolUseFromStream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":4,"output_tokens":0}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"I will check."}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"Bash","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"command\\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\\"pwd\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":3}}

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, ProductionWebClients.builder().build());
            ProxyAccountEntity account = anthropicAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {"model":"anthropic/claude-sonnet-4.5","stream":true,"messages":[{"role":"user","content":"ping"}]}
                """);

            PlatformCompletionResult result = client.complete(account, request, "anthropic/claude-sonnet-4.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("I will check.");
            assertThat(result.usage().inputTokens()).isEqualTo(4);
            assertThat(result.usage().outputTokens()).isEqualTo(3);
            assertThat(result.toolUses()).singleElement()
                .satisfies(tool -> {
                    assertThat(tool.id()).isEqualTo("toolu_1");
                    assertThat(tool.name()).isEqualTo("Bash");
                    assertThat(tool.input().path("command").asString("")).isEqualTo("pwd");
                });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheReadAndWriteTokensAreIncludedInTheBilledInput() throws Exception {
        HttpServer server = streamServer("""
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":12,"cache_read_input_tokens":4000,"cache_creation_input_tokens":300,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":55}}

            event: message_stop
            data: {"type":"message_stop"}

            """);
        try {
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, ProductionWebClients.builder().build());
            PlatformCompletionResult result = client.complete(
                anthropicAccount("http://127.0.0.1:" + server.getAddress().getPort()),
                objectMapper.readTree("{\"model\":\"anthropic/claude-sonnet-4.5\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}"),
                "anthropic/claude-sonnet-4.5"
            ).block();

            assertThat(result.content()).isEqualTo("hi");
            // input_tokens (12) excludes the cached part; the platform convention is total input with cache subsets.
            assertThat(result.usage().inputTokens()).isEqualTo(12 + 4000 + 300);
            assertThat(result.usage().cacheReadTokens()).isEqualTo(4000);
            assertThat(result.usage().cacheWriteTokens()).isEqualTo(300);
            assertThat(result.usage().outputTokens()).isEqualTo(55);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anErrorEventInsideAnOkStreamIsAnUpstreamFailureNotASuccess() throws Exception {
        HttpServer server = streamServer("""
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}

            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

            """);
        try {
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, ProductionWebClients.builder().build());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.complete(
                anthropicAccount("http://127.0.0.1:" + server.getAddress().getPort()),
                objectMapper.readTree("{\"model\":\"anthropic/claude-sonnet-4.5\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}"),
                "anthropic/claude-sonnet-4.5"
            ).block())
                .isInstanceOfSatisfying(tech.starryapi.platform.upstream.UpstreamException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(529);
                    assertThat(error.isRecoverable()).isTrue();
                    // The partial text was generated: the answer must not be generated again by another source.
                    assertThat(error.isGenerationStarted()).isTrue();
                });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anErrorEventAfterAToolCallBeganSaysTheGenerationStarted() throws Exception {
        UpstreamException failure = failureOf("""
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}}

            event: error
            data: {"type":"error","error":{"type":"api_error","message":"Internal server error"}}

            """);

        assertThat(failure.getStatusCode()).isEqualTo(500);
        assertThat(failure.isRecoverable()).isTrue();
        assertThat(failure.isGenerationStarted()).isTrue();
    }

    @Test
    void anErrorEventAfterAThinkingDeltaSaysTheGenerationStarted() throws Exception {
        UpstreamException failure = failureOf("""
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think"}}

            event: error
            data: {"type":"error","error":{"type":"timeout_error","message":"Request timed out"}}

            """);

        assertThat(failure.getStatusCode()).isEqualTo(504);
        assertThat(failure.isGenerationStarted()).isTrue();
    }

    @Test
    void anErrorEventAfterTheMessageReportedAnOutputTokenSaysTheGenerationStarted() throws Exception {
        UpstreamException failure = failureOf("""
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}

            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

            """);

        assertThat(failure.getStatusCode()).isEqualTo(529);
        assertThat(failure.isGenerationStarted()).isTrue();
    }

    @Test
    void anErrorEventBeforeAnyOutputLeavesTheGenerationNotStarted() throws Exception {
        UpstreamException failure = failureOf("""
            event: ping
            data: {"type":"ping"}

            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}

            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

            """);

        // Nothing was generated, so another source may still serve the request once.
        assertThat(failure.getStatusCode()).isEqualTo(529);
        assertThat(failure.isRecoverable()).isTrue();
        assertThat(failure.isGenerationStarted()).isFalse();
    }

    @Test
    void anErrorEventAsTheVeryFirstEventLeavesTheGenerationNotStarted() throws Exception {
        UpstreamException failure = failureOf("""
            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

            """);

        assertThat(failure.isGenerationStarted()).isFalse();
    }

    private UpstreamException failureOf(String sse) throws Exception {
        HttpServer server = streamServer(sse);
        try {
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, ProductionWebClients.builder().build());
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> client.complete(
                anthropicAccount("http://127.0.0.1:" + server.getAddress().getPort()),
                objectMapper.readTree("{\"model\":\"anthropic/claude-sonnet-4.5\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}"),
                "anthropic/claude-sonnet-4.5"
            ).block());
            assertThat(failure).isInstanceOf(UpstreamException.class);
            return (UpstreamException) failure;
        } finally {
            server.stop(0);
        }
    }

    private HttpServer streamServer(String sse) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private ProxyAccountEntity anthropicAccount(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("anthropic-main");
        account.setAccessToken("sk-ant-test");
        account.setProvider(AccountPlatforms.ANTHROPIC_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl(baseUrl);
        account.setEnabled(true);
        return account;
    }
}
