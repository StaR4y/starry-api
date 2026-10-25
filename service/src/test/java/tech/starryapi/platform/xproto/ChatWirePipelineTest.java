package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sanitizer, canonicalizer and lint together, on what the real conversion makes of real clients' requests (design 7.6;
 * the acceptance of the task is that a normal Claude Code or OpenCode history is not what makes the lint skip a relay).
 * The histories are the ones design 7.14 lists, each followed by three more turns: a tool result that held only an
 * image, a tool result without content, an empty command output, a user turn that was only an image, and a relay that
 * answers every turn with {@code call_0}. A seeded random run then checks, for any Anthropic request and any Chat
 * request a client could send, that the lint passes, nothing outside the whitelist is sent, no text is lost, the input
 * is not changed and a second canonicalization changes nothing.
 */
class ChatWirePipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AnthropicRequestAdapter ADAPTER = new AnthropicRequestAdapter(JSON, new DefaultModelCatalogProvider());

    private static final Settings PLAIN = new Settings(false, false, false, false, false, false, false, false, false, false);
    /**
     * A Claude model behind the relay, with nothing else switched on: its tool continuation turns are not asked for a
     * reasoning level, and its upstream refuses a history with tool calls in a request that defines no tools.
     */
    private static final Settings CLAUDE = new Settings(false, false, false, false, false, false, false, false, true, true);
    /** A Claude model behind the relay that takes a reasoning level. */
    private static final Settings EFFORT = new Settings(false, false, false, false, false, true, false, false, true, true);
    /** An OpenAI model: it takes its reasoning level on every turn, and its upstream takes any history. */
    private static final Settings GPT_EFFORT = new Settings(false, false, false, false, false, true, false, false, false, false);
    private static final Settings FULL = new Settings(true, true, true, true, true, true, true, true, true, true);

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    /** What the gateway does for a Messages request to a relay member, up to the lint. */
    private static Wire fromMessages(JsonNode request, Settings settings) {
        ObjectNode chat = ADAPTER.messagesToChat(request);
        return ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, request, settings, "relay-model"), settings);
    }

    private static Wire fromChat(JsonNode request, Settings settings) {
        return ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(request, null, settings, "relay-model"), settings);
    }

    private static Wire fromMessages(String request, Settings settings) {
        return fromMessages(tree(request), settings);
    }

    private static void assertSendable(Wire wire) {
        assertThat(StrictChatLint.problems(wire.body())).isEmpty();
        assertThat(wire.body().propertyNames()).isSubsetOf(ChatWireSanitizer.BODY_KEYS);
    }

    // ---------------------------------------------------------------------------------------- Claude Code

    private static final String CLAUDE_CODE = """
        {"model":"claude-sonnet-4-5-20250929","max_tokens":32000,"stream":true,"temperature":1,
         "metadata":{"user_id":"user_0123456789abcdef_account__session_00000000-0000-0000-0000-000000000000"},
         "thinking":{"type":"adaptive"},"output_config":{"effort":"medium"},
         "context_management":{"edits":[{"type":"clear_thinking_20251015"}]},
         "system":[{"type":"text","text":"You are Claude Code.","cache_control":{"type":"ephemeral"}},{"type":"text","text":"Working directory: /work"}],
         "tools":[{"name":"Bash","description":"Runs a command","input_schema":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]},
           "cache_control":{"type":"ephemeral"}},
          {"name":"Read","description":"Reads a file","input_schema":{"type":"object","properties":{"file_path":{"type":"string"}}}}],
         "tool_choice":{"type":"auto"},
         "messages":[
          {"role":"user","content":[{"type":"text","text":"<system-reminder>context</system-reminder>"},
           {"type":"text","text":"list the files","cache_control":{"type":"ephemeral"}}]},
          {"role":"assistant","content":[{"type":"thinking","thinking":"hm","signature":"sig"},{"type":"text","text":"I will list them."},
           {"type":"tool_use","id":"toolu_01AAAA","name":"Bash","input":{"command":"ls"}}]},
          {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01AAAA","content":"a.txt\\nb.txt"},
           {"type":"text","text":"<system-reminder>todo list</system-reminder>"}]},
          {"role":"assistant","content":[{"type":"text","text":"Two files."}]},
          {"role":"user","content":"read them"},
          {"role":"assistant","content":[{"type":"tool_use","id":"toolu_01BBBB","name":"Read","input":{"file_path":"a.txt"}},
           {"type":"tool_use","id":"toolu_01CCCC","name":"Read","input":{"file_path":"b.txt"}}]},
          {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01BBBB","content":[{"type":"text","text":"hello"}]},
           {"type":"tool_result","tool_use_id":"toolu_01CCCC","content":[{"type":"text","text":"world"}],"is_error":false}]}
         ]}
        """;

    @Test
    void aClaudeCodeMainLoopRequestIsSentAsAStrictChatRequest() {
        Wire wire = fromMessages(CLAUDE_CODE, EFFORT);

        assertSendable(wire);
        JsonNode body = wire.body();
        assertThat(body.propertyNames()).containsExactly("model", "messages", "max_tokens", "temperature", "tools", "tool_choice", "stream");
        assertThat(body.path("model").stringValue()).isEqualTo("relay-model");
        assertThat(body.path("max_tokens").intValue()).isEqualTo(32000);
        assertThat(body.path("stream").booleanValue()).isFalse();
        assertThat(body.path("tools")).hasSize(2);
        assertThat(body.path("tools").get(0).path("function").propertyNames()).containsExactly("name", "description", "parameters");
        assertThat(body.toString()).doesNotContain("user_id", "session_", "context_management", "signature", "cache_control");

        List<String> roles = new ArrayList<>();
        body.path("messages").forEach(message -> roles.add(message.path("role").stringValue()));
        assertThat(roles).containsExactly("system", "user", "assistant", "tool", "user", "assistant", "user", "assistant", "tool", "tool");
        assertThat(body.path("messages").get(2).path("content").stringValue()).isEqualTo("I will list them.");
        assertThat(body.path("messages").get(7).path("tool_calls")).hasSize(2);
        // The request ends with tool results, so it is a continuation turn and thinking is not asked of the relay.
        assertThat(wire.degraded()).containsExactly("thinking");
    }

    @Test
    void aClaudeCodeRequestWithTheToolSearchToolIsSentWithoutItAndWithTheToolsItDefersAsFunctions() {
        ObjectNode request = (ObjectNode) tree(CLAUDE_CODE);
        ArrayNode tools = (ArrayNode) request.path("tools");
        tools.insert(0, tree("{\"type\":\"tool_search_tool_regex_20251119\",\"name\":\"tool_search_tool_regex\"}"));
        ((ObjectNode) tools.get(2)).put("defer_loading", true);
        request.set("tool_choice", tree("{\"type\":\"tool\",\"name\":\"tool_search_tool_regex\"}"));

        Wire wire = fromMessages(request, EFFORT);

        assertSendable(wire);
        List<String> names = new ArrayList<>();
        wire.body().path("tools").forEach(tool -> names.add(tool.path("function").path("name").stringValue()));
        assertThat(names).containsExactly("Bash", "Read");
        assertThat(wire.body().toString()).doesNotContain("tool_search");
        assertThat(wire.body().has("tool_choice")).isFalse();
        assertThat(wire.degraded()).containsExactlyInAnyOrder("thinking", "tool_search_tool");
        // Without the choice of it, the choice of the client is kept.
        request.set("tool_choice", tree("{\"type\":\"auto\"}"));
        Wire auto = fromMessages(request, EFFORT);
        assertThat(auto.body().path("tool_choice").stringValue()).isEqualTo("auto");
        assertThat(auto.degraded()).containsExactlyInAnyOrder("thinking", "tool_search_tool");
    }

    @Test
    void aToolChoiceOfATypeNobodyConvertedDoesNotReachTheRelayAndTheBodyStillPassesTheLint() {
        ObjectNode request = (ObjectNode) tree(CLAUDE_CODE);
        request.set("tool_choice", tree("{\"type\":\"allowed_tools\",\"allowed_tools\":{\"mode\":\"auto\",\"tools\":[\"Bash\"]},"
            + "\"metadata\":{\"user_id\":\"user_8f3a-session-42\"}}"));

        Wire wire = fromMessages(request, EFFORT);

        assertSendable(wire);
        assertThat(wire.body().has("tool_choice")).isFalse();
        assertThat(wire.body().toString()).doesNotContain("allowed_tools", "user_8f3a");
        assertThat(wire.degraded()).contains("tool_choice");
    }

    @Test
    void aChatClientThatSpeaksTheResponsesNamesForTextIsNotAnsweredForAPlaceholder() {
        Wire wire = fromChat(tree("""
            {"model":"gpt-5","messages":[
             {"role":"user","content":[{"type":"input_text","text":"Explain X"}]},
             {"role":"assistant","content":[{"type":"output_text","text":"X is a thing"}]},
             {"role":"user","content":[{"type":"input_text","text":"and Y?"}]}]}
            """), PLAIN);

        assertSendable(wire);
        List<String> texts = new ArrayList<>();
        wire.body().path("messages").forEach(message -> texts.add(message.path("role").stringValue() + ": "
            + message.path("content").toString()));
        assertThat(texts).containsExactly(
            "user: [{\"type\":\"text\",\"text\":\"Explain X\"}]",
            "assistant: [{\"type\":\"text\",\"text\":\"X is a thing\"}]",
            "user: [{\"type\":\"text\",\"text\":\"and Y?\"}]");
        assertThat(wire.body().toString()).doesNotContain("non-text content omitted");
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void aClaudeCodeToolContinuationTurnGetsNoEffortAndAFollowingPromptGetsOne() {
        Wire continuation = fromMessages(CLAUDE_CODE, EFFORT);
        ObjectNode withPrompt = (ObjectNode) tree(CLAUDE_CODE);
        ((ArrayNode) withPrompt.path("messages")).add(tree("{\"role\":\"assistant\",\"content\":\"done\"}"));
        ((ArrayNode) withPrompt.path("messages")).add(tree("{\"role\":\"user\",\"content\":\"now fix it\"}"));

        Wire prompt = fromMessages(withPrompt, EFFORT);

        assertThat(continuation.body().has("reasoning_effort")).isFalse();
        assertThat(continuation.degraded()).containsExactly("thinking");
        assertThat(prompt.body().path("reasoning_effort").stringValue()).isEqualTo("medium");
        assertThat(prompt.degraded()).isEmpty();
        assertSendable(prompt);
    }

    private static final String GPT_TOOL_LOOP = """
        {"model":"gpt-5","reasoning_effort":"minimal","tools":[{"type":"function","function":{"name":"bash","parameters":{"type":"object"}}}],
         "messages":[{"role":"user","content":"list the files"},
          {"role":"assistant","content":null,"tool_calls":[{"id":"call_a","type":"function","function":{"name":"bash","arguments":"{\\"command\\":\\"ls\\"}"}}]},
          {"role":"tool","tool_call_id":"call_a","content":"a.txt"},
          {"role":"assistant","content":null,"tool_calls":[{"id":"call_b","type":"function","function":{"name":"bash","arguments":"{\\"command\\":\\"cat a.txt\\"}"}}]},
          {"role":"tool","tool_call_id":"call_b","content":"hello"}]}
        """;

    /**
     * An agent loop of an OpenAI model is nearly all continuation turns. The level the client asked for goes with every
     * one of them, as it does on the native path: a relay that never gets it answers at the model's default level, which
     * is more reasoning than "minimal" (and paid for by the user at the tier's price) and less than "high".
     */
    @Test
    void aGptToolLoopKeepsTheReasoningEffortTheClientAskedForOnEveryContinuationTurn() {
        Wire wire = fromChat(tree(GPT_TOOL_LOOP), GPT_EFFORT);

        assertSendable(wire);
        assertThat(wire.body().path("reasoning_effort").stringValue()).isEqualTo("minimal");
        assertThat(wire.degraded()).isEmpty();
        // The tool results are the last messages, so this is a continuation turn by every reading.
        assertThat(RequestFeatureDetector.detect(PairRegistry.Entry.CHAT, tree(GPT_TOOL_LOOP)).toolContinuation()).isTrue();
        // A Claude model behind the same relay is not asked on that turn: its next turn needs the thinking before.
        Wire claude = fromChat(tree(GPT_TOOL_LOOP), EFFORT);
        assertThat(claude.body().has("reasoning_effort")).isFalse();
        assertThat(claude.degraded()).containsExactly("reasoning_effort");
    }

    @Test
    void aClaudeCodeRequestToAMemberThatDoesNotDoEffortRecordsThatThinkingWasLeftOut() {
        Wire wire = fromMessages(CLAUDE_CODE, PLAIN);

        assertThat(wire.body().has("reasoning_effort")).isFalse();
        assertThat(wire.degraded()).containsExactly("thinking");
        assertSendable(wire);
    }

    @Test
    void aClaudeCodeRequestIsTheSameAndSendableForAMemberThatCanDoEverything() {
        Wire wire = fromMessages(CLAUDE_CODE, FULL);

        assertSendable(wire);
        assertThat(wire.body().path("stream").booleanValue()).isTrue();
        assertThat(wire.body().path("stream_options").path("include_usage").booleanValue()).isTrue();
        assertThat(wire.body().has("service_tier")).isTrue();
        assertThat(wire.body().has("max_completion_tokens")).isTrue();
    }

    // ---------------------------------------------------------------------------------------- the histories of design 7.14

    private static final String THREE_TURNS = """
        {"role":"assistant","content":[{"type":"text","text":"Noted."}]},
        {"role":"user","content":"and now?"},
        {"role":"assistant","content":[{"type":"tool_use","id":"toolu_02","name":"Bash","input":{"command":"pwd"}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_02","content":"/work"}]},
        {"role":"assistant","content":[{"type":"text","text":"We are in /work."}]},
        {"role":"user","content":"thanks"}
        """;

    private static final String ASK_TO_READ = "{\"role\":\"user\",\"content\":\"read the file\"},"
        + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Reading.\"},"
        + "{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"Read\",\"input\":{\"file_path\":\"a.png\"}}]},";

    /** The tools of a Claude Code request: a history with tool calls in a request that declares none is flattened to text. */
    private static final String TOOLS = "\"tools\":[{\"name\":\"Bash\",\"input_schema\":{\"type\":\"object\"}},{\"name\":\"Read\",\"input_schema\":{\"type\":\"object\"}}]";

    private static Wire history(String turns) {
        Wire wire = fromMessages("{\"model\":\"claude-sonnet-4.5\",\"max_tokens\":100," + TOOLS + ",\"messages\":[" + turns + "]}", PLAIN);
        assertSendable(wire);
        return wire;
    }

    private static List<String> contents(Wire wire, String role) {
        List<String> contents = new ArrayList<>();
        for (JsonNode message : wire.body().path("messages")) {
            if (role.equals(message.path("role").stringValue())) {
                contents.add(message.path("content").stringValue());
            }
        }
        return contents;
    }

    @Test
    void aToolResultThatHeldOnlyAnImageIsAPlaceholderAndTheHistoryAfterItIsSendable() {
        Wire wire = history(ASK_TO_READ
            + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01\",\"content\":"
            + "[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AAAA\"}}]}]},"
            + THREE_TURNS);

        assertThat(contents(wire, "tool")).containsExactly("[no output]", "/work");
        assertThat(wire.degraded()).containsExactly("empty_tool_result");
        assertThat(wire.body().toString()).doesNotContain("AAAA");
    }

    @Test
    void aToolResultWithoutContentIsAPlaceholderAndTheHistoryAfterItIsSendable() {
        Wire wire = history(ASK_TO_READ + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01\"}]}," + THREE_TURNS);

        assertThat(contents(wire, "tool")).containsExactly("[no output]", "/work");
        assertThat(wire.degraded()).containsExactly("empty_tool_result");
    }

    @Test
    void anEmptyCommandOutputIsAPlaceholderAndTheHistoryAfterItIsSendable() {
        Wire wire = history(ASK_TO_READ + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01\",\"content\":\"\"}]}," + THREE_TURNS);

        assertThat(contents(wire, "tool")).containsExactly("[no output]", "/work");
        assertThat(wire.degraded()).containsExactly("empty_tool_result");
    }

    @Test
    void aUserTurnThatWasOnlyAnImageIsKeptAsAPlaceholderTurnAndTheHistoryAfterItIsSendable() {
        Wire wire = history("{\"role\":\"user\",\"content\":\"look\"},{\"role\":\"assistant\",\"content\":\"Looking.\"},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AAAA\"}}]},"
            + THREE_TURNS);

        assertThat(contents(wire, "user")).containsExactly("look", "[non-text content omitted]", "and now?", "thanks");
        assertThat(wire.degraded()).containsExactly("empty_user_message");
        assertThat(wire.body().path("messages")).hasSize(9);
    }

    @Test
    void aRelayThatAnswersEveryTurnWithCall0LeavesAHistoryWithDistinctIds() {
        Wire wire = history("{\"role\":\"user\",\"content\":\"go\"},"
            + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_0\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_0\",\"content\":\"one\"}]},"
            + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_0\",\"name\":\"Bash\",\"input\":{\"command\":\"pwd\"}}]},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_0\",\"content\":\"two\"}]},"
            + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_0\",\"name\":\"Read\",\"input\":{\"file_path\":\"a\"}}]},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_0\",\"content\":\"three\"}]},"
            + "{\"role\":\"assistant\",\"content\":\"done\"},{\"role\":\"user\",\"content\":\"ok\"}");

        List<String> ids = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        for (JsonNode message : wire.body().path("messages")) {
            message.path("tool_calls").forEach(call -> ids.add(call.path("id").stringValue()));
            if ("tool".equals(message.path("role").stringValue())) {
                answers.add(message.path("tool_call_id").stringValue() + "=" + message.path("content").stringValue());
            }
        }
        assertThat(ids).containsExactly("call_0", "call_0_2", "call_0_3");
        assertThat(answers).containsExactly("call_0=one", "call_0_2=two", "call_0_3=three");
    }

    private static final String LIST_THE_FILES = "{\"role\":\"user\",\"content\":\"list the files\"},"
        + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Looking.\"},"
        + "{\"type\":\"tool_use\",\"id\":\"toolu_01AAA\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]},"
        + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01AAA\",\"content\":\"a.txt\"}]},"
        + "{\"role\":\"assistant\",\"content\":\"One file.\"},{\"role\":\"user\",\"content\":\"Write a summary\"}";

    /**
     * A client that summarizes (Claude Code's compaction) resends the whole conversation with its tools taken out. Anthropic
     * and Bedrock refuse tool calls and results in a request that defines no tools, and a relay that forwards to them
     * would answer 400 for a request Kiro serves: the history is sent as text, as Kiro does.
     */
    @Test
    void aSummaryRequestThatResendsTheHistoryWithoutToolsIsSentAsTextAndPassesTheLint() {
        Wire wire = fromMessages("{\"model\":\"claude-sonnet-4.5\",\"max_tokens\":100,\"messages\":[" + LIST_THE_FILES + "]}", CLAUDE);

        assertSendable(wire);
        assertThat(wire.body().has("tools")).isFalse();
        assertThat(wire.body().toString()).doesNotContain("tool_calls", "tool_call_id", "\"tool\"");
        assertThat(contents(wire, "assistant")).containsExactly("Looking.\n\nTool use: Bash\nInput: {\"command\":\"ls\"}", "One file.");
        assertThat(contents(wire, "user")).containsExactly("list the files", "Tool result:\na.txt", "Write a summary");
        assertThat(wire.degraded()).containsExactly("tool_history_flattened");
    }

    /**
     * An OpenCode-style summary request of a gpt request that a relay serves: the native accounts of a gpt request are
     * handed the structured history, and so is a relay that forwards to OpenAI. Making text of it would be a change the
     * native path never makes, recorded as a repair the policy has no word for.
     */
    @Test
    void theSameSummaryRequestForAGptModelKeepsItsCallsAndRecordsNothing() {
        JsonNode request = tree("{\"model\":\"gpt-5\",\"messages\":[{\"role\":\"user\",\"content\":\"list the files\"},"
            + "{\"role\":\"assistant\",\"content\":\"Looking.\",\"tool_calls\":[{\"id\":\"call_a\",\"type\":\"function\","
            + "\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"call_a\",\"content\":\"a.txt\"},"
            + "{\"role\":\"user\",\"content\":\"Write a summary\"}]}");

        Wire gpt = fromChat(request, GPT_EFFORT);
        Wire claude = fromChat(request, CLAUDE);

        assertSendable(gpt);
        assertThat(gpt.body().has("tools")).isFalse();
        assertThat(gpt.body().path("messages").get(1).path("tool_calls")).hasSize(1);
        assertThat(contents(gpt, "tool")).containsExactly("a.txt");
        assertThat(gpt.degraded()).isEmpty();
        assertSendable(claude);
        assertThat(claude.body().toString()).doesNotContain("tool_calls", "tool_call_id");
        assertThat(claude.degraded()).containsExactly("tool_history_flattened");
    }

    /**
     * The results of parallel calls that a client wrote in another order than the calls are read against their own calls
     * once the history is text, as they are when it is not: the text of a result does not say which call it answers.
     */
    @Test
    void aSummaryRequestWhoseParallelResultsCameInAnotherOrderReadsEachResultAgainstItsOwnCall() {
        JsonNode request = tree("{\"model\":\"claude-sonnet-4.5\",\"messages\":[{\"role\":\"user\",\"content\":\"look around\"},"
            + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
            + "{\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":[\\\"ls\\\"]}\"}},"
            + "{\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":[\\\"pwd\\\"]}\"}}]},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"call_b\",\"content\":\"/w\"},"
            + "{\"role\":\"tool\",\"tool_call_id\":\"call_a\",\"content\":\"a.txt\"},"
            + "{\"role\":\"user\",\"content\":\"summarize\"}]}");

        Wire wire = fromChat(request, CLAUDE);

        assertSendable(wire);
        assertThat(contents(wire, "assistant")).containsExactly(
            "Tool use: shell\nInput: {\"command\":[\"ls\"]}\n\nTool use: shell\nInput: {\"command\":[\"pwd\"]}");
        assertThat(contents(wire, "user")).containsExactly("look around", "Tool result:\na.txt\n\nTool result:\n/w\n\nsummarize");
        assertThat(wire.degraded()).containsExactly("tool_history_flattened");
    }

    @Test
    void aRequestWhoseOnlyToolIsTheToolSearchToolHasNoToolsLeftAndItsHistoryIsText() {
        Wire wire = fromMessages("{\"model\":\"claude-sonnet-4.5\",\"max_tokens\":100,"
            + "\"tools\":[{\"type\":\"tool_search_tool_regex_20251119\",\"name\":\"tool_search_tool_regex\"}],"
            + "\"messages\":[" + LIST_THE_FILES + "]}", CLAUDE);

        assertSendable(wire);
        assertThat(wire.body().has("tools")).isFalse();
        assertThat(wire.degraded()).containsExactly("tool_search_tool", "tool_history_flattened");
    }

    @Test
    void theSameHistoryWithItsToolsKeepsItsCallsAsCalls() {
        Wire wire = history(LIST_THE_FILES);

        assertThat(wire.body().path("tools")).hasSize(2);
        assertThat(contents(wire, "tool")).containsExactly("a.txt");
        assertThat(wire.degraded()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- OpenCode, the Chat entry

    @Test
    void anOpenCodeChatRequestIsSentAsAStrictChatRequestAndItsFieldsDoNotLeak() {
        JsonNode request = tree("""
            {"model":"claude-sonnet-4.5","stream":true,"stream_options":{"include_usage":true},"max_tokens":8192,"temperature":0,
             "user":"opencode-session-7","metadata":{"session":"s"},"store":false,"reasoning_effort":"high",
             "messages":[
              {"role":"system","content":"You are opencode.","cache_control":{"type":"ephemeral"}},
              {"role":"user","content":[{"type":"text","text":"fix the bug","cache_control":{"type":"ephemeral"}}]},
              {"role":"assistant","content":"","tool_calls":[{"id":"call_a1","type":"function","function":{"name":"read","arguments":"{\\"path\\":\\"a.go\\"}"}}]},
              {"role":"tool","tool_call_id":"call_a1","content":"package main"},
              {"role":"assistant","content":"Found it.","reasoning_content":"thinking...","tool_calls":[
               {"id":"call_a2","type":"function","function":{"name":"edit","arguments":"{\\"path\\":\\"a.go\\"}"}},
               {"id":"call_a3","type":"function","function":{"name":"bash","arguments":"{\\"command\\":\\"go build\\"}"}}]},
              {"role":"tool","tool_call_id":"call_a2","content":"edited"},
              {"role":"tool","tool_call_id":"call_a3","content":""},
              {"role":"assistant","content":"The build output is empty."},
              {"role":"user","content":"does it build?"}],
             "tools":[{"type":"function","function":{"name":"read","description":"Reads","parameters":{"type":"object"}}},
              {"type":"function","function":{"name":"edit","description":"Edits","parameters":{"type":"object"}}},
              {"type":"function","function":{"name":"bash","description":"Runs","parameters":{"type":"object"}}}],
             "tool_choice":"auto","parallel_tool_calls":true}
            """);

        Wire wire = fromChat(request, EFFORT);

        assertSendable(wire);
        assertThat(wire.body().toString()).doesNotContain("opencode-session-7", "thinking...", "cache_control", "store");
        assertThat(wire.body().path("reasoning_effort").stringValue()).isEqualTo("high");
        assertThat(wire.body().path("parallel_tool_calls").booleanValue()).isTrue();
        assertThat(wire.body().path("messages").get(2).path("content").isNull()).isTrue();
        assertThat(wire.body().path("messages")).hasSize(9);
        assertThat(wire.degraded()).containsExactly("cache_control", "empty_tool_result");
    }

    @Test
    void anOpenCodeRequestToAMemberThatPassesCacheControlThroughKeepsIt() {
        JsonNode request = tree("""
            {"model":"claude-sonnet-4.5","messages":[
              {"role":"system","content":"You are opencode.","cache_control":{"type":"ephemeral"}},
              {"role":"user","content":[{"type":"text","text":"hi","cache_control":{"type":"ephemeral"}}]}]}
            """);

        Wire wire = fromChat(request, new Settings(false, false, false, true, false, false, false, false, false, false));

        assertSendable(wire);
        assertThat(wire.body().path("messages").get(0).has("cache_control")).isTrue();
        assertThat(wire.body().path("messages").get(1).path("content").get(0).has("cache_control")).isTrue();
        assertThat(wire.degraded()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- random requests

    private static final String[] TEXTS = {
        "hello", "", " ", "\n", "你好，世界", "😀 emoji", "tab\there", "line1\nline2", "x".repeat(300), "{\"json\":true}", "<system-reminder>r</system-reminder>"
    };
    private static final String[] IDS = {"call_0", "call_0", "toolu_01", "toolu_02", "a.b", "a:b", "a_b", "", "é😀", "x".repeat(80), "T-1"};
    private static final String[] NAMES = {"Bash", "Read", "mcp__srv__tool", "f"};
    private static final String IMAGE = "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"QUJD\"}}";

    private static String pick(Random random, String[] values) {
        return values[random.nextInt(values.length)];
    }

    private static ObjectNode text(Random random) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "text").put("text", pick(random, TEXTS));
        return block;
    }

    private static ObjectNode randomAnthropicRequest(Random random, int serial) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", "claude-sonnet-4.5");
        request.put("max_tokens", 1 + random.nextInt(4000));
        if (random.nextBoolean()) {
            request.put("stream", true);
        }
        request.putObject("metadata").put("user_id", "SECRET-USER-" + serial);
        if (random.nextInt(3) == 0) {
            request.putObject("thinking").put("type", random.nextBoolean() ? "adaptive" : "enabled").put("budget_tokens", random.nextInt(40_000));
            request.putObject("output_config").put("effort", random.nextBoolean() ? "high" : "max");
        }
        int stops = random.nextInt(5);
        if (stops > 0) {
            ArrayNode stop = request.putArray("stop_sequences");
            for (int i = 0; i < stops; i++) {
                stop.add("stop" + i);
            }
        }
        if (random.nextInt(4) == 0) {
            request.put("system", pick(random, TEXTS));
        } else if (random.nextInt(4) == 0) {
            ArrayNode system = request.putArray("system");
            system.add(text(random)).add(text(random));
        }
        if (random.nextInt(3) == 0) {
            ArrayNode tools = request.putArray("tools");
            for (int i = 1 + random.nextInt(3); i > 0; i--) {
                ObjectNode tool = tools.addObject();
                tool.put("name", pick(random, NAMES));
                tool.putObject("input_schema").put("type", "object");
            }
            request.putObject("tool_choice").put("type", random.nextBoolean() ? "auto" : "any");
        }
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "user").put("content", "start");
        String role = random.nextInt(8) == 0 ? "user" : "assistant";
        for (int turn = random.nextInt(14); turn >= 0; turn--) {
            ObjectNode message = messages.addObject();
            message.put("role", role);
            if (random.nextInt(4) == 0) {
                message.put("content", pick(random, TEXTS));
            } else {
                ArrayNode content = message.putArray("content");
                for (int i = random.nextInt(5); i > 0; i--) {
                    content.add("user".equals(role) ? randomUserBlock(random) : randomAssistantBlock(random));
                }
            }
            role = random.nextInt(10) == 0 ? role : "user".equals(role) ? "assistant" : "user";
        }
        return request;
    }

    private static JsonNode randomUserBlock(Random random) {
        switch (random.nextInt(5)) {
            case 0:
                return tree(IMAGE);
            case 1, 2: {
                ObjectNode block = JSON.createObjectNode();
                block.put("type", "tool_result").put("tool_use_id", pick(random, IDS));
                switch (random.nextInt(5)) {
                    case 0 -> { }
                    case 1 -> block.put("content", pick(random, TEXTS));
                    case 2 -> block.putArray("content").add(tree(IMAGE));
                    case 3 -> block.putArray("content").add(text(random)).add(tree(IMAGE)).add(text(random));
                    default -> block.put("content", "");
                }
                if (random.nextInt(5) == 0) {
                    block.put("is_error", true);
                }
                return block;
            }
            default:
                return text(random);
        }
    }

    private static JsonNode randomAssistantBlock(Random random) {
        switch (random.nextInt(4)) {
            case 0: {
                ObjectNode block = JSON.createObjectNode();
                block.put("type", "thinking").put("thinking", pick(random, TEXTS)).put("signature", "sig");
                return block;
            }
            case 1, 2: {
                ObjectNode block = JSON.createObjectNode();
                block.put("type", "tool_use").put("id", pick(random, IDS)).put("name", pick(random, NAMES));
                if (random.nextInt(4) != 0) {
                    block.putObject("input").put("n", random.nextInt(100)).put("s", pick(random, TEXTS));
                }
                return block;
            }
            default:
                return text(random);
        }
    }

    private static void collectTexts(JsonNode content, List<String> into) {
        if (content.isString()) {
            into.add(content.stringValue());
        } else if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").stringValue(""))) {
                    into.add(block.path("text").stringValue(""));
                } else if ("tool_result".equals(block.path("type").stringValue(""))) {
                    collectTexts(block.path("content"), into);
                }
            }
        }
    }

    @Test
    void anyAnthropicRequestIsSendableLosesNoTextAndLeavesTheInputAlone() {
        Random random = new Random(20261024);
        Settings[] settingsToTry = {PLAIN, EFFORT, GPT_EFFORT, FULL};
        for (int serial = 0; serial < 600; serial++) {
            ObjectNode request = randomAnthropicRequest(random, serial);
            JsonNode snapshot = request.deepCopy();
            Settings settings = settingsToTry[serial % settingsToTry.length];
            ObjectNode chat = ADAPTER.messagesToChat(request);
            JsonNode chatSnapshot = chat.deepCopy();

            Wire wire = ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, request, settings, "relay-model"), settings);

            String where = "request " + serial + ": " + snapshot;
            assertThat(StrictChatLint.problems(wire.body())).as(where).isEmpty();
            assertThat(request).as(where).isEqualTo(snapshot);
            assertThat(chat).as(where).isEqualTo(chatSnapshot);
            assertThat(wire.body().toString()).as(where).doesNotContain("SECRET-USER", "top_k", "metadata", "signature");
            assertThat(ChatCanonicalizer.canonicalize(wire, settings).body()).as(where).isEqualTo(wire.body());

            String sent = String.join("\u0000", sentTexts(wire.body()));
            List<String> wanted = new ArrayList<>();
            for (JsonNode message : request.path("messages")) {
                if (message.path("content").isArray()) {
                    for (JsonNode block : message.path("content")) {
                        if (!"thinking".equals(block.path("type").stringValue(""))) {
                            collectTexts(JSON.createArrayNode().add(block), wanted);
                        }
                    }
                } else {
                    collectTexts(message.path("content"), wanted);
                }
            }
            for (String text : wanted) {
                if (!text.isBlank()) {
                    assertThat(sent).as(where + " lost the text " + text).contains(text);
                }
            }
        }
    }

    private static List<String> sentTexts(JsonNode body) {
        List<String> texts = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            JsonNode content = message.path("content");
            if (content.isString()) {
                texts.add(content.stringValue());
            } else if (content.isArray()) {
                content.forEach(part -> texts.add(part.path("text").stringValue("")));
            }
            for (JsonNode call : message.path("tool_calls")) {
                texts.add(call.path("function").path("arguments").stringValue(""));
            }
        }
        return texts;
    }

    // ---------------------------------------------------------------------------------------- random Chat requests

    private static final String[] EXTRA_KEYS = {"name", "reasoning_content", "refusal", "audio", "annotations", "x-session", "function_call", "index"};

    private static ObjectNode randomChatRequest(Random random, int serial) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", "claude-sonnet-4.5");
        request.put("user", "SECRET-USER-" + serial);
        request.put("x-trace-" + serial, "SECRET-TRACE-" + serial);
        if (random.nextBoolean()) {
            request.put("max_tokens", 1 + random.nextInt(4000));
        }
        if (random.nextInt(3) == 0) {
            request.put("reasoning_effort", "high");
        }
        if (random.nextInt(3) == 0) {
            ArrayNode tools = request.putArray("tools");
            tools.addObject().put("type", "function").putObject("function").put("name", pick(random, NAMES)).put("x-extra", "no");
            if (random.nextBoolean()) {
                request.put("tool_choice", "auto");
            }
        }
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "user").put("content", "start");
        String[] roles = {"system", "developer", "user", "assistant", "tool"};
        for (int turn = random.nextInt(14); turn >= 0; turn--) {
            ObjectNode message = messages.addObject();
            String role = roles[random.nextInt(roles.length)];
            message.put("role", role);
            switch (random.nextInt(5)) {
                case 0 -> message.put("content", pick(random, TEXTS));
                case 1 -> message.putNull("content");
                case 2 -> { }
                default -> {
                    ArrayNode parts = message.putArray("content");
                    for (int i = random.nextInt(4); i > 0; i--) {
                        parts.add(randomPart(random));
                    }
                }
            }
            if (random.nextInt(3) == 0) {
                message.put(pick(random, EXTRA_KEYS), "SECRET-EXTRA-" + serial);
            }
            if ("tool".equals(role) && random.nextInt(5) != 0) {
                message.put("tool_call_id", pick(random, IDS));
            }
            if ("assistant".equals(role) && random.nextBoolean()) {
                ArrayNode calls = message.putArray("tool_calls");
                for (int i = random.nextInt(3); i > 0; i--) {
                    ObjectNode call = calls.addObject();
                    call.put("id", pick(random, IDS)).put("type", "function").put("index", i);
                    ObjectNode function = call.putObject("function");
                    function.put("name", pick(random, NAMES));
                    if (random.nextInt(3) != 0) {
                        function.put("arguments", random.nextBoolean() ? "{}" : "");
                    }
                }
            }
        }
        return request;
    }

    private static JsonNode randomPart(Random random) {
        ObjectNode part = JSON.createObjectNode();
        switch (random.nextInt(7)) {
            case 0 -> part.put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64,QUJD").put("detail", "high").put("x", 1);
            case 1 -> part.put("type", "input_audio").putObject("input_audio").put("data", "QUJD");
            case 2 -> part.put("type", "file").putObject("file").put("file_id", "file-1");
            case 3 -> part.put("type", "refusal").put("refusal", "SECRET-REFUSAL");
            default -> {
                part.put("type", "text").put("text", pick(random, TEXTS));
                if (random.nextBoolean()) {
                    part.putObject("cache_control").put("type", "ephemeral");
                }
                if (random.nextInt(4) == 0) {
                    part.put("annotations", "SECRET-ANNOTATION");
                }
            }
        }
        return part;
    }

    @Test
    void anyChatRequestIsSendableWithNothingOutsideTheWhitelistAndTheInputUntouched() {
        Random random = new Random(7);
        Settings[] settingsToTry = {PLAIN, EFFORT, GPT_EFFORT, FULL};
        for (int serial = 0; serial < 600; serial++) {
            ObjectNode request = randomChatRequest(random, serial);
            JsonNode snapshot = request.deepCopy();

            Settings settings = settingsToTry[serial % settingsToTry.length];

            Wire wire = fromChat(request, settings);

            String where = "request " + serial + ": " + snapshot;
            assertThat(StrictChatLint.problems(wire.body())).as(where).isEmpty();
            assertThat(request).as(where).isEqualTo(snapshot);
            assertThat(wire.body().toString()).as(where).doesNotContain("SECRET-");
            assertThat(wire.body().propertyNames()).as(where).isSubsetOf(ChatWireSanitizer.BODY_KEYS);
            for (JsonNode message : wire.body().path("messages")) {
                assertThat(message.propertyNames()).as(where).isSubsetOf(ChatWireSanitizer.MESSAGE_KEYS);
                for (JsonNode part : message.path("content")) {
                    assertThat(part.propertyNames()).as(where).isSubsetOf(ChatWireSanitizer.PART_KEYS);
                }
            }
            assertThat(ChatCanonicalizer.canonicalize(wire, settings).body()).as(where).isEqualTo(wire.body());
        }
    }
}
