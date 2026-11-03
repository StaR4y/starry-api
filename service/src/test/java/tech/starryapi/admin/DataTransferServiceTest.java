package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.starryapi.common.ApiException;
import tools.jackson.databind.ObjectMapper;

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
        Path tocList = tempDir.resolve("restore.toc");
        assertThat(service.pgRestoreCommand(dump, tocList)).containsSubsequence(
            "--use-list",
            tocList.toAbsolutePath().toString()
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
    void filtersInheritedPartitionConstraintsAndIndexAttachmentsFromRestoreToc() {
        String toc = """
            ; PostgreSQL database dump
            1; 2615 1 SCHEMA - starry_api starry_api_owner
            2; 1259 2 TABLE starry_api request_logs starry_api_owner
            3; 1259 3 TABLE starry_api request_logs_y2027m08 starry_api_owner
            4; 0 0 TABLE ATTACH starry_api request_logs_y2027m08 starry_api_owner
            5; 2606 5 CONSTRAINT starry_api request_logs request_logs_pkey starry_api_owner
            6; 2606 6 CONSTRAINT starry_api request_logs_y2027m08 request_logs_y2027m08_pkey starry_api_owner
            7; 0 0 INDEX ATTACH starry_api request_logs_y2027m08_pkey starry_api_owner
            8; 0 0 TABLE DATA starry_api request_logs_y2027m08 starry_api_owner
            """;

        assertThat(DataTransferService.filterInheritedPartitionObjects(toc))
            .contains("TABLE ATTACH starry_api request_logs_y2027m08")
            .contains("TABLE DATA starry_api request_logs_y2027m08")
            .contains("CONSTRAINT starry_api request_logs request_logs_pkey")
            .doesNotContain("CONSTRAINT starry_api request_logs_y2027m08 request_logs_y2027m08_pkey")
            .doesNotContain("INDEX ATTACH starry_api request_logs_y2027m08_pkey");
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
    void rejectsArchiveMissingCoreSchemaObjectsOrUsingUnsupportedPostgres() {
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
    void rejectsArchiveMissingAnySourceTierTable() {
        for (String table : List.of(
            "source_tier_vendors", "source_tiers", "source_tier_members", "source_tier_member_models",
            "source_tier_pair_gates"
        )) {
            String withoutTable = validToc("17.6", "17.6", "").lines()
                .filter(line -> !line.contains("TABLE starry_api " + table + " "))
                .collect(Collectors.joining("\n"));
            assertThat(withoutTable).doesNotContain(" " + table + " ");

            assertThatThrownBy(() -> DataTransferService.validateRestoreToc(withoutTable))
                .as(table)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("DATA_IMPORT_INCOMPATIBLE_ARCHIVE");
                    assertThat(exception.getMessage()).contains("missing required Starry API tables: [" + table + "]");
                });
        }
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
    void requiresExactFlywayV33HistoryAndMatchingChecksumsForRestore() {
        List<DataTransferService.FlywayMigration> current = currentMigrationHistory();

        assertThatCode(() -> DataTransferService.validateMigrationHistory(current)).doesNotThrowAnyException();
        assertThatCode(() -> DataTransferService.verifyRestoreMigrationHistory(current, current))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> DataTransferService.validateMigrationHistory(current.subList(0, 9)))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");

        // A backup taken before the tiered source pools migration cannot be restored from the console.
        List<DataTransferService.FlywayMigration> beforeSourceTiers = current.subList(0, 28);
        assertThat(beforeSourceTiers.get(27).version()).isEqualTo("28");
        assertThatThrownBy(() -> DataTransferService.validateMigrationHistory(beforeSourceTiers))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");
        assertThatThrownBy(() -> DataTransferService.verifyRestoreMigrationHistory(current, beforeSourceTiers))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");

        // Nor can one taken after V29 and before the tutorials about tiers.
        List<DataTransferService.FlywayMigration> beforeTierTutorials = current.subList(0, 29);
        assertThat(beforeTierTutorials.get(28).version()).isEqualTo("29");
        assertThatThrownBy(() -> DataTransferService.validateMigrationHistory(beforeTierTutorials))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");
        assertThatThrownBy(() -> DataTransferService.verifyRestoreMigrationHistory(current, beforeTierTutorials))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");

        // Nor can one taken before the columns that keep what a withheld relay answer cost the operator.
        List<DataTransferService.FlywayMigration> beforeWithheldUsage = current.subList(0, 30);
        assertThat(beforeWithheldUsage.get(29).version()).isEqualTo("30");
        assertThatThrownBy(() -> DataTransferService.validateMigrationHistory(beforeWithheldUsage))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");
        assertThatThrownBy(() -> DataTransferService.verifyRestoreMigrationHistory(current, beforeWithheldUsage))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");

        // Nor can one taken before the maximum output a relay's mapping may state.
        List<DataTransferService.FlywayMigration> beforeMappingMaxOutput = current.subList(0, 31);
        assertThat(beforeMappingMaxOutput.get(30).version()).isEqualTo("31");
        assertThatThrownBy(() -> DataTransferService.validateMigrationHistory(beforeMappingMaxOutput))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");
        assertThatThrownBy(() -> DataTransferService.verifyRestoreMigrationHistory(current, beforeMappingMaxOutput))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");

        // Nor can one taken before V33 trimmed and polished the tutorials.
        List<DataTransferService.FlywayMigration> beforeTutorialPolish = current.subList(0, 32);
        assertThat(beforeTutorialPolish.get(31).version()).isEqualTo("32");
        assertThatThrownBy(() -> DataTransferService.validateMigrationHistory(beforeTutorialPolish))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");
        assertThatThrownBy(() -> DataTransferService.verifyRestoreMigrationHistory(current, beforeTutorialPolish))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");

        List<DataTransferService.FlywayMigration> future = new ArrayList<>(current);
        future.add(new DataTransferService.FlywayMigration(
            "34",
            "V34__unknown_future.sql",
            "SQL",
            8008,
            true
        ));
        assertThatThrownBy(() -> DataTransferService.validateMigrationHistory(future))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("V1-V33");

        List<DataTransferService.FlywayMigration> changedChecksum = new ArrayList<>(current);
        DataTransferService.FlywayMigration v5 = changedChecksum.get(4);
        changedChecksum.set(4, new DataTransferService.FlywayMigration(
            v5.version(),
            v5.script(),
            v5.type(),
            v5.checksum() + 1,
            true
        ));
        assertThatThrownBy(() -> DataTransferService.verifyRestoreMigrationHistory(current, changedChecksum))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("checksums");
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
            43; 1259 43 TABLE starry_api documentation_pages starry_api_owner
            44; 1259 44 TABLE starry_api documentation_attachments starry_api_owner
            45; 1259 45 TABLE starry_api proxy_channels starry_api_owner
            46; 1259 46 TABLE starry_api proxy_subscriptions starry_api_owner
            21; 1259 21 TABLE starry_api admin_users starry_api_owner
            22; 1259 22 TABLE starry_api system_settings starry_api_owner
            23; 1259 23 TABLE starry_api proxy_accounts starry_api_owner
            24; 1259 24 TABLE starry_api api_keys starry_api_owner
            25; 1259 25 TABLE starry_api request_logs starry_api_owner
            26; 1259 26 TABLE starry_api payment_orders starry_api_owner
            27; 1259 27 TABLE starry_api balance_ledger starry_api_owner
            28; 0 0 TABLE DATA starry_api admin_users starry_api_owner
            29; 1259 29 TABLE starry_api coding_plans starry_api_owner
            30; 1259 30 TABLE starry_api coding_plan_versions starry_api_owner
            31; 1259 31 TABLE starry_api coding_plan_subscriptions starry_api_owner
            32; 1259 32 TABLE starry_api subscription_ledger starry_api_owner
            33; 1259 33 TABLE starry_api billing_reservations starry_api_owner
            34; 1259 34 TABLE starry_api billing_reservation_allocations starry_api_owner
            35; 1259 35 TABLE starry_api billing_review_actions starry_api_owner
            36; 1259 36 TABLE starry_api support_tickets starry_api_owner
            37; 1259 37 TABLE starry_api support_ticket_messages starry_api_owner
            38; 1259 38 TABLE starry_api support_ticket_attachments starry_api_owner
            39; 1259 39 TABLE starry_api announcements starry_api_owner
            40; 1259 40 TABLE starry_api announcement_recipients starry_api_owner
            47; 1259 47 TABLE starry_api source_tier_vendors starry_api_owner
            48; 1259 48 TABLE starry_api source_tiers starry_api_owner
            49; 1259 49 TABLE starry_api source_tier_members starry_api_owner
            50; 1259 50 TABLE starry_api source_tier_member_models starry_api_owner
            51; 1259 51 TABLE starry_api source_tier_pair_gates starry_api_owner
            %s""".formatted(databaseVersion, dumpVersion, extraEntry);
    }

    private List<DataTransferService.FlywayMigration> currentMigrationHistory() {
        return List.of(
            new DataTransferService.FlywayMigration("1", "V1__postgresql_baseline.sql", "SQL", 1001, true),
            new DataTransferService.FlywayMigration(
                "2",
                "V2__model_family_account_pool_circuits.sql",
                "SQL",
                2002,
                true
            ),
            new DataTransferService.FlywayMigration(
                "3",
                "V3__data_management_security_audit.sql",
                "SQL",
                3003,
                true
            ),
            new DataTransferService.FlywayMigration(
                "4",
                "V4__data_management_security_outbox.sql",
                "SQL",
                4004,
                true
            ),
            new DataTransferService.FlywayMigration(
                "5",
                "V5__account_pool_circuit_epochs.sql",
                "SQL",
                5005,
                true
            ),
            new DataTransferService.FlywayMigration(
                "6",
                "V6__application_root_identity.sql",
                "SQL",
                6006,
                true
            ),
            new DataTransferService.FlywayMigration(
                "7",
                "V7__api_key_model_provider.sql",
                "SQL",
                7007,
                true
            ),
            new DataTransferService.FlywayMigration(
                "8",
                "V8__account_allocation_admission.sql",
                "SQL",
                8008,
                true
            ),
            new DataTransferService.FlywayMigration(
                "9",
                "V9__account_allocation_consistency_fixes.sql",
                "SQL",
                9009,
                true
            ),
            new DataTransferService.FlywayMigration(
                "10",
                "V10__account_scoped_cooldowns_and_retention.sql",
                "SQL",
                1010,
                true
            ),
            new DataTransferService.FlywayMigration("11", "V11__account_badge_color.sql", "SQL", 1011, true),
            new DataTransferService.FlywayMigration("12", "V12__coding_plan_subscriptions.sql", "SQL", 1012, true),
            new DataTransferService.FlywayMigration("13", "V13__coding_plan_versions.sql", "SQL", 1013, true),
            new DataTransferService.FlywayMigration("14", "V14__explicit_billing_reservations.sql", "SQL", 1014, true),
            new DataTransferService.FlywayMigration("15", "V15__billing_boundary_consistency.sql", "SQL", 1015, true),
            new DataTransferService.FlywayMigration("16", "V16__plugin_developer_mode.sql", "SQL", 1016, true),
            new DataTransferService.FlywayMigration("17", "V17__announcements.sql", "SQL", 1691840228, true),
            new DataTransferService.FlywayMigration("18", "V18__announcement_runtime_grants.sql", "SQL", 1273926045, true),
            new DataTransferService.FlywayMigration("19", "V19__support_tickets.sql", "SQL", 1950034466, true),
            new DataTransferService.FlywayMigration("20", "V20__function_execute_privileges.sql", "SQL", 1880747977, true),
            new DataTransferService.FlywayMigration("21", "V21__documentation.sql", "SQL", 1021, true),
            new DataTransferService.FlywayMigration("22", "V22__documentation_english.sql", "SQL", 1022, true),
            new DataTransferService.FlywayMigration("23", "V23__documentation_tutorials.sql", "SQL", 1023, true),
            new DataTransferService.FlywayMigration("24", "V24__documentation_user_focus.sql", "SQL", 1024, true),
            new DataTransferService.FlywayMigration("25", "V25__account_egress_proxy_channels.sql", "SQL", 1025, true),
            new DataTransferService.FlywayMigration("26", "V26__account_egress_policy.sql", "SQL", 1026, true),
            new DataTransferService.FlywayMigration("27", "V27__egress_listener_ports_and_probe_results.sql", "SQL", 1027, true),
            new DataTransferService.FlywayMigration("28", "V28__account_allocation_defaults.sql", "SQL", 1028, true),
            new DataTransferService.FlywayMigration("29", "V29__source_tiers.sql", "SQL", 1029, true),
            new DataTransferService.FlywayMigration("30", "V30__documentation_source_tiers.sql", "SQL", 1030, true),
            new DataTransferService.FlywayMigration("31", "V31__withheld_relay_usage.sql", "SQL", 1031, true),
            new DataTransferService.FlywayMigration("32", "V32__relay_mapping_max_output.sql", "SQL", 1032, true),
            new DataTransferService.FlywayMigration("33", "V33__documentation_trim_and_polish.sql", "SQL", 1033, true)
        );
    }
}
