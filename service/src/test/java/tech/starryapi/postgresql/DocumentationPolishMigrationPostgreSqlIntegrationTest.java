package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * V33 (the 开始使用 tutorials trimmed: four deleted, two rewritten; the others polished): like V30, it replaces or deletes a tutorial only while it is
 * exactly the version the earlier migrations left, which it recognises by the md5 of the stored draft. The test runs the
 * real migrations on a database that stands at V32, so a hash that does not match (a changed seed, a hash copied wrongly)
 * shows up as a tutorial that was not replaced or not deleted. A deletion is the console's: unpublished, marked deleted.
 */
@Timeout(240)
class DocumentationPolishMigrationPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String POLISH = "/db/migration/V33__documentation_trim_and_polish.sql";
    private static final Pattern ENTRY = Pattern.compile("(?m)^ {4}\\('([a-z0-9-]+)', '[0-9a-f]{32}', jsonb_build_object\\(");
    /** Rewritten as short pages: they get new titles and English as well. */
    private static final List<String> REWRITTEN = List.of("billing", "api-keys");
    private static final Pattern REMOVED_ENTRY = Pattern.compile("(?m)^ {4}\\('([a-z0-9-]+)', '[0-9a-f]{32}'\\)");

    @Test
    void everyTutorialStillAsTheEarlierMigrationsLeftItIsReplacedOrDeletedAndNothingElseChanges() throws Exception {
        List<String> revised = listed(ENTRY);
        List<String> removed = listed(REMOVED_ENTRY);
        String url = database("tutorial_polish_untouched");
        migrate(url, "32");
        Map<String, Page> before;
        try (Connection db = owner(url)) {
            before = pages(db);
            assertThat(before.keySet()).containsAll(revised).containsAll(removed);
            // V30 left every one of them published and identical to the draft, which is what V33 looks for.
            assertThat(before.values()).allSatisfy(page -> assertThat(page.draft()).isEqualTo(page.published()));
        }

        migrate(url, null);

        try (Connection db = owner(url)) {
            assertThat(scalar(db, "SELECT success FROM flyway_schema_history WHERE version = '33'")).isEqualTo(true);
            Map<String, Page> after = pages(db);
            assertThat(after.keySet()).isEqualTo(before.keySet());
            for (String id : after.keySet()) {
                Page was = before.get(id);
                Page page = after.get(id);
                if (removed.contains(id)) {
                    assertThat(page.deleted()).as(id + " is deleted").isTrue();
                    assertThat(page.published()).as(id).isNull();
                    assertThat(page.version()).as(id).isEqualTo(was.version() + 1);
                    assertThat(page.draft()).as(id + ": the draft is kept, as the console keeps it").isEqualTo(was.draft());
                } else if (revised.contains(id)) {
                    assertThat(page.deleted()).as(id).isFalse();
                    assertThat(page.version()).as(id).isEqualTo(was.version() + 1);
                    assertThat(page.draft()).as(id).isEqualTo(page.published()).isNotEqualTo(was.draft());
                    assertThat(page.zhSummary()).as(id).isNotBlank().doesNotContain("\n");
                    for (String gone : removed) {
                        assertThat(page.zhBody()).as(id).isNotBlank().doesNotContain("#/documentation/" + gone);
                    }
                    assertThat(page.category()).as(id).isEqualTo(was.category());
                    assertThat(page.sortOrder()).as(id).isEqualTo(was.sortOrder());
                    if (!REWRITTEN.contains(id)) {
                        assertThat(page.title()).as(id).isEqualTo(was.title());
                        assertThat(page.englishHead()).as(id + ": the English title, category and summary").isEqualTo(was.englishHead());
                    }
                } else {
                    assertThat(page).as(id).isEqualTo(was);
                }
            }
            assertThat(revised).containsAll(REWRITTEN);
            assertThat(after.get("api-keys").title()).isEqualTo("API Key 与模型");
            assertThat(scalar(db, "SELECT count(*) FROM documentation_pages WHERE deleted = FALSE AND published IS NOT NULL"))
                .isEqualTo((long) (before.size() - removed.size()));
        }
    }

    @Test
    void aTutorialAnAdministratorEditedUnpublishedOrDeletedIsLeftAloneAndTheOthersAreReplacedOrDeleted() throws Exception {
        List<String> revised = listed(ENTRY);
        List<String> removed = listed(REMOVED_ENTRY);
        assertThat(revised).hasSizeGreaterThanOrEqualTo(3);
        assertThat(removed).hasSizeGreaterThanOrEqualTo(3);
        String editedAndPublished = removed.get(0);
        String unpublished = removed.get(1);
        String editedDraft = revised.get(0);
        String deleted = revised.get(1);
        String url = database("tutorial_polish_changed");
        migrate(url, "32");
        Map<String, Page> before;
        try (Connection db = owner(url)) {
            before = pages(db);
            // Edited and published again: draft and publication agree, but they are no longer the seed.
            update(db, "UPDATE documentation_pages SET draft = jsonb_set(draft, '{summary}', '\"Our own summary\"'),"
                + " published = jsonb_set(published, '{summary}', '\"Our own summary\"') WHERE id = '" + editedAndPublished + "'");
            // Edited and only saved as a draft.
            update(db, "UPDATE documentation_pages SET draft = jsonb_set(draft, '{summary}', '\"A draft\"') WHERE id = '"
                + editedDraft + "'");
            // Taken offline.
            update(db, "UPDATE documentation_pages SET published = NULL WHERE id = '" + unpublished + "'");
            // Deleted.
            update(db, "UPDATE documentation_pages SET deleted = TRUE WHERE id = '" + deleted + "'");
        }
        Map<String, Page> edited;
        try (Connection db = owner(url)) {
            edited = pages(db);
        }

        migrate(url, null);

        try (Connection db = owner(url)) {
            Map<String, Page> after = pages(db);
            for (String id : List.of(editedAndPublished, editedDraft, unpublished, deleted)) {
                assertThat(after.get(id)).as(id + " is left alone").isEqualTo(edited.get(id));
            }
            assertThat(after.get(editedAndPublished).deleted()).isFalse();
            assertThat(after.get(editedAndPublished).zhSummary()).isEqualTo("Our own summary");
            assertThat(after.get(editedDraft).zhSummary()).isEqualTo("A draft");
            for (String id : revised.subList(2, revised.size())) {
                assertThat(after.get(id).version()).as(id).isEqualTo(before.get(id).version() + 1);
                assertThat(after.get(id).draft()).as(id).isNotEqualTo(before.get(id).draft());
            }
            for (String id : removed.subList(2, removed.size())) {
                assertThat(after.get(id).deleted()).as(id + " is deleted").isTrue();
            }
        }
    }

    // ---- fixtures ----

    /** The tutorials V33 lists in one of its two parts, in its order. */
    private static List<String> listed(Pattern entryPattern) throws IOException {
        String sql;
        try (var in = DocumentationPolishMigrationPostgreSqlIntegrationTest.class.getResourceAsStream(POLISH)) {
            assertThat(in).as(POLISH).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> ids = new ArrayList<>();
        Matcher entry = entryPattern.matcher(sql);
        while (entry.find()) ids.add(entry.group(1));
        assertThat(ids).isNotEmpty().doesNotHaveDuplicates();
        return ids;
    }

    /** What the test compares of a page: the whole stored draft and publication as text, and the parts it reads. */
    private record Page(String id, long version, boolean deleted, String draft, String published, String title,
                        String category, String sortOrder, String zhSummary, String zhBody, String englishHead) {}

    private static Map<String, Page> pages(Connection db) throws SQLException {
        Map<String, Page> pages = new LinkedHashMap<>();
        try (PreparedStatement statement = db.prepareStatement(
            "SELECT id, version, deleted, draft::text AS draft, published::text AS published, draft->>'title' AS title,"
                + " draft->>'category' AS category, draft->>'sortOrder' AS sort_order, draft->>'summary' AS summary,"
                + " draft->>'body' AS zh, (draft->'english') - 'body' AS english FROM documentation_pages ORDER BY id");
            ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                pages.put(result.getString("id"), new Page(result.getString("id"), result.getLong("version"),
                    result.getBoolean("deleted"), result.getString("draft"), result.getString("published"),
                    result.getString("title"), result.getString("category"), result.getString("sort_order"),
                    result.getString("summary"), result.getString("zh"), result.getString("english")));
            }
        }
        return pages;
    }

    private static long update(Connection db, String sql) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static Object scalar(Connection db, String sql) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).as(sql).isTrue();
            return result.getObject(1);
        }
    }

    private static Connection owner(String url) throws SQLException {
        Connection connection = DriverManager.getConnection(url, MIGRATION_USERNAME, MIGRATION_PASSWORD);
        try (var statement = connection.createStatement()) {
            statement.execute("SET ROLE starry_api_owner");
            statement.execute("SET search_path TO starry_api");
        }
        return connection;
    }

    private static String database(String name) throws Exception {
        // Each -c is its own transaction, which CREATE DATABASE needs.
        exec("psql", "-U", "starry_api_bootstrap", "-d", "starry_api", "-v", "ON_ERROR_STOP=1",
            "-c", "CREATE DATABASE " + name + " OWNER starry_api_owner",
            "-c", "REVOKE ALL ON DATABASE " + name + " FROM PUBLIC",
            "-c", "GRANT CONNECT ON DATABASE " + name + " TO starry_api_migration, starry_api_runtime");
        exec("psql", "-U", "starry_api_bootstrap", "-d", name, "-v", "ON_ERROR_STOP=1", "-c", """
            CREATE SCHEMA starry_api AUTHORIZATION starry_api_owner;
            REVOKE ALL ON SCHEMA starry_api FROM PUBLIC;
            GRANT USAGE ON SCHEMA starry_api TO starry_api_runtime;
            REVOKE ALL ON SCHEMA public FROM PUBLIC;
            """);
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
    }

    private static void exec(String... command) throws Exception {
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
    }

    private static void migrate(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, MIGRATION_USERNAME, MIGRATION_PASSWORD)
            .locations("classpath:db/migration").schemas("starry_api").defaultSchema("starry_api").createSchemas(false)
            .initSql("SET ROLE starry_api_owner");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
