package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformCompletionResult.StopReason;
import tech.starryapi.platform.core.PlatformCompletionResult.UsageSource;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The reading of a relay's answer and every row of the in-band error table of design 7.7: each failure with nothing
 * possibly generated (a recoverable 502 that another source may answer) and with something possibly generated (an
 * interruption that nothing may answer). A JSON body is the way to stay before the first sign of generation, because
 * every event of a stream is one; the thirty seconds are moved by the test clock.
 */
class ChatCompletionAccumulatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RELAY_TEXT = "RELAY-WORDS-THAT-MUST-NOT-LEAK";
    private static final Duration PAST_THIRTY_SECONDS = Duration.ofSeconds(31);

    private final AtomicLong clock = new AtomicLong();
    private final List<JsonNode> mapped = new ArrayList<>();
    private final Function<JsonNode, UpstreamException> mapper = error -> {
        mapped.add(error);
        return new UpstreamException(429, "mapped error", true);
    };

    private ChatCompletionAccumulator accumulator() {
        return new ChatCompletionAccumulator(JSON, mapper, clock::get, IncrementalSseDecoder.DEFAULT_MAX_BYTES);
    }

    private void thirtyOneSecondsPass() {
        clock.addAndGet(PAST_THIRTY_SECONDS.toNanos());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A stream of the chunks given, each as one event, ended with [DONE] unless it already is the last. */
    private static String stream(String... chunks) {
        StringBuilder body = new StringBuilder();
        for (String chunk : chunks) {
            body.append("data: ").append(chunk).append("\n\n");
        }
        return body.toString();
    }

    private static final String DONE = "[DONE]";
    private static final String ROLE = "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}";

    private static String delta(String delta) {
        return "{\"choices\":[{\"index\":0,\"delta\":" + delta + "}]}";
    }

    private static String finish(String reason) {
        return "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + reason + "\"}]}";
    }

    private Completion read(String body) {
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes(body));
        return accumulator.finish();
    }

    private UpstreamException failureOf(String body) {
        ChatCompletionAccumulator accumulator = accumulator();
        return failureOf(accumulator, body);
    }

    private static UpstreamException failureOf(ChatCompletionAccumulator accumulator, String body) {
        try {
            accumulator.acceptBytes(bytes(body));
            accumulator.finish();
        } catch (UpstreamException error) {
            return error;
        }
        throw new AssertionError("The body was read without a failure");
    }

    /** Recoverable 502, and nothing says the relay generated anything: another source may answer. */
    private static void assertNothingGenerated(UpstreamException error) {
        assertThat(error).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(error.getStatusCode()).isEqualTo(502);
        assertThat(error.isRecoverable()).isTrue();
        assertThat(error.isGenerationStarted()).isFalse();
    }

    /** Recoverable 502 that says generation may have started: nobody else is asked. */
    private static void assertInterrupted(UpstreamException error) {
        assertThat(error).isInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(error.getStatusCode()).isEqualTo(502);
        assertThat(error.isRecoverable()).isTrue();
        assertThat(error.isGenerationStarted()).isTrue();
    }

    // ---------------------------------------------------------------- the answer

    @Test
    void aJsonAnswerWithTextAndUsage() {
        Completion completion = read("""
            {"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},
            "finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":3,
            "prompt_tokens_details":{"cached_tokens":5}}}""");

        assertThat(completion.content()).isEqualTo("Hello");
        assertThat(completion.reasoningContent()).isEmpty();
        assertThat(completion.toolUses()).isEmpty();
        assertThat(completion.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(completion.usage().path("prompt_tokens").asLong()).isEqualTo(12);
        assertThat(completion.usage().path("completion_tokens").asLong()).isEqualTo(3);
        assertThat(completion.usage().path("prompt_tokens_details").path("cached_tokens").asLong()).isEqualTo(5);
    }

    @Test
    void anAnswerWithoutUsageHasAnEmptyUsageObject() {
        Completion completion = read(
            "{\"choices\":[{\"message\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}");

        assertThat(completion.usage().isObject()).isTrue();
        assertThat(completion.usage().size()).isZero();
    }

    @Test
    void contentCanBeAnArrayOfParts() {
        Completion completion = read("""
            {"choices":[{"message":{"content":[{"type":"text","text":"Hel"},"lo",{"type":"image_url","image_url":{}},
            {"type":"text","text":"!"}]},"finish_reason":"stop"}]}""");

        assertThat(completion.content()).isEqualTo("Hello!");
    }

    @Test
    void reasoningComesFromEitherFieldAndIsKeptApartFromTheText() {
        assertThat(read("{\"choices\":[{\"message\":{\"content\":\"A\",\"reasoning_content\":\"think\"},\"finish_reason\":\"stop\"}]}")
            .reasoningContent()).isEqualTo("think");
        Completion other = read("{\"choices\":[{\"message\":{\"content\":\"A\",\"reasoning\":\"mull\"},\"finish_reason\":\"stop\"}]}");
        assertThat(other.reasoningContent()).isEqualTo("mull");
        assertThat(other.content()).isEqualTo("A");
        // Both fields with the same words are one thought, not two.
        assertThat(read("{\"choices\":[{\"message\":{\"content\":\"A\",\"reasoning_content\":\"same\",\"reasoning\":\"same\"},\"finish_reason\":\"stop\"}]}")
            .reasoningContent()).isEqualTo("same");
    }

    @Test
    void aRefusalIsTheText() {
        assertThat(read("{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"I cannot help with that.\"},\"finish_reason\":\"stop\"}]}")
            .content()).isEqualTo("I cannot help with that.");
    }

    @Test
    void onlyTheFirstChoiceIsAnswered() {
        Completion completion = read("""
            {"choices":[{"index":1,"message":{"content":"second"},"finish_reason":"stop"},
            {"index":0,"message":{"content":"first"},"finish_reason":"stop"}]}""");

        assertThat(completion.content()).isEqualTo("first");
    }

    @Test
    void theStopReasonFollowsTheFinishReason() {
        String tool = "\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]";
        assertThat(read(message("\"content\":\"A\"", "stop")).stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(read(message("\"content\":\"A\"", "length")).stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(read(message("\"content\":\"A\"", "max_tokens")).stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(read(message("\"content\":\"A\"", "content_filter")).stopReason()).isEqualTo(StopReason.CONTENT_FILTER);
        assertThat(read(message("\"content\":null," + tool, "tool_calls")).stopReason()).isEqualTo(StopReason.TOOL_USE);
        // Tools make a stop a tool use, as the platform always renders it.
        assertThat(read(message("\"content\":\"A\"," + tool, "stop")).stopReason()).isEqualTo(StopReason.TOOL_USE);
        // A relay that says tool_calls without a call has an ordinary answer.
        assertThat(read(message("\"content\":\"A\"", "tool_calls")).stopReason()).isEqualTo(StopReason.END_TURN);
        // No finish reason at all in a JSON body: derived from what is in it.
        assertThat(read("{\"choices\":[{\"message\":{\"content\":\"A\"}}]}").stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(read("{\"choices\":[{\"message\":{" + tool + "}}]}").stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(read(message("\"content\":\"A\"", "STOP")).stopReason()).isEqualTo(StopReason.END_TURN);
    }

    @Test
    void theFinishReasonIsKeptAsTheRelayWroteItAndIsNullWhenItWroteNone() {
        assertThat(read(message("\"content\":\"A\"", "stop")).finishReason()).isEqualTo("stop");
        assertThat(read(message("\"content\":\"A\"", "STOP")).finishReason()).isEqualTo("STOP");
        assertThat(read(message("\"content\":\"A\"", "weird_reason")).finishReason()).isEqualTo("weird_reason");
        assertThat(read("{\"choices\":[{\"message\":{\"content\":\"A\"}}]}").finishReason()).isNull();
        assertThat(read("{\"choices\":[{\"message\":{\"content\":\"A\"},\"finish_reason\":null}]}").finishReason()).isNull();
        assertThat(read(stream(ROLE, delta("{\"content\":\"A\"}"), finish("length"), DONE)).finishReason()).isEqualTo("length");
        // A stream that ends with [DONE] and never said why has an answer and no reason.
        assertThat(read(stream(ROLE, delta("{\"content\":\"A\"}"), DONE)).finishReason()).isNull();
    }

    private static String message(String message, String finishReason) {
        return "{\"choices\":[{\"index\":0,\"message\":{" + message + "},\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    @Test
    void theResultCarriesWhatWasRead() {
        Completion completion = read(message("\"content\":\"Hi\",\"reasoning_content\":\"why\"", "length"));
        PlatformUsage usage = new PlatformUsage(10, 2, BigDecimal.ZERO, 0, 0, 0);

        PlatformCompletionResult result = completion.toResult(usage, UsageSource.CLAMPED, List.of("cache_control"));

        assertThat(result.content()).isEqualTo("Hi");
        assertThat(result.reasoningContent()).isEqualTo("why");
        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(result.usage()).isEqualTo(usage);
        assertThat(result.usageSource()).isEqualTo(UsageSource.CLAMPED);
        assertThat(result.degraded()).containsExactly("cache_control");
    }

    // ---------------------------------------------------------------- tool calls

    @Test
    void toolCallsOfAJsonAnswerAreOneEachEvenWhenTheyShareAnIdOrHaveNone() {
        Completion completion = read("""
            {"choices":[{"message":{"content":null,"tool_calls":[
            {"id":"call_0","type":"function","function":{"name":"Read","arguments":"{\\"path\\":\\"a\\"}"}},
            {"id":"call_0","type":"function","function":{"name":"Bash","arguments":"{\\"command\\":\\"ls\\"}"}},
            {"type":"function","function":{"name":"Idle","arguments":""}}]},"finish_reason":"tool_calls"}]}""");

        assertThat(completion.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(completion.toolUses()).extracting(PlatformToolUse::name).containsExactly("Read", "Bash", "Idle");
        assertThat(completion.toolUses()).extracting(PlatformToolUse::id).containsExactly("call_0", "call_0", "");
        PlatformToolUse first = completion.toolUses().get(0);
        assertThat(first.input().path("path").asString()).isEqualTo("a");
        assertThat(first.rawInput()).isEqualTo("{\"path\":\"a\"}");
        assertThat(first.type()).isEqualTo("function_call");
        assertThat(first.rawOutputItem().path("id").asString()).isEqualTo("call_0");
        assertThat(first.rawOutputItem().path("function").path("name").asString()).isEqualTo("Read");
        assertThat(first.rawOutputItem().path("function").path("arguments").asString()).isEqualTo("{\"path\":\"a\"}");
        PlatformToolUse idle = completion.toolUses().get(2);
        assertThat(idle.input().isObject()).isTrue();
        assertThat(idle.input().size()).isZero();
        assertThat(idle.rawInput()).isEqualTo("{}");
        assertThat(idle.rawOutputItem().has("id")).isFalse();
    }

    @Test
    void argumentsThatAreAnObjectInsteadOfAStringAreTaken() {
        Completion completion = read("""
            {"choices":[{"message":{"tool_calls":[{"id":"a","function":{"name":"f","arguments":{"x":1}}}]},"finish_reason":"tool_calls"}]}""");

        assertThat(completion.toolUses().get(0).input().path("x").asInt()).isEqualTo(1);
        assertThat(completion.toolUses().get(0).rawInput()).isEqualTo("{\"x\":1}");
    }

    @Test
    void aStreamAssemblesToolCallsByIndexFromFragments() {
        Completion completion = read(stream(
            ROLE,
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"Read\",\"arguments\":\"\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"pa\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"a\\\"}\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"\\\"ls\\\"}\"}}]}"),
            finish("tool_calls"),
            DONE));

        assertThat(completion.toolUses()).extracting(PlatformToolUse::name).containsExactly("Read", "Bash");
        assertThat(completion.toolUses()).extracting(PlatformToolUse::id).containsExactly("call_a", "call_b");
        assertThat(completion.toolUses().get(0).input().path("path").asString()).isEqualTo("a");
        assertThat(completion.toolUses().get(1).input().path("command").asString()).isEqualTo("ls");
        assertThat(completion.stopReason()).isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void aStreamWithoutIndexesAssemblesByIdAndThenByTheCallBefore() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"id\":\"x\",\"function\":{\"name\":\"One\",\"arguments\":\"{\\\"a\\\":\"}}]}"),
            delta("{\"tool_calls\":[{\"id\":\"y\",\"function\":{\"name\":\"Two\",\"arguments\":\"{}\"}}]}"),
            delta("{\"tool_calls\":[{\"id\":\"x\",\"function\":{\"arguments\":\"1}\"}}]}"),
            finish("tool_calls"),
            DONE));
        assertThat(completion.toolUses()).extracting(PlatformToolUse::name).containsExactly("One", "Two");
        assertThat(completion.toolUses().get(0).input().path("a").asInt()).isEqualTo(1);

        Completion unnamed = read(stream(
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"Only\",\"arguments\":\"{\\\"k\\\":\"}}]}"),
            delta("{\"tool_calls\":[{\"function\":{\"arguments\":\"2}\"}}]}"),
            finish("tool_calls"),
            DONE));
        assertThat(unnamed.toolUses()).hasSize(1);
        assertThat(unnamed.toolUses().get(0).input().path("k").asInt()).isEqualTo(2);
    }

    @Test
    void aNameRepeatedInEveryChunkIsTakenOnce() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"p\\\":\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"Read\",\"arguments\":\"1}\"}}]}"),
            finish("tool_calls")));

        assertThat(completion.toolUses()).hasSize(1);
        assertThat(completion.toolUses().get(0).name()).isEqualTo("Read");
        assertThat(completion.toolUses().get(0).input().path("p").asInt()).isEqualTo(1);
    }

    @Test
    void parallelCallsThatAllSayIndexZeroAreStillSeparateCalls() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"One\",\"arguments\":\"{\\\"x\\\":\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"1}\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"b\",\"function\":{\"name\":\"Two\",\"arguments\":\"{\\\"y\\\":\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"2}\"}}]}"),
            finish("tool_calls")));

        assertThat(completion.toolUses()).extracting(PlatformToolUse::name).containsExactly("One", "Two");
        assertThat(completion.toolUses().get(0).input().path("x").asInt()).isEqualTo(1);
        assertThat(completion.toolUses().get(1).input().path("y").asInt()).isEqualTo(2);
    }

    @Test
    void anEmptyToolCallEntryIsNotACall() {
        Completion completion = read(stream(
            delta("{\"content\":\"Hi\",\"tool_calls\":[{\"index\":0}]}"),
            finish("stop")));

        assertThat(completion.toolUses()).isEmpty();
        assertThat(completion.stopReason()).isEqualTo(StopReason.END_TURN);
    }

    @Test
    void toolArgumentsAreNeverReturnedAsRawArguments() {
        UpstreamException error = failureOf(message("\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"f\",\"arguments\":\"{broken\"}}]", "tool_calls"));
        assertInterrupted(error);

        Completion truncated = read(message("\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"f\",\"arguments\":\"{broken\"}}]", "length"));
        assertThat(truncated.toolUses()).isEmpty();
        assertThat(truncated.toString()).doesNotContain("_rawArguments");
    }

    @Test
    void aToolCallCutOffByTheLimitIsDroppedAndTheAnswerEndsForLength() {
        Completion completion = read(stream(
            delta("{\"content\":\"Working on it.\"}"),
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"Done\",\"arguments\":\"{}\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"Write\",\"arguments\":\"{\\\"content\\\":\\\"unfinis\"}}]}"),
            finish("length"),
            DONE));

        assertThat(completion.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(completion.toolUses()).extracting(PlatformToolUse::name).containsExactly("Done");
        assertThat(completion.content()).isEqualTo("Working on it.");
        assertThat(completion.callCut()).as("the answer lacks the call that was cut").isTrue();
    }

    @Test
    void anAnswerSaysThatACallWasCutOffOnlyWhenOneWas() {
        // Text that ran out, a whole call, an answer that ended by itself: nothing was lost to the limit that is not in the answer.
        assertThat(read(message("\"content\":\"A\"", "length")).callCut()).isFalse();
        assertThat(read(message("\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]", "length")).callCut()).isFalse();
        assertThat(read(message("\"content\":\"A\"", "stop")).callCut()).isFalse();
        assertThat(read(message("\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]", "tool_calls")).callCut()).isFalse();
        // The only call of the answer, cut in its arguments, and nothing else of the answer: the same loss.
        Completion onlyCall = read(message("\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"cmd\\\":\\\"ec\"}}]", "length"));
        assertThat(onlyCall.callCut()).isTrue();
        assertThat(onlyCall.toolUses()).isEmpty();
        assertThat(onlyCall.content()).isEmpty();
    }

    @Test
    void aToolCallTheRelayCutOffWithoutALimitIsAnInterruption() {
        assertInterrupted(failureOf(stream(
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"b\",\"function\":{\"name\":\"Write\",\"arguments\":\"{\\\"content\\\":\\\"unfinis\"}}]}"),
            finish("stop"),
            DONE)));
    }

    @Test
    void argumentsThatAreNotAnObjectAndCallsWithoutANameAreNotUsable() {
        for (String arguments : List.of("[1]", "\\\"text\\\"", "null", "7")) {
            assertInterrupted(failureOf(message(
                "\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"f\",\"arguments\":\"" + arguments + "\"}}]", "tool_calls")));
        }
        assertInterrupted(failureOf(message("\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"\",\"arguments\":\"{}\"}}]", "tool_calls")));
        assertInterrupted(failureOf(message("\"content\":\"text\",\"tool_calls\":[{\"id\":\"a\",\"function\":{\"arguments\":\"{}\"}}]", "stop")));
        // Unusable calls ending for length are dropped just the same.
        Completion completion = read(message("\"tool_calls\":[{\"id\":\"a\",\"function\":{\"arguments\":\"{}\"}}]", "length"));
        assertThat(completion.toolUses()).isEmpty();
        assertThat(completion.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
    }

    // ---------------------------------------------------------------- streams and usage

    @Test
    void aStreamIsReadWhereverItIsCutIncludingInsideACharacter() {
        String body = stream(
            ROLE,
            delta("{\"content\":\"Hel\"}"),
            delta("{\"content\":\"lo 你好 😀\"}"),
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}",
            DONE);
        byte[] bytes = bytes(body);
        for (int piece : new int[] {1, 2, 3, 5, 11, 64, bytes.length}) {
            ChatCompletionAccumulator accumulator = accumulator();
            for (int offset = 0; offset < bytes.length; offset += piece) {
                accumulator.acceptBytes(bytes, offset, Math.min(piece, bytes.length - offset));
            }
            Completion completion = accumulator.finish();

            assertThat(completion.content()).as("pieces of %d bytes", piece).isEqualTo("Hello 你好 😀");
            assertThat(completion.stopReason()).isEqualTo(StopReason.END_TURN);
            assertThat(completion.usage().path("completion_tokens").asLong()).isEqualTo(4);
        }
    }

    @Test
    void usageOfAllChunksIsMergedToTheLargestValuePerField() {
        Completion completion = read(stream(
            "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}],\"usage\":{\"prompt_tokens\":100000,"
                + "\"completion_tokens\":0,\"prompt_tokens_details\":{\"cached_tokens\":90000}}}",
            delta("{\"content\":\"ok\"}"),
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":42,"
                + "\"prompt_tokens_details\":{\"audio_tokens\":null},\"cache_read_input_tokens\":7}}",
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":99,\"completion_tokens\":40,\"cost\":0.25}}",
            DONE));

        JsonNode usage = completion.usage();
        assertThat(usage.path("prompt_tokens").asLong()).isEqualTo(100000);
        assertThat(usage.path("completion_tokens").asLong()).isEqualTo(42);
        assertThat(usage.path("prompt_tokens_details").path("cached_tokens").asLong()).isEqualTo(90000);
        assertThat(usage.path("prompt_tokens_details").has("audio_tokens")).isFalse();
        assertThat(usage.path("cache_read_input_tokens").asLong()).isEqualTo(7);
        assertThat(usage.path("cost").asDouble()).isEqualTo(0.25);
    }

    @Test
    void aStreamIsCompleteWithAFinishReasonOrDoneAlone() {
        assertThat(read(stream(delta("{\"content\":\"a\"}"), finish("stop"))).content()).isEqualTo("a");
        Completion noFinishReason = read(stream(delta("{\"content\":\"a\"}"), DONE));
        assertThat(noFinishReason.content()).isEqualTo("a");
        assertThat(noFinishReason.stopReason()).isEqualTo(StopReason.END_TURN);
        // What follows [DONE] is not read.
        assertThat(read(stream(delta("{\"content\":\"a\"}"), DONE, "not json at all")).content()).isEqualTo("a");
    }

    @Test
    void commentsAndLeadingBlankLinesDoNotChangeTheAnswer() {
        Completion completion = read("\n\n: ping\n\n" + stream(delta("{\"content\":\"a\"}"), finish("stop"), DONE));

        assertThat(completion.content()).isEqualTo("a");
    }

    @Test
    void aJsonBodyAnswersAStreamRequestAndAStreamAnswersAJsonRequest() {
        // Where a JSON body was asked for and a stream came back, and the other way round: one reader for both.
        ChatCompletionAccumulator json = accumulator();
        byte[] body = bytes("﻿ \n" + message("\"content\":\"Hi 你\"", "stop"));
        for (int offset = 0; offset < body.length; offset += 3) {
            json.acceptBytes(body, offset, Math.min(3, body.length - offset));
        }
        assertThat(json.finish().content()).isEqualTo("Hi 你");

        assertThat(read("  \r\n" + stream(delta("{\"content\":\"Hi\"}"), finish("stop"), DONE)).content()).isEqualTo("Hi");
    }

    @Test
    void anErrorEventWhoseDataIsTheErrorItselfIsAnErrorToo() {
        UpstreamException error = failureOf("event: error\ndata: {\"message\":\"overloaded\",\"code\":529}\n\n");

        assertThat(error.getStatusCode()).isEqualTo(429);
        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).path("code").asInt()).isEqualTo(529);
    }

    // ---------------------------------------------------------------- what the relay may have done

    @Test
    void nothingMayHaveBeenGeneratedBeforeAnythingIsReceived() {
        ChatCompletionAccumulator accumulator = accumulator();

        assertThat(accumulator.started()).isFalse();
        assertThat(accumulator.possiblyGenerated()).isFalse();
    }

    @Test
    void aStreamEventEvenWithOnlyTheRoleMeansItMayBeGenerating() {
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes("data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n"));

        assertThat(accumulator.started()).isFalse();
        assertThat(accumulator.possiblyGenerated()).isTrue();
    }

    @Test
    void textReasoningAToolCallOrCompletionTokensMeanItStarted() {
        for (String chunk : List.of(
            delta("{\"content\":\"a\"}"),
            delta("{\"reasoning_content\":\"a\"}"),
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"c\"}]}"),
            "{\"choices\":[],\"usage\":{\"completion_tokens\":1}}",
            "{\"choices\":[],\"usage\":{\"output_tokens\":1}}")) {
            ChatCompletionAccumulator accumulator = accumulator();
            accumulator.acceptBytes(bytes(stream(chunk)));

            assertThat(accumulator.started()).as(chunk).isTrue();
            assertThat(accumulator.possiblyGenerated()).isTrue();
        }
    }

    @Test
    void emptyTextAndZeroCompletionTokensDoNotCount() {
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes(stream(
            delta("{\"content\":\"\",\"reasoning_content\":\"\",\"tool_calls\":[]}"),
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":0}}")));

        assertThat(accumulator.started()).isFalse();
    }

    @Test
    void doneIsKnownWhenTheStreamHasSaidIt() {
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes(stream(delta("{\"content\":\"a\"}"), finish("stop"))));
        assertThat(accumulator.isDone()).isFalse();

        accumulator.acceptBytes(bytes(stream(DONE)));

        assertThat(accumulator.isDone()).isTrue();
        assertThat(accumulator.finish().content()).isEqualTo("a");
    }

    @Test
    void aKeepAliveCommentMeansItMayBeGenerating() {
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes(": OPENROUTER PROCESSING\n\n"));

        assertThat(accumulator.started()).isFalse();
        assertThat(accumulator.possiblyGenerated()).isTrue();
    }

    @Test
    void thirtySecondsAfterTheRequestWasWrittenItMayBeGenerating() {
        ChatCompletionAccumulator accumulator = accumulator();
        clock.addAndGet(Duration.ofSeconds(20).toNanos());
        accumulator.requestWritten();
        clock.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(accumulator.possiblyGenerated()).isFalse();

        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(accumulator.possiblyGenerated()).isTrue();
        assertThat(accumulator.started()).isFalse();
    }

    // ---------------------------------------------------------------- in-band errors (7.7)

    @Test
    void anErrorInASuccessfulJsonBodyBeforeAnyOutputIsMappedByTheMapper() {
        UpstreamException error = failureOf("{\"error\":{\"message\":\"" + RELAY_TEXT + "\",\"type\":\"rate_limit_error\"}}");

        assertThat(error.getStatusCode()).isEqualTo(429);
        assertThat(error.isGenerationStarted()).isFalse();
        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).path("type").asString()).isEqualTo("rate_limit_error");
    }

    @Test
    void thatStaysSoEvenAfterThirtySeconds() {
        ChatCompletionAccumulator accumulator = accumulator();
        thirtyOneSecondsPass();

        assertThat(accumulator.possiblyGenerated()).isTrue();
        UpstreamException error = failureOf(accumulator, "{\"error\":{\"message\":\"slow\"}}");
        assertThat(error.getStatusCode()).isEqualTo(429);
        assertThat(error.isGenerationStarted()).isFalse();
    }

    @Test
    void anErrorEventAfterOnlyTheRoleChunkIsMappedToo() {
        ChatCompletionAccumulator accumulator = accumulator();

        UpstreamException error = failureOf(accumulator, stream(ROLE, "{\"error\":{\"message\":\"quota\",\"code\":\"insufficient_quota\"}}"));

        assertThat(accumulator.possiblyGenerated()).isTrue();
        assertThat(error.getStatusCode()).isEqualTo(429);
        assertThat(mapped.get(0).path("code").asString()).isEqualTo("insufficient_quota");
    }

    @Test
    void anErrorAfterTextOrCompletionTokensIsAnInterruption() {
        assertInterrupted(failureOf(stream(delta("{\"content\":\"a\"}"), "{\"error\":{\"message\":\"boom\"}}")));
        assertInterrupted(failureOf(stream(delta("{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"f\"}}]}"),
            "{\"error\":{\"message\":\"boom\"}}")));
        assertInterrupted(failureOf(stream("{\"choices\":[],\"usage\":{\"completion_tokens\":5}}", "{\"error\":{\"message\":\"boom\"}}")));
        // The chunk that carries the output and the error is one chunk.
        assertInterrupted(failureOf(stream("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}}],\"error\":{\"message\":\"boom\"}}")));
        assertThat(mapped).isEmpty();
    }

    @Test
    void theErrorMemberCanBeAStringAndANullOneIsNoError() {
        assertThat(failureOf("{\"error\":\"plain words\"}").getStatusCode()).isEqualTo(429);
        assertThat(mapped.get(0).isString()).isTrue();

        assertThat(read("{\"error\":null,\"choices\":[{\"message\":{\"content\":\"fine\"},\"finish_reason\":\"stop\"}]}").content())
            .isEqualTo("fine");
    }

    @Test
    void aMapperThatFailsOrAnswersNothingStillGivesAFixedRecoverableError() {
        ChatCompletionAccumulator throwing = new ChatCompletionAccumulator(JSON, error -> {
            throw new IllegalStateException(RELAY_TEXT);
        }, clock::get, 1024);
        UpstreamException failed = failureOf(throwing, "{\"error\":{\"message\":\"x\"}}");
        assertNothingGenerated(failed);
        assertThat(failed.getMessage()).doesNotContain(RELAY_TEXT);

        ChatCompletionAccumulator silent = new ChatCompletionAccumulator(JSON, error -> null, clock::get, 1024);
        assertNothingGenerated(failureOf(silent, "{\"error\":{\"message\":\"x\"}}"));
    }

    @Test
    void aFinishReasonOfErrorIsNothingBeforeAndAnInterruptionOnceItMayBeGenerating() {
        String body = message("\"content\":\"\"", "error");
        assertNothingGenerated(failureOf(body));

        ChatCompletionAccumulator slow = accumulator();
        thirtyOneSecondsPass();
        assertInterrupted(failureOf(slow, body));

        // In a stream the chunk that says so is an event of its own.
        assertInterrupted(failureOf(stream(finish("error"))));
        assertInterrupted(failureOf(stream(delta("{\"content\":\"a\"}"), finish("error"))));
    }

    @Test
    void noChoicesAndNoUsageIsNothingBeforeAndAnInterruptionOnceItMayBeGenerating() {
        assertNothingGenerated(failureOf("{\"id\":\"x\",\"object\":\"chat.completion\"}"));
        assertNothingGenerated(failureOf("{\"choices\":[]}"));

        ChatCompletionAccumulator slow = accumulator();
        thirtyOneSecondsPass();
        assertInterrupted(failureOf(slow, "{\"id\":\"x\"}"));

        assertInterrupted(failureOf(stream("{\"id\":\"x\"}", DONE)));
    }

    @Test
    void usageWithoutChoicesIsNoAnswerEitherUnlessItHasCompletionTokensInWhichCaseItIsOutputThatCannotBeRead() {
        assertNothingGenerated(failureOf("{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":0}}"));
        assertInterrupted(failureOf("{\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":7}}"));
        assertInterrupted(failureOf(stream("{\"choices\":[],\"usage\":{\"completion_tokens\":7}}", DONE)));
    }

    @Test
    void anEmptySuccessIsNothingBeforeAndAnInterruptionOnceItMayBeGenerating() {
        String body = message("\"role\":\"assistant\",\"content\":\"\"", "stop") .replace("}]}", "}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":0}}");
        assertNothingGenerated(failureOf(body));
        assertNothingGenerated(failureOf(message("\"content\":null", "stop")));
        assertNothingGenerated(failureOf(message("\"content\":[],\"tool_calls\":[]", "stop")));

        ChatCompletionAccumulator slow = accumulator();
        thirtyOneSecondsPass();
        assertInterrupted(failureOf(slow, body));

        // A stream shows the role chunk first, which is a sign of life.
        assertInterrupted(failureOf(stream(ROLE, finish("stop"), DONE)));
    }

    @Test
    void emptyTextWithCompletionTokensIsOutputThatCannotBeRead() {
        assertInterrupted(failureOf("{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":12}}"));
        assertInterrupted(failureOf(stream(ROLE, "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":12}}", DONE)));
    }

    @Test
    void aStreamThatEndsWithoutFinishReasonOrDoneIsNothingBeforeAnyEventAndAnInterruptionAfter() {
        assertNothingGenerated(failureOf(""));
        assertNothingGenerated(failureOf("   \n"));

        // Only a comment so far: it is sign of life.
        assertInterrupted(failureOf(": ping\n\n"));
        // Only the role chunk, then the end of the body.
        assertInterrupted(failureOf(stream(ROLE)));
        // Halfway through the text.
        assertInterrupted(failureOf(stream(delta("{\"content\":\"half an ans\"}"))));
        // A truncated last event is read as far as it goes and the stream is still unfinished.
        assertInterrupted(failureOf("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x"));

        ChatCompletionAccumulator slow = accumulator();
        thirtyOneSecondsPass();
        assertInterrupted(failureOf(slow, ""));
    }

    @Test
    void aBodyThatIsNeitherJsonNorAStreamIsNothingBeforeAndAnInterruptionOnceItMayBeGenerating() {
        String html = "<html><body><h1>502 Bad Gateway</h1>" + RELAY_TEXT + "</body></html>";
        UpstreamException error = failureOf(html);
        assertNothingGenerated(error);
        assertThat(error.getMessage()).doesNotContain(RELAY_TEXT);

        ChatCompletionAccumulator slow = accumulator();
        thirtyOneSecondsPass();
        assertInterrupted(failureOf(slow, html));
    }

    @Test
    void dataThatCannotBeParsedIsNothingInAJsonBodyAndAnInterruptionOnceItMayBeGenerating() {
        assertNothingGenerated(failureOf("{\"id\":\"x\",\"obj"));
        assertNothingGenerated(failureOf("[1,2]"));
        assertNothingGenerated(failureOf("[]"));

        ChatCompletionAccumulator slow = accumulator();
        thirtyOneSecondsPass();
        assertInterrupted(failureOf(slow, "{\"id\":\"x\",\"obj"));
    }

    /** A body that is sent whole is sent when the work is done: one that names its choices was generated, and is cut or damaged. */
    @Test
    void aJsonBodyThatNamesItsChoicesButCannotBeReadIsAnInterruptionBecauseItWasGenerated() {
        assertInterrupted(failureOf("{\"choices\":[{\"message\":{\"content\":\"cut"));
        assertInterrupted(failureOf("{\"id\":\"x\",\"choices\":[{\"message\":{\"content\":\"a full answer\"},\"finish_reason\":\"stop\"}"));

        // A whole answer with a damaged byte pair in its text.
        byte[] head = bytes("{\"choices\":[{\"message\":{\"content\":\"bad ");
        byte[] tail = bytes("\"},\"finish_reason\":\"stop\"}]}");
        byte[] damaged = new byte[head.length + 2 + tail.length];
        System.arraycopy(head, 0, damaged, 0, head.length);
        damaged[head.length] = (byte) 0xC3;
        damaged[head.length + 1] = (byte) 0x28;
        System.arraycopy(tail, 0, damaged, head.length + 2, tail.length);
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(damaged);
        assertThatThrownBy(accumulator::finish).isInstanceOfSatisfying(UpstreamException.class,
            ChatCompletionAccumulatorTest::assertInterrupted);
    }

    @Test
    void aJsonBodyThatIsNoAnswerAndCannotBeReadIsStillNothing() {
        assertNothingGenerated(failureOf("{\"error\":{\"message\":\"cut"));
        assertNothingGenerated(failureOf("{\"detail\":\"nope\""));
        // An error can have a usage of its own and a message that talks about completions: neither is an answer.
        assertNothingGenerated(failureOf("{\"error\":{\"message\":\"no such route for chat.completions\",\"usage\":{\"quota\":1"));
    }

    /**
     * A body is sent whole when the relay has finished, and one that is cut short (a relay that pipes the body of its
     * upstream ends cleanly wherever the upstream broke) can end before it reaches {@code choices}. It says what it is by
     * the object or the id it begins with; asking another source would generate the answer a second time.
     */
    @Test
    void aJsonBodyCutBeforeItsChoicesIsAnInterruptionWhenItSaysItIsACompletion() {
        for (String cut : List.of(
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"claude\",\"cho",
            "{\"object\": \"chat.completion\",\"id\":\"x\",\"cre",
            "{\"id\" : \"chatcmpl-9\",\"mod",
            "{\"id\":\"x\",\"model\":\"m\",\"usage\":{\"completion_tokens\":5",
            "{\"id\":\"x\",\"finish_reason\":\"stop")) {
            assertInterrupted(failureOf(cut));
            assertThat(cut).isNotBlank();
        }
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes("{\"id\":\"x\",\"object\":\"cha"));
        assertThat(accumulator.possiblyGenerated()).isFalse();
        assertThatThrownBy(accumulator::finish).isInstanceOfSatisfying(UpstreamException.class,
            ChatCompletionAccumulatorTest::assertNothingGenerated);
    }

    /** What follows the first value of a JSON body is not part of the answer: a whole answer is not lost to it. */
    @Test
    void aCompleteJsonAnswerFollowedByAnythingIsTheAnswer() {
        String answer = message("\"content\":\"Hi\"", "stop");
        assertThat(read(answer + "\n\ndata: [DONE]\n\n").content()).isEqualTo("Hi");
        assertThat(read(answer + "   \n").content()).isEqualTo("Hi");
        assertThat(read(answer + "{\"another\":1}").content()).isEqualTo("Hi");

        ChatCompletionAccumulator damaged = accumulator();
        damaged.acceptBytes(bytes(answer));
        damaged.acceptBytes(new byte[] {'\n', (byte) 0xC3, (byte) 0x28});
        assertThat(damaged.finish().content()).isEqualTo("Hi");
    }

    @Test
    void aJsonBodyThatIsCutIsNeverTheAnswerWhateverFollowsTheCut() {
        String answer = message("\"content\":\"Hi\"", "stop");
        assertInterrupted(failureOf(answer.substring(0, answer.length() - 2)));
    }

    /** The relay says the model spent its tokens or was filtered: that is generation, whatever it chose to show of it. */
    @Test
    void aFinishReasonOfLengthOrAFilterWithNothingToShowIsAnInterruptionNotAnEmptyAnswerToTryElsewhere() {
        for (String reason : List.of("length", "max_tokens", "LENGTH", "content_filter")) {
            // A small budget spent on reasoning the relay does not show, and a relay that strips the usage with it.
            assertInterrupted(failureOf(message("\"role\":\"assistant\",\"content\":\"\"", reason)));
            assertInterrupted(failureOf(message("\"content\":null", reason)));
            assertInterrupted(failureOf(message("\"content\":\"\"", reason).replace("}]}", "}],\"usage\":{\"prompt_tokens\":50,\"total_tokens\":250}}")));
            // And in a stream, where the finish chunk has nothing in it.
            assertInterrupted(failureOf(stream(finish(reason), DONE)));
        }
    }

    @Test
    void aFinishReasonOfLengthOrAFilterMeansItStartedAndOneOfStopDoesNot() {
        for (String reason : List.of("length", "max_tokens", "content_filter")) {
            ChatCompletionAccumulator accumulator = accumulator();
            accumulator.acceptBytes(bytes(stream(finish(reason))));

            assertThat(accumulator.started()).as(reason).isTrue();
            assertThat(accumulator.possiblyGenerated()).isTrue();
        }
        for (String reason : List.of("stop", "tool_calls")) {
            ChatCompletionAccumulator accumulator = accumulator();
            accumulator.acceptBytes(bytes(stream(finish(reason))));

            assertThat(accumulator.started()).as(reason).isFalse();
        }
        // An empty answer that stopped on its own says nothing about what it cost, and is still nothing to pay for.
        assertNothingGenerated(failureOf(message("\"content\":\"\"", "stop")));
    }

    @Test
    void anAnswerThatRanOutOfTokensWithTextIsStillTheAnswerItWas() {
        Completion completion = read(message("\"content\":\"Half an ans\"", "length"));

        assertThat(completion.content()).isEqualTo("Half an ans");
        assertThat(completion.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    void anEventThatCannotBeParsedIsASignOfLifeAndSoAnInterruption() {
        // The malformed event is an event the relay sent: whether it was generating cannot be told.
        assertInterrupted(failureOf("data: {not json\n\n"));
        assertInterrupted(failureOf(stream(ROLE) + "data: {not json\n\n"));
        assertInterrupted(failureOf(stream(delta("{\"content\":\"a\"}")) + "data: <html>\n\n"));
        assertInterrupted(failureOf("data: 12\n\n"));
        assertInterrupted(failureOf("data: [1]\n\n"));
    }

    @Test
    void aStreamThatIsNotUtf8IsNothingBeforeAnyEventAndAnInterruptionAfter() {
        ChatCompletionAccumulator first = accumulator();
        UpstreamException before = null;
        try {
            first.acceptBytes(new byte[] {'x', (byte) 0xC3, (byte) 0x28});
            first.finish();
        } catch (UpstreamException error) {
            before = error;
        }
        assertThat(before).isNotNull();
        assertNothingGenerated(before);

        ChatCompletionAccumulator later = accumulator();
        later.acceptBytes(bytes(stream(delta("{\"content\":\"a\"}"))));
        assertThatThrownBy(() -> later.acceptBytes(new byte[] {'d', (byte) 0xC3, (byte) 0x28}))
            .isInstanceOfSatisfying(UpstreamException.class, ChatCompletionAccumulatorTest::assertInterrupted);
    }

    /**
     * Whether the events and the damaged bytes arrive in one read or two is the network's doing: one read must not hide
     * from the failure what the relay had already said, or the same answer is generated twice depending on the segments.
     */
    @Test
    void badBytesInTheSamePieceAsEventsAreAnInterruptionLikeBadBytesInTheNextPiece() {
        String events = stream(ROLE, delta("{\"content\":\"Here is the plan: \"}"));
        byte[] head = bytes(events + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"");
        byte[] onePiece = new byte[head.length + 2];
        System.arraycopy(head, 0, onePiece, 0, head.length);
        onePiece[head.length] = (byte) 0xC3;
        onePiece[head.length + 1] = (byte) 0x28;

        ChatCompletionAccumulator oneRead = accumulator();
        assertThatThrownBy(() -> oneRead.acceptBytes(onePiece)).isInstanceOfSatisfying(UpstreamException.class,
            ChatCompletionAccumulatorTest::assertInterrupted);
        assertThat(oneRead.started()).isTrue();
        assertThat(oneRead.possiblyGenerated()).isTrue();

        ChatCompletionAccumulator twoReads = accumulator();
        twoReads.acceptBytes(onePiece, 0, head.length);
        assertThatThrownBy(() -> twoReads.acceptBytes(onePiece, head.length, 2)).isInstanceOfSatisfying(UpstreamException.class,
            ChatCompletionAccumulatorTest::assertInterrupted);
        assertThat(twoReads.started()).isEqualTo(oneRead.started());
    }

    /** A whole answer, as the relay writes it: the text, the reason, the usage, and [DONE]. */
    private static final String WHOLE_ANSWER = stream(
        ROLE,
        delta("{\"content\":\"Hello\"}"),
        "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}",
        DONE);

    private static byte[] join(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static void assertTheWholeAnswer(Completion completion) {
        assertThat(completion.content()).isEqualTo("Hello");
        assertThat(completion.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(completion.usage().path("completion_tokens").asLong()).isEqualTo(5);
    }

    /**
     * Where the network cuts the stream is not a fact about the relay. Whatever follows [DONE] is outside the answer,
     * whether it comes in the read that carried [DONE] or in a later one: the answer was whole and paid for, and a
     * client that is told it was interrupted asks again.
     */
    @Test
    void bytesThatAreNotTextAfterDoneInTheSamePieceAreNotPartOfTheAnswer() {
        byte[][] strays = {
            {(byte) 0xFF},
            {(byte) 0xC3, (byte) 0x28},
            {'\n', (byte) 0x80, (byte) 0x80, '\n'},
            {(byte) 0xC0, (byte) 0xAF}
        };
        for (byte[] stray : strays) {
            ChatCompletionAccumulator accumulator = accumulator();
            accumulator.acceptBytes(join(bytes(WHOLE_ANSWER), stray));

            assertThat(accumulator.isDone()).isTrue();
            assertTheWholeAnswer(accumulator.finish());
        }
    }

    @Test
    void theFirstBytesOfACharacterAfterDoneInTheSamePieceAreNotPartOfTheAnswerEither() {
        // A keep-alive comment `: <two Chinese characters>` cut after the first byte of the first one, and a lone lead byte.
        byte[] comment = bytes(": \u7ed3\u675f\n\n");
        byte[][] strays = {
            {(byte) 0xE4},
            {(byte) 0xF0, (byte) 0x9F},
            Arrays.copyOf(comment, 3),
            Arrays.copyOf(comment, 4)
        };
        for (byte[] stray : strays) {
            ChatCompletionAccumulator accumulator = accumulator();
            accumulator.acceptBytes(join(bytes(WHOLE_ANSWER), stray));

            assertThat(accumulator.isDone()).isTrue();
            assertTheWholeAnswer(accumulator.finish());
        }
    }

    @Test
    void anythingInALaterPieceAfterDoneIsNotReadAtAll() {
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes(WHOLE_ANSWER));

        accumulator.acceptBytes(new byte[] {(byte) 0xFF});
        accumulator.acceptBytes(new byte[] {(byte) 0xC3, (byte) 0x28});
        accumulator.acceptBytes(new byte[] {(byte) 0xE4});
        accumulator.acceptBytes(bytes("data: {not json\n\n"));

        assertTheWholeAnswer(accumulator.finish());
    }

    /** The segmentation of the same bytes makes no difference to the verdict. */
    @Test
    void theAnswerIsTheSameWhereverTheStrayBytesAfterDoneAreCut() {
        byte[] bytes = join(bytes(WHOLE_ANSWER), new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xE4});
        for (int cut = 1; cut < bytes.length; cut++) {
            ChatCompletionAccumulator accumulator = accumulator();
            accumulator.acceptBytes(bytes, 0, cut);
            if (!accumulator.isDone()) {
                accumulator.acceptBytes(bytes, cut, bytes.length - cut);
            }
            assertTheWholeAnswer(accumulator.finish());
        }
    }

    @Test
    void badBytesBeforeDoneAreStillABrokenStream() {
        // The other half: nothing is forgiven that comes before the answer is whole.
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes(stream(ROLE, delta("{\"content\":\"Hello\"}"))));

        assertThatThrownBy(() -> accumulator.acceptBytes(join(new byte[] {(byte) 0xC3, (byte) 0x28}, bytes(stream(DONE)))))
            .isInstanceOfSatisfying(UpstreamException.class, ChatCompletionAccumulatorTest::assertInterrupted);
    }

    @Test
    void aKeepAliveOrAnEventOfTheSamePieceAsBadBytesIsASignOfLifeToo() {
        for (String before : List.of(": ping\n\n", stream(ROLE))) {
            byte[] head = bytes(before + "data: ");
            byte[] piece = new byte[head.length + 2];
            System.arraycopy(head, 0, piece, 0, head.length);
            piece[head.length] = (byte) 0xC3;
            piece[head.length + 1] = (byte) 0x28;
            ChatCompletionAccumulator accumulator = accumulator();

            assertThatThrownBy(() -> accumulator.acceptBytes(piece)).as(before).isInstanceOfSatisfying(UpstreamException.class,
                ChatCompletionAccumulatorTest::assertInterrupted);
            assertThat(accumulator.possiblyGenerated()).isTrue();
        }
    }

    @Test
    void badBytesWithNoEventBeforeThemInThePieceAreStillNothing() {
        // A line that was started and not finished is no event: the case the first test of this kind has always covered.
        ChatCompletionAccumulator accumulator = accumulator();
        byte[] piece = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n', '\n'};

        assertThatThrownBy(() -> accumulator.acceptBytes(piece)).isInstanceOfSatisfying(UpstreamException.class,
            ChatCompletionAccumulatorTest::assertNothingGenerated);
    }

    @Test
    void anEventBeforeBadBytesThatFailsOnItsOwnIsTheFailureOfThePiece() {
        byte[] head = bytes("data: {oops\n\ndata: ");
        byte[] piece = new byte[head.length + 2];
        System.arraycopy(head, 0, piece, 0, head.length);
        piece[head.length] = (byte) 0xC3;
        piece[head.length + 1] = (byte) 0x28;
        ChatCompletionAccumulator accumulator = accumulator();

        UpstreamException error = null;
        try {
            accumulator.acceptBytes(piece);
        } catch (UpstreamException thrown) {
            error = thrown;
        }
        assertThat(error).isNotNull();
        assertInterrupted(error);
        assertThat(error.getMessage()).contains("not a completion");
        assertThatThrownBy(accumulator::finish).isSameAs(error);
    }

    @Test
    void aBodyThatEndsInTheMiddleOfACharacterIsABrokenStream() {
        byte[] text = bytes("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\u4f60");
        ChatCompletionAccumulator before = accumulator();
        before.acceptBytes(text, 0, text.length - 1);
        assertThatThrownBy(before::finish).isInstanceOfSatisfying(UpstreamException.class,
            ChatCompletionAccumulatorTest::assertNothingGenerated);

        // Once an event has been seen it is an interruption.
        ChatCompletionAccumulator after = accumulator();
        after.acceptBytes(bytes(stream(ROLE)));
        after.acceptBytes(text, 0, text.length - 1);
        assertThatThrownBy(after::finish).isInstanceOfSatisfying(UpstreamException.class,
            ChatCompletionAccumulatorTest::assertInterrupted);
    }

    @Test
    void moreThanTheSizeLimitIsAnInterruptionWhateverTheBodyIs() {
        ChatCompletionAccumulator stream = new ChatCompletionAccumulator(JSON, mapper, clock::get, 64);
        assertInterrupted(failureOf(stream, stream(delta("{\"content\":\"" + "x".repeat(100) + "\"}"))));

        ChatCompletionAccumulator json = new ChatCompletionAccumulator(JSON, mapper, clock::get, 64);
        assertInterrupted(failureOf(json, message("\"content\":\"" + "x".repeat(100) + "\"", "stop")));

        ChatCompletionAccumulator blanks = new ChatCompletionAccumulator(JSON, mapper, clock::get, 64);
        assertInterrupted(failureOf(blanks, " ".repeat(100)));
    }

    @Test
    void afterAFailureNothingIsAccepted() {
        ChatCompletionAccumulator accumulator = accumulator();
        UpstreamException error = failureOf(accumulator, stream(delta("{\"content\":\"a\"}"), finish("error")));

        assertThatThrownBy(() -> accumulator.acceptBytes(bytes("data: more\n\n"))).isSameAs(error);
        assertThatThrownBy(accumulator::finish).isSameAs(error);
    }

    @Test
    void anAnswerIsReadOnce() {
        ChatCompletionAccumulator accumulator = accumulator();
        accumulator.acceptBytes(bytes(message("\"content\":\"a\"", "stop")));
        accumulator.finish();

        assertThatThrownBy(accumulator::finish).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accumulator.acceptBytes(bytes("x"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nothingTheRelayWroteReachesAnyMessage() {
        List<String> bodies = List.of(
            "{\"error\":\"" + RELAY_TEXT + "\"}",
            stream(delta("{\"content\":\"" + RELAY_TEXT + "\"}"), "{\"error\":{\"message\":\"" + RELAY_TEXT + "\"}}"),
            stream(delta("{\"content\":\"a\"}"), "data: " + RELAY_TEXT),
            "data: " + RELAY_TEXT + "\n\n",
            "{\"choices\":[{\"message\":{\"content\":\"" + RELAY_TEXT + "\"}}",
            message("\"tool_calls\":[{\"id\":\"" + RELAY_TEXT + "\",\"function\":{\"name\":\"" + RELAY_TEXT + "\",\"arguments\":\"" + RELAY_TEXT + "\"}}]", "stop"),
            message("\"content\":\"" + RELAY_TEXT + "\"", "error"),
            RELAY_TEXT);
        for (String body : bodies) {
            UpstreamException error = failureOf(body);

            assertThat(error.getMessage()).as(body).doesNotContain(RELAY_TEXT);
        }
    }

    // ---------------------------------------------------------------- an error beside a finish reason of error

    /** The chunk OpenRouter ends a stream with when its provider fails: the error and the finish reason in one. */
    private static final String ERROR_AND_FINISH = "{\"error\":{\"code\":\"server_error\",\"message\":\"Provider disconnected unexpectedly\"},"
        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"error\"}]}";

    @Test
    void anErrorBesideAFinishReasonOfErrorIsMappedByTheMapperBeforeAnythingStarted() {
        // A stream that began with comments, as OpenRouter's does: the chunk is a sign of life, but not output.
        UpstreamException stream = failureOf(": OPENROUTER PROCESSING\n\n: OPENROUTER PROCESSING\n\n" + stream(ERROR_AND_FINISH));
        assertThat(stream).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(stream.getStatusCode()).isEqualTo(429);
        assertThat(stream.isGenerationStarted()).isFalse();
        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).path("code").asString()).isEqualTo("server_error");

        // The same in a JSON body, with a verdict that is not a server error.
        mapped.clear();
        UpstreamException json = failureOf(ERROR_AND_FINISH.replace("\"server_error\"", "429"));
        assertThat(json).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
        assertThat(json.getStatusCode()).isEqualTo(429);
        assertThat(json.getMessage()).isEqualTo("mapped error");
        assertThat(mapped).hasSize(1);

        // Late, but nothing has started: still the relay's verdict.
        ChatCompletionAccumulator slow = accumulator();
        thirtyOneSecondsPass();
        assertThat(failureOf(slow, ERROR_AND_FINISH)).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
    }

    @Test
    void anErrorBesideAFinishReasonOfErrorAfterOutputIsAnInterruption() {
        mapped.clear();

        assertInterrupted(failureOf(stream(delta("{\"content\":\"a\"}"), ERROR_AND_FINISH)));
        assertInterrupted(failureOf(stream(ERROR_AND_FINISH.replace("\"content\":\"\"", "\"content\":\"a\""))));
        assertThat(mapped).isEmpty();
    }

    // ---------------------------------------------------------------- what a body is

    @Test
    void aWebPageIsNoStreamEvenWhenALineOfItStartsLikeAComment() {
        String page = "<!DOCTYPE html>\n<html><head><style>\n:root{--bg:#fff}\n</style></head><body>Bad gateway</body></html>\n";
        assertNothingGenerated(failureOf(page));
        // A line that starts like a data line, as an inline image does.
        assertNothingGenerated(failureOf("<html><body>\ndata:image/png;base64,AAAA\n</body></html>"));
        assertNothingGenerated(failureOf("Bad Gateway\n: nginx\ndata: x\n"));

        // Piece by piece, the page is known not to be a completion as soon as its first line is.
        ChatCompletionAccumulator pieces = accumulator();
        byte[] all = bytes(page);
        assertThatThrownBy(() -> {
            for (byte one : all) {
                pieces.acceptBytes(new byte[] {one});
            }
            pieces.finish();
        }).isInstanceOfSatisfying(UpstreamException.class, ChatCompletionAccumulatorTest::assertNothingGenerated);

        // Late, nothing says it did not generate: an interruption, whatever the body is.
        ChatCompletionAccumulator slow = accumulator();
        thirtyOneSecondsPass();
        assertInterrupted(failureOf(slow, page));
    }

    @Test
    void aStreamIsToldFromItsFirstLineWhereverTheBytesAreCut() {
        String answer = delta("{\"content\":\"hi\"}");
        for (String start : List.of("data: ", "data:", "event: message\ndata: ", "id: 1\ndata: ", "retry: 3000\ndata: ", ": ping\n\ndata: ")) {
            String body = start + answer + "\n\ndata: " + finish("stop") + "\n\ndata: [DONE]\n\n";
            if (start.endsWith("data:")) {
                body = start + answer + "\n\ndata:" + finish("stop") + "\n\ndata:[DONE]\n\n";
            }
            for (int piece : new int[] {1, 2, 3, 5, 7}) {
                ChatCompletionAccumulator accumulator = accumulator();
                byte[] bytes = bytes(body);
                for (int offset = 0; offset < bytes.length; offset += piece) {
                    accumulator.acceptBytes(bytes, offset, Math.min(piece, bytes.length - offset));
                }
                assertThat(accumulator.finish().content()).as("%s in pieces of %s", start, piece).isEqualTo("hi");
            }
        }
    }

    @Test
    void aBodyThatEndsBeforeItSaysWhatItIsIsNotACompletion() {
        assertNothingGenerated(failureOf("  da"));
        assertNothingGenerated(failureOf("ev"));
        assertNothingGenerated(failureOf("dat"));
    }

    // ---------------------------------------------------------------- tool calls that are hard to tell apart

    @Test
    void parallelCallsWithNeitherAnIndexNorAnIdAreToldApartByTheirFunction() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"arguments\":\"{\\\"x\\\":1}\"}}]}"),
            delta("{\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"b\",\"arguments\":\"{\\\"y\\\":2}\"}}]}"),
            finish("tool_calls"),
            DONE));

        assertThat(completion.toolUses()).extracting(PlatformToolUse::name).containsExactly("a", "b");
        assertThat(completion.toolUses().get(0).input().path("x").asInt()).isEqualTo(1);
        assertThat(completion.toolUses().get(1).input().path("y").asInt()).isEqualTo(2);
        assertThat(completion.stopReason()).isEqualTo(StopReason.TOOL_USE);
    }

    /** A relay that numbers its calls from call_0 on every answer, parallel calls of one answer included. */
    @Test
    void parallelCallsThatAllCarryTheSameIdButNameOtherFunctionsAreSeparateCalls() {
        String chunk = "{\"tool_calls\":[{\"id\":\"call_0\",\"type\":\"function\",\"function\":{\"name\":\"%s\",\"arguments\":\"%s\"}}]}";
        Completion withoutIndex = read(stream(
            delta(chunk.formatted("Read", "{\\\"path\\\":\\\"a\\\"}")),
            delta(chunk.formatted("Bash", "{\\\"command\\\":\\\"ls\\\"}")),
            finish("tool_calls"),
            DONE));

        assertThat(withoutIndex.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(withoutIndex.toolUses()).extracting(PlatformToolUse::name).containsExactly("Read", "Bash");
        assertThat(withoutIndex.toolUses().get(0).input().path("path").asString()).isEqualTo("a");
        assertThat(withoutIndex.toolUses().get(1).input().path("command").asString()).isEqualTo("ls");

        String indexed = "{\"tool_calls\":[{\"index\":0,\"id\":\"call_0\",\"type\":\"function\",\"function\":{\"name\":\"%s\",\"arguments\":\"%s\"}}]}";
        Completion allIndexZero = read(stream(
            delta(indexed.formatted("Read", "{\\\"path\\\":\\\"a\\\"}")),
            delta(indexed.formatted("Bash", "{\\\"command\\\":\\\"ls\\\"}")),
            delta(indexed.formatted("Edit", "{}")),
            finish("tool_calls"),
            DONE));

        assertThat(allIndexZero.toolUses()).extracting(PlatformToolUse::name).containsExactly("Read", "Bash", "Edit");
    }

    @Test
    void aFragmentedCallThatSaysItsIdAndNameInEveryPieceIsStillOneCall() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"id\":\"call_0\",\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"comm\"}}]}"),
            delta("{\"tool_calls\":[{\"id\":\"call_0\",\"function\":{\"name\":\"Bash\",\"arguments\":\"and\\\":\\\"ls\\\"}\"}}]}"),
            finish("tool_calls"),
            DONE));

        assertThat(completion.toolUses()).hasSize(1);
        assertThat(completion.toolUses().get(0).input().path("command").asString()).isEqualTo("ls");
    }

    @Test
    void aPieceWithTheIdOfTheCallThatNamesNoFunctionBelongsToIt() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"id\":\"call_0\",\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\"}}]}"),
            delta("{\"tool_calls\":[{\"id\":\"call_0\",\"function\":{\"arguments\":\"\\\"ls\\\"}\"}}]}"),
            finish("tool_calls"),
            DONE));

        assertThat(completion.toolUses()).hasSize(1);
        assertThat(completion.toolUses().get(0).input().path("command").asString()).isEqualTo("ls");
    }

    @Test
    void parallelCallsOfOneFunctionWithNoIndexOrIdAreToldApartBecauseTheArgumentsAreWhole() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"p\\\":1}\"}}]}"),
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"p\\\":2}\"}}]}"),
            finish("tool_calls")));

        assertThat(completion.toolUses()).extracting(PlatformToolUse::name).containsExactly("Read", "Read");
        assertThat(completion.toolUses().get(0).input().path("p").asInt()).isEqualTo(1);
        assertThat(completion.toolUses().get(1).input().path("p").asInt()).isEqualTo(2);
    }

    @Test
    void callsThatAllSayIndexZeroWithAnEmptyIdAreToldApartByTheirFunction() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"function\":{\"name\":\"a\",\"arguments\":\"{\\\"x\\\":1}\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"function\":{\"name\":\"b\",\"arguments\":\"{\\\"y\\\":2}\"}}]}"),
            finish("tool_calls")));

        assertThat(completion.toolUses()).extracting(PlatformToolUse::name).containsExactly("a", "b");
        assertThat(completion.toolUses().get(1).input().path("y").asInt()).isEqualTo(2);
    }

    @Test
    void argumentsSentAgainInWholeAtTheEndAreKeptOnce() {
        Completion again = read(stream(
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"x\\\":1}\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"x\\\":1}\"}}]}"),
            finish("tool_calls")));
        assertThat(again.toolUses()).hasSize(1);
        assertThat(again.toolUses().get(0).input().path("x").asInt()).isEqualTo(1);
        assertThat(again.toolUses().get(0).rawInput()).isEqualTo("{\"x\":1}");

        // Without an index or an id, and with the name sent again as well.
        Completion unnamed = read(stream(
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"x\\\":1}\"}}]}"),
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"x\\\":1}\"}}]}"),
            finish("tool_calls")));
        assertThat(unnamed.toolUses()).hasSize(1);
    }

    @Test
    void aNameRepeatedInTheFragmentsOfOneCallWithoutIndexOrIdIsNotAnotherCall() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"a\\\":\"}}]}"),
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"f\",\"arguments\":\"1}\"}}]}"),
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"f\",\"arguments\":\"\"}}]}"),
            finish("tool_calls")));

        assertThat(completion.toolUses()).hasSize(1);
        assertThat(completion.toolUses().get(0).input().path("a").asInt()).isEqualTo(1);
    }

    @Test
    void aNameAndEmptyArgumentsRepeatedAfterACompleteCallAreStillThatCall() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"a\\\":1}\"}}]}"),
            delta("{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"f\",\"arguments\":\"\"}}]}"),
            finish("tool_calls")));

        assertThat(completion.toolUses()).hasSize(1);
        assertThat(completion.toolUses().get(0).input().path("a").asInt()).isEqualTo(1);
    }

    @Test
    void moreToolCallsThanAClientCanUseEndTheAnswer() {
        int max = ChatCompletionAccumulator.MAX_TOOL_CALLS;
        StringBuilder atTheLimit = new StringBuilder();
        for (int i = 0; i < max; i++) {
            atTheLimit.append(stream(delta("{\"tool_calls\":[{\"index\":" + i + ",\"function\":{\"name\":\"a\"}}]}")));
        }
        Completion ok = read(atTheLimit + stream(finish("tool_calls")));
        assertThat(ok.toolUses()).hasSize(max);

        assertInterrupted(failureOf(atTheLimit
            + stream(delta("{\"tool_calls\":[{\"index\":" + max + ",\"function\":{\"name\":\"a\"}}]}"))));
        // A whole message is no different.
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= max; i++) {
            many.append(i == 0 ? "" : ",").append("{\"id\":\"c").append(i).append("\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}");
        }
        assertInterrupted(failureOf(message("\"tool_calls\":[" + many + "]", "tool_calls")));
    }


    // ---------------------------------------------------------------- an id is the strongest sign, and a megabyte is not slow

    private static final String CALL_START =
        "{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"Bash\",\"arguments\":\"\"}}]}";
    private static final String CALL_ARGUMENTS =
        "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"command\\\":\\\"rm -rf build\\\"}\"}}]}";

    /** A relay that sends a finished call again whole, with its id and name, but writes the arguments another way. */
    @Test
    void aFinishedCallSentAgainWithItsIdIsKeptOnceWhateverWayTheArgumentsAreWritten() {
        for (String again : List.of(
            "{\\\"command\\\": \\\"rm -rf build\\\"}",
            "{ \\\"command\\\" :\\\"rm -rf build\\\" }\\n",
            "{\\\"command\\\":\\\"rm -rf build\\\"}")) {
            Completion completion = read(stream(
                ROLE,
                delta(CALL_START),
                delta(CALL_ARGUMENTS),
                delta("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"Bash\",\"arguments\":\"" + again + "\"}}]}"),
                finish("tool_calls"),
                DONE));

            assertThat(completion.toolUses()).as(again).hasSize(1);
            assertThat(completion.toolUses().get(0).id()).isEqualTo("call_1");
            assertThat(completion.toolUses().get(0).input().path("command").asString()).isEqualTo("rm -rf build");
            assertThat(completion.toolUses().get(0).rawInput()).as("the first writing is kept").isEqualTo("{\"command\":\"rm -rf build\"}");
            assertThat(completion.stopReason()).isEqualTo(StopReason.TOOL_USE);
        }
    }

    @Test
    void thatIsSoWithoutTheIdAndInAnyOrderOfTheKeys() {
        Completion noId = read(stream(
            ROLE,
            delta(CALL_START),
            delta(CALL_ARGUMENTS),
            delta("{\"tool_calls\":[{\"index\":0,\"type\":\"function\",\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\": \\\"rm -rf build\\\"}\"}}]}"),
            finish("tool_calls"),
            DONE));
        assertThat(noId.toolUses()).hasSize(1);
        assertThat(noId.toolUses().get(0).input().path("command").asString()).isEqualTo("rm -rf build");

        // Without an index either, and with two keys that come in the other order the second time.
        Completion reordered = read(stream(
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"Edit\",\"arguments\":\"{\\\"path\\\":\\\"a\\\",\\\"line\\\":3}\"}}]}"),
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"Edit\",\"arguments\":\"{\\\"line\\\": 3, \\\"path\\\": \\\"a\\\"}\"}}]}"),
            finish("tool_calls")));
        assertThat(reordered.toolUses()).hasSize(1);
        assertThat(reordered.toolUses().get(0).rawInput()).isEqualTo("{\"path\":\"a\",\"line\":3}");
    }

    @Test
    void aSecondCallOfTheSameFunctionWithOtherArgumentsIsStillASecondCallWhenItHasNoIdOfItsOwn() {
        Completion completion = read(stream(
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]}"),
            delta("{\"tool_calls\":[{\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\": \\\"pwd\\\"}\"}}]}"),
            finish("tool_calls")));

        assertThat(completion.toolUses()).extracting(use -> use.input().path("command").asString()).containsExactly("ls", "pwd");
    }

    /**
     * An id that the call already has says the chunk is a piece of it. Other arguments than the whole ones it has make the
     * call unusable, which is an interruption, and not a second call that a client would run as well.
     */
    @Test
    void aChunkWithTheIdOfTheCallIsNeverAnotherCallEvenWithOtherArguments() {
        assertInterrupted(failureOf(stream(
            ROLE,
            delta(CALL_START),
            delta(CALL_ARGUMENTS),
            delta("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"Bash\",\"arguments\":\"{\\\"command\\\":\\\"other\\\"}\"}}]}"),
            finish("tool_calls"),
            DONE)));
    }

    /** One call of a megabyte from a relay that repeats the function name in each of its 12-character pieces. */
    @Test
    void aLargeCallInSmallPiecesThatRepeatTheNameIsAssembledInLinearTime() {
        int size = 2_000_000;
        StringBuilder whole = new StringBuilder("{\"content\":\"");
        whole.append("a".repeat(size)).append("\"}");
        String arguments = whole.toString();
        StringBuilder body = new StringBuilder();
        body.append(stream(ROLE, delta(CALL_START)));
        for (int from = 0; from < arguments.length(); ) {
            StringBuilder event = new StringBuilder("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[");
            for (int i = 0; i < 100 && from < arguments.length(); i++) {
                int to = Math.min(arguments.length(), from + 12);
                event.append(i == 0 ? "" : ",").append("{\"index\":0,\"function\":{\"name\":\"Bash\",\"arguments\":\"")
                    .append(arguments.substring(from, to).replace("\"", "\\\"")).append("\"}}");
                from = to;
            }
            body.append("data: ").append(event).append("]}}]}\n\n");
        }
        body.append(stream(finish("tool_calls"), DONE));

        long started = System.nanoTime();
        Completion completion = read(body.toString());
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(completion.toolUses()).hasSize(1);
        assertThat(completion.toolUses().get(0).input().path("content").asString()).hasSize(size);
        // Each piece used to copy what had come (and parse it when it ended in a brace): seconds for these two megabytes.
        assertThat(millis).as("milliseconds").isLessThan(3_000);
    }

    /** A relay that opens a string and then sends braces, in many pieces, in the hope that each is parsed with all before it. */
    @Test
    void aHostileStreamOfBracesIsReadInLinearTimeAndEndsAsAnInterruption() {
        StringBuilder body = new StringBuilder();
        body.append(stream(delta("{\"tool_calls\":[{\"function\":{\"name\":\"a\",\"arguments\":\"{\\\"x\\\":\\\"\"}}]}")));
        String piece = "{\"function\":{\"name\":\"a\",\"arguments\":\"}\"}}";
        String event = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
            + String.join(",", Collections.nCopies(100, piece)) + "]}}]}\n\n";
        while (body.length() + event.length() < 12_000_000) {
            body.append(event);
        }
        body.append(stream(finish("tool_calls"), DONE));

        long started = System.nanoTime();
        UpstreamException error = failureOf(body.toString());
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertInterrupted(error);
        // Each piece used to be parsed with all before it: about a minute for this much.
        assertThat(millis).as("milliseconds").isLessThan(5_000);
    }

    // ---------------------------------------------------------------- an error inside a 2xx, late (7.7, 7.8)

    private static final RelayErrorMapper RELAY_ERRORS = new RelayErrorMapper(JSON);

    /** The accumulator as the relay client builds it: the errors are the error mapper's. */
    private ChatCompletionAccumulator withTheRealMapper() {
        return new ChatCompletionAccumulator(JSON, error -> RELAY_ERRORS.fromInBand(error, "relay-1", null), clock::get,
            IncrementalSseDecoder.DEFAULT_MAX_BYTES);
    }

    /** The shapes a relay says it in, none of which has any output: what each is before and after thirty seconds. */
    private static List<String[]> serverErrorsInASuccess() {
        return List.of(
            new String[] {"a keep-alive, then a server error", ": keep-alive\n\n"
                + "data: {\"error\":{\"type\":\"server_error\",\"message\":\"upstream broke\"}}\n\n", "503"},
            new String[] {"the role chunk, then a server error", stream(ROLE, "{\"error\":{\"type\":\"server_error\"}}"), "503"},
            new String[] {"the role chunk, then an overloaded error event", "data: " + ROLE + "\n\nevent: error\n"
                + "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n", "503"},
            new String[] {"the shape OpenRouter ends a stream with", ": OPENROUTER PROCESSING\n\n"
                + "data: {\"error\":{\"code\":502,\"message\":\"Provider returned error\"},\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"\"},\"finish_reason\":\"error\"}]}\n\n", "502"},
            new String[] {"a JSON body with a 500", "{\"error\":{\"code\":500,\"message\":\"internal\"}}", "500"}
        );
    }

    @Test
    void aServerErrorInASuccessfulAnswerInAMomentIsTheRelaysOwnVerdict() {
        for (String[] shape : serverErrorsInASuccess()) {
            UpstreamException error = failureOf(withTheRealMapper(), shape[1]);

            assertThat(error).as(shape[0]).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
            assertThat(error.getStatusCode()).as(shape[0]).isEqualTo(Integer.parseInt(shape[2]));
            assertThat(error.isRecoverable()).as(shape[0]).isTrue();
            assertThat(error.isGenerationStarted()).as(shape[0]).isFalse();
        }
    }

    /**
     * A relay that answered 200 at once and went on working on its own upstream has committed its response before it knows
     * the result, and may have produced the answer it then fails to give. The same server error with an error status is an
     * interruption after thirty seconds already, so that nobody else is asked for the same answer.
     */
    @Test
    void theSameServerErrorAfterThirtySecondsIsAnInterruption() {
        for (String[] shape : serverErrorsInASuccess()) {
            ChatCompletionAccumulator slow = withTheRealMapper();
            thirtyOneSecondsPass();

            assertInterrupted(failureOf(slow, shape[1]));
            assertThat(slow.possiblyGenerated()).as(shape[0]).isTrue();
        }
    }

    @Test
    void aRefusalIsARefusalWheneverItComes() {
        List<String[]> refusals = List.of(
            new String[] {"{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}", "429"},
            new String[] {"{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}", "402"},
            new String[] {"{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\",\"type\":\"invalid_request_error\"}}", "400"},
            new String[] {"{\"error\":{\"message\":\"No available channel for model m\",\"type\":\"new_api_error\"}}", "404"},
            new String[] {"{\"error\":{\"message\":\"bad key\",\"type\":\"authentication_error\"}}", "401"},
            // A server error that the words of the relay make a refusal: the model is not served, however it is called.
            new String[] {"{\"error\":{\"message\":\"model not found\",\"type\":\"server_error\"}}", "404"});
        for (String[] refusal : refusals) {
            ChatCompletionAccumulator slow = withTheRealMapper();
            thirtyOneSecondsPass();

            UpstreamException error = failureOf(slow, refusal[0]);

            assertThat(error).as(refusal[0]).isNotInstanceOf(RelayAbortedAfterDispatchException.class);
            assertThat(error.getStatusCode()).as(refusal[0]).isEqualTo(Integer.parseInt(refusal[1]));
            assertThat(error.isGenerationStarted()).as(refusal[0]).isFalse();
        }
    }

    @Test
    void anErrorThatTheMapperCouldNotMakeAnythingOfIsAServerErrorToo() {
        ChatCompletionAccumulator silent = new ChatCompletionAccumulator(JSON, error -> null, clock::get, 1024);
        thirtyOneSecondsPass();

        assertInterrupted(failureOf(silent, "{\"error\":{\"message\":\"x\"}}"));
    }

    // ---------------------------------------------------------------- text of nothing, and numbers of nothing

    @Test
    void textOfWhiteSpaceAloneIsNoAnswerButReasoningAloneIs() {
        assertInterrupted(failureOf(message("\"content\":\"  \\n\\n\"", "stop")));
        assertInterrupted(failureOf(stream(delta("{\"content\":\" \"}"), finish("stop"), DONE)));

        Completion thought = read(message("\"content\":\"\",\"reasoning_content\":\"thinking\"", "stop"));
        assertThat(thought.content()).isEmpty();
        assertThat(thought.reasoningContent()).isEqualTo("thinking");
        // White space beside a tool call is the call's answer.
        assertThat(read(message("\"content\":\"\\n\",\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]",
            "tool_calls")).toolUses()).hasSize(1);
    }

    @Test
    void aUsageNumberBeyondTheRangeOfADoubleDoesNotBreakTheReading() {
        Completion json = read("{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1e400,\"completion_tokens\":3}}");
        assertThat(json.content()).isEqualTo("hi");

        // A later chunk's number is compared with the earlier, whichever is the larger.
        Completion larger = read(stream(
            "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}],\"usage\":{\"prompt_tokens\":5}}",
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1e400,\"completion_tokens\":-1e400}}",
            DONE));
        assertThat(larger.usage().path("prompt_tokens").doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(larger.usage().has("completion_tokens")).as("minus infinity is no count").isFalse();

        // And a later, finite number does not take the place of infinity.
        Completion kept = read(stream(
            "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}],\"usage\":{\"prompt_tokens\":1e400}}",
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":50}}",
            DONE));
        assertThat(kept.usage().path("prompt_tokens").doubleValue()).isEqualTo(Double.POSITIVE_INFINITY);
    }
}
