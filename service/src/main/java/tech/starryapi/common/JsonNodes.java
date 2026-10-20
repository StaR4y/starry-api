package tech.starryapi.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.Writer;

/**
 * Helpers for working with large request trees without duplicating them.
 *
 * <p>A proxied request can be several megabytes of JSON. {@code node.toString()} and {@code deepCopy()} each
 * allocate a full second copy (a String plus its byte form, or a whole new tree), and doing that a few times
 * per request is what made heap use scale at several times the request size under concurrency.
 */
public final class JsonNodes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNodes() {
    }

    /**
     * Copies only the top-level fields. Children are shared with the source, so the copy may be used to
     * add, replace or remove top-level fields but its nested values must be treated as read-only.
     */
    public static ObjectNode shallowCopy(ObjectNode source) {
        ObjectNode copy = new ObjectNode(JsonNodeFactory.instance);
        copy.setAll(source);
        return copy;
    }

    /**
     * Size of the compact JSON form in UTF-8 bytes, counted while streaming and without building a String.
     * Counting goes through the char-based generator because Jackson's UTF-8 generator escapes astral
     * characters (emoji) as {@code \\uD83D\\uDE00}, which would overstate the size of what {@code toString()} gives.
     */
    public static long utf8Length(JsonNode node) {
        return count(node).utf8;
    }

    /** Length of the compact JSON form in UTF-16 chars, counted while streaming and without building a String. */
    public static long charLength(JsonNode node) {
        return count(node).chars;
    }

    /**
     * The compact JSON form as UTF-8 bytes, byte-for-byte what {@code node.toString().getBytes(UTF_8)} returns, but
     * without the intermediate String: for a 1 MB payload that String alone is 1-2 MB of garbage. The exact size is
     * counted first so the result array is allocated once and never grown or copied.
     */
    public static byte[] toUtf8Bytes(JsonNode node) {
        if (node == null) {
            return new byte[0];
        }
        long length = utf8Length(node);
        if (length > Integer.MAX_VALUE - 16) {
            throw new IllegalArgumentException("JSON document is too large");
        }
        FixedBytes sink = new FixedBytes((int) length);
        try (Writer writer = new java.io.OutputStreamWriter(sink, java.nio.charset.StandardCharsets.UTF_8);
             JsonGenerator generator = MAPPER.getFactory().createGenerator(writer)) {
            MAPPER.writeTree(generator, node);
        } catch (IOException error) {
            return node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return sink.result();
    }

    private static final class FixedBytes extends java.io.OutputStream {
        private byte[] bytes;
        private int position;

        private FixedBytes(int capacity) {
            this.bytes = new byte[capacity];
        }

        @Override
        public void write(int b) {
            ensure(1);
            bytes[position++] = (byte) b;
        }

        @Override
        public void write(byte[] source, int offset, int length) {
            ensure(length);
            System.arraycopy(source, offset, bytes, position, length);
            position += length;
        }

        private void ensure(int extra) {
            if (position + extra > bytes.length) {
                bytes = java.util.Arrays.copyOf(bytes, Math.max(bytes.length * 2, position + extra));
            }
        }

        private byte[] result() {
            return position == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, position);
        }
    }

    private static CountingWriter count(JsonNode node) {
        CountingWriter counter = new CountingWriter();
        if (node == null) {
            return counter;
        }
        try (JsonGenerator generator = MAPPER.getFactory().createGenerator(counter)) {
            MAPPER.writeTree(generator, node);
        } catch (IOException error) {
            String text = node.toString();
            CountingWriter fallback = new CountingWriter();
            fallback.write(text, 0, text.length());
            return fallback;
        }
        return counter;
    }

    private static final class CountingWriter extends Writer {
        private long chars;
        private long utf8;
        private boolean pendingHighSurrogate;

        @Override
        public void write(char[] buffer, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                accept(buffer[i]);
            }
        }

        @Override
        public void write(String text, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                accept(text.charAt(i));
            }
        }

        private void accept(char c) {
            chars++;
            if (pendingHighSurrogate) {
                pendingHighSurrogate = false;
                if (Character.isLowSurrogate(c)) {
                    utf8 += 4;
                    return;
                }
                utf8 += 3;
            }
            if (c < 0x80) {
                utf8 += 1;
            } else if (c < 0x800) {
                utf8 += 2;
            } else if (Character.isHighSurrogate(c)) {
                pendingHighSurrogate = true;
            } else {
                utf8 += 3;
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            if (pendingHighSurrogate) {
                pendingHighSurrogate = false;
                utf8 += 3;
            }
        }
    }
}
