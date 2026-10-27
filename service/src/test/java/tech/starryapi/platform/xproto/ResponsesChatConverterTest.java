package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.openai.ApplyPatchEmulation;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Design 7.6, RESPONSES: what the conversion of a Codex request to Chat does that the entry's own conversion
 * ({@code OpenAiRequestAdapter#responsesToChat}) does not, item by item as the class description lists them, and the
 * acceptance of the task: the body passes the sanitizer, the canonicalizer and the lint, for the request of a real client
 * and for any history a seeded random run can make.
 */
class ResponsesChatConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ResponsesChatConverter CONVERTER = new ResponsesChatConverter(JSON);

    /** A Claude model behind the relay that takes a reasoning level. */
    private static final Settings CLAUDE = new Settings(false, false, false, false, false, true, false, false, true, true);
    /** A gpt model behind the relay: reasoning on every turn, any history. */
    private static final Settings GPT = new Settings(true, true, false, false, false, true, false, true, false, false);

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static ObjectNode convert(String request) {
        return CONVERTER.toChat(tree(request));
    }

    private static List<String> roles(JsonNode chat) {
        List<String> roles = new ArrayList<>();
        chat.path("messages").forEach(message -> roles.add(message.path("role").stringValue()));
        return roles;
    }

    private static List<String> callIds(JsonNode message) {
        List<String> ids = new ArrayList<>();
        message.path("tool_calls").forEach(call -> ids.add(call.path("id").stringValue()));
        return ids;
    }

    private static final String CALL_A = "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_a\",\"name\":\"exec_command\","
        + "\"arguments\":\"{\\\"cmd\\\":\\\"ls\\\"}\",\"status\":\"completed\"}";
    private static final String CALL_B = "{\"type\":\"function_call\",\"id\":\"fc_2\",\"call_id\":\"call_b\",\"name\":\"exec_command\","
        + "\"arguments\":\"{\\\"cmd\\\":\\\"pwd\\\"}\"}";
    private static final String OUT_A = "{\"type\":\"function_call_output\",\"call_id\":\"call_a\",\"output\":\"a.txt\"}";
    private static final String OUT_B = "{\"type\":\"function_call_output\",\"call_id\":\"call_b\",\"output\":\"/work\"}";
    private static final String USER_HI = "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"hi\"}]}";
    private static final String REASONING = "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"opaque\"}";
    private static final String EXEC_TOOL = "{\"type\":\"function\",\"name\":\"exec_command\",\"description\":\"Runs a command\","
        + "\"strict\":false,\"parameters\":{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}},\"required\":[\"cmd\"],"
        + "\"additionalProperties\":false}}";

    private static String request(String input, String rest) {
        return "{\"model\":\"claude-sonnet-4.5\",\"input\":[" + input + "]" + (rest.isEmpty() ? "" : "," + rest) + "}";
    }

    // ---------------------------------------------------------------------------------------- the calls of a turn

    @Test
    void theCallsThatFollowEachOtherAreTheCallsOfOneAssistantMessage() {
        ObjectNode chat = convert(request(String.join(",", USER_HI, CALL_A, CALL_B, OUT_A, OUT_B), "\"tools\":[" + EXEC_TOOL + "]"));

        assertThat(roles(chat)).containsExactly("user", "assistant", "tool", "tool");
        JsonNode assistant = chat.path("messages").get(1);
        assertThat(callIds(assistant)).containsExactly("call_a", "call_b");
        assertThat(assistant.path("content").stringValue()).isEmpty();
        JsonNode call = assistant.path("tool_calls").get(0);
        assertThat(call.propertyNames()).containsExactly("id", "type", "function");
        assertThat(call.path("type").stringValue()).isEqualTo("function");
        assertThat(call.path("function").path("name").stringValue()).isEqualTo("exec_command");
        assertThat(call.path("function").path("arguments").stringValue()).isEqualTo("{\"cmd\":\"ls\"}");
        assertThat(chat.path("messages").get(2).path("tool_call_id").stringValue()).isEqualTo("call_a");
        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("a.txt");
        assertThat(chat.path("messages").get(3).path("tool_call_id").stringValue()).isEqualTo("call_b");
    }

    @Test
    void theCallsOfAModelTurnThatBeganWithTextJoinThatMessageWhateverReasoningIsBetween() {
        String text = "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Let me look.\"}]}";

        ObjectNode chat = convert(request(String.join(",", USER_HI, REASONING, text, REASONING, CALL_A, REASONING, CALL_B, OUT_A, OUT_B), ""));

        assertThat(roles(chat)).containsExactly("user", "assistant", "tool", "tool");
        JsonNode assistant = chat.path("messages").get(1);
        assertThat(assistant.path("content").stringValue()).isEqualTo("Let me look.");
        assertThat(callIds(assistant)).containsExactly("call_a", "call_b");
    }

    @Test
    void aCallAfterAnAnswerStartsAnAssistantMessageOfItsOwn() {
        ObjectNode chat = convert(request(String.join(",", USER_HI, CALL_A, OUT_A, CALL_B, OUT_B), ""));

        assertThat(roles(chat)).containsExactly("user", "assistant", "tool", "assistant", "tool");
        assertThat(callIds(chat.path("messages").get(1))).containsExactly("call_a");
        assertThat(callIds(chat.path("messages").get(3))).containsExactly("call_b");
    }

    @Test
    void aCallAfterAUserMessageDoesNotJoinTheAssistantMessageBeforeIt() {
        String answer = "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}";

        ObjectNode chat = convert(request(String.join(",", USER_HI, answer, USER_HI, CALL_A), ""));

        assertThat(roles(chat)).containsExactly("user", "assistant", "user", "assistant");
        assertThat(chat.path("messages").get(1).has("tool_calls")).isFalse();
        assertThat(callIds(chat.path("messages").get(3))).containsExactly("call_a");
    }

    @Test
    void aCallIsKnownByItsCallIdAndOnlyThenByTheIdOfTheItem() {
        String byId = "{\"type\":\"function_call\",\"id\":\"fc_9\",\"name\":\"f\",\"arguments\":\"{}\"}";
        String blank = "{\"type\":\"function_call\",\"id\":\"fc_8\",\"call_id\":\" \",\"name\":\"f\",\"arguments\":\"{}\"}";

        ObjectNode chat = convert(request(String.join(",", USER_HI, CALL_A, byId, blank), ""));

        assertThat(callIds(chat.path("messages").get(1))).containsExactly("call_a", "fc_9", "fc_8");
    }

    @Test
    void theArgumentsOfACallAreTheStringTheClientWroteAndNeverNothing() {
        String object = "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"f\",\"arguments\":{\"a\":1}}";
        String none = "{\"type\":\"function_call\",\"call_id\":\"c2\",\"name\":\"f\"}";

        ObjectNode chat = convert(request(String.join(",", USER_HI, CALL_A, object, none), ""));

        JsonNode calls = chat.path("messages").get(1).path("tool_calls");
        assertThat(calls.get(0).path("function").path("arguments").stringValue()).isEqualTo("{\"cmd\":\"ls\"}");
        assertThat(calls.get(1).path("function").path("arguments").stringValue()).isEqualTo("{\"a\":1}");
        assertThat(calls.get(2).path("function").path("arguments").stringValue()).isEqualTo("{}");
    }

    @Test
    void anOutputIsTheTextOfItsPartsAndAnImageIsNotText() {
        String parts = "{\"type\":\"function_call_output\",\"call_id\":\"call_a\",\"output\":["
            + "{\"type\":\"input_text\",\"text\":\"line one\"},{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,AAAA\"},"
            + "{\"type\":\"output_text\",\"text\":\"line two\"}]}";
        String imageOnly = "{\"type\":\"function_call_output\",\"call_id\":\"call_b\",\"output\":[{\"type\":\"input_image\",\"image_url\":\"x\"}]}";
        String structured = "{\"type\":\"function_call_output\",\"call_id\":\"call_c\",\"output\":{\"ok\":true}}";
        String missing = "{\"type\":\"function_call_output\",\"call_id\":\"call_d\"}";

        ObjectNode chat = convert(request(String.join(",", USER_HI, CALL_A, CALL_B, parts, imageOnly, structured, missing), ""));

        JsonNode messages = chat.path("messages");
        assertThat(messages.get(2).path("content").stringValue()).isEqualTo("line one\nline two");
        assertThat(messages.get(3).path("content").stringValue()).isEmpty();
        assertThat(messages.get(4).path("content").stringValue()).isEqualTo("{\"ok\":true}");
        assertThat(messages.get(5).path("content").stringValue()).isEmpty();
        assertThat(messages.toString()).doesNotContain("AAAA");
    }

    // ---------------------------------------------------------------------------------------- what is left out

    @Test
    void reasoningAndReferencesAndWhatNobodyKnowsAreNotMessages() {
        String reference = "{\"type\":\"item_reference\",\"id\":\"msg_0\"}";
        String compaction = "{\"type\":\"compaction\",\"id\":\"cmp_1\",\"encrypted_content\":\"opaque\"}";
        String search = "{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\"}";
        String unknownRole = "{\"type\":\"message\",\"role\":\"tool\",\"content\":\"x\"}";
        String typeless = "{\"foo\":\"bar\"}";

        ObjectNode chat = convert(request(String.join(",", USER_HI, REASONING, reference, compaction, search, unknownRole, typeless, "\"stray\"", "7"), ""));

        assertThat(roles(chat)).containsExactly("user");
        assertThat(chat.toString()).doesNotContain("opaque", "msg_0", "ws_1", "bar", "stray");
    }

    @Test
    void theRequestIsNotCopiedAsItIsOnlyWhatChatHasIsConverted() {
        ObjectNode chat = convert("{\"model\":\"claude-sonnet-4.5\",\"previous_response_id\":\"resp_1\",\"store\":false,\"stream\":true,"
            + "\"include\":[\"reasoning.encrypted_content\"],\"prompt_cache_key\":\"k\",\"user\":\"u\",\"metadata\":{\"a\":\"b\"},"
            + "\"client_metadata\":{\"session_id\":\"s\"},\"service_tier\":\"priority\",\"truncation\":\"auto\",\"max_tool_calls\":3,"
            + "\"top_logprobs\":2,\"safety_identifier\":\"x\",\"text\":{\"verbosity\":\"low\"},\"temperature\":0.5,\"top_p\":0.9,"
            + "\"max_output_tokens\":4096,\"input\":\"hello\"}");

        assertThat(chat.propertyNames()).containsExactly("model", "temperature", "top_p", "max_tokens", "stream", "messages");
        assertThat(chat.path("max_tokens").intValue()).isEqualTo(4096);
        assertThat(chat.path("stream").booleanValue()).isTrue();
        assertThat(chat.path("temperature").doubleValue()).isEqualTo(0.5);
        assertThat(chat.path("messages")).isEqualTo(tree("[{\"role\":\"user\",\"content\":\"hello\"}]"));
    }

    @Test
    void theRequestIsNotChangedAndTheResultSharesNothingWithIt() {
        JsonNode request = tree(request(String.join(",", USER_HI, CALL_A, OUT_A), "\"tools\":[" + EXEC_TOOL + "],"
            + "\"tool_choice\":{\"type\":\"allowed_tools\",\"tools\":[{\"type\":\"function\",\"name\":\"exec_command\"}]},"
            + "\"text\":{\"format\":{\"type\":\"json_schema\",\"name\":\"n\",\"schema\":{\"type\":\"object\",\"properties\":{}}}}"));
        String before = request.toString();

        ObjectNode chat = CONVERTER.toChat(request);
        ((ObjectNode) chat.path("messages").get(0)).put("role", "changed");
        ((ObjectNode) chat.path("tools").get(0).path("function")).put("name", "changed");
        ((ObjectNode) chat.path("tool_choice")).put("type", "changed");
        ((ObjectNode) chat.path("response_format").path("json_schema").path("schema")).put("type", "changed");

        assertThat(request.toString()).isEqualTo(before);
    }

    @Test
    void aBodyThatIsNotAnObjectCannotBeConverted() {
        assertThatThrownBy(() -> CONVERTER.toChat(tree("[]"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CONVERTER.toChat(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------- the messages

    @Test
    void theInstructionsAndTheDeveloperMessagesAreSystemMessages() {
        ObjectNode chat = convert("{\"instructions\":\"You are Codex.\",\"input\":["
            + "{\"type\":\"message\",\"role\":\"developer\",\"content\":[{\"type\":\"input_text\",\"text\":\"<permissions/>\"},"
            + "{\"type\":\"input_text\",\"text\":\" \"},{\"type\":\"input_text\",\"text\":\"<skills/>\"}]},"
            + "{\"role\":\"system\",\"content\":\"be brief\"},"
            + USER_HI + "]}");

        assertThat(roles(chat)).containsExactly("system", "system", "system", "user");
        assertThat(chat.path("messages").get(0).path("content").stringValue()).isEqualTo("You are Codex.");
        assertThat(chat.path("messages").get(1).path("content").stringValue()).isEqualTo("<permissions/>\n<skills/>");
        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("be brief");
        assertThat(chat.toString()).doesNotContain("developer");
    }

    @Test
    void blankInstructionsAreNoMessage() {
        assertThat(roles(convert("{\"instructions\":\"  \",\"input\":\"x\"}"))).containsExactly("user");
        assertThat(roles(convert("{\"instructions\":null,\"input\":\"x\"}"))).containsExactly("user");
    }

    @Test
    void aMessageWithoutATypeIsAMessageWhenItHasARole() {
        ObjectNode chat = convert("{\"input\":[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"},{\"content\":\"c\"}]}");

        assertThat(roles(chat)).containsExactly("user", "assistant");
        assertThat(chat.path("messages").get(1).path("content").stringValue()).isEqualTo("b");
    }

    @Test
    void theTextOfAMessageIsItsTextPartsAndARefusalIsText() {
        ObjectNode chat = convert(request("{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"a\"},"
            + "{\"type\":\"input_text\",\"text\":\"b\"}]},"
            + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"c\",\"annotations\":[]},"
            + "{\"type\":\"refusal\",\"refusal\":\"I cannot.\"}]},"
            + "{\"type\":\"message\",\"role\":\"user\",\"content\":[]},"
            + "{\"type\":\"message\",\"role\":\"user\"}", ""));

        JsonNode messages = chat.path("messages");
        assertThat(messages.get(0).path("content").stringValue()).isEqualTo("a\nb");
        assertThat(messages.get(1).path("content").stringValue()).isEqualTo("c\nI cannot.");
        assertThat(messages.get(2).path("content").stringValue()).isEmpty();
        assertThat(messages.get(3).path("content").stringValue()).isEmpty();
    }

    @Test
    void aUserMessageWithImagesKeepsItsPartsInOrder() {
        ObjectNode chat = convert(request("{\"type\":\"message\",\"role\":\"user\",\"content\":["
            + "{\"type\":\"input_text\",\"text\":\"what is this\"},"
            + "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,AAAA\",\"detail\":\"high\"},"
            + "{\"type\":\"input_image\",\"image_url\":\"https://example.com/a.png\",\"detail\":\"original\"},"
            + "{\"type\":\"input_image\",\"file_id\":\"file_1\",\"detail\":\"auto\"},"
            + "{\"type\":\"input_text\",\"text\":\" \"},"
            + "{\"type\":\"input_image\",\"image_url\":{\"url\":\"https://example.com/b.png\",\"detail\":\"low\"}},"
            + "{\"type\":\"input_file\",\"filename\":\"notes.txt\",\"file_id\":\"file_2\",\"file_data\":\"BBBB\"}]}", ""));

        JsonNode content = chat.path("messages").get(0).path("content");
        assertThat(content).isEqualTo(tree("["
            + "{\"type\":\"text\",\"text\":\"what is this\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\",\"detail\":\"high\"}},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/a.png\"}},"
            + "{\"type\":\"image_url\",\"image_url\":{\"detail\":\"auto\"}},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/b.png\",\"detail\":\"low\"}},"
            + "{\"type\":\"file\",\"file\":{\"filename\":\"notes.txt\",\"file_id\":\"file_2\"}}]"));
        assertThat(chat.toString()).doesNotContain("BBBB");
    }

    @Test
    void whatTheSanitizerReplacesForAMemberItIsGivenInTheWordsOfThePolicy() {
        ObjectNode chat = convert(request("{\"type\":\"message\",\"role\":\"user\",\"content\":["
            + "{\"type\":\"input_text\",\"text\":\"look\"},{\"type\":\"input_image\",\"file_id\":\"file_1\"},"
            + "{\"type\":\"input_file\",\"filename\":\"notes.txt\"}]}", ""));

        Wire wire = ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, null, GPT, "relay-model"), GPT);

        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        assertThat(wire.body().path("messages").get(0).path("content").toString())
            .isEqualTo("[{\"type\":\"text\",\"text\":\"look\"},{\"type\":\"text\",\"text\":\"[image omitted]\"},{\"type\":\"text\",\"text\":\"[file omitted]\"}]");
        assertThat(wire.degraded()).containsExactlyInAnyOrder("image", "file");
    }

    // ---------------------------------------------------------------------------------------- tools and tool_choice

    @Test
    void aFunctionIsTheFunctionOfTheChatShapeWithTheSchemaOfTheOtherConversions() {
        ObjectNode chat = convert(request(USER_HI, "\"tools\":[" + EXEC_TOOL + ","
            + "{\"type\":\"function\",\"name\":\"no_schema\"},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"nested\",\"description\":\"Chat shape\",\"parameters\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":[\"string\",\"null\"],\"format\":\"uri\"}}}}},"
            + "{\"name\":\"typeless\",\"description\":\" \",\"parameters\":{\"type\":\"object\"}},"
            + "{\"type\":\"function\",\"name\":\" \"}],\"parallel_tool_calls\":false"));

        JsonNode tools = chat.path("tools");
        assertThat(tools).hasSize(4);
        assertThat(tools.get(0)).isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"exec_command\",\"description\":\"Runs a command\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"}},\"required\":[\"cmd\"],\"additionalProperties\":false},"
            + "\"strict\":false}}"));
        assertThat(tools.get(1)).isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"no_schema\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}"));
        assertThat(tools.get(2).path("function").path("description").stringValue()).isEqualTo("Chat shape");
        assertThat(tools.get(2).path("function").path("parameters").path("properties").path("a").has("format")).isFalse();
        assertThat(tools.get(3).path("function").propertyNames()).containsExactly("name", "parameters");
        assertThat(chat.path("parallel_tool_calls").booleanValue()).isFalse();
    }

    @Test
    void theToolsNobodyCanServeAreLeftOutAndTheChoicesThatGoWithNoToolsGoWithThem() {
        ObjectNode chat = convert(request(USER_HI, "\"tools\":[{\"type\":\"web_search\",\"external_web_access\":false},"
            + "{\"type\":\"tool_search\",\"execution\":\"client\"},{\"type\":\"file_search\"},{\"type\":\"mcp\",\"server_label\":\"x\"},"
            + "{\"type\":\"custom\",\"name\":\"other_freeform\"}],\"tool_choice\":\"required\",\"parallel_tool_calls\":true"));

        assertThat(chat.has("tools")).isFalse();
        assertThat(chat.has("tool_choice")).isFalse();
        assertThat(chat.has("parallel_tool_calls")).isFalse();

        ObjectNode some = convert(request(USER_HI, "\"tools\":[{\"type\":\"web_search\"}," + EXEC_TOOL + "],\"tool_choice\":\"required\""));
        assertThat(some.path("tools")).hasSize(1);
        assertThat(some.path("tool_choice").stringValue()).isEqualTo("required");
    }

    @Test
    void theChoiceOfAFunctionIsInTheChatShape() {
        String tools = "\"tools\":[" + EXEC_TOOL + "],";
        for (String choice : List.of("{\"type\":\"function\",\"name\":\"exec_command\"}",
            "{\"type\":\"function\",\"function\":{\"name\":\"exec_command\"}}")) {
            ObjectNode chat = convert(request(USER_HI, tools + "\"tool_choice\":" + choice));

            assertThat(chat.path("tool_choice")).isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"exec_command\"}}"));
        }
        for (String choice : List.of("auto", "none", "required")) {
            assertThat(convert(request(USER_HI, tools + "\"tool_choice\":\"" + choice + "\"")).path("tool_choice").stringValue()).isEqualTo(choice);
        }
    }

    @Test
    void aChoiceARelayHasNoWordForIsHandedOnAsItIsForTheSanitizerToRecord() {
        String tools = "\"tools\":[" + EXEC_TOOL + "],";
        for (String choice : List.of(
            "{\"type\":\"allowed_tools\",\"mode\":\"auto\",\"tools\":[{\"type\":\"function\",\"name\":\"exec_command\"}]}",
            "{\"type\":\"web_search_preview\"}",
            "{\"type\":\"custom\",\"name\":\"other_freeform\"}",
            "{\"type\":\"function\"}",
            "\"web_search\"")) {
            ObjectNode chat = convert(request(USER_HI, tools + "\"tool_choice\":" + choice));

            assertThat(chat.path("tool_choice")).isEqualTo(tree(choice));
            Wire wire = ChatWireSanitizer.sanitize(chat, null, CLAUDE, "relay-model");
            assertThat(wire.body().has("tool_choice")).isFalse();
            assertThat(wire.degraded()).contains("tool_choice");
        }
    }

    // ---------------------------------------------------------------------------------------- apply_patch

    private static final String PATCH = "*** Begin Patch\n*** Update File: a.txt\n@@\n-hello\n+hello \"world\"\n*** End Patch\n";

    /**
     * The structured-output promise of a function is the client's, as the Chat entry and the native Codex path carry it: a relay is
     * asked to keep to the schema where the client asked it to, and nothing is said where the client said nothing.
     */
    @Test
    void theStrictOfAFunctionIsCarriedAsTheClientWroteItAndOnlyWhenItIsABoolean() {
        ObjectNode chat = convert(request(USER_HI, "\"tools\":["
            + "{\"type\":\"function\",\"name\":\"on\",\"strict\":true,\"parameters\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
            + "\"required\":[\"a\"],\"additionalProperties\":false}},"
            + "{\"type\":\"function\",\"name\":\"off\",\"strict\":false},"
            + "{\"type\":\"function\",\"name\":\"words\",\"strict\":\"yes\"},"
            + "{\"type\":\"function\",\"name\":\"unsaid\"},"
            + "{\"type\":\"function\",\"function\":{\"name\":\"nested\",\"strict\":true}}]"));

        JsonNode tools = chat.path("tools");
        assertThat(tools.get(0).path("function").path("strict").booleanValue()).isTrue();
        assertThat(tools.get(1).path("function").path("strict").booleanValue()).isFalse();
        assertThat(tools.get(2).path("function").has("strict")).isFalse();
        assertThat(tools.get(3).path("function").has("strict")).isFalse();
        assertThat(tools.get(4).path("function").path("strict").booleanValue()).isTrue();
        // The sanitizer keeps it, with the schema it was declared over, which is not changed.
        Wire wire = sendable(tree(request(USER_HI, "\"tools\":[{\"type\":\"function\",\"name\":\"on\",\"strict\":true,\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"],\"additionalProperties\":false}}]")), CLAUDE);
        assertSendable(wire);
        assertThat(wire.body().path("tools").get(0).path("function").path("strict").booleanValue()).isTrue();
        assertThat(wire.body().path("tools").get(0).path("function").path("parameters").path("additionalProperties").booleanValue()).isFalse();
    }

    private static String quoted(String text) {
        return JSON.valueToTree(text).toString();
    }

    private static final String APPLY_PATCH_CUSTOM = "{\"type\":\"custom\",\"name\":\"apply_patch\",\"description\":\"Use the `apply_patch` tool to edit files.\","
        + "\"format\":{\"type\":\"grammar\",\"syntax\":\"lark\",\"definition\":\"start: begin_patch hunk+ end_patch\"}}";

    @Test
    void theCustomToolApplyPatchIsTheFunctionTheCodexPathSendsAsWell() {
        ObjectNode chat = convert(request(USER_HI, "\"tools\":[" + APPLY_PATCH_CUSTOM + "," + EXEC_TOOL + "],\"tool_choice\":{\"type\":\"custom\",\"name\":\"apply_patch\"}"));

        JsonNode tool = chat.path("tools").get(0);
        assertThat(tool.propertyNames()).containsExactly("type", "function");
        JsonNode function = tool.path("function");
        assertThat(function.propertyNames()).containsExactly("name", "description", "parameters");
        assertThat(function.path("name").stringValue()).isEqualTo("apply_patch");
        // Codex's own words are those of the custom tool (free-form, no JSON), which this function is not.
        assertThat(function.path("description").stringValue()).isEqualTo(ApplyPatchEmulation.CHAT_DESCRIPTION);
        assertThat(function.path("parameters").path("properties").path("patch").path("type").stringValue()).isEqualTo("string");
        assertThat(function.path("parameters").path("required")).isEqualTo(tree("[\"patch\"]"));
        assertThat(chat.path("tools")).hasSize(2);
        assertThat(chat.path("tool_choice")).isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"apply_patch\"}}"));
        // The same function as the native Codex path sends, in the Chat shape and with the words that suit a JSON argument.
        ObjectNode emulated = new ApplyPatchEmulation(JSON).functionTool(tree(APPLY_PATCH_CUSTOM));
        emulated.remove("type");
        emulated.put("description", ApplyPatchEmulation.CHAT_DESCRIPTION);
        assertThat(function).isEqualTo(emulated);
    }

    @Test
    void applyPatchDeclaredAsAFunctionIsTheSameFunctionAsOnTheNativePath() {
        ObjectNode chat = convert(request(USER_HI, "\"tools\":[{\"type\":\"function\",\"name\":\"apply_patch\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"input\":{\"type\":\"string\"}}}}],\"tool_choice\":{\"type\":\"function\",\"name\":\"apply_patch\"}"));

        assertThat(chat.path("tools").get(0).path("function").path("parameters").path("properties").propertyNames()).containsExactly("patch");
        assertThat(chat.path("tool_choice").path("function").path("name").stringValue()).isEqualTo("apply_patch");
    }

    @Test
    void aCustomCallOfApplyPatchAndItsAnswerAreACallOfTheFunctionAndItsResult() {
        String call = "{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_p\",\"name\":\"apply_patch\",\"input\":" + quoted(PATCH) + ",\"status\":\"completed\"}";
        String answer = "{\"type\":\"custom_tool_call_output\",\"call_id\":\"call_p\",\"output\":\"Success. Updated the following files:\\nM a.txt\\n\"}";

        ObjectNode chat = convert(request(String.join(",", USER_HI, REASONING, call, answer), "\"tools\":[" + APPLY_PATCH_CUSTOM + "]"));

        assertThat(roles(chat)).containsExactly("user", "assistant", "tool");
        JsonNode function = chat.path("messages").get(1).path("tool_calls").get(0).path("function");
        assertThat(callIds(chat.path("messages").get(1))).containsExactly("call_p");
        assertThat(function.path("name").stringValue()).isEqualTo("apply_patch");
        assertThat(tree(function.path("arguments").stringValue())).isEqualTo(tree("{\"patch\":" + quoted(PATCH) + "}"));
        assertThat(chat.path("messages").get(2).path("tool_call_id").stringValue()).isEqualTo("call_p");
        assertThat(chat.path("messages").get(2).path("content").stringValue()).isEqualTo("Success. Updated the following files:\nM a.txt\n");
    }

    @Test
    void aPatchGoesToTheRelayAndComesBackAsTheSamePatch() {
        ApplyPatchEmulation emulation = new ApplyPatchEmulation(JSON);
        String call = "{\"type\":\"custom_tool_call\",\"call_id\":\"call_p\",\"name\":\"apply_patch\",\"input\":" + quoted(PATCH) + "}";
        ObjectNode chat = convert(request(String.join(",", USER_HI, call), ""));
        String arguments = chat.path("messages").get(1).path("tool_calls").get(0).path("function").path("arguments").stringValue();

        // What the relay's model writes in its own call is what the history had, and the response side makes the custom call of it.
        String type = emulation.toolUseType("function_call", "apply_patch");
        assertThat(type).isEqualTo("custom_tool_call");
        assertThat(emulation.toolUseRawInput(type, "apply_patch", arguments)).isEqualTo(PATCH);
    }

    @Test
    void aCustomCallOfAnotherToolIsAFunctionCallWithItsTextAsTheInput() {
        String call = "{\"type\":\"custom_tool_call\",\"call_id\":\"call_q\",\"name\":\"shell_freeform\",\"input\":\"ls -la\"}";
        String missing = "{\"type\":\"custom_tool_call\",\"call_id\":\"call_r\",\"name\":\"apply_patch\"}";

        ObjectNode chat = convert(request(String.join(",", USER_HI, call, missing), ""));

        JsonNode calls = chat.path("messages").get(1).path("tool_calls");
        assertThat(calls.get(0).path("function").path("name").stringValue()).isEqualTo("shell_freeform");
        assertThat(calls.get(0).path("function").path("arguments").stringValue()).isEqualTo("{\"input\":\"ls -la\"}");
        assertThat(calls.get(1).path("function").path("arguments").stringValue()).isEqualTo("{\"patch\":\"\"}");
    }

    // ---------------------------------------------------------------------------------------- reasoning and format

    @Test
    void theReasoningEffortIsTheOneTheSanitizerReads() {
        ObjectNode chat = convert(request(USER_HI, "\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"}"));

        assertThat(chat.path("reasoning_effort").stringValue()).isEqualTo("high");
        assertThat(ChatWireSanitizer.sanitize(chat, null, CLAUDE, "relay-model").body().path("reasoning_effort").stringValue()).isEqualTo("high");
        assertThat(convert(request(USER_HI, "\"reasoning\":{\"summary\":\"auto\"}")).has("reasoning_effort")).isFalse();
        assertThat(convert(request(USER_HI, "\"reasoning\":null")).has("reasoning_effort")).isFalse();
    }

    @Test
    void theFormatOfTheTextIsAResponseFormat() {
        ObjectNode schema = convert(request(USER_HI, "\"text\":{\"verbosity\":\"low\",\"format\":{\"type\":\"json_schema\",\"name\":\"answer\","
            + "\"description\":\"The answer\",\"strict\":true,\"schema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}}}"));

        assertThat(schema.path("response_format")).isEqualTo(tree("{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"answer\","
            + "\"description\":\"The answer\",\"schema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}},\"strict\":true}}"));
        assertThat(schema.toString()).doesNotContain("verbosity");
        assertThat(convert(request(USER_HI, "\"text\":{\"format\":{\"type\":\"json_object\"}}")).path("response_format"))
            .isEqualTo(tree("{\"type\":\"json_object\"}"));
        assertThat(convert(request(USER_HI, "\"text\":{\"format\":{\"type\":\"text\"}}")).has("response_format")).isFalse();
        assertThat(convert(request(USER_HI, "\"text\":{\"format\":{\"type\":\"json_schema\",\"name\":\"n\"}}")).has("response_format")).isFalse();
        assertThat(convert(request(USER_HI, "\"text\":{\"format\":{\"type\":\"json_schema\",\"schema\":{\"type\":\"object\"}}}"))
            .path("response_format").path("json_schema").path("name").stringValue()).isEqualTo("response");
    }

    // ---------------------------------------------------------------------------------------- the acceptance: the lint

    /** A Codex request as it comes on the wire, with the text of the long instructions and the identifiers cut down. */
    private static final String CODEX = """
        {"model":"claude-sonnet-4.5","instructions":"You are Codex, a coding agent.",
         "input":[
          {"type":"message","role":"developer","content":[{"type":"input_text","text":"<permissions instructions>Filesystem sandboxing.</permissions instructions>"},
            {"type":"input_text","text":"<skills_instructions>## Skills</skills_instructions>"}]},
          {"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context><cwd>/work</cwd></environment_context>"}]},
          {"type":"message","role":"user","content":[{"type":"input_text","text":"fix the greeting in a.txt"}]},
          {"type":"reasoning","id":"rs_0001","summary":[],"encrypted_content":"opaque-1"},
          {"type":"message","role":"assistant","content":[{"type":"output_text","text":"I will read it first."}]},
          {"type":"function_call","id":"fc_0001","call_id":"call_0001","name":"exec_command","arguments":"{\\"cmd\\":\\"cat a.txt\\"}","status":"completed"},
          {"type":"function_call","id":"fc_0002","call_id":"call_0002","name":"exec_command","arguments":"{\\"cmd\\":\\"ls\\"}","status":"completed"},
          {"type":"function_call_output","call_id":"call_0001","output":"hello\\n"},
          {"type":"function_call_output","call_id":"call_0002","output":"a.txt\\n"},
          {"type":"reasoning","id":"rs_0002","summary":[],"encrypted_content":"opaque-2"},
          {"type":"custom_tool_call","id":"ctc_0001","call_id":"call_0003","name":"apply_patch","status":"completed",
           "input":"*** Begin Patch\\n*** Update File: a.txt\\n@@\\n-hello\\n+hello world\\n*** End Patch\\n"},
          {"type":"custom_tool_call_output","call_id":"call_0003","output":"Success. Updated the following files:\\nM a.txt\\n"},
          {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Done."}]},
          {"type":"message","role":"user","content":[{"type":"input_text","text":"now run the tests"}]}],
         "tools":[
          {"type":"function","name":"exec_command","description":"Runs a command in a PTY.","strict":false,
           "parameters":{"type":"object","properties":{"cmd":{"type":"string","description":"Shell command to execute."},"yield_time_ms":{"type":"number"}},"required":["cmd"],"additionalProperties":false}},
          {"type":"function","name":"update_plan","description":"Updates the task plan.","strict":false,
           "parameters":{"type":"object","properties":{"plan":{"type":"array","items":{"type":"object","properties":{"step":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","completed"]}},"required":["step","status"],"additionalProperties":false}}},"required":["plan"],"additionalProperties":false}},
          {"type":"custom","name":"apply_patch","description":"Use the `apply_patch` tool to edit files. This is a FREEFORM tool, so do not wrap the patch in JSON.",
           "format":{"type":"grammar","syntax":"lark","definition":"start: begin_patch hunk+ end_patch"}}],
         "tool_choice":"auto","parallel_tool_calls":true,"reasoning":{"effort":"medium","summary":"auto"},"store":false,"stream":true,
         "include":["reasoning.encrypted_content"],"prompt_cache_key":"00000000-0000-0000-0000-000000000001","text":{"verbosity":"low"},
         "client_metadata":{"session_id":"00000000-0000-0000-0000-000000000001","thread_id":"00000000-0000-0000-0000-000000000001"}}
        """;

    private static Wire sendable(JsonNode request, Settings settings) {
        return ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(CONVERTER.toChat(request), null, settings, "relay-model"), settings);
    }

    private static void assertSendable(Wire wire) {
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        assertThat(wire.body().propertyNames()).isSubsetOf(ChatWireSanitizer.BODY_KEYS);
    }

    @Test
    void aCodexRequestIsSentAsAStrictChatRequest() {
        Wire wire = sendable(tree(CODEX), CLAUDE);

        assertSendable(wire);
        JsonNode body = wire.body();
        assertThat(body.propertyNames()).containsExactly("model", "messages", "tools", "tool_choice", "parallel_tool_calls", "reasoning_effort", "stream");
        assertThat(body.path("model").stringValue()).isEqualTo("relay-model");
        assertThat(body.path("stream").booleanValue()).isFalse();
        assertThat(body.path("reasoning_effort").stringValue()).isEqualTo("medium");
        assertThat(roles(body)).containsExactly("system", "system", "user", "assistant", "tool", "tool", "assistant", "tool", "assistant", "user");
        // The two user messages of the start are one, the text of the model's turn carries its calls.
        assertThat(body.path("messages").get(2).path("content").stringValue()).contains("<environment_context>").contains("fix the greeting");
        assertThat(body.path("messages").get(3).path("content").stringValue()).isEqualTo("I will read it first.");
        assertThat(callIds(body.path("messages").get(3))).containsExactly("call_0001", "call_0002");
        assertThat(callIds(body.path("messages").get(6))).containsExactly("call_0003");
        List<String> names = new ArrayList<>();
        body.path("tools").forEach(tool -> names.add(tool.path("function").path("name").stringValue()));
        assertThat(names).containsExactly("exec_command", "update_plan", "apply_patch");
        assertThat(body.toString()).doesNotContain("opaque", "previous_response_id", "prompt_cache_key", "client_metadata", "session_id",
            "include", "verbosity", "store", "developer", "grammar");
        // The functions of the client carry the strict they were declared with; the editing tool, which is no function of its own, none.
        assertThat(body.path("tools").get(0).path("function").path("strict").booleanValue()).isFalse();
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void theSameRequestIsSentToAGptModelAndKeepsItsReasoningOnTheLastTurn() {
        ObjectNode withResult = (ObjectNode) tree(CODEX);
        ((ArrayNode) withResult.path("input")).removeAll();
        ArrayNode input = (ArrayNode) withResult.path("input");
        input.add(tree("{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"go\"}]}"));
        input.add(tree(CALL_A));
        input.add(tree(OUT_A));

        Wire claude = sendable(withResult, CLAUDE);
        Wire gpt = sendable(withResult, GPT);

        assertSendable(claude);
        assertSendable(gpt);
        // The turn after a tool call needs the thinking of the one before for Claude; a gpt model takes its level every turn.
        assertThat(claude.body().has("reasoning_effort")).isFalse();
        assertThat(claude.degraded()).containsExactly("reasoning_effort");
        assertThat(gpt.body().path("reasoning_effort").stringValue()).isEqualTo("medium");
    }

    @Test
    void aCodexRequestWithoutToolsKeepsItsToolHistoryAsTextForAClaudeModel() {
        ObjectNode request = (ObjectNode) tree(CODEX);
        request.remove("tools");

        Wire wire = sendable(request, CLAUDE);

        assertSendable(wire);
        assertThat(wire.body().has("tools")).isFalse();
        assertThat(wire.body().toString()).contains("Tool use: exec_command").contains("Tool use: apply_patch").doesNotContain("tool_calls");
        assertThat(wire.degraded()).contains("tool_history_flattened");
    }

    @Test
    void theHistoryOfARelayThatNumbersItsCallsFromZeroEveryTurnStillPassesTheLint() {
        StringBuilder input = new StringBuilder(USER_HI);
        for (int turn = 0; turn < 3; turn++) {
            input.append(",{\"type\":\"function_call\",\"id\":\"fc_").append(turn).append("\",\"call_id\":\"call_0\",\"name\":\"exec_command\",\"arguments\":\"{}\"}")
                .append(",{\"type\":\"function_call_output\",\"call_id\":\"call_0\",\"output\":\"r").append(turn).append("\"}");
        }
        input.append(",{\"type\":\"custom_tool_call\",\"call_id\":\"call_0\",\"name\":\"apply_patch\",\"input\":\"p\"},")
            .append("{\"type\":\"custom_tool_call_output\",\"call_id\":\"call_0\",\"output\":\"ok\"},").append(USER_HI);

        Wire wire = sendable(tree(request(input.toString(), "\"tools\":[" + EXEC_TOOL + "]")), CLAUDE);

        assertSendable(wire);
        assertThat(roles(wire.body())).containsExactly("user", "assistant", "tool", "assistant", "tool", "assistant", "tool", "assistant", "tool", "user");
    }

    @Test
    void whateverOrderTheAnswersCameInEachIsPairedWithItsCall() {
        Wire wire = sendable(tree(request(String.join(",", USER_HI, CALL_A, CALL_B, OUT_B, OUT_A, USER_HI), "\"tools\":[" + EXEC_TOOL + "]")), CLAUDE);

        assertSendable(wire);
        assertThat(roles(wire.body())).containsExactly("user", "assistant", "tool", "tool", "user");
        assertThat(wire.body().path("messages").get(2).path("tool_call_id").stringValue()).isEqualTo("call_a");
        assertThat(wire.body().path("messages").get(2).path("content").stringValue()).isEqualTo("a.txt");
        assertThat(wire.body().path("messages").get(3).path("tool_call_id").stringValue()).isEqualTo("call_b");
    }

    // ---------------------------------------------------------------------------------------- a seeded random run

    private static final String[] TEXTS = {"hello", "run it", "  ", "", "naïve 日本語", "line\nbreak", "{\"json\":true}"};
    private static final String[] IDS = {"call_a", "call_b", "call_0", "a b", "", "call_é", "x".repeat(80)};

    private static String pick(Random random, String[] values) {
        return values[random.nextInt(values.length)];
    }

    private static String randomContent(Random random, boolean assistant) {
        if (random.nextInt(5) == 0) {
            return quoted(pick(random, TEXTS));
        }
        if (random.nextInt(12) == 0) {
            return "null";
        }
        StringBuilder parts = new StringBuilder("[");
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                parts.append(',');
            }
            switch (random.nextInt(assistant ? 3 : 5)) {
                case 0 -> parts.append("{\"type\":\"").append(assistant ? "output_text" : "input_text").append("\",\"text\":").append(quoted(pick(random, TEXTS))).append('}');
                case 1 -> parts.append("{\"type\":\"refusal\",\"refusal\":").append(quoted(pick(random, TEXTS))).append('}');
                case 2 -> parts.append("{\"type\":\"summary_text\",\"text\":\"unknown part\"}");
                case 3 -> parts.append("{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,AAAA\",\"detail\":\"").append(random.nextBoolean() ? "high" : "original").append("\"}");
                default -> parts.append(random.nextBoolean() ? "{\"type\":\"input_file\",\"file_id\":\"file_1\"}" : "{\"type\":\"input_image\",\"file_id\":\"file_2\"}");
            }
        }
        return parts.append(']').toString();
    }

    private static String randomItem(Random random) {
        String id = pick(random, IDS);
        return switch (random.nextInt(13)) {
            case 0, 1 -> "{\"type\":\"message\",\"role\":\"user\",\"content\":" + randomContent(random, false) + "}";
            case 2 -> "{\"type\":\"message\",\"role\":\"assistant\",\"content\":" + randomContent(random, true) + "}";
            case 3 -> "{\"type\":\"message\",\"role\":\"" + (random.nextBoolean() ? "developer" : "system") + "\",\"content\":" + randomContent(random, true) + "}";
            case 4, 5 -> "{\"type\":\"function_call\",\"call_id\":" + quoted(id) + ",\"name\":\"exec_command\",\"arguments\":" + (random.nextInt(4) == 0 ? "{\"a\":1}" : quoted(random.nextBoolean() ? "{}" : "{\"cmd\":\"ls\"}")) + "}";
            case 6, 7 -> "{\"type\":\"function_call_output\",\"call_id\":" + quoted(id) + ",\"output\":"
                + (random.nextBoolean() ? quoted(pick(random, TEXTS)) : randomContent(random, false)) + "}";
            case 8 -> "{\"type\":\"custom_tool_call\",\"call_id\":" + quoted(id) + ",\"name\":\"" + (random.nextBoolean() ? "apply_patch" : "freeform")
                + "\",\"input\":" + quoted(pick(random, TEXTS)) + "}";
            case 9 -> "{\"type\":\"custom_tool_call_output\",\"call_id\":" + quoted(id) + ",\"output\":" + quoted(pick(random, TEXTS)) + "}";
            case 10 -> REASONING;
            case 11 -> "{\"type\":\"item_reference\",\"id\":\"msg_1\"}";
            default -> random.nextBoolean() ? "{\"type\":\"compaction\",\"encrypted_content\":\"opaque\"}" : "\"stray\"";
        };
    }

    private static String randomTools(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> "";
            case 1 -> "\"tools\":[" + EXEC_TOOL + "],";
            case 2 -> "\"tools\":[" + EXEC_TOOL + "," + APPLY_PATCH_CUSTOM + ",{\"type\":\"web_search\"}],\"parallel_tool_calls\":true,";
            default -> "\"tools\":[{\"type\":\"web_search\"}],\"tool_choice\":\"required\",";
        };
    }

    @Test
    void anyHistoryAClientCanSendIsSentAsAStrictChatRequestAndNothingOfItIsChanged() {
        Random random = new Random(20261027L);
        for (int run = 0; run < 400; run++) {
            StringBuilder input = new StringBuilder();
            int items = 1 + random.nextInt(14);
            for (int i = 0; i < items; i++) {
                input.append(randomItem(random)).append(',');
            }
            input.append(USER_HI);
            JsonNode request = tree("{\"model\":\"claude-sonnet-4.5\"," + randomTools(random) + "\"reasoning\":{\"effort\":\"low\"},\"input\":[" + input + "]}");
            String before = request.toString();

            for (Settings settings : List.of(CLAUDE, GPT)) {
                Wire wire = sendable(request, settings);

                assertThat(StrictChatLint.problems(wire.body())).as("run %d: %s", run, before).isEmpty();
                assertThat(wire.body().propertyNames()).isSubsetOf(ChatWireSanitizer.BODY_KEYS);
                assertThat(wire.body().toString()).doesNotContain("opaque", "msg_1", "stray", "unknown part", "developer");
            }
            assertThat(request.toString()).isEqualTo(before);
        }
    }

    @Test
    void theTextOfTheUserTurnsIsNeverLost() {
        Random random = new Random(7L);
        for (int run = 0; run < 200; run++) {
            String text = "turn-" + run + "-" + random.nextInt(1000);
            String input = randomItem(random) + "," + randomItem(random) + "," + randomItem(random)
                + ",{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"" + text + "\"}]}";

            Wire wire = sendable(tree(request(input, "")), CLAUDE);

            assertThat(wire.body().path("messages").toString()).contains(text);
        }
    }
}
