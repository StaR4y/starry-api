package tech.starryapi.cli;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import tech.starryapi.admin.PasswordHasher;

/**
 * Server-side administration commands, run next to the service by the {@code starry-api} host command:
 *
 * <pre>
 * java -cp /app/app.jar -Dloader.main=tech.starryapi.cli.AdminCli \
 *     org.springframework.boot.loader.launch.PropertiesLauncher status|reset-root-password|check-root-password
 * </pre>
 *
 * <p>This is deliberately not a Spring application. A second application instance next to the running service would
 * compete for the data-management state lock and start every scheduler again. The CLI talks to PostgreSQL with plain
 * JDBC (the driver is already in the jar) and uses the same {@link PasswordHasher} as the service.
 *
 * <p>Output is {@code key=value} lines on stdout so a shell script can read it. Errors are {@code error=<code>} plus a
 * sentence on stderr and exit status 1; a malformed command line exits with 2. Passwords can never be read back from the
 * database (salted PBKDF2), so the only secret this class ever prints is the new password of
 * {@code reset-root-password}. Nothing here prints, resets or recovers the data-management security key; the state file
 * is read for its status fields only (see {@link DataManagementStateReader}).
 */
public final class AdminCli {

    static final String SCHEMA = "starry_api";
    static final String ROOT_SETTING_KEY = "application_root_user_id";
    static final String MIHOMO_SETTING_KEY = "egress_mihomo_enabled";
    static final String DEFAULT_STATE_FILE = "/.starry-api/security/data-management.json";
    static final int MAX_CANDIDATE_PASSWORD_CHARS = 256;

    private static final String USAGE = """
        Usage: AdminCli <command>

        Commands:
          status               print the Root account, data-management and Mihomo status as key=value lines
          reset-root-password  set a new random temporary password for Root and print it as password=<value>
          check-root-password  read one candidate password from stdin; print password_matches=true|false

        Connection settings come from the service environment: POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DATABASE,
        POSTGRES_USERNAME, POSTGRES_PASSWORD, POSTGRES_SSL_MODE and STARRY_DATA_MANAGEMENT_SECURITY_FILE.
        """;

    private AdminCli() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), false, StandardCharsets.UTF_8);
        int status = run(args, System.getenv(), System.in, out, err);
        out.flush();
        err.flush();
        System.exit(status);
    }

    /** Runs one command; the return value is the process exit status. */
    public static int run(
        String[] args,
        Map<String, String> environment,
        InputStream in,
        PrintStream out,
        PrintStream err
    ) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help"))) {
            out.print(USAGE);
            return 0;
        }
        if (args.length != 1) {
            err.println("error=usage");
            err.print(USAGE);
            return 2;
        }
        try {
            Settings settings = Settings.fromEnvironment(environment);
            switch (args[0]) {
                case "status" -> status(settings, out);
                case "reset-root-password" -> resetRootPassword(settings, out);
                case "check-root-password" -> checkRootPassword(settings, in, out);
                default -> {
                    err.println("error=usage");
                    err.print(USAGE);
                    return 2;
                }
            }
            return 0;
        } catch (CliException exception) {
            err.println("error=" + exception.code());
            err.println(oneLine(exception.getMessage()));
            return 1;
        }
    }

    // --- status ---------------------------------------------------------------------------------------------------

    private static void status(Settings settings, PrintStream out) {
        Map<String, String> lines = new LinkedHashMap<>();
        try (Connection connection = connect(settings)) {
            lines.put("database", "ok");
            RootAccount root = findRoot(connection, false);
            lines.put("root_assigned", Boolean.toString(root.assigned()));
            if (root.assigned()) {
                lines.put("root_user_id", root.userId());
                if (root.present()) {
                    lines.put("root_username", root.username());
                    lines.put("root_email", root.email());
                    lines.put("root_enabled", Boolean.toString(root.enabled()));
                    lines.put("root_first_login", Boolean.toString(root.firstLogin()));
                } else {
                    lines.put("root_error", "user_missing");
                }
            }
            lines.put("egress_mihomo_enabled", mihomoSwitch(connection));
        } catch (SQLException | CliException exception) {
            lines.put("database", "error");
            lines.put("database_error", oneLine(exception.getMessage()));
        }
        addDataManagement(lines, settings);
        lines.forEach((key, value) -> out.println(key + "=" + oneLine(value)));
    }

    private static void addDataManagement(Map<String, String> lines, Settings settings) {
        DataManagementStateReader.Status state = DataManagementStateReader.read(
            settings.stateFile(),
            settings.requireInitialized()
        );
        lines.put("data_management", state.state().name().toLowerCase(Locale.ROOT));
        if (state.problem() != null) {
            lines.put("data_management_error", state.problem().name().toLowerCase(Locale.ROOT));
        }
        if (state.initializedAt() != null) {
            lines.put("data_management_initialized_at", state.initializedAt().toString());
        }
        if (state.failedAttempts() != null) {
            lines.put("data_management_failed_attempts", Integer.toString(state.failedAttempts()));
        }
        if (state.lockedAt() != null) {
            lines.put("data_management_locked_at", state.lockedAt().toString());
        }
    }

    /** {@code true}, {@code false}, or {@code unset} while no administrator has used the console switch. */
    private static String mihomoSwitch(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT setting_value FROM system_settings WHERE setting_key = ?")) {
            statement.setString(1, MIHOMO_SETTING_KEY);
            try (ResultSet row = statement.executeQuery()) {
                String value = row.next() && row.getString(1) != null ? row.getString(1).trim() : "";
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    return value.toLowerCase(Locale.ROOT);
                }
                return "unset";
            }
        }
    }

    // --- reset-root-password --------------------------------------------------------------------------------------

    /**
     * Gives Root a new random password and marks it as temporary ({@code first_login}), exactly as the console's
     * "reset password" does in {@code AdminUserService.resetPasswordAuthorized}. Console sessions live in the memory of
     * the service and cannot be revoked from here; the host command restarts the service afterwards.
     */
    private static void resetRootPassword(Settings settings, PrintStream out) {
        PasswordHasher hasher = new PasswordHasher();
        String password = hasher.randomPassword();
        String salt = hasher.newSalt();
        String hash = hasher.hash(password, salt);
        try (Connection connection = connect(settings)) {
            connection.setAutoCommit(false);
            try {
                RootAccount root = requireRoot(findRoot(connection, false));
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE admin_users
                    SET password_hash = ?,
                        password_salt = ?,
                        first_login = TRUE,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = ?
                    """)) {
                    update.setString(1, hash);
                    update.setString(2, salt);
                    update.setString(3, root.userId());
                    if (update.executeUpdate() != 1) {
                        throw new CliException("root_missing", "The Root account could not be updated.");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        out.println("password=" + password);
    }

    // --- check-root-password --------------------------------------------------------------------------------------

    /**
     * Tells whether the candidate on stdin is Root's current password. The host command uses it so that it never shows
     * an "initial password" from the service log that is no longer the real one (for example after a reset). The
     * candidate is read from stdin rather than the command line to keep it out of process listings, and is not echoed.
     */
    private static void checkRootPassword(Settings settings, InputStream in, PrintStream out) {
        String candidate = readCandidate(in);
        boolean matches = false;
        try (Connection connection = connect(settings)) {
            RootAccount root = requireRoot(findRoot(connection, true));
            matches = !candidate.isEmpty() && new PasswordHasher().matches(candidate, root.passwordSalt(), root.passwordHash());
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
        out.println("password_matches=" + matches);
    }

    private static String readCandidate(InputStream in) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder candidate = new StringBuilder();
            int next;
            while ((next = reader.read()) != -1 && next != '\n') {
                if (next != '\r') {
                    candidate.append((char) next);
                }
                if (candidate.length() > MAX_CANDIDATE_PASSWORD_CHARS) {
                    return "";
                }
            }
            return candidate.toString();
        } catch (IOException exception) {
            return "";
        }
    }

    // --- database -------------------------------------------------------------------------------------------------

    private record RootAccount(
        String userId,
        boolean present,
        String username,
        String email,
        boolean enabled,
        boolean firstLogin,
        String passwordHash,
        String passwordSalt
    ) {

        static RootAccount unassigned() {
            return new RootAccount("", false, "", "", false, false, "", "");
        }

        boolean assigned() {
            return !userId.isEmpty();
        }

        /** Never the credentials: this record must be safe to print by accident. */
        @Override
        public String toString() {
            return "RootAccount[userId=" + userId + ", username=" + username + "]";
        }
    }

    /**
     * The Root marker and the account it points to; the marker row is created by Flyway V6. The stored password hash and
     * salt are read only when {@code withCredentials} is set (to check a candidate password), never for a status report.
     */
    private static RootAccount findRoot(Connection connection, boolean withCredentials) throws SQLException {
        String userId;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT setting_value FROM system_settings WHERE setting_key = ?")) {
            statement.setString(1, ROOT_SETTING_KEY);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CliException(
                        "schema_missing",
                        "The Root marker " + ROOT_SETTING_KEY + " does not exist; the database has not been migrated "
                            + "by the service yet."
                    );
                }
                userId = row.getString(1) == null ? "" : row.getString(1).trim();
            }
        }
        if (userId.isEmpty()) {
            return RootAccount.unassigned();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT username, COALESCE(email, '') AS email, enabled, first_login,
                   CASE WHEN ? THEN password_hash ELSE '' END AS password_hash,
                   CASE WHEN ? THEN password_salt ELSE '' END AS password_salt
            FROM admin_users
            WHERE user_id = ?
            """)) {
            statement.setBoolean(1, withCredentials);
            statement.setBoolean(2, withCredentials);
            statement.setString(3, userId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return new RootAccount(userId, false, "", "", false, false, "", "");
                }
                return new RootAccount(
                    userId,
                    true,
                    row.getString("username"),
                    row.getString("email"),
                    row.getBoolean("enabled"),
                    row.getBoolean("first_login"),
                    row.getString("password_hash"),
                    row.getString("password_salt")
                );
            }
        }
    }

    private static RootAccount requireRoot(RootAccount root) {
        if (!root.assigned()) {
            throw new CliException(
                "no_root",
                "No Root account is assigned yet. Start the service once so that it can create the Root account."
            );
        }
        if (!root.present()) {
            throw new CliException("root_missing", "The Root marker points to an account that does not exist.");
        }
        return root;
    }

    private static CliException databaseFailure(SQLException exception) {
        return new CliException("database_unavailable", "Could not use the database: " + oneLine(exception.getMessage()));
    }

    /**
     * Opens a connection with the PostgreSQL driver directly. {@code DriverManager} would not find a driver that sits in
     * {@code BOOT-INF/lib}, because it looks through the system class loader only.
     */
    static Connection connect(Settings settings) {
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        // Bounded waits: an unreachable database or a held lock must end the command, not hang the terminal.
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "60");
        properties.setProperty("options", "-c lock_timeout=5000 -c statement_timeout=30000");
        try {
            Driver driver = (Driver) Class.forName("org.postgresql.Driver", true, AdminCli.class.getClassLoader())
                .getDeclaredConstructor()
                .newInstance();
            Connection connection = driver.connect(settings.jdbcUrl(), properties);
            if (connection == null) {
                throw new CliException("database_unavailable", "The PostgreSQL driver rejected the connection URL.");
            }
            return connection;
        } catch (ReflectiveOperationException exception) {
            throw new CliException("database_unavailable", "The PostgreSQL JDBC driver is not available.");
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    // --- settings -------------------------------------------------------------------------------------------------

    /** Where to connect, from the same environment variables the service reads. */
    record Settings(String jdbcUrl, String username, String password, Path stateFile, boolean requireInitialized) {

        static Settings fromEnvironment(Map<String, String> environment) {
            String host = value(environment, "POSTGRES_HOST", "localhost");
            String port = value(environment, "POSTGRES_PORT", "5432");
            String database = value(environment, "POSTGRES_DATABASE", SCHEMA);
            String sslMode = value(environment, "POSTGRES_SSL_MODE", "disable");
            if (!port.matches("[0-9]{1,5}")) {
                throw new CliException("configuration", "POSTGRES_PORT must be a port number.");
            }
            String url = "jdbc:postgresql://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + "/"
                + URLEncoder.encode(database, StandardCharsets.UTF_8)
                + "?currentSchema=" + SCHEMA
                + "&sslmode=" + URLEncoder.encode(sslMode, StandardCharsets.UTF_8)
                + "&ApplicationName=starry-api-admin-cli";
            String stateFile = value(
                environment,
                "STARRY_DATA_MANAGEMENT_SECURITY_FILE",
                System.getProperty("user.home") + DEFAULT_STATE_FILE
            );
            return new Settings(
                url,
                value(environment, "POSTGRES_USERNAME", "starry_api"),
                environment.getOrDefault("POSTGRES_PASSWORD", ""),
                Path.of(stateFile),
                Boolean.parseBoolean(value(environment, "STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED", "false"))
            );
        }

        private static String value(Map<String, String> environment, String name, String fallback) {
            String value = environment.get(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    /** A failure the operator can act on; {@code code} is stable so scripts can translate it. */
    static final class CliException extends RuntimeException {

        private final String code;

        CliException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** Keeps every value on one line so a {@code key=value} line cannot be split or forged by a stray newline. */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint ->
            cleaned.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint));
        return cleaned.toString().strip();
    }
}
