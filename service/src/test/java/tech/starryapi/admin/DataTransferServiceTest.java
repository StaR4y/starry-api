package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.starryapi.common.ApiException;

class DataTransferServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsCustomDumpCommandWithoutPuttingPasswordOnCommandLine() {
        String password = "secret;$(not-a-shell-command)";
        DataTransferService service = service(
            "jdbc:postgresql://postgres.internal:5544/starry_api?sslmode=require",
            "backup-user",
            password
        );
        Path dump = tempDir.resolve("database.dump");

        List<String> command = service.pgDumpCommand(dump);

        assertThat(command).containsExactly(
            "pg_dump",
            "--host", "postgres.internal",
            "--port", "5544",
            "--username", "backup-user",
            "--dbname", "starry_api",
            "--no-password",
            "--role", "starry_api_owner",
            "--schema", "starry_api",
            "--format", "custom",
            "--no-owner",
            "--file", dump.toAbsolutePath().toString()
        );
        assertThat(command).doesNotContain(password, "sh", "bash", "zsh");
        assertThat(service.databaseToolEnvironment())
            .containsEntry("PGPASSWORD", password)
            .containsEntry("PGCONNECT_TIMEOUT", "10")
            .containsEntry("PGSSLMODE", "require");
    }

    @Test
    void buildsTransactionalFailFastRestoreCommand() {
        DataTransferService service = service(
            "jdbc:postgresql://localhost/starry_api",
            "restore-user",
            "password"
        );
        Path dump = tempDir.resolve("restore.dump");

        assertThat(service.pgRestoreCommand(dump)).containsExactly(
            "pg_restore",
            "--host", "localhost",
            "--port", "5432",
            "--username", "restore-user",
            "--dbname", "starry_api",
            "--no-password",
            "--role", "starry_api_owner",
            "--schema", "starry_api",
            "--clean",
            "--if-exists",
            "--no-owner",
            "--single-transaction",
            "--exit-on-error",
            dump.toAbsolutePath().toString()
        );
        assertThat(service.pgRestoreValidationCommand(dump)).containsExactly(
            "pg_restore",
            "--list",
            dump.toAbsolutePath().toString()
        );
        assertThat(DataTransferService.maintenanceLockSql(true))
            .isEqualTo("SELECT pg_catalog.pg_try_advisory_lock_shared(?)");
        assertThat(DataTransferService.maintenanceLockSql(false))
            .isEqualTo("SELECT pg_catalog.pg_try_advisory_lock(?)");
        assertThat(DataTransferService.maintenanceUnlockSql(true))
            .isEqualTo("SELECT pg_catalog.pg_advisory_unlock_shared(?)");
        assertThat(DataTransferService.maintenanceUnlockSql(false))
            .isEqualTo("SELECT pg_catalog.pg_advisory_unlock(?)");
    }

    @Test
    void acceptsOnlyCurrentStarryApiArchiveContract() {
        DataTransferService.validateRestoreToc(validToc("17.6", "17.6", ""));
        assertThatThrownBy(() -> DataTransferService.validateRestoreToc(validToc("18.1", "18.1", "")))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getMessage()).contains("major version 18 is not supported")
            );
    }

    @Test
    void rejectsArchiveObjectsFromAnotherSchema() {
        assertThatThrownBy(() -> DataTransferService.validateRestoreToc(validToc(
            "17.6",
            "17.6",
            "99; 1259 999 TABLE public injected_table starry_api_owner\n"
        )))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getCode()).isEqualTo("DATA_IMPORT_INCOMPATIBLE_ARCHIVE");
                assertThat(exception.getMessage()).contains("outside schema starry_api");
            });
    }

    @Test
    void rejectsArchiveMissingFlywayHistoryOrUsingUnsupportedPostgres() {
        String withoutFlyway = validToc("17.6", "17.6", "")
            .replace("20; 1259 20 TABLE starry_api flyway_schema_history starry_api_owner\n", "");

        assertThatThrownBy(() -> DataTransferService.validateRestoreToc(withoutFlyway))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getCode()).isEqualTo("DATA_IMPORT_INCOMPATIBLE_ARCHIVE");
                assertThat(exception.getMessage()).contains("flyway_schema_history");
            });
        String withoutSchema = validToc("17.6", "17.6", "")
            .replace("3; 2615 3 SCHEMA - starry_api starry_api_owner\n", "");
        assertThatThrownBy(() -> DataTransferService.validateRestoreToc(withoutSchema))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getMessage()).contains("required schema object")
            );
        assertThatThrownBy(() -> DataTransferService.validateRestoreToc(validToc("16.9", "16.9", "")))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getMessage()).contains("major version 16 is not supported")
            );
    }

    @Test
    void freshClusterOwnerHasOnlyTheApplicationRoleMembershipNeededToTerminateRuntimeBackends() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/postgresql/init-test-roles.sql")) {
            assertThat(input).isNotNull();
            String roles = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(roles).contains(
                "GRANT starry_api TO starry_api_owner WITH INHERIT TRUE, SET FALSE, ADMIN FALSE;"
            );
            assertThat(roles).doesNotContain("GRANT pg_signal_backend TO starry_api_owner;");
        }
    }

    @Test
    void postRestoreValidationFailureRollsBackAndSuppressesRollbackFailure() {
        IllegalStateException verificationFailure = new IllegalStateException("restored schema is invalid");
        IllegalStateException rollbackFailure = new IllegalStateException("rollback restore failed");
        AtomicBoolean rollbackAttempted = new AtomicBoolean();

        assertThatThrownBy(() -> DataTransferService.restoreWithRollback(
            () -> {},
            () -> { throw verificationFailure; },
            () -> {
                rollbackAttempted.set(true);
                throw rollbackFailure;
            },
            () -> {}
        ))
            .isSameAs(verificationFailure)
            .satisfies(error -> assertThat(error.getSuppressed()).containsExactly(rollbackFailure));
        assertThat(rollbackAttempted).isTrue();
    }

    @Test
    void restoreCommandFailureDoesNotOverwriteTheExistingDatabaseWithRollback() {
        IllegalStateException restoreFailure = new IllegalStateException("incoming restore failed");
        AtomicBoolean rollbackAttempted = new AtomicBoolean();

        assertThatThrownBy(() -> DataTransferService.restoreWithRollback(
            () -> { throw restoreFailure; },
            () -> {},
            () -> rollbackAttempted.set(true),
            () -> {}
        )).isSameAs(restoreFailure);
        assertThat(rollbackAttempted).isFalse();
    }

    @Test
    void rejectsNonPostgresqlAndMalformedConnectionSettings() {
        assertThatThrownBy(() -> service("jdbc:h2:mem:starry_api", "user", "password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only a PostgreSQL JDBC URL");
        assertThatThrownBy(() -> service("jdbc:postgresql://localhost/starry_api/extra", "user", "password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one database name");
        assertThatThrownBy(() -> service("jdbc:postgresql://localhost/other", "user", "password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires PostgreSQL database starry_api");
        assertThatThrownBy(() -> service("jdbc:postgresql://localhost/starry_api?sslmode=unsafe", "user", "password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported PostgreSQL sslmode");
        assertThatThrownBy(() -> service("jdbc:postgresql://localhost/starry_api?user=other", "user", "password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("credentials must be configured separately");
        assertThatThrownBy(() -> service("jdbc:postgresql://localhost/starry_api", "user\nnext", "password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("control characters");
        assertThatThrownBy(() -> service("jdbc:postgresql://localhost/starry_api", "user", "bad\0password"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null character");
    }

    @Test
    void rejectsPathTraversalAndControlCharactersInFileNames() {
        DataTransferService service = service(
            "jdbc:postgresql://localhost/starry_api",
            "user",
            "password"
        );

        assertThat(service.sanitizeUploadName("database.dump")).isEqualTo("database.dump");
        assertThat(service.sanitizeBackupName("starry-api-backup.zip")).isEqualTo("starry-api-backup.zip");
        assertThatThrownBy(() -> service.sanitizeUploadName("../database.dump"))
            .isInstanceOf(ApiException.class)
            .hasMessage("Invalid file name");
        assertThatThrownBy(() -> service.sanitizeUploadName("folder\\database.dump"))
            .isInstanceOf(ApiException.class)
            .hasMessage("Invalid file name");
        assertThatThrownBy(() -> service.sanitizeUploadName("database.dump\n"))
            .isInstanceOf(ApiException.class)
            .hasMessage("Invalid file name");
        assertThatThrownBy(() -> service.sanitizeBackupName("database.dump"))
            .isInstanceOf(ApiException.class)
            .hasMessage("Invalid backup file name");
    }

    private DataTransferService service(String jdbcUrl, String username, String password) {
        return new DataTransferService(
            new ObjectMapper(),
            jdbcUrl,
            username,
            password,
            tempDir.toString(),
            1024 * 1024,
            Duration.ofMinutes(1),
            mock(RuntimeStateInvalidator.class)
        );
    }

    private String validToc(String databaseVersion, String dumpVersion, String extraEntry) {
        return """
            ; PostgreSQL database dump
            ;
            ; Archive created at 2026-07-29 12:00:00 CST
            ;     dbname: starry_api
            ;     TOC Entries: 20
            ;     Compression: gzip
            ;     Dump Version: 1.16-0
            ;     Format: CUSTOM
            ;     Integer: 4 bytes
            ;     Offset: 8 bytes
            ;     Dumped from database version: %s
            ;     Dumped by pg_dump version: %s
            ;
            1; 0 0 ENCODING - ENCODING
            2; 0 0 STDSTRINGS - STDSTRINGS
            3; 2615 3 SCHEMA - starry_api starry_api_owner
            4; 0 0 ACL - SCHEMA starry_api starry_api_owner
            20; 1259 20 TABLE starry_api flyway_schema_history starry_api_owner
            21; 1259 21 TABLE starry_api admin_users starry_api_owner
            22; 1259 22 TABLE starry_api system_settings starry_api_owner
            23; 1259 23 TABLE starry_api proxy_accounts starry_api_owner
            24; 1259 24 TABLE starry_api api_keys starry_api_owner
            25; 1259 25 TABLE starry_api request_logs starry_api_owner
            26; 1259 26 TABLE starry_api payment_orders starry_api_owner
            27; 1259 27 TABLE starry_api balance_ledger starry_api_owner
            28; 0 0 TABLE DATA starry_api admin_users starry_api_owner
            %s""".formatted(databaseVersion, dumpVersion, extraEntry);
    }
}
