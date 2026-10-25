package tech.starryapi.admin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;
import tools.jackson.databind.ObjectMapper;

@Service
public class DataTransferService {

    public static final String IMPORT_CONFIRMATION = "RESTORE_DATABASE";

    private static final String BACKUP_EXTENSION = ".zip";
    private static final String DUMP_EXTENSION = ".dump";
    private static final String DATABASE_DUMP_ENTRY = "database.dump";
    private static final String POSTGRES_DATABASE = "starry_api";
    private static final String POSTGRES_SCHEMA = "starry_api";
    private static final String POSTGRES_SECURITY_SCHEMA = "starry_security";
    private static final String POSTGRES_OWNER_ROLE = "starry_api_owner";
    private static final String POSTGRES_RUNTIME_ROLE = "starry_api_runtime";
    private static final String POSTGRES_RUNTIME_LOGIN = "starry_api";
    private static final String REQUIRED_FLYWAY_VERSION = "29";
    private static final List<FlywayMigration> REQUIRED_FLYWAY_MIGRATIONS = List.of(
        new FlywayMigration("1", "V1__postgresql_baseline.sql", "SQL", null, true),
        new FlywayMigration("2", "V2__model_family_account_pool_circuits.sql", "SQL", null, true),
        new FlywayMigration("3", "V3__data_management_security_audit.sql", "SQL", null, true),
        new FlywayMigration("4", "V4__data_management_security_outbox.sql", "SQL", null, true),
        new FlywayMigration("5", "V5__account_pool_circuit_epochs.sql", "SQL", null, true),
        new FlywayMigration("6", "V6__application_root_identity.sql", "SQL", null, true),
        new FlywayMigration("7", "V7__api_key_model_provider.sql", "SQL", null, true),
        new FlywayMigration("8", "V8__account_allocation_admission.sql", "SQL", null, true),
        new FlywayMigration("9", "V9__account_allocation_consistency_fixes.sql", "SQL", null, true),
        new FlywayMigration("10", "V10__account_scoped_cooldowns_and_retention.sql", "SQL", null, true),
        new FlywayMigration("11", "V11__account_badge_color.sql", "SQL", null, true),
        new FlywayMigration("12", "V12__coding_plan_subscriptions.sql", "SQL", null, true),
        new FlywayMigration("13", "V13__coding_plan_versions.sql", "SQL", null, true),
        new FlywayMigration("14", "V14__explicit_billing_reservations.sql", "SQL", null, true),
        new FlywayMigration("15", "V15__billing_boundary_consistency.sql", "SQL", null, true),
        new FlywayMigration("16", "V16__plugin_developer_mode.sql", "SQL", null, true),
        new FlywayMigration("17", "V17__announcements.sql", "SQL", null, true),
        new FlywayMigration("18", "V18__announcement_runtime_grants.sql", "SQL", null, true),
        new FlywayMigration("19", "V19__support_tickets.sql", "SQL", null, true),
        new FlywayMigration("20", "V20__function_execute_privileges.sql", "SQL", null, true),
        new FlywayMigration("21", "V21__documentation.sql", "SQL", null, true),
        new FlywayMigration("22", "V22__documentation_english.sql", "SQL", null, true),
        new FlywayMigration("23", "V23__documentation_tutorials.sql", "SQL", null, true),
        new FlywayMigration("24", "V24__documentation_user_focus.sql", "SQL", null, true),
        new FlywayMigration("25", "V25__account_egress_proxy_channels.sql", "SQL", null, true),
        new FlywayMigration("26", "V26__account_egress_policy.sql", "SQL", null, true),
        new FlywayMigration("27", "V27__egress_listener_ports_and_probe_results.sql", "SQL", null, true),
        new FlywayMigration("28", "V28__account_allocation_defaults.sql", "SQL", null, true),
        new FlywayMigration("29", "V29__source_tiers.sql", "SQL", null, true)
    );
    private static final long DATA_TRANSFER_LOCK_KEY = 0x5354415252594150L;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_TOC_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 32;
    private static final Set<Integer> SUPPORTED_POSTGRES_MAJORS = Set.of(17);
    private static final Set<String> REQUIRED_RESTORE_TABLES = Set.of(
        "flyway_schema_history",
        "admin_users",
        "system_settings",
        "proxy_accounts",
        "api_keys",
        "request_logs",
        "payment_orders",
        "balance_ledger", "coding_plans", "coding_plan_versions", "coding_plan_subscriptions",
        "subscription_ledger", "billing_reservations", "billing_reservation_allocations", "billing_review_actions",
        "support_tickets", "support_ticket_messages", "support_ticket_attachments",
        "announcements", "announcement_recipients",
        "documentation_pages", "documentation_attachments", "proxy_subscriptions", "proxy_channels",
        "source_tier_vendors", "source_tiers", "source_tier_members", "source_tier_member_models",
        "source_tier_pair_gates"
    );
    private static final Set<String> GLOBAL_TOC_TYPES = Set.of(
        "ENCODING",
        "STDSTRINGS",
        "SEARCHPATH",
        "DATABASE",
        "DATABASE PROPERTIES",
        "PRE-DATA BOUNDARY",
        "POST-DATA BOUNDARY"
    );
    private static final List<String> TOC_OBJECT_TYPES = List.of(
        "MATERIALIZED VIEW DATA",
        "SEQUENCE OWNED BY",
        "DATABASE PROPERTIES",
        "POST-DATA BOUNDARY",
        "PRE-DATA BOUNDARY",
        "PUBLICATION TABLE",
        "PROCEDURAL LANGUAGE",
        "CHECK CONSTRAINT",
        "FK CONSTRAINT",
        "DEFAULT ACL",
        "SECURITY LABEL",
        "INDEX ATTACH",
        "TABLE ATTACH",
        "TABLE DATA",
        "SEQUENCE SET",
        "MATERIALIZED VIEW",
        "BLOB DATA",
        "FOREIGN TABLE",
        "FOREIGN DATA WRAPPER",
        "SERVER",
        "USER MAPPING",
        "ACCESS METHOD",
        "EVENT TRIGGER",
        "TEXT SEARCH CONFIGURATION",
        "TEXT SEARCH DICTIONARY",
        "TEXT SEARCH PARSER",
        "TEXT SEARCH TEMPLATE",
        "OPERATOR CLASS",
        "OPERATOR FAMILY",
        "CAST",
        "TRANSFORM",
        "COLLATION",
        "CONVERSION",
        "DOMAIN CONSTRAINT",
        "DATABASE",
        "ENCODING",
        "STDSTRINGS",
        "SEARCHPATH",
        "SCHEMA",
        "EXTENSION",
        "TYPE",
        "SHELL TYPE",
        "DOMAIN",
        "FUNCTION",
        "PROCEDURE",
        "AGGREGATE",
        "OPERATOR",
        "TABLE",
        "VIEW",
        "SEQUENCE",
        "DEFAULT",
        "CONSTRAINT",
        "INDEX",
        "STATISTICS",
        "RULE",
        "TRIGGER",
        "POLICY",
        "PUBLICATION",
        "SUBSCRIPTION",
        "COMMENT",
        "ACL",
        "BLOB",
        "BLOBS"
    );
    private static final Pattern TOC_ENTRY = Pattern.compile("^\\d+;\\s+\\d+\\s+\\d+\\s+(.+)$");
    private static final Pattern TOC_DATABASE = Pattern.compile("(?m)^;\\s+dbname:\\s+([^\\s]+)\\s*$");
    private static final Pattern TOC_DATABASE_VERSION = Pattern.compile(
        "(?m)^;\\s+Dumped from database version:\\s+(\\d+)(?:\\.|\\s|$)"
    );
    private static final Pattern TOC_DUMP_VERSION = Pattern.compile(
        "(?m)^;\\s+Dumped by pg_dump version:\\s+(\\d+)(?:\\.|\\s|$)"
    );
    private static final Set<String> SUPPORTED_SSL_MODES = Set.of(
        "disable", "allow", "prefer", "require", "verify-ca", "verify-full"
    );
    private static final Duration PROCESS_TERMINATION_GRACE = Duration.ofSeconds(5);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.of("Asia/Shanghai"));

    private final ObjectMapper objectMapper;
    private final PostgresConnection database;
    private final String username;
    private final String password;
    private final Path backupDir;
    private final long maxImportBytes;
    private final Duration processTimeout;
    private final RuntimeStateInvalidator runtimeStateInvalidator;
    private final ReentrantReadWriteLock maintenanceLock = new ReentrantReadWriteLock(true);

    public DataTransferService(
        ObjectMapper objectMapper,
        @Value("${spring.flyway.url}") String jdbcUrl,
        @Value("${spring.flyway.user}") String username,
        @Value("${spring.flyway.password:}") String password,
        @Value("${starry.data-transfer.backup-dir:${user.home}/.starry-api/backups}") String backupDir,
        @Value("${starry.data-transfer.max-import-bytes:268435456}") long maxImportBytes,
        @Value("${starry.data-transfer.process-timeout:30m}") Duration processTimeout,
        RuntimeStateInvalidator runtimeStateInvalidator
    ) {
        if (maxImportBytes <= 0) {
            throw new IllegalArgumentException("starry.data-transfer.max-import-bytes must be positive");
        }
        if (processTimeout == null || processTimeout.isZero() || processTimeout.isNegative()) {
            throw new IllegalArgumentException("starry.data-transfer.process-timeout must be positive");
        }
        this.objectMapper = objectMapper;
        this.database = parsePostgresJdbcUrl(jdbcUrl);
        this.username = requireSafeValue(username, "PostgreSQL username");
        this.password = password == null ? "" : password;
        if (this.password.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("PostgreSQL password must not contain a null character");
        }
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.maxImportBytes = maxImportBytes;
        this.processTimeout = processTimeout;
        this.runtimeStateInvalidator = runtimeStateInvalidator;
    }

    public Mono<DataTransferDtos.DataTransferStatusResponse> status() {
        return Mono.fromCallable(() -> {
            List<DataTransferDtos.BackupFileResponse> backups = listBackupFiles();
            long size = backups.stream().mapToLong(DataTransferDtos.BackupFileResponse::sizeBytes).sum();
            return new DataTransferDtos.DataTransferStatusResponse(
                database.database(),
                backupDir.toString(),
                maxImportBytes,
                backups.size(),
                size
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExportFile> exportDatabase() {
        return Mono.fromCallable(() -> withBackupLock(() -> {
            Path export = Files.createTempFile("starry-api-export-", DUMP_EXTENSION);
            try {
                runDatabaseTool(pgDumpCommand(export));
                return ExportFile.temporary(
                    "starry-api-database-" + FILE_TIMESTAMP.format(Instant.now()) + DUMP_EXTENSION,
                    export
                );
            } catch (Exception exception) {
                deleteTempFile(export);
                throw exception;
            }
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExportFile> exportBackup() {
        return Mono.fromCallable(() -> withBackupLock(() -> {
            Path export = Files.createTempFile("starry-api-backup-export-", BACKUP_EXTENSION);
            try {
                createBackupArchive(export, Instant.now());
                return ExportFile.temporary(
                    "starry-api-backup-" + FILE_TIMESTAMP.format(Instant.now()) + BACKUP_EXTENSION,
                    export
                );
            } catch (Exception exception) {
                deleteTempFile(export);
                throw exception;
            }
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<DataTransferDtos.BackupListResponse> backups() {
        return Mono.fromCallable(() -> new DataTransferDtos.BackupListResponse(
            ensureBackupDir().toString(),
            listBackupFiles()
        )).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<DataTransferDtos.BackupCreateResponse> createBackup() {
        return Mono.fromCallable(() -> withBackupLock(this::createStoredBackup))
            .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExportFile> downloadBackup(String fileName) {
        return Mono.fromCallable(() -> ExportFile.stored(resolveBackupFile(fileName)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<DataTransferDtos.DeleteBackupResponse> deleteBackup(String fileName) {
        return Mono.fromCallable(() -> {
            Path path = resolveBackupFile(fileName);
            boolean deleted = Files.deleteIfExists(path);
            return new DataTransferDtos.DeleteBackupResponse(fileName, deleted);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<DataTransferDtos.ImportResultResponse> importBackup(Mono<FilePart> filePart, String confirm) {
        if (!IMPORT_CONFIRMATION.equals(confirm)) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "DATA_IMPORT_CONFIRMATION_REQUIRED",
                "PostgreSQL database import requires confirm=RESTORE_DATABASE"
            ));
        }
        return filePart.flatMap(file -> Mono.fromCallable(() -> Files.createTempFile("starry-api-import-", ".upload"))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(tempFile -> file.transferTo(tempFile)
                .then(Mono.fromCallable(() -> importFromFile(file.filename(), tempFile))
                    .subscribeOn(Schedulers.boundedElastic()))
                .doFinally(signal -> deleteTempFile(tempFile))));
    }

    private DataTransferDtos.ImportResultResponse importFromFile(String originalName, Path upload) throws Exception {
        validateImportSize(upload, "Import file exceeds the configured limit");
        String fileName = sanitizeUploadName(originalName);
        String lowerName = fileName.toLowerCase(Locale.ROOT);

        Path restoreFile = upload;
        boolean deleteRestoreFile = false;
        String sourceType;
        if (lowerName.endsWith(BACKUP_EXTENSION)) {
            sourceType = "ZIP";
            restoreFile = extractDumpFromZip(upload);
            deleteRestoreFile = true;
        } else if (lowerName.endsWith(DUMP_EXTENSION)) {
            sourceType = "DUMP";
        } else {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DATA_IMPORT_UNSUPPORTED_FILE",
                "Only PostgreSQL .dump and Starry API .zip backup files can be imported"
            );
        }

        try {
            validateRestoreArchive(restoreFile);
            return restoreDatabase(fileName, sourceType, restoreFile);
        } finally {
            if (deleteRestoreFile) {
                deleteTempFile(restoreFile);
            }
        }
    }

    private DataTransferDtos.ImportResultResponse restoreDatabase(
        String fileName,
        String sourceType,
        Path restoreFile
    ) throws Exception {
        Lock restoreLock = maintenanceLock.writeLock();
        if (!restoreLock.tryLock()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "DATA_DATABASE_MAINTENANCE_IN_PROGRESS",
                "Another PostgreSQL backup or restore operation is in progress"
            );
        }
        try (MaintenanceSession maintenance = acquireMaintenanceSession(false)) {
            DataTransferDtos.BackupCreateResponse preRestoreBackup;
            Exception restoreFailure = null;
            boolean runtimeStateRequiresInvalidation = false;
            Path rollbackDump = null;
            Path restoreTocList = null;
            Path rollbackTocList = null;
            try {
                List<FlywayMigration> expectedMigrations = maintenance.requireCurrentMigrationHistory();
                runtimeStateRequiresInvalidation = true;
                maintenance.suspendRuntimeConnections();
                preRestoreBackup = createStoredBackup();
                rollbackDump = extractDumpFromZip(
                    resolveBackupFile(preRestoreBackup.file().fileName()),
                    Long.MAX_VALUE
                );
                restoreTocList = createRestoreTocList(restoreFile);
                rollbackTocList = createRestoreTocList(rollbackDump);
                Path rollbackArchive = rollbackDump;
                Path incomingTocList = restoreTocList;
                Path previousTocList = rollbackTocList;
                restoreWithRollback(
                    () -> runDatabaseTool(pgRestoreCommand(restoreFile, incomingTocList)),
                    () -> maintenance.verifyAndAnalyzeRestoredDatabase(expectedMigrations),
                    () -> runDatabaseTool(pgRestoreCommand(rollbackArchive, previousTocList)),
                    () -> maintenance.verifyAndAnalyzeRestoredDatabase(expectedMigrations)
                );
            } catch (Exception exception) {
                restoreFailure = exception;
                throw exception;
            } finally {
                deleteTempFile(restoreTocList);
                deleteTempFile(rollbackTocList);
                deleteTempFile(rollbackDump);
                Exception cleanupFailure = null;
                try {
                    ensureRuntimeConnectionsAllowed(maintenance);
                } catch (SQLException reconnectFailure) {
                    cleanupFailure = reconnectFailure;
                }
                if (runtimeStateRequiresInvalidation) {
                    try {
                        runtimeStateInvalidator.invalidateAll();
                    } catch (RuntimeException invalidationFailure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = invalidationFailure;
                        } else {
                            cleanupFailure.addSuppressed(invalidationFailure);
                        }
                    }
                }
                if (cleanupFailure != null) {
                    if (restoreFailure != null) {
                        restoreFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }

            return new DataTransferDtos.ImportResultResponse(
                fileName,
                sourceType,
                preRestoreBackup.file().fileName(),
                Instant.now()
            );
        } finally {
            restoreLock.unlock();
        }
    }

    private DataTransferDtos.BackupCreateResponse createStoredBackup() throws Exception {
        Instant now = Instant.now();
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String fileName = "starry-api-backup-" + FILE_TIMESTAMP.format(now) + "-" + uniqueSuffix + BACKUP_EXTENSION;
        Path target = ensureBackupDir().resolve(fileName);
        Path partial = Files.createTempFile(backupDir, ".starry-api-backup-", ".part");
        try {
            createBackupArchive(partial, now);
            moveAtomically(partial, target);
            return new DataTransferDtos.BackupCreateResponse(toBackupFile(target));
        } finally {
            deleteTempFile(partial);
        }
    }

    private void createBackupArchive(Path target, Instant createdAt) throws Exception {
        Path dump = Files.createTempFile("starry-api-database-", DUMP_EXTENSION);
        try {
            runDatabaseTool(pgDumpCommand(dump));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("application", "starry-api");
            metadata.put("database", database.database());
            metadata.put("databaseEngine", "postgresql");
            metadata.put("createdAt", createdAt.toString());
            metadata.put("format", "starry-api-postgresql-backup-v2");
            metadata.put("content", DATABASE_DUMP_ENTRY);

            try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                StandardCharsets.UTF_8
            )) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                putZipFileEntry(zip, DATABASE_DUMP_ENTRY, dump);
                putZipEntry(
                    zip,
                    "metadata.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata)
                );
                putZipEntry(zip, "README.txt", """
                    Starry API PostgreSQL backup package.

                    database.dump is a PostgreSQL custom-format archive created with pg_dump.
                    Restore this package from the Starry API admin data-transfer page.
                    """.getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            deleteTempFile(dump);
        }
    }

    List<String> pgDumpCommand(Path output) {
        return List.of(
            "pg_dump",
            "--host", database.host(),
            "--port", Integer.toString(database.port()),
            "--username", username,
            "--dbname", database.database(),
            "--no-password",
            "--role", POSTGRES_OWNER_ROLE,
            "--schema", POSTGRES_SCHEMA,
            "--format", "custom",
            "--no-owner",
            "--file", output.toAbsolutePath().normalize().toString()
        );
    }

    List<String> pgRestoreValidationCommand(Path archive) {
        return List.of(
            "pg_restore",
            "--list",
            archive.toAbsolutePath().normalize().toString()
        );
    }

    List<String> pgRestoreCommand(Path archive) {
        return pgRestoreCommand(archive, null);
    }

    List<String> pgRestoreCommand(Path archive, Path tocList) {
        List<String> command = new ArrayList<>(List.of(
            "pg_restore",
            "--host", database.host(),
            "--port", Integer.toString(database.port()),
            "--username", username,
            "--dbname", database.database(),
            "--no-password",
            "--role", POSTGRES_OWNER_ROLE,
            "--schema", POSTGRES_SCHEMA,
            "--clean",
            "--if-exists",
            "--no-owner",
            "--single-transaction",
            "--exit-on-error",
            archive.toAbsolutePath().normalize().toString()
        ));
        if (tocList != null) {
            int archiveIndex = command.size() - 1;
            command.add(archiveIndex, "--use-list");
            command.add(archiveIndex + 1, tocList.toAbsolutePath().normalize().toString());
        }
        return List.copyOf(command);
    }

    private Path createRestoreTocList(Path archive) throws IOException {
        ProcessOutput toc = runDatabaseToolForOutput(pgRestoreValidationCommand(archive), MAX_TOC_OUTPUT_BYTES);
        if (toc.truncated()) {
            throw incompatibleArchive("PostgreSQL archive table of contents exceeds the validation limit");
        }
        validateRestoreToc(toc.text());
        Path list = Files.createTempFile("starry-api-restore-", ".toc");
        try {
            Files.writeString(
                list,
                filterInheritedPartitionObjects(toc.text()),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            return list;
        } catch (Exception exception) {
            deleteTempFile(list);
            throw exception;
        }
    }

    static String filterInheritedPartitionObjects(String toc) {
        if (toc == null || toc.isEmpty()) {
            return toc;
        }

        // Partition creation recreates inherited constraints and their indexes; pg_restore --clean cannot drop them.
        List<String> lines = toc.lines().toList();
        Set<String> partitionTables = new HashSet<>();
        for (String line : lines) {
            Matcher matcher = TOC_ENTRY.matcher(line.strip());
            if (!matcher.matches()) {
                continue;
            }
            TocObject object = parseTocObject(matcher.group(1));
            if (POSTGRES_SCHEMA.equals(object.namespace()) && "TABLE ATTACH".equals(object.type())) {
                partitionTables.add(readIdentifier(object.details()).value());
            }
        }

        if (partitionTables.isEmpty()) {
            return toc;
        }

        Set<String> inheritedConstraintNames = new HashSet<>();
        Set<String> skippedLines = new HashSet<>();
        for (String line : lines) {
            Matcher matcher = TOC_ENTRY.matcher(line.strip());
            if (!matcher.matches()) {
                continue;
            }
            TocObject object = parseTocObject(matcher.group(1));
            if (!POSTGRES_SCHEMA.equals(object.namespace()) || !"CONSTRAINT".equals(object.type())) {
                continue;
            }
            Identifier table = readIdentifier(object.details());
            if (!partitionTables.contains(table.value())) {
                continue;
            }
            Identifier constraint = readIdentifier(table.remaining());
            inheritedConstraintNames.add(constraint.value());
            skippedLines.add(line);
        }

        StringBuilder filtered = new StringBuilder(toc.length());
        for (String line : lines) {
            Matcher matcher = TOC_ENTRY.matcher(line.strip());
            boolean skip = skippedLines.contains(line);
            if (!skip && matcher.matches()) {
                TocObject object = parseTocObject(matcher.group(1));
                if (POSTGRES_SCHEMA.equals(object.namespace()) && "INDEX ATTACH".equals(object.type())) {
                    skip = inheritedConstraintNames.contains(readIdentifier(object.details()).value());
                }
            }
            if (!skip) {
                filtered.append(line).append('\n');
            }
        }
        return filtered.toString();
    }

    private void validateRestoreArchive(Path archive) {
        ProcessOutput toc;
        try {
            toc = runDatabaseToolForOutput(pgRestoreValidationCommand(archive), MAX_TOC_OUTPUT_BYTES);
        } catch (IOException exception) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DATA_IMPORT_INVALID_ARCHIVE",
                "The uploaded file is not a readable PostgreSQL custom-format archive"
            );
        }
        if (toc.truncated()) {
            throw incompatibleArchive("PostgreSQL archive table of contents exceeds the validation limit");
        }
        validateRestoreToc(toc.text());
    }

    static void validateRestoreToc(String toc) {
        if (toc == null || toc.isBlank()) {
            throw incompatibleArchive("PostgreSQL archive table of contents is empty");
        }

        String databaseName = requiredHeaderValue(TOC_DATABASE, toc, "database name");
        if (!POSTGRES_DATABASE.equals(databaseName)) {
            throw incompatibleArchive("PostgreSQL archive must originate from database " + POSTGRES_DATABASE);
        }
        validatePostgresMajor(TOC_DATABASE_VERSION, toc, "source database");
        validatePostgresMajor(TOC_DUMP_VERSION, toc, "pg_dump");

        boolean schemaFound = false;
        Set<String> tables = new HashSet<>();
        for (String line : toc.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith(";")) {
                continue;
            }
            Matcher matcher = TOC_ENTRY.matcher(trimmed);
            if (!matcher.matches()) {
                throw incompatibleArchive("PostgreSQL archive contains an unrecognized TOC entry");
            }
            TocObject object = parseTocObject(matcher.group(1));
            if (POSTGRES_SCHEMA.equals(object.namespace())) {
                if ("TABLE".equals(object.type())) {
                    tables.add(readIdentifier(object.details()).value());
                }
                continue;
            }
            if (!"-".equals(object.namespace())) {
                throw incompatibleArchive("PostgreSQL archive contains objects outside schema " + POSTGRES_SCHEMA);
            }
            if ("SCHEMA".equals(object.type())) {
                Identifier schema = readIdentifier(object.details());
                if (!POSTGRES_SCHEMA.equals(schema.value())) {
                    throw incompatibleArchive("PostgreSQL archive contains an unexpected schema");
                }
                schemaFound = true;
                continue;
            }
            if (("ACL".equals(object.type()) || "COMMENT".equals(object.type()))
                && isStarrySchemaMetadata(object.details())) {
                continue;
            }
            if (!GLOBAL_TOC_TYPES.contains(object.type())) {
                throw incompatibleArchive("PostgreSQL archive contains unsupported global objects");
            }
            if (("DATABASE".equals(object.type()) || "DATABASE PROPERTIES".equals(object.type()))
                && !POSTGRES_DATABASE.equals(readIdentifier(object.details()).value())) {
                throw incompatibleArchive("PostgreSQL archive targets an unexpected database");
            }
        }

        if (!schemaFound) {
            throw incompatibleArchive("PostgreSQL archive does not contain the required schema object");
        }
        Set<String> missingTables = new HashSet<>(REQUIRED_RESTORE_TABLES);
        missingTables.removeAll(tables);
        if (!missingTables.isEmpty()) {
            throw incompatibleArchive(
                "PostgreSQL archive is missing required Starry API tables: "
                    + missingTables.stream().sorted().toList()
            );
        }
    }

    private static TocObject parseTocObject(String body) {
        for (String type : TOC_OBJECT_TYPES) {
            String prefix = type + " ";
            if (!body.startsWith(prefix)) {
                continue;
            }
            Identifier namespace = readIdentifier(body.substring(prefix.length()));
            return new TocObject(type, namespace.value(), namespace.remaining());
        }
        throw incompatibleArchive("PostgreSQL archive contains an unsupported TOC object type");
    }

    private static Identifier readIdentifier(String input) {
        String value = input == null ? "" : input.stripLeading();
        if (value.isEmpty()) {
            throw incompatibleArchive("PostgreSQL archive contains an incomplete TOC entry");
        }
        if (value.charAt(0) != '"') {
            int separator = firstWhitespace(value);
            return separator < 0
                ? new Identifier(value, "")
                : new Identifier(value.substring(0, separator), value.substring(separator).stripLeading());
        }

        StringBuilder identifier = new StringBuilder();
        for (int index = 1; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '"') {
                identifier.append(current);
                continue;
            }
            if (index + 1 < value.length() && value.charAt(index + 1) == '"') {
                identifier.append('"');
                index++;
                continue;
            }
            return new Identifier(identifier.toString(), value.substring(index + 1).stripLeading());
        }
        throw incompatibleArchive("PostgreSQL archive contains an invalid quoted identifier");
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isStarrySchemaMetadata(String details) {
        Identifier objectType = readIdentifier(details);
        return "SCHEMA".equals(objectType.value())
            && POSTGRES_SCHEMA.equals(readIdentifier(objectType.remaining()).value());
    }

    private static String requiredHeaderValue(Pattern pattern, String toc, String label) {
        Matcher matcher = pattern.matcher(toc);
        if (!matcher.find()) {
            throw incompatibleArchive("PostgreSQL archive is missing its " + label + " metadata");
        }
        return matcher.group(1);
    }

    private static void validatePostgresMajor(Pattern pattern, String toc, String label) {
        String value = requiredHeaderValue(pattern, toc, label + " version");
        int major;
        try {
            major = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw incompatibleArchive("PostgreSQL archive has an invalid " + label + " version");
        }
        if (!SUPPORTED_POSTGRES_MAJORS.contains(major)) {
            throw incompatibleArchive(
                "PostgreSQL archive " + label + " major version " + major + " is not supported"
            );
        }
    }

    private static ApiException incompatibleArchive(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "DATA_IMPORT_INCOMPATIBLE_ARCHIVE", message);
    }

    static void restoreWithRollback(
        CheckedRunnable restore,
        CheckedRunnable verify,
        CheckedRunnable rollback,
        CheckedRunnable verifyRollback
    ) throws Exception {
        restore.run();
        try {
            verify.run();
        } catch (Exception verificationFailure) {
            try {
                rollback.run();
                verifyRollback.run();
            } catch (Exception rollbackFailure) {
                verificationFailure.addSuppressed(rollbackFailure);
            }
            throw verificationFailure;
        }
    }

    static void validateMigrationHistory(List<FlywayMigration> history) throws SQLException {
        if (history == null || history.size() != REQUIRED_FLYWAY_MIGRATIONS.size()) {
            throw new SQLException(
                "PostgreSQL schema must contain exactly the supported Flyway V1-V"
                    + REQUIRED_FLYWAY_VERSION + " migration history"
            );
        }
        for (int index = 0; index < REQUIRED_FLYWAY_MIGRATIONS.size(); index++) {
            FlywayMigration expected = REQUIRED_FLYWAY_MIGRATIONS.get(index);
            FlywayMigration actual = history.get(index);
            if (actual == null
                || !expected.version().equals(actual.version())
                || !expected.script().equals(actual.script())
                || !expected.type().equals(actual.type())
                || !actual.success()
                || actual.checksum() == null) {
                throw new SQLException(
                    "PostgreSQL schema is not at the exact supported Flyway V"
                        + REQUIRED_FLYWAY_VERSION + " migration contract"
                );
            }
        }
    }

    static void verifyRestoreMigrationHistory(
        List<FlywayMigration> expected,
        List<FlywayMigration> restored
    ) throws SQLException {
        validateMigrationHistory(expected);
        validateMigrationHistory(restored);
        if (!expected.equals(restored)) {
            throw new SQLException(
                "PostgreSQL restore Flyway history or migration checksums do not match the running application"
            );
        }
    }

    static String maintenanceLockSql(boolean shared) {
        return shared
            ? "SELECT pg_catalog.pg_try_advisory_lock_shared(?)"
            : "SELECT pg_catalog.pg_try_advisory_lock(?)";
    }

    static String maintenanceUnlockSql(boolean shared) {
        return shared
            ? "SELECT pg_catalog.pg_advisory_unlock_shared(?)"
            : "SELECT pg_catalog.pg_advisory_unlock(?)";
    }

    private MaintenanceSession acquireMaintenanceSession(boolean shared) throws SQLException {
        Connection connection = openMaintenanceConnection();
        boolean acquired = false;
        try {
            try (PreparedStatement statement = connection.prepareStatement(maintenanceLockSql(shared))) {
                statement.setLong(1, DATA_TRANSFER_LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    acquired = result.next() && result.getBoolean(1);
                }
            }
            if (!acquired) {
                throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DATA_DATABASE_MAINTENANCE_IN_PROGRESS",
                    "Another PostgreSQL backup or restore operation is in progress"
                );
            }
            return new MaintenanceSession(connection, shared);
        } finally {
            if (!acquired) {
                connection.close();
            }
        }
    }

    private Connection openMaintenanceConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty(
            "socketTimeout",
            Long.toString(Math.max(1, Math.min(Integer.MAX_VALUE, processTimeout.toSeconds())))
        );
        Connection connection = DriverManager.getConnection(database.jdbcUrl(), properties);
        try {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + POSTGRES_OWNER_ROLE);
            }
            return connection;
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private void ensureRuntimeConnectionsAllowed(MaintenanceSession maintenance) throws SQLException {
        try {
            maintenance.allowRuntimeConnections();
            return;
        } catch (SQLException primaryFailure) {
            try (Connection recovery = openMaintenanceConnection(); Statement statement = recovery.createStatement()) {
                statement.executeUpdate(
                    "GRANT CONNECT ON DATABASE " + POSTGRES_DATABASE + " TO " + POSTGRES_RUNTIME_ROLE
                );
                maintenance.runtimeConnectRevoked = false;
                return;
            } catch (SQLException recoveryFailure) {
                primaryFailure.addSuppressed(recoveryFailure);
                throw primaryFailure;
            }
        }
    }

    Map<String, String> databaseToolEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>(database.libpqEnvironment());
        environment.put("PGCONNECT_TIMEOUT", "10");
        environment.put("PGPASSWORD", password);
        return Map.copyOf(environment);
    }

    private void runDatabaseTool(List<String> command) throws IOException {
        executeProcess(command, databaseToolEnvironment(), processTimeout);
    }

    private ProcessOutput runDatabaseToolForOutput(List<String> command, int outputLimit) throws IOException {
        return executeProcess(command, databaseToolEnvironment(), processTimeout, outputLimit);
    }

    static String executeProcess(List<String> command, Map<String, String> environment, Duration timeout) throws IOException {
        return executeProcess(command, environment, timeout, MAX_PROCESS_OUTPUT_BYTES).text();
    }

    private static ProcessOutput executeProcess(
        List<String> command,
        Map<String, String> environment,
        Duration timeout,
        int outputLimit
    ) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Map<String, String> processEnvironment = processBuilder.environment();
        processEnvironment.keySet().removeIf(key -> key.startsWith("PG"));
        processEnvironment.putAll(environment);

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException exception) {
            throw new IOException("Could not start PostgreSQL utility " + command.getFirst(), exception);
        }

        BoundedProcessOutput output = new BoundedProcessOutput(outputLimit);
        Thread outputReader = Thread.ofVirtual()
            .name("postgresql-utility-output")
            .start(() -> output.readFrom(process.getInputStream()));

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            terminateProcess(process);
            joinOutputReader(outputReader);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running PostgreSQL utility " + command.getFirst(), exception);
        }

        if (!finished) {
            terminateProcess(process);
            joinOutputReader(outputReader);
            throw new IOException("PostgreSQL utility " + command.getFirst() + " timed out after " + timeout);
        }

        joinOutputReader(outputReader);
        if (process.exitValue() != 0) {
            String details = output.text().strip();
            String suffix = details.isEmpty() ? "" : ": " + details;
            throw new IOException(
                "PostgreSQL utility " + command.getFirst() + " exited with code " + process.exitValue() + suffix
            );
        }
        return new ProcessOutput(output.text(), output.truncated());
    }

    private static void terminateProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(PROCESS_TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(PROCESS_TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void joinOutputReader(Thread outputReader) throws IOException {
        try {
            if (!outputReader.join(PROCESS_TERMINATION_GRACE)) {
                outputReader.interrupt();
                throw new IOException("Timed out while collecting PostgreSQL utility output");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while collecting PostgreSQL utility output", exception);
        }
    }

    private Path extractDumpFromZip(Path zipFile) throws IOException {
        return extractDumpFromZip(zipFile, maxImportBytes);
    }

    private Path extractDumpFromZip(Path zipFile, long extractedSizeLimit) throws IOException {
        Path extracted = Files.createTempFile("starry-api-restore-", DUMP_EXTENSION);
        boolean found = false;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "DATA_IMPORT_TOO_MANY_ZIP_ENTRIES",
                        "PostgreSQL backup package contains too many entries"
                    );
                }
                if (entry.isDirectory() || !DATABASE_DUMP_ENTRY.equals(entry.getName())) {
                    continue;
                }
                if (found) {
                    throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "DATA_IMPORT_DUPLICATE_DUMP",
                        "PostgreSQL backup package contains duplicate database.dump entries"
                    );
                }
                copyWithLimit(zip, extracted, extractedSizeLimit);
                found = true;
            }
        } catch (Exception exception) {
            deleteTempFile(extracted);
            throw exception;
        }
        if (!found) {
            deleteTempFile(extracted);
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DATA_IMPORT_DUMP_NOT_FOUND",
                "PostgreSQL backup package does not contain database.dump"
            );
        }
        return extracted;
    }

    private void copyWithLimit(InputStream input, Path target, long limit) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        try (var output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new ApiException(
                        HttpStatus.CONTENT_TOO_LARGE,
                        "DATA_IMPORT_FILE_TOO_LARGE",
                        "PostgreSQL dump inside the backup exceeds the configured limit"
                    );
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private void validateImportSize(Path file, String message) throws IOException {
        if (Files.size(file) > maxImportBytes) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "DATA_IMPORT_FILE_TOO_LARGE", message);
        }
    }

    private void putZipFileEntry(ZipOutputStream zip, String name, Path source) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(source, zip);
        zip.closeEntry();
    }

    private void putZipEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private List<DataTransferDtos.BackupFileResponse> listBackupFiles() throws IOException {
        Path dir = ensureBackupDir();
        try (var stream = Files.list(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> path.getFileName().toString().endsWith(BACKUP_EXTENSION))
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .map(this::toBackupFileUnchecked)
                .toList();
        }
    }

    private DataTransferDtos.BackupFileResponse toBackupFileUnchecked(Path path) {
        try {
            return toBackupFile(path);
        } catch (IOException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "DATA_BACKUP_READ_FAILED",
                "Could not read PostgreSQL backup file metadata"
            );
        }
    }

    private DataTransferDtos.BackupFileResponse toBackupFile(Path path) throws IOException {
        return new DataTransferDtos.BackupFileResponse(
            path.getFileName().toString(),
            Files.size(path),
            Files.getLastModifiedTime(path).toInstant()
        );
    }

    private Path resolveBackupFile(String fileName) throws IOException {
        String safeName = sanitizeBackupName(fileName);
        Path path = ensureBackupDir().resolve(safeName).normalize();
        if (!path.startsWith(backupDir) || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DATA_BACKUP_NOT_FOUND", "PostgreSQL backup file not found");
        }
        return path;
    }

    private Path ensureBackupDir() throws IOException {
        Files.createDirectories(backupDir);
        return backupDir;
    }

    String sanitizeBackupName(String fileName) {
        String name = sanitizeUploadName(fileName);
        if (!name.endsWith(BACKUP_EXTENSION)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_BACKUP_INVALID_NAME", "Invalid backup file name");
        }
        return name;
    }

    String sanitizeUploadName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "upload" : fileName;
        if (
            name.length() > 255
                || name.startsWith(".")
                || name.contains("/")
                || name.contains("\\")
                || containsControlCharacter(name)
        ) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_FILE_INVALID_NAME", "Invalid file name");
        }
        return name;
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static PostgresConnection parsePostgresJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("Data transfer supports only a PostgreSQL JDBC URL");
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            String host = requireSafeValue(uri.getHost(), "PostgreSQL host");
            int port = uri.getPort() < 0 ? 5432 : uri.getPort();
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("PostgreSQL port must be between 1 and 65535");
            }
            String path = uri.getPath();
            if (path == null || path.length() <= 1 || path.indexOf('/', 1) >= 0) {
                throw new IllegalArgumentException("PostgreSQL JDBC URL must contain exactly one database name");
            }
            String database = requireSafeValue(path.substring(1), "PostgreSQL database name");
            if (!POSTGRES_DATABASE.equals(database)) {
                throw new IllegalArgumentException("Data transfer requires PostgreSQL database starry_api");
            }
            return new PostgresConnection(
                host,
                port,
                database,
                jdbcUrl,
                parseLibpqEnvironment(uri.getRawQuery())
            );
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid PostgreSQL JDBC URL", exception);
        }
    }

    private static String requireSafeValue(String value, String label) {
        if (value == null || value.isBlank() || containsControlCharacter(value)) {
            throw new IllegalArgumentException(label + " must not be blank or contain control characters");
        }
        return value;
    }

    private static Map<String, String> parseLibpqEnvironment(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> environment = new LinkedHashMap<>();
        for (String parameter : rawQuery.split("&")) {
            int separator = parameter.indexOf('=');
            String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
            String rawValue = separator < 0 ? "" : parameter.substring(separator + 1);
            String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            if ("user".equals(name) || "password".equals(name)) {
                throw new IllegalArgumentException(
                    "PostgreSQL credentials must be configured separately from the JDBC URL"
                );
            }
            String environmentName = switch (name) {
                case "sslmode" -> "PGSSLMODE";
                case "sslrootcert" -> "PGSSLROOTCERT";
                case "sslcert" -> "PGSSLCERT";
                case "sslkey" -> "PGSSLKEY";
                case "sslcrl" -> "PGSSLCRL";
                case "sslpassword" -> "PGSSLPASSWORD";
                default -> null;
            };
            if (environmentName == null) {
                continue;
            }
            value = requireSafeValue(value, "PostgreSQL " + name);
            if ("PGSSLMODE".equals(environmentName)) {
                value = value.toLowerCase(Locale.ROOT);
                if (!SUPPORTED_SSL_MODES.contains(value)) {
                    throw new IllegalArgumentException("Unsupported PostgreSQL sslmode: " + value);
                }
            }
            if (environment.putIfAbsent(environmentName, value) != null) {
                throw new IllegalArgumentException("Duplicate PostgreSQL connection parameter: " + name);
            }
        }
        return Map.copyOf(environment);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private <T> T withBackupLock(CheckedSupplier<T> supplier) throws Exception {
        Lock backupLock = maintenanceLock.readLock();
        backupLock.lockInterruptibly();
        try (MaintenanceSession ignored = acquireMaintenanceSession(true)) {
            return supplier.get();
        } finally {
            backupLock.unlock();
        }
    }

    private static void deleteTempFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup after response completion or a failed operation.
        }
    }

    private record PostgresConnection(
        String host,
        int port,
        String database,
        String jdbcUrl,
        Map<String, String> libpqEnvironment
    ) {
    }

    private record TocObject(String type, String namespace, String details) {
    }

    private record Identifier(String value, String remaining) {
    }

    record FlywayMigration(
        String version,
        String script,
        String type,
        Integer checksum,
        boolean success
    ) {
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface CheckedRunnable {
        void run() throws Exception;
    }

    private final class MaintenanceSession implements AutoCloseable {

        private final Connection connection;
        private final boolean shared;
        private boolean runtimeConnectRevoked;

        private MaintenanceSession(Connection connection, boolean shared) {
            this.connection = connection;
            this.shared = shared;
        }

        private void suspendRuntimeConnections() throws SQLException {
            if (shared) {
                throw new IllegalStateException("A shared backup lock cannot suspend runtime connections");
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "REVOKE CONNECT ON DATABASE " + POSTGRES_DATABASE + " FROM " + POSTGRES_RUNTIME_ROLE
                );
                runtimeConnectRevoked = true;
            }

            String sql = """
                SELECT activity.pid,
                       pg_catalog.pg_terminate_backend(activity.pid, 10000) AS terminated
                FROM pg_catalog.pg_stat_activity activity
                WHERE activity.datname = ?
                  AND activity.usename = ?
                  AND activity.pid <> pg_catalog.pg_backend_pid()
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, POSTGRES_DATABASE);
                statement.setString(2, POSTGRES_RUNTIME_LOGIN);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (!result.getBoolean("terminated")) {
                            throw new SQLException(
                                "Could not terminate PostgreSQL runtime backend " + result.getInt("pid")
                            );
                        }
                    }
                }
            }
        }

        private void allowRuntimeConnections() throws SQLException {
            if (!runtimeConnectRevoked) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "GRANT CONNECT ON DATABASE " + POSTGRES_DATABASE + " TO " + POSTGRES_RUNTIME_ROLE
                );
                runtimeConnectRevoked = false;
            }
        }

        private List<FlywayMigration> requireCurrentMigrationHistory() throws SQLException {
            List<FlywayMigration> migrations = readMigrationHistory();
            validateMigrationHistory(migrations);
            return migrations;
        }

        private List<FlywayMigration> readMigrationHistory() throws SQLException {
            List<FlywayMigration> migrations = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
                SELECT version,
                       script,
                       type,
                       checksum,
                       success
                FROM starry_api.flyway_schema_history
                ORDER BY installed_rank
                """)) {
                while (result.next()) {
                    migrations.add(new FlywayMigration(
                        result.getString("version"),
                        result.getString("script"),
                        result.getString("type"),
                        result.getObject("checksum", Integer.class),
                        result.getBoolean("success")
                    ));
                }
            }
            return List.copyOf(migrations);
        }

        private void verifyRestoredDatabase(List<FlywayMigration> expectedMigrations) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.to_regnamespace(?) IS NOT NULL"
            )) {
                statement.setString(1, POSTGRES_SCHEMA);
                if (!singleBoolean(statement)) {
                    throw new SQLException("PostgreSQL restore did not recreate schema " + POSTGRES_SCHEMA);
                }
            }

            for (String table : REQUIRED_RESTORE_TABLES) {
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_catalog.to_regclass(?) IS NOT NULL"
                )) {
                    statement.setString(1, POSTGRES_SCHEMA + "." + table);
                    if (!singleBoolean(statement)) {
                        throw new SQLException("PostgreSQL restore did not recreate required table " + table);
                    }
                }
            }

            verifyRestoreMigrationHistory(expectedMigrations, readMigrationHistory());
            verifyApplicationRoot();
            verifyCircuitEpochContract();
            verifySecurityAuditBoundary();
        }

        private void verifyApplicationRoot() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) = 1
                       AND COUNT(*) FILTER (
                           WHERE users.username = 'admin'
                             AND users.role = 'ADMIN'
                             AND users.enabled
                             AND NULLIF(btrim(users.user_id), '') IS NOT NULL
                             AND users.password_hash ~ '^[0-9a-f]{64}$'
                             AND users.password_salt ~ '^[0-9a-f]{32}$'
                       ) = 1
                FROM starry_api.admin_users AS users
                JOIN starry_api.system_settings AS settings
                  ON settings.setting_key = 'application_root_user_id'
                 AND settings.setting_value = users.user_id
                WHERE lower(users.username) = 'admin'
                  AND settings.setting_value <> ''
                """)) {
                if (!singleBoolean(statement)) {
                    throw new SQLException(
                        "PostgreSQL restore does not contain exactly one enabled ADMIN system Root matching its identity marker"
                    );
                }
            }
        }

        private void verifyCircuitEpochContract() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_attribute
                           WHERE attrelid = 'starry_api.request_logs'::pg_catalog.regclass
                             AND attname = 'circuit_epoch'
                             AND atttypid = 'pg_catalog.uuid'::pg_catalog.regtype
                             AND attnum > 0
                             AND NOT attisdropped
                       )
                       AND EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_class AS index_relation
                           JOIN pg_catalog.pg_namespace AS namespace
                             ON namespace.oid = index_relation.relnamespace
                           JOIN pg_catalog.pg_index AS index_metadata
                             ON index_metadata.indexrelid = index_relation.oid
                           WHERE namespace.nspname = 'starry_api'
                             AND index_relation.relname = 'idx_request_logs_circuit_window'
                             AND index_metadata.indrelid = 'starry_api.request_logs'::pg_catalog.regclass
                             AND index_metadata.indisvalid
                             AND index_metadata.indisready
                       )
                       AND (
                           SELECT COUNT(*)
                           FROM starry_api.system_settings
                           WHERE setting_key IN (
                               'account_pool_policy_epoch',
                               'account_pool_policy_activation_started_at'
                           )
                       ) = 2
                """)) {
                if (!singleBoolean(statement)) {
                    throw new SQLException(
                        "PostgreSQL restore is missing the Flyway V5 account-pool circuit epoch contract"
                    );
                }
            }
        }

        private void verifySecurityAuditBoundary() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    pg_catalog.to_regnamespace(?) IS NOT NULL
                    AND pg_catalog.to_regclass('starry_security.data_management_security_audit_events')
                        IS NOT NULL
                    AND pg_catalog.to_regclass('starry_security.data_management_security_alert_outbox')
                        IS NOT NULL
                    AND EXISTS (
                        SELECT 1
                        FROM pg_catalog.pg_trigger AS trigger
                        JOIN pg_catalog.pg_class AS relation ON relation.oid = trigger.tgrelid
                        JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
                        WHERE namespace.nspname = 'starry_security'
                          AND relation.relname = 'data_management_security_audit_events'
                          AND trigger.tgname = 'trg_data_management_security_audit_append_only'
                          AND NOT trigger.tgisinternal
                          AND trigger.tgenabled = 'O'
                    )
                    AND pg_catalog.has_schema_privilege(
                        'starry_api_runtime',
                        'starry_security',
                        'USAGE'
                    )
                    AND pg_catalog.has_table_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_audit_events',
                        'SELECT'
                    )
                    AND pg_catalog.has_table_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_audit_events',
                        'INSERT'
                    )
                    AND NOT pg_catalog.has_table_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_audit_events',
                        'UPDATE'
                    )
                    AND NOT pg_catalog.has_table_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_audit_events',
                        'DELETE'
                    )
                    AND NOT pg_catalog.has_table_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_audit_events',
                        'TRUNCATE'
                    )
                    AND pg_catalog.has_table_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_alert_outbox',
                        'SELECT'
                    )
                    AND pg_catalog.has_column_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_alert_outbox',
                        'event_id',
                        'INSERT'
                    )
                    AND pg_catalog.has_column_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_alert_outbox',
                        'lease_owner',
                        'UPDATE'
                    )
                    AND NOT pg_catalog.has_table_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_alert_outbox',
                        'DELETE'
                    )
                    AND NOT pg_catalog.has_table_privilege(
                        'starry_api_runtime',
                        'starry_security.data_management_security_alert_outbox',
                        'TRUNCATE'
                    )
                    AND NOT EXISTS (
                        SELECT 1
                        FROM information_schema.columns
                        WHERE table_schema = 'starry_security'
                          AND table_name IN (
                              'data_management_security_audit_events',
                              'data_management_security_alert_outbox'
                          )
                          AND column_name IN (
                              'password',
                              'password_hash',
                              'password_salt',
                              'security_key',
                              'submitted_key',
                              'access_token',
                              'refresh_token',
                              'id_token',
                              'client_secret',
                              'session_token',
                              'data_management_token'
                          )
                    )
                """)) {
                statement.setString(1, POSTGRES_SECURITY_SCHEMA);
                if (!singleBoolean(statement)) {
                    throw new SQLException(
                        "PostgreSQL security audit schema failed append-only, privilege, or secret-column checks"
                    );
                }
            }
        }

        private void verifyAndAnalyzeRestoredDatabase(List<FlywayMigration> expectedMigrations) throws SQLException {
            verifyRestoredDatabase(expectedMigrations);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE");
            }
        }

        private boolean singleBoolean(PreparedStatement statement) throws SQLException {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            if (runtimeConnectRevoked) {
                try {
                    ensureRuntimeConnectionsAllowed(this);
                } catch (SQLException exception) {
                    failure = exception;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(maintenanceUnlockSql(shared))) {
                statement.setLong(1, DATA_TRANSFER_LOCK_KEY);
                statement.executeQuery().close();
            } catch (SQLException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            try {
                connection.close();
            } catch (SQLException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class BoundedProcessOutput {

        private final int limit;
        private final ByteArrayOutputStream bytes;
        private volatile boolean truncated;

        private BoundedProcessOutput(int limit) {
            this.limit = limit;
            this.bytes = new ByteArrayOutputStream(limit);
        }

        private void readFrom(InputStream input) {
            byte[] buffer = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = limit - bytes.size();
                    if (remaining > 0) {
                        bytes.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // Process termination can close the stream while the reader is draining it.
            }
        }

        private String text() {
            String text = bytes.toString(StandardCharsets.UTF_8);
            if (truncated) {
                return text + "\n[output truncated]";
            }
            return text;
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private record ProcessOutput(String text, boolean truncated) {
    }

    public record ExportFile(String fileName, Path path, long sizeBytes, boolean deleteAfterTransfer) {

        private static ExportFile temporary(String fileName, Path path) throws IOException {
            return new ExportFile(fileName, path, Files.size(path), true);
        }

        private static ExportFile stored(Path path) throws IOException {
            return new ExportFile(path.getFileName().toString(), path, Files.size(path), false);
        }

        public void cleanup() {
            if (deleteAfterTransfer) {
                deleteTempFile(path);
            }
        }
    }
}
