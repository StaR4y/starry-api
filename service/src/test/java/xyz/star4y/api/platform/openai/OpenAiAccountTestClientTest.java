package xyz.star4y.api.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import xyz.star4y.api.account.AccountTokenRefreshService;
import xyz.star4y.api.account.ProxyAccountEntity;
import xyz.star4y.api.platform.core.PlatformCompletionResult;

class OpenAiAccountTestClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void openAiCompatibleAccountTestUsesV1ChatCompletionsForRootBaseUrl() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<List<String>> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().get(HttpHeaders.AUTHORIZATION));
            byte[] response = """
                {"choices":[{"message":{"content":"OK"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiAccountTestClient client = new OpenAiAccountTestClient(
                objectMapper,
                WebClient.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = openAiCompatibleAccount("http://127.0.0.1:" + server.getAddress().getPort());

            PlatformCompletionResult result = client.test(account, "deepseek-chat", "ping").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(result.usage().totalTokens()).isEqualTo(2);
            assertThat(authorization.get()).containsExactly("Bearer test-access-token");

            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asText()).isEqualTo("deepseek-chat");
            assertThat(body.path("stream").asBoolean()).isFalse();
            assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("ping");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexAccountTestSendsRequiredInstructions() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<List<String>> authorization = new AtomicReference<>();
        AtomicReference<List<String>> originator = new AtomicReference<>();
        AtomicReference<List<String>> accept = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().get(HttpHeaders.AUTHORIZATION));
            originator.set(exchange.getRequestHeaders().get("Originator"));
            accept.set(exchange.getRequestHeaders().get(HttpHeaders.ACCEPT));
            byte[] response = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":1},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiAccountTestClient client = new OpenAiAccountTestClient(
                objectMapper,
                WebClient.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());

            PlatformCompletionResult result = client.test(account, "gpt-5.5", "ping").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(result.usage().totalTokens()).isEqualTo(2);
            assertThat(authorization.get()).containsExactly("Bearer test-access-token");
            assertThat(originator.get()).containsExactly("codex-tui");
            assertThat(accept.get()).singleElement().asString().contains("text/event-stream");

            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asText()).isEqualTo("gpt-5.5");
            assertThat(body.path("instructions").asText()).isNotBlank();
            assertThat(body.has("max_output_tokens")).isFalse();
            assertThat(body.path("store").asBoolean()).isFalse();
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("input").path(0).path("type").asText()).isEqualTo("message");
            assertThat(body.path("input").path(0).path("role").asText()).isEqualTo("user");
            assertThat(body.path("input").path(0).path("content").path(0).path("type").asText())
                .isEqualTo("input_text");
            assertThat(body.path("input").path(0).path("content").path(0).path("text").asText())
                .isEqualTo("ping");
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

    private ProxyAccountEntity openAiCompatibleAccount(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("test-access-token");
        return account;
    }
}
