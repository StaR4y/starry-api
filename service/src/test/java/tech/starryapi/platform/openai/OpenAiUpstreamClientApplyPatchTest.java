package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountTokenRefreshService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.config.ProductionWebClients;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformToolUse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The editing tool of Codex on the native paths, pinned byte for byte: what {@link OpenAiUpstreamClient} sends of the tool
 * and its {@code tool_choice}, and what it makes of the calls that come back. The rules are {@link ApplyPatchEmulation}'s
 * now (the relays of the Responses entry are given the same function, design 7.6), and a change of them must not change
 * a request or a response of the accounts the client speaks Responses to. The expected texts are what the client produced
 * before the rules were taken out of it. {@code OpenAiUpstreamClientTest} is left as it was.
 */
class OpenAiUpstreamClientApplyPatchTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CUSTOM = "{\"type\":\"custom\",\"name\":\"apply_patch\",\"description\":\"Use the `apply_patch` tool to edit files.\","
        + "\"format\":{\"type\":\"grammar\",\"syntax\":\"lark\",\"definition\":\"start: x\"}}";
    private static final String FUNCTION = "{\"type\":\"function\",\"name\":\"apply_patch\",\"parameters\":{\"type\":\"object\","
        + "\"properties\":{\"input\":{\"type\":\"string\"}}},\"strict\":true}";
    private static final String NESTED = "{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\",\"description\":\"nested\",\"strict\":false}}";
    private static final String OTHER = "{\"type\":\"function\",\"name\":\"exec_command\",\"description\":\"Runs\",\"strict\":false,"
        + "\"parameters\":{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\",\"format\":\"x\"}}}}";

    /** The function the tool becomes, in the Responses shape, with the description the client gave it. */
    private static String patchFunction(String description, String strict) {
        return "{\"type\":\"function\",\"name\":\"apply_patch\",\"description\":\"" + description + "\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"patch\":{\"type\":\"string\",\"description\":\"Raw apply_patch payload, including the *** Begin Patch and *** End Patch lines.\"}},"
            + "\"required\":[\"patch\"]}" + strict + "}";
    }

    private static final String DEFAULT_DESCRIPTION = "Apply a raw patch. The patch string must start with *** Begin Patch and end with *** End Patch.";
    private static final String CUSTOM_DESCRIPTION = "Use the `apply_patch` tool to edit files.";
    private static final String CODEX_INPUT = "\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"edit\"}]}]";

    private record Exchange(String requestBody, PlatformCompletionResult result) {
    }

    private Exchange exchange(boolean codex, boolean chatShape, String request, String response) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, codex ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiUpstreamClient client = new OpenAiUpstreamClient(
                objectMapper,
                ProductionWebClients.builder().build(),
                mock(AccountTokenRefreshService.class)
            );
            ProxyAccountEntity account = new ProxyAccountEntity();
            account.setProvider(AccountPlatforms.OPENAI_PROVIDER);
            account.setAuthMethod(codex ? AccountPlatforms.OPENAI_CODEX_AUTH : AccountPlatforms.OPENAI_API_KEY_AUTH);
            account.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            account.setAccessToken("test-access-token");
            JsonNode node = objectMapper.readTree(request);
            PlatformCompletionResult result = (chatShape ? client.complete(account, node, "gpt-5.5") : client.completeResponses(account, node, "gpt-5.5")).block();
            return new Exchange(body.get(), result);
        } finally {
            server.stop(0);
        }
    }

    private static final String CODEX_REPLY = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\ndata: [DONE]\n\n";
    private static final String OPENAI_REPLY = "{\"output_text\":\"OK\"}";

    private String codexRequest(String tools, String choice) throws Exception {
        return exchange(true, false, "{\"model\":\"gpt-5.5\",\"input\":\"edit\",\"tools\":" + tools + ",\"tool_choice\":" + choice + "}", CODEX_REPLY).requestBody();
    }

    private String openAiRequest(String tools, String choice) throws Exception {
        return exchange(false, false, "{\"model\":\"gpt-5.5\",\"input\":\"edit\",\"tools\":" + tools + ",\"tool_choice\":" + choice + "}", OPENAI_REPLY).requestBody();
    }

    // ---------------------------------------------------------------------------------------- what is sent

    @Test
    void aCustomToolAndItsChoiceAreTheFunctionOnTheCodexBackend() throws Exception {
        String body = codexRequest("[" + CUSTOM + "," + OTHER + ",{\"type\":\"web_search\"}]", "{\"type\":\"custom\",\"name\":\"apply_patch\"}");

        assertThat(body).isEqualTo("{\"model\":\"gpt-5.5\"," + CODEX_INPUT + ",\"tools\":[" + patchFunction(CUSTOM_DESCRIPTION, "") + ","
            + "{\"type\":\"function\",\"name\":\"exec_command\",\"description\":\"Runs\",\"parameters\":{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}}},\"strict\":false},"
            + "{\"type\":\"web_search\"}],\"tool_choice\":{\"type\":\"function\",\"name\":\"apply_patch\"},\"store\":false,\"stream\":true}");
    }

    @Test
    void aCustomToolAndItsChoiceAreTheFunctionOnAnOpenAiKeyAndTheOtherToolsAreLeftAlone() throws Exception {
        String body = openAiRequest("[" + CUSTOM + "," + OTHER + ",{\"type\":\"web_search\"}]", "{\"type\":\"custom\",\"name\":\"apply_patch\"}");

        assertThat(body).isEqualTo("{\"model\":\"openai/gpt-5.5\",\"input\":\"edit\",\"tools\":[" + patchFunction(CUSTOM_DESCRIPTION, "") + ","
            + "{\"type\":\"function\",\"name\":\"exec_command\",\"description\":\"Runs\",\"strict\":false,"
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\",\"format\":\"x\"}}}},"
            + "{\"type\":\"web_search\"}],\"tool_choice\":{\"type\":\"function\",\"name\":\"apply_patch\"},\"stream\":false}");
    }

    @Test
    void aFunctionNamedApplyPatchGetsTheSchemaOfThePatchAndKeepsItsStrictness() throws Exception {
        String expected = "[" + patchFunction(DEFAULT_DESCRIPTION, ",\"strict\":true") + "],\"tool_choice\":{\"type\":\"function\",\"name\":\"apply_patch\"}";

        assertThat(codexRequest("[" + FUNCTION + "]", "{\"type\":\"function\",\"name\":\"apply_patch\"}"))
            .isEqualTo("{\"model\":\"gpt-5.5\"," + CODEX_INPUT + ",\"tools\":" + expected + ",\"store\":false,\"stream\":true}");
        assertThat(openAiRequest("[" + FUNCTION + "]", "{\"type\":\"function\",\"name\":\"apply_patch\"}"))
            .isEqualTo("{\"model\":\"openai/gpt-5.5\",\"input\":\"edit\",\"tools\":" + expected + ",\"stream\":false}");
    }

    @Test
    void aFunctionNestedTheChatWayIsTheSameFunction() throws Exception {
        String choice = "{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\"}}";
        String expected = "[" + patchFunction("nested", ",\"strict\":false") + "],\"tool_choice\":{\"type\":\"function\",\"name\":\"apply_patch\"}";

        assertThat(codexRequest("[" + NESTED + "]", choice))
            .isEqualTo("{\"model\":\"gpt-5.5\"," + CODEX_INPUT + ",\"tools\":" + expected + ",\"store\":false,\"stream\":true}");
        assertThat(openAiRequest("[" + NESTED + "]", choice))
            .isEqualTo("{\"model\":\"openai/gpt-5.5\",\"input\":\"edit\",\"tools\":" + expected + ",\"stream\":false}");
    }

    @Test
    void theChoiceOfAnotherToolOrOfNoSpecificToolIsNotTouched() throws Exception {
        assertThat(codexRequest("[" + OTHER + "," + CUSTOM + "]", "{\"type\":\"function\",\"name\":\"exec_command\"}"))
            .isEqualTo("{\"model\":\"gpt-5.5\"," + CODEX_INPUT + ",\"tools\":[{\"type\":\"function\",\"name\":\"exec_command\",\"description\":\"Runs\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}}},\"strict\":false},"
                + patchFunction(CUSTOM_DESCRIPTION, "") + "],\"tool_choice\":{\"type\":\"function\",\"name\":\"exec_command\"},\"store\":false,\"stream\":true}");
        assertThat(openAiRequest("[" + CUSTOM + "]", "\"auto\""))
            .isEqualTo("{\"model\":\"openai/gpt-5.5\",\"input\":\"edit\",\"tools\":[" + patchFunction(CUSTOM_DESCRIPTION, "") + "],\"tool_choice\":\"auto\",\"stream\":false}");
        assertThat(codexRequest("[" + CUSTOM + "]", "{\"type\":\"allowed_tools\",\"tools\":[]}"))
            .isEqualTo("{\"model\":\"gpt-5.5\"," + CODEX_INPUT + ",\"tools\":[" + patchFunction(CUSTOM_DESCRIPTION, "")
                + "],\"tool_choice\":{\"type\":\"allowed_tools\",\"tools\":[]},\"store\":false,\"stream\":true}");
    }

    @Test
    void aChoiceThatOnlyNamesTheToolIsAFunctionChoice() throws Exception {
        assertThat(openAiRequest("[" + CUSTOM + "]", "{\"name\":\"apply_patch\"}"))
            .isEqualTo("{\"model\":\"openai/gpt-5.5\",\"input\":\"edit\",\"tools\":[" + patchFunction(CUSTOM_DESCRIPTION, "")
                + "],\"tool_choice\":{\"type\":\"function\",\"name\":\"apply_patch\"},\"stream\":false}");
    }

    @Test
    void aChatRequestToTheCodexBackendHasTheSameFunctionAndChoice() throws Exception {
        String request = "{\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"edit\"}],\"tools\":[" + NESTED + "," + OTHER + "],"
            + "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\"}}}";

        assertThat(exchange(true, true, request, CODEX_REPLY).requestBody())
            .isEqualTo("{\"model\":\"gpt-5.5\",\"store\":false,\"stream\":true," + CODEX_INPUT + ",\"tools\":[" + patchFunction("nested", ",\"strict\":false") + ","
                + "{\"type\":\"function\",\"name\":\"exec_command\",\"description\":\"Runs\",\"parameters\":{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}}},\"strict\":false}],"
                + "\"tool_choice\":{\"type\":\"function\",\"name\":\"apply_patch\"}}");
    }

    // ---------------------------------------------------------------------------------------- what comes back

    private static final String PATCH = "*** Begin Patch\n*** Add File: a.txt\n+hello\n*** End Patch";
    private static final String PATCH_TEXT = "\"*** Begin Patch\\n*** Add File: a.txt\\n+hello\\n*** End Patch\\n\"";
    private static final String DONE = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":6,\"output_tokens\":2},\"output\":[]}}\n\ndata: [DONE]\n\n";

    private static String event(String name, String json) {
        return "event: " + name + "\ndata: " + json + "\n\n";
    }

    private static String quoted(String text) {
        return new ObjectMapper().valueToTree(text).toString();
    }

    private PlatformToolUse callOf(String stream) throws Exception {
        Exchange exchange = exchange(true, false, "{\"model\":\"gpt-5.5\",\"input\":\"x\",\"tools\":[{\"type\":\"custom\",\"name\":\"apply_patch\"}]}", stream);
        assertThat(exchange.result().toolUses()).hasSize(1);
        return exchange.result().toolUses().get(0);
    }

    private static String functionCallDone(String arguments) {
        return event("response.output_item.done", "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
            + "\"call_id\":\"call_1\",\"name\":\"apply_patch\",\"arguments\":" + quoted(arguments) + "}}") + DONE;
    }

    private void assertCustomCall(PlatformToolUse call, String id, String itemId, String input) {
        assertThat(call.id()).isEqualTo(id);
        assertThat(call.name()).isEqualTo("apply_patch");
        assertThat(call.type()).isEqualTo("custom_tool_call");
        assertThat(call.rawInput()).isEqualTo(input);
        assertThat(call.rawOutputItem().toString()).isEqualTo("{\"type\":\"custom_tool_call\",\"id\":\"" + itemId + "\",\"call_id\":\"" + id
            + "\",\"name\":\"apply_patch\",\"input\":" + quoted(input) + "}");
    }

    @Test
    void theArgumentsOfAFunctionCallBecomeTheInputOfACustomCall() throws Exception {
        String patch = PATCH + "\n";
        for (String arguments : List.of(
            "{\"patch\":" + quoted(PATCH) + "}",
            "{\"patch\":" + quoted(PATCH.replace("\n", "\r\n") + "\r\nextra words") + "}",
            "{\"input\":" + quoted("prefix\n" + PATCH + "\ntail") + "}",
            "{\"text\":" + quoted(PATCH) + "}",
            quoted(PATCH),
            PATCH,
            "prefix text\n" + PATCH + "\nextra tail")) {
            assertCustomCall(callOf(functionCallDone(arguments)), "call_1", "fc_1", patch);
        }
    }

    @Test
    void emptyOrBrokenArgumentsOfAFunctionCallAreAnEmptyInputOrTheTextAsItIs() throws Exception {
        assertCustomCall(callOf(functionCallDone("{}")), "call_1", "fc_1", "");
        // Arguments that name no patch are the input, as they came.
        assertThat(callOf(functionCallDone("{\"patch\":\"\"}")).rawInput()).isEqualTo("{\"patch\":\"\"}");
        assertThat(callOf(functionCallDone("not json {")).rawInput()).isEqualTo("not json {");
        assertThat(callOf(functionCallDone("{\"other\":1}")).rawInput()).isEqualTo("{\"other\":1}");
    }

    @Test
    void theArgumentsOfTheModelAreReadFromTheEventsThatDeliverThemInPieces() throws Exception {
        PlatformToolUse call = callOf(
            event("response.output_item.added", "{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"apply_patch\",\"arguments\":\"\"}}")
                + event("response.function_call_arguments.done", "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\",\"arguments\":"
                    + quoted("{\"patch\":" + quoted(PATCH) + "}") + "}") + DONE);

        assertThat(call.rawInput()).isEqualTo(PATCH + "\n");
        assertThat(call.input().toString()).isEqualTo("{\"_rawArguments\":" + PATCH_TEXT + "}");
        assertThat(call.type()).isEqualTo("custom_tool_call");
    }

    @Test
    void aCustomCallThatArrivesInPiecesIsTheCompleteLongestPatch() throws Exception {
        PlatformToolUse call = callOf(
            event("response.output_item.added", "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_2\",\"name\":\"apply_patch\",\"input\":\"*** Begin Patch\\n\"}}")
                + event("response.custom_tool_call_input.delta", "{\"type\":\"response.custom_tool_call_input.delta\",\"item_id\":\"ctc_1\",\"delta\":\"*** Add File: a.txt\\n+hello\\n*** End Patch\"}")
                + event("response.custom_tool_call_input.done", "{\"type\":\"response.custom_tool_call_input.done\",\"item_id\":\"ctc_1\",\"input\":\"*** Begin Patch\\n*** Add File: a.txt\\n+hello\"}") + DONE);

        assertCustomCall(call, "call_2", "ctc_1", PATCH + "\n");
    }

    @Test
    void aCallOfAnotherToolIsLeftAsItCame() throws Exception {
        Exchange function = exchange(true, false, "{\"model\":\"gpt-5.5\",\"input\":\"x\"}",
            event("response.output_item.done", "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\","
                + "\"name\":\"exec_command\",\"arguments\":\"{\\\"cmd\\\":\\\"ls\\\"}\"}}") + DONE);
        PlatformToolUse call = function.result().toolUses().get(0);
        assertThat(call.type()).isEqualTo("function_call");
        assertThat(call.rawInput()).isEqualTo("{\"cmd\":\"ls\"}");
        assertThat(call.input().toString()).isEqualTo("{\"cmd\":\"ls\"}");
        assertThat(call.rawOutputItem().toString()).isEqualTo("{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"exec_command\",\"arguments\":\"{\\\"cmd\\\":\\\"ls\\\"}\"}");

        Exchange custom = exchange(true, false, "{\"model\":\"gpt-5.5\",\"input\":\"x\"}",
            event("response.output_item.done", "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\","
                + "\"call_id\":\"call_3\",\"name\":\"freeform\",\"input\":\"ls -la\"}}") + DONE);
        PlatformToolUse freeform = custom.result().toolUses().get(0);
        assertThat(freeform.type()).isEqualTo("custom_tool_call");
        assertThat(freeform.rawInput()).isEqualTo("ls -la");
        assertThat(freeform.rawOutputItem().toString()).isEqualTo("{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_3\",\"name\":\"freeform\",\"input\":\"ls -la\"}");
    }
}
