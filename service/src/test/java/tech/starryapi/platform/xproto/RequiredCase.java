package tech.starryapi.platform.xproto;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import tech.starryapi.platform.xproto.PairRegistry.Entry;

/**
 * The fixtures every offered pair must have (design 7.14), one directory per case under the pair's fixture directory
 * and named by {@link #id()}. The design lists the cases by theme; a theme with variants ("system as a string or an
 * array", "four tool choices") is one case per variant, so that a missing variant is a missing directory.
 *
 * <p>A case is required of every entry unless it is about something only one wire format has. What a case means for
 * an entry is that entry's own form of it: {@code THINKING_EFFORT} is {@code thinking.budget_tokens} on Messages,
 * {@code reasoning_effort} on Chat, {@code reasoning.effort} on Responses and {@code thinkingConfig} on Gemini. The
 * entries named after a constant are the only ones that need it.
 */
enum RequiredCase {

    // ---- what a client sends ----
    PLAIN_TEXT,
    SYSTEM_STRING,
    SYSTEM_ARRAY,
    MULTI_TURN,
    SINGLE_TOOL,
    PARALLEL_TOOLS,
    TOOL_LOOP_TWO_ROUNDS,
    /** {@code tool_result.is_error}: no other wire format has the flag. */
    TOOL_RESULT_ERROR(Entry.MESSAGES),
    TOOL_CHOICE_AUTO,
    TOOL_CHOICE_REQUIRED,
    TOOL_CHOICE_NONE,
    TOOL_CHOICE_NAMED,
    /** Responses has no stop sequences. */
    STOP_SEQUENCES_UP_TO_4(Entry.CHAT, Entry.MESSAGES, Entry.GEMINI),
    STOP_SEQUENCES_OVER_4(Entry.CHAT, Entry.MESSAGES, Entry.GEMINI),
    MAX_TOKENS_TRUNCATES_TOOL_JSON,
    VISION_ON,
    VISION_OFF,
    /** A {@code cache_control} beside {@code system} or {@code tools}: Messages only. */
    CACHE_CONTROL_TOP_LEVEL(Entry.MESSAGES),
    /** A {@code cache_control} on a content part. Responses and Gemini have none. */
    CACHE_CONTROL_PART_LEVEL(Entry.CHAT, Entry.MESSAGES),
    THINKING_NONE,
    THINKING_EFFORT,
    THINKING_TOOL_CONTINUATION,
    /** {@code thinking.type: adaptive} with {@code output_config.effort}, as Claude Code writes it. */
    ADAPTIVE_THINKING_OUTPUT_EFFORT(Entry.MESSAGES),
    SERVER_TOOL_PRUNING,
    /** {@code tool_search_tool_*} among the tools. */
    TOOL_SEARCH_TOOL(Entry.MESSAGES),

    // ---- what the relay reports ----
    /** The four combinations of the two member switches that say whether the prompt count includes cache reads and writes. */
    CACHE_BASIS_READ_IN_WRITE_IN,
    CACHE_BASIS_READ_IN_WRITE_OUT,
    CACHE_BASIS_READ_OUT_WRITE_IN,
    CACHE_BASIS_READ_OUT_WRITE_OUT,
    USAGE_MISSING_ESTIMATED,
    USAGE_FIRST_CHUNK_ONLY,
    USAGE_OVERREPORTED_CLAMPED,
    ERROR_IN_200,
    HTML_IN_200,
    MALFORMED_SSE_DATA_LINE,
    NO_CHOICES_WITH_COMPLETION,
    NO_CHOICES_WITHOUT_COMPLETION,
    EMPTY_SUCCESS,
    /** The client asked for no stream and the relay answers with one anyway. */
    SSE_WHEN_JSON_REQUESTED,
    USAGE_CHUNK_PRESENT,
    USAGE_CHUNK_ABSENT,
    TRUNCATED_BEFORE_GENERATION,
    TRUNCATED_AFTER_GENERATION,
    ROLE_CHUNK_THEN_EOF,
    ERROR_CHUNK_IN_STREAM,
    UPSTREAM_400,
    UPSTREAM_404_MODEL_NOT_FOUND,
    CONTEXT_EXCEEDED,
    /** The request that compacts a whole Claude Code history is too long for the relay and goes to the native tier. */
    CONTEXT_EXCEEDED_COMPACTION_FALLS_BACK(Entry.MESSAGES),
    UPSTREAM_429_RETRY_AFTER,
    UPSTREAM_5XX,
    UPSTREAM_402,

    // ---- histories the conversion has to survive, each followed by three turns ----
    /** A {@code tool_result} whose content is only an image; Chat, Responses and Gemini cannot carry one. */
    IMAGE_ONLY_TOOL_RESULT_3_TURNS(Entry.MESSAGES),
    /** A {@code tool_result} without a {@code content}. */
    TOOL_RESULT_WITHOUT_CONTENT_3_TURNS(Entry.MESSAGES),
    EMPTY_BASH_OUTPUT_3_TURNS,
    IMAGE_ONLY_USER_TURN_3_TURNS,
    /** A relay that answers every turn with the tool call id {@code call_0}, and the native tier the request falls back to. */
    RELAY_REPEATS_CALL_0_FALLS_BACK_TO_NATIVE,

    // ---- what must hold throughout ----
    RELAY_TEXT_ABSENT_FROM_CLIENT_MESSAGE,
    UNICODE,
    /** The relay's request has only the allowed keys at the top of the body, ... */
    WHITELIST_TOP_LEVEL,
    /** ... in every message, ... */
    WHITELIST_MESSAGE_LEVEL,
    /** ... and in every content part. */
    WHITELIST_PART_LEVEL;

    private final Set<Entry> entries;

    RequiredCase(Entry... entries) {
        this.entries = entries.length == 0 ? EnumSet.allOf(Entry.class) : EnumSet.copyOf(Set.of(entries));
    }

    /** The directory name of the case: the constant in lower case with dashes. */
    String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    boolean appliesTo(Entry entry) {
        return entries.contains(entry);
    }

    static RequiredCase ofId(String id) {
        for (RequiredCase required : values()) {
            if (required.id().equals(id)) {
                return required;
            }
        }
        return null;
    }
}
