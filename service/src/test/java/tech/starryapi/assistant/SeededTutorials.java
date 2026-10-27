package tech.starryapi.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.starryapi.documentation.DocumentationDtos.Content;
import tech.starryapi.documentation.DocumentationDtos.Document;
import tech.starryapi.documentation.DocumentationDtos.Translation;

/**
 * The tutorials a fresh installation publishes, read out of the Flyway migrations that install them (V23, then V24,
 * which replaces eight of V23's pages): the real texts, without a database. The migrations write every page as the same
 * jsonb_build_object call with the body in a {@code $doc$} quote, which is all this reads. The PostgreSQL test of the
 * assistant compares the result with what the migrated schema really publishes: the same pages, in the same order, with
 * the same text. A migration that changes, adds, removes or reorders a tutorial without this noticing fails there.
 */
final class SeededTutorials {

    /** The migrations in the order they are applied; a later one replaces a page of an earlier one. */
    private static final List<String> MIGRATIONS = List.of(
        "db/migration/V23__documentation_tutorials.sql", "db/migration/V24__documentation_user_focus.sql");
    private static final Pattern ENTRY = Pattern.compile("(?m)^ {4}\\('([a-z0-9-]+)', ");
    private static final String ENGLISH = "'english', jsonb_build_object(";
    private static final Pattern BODY = Pattern.compile("(?s)'body', \\$doc\\$(.*?)\\$doc\\$");

    private SeededTutorials() {
    }

    /** The published pages in catalog order (sort order, then title), as {@code publishedPages()} lists them. */
    static List<Document> pages() {
        Map<String, Content> byId = new LinkedHashMap<>();
        for (String migration : MIGRATIONS) {
            byId.putAll(read(migration));
        }
        List<Document> pages = new ArrayList<>();
        byId.forEach((id, content) -> pages.add(new Document(id, content, Instant.EPOCH, List.of())));
        pages.sort(Comparator.<Document>comparingInt(page -> page.content().sortOrder())
            .thenComparing(page -> page.content().title()).thenComparing(Document::id));
        return pages;
    }

    private static Map<String, Content> read(String resource) {
        String sql = text(resource);
        Matcher entries = ENTRY.matcher(sql);
        List<int[]> starts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (entries.find()) {
            starts.add(new int[] {entries.start(), entries.end()});
            ids.add(entries.group(1));
        }
        Map<String, Content> pages = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            int end = index + 1 < starts.size() ? starts.get(index + 1)[0] : sql.length();
            pages.put(ids.get(index), content(sql.substring(starts.get(index)[1], end)));
        }
        return pages;
    }

    private static Content content(String entry) {
        int english = entry.indexOf(ENGLISH);
        String chinese = english < 0 ? entry : entry.substring(0, english);
        Translation translation = english < 0 ? null : new Translation(
            string(entry, "title", english), string(entry, "category", english), string(entry, "summary", english),
            body(entry.substring(english)));
        return new Content(string(chinese, "title", 0), string(chinese, "category", 0), string(chinese, "summary", 0),
            body(chinese), Integer.parseInt(find(chinese, "'sortOrder', (\\d+)", 0)), translation);
    }

    private static String body(String part) {
        Matcher body = BODY.matcher(part);
        if (!body.find()) {
            throw new IllegalStateException("No body in " + part.substring(0, Math.min(80, part.length())));
        }
        return body.group(1);
    }

    /** A single-quoted SQL string value of the key, searched from {@code from}; '' is a quote. */
    private static String string(String text, String key, int from) {
        return find(text, "'" + key + "', '((?:[^']|'')*)'", from).replace("''", "'");
    }

    private static String find(String text, String pattern, int from) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        if (!matcher.find(from)) {
            throw new IllegalStateException("No " + pattern + " in " + text.substring(0, Math.min(80, text.length())));
        }
        return matcher.group(1);
    }

    private static String text(String resource) {
        try (InputStream in = SeededTutorials.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
