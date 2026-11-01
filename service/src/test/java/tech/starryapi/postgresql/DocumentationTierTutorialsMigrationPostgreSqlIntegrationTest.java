package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * V30 (the default tutorials learn about source tiers): it replaces a tutorial only while it is exactly the version
 * V21-V24 left, which it recognises by the md5 of the stored draft. The test runs the real migrations on a database that
 * stands at V29, so a hash that does not match what V24 leaves (a changed seed, a hash copied wrongly) shows up as a
 * tutorial that was not replaced.
 */
@Timeout(240)
class DocumentationTierTutorialsMigrationPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final List<String> REVISED = List.of(
        "api-keys", "models-and-pricing", "errors", "claude-code", "codex-cli", "opencode");
    private static final List<String> NOT_REVISED = List.of(
        "getting-started", "console-tour", "billing", "coding-plan", "cc-switch", "manual-api", "faq", "tickets");

    @Test
    void everyTutorialStillAsTheEarlierMigrationsLeftItIsReplacedAndNoOtherIsTouched() throws Exception {
        String url = database("tier_tutorials_untouched");
        migrate(url, "29");
        Map<String, Page> before;
        try (Connection db = owner(url)) {
            before = pages(db);
            assertThat(before.keySet()).containsAll(REVISED).containsAll(NOT_REVISED);
            // V24 left every one of them published and identical to the draft, which is what V30 looks for.
            assertThat(before.values()).allSatisfy(page -> assertThat(page.draft()).isEqualTo(page.published()));
        }

        migrate(url, null);

        try (Connection db = owner(url)) {
            assertThat(scalar(db, "SELECT success FROM flyway_schema_history WHERE version = '30'")).isEqualTo(true);
            Map<String, Page> after = pages(db);
            for (String id : REVISED) {
                Page page = after.get(id);
                assertThat(page.version()).as(id).isEqualTo(before.get(id).version() + 1);
                assertThat(page.draft()).as(id).isEqualTo(page.published()).isNotEqualTo(before.get(id).draft());
                assertThat(page.zhBody()).as(id).contains("号池");
                assertThat(page.enBody()).as(id).contains("tier");
            }
            assertThat(after.get("api-keys").zhBody()).contains("号源偏好");
            assertThat(after.get("api-keys").enBody()).contains("Tier preference");
            assertThat(after.get("models-and-pricing").zhBody()).contains("模型@标识");
            assertThat(after.get("models-and-pricing").enBody()).contains("model@tier");
            assertThat(after.get("errors").zhBody()).contains("SOURCE_TIER_NOT_SELECTABLE", "SOURCE_TIER_UNAVAILABLE",
                "RESPONSE_OWNER_TIER_MISMATCH", "API_KEY_TIER_PREFERENCE_INVALID");
            assertThat(after.get("errors").enBody()).contains("SOURCE_TIER_NOT_SELECTABLE", "SOURCE_TIER_UNAVAILABLE",
                "RESPONSE_OWNER_TIER_MISMATCH", "API_KEY_TIER_PREFERENCE_INVALID");
            assertThat(after.get("claude-code").zhBody()).contains("ANTHROPIC_CUSTOM_HEADERS");
            assertThat(after.get("codex-cli").zhBody()).contains("http_headers");
            assertThat(after.get("opencode").zhBody()).contains("X-Starry-Tier");
            for (String id : NOT_REVISED) {
                assertThat(after.get(id)).as(id).isEqualTo(before.get(id));
            }
        }
    }

    @Test
    void aTutorialAnAdministratorEditedUnpublishedOrDeletedIsLeftAloneAndTheOthersAreReplaced() throws Exception {
        String url = database("tier_tutorials_changed");
        migrate(url, "29");
        Map<String, Page> before;
        try (Connection db = owner(url)) {
            before = pages(db);
            // Edited and published again: draft and publication agree, but they are no longer the seed.
            update(db, "UPDATE documentation_pages SET draft = jsonb_set(draft, '{summary}', '\"Our own summary\"'),"
                + " published = jsonb_set(published, '{summary}', '\"Our own summary\"') WHERE id = 'api-keys'");
            // Edited and only saved as a draft.
            update(db, "UPDATE documentation_pages SET draft = jsonb_set(draft, '{summary}', '\"A draft\"') WHERE id = 'models-and-pricing'");
            // Taken offline.
            update(db, "UPDATE documentation_pages SET published = NULL WHERE id = 'errors'");
            // Deleted.
            update(db, "UPDATE documentation_pages SET deleted = TRUE WHERE id = 'claude-code'");
        }
        Map<String, Page> edited;
        try (Connection db = owner(url)) {
            edited = pages(db);
        }

        migrate(url, null);

        try (Connection db = owner(url)) {
            Map<String, Page> after = pages(db);
            for (String id : List.of("api-keys", "models-and-pricing", "errors", "claude-code")) {
                assertThat(after.get(id)).as(id + " is not replaced").isEqualTo(edited.get(id));
            }
            assertThat(after.get("api-keys").summary()).isEqualTo("Our own summary");
            assertThat(after.get("models-and-pricing").summary()).isEqualTo("A draft");
            for (String id : List.of("codex-cli", "opencode")) {
                assertThat(after.get(id).version()).as(id).isEqualTo(before.get(id).version() + 1);
                assertThat(after.get(id).zhBody()).as(id).contains("号池");
            }
        }
    }

    // ---- fixtures ----

    /** What the test compares of a page: the whole stored draft and publication as text, and the parts it reads. */
    private record Page(String id, long version, boolean deleted, String draft, String published, String summary,
                        String zhBody, String enBody) {}

    private static Map<String, Page> pages(Connection db) throws SQLException {
        Map<String, Page> pages = new LinkedHashMap<>();
        try (PreparedStatement statement = db.prepareStatement(
            "SELECT id, version, deleted, draft::text AS draft, published::text AS published, draft->>'summary' AS summary,"
                + " draft->>'body' AS zh, draft->'english'->>'body' AS en FROM documentation_pages ORDER BY id");
            ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                pages.put(result.getString("id"), new Page(result.getString("id"), result.getLong("version"),
                    result.getBoolean("deleted"), result.getString("draft"), result.getString("published"),
                    result.getString("summary"), result.getString("zh"), result.getString("en")));
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
