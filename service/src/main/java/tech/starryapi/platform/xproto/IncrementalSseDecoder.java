package tech.starryapi.platform.xproto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the bytes of a {@code text/event-stream} body, in whatever pieces the network delivers them, into events
 * (design 7.7). A piece can end inside a multi-byte character, between the CR and the LF of a line end, or in the middle
 * of a line; the decoder keeps what it cannot finish and carries on with the next piece. It follows the event stream
 * format of the HTML standard in the parts a chat completions relay uses: lines end with LF, CR or CRLF, a line that
 * starts with a colon is a comment (the keep-alive of many relays), {@code data} lines of one event are joined with LF,
 * {@code event} names the event, {@code id} and {@code retry} are read and ignored, and a blank line ends the event.
 *
 * <p>One difference from the standard, on purpose: what is left when the body ends is delivered as an event even though
 * no blank line follows it, as {@code OpenAiUpstreamClient} has always read a buffered stream. Whether that event is
 * enough to call the answer complete is for the reader of the events to say.
 *
 * <p>Input that cannot be read is an {@link SseDecodingException}: bytes that are not UTF-8, a body that ends in the
 * middle of a character, or more bytes than the limit. What came before the bad bytes in the same piece was read all the
 * same, and its events are the exception's ({@link SseDecodingException#events()}): where the network cuts the pieces is
 * not a fact about the relay, so a stream that has shown life in a piece must not look as if it had not because the
 * damage was in the same piece. The decoder holds one event at most, not the stream, so memory stays with the limit. It
 * is not thread safe.
 */
public final class IncrementalSseDecoder {

    /** What one response may be, in bytes: the 16 MB the upstream codecs hold in memory (HttpClientConfig). */
    public static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;

    /**
     * One event, or one comment. A comment has {@code comment} set, no name, and the text after the colon as data.
     * An event has the data lines joined with LF (empty when its only data line was empty) and the name its
     * {@code event} line gave, or null.
     */
    public record Event(boolean comment, String name, String data) {
    }

    public static final class SseDecodingException extends RuntimeException {

        private final boolean tooLarge;
        private final List<Event> events;

        SseDecodingException(String message, boolean tooLarge, Throwable cause) {
            this(message, tooLarge, cause, List.of());
        }

        SseDecodingException(String message, boolean tooLarge, Throwable cause, List<Event> events) {
            super(message, cause);
            this.tooLarge = tooLarge;
            this.events = events;
        }

        /** True when the stream was cut for its size, not because it was unreadable. */
        public boolean isTooLarge() {
            return tooLarge;
        }

        /** The events the piece completed before its unreadable bytes, in order; empty when there were none. */
        public List<Event> events() {
            return events;
        }
    }

    private final long maxBytes;
    private final CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final StringBuilder line = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private byte[] carry = new byte[0];
    private long bytesRead;
    private boolean atStart = true;
    private boolean afterCr;
    private boolean hasData;
    private String eventName;
    private boolean finished;

    public IncrementalSseDecoder() {
        this(DEFAULT_MAX_BYTES);
    }

    public IncrementalSseDecoder(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public long bytesRead() {
        return bytesRead;
    }

    public List<Event> feed(byte[] bytes) {
        return feed(bytes, 0, bytes.length);
    }

    public List<Event> feed(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return feed(bytes, 0, bytes.length);
    }

    /** The events this piece completed, in order. */
    public List<Event> feed(byte[] bytes, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("The stream was already finished");
        }
        if (length < 0 || offset < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException();
        }
        bytesRead += length;
        if (bytesRead > maxBytes) {
            throw new SseDecodingException("The stream is larger than " + maxBytes + " bytes", true, null);
        }
        if (length == 0) {
            return List.of();
        }
        ByteBuffer input;
        if (carry.length == 0) {
            input = ByteBuffer.wrap(bytes, offset, length);
        } else {
            byte[] joined = new byte[carry.length + length];
            System.arraycopy(carry, 0, joined, 0, carry.length);
            System.arraycopy(bytes, offset, joined, carry.length, length);
            input = ByteBuffer.wrap(joined);
        }
        CharBuffer chars = CharBuffer.allocate(input.remaining());
        CoderResult result = utf8.decode(input, chars, false);
        if (!result.isError()) {
            carry = new byte[input.remaining()];
            input.get(carry);
        }
        chars.flip();
        List<Event> events = new ArrayList<>();
        while (chars.hasRemaining()) {
            read(chars.get(), events);
        }
        if (result.isError()) {
            // The characters before the bad bytes were decoded: what they completed is handed on with the failure.
            throw notText(result, events);
        }
        return events.isEmpty() ? List.of() : events;
    }

    /**
     * The end of the body: what is left of the last line and the last event is delivered. The decoder takes no more
     * pieces after this.
     */
    public List<Event> finish() {
        if (finished) {
            return List.of();
        }
        finished = true;
        if (carry.length > 0) {
            throw new SseDecodingException("The stream ends inside a character", false, null);
        }
        List<Event> events = new ArrayList<>();
        if (line.length() > 0) {
            endLine(events);
        }
        dispatch(events);
        return events.isEmpty() ? List.of() : events;
    }

    private SseDecodingException notText(CoderResult result, List<Event> events) {
        CharacterCodingException cause = null;
        try {
            result.throwException();
        } catch (CharacterCodingException error) {
            cause = error;
        }
        return new SseDecodingException("The stream is not UTF-8 text", false, cause, events.isEmpty() ? List.of() : events);
    }

    private void read(char c, List<Event> events) {
        if (atStart) {
            atStart = false;
            if (c == '﻿') {
                return;
            }
        }
        if (afterCr) {
            afterCr = false;
            if (c == '\n') {
                return;
            }
        }
        if (c == '\r') {
            afterCr = true;
            endLine(events);
        } else if (c == '\n') {
            endLine(events);
        } else {
            line.append(c);
        }
    }

    private void endLine(List<Event> events) {
        String text = line.toString();
        line.setLength(0);
        if (text.isEmpty()) {
            dispatch(events);
            return;
        }
        if (text.charAt(0) == ':') {
            events.add(new Event(true, null, withoutLeadingSpace(text.substring(1))));
            return;
        }
        int colon = text.indexOf(':');
        String field = colon < 0 ? text : text.substring(0, colon);
        String value = colon < 0 ? "" : withoutLeadingSpace(text.substring(colon + 1));
        switch (field) {
            case "data" -> {
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
            case "event" -> eventName = value;
            default -> {
                // id, retry and anything the format does not know are not read.
            }
        }
    }

    private void dispatch(List<Event> events) {
        if (hasData) {
            events.add(new Event(false, eventName == null || eventName.isEmpty() ? null : eventName, data.toString()));
        }
        data.setLength(0);
        hasData = false;
        eventName = null;
    }

    private static String withoutLeadingSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }
}
