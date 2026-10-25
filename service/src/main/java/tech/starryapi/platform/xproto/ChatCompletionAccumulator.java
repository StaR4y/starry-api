package tech.starryapi.platform.xproto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformCompletionResult.StopReason;
import tech.starryapi.platform.core.PlatformCompletionResult.UsageSource;
import tech.starryapi.platform.core.PlatformToolUse;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.upstream.UpstreamException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the answer of an OpenAI-format relay to one Chat Completions request, whichever way it arrives: a JSON body, a
 * server-sent event stream, or an event stream where a JSON body was asked for, or the other way round (design 7.7).
 * The bytes are fed as they come; {@link #finish()} is called when the body has ended, and either returns the answer
 * or throws what the relay's behavior deserves.
 *
 * <p>What the relay may have done by now is the question every failure turns on, because a cross-protocol request is
 * generated once and never sent on once it may have been (invariant I7):
 *
 * <ul>
 *   <li>{@link #started()}: the relay produced something. Text, reasoning or a tool call arrived, its usage
 *       reports completion tokens, or it says the model ran out of its budget or was filtered (a finish reason of {@code
 *       length}, {@code max_tokens} or {@code content_filter}: the text may be all hidden, as the reasoning of a model
 *       is, and there is no answer more generated than one that used up its tokens).
 *   <li>{@link #possiblyGenerated()}: it may have started without us seeing it. That is {@code started}, any event
 *       of a stream (a chunk with nothing but the role in it counts, a chunk that cannot be read does too), a
 *       keep-alive comment, a JSON body that cannot be read but shows that it is a completion (it names its {@code
 *       choices}, or its object, or the id of a completion: a body that is sent whole is sent when the work is done), or
 *       30 seconds since the request was written.
 * </ul>
 *
 * <p>What is thrown follows design 7.7. A failure that is plainly nothing (no answer, an empty answer, a stream that ended
 * unfinished, data that is not a completion, a flag the relay raised itself) is a recoverable 502 while nothing may
 * have been generated, so the request can be answered by another source, and a {@link
 * RelayAbortedAfterDispatchException} once something may have. An error the relay reports inside a successful
 * response is the relay's own verdict while nothing has started, and is given to the error mapper (7.11) to become
 * the status it deserves; after the first output it is an interruption. So is a server error (a mapped status of 500 or
 * more) that comes thirty seconds or more after the request was written, as it is when the relay says it with an error
 * status ({@code OpenAiChatRelayClient}): a relay that answered 200 at once and went on working on its own upstream
 * (a streaming relay, or one that always answers 200) may have produced the answer it now fails to deliver, and is
 * paid for it. A refusal (a 400, 402, 404 or 429 that the mapper decides) is a refusal whenever it comes. That verdict
 * comes before the flag: a chunk that has both an {@code error} and a finish reason of {@code error} (the shape
 * OpenRouter ends a stream with) is the error. A tool call the relay cut off (unreadable arguments) is dropped when the
 * answer ended for length, and is an interruption otherwise. The messages are fixed texts: nothing the relay wrote
 * reaches them.
 *
 * <p>Not decided here: the usage. The accumulator merges what the relay reports, field by field to the largest value
 * seen (a relay may report the prompt in the first chunk and the completion in the last), and hands that over; turning
 * it into billable tokens is the usage normalizer's work. Tool calls come out with the relay's own ids, which may be
 * empty or repeat between answers; the dispatcher mints the ones the client sees.
 *
 * <p>One instance reads one answer, from one thread at a time.
 */
public final class ChatCompletionAccumulator {

    /** After this long since the request was written, a relay that has said nothing may be generating (design 7.7). */
    static final Duration MAY_BE_GENERATING_AFTER = Duration.ofSeconds(30);

    /**
     * The most tool calls one answer may have. A client makes a few at a time; a relay that makes more than this is
     * broken or hostile, and every call costs memory in proportion to the 16 MB the answer may fill.
     */
    static final int MAX_TOOL_CALLS = 256;

    private static final String DONE = "[DONE]";

    /**
     * An answer: the text, the reasoning, the tool calls in the order the relay made them, the reason it stopped and the
     * usage the relay reported (an empty object when it reported none). {@code finishReason} is what the relay wrote
     * as the reason, as it wrote it, or null when it wrote none: {@code stopReason} is the platform's reading of it and
     * is {@code END_TURN} or {@code TOOL_USE} for an answer that had no reason, which only the verification of a relay
     * (design 7.4) needs to tell apart.
     */
    public record Completion(
        String content,
        String reasoningContent,
        List<PlatformToolUse> toolUses,
        StopReason stopReason,
        JsonNode usage,
        String finishReason
    ) {

        public Completion(String content, String reasoningContent, List<PlatformToolUse> toolUses, StopReason stopReason, JsonNode usage) {
            this(content, reasoningContent, toolUses, stopReason, usage, null);
        }

        /** The platform result, with the usage the normalizer made of {@link #usage()}. */
        public PlatformCompletionResult toResult(PlatformUsage platformUsage, UsageSource usageSource, List<String> degraded) {
            return new PlatformCompletionResult(content, reasoningContent, toolUses, platformUsage, stopReason, usageSource, degraded);
        }
    }

    private enum Mode {
        UNDECIDED, SSE, JSON
    }

    /** What the start of a body says it is. */
    private enum Kind {
        JSON, EVENT_STREAM, OTHER
    }

    /** What a JSON body that cannot be read says when it is an answer: see {@link #looksLikeACompletion}. */
    private static final Pattern COMPLETION_MARKERS = Pattern.compile(
        "\"choices\"|\"finish_reason\"|\"completion_tokens\"|\"object\"\\s*:\\s*\"chat\\.|\"id\"\\s*:\\s*\"chatcmpl"
    );

    /** The lines an event stream can start with: a field and its colon, or a colon alone for a comment. */
    private static final List<String> STREAM_STARTS = List.of(":", "data:", "event:", "id:", "retry:");

    private final ObjectMapper mapper;
    /** Reads the first value of a JSON body and what follows it is not looked at: an answer is not lost to what comes after it. */
    private final ObjectReader firstValue;
    private final Function<JsonNode, UpstreamException> inBandError;
    private final LongSupplier nanoTime;
    private final long maxBytes;
    private final IncrementalSseDecoder decoder;

    private final ByteArrayOutputStream lead = new ByteArrayOutputStream();
    private final ByteArrayOutputStream jsonBody = new ByteArrayOutputStream();
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final Map<String, ToolCall> toolCallsByKey = new HashMap<>();
    private final ObjectNode usage;

    private Mode mode = Mode.UNDECIDED;
    private long bytesRead;
    private long writtenAt;
    private boolean started;
    private boolean receivedEvent;
    private boolean keepAlive;
    private boolean answerBody;
    private boolean done;
    private boolean choicesSeen;
    private boolean finishedWithError;
    private String finishReason;
    private UpstreamException failure;
    private boolean finished;

    /**
     * @param inBandError makes the status an error inside a successful response deserves (the relay error mapper,
     *                    design 7.11) from the {@code error} member the relay sent, without any of its text in the
     *                    message
     */
    public ChatCompletionAccumulator(ObjectMapper mapper, Function<JsonNode, UpstreamException> inBandError) {
        this(mapper, inBandError, System::nanoTime, IncrementalSseDecoder.DEFAULT_MAX_BYTES);
    }

    /** @param nanoTime the clock the thirty seconds are counted by */
    public ChatCompletionAccumulator(
        ObjectMapper mapper,
        Function<JsonNode, UpstreamException> inBandError,
        LongSupplier nanoTime
    ) {
        this(mapper, inBandError, nanoTime, IncrementalSseDecoder.DEFAULT_MAX_BYTES);
    }

    ChatCompletionAccumulator(
        ObjectMapper mapper,
        Function<JsonNode, UpstreamException> inBandError,
        LongSupplier nanoTime,
        long maxBytes
    ) {
        this.mapper = mapper;
        this.firstValue = mapper.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.inBandError = inBandError;
        this.nanoTime = nanoTime;
        this.maxBytes = maxBytes;
        this.decoder = new IncrementalSseDecoder(maxBytes);
        this.usage = mapper.createObjectNode();
        this.writtenAt = nanoTime.getAsLong();
    }

    /** The request has been written; the 30 seconds count from here (they count from the creation until this is called). */
    public void requestWritten() {
        writtenAt = nanoTime.getAsLong();
    }

    public boolean started() {
        return started;
    }

    public boolean possiblyGenerated() {
        return started || receivedEvent || keepAlive || answerBody || thirtySecondsPassed();
    }

    /**
     * The stream has said {@code [DONE]}: the answer is whole, and whatever the relay does after that is not part of it:
     * an unclean close, a connection it keeps open, and bytes it writes (text that is not UTF-8, the first byte of a
     * character that never ends) are ignored, whether they came in the read that carried {@code [DONE]} or in a later one.
     * Where the network cuts the stream is not a fact about the relay, so the verdict on a whole answer must not depend on
     * it. The caller that reads the body may stop reading here and call {@link #finish()}.
     */
    public boolean isDone() {
        return done;
    }

    /** Thirty seconds or more since the request was written: a relay that has said nothing may have been working all that time. */
    private boolean thirtySecondsPassed() {
        return nanoTime.getAsLong() - writtenAt >= MAY_BE_GENERATING_AFTER.toNanos();
    }

    public void acceptBytes(byte[] bytes) {
        acceptBytes(bytes, 0, bytes.length);
    }

    public void acceptBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        acceptBytes(bytes, 0, bytes.length);
    }

    /**
     * Takes the next piece of the body. The first character that is not white space decides what the body is: a brace
     * or bracket is a JSON body, which is read when the body has ended; a line of an event stream (a field name
     * {@code data}, {@code event}, {@code id} or {@code retry} and a colon, or a colon for a comment) is a stream, read as
     * it comes; anything else, a web page for one, is not a completion and ends the exchange at once. A page must not
     * pass for a stream because a line of its style sheet starts with a colon. Throws what {@link #finish()} would, as
     * soon as the answer is known to be a failure.
     */
    public void acceptBytes(byte[] bytes, int offset, int length) {
        checkOpen();
        if (done) {
            // [DONE] ended the answer: what comes after it is outside it, whatever it is (see isDone).
            return;
        }
        bytesRead += length;
        if (bytesRead > maxBytes) {
            throw record(interrupted("The source's answer is larger than the size limit"));
        }
        try {
            if (mode == Mode.UNDECIDED) {
                lead.write(bytes, offset, length);
                byte[] head = lead.toByteArray();
                lead.reset();
                int first = firstContentByte(head, 0, head.length);
                if (first < 0) {
                    // Only white space so far: it says nothing and is not kept.
                    return;
                }
                head = Arrays.copyOfRange(head, first, head.length);
                Kind kind = kindOf(head);
                if (kind == null) {
                    // Too little yet to tell the name of a field from other text.
                    lead.writeBytes(head);
                } else if (kind == Kind.JSON) {
                    mode = Mode.JSON;
                    jsonBody.writeBytes(head);
                } else if (kind == Kind.EVENT_STREAM) {
                    mode = Mode.SSE;
                    readEvents(decoder.feed(head));
                } else {
                    throw record(broken("The source returned data that is not a completion"));
                }
                return;
            }
            if (mode == Mode.JSON) {
                jsonBody.write(bytes, offset, length);
            } else {
                readEvents(decoder.feed(bytes, offset, length));
            }
        } catch (IncrementalSseDecoder.SseDecodingException error) {
            UpstreamException failed = decodingFailure(error);
            if (failed != null) {
                throw failed;
            }
        }
    }

    /**
     * The body has ended. Returns the answer, or throws: see the class description. An answer is complete when a JSON
     * body was read, or an event stream carried a finish reason or ended with {@code [DONE]}.
     */
    public Completion finish() {
        checkOpen();
        if (mode == Mode.UNDECIDED && firstContentByte(lead.toByteArray(), 0, lead.size()) >= 0) {
            // The body ended before it said what it is: a few letters that are not the start of a stream.
            throw record(broken("The source returned data that is not a completion"));
        }
        try {
            if (mode == Mode.JSON) {
                readJsonBody();
            } else if (mode == Mode.SSE && !done) {
                // After [DONE] the end of the body is not looked at: a half character that the relay wrote after it
                // is not part of the answer, whichever read it came in.
                readEvents(decoder.finish());
            }
        } catch (IncrementalSseDecoder.SseDecodingException error) {
            UpstreamException failed = decodingFailure(error);
            if (failed != null) {
                throw failed;
            }
        }
        if (mode != Mode.JSON && !done && finishReason == null) {
            throw record(broken("The source's stream ended before the answer was complete"));
        }
        if (!choicesSeen) {
            // Also a usage without choices: with no completion tokens it is no answer, with some it is output that
            // cannot be read, and started has made that an interruption.
            throw record(broken("The source returned no answer"));
        }
        List<ToolCall> calls = toolCalls.stream().filter(call -> !call.isEmpty()).toList();
        // Text of white space alone is no more of an answer than none: a client cannot send it back in its history.
        if (content.toString().isBlank() && reasoning.isEmpty() && calls.isEmpty()) {
            throw record(broken("The source returned an empty answer"));
        }
        Completion completion = complete(calls);
        finished = true;
        // What was assembled is not needed again, and an answer can be large.
        toolCalls.clear();
        toolCallsByKey.clear();
        return completion;
    }

    private Completion complete(List<ToolCall> calls) {
        List<PlatformToolUse> uses = new ArrayList<>();
        boolean dropped = false;
        for (ToolCall call : calls) {
            PlatformToolUse use = call.toToolUse(mapper);
            if (use == null) {
                dropped = true;
            } else {
                uses.add(use);
            }
        }
        String reason = finishReason == null ? "" : finishReason.toLowerCase(Locale.ROOT);
        boolean truncated = reason.equals("length") || reason.equals("max_tokens");
        if (dropped && !truncated) {
            throw record(interrupted("The source returned a tool call that is not valid"));
        }
        StopReason stop;
        if (truncated) {
            stop = StopReason.MAX_TOKENS;
        } else if (reason.equals("content_filter")) {
            stop = StopReason.CONTENT_FILTER;
        } else {
            stop = uses.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
        }
        return new Completion(content.toString(), reasoning.toString(), List.copyOf(uses), stop, usage, finishReason);
    }

    private void readJsonBody() {
        JsonNode root;
        byte[] body = jsonBody.toByteArray();
        try {
            root = firstValue.readTree(body);
        } catch (JacksonException error) {
            // A body that is sent whole is sent when the relay is done: one that shows it is a completion (it names
            // its choices, or says what object it is, or begins with the id of one) is an answer that was cut or
            // damaged, not a refusal, and asking another source would generate it twice. A body that was cut before it
            // got that far (a Go relay that pipes the body of its upstream ends cleanly wherever the upstream broke) is
            // told by the same words, whichever of them comes first.
            answerBody = looksLikeACompletion(body);
            throw record(broken("The source returned data that is not a completion"));
        }
        apply(root);
    }

    /**
     * Whether a body that cannot be read shows that it is a chat completion: the members that an OpenAI-format answer
     * has and an error does not. {@code message} and {@code usage} are not among them, an error can have both.
     */
    private static boolean looksLikeACompletion(byte[] body) {
        return COMPLETION_MARKERS.matcher(new String(body, StandardCharsets.ISO_8859_1)).find();
    }

    private void readEvents(List<IncrementalSseDecoder.Event> events) {
        for (IncrementalSseDecoder.Event event : events) {
            if (event.comment()) {
                keepAlive = true;
                continue;
            }
            // Before the data is read: an event the relay sent is a sign of life even when it makes no sense.
            receivedEvent = true;
            if (done) {
                continue;
            }
            String text = event.data().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (DONE.equals(text)) {
                done = true;
                continue;
            }
            JsonNode chunk;
            try {
                chunk = mapper.readTree(text);
            } catch (JacksonException error) {
                throw record(broken("The source returned data that is not a completion"));
            }
            if ("error".equals(event.name()) && chunk != null && !chunk.hasNonNull("error")) {
                // An event named error whose data is the error itself.
                throw record(inBand(chunk));
            }
            apply(chunk);
        }
    }

    /** One chunk of a stream, or a whole JSON body. */
    private void apply(JsonNode chunk) {
        if (chunk == null || !chunk.isObject()) {
            throw record(broken("The source returned data that is not a completion"));
        }
        mergeUsage(chunk.get("usage"));
        JsonNode choices = chunk.get("choices");
        if (choices != null && choices.isArray()) {
            for (JsonNode choice : choices) {
                applyChoice(choice);
            }
        }
        // After the rest, so that what the chunk carried counts as started. The error comes before a finish reason of
        // error: the relay's verdict is what the failure is mapped by while nothing has started.
        JsonNode error = chunk.get("error");
        if (error != null && !error.isNull()) {
            throw record(inBand(error));
        }
        if (finishedWithError) {
            throw record(broken("The source ended the answer with an error"));
        }
    }

    private void applyChoice(JsonNode choice) {
        if (!choice.isObject()) {
            return;
        }
        choicesSeen = true;
        if (choice.path("index").asInt(0) != 0) {
            // Only the first choice is answered; a request for more than one is not carried across.
            return;
        }
        JsonNode delta = choice.get("delta");
        boolean whole = delta == null || !delta.isObject();
        JsonNode part = whole ? choice.get("message") : delta;
        if (part != null && part.isObject()) {
            appendContent(part.get("content"));
            appendContent(part.get("refusal"));
            String thought = text(part.get("reasoning_content"));
            appendReasoning(thought.isEmpty() ? text(part.get("reasoning")) : thought);
            applyToolCalls(part.get("tool_calls"), whole);
        }
        String reason = text(choice.get("finish_reason"));
        if (!reason.isEmpty()) {
            finishReason = reason;
            finishedWithError |= reason.equalsIgnoreCase("error");
            // The model spent its budget or was stopped by a filter: it generated, however little of it the relay shows
            // (relays strip the reasoning that a small budget is all spent on, and often the usage with it).
            started |= isSpentOrFiltered(reason);
        }
    }

    private static boolean isSpentOrFiltered(String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.equals("length") || lower.equals("max_tokens") || lower.equals("content_filter");
    }

    private void appendContent(JsonNode value) {
        if (value == null) {
            return;
        }
        if (value.isString()) {
            appendText(content, value.asString(""));
        } else if (value.isArray()) {
            for (JsonNode part : value) {
                appendText(content, part.isString() ? part.asString("") : text(part.get("text")));
            }
        }
    }

    private void appendReasoning(String value) {
        appendText(reasoning, value);
    }

    private void appendText(StringBuilder into, String value) {
        if (!value.isEmpty()) {
            into.append(value);
            started = true;
        }
    }

    private void applyToolCalls(JsonNode calls, boolean whole) {
        if (calls == null || !calls.isArray()) {
            return;
        }
        for (JsonNode call : calls) {
            if (!call.isObject()) {
                continue;
            }
            JsonNode function = call.get("function");
            JsonNode arguments = function != null && function.isObject() ? function.get("arguments") : null;
            String id = text(call.get("id"));
            String named = function != null && function.isObject() ? text(function.get("name")) : "";
            ToolCall target = toolCallFor(call, whole);
            // The id of the call a piece is given to is the strongest sign there is that it belongs to it: a piece that
            // repeats it is never another call, whatever its arguments are (it is a second copy of them, or not valid).
            // Unless it names another function: a copy of a call says its name again, and a relay that gives every call
            // the same id (call_0 for each) is told by the function, as one without ids is.
            boolean ofTheCall = !id.isEmpty() && id.equals(target.id) && (named.isEmpty() || named.equals(target.name));
            if (!whole && !ofTheCall && target.isAnotherCall(function, mapper)) {
                target = newToolCall(keyOf(call));
            }
            if (target.id.isEmpty()) {
                target.id = id;
            }
            if (function != null && function.isObject()) {
                // The name is taken once: relays that repeat it in every chunk would otherwise double it.
                if (target.name.isEmpty()) {
                    target.name = text(function.get("name"));
                }
                if (arguments != null && arguments.isString()) {
                    // A relay that sends the whole of the arguments again at the end must not have them twice.
                    String piece = arguments.asString("");
                    if (!target.isSentAgain(piece, mapper)) {
                        target.append(piece);
                    }
                } else if (arguments != null && arguments.isObject()) {
                    // A relay that sends the arguments as an object sends them whole.
                    target.replaceArguments(arguments.toString());
                }
            }
            if (!target.isEmpty()) {
                started = true;
            }
        }
    }

    /**
     * The call a piece of a stream belongs to: by index, else by id, else the call before. Every entry of a whole
     * message is a call of its own. An id that differs from the one the index already carries starts another call, as
     * parallel calls from a relay that gives them all index 0 would otherwise run together. (A relay that gives neither
     * an index nor a distinct id is told by the function: see {@link ToolCall#isAnotherCall}.)
     */
    private ToolCall toolCallFor(JsonNode call, boolean whole) {
        if (whole) {
            return newToolCall(null);
        }
        String id = text(call.get("id"));
        String key = keyOf(call);
        if (key == null) {
            return toolCalls.isEmpty() ? newToolCall(null) : toolCalls.get(toolCalls.size() - 1);
        }
        ToolCall existing = toolCallsByKey.get(key);
        if (existing == null || (!id.isEmpty() && !existing.id.isEmpty() && !existing.id.equals(id))) {
            return newToolCall(key);
        }
        return existing;
    }

    /** What ties the pieces of one call together: its index, else its id, else nothing. */
    private static String keyOf(JsonNode call) {
        JsonNode index = call.get("index");
        if (index != null && index.isNumber()) {
            return "index:" + index.asInt();
        }
        String id = text(call.get("id"));
        return id.isEmpty() ? null : "id:" + id;
    }

    private ToolCall newToolCall(String key) {
        if (toolCalls.size() >= MAX_TOOL_CALLS) {
            throw record(interrupted("The source returned more tool calls than a client can use"));
        }
        ToolCall created = new ToolCall();
        toolCalls.add(created);
        if (key != null) {
            toolCallsByKey.put(key, created);
        }
        return created;
    }

    /** Usage objects of all chunks, field by field to the largest number; objects within are merged the same way. */
    private void mergeUsage(JsonNode reported) {
        if (reported == null || !reported.isObject()) {
            return;
        }
        mergeLargest(usage, reported);
        if (Math.max(usage.path("completion_tokens").asLong(0), usage.path("output_tokens").asLong(0)) > 0) {
            started = true;
        }
    }

    private static void mergeLargest(ObjectNode into, JsonNode from) {
        for (Map.Entry<String, JsonNode> field : from.properties()) {
            JsonNode value = field.getValue();
            JsonNode present = into.get(field.getKey());
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isObject()) {
                ObjectNode child = present != null && present.isObject() ? (ObjectNode) present : into.putObject(field.getKey());
                mergeLargest(child, value);
            } else if (value.isNumber()) {
                // A number outside the range of a double (1e400) is read as infinity, which no decimal can hold: it
                // is the largest there is when it is positive, and no count when it is not.
                if (isNotACount(value)) {
                    continue;
                }
                if (present == null || !present.isNumber() || exceeds(value, present)) {
                    into.set(field.getKey(), value);
                }
            } else if (present == null) {
                into.set(field.getKey(), value);
            }
        }
    }

    private static boolean isNotACount(JsonNode number) {
        return number.isFloatingPointNumber() && (Double.isNaN(number.doubleValue())
            || number.doubleValue() == Double.NEGATIVE_INFINITY);
    }

    private static boolean exceeds(JsonNode value, JsonNode present) {
        boolean finite = !(value.isFloatingPointNumber() && Double.isInfinite(value.doubleValue()))
            && !(present.isFloatingPointNumber() && Double.isInfinite(present.doubleValue()));
        return finite
            ? value.decimalValue().compareTo(present.decimalValue()) > 0
            : Double.compare(value.doubleValue(), present.doubleValue()) > 0;
    }

    /**
     * An error inside a successful response: the relay's own verdict while nothing has started, an interruption after.
     * A verdict that is a server error is one too once thirty seconds have passed, the rule of an error status (design
     * 7.8): the response was committed before the relay's upstream answered, and the work may have been done.
     */
    private UpstreamException inBand(JsonNode error) {
        if (started) {
            return interrupted("The source reported an error after it had started to answer");
        }
        UpstreamException mapped = null;
        try {
            mapped = inBandError.apply(error);
        } catch (RuntimeException ignored) {
            // The message of a failed mapping would be the relay's to write; the fixed one below says enough.
        }
        if (mapped == null) {
            mapped = new UpstreamException(502, "The source answered with an error", true);
        }
        if (mapped.getStatusCode() >= 500 && thirtySecondsPassed()) {
            return interrupted("The source failed after it may have started to answer");
        }
        return mapped;
    }

    /** A failure that is nothing the relay did unless it may already have been generating. */
    private UpstreamException broken(String message) {
        return possiblyGenerated() ? new RelayAbortedAfterDispatchException(message) : new UpstreamException(502, message, true);
    }

    private UpstreamException interrupted(String message) {
        return new RelayAbortedAfterDispatchException(message);
    }

    /**
     * The failure a piece that cannot be read comes to, or null when the answer was whole before the bytes that cannot
     * be read: they are outside it, as they would be if they had come in a piece of their own after {@code [DONE]}.
     */
    private UpstreamException decodingFailure(IncrementalSseDecoder.SseDecodingException error) {
        if (!error.events().isEmpty()) {
            // What the piece said before its bad bytes is read first, as it would have been if the network had cut the
            // piece there: an event is a sign of life, and a failure of its own comes before this one.
            try {
                readEvents(error.events());
            } catch (UpstreamException earlier) {
                return earlier;
            }
        }
        if (done) {
            return null;
        }
        return record(error.isTooLarge()
            ? interrupted("The source's answer is larger than the size limit")
            : broken("The source returned a stream that is not valid text"));
    }

    private UpstreamException record(UpstreamException error) {
        failure = error;
        return error;
    }

    private void checkOpen() {
        if (failure != null) {
            throw failure;
        }
        if (finished) {
            throw new IllegalStateException("The answer was already read");
        }
    }

    /**
     * What a body is from its first bytes (the first of them is not white space), or null when they are too few to tell:
     * {@code da} may be the start of {@code data:}.
     */
    private static Kind kindOf(byte[] head) {
        if (head[0] == '{' || head[0] == '[') {
            return Kind.JSON;
        }
        boolean couldStillBe = false;
        for (String start : STREAM_STARTS) {
            int compared = Math.min(head.length, start.length());
            boolean same = true;
            for (int i = 0; i < compared && same; i++) {
                same = head[i] == start.charAt(i);
            }
            if (same && head.length >= start.length()) {
                return Kind.EVENT_STREAM;
            }
            couldStillBe |= same;
        }
        return couldStillBe ? null : Kind.OTHER;
    }

    /** The first byte that says what the body is, or -1. A byte order mark is skipped like white space. */
    private static int firstContentByte(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == 0xEF || b == 0xBB || b == 0xBF) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static String text(JsonNode value) {
        return value != null && value.isString() ? value.asString("") : "";
    }

    private static final class ToolCall {

        /** What the arguments are, as far as they have arrived. */
        private enum Shape {
            /** Nothing yet, or white space. */
            EMPTY,
            /** An object that has begun and is not closed. */
            OPEN,
            /** A whole object, with nothing but white space after it. */
            CLOSED,
            /** Anything else: it cannot become a whole object by more text. */
            OTHER
        }

        String id = "";
        String name = "";
        private final StringBuilder arguments = new StringBuilder();

        // The shape of the arguments is kept as they are appended, so that asking for it costs nothing however many pieces
        // a relay makes of a large call (a call of a megabyte arrives in thousands of them, and each is asked about).
        private Shape shape = Shape.EMPTY;
        private int depth;
        private boolean inString;
        private boolean escaped;

        // The arguments of a whole call as a tree, for the one question that needs it; valid for the length it was made at.
        private JsonNode tree;
        private int treeLength = -1;

        boolean isEmpty() {
            return id.isEmpty() && name.isEmpty() && arguments.length() == 0;
        }

        void append(String piece) {
            arguments.append(piece);
            for (int i = 0; i < piece.length() && shape != Shape.OTHER; i++) {
                look(piece.charAt(i));
            }
        }

        void replaceArguments(String whole) {
            arguments.setLength(0);
            shape = Shape.EMPTY;
            depth = 0;
            inString = false;
            escaped = false;
            tree = null;
            treeLength = -1;
            append(whole);
        }

        /** One character of the arguments: braces and brackets are counted outside strings, which is all a whole object needs. */
        private void look(char c) {
            switch (shape) {
                case EMPTY -> {
                    if (c == '{') {
                        shape = Shape.OPEN;
                        depth = 1;
                    } else if (!Character.isWhitespace(c)) {
                        shape = Shape.OTHER;
                    }
                }
                case OPEN -> {
                    if (inString) {
                        if (escaped) {
                            escaped = false;
                        } else if (c == '\\') {
                            escaped = true;
                        } else if (c == '"') {
                            inString = false;
                        }
                    } else if (c == '"') {
                        inString = true;
                    } else if (c == '{' || c == '[') {
                        depth++;
                    } else if ((c == '}' || c == ']') && --depth == 0) {
                        shape = Shape.CLOSED;
                    }
                }
                case CLOSED -> {
                    if (!Character.isWhitespace(c)) {
                        shape = Shape.OTHER;
                    }
                }
                case OTHER -> {
                    // Nothing follows that makes it whole.
                }
            }
        }

        /** Whether the arguments so far are a whole JSON object, which nothing can follow. */
        boolean isComplete() {
            return shape == Shape.CLOSED;
        }

        /**
         * Whether {@code piece} is the whole of the arguments that are already whole, sent a second time: the same text,
         * or the same object written another way (spaces, the order of the keys), as relays that send the call again at
         * the end write it.
         */
        boolean isSentAgain(String piece, ObjectMapper mapper) {
            return isComplete() && sameArguments(piece, mapper);
        }

        private boolean sameArguments(String piece, ObjectMapper mapper) {
            if (piece.length() == arguments.length() && piece.contentEquals(arguments)) {
                return true;
            }
            JsonNode brought = object(piece, mapper);
            return brought != null && brought.equals(whole(mapper));
        }

        /** The arguments, which are known to be a whole object: parsed once for as long as they do not change. */
        private JsonNode whole(ObjectMapper mapper) {
            if (treeLength != arguments.length()) {
                tree = object(arguments.toString(), mapper);
                treeLength = arguments.length();
            }
            return tree;
        }

        /** The text as an object, or null when it is not one. */
        private static JsonNode object(String text, ObjectMapper mapper) {
            String stripped = text.strip();
            if (stripped.length() < 2 || stripped.charAt(0) != '{' || stripped.charAt(stripped.length() - 1) != '}') {
                return null;
            }
            try {
                JsonNode parsed = mapper.readTree(stripped);
                return parsed != null && parsed.isObject() ? parsed : null;
            } catch (JacksonException error) {
                return null;
            }
        }

        /**
         * Whether a piece that was given to this call (it carries no index and no id that tells it apart) begins another
         * one: it names a function other than this call's, or it names the same one and brings arguments to a call whose
         * arguments are already whole and are not the same. A relay that sends parallel calls whole, one after the other,
         * without indexes or ids is read right; one that repeats the name in every fragment of a call is not misread,
         * because the fragments of a call that is not yet whole are its own, and neither is one that sends the whole
         * arguments a second time.
         */
        boolean isAnotherCall(JsonNode function, ObjectMapper mapper) {
            if (function == null || !function.isObject() || name.isEmpty()) {
                return false;
            }
            String named = text(function.get("name"));
            if (named.isEmpty()) {
                return false;
            }
            if (!named.equals(name)) {
                return true;
            }
            JsonNode brought = function.get("arguments");
            return isComplete() && brought != null && brought.isString() && !brought.asString("").isBlank()
                && !sameArguments(brought.asString(""), mapper);
        }

        /** The call, or null when it cannot be used: no name, or arguments that are not a JSON object. */
        PlatformToolUse toToolUse(ObjectMapper mapper) {
            String functionName = name.strip();
            if (functionName.isEmpty()) {
                return null;
            }
            String rawInput = arguments.toString().isBlank() ? "{}" : arguments.toString();
            JsonNode input;
            try {
                input = mapper.readTree(rawInput);
            } catch (JacksonException error) {
                return null;
            }
            if (input == null || !input.isObject()) {
                return null;
            }
            ObjectNode raw = mapper.createObjectNode();
            if (!id.isEmpty()) {
                raw.put("id", id);
            }
            raw.put("type", "function");
            ObjectNode function = raw.putObject("function");
            function.put("name", functionName);
            function.put("arguments", rawInput);
            return new PlatformToolUse(id, functionName, input, "function_call", rawInput, raw);
        }
    }
}
