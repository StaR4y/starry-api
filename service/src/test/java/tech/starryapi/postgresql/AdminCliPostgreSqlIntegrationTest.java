package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tech.starryapi.admin.PasswordHasher;
import tech.starryapi.cli.AdminCli;

/**
 * The server-side administration command against a real, migrated PostgreSQL, connecting as the runtime role the
 * service itself uses (so the grants and the Root identity triggers of V6 are exercised as in production).
 */
class AdminCliPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String ROOT_ID = "root-user-id";
    private static final String INITIAL_PASSWORD = "0123456789abcdef0123456789abcdef0123";

    private final PasswordHasher hasher = new PasswordHasher();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl() + "&currentSchema=starry_api", MIGRATION_USERNAME, MIGRATION_PASSWORD)
            .defaultSchema("starry_api")
            .schemas("starry_api")
            .createSchemas(false)
            .initSql("SET ROLE starry_api_owner")
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    /** An empty database as the service leaves it before the first start: marker present but unassigned. */
    @BeforeEach
    void emptyDatabase() {
        executeAsDatabaseOwner(
            "ALTER TABLE starry_api.system_settings DISABLE TRIGGER trg_application_root_setting",
            "ALTER TABLE starry_api.admin_users DISABLE TRIGGER trg_application_root_user",
            "UPDATE starry_api.system_settings SET setting_value = '' WHERE setting_key = 'application_root_user_id'",
            "DELETE FROM starry_api.admin_users",
            "DELETE FROM starry_api.system_settings WHERE setting_key = 'egress_mihomo_enabled'",
            "ALTER TABLE starry_api.system_settings ENABLE TRIGGER trg_application_root_setting",
            "ALTER TABLE starry_api.admin_users ENABLE TRIGGER trg_application_root_user"
        );
    }

    // --- status ---------------------------------------------------------------------------------------------------

    @Test
    void statusBeforeAnyRootExistsSaysSo() {
        Result result = run(env(), "status");

        assertThat(result.exit()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.values())
            .containsEntry("database", "ok")
            .containsEntry("root_assigned", "false")
            .containsEntry("egress_mihomo_enabled", "unset")
            .containsEntry("data_management", "not_initialized")
            .doesNotContainKeys("root_user_id", "root_username", "root_first_login", "data_management_error");
    }

    @Test
    void statusReportsTheRootAndWhetherItsInitialPasswordIsUnchanged() {
        createRoot(true);

        Result unchanged = run(env(), "status");

        assertThat(unchanged.exit()).isZero();
        assertThat(unchanged.values())
            .containsEntry("database", "ok")
            .containsEntry("root_assigned", "true")
            .containsEntry("root_user_id", ROOT_ID)
            .containsEntry("root_username", "admin")
            .containsEntry("root_email", "root@example.com")
            .containsEntry("root_enabled", "true")
            .containsEntry("root_first_login", "true");

        executeAsDatabaseOwner("UPDATE starry_api.admin_users SET first_login = FALSE WHERE user_id = '" + ROOT_ID + "'");
        Result changed = run(env(), "status");
        assertThat(changed.values()).containsEntry("root_first_login", "false");
    }

    @Test
    void statusNeverContainsAPasswordHashOrSalt() {
        createRoot(true);
        String[] stored = storedCredentials();

        Result result = run(env(), "status");

        assertThat(result.stdout()).doesNotContain(stored[0]).doesNotContain(stored[1]).doesNotContain(INITIAL_PASSWORD);
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void statusReportsTheMihomoConsoleSwitchOnlyWhenItWasUsed() {
        createRoot(false);
        assertThat(run(env(), "status").values()).containsEntry("egress_mihomo_enabled", "unset");

        setSetting("egress_mihomo_enabled", "true");
        assertThat(run(env(), "status").values()).containsEntry("egress_mihomo_enabled", "true");

        setSetting("egress_mihomo_enabled", "false");
        assertThat(run(env(), "status").values()).containsEntry("egress_mihomo_enabled", "false");

        setSetting("egress_mihomo_enabled", "maybe");
        assertThat(run(env(), "status").values()).containsEntry("egress_mihomo_enabled", "unset");
    }

    @Test
    void statusStillReportsTheDataManagementStateWhenTheDatabaseCannotBeReached() throws Exception {
        Path stateFile = tempDir.resolve("security/data-management.json");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile.resolveSibling("data-management.json.initialized"), "starry-data-management-initialized-v1\n");
        Map<String, String> environment = env(stateFile);
        environment.put("POSTGRES_PORT", "1");

        Result result = run(environment, "status");

        assertThat(result.exit()).isZero();
        assertThat(result.values())
            .containsEntry("database", "error")
            .containsEntry("data_management", "error")
            .containsEntry("data_management_error", "state_missing");
        assertThat(result.values().get("database_error")).isNotBlank().doesNotContain("\n");
        assertThat(result.values()).doesNotContainKeys("root_assigned", "root_user_id");
    }

    @Test
    void aWrongDatabasePasswordIsReportedWithoutLeakingIt() {
        Map<String, String> environment = env();
        environment.put("POSTGRES_PASSWORD", "definitely-not-the-password");

        Result result = run(environment, "status");

        assertThat(result.exit()).isZero();
        assertThat(result.values()).containsEntry("database", "error");
        assertThat(result.stdout()).doesNotContain("definitely-not-the-password");
    }

    @Test
    void statusReadsTheStateFileThatTheEnvironmentPointsTo() throws Exception {
        createRoot(false);
        Path stateFile = tempDir.resolve("security/data-management.json");
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, stateJson(0, false, null));
        Files.writeString(stateFile.resolveSibling("data-management.json.initialized"), "starry-data-management-initialized-v1\n");

        Result initialized = run(env(stateFile), "status");
        assertThat(initialized.values())
            .containsEntry("data_management", "initialized")
            .containsEntry("data_management_initialized_at", "2026-09-01T08:00:00Z")
            .containsEntry("data_management_failed_attempts", "0")
            .doesNotContainKey("data_management_locked_at");

        Files.writeString(stateFile, stateJson(5, true, "2026-09-03T10:30:00Z"));
        Result locked = run(env(stateFile), "status");
        assertThat(locked.values())
            .containsEntry("data_management", "locked")
            .containsEntry("data_management_failed_attempts", "5")
            .containsEntry("data_management_locked_at", "2026-09-03T10:30:00Z");

        assertThat(locked.stdout()).doesNotContain("verifier").doesNotContain("salt");

        Files.delete(stateFile);
        Result missing = run(env(stateFile), "status");
        assertThat(missing.values())
            .containsEntry("data_management", "error")
            .containsEntry("data_management_error", "state_missing");
    }

    // --- reset-root-password --------------------------------------------------------------------------------------

    @Test
    void resetSetsANewTemporaryPasswordThatMatchesWhatWasPrinted() {
        createRoot(false);
        String[] before = storedCredentials();
        Timestamp updatedBefore = updatedAt();

        Result result = run(env(), "reset-root-password");

        assertThat(result.exit()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).matches("password=[0-9a-f]{36}\\R");
        String printed = result.stdout().strip().substring("password=".length());

        String[] after = storedCredentials();
        assertThat(hasher.matches(printed, after[1], after[0])).isTrue();
        assertThat(hasher.matches(INITIAL_PASSWORD, after[1], after[0])).isFalse();
        assertThat(after[1]).isNotEqualTo(before[1]);
        assertThat(after[0]).isNotEqualTo(before[0]).hasSize(64);
        assertThat(after[1]).hasSize(32);
        assertThat(firstLogin()).as("a reset password is temporary again").isTrue();
        assertThat(updatedAt()).isAfter(updatedBefore);
        assertThat(run(env(), "status").values())
            .containsEntry("root_first_login", "true")
            .containsEntry("root_user_id", ROOT_ID)
            .containsEntry("root_username", "admin");
    }

    @Test
    void everyResetPrintsADifferentPasswordAndInvalidatesTheLastOne() {
        createRoot(false);

        String first = run(env(), "reset-root-password").stdout().strip().substring("password=".length());
        String second = run(env(), "reset-root-password").stdout().strip().substring("password=".length());

        assertThat(second).isNotEqualTo(first);
        String[] stored = storedCredentials();
        assertThat(hasher.matches(second, stored[1], stored[0])).isTrue();
        assertThat(hasher.matches(first, stored[1], stored[0])).isFalse();
    }

    @Test
    void resetLeavesEveryOtherAccountAlone() {
        createRoot(false);
        String salt = hasher.newSalt();
        executeAsDatabaseOwner(
            "INSERT INTO starry_api.admin_users (user_id, username, email, role, password_hash, password_salt, first_login) VALUES "
                + "('operator-id', 'operator', 'operator@example.com', 'ADMIN', '" + hasher.hash("operator-password-1", salt)
                + "', '" + salt + "', FALSE)"
        );
        String operatorHash = hashOf("operator-id");

        assertThat(run(env(), "reset-root-password").exit()).isZero();

        assertThat(hashOf("operator-id")).isEqualTo(operatorHash);
        assertThat(firstLogin("operator-id")).isFalse();
    }

    @Test
    void resetRefusesWhenNoRootIsAssigned() {
        Result result = run(env(), "reset-root-password");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).startsWith("error=no_root").contains("Root");
        assertThat(count("starry_api.admin_users")).isZero();
    }

    @Test
    void resetRefusesWhenTheRootAccountsMarkerExistsButNoAccountDoes() {
        createRoot(false);
        executeAsDatabaseOwner(
            "ALTER TABLE starry_api.admin_users DISABLE TRIGGER trg_application_root_user",
            "DELETE FROM starry_api.admin_users WHERE user_id = '" + ROOT_ID + "'",
            "ALTER TABLE starry_api.admin_users ENABLE TRIGGER trg_application_root_user"
        );

        Result result = run(env(), "reset-root-password");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).startsWith("error=root_missing");
    }

    @Test
    void resetFailsCleanlyWhenTheDatabaseIsUnreachable() {
        Map<String, String> environment = env();
        environment.put("POSTGRES_PORT", "1");

        Result result = run(environment, "reset-root-password");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).startsWith("error=database_unavailable");
    }

    // --- check-root-password --------------------------------------------------------------------------------------

    @Test
    void checkTellsWhetherTheCandidateIsTheCurrentRootPassword() {
        createRoot(true);

        assertThat(check(INITIAL_PASSWORD + "\n").stdout()).isEqualTo("password_matches=true\n");
        assertThat(check(INITIAL_PASSWORD).stdout()).as("no trailing newline").isEqualTo("password_matches=true\n");
        assertThat(check(INITIAL_PASSWORD + "\r\n").stdout()).as("CRLF").isEqualTo("password_matches=true\n");
        assertThat(check("not-the-password\n").stdout()).isEqualTo("password_matches=false\n");
        assertThat(check("").stdout()).isEqualTo("password_matches=false\n");
        assertThat(check("x".repeat(5000) + "\n").stdout()).isEqualTo("password_matches=false\n");
    }

    @Test
    void anOldInitialPasswordNoLongerMatchesAfterAReset() {
        createRoot(true);
        assertThat(run(env(), "reset-root-password").exit()).isZero();

        Result result = check(INITIAL_PASSWORD + "\n");

        assertThat(result.stdout()).isEqualTo("password_matches=false\n");
        assertThat(result.stdout()).doesNotContain(INITIAL_PASSWORD);
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void checkWithoutARootIsAnError() {
        Result result = check("anything\n");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("error=no_root");
    }

    // --- command line ---------------------------------------------------------------------------------------------

    @Test
    void unknownCommandsAndMissingArgumentsExitWithUsage() {
        assertThat(run(env(), "nonsense").exit()).isEqualTo(2);
        assertThat(run(env(), "nonsense").stderr()).startsWith("error=usage");
        assertThat(run(env()).exit()).isEqualTo(2);
        assertThat(run(env(), "status", "extra").exit()).isEqualTo(2);
        Result help = run(env(), "--help");
        assertThat(help.exit()).isZero();
        assertThat(help.stdout()).contains("reset-root-password").contains("status");
    }

    @Test
    void anInvalidPortIsAConfigurationError() {
        Map<String, String> environment = env();
        environment.put("POSTGRES_PORT", "not-a-port");

        Result result = run(environment, "status");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("error=configuration");
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private record Result(int exit, String stdout, String stderr) {

        Map<String, String> values() {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : stdout.split("\\R")) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    values.put(line.substring(0, separator), line.substring(separator + 1));
                }
            }
            return values;
        }
    }

    private Result run(Map<String, String> environment, String... args) {
        return run(environment, InputStream.nullInputStream(), args);
    }

    private Result check(String stdin) {
        return run(env(), new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), "check-root-password");
    }

    private Result run(Map<String, String> environment, InputStream stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = AdminCli.run(
            args,
            environment,
            stdin,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** What the service container gets from Compose, pointing at the test database as the runtime role. */
    private Map<String, String> env() {
        return env(tempDir.resolve("security/data-management.json"));
    }

    private Map<String, String> env(Path stateFile) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("POSTGRES_HOST", POSTGRES.getHost());
        environment.put("POSTGRES_PORT", String.valueOf(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
        environment.put("POSTGRES_DATABASE", POSTGRES.getDatabaseName());
        environment.put("POSTGRES_USERNAME", APP_USERNAME);
        environment.put("POSTGRES_PASSWORD", APP_PASSWORD);
        environment.put("POSTGRES_SSL_MODE", "disable");
        environment.put("STARRY_DATA_MANAGEMENT_SECURITY_FILE", stateFile.toString());
        return environment;
    }

    private void createRoot(boolean firstLogin) {
        String salt = hasher.newSalt();
        executeAsDatabaseOwner(
            "INSERT INTO starry_api.admin_users (user_id, username, email, display_name, role, password_hash, password_salt, "
                + "enabled, first_login, created_at, updated_at) VALUES ('" + ROOT_ID + "', 'admin', 'root@example.com', 'Default Administrator', "
                + "'ADMIN', '" + hasher.hash(INITIAL_PASSWORD, salt) + "', '" + salt + "', TRUE, " + firstLogin
                + ", TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')",
            "UPDATE starry_api.system_settings SET setting_value = '" + ROOT_ID
                + "' WHERE setting_key = 'application_root_user_id'"
        );
    }

    private void setSetting(String key, String value) {
        executeAsDatabaseOwner(
            "INSERT INTO starry_api.system_settings (setting_key, setting_value) VALUES ('" + key + "', '" + value
                + "') ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value"
        );
    }

    /** {hash, salt} of the Root account, read as the owner so the test sees exactly what is stored. */
    private String[] storedCredentials() {
        List<String> row = queryOwner(
            "SELECT password_hash, password_salt FROM starry_api.admin_users WHERE user_id = '" + ROOT_ID + "'", 2
        );
        return new String[] {row.get(0), row.get(1)};
    }

    private String hashOf(String userId) {
        return queryOwner("SELECT password_hash FROM starry_api.admin_users WHERE user_id = '" + userId + "'", 1).get(0);
    }

    private boolean firstLogin() {
        return firstLogin(ROOT_ID);
    }

    private boolean firstLogin(String userId) {
        return Boolean.parseBoolean(
            queryOwner("SELECT first_login::text FROM starry_api.admin_users WHERE user_id = '" + userId + "'", 1).get(0)
        );
    }

    private Timestamp updatedAt() {
        return Timestamp.valueOf(
            queryOwner("SELECT updated_at::timestamp FROM starry_api.admin_users WHERE user_id = '" + ROOT_ID + "'", 1).get(0)
        );
    }

    private int count(String table) {
        return Integer.parseInt(queryOwner("SELECT count(*) FROM " + table, 1).get(0));
    }

    private static List<String> queryOwner(String sql, int columns) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATION_USERNAME, MIGRATION_PASSWORD)) {
            connection.setAutoCommit(false);
            try (var role = connection.createStatement()) {
                role.execute("SET LOCAL ROLE starry_api_owner");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as(sql).isTrue();
                java.util.ArrayList<String> values = new java.util.ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    values.add(row.getString(column));
                }
                return values;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The JSON the service writes (pretty-printed, ISO instants), with a well-formed but meaningless salt and verifier. */
    private static String stateJson(int failedAttempts, boolean locked, String lockedAt) {
        String salt = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        String verifier = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        return """
            {
              "version" : 2,
              "algorithm" : "PBKDF2WithHmacSHA256",
              "iterations" : 600000,
              "keyLengthBits" : 256,
              "salt" : "%s",
              "verifier" : "%s",
              "failedAttempts" : %d,
              "locked" : %s,
              "initializedAt" : "2026-09-01T08:00:00Z",
              "lastFailureAt" : null,
              "lockedAt" : %s,
              "updatedAt" : "2026-09-03T10:30:00Z",
              "pendingIncidents" : [ ]
            }
            """.formatted(salt, verifier, failedAttempts, locked, lockedAt == null ? "null" : "\"" + lockedAt + "\"");
    }
}
