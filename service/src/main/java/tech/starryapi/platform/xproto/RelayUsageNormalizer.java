package tech.starryapi.platform.xproto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.OptionalLong;
import tech.starryapi.common.JsonNodes;
import tech.starryapi.platform.core.PlatformCompletionResult.UsageSource;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tools.jackson.databind.JsonNode;

/**
 * Makes the tokens the gateway bills out of what an OpenAI-format relay reported as usage (design 5.8). The platform
 * convention is that the input contains the cache and the cache counters are subsets of it
 * ({@code AnthropicUpstreamClient.usage}); relays do not agree on that, and some do not report at all, so the report
 * is read, completed and bounded here, once, and the result is what {@code BillingQuote.cost} is given.
 *
 * <p>Reading. The usage is the merged object of {@link Completion#usage()} (the largest value of each field over the
 * whole answer). The first field that is present decides each count:
 *
 * <ul>
 *   <li>prompt: {@code prompt_tokens}, {@code input_tokens};
 *   <li>cache read: {@code prompt_tokens_details.cached_tokens}, {@code prompt_cache_hit_tokens}, {@code
 *       cache_read_input_tokens}, and last {@code input_tokens_details.cached_tokens} for a relay that names the
 *       prompt {@code input_tokens};
 *   <li>cache write: {@code prompt_tokens_details.cache_write_tokens}, {@code
 *       prompt_tokens_details.cache_creation_tokens}, {@code cache_creation_input_tokens}, and the same two names
 *       under {@code input_tokens_details};
 *   <li>output: {@code completion_tokens}, {@code output_tokens};
 *   <li>reasoning: {@code reasoning_tokens} at the top or under {@code completion_tokens_details} or {@code
 *       output_tokens_details}.
 * </ul>
 *
 * Numbers may come as text; a count that is negative or not a number is not a count, and one beyond the range of a
 * long saturates (the bounds below are what keeps it from being billed). Text is read only as far as a count can be
 * written: a longer one is read for its digits and never parsed as a decimal, which takes time in proportion to the
 * square of its length (a relay's megabyte of digits would hold the thread that reads the answer for minutes).
 *
 * <p>The caliber. Whether the relay's prompt already contains the cache read and the cache write is a setting of the
 * member, one switch each ({@link Caliber}); the input is the prompt plus whichever the prompt does not contain. DeepSeek
 * reports the prompt only as the cache hits and misses: when no prompt is given, it is the cache read plus {@code
 * prompt_cache_miss_tokens}, and that sum contains the cache read whatever the switch says. A switch wrongly set to
 * "contained" can only bill less.
 *
 * <p>Estimating, only what is missing. A prompt that is reported (above zero, or made of cache counts the caliber
 * adds) keeps the input and the cache exactly as reported. Only when the relay gave no prompt at all is the input
 * estimated: the characters of the request as sent, without inline base64, divided by four (the same rule as the
 * reservation, {@code ProxyFacade.estimatedInputTokens}), plus 1,600 tokens for each image or other media. And only when
 * the relay gave no completion tokens although the answer has text, reasoning or a tool call is the output estimated:
 * the UTF-8 bytes of all of it divided by 3.5 (the rule of the Kiro parser), at least 1. A relay that reports usage in
 * its first chunk and nothing in its last is the common case: its prompt and cache stay as reported, and only the
 * output is estimated. Any estimate makes the result {@link UsageSource#ESTIMATED}.
 *
 * <p>Bounding. What is billed never exceeds what the request was reserved for (invariant I2, which the caller completes
 * with {@code min(cost, estimate)}): an input above the tier's input bound becomes the bound, an output above the
 * forwarded limit becomes exactly the limit, and cache read plus cache write above the input are reduced in
 * proportion until they fit. Each cut of a reported figure makes the result {@link UsageSource#CLAMPED}; an estimate is
 * held to the bounds without being called clamped. When a result is both estimated and clamped, the source is
 * ESTIMATED, which is the one the promotion evidence of design 7.3 counts; {@link Result#clamped()} still says so.
 *
 * <p>The result is never zero for an answer that exists: input and output are at least 1 wherever there is nothing
 * reported to take their place, so {@code BillingQuote.cost} does not fall back to the (zero) credits of the usage
 * and a successful generation is never billed nothing. A report that is far below what the request weighs
 * ({@link Result#suspectedUnderReport()}) is billed as reported and counted.
 */
public final class RelayUsageNormalizer {

    /** The tokens counted for each image, and each other media item, in a request whose prompt the relay did not count. */
    static final long TOKENS_PER_MEDIA_ITEM = 1_600;

    /** UTF-8 bytes per token for the output of a relay that did not count it (the Kiro parser's rule). */
    static final double BYTES_PER_OUTPUT_TOKEN = 3.5;

    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    /** Text up to this long is parsed as a decimal (exponents and fractions included); a count of any size fits. */
    private static final int MAX_DECIMAL_TEXT = 32;

    /** A run of digits as long as this fits a long, whatever they are. */
    private static final int LONG_DIGITS = 18;

    private static final String[] PROMPT = {"/prompt_tokens", "/input_tokens"};
    private static final String[] CACHE_READ = {
        "/prompt_tokens_details/cached_tokens", "/prompt_cache_hit_tokens", "/cache_read_input_tokens",
        "/input_tokens_details/cached_tokens"
    };
    private static final String[] CACHE_WRITE = {
        "/prompt_tokens_details/cache_write_tokens", "/prompt_tokens_details/cache_creation_tokens",
        "/cache_creation_input_tokens", "/input_tokens_details/cache_write_tokens",
        "/input_tokens_details/cache_creation_tokens"
    };
    private static final String[] CACHE_MISS = {"/prompt_cache_miss_tokens"};
    private static final String[] OUTPUT = {"/completion_tokens", "/output_tokens"};
    private static final String[] REASONING = {
        "/reasoning_tokens", "/completion_tokens_details/reasoning_tokens", "/output_tokens_details/reasoning_tokens"
    };

    private RelayUsageNormalizer() {
    }

    /**
     * Whether the relay's prompt tokens already contain the cache read and the cache write (the member's {@code
     * usage_cache_read_in_prompt} and {@code usage_cache_write_in_prompt}). A prompt that does not contain one has it
     * added to make the input.
     */
    public record Caliber(boolean cacheReadInPrompt, boolean cacheWriteInPrompt) {
    }

    /**
     * What the request was reserved for on the serving tier.
     *
     * @param maxInputTokens  the tier's input bound; at least 1
     * @param maxOutputTokens the output limit that was forwarded to the relay; at least 1
     */
    public record Limits(long maxInputTokens, long maxOutputTokens) {

        public Limits {
            maxInputTokens = Math.max(1, maxInputTokens);
            maxOutputTokens = Math.max(1, maxOutputTokens);
        }
    }

    /**
     * @param usage               what is billed
     * @param estimated           some count was estimated, not reported
     * @param clamped             some reported count was cut to the bounds, or the cache to the input
     * @param suspectedUnderReport the relay reported an input of less than three quarters of what the request weighs
     */
    public record Result(PlatformUsage usage, boolean estimated, boolean clamped, boolean suspectedUnderReport) {

        /** The value the request log and the status page record; estimated before clamped before reported. */
        public UsageSource source() {
            return estimated ? UsageSource.ESTIMATED : clamped ? UsageSource.CLAMPED : UsageSource.REPORTED;
        }
    }

    /**
     * @param completion the answer, with the relay's usage merged over the whole of it
     * @param wireBody   the body that was sent to the relay, which weighs the request when the relay did not; null
     *                   weighs as empty
     */
    public static Result normalize(Completion completion, JsonNode wireBody, Caliber caliber, Limits limits) {
        JsonNode usage = completion.usage();
        long prompt = count(usage, PROMPT).orElse(0);
        long cacheRead = count(usage, CACHE_READ).orElse(0);
        long cacheWrite = count(usage, CACHE_WRITE).orElse(0);
        long cacheMiss = count(usage, CACHE_MISS).orElse(0);
        boolean readInPrompt = caliber.cacheReadInPrompt();
        if (prompt <= 0 && (cacheRead > 0 || cacheMiss > 0)) {
            prompt = saturatedSum(cacheRead, cacheMiss);
            readInPrompt = true;
        }
        long added = saturatedSum(readInPrompt ? 0 : cacheRead, caliber.cacheWriteInPrompt() ? 0 : cacheWrite);
        long input = saturatedSum(prompt, added);

        boolean estimated = false;
        boolean clamped = false;
        boolean suspectedUnderReport = false;
        long weight = estimateInputTokens(wireBody);
        if (input <= 0) {
            input = weight;
            estimated = true;
        } else {
            suspectedUnderReport = input < weight - weight / 4;
        }
        if (input > limits.maxInputTokens()) {
            input = limits.maxInputTokens();
            clamped |= !estimated;
        }
        BigInteger cache = BigInteger.valueOf(cacheRead).add(BigInteger.valueOf(cacheWrite));
        if (cache.compareTo(BigInteger.valueOf(input)) > 0) {
            cacheRead = scaleDown(cacheRead, input, cache);
            cacheWrite = scaleDown(cacheWrite, input, cache);
            clamped = true;
        }

        long output = count(usage, OUTPUT).orElse(0);
        boolean outputEstimated = false;
        if (output <= 0 && hasAnswer(completion)) {
            output = Math.max(1, Math.round(answerBytes(completion) / BYTES_PER_OUTPUT_TOKEN));
            outputEstimated = true;
            estimated = true;
        }
        if (output > limits.maxOutputTokens()) {
            output = limits.maxOutputTokens();
            clamped |= !outputEstimated;
        }
        long reasoning = Math.min(count(usage, REASONING).orElse(0), output);

        return new Result(new PlatformUsage(input, output, BigDecimal.ZERO, cacheRead, cacheWrite, reasoning),
            estimated, clamped, suspectedUnderReport);
    }

    /**
     * What a relay reported, count by count, read by the rules above and before anything is done with it: empty where
     * the relay has no such field. The verification of a relay (design 7.4) reads this to see what it says about its
     * cache; the normalizer itself works from the same fields.
     */
    public record Reported(
        OptionalLong prompt,
        OptionalLong cacheRead,
        OptionalLong cacheWrite,
        OptionalLong cacheMiss,
        OptionalLong output
    ) {
    }

    public static Reported read(JsonNode usage) {
        return new Reported(count(usage, PROMPT), count(usage, CACHE_READ), count(usage, CACHE_WRITE), count(usage, CACHE_MISS),
            count(usage, OUTPUT));
    }

    /**
     * What the request weighs when the relay did not say: see the class description. At least 1. The planner asks it too,
     * whether the request fits the window of the mapping that would serve it (design 3, step 5b, condition 5).
     */
    public static long estimateInputTokens(JsonNode wireBody) {
        JsonNodes.MediaStats stats = JsonNodes.mediaStats(wireBody);
        long text = Math.max(1, (stats.charsWithoutBase64() + 3) / 4);
        return text + TOKENS_PER_MEDIA_ITEM * (stats.inlineImages() + stats.remoteMedia());
    }

    private static boolean hasAnswer(Completion completion) {
        return !isEmpty(completion.content()) || !isEmpty(completion.reasoningContent())
            || (completion.toolUses() != null && !completion.toolUses().isEmpty());
    }

    /** The text, the reasoning, and the name and arguments of every tool call, in UTF-8 bytes. */
    private static long answerBytes(Completion completion) {
        long bytes = utf8Length(completion.content()) + utf8Length(completion.reasoningContent());
        if (completion.toolUses() != null) {
            for (PlatformToolUse use : completion.toolUses()) {
                bytes += utf8Length(use.name());
                bytes += use.rawInput() != null ? utf8Length(use.rawInput())
                    : use.input() == null ? 0 : JsonNodes.utf8Length(use.input());
            }
        }
        return bytes;
    }

    private static boolean isEmpty(String text) {
        return text == null || text.isEmpty();
    }

    /** The first of the fields that is a number (or text that is one), as a count: below zero is zero. */
    private static OptionalLong count(JsonNode usage, String[] pointers) {
        if (usage == null || !usage.isObject()) {
            return OptionalLong.empty();
        }
        for (String pointer : pointers) {
            JsonNode value = usage.at(pointer);
            BigDecimal number = null;
            if (value.isNumber()) {
                number = decimalOf(value);
            } else if (value.isString()) {
                number = decimalOfText(value.asString("").strip());
            }
            if (number != null) {
                return OptionalLong.of(
                    number.signum() <= 0 ? 0 : number.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : number.longValue()
                );
            }
        }
        return OptionalLong.empty();
    }

    /**
     * The number as a decimal. One outside the range of a double (1e400) is read as infinity, which has no decimal: it is
     * beyond any long when it is positive, and no number at all when it is not.
     */
    private static BigDecimal decimalOf(JsonNode number) {
        if (number.isFloatingPointNumber() && !Double.isFinite(number.doubleValue())) {
            return number.doubleValue() == Double.POSITIVE_INFINITY ? LONG_MAX.add(BigDecimal.ONE) : null;
        }
        return number.decimalValue();
    }

    /**
     * The count a text says, or null when it says none. A short text is any decimal. A longer one can only be digits (a
     * sign allowed): one that has more than eighteen of them after the zeros in front is beyond a long, and is made one
     * more than the largest long; every other long text is not a count.
     */
    private static BigDecimal decimalOfText(String text) {
        if (text.length() <= MAX_DECIMAL_TEXT) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        int from = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
        int significant = -1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            if (significant < 0 && c != '0') {
                significant = i;
            }
        }
        if (significant < 0 || text.charAt(0) == '-') {
            return BigDecimal.ZERO;
        }
        return text.length() - significant > LONG_DIGITS
            ? LONG_MAX.add(BigDecimal.ONE) : new BigDecimal(text.substring(significant));
    }

    private static long saturatedSum(long first, long second) {
        long sum = first + second;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** {@code value * to / from} rounded down, which keeps the parts of a sum from adding up to more than {@code to}. */
    private static long scaleDown(long value, long to, BigInteger from) {
        return BigInteger.valueOf(value).multiply(BigInteger.valueOf(to)).divide(from).longValueExact();
    }

    private static long utf8Length(String text) {
        if (text == null) {
            return 0;
        }
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
