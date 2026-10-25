package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tech.starryapi.common.JsonNodes;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.platform.core.PlatformCompletionResult.StopReason;
import tech.starryapi.platform.core.PlatformCompletionResult.UsageSource;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Caliber;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Limits;
import tech.starryapi.platform.xproto.RelayUsageNormalizer.Result;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Design 5.8: what a relay's usage becomes. The three examples of 5.5 are priced with the real {@link BillingQuote}
 * (claude-sonnet-4.5: $3 in, $15 out, $0.30 cache read per million), the field names and both caliber switches are
 * covered one by one, then what is missing is estimated and nothing else, the bounds hold, and a seeded random run
 * checks the promises of the class for any usage a relay could send: the cache fits in the input, nothing is above the
 * reservation (so {@code cost <= estimate}), and an answer that exists is never billed nothing.
 */
class RelayUsageNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final BillingQuote SONNET = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), BigDecimal.ZERO
    );

    private static final Caliber INCLUDED = new Caliber(true, true);
    private static final Limits ROOMY = new Limits(1_000_000, 64_000);
    private static final JsonNode SMALL_REQUEST = tree("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static Completion answer(String content, String usage) {
        return new Completion(content, "", List.of(), StopReason.END_TURN, tree(usage));
    }

    private static Result normalize(String content, String usage, Caliber caliber, Limits limits) {
        return RelayUsageNormalizer.normalize(answer(content, usage), SMALL_REQUEST, caliber, limits);
    }

    private static Result normalize(String usage) {
        return normalize("an answer", usage, INCLUDED, ROOMY);
    }

    private static PlatformUsage usage(long input, long output, long cacheRead, long cacheWrite) {
        return new PlatformUsage(input, output, BigDecimal.ZERO, cacheRead, cacheWrite, 0);
    }

    // ---------------------------------------------------------------------------------- the examples of design 5.5

    @Test
    void aPromptThatContainsTheCacheReadIsBilledAsReported() {
        Result result = normalize(
            "answer", "{\"prompt_tokens\":8000,\"prompt_tokens_details\":{\"cached_tokens\":6000},\"completion_tokens\":1000}",
            INCLUDED, ROOMY
        );

        assertThat(result.usage()).isEqualTo(usage(8000, 1000, 6000, 0));
        assertThat(result.source()).isEqualTo(UsageSource.REPORTED);
        // 2000 x 3e-6 + 6000 x 0.3e-6 + 1000 x 15e-6
        assertThat(SONNET.cost(result.usage())).isEqualByComparingTo("0.0228");
    }

    @Test
    void aPromptThatLeavesTheCacheReadOutGetsItAdded() {
        Result result = normalize(
            "answer", "{\"prompt_tokens\":2000,\"cache_read_input_tokens\":6000,\"completion_tokens\":1000}",
            new Caliber(false, true), ROOMY
        );

        assertThat(result.usage()).isEqualTo(usage(8000, 1000, 6000, 0));
        assertThat(result.source()).isEqualTo(UsageSource.REPORTED);
        assertThat(SONNET.cost(result.usage())).isEqualByComparingTo("0.0228");
    }

    @Test
    void aRelayThatReportsOnlyInTheFirstChunkIsBilledForTheInputItReported() {
        String body = "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}],"
            + "\"usage\":{\"prompt_tokens\":100000,\"completion_tokens\":0,\"prompt_tokens_details\":{\"cached_tokens\":90000}}}\n\n"
            + "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Done.\"}}]}\n\n"
            + "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}\n\n"
            + "data: [DONE]\n\n";
        ChatCompletionAccumulator accumulator = new ChatCompletionAccumulator(
            JSON, error -> new UpstreamException(502, "relay error", true)
        );
        accumulator.acceptBytes(body.getBytes(StandardCharsets.UTF_8));
        Completion completion = accumulator.finish();

        Result result = RelayUsageNormalizer.normalize(completion, SMALL_REQUEST, INCLUDED, ROOMY);

        // The prompt and the cache stay as the first chunk reported them; the one thing missing is the output.
        assertThat(result.usage().inputTokens()).isEqualTo(100_000);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(90_000);
        assertThat(result.usage().outputTokens()).isEqualTo(1);
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
        // 10k x 3e-6 + 90k x 0.3e-6 = 0.057 for the input; the five bytes of output are one token, 15e-6, more
        assertThat(SONNET.cost(usage(100_000, 0, 90_000, 0))).isEqualByComparingTo("0.057");
        assertThat(SONNET.cost(result.usage())).isEqualByComparingTo("0.057015");
        // The old rule estimated both and took the whole prompt as uncached: 100k x 3e-6 = 0.30, five times as much
        assertThat(SONNET.cost(usage(100_000, 1, 0, 0))).isGreaterThan(new BigDecimal("0.30"));
    }

    // -------------------------------------------------------------------------------------------- reading the fields

    static Stream<Arguments> fields() {
        return Stream.of(
            Arguments.of("prompt_tokens and completion_tokens",
                "{\"prompt_tokens\":100,\"completion_tokens\":20}", usage(100, 20, 0, 0)),
            Arguments.of("input_tokens and output_tokens",
                "{\"input_tokens\":100,\"output_tokens\":20}", usage(100, 20, 0, 0)),
            Arguments.of("prompt_tokens before input_tokens",
                "{\"prompt_tokens\":100,\"input_tokens\":999,\"completion_tokens\":20,\"output_tokens\":999}", usage(100, 20, 0, 0)),
            Arguments.of("cached_tokens before the others",
                "{\"prompt_tokens\":100,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":50},"
                    + "\"prompt_cache_hit_tokens\":60,\"cache_read_input_tokens\":70}", usage(100, 2, 50, 0)),
            Arguments.of("prompt_cache_hit_tokens before cache_read_input_tokens",
                "{\"prompt_tokens\":100,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":60,\"cache_read_input_tokens\":70}",
                usage(100, 2, 60, 0)),
            Arguments.of("cache_read_input_tokens",
                "{\"prompt_tokens\":100,\"completion_tokens\":2,\"cache_read_input_tokens\":70}", usage(100, 2, 70, 0)),
            Arguments.of("cached_tokens under input_tokens_details",
                "{\"input_tokens\":100,\"output_tokens\":2,\"input_tokens_details\":{\"cached_tokens\":40}}", usage(100, 2, 40, 0)),
            Arguments.of("a cached_tokens of zero is a present field",
                "{\"prompt_tokens\":100,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":0},"
                    + "\"cache_read_input_tokens\":70}", usage(100, 2, 0, 0)),
            Arguments.of("cache_write_tokens",
                "{\"prompt_tokens\":100,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cache_write_tokens\":30,"
                    + "\"cache_creation_tokens\":31},\"cache_creation_input_tokens\":32}", usage(100, 2, 0, 30)),
            Arguments.of("cache_creation_tokens",
                "{\"prompt_tokens\":100,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cache_creation_tokens\":31},"
                    + "\"cache_creation_input_tokens\":32}", usage(100, 2, 0, 31)),
            Arguments.of("cache_creation_input_tokens",
                "{\"prompt_tokens\":100,\"completion_tokens\":2,\"cache_creation_input_tokens\":32}", usage(100, 2, 0, 32)),
            Arguments.of("counts as text and as decimals",
                "{\"prompt_tokens\":\" 100 \",\"completion_tokens\":20.0,\"cache_read_input_tokens\":\"30\"}", usage(100, 20, 30, 0)),
            Arguments.of("a count that is not a number is not a count",
                "{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":\"many\"},"
                    + "\"cache_read_input_tokens\":30}", usage(100, 20, 30, 0)),
            Arguments.of("a negative count is zero",
                "{\"prompt_tokens\":100,\"completion_tokens\":20,\"cache_read_input_tokens\":-5}", usage(100, 20, 0, 0))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    void readsTheFirstFieldThatIsPresent(String name, String usage, PlatformUsage expected) {
        Result result = normalize("an answer", usage, INCLUDED, ROOMY);

        assertThat(result.usage()).isEqualTo(expected);
        assertThat(result.source()).isEqualTo(UsageSource.REPORTED);
    }

    @Test
    void theReportedCountsAreTheOnesTheNormalizerReadsAndEmptyWhereTheRelayNamedNone() {
        RelayUsageNormalizer.Reported reported = RelayUsageNormalizer.read(tree(
            "{\"prompt_tokens\":76,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":1024},"
                + "\"cache_creation_input_tokens\":\"12\",\"prompt_cache_miss_tokens\":3}"));
        assertThat(reported.prompt()).hasValue(76);
        assertThat(reported.output()).hasValue(4);
        assertThat(reported.cacheRead()).hasValue(1024);
        assertThat(reported.cacheWrite()).hasValue(12);
        assertThat(reported.cacheMiss()).hasValue(3);

        RelayUsageNormalizer.Reported none = RelayUsageNormalizer.read(tree("{}"));
        assertThat(none.prompt()).isEmpty();
        assertThat(none.output()).isEmpty();
        assertThat(none.cacheRead()).isEmpty();
        assertThat(none.cacheWrite()).isEmpty();
        assertThat(none.cacheMiss()).isEmpty();
        assertThat(RelayUsageNormalizer.read(null).prompt()).isEmpty();
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":-3}")).prompt()).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reasoningFields")
    void readsTheReasoningTokensWhereverTheRelayPutThem(String name, String usage) {
        Result result = normalize("an answer", usage, INCLUDED, ROOMY);

        assertThat(result.usage().reasoningTokens()).isEqualTo(7);
    }

    static Stream<Arguments> reasoningFields() {
        return Stream.of(
            Arguments.of("top level", "{\"prompt_tokens\":9,\"completion_tokens\":20,\"reasoning_tokens\":7}"),
            Arguments.of("completion_tokens_details",
                "{\"prompt_tokens\":9,\"completion_tokens\":20,\"completion_tokens_details\":{\"reasoning_tokens\":7}}"),
            Arguments.of("output_tokens_details",
                "{\"input_tokens\":9,\"output_tokens\":20,\"output_tokens_details\":{\"reasoning_tokens\":7}}")
        );
    }

    @Test
    void reasoningIsAPartOfTheOutputAndNeverMoreThanIt() {
        Result result = normalize("an answer", "{\"prompt_tokens\":9,\"completion_tokens\":5,\"reasoning_tokens\":50}", INCLUDED, ROOMY);

        assertThat(result.usage().reasoningTokens()).isEqualTo(5);
    }

    @Test
    void theCreditsOfTheUsageAreZeroLikeAnyOtherTokenCountedSource() {
        assertThat(normalize("{\"prompt_tokens\":9,\"completion_tokens\":5}").usage().credits()).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------------------------------------- the caliber

    @ParameterizedTest(name = "read in prompt {0}, write in prompt {1}: input {2}")
    @MethodSource("calibers")
    void eachSwitchSaysWhetherThePromptContainsThatCache(boolean readInPrompt, boolean writeInPrompt, long input) {
        Result result = normalize(
            "an answer",
            "{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":300,\"cache_write_tokens\":200}}",
            new Caliber(readInPrompt, writeInPrompt), ROOMY
        );

        assertThat(result.usage()).isEqualTo(usage(input, 10, 300, 200));
        assertThat(result.source()).isEqualTo(UsageSource.REPORTED);
    }

    static Stream<Arguments> calibers() {
        return Stream.of(
            Arguments.of(true, true, 1000L),
            Arguments.of(false, true, 1300L),
            Arguments.of(true, false, 1200L),
            Arguments.of(false, false, 1500L)
        );
    }

    @Test
    void aSwitchSetToContainedWhenItIsNotOnlyBillsLess() {
        String usage = "{\"prompt_tokens\":2000,\"cache_read_input_tokens\":6000,\"completion_tokens\":1000}";

        Result right = normalize("answer", usage, new Caliber(false, true), ROOMY);
        Result wrong = normalize("answer", usage, new Caliber(true, true), ROOMY);

        // The cache cannot be more than the input: it is cut to it, and the result says so.
        assertThat(wrong.usage()).isEqualTo(usage(2000, 1000, 2000, 0));
        assertThat(wrong.source()).isEqualTo(UsageSource.CLAMPED);
        assertThat(SONNET.cost(wrong.usage())).isLessThan(SONNET.cost(right.usage()));
    }

    @Test
    void deepSeekReportsThePromptAsCacheHitsAndMisses() {
        String usage = "{\"prompt_cache_hit_tokens\":700,\"prompt_cache_miss_tokens\":300,\"completion_tokens\":50}";

        // The sum contains the hits whatever the switch says; it is not added to a second time.
        assertThat(normalize("answer", usage, new Caliber(true, true), ROOMY).usage()).isEqualTo(usage(1000, 50, 700, 0));
        assertThat(normalize("answer", usage, new Caliber(false, true), ROOMY).usage()).isEqualTo(usage(1000, 50, 700, 0));
    }

    @Test
    void aPromptThatIsReportedIsNotMadeFromHitsAndMisses() {
        String usage = "{\"prompt_tokens\":900,\"prompt_cache_hit_tokens\":700,\"prompt_cache_miss_tokens\":300,\"completion_tokens\":50}";

        assertThat(normalize("answer", usage, INCLUDED, ROOMY).usage()).isEqualTo(usage(900, 50, 700, 0));
    }

    @Test
    void hitsAndMissesMakeThePromptWithTheCacheReadOfAnotherField() {
        Result result = normalize("answer", "{\"cache_read_input_tokens\":700,\"prompt_cache_miss_tokens\":300,\"completion_tokens\":50}",
            new Caliber(false, false), ROOMY);

        assertThat(result.usage()).isEqualTo(usage(1000, 50, 700, 0));
        assertThat(result.source()).isEqualTo(UsageSource.REPORTED);
    }

    @Test
    void aPromptOfCacheCountsTheCaliberAddsIsAReportedPrompt() {
        // Everything cached: nothing uncached is a number the relay gave, not a missing one.
        Result result = normalize("answer", "{\"prompt_tokens\":0,\"cache_creation_input_tokens\":500,\"completion_tokens\":50}",
            new Caliber(true, false), ROOMY);

        assertThat(result.usage()).isEqualTo(usage(500, 50, 0, 500));
        assertThat(result.source()).isEqualTo(UsageSource.REPORTED);
    }

    // ------------------------------------------------------------------------------- estimating what is missing

    @Test
    void aRelayWithoutAnyUsageIsEstimatedFromTheRequestAndTheAnswer() {
        JsonNode request = tree("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hello hello hello\"}]}");
        long chars = JsonNodes.charLength(request);
        Completion completion = answer("Hello there!", "{}");

        Result result = RelayUsageNormalizer.normalize(completion, request, INCLUDED, ROOMY);

        assertThat(result.usage().inputTokens()).isEqualTo((chars + 3) / 4);
        assertThat(result.usage().outputTokens()).isEqualTo(3);   // 12 bytes / 3.5 = 3.43
        assertThat(result.usage().cacheReadTokens()).isZero();
        assertThat(result.usage().cacheWriteTokens()).isZero();
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(result.estimated()).isTrue();
        assertThat(result.suspectedUnderReport()).isFalse();
    }

    @ParameterizedTest(name = "usage {0}")
    @MethodSource("reportsOfNothing")
    void aReportOfNothingIsNoReport(String usage) {
        Completion completion = new Completion("Hello there!", "", List.of(), StopReason.END_TURN, usage.equals("null") ? null : tree(usage));

        Result result = RelayUsageNormalizer.normalize(completion, SMALL_REQUEST, INCLUDED, ROOMY);

        assertThat(result.usage().inputTokens()).isGreaterThan(0);
        assertThat(result.usage().outputTokens()).isEqualTo(3);
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    static Stream<String> reportsOfNothing() {
        return Stream.of(
            "null", "{}", "[]", "\"usage\"", "{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}",
            "{\"prompt_tokens\":null,\"completion_tokens\":null}", "{\"prompt_tokens\":\"many\",\"completion_tokens\":\"few\"}",
            "{\"prompt_tokens\":-4,\"completion_tokens\":-1}"
        );
    }

    @Test
    void theRequestWeighsWithoutInlineBase64AndWithAnImageFor1600Tokens() {
        String payload = "A".repeat(200_000);
        JsonNode request = tree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"look\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + payload + "\"}},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + payload + "\"}},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/cat.png\"}}]}]}");
        JsonNodes.MediaStats stats = JsonNodes.mediaStats(request);

        Result result = RelayUsageNormalizer.normalize(answer("ok", "{}"), request, INCLUDED, ROOMY);

        assertThat(stats.inlineImages()).isEqualTo(2);
        assertThat(stats.remoteMedia()).isEqualTo(1);
        assertThat(result.usage().inputTokens()).isEqualTo((stats.charsWithoutBase64() + 3) / 4 + 3 * 1600);
        assertThat(result.usage().inputTokens()).isLessThan(60_000);
    }

    @Test
    void theOutputIsTheBytesOfTheTextTheReasoningAndTheToolCallsOver3Point5() {
        PlatformToolUse call = new PlatformToolUse("call_0", "read_file", tree("{\"path\":\"a\"}"), "function_call", "{\"path\":\"a\"}", null);
        // 10 + 3 + 9 + 12 = 34 bytes
        Completion completion = new Completion("12345678é", "你", List.of(call), StopReason.TOOL_USE, tree("{\"prompt_tokens\":50}"));

        Result result = RelayUsageNormalizer.normalize(completion, SMALL_REQUEST, INCLUDED, ROOMY);

        assertThat(result.usage().outputTokens()).isEqualTo(Math.round(34 / 3.5));
        assertThat(result.usage().inputTokens()).isEqualTo(50);
    }

    @Test
    void aToolCallWithoutRawArgumentsIsCountedFromItsInput() {
        PlatformToolUse call = new PlatformToolUse("call_0", "go", tree("{\"n\":1}"));
        Completion completion = new Completion("", "", List.of(call), StopReason.TOOL_USE, tree("{\"prompt_tokens\":50}"));

        Result result = RelayUsageNormalizer.normalize(completion, SMALL_REQUEST, INCLUDED, ROOMY);

        assertThat(result.usage().outputTokens()).isEqualTo(Math.round((2 + 7) / 3.5));
    }

    @ParameterizedTest(name = "[{index}] {1} output tokens")
    @MethodSource("outputEstimates")
    void theOutputEstimateIsRoundedAndAtLeastOne(String text, long tokens) {
        Result result = RelayUsageNormalizer.normalize(answer(text, "{\"prompt_tokens\":50}"), SMALL_REQUEST, INCLUDED, ROOMY);

        assertThat(result.usage().outputTokens()).isEqualTo(tokens);
    }

    static Stream<Arguments> outputEstimates() {
        return Stream.of(
            Arguments.of("a", 1L), Arguments.of("ok", 1L), Arguments.of("seven!!", 2L), Arguments.of("1234567890", 3L),
            Arguments.of("你好", 2L), Arguments.of("😀", 1L), Arguments.of("x".repeat(3500), 1000L)
        );
    }

    @Test
    void onlyTheOutputIsEstimatedWhenThePromptIsReported() {
        Result result = normalize("Hello there!", "{\"prompt_tokens\":5000,\"prompt_tokens_details\":{\"cached_tokens\":4000}}", INCLUDED, ROOMY);

        assertThat(result.usage()).isEqualTo(usage(5000, 3, 4000, 0));
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    @Test
    void onlyTheInputIsEstimatedWhenTheCompletionIsReported() {
        JsonNode request = tree("{\"messages\":[{\"role\":\"user\",\"content\":\"" + "word ".repeat(400) + "\"}]}");

        Result result = RelayUsageNormalizer.normalize(answer("Hello there!", "{\"completion_tokens\":77}"), request, INCLUDED, ROOMY);

        assertThat(result.usage().outputTokens()).isEqualTo(77);
        assertThat(result.usage().inputTokens()).isEqualTo((JsonNodes.charLength(request) + 3) / 4);
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    @Test
    void theOutputIsEstimatedAlsoWhenTheRelayReportsZeroForAnAnswerThatHasText() {
        Result result = normalize("Hello there!", "{\"prompt_tokens\":10,\"completion_tokens\":0}", INCLUDED, ROOMY);

        assertThat(result.usage().outputTokens()).isEqualTo(3);
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    @Test
    void anAnswerWithNothingInItHasNoOutputToEstimate() {
        Completion empty = new Completion("", "", List.of(), StopReason.END_TURN, tree("{\"prompt_tokens\":10}"));

        Result result = RelayUsageNormalizer.normalize(empty, SMALL_REQUEST, INCLUDED, ROOMY);

        assertThat(result.usage().outputTokens()).isZero();
        assertThat(result.source()).isEqualTo(UsageSource.REPORTED);
    }

    @Test
    void aMissingRequestWeighsAtLeastOneToken() {
        Result result = RelayUsageNormalizer.normalize(answer("x", "{}"), null, INCLUDED, ROOMY);

        assertThat(result.usage().inputTokens()).isEqualTo(1);
        assertThat(result.usage().outputTokens()).isEqualTo(1);
    }

    @Test
    void aCacheWriteWithNoPromptIsHeldBelowTheEstimatedInput() {
        // The relay counted a cache write that its prompt contains, and no prompt: the input is the estimate, and the
        // cache, which cannot be more than the input, is cut to fit it.
        Result result = RelayUsageNormalizer.normalize(
            answer("ok", "{\"cache_creation_input_tokens\":5000,\"completion_tokens\":4}"),
            SMALL_REQUEST, new Caliber(true, true), ROOMY
        );

        long estimate = (JsonNodes.charLength(SMALL_REQUEST) + 3) / 4;
        assertThat(result.usage()).isEqualTo(usage(estimate, 4, 0, estimate));
        assertThat(result.estimated()).isTrue();
        assertThat(result.clamped()).isTrue();
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    // ---------------------------------------------------------------------------------------------------- bounds

    @Test
    void anOutputAboveTheForwardedLimitIsExactlyTheLimit() {
        Result result = normalize("answer", "{\"prompt_tokens\":100,\"completion_tokens\":5000}", INCLUDED, new Limits(1_000_000, 4096));

        assertThat(result.usage().outputTokens()).isEqualTo(4096);
        assertThat(result.source()).isEqualTo(UsageSource.CLAMPED);
        assertThat(result.clamped()).isTrue();
        assertThat(result.estimated()).isFalse();
    }

    @Test
    void anOutputAtTheLimitIsNotClamped() {
        Result result = normalize("answer", "{\"prompt_tokens\":100,\"completion_tokens\":4096}", INCLUDED, new Limits(1_000_000, 4096));

        assertThat(result.usage().outputTokens()).isEqualTo(4096);
        assertThat(result.source()).isEqualTo(UsageSource.REPORTED);
    }

    @Test
    void anInputAboveTheTiersBoundIsTheBound() {
        Result result = normalize("answer", "{\"prompt_tokens\":300000,\"completion_tokens\":10,\"cache_read_input_tokens\":100000}",
            new Caliber(true, true), new Limits(200_000, 4096));

        assertThat(result.usage()).isEqualTo(usage(200_000, 10, 100_000, 0));
        assertThat(result.source()).isEqualTo(UsageSource.CLAMPED);
    }

    @Test
    void aCacheLargerThanTheInputIsCutInProportion() {
        Result result = normalize("answer",
            "{\"prompt_tokens\":600,\"completion_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":600,\"cache_write_tokens\":300}}",
            INCLUDED, ROOMY);

        // 600 + 300 > 600: both shrink by two thirds.
        assertThat(result.usage()).isEqualTo(usage(600, 10, 400, 200));
        assertThat(result.source()).isEqualTo(UsageSource.CLAMPED);
    }

    @Test
    void cuttingTheCacheRoundsDownSoItNeverExceedsTheInput() {
        Result result = normalize("answer",
            "{\"prompt_tokens\":10,\"completion_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":7,\"cache_write_tokens\":6}}",
            INCLUDED, ROOMY);

        // 7 x 10 / 13 = 5.4 and 6 x 10 / 13 = 4.6, both down
        assertThat(result.usage().cacheReadTokens()).isEqualTo(5);
        assertThat(result.usage().cacheWriteTokens()).isEqualTo(4);
    }

    @Test
    void anEstimateIsHeldToTheBoundsWithoutBeingCalledClamped() {
        JsonNode request = tree("{\"messages\":[{\"role\":\"user\",\"content\":\"" + "word ".repeat(2000) + "\"}]}");

        Result result = RelayUsageNormalizer.normalize(answer("x".repeat(3500), "{}"), request, INCLUDED, new Limits(100, 50));

        assertThat(result.usage().inputTokens()).isEqualTo(100);
        assertThat(result.usage().outputTokens()).isEqualTo(50);
        assertThat(result.clamped()).isFalse();
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    @Test
    void whenBothHappenTheSourceIsEstimatedAndTheResultStillSaysClamped() {
        // A reported prompt over the bound, an output that had to be estimated.
        Result result = normalize("Hello there!", "{\"prompt_tokens\":900}", INCLUDED, new Limits(500, 50));

        assertThat(result.usage()).isEqualTo(usage(500, 3, 0, 0));
        assertThat(result.estimated()).isTrue();
        assertThat(result.clamped()).isTrue();
        assertThat(result.source()).isEqualTo(UsageSource.ESTIMATED);
    }

    @Test
    void numbersBeyondAnyBoundSaturateInsteadOfFailing() {
        String huge = "{\"prompt_tokens\":99999999999999999999999,\"completion_tokens\":9223372036854775807,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":9223372036854775807,\"cache_write_tokens\":9223372036854775807}}";

        Result result = normalize("answer", huge, new Caliber(false, false), new Limits(1_000_000, 8192));

        assertThat(result.usage().inputTokens()).isEqualTo(1_000_000);
        assertThat(result.usage().outputTokens()).isEqualTo(8192);
        assertThat(result.usage().cacheReadTokens() + result.usage().cacheWriteTokens()).isLessThanOrEqualTo(1_000_000);
        assertThat(result.source()).isEqualTo(UsageSource.CLAMPED);
    }

    /**
     * {@code new BigDecimal(text)} takes time in proportion to the square of the digits: a megabyte of them held the thread
     * that reads the answer for ten seconds, the time limit of the exchange (which is over by then) notwithstanding.
     */
    @Test
    void aCountWrittenAsAMegabyteOfDigitsIsReadInMillisecondsAndSaturates() {
        String digits = "7".repeat(1_000_000);
        ObjectNode usage = JSON.createObjectNode();
        usage.put("prompt_tokens", digits);
        usage.put("completion_tokens", digits);
        usage.putObject("prompt_tokens_details").put("cached_tokens", "-" + digits);
        usage.put("cache_creation_input_tokens", "1" + "0".repeat(999_999) + "e5");

        long started = System.nanoTime();
        RelayUsageNormalizer.Reported reported = RelayUsageNormalizer.read(usage);
        Result result = RelayUsageNormalizer.normalize(
            new Completion("an answer", "", List.of(), StopReason.END_TURN, usage), SMALL_REQUEST, INCLUDED, new Limits(200_000, 8192));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).as("milliseconds for four counts of a megabyte").isLessThan(2_000);
        assertThat(reported.prompt()).hasValue(Long.MAX_VALUE);
        assertThat(reported.output()).hasValue(Long.MAX_VALUE);
        assertThat(reported.cacheRead()).as("below zero is zero").hasValue(0);
        assertThat(reported.cacheWrite()).as("an exponent is no count in a text this long").isEmpty();
        // What is billed is bounded by what was reserved, as for any count beyond a long.
        assertThat(result.usage().inputTokens()).isEqualTo(200_000);
        assertThat(result.usage().outputTokens()).isEqualTo(8192);
        assertThat(result.source()).isEqualTo(UsageSource.CLAMPED);
    }

    @Test
    void aLongCountOfDigitsIsReadBySignAndSignificantDigitsLikeAShortOne() {
        String zeros = "0".repeat(40);
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"" + zeros + "123\"}")).prompt()).hasValue(123);
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"+" + zeros + "123\"}")).prompt()).hasValue(123);
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"" + zeros + "\"}")).prompt()).hasValue(0);
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"-" + zeros + "123\"}")).prompt()).hasValue(0);
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"" + zeros + "1" + "0".repeat(18) + "\"}")).prompt())
            .as("nineteen significant digits are beyond eighteen, which are beyond any count")
            .hasValue(Long.MAX_VALUE);
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"" + zeros + "9" + "0".repeat(17) + "\"}")).prompt())
            .hasValue(900_000_000_000_000_000L);
        // Not digits: no count, so the next field decides.
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"" + zeros + "1.5\",\"input_tokens\":9}")).prompt()).hasValue(9);
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"" + zeros + "1e3\",\"input_tokens\":9}")).prompt()).hasValue(9);
        // A short text is any decimal, as it was.
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"1.5e3\"}")).prompt()).hasValue(1500);
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":\"" + "9".repeat(32) + "\"}")).prompt()).hasValue(Long.MAX_VALUE);
    }

    @Test
    void aNumberOutsideTheRangeOfADoubleSaturatesIfItIsPositiveAndIsNoCountIfItIsNot() {
        // 1e400 is read as infinity, which has no decimal: it is beyond any limit, as 99999999999999999999999 is.
        Result huge = normalize("answer", "{\"prompt_tokens\":1e400,\"completion_tokens\":1e400,\"cache_read_input_tokens\":1e400}",
            INCLUDED, new Limits(1_000_000, 8192));
        assertThat(huge.usage().inputTokens()).isEqualTo(1_000_000);
        assertThat(huge.usage().outputTokens()).isEqualTo(8192);
        assertThat(huge.usage().cacheReadTokens()).isLessThanOrEqualTo(1_000_000);
        assertThat(huge.source()).isEqualTo(UsageSource.CLAMPED);

        // Minus infinity is not a count, and the next field that names one decides, as for any other word.
        Result negative = normalize("{\"prompt_tokens\":-1e400,\"input_tokens\":120,\"completion_tokens\":-1e400,\"output_tokens\":7}");
        assertThat(negative.usage()).isEqualTo(usage(120, 7, 0, 0));
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":-1e400}")).prompt()).isEmpty();
        assertThat(RelayUsageNormalizer.read(tree("{\"prompt_tokens\":1e400}")).prompt()).hasValue(Long.MAX_VALUE);
    }

    @Test
    void boundsBelowOneAreOne() {
        Limits limits = new Limits(0, -5);

        assertThat(limits.maxInputTokens()).isEqualTo(1);
        assertThat(limits.maxOutputTokens()).isEqualTo(1);
    }

    @Test
    void whatIsBilledIsNeverMoreThanWhatWasReserved() {
        // The same relay, over-reporting everything, on the quote of the serving tier.
        Limits limits = new Limits(8000, 1000);
        Result result = normalize("answer",
            "{\"prompt_tokens\":80000,\"completion_tokens\":10000,\"prompt_tokens_details\":{\"cached_tokens\":70000}}",
            INCLUDED, limits);

        assertThat(SONNET.cost(result.usage())).isLessThanOrEqualTo(SONNET.estimate(8000, 1000));
    }

    // ----------------------------------------------------------------------------------------- the under-report

    @Test
    void anInputBelowThreeQuartersOfWhatTheRequestWeighsIsBilledAsReportedAndCounted() {
        JsonNode request = tree("{\"messages\":[{\"role\":\"user\",\"content\":\"" + "word ".repeat(800) + "\"}]}");
        long weight = (JsonNodes.charLength(request) + 3) / 4;
        long threeQuarters = weight - weight / 4;

        Result below = RelayUsageNormalizer.normalize(answer("ok", "{\"prompt_tokens\":" + (threeQuarters - 1) + ",\"completion_tokens\":3}"),
            request, INCLUDED, ROOMY);
        Result exactly = RelayUsageNormalizer.normalize(answer("ok", "{\"prompt_tokens\":" + threeQuarters + ",\"completion_tokens\":3}"),
            request, INCLUDED, ROOMY);
        Result above = RelayUsageNormalizer.normalize(answer("ok", "{\"prompt_tokens\":" + weight + ",\"completion_tokens\":3}"),
            request, INCLUDED, ROOMY);

        assertThat(below.suspectedUnderReport()).isTrue();
        assertThat(below.usage().inputTokens()).isEqualTo(threeQuarters - 1);
        assertThat(below.source()).isEqualTo(UsageSource.REPORTED);
        assertThat(exactly.suspectedUnderReport()).isFalse();
        assertThat(above.suspectedUnderReport()).isFalse();
    }

    @Test
    void anEstimatedInputIsNeverCalledAnUnderReport() {
        Result result = normalize("ok", "{}", INCLUDED, ROOMY);

        assertThat(result.suspectedUnderReport()).isFalse();
    }

    // -------------------------------------------------------------------------------------------- the properties

    private static final String[] FIELD_PATHS = {
        "prompt_tokens", "input_tokens", "completion_tokens", "output_tokens", "total_tokens", "prompt_cache_hit_tokens",
        "prompt_cache_miss_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "reasoning_tokens",
        "prompt_tokens_details/cached_tokens", "prompt_tokens_details/cache_write_tokens",
        "prompt_tokens_details/cache_creation_tokens", "input_tokens_details/cached_tokens",
        "completion_tokens_details/reasoning_tokens", "output_tokens_details/reasoning_tokens"
    };

    private static JsonNode randomUsage(Random random) {
        if (random.nextInt(25) == 0) {
            return random.nextBoolean() ? null : JSON.createArrayNode();
        }
        ObjectNode usage = JSON.createObjectNode();
        for (String path : FIELD_PATHS) {
            if (random.nextInt(3) != 0) {
                continue;
            }
            ObjectNode parent = usage;
            String[] steps = path.split("/");
            for (int i = 0; i < steps.length - 1; i++) {
                parent = parent.has(steps[i]) && parent.get(steps[i]).isObject()
                    ? (ObjectNode) parent.get(steps[i]) : parent.putObject(steps[i]);
            }
            String name = steps[steps.length - 1];
            switch (random.nextInt(12)) {
                case 0 -> parent.put(name, 0);
                case 1 -> parent.put(name, -random.nextInt(1000));
                case 2 -> parent.put(name, Long.MAX_VALUE);
                case 3 -> parent.put(name, String.valueOf(random.nextInt(50_000)));
                case 4 -> parent.put(name, "n/a");
                case 5 -> parent.putNull(name);
                case 6 -> parent.put(name, random.nextDouble() * 1000);
                case 7 -> parent.put(name, random.nextLong(1, 10_000_000_000L));
                default -> parent.put(name, random.nextInt(1, 20_000));
            }
        }
        return usage;
    }

    private static Completion randomAnswer(Random random, JsonNode usage) {
        String[] pieces = {"", "a", "Hello there!", "你好", "😀", "x".repeat(random.nextInt(2000))};
        String content = pieces[random.nextInt(pieces.length)];
        String reasoning = random.nextInt(4) == 0 ? pieces[random.nextInt(pieces.length)] : "";
        List<PlatformToolUse> calls = random.nextInt(4) == 0
            ? List.of(new PlatformToolUse("call_0", "go", tree("{\"n\":" + random.nextInt(100) + "}"))) : List.of();
        return new Completion(content, reasoning, calls, StopReason.END_TURN, usage);
    }

    private static JsonNode randomRequest(Random random) {
        StringBuilder text = new StringBuilder();
        for (int i = random.nextInt(600); i > 0; i--) {
            text.append("word ");
        }
        String images = random.nextBoolean() ? ""
            : ",{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}"
            + (random.nextBoolean() ? ",{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/a.png\"}}" : "");
        return tree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}" + images + "]}]}");
    }

    @Test
    void forAnyUsageARelayCouldSendTheResultIsWithinWhatWasReservedAndNeverEmpty() {
        Random random = new Random(20261024);
        for (int round = 0; round < 6000; round++) {
            JsonNode usage = randomUsage(random);
            Completion completion = randomAnswer(random, usage);
            JsonNode request = randomRequest(random);
            Caliber caliber = new Caliber(random.nextBoolean(), random.nextBoolean());
            Limits limits = new Limits(1 + random.nextLong(2_000_000), 1 + random.nextLong(100_000));
            JsonNode usageBefore = usage == null ? null : usage.deepCopy();
            JsonNode requestBefore = request.deepCopy();
            String context = "round " + round + " usage " + usage + " caliber " + caliber + " limits " + limits;

            Result result = RelayUsageNormalizer.normalize(completion, request, caliber, limits);
            PlatformUsage billed = result.usage();

            assertThat(billed.inputTokens()).as(context).isBetween(1L, limits.maxInputTokens());
            assertThat(billed.outputTokens()).as(context).isBetween(0L, limits.maxOutputTokens());
            assertThat(billed.cacheReadTokens()).as(context).isGreaterThanOrEqualTo(0);
            assertThat(billed.cacheWriteTokens()).as(context).isGreaterThanOrEqualTo(0);
            assertThat(BigInteger.valueOf(billed.cacheReadTokens()).add(BigInteger.valueOf(billed.cacheWriteTokens())))
                .as(context).isLessThanOrEqualTo(BigInteger.valueOf(billed.inputTokens()));
            assertThat(billed.reasoningTokens()).as(context).isBetween(0L, billed.outputTokens());
            boolean hasAnswer = !completion.content().isEmpty() || !completion.reasoningContent().isEmpty()
                || !completion.toolUses().isEmpty();
            if (hasAnswer) {
                assertThat(billed.outputTokens()).as(context).isGreaterThanOrEqualTo(1);
                assertThat(billed.totalTokens()).as(context).isGreaterThan(0);
            }
            // Invariant I2: the bounds are the reservation, so the cost cannot be above its estimate.
            assertThat(SONNET.cost(billed)).as(context).isLessThanOrEqualTo(SONNET.estimate(limits.maxInputTokens(), limits.maxOutputTokens()));
            assertThat(result.source()).as(context).isEqualTo(
                result.estimated() ? UsageSource.ESTIMATED : result.clamped() ? UsageSource.CLAMPED : UsageSource.REPORTED);
            // The inputs are read, never changed.
            assertThat(usage == null ? null : usage.toString()).as(context).isEqualTo(usageBefore == null ? null : usageBefore.toString());
            assertThat(request.toString()).as(context).isEqualTo(requestBefore.toString());
        }
    }
}
