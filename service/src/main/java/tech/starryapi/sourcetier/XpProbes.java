package tech.starryapi.sourcetier;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Function;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.xproto.ChatCanonicalizer;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.ChatWireSanitizer;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tech.starryapi.platform.xproto.RelayUsageNormalizer;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Reported;
import tech.starryapi.platform.xproto.StrictChatLint;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The questions a relay is asked to be verified, and how its answers are read (design 7.4). Nothing here talks to the
 * relay: a probe is a request body and a judgement of the answer, and {@link XpMappingVerifier} sends the one and
 * applies the other.
 *
 * <p>The requests are built the way traffic is built, not by hand: a plain Chat body goes through the whitelist
 * sanitizer, the canonicalizer and the lint with the member's own settings, so a probe sends the shapes a request of the
 * gateway has (the token field the model takes, {@code stream_options} for a stream, an image only for a member with
 * vision) and a mistake of the pipeline would show here as it would show in a real request. The answers are read by the
 * accumulator that reads real answers, so what passes here is what the gateway can use.
 *
 * <ol>
 *   <li>{@link #BASIC}: a short prompt with {@code max_tokens} 16, no stream. The answer must say why it ended ({@code
 *       finish_reason}) and report what the prompt and the answer cost, because a relay that does neither cannot be billed
 *       or told to have been cut short. Models that take {@code max_completion_tokens} count their reasoning in it, and
 *       16 would leave them nothing to answer with, so they get {@link #REASONING_BUDGET}.
 *   <li>{@link #TOOL_CALL}: a function offered with {@code tool_choice: auto} and an instruction to call it, not {@code
 *       required}, which relays turn into Anthropic's {@code any}, and Claude models that think refuse it. The answer must
 *       call it with arguments that are a JSON object holding the city.
 *   <li>{@link #TOOL_LOOP}: the call, its result and the question again, the shape a client sends on every turn after a
 *       tool use, where OpenAI-to-Anthropic relays most often go wrong. The answer must be text.
 *   <li>{@link #STREAM}, for a member that asks the relay for a stream: the first prompt again as an event stream with
 *       usage chunks.
 *   <li>{@link #VISION}, for a member with vision: a one-pixel PNG and a question about it.
 * </ol>
 *
 * The tool probes use the member's stream setting, so they read the answer the way traffic does.
 */
final class XpProbes {

    static final String BASIC = "basic";
    static final String TOOL_CALL = "tool_call";
    static final String TOOL_LOOP = "tool_loop";
    static final String STREAM = "stream";
    static final String VISION = "vision";
    static final String CACHE = "cache";
    /** Not a question to the relay: the finding that a "not contained" setting has no evidence behind it (see {@link XpCacheEvidence}). */
    static final String CACHE_EVIDENCE = "cache_evidence";

    static final String TOOL_NAME = "get_weather";

    /** Tokens for a model that counts its reasoning in the limit: 16 would be spent before the first word. */
    static final int REASONING_BUDGET = 512;

    /** A 1x1 PNG. */
    static final String PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    /** The prompt of the cache probe is this sentence (ten tokens) this many times: about 1,200 tokens, over the 1,024 caches start at. */
    static final int CACHE_SENTENCES = 120;
    private static final String CACHE_SENTENCE = "The quick brown fox jumps over the lazy dog. ";

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private XpProbes() {
    }

    /** What a probe is made for: the member's settings, the mapping, and whether the model takes {@code max_completion_tokens}. */
    record Setup(Member member, MemberModel model, boolean maxCompletionTokens) {
    }

    /**
     * One question. {@code judge} returns what is wrong with the answer, in the gateway's words, or null when it is what
     * was needed.
     */
    record Probe(String name, ObjectNode body, Function<Completion, String> judge) {
    }

    /** The probes a pass needs, in the order they are asked. */
    static List<Probe> required(Setup setup) {
        Member member = setup.member();
        List<Probe> probes = new ArrayList<>();
        probes.add(new Probe(BASIC, wire(setup, basic(setup), false), XpProbes::judgeUsage));
        probes.add(new Probe(TOOL_CALL, wire(setup, toolCall(setup), member.upstreamStream()), XpProbes::judgeToolCall));
        probes.add(new Probe(TOOL_LOOP, wire(setup, toolLoop(setup), member.upstreamStream()), XpProbes::judgeFinalAnswer));
        if (member.upstreamStream()) {
            probes.add(new Probe(STREAM, wire(setup, basic(setup), true), XpProbes::judgeUsage));
        }
        if (member.vision()) {
            probes.add(new Probe(VISION, wire(setup, vision(setup), member.upstreamStream()), completion -> null));
        }
        return probes;
    }

    /**
     * The request of the cache probe, asked twice as it is. {@code nonce} opens the prompt, so a prompt of an earlier
     * verification (or of anyone else) is not what the first answer finds in the relay's cache.
     */
    static ObjectNode cacheRequest(Setup setup, String nonce) {
        ObjectNode chat = chat(setup, 16);
        chat.putArray("messages").add(message("user", "Marker " + nonce + ". " + CACHE_SENTENCE.repeat(CACHE_SENTENCES)
            + "\nReply with the single word: ok"));
        return wire(setup, chat, setup.member().upstreamStream());
    }

    // ---- the requests ----

    private static ObjectNode chat(Setup setup, int tokens) {
        ObjectNode chat = FACTORY.objectNode();
        chat.put("max_tokens", setup.maxCompletionTokens() ? Math.max(tokens, REASONING_BUDGET) : tokens);
        return chat;
    }

    private static ObjectNode message(String role, String text) {
        ObjectNode message = FACTORY.objectNode();
        message.put("role", role);
        message.put("content", text);
        return message;
    }

    private static ObjectNode basic(Setup setup) {
        ObjectNode chat = chat(setup, 16);
        chat.putArray("messages").add(message("user", "Reply with the single word: ok"));
        return chat;
    }

    private static ObjectNode weatherTool() {
        ObjectNode tool = FACTORY.objectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", TOOL_NAME);
        function.put("description", "Gets the current weather of a city");
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        parameters.putObject("properties").putObject("city").put("type", "string");
        parameters.putArray("required").add("city");
        return tool;
    }

    private static ObjectNode toolCall(Setup setup) {
        ObjectNode chat = chat(setup, 128);
        ArrayNode messages = chat.putArray("messages");
        messages.add(message("system", "You are a test harness. When asked to call a function, call it and write no text."));
        messages.add(message("user", "Call the function " + TOOL_NAME + " for the city Paris."));
        chat.putArray("tools").add(weatherTool());
        chat.put("tool_choice", "auto");
        return chat;
    }

    private static ObjectNode toolLoop(Setup setup) {
        ObjectNode chat = chat(setup, 128);
        ArrayNode messages = chat.putArray("messages");
        messages.add(message("user", "What is the weather in Paris? Use the function " + TOOL_NAME
            + ", then answer in one short sentence."));
        ObjectNode call = FACTORY.objectNode();
        call.put("role", "assistant");
        call.putNull("content");
        ObjectNode toolCall = call.putArray("tool_calls").addObject();
        toolCall.put("id", "call_probe0001");
        toolCall.put("type", "function");
        ObjectNode function = toolCall.putObject("function");
        function.put("name", TOOL_NAME);
        function.put("arguments", "{\"city\":\"Paris\"}");
        messages.add(call);
        ObjectNode result = FACTORY.objectNode();
        result.put("role", "tool");
        result.put("tool_call_id", "call_probe0001");
        result.put("content", "{\"temperature_celsius\":21,\"condition\":\"sunny\"}");
        messages.add(result);
        chat.putArray("tools").add(weatherTool());
        chat.put("tool_choice", "auto");
        return chat;
    }

    private static ObjectNode vision(Setup setup) {
        ObjectNode chat = chat(setup, 32);
        ObjectNode user = chat.putArray("messages").addObject();
        user.put("role", "user");
        ArrayNode parts = user.putArray("content");
        parts.addObject().put("type", "text").put("text", "What is in this image? Answer in one word.");
        parts.addObject().put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64," + PIXEL);
        return chat;
    }

    /** The body the gateway would send for {@code chat}: the whitelist, the canonical form, the lint. */
    private static ObjectNode wire(Setup setup, ObjectNode chat, boolean stream) {
        Member member = setup.member();
        Settings settings = new Settings(member.vision(), member.jsonSchema(), member.priorityTier(),
            member.cacheControlPassthrough(), stream, "EFFORT".equals(member.reasoningMode()), false, setup.maxCompletionTokens(),
            ChatWireSanitizer.dropsReasoningOnContinuation(member.vendor()), ChatWireSanitizer.needsToolsForToolHistory(member.vendor()));
        Wire wire = ChatCanonicalizer.canonicalize(ChatWireSanitizer.sanitize(chat, null, settings, setup.model().upstreamModel()), settings);
        List<String> problems = StrictChatLint.problems(wire.body());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("A probe request does not pass the lint: " + problems);
        }
        return wire.body();
    }

    // ---- reading the answers ----

    /** The counts an answer reported, as numbers for the report. */
    static Reported reported(Completion completion) {
        return RelayUsageNormalizer.read(completion.usage());
    }

    private static boolean positive(OptionalLong count) {
        return count.isPresent() && count.getAsLong() > 0;
    }

    private static String judgeUsage(Completion completion) {
        if (completion.finishReason() == null) {
            return "The answer does not say why it ended (no finish_reason)";
        }
        Reported usage = reported(completion);
        if (!positive(usage.prompt()) || !positive(usage.output())) {
            return "The answer does not report the tokens of the prompt and of the answer (usage)";
        }
        return null;
    }

    private static String judgeToolCall(Completion completion) {
        if (completion.toolUses().isEmpty()) {
            return "The relay did not call the function it was asked to call";
        }
        PlatformToolUse call = completion.toolUses().getFirst();
        if (!TOOL_NAME.equals(call.name())) {
            return "The relay called a function that was not offered";
        }
        JsonNode city = call.input() == null ? null : call.input().get("city");
        if (city == null || !city.isString() || city.asString("").isBlank()) {
            return "The function call does not have the arguments that were asked for";
        }
        return null;
    }

    private static String judgeFinalAnswer(Completion completion) {
        return completion.content() == null || completion.content().isBlank()
            ? "The relay gave no text after the result of the function" : null;
    }
}
