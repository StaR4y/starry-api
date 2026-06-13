package xyz.star4y.api.platform.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.star4y.api.account.AccountPlatforms;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.platform.core.PlatformCompletionResult;

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
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, WebClient.builder().build());
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
            assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-4-5-20250929");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
            assertThat(body.path("system").asText()).isEqualTo("Use short replies.");
            assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
            assertThat(body.path("messages").path(0).path("content").path(0).path("type").asText()).isEqualTo("text");
            assertThat(body.path("messages").path(0).path("content").path(0).path("text").asText()).isEqualTo("ping");
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
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, WebClient.builder().build());
            ProxyAccountEntity account = anthropicAccount("http://127.0.0.1:" + server.getAddress().getPort());

            PlatformCompletionResult result = client.testClaudeCode(account, "claude-sonnet-4.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(apiKey.get()).containsExactly("sk-ant-test");
            assertThat(authorization.get()).isNull();
            assertThat(beta.get()).containsExactly("claude-code-20250219,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14");
            assertThat(query.get()).isEqualTo("beta=true");

            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asText()).isEqualTo("claude-sonnet-4-5-20250929");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
            assertThat(body.path("temperature").asInt()).isEqualTo(1);
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("messages").path(0).path("content").path(0).path("text").asText()).isEqualTo("hi");
            assertThat(body.path("messages").path(0).path("content").path(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
            assertThat(body.path("system").path(0).path("text").asText()).isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.");
            assertThat(body.path("system").path(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
            JsonNode userId = objectMapper.readTree(body.path("metadata").path("user_id").asText());
            assertThat(userId.path("device_id").asText()).hasSize(64);
            assertThat(userId.path("account_uuid").asText()).isEmpty();
            assertThat(userId.path("session_id").asText()).isNotBlank();
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
            AnthropicUpstreamClient client = new AnthropicUpstreamClient(objectMapper, WebClient.builder().build());
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
                    assertThat(tool.input().path("command").asText()).isEqualTo("pwd");
                });
        } finally {
            server.stop(0);
        }
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
