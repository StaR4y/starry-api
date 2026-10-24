package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountTokenRefreshService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.platform.core.PlatformCompletionResult;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiUpstreamClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void codexDoesNotInjectDefaultInstructions() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {"model":"gpt-5.5","messages":[{"role":"user","content":"write a detailed explanation"}]}
                """);

            PlatformCompletionResult result = client.complete(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.has("instructions")).isFalse();
        } finally {
            server.stop(0);
        }
    }

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
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "service_tier": "priority",
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

            PlatformCompletionResult result = client.complete(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("instructions").asString("")).isEqualTo("Use short replies.");
            assertThat(body.path("service_tier").asString("")).isEqualTo("priority");
            JsonNode input = body.path("input");
            assertThat(input.size()).isEqualTo(5);
            assertThat(input.path(0).path("role").asString("")).isEqualTo("user");
            assertThat(input.path(0).path("content").path(0).path("type").asString("")).isEqualTo("input_text");
            assertThat(input.path(1).path("role").asString("")).isEqualTo("assistant");
            assertThat(input.path(1).path("content").path(0).path("type").asString("")).isEqualTo("output_text");
            assertThat(input.path(1).path("content").path(0).path("text").asString("")).isEqualTo("answer");
            assertThat(input.path(2).path("type").asString("")).isEqualTo("function_call");
            assertThat(input.path(2).path("call_id").asString("")).isEqualTo("call_1");
            assertThat(input.path(2).path("name").asString("")).isEqualTo("Bash");
            assertThat(input.path(2).path("arguments").asString("")).isEqualTo("{\"command\":\"pwd\"}");
            assertThat(input.path(3).path("type").asString("")).isEqualTo("function_call_output");
            assertThat(input.path(3).path("call_id").asString("")).isEqualTo("call_1");
            assertThat(input.path(3).path("output").asString("")).isEqualTo("/tmp/project");
            assertThat(input.path(4).path("role").asString("")).isEqualTo("user");
            assertThat(input.path(4).path("content").path(0).path("type").asString("")).isEqualTo("input_text");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexUpstreamRefreshesTokenAndRetriesOnceOnUnauthorized() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<List<String>> secondAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            int count = requestCount.incrementAndGet();
            if (count == 1) {
                byte[] response = """
                    {"error":{"message":"Encountered invalidated oauth token for user, failing request","code":"token_revoked"},"status":401}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
                exchange.sendResponseHeaders(401, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            secondAuthorization.set(exchange.getRequestHeaders().get(HttpHeaders.AUTHORIZATION));
            byte[] response = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AccountTokenRefreshService tokenRefreshService = mock(AccountTokenRefreshService.class);
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            account.setRefreshToken("old-refresh");
            ProxyAccountEntity refreshed = codexAccount(account.getBaseUrl());
            refreshed.setAccessToken("new-access-token");
            when(tokenRefreshService.canRefresh(any(ProxyAccountEntity.class))).thenReturn(true);
            when(tokenRefreshService.refresh(account)).thenReturn(Mono.just(refreshed));
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                tokenRefreshService
            );
            JsonNode request = objectMapper.readTree("""
                {"model":"gpt-5.5","messages":[{"role":"user","content":"ping"}]}
                """);

            PlatformCompletionResult result = client.complete(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(requestCount.get()).isEqualTo(2);
            assertThat(secondAuthorization.get()).containsExactly("Bearer new-access-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexUpstreamSendsToolsAndParsesFunctionCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                event: response.output_item.done
                data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_lookup","name":"lookup","arguments":"{\\"query\\":\\"stars\\"}"}}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":6,"output_tokens":2},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "messages": [{"role": "user", "content": "lookup stars"}],
                  "tools": [
                    {
                      "type": "function",
                      "function": {
                        "name": "lookup",
                        "description": "Lookup data",
                        "parameters": {
                          "type": "object",
                          "properties": {
                            "query": {"type": "string"},
                            "page": {
                              "type": "integer",
                              "minimum": 0,
                              "default": 0,
                              "format": "int32"
                            }
                          },
                          "required": ["query", "page"]
                        },
                        "strict": true
                      }
                    }
                  ],
                  "tool_choice": {"type": "function", "function": {"name": "lookup"}},
                  "parallel_tool_calls": false
                }
                """);

            PlatformCompletionResult result = client.complete(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isBlank();
            assertThat(result.toolUses()).hasSize(1);
            assertThat(result.toolUses().get(0).id()).isEqualTo("call_lookup");
            assertThat(result.toolUses().get(0).name()).isEqualTo("lookup");
            assertThat(result.toolUses().get(0).input().path("query").asString("")).isEqualTo("stars");

            JsonNode body = objectMapper.readTree(requestBody.get());
            JsonNode tool = body.path("tools").get(0);
            assertThat(tool.path("type").asString("")).isEqualTo("function");
            assertThat(tool.path("name").asString("")).isEqualTo("lookup");
            assertThat(tool.path("function").isMissingNode()).isTrue();
            assertThat(tool.path("parameters").path("properties").path("query").path("type").asString("")).isEqualTo("string");
            JsonNode page = tool.path("parameters").path("properties").path("page");
            assertThat(page.path("type").asString("")).isEqualTo("integer");
            assertThat(page.has("minimum")).isFalse();
            assertThat(page.has("default")).isFalse();
            assertThat(page.has("format")).isFalse();
            assertThat(tool.path("strict").asBoolean(false)).isTrue();
            assertThat(body.path("tool_choice").path("type").asString("")).isEqualTo("function");
            assertThat(body.path("tool_choice").path("name").asString("")).isEqualTo("lookup");
            assertThat(body.path("parallel_tool_calls").asBoolean(true)).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexUpstreamConvertsFunctionApplyPatchArgumentsToCustomToolInput() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                event: response.output_item.added
                data: {"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_write","name":"apply_patch","arguments":""}}

                event: response.function_call_arguments.delta
                data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\\"patch\\":\\"*** Begin"}

                event: response.function_call_arguments.delta
                data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":" Patch\\"}"}

                event: response.function_call_arguments.done
                data: {"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\\"patch\\":\\"*** Begin Patch\\"}"}

                event: response.output_item.done
                data: {"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_write","name":"apply_patch","arguments":""}}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":6,"output_tokens":2},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": "edit the file",
                  "tools": [{"type": "function", "name": "apply_patch", "parameters": {"type": "object", "properties": {}}}],
                  "tool_choice": {"type": "function", "name": "apply_patch"}
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.toolUses()).hasSize(1);
            assertThat(result.toolUses().get(0).id()).isEqualTo("call_write");
            assertThat(result.toolUses().get(0).name()).isEqualTo("apply_patch");
            assertThat(result.toolUses().get(0).type()).isEqualTo("custom_tool_call");
            assertThat(result.toolUses().get(0).rawInput()).isEqualTo("*** Begin Patch");
            assertThat(result.toolUses().get(0).input().path("_rawArguments").asString("")).isEqualTo("*** Begin Patch");
            assertThat(result.toolUses().get(0).rawOutputItem().path("type").asString("")).isEqualTo("custom_tool_call");
            assertThat(result.toolUses().get(0).rawOutputItem().path("input").asString("")).isEqualTo("*** Begin Patch");
            assertThat(result.toolUses().get(0).rawOutputItem().has("arguments")).isFalse();
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("tools").path(0).path("type").asString("")).isEqualTo("function");
            assertThat(body.path("tools").path(0).path("name").asString("")).isEqualTo("apply_patch");
            assertThat(body.path("tools").path(0).path("parameters").path("properties").path("patch").path("type").asString(""))
                .isEqualTo("string");
            assertThat(body.path("tool_choice").path("type").asString("")).isEqualTo("function");
            assertThat(body.path("tool_choice").path("name").asString("")).isEqualTo("apply_patch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexUpstreamParsesCustomToolCallInputDeltas() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                event: response.output_item.added
                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"custom_tool_call","id":"ctc_1","call_id":"call_patch","name":"apply_patch","input":{}}}

                event: response.custom_tool_call_input.delta
                data: {"type":"response.custom_tool_call_input.delta","item_id":"ctc_1","output_index":0,"delta":"*** Begin Patch\\n"}

                event: response.custom_tool_call_input.delta
                data: {"type":"response.custom_tool_call_input.delta","item_id":"ctc_1","output_index":0,"delta":"*** Add File: codex_apply_patch_test.txt\\n+hello\\n*** End Patch"}

                event: response.custom_tool_call_input.done
                data: {"type":"response.custom_tool_call_input.done","item_id":"ctc_1","output_index":0,"input":"*** Begin Patch\\n*** Add File: codex_apply_patch_test.txt\\n+hello"}

                event: response.output_item.done
                data: {"type":"response.output_item.done","output_index":0,"item":{"type":"custom_tool_call","id":"ctc_1","call_id":"call_patch","name":"apply_patch","input":{}}}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":6,"output_tokens":2},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": "write a file",
                  "tools": [{"type": "custom", "name": "apply_patch"}],
                  "tool_choice": {"type": "custom", "name": "apply_patch"}
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.toolUses()).hasSize(1);
            assertThat(result.toolUses().get(0).id()).isEqualTo("call_patch");
            assertThat(result.toolUses().get(0).name()).isEqualTo("apply_patch");
            assertThat(result.toolUses().get(0).type()).isEqualTo("custom_tool_call");
            assertThat(result.toolUses().get(0).rawInput()).endsWith("*** End Patch\n");
            assertThat(result.toolUses().get(0).rawOutputItem().path("input").asString("")).endsWith("*** End Patch\n");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("tools").path(0).path("type").asString("")).isEqualTo("function");
            assertThat(body.path("tools").path(0).path("parameters").path("properties").path("patch").path("type").asString(""))
                .isEqualTo("string");
            assertThat(body.path("tool_choice").path("type").asString("")).isEqualTo("function");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexUpstreamKeepsCompletePatchWhenInitialCustomToolInputIsPartial() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            byte[] response = """
                event: response.output_item.added
                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"custom_tool_call","id":"ctc_1","call_id":"call_patch","name":"apply_patch","input":"*** Begin Patch\\n"}}

                event: response.custom_tool_call_input.delta
                data: {"type":"response.custom_tool_call_input.delta","item_id":"ctc_1","output_index":0,"delta":"*** Add File: codex_apply_patch_test.txt\\n+hello\\n*** End Patch"}

                event: response.custom_tool_call_input.done
                data: {"type":"response.custom_tool_call_input.done","item_id":"ctc_1","output_index":0,"input":"*** Begin Patch\\n*** Add File: codex_apply_patch_test.txt\\n+hello"}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":6,"output_tokens":2},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": "write a file",
                  "tools": [{"type": "custom", "name": "apply_patch"}]
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.toolUses()).hasSize(1);
            assertThat(result.toolUses().get(0).rawInput()).startsWith("*** Begin Patch");
            assertThat(result.toolUses().get(0).rawInput()).endsWith("*** End Patch\n");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexUpstreamUsesCompleteOutputItemDoneInputWhenDeltasArePartial() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            byte[] response = """
                event: response.output_item.added
                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"custom_tool_call","id":"ctc_1","call_id":"call_patch","name":"apply_patch","input":""}}

                event: response.custom_tool_call_input.delta
                data: {"type":"response.custom_tool_call_input.delta","item_id":"ctc_1","output_index":0,"delta":"*** Begin Patch\\n"}

                event: response.output_item.done
                data: {"type":"response.output_item.done","output_index":0,"item":{"type":"custom_tool_call","id":"ctc_1","call_id":"call_patch","name":"apply_patch","input":"*** Begin Patch\\n*** Add File: codex_apply_patch_test.txt\\n+hello\\n*** End Patch"}}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":6,"output_tokens":2},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": "write a file",
                  "tools": [{"type": "custom", "name": "apply_patch"}]
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.toolUses()).hasSize(1);
            assertThat(result.toolUses().get(0).rawInput()).startsWith("*** Begin Patch");
            assertThat(result.toolUses().get(0).rawInput()).endsWith("*** End Patch\n");
            assertThat(result.toolUses().get(0).rawOutputItem().path("input").asString("")).endsWith("*** End Patch\n");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexUpstreamTrimsApplyPatchInputAfterLastEndPatchLine() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            byte[] response = """
                event: response.output_item.done
                data: {"type":"response.output_item.done","output_index":0,"item":{"type":"custom_tool_call","id":"ctc_1","call_id":"call_patch","name":"apply_patch","input":"prefix text\\n*** Begin Patch\\n*** Add File: codex_apply_patch_test.txt\\n+hello\\n*** End Patch\\nextra tail"}}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":6,"output_tokens":2},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": "write a file",
                  "tools": [{"type": "custom", "name": "apply_patch"}]
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.toolUses()).hasSize(1);
            assertThat(result.toolUses().get(0).rawInput()).isEqualTo(
                "*** Begin Patch\n*** Add File: codex_apply_patch_test.txt\n+hello\n*** End Patch\n"
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexResponsesRequestSanitizesFlatToolsAndNormalizesFunctionOutput() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": [
                    {"type": "message", "role": "user", "content": [{"type": "input_text", "text": "edit file"}]},
                    {"type": "function_call", "call_id": "call_write", "name": "edit_file", "arguments": {"patch": "*** Begin Patch"}},
                    {"type": "function_call_output", "call_id": "call_write", "output": [{"type": "output_text", "text": "Done"}]}
                  ],
                  "tools": [
                    {
                      "type": "function",
                      "name": "edit_file",
                      "description": "Apply a patch to files",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "page": {
                            "type": "integer",
                            "minimum": 0,
                            "default": 0,
                            "format": "int32"
                          }
                        },
                        "required": ["page", "missing"]
                      },
                      "strict": true
                    }
                  ],
                  "tool_choice": {"type": "function", "name": "edit_file"},
                  "parallel_tool_calls": false
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            JsonNode body = objectMapper.readTree(requestBody.get());
            JsonNode tool = body.path("tools").get(0);
            assertThat(tool.path("type").asString("")).isEqualTo("function");
            assertThat(tool.path("name").asString("")).isEqualTo("edit_file");
            assertThat(tool.path("function").isMissingNode()).isTrue();
            JsonNode page = tool.path("parameters").path("properties").path("page");
            assertThat(page.path("type").asString("")).isEqualTo("integer");
            assertThat(page.has("minimum")).isFalse();
            assertThat(page.has("default")).isFalse();
            assertThat(page.has("format")).isFalse();
            assertThat(tool.path("parameters").path("required")).hasSize(1);
            assertThat(tool.path("parameters").path("required").get(0).asString("")).isEqualTo("page");
            assertThat(tool.path("strict").asBoolean(false)).isTrue();
            assertThat(body.path("input").path(1).path("arguments").asString("")).isEqualTo("{\"patch\":\"*** Begin Patch\"}");
            assertThat(body.path("input").path(2).path("output").asString("")).isEqualTo("Done");
            assertThat(body.path("tool_choice").path("name").asString("")).isEqualTo("edit_file");
            assertThat(body.path("parallel_tool_calls").asBoolean(true)).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexResponsesRequestPreservesAgentExecutionFieldsAndOfficialHeaders() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        AtomicReference<String> originator = new AtomicReference<>();
        AtomicReference<String> beta = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            userAgent.set(exchange.getRequestHeaders().getFirst(HttpHeaders.USER_AGENT));
            originator.set(exchange.getRequestHeaders().getFirst("Originator"));
            beta.set(exchange.getRequestHeaders().getFirst("OpenAI-Beta"));
            byte[] response = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": [{"type": "message", "role": "user", "content": "continue"}],
                  "prompt_cache_key": "session-1",
                  "reasoning": {"effort": "high"},
                  "include": ["reasoning.encrypted_content"],
                  "metadata": {"drop": true},
                  "max_output_tokens": 123
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("prompt_cache_key").asString("")).isEqualTo("session-1");
            assertThat(body.path("reasoning").path("effort").asString("")).isEqualTo("high");
            assertThat(body.path("include").path(0).asString("")).isEqualTo("reasoning.encrypted_content");
            assertThat(body.has("metadata")).isFalse();
            assertThat(body.has("max_output_tokens")).isFalse();
            assertThat(body.path("store").asBoolean(true)).isFalse();
            assertThat(body.path("stream").asBoolean(false)).isTrue();
            assertThat(userAgent.get()).startsWith("codex_cli_rs/");
            assertThat(originator.get()).isEqualTo("codex_cli_rs");
            assertThat(beta.get()).isEqualTo("responses=experimental");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiResponsesApiPreservesMultimodalInput() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"output_text":"OK","usage":{"input_tokens":8,"output_tokens":1}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = openAiAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": [
                    {
                      "type": "message",
                      "role": "user",
                      "content": [
                        {"type": "input_text", "text": "describe"},
                        {"type": "input_image", "image_url": "data:image/png;base64,abc", "detail": "high"},
                        {"type": "input_file", "file_id": "file_123", "filename": "notes.txt"}
                      ]
                    }
                  ]
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "openai/gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            JsonNode body = objectMapper.readTree(requestBody.get());
            JsonNode content = body.path("input").path(0).path("content");
            assertThat(content.path(0).path("type").asString("")).isEqualTo("input_text");
            assertThat(content.path(1).path("type").asString("")).isEqualTo("input_image");
            assertThat(content.path(1).path("image_url").asString("")).isEqualTo("data:image/png;base64,abc");
            assertThat(content.path(1).path("detail").asString("")).isEqualTo("high");
            assertThat(content.path(2).path("type").asString("")).isEqualTo("input_file");
            assertThat(content.path(2).path("file_id").asString("")).isEqualTo("file_123");
            assertThat(content.path(2).path("filename").asString("")).isEqualTo("notes.txt");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiResponsesApiForcesApplyPatchToolToPatchFunctionSchema() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"output_text":"OK","usage":{"input_tokens":1,"output_tokens":1}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = openAiAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": "write a file",
                  "tools": [
                    {
                      "type": "function",
                      "name": "apply_patch",
                      "description": "Apply a raw unified patch",
                      "parameters": {"type": "object", "properties": {"patch": {"type": "string"}}}
                    }
                  ],
                  "tool_choice": {"type": "function", "name": "apply_patch"}
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            JsonNode body = objectMapper.readTree(requestBody.get());
            JsonNode tool = body.path("tools").path(0);
            assertThat(tool.path("type").asString("")).isEqualTo("function");
            assertThat(tool.path("name").asString("")).isEqualTo("apply_patch");
            assertThat(tool.path("description").asString("")).isEqualTo("Apply a raw unified patch");
            assertThat(tool.path("parameters").path("properties").path("patch").path("type").asString("")).isEqualTo("string");
            assertThat(body.path("tool_choice").path("type").asString("")).isEqualTo("function");
            assertThat(body.path("tool_choice").path("name").asString("")).isEqualTo("apply_patch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiCompatibleResponsesPreservesCodexDisplayModel() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"output_text":"OK","usage":{"input_tokens":1,"output_tokens":1}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = openAiCompatibleAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": "hi"
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asString("")).isEqualTo("openai/gpt-5.5");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiCompatibleResponsesRetriesUnprefixedModelWhenPrefixedModelIsUnavailable() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<String> requestedModels = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            JsonNode body = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            String model = body.path("model").asString("");
            requestedModels.add(model);
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                byte[] response = """
                    {"error":{"code":"model_not_found","message":"No available channel for model openai/gpt-5.5 under group codex-pro","type":"api_error"}}
                    """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
                exchange.sendResponseHeaders(503, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            byte[] response = """
                {"output_text":"OK","usage":{"input_tokens":1,"output_tokens":1}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = openAiCompatibleAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "input": "hi"
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(requestedModels).containsExactly("openai/gpt-5.5", "gpt-5.5");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiCompatibleModelPrefixRoutesWithoutSendingPrefixToUpstream() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = openAiCompatibleAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "starry/deepseek",
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """);

            PlatformCompletionResult result = client.complete(account, request, "openai-compatible/deepseek-chat").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asString("")).isEqualTo("deepseek-chat");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deepSeekAccountSendsOfficialModelIdAndParsesCacheUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {
                  "choices":[{"message":{
                    "content":"OK",
                    "reasoning_content":"checked the request",
                    "tool_calls":[{
                      "id":"call_lookup",
                      "type":"function",
                      "function":{"name":"lookup","arguments":"{\\"query\\":\\"stars\\"}"}
                    }]
                  }}],
                  "usage":{
                    "prompt_tokens":110,
                    "completion_tokens":20,
                    "prompt_cache_hit_tokens":100,
                    "prompt_cache_miss_tokens":10
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = deepSeekAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "starry/deepseek-v4-flash",
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """);

            PlatformCompletionResult result = client.complete(account, request, "deepseek/deepseek-v4-flash").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("model").asString("")).isEqualTo("deepseek-v4-flash");
            assertThat(result.usage().inputTokens()).isEqualTo(110);
            assertThat(result.usage().cacheReadTokens()).isEqualTo(100);
            assertThat(result.usage().outputTokens()).isEqualTo(20);
            assertThat(result.reasoningContent()).isEqualTo("checked the request");
            assertThat(result.toolUses()).singleElement().satisfies(toolUse -> {
                assertThat(toolUse.id()).isEqualTo("call_lookup");
                assertThat(toolUse.name()).isEqualTo("lookup");
                assertThat(toolUse.input().path("query").asString("")).isEqualTo("stars");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deepSeekResponsesJsonPreservesReasoningTextAndToolCalls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] response = """
                {
                  "output": [
                    {
                      "type": "reasoning",
                      "id": "rs_1",
                      "status": "completed",
                      "content": [{"type": "reasoning_text", "text": "checked the JSON response"}]
                    },
                    {
                      "type": "message",
                      "id": "msg_1",
                      "role": "assistant",
                      "status": "completed",
                      "content": [{"type": "output_text", "text": "OK"}]
                    },
                    {
                      "type": "function_call",
                      "id": "fc_1",
                      "call_id": "call_lookup",
                      "name": "lookup",
                      "arguments": "{\\"query\\":\\"stars\\"}",
                      "status": "completed"
                    }
                  ],
                  "usage": {"input_tokens": 110, "output_tokens": 20}
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = deepSeekAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {"model":"deepseek-v4-flash","input":"hi"}
                """);

            PlatformCompletionResult result = client
                .completeResponses(account, request, "deepseek-v4-flash")
                .block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(result.reasoningContent()).isEqualTo("checked the JSON response");
            assertThat(result.toolUses()).singleElement().satisfies(toolUse -> {
                assertThat(toolUse.id()).isEqualTo("call_lookup");
                assertThat(toolUse.name()).isEqualTo("lookup");
                assertThat(toolUse.input().path("query").asString("")).isEqualTo("stars");
            });
            assertThat(result.usage().inputTokens()).isEqualTo(110);
            assertThat(result.usage().outputTokens()).isEqualTo(20);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deepSeekResponsesStreamAggregatesReasoningTextDeltas() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] response = """
                event: response.output_item.added
                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1","content":[]}}

                event: response.reasoning_text.delta
                data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"delta":"checked "}

                event: response.reasoning_text.delta
                data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"delta":"the stream"}

                event: response.reasoning_text.done
                data: {"type":"response.reasoning_text.done","item_id":"rs_1","output_index":0,"content_index":0,"text":"checked the stream"}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                event: response.completed
                data: {"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs_1","content":[{"type":"reasoning_text","text":"checked the stream"}]}],"usage":{"input_tokens":12,"output_tokens":3}}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = deepSeekAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {"model":"deepseek-v4-flash","input":"hi","stream":true}
                """);

            PlatformCompletionResult result = client
                .completeResponses(account, request, "deepseek-v4-flash")
                .block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(result.reasoningContent()).isEqualTo("checked the stream");
            assertThat(result.usage().inputTokens()).isEqualTo(12);
            assertThat(result.usage().outputTokens()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deepSeekProRejectsUnsupportedResponsesApiBeforeNetworkCall() throws Exception {
        OpenAiUpstreamClient client = new OpenAiUpstreamClient(
            objectMapper,
            ProductionWebClients.builder().build(),
            mock(AccountTokenRefreshService.class)
        );
        ProxyAccountEntity account = deepSeekAccount("http://127.0.0.1:1");
        JsonNode request = objectMapper.readTree("""
            {"model":"deepseek-v4-pro","input":"hi"}
            """);

        assertThatThrownBy(() -> client.completeResponses(account, request, "deepseek-v4-pro").block())
            .isInstanceOfSatisfying(tech.starryapi.platform.upstream.UpstreamException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(400);
                assertThat(error.getMessage()).contains("deepseek-v4-flash");
            });
    }

    @Test
    void openAiCompatibleChatForcesNonStreamingUpstreamRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"choices":[{"message":{"content":"OK"}}],"usage":{"prompt_tokens":2,"completion_tokens":1}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = openAiCompatibleAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "stream": true,
                  "stream_options": {"include_usage": true},
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """);

            PlatformCompletionResult result = client.complete(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("stream").asBoolean(true)).isFalse();
            assertThat(body.has("stream_options")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiCompatibleResponsesParsesStreamAfterPingComment() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] response = """
                : PING

                event: response.created
                data: {"type":"response.created","response":{"id":"resp_test","status":"in_progress"}}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                event: response.completed
                data: {"type":"response.completed","response":{"usage":{"input_tokens":3,"output_tokens":2},"output":[]}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = openAiCompatibleAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {"model":"gpt-5.5","input":"hi"}
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            assertThat(result.usage().inputTokens()).isEqualTo(3);
            assertThat(result.usage().outputTokens()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexResponsesInputPreservesMultimodalContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","delta":"OK"}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = codexAccount("http://127.0.0.1:" + server.getAddress().getPort());
            JsonNode request = objectMapper.readTree("""
                {
                  "model": "gpt-5.5",
                  "instructions": "Use vision.",
                  "input": [
                    {
                      "type": "message",
                      "role": "user",
                      "content": [
                        {"type": "input_text", "text": "describe"},
                        {"type": "input_image", "image_url": "data:image/png;base64,abc", "detail": "high"},
                        {"type": "input_file", "file_id": "file_123", "filename": "notes.txt"}
                      ]
                    }
                  ]
                }
                """);

            PlatformCompletionResult result = client.completeResponses(account, request, "gpt-5.5").block();

            assertThat(result).isNotNull();
            assertThat(result.content()).isEqualTo("OK");
            JsonNode body = objectMapper.readTree(requestBody.get());
            assertThat(body.path("instructions").asString("")).isEqualTo("Use vision.");
            JsonNode content = body.path("input").path(0).path("content");
            assertThat(content.path(0).path("type").asString("")).isEqualTo("input_text");
            assertThat(content.path(0).path("text").asString("")).isEqualTo("describe");
            assertThat(content.path(1).path("type").asString("")).isEqualTo("input_image");
            assertThat(content.path(1).path("image_url").asString("")).isEqualTo("data:image/png;base64,abc");
            assertThat(content.path(1).path("detail").asString("")).isEqualTo("high");
            assertThat(content.path(2).path("type").asString("")).isEqualTo("input_file");
            assertThat(content.path(2).path("file_id").asString("")).isEqualTo("file_123");
            assertThat(content.path(2).path("filename").asString("")).isEqualTo("notes.txt");
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

    private ProxyAccountEntity openAiAccount(String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
        account.setAuthMethod(AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("sk-test");
        return account;
    }

    private ProxyAccountEntity openAiCompatibleAccount(String baseUrl) {
        ProxyAccountEntity account = openAiAccount(baseUrl);
        account.setProvider(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        return account;
    }

    private ProxyAccountEntity deepSeekAccount(String baseUrl) {
        ProxyAccountEntity account = openAiAccount(baseUrl);
        account.setProvider(AccountPlatforms.DEEPSEEK_PROVIDER);
        return account;
    }
}
