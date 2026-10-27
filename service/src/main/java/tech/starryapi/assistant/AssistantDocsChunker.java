package tech.starryapi.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Markdown into plain-text sections for the assistant's search index: split at headings (levels 1 to 3, never
 * inside fenced code), long sections cut at paragraph boundaries, and everything that must not reach the prompt
 * (images, attachment references, signed media URLs, HTML) removed.
 *
 * <p>The text comes from documents that administrators edit, and it is processed on a server thread while a request
 * waits, so nothing here may take more than linear time however odd the Markdown is. That is why headings are parsed
 * by hand, the patterns have bounded and possessive quantifiers, and comments and media addresses are found with
 * {@code indexOf}.
 */
final class AssistantDocsChunker {

    static final int MAX_CHUNK_CHARS = 1600;
    /** A heading longer than this is cut; it is repeated in the label of every chunk of its section. */
    static final int MAX_HEADING_CHARS = 100;
    /** A section shorter than this that is followed by a deeper heading is carried into that heading's first chunk. */
    static final int SHORT_INTRO_CHARS = 80;
    /** A paragraph this short at the end of a piece introduces what follows ("**Windows CMD**:") and goes with it. */
    static final int LEAD_IN_CHARS = 80;
    /** Joins the headings a section sits under: the parent first. */
    static final String PATH_SEPARATOR = " › ";

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]{0,300}+\\](?:\\([^)]{0,2000}+\\)|\\[[^\\]]{0,300}+\\])");
    // Links to attachments or to other console pages keep their text and lose their target.
    private static final Pattern INTERNAL_LINK = Pattern.compile("\\[([^\\]]{0,300}+)\\]\\((?:attachment:|#)[^)]{0,2000}+\\)");
    private static final Pattern ATTACHMENT_REFERENCE = Pattern.compile("attachment:[0-9A-Za-z][0-9A-Za-z-]{7,}");
    // What may stay in code is anything but a reference to an attachment, so the whole embed goes, not just its id.
    private static final Pattern ATTACHMENT_EMBED = Pattern.compile("!?\\[[^\\]]{0,300}+\\]\\(attachment:[^)]{0,200}+\\)");
    private static final String MEDIA_PATH = "/documentation/media/";
    // Only real HTML elements are removed, so placeholders such as <model> or <UUID> in prose survive.
    private static final Pattern HTML_TAG = Pattern.compile(
        "(?i)</?(?:a|abbr|audio|b|blockquote|br|button|center|code|details|div|em|embed|figcaption|figure|font|form|"
            + "h[1-6]|hr|i|iframe|img|input|kbd|label|li|mark|object|ol|p|picture|pre|script|section|small|source|"
            + "span|strong|style|sub|summary|sup|svg|table|tbody|td|th|thead|tr|u|ul|video)(?:\\s[^<>]*+)?+/?>"
    );
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*+/?>");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*+`");
    private static final Pattern EMPHASIS = Pattern.compile("\\*{1,3}|`{1,3}");

    private AssistantDocsChunker() {
    }

    /**
     * One piece of a document.
     *
     * @param ordinal the position of its heading among all headings of the document (0 before the first); the pieces
     *                of one long section share it, which is how the two language versions of a page are matched up
     * @param heading the headings it sits under, the parent first (empty before the first heading)
     * @param body    its plain text
     */
    record Section(int ordinal, String heading, String body) {
    }

    /** The sections of a document and how many headings it has, whether or not they ended up with any text. */
    record Parsed(List<Section> sections, int headings) {
    }

    /**
     * Splits Markdown into sections of at most {@link #MAX_CHUNK_CHARS} characters. A section that is longer is
     * continued in further pieces under the same heading rather than cut off. Pieces shorter than {@code minChars}
     * are dropped.
     */
    static List<Section> split(String markdown, int minChars) {
        return parse(markdown, minChars).sections();
    }

    static Parsed parse(String markdown, int minChars) {
        if (markdown == null || markdown.isBlank()) {
            return new Parsed(List.of(), 0);
        }
        List<Section> sections = new ArrayList<>();
        Blocks blocks = new Blocks();
        String[] outline = new String[4];
        String path = "";
        int level = 0;
        int ordinal = 0;
        int headings = 0;
        List<Block> carried = List.of();
        String fenceMarker = null;
        boolean inComment = false;
        for (String raw : (Iterable<String>) markdown.lines()::iterator) {
            if (fenceMarker != null) {
                blocks.codeLine(codeSafe(raw));
                if (closesFence(raw, fenceMarker)) {
                    fenceMarker = null;
                    blocks.endBlock();
                }
                continue;
            }
            Matcher fence = FENCE.matcher(raw);
            if (fence.matches() && !(fence.group(1).charAt(0) == '`' && fence.group(2).contains("`"))) {
                blocks.endBlock();
                fenceMarker = fence.group(1);
                blocks.codeLine(codeSafe(raw));
                continue;
            }
            String line = raw;
            if (inComment) {
                int end = line.indexOf("-->");
                if (end < 0) {
                    continue;
                }
                inComment = false;
                line = line.substring(end + 3);
            }
            if (line.contains("<!--")) {
                line = withoutCompleteComments(line);
                int open = line.indexOf("<!--");
                if (open >= 0) {
                    inComment = true;
                    line = line.substring(0, open);
                }
            }
            int depth = headingLevel(line);
            if (depth > 0) {
                blocks.endBlock();
                List<Block> section = new ArrayList<>(carried);
                section.addAll(blocks.take());
                // A one-line introduction to subsections is no answer by itself, but it says what they are about.
                if (level > 0 && depth > level && !section.isEmpty() && textLength(section) < SHORT_INTRO_CHARS) {
                    carried = section;
                } else {
                    emit(ordinal, path, section, minChars, sections);
                    carried = List.of();
                }
                headings++;
                ordinal = headings;
                level = depth;
                outline[depth] = headingText(headingContent(line));
                for (int deeper = depth + 1; deeper < outline.length; deeper++) {
                    outline[deeper] = null;
                }
                path = path(outline, depth);
                continue;
            }
            String text = clean(line);
            if (text.isBlank()) {
                blocks.endBlock();
            } else {
                blocks.textLine(text);
            }
        }
        blocks.endBlock();
        List<Block> last = new ArrayList<>(carried);
        last.addAll(blocks.take());
        emit(ordinal, path, last, minChars, sections);
        return new Parsed(sections, headings);
    }

    /** Removes images, attachment references, signed media URLs and HTML from one line of prose. */
    static String clean(String line) {
        String text = IMAGE.matcher(line).replaceAll("");
        text = INTERNAL_LINK.matcher(text).replaceAll("$1");
        text = ATTACHMENT_REFERENCE.matcher(text).replaceAll("");
        text = withoutMediaUrls(text);
        text = stripHtml(text);
        return text.stripTrailing();
    }

    /**
     * Removes only what must never reach the prompt from a line of fenced code: attachment embeds and references and
     * signed media addresses. Everything else, HTML included, is something the reader is meant to type.
     */
    static String codeSafe(String line) {
        String text = line;
        if (text.contains("attachment:")) {
            text = ATTACHMENT_EMBED.matcher(text).replaceAll("");
            text = ATTACHMENT_REFERENCE.matcher(text).replaceAll("");
        }
        return withoutMediaUrls(text);
    }

    /** Drops every whitespace-delimited token that contains a documentation media path, scanning once. */
    private static String withoutMediaUrls(String text) {
        int at = text.indexOf(MEDIA_PATH);
        if (at < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (at >= 0) {
            int start = at;
            while (start > from && !Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }
            int end = at + MEDIA_PATH.length();
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            out.append(text, from, start);
            from = end;
            at = text.indexOf(MEDIA_PATH, end);
        }
        return out.append(text, from, text.length()).toString();
    }

    private static String withoutCompleteComments(String line) {
        int open = line.indexOf("<!--");
        StringBuilder out = new StringBuilder(line.length());
        int from = 0;
        while (open >= 0) {
            int close = line.indexOf("-->", open + 4);
            if (close < 0) {
                break;
            }
            out.append(line, from, open);
            from = close + 3;
            open = line.indexOf("<!--", from);
        }
        return out.append(line, from, line.length()).toString();
    }

    private static String stripHtml(String text) {
        if (text.indexOf('<') < 0) {
            return text;
        }
        text = LINE_BREAK.matcher(text).replaceAll(" ");
        // Inline code is left alone: `<tag>` there is something the reader is meant to type.
        StringBuilder out = new StringBuilder(text.length());
        Matcher code = INLINE_CODE.matcher(text);
        int from = 0;
        while (code.find()) {
            out.append(HTML_TAG.matcher(text.substring(from, code.start())).replaceAll(""));
            out.append(code.group());
            from = code.end();
        }
        out.append(HTML_TAG.matcher(text.substring(from)).replaceAll(""));
        return out.toString();
    }

    /**
     * The level (1 to 3) of an ATX heading line, or 0 when the line is not one. Up to three spaces may precede the
     * hashes, which are followed by a space or tab; deeper headings stay in the text of their section.
     */
    static int headingLevel(String line) {
        int index = 0;
        while (index < line.length() && index <= 3 && line.charAt(index) == ' ') {
            index++;
        }
        if (index > 3) {
            return 0;
        }
        int hashes = index;
        while (hashes < line.length() && line.charAt(hashes) == '#') {
            hashes++;
        }
        int level = hashes - index;
        if (level < 1 || level > 3 || hashes >= line.length()) {
            return 0;
        }
        char next = line.charAt(hashes);
        return next == ' ' || next == '\t' ? level : 0;
    }

    /** The text of a heading line without the opening hashes and without a closing run of hashes. */
    private static String headingContent(String line) {
        int start = 0;
        while (line.charAt(start) == ' ') {
            start++;
        }
        while (line.charAt(start) == '#') {
            start++;
        }
        int end = line.length();
        while (end > start && isBlank(line.charAt(end - 1))) {
            end--;
        }
        int closing = end;
        while (closing > start && line.charAt(closing - 1) == '#') {
            closing--;
        }
        // "## Title ##" loses its closing hashes, "## C#" keeps its own.
        if (closing < end && (closing == start || isBlank(line.charAt(closing - 1)))) {
            end = closing;
            while (end > start && isBlank(line.charAt(end - 1))) {
                end--;
            }
        }
        while (start < end && isBlank(line.charAt(start))) {
            start++;
        }
        return line.substring(start, end);
    }

    private static boolean isBlank(char value) {
        return value == ' ' || value == '\t';
    }

    private static String headingText(String markdown) {
        String text = EMPHASIS.matcher(clean(markdown)).replaceAll("").strip();
        if (text.length() <= MAX_HEADING_CHARS) {
            return text;
        }
        int end = MAX_HEADING_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + "…";
    }

    private static String path(String[] outline, int level) {
        StringBuilder path = new StringBuilder();
        for (int depth = 1; depth <= level; depth++) {
            if (outline[depth] != null && !outline[depth].isEmpty()) {
                if (!path.isEmpty()) {
                    path.append(PATH_SEPARATOR);
                }
                path.append(outline[depth]);
            }
        }
        return path.toString();
    }

    private static boolean closesFence(String line, String marker) {
        String trimmed = line.strip();
        if (trimmed.length() < marker.length()) {
            return false;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (trimmed.charAt(index) != marker.charAt(0)) {
                return false;
            }
        }
        return line.length() - line.stripLeading().length() <= 3;
    }

    private static int textLength(List<Block> blocks) {
        int length = 0;
        for (Block block : blocks) {
            length += block.text().length() + 2;
        }
        return Math.max(0, length - 2);
    }

    private static void emit(int ordinal, String heading, List<Block> blocks, int minChars, List<Section> out) {
        List<Block> piece = new ArrayList<>();
        for (Block block : blocks) {
            if (block.text().length() > MAX_CHUNK_CHARS) {
                // The short paragraph that introduces a block too big for a piece goes with the block's first piece.
                Block lead = takeLeadIn(piece, 0);
                flush(ordinal, heading, piece, minChars, out);
                for (String part : cut(block, lead == null ? null : lead.text())) {
                    add(ordinal, heading, part, minChars, out);
                }
                continue;
            }
            if (!piece.isEmpty() && textLength(piece) + 2 + block.text().length() > MAX_CHUNK_CHARS) {
                // So does a label that ends a piece when the block it labels starts the next one.
                Block lead = takeLeadIn(piece, block.text().length());
                flush(ordinal, heading, piece, minChars, out);
                if (lead != null) {
                    piece.add(lead);
                }
            }
            piece.add(block);
        }
        flush(ordinal, heading, piece, minChars, out);
    }

    /**
     * Takes the last block off the piece when it is a short paragraph that leads into whatever comes next and the next
     * block (of {@code nextChars} characters, 0 when it is cut into pieces anyway) still fits with it.
     */
    private static Block takeLeadIn(List<Block> piece, int nextChars) {
        if (piece.isEmpty()) {
            return null;
        }
        Block last = piece.getLast();
        if (last.code() || last.text().length() >= LEAD_IN_CHARS
            || (nextChars > 0 && last.text().length() + 2 + nextChars > MAX_CHUNK_CHARS)) {
            return null;
        }
        piece.removeLast();
        return last;
    }

    private static void flush(int ordinal, String heading, List<Block> piece, int minChars, List<Section> out) {
        add(ordinal, heading, String.join("\n\n", piece.stream().map(Block::text).toList()), minChars, out);
        piece.clear();
    }

    private static void add(int ordinal, String heading, String text, int minChars, List<Section> out) {
        String body = text.strip();
        // The heading counts towards the minimum: "## 版本\n1.2" is a perfectly good answer to a version question.
        int length = body.length() + (heading.isEmpty() ? 0 : heading.length() + 1);
        if (!body.isEmpty() && length >= minChars) {
            out.add(new Section(ordinal, heading, body));
        }
    }

    /**
     * Cuts a block that is too long at line boundaries (and inside a single enormous line only as a last resort). A
     * piece of fenced code is closed and the next one reopened with the same language, so that no excerpt leaves a
     * fence open; the pieces of a table repeat its header rows.
     */
    private static List<String> cut(Block block, String lead) {
        List<String> lines = new ArrayList<>(List.of(block.text().split("\n", -1)));
        if (block.code()) {
            Matcher opener = FENCE.matcher(lines.getFirst());
            if (opener.matches()) {
                String marker = opener.group(1);
                String[] info = opener.group(2).strip().split("\\s+", 2);
                String language = info[0].length() > 30 ? info[0].substring(0, 30) : info[0];
                lines.removeFirst();
                if (!lines.isEmpty() && closesFence(lines.getLast(), marker)) {
                    lines.removeLast();
                }
                return cutLines(lines, marker + language, marker, lead);
            }
        }
        if (lines.size() > 2 && isTableRow(lines.get(0)) && isTableSeparator(lines.get(1))) {
            String header = lines.get(0) + "\n" + lines.get(1);
            if (header.length() <= 400) {
                return cutLines(lines.subList(2, lines.size()), header, "", lead);
            }
        }
        return cutLines(lines, "", "", lead);
    }

    /** @param lead a short paragraph that introduces the block and is put in front of its first piece; may be null */
    private static List<String> cutLines(List<String> lines, String head, String tail, String lead) {
        int overhead = (head.isEmpty() ? 0 : head.length() + 1) + (tail.isEmpty() ? 0 : tail.length() + 1);
        int room = Math.max(200, MAX_CHUNK_CHARS - overhead);
        int firstRoom = lead == null ? room : Math.max(200, room - lead.length() - 2);
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (String line : lines) {
            while (line.length() > (parts.isEmpty() ? firstRoom : room)) {
                int end = parts.isEmpty() ? firstRoom : room;
                if (Character.isHighSurrogate(line.charAt(end - 1))) {
                    end--;
                }
                if (!part.isEmpty()) {
                    parts.add(wrap(head, part.toString(), tail, parts.isEmpty() ? lead : null));
                    part.setLength(0);
                }
                parts.add(wrap(head, line.substring(0, end), tail, parts.isEmpty() ? lead : null));
                line = line.substring(end);
            }
            if (!part.isEmpty() && part.length() + 1 + line.length() > (parts.isEmpty() ? firstRoom : room)) {
                parts.add(wrap(head, part.toString(), tail, parts.isEmpty() ? lead : null));
                part.setLength(0);
            }
            if (!part.isEmpty()) {
                part.append('\n');
            }
            part.append(line);
        }
        if (!part.isEmpty()) {
            parts.add(wrap(head, part.toString(), tail, parts.isEmpty() ? lead : null));
        }
        return parts;
    }

    private static String wrap(String head, String body, String tail, String lead) {
        return (lead == null ? "" : lead + "\n\n") + (head.isEmpty() ? "" : head + "\n") + body
            + (tail.isEmpty() ? "" : "\n" + tail);
    }

    private static boolean isTableRow(String line) {
        return line.stripLeading().startsWith("|");
    }

    private static boolean isTableSeparator(String line) {
        String text = line.strip();
        boolean dash = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '-') {
                dash = true;
            } else if (value != '|' && value != ':' && value != ' ') {
                return false;
            }
        }
        return dash && text.indexOf('|') >= 0;
    }

    /** A paragraph, table or fenced code block of a section. */
    private record Block(String text, boolean code) {
    }

    /** Collects the lines of one section into blocks: paragraphs, and fenced code blocks kept whole. */
    private static final class Blocks {
        private final List<Block> done = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private boolean code;

        void textLine(String line) {
            append(line, false);
        }

        void codeLine(String line) {
            append(line, true);
        }

        private void append(String line, boolean isCode) {
            if (!current.isEmpty()) {
                current.append('\n');
            } else {
                code = isCode;
            }
            current.append(line);
        }

        void endBlock() {
            if (!current.isEmpty()) {
                done.add(new Block(current.toString(), code));
                current.setLength(0);
            }
        }

        List<Block> take() {
            endBlock();
            List<Block> blocks = new ArrayList<>(done);
            done.clear();
            return blocks;
        }
    }
}
