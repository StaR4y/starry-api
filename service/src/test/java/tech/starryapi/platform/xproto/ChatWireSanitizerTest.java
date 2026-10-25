package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Design 7.6, the whitelist half: what a relay is sent is built from a list of what is allowed at the body, message
 * and part level, never copied. Each level is tested with the keys clients and conversions really put there, then the
 * placeholders for what a member cannot take, the token limit, the tools, and the reasoning request with every source
 * and every reason it is not sent.
 */
class ChatWireSanitizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The settings of a member with everything off; the names turn on what a test is about. */
    private static Settings settings(String... on) {
        Set<String> names = new HashSet<>(Arrays.asList(on));
        return new Settings(
            names.contains("vision"), names.contains("jsonSchema"), names.contains("priority"), names.contains("cache"),
            names.contains("stream"), names.contains("effort"), names.contains("fast"), names.contains("maxCompletion"),
            names.contains("claude"), names.contains("claude")
        );
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static Wire sanitize(String chatBody, String... on) {
        return ChatWireSanitizer.sanitize(tree(chatBody), null, settings(on), null);
    }

    private static Wire withEntry(String chatBody, String entryBody, String... on) {
        return ChatWireSanitizer.sanitize(tree(chatBody), tree(entryBody), settings(on), null);
    }

    private static final String HELLO = "[{\"role\":\"user\",\"content\":\"hello\"}]";

    private static String chat(String extra) {
        return "{\"messages\":" + HELLO + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    // ---------------------------------------------------------------------------------------- the body

    @Test
    void onlyTheWhitelistedKeysOfTheBodyAreSentAndTheOthersNever() {
        Wire wire = sanitize("""
            {"model":"claude-sonnet-4.5","messages":[{"role":"user","content":"hello"}],"max_tokens":512,
             "temperature":0.5,"top_p":0.9,"stop":["END"],"top_k":40,"metadata":{"user_id":"user_secret_1"},
             "thinking":{"type":"enabled","budget_tokens":2000},"user":"u-1","previous_response_id":"resp_1",
             "store":true,"include":["x"],"n":2,"logprobs":true,"top_logprobs":3,"fast":true,"session_id":"s-1",
             "prompt_cache_key":"k","service_tier":"priority","stream":true,"stream_options":{"include_usage":false},
             "logit_bias":{"1":2},"modalities":["text"],"prediction":{"type":"content"},"x-session":"s-2",
             "frequency_penalty":0.1,"presence_penalty":0.2,"seed":7,"parallel_tool_calls":false,
             "tool_choice":"auto","functions":[{"name":"f"}],"function_call":"auto"}
            """);

        assertThat(wire.body().propertyNames()).isSubsetOf(ChatWireSanitizer.BODY_KEYS);
        assertThat(wire.body().propertyNames()).containsExactly(
            "messages", "max_tokens", "temperature", "top_p", "stop", "frequency_penalty", "presence_penalty", "seed",
            "stream");
        assertThat(wire.body().toString()).doesNotContain("user_secret_1", "u-1", "resp_1", "s-1", "s-2", "claude-sonnet-4.5");
        assertThat(wire.body().path("stream").booleanValue()).isFalse();
    }

    @Test
    void theModelIsTheWireNameWhenOneIsGivenAndNeverTheClientsModel() {
        JsonNode chat = tree(chat("\"model\":\"claude-sonnet-4.5\""));

        assertThat(ChatWireSanitizer.sanitize(chat, null, settings(), null).body().has("model")).isFalse();
        assertThat(ChatWireSanitizer.sanitize(chat, null, settings(), "  ").body().has("model")).isFalse();
        assertThat(ChatWireSanitizer.sanitize(chat, null, settings(), "Vendor/Claude-Sonnet-4.5").body().path("model").stringValue())
            .isEqualTo("Vendor/Claude-Sonnet-4.5");
        assertThat(ChatWireSanitizer.sanitize(chat, null, settings(), "w").body().propertyNames().iterator().next()).isEqualTo("model");
    }

    @Test
    void theKeysComeInAFixedOrderWhateverOrderTheInputHas() {
        Wire wire = sanitize("""
            {"stream":true,"seed":1,"response_format":{"type":"json_object"},"presence_penalty":0.1,
             "tools":[{"type":"function","function":{"name":"f"}}],"tool_choice":"auto","stop":"x","top_p":1,
             "temperature":0,"max_tokens":9,"messages":[{"role":"user","content":"hi"}]}
            """, "stream");

        assertThat(wire.body().propertyNames()).containsExactly(
            "messages", "max_tokens", "temperature", "top_p", "stop", "tools", "tool_choice", "presence_penalty", "seed",
            "response_format", "stream", "stream_options");
    }

    @Test
    void theInputIsNotChanged() {
        String json = """
            {"model":"m","messages":[{"role":"developer","name":"n","content":[{"type":"image_url","image_url":{"url":"u"}},
             {"type":"text","text":"t","cache_control":{"type":"ephemeral"}}]},
             {"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"{}"}}]},
             {"role":"tool","tool_call_id":"a","content":"r"}],
             "max_tokens":5,"stop_sequences":["a"],"thinking":{"type":"enabled","budget_tokens":9000},
             "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],"metadata":{"a":1}}
            """;
        JsonNode input = tree(json);

        ChatWireSanitizer.sanitize(input, input, settings("vision", "effort", "cache", "stream", "maxCompletion"), "w");

        assertThat(input).isEqualTo(tree(json));
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> ChatWireSanitizer.sanitize(tree("[]"), null, settings(), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatWireSanitizer.sanitize(null, null, settings(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sanitizingTheResultAgainChangesNothing() {
        String json = """
            {"messages":[{"role":"developer","content":"s"},{"role":"user","content":[{"type":"image_url","image_url":{"url":"u"}},
             {"type":"text","text":"t","cache_control":{"type":"ephemeral"}}]}],"max_tokens":5,"stop_sequences":["a"],
             "reasoning_effort":"high","response_format":{"type":"json_schema","json_schema":{"name":"n"}},
             "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],"tool_choice":"auto","user":"u"}
            """;
        Settings settings = settings("effort", "stream", "maxCompletion", "fast", "priority");

        Wire once = ChatWireSanitizer.sanitize(tree(json), null, settings, "w");
        Wire twice = ChatWireSanitizer.sanitize(once.body(), null, settings, "w");

        assertThat(twice.body()).isEqualTo(once.body());
    }

    // ---------------------------------------------------------------------------------------- the token limit

    @ParameterizedTest
    @CsvSource({
        "gpt-5.5,true", "GPT-5,true", "gpt-6-mini,true", "o1-preview,true", "o3,true", "o4-mini,true", " gpt-5.1 ,true",
        "gpt-4o,false", "gpt-4.1,false", "claude-sonnet-4.5,false", "deepseek-chat,false", "o2,false", "omni,false"
    })
    void theModelsThatTakeMaxCompletionTokensAreTheOnesOpenAiUpstreamClientNames(String model, boolean expected) {
        assertThat(ChatWireSanitizer.usesMaxCompletionTokens(model)).isEqualTo(expected);
    }

    @Test
    void theSanitizersRuleIsTheOneOfOpenAiUpstreamClientForEveryModelIdItCouldBeAsked() throws Exception {
        // The original is private and that client is left as it is, so its method is called as it is, on a bare instance.
        Method original = OpenAiUpstreamClient.class.getDeclaredMethod("usesMaxCompletionTokens", String.class);
        original.setAccessible(true);
        OpenAiUpstreamClient client = Mockito.mock(OpenAiUpstreamClient.class, Mockito.CALLS_REAL_METHODS);
        String[] models = {
            "gpt-5", "gpt-5.5", "GPT-5-Codex", "gpt-6", "gpt-6-pro", "o1", "o1-mini", "o3", "o3-mini", "o4-mini", "O4", "gpt-4o",
            "gpt-4.1", "gpt-4", "gpt-3.5-turbo", "claude-sonnet-4.5", "claude-opus-4.5", "gemini-2.5-pro", "deepseek-chat", "glm-4.6",
            "openai/gpt-5", "starry/gpt-5", "omni-moderation", "o", "o2", "", "  ", " gpt-5 "
        };

        for (String model : models) {
            assertThat(ChatWireSanitizer.usesMaxCompletionTokens(model)).as(model).isEqualTo(original.invoke(client, model));
        }
        assertThat(ChatWireSanitizer.usesMaxCompletionTokens(null)).isEqualTo(original.invoke(client, (Object) null));
    }

    @Test
    void noModelTakesOnlyMaxTokens() {
        assertThat(ChatWireSanitizer.usesMaxCompletionTokens(null)).isFalse();
        assertThat(ChatWireSanitizer.usesMaxCompletionTokens(" ")).isFalse();
    }

    @Test
    void theLimitIsNamedForTheModelAndTakenFromTheFirstOfTheThreeNamesThatHasOne() {
        assertThat(sanitize(chat("\"max_tokens\":100")).body().get("max_tokens").intValue()).isEqualTo(100);
        assertThat(sanitize(chat("\"max_tokens\":100"), "maxCompletion").body().get("max_completion_tokens").intValue()).isEqualTo(100);
        assertThat(sanitize(chat("\"max_tokens\":100"), "maxCompletion").body().has("max_tokens")).isFalse();
        assertThat(sanitize(chat("\"max_completion_tokens\":7,\"max_tokens\":100")).body().get("max_tokens").intValue()).isEqualTo(7);
        assertThat(sanitize(chat("\"max_output_tokens\":55")).body().get("max_tokens").intValue()).isEqualTo(55);
        assertThat(sanitize(chat("\"max_tokens\":null,\"max_output_tokens\":55")).body().get("max_tokens").intValue()).isEqualTo(55);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "\"100\"", "1.5", "true", "null", "[100]"})
    void aLimitThatIsNotAPositiveIntegerIsLeftOut(String limit) {
        Wire wire = sanitize(chat("\"max_tokens\":" + limit));

        assertThat(wire.body().has("max_tokens")).isFalse();
        assertThat(wire.body().has("max_completion_tokens")).isFalse();
    }

    @Test
    void numbersAreSentOnlyAsNumbers() {
        Wire wire = sanitize(chat("\"temperature\":\"hot\",\"top_p\":0.5,\"frequency_penalty\":null,\"presence_penalty\":-1,\"seed\":1.5"));

        assertThat(wire.body().has("temperature")).isFalse();
        assertThat(wire.body().path("top_p").doubleValue()).isEqualTo(0.5);
        assertThat(wire.body().has("frequency_penalty")).isFalse();
        assertThat(wire.body().path("presence_penalty").intValue()).isEqualTo(-1);
        assertThat(wire.body().has("seed")).isFalse();
    }

    // ---------------------------------------------------------------------------------------- stop

    @Test
    void stopComesFromStopOrFromAnthropicsStopSequencesAndIsFreeOfEmptyOnes() {
        assertThat(sanitize(chat("\"stop\":\"END\"")).body().path("stop").stringValue()).isEqualTo("END");
        assertThat(sanitize(chat("\"stop_sequences\":[\"a\",\"\",\"b\"]")).body().path("stop")).isEqualTo(tree("[\"a\",\"b\"]"));
        assertThat(sanitize(chat("\"stop\":[\"x\"],\"stop_sequences\":[\"a\"]")).body().path("stop")).isEqualTo(tree("[\"x\"]"));
        assertThat(sanitize(chat("\"stop\":null,\"stop_sequences\":[\"a\"]")).body().path("stop")).isEqualTo(tree("[\"a\"]"));
        assertThat(sanitize(chat("\"stop_sequences\":[]")).body().has("stop")).isFalse();
        assertThat(sanitize(chat("\"stop\":\"\"")).body().has("stop")).isFalse();
        assertThat(sanitize(chat("\"stop\":[1,{}]")).body().has("stop")).isFalse();
    }

    @Test
    void moreThanFourStopSequencesAreNotCutHereButLeftForTheLintToRefuse() {
        Wire wire = sanitize(chat("\"stop\":[\"a\",\"b\",\"c\",\"d\",\"e\"]"));

        assertThat(wire.body().path("stop")).hasSize(5);
        assertThat(StrictChatLint.passes(wire.body())).isFalse();
    }

    // ---------------------------------------------------------------------------------------- tools

    @Test
    void aToolIsRebuiltWithTheKeysOfAFunctionOnly() {
        Wire wire = sanitize(chat("""
            "tools":[{"type":"function","cache_control":{"type":"ephemeral"},"x":1,
             "function":{"name":"read","description":"Reads","parameters":{"type":"object","properties":{}},"strict":true,
             "extra":"no"}},{"function":{"name":"bare"}}]"""));

        assertThat(wire.body().path("tools")).isEqualTo(tree("""
            [{"type":"function","function":{"name":"read","description":"Reads","parameters":{"type":"object","properties":{}},"strict":true}},
             {"type":"function","function":{"name":"bare"}}]"""));
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void aToolOfAnotherTypeIsKeptAsItIsSoThatTheLintRefusesTheBody() {
        Wire wire = sanitize(chat("\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}]"));

        assertThat(wire.body().path("tools").get(0).path("type").stringValue()).isEqualTo("web_search_20250305");
        assertThat(StrictChatLint.passes(wire.body())).isFalse();
    }

    @Test
    void toolChoiceAndParallelToolCallsGoOnlyWithTools() {
        String tools = "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}]";

        Wire without = sanitize(chat("\"tool_choice\":\"none\",\"parallel_tool_calls\":false"));
        Wire emptyTools = sanitize(chat("\"tools\":[],\"tool_choice\":\"auto\",\"parallel_tool_calls\":true"));
        Wire with = sanitize(chat(tools + ",\"tool_choice\":\"required\",\"parallel_tool_calls\":false"));

        assertThat(without.body().has("tool_choice")).isFalse();
        assertThat(without.body().has("parallel_tool_calls")).isFalse();
        assertThat(emptyTools.body().has("tools")).isFalse();
        assertThat(emptyTools.body().has("tool_choice")).isFalse();
        assertThat(with.body().path("tool_choice").stringValue()).isEqualTo("required");
        assertThat(with.body().path("parallel_tool_calls").booleanValue()).isFalse();
    }

    @Test
    void aNamedToolChoiceKeepsTheFunctionNameAndNothingElse() {
        Wire wire = sanitize(chat(
            "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}],"
                + "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"f\",\"x\":1},\"y\":2}"));

        assertThat(wire.body().path("tool_choice")).isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"f\"}}"));
    }

    @Test
    void toolChoiceIsOneOfTheStringsOfARelayOrTheFunctionOfATheToolThatIsSentAndNothingElse() {
        String tools = "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"f\"}}],";

        for (String kept : List.of("auto", "none", "required")) {
            Wire wire = sanitize(chat(tools + "\"tool_choice\":\"" + kept + "\""));
            assertThat(wire.body().path("tool_choice").stringValue()).isEqualTo(kept);
            assertThat(wire.degraded()).isEmpty();
            assertThat(StrictChatLint.passes(wire.body())).isTrue();
        }
        // The shapes of other APIs and of a client's own: nothing of them reaches the relay, and the request says so.
        for (String left : List.of(
            "{\"type\":\"allowed_tools\",\"metadata\":{\"user_id\":\"user_8f3a-session-42\"},\"allowed_tools\":{\"mode\":\"auto\",\"tools\":[]}}",
            "{\"type\":\"any\",\"disable_parallel_tool_use\":true}",
            "{\"type\":\"custom\",\"name\":\"f\"}",
            "\"any\"", "\"AUTO\"", "\"\"", "7", "[\"f\"]",
            // A function that is not among the tools.
            "{\"type\":\"function\",\"function\":{\"name\":\"elsewhere\"}}",
            "{\"type\":\"function\",\"function\":{}}")) {
            Wire wire = sanitize(chat(tools + "\"tool_choice\":" + left));
            assertThat(wire.body().has("tool_choice")).as(left).isFalse();
            assertThat(wire.body().toString()).as(left).doesNotContain("user_8f3a", "allowed_tools", "disable_parallel");
            assertThat(wire.degraded()).as(left).containsExactly("tool_choice");
            assertThat(StrictChatLint.passes(wire.body())).as(left).isTrue();
        }
        // None given is nothing to record.
        assertThat(sanitize(chat(tools + "\"tool_choice\":null")).degraded()).isEmpty();
    }

    @Test
    void aToolSearchToolOfAMessagesRequestIsTakenOutAndTheToolsItDefersAreSentAsFunctions() {
        String entry = """
            {"model":"claude-sonnet-4-5","max_tokens":10,"messages":[{"role":"user","content":"hi"}],
             "tools":[{"type":"tool_search_tool_regex_20251119","name":"tool_search_tool_regex"},
              {"name":"Bash","description":"Runs","input_schema":{"type":"object","properties":{}},"defer_loading":true},
              {"type":"tool_search_tool_bm25_20251119","name":"tool_search_tool_bm25"},
              {"name":"Read","description":"Reads","input_schema":{"type":"object","properties":{}}}],
             "tool_choice":{"type":"auto"}}
            """;
        String chat = """
            {"messages":[{"role":"user","content":"hi"}],"max_tokens":10,
             "tools":[{"type":"function","function":{"name":"tool_search_tool_regex","description":"","parameters":{"type":"object","properties":{}}}},
              {"type":"function","function":{"name":"Bash","description":"Runs","parameters":{"type":"object","properties":{}}}},
              {"type":"function","function":{"name":"tool_search_tool_bm25","description":"","parameters":{"type":"object","properties":{}}}},
              {"type":"function","function":{"name":"Read","description":"Reads","parameters":{"type":"object","properties":{}}}}],
             "tool_choice":"auto"}
            """;

        Wire wire = withEntry(chat, entry);

        assertThat(wire.body().path("tools")).hasSize(2);
        assertThat(wire.body().path("tools").get(0).path("function").path("name").stringValue()).isEqualTo("Bash");
        assertThat(wire.body().path("tools").get(1).path("function").path("name").stringValue()).isEqualTo("Read");
        assertThat(wire.body().toString()).doesNotContain("tool_search");
        assertThat(wire.body().path("tool_choice").stringValue()).isEqualTo("auto");
        assertThat(wire.degraded()).containsExactly("tool_search_tool");
        assertThat(StrictChatLint.passes(wire.body())).isTrue();

        // A choice of the very tool goes with it, and is not recorded a second time as a choice that was left out.
        Wire named = withEntry(chat.replace("\"tool_choice\":\"auto\"",
            "\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"tool_search_tool_regex\"}}"), entry);
        assertThat(named.body().has("tool_choice")).isFalse();
        assertThat(named.degraded()).containsExactly("tool_search_tool");

        // Only the tool search: nothing is left to offer, and nothing to choose among.
        Wire only = withEntry("""
            {"messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"tool_search_tool_regex"}}],
             "tool_choice":"required","parallel_tool_calls":false}""", entry);
        assertThat(only.body().has("tools")).isFalse();
        assertThat(only.body().has("tool_choice")).isFalse();
        assertThat(only.body().has("parallel_tool_calls")).isFalse();
        assertThat(only.degraded()).containsExactly("tool_search_tool");
    }

    @Test
    void aFunctionWithTheNameOfAToolSearchToolIsKeptWhenTheRequestHasNoToolSearch() {
        Wire wire = withEntry(chat("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"tool_search_tool_regex\"}}]"),
            "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"tools\":[{\"name\":\"tool_search_tool_regex\",\"input_schema\":{}}]}");

        assertThat(wire.body().path("tools")).hasSize(1);
        assertThat(wire.degraded()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- messages

    @Test
    void aMessageKeepsOnlyItsRoleContentCallsAndCallId() {
        Wire wire = sanitize("""
            {"messages":[
             {"role":"developer","name":"dev","content":"be brief"},
             {"role":"assistant","name":"a","reasoning_content":"thought","refusal":null,"audio":{"id":"x"},"content":null,
              "tool_calls":[{"id":"c1","type":"function","index":0,"function":{"name":"f","arguments":"{\\"a\\":1}","x":1},"y":2}],
              "function_call":{"name":"f"}},
             {"role":"tool","name":"f","tool_call_id":"c1","content":"ok","is_error":true},
             {"role":"user","name":"bob","content":"hi","metadata":{"a":1},"user":"u"}]}
            """);

        assertThat(wire.body().path("messages")).isEqualTo(tree("""
            [{"role":"system","content":"be brief"},
             {"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{\\"a\\":1}"}}]},
             {"role":"tool","content":"ok","tool_call_id":"c1"},
             {"role":"user","content":"hi"}]"""));
        for (JsonNode message : wire.body().path("messages")) {
            assertThat(message.propertyNames()).isSubsetOf(ChatWireSanitizer.MESSAGE_KEYS);
        }
    }

    @Test
    void callsAreKeptOnlyOnAssistantMessagesAndTheirArgumentsAreAlwaysText() {
        Wire wire = sanitize("""
            {"messages":[{"role":"user","content":"x","tool_calls":[{"id":"c","function":{"name":"f"}}]},
             {"role":"assistant","content":"","tool_calls":[{"id":"a","function":{"name":"f","arguments":{"k":1}}},
              {"function":{"name":"g"}},"junk",{"id":"n","function":{"name":"h","arguments":null}}]}]}
            """);

        assertThat(wire.body().path("messages").get(0).has("tool_calls")).isFalse();
        assertThat(wire.body().path("messages").get(1).path("tool_calls")).isEqualTo(tree("""
            [{"id":"a","type":"function","function":{"name":"f","arguments":"{\\"k\\":1}"}},
             {"id":"","type":"function","function":{"name":"g","arguments":""}},
             {"id":"n","type":"function","function":{"name":"h","arguments":""}}]"""));
    }

    @Test
    void aMessageThatIsNotAnObjectIsSkippedAndOneWithoutARoleKeepsNone() {
        Wire wire = sanitize("{\"messages\":[\"text\",null,{\"content\":\"x\"},{\"role\":\"user\",\"content\":\"y\"}]}");

        assertThat(wire.body().path("messages")).hasSize(2);
        assertThat(wire.body().path("messages").get(0).has("role")).isFalse();
        assertThat(StrictChatLint.passes(wire.body())).isFalse();
    }

    @Test
    void contentIsTextPartsOrNullAndOddShapesAreMadeIntoThose() {
        Wire wire = sanitize("""
            {"messages":[{"role":"user","content":7},{"role":"user","content":true},
             {"role":"user","content":{"type":"text","text":"one"}},{"role":"user"},{"role":"user","content":["bare",
             {"text":"no type"},{"type":"text","text":7}]}]}
            """);

        JsonNode messages = wire.body().path("messages");
        assertThat(messages.get(0).path("content").stringValue()).isEqualTo("7");
        assertThat(messages.get(1).path("content").stringValue()).isEqualTo("true");
        assertThat(messages.get(2).path("content")).isEqualTo(tree("[{\"type\":\"text\",\"text\":\"one\"}]"));
        assertThat(messages.get(3).path("content").isNull()).isTrue();
        assertThat(messages.get(4).path("content")).isEqualTo(tree(
            "[{\"type\":\"text\",\"text\":\"bare\"},{\"type\":\"text\",\"text\":\"no type\"},{\"type\":\"text\",\"text\":\"7\"}]"));
    }

    // ---------------------------------------------------------------------------------------- parts

    @Test
    void aPartKeepsOnlyItsTypeAndItsTextOrImage() {
        Wire wire = sanitize("""
            {"messages":[{"role":"user","content":[
             {"type":"text","text":"a","annotations":[],"extra":1},
             {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA","detail":"low","extra":1},"extra":2},
             {"type":"refusal","refusal":"no"},{"type":"thinking","thinking":"hmm"},{"type":"tool_result","content":"x"},5,null]}]}
            """, "vision");

        assertThat(wire.body().path("messages").get(0).path("content")).isEqualTo(tree("""
            [{"type":"text","text":"a"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA","detail":"low"}}]"""));
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void anImageIsReplacedByAPlaceholderForAMemberWithoutVision() {
        Wire wire = sanitize("""
            {"messages":[{"role":"user","content":[{"type":"text","text":"look"},
             {"type":"image_url","image_url":{"url":"https://example.invalid/a.png"}}]}]}
            """);

        assertThat(wire.body().path("messages").get(0).path("content")).isEqualTo(tree("""
            [{"type":"text","text":"look"},{"type":"text","text":"[image omitted]"}]"""));
        assertThat(wire.degraded()).containsExactly("image");
    }

    @Test
    void anImageThatIsNotTheUsersIsAPlaceholderEvenWithVision() {
        Wire wire = sanitize("""
            {"messages":[{"role":"system","content":[{"type":"image_url","image_url":{"url":"u"}}]},
             {"role":"assistant","content":[{"type":"image_url","image_url":{"url":"u"}}]},
             {"role":"tool","tool_call_id":"c","content":[{"type":"image_url","image_url":{"url":"u"}}]}]}
            """, "vision");

        for (JsonNode message : wire.body().path("messages")) {
            assertThat(message.path("content")).isEqualTo(tree("[{\"type\":\"text\",\"text\":\"[image omitted]\"}]"));
        }
        assertThat(wire.degraded()).containsExactly("image");
    }

    @Test
    void anImageWithoutAUrlIsAPlaceholderToo() {
        Wire wire = sanitize("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{}},"
            + "{\"type\":\"image_url\"},{\"type\":\"image_url\",\"image_url\":\"data:image/png;base64,AAAA\"}]}]}", "vision");

        assertThat(wire.body().path("messages").get(0).path("content")).isEqualTo(tree("""
            [{"type":"text","text":"[image omitted]"},{"type":"text","text":"[image omitted]"},
             {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]"""));
    }

    @Test
    void audioAndFilePartsArePlaceholdersWhateverTheMemberCan() {
        Wire wire = sanitize("""
            {"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"AAAA","format":"wav"}},
             {"type":"file","file":{"file_id":"file-1"}},{"type":"audio","audio":{"id":"a"}}]}]}
            """, "vision", "cache");

        assertThat(wire.body().path("messages").get(0).path("content")).isEqualTo(tree("""
            [{"type":"text","text":"[audio omitted]"},{"type":"text","text":"[file omitted]"},{"type":"text","text":"[audio omitted]"}]"""));
        assertThat(wire.degraded()).containsExactly("audio", "file");
    }

    @Test
    void textPartsWithoutTextAreLeftOutBecauseRelaysThatForwardToAnthropicRefuseThem() {
        Wire wire = sanitize("""
            {"messages":[{"role":"user","content":[{"type":"text","text":""},{"type":"text","text":"  \\n"},
             {"type":"text"},{"type":"text","text":null},{"type":"text","text":"kept"}]}]}
            """);

        assertThat(wire.body().path("messages").get(0).path("content")).isEqualTo(tree("[{\"type\":\"text\",\"text\":\"kept\"}]"));
    }

    @Test
    void cacheControlIsCarriedAtMessageAndPartLevelOnlyByAMemberThatPassesItThrough() {
        String json = """
            {"messages":[{"role":"system","content":"s","cache_control":{"type":"ephemeral"}},
             {"role":"user","content":[{"type":"text","text":"a","cache_control":{"type":"ephemeral","ttl":"1h"}},
              {"type":"image_url","image_url":{"url":"u"},"cache_control":{"type":"ephemeral"}}]}]}
            """;

        Wire carried = sanitize(json, "cache", "vision");
        Wire dropped = sanitize(json, "vision");

        assertThat(carried.body().path("messages").get(0).path("cache_control")).isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(carried.body().path("messages").get(1).path("content").get(0).path("cache_control").path("ttl").stringValue())
            .isEqualTo("1h");
        assertThat(carried.body().path("messages").get(1).path("content").get(1).has("cache_control")).isTrue();
        assertThat(carried.degraded()).isEmpty();
        assertThat(dropped.body().toString()).doesNotContain("cache_control");
        assertThat(dropped.degraded()).containsExactly("cache_control");
        for (Wire wire : List.of(carried, dropped)) {
            assertThat(StrictChatLint.passes(wire.body())).isTrue();
        }
    }

    @Test
    void aCacheControlIsRebuiltSoThatNothingOfTheClientsRidesInIt() {
        Wire wire = sanitize("""
            {"messages":[{"role":"system","content":"s","cache_control":{"type":"ephemeral","metadata":{"user_id":"user_8f3a"},"x":[1]}},
             {"role":"user","content":[{"type":"text","text":"a","cache_control":{"type":"persistent","ttl":"1h","note":"n"}},
              {"type":"text","text":"b","cache_control":{"ttl":5}},
              {"type":"text","text":"c","cache_control":{"ttl":"a-very-long-ttl-that-is-not-one"}},
              {"type":"text","text":"d","cache_control":"yes"}]}]}
            """, "cache");

        JsonNode messages = wire.body().path("messages");
        assertThat(messages.get(0).path("cache_control")).isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(messages.get(1).path("content").get(0).path("cache_control")).isEqualTo(tree("{\"type\":\"ephemeral\",\"ttl\":\"1h\"}"));
        assertThat(messages.get(1).path("content").get(1).path("cache_control")).isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(messages.get(1).path("content").get(2).path("cache_control")).isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(messages.get(1).path("content").get(3).path("cache_control")).isEqualTo(tree("{\"type\":\"ephemeral\"}"));
        assertThat(wire.body().toString()).doesNotContain("user_8f3a", "persistent", "\"note\"", "very-long");
        assertThat(StrictChatLint.passes(wire.body())).isTrue();
    }

    @Test
    void theTextPartsOfTheResponsesApiAreText() {
        Wire wire = sanitize("""
            {"messages":[{"role":"user","content":[{"type":"input_text","text":"Explain X"}]},
             {"role":"assistant","content":[{"type":"output_text","text":"X is a thing","cache_control":{"type":"ephemeral"}}]},
             {"role":"user","content":[{"type":"input_text","text":"and Y?"},{"type":"input_text","text":"  "},
              {"type":"output_text"},{"type":"refusal","refusal":"no"}]}]}
            """, "cache");

        assertThat(wire.body().path("messages").get(0).path("content")).isEqualTo(tree("[{\"type\":\"text\",\"text\":\"Explain X\"}]"));
        assertThat(wire.body().path("messages").get(1).path("content")).isEqualTo(
            tree("[{\"type\":\"text\",\"text\":\"X is a thing\",\"cache_control\":{\"type\":\"ephemeral\"}}]"));
        assertThat(wire.body().path("messages").get(2).path("content")).isEqualTo(tree("[{\"type\":\"text\",\"text\":\"and Y?\"}]"));
        assertThat(wire.degraded()).isEmpty();
        assertThat(StrictChatLint.passes(wire.body())).isTrue();
    }

    @Test
    void aNullCacheControlIsNothing() {
        Wire wire = sanitize("{\"messages\":[{\"role\":\"user\",\"content\":\"a\",\"cache_control\":null}]}");

        assertThat(wire.degraded()).isEmpty();
        assertThat(wire.body().path("messages").get(0).has("cache_control")).isFalse();
    }

    @Test
    void unicodeIsKeptAsItWas() {
        String text = "你好, 世界 😀 \\u0000 é \\n tab\\t \\\"quoted\\\"";
        Wire wire = sanitize("{\"messages\":[{\"role\":\"user\",\"content\":\"" + text + "\"},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]}");

        String expected = tree("\"" + text + "\"").stringValue();
        assertThat(wire.body().path("messages").get(0).path("content").stringValue()).isEqualTo(expected);
        assertThat(wire.body().path("messages").get(1).path("content").get(0).path("text").stringValue()).isEqualTo(expected);
    }

    // ---------------------------------------------------------------------------------------- response format

    @Test
    void jsonSchemaIsSentOnlyToAMemberThatHasItAndLoweredToJsonObjectForOthers() {
        String format = "\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"r\",\"schema\":{\"type\":\"object\"}},\"x\":1}";

        Wire supported = sanitize(chat(format), "jsonSchema");
        Wire lowered = sanitize(chat(format));

        assertThat(supported.body().path("response_format")).isEqualTo(tree(
            "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"r\",\"schema\":{\"type\":\"object\"}}}"));
        assertThat(supported.degraded()).isEmpty();
        assertThat(lowered.body().path("response_format")).isEqualTo(tree("{\"type\":\"json_object\"}"));
        assertThat(lowered.degraded()).containsExactly("json_schema");
    }

    @Test
    void aJsonSchemaFormatWithoutASchemaIsLoweredEvenForAMemberThatHasIt() {
        Wire wire = sanitize(chat("\"response_format\":{\"type\":\"json_schema\"}"), "jsonSchema");

        assertThat(wire.body().path("response_format")).isEqualTo(tree("{\"type\":\"json_object\"}"));
    }

    @Test
    void otherResponseFormatsAreCarriedByTypeOrLeftOut() {
        assertThat(sanitize(chat("\"response_format\":{\"type\":\"json_object\",\"x\":1}")).body().path("response_format"))
            .isEqualTo(tree("{\"type\":\"json_object\"}"));
        assertThat(sanitize(chat("\"response_format\":{\"type\":\"text\"}")).body().path("response_format"))
            .isEqualTo(tree("{\"type\":\"text\"}"));
        assertThat(sanitize(chat("\"response_format\":{\"type\":\"grammar\"}")).body().has("response_format")).isFalse();
        assertThat(sanitize(chat("\"response_format\":\"json\"")).body().has("response_format")).isFalse();
    }

    // ---------------------------------------------------------------------------------------- priority and stream

    @Test
    void priorityIsAskedForOnlyByABilledFastRequestToAMemberWithAPriorityTier() {
        String client = chat("\"service_tier\":\"priority\"");

        assertThat(sanitize(client, "fast", "priority").body().path("service_tier").stringValue()).isEqualTo("priority");
        assertThat(sanitize(client, "fast").body().has("service_tier")).isFalse();
        assertThat(sanitize(client, "priority").body().has("service_tier")).isFalse();
        assertThat(sanitize(client).body().has("service_tier")).isFalse();
        assertThat(sanitize(chat("\"fast\":true"), "priority").body().has("service_tier")).isFalse();
        assertThat(sanitize(chat("\"fast\":true"), "fast", "priority").body().path("service_tier").stringValue()).isEqualTo("priority");
    }

    @Test
    void theRelayIsAskedForAStreamOrNotAsTheMemberIsSetWhateverTheClientAsked() {
        Wire streaming = sanitize(chat("\"stream\":false,\"stream_options\":{\"include_usage\":false}"), "stream");
        Wire buffered = sanitize(chat("\"stream\":true,\"stream_options\":{\"include_usage\":true}"));

        assertThat(streaming.body().path("stream").booleanValue()).isTrue();
        assertThat(streaming.body().path("stream_options")).isEqualTo(tree("{\"include_usage\":true}"));
        assertThat(buffered.body().path("stream").booleanValue()).isFalse();
        assertThat(buffered.body().has("stream_options")).isFalse();
    }

    // ---------------------------------------------------------------------------------------- reasoning

    private static String effortOf(Wire wire) {
        return wire.body().path("reasoning_effort").stringValue(null);
    }

    @Test
    void aChatClientsReasoningEffortIsSentAsItIsToAMemberInEffortMode() {
        Wire wire = sanitize(chat("\"reasoning_effort\":\"xhigh\""), "effort");

        assertThat(effortOf(wire)).isEqualTo("xhigh");
        assertThat(wire.degraded()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1,low", "1024,low", "4000,low", "4095,low", "4096,medium", "10000,medium", "16383,medium", "16384,high", "31999,high", "200000,high"})
    void aThinkingBudgetBecomesAnEffortByTheThresholdsOfTheDesign(int budget, String effort) {
        String request = "{\"messages\":" + HELLO + ",\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":" + budget + "}}";

        Wire wire = sanitize(request, "effort");

        assertThat(effortOf(wire)).isEqualTo(effort);
    }

    @Test
    void thinkingFromTheEntryBodyIsReadWhenTheChatBodyHasNone() {
        Wire wire = withEntry(chat(""), "{\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":20000},\"messages\":[]}", "effort");

        assertThat(effortOf(wire)).isEqualTo("high");
    }

    @ParameterizedTest
    @CsvSource({"low,low", "medium,medium", "high,high", "max,high", "xhigh,high", "MAX,high", "' High ',high"})
    void adaptiveThinkingTakesItsEffortFromTheOutputConfigOfTheEntryBody(String given, String effort) {
        Wire wire = withEntry(
            chat("\"thinking\":{\"type\":\"adaptive\"}"),
            "{\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"" + given + "\"}}",
            "effort");

        assertThat(effortOf(wire)).isEqualTo(effort);
    }

    @Test
    void adaptiveThinkingWithoutAnEffortIsHighWhichIsAnthropicsDefault() {
        Wire wire = withEntry(chat("\"thinking\":{\"type\":\"adaptive\"}"), "{\"thinking\":{\"type\":\"adaptive\"}}", "effort");

        assertThat(effortOf(wire)).isEqualTo("high");
    }

    @Test
    void anEffortThatHasNoNameHereIsNotGuessedAndIsRecorded() {
        Wire wire = withEntry(chat(""), "{\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"ludicrous\"}}", "effort");

        assertThat(wire.body().has("reasoning_effort")).isFalse();
        assertThat(wire.degraded()).containsExactly("thinking");
    }

    @Test
    void theOutputConfigOfTheConversionIsNotNeededWhenTheEntryBodyIsTheChatBody() {
        Wire wire = sanitize("{\"messages\":" + HELLO + ",\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"low\"}}", "effort");

        assertThat(effortOf(wire)).isEqualTo("low");
    }

    @Test
    void outputConfigWithoutThinkingAsksForNoReasoning() {
        Wire wire = withEntry(chat(""), "{\"output_config\":{\"effort\":\"high\"}}", "effort");

        assertThat(wire.body().has("reasoning_effort")).isFalse();
        assertThat(wire.degraded()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"disabled\"}", "{\"type\":\"enabled\",\"budget_tokens\":0}", "{\"budget_tokens\":-1}", "{}", "\"yes\"", "null"
    })
    void noThinkingRequestedMeansNothingSentAndNothingRecorded(String thinking) {
        Wire wire = sanitize(chat("\"thinking\":" + thinking), "effort");

        assertThat(wire.body().has("reasoning_effort")).isFalse();
        assertThat(wire.degraded()).isEmpty();
    }

    @Test
    void enabledThinkingWithoutABudgetIsMedium() {
        assertThat(effortOf(sanitize(chat("\"thinking\":{\"type\":\"enabled\"}"), "effort"))).isEqualTo("medium");
    }

    @Test
    void geminiThinkingConfigIsReadFromTheEntryBody() {
        assertThat(effortOf(withEntry(chat(""), "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":2048}}}", "effort")))
            .isEqualTo("low");
        assertThat(effortOf(withEntry(chat(""), "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":8192}}}", "effort")))
            .isEqualTo("medium");
        assertThat(effortOf(withEntry(chat(""), "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingLevel\":\"HIGH\"}}}", "effort")))
            .isEqualTo("high");
        assertThat(withEntry(chat(""), "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":0}}}", "effort").body().has("reasoning_effort"))
            .isFalse();
    }

    @Test
    void aMemberNotInEffortModeIsNotSentTheEffortAndTheRequestSaysWhatWasLeftOut() {
        Wire fromThinking = sanitize(chat("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":9000}"));
        Wire fromEffort = sanitize(chat("\"reasoning_effort\":\"high\""));

        assertThat(fromThinking.body().has("reasoning_effort")).isFalse();
        assertThat(fromThinking.degraded()).containsExactly("thinking");
        assertThat(fromEffort.body().has("reasoning_effort")).isFalse();
        assertThat(fromEffort.degraded()).containsExactly("reasoning_effort");
    }

    @Test
    void aClaudeModelsToolContinuationTurnIsNotSentAnEffort() {
        String thinking = "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":9000},";
        String calls = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}";
        String result = "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"r\"}";

        Wire afterResult = sanitize("{" + thinking + "\"messages\":[{\"role\":\"user\",\"content\":\"go\"}," + calls + "," + result + "]}", "effort", "claude");
        Wire resultWithClientText = sanitize("{" + thinking + "\"messages\":[{\"role\":\"user\",\"content\":\"go\"}," + calls + ","
            + result + ",{\"role\":\"user\",\"content\":\"<system-reminder>\"}]}", "effort", "claude");
        Wire newPrompt = sanitize("{" + thinking + "\"messages\":[{\"role\":\"user\",\"content\":\"go\"}," + calls + "," + result
            + ",{\"role\":\"assistant\",\"content\":\"done\"},{\"role\":\"user\",\"content\":\"again\"}]}", "effort", "claude");
        Wire first = sanitize("{" + thinking + "\"messages\":[{\"role\":\"user\",\"content\":\"go\"}]}", "effort", "claude");

        assertThat(afterResult.body().has("reasoning_effort")).isFalse();
        assertThat(afterResult.degraded()).containsExactly("thinking");
        assertThat(resultWithClientText.body().has("reasoning_effort")).isFalse();
        assertThat(effortOf(newPrompt)).isEqualTo("medium");
        assertThat(effortOf(first)).isEqualTo("medium");
    }

    private static final String CHAT_TOOL_LOOP_HIGH = """
        {"reasoning_effort":"high","messages":[{"role":"user","content":"go"},
         {"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"{}"}}]},
         {"role":"tool","tool_call_id":"a","content":"r"}]}
        """;

    @Test
    void aChatClientsEffortOnAToolContinuationTurnIsLeftOutForAClaudeModelToo() {
        Wire wire = sanitize(CHAT_TOOL_LOOP_HIGH, "effort", "claude");

        assertThat(wire.body().has("reasoning_effort")).isFalse();
        assertThat(wire.degraded()).containsExactly("reasoning_effort");
    }

    /**
     * The reason a continuation turn is not asked for a level is Anthropic's (the next turn needs the thinking blocks of
     * the one before). An OpenAI model takes its level on every request of an agent loop, and one that loses it after
     * the first turn answers at its default, which is not what the client asked for.
     */
    @Test
    void aChatClientsEffortOnAToolContinuationTurnIsSentToAnOpenAiModel() {
        Wire wire = sanitize(CHAT_TOOL_LOOP_HIGH, "effort");

        assertThat(effortOf(wire)).isEqualTo("high");
        assertThat(wire.degraded()).isEmpty();
        // Whatever the level the client asked for is what is sent, minimal included.
        assertThat(effortOf(sanitize(CHAT_TOOL_LOOP_HIGH.replace("\"high\"", "\"minimal\""), "effort"))).isEqualTo("minimal");
        // The member's own mode still decides first.
        assertThat(sanitize(CHAT_TOOL_LOOP_HIGH).degraded()).containsExactly("reasoning_effort");
    }

    @Test
    void theVendorsWhoseModelsNeedTheThinkingOfTheTurnBeforeAreAnthropicsAlone() {
        assertThat(ChatWireSanitizer.dropsReasoningOnContinuation("anthropic")).isTrue();
        assertThat(ChatWireSanitizer.dropsReasoningOnContinuation("openai")).isFalse();
        assertThat(ChatWireSanitizer.dropsReasoningOnContinuation("google")).isFalse();
        assertThat(ChatWireSanitizer.dropsReasoningOnContinuation("deepseek")).isFalse();
        assertThat(ChatWireSanitizer.dropsReasoningOnContinuation(null)).isFalse();
    }

    @Test
    void onlyAnAnthropicModelNeedsToolsForItsToolHistory() {
        assertThat(ChatWireSanitizer.needsToolsForToolHistory("anthropic")).isTrue();
        assertThat(ChatWireSanitizer.needsToolsForToolHistory("openai")).isFalse();
        assertThat(ChatWireSanitizer.needsToolsForToolHistory("google")).isFalse();
        assertThat(ChatWireSanitizer.needsToolsForToolHistory("deepseek")).isFalse();
        assertThat(ChatWireSanitizer.needsToolsForToolHistory(null)).isFalse();
    }

    @Test
    void theNamesOfWhatWasLeftOutAreListedOnceInTheOrderFound() {
        Wire wire = sanitize("""
            {"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"u"}},
             {"type":"text","text":"a","cache_control":{"type":"ephemeral"}},{"type":"image_url","image_url":{"url":"v"}},
             {"type":"file","file":{}}]},{"role":"user","content":[{"type":"text","text":"b","cache_control":{"type":"ephemeral"}}]}],
             "response_format":{"type":"json_schema","json_schema":{}},"reasoning_effort":"low"}
            """);

        assertThat(wire.degraded()).containsExactly("image", "cache_control", "file", "json_schema", "reasoning_effort");
    }

    @Test
    void theWireRecordKeepsOneOfEachName() {
        Wire wire = new Wire(JSON.createObjectNode(), List.of("image", "file", "image"));

        assertThat(wire.degraded()).containsExactly("image", "file");
    }
}
