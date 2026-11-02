package tech.starryapi.platform.xproto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tech.starryapi.account.PoolCell;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Detected;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Feature;

/**
 * What a relay member makes of what a request asks for (design 7.9): each feature {@link RequestFeatureDetector}
 * found is carried, left out and recorded, or reason enough to leave the member out of this request.
 *
 * <ul>
 *   <li><b>CARRY</b>: the relay is given it, so nothing is said of it.
 *   <li><b>SOFT</b>: the relay is not given it and the request goes on; the request log records the name. This is
 *       what the native path does too (design 7.2): the answer is no worse than the one the next tier would give.
 *   <li><b>HARD</b>: the native path would have carried it and this member cannot, so the member is skipped for this
 *       request. A request that is served by a relay may not quietly lose what a native account would have kept.
 * </ul>
 *
 * <p>The verdict depends on three things, and on nothing else (the policy has no state):
 *
 * <ul>
 *   <li><b>The entry and what its conversion carries.</b> A feature is carried only when the conversion of the entry
 *       to the relay's Chat body has it: {@code CONVERTER_CARRIES}. The Chat entry's conversion is the sanitizer
 *       itself, which carries images, cache control, response schemas and reasoning for members that allow them. The
 *       Responses conversion ({@link ResponsesChatConverter}) hands the sanitizer a Chat body that has the images of the
 *       user's messages, the response schema and the reasoning effort, and none of the rest. The Messages conversion
 *       ({@link AnthropicChatWireConverter}) has the images of the user's turns, the cache controls (the ones on text, on
 *       tool results and on assistant turns, and the request's own), the failure of a tool result and the reasoning request;
 *       not the image in the output of a tool, nor a document. Nothing is promised that the converter does not do.
 *   <li><b>The baseline: what the route's native accounts do</b> ({@link Baseline}). A Claude request through Chat
 *       Completions or Responses goes to Kiro or an Anthropic key, which read text only: an image or a schema that the
 *       member cannot carry is no loss, SOFT, and neither is a tool of the Responses API that is not a function (the
 *       native conversion makes a function of it that has no name and no parameters, which does nothing). Codex 0.142.5
 *       declares a {@code web_search} whatever the model, so it matters that this is SOFT and not the reason to skip
 *       every relay. A
 *       {@code gpt-*} request goes to OpenAI, Codex or a compatible key, which are handed the client's body whole: the
 *       same image or schema, audio or file part, that the member cannot carry, a {@code tool_choice} that restricts the
 *       tools (the {@code allowed_tools} of the OpenAI API, which a relay is not given), a {@code logit_bias} (it changes
 *       the odds of the words, and no relay is sent it) and the built-in tools of the Responses API (OpenAI runs a {@code
 *       web_search} itself and gives the model the tools that its tool search finds) are HARD, so that moving to the
 *       relay does not drop what the client sent and the native path would have kept. The Messages entry has no
 *       baseline: its conversion has flattened the request before any route (the native path already drops images and
 *       documents there), so these are SOFT for every route. The Gemini entry has none either: a Gemini client's request
 *       is served by Kiro and the Anthropic keys, which read text only, so what a member cannot carry (an image without
 *       vision, a response schema without {@code json_schema}, audio, a file, a {@code safetySettings}) is left out and
 *       recorded, and what no relay can do for the answer (a {@code googleSearch} or {@code codeExecution} tool, a
 *       {@code cachedContent}, several candidates, more than four stop sequences) is HARD, as is a function name only Gemini's
 *       upstream takes for a member of any other vendor ({@link Feature#FUNCTION_NAME}: a dot or a colon in it, which the native
 *       accounts are given as it is). A {@code serviceTier} and the summaries of thoughts ({@code includeThoughts}) are SOFT. A {@code verbosity} is SOFT
 *       on every baseline and entry: it only asks the model to be brief or full, OpenCode sends it with every
 *       request of a gpt model, and a relay that refused the request for it would never be given one. The sanitizer does not send it and the request
 *       log records it. Three things of the Responses entry are HARD on every baseline: a custom tool other than {@code
 *       apply_patch} (a relay's model answers with a function, which the client cannot run as its tool), a chain that
 *       this node could not expand (the relay would be given the last turn alone, design 4.8) and the context that is
 *       held on OpenAI's side and only pointed at ({@code conversation}, a stored {@code prompt}, an {@code
 *       item_reference} or a compaction item: the relay would answer without it and the user would pay for the answer).
 *   <li><b>The member's settings</b>, the same record the sanitizer builds the body from ({@link Settings}): vision,
 *       response schemas, priority tier, cache-control pass-through and reasoning effort. Its {@code billedFastMode}
 *       is how a fast request gets here, because the body does not say whether the request is billed as fast: the
 *       member needs a priority tier or it is HARD ({@link Feature#FAST}), since the price paid is the fast one.
 * </ul>
 *
 * <p>Reasoning is carried when the member's reasoning mode is effort, the client asked for a level that has a name, and
 * the request is not a tool continuation of a model that needs the thinking of the turn before ({@link
 * Settings#continuationDropsReasoning}, a Claude model: that thinking cannot be given back to a relay); it is SOFT
 * otherwise, never HARD. The sanitizer reads the same flag, so the two cannot disagree.
 *
 * <p>Not here: an input larger than the mapping's context window. That depends on an estimate and on the mapping, and
 * the planner skips the member for it with a reason of its own (design 3, step 5b, condition 5).
 */
public final class FeaturePolicy {

    public enum Treatment {
        CARRY, SOFT, HARD
    }

    /**
     * What the native accounts of a route do with the body of a Chat request (design 7.9, the baseline of CHAT).
     * It is a property of the route's cells ({@link PoolCell}), not of the vendor, so it follows the route a request
     * is served on, including the all-endpoints switch (design 4.9) that moves a Claude request to the Anthropic cells.
     */
    public enum Baseline {
        /** Kiro and Anthropic keys: only text of the request reaches the account. */
        TEXT_ONLY,
        /** OpenAI, Codex, DeepSeek and compatible keys: the client's body is handed to the account whole. */
        PASSTHROUGH;

        /**
         * The baseline of a route from its native cells. One cell that takes the body whole makes it a passthrough
         * route; so does a route with no native cell at all, because HARD is the safe side: a member skipped wrongly
         * leaves the request to the native accounts, and one that is not skipped drops what a native account would
         * have carried. The cross-protocol cell is not a native one and is not looked at.
         */
        public static Baseline of(Collection<PoolCell> nativeUnits) {
            boolean any = false;
            for (PoolCell cell : nativeUnits) {
                if (cell == PoolCell.XP_OPENAI_CHAT) {
                    continue;
                }
                if (!textOnly(cell)) {
                    return PASSTHROUGH;
                }
                any = true;
            }
            return any ? TEXT_ONLY : PASSTHROUGH;
        }

        /** No default: a cell added to the enum has to be placed here. */
        private static boolean textOnly(PoolCell cell) {
            return switch (cell) {
                case ANTHROPIC_KEY, KIRO_COMPATIBLE -> true;
                case DEEPSEEK, OPENAI_COMPATIBLE, CODEX, OFFICIAL_OPENAI, OFFICIAL_OR_CODEX, XP_OPENAI_CHAT -> false;
            };
        }
    }

    /**
     * What a member makes of a request.
     *
     * @param hard the features that leave the member out, in the order of {@link Feature}
     * @param soft the features that are left out of the relay's body and recorded, in the same order
     */
    public record Verdict(List<Feature> hard, List<Feature> soft) {

        public Verdict {
            hard = List.copyOf(hard);
            soft = List.copyOf(soft);
        }

        /** Whether the member may serve the request. */
        public boolean allowed() {
            return hard.isEmpty();
        }
    }

    /**
     * The features each entry's conversion can give a relay at all, when the member allows it. The Messages entry
     * gives the images of the user's turns, the cache controls and the tool-result error flag, and the reasoning
     * request through the sanitizer (which reads the original body for it). The Responses conversion gives
     * the images of the user's messages, the response schema and the reasoning effort (not the images in the output of a
     * tool, nor cache control, which the API does not have). The Gemini conversion gives the images of the user's turns
     * ({@code inlineData}, and the {@code fileData} a relay can fetch), the response schema and, through the sanitizer's
     * reading of the original body, the thinking request (not the media of the model's turns or of function answers, nor
     * cache control, which the API does not have).
     */
    private static final Map<Entry, Set<Feature>> CONVERTER_CARRIES = carries();

    private static Map<Entry, Set<Feature>> carries() {
        Map<Entry, Set<Feature>> carries = new EnumMap<>(Entry.class);
        carries.put(Entry.CHAT, EnumSet.of(
            Feature.IMAGE, Feature.CACHE_CONTROL, Feature.JSON_SCHEMA, Feature.THINKING, Feature.REASONING_EFFORT
        ));
        carries.put(Entry.MESSAGES, EnumSet.of(Feature.IMAGE, Feature.CACHE_CONTROL, Feature.TOOL_RESULT_ERROR, Feature.THINKING));
        carries.put(Entry.RESPONSES, EnumSet.of(Feature.IMAGE, Feature.JSON_SCHEMA, Feature.REASONING_EFFORT));
        carries.put(Entry.GEMINI, EnumSet.of(Feature.IMAGE, Feature.JSON_SCHEMA, Feature.THINKING));
        return carries;
    }

    private FeaturePolicy() {
    }

    /**
     * @param detected what the request asks for
     * @param baseline what the route's native accounts do with a Chat body
     * @param member   the member's settings, with the request's {@code billedFastMode}
     */
    public static Verdict judge(Detected detected, Baseline baseline, Settings member) {
        Objects.requireNonNull(detected, "detected");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(member, "member");
        Set<Feature> asked = EnumSet.noneOf(Feature.class);
        asked.addAll(detected.features());
        if (member.billedFastMode()) {
            asked.add(Feature.FAST);
        }
        List<Feature> hard = new ArrayList<>();
        List<Feature> soft = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (!asked.contains(feature)) {
                continue;
            }
            switch (treatment(feature, detected, baseline, member)) {
                case HARD -> hard.add(feature);
                case SOFT -> soft.add(feature);
                case CARRY -> {
                    // Nothing to say of what the relay is given.
                }
            }
        }
        return new Verdict(hard, soft);
    }

    /** What one feature comes to for a member, whether or not the request has it; {@link #judge} asks it of those it has. */
    public static Treatment treatment(Feature feature, Detected detected, Baseline baseline, Settings member) {
        Entry entry = detected.entry();
        return switch (feature) {
            case STOP_SEQUENCES_OVER_4, N_OVER_ONE, WEB_SEARCH_OPTIONS, LEGACY_FUNCTIONS, NON_FUNCTION_TOOL,
                 WEB_SEARCH, WEB_FETCH, CODE_EXECUTION, BASH_TOOL, TEXT_EDITOR_TOOL, COMPUTER_TOOL, MEMORY_TOOL,
                 ANTHROPIC_DEFINED_TOOL, MCP_SERVERS, CONTAINER, PREVIOUS_RESPONSE_ID, SERVER_HELD_CONTEXT, CUSTOM_TOOL,
                 UNSUPPORTED_ENTRY -> Treatment.HARD;
            case FAST -> member.priorityTier() ? Treatment.CARRY : Treatment.HARD;
            // A name with a dot or a colon is Gemini's, and the native accounts of the route (Kiro, an Anthropic key) are given it
            // as it is: a relay of any other vendor's upstream would refuse the request, a relay in front of Gemini takes it.
            case FUNCTION_NAME -> member.carriesThoughtSignatures() ? Treatment.CARRY : Treatment.HARD;
            case THINKING, REASONING_EFFORT -> carriedIf(
                entry, feature, member.reasoningEffort()
                    && !(member.continuationDropsReasoning() && detected.toolContinuation())
                    && !detected.reasoningUnmapped()
            );
            case CACHE_CONTROL -> carriedIf(entry, feature, member.cacheControlPassthrough());
            case TOOL_RESULT_ERROR -> carriedIf(entry, feature, true);
            // An image that the conversion has no place for is not carried by a member that can see: the loss is the same
            // as for a member that cannot, and it is named the same.
            case IMAGE -> carriedOr(entry, feature, member.vision() && !detected.imageNotCarried(), baseline);
            case JSON_SCHEMA -> carriedOr(entry, feature, member.jsonSchema(), baseline);
            // No setting of a member lets it carry these, only the baseline says what leaving them out is. The logit bias
            // changes the odds of the words of the answer and a passthrough route hands it to the account; the built-in
            // tools and the image in the output of a tool are what a passthrough route gives the model and the relay has
            // no place for.
            case AUDIO, FILE, TOOL_CHOICE, LOGIT_BIAS, BUILTIN_TOOL, TOOL_OUTPUT_IMAGE ->
                carriedOr(entry, feature, false, baseline);
            // A verbosity is advice about the length of the answer, left out on every baseline and entry, recorded.
            case DOCUMENT, TOOL_SEARCH, CONTEXT_MANAGEMENT, HISTORY_THINKING, SERVER_TOOL_HISTORY, OUTPUT_CONFIG,
                 TOP_K, METADATA, SERVICE_TIER, VERBOSITY, INCLUDE, TRUNCATION, MAX_TOOL_CALLS, REASONING_SUMMARY, LOGPROBS,
                 USER, STORE, PREDICTION,
                 PROMPT_CACHE_KEY, PROMPT_CACHE_RETENTION, SAFETY_IDENTIFIER, SAFETY_SETTINGS, MODALITIES, ENUM_ANSWER -> Treatment.SOFT;
        };
    }

    /** CARRY when the entry's conversion has the feature and the member allows it, else SOFT. */
    private static Treatment carriedIf(Entry entry, Feature feature, boolean allowed) {
        return allowed && CONVERTER_CARRIES.get(entry).contains(feature) ? Treatment.CARRY : Treatment.SOFT;
    }

    /**
     * CARRY as {@link #carriedIf} says; otherwise HARD where the native accounts of a Chat request would have been
     * handed the feature, SOFT where they would not have read it.
     */
    private static Treatment carriedOr(Entry entry, Feature feature, boolean allowed, Baseline baseline) {
        if (carriedIf(entry, feature, allowed) == Treatment.CARRY) {
            return Treatment.CARRY;
        }
        boolean hasBaseline = entry == Entry.CHAT || entry == Entry.RESPONSES;
        return hasBaseline && baseline == Baseline.PASSTHROUGH ? Treatment.HARD : Treatment.SOFT;
    }
}
