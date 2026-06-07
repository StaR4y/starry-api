package xyz.star4y.api.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.star4y.api.account.AccountPlatforms;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.platform.core.PlatformCompletionResult;

class OpenAiUpstreamClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void codexUpstreamUsesOutputTextForAssistantHistoryAndPreservesToolItems() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":3,"output_tokens":1},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(objectMapper, WebClient.builder().build());
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.4-mini",
                  "messages": [
                    {"role": "system", "content": "Use short replies."},
                    {"role": "user", "content": [{"type": "text", "text": "first"}]},
                    {"role": "assistant", "content": [{"type": "text", "text": "answer"}]},
                    {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [
                        {
                          "id": "call_1",
                          "type": "function",
                          "function": {"name": "Bash", "arguments": "{\\"command\\":\\"pwd\\"}"}
                        }
                      ]
                    },
                    {"role": "tool", "tool_call_id": "call_1", "content": "/tmp/project"},
                    {"role": "user", "content": "next"}
                  ]
                }
                """);

            PlatformCompletionResult result = client.complete(account, request, "gpt-5.4-mini").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("instructions").asText()).isEqualTo("Use short replies.");
            JsonNode input = body.path("input");
            assertThat(input.size()).isEqualTo(5);
            assertThat(input.path(0).path("role").asText()).isEqualTo("user");
            assertThat(input.path(0).path("content").path(0).path("type").asText()).isEqualTo("input_text");
            assertThat(input.path(1).path("role").asText()).isEqualTo("assistant");
            assertThat(input.path(1).path("content").path(0).path("type").asText()).isEqualTo("output_text");
            assertThat(input.path(1).path("content").path(0).path("text").asText()).isEqualTo("answer");
            assertThat(input.path(2).path("type").asText()).isEqualTo("function_call");
            assertThat(input.path(2).path("call_id").asText()).isEqualTo("call_1");
            assertThat(input.path(2).path("name").asText()).isEqualTo("Bash");
            assertThat(input.path(2).path("arguments").asText()).isEqualTo("{\"command\":\"pwd\"}");
            assertThat(input.path(3).path("type").asText()).isEqualTo("function_call_output");
            assertThat(input.path(3).path("call_id").asText()).isEqualTo("call_1");
            assertThat(input.path(3).path("output").asText()).isEqualTo("/tmp/project");
            assertThat(input.path(4).path("role").asText()).isEqualTo("user");
            assertThat(input.path(4).path("content").path(0).path("type").asText()).isEqualTo("input_text");
        } finally {
            server.stop(0);
        }
    }

    private ProxyAccountEntity codexAccount(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_CODEX_AUTH);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("test-access-token");
        return account;
    }
}
