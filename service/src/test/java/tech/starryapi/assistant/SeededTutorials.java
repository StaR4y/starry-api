package tech.starryapi.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tech.starryapi.documentation.DocumentationDtos.Content;
import tech.starryapi.documentation.DocumentationDtos.Document;
import tech.starryapi.documentation.DocumentationDtos.Translation;

/**
 * The tutorials a fresh installation publishes, read out of the Flyway migrations that install them: the real texts,
 * without a database. V23 installs the pages; each later documentation migration (V24, V30, ...) replaces some of them
 * and, like the database, is applied in version order, so the last one to write a page decides its text. Which
 * migrations those are is not listed here: every migration in {@code db/migration} (in any folder below it, as Flyway
 * scans) that mentions the table of the pages and is written as the tutorials migrations are ({@code WITH tutorials(...)
 * AS (VALUES ...)}, one jsonb_build_object per page with the body in a {@code $doc$} quote, which is all this reads) is
 * taken up from V23 on. The order is the one of Flyway's {@link MigrationVersion}, so a V31.1 (or V31_1) falls between V31
 * and V32 as it does in the database.
 *
 * <p>Whatever else touches the documentation pages is refused here, with its name, instead of being silently left out:
 * a migration written in another form, one that Flyway does not order by a version (a repeatable {@code R__}, an
 * undo {@code U__}, or a name this cannot parse), two with the same version, and one older than V23 that is not one of
 * the two first-generation migrations V23 replaces. The PostgreSQL test of the assistant compares the result with what
 * the migrated schema really publishes: the same pages, in the same order, with the same text. A migration that changes,
 * adds, removes or reorders a tutorial without this noticing fails there.
 */
final class SeededTutorials {

    /** V23 installs the tutorials. */
    private static final MigrationVersion FIRST_VERSION = MigrationVersion.fromVersion("23");
    /** V21 created the pages and V22 added their English text; V23 replaces the six pages they wrote and adds the rest. */
    private static final Set<MigrationVersion> REPLACED_BY_FIRST = Set.of(MigrationVersion.fromVersion("21"),
        MigrationVersion.fromVersion("22"));
    private static final String LOCATION = "classpath*:db/migration/**/*.sql";
    /** Flyway's naming of a versioned migration, its default: V, the version (parts joined by . or _), two underscores. */
    private static final Pattern VERSIONED = Pattern.compile("V(\\d+(?:[._]\\d+)*)__.+\\.sql");
    private static final String TUTORIALS = "WITH tutorials(";
    private static final String PAGES_TABLE = "documentation_pages";
    private static final Pattern ENTRY = Pattern.compile("(?m)^ {4}\\('([a-z0-9-]+)', ");
    private static final String ENGLISH = "'english', jsonb_build_object(";
    private static final Pattern BODY = Pattern.compile("(?s)'body', \\$doc\\$(.*?)\\$doc\\$");

    private SeededTutorials() {
    }

    /** The published pages in catalog order (sort order, then title), as {@code publishedPages()} lists them. */
    static List<Document> pages() {
        Map<String, Content> byId = new LinkedHashMap<>();
        for (String migration : migrations()) {
            byId.putAll(read(migration));
        }
        List<Document> pages = new ArrayList<>();
        byId.forEach((id, content) -> pages.add(new Document(id, content, Instant.EPOCH, List.of())));
        pages.sort(Comparator.<Document>comparingInt(page -> page.content().sortOrder())
            .thenComparing(page -> page.content().title()).thenComparing(Document::id));
        return pages;
    }

    /** The SQL of the documentation migrations from V23 on, in the order Flyway applies them (by version). */
    private static List<String> migrations() {
        Map<MigrationVersion, String> byVersion = new TreeMap<>();
        Map<MigrationVersion, String> nameOf = new TreeMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver(SeededTutorials.class.getClassLoader())
                .getResources(LOCATION)) {
                String name = String.valueOf(resource.getFilename());
                String sql = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
                if (!sql.contains(PAGES_TABLE)) {
                    continue;
                }
                Matcher versioned = VERSIONED.matcher(name);
                if (!versioned.matches()) {
                    throw refused(name, "is not a versioned migration (V<version>__<description>.sql), so Flyway does not"
                        + " order it by a version and SeededTutorials cannot tell where in the order it falls");
                }
                MigrationVersion version = MigrationVersion.fromVersion(versioned.group(1).replace('_', '.'));
                if (version.compareTo(FIRST_VERSION) >= 0) {
                    if (!sql.contains(TUTORIALS)) {
                        throw refused(name, "is not written as the tutorial migrations are (WITH tutorials(...)), so"
                            + " SeededTutorials cannot read what it publishes");
                    }
                    if (byVersion.put(version, sql) != null) {
                        throw refused(name, "has the same version as " + nameOf.get(version) + " (V" + version + ")");
                    }
                    nameOf.put(version, name);
                } else if (!REPLACED_BY_FIRST.contains(version)) {
                    throw refused(name, "is older than V" + FIRST_VERSION + " and is not one of the migrations it replaces"
                        + " (V21 and V22), so SeededTutorials cannot tell what it publishes");
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        if (byVersion.isEmpty()) {
            throw new IllegalStateException("No documentation migration found at " + LOCATION);
        }
        return List.copyOf(byVersion.values());
    }

    private static IllegalStateException refused(String migration, String why) {
        return new IllegalStateException(migration + " touches the documentation pages but " + why);
    }

    private static Map<String, Content> read(String sql) {
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
}
