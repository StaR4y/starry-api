package tech.starryapi.common;

import java.io.IOException;
import java.io.Writer;
import tech.starryapi.config.JacksonConfig;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Helpers for working with large request trees without duplicating them.
 *
 * <p>A proxied request can be several megabytes of JSON. {@code node.toString()} and {@code deepCopy()} each
 * allocate a full second copy (a String plus its byte form, or a whole new tree), and doing that a few times
 * per request is what made heap use scale at several times the request size under concurrency.
 */
public final class JsonNodes {

    private static final ObjectMapper MAPPER = JacksonConfig.newMapper();

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
     * Counting goes through the char-based generator, the one {@code toString()} uses: Jackson 2's UTF-8 generator
     * escaped astral characters (emoji) as {@code \\uD83D\\uDE00}, which would have overstated the size of what
     * {@code toString()} gives.
     */
    public static long utf8Length(JsonNode node) {
        return count(node).utf8;
    }

    /** Length of the compact JSON form in UTF-16 chars, counted while streaming and without building a String. */
    public static long charLength(JsonNode node) {
        return count(node).chars;
    }

    /**
     * Size and media census of a request with its inline base64 payloads left out of the sizes, counted without
     * building a copy. The sizes are what {@link #charLength} and {@link #utf8Length} would give for the same tree
     * with every payload emptied (a data URI keeps its {@code data:...;base64,} header), so only text that really is
     * base64 media is dropped: a text block that merely mentions {@code base64,} stays in. What the census sees:
     *
     * <ul>
     *   <li>Chat and Responses images, {@code image_url} as a string or {@code {"url": ...}}: a {@code data:} URI is an
     *       inline image, anything else (http URL, file id) is remote.
     *   <li>Anthropic blocks with a {@code source}: {@code base64} data is an inline image on an {@code image} block
     *       and remote on any other (a document), a {@code url} source is remote.
     *   <li>Gemini {@code inlineData}/{@code inline_data}: an {@code image/*} MIME type is an inline image, anything
     *       else is remote.
     *   <li>File ids, file URLs and file URIs ({@code file_id}, {@code file_url}, {@code fileUri}), Chat
     *       {@code file_data} and {@code input_audio.data}: remote.
     * </ul>
     *
     * <p>"Remote" here means media whose token cost cannot be derived from the request size; callers should treat it
     * like the existing 1,000,000-token floor. Inline documents and audio land there too, with their payload stripped,
     * because leaving them in would only make the size an arbitrary stand-in for a cost it does not describe.
     */
    public static MediaStats mediaStats(JsonNode node) {
        if (node == null) {
            return MediaStats.NONE;
        }
        CountingWriter whole = count(node);
        MediaCensus census = new MediaCensus();
        census.visit(node);
        return new MediaStats(whole.chars - census.chars, whole.utf8 - census.utf8, census.inlineImages,
            census.remoteMedia);
    }

    /**
     * @param charsWithoutBase64 UTF-16 chars of the compact JSON form, not counting inline base64 payloads
     * @param bytesWithoutBase64 UTF-8 bytes of the compact JSON form, not counting inline base64 payloads
     * @param inlineImages images carried inline as base64
     * @param remoteMedia media that is not an inline image: URLs, file ids and URIs, inline documents and audio
     */
    public record MediaStats(long charsWithoutBase64, long bytesWithoutBase64, long inlineImages, long remoteMedia) {
        static final MediaStats NONE = new MediaStats(0, 0, 0, 0);
    }

    private static final class MediaCensus {
        private static final String[] MIME_KEYS = {"mimeType", "mime_type"};
        private static final String[] FILE_REFERENCE_KEYS = {"file_id", "file_url", "fileUri", "file_uri"};

        // The serialized size of the payloads left out, in chars and in UTF-8 bytes (equal: base64 is ASCII).
        private long chars;
        private long utf8;
        private long inlineImages;
        private long remoteMedia;

        private void visit(JsonNode node) {
            if (node.isObject()) {
                inspect(node);
            }
            if (node.isContainer()) {
                for (JsonNode child : node) {
                    visit(child);
                }
            }
        }

        /** Counts at most one media item per object, so a part naming both a URL and a file id is one item. */
        private void inspect(JsonNode object) {
            JsonNode imageUrl = object.path("image_url");
            String url = imageUrl.isObject() ? imageUrl.path("url").stringValue(null) : imageUrl.stringValue(null);
            if (url != null) {
                int body = base64BodyStart(url);
                if (body >= 0) {
                    inlineImage(url, body);
                } else if (url.regionMatches(true, 0, "data:", 0, 5)) {
                    inlineImages++;
                } else {
                    remoteMedia++;
                }
                return;
            }
            JsonNode source = object.path("source");
            String sourceType = source.path("type").stringValue(null);
            if ("base64".equals(sourceType) && source.path("data").isString()) {
                if ("image".equals(object.path("type").stringValue(null))) {
                    inlineImage(source.path("data").stringValue(), 0);
                } else {
                    remoteBytes(source.path("data").stringValue(), 0);
                }
                return;
            }
            if ("url".equals(sourceType) && source.path("url").isString()) {
                remoteMedia++;
                return;
            }
            JsonNode inline = object.has("inlineData") ? object.path("inlineData") : object.path("inline_data");
            if (inline.path("data").isString()) {
                String mime = firstString(inline, MIME_KEYS);
                if (mime != null && mime.regionMatches(true, 0, "image/", 0, 6)) {
                    inlineImage(inline.path("data").stringValue(), 0);
                } else {
                    remoteBytes(inline.path("data").stringValue(), 0);
                }
                return;
            }
            JsonNode audio = object.path("input_audio");
            if (audio.path("data").isString()) {
                remoteBytes(audio.path("data").stringValue(), 0);
                return;
            }
            // Chat file parts carry a string; Gemini's snake_case file_data is an object, counted by its file_uri.
            String fileData = object.path("file_data").stringValue(null);
            if (fileData != null) {
                remoteBytes(fileData, Math.max(0, base64BodyStart(fileData)));
                return;
            }
            if (firstString(object, FILE_REFERENCE_KEYS) != null) {
                remoteMedia++;
            }
        }

        private void inlineImage(String payload, int from) {
            inlineImages++;
            strip(payload, from);
        }

        private void remoteBytes(String payload, int from) {
            remoteMedia++;
            strip(payload, from);
        }

        /**
         * Leaves out {@code payload[from..]} when all of it is base64 (new lines are escaped to two chars in JSON), and
         * otherwise keeps it in the size: a text that only claims to be base64 is not trusted to be small.
         */
        private void strip(String payload, int from) {
            long serialized = 0;
            for (int i = from; i < payload.length(); i++) {
                char c = payload.charAt(i);
                if (c == '\n' || c == '\r') {
                    serialized += 2;
                } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '-' || c == '_') {
                    serialized++;
                } else {
                    return;
                }
            }
            chars += serialized;
            utf8 += serialized;
        }

        /** Index of the first payload char of a {@code data:...;base64,} URI, or -1 for anything else. */
        private static int base64BodyStart(String value) {
            if (!value.regionMatches(true, 0, "data:", 0, 5)) {
                return -1;
            }
            int comma = value.indexOf(',');
            return comma > 0 && value.regionMatches(true, comma - 7, ";base64", 0, 7) ? comma + 1 : -1;
        }

        private static String firstString(JsonNode object, String[] keys) {
            for (String key : keys) {
                String value = object.path(key).stringValue(null);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
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
             JsonGenerator generator = MAPPER.createGenerator(writer)) {
            MAPPER.writeTree(generator, node);
        } catch (IOException | JacksonException error) {
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
        try (JsonGenerator generator = MAPPER.createGenerator(counter)) {
            MAPPER.writeTree(generator, node);
        } catch (JacksonException error) {
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
