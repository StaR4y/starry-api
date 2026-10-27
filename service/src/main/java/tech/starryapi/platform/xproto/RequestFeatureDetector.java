package tech.starryapi.platform.xproto;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import tech.starryapi.platform.openai.ApplyPatchEmulation;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Requested;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Finds out what a client's request asks for that an OpenAI-format relay may not be able to give (design 7.9). It reads
 * the request <em>as the client sent it</em>, not the Chat body the entry converts it to, because the conversion already
 * lost some of it ({@code messagesToChat} drops {@code output_config}, server tools and images without a word) and the
 * policy has to name what was lost. It says what is there and nothing about any relay: {@link FeaturePolicy} decides what
 * each member makes of it, so the body is read once per request whatever the number of members.
 *
 * <p>The result holds no reference to the body. The body of a Messages or Gemini request may carry large base64 images,
 * and the facade must not keep it alive in the reactive chain (the comment at {@code ProxyFacade.invokeChat}): it
 * detects before the chain is built and captures this record.
 *
 * <p>Detection is structural. A feature is found where the wire format puts it (a block of a message, a tool of {@code
 * tools}, a field of the body) and never by looking for its name in text, so a tool input that happens to have a {@code
 * cache_control} key, or a user who asks about {@code web_search}, is not a feature. Anything the client sent that this
 * class has no name for is left unnamed: the sanitizer's whitelist keeps it from the relay, and that is only honest where
 * the native path drops it too. A route whose native accounts are handed the body whole ({@link
 * FeaturePolicy.Baseline#PASSTHROUGH}) passes on every field of the Chat API, so each field of it that the sanitizer
 * does not send has a name here, even one that only the log will carry ({@code verbosity}, {@code logit_bias} and the
 * cache and safety identifiers of the newer models among them); the sanitizer's own list of what it sends and the
 * features of the Chat entry are kept joined by {@code FeaturePolicySanitizerTest}. Headers are not read, since none
 * reaches a relay.
 *
 * <p>Three entries are known, the ones of design 7.9 that this release can convert: {@link Entry#MESSAGES} (the Anthropic
 * request of Claude Code), {@link Entry#CHAT} and {@link Entry#RESPONSES} (the request of Codex, as the gateway has
 * expanded it, see {@link #detect}). Any other entry is reported as {@link Feature#UNSUPPORTED_ENTRY}, which no member
 * can carry, so that a caller that asks for an entry whose table has not been written cannot send a request to a relay
 * unchecked. The Gemini table joins with the task that writes its converter.
 *
 * <p>The body is not changed, and the detector keeps no state.
 */
public final class RequestFeatureDetector {

    /**
     * Something a request asks for that a relay may not take. The label is how the request log, the skip reasons of the
     * status page and the {@code SOURCE_TIER_FEATURE_UNSUPPORTED} error name it; the ones the sanitizer also records
     * when it leaves something out ({@link ChatWireSanitizer.Wire#degraded()}) have the sanitizer's own words, so the two lists
     * can be joined without duplicates.
     *
     * <p>What each feature means for a member is {@link FeaturePolicy}'s to say. They are in the order the policy
     * lists them: the ones no relay carries, the ones a member's switches decide, then the ones that are only left out.
     */
    public enum Feature {
        // ---- no relay carries these: the member is left out ----
        /** More than four stop sequences, which is as many as the OpenAI format promises. */
        STOP_SEQUENCES_OVER_4("stop_sequences"),
        /** {@code n} above one: only the first choice is ever returned. */
        N_OVER_ONE("n"),
        WEB_SEARCH_OPTIONS("web_search_options"),
        /** The legacy {@code functions}, {@code function_call} and {@code function} role. */
        LEGACY_FUNCTIONS("functions"),
        /** A tool of a Chat request whose type is not {@code function}. */
        NON_FUNCTION_TOOL("non_function_tool"),
        /** The tools Anthropic runs or defines: the native path turns them into functions nobody can answer. */
        WEB_SEARCH("web_search"),
        WEB_FETCH("web_fetch"),
        CODE_EXECUTION("code_execution"),
        BASH_TOOL("bash"),
        TEXT_EDITOR_TOOL("text_editor"),
        COMPUTER_TOOL("computer"),
        MEMORY_TOOL("memory"),
        /** An Anthropic-defined tool of a type this list has no name for. */
        ANTHROPIC_DEFINED_TOOL("anthropic_defined_tool"),
        MCP_SERVERS("mcp_servers"),
        CONTAINER("container"),
        /**
         * A Responses request that continues a response this node could not expand into its history: the relay would be
         * given the last turn alone. A native Kiro route does the same as it always did (design 4.8, 7.9).
         */
        PREVIOUS_RESPONSE_ID("previous_response_id"),
        /**
         * Context of a Responses request that the server holds and the request only points at: a {@code conversation}, a
         * stored {@code prompt}, an {@code item_reference} in the input, a {@code compaction} item (the history Codex
         * summarised into text only OpenAI can read). The conversion leaves all of them out, so a relay would answer
         * without the context and be paid for it. Native OpenAI accounts are handed the body whole and resolve them.
         */
        SERVER_HELD_CONTEXT("server_held_context"),
        /** A custom tool of a Responses request other than {@code apply_patch}: a relay's functions cannot stand for its calls. */
        CUSTOM_TOOL("custom_tool"),
        /** The request is billed as fast: only a member with a priority tier gives what is paid for. Never detected from the body. */
        FAST("fast"),
        /** The entry has no table in this release. */
        UNSUPPORTED_ENTRY("entry"),

        // ---- a member's switches decide ----
        IMAGE(ChatWireSanitizer.IMAGE),
        DOCUMENT("document"),
        AUDIO(ChatWireSanitizer.AUDIO),
        FILE(ChatWireSanitizer.FILE),
        /**
         * A tool of a Responses request that is neither a function nor {@code apply_patch}: the ones OpenAI runs ({@code
         * web_search}, {@code file_search}, {@code code_interpreter}, {@code image_generation}, {@code computer_use}, {@code
         * mcp}), and the ones the client's tool search adds ({@code tool_search}, {@code namespace}). A relay is given
         * none of them. Codex 0.142.5 declares a {@code web_search} whatever the model, so what leaving it out comes to
         * is the baseline's to say (design 7.9, {@link FeaturePolicy}).
         */
        BUILTIN_TOOL("builtin_tool"),
        /**
         * An image in the output of a tool ({@code view_image} of Codex), which the conversion to Chat has no place for:
         * the text of the output is all a tool message holds.
         */
        TOOL_OUTPUT_IMAGE("tool_output_image"),
        /** A {@code tool_choice} of a kind the relay's format has no word for ({@code allowed_tools}): the model would be offered every tool. */
        TOOL_CHOICE(ChatWireSanitizer.TOOL_CHOICE),
        JSON_SCHEMA(ChatWireSanitizer.JSON_SCHEMA),
        CACHE_CONTROL(ChatWireSanitizer.CACHE_CONTROL),
        /** The Anthropic {@code thinking} parameter, with the {@code output_config.effort} that sets its level. */
        THINKING(ChatWireSanitizer.THINKING),
        /** The {@code reasoning_effort} of a Chat request. */
        REASONING_EFFORT(ChatWireSanitizer.REASONING_EFFORT),
        TOOL_RESULT_ERROR("tool_result_error"),
        /** The {@code logit_bias} of a Chat request: the answer is made from other probabilities if a relay leaves it out. */
        LOGIT_BIAS("logit_bias"),

        // ---- only left out ----
        /** A {@code tool_search_tool_*}: the tool is taken out and the deferred tools are sent as plain functions. */
        TOOL_SEARCH("tool_search_tool"),
        CONTEXT_MANAGEMENT("context_management"),
        /** {@code thinking} and {@code redacted_thinking} blocks of earlier turns. */
        HISTORY_THINKING("thinking_history"),
        /** Blocks of earlier turns that record a tool Anthropic ran ({@code server_tool_use}, {@code web_search_tool_result}). */
        SERVER_TOOL_HISTORY("server_tool_history"),
        /** An {@code output_config.effort} that is not what sets the level of an adaptive thinking. */
        OUTPUT_CONFIG("output_config"),
        TOP_K("top_k"),
        METADATA("metadata"),
        SERVICE_TIER("service_tier"),
        /**
         * The {@code verbosity} of a Chat request (GPT-5's control of the length of the answer) when it is {@code low} or
         * {@code high}: no relay is sent it, and the answer is recorded as made without it (design 7.9, D6). OpenCode sends
         * {@code low} with every request of a gpt model, so a relay that refused the request for it would never be given one.
         * {@code medium} is the default and is not a feature.
         */
        VERBOSITY("verbosity"),
        /** The {@code include} of a Responses request: what the answer is to carry besides itself (Codex asks for its encrypted reasoning). */
        INCLUDE("include"),
        /** A {@code truncation} of a Responses request other than the default, {@code disabled}. */
        TRUNCATION("truncation"),
        /** The {@code max_tool_calls} of a Responses request: a cap on the calls of the tools OpenAI runs, which no relay is given. */
        MAX_TOOL_CALLS("max_tool_calls"),
        /** The {@code reasoning.summary} of a Responses request: a relay returns its reasoning as it has it, not as a summary. */
        REASONING_SUMMARY("reasoning_summary"),
        LOGPROBS("logprobs"),
        USER("user"),
        STORE("store"),
        PREDICTION("prediction"),
        /** The {@code prompt_cache_key} and {@code prompt_cache_retention} of a Chat request: advice about the cache, not about the answer. */
        PROMPT_CACHE_KEY("prompt_cache_key"),
        PROMPT_CACHE_RETENTION("prompt_cache_retention"),
        /** The {@code safety_identifier} of a Chat request: the id of the end user, which is never passed on (design 7.12). */
        SAFETY_IDENTIFIER("safety_identifier"),
        /** A request for output other than text, or the {@code audio} parameter that goes with it. */
        MODALITIES("modalities");

        private final String label;

        Feature(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * What a request asks for.
     *
     * @param entry             the endpoint the request came in through
     * @param features          every feature found, once
     * @param toolContinuation  the model is to continue after tool results (the request ends with tool results,
     *                          possibly followed by user text of the same client message), a turn that needs the
     *                          thinking of the one before and so goes to a relay without reasoning
     * @param reasoningUnmapped the client asked for reasoning at a level no relay setting has a name for (an {@code
     *                          output_config.effort} of a kind this release does not know): it is left out, whatever
     *                          the member
     * @param imageNotCarried   one of the images of the request is an image the conversion to Chat has no place for, so
     *                          that no member can carry {@link Feature#IMAGE} whatever its vision setting: an image given
     *                          only by its {@code file_id} (a relay cannot read the files of OpenAI), or in a message that
     *                          is not the user's (the text is all the conversion keeps of a developer message)
     */
    public record Detected(Entry entry, Set<Feature> features, boolean toolContinuation, boolean reasoningUnmapped,
                           boolean imageNotCarried) {

        public Detected {
            Objects.requireNonNull(entry, "entry");
            features = features.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(features));
        }

        public Detected(Entry entry, Set<Feature> features, boolean toolContinuation, boolean reasoningUnmapped) {
            this(entry, features, toolContinuation, reasoningUnmapped, false);
        }

        public boolean has(Feature feature) {
            return features.contains(feature);
        }
    }

    /** Fields of the Chat conversion of a Messages request that the conversion does not have, as a stand-in for it. */
    private static final JsonNode NO_CHAT_FIELDS = JsonNodeFactory.instance.objectNode();

    private RequestFeatureDetector() {
    }

    /**
     * @param entry the endpoint the request came in through
     * @param body  the request as the client sent it: the Anthropic body for Messages, the Chat body for Chat, and for
     *              Responses the body after the gateway expanded its {@code previous_response_id} into the input
     *              ({@code OpenAiResponseStateStore#expand}), so that one that is still there is one this node could not
     *              expand
     */
    public static Detected detect(Entry entry, JsonNode body) {
        Objects.requireNonNull(entry, "entry");
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("The request body must be a JSON object");
        }
        return switch (entry) {
            case MESSAGES -> messages(body);
            case CHAT -> chat(body);
            case RESPONSES -> responses(body);
            case GEMINI -> new Detected(entry, EnumSet.of(Feature.UNSUPPORTED_ENTRY), false, false);
        };
    }

    // ---- Messages ----

    private static Detected messages(JsonNode body) {
        EnumSet<Feature> found = EnumSet.noneOf(Feature.class);
        if (filled(body.path("mcp_servers"))) {
            found.add(Feature.MCP_SERVERS);
        }
        if (filled(body.path("container"))) {
            found.add(Feature.CONTAINER);
        }
        if (stopSequences(body.path("stop_sequences")) > StrictChatLint.MAX_STOP_SEQUENCES) {
            found.add(Feature.STOP_SEQUENCES_OVER_4);
        }
        if (body.hasNonNull("top_k")) {
            found.add(Feature.TOP_K);
        }
        if (filled(body.path("metadata"))) {
            found.add(Feature.METADATA);
        }
        if (body.hasNonNull("service_tier")) {
            found.add(Feature.SERVICE_TIER);
        }
        if (filled(body.path("context_management"))) {
            found.add(Feature.CONTEXT_MANAGEMENT);
        }
        if (body.hasNonNull("cache_control")) {
            found.add(Feature.CACHE_CONTROL);
        }
        if (jsonSchemaFormat(body.path("output_config").path("format")) || jsonSchemaFormat(body.path("output_format"))) {
            found.add(Feature.JSON_SCHEMA);
        }
        boolean unmapped = reasoning(NO_CHAT_FIELDS, body, found);
        if (body.path("output_config").path("effort").isString() && !adaptive(body.path("thinking"))) {
            found.add(Feature.OUTPUT_CONFIG);
        }
        system(body.path("system"), found);
        anthropicTools(body.path("tools"), found);
        JsonNode messages = body.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                anthropicMessage(message, found);
            }
        }
        return new Detected(Entry.MESSAGES, found, anthropicToolContinuation(messages), unmapped);
    }

    private static void system(JsonNode system, Set<Feature> found) {
        if (!system.isArray()) {
            return;
        }
        for (JsonNode block : system) {
            if (block.isObject() && block.hasNonNull("cache_control")) {
                found.add(Feature.CACHE_CONTROL);
            }
        }
    }

    /**
     * A tool without a type, or of type {@code custom}, is the client's own function. Any other type is one Anthropic
     * defines: its family decides which feature it is, and the {@code tool_search_tool_*} family is the one a request
     * may keep going without (design 7.9).
     */
    private static void anthropicTools(JsonNode tools, Set<Feature> found) {
        if (!tools.isArray()) {
            return;
        }
        for (JsonNode tool : tools) {
            if (!tool.isObject()) {
                continue;
            }
            if (tool.hasNonNull("cache_control")) {
                found.add(Feature.CACHE_CONTROL);
            }
            String type = tool.path("type").stringValue("").toLowerCase(Locale.ROOT);
            if (!type.isEmpty() && !"custom".equals(type)) {
                found.add(anthropicToolFeature(type));
            }
        }
    }

    private static Feature anthropicToolFeature(String type) {
        if (type.startsWith("tool_search_tool")) {
            return Feature.TOOL_SEARCH;
        }
        if (type.startsWith("web_search")) {
            return Feature.WEB_SEARCH;
        }
        if (type.startsWith("web_fetch")) {
            return Feature.WEB_FETCH;
        }
        if (type.startsWith("code_execution")) {
            return Feature.CODE_EXECUTION;
        }
        if (type.startsWith("bash")) {
            return Feature.BASH_TOOL;
        }
        if (type.startsWith("text_editor")) {
            return Feature.TEXT_EDITOR_TOOL;
        }
        if (type.startsWith("computer")) {
            return Feature.COMPUTER_TOOL;
        }
        if (type.startsWith("memory")) {
            return Feature.MEMORY_TOOL;
        }
        return Feature.ANTHROPIC_DEFINED_TOOL;
    }

    private static void anthropicMessage(JsonNode message, Set<Feature> found) {
        JsonNode content = message.path("content");
        if (!content.isArray()) {
            return;
        }
        for (JsonNode block : content) {
            if (block.isObject()) {
                anthropicBlock(block, found, true);
            }
        }
    }

    /** @param topLevel false for a block inside a {@code tool_result}, which has no further tool results of its own */
    private static void anthropicBlock(JsonNode block, Set<Feature> found, boolean topLevel) {
        String type = block.path("type").stringValue("");
        switch (type) {
            case "text", "tool_use" -> cacheControl(block, found);
            case "image" -> {
                found.add(Feature.IMAGE);
                cacheControl(block, found);
            }
            case "document" -> {
                found.add(Feature.DOCUMENT);
                cacheControl(block, found);
            }
            case "tool_result" -> {
                if (!topLevel) {
                    return;
                }
                cacheControl(block, found);
                if (block.path("is_error").isBoolean() && block.path("is_error").booleanValue()) {
                    found.add(Feature.TOOL_RESULT_ERROR);
                }
                if (block.path("content").isArray()) {
                    for (JsonNode part : block.path("content")) {
                        if (part.isObject()) {
                            anthropicBlock(part, found, false);
                        }
                    }
                }
            }
            case "thinking", "redacted_thinking" -> found.add(Feature.HISTORY_THINKING);
            default -> {
                if (type.equals("server_tool_use") || type.equals("mcp_tool_use") || type.endsWith("_tool_result")) {
                    found.add(Feature.SERVER_TOOL_HISTORY);
                }
            }
        }
    }

    /**
     * The last turn is the answer to tool calls: going back from the end, a user message that holds a {@code
     * tool_result} comes before any assistant message. This is the conversion's own reading, which turns such a
     * message into tool messages followed by its text, so the sanitizer, which sees the converted body, agrees.
     */
    private static boolean anthropicToolContinuation(JsonNode messages) {
        if (!messages.isArray()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if ("assistant".equals(message.path("role").stringValue("user"))) {
                return false;
            }
            JsonNode content = message.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("tool_result".equals(block.path("type").stringValue(""))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- Chat ----

    private static Detected chat(JsonNode body) {
        EnumSet<Feature> found = EnumSet.noneOf(Feature.class);
        if (body.path("n").isIntegralNumber() && body.path("n").longValue() > 1) {
            found.add(Feature.N_OVER_ONE);
        }
        JsonNode stop = body.hasNonNull("stop") ? body.path("stop") : body.path("stop_sequences");
        if (stopSequences(stop) > StrictChatLint.MAX_STOP_SEQUENCES) {
            found.add(Feature.STOP_SEQUENCES_OVER_4);
        }
        if (body.hasNonNull("web_search_options")) {
            found.add(Feature.WEB_SEARCH_OPTIONS);
        }
        if (filled(body.path("functions")) || body.hasNonNull("function_call")) {
            found.add(Feature.LEGACY_FUNCTIONS);
        }
        chatTools(body.path("tools"), found);
        if (hasToolObject(body.path("tools")) && body.hasNonNull("tool_choice")
            && !ChatWireSanitizer.isRelayToolChoice(body.path("tool_choice"))) {
            found.add(Feature.TOOL_CHOICE);
        }
        if (jsonSchemaFormat(body.path("response_format"))) {
            found.add(Feature.JSON_SCHEMA);
        }
        if ((body.path("logprobs").isBoolean() && body.path("logprobs").booleanValue()) || body.hasNonNull("top_logprobs")) {
            found.add(Feature.LOGPROBS);
        }
        if (filled(body.path("user"))) {
            found.add(Feature.USER);
        }
        if (filled(body.path("metadata"))) {
            found.add(Feature.METADATA);
        }
        if (body.path("store").isBoolean() && body.path("store").booleanValue()) {
            found.add(Feature.STORE);
        }
        if (body.hasNonNull("prediction")) {
            found.add(Feature.PREDICTION);
        }
        if (outputModalities(body)) {
            found.add(Feature.MODALITIES);
        }
        if (body.hasNonNull("top_k")) {
            found.add(Feature.TOP_K);
        }
        if (otherServiceTier(body.path("service_tier"))) {
            found.add(Feature.SERVICE_TIER);
        }
        if (changesTheLength(body.path("verbosity"))) {
            found.add(Feature.VERBOSITY);
        }
        if (filled(body.path("logit_bias"))) {
            found.add(Feature.LOGIT_BIAS);
        }
        if (filled(body.path("prompt_cache_key"))) {
            found.add(Feature.PROMPT_CACHE_KEY);
        }
        if (filled(body.path("prompt_cache_retention"))) {
            found.add(Feature.PROMPT_CACHE_RETENTION);
        }
        if (filled(body.path("safety_identifier"))) {
            found.add(Feature.SAFETY_IDENTIFIER);
        }
        boolean unmapped = reasoning(body, body, found);
        JsonNode messages = body.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                chatMessage(message, found);
            }
        }
        return new Detected(Entry.CHAT, found, chatToolContinuation(messages), unmapped);
    }

    private static void chatTools(JsonNode tools, Set<Feature> found) {
        if (!tools.isArray()) {
            return;
        }
        for (JsonNode tool : tools) {
            if (tool.isObject() && !"function".equals(tool.path("type").stringValue("function"))) {
                found.add(Feature.NON_FUNCTION_TOOL);
            }
        }
    }

    /** The sanitizer reads a {@code tool_choice} only of a body that has tools, as relays refuse one without. */
    private static boolean hasToolObject(JsonNode tools) {
        if (!tools.isArray()) {
            return false;
        }
        for (JsonNode tool : tools) {
            if (tool.isObject()) {
                return true;
            }
        }
        return false;
    }

    private static void chatMessage(JsonNode message, Set<Feature> found) {
        if (!message.isObject()) {
            return;
        }
        if ("function".equals(message.path("role").stringValue("")) || message.hasNonNull("function_call")) {
            found.add(Feature.LEGACY_FUNCTIONS);
        }
        if (message.path("audio").isObject()) {
            found.add(Feature.AUDIO);
        }
        cacheControl(message, found);
        JsonNode content = message.path("content");
        if (content.isObject()) {
            chatPart(content, found);
        } else if (content.isArray()) {
            for (JsonNode part : content) {
                chatPart(part, found);
            }
        }
    }

    /** The part types the sanitizer reads, found the way it finds them: a part without a type but with text is text. */
    private static void chatPart(JsonNode part, Set<Feature> found) {
        if (!part.isObject()) {
            return;
        }
        switch (part.path("type").stringValue(part.has("text") ? "text" : "")) {
            case "text", "input_text", "output_text" -> cacheControl(part, found);
            case "image_url" -> {
                found.add(Feature.IMAGE);
                cacheControl(part, found);
            }
            case "input_audio", "audio" -> found.add(Feature.AUDIO);
            case "file" -> found.add(Feature.FILE);
            default -> {
                // A part nothing in a relay's request can be: the sanitizer leaves it out and no table names it.
            }
        }
    }

    /** The sanitizer's reading: going back from the end, the first message that is not the user's is a tool message. */
    private static boolean chatToolContinuation(JsonNode messages) {
        if (!messages.isArray()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            String role = messages.get(i).path("role").stringValue("");
            if (!"user".equals(role)) {
                return "tool".equals(role);
            }
        }
        return false;
    }

    /** Output other than text asked for with {@code modalities}, or an {@code audio} parameter for it. */
    private static boolean outputModalities(JsonNode body) {
        if (body.path("audio").isObject()) {
            return true;
        }
        JsonNode modalities = body.path("modalities");
        if (!modalities.isArray()) {
            return false;
        }
        for (JsonNode modality : modalities) {
            if (!"text".equals(modality.stringValue("text"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Any {@code service_tier} but the two that mean fast, which the facade turns into the billed fast mode and which
     * the policy takes from the member's settings: they are not left out, they are a request the member can meet or not.
     */
    private static boolean otherServiceTier(JsonNode tier) {
        if (!tier.isString() || tier.stringValue().isBlank()) {
            return false;
        }
        String normalized = tier.stringValue().strip().toLowerCase(Locale.ROOT);
        return !normalized.equals("priority") && !normalized.equals("fast");
    }

    // ---- Responses ----

    /**
     * What a Codex request asks for, read the way {@link ResponsesChatConverter} will convert it (design 7.9, RESPONSES):
     * a thing is a feature where the conversion does not carry it, and what the conversion carries is none, so that the
     * policy never promises a relay what the Chat body does not hold. The body is the expanded one, whose input holds
     * the history of its chain; a {@code previous_response_id} that is still in it is a chain this node could not expand,
     * which a relay cannot be given (design 4.8).
     */
    private static Detected responses(JsonNode body) {
        EnumSet<Feature> found = EnumSet.noneOf(Feature.class);
        if (filled(body.path("previous_response_id"))) {
            found.add(Feature.PREVIOUS_RESPONSE_ID);
        }
        if (heldByTheServer(body)) {
            found.add(Feature.SERVER_HELD_CONTEXT);
        }
        if (body.hasNonNull("max_tool_calls")) {
            found.add(Feature.MAX_TOOL_CALLS);
        }
        if (asksForReasoningSummary(body.path("reasoning").path("summary"))) {
            found.add(Feature.REASONING_SUMMARY);
        }
        if (filled(body.path("include"))) {
            found.add(Feature.INCLUDE);
        }
        if (body.path("store").isBoolean() && body.path("store").booleanValue()) {
            found.add(Feature.STORE);
        }
        JsonNode truncation = body.path("truncation");
        if (filled(truncation) && !"disabled".equalsIgnoreCase(truncation.stringValue("").strip())) {
            found.add(Feature.TRUNCATION);
        }
        if (filled(body.path("prompt_cache_key"))) {
            found.add(Feature.PROMPT_CACHE_KEY);
        }
        if (filled(body.path("prompt_cache_retention"))) {
            found.add(Feature.PROMPT_CACHE_RETENTION);
        }
        if (filled(body.path("safety_identifier"))) {
            found.add(Feature.SAFETY_IDENTIFIER);
        }
        if (filled(body.path("metadata"))) {
            found.add(Feature.METADATA);
        }
        if (filled(body.path("user"))) {
            found.add(Feature.USER);
        }
        if (body.hasNonNull("top_logprobs")) {
            found.add(Feature.LOGPROBS);
        }
        if (otherServiceTier(body.path("service_tier"))) {
            found.add(Feature.SERVICE_TIER);
        }
        JsonNode text = body.path("text");
        if (changesTheLength(text.path("verbosity"))) {
            found.add(Feature.VERBOSITY);
        }
        if (jsonSchemaFormat(text.path("format")) && text.path("format").path("schema").isObject()) {
            found.add(Feature.JSON_SCHEMA);
        }
        boolean unmapped = responsesReasoning(body.path("reasoning").path("effort"), found);
        responsesTools(body, found);
        JsonNode input = body.path("input");
        boolean[] imageNotCarried = new boolean[1];
        if (input.isArray()) {
            for (JsonNode item : input) {
                if (item.isObject()) {
                    responsesItem(item, found, imageNotCarried);
                }
            }
        }
        return new Detected(Entry.RESPONSES, found, responsesToolContinuation(input), unmapped, imageNotCarried[0]);
    }

    /**
     * Whether the request points at context that lives on OpenAI's side: a {@code conversation} (an id, or an object
     * that has one) or a stored {@code prompt}. The items of the input that do the same are found with the input
     * ({@link #responsesItem}). The conversion carries none of them.
     */
    private static boolean heldByTheServer(JsonNode body) {
        JsonNode conversation = body.path("conversation");
        return filled(conversation.isObject() ? conversation.path("id") : conversation) || filled(body.path("prompt"));
    }

    /** {@code none} is how a client configures that it wants no summary; every other value asks for one. */
    private static boolean asksForReasoningSummary(JsonNode summary) {
        return filled(summary) && !(summary.isString() && summary.stringValue().strip().equalsIgnoreCase("none"));
    }

    /** The effort of {@code reasoning} reaches the sanitizer as the {@code reasoning_effort} of the Chat body, so it is asked the same way. */
    private static boolean responsesReasoning(JsonNode effort, Set<Feature> found) {
        if (!effort.isString()) {
            return false;
        }
        ObjectNode chat = JsonNodeFactory.instance.objectNode();
        chat.set("reasoning_effort", effort);
        return reasoning(chat, NO_CHAT_FIELDS, found);
    }

    /**
     * The tools are the functions, which are carried, {@code apply_patch}, which the conversion gives as a function (the
     * emulation of the native Codex path), and everything else, which it leaves out: a custom tool is HARD on every
     * baseline (its calls come back as functions the client would not understand), the rest is the baseline's to judge. The
     * {@code tool_choice} is a feature when it is of a shape a relay has no word for, or names a tool that is not carried;
     * like the conversion, this reads it only in a request that has a function to choose from.
     */
    private static void responsesTools(JsonNode body, Set<Feature> found) {
        Set<String> carried = new HashSet<>();
        JsonNode tools = body.path("tools");
        if (tools.isArray()) {
            for (JsonNode tool : tools) {
                if (!tool.isObject()) {
                    continue;
                }
                String type = tool.path("type").stringValue("function");
                boolean function = "function".equals(type) || type.isBlank();
                // The conversion also takes the Chat shape of a function, with the function nested.
                JsonNode source = tool.path("function").isObject() ? tool.path("function") : tool;
                String name = source.path("name").stringValue("");
                if (ApplyPatchEmulation.TOOL_NAME.equals(name) && (function || "custom".equals(type))) {
                    carried.add(name);
                } else if (function) {
                    if (!name.isBlank()) {
                        carried.add(name);
                    }
                } else {
                    found.add("custom".equals(type) ? Feature.CUSTOM_TOOL : Feature.BUILTIN_TOOL);
                }
            }
        }
        JsonNode choice = body.path("tool_choice");
        if (!carried.isEmpty() && !choice.isMissingNode() && !choice.isNull() && !carriedChoice(choice, carried)) {
            found.add(Feature.TOOL_CHOICE);
        }
    }

    /**
     * Whether the sanitizer will find the choice in a form it can pass on: one of the strings a relay knows, or a tool of
     * the request that is carried (the editing tool whatever type the choice gives it, as the conversion has it).
     */
    private static boolean carriedChoice(JsonNode choice, Set<String> carried) {
        if (!choice.isObject()) {
            return ChatWireSanitizer.isRelayToolChoice(choice);
        }
        String name = ApplyPatchEmulation.toolChoiceName(choice);
        return carried.contains(name)
            && (ApplyPatchEmulation.TOOL_NAME.equals(name) || "function".equals(choice.path("type").stringValue("")));
    }

    /**
     * The parts of the input the conversion has a place for, the reasoning of earlier turns, which it has not, and the items
     * that only point at what the server holds.
     */
    private static void responsesItem(JsonNode item, Set<Feature> found, boolean[] imageNotCarried) {
        switch (item.path("type").stringValue(item.has("role") ? "message" : "")) {
            case "message" -> {
                // Only the message of the user holds parts: what the assistant, the developer or the system wrote is its text,
                // and an image given to one of them is lost.
                boolean user = "user".equals(item.path("role").stringValue("user"));
                responsesParts(item.path("content"), found, Feature.IMAGE, user, imageNotCarried);
            }
            case "function_call_output", "custom_tool_call_output" ->
                responsesParts(item.path("output"), found, Feature.TOOL_OUTPUT_IMAGE, false, null);
            case "reasoning" -> found.add(Feature.HISTORY_THINKING);
            case "item_reference", "compaction", "compaction_summary" -> found.add(Feature.SERVER_HELD_CONTEXT);
            default -> {
                // The calls of the tools and the other items of earlier turns that the conversion leaves out are not named.
            }
        }
    }

    /**
     * @param image what an image of these parts is
     * @param user whether the parts are the user's, which the conversion keeps images of (an image that names a URL: a relay
     *     cannot read an OpenAI file)
     * @param imageNotCarried set when an image of these parts is one the conversion has no place for, whatever the member;
     *     null where {@code image} already says it (the image in the output of a tool)
     */
    private static void responsesParts(JsonNode parts, Set<Feature> found, Feature image, boolean user, boolean[] imageNotCarried) {
        if (!parts.isArray()) {
            return;
        }
        for (JsonNode part : parts) {
            switch (part.path("type").stringValue("")) {
                case "input_image" -> {
                    found.add(image);
                    if (imageNotCarried != null && !(user && ResponsesChatConverter.hasImageUrl(part))) {
                        imageNotCarried[0] = true;
                    }
                }
                case "input_file" -> found.add(Feature.FILE);
                case "input_audio" -> found.add(Feature.AUDIO);
                default -> {
                    // Text is carried.
                }
            }
        }
    }

    /**
     * The conversion's reading of the end of the conversation, which the sanitizer reads again in the Chat body: what the
     * conversion leaves out makes no message, a message of the user is looked through, and the answer to a call is a tool
     * message while anything else ends the search.
     */
    private static boolean responsesToolContinuation(JsonNode input) {
        if (!input.isArray()) {
            return false;
        }
        for (int i = input.size() - 1; i >= 0; i--) {
            JsonNode item = input.get(i);
            if (!item.isObject()) {
                continue;
            }
            switch (item.path("type").stringValue(item.has("role") ? "message" : "")) {
                case "message" -> {
                    switch (item.path("role").stringValue("user")) {
                        case "user" -> {
                            // The turn is not over until the end of what the user said.
                        }
                        case "assistant", "system", "developer" -> {
                            return false;
                        }
                        default -> {
                            // A role the Responses API does not have makes no message.
                        }
                    }
                }
                case "function_call", "custom_tool_call" -> {
                    return false;
                }
                case "function_call_output", "custom_tool_call_output" -> {
                    return true;
                }
                default -> {
                    // reasoning, item_reference and the rest make no message.
                }
            }
        }
        return false;
    }

    // ---- both ----

    /**
     * Asks the sanitizer what the client asked for, so that the two cannot disagree about it, and records the
     * feature the sanitizer would record. Returns whether the level asked for has no name here.
     */
    private static boolean reasoning(JsonNode chat, JsonNode entry, Set<Feature> found) {
        Requested requested = ChatWireSanitizer.requestedReasoning(chat, entry);
        if (requested == null) {
            return false;
        }
        found.add(ChatWireSanitizer.REASONING_EFFORT.equals(requested.feature()) ? Feature.REASONING_EFFORT : Feature.THINKING);
        return requested.effort() == null;
    }

    private static boolean adaptive(JsonNode thinking) {
        return thinking.isObject() && "adaptive".equalsIgnoreCase(thinking.path("type").stringValue(""));
    }

    private static void cacheControl(JsonNode node, Set<Feature> found) {
        if (node.hasNonNull("cache_control")) {
            found.add(Feature.CACHE_CONTROL);
        }
    }

    private static boolean jsonSchemaFormat(JsonNode format) {
        return format.isObject() && "json_schema".equals(format.path("type").stringValue(""));
    }

    /** The stop sequences the sanitizer would send: the strings that are not empty, however the client wrote them. */
    private static int stopSequences(JsonNode stop) {
        if (stop.isString()) {
            return stop.stringValue().isEmpty() ? 0 : 1;
        }
        int count = 0;
        if (stop.isArray()) {
            for (JsonNode sequence : stop) {
                if (sequence.isString() && !sequence.stringValue().isEmpty()) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Whether a {@code verbosity} asks for an answer other than the one the model gives by itself: {@code medium} is the
     * default level of the models that have the parameter, so a client that states it (the configuration of a coding
     * agent does, on every request) asks for nothing, and no member is left out for it. {@code low} and {@code high}, and
     * any level this class does not know, change the answer.
     */
    private static boolean changesTheLength(JsonNode verbosity) {
        return filled(verbosity)
            && !(verbosity.isString() && verbosity.stringValue().strip().equalsIgnoreCase("medium"));
    }

    /** Whether the client put something in the field: an empty list, object or string and {@code null} are nothing. */
    private static boolean filled(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isArray() || node.isObject()) {
            return !node.isEmpty();
        }
        return !node.isString() || !node.stringValue().isBlank();
    }
}
