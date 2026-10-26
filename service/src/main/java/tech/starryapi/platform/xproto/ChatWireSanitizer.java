package tech.starryapi.platform.xproto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the Chat Completions request an OpenAI-format relay is sent from the one the gateway has (design 7.6): a new
 * body made of what is allowed, not a copy of the input with some fields taken out. A relay sees only this body, a
 * bearer key and the route (design 7.12), so whatever is not named here, the client's own fields, its session ids,
 * {@code metadata.user_id} and {@code user} among them, cannot reach it, however the entry's conversion or a client
 * happened to name it. The whitelist has three levels, and {@link StrictChatLint} rejects a body that has a key outside
 * any of them:
 *
 * <ul>
 *   <li><b>The body</b>: {@code model} (only when the caller names the wire model; the dispatcher writes it otherwise),
 *       {@code messages}, one token limit, {@code temperature}, {@code top_p}, {@code stop} (more than four are not
 *       cut here: the feature policy refuses them earlier and the lint after), {@code tools}, {@code tool_choice},
 *       {@code parallel_tool_calls}, the two penalties, {@code seed}, {@code response_format}, {@code
 *       reasoning_effort}, {@code service_tier} and {@code stream} with {@code stream_options}. Everything else is
 *       left out: {@code top_k}, {@code metadata}, {@code thinking}, {@code user}, {@code previous_response_id},
 *       {@code store}, {@code include}, {@code n}, {@code logprobs}, {@code fast}, {@code verbosity}, {@code
 *       logit_bias}, {@code prompt_cache_key}, {@code prompt_cache_retention}, {@code safety_identifier}, the session ids
 *       and the rest (the feature policy names the ones a native account would have been given: {@link
 *       RequestFeatureDetector}).
 *   <li><b>A message</b>: {@code role} ({@code developer} is {@code system}), {@code content}, {@code tool_calls}
 *       (each with {@code id}, {@code type} and {@code function.name}/{@code arguments}) and {@code tool_call_id}; and
 *       a {@code cache_control}, which design 7.9 carries at message and at part level together, when the member
 *       passes it through. {@code name}, {@code reasoning_content}, {@code refusal}, {@code audio} and the rest are
 *       left out.
 *   <li><b>A content part</b>: {@code {type:text,text}} (the Responses names {@code input_text} and {@code
 *       output_text}, which the native accounts read as text too, are text), {@code {type:image_url,image_url:{url,
 *       detail}}} for a member with vision, and the {@code cache_control} of a member that passes it through. Any other
 *       part (a thinking or refusal part the relay has no use for) is left out, and so is a text part without text,
 *       which relays that forward to Anthropic refuse.
 * </ul>
 *
 * <p>Two values of the whitelist are built, not copied, because a relay is handed whatever the client wrote in them. A
 * {@code cache_control} is {@code {type:ephemeral}} and the {@code ttl}, if it is a short string. A {@code tool_choice}
 * is one of the strings {@code auto}, {@code none} and {@code required}, or {@code {type:function,function:{name}}} for a
 * tool that is sent; any other (the {@code allowed_tools} of a client of another API, an Anthropic type nobody
 * converted) is left out and recorded as {@code tool_choice}.
 *
 * <p>What a relay cannot take is replaced, not dropped, so that the turn it belonged to is still there for the model
 * and the canonicalizer: an image the member cannot see, and every image in a message that is not the user's, becomes
 * a text part {@code [image omitted]}; audio and file parts become {@code [audio omitted]} and {@code [file
 * omitted]}. A {@code json_schema} response format the member does not support is lowered to {@code json_object}.
 * Each of these, a {@code cache_control} that is not passed through, and a reasoning request that is not carried
 * (below), is recorded by name in {@link Wire#degraded()} so the request log can say what the answer was made without
 * (these are the SOFT features of design 7.9, which the feature policy names from the original request in the same
 * words). Fields that are only left out, such as {@code top_k}, are the policy's to name and are not listed here.
 *
 * <p>Reasoning. {@code reasoning_effort} is sent only to a member whose reasoning mode is effort, only when the client
 * asked for reasoning, and, for a model that needs it ({@link Settings#continuationDropsReasoning}), not on a tool
 * continuation turn (the request ends with tool results, possibly followed by more user text of the same client
 * message): the turn after a tool call of a Claude model with thinking needs the thinking blocks of the one before, which
 * no OpenAI-format history has. The reason is Anthropic's alone. An OpenAI model takes its {@code reasoning_effort} on
 * every request of an agent loop, and one that loses it on all but the first turn answers at the model's default level,
 * which the client did not ask for and the tier is paid for. What the client asked for is, in this order: a {@code reasoning_effort} of the
 * Chat body (sent as it is); an Anthropic {@code thinking} with a {@code budget_tokens} (under 4,096 low, under 16,384
 * medium, else high, so that Claude Code's 4,000-token "think" is the lowest level); an Anthropic {@code thinking}
 * of type {@code adaptive} with the {@code output_config.effort} of the <em>entry</em> body ({@code low}, {@code
 * medium}, {@code high}; {@code max} and {@code xhigh} are {@code high}; none given is Anthropic's default, high; a
 * level not listed is not guessed, and is recorded as left out), which the conversion to Chat does not carry and is why
 * the original body is a parameter; and a Gemini {@code
 * thinkingConfig} with a positive {@code thinkingBudget}, bucketed the same way, or a {@code thinkingLevel}.
 *
 * <p>The token limit is {@code max_completion_tokens} for the models that only take that one ({@link
 * #usesMaxCompletionTokens}, which is {@code OpenAiUpstreamClient}'s rule applied to the catalog's model) and {@code
 * max_tokens} otherwise, whichever of {@code max_completion_tokens}, {@code max_tokens} and {@code max_output_tokens}
 * the input has first. {@code tool_choice} and {@code parallel_tool_calls} go only with tools, since relays refuse them
 * alone. The relay is always asked for what the member is set to: {@code stream: true} with usage chunks or {@code
 * stream: false}, whatever the client asked.
 *
 * <p>A {@code tool_search_tool_*} of a Messages request is taken out (design 7.9): the relay cannot run it, and the
 * deferred tools are sent as plain functions, as the native path does. It is recorded as {@code tool_search_tool}, and
 * a {@code tool_choice} that names it goes with it. The conversion to Chat turns it into a function of the same name,
 * so it is found by the name it has in the original body ({@code entryBody}).
 *
 * <p>The input is not changed. The result shares unchanged subtrees with it (tool schemas, scalar nodes), as {@code
 * JsonNodes.shallowCopy} does, so it must not be edited in place either; the canonicalizer copies what it changes.
 * Malformed pieces are skipped rather than failed on (a message that is not an object, a part with a type nothing
 * knows), because the lint is what judges the result and a body that cannot be repaired fails there.
 */
public final class ChatWireSanitizer {

    /** Names recorded in {@link Wire#degraded()}, in the words of the feature policy (design 7.9). */
    public static final String IMAGE = "image";
    public static final String AUDIO = "audio";
    public static final String FILE = "file";
    public static final String CACHE_CONTROL = "cache_control";
    public static final String JSON_SCHEMA = "json_schema";
    public static final String THINKING = "thinking";
    public static final String REASONING_EFFORT = "reasoning_effort";
    public static final String TOOL_SEARCH = "tool_search_tool";
    public static final String TOOL_CHOICE = "tool_choice";

    static final String IMAGE_PLACEHOLDER = "[image omitted]";
    static final String AUDIO_PLACEHOLDER = "[audio omitted]";
    static final String FILE_PLACEHOLDER = "[file omitted]";

    // The whitelist, one set per level. The lint accepts exactly these, so the two cannot drift apart.
    static final Set<String> BODY_KEYS = Set.of(
        "model", "messages", "max_tokens", "max_completion_tokens", "temperature", "top_p", "stop", "tools",
        "tool_choice", "parallel_tool_calls", "frequency_penalty", "presence_penalty", "seed", "response_format",
        "reasoning_effort", "service_tier", "stream", "stream_options"
    );
    static final Set<String> MESSAGE_KEYS = Set.of("role", "content", "tool_calls", "tool_call_id", "cache_control");
    static final Set<String> PART_KEYS = Set.of("type", "text", "image_url", "cache_control");
    static final Set<String> IMAGE_URL_KEYS = Set.of("url", "detail");
    static final Set<String> TOOL_CALL_KEYS = Set.of("id", "type", "function");
    static final Set<String> TOOL_CALL_FUNCTION_KEYS = Set.of("name", "arguments");
    static final Set<String> TOOL_KEYS = Set.of("type", "function");
    static final Set<String> TOOL_FUNCTION_KEYS = Set.of("name", "description", "parameters", "strict");
    static final Set<String> CACHE_CONTROL_KEYS = Set.of("type", "ttl");
    static final Set<String> TOOL_CHOICE_STRINGS = Set.of("auto", "none", "required");
    static final Set<String> TOOL_CHOICE_KEYS = Set.of("type", "function");

    /** The longest {@code ttl} of a cache control that is passed on; the ones Anthropic has are {@code 5m} and {@code 1h}. */
    private static final int MAX_TTL_LENGTH = 8;

    /** Budgets at and above these are medium and high; Claude Code's "think" is 4,000 and "ultrathink" 31,999. */
    private static final long MEDIUM_FROM_BUDGET = 4_096;
    private static final long HIGH_FROM_BUDGET = 16_384;

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private ChatWireSanitizer() {
    }

    /**
     * What a member is set to and what the request is billed as. The body that results depends on nothing else, so
     * members that agree on this record are sent the same body and it is built once (design 7.6); the wire model name,
     * which differs per member, is the dispatcher's to write.
     *
     * @param vision                 the member's {@code vision}: user images are carried, not replaced
     * @param jsonSchema             the member's {@code json_schema}
     * @param priorityTier           the member's {@code priority_tier}
     * @param cacheControlPassthrough the member's {@code cache_control_passthrough}
     * @param upstreamStream         the member's {@code upstream_stream}
     * @param reasoningEffort        the member's reasoning mode is {@code EFFORT}
     * @param billedFastMode         the request is billed as fast; only this asks for {@code service_tier: priority},
     *                               and only of a member with a priority tier (design 5.1)
     * @param maxCompletionTokens    the catalog model takes {@code max_completion_tokens}, see {@link
     *                               #usesMaxCompletionTokens}
     * @param continuationDropsReasoning the model cannot be asked for a reasoning level on a tool continuation turn: it
     *                               needs the thinking blocks of the turn before, which a relay's history does not
     *                               have. It is a property of the model's vendor ({@link
     *                               #dropsReasoningOnContinuation}), not of the entry the request came in through
     * @param needsToolsForToolHistory the model's upstream refuses a history that has tool calls and results in a request
     *                               that defines no tools, so {@link ChatCanonicalizer} makes text of them in that case.
     *                               It is a property of the model's vendor ({@link #needsToolsForToolHistory}), the
     *                               same as the flag above, and members that differ in it get different bodies
     */
    public record Settings(
        boolean vision,
        boolean jsonSchema,
        boolean priorityTier,
        boolean cacheControlPassthrough,
        boolean upstreamStream,
        boolean reasoningEffort,
        boolean billedFastMode,
        boolean maxCompletionTokens,
        boolean continuationDropsReasoning,
        boolean needsToolsForToolHistory
    ) {
    }

    /**
     * Whether the models of a vendor lose their thinking when the turn after a tool call is asked at a reasoning level
     * (design 7.6): Claude's, whose next turn is refused or answered without thinking unless the thinking blocks of the
     * one before come back with it. OpenAI's reasoning models, and the others a relay can serve, take the level on every
     * turn.
     */
    public static boolean dropsReasoningOnContinuation(String vendor) {
        return "anthropic".equals(vendor);
    }

    /**
     * Whether the upstream of a vendor's models refuses a history with tool calls and results in a request that defines no
     * tools (design 7.6): Anthropic and Bedrock, which the relays that serve Claude forward to, and which Kiro, the native
     * source of those requests, answers by making text of the history as well. A relay that forwards to OpenAI is given
     * the structured history, as the native OpenAI accounts are, and the policy has no name for a change they do not make.
     * The vendors that join later (Google, with its own pair and fixtures) decide it with them.
     */
    public static boolean needsToolsForToolHistory(String vendor) {
        return "anthropic".equals(vendor);
    }

    /**
     * A request body for a relay and what was left out of it to make it one. The body belongs to nobody but the pipeline:
     * it is not edited after it is built.
     *
     * @param degraded the features the client asked for that the relay was not given, once each, in the order they
     *                 were found
     */
    public record Wire(ObjectNode body, List<String> degraded) {

        public Wire {
            Objects.requireNonNull(body, "body");
            degraded = List.copyOf(new LinkedHashSet<>(degraded));
        }

        /** The same body with more features recorded as left out. */
        Wire degradedAlso(Set<String> more) {
            if (more.isEmpty()) {
                return this;
            }
            Set<String> all = new LinkedHashSet<>(degraded);
            all.addAll(more);
            return new Wire(body, List.copyOf(all));
        }
    }

    /**
     * Whether the model takes {@code max_completion_tokens} and not {@code max_tokens}: the rule of {@code
     * OpenAiUpstreamClient} (the GPT-5 and GPT-6 families and the o-series). It is a copy because that client's
     * method is private and the client is left as it is; {@code ChatWireSanitizerTest} pins the list.
     */
    public static boolean usesMaxCompletionTokens(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String lower = model.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("gpt-6") || lower.startsWith("gpt-5") || lower.startsWith("o1") || lower.startsWith("o3")
            || lower.startsWith("o4");
    }

    /**
     * What {@link #sanitize} reads of the client's own request ({@code entryBody}), kept in a body of its own: the
     * reasoning request ({@code thinking}, {@code output_config.effort}, a Gemini {@code thinkingConfig}) and the tools of
     * the tool search, by type and name. A request is planned long before it is sent, and the body of a Messages or Gemini
     * request may hold large base64 images that nothing should keep alive for that long, so the facade takes this when the
     * request comes in and plans, and later sanitizes, from it. {@code sanitize(chat, entryDigest(entry), ...)} gives
     * exactly what {@code sanitize(chat, entry, ...)} does; {@code ChatWireSanitizerTest} pins that for the shapes the
     * sanitizer reads.
     */
    public static ObjectNode entryDigest(JsonNode entryBody) {
        ObjectNode digest = FACTORY.objectNode();
        if (entryBody == null || !entryBody.isObject()) {
            return digest;
        }
        JsonNode thinking = entryBody.path("thinking");
        if (thinking.isObject()) {
            ObjectNode kept = digest.putObject("thinking");
            if (thinking.path("type").isString()) {
                kept.set("type", thinking.path("type"));
            }
            if (thinking.path("budget_tokens").isNumber()) {
                kept.set("budget_tokens", thinking.path("budget_tokens"));
            }
        }
        JsonNode outputConfig = entryBody.path("output_config");
        if (outputConfig.isObject()) {
            ObjectNode kept = digest.putObject("output_config");
            if (outputConfig.path("effort").isString()) {
                kept.set("effort", outputConfig.path("effort"));
            }
        }
        JsonNode config = entryBody.path("generationConfig").path("thinkingConfig");
        if (!config.isObject()) {
            config = entryBody.path("thinkingConfig");
        }
        if (config.isObject()) {
            ObjectNode kept = digest.putObject("thinkingConfig");
            if (config.path("thinkingBudget").isNumber()) {
                kept.set("thinkingBudget", config.path("thinkingBudget"));
            }
            if (config.path("thinkingLevel").isString()) {
                kept.set("thinkingLevel", config.path("thinkingLevel"));
            }
        }
        JsonNode tools = entryBody.path("tools");
        if (tools.isArray()) {
            ArrayNode search = FACTORY.arrayNode();
            for (JsonNode tool : tools) {
                if (tool.isObject() && tool.path("type").stringValue("").toLowerCase(Locale.ROOT).startsWith("tool_search_tool")) {
                    ObjectNode kept = search.addObject();
                    kept.put("type", tool.path("type").stringValue(""));
                    if (tool.path("name").isString()) {
                        kept.set("name", tool.path("name"));
                    }
                }
            }
            if (!search.isEmpty()) {
                digest.set("tools", search);
            }
        }
        return digest;
    }

    /**
     * @param chatBody  the Chat Completions body the entry gives: the client's own for the Chat entry, the conversion's
     *                  output for the others
     * @param entryBody the client's request as it came in, which carries what the conversion drops ({@code
     *                  output_config}), or its {@link #entryDigest}; null when the chat body is the client's own
     * @param wireModel the model name the relay is to be asked for, or null when the dispatcher writes it
     */
    public static Wire sanitize(JsonNode chatBody, JsonNode entryBody, Settings settings, String wireModel) {
        if (chatBody == null || !chatBody.isObject()) {
            throw new IllegalArgumentException("The chat body must be a JSON object");
        }
        Objects.requireNonNull(settings, "settings");
        JsonNode entry = entryBody == null || !entryBody.isObject() ? chatBody : entryBody;
        Set<String> degraded = new LinkedHashSet<>();
        ObjectNode body = FACTORY.objectNode();

        if (wireModel != null && !wireModel.isBlank()) {
            body.put("model", wireModel);
        }
        ArrayNode messages = messages(chatBody.path("messages"), settings, degraded);
        body.set("messages", messages);
        tokenLimit(chatBody, settings, body);
        number(chatBody, "temperature", body);
        number(chatBody, "top_p", body);
        stop(chatBody, body);
        Set<String> sentTools = new LinkedHashSet<>();
        Set<String> searchTools = toolSearchNames(entry);
        boolean tools = tools(chatBody.path("tools"), searchTools, body, sentTools, degraded);
        if (tools) {
            toolChoice(chatBody.path("tool_choice"), sentTools, searchTools, body, degraded);
            if (chatBody.path("parallel_tool_calls").isBoolean()) {
                body.put("parallel_tool_calls", chatBody.path("parallel_tool_calls").booleanValue());
            }
        }
        number(chatBody, "frequency_penalty", body);
        number(chatBody, "presence_penalty", body);
        if (chatBody.path("seed").isIntegralNumber()) {
            body.set("seed", chatBody.path("seed"));
        }
        responseFormat(chatBody.path("response_format"), settings, body, degraded);
        reasoning(chatBody, entry, messages, settings, body, degraded);
        if (settings.billedFastMode() && settings.priorityTier()) {
            body.put("service_tier", "priority");
        }
        body.put("stream", settings.upstreamStream());
        if (settings.upstreamStream()) {
            body.putObject("stream_options").put("include_usage", true);
        }
        return new Wire(body, List.copyOf(degraded));
    }

    // ---- messages ----

    private static ArrayNode messages(JsonNode input, Settings settings, Set<String> degraded) {
        ArrayNode messages = FACTORY.arrayNode();
        if (!input.isArray()) {
            return messages;
        }
        for (JsonNode message : input) {
            if (message.isObject()) {
                messages.add(message(message, settings, degraded));
            }
        }
        return messages;
    }

    private static ObjectNode message(JsonNode input, Settings settings, Set<String> degraded) {
        String role = input.path("role").stringValue(null);
        if ("developer".equals(role)) {
            role = "system";
        }
        ObjectNode message = FACTORY.objectNode();
        if (role != null) {
            message.put("role", role);
        }
        message.set("content", content(input.path("content"), "user".equals(role), settings, degraded));
        if ("assistant".equals(role) && input.path("tool_calls").isArray() && !input.path("tool_calls").isEmpty()) {
            message.set("tool_calls", toolCalls(input.path("tool_calls")));
        }
        if ("tool".equals(role) && input.path("tool_call_id").isString()) {
            message.set("tool_call_id", input.path("tool_call_id"));
        }
        cacheControl(input, message, settings, degraded);
        return message;
    }

    private static JsonNode content(JsonNode content, boolean imagesAllowed, Settings settings, Set<String> degraded) {
        if (content.isString()) {
            return content;
        }
        if (content.isArray()) {
            return parts(content, imagesAllowed, settings, degraded);
        }
        if (content.isObject()) {
            return parts(FACTORY.arrayNode().add(content), imagesAllowed, settings, degraded);
        }
        if (content.isNumber() || content.isBoolean()) {
            return FACTORY.stringNode(content.asString());
        }
        return FACTORY.nullNode();
    }

    private static ArrayNode parts(JsonNode input, boolean imagesAllowed, Settings settings, Set<String> degraded) {
        ArrayNode parts = FACTORY.arrayNode();
        for (JsonNode part : input) {
            if (part.isString()) {
                textPart(part, null, parts, settings, degraded);
                continue;
            }
            if (!part.isObject()) {
                continue;
            }
            String type = part.path("type").stringValue(part.has("text") ? "text" : "");
            switch (type) {
                case "text", "input_text", "output_text" -> textPart(part.path("text"), part, parts, settings, degraded);
                case "image_url" -> imagePart(part, imagesAllowed && settings.vision(), parts, settings, degraded);
                case "input_audio", "audio" -> placeholder(AUDIO_PLACEHOLDER, AUDIO, parts, degraded);
                case "file" -> placeholder(FILE_PLACEHOLDER, FILE, parts, degraded);
                default -> {
                    // A part nothing in a relay's request can be: a refusal, a thinking part, a block of another API.
                }
            }
        }
        return parts;
    }

    /** A text part; one without text is left out, since relays that forward to Anthropic refuse an empty block. */
    private static void textPart(JsonNode text, JsonNode source, ArrayNode parts, Settings settings, Set<String> degraded) {
        String value = text.isString() ? text.stringValue() : text.isNumber() || text.isBoolean() ? text.asString() : null;
        if (value == null || value.isBlank()) {
            return;
        }
        ObjectNode part = FACTORY.objectNode();
        part.put("type", "text");
        part.set("text", text.isString() ? text : FACTORY.stringNode(value));
        if (source != null) {
            cacheControl(source, part, settings, degraded);
        }
        parts.add(part);
    }

    private static void imagePart(
        JsonNode input,
        boolean carried,
        ArrayNode parts,
        Settings settings,
        Set<String> degraded
    ) {
        JsonNode image = input.path("image_url");
        String url = image.isObject() ? image.path("url").stringValue(null) : image.stringValue(null);
        if (!carried || url == null || url.isBlank()) {
            placeholder(IMAGE_PLACEHOLDER, IMAGE, parts, degraded);
            return;
        }
        ObjectNode part = FACTORY.objectNode();
        part.put("type", "image_url");
        ObjectNode target = part.putObject("image_url");
        target.put("url", url);
        if (image.isObject() && image.path("detail").isString()) {
            target.set("detail", image.path("detail"));
        }
        cacheControl(input, part, settings, degraded);
        parts.add(part);
    }

    private static void placeholder(String text, String feature, ArrayNode parts, Set<String> degraded) {
        ObjectNode part = FACTORY.objectNode();
        part.put("type", "text");
        part.put("text", text);
        parts.add(part);
        degraded.add(feature);
    }

    private static void cacheControl(JsonNode source, ObjectNode target, Settings settings, Set<String> degraded) {
        JsonNode control = source.path("cache_control");
        if (control.isMissingNode() || control.isNull()) {
            return;
        }
        if (settings.cacheControlPassthrough()) {
            ObjectNode rebuilt = FACTORY.objectNode();
            rebuilt.put("type", "ephemeral");
            JsonNode ttl = control.path("ttl");
            if (ttl.isString() && !ttl.stringValue().isBlank() && ttl.stringValue().length() <= MAX_TTL_LENGTH) {
                rebuilt.put("ttl", ttl.stringValue());
            }
            target.set("cache_control", rebuilt);
        } else {
            degraded.add(CACHE_CONTROL);
        }
    }

    private static ArrayNode toolCalls(JsonNode input) {
        ArrayNode calls = FACTORY.arrayNode();
        for (JsonNode call : input) {
            if (!call.isObject()) {
                continue;
            }
            ObjectNode converted = FACTORY.objectNode();
            converted.put("id", call.path("id").stringValue(""));
            converted.put("type", "function");
            JsonNode function = call.path("function");
            ObjectNode target = converted.putObject("function");
            target.put("name", function.path("name").stringValue(""));
            JsonNode arguments = function.path("arguments");
            target.set("arguments", arguments.isString() ? arguments
                : FACTORY.stringNode(arguments.isMissingNode() || arguments.isNull() ? "" : arguments.toString()));
            calls.add(converted);
        }
        return calls;
    }

    // ---- the rest of the body ----

    private static void tokenLimit(JsonNode chat, Settings settings, ObjectNode body) {
        for (String name : new String[] {"max_completion_tokens", "max_tokens", "max_output_tokens"}) {
            JsonNode value = chat.path(name);
            if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0) {
                body.set(settings.maxCompletionTokens() ? "max_completion_tokens" : "max_tokens", value);
                return;
            }
        }
    }

    private static void number(JsonNode source, String name, ObjectNode body) {
        if (source.path(name).isNumber()) {
            body.set(name, source.path(name));
        }
    }

    /** {@code stop} or Anthropic's {@code stop_sequences}: strings only, the empty ones left out, none when none is left. */
    private static void stop(JsonNode chat, ObjectNode body) {
        JsonNode stop = chat.hasNonNull("stop") ? chat.path("stop") : chat.path("stop_sequences");
        if (stop.isString()) {
            if (!stop.stringValue().isEmpty()) {
                body.set("stop", stop);
            }
            return;
        }
        if (!stop.isArray()) {
            return;
        }
        ArrayNode sequences = FACTORY.arrayNode();
        for (JsonNode sequence : stop) {
            if (sequence.isString() && !sequence.stringValue().isEmpty()) {
                sequences.add(sequence);
            }
        }
        if (!sequences.isEmpty()) {
            body.set("stop", sequences);
        }
    }

    /**
     * Returns whether the body has tools. A tool of another type than function is kept as it is, for the lint to refuse.
     * The tools of {@code searchTools} (see the class description) are left out and recorded, and the names of the
     * functions that are sent are added to {@code sent}.
     */
    private static boolean tools(JsonNode input, Set<String> searchTools, ObjectNode body, Set<String> sent, Set<String> degraded) {
        if (!input.isArray() || input.isEmpty()) {
            return false;
        }
        ArrayNode tools = FACTORY.arrayNode();
        for (JsonNode tool : input) {
            if (!tool.isObject()) {
                continue;
            }
            String type = tool.path("type").stringValue("function");
            if (!"function".equals(type) || !tool.path("function").isObject()) {
                tools.add(tool);
                continue;
            }
            JsonNode function = tool.path("function");
            String name = function.path("name").stringValue("");
            if (searchTools.contains(name)) {
                degraded.add(TOOL_SEARCH);
                continue;
            }
            ObjectNode converted = FACTORY.objectNode();
            converted.put("type", "function");
            ObjectNode target = converted.putObject("function");
            target.put("name", name);
            if (function.path("description").isString()) {
                target.set("description", function.path("description"));
            }
            if (function.path("parameters").isObject()) {
                target.set("parameters", function.path("parameters"));
            }
            if (function.path("strict").isBoolean()) {
                target.set("strict", function.path("strict"));
            }
            sent.add(name);
            tools.add(converted);
        }
        if (tools.isEmpty()) {
            return false;
        }
        body.set("tools", tools);
        return true;
    }

    /**
     * The names of the tools of the original request that are Anthropic's tool search ({@code tool_search_tool_regex_*},
     * {@code tool_search_tool_bm25_*}): what the conversion to Chat made a function of. A tool without a name is found by
     * the empty name it was converted with.
     */
    private static Set<String> toolSearchNames(JsonNode entry) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode tools = entry.path("tools");
        if (!tools.isArray()) {
            return names;
        }
        for (JsonNode tool : tools) {
            if (tool.isObject() && tool.path("type").stringValue("").toLowerCase(Locale.ROOT).startsWith("tool_search_tool")) {
                names.add(tool.path("name").stringValue(""));
            }
        }
        return names;
    }

    /**
     * One of the strings a relay knows, or the function of a tool that is sent; nothing else is passed on, since a relay
     * is handed whatever else the client wrote there (the lint, which only looks at the keys of the body, would not see it).
     */
    private static void toolChoice(JsonNode choice, Set<String> sent, Set<String> searchTools, ObjectNode body, Set<String> degraded) {
        if (choice.isMissingNode() || choice.isNull()) {
            return;
        }
        if (!isRelayToolChoice(choice)) {
            degraded.add(TOOL_CHOICE);
            return;
        }
        if (choice.isString()) {
            body.set("tool_choice", choice);
            return;
        }
        String name = choice.path("function").path("name").stringValue("");
        if (sent.contains(name)) {
            ObjectNode converted = body.putObject("tool_choice");
            converted.put("type", "function");
            converted.putObject("function").put("name", name);
            return;
        }
        if (searchTools.contains(name)) {
            // It went with the tool, which is recorded.
            return;
        }
        degraded.add(TOOL_CHOICE);
    }

    /**
     * Whether a {@code tool_choice} is of a kind a relay can be given: one of the strings {@code auto}, {@code none} and
     * {@code required}, or {@code {type:function}}. Any other (the {@code allowed_tools} of a client of the OpenAI API,
     * which restricts the model to some of the tools it was offered, or a type nobody converted) is left out, which the
     * feature detector names from the original request by asking this same question.
     */
    static boolean isRelayToolChoice(JsonNode choice) {
        if (choice.isString()) {
            return TOOL_CHOICE_STRINGS.contains(choice.stringValue());
        }
        return choice.isObject() && "function".equals(choice.path("type").stringValue(null));
    }

    /** {@code json_schema} only for a member that has it; for one that has not, the lower {@code json_object}. */
    private static void responseFormat(JsonNode format, Settings settings, ObjectNode body, Set<String> degraded) {
        if (!format.isObject()) {
            return;
        }
        String type = format.path("type").stringValue("");
        switch (type) {
            case "json_schema" -> {
                if (settings.jsonSchema() && format.path("json_schema").isObject()) {
                    ObjectNode converted = body.putObject("response_format");
                    converted.put("type", "json_schema");
                    converted.set("json_schema", format.path("json_schema"));
                } else {
                    body.putObject("response_format").put("type", "json_object");
                    degraded.add(JSON_SCHEMA);
                }
            }
            case "json_object", "text" -> body.putObject("response_format").put("type", type);
            default -> {
                // Another kind of format is not one a relay was promised.
            }
        }
    }

    // ---- reasoning ----

    /**
     * What the client asked for. The feature detector reads it through {@link #requestedReasoning}, so that the policy
     * and this class cannot disagree about whether reasoning was asked for.
     *
     * @param effort the level to send, or null when the client asked for reasoning at a level that has no name here
     */
    record Requested(String effort, String feature) {
    }

    private static void reasoning(
        JsonNode chat,
        JsonNode entry,
        ArrayNode messages,
        Settings settings,
        ObjectNode body,
        Set<String> degraded
    ) {
        Requested requested = requestedReasoning(chat, entry);
        if (requested == null) {
            return;
        }
        if (requested.effort() == null || !settings.reasoningEffort()
            || (settings.continuationDropsReasoning() && toolContinuation(messages))) {
            degraded.add(requested.feature());
            return;
        }
        body.put("reasoning_effort", requested.effort());
    }

    /** What the client asked for, as the effort to send, or null when it asked for no reasoning or nothing usable. */
    static Requested requestedReasoning(JsonNode chat, JsonNode entry) {
        JsonNode effort = chat.path("reasoning_effort");
        if (effort.isString() && !effort.stringValue().isBlank()) {
            return new Requested(effort.stringValue(), REASONING_EFFORT);
        }
        JsonNode thinking = entry.path("thinking").isObject() ? entry.path("thinking") : chat.path("thinking");
        if (thinking.isObject()) {
            String type = thinking.path("type").stringValue("").toLowerCase(Locale.ROOT);
            if ("disabled".equals(type)) {
                return null;
            }
            if ("adaptive".equals(type)) {
                JsonNode outputConfig = entry.path("output_config").isObject()
                    ? entry.path("output_config") : chat.path("output_config");
                JsonNode given = outputConfig.path("effort");
                String mapped = given.isString() ? anthropicEffort(given.stringValue()) : "high";
                return mapped == null ? new Requested(null, THINKING) : new Requested(mapped, THINKING);
            }
            JsonNode budget = thinking.path("budget_tokens");
            if (budget.isNumber()) {
                return budget.longValue() > 0 ? new Requested(bucket(budget.longValue()), THINKING) : null;
            }
            return "enabled".equals(type) ? new Requested("medium", THINKING) : null;
        }
        JsonNode config = entry.path("generationConfig").path("thinkingConfig");
        if (!config.isObject()) {
            config = entry.path("thinkingConfig");
        }
        if (config.isObject()) {
            JsonNode budget = config.path("thinkingBudget");
            if (budget.isNumber() && budget.longValue() > 0) {
                return new Requested(bucket(budget.longValue()), THINKING);
            }
            String level = config.path("thinkingLevel").stringValue("").toLowerCase(Locale.ROOT);
            if (Set.of("low", "medium", "high").contains(level)) {
                return new Requested(level, THINKING);
            }
        }
        return null;
    }

    /** {@code low}, {@code medium} and {@code high} as they are, {@code max} and {@code xhigh} as high, others none. */
    private static String anthropicEffort(String effort) {
        return switch (effort.strip().toLowerCase(Locale.ROOT)) {
            case "low" -> "low";
            case "medium" -> "medium";
            case "high", "xhigh", "max" -> "high";
            default -> null;
        };
    }

    private static String bucket(long budgetTokens) {
        return budgetTokens < MEDIUM_FROM_BUDGET ? "low" : budgetTokens < HIGH_FROM_BUDGET ? "medium" : "high";
    }

    /**
     * Whether the model is to continue after tool results: the last message that is not the user's is a tool message.
     * A tool-result message with text of the client's own converts to tool messages followed by a user message, and
     * is the same turn.
     */
    private static boolean toolContinuation(ArrayNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            String role = messages.get(i).path("role").stringValue("");
            if (!"user".equals(role)) {
                return "tool".equals(role);
            }
        }
        return false;
    }
}
