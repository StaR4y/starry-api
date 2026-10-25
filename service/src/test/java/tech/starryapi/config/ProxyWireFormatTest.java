package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseFactory;

/**
 * The OpenAI-compatible, Anthropic and Gemini bodies the proxy builds as JSON trees. SDKs parse these strictly, so key order,
 * null members, escaping and number formatting are asserted exactly. Expected strings were recorded from the Jackson 2
 * application. Random ids and clock values are replaced by placeholders before comparing.
 */
class ProxyWireFormatTest {

    @Test
    void chatCompletionsAndResponsesKeepTheirShape() {
        ProductionJson.run(json -> {
            var mapper = json.mapper();
            var openai = new OpenAiResponseFactory(mapper);
            var usage = new PlatformUsage(25, 7, new BigDecimal("0.001500"), 3, 2, 1);
            var text = new PlatformCompletionResult("你好 \"quoted\" \\ <b>&</b>\n", "thinking", List.of(), usage);
            var tools = new PlatformCompletionResult("", "", List.of(new PlatformToolUse("toolu_1", "Bash",
                mapper.readTree("{\"command\":\"ls -la\",\"timeout\":1.5,\"retries\":3,\"big\":12345678901234567890,\"z\":null,\"a\":[1,2.0]}"))),
                usage);

            check("chatCompletionText", json.write(openai.chatCompletion("gpt-5", text)),
                """
                {"id":"chatcmpl-<uuid>","object":"chat.completion","created":<epoch>,"model":"gpt-5","choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"thinking","content":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"},"finish_reason":"stop"}],"usage":{"prompt_tokens":25,"completion_tokens":7,"total_tokens":32,"prompt_tokens_details":{"cached_tokens":3},"completion_tokens_details":{"reasoning_tokens":1}}}""");
            check("chatCompletionToolCalls", json.write(openai.chatCompletion("gpt-5", tools)),
                """
                {"id":"chatcmpl-<uuid>","object":"chat.completion","created":<epoch>,"model":"gpt-5","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"toolu_1","type":"function","function":{"name":"Bash","arguments":"{\\"command\\":\\"ls -la\\",\\"timeout\\":1.5,\\"retries\\":3,\\"big\\":12345678901234567890,\\"z\\":null,\\"a\\":[1,2.0]}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":25,"completion_tokens":7,"total_tokens":32,"prompt_tokens_details":{"cached_tokens":3},"completion_tokens_details":{"reasoning_tokens":1}}}""");
            check("responsesText", json.write(openai.responses("gpt-5", text, "resp_previous")),
                """
                {"id":"resp_<id32>","object":"response","created_at":<epoch>,"status":"completed","error":null,"incomplete_details":null,"model":"gpt-5","previous_response_id":"resp_previous","output":[{"type":"reasoning","id":"rs_<id32>","status":"completed","summary":[],"content":[{"type":"reasoning_text","text":"thinking"}]},{"type":"message","id":"msg_<id32>","role":"assistant","status":"completed","content":[{"type":"output_text","text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}]}],"output_text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n","usage":{"input_tokens":25,"output_tokens":7,"total_tokens":32,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":1}}}""");
            check("responsesToolCall", json.write(openai.responses("gpt-5", tools, null)),
                """
                {"id":"resp_<id32>","object":"response","created_at":<epoch>,"status":"completed","error":null,"incomplete_details":null,"model":"gpt-5","output":[{"type":"function_call","id":"fc_<id32>","call_id":"toolu_1","name":"Bash","arguments":"{\\"command\\":\\"ls -la\\",\\"timeout\\":1.5,\\"retries\\":3,\\"big\\":12345678901234567890,\\"z\\":null,\\"a\\":[1,2.0]}","status":"completed"}],"output_text":"","usage":{"input_tokens":25,"output_tokens":7,"total_tokens":32,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":1}}}""");
            check("chatStreamText", events(openai.chatStreamEvents("gpt-5", text)),
                """
                data: {"id":"chatcmpl-<uuid>","object":"chat.completion.chunk","created":<epoch>,"model":"gpt-5","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"thinking"},"finish_reason":null}]}
                data: {"id":"chatcmpl-<uuid>","object":"chat.completion.chunk","created":<epoch>,"model":"gpt-5","choices":[{"index":0,"delta":{"role":"assistant","content":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"},"finish_reason":null}]}
                data: {"id":"chatcmpl-<uuid>","object":"chat.completion.chunk","created":<epoch>,"model":"gpt-5","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                data: [DONE]""");
            check("chatStreamToolCalls", events(openai.chatStreamEvents("gpt-5", tools)),
                """
                data: {"id":"chatcmpl-<uuid>","object":"chat.completion.chunk","created":<epoch>,"model":"gpt-5","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"toolu_1","type":"function","function":{"name":"Bash","arguments":"{\\"command\\":\\"ls -la\\",\\"timeout\\":1.5,\\"retries\\":3,\\"big\\":12345678901234567890,\\"z\\":null,\\"a\\":[1,2.0]}"}}]},"finish_reason":null}]}
                data: {"id":"chatcmpl-<uuid>","object":"chat.completion.chunk","created":<epoch>,"model":"gpt-5","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
                data: [DONE]""");
            check("responsesStreamText", events(openai.responsesStreamEvents("gpt-5", text, null)),
                """
                event: response.created
                data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_<id32>","object":"response","created_at":<epoch>,"status":"in_progress","error":null,"incomplete_details":null,"model":"gpt-5","output":[],"output_text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n","usage":{"input_tokens":25,"output_tokens":7,"total_tokens":32,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":1}}}}
                event: response.output_item.added
                data: {"type":"response.output_item.added","sequence_number":1,"response_id":"resp_<id32>","output_index":0,"item":{"type":"reasoning","id":"rs_<id32>","status":"completed","summary":[],"content":[{"type":"reasoning_text","text":""}]}}
                event: response.reasoning_text.delta
                data: {"type":"response.reasoning_text.delta","sequence_number":2,"response_id":"resp_<id32>","item_id":"rs_<id32>","output_index":0,"content_index":0,"delta":"thinking"}
                event: response.reasoning_text.done
                data: {"type":"response.reasoning_text.done","sequence_number":3,"response_id":"resp_<id32>","item_id":"rs_<id32>","output_index":0,"content_index":0,"text":"thinking"}
                event: response.output_item.done
                data: {"type":"response.output_item.done","sequence_number":4,"response_id":"resp_<id32>","output_index":0,"item":{"type":"reasoning","id":"rs_<id32>","status":"completed","summary":[],"content":[{"type":"reasoning_text","text":"thinking"}]}}
                event: response.output_item.added
                data: {"type":"response.output_item.added","sequence_number":5,"response_id":"resp_<id32>","output_index":1,"item":{"type":"message","id":"msg_<id32>","role":"assistant","status":"completed","content":[{"type":"output_text","text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}]}}
                event: response.content_part.added
                data: {"type":"response.content_part.added","sequence_number":6,"response_id":"resp_<id32>","item_id":"msg_<id32>","output_index":1,"content_index":0,"part":{"type":"output_text","text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}}
                event: response.output_text.delta
                data: {"type":"response.output_text.delta","sequence_number":7,"response_id":"resp_<id32>","item_id":"msg_<id32>","output_index":1,"content_index":0,"delta":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}
                event: response.output_text.done
                data: {"type":"response.output_text.done","sequence_number":8,"response_id":"resp_<id32>","item_id":"msg_<id32>","output_index":1,"content_index":0,"text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}
                event: response.content_part.done
                data: {"type":"response.content_part.done","sequence_number":9,"response_id":"resp_<id32>","item_id":"msg_<id32>","output_index":1,"content_index":0,"part":{"type":"output_text","text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}}
                event: response.output_item.done
                data: {"type":"response.output_item.done","sequence_number":10,"response_id":"resp_<id32>","output_index":1,"item":{"type":"message","id":"msg_<id32>","role":"assistant","status":"completed","content":[{"type":"output_text","text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}]}}
                event: response.completed
                data: {"type":"response.completed","sequence_number":11,"response":{"id":"resp_<id32>","object":"response","created_at":<epoch>,"status":"completed","error":null,"incomplete_details":null,"model":"gpt-5","output":[{"type":"reasoning","id":"rs_<id32>","status":"completed","summary":[],"content":[{"type":"reasoning_text","text":"thinking"}]},{"type":"message","id":"msg_<id32>","role":"assistant","status":"completed","content":[{"type":"output_text","text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}]}],"output_text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n","usage":{"input_tokens":25,"output_tokens":7,"total_tokens":32,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":1}}}}
                data: [DONE]""");
            check("responsesStreamToolCall", events(openai.responsesStreamEvents("gpt-5", tools, null)),
                """
                event: response.created
                data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_<id32>","object":"response","created_at":<epoch>,"status":"in_progress","error":null,"incomplete_details":null,"model":"gpt-5","output":[],"output_text":"","usage":{"input_tokens":25,"output_tokens":7,"total_tokens":32,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":1}}}}
                event: response.output_item.added
                data: {"type":"response.output_item.added","sequence_number":1,"response_id":"resp_<id32>","output_index":0,"item":{"type":"function_call","id":"fc_<id32>","call_id":"toolu_1","name":"Bash","arguments":"","status":"completed"}}
                event: response.function_call_arguments.delta
                data: {"type":"response.function_call_arguments.delta","sequence_number":2,"response_id":"resp_<id32>","item_id":"fc_<id32>","name":"Bash","output_index":0,"delta":"{\\"command\\":\\"ls -la\\",\\"timeout\\":1.5,\\"retries\\":3,\\"big\\":12345678901234567890,\\"z\\":null,\\"a\\":[1,2.0]}"}
                event: response.function_call_arguments.done
                data: {"type":"response.function_call_arguments.done","sequence_number":3,"response_id":"resp_<id32>","item_id":"fc_<id32>","name":"Bash","output_index":0,"arguments":"{\\"command\\":\\"ls -la\\",\\"timeout\\":1.5,\\"retries\\":3,\\"big\\":12345678901234567890,\\"z\\":null,\\"a\\":[1,2.0]}"}
                event: response.output_item.done
                data: {"type":"response.output_item.done","sequence_number":4,"response_id":"resp_<id32>","output_index":0,"item":{"type":"function_call","id":"fc_<id32>","call_id":"toolu_1","name":"Bash","arguments":"{\\"command\\":\\"ls -la\\",\\"timeout\\":1.5,\\"retries\\":3,\\"big\\":12345678901234567890,\\"z\\":null,\\"a\\":[1,2.0]}","status":"completed"}}
                event: response.completed
                data: {"type":"response.completed","sequence_number":5,"response":{"id":"resp_<id32>","object":"response","created_at":<epoch>,"status":"completed","error":null,"incomplete_details":null,"model":"gpt-5","output":[{"type":"function_call","id":"fc_<id32>","call_id":"toolu_1","name":"Bash","arguments":"{\\"command\\":\\"ls -la\\",\\"timeout\\":1.5,\\"retries\\":3,\\"big\\":12345678901234567890,\\"z\\":null,\\"a\\":[1,2.0]}","status":"completed"}],"output_text":"","usage":{"input_tokens":25,"output_tokens":7,"total_tokens":32,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":1}}}}
                data: [DONE]""");
        });
    }

    @Test
    void anthropicMessagesKeepTheirShape() {
        ProductionJson.run(json -> {
            var mapper = json.mapper();
            var anthropic = new AnthropicResponseFactory(mapper);
            var usage = new PlatformUsage(25, 7, new BigDecimal("0.001500"), 3, 2, 1);
            var text = new PlatformCompletionResult("你好 \"quoted\" \\ <b>&</b>\n", usage);
            var tools = new PlatformCompletionResult("Running", List.of(new PlatformToolUse("toolu_1", "Bash",
                mapper.readTree("{\"command\":\"ls -la\",\"timeout\":1.5,\"retries\":3,\"big\":12345678901234567890,\"z\":null,\"a\":[1,2.0]}"))),
                usage);

            check("messageText", json.write(anthropic.message("claude-sonnet-4.5", text)),
                """
                {"id":"msg_<uuid>","type":"message","role":"assistant","model":"claude-sonnet-4.5","content":[{"type":"text","text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":25,"output_tokens":7}}""");
            check("messageToolUse", json.write(anthropic.message("claude-sonnet-4.5", tools)),
                """
                {"id":"msg_<uuid>","type":"message","role":"assistant","model":"claude-sonnet-4.5","content":[{"type":"text","text":"Running"},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls -la","timeout":1.5,"retries":3,"big":12345678901234567890,"z":null,"a":[1,2.0]}}],"stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":25,"output_tokens":7}}""");
            check("messageStreamText", events(anthropic.messageStream("claude-sonnet-4.5", text)),
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_<uuid>","type":"message","role":"assistant","model":"claude-sonnet-4.5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":25,"output_tokens":0}}}
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}}
                event: content_block_stop
                data: {"type":"content_block_stop","index":0}
                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":7}}
                event: message_stop
                data: {"type":"message_stop"}""");
            check("messageStreamToolUse", events(anthropic.messageStream("claude-sonnet-4.5", tools)),
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_<uuid>","type":"message","role":"assistant","model":"claude-sonnet-4.5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":25,"output_tokens":0}}}
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Running"}}
                event: content_block_stop
                data: {"type":"content_block_stop","index":0}
                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"Bash","input":{}}}
                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"command\\":\\"ls -la\\",\\"timeout\\":1.5,\\"retries\\":3,\\"big\\":12345678901234567890,\\"z\\":null,\\"a\\":[1,2.0]}"}}
                event: content_block_stop
                data: {"type":"content_block_stop","index":1}
                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":7}}
                event: message_stop
                data: {"type":"message_stop"}""");
        });
    }

    /** Written from the factory as it was before stop reasons existed: a result without one finishes with STOP. */
    @Test
    void geminiGenerateContentKeepsItsShape() {
        ProductionJson.run(json -> {
            var mapper = json.mapper();
            var gemini = new GeminiResponseFactory(mapper);
            var usage = new PlatformUsage(25, 7, new BigDecimal("0.001500"), 3, 2, 1);
            var text = new PlatformCompletionResult("你好 \"quoted\" \\ <b>&</b>\n", usage);
            var tools = new PlatformCompletionResult("Running", List.of(new PlatformToolUse("toolu_1", "Bash",
                mapper.readTree("{\"command\":\"ls -la\",\"timeout\":1.5,\"retries\":3,\"big\":12345678901234567890,\"z\":null,\"a\":[1,2.0]}"))),
                usage);
            var toolsOnly = new PlatformCompletionResult("", List.of(new PlatformToolUse("toolu_1", "Bash", mapper.readTree("{}"))), usage);

            check("geminiText", json.write(gemini.generateContent("gemini-2.5-pro", text)),
                """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"你好 \\"quoted\\" \\\\ <b>&</b>\\n"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":25,"candidatesTokenCount":7,"totalTokenCount":32},"modelVersion":"gemini-2.5-pro","responseId":"gen-<uuid>"}""");
            check("geminiToolCall", json.write(gemini.generateContent("gemini-2.5-pro", tools)),
                """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"Running"},{"functionCall":{"id":"toolu_1","name":"Bash","args":{"command":"ls -la","timeout":1.5,"retries":3,"big":12345678901234567890,"z":null,"a":[1,2.0]}}}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":25,"candidatesTokenCount":7,"totalTokenCount":32},"modelVersion":"gemini-2.5-pro","responseId":"gen-<uuid>"}""");
            check("geminiToolCallWithoutText", json.write(gemini.generateContent("gemini-2.5-pro", toolsOnly)),
                """
                {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"toolu_1","name":"Bash","args":{}}}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":25,"candidatesTokenCount":7,"totalTokenCount":32},"modelVersion":"gemini-2.5-pro","responseId":"gen-<uuid>"}""");
        });
    }

    private static String events(Flux<ServerSentEvent<String>> stream) {
        return String.join("\n", stream.map(event ->
            (event.event() == null ? "" : "event: " + event.event() + "\n") + "data: " + event.data()).collectList().block());
    }

    private static void check(String name, String actual, String expected) {
        String normalized = actual
            .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
            .replaceAll("[0-9a-f]{32}", "<id32>")
            .replaceAll("\"(created|created_at)\":[0-9]+", "\"$1\":<epoch>");
        assertThat(normalized).as(name).isEqualTo(expected);
    }
}
