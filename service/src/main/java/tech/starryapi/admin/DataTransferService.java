package tech.starryapi.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

@Service
public class DataTransferService {

    public static final String IMPORT_CONFIRMATION = "RESTORE_DATABASE";

    private static final String BACKUP_EXTENSION = ".zip";
    private static final String SQL_EXTENSION = ".sql";
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.of("Asia/Shanghai"));

    private final ObjectMapper objectMapper;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Path backupDir;
    private final long maxImportBytes;

    public DataTransferService(
        ObjectMapper objectMapper,
        @Value("${spring.flyway.url}") String jdbcUrl,
        @Value("${spring.flyway.user}") String username,
        @Value("${spring.flyway.password:}") String password,
        @Value("${starry.data-transfer.backup-dir:${user.home}/.starry-api/backups}") String backupDir,
        @Value("${starry.data-transfer.max-import-bytes:268435456}") long maxImportBytes
    ) {
        this.objectMapper = objectMapper;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.backupDir = Paths.get(backupDir).toAbsolutePath().normalize();
        this.maxImportBytes = maxImportBytes;
    }

    public Mono<DataTransferDtos.DataTransferStatusResponse> status() {
        return Mono.fromCallable(() -> {
            List<DataTransferDtos.BackupFileResponse> backups = listBackupFiles();
            long size = backups.stream().mapToLong(DataTransferDtos.BackupFileResponse::sizeBytes).sum();
            return new DataTransferDtos.DataTransferStatusResponse(
                databaseName(),
                backupDir.toString(),
                maxImportBytes,
                backups.size(),
                size
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExportFile> exportDatabase() {
        return Mono.fromCallable(() -> {
            String sql = buildSqlDump();
            return new ExportFile(
                "starry-api-database-" + FILE_TIMESTAMP.format(Instant.now()) + SQL_EXTENSION,
                sql.getBytes(StandardCharsets.UTF_8)
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExportFile> exportBackup() {
        return Mono.fromCallable(() -> createBackupPayload()).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<DataTransferDtos.BackupListResponse> backups() {
        return Mono.fromCallable(() -> new DataTransferDtos.BackupListResponse(
            ensureBackupDir().toString(),
            listBackupFiles()
        )).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<DataTransferDtos.BackupCreateResponse> createBackup() {
        return Mono.fromCallable(() -> {
            ExportFile payload = createBackupPayload();
            Path target = ensureBackupDir().resolve(payload.fileName());
            Files.write(target, payload.bytes());
            return new DataTransferDtos.BackupCreateResponse(toBackupFile(target));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExportFile> downloadBackup(String fileName) {
        return Mono.fromCallable(() -> {
            Path path = resolveBackupFile(fileName);
            return new ExportFile(path.getFileName().toString(), Files.readAllBytes(path));
        }).subscribeOn(Schedulers.boundedElastic());
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
                "Database import requires confirm=RESTORE_DATABASE"
            ));
        }
        return filePart.flatMap(file -> Mono.fromCallable(() -> Files.createTempFile("starry-api-import-", ".upload"))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(tempFile -> file.transferTo(tempFile)
                .then(Mono.fromCallable(() -> importFromFile(file.filename(), tempFile)).subscribeOn(Schedulers.boundedElastic()))
                .doFinally(signal -> deleteTempFile(tempFile))));
    }

    private DataTransferDtos.ImportResultResponse importFromFile(String originalName, Path file) throws IOException, SQLException {
        long size = Files.size(file);
        if (size > maxImportBytes) {
            throw new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "DATA_IMPORT_FILE_TOO_LARGE",
                "Import file exceeds the configured limit"
            );
        }

        String fileName = sanitizeUploadName(originalName);
        String lower = fileName.toLowerCase(Locale.ROOT);
        String sourceType;
        String sql;
        if (lower.endsWith(BACKUP_EXTENSION)) {
            sourceType = "ZIP";
            sql = extractSqlFromZip(Files.readAllBytes(file));
        } else if (lower.endsWith(SQL_EXTENSION)) {
            sourceType = "SQL";
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } else {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DATA_IMPORT_UNSUPPORTED_FILE",
                "Only .sql and .zip backup files can be imported"
            );
        }

        DataTransferDtos.BackupCreateResponse preRestoreBackup = createBackup().block();
        int statements = executeSql(sql);
        String preRestoreBackupName = preRestoreBackup == null ? null : preRestoreBackup.file().fileName();
        return new DataTransferDtos.ImportResultResponse(
            fileName,
            sourceType,
            statements,
            preRestoreBackupName,
            Instant.now()
        );
    }

    private ExportFile createBackupPayload() throws IOException, SQLException {
        Instant now = Instant.now();
        String fileName = "starry-api-backup-" + FILE_TIMESTAMP.format(now) + BACKUP_EXTENSION;
        String sql = buildSqlDump();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("application", "starry-api");
        metadata.put("database", databaseName());
        metadata.put("createdAt", now.toString());
        metadata.put("format", "starry-api-backup-v1");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            putZipEntry(zip, "database.sql", sql.getBytes(StandardCharsets.UTF_8));
            putZipEntry(zip, "metadata.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata));
            putZipEntry(zip, "README.txt", """
                Starry API backup package.

                database.sql contains the full SQL export and can be restored from the admin data-transfer page.
                Always keep a copy before importing it into another server.
                """.getBytes(StandardCharsets.UTF_8));
        }
        return new ExportFile(fileName, bytes.toByteArray());
    }

    private String buildSqlDump() throws SQLException {
        StringBuilder sql = new StringBuilder(1024 * 1024);
        sql.append("-- Starry API database export\n");
        sql.append("-- Database: ").append(databaseName()).append('\n');
        sql.append("-- Created at: ").append(Instant.now()).append("\n\n");
        sql.append("SET FOREIGN_KEY_CHECKS=0;\n");
        sql.append("SET SQL_MODE='NO_AUTO_VALUE_ON_ZERO';\n\n");

        try (Connection connection = openConnection()) {
            List<String> tables = listTables(connection);
            for (String table : tables) {
                appendTableDump(connection, table, sql);
            }
        }

        sql.append("SET FOREIGN_KEY_CHECKS=1;\n");
        return sql.toString();
    }

    private void appendTableDump(Connection connection, String table, StringBuilder sql) throws SQLException {
        sql.append("\n-- --------------------------------------------------------\n");
        sql.append("-- Table `").append(table).append("`\n\n");
        sql.append("DROP TABLE IF EXISTS ").append(quoteIdentifier(table)).append(";\n");
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW CREATE TABLE " + quoteIdentifier(table))) {
            if (resultSet.next()) {
                sql.append(resultSet.getString(2)).append(";\n\n");
            }
        }

        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rows = statement.executeQuery("SELECT * FROM " + quoteIdentifier(table))) {
                appendRows(table, rows, sql);
            }
        }
    }

    private void appendRows(String table, ResultSet rows, StringBuilder sql) throws SQLException {
        ResultSetMetaData metaData = rows.getMetaData();
        int columnCount = metaData.getColumnCount();
        StringBuilder columns = new StringBuilder();
        for (int index = 1; index <= columnCount; index++) {
            if (index > 1) {
                columns.append(", ");
            }
            columns.append(quoteIdentifier(metaData.getColumnName(index)));
        }

        while (rows.next()) {
            sql.append("INSERT INTO ")
                .append(quoteIdentifier(table))
                .append(" (")
                .append(columns)
                .append(") VALUES (");
            for (int index = 1; index <= columnCount; index++) {
                if (index > 1) {
                    sql.append(", ");
                }
                sql.append(toSqlLiteral(rows.getObject(index), metaData.getColumnType(index)));
            }
            sql.append(");\n");
        }
        sql.append('\n');
    }

    private int executeSql(String sql) throws SQLException {
        List<String> statements = splitStatements(sql);
        int executed = 0;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String sqlStatement : statements) {
                if (sqlStatement.isBlank()) {
                    continue;
                }
                statement.execute(sqlStatement);
                executed++;
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        return executed;
    }

    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int index = 0; index < sql.length(); index++) {
            char currentChar = sql.charAt(index);
            char nextChar = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (inLineComment) {
                if (currentChar == '\n' || currentChar == '\r') {
                    inLineComment = false;
                    current.append('\n');
                }
                continue;
            }

            if (inBlockComment) {
                if (currentChar == '*' && nextChar == '/') {
                    inBlockComment = false;
                    index++;
                    current.append(' ');
                }
                continue;
            }

            if (!inSingleQuote && !inDoubleQuote && !inBacktick) {
                if (currentChar == '-' && nextChar == '-') {
                    inLineComment = true;
                    index++;
                    continue;
                }
                if (currentChar == '#') {
                    inLineComment = true;
                    continue;
                }
                if (currentChar == '/' && nextChar == '*') {
                    inBlockComment = true;
                    index++;
                    continue;
                }
                if (currentChar == ';') {
                    addStatement(statements, current);
                    continue;
                }
                if (currentChar == '\'') {
                    inSingleQuote = true;
                } else if (currentChar == '"') {
                    inDoubleQuote = true;
                } else if (currentChar == '`') {
                    inBacktick = true;
                }
                current.append(currentChar);
                continue;
            }

            current.append(currentChar);
            if ((inSingleQuote || inDoubleQuote) && currentChar == '\\' && nextChar != '\0') {
                current.append(nextChar);
                index++;
                continue;
            }
            if (inSingleQuote && currentChar == '\'') {
                inSingleQuote = false;
            } else if (inDoubleQuote && currentChar == '"') {
                inDoubleQuote = false;
            } else if (inBacktick && currentChar == '`') {
                inBacktick = false;
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    private List<String> listTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW FULL TABLES WHERE Table_type = 'BASE TABLE'")) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        tables.sort(String::compareToIgnoreCase);
        return tables;
    }

    private String toSqlLiteral(Object value, int sqlType) throws SQLException {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes || isBinaryType(sqlType)) {
            byte[] binary = value instanceof byte[] bytesValue ? bytesValue : value.toString().getBytes(StandardCharsets.UTF_8);
            return "X'" + bytesToHex(binary) + "'";
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof Number number && !(number instanceof BigDecimal && requiresQuotedDecimal(sqlType))) {
            return number.toString();
        }
        return quoteString(value.toString());
    }

    private boolean requiresQuotedDecimal(int sqlType) {
        return sqlType == Types.VARCHAR || sqlType == Types.CHAR || sqlType == Types.LONGVARCHAR;
    }

    private boolean isBinaryType(int sqlType) {
        return sqlType == Types.BINARY
            || sqlType == Types.VARBINARY
            || sqlType == Types.LONGVARBINARY
            || sqlType == Types.BLOB;
    }

    private String quoteString(String value) {
        return "'" + value
            .replace("\\", "\\\\")
            .replace("\0", "\\0")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\b", "\\b")
            .replace("\t", "\\t")
            .replace("\u001A", "\\Z")
            .replace("'", "\\'") + "'";
    }

    private String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private String extractSqlFromZip(byte[] zipBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!"database.sql".equals(name) && !name.endsWith(SQL_EXTENSION)) {
                    continue;
                }
                ByteArrayOutputStream sql = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    total += read;
                    if (total > maxImportBytes) {
                        throw new ApiException(
                            HttpStatus.PAYLOAD_TOO_LARGE,
                            "DATA_IMPORT_FILE_TOO_LARGE",
                            "SQL inside the backup exceeds the configured limit"
                        );
                    }
                    sql.write(buffer, 0, read);
                }
                return sql.toString(StandardCharsets.UTF_8);
            }
        }
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "DATA_IMPORT_SQL_NOT_FOUND",
            "Backup package does not contain database.sql"
        );
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
                "Could not read backup file metadata"
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
        if (!path.startsWith(backupDir) || !Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DATA_BACKUP_NOT_FOUND", "Backup file not found");
        }
        return path;
    }

    private Path ensureBackupDir() throws IOException {
        Files.createDirectories(backupDir);
        return backupDir;
    }

    private String sanitizeBackupName(String fileName) {
        String name = sanitizeUploadName(fileName);
        if (!name.endsWith(BACKUP_EXTENSION)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_BACKUP_INVALID_NAME", "Invalid backup file name");
        }
        return name;
    }

    private String sanitizeUploadName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "upload" : Paths.get(fileName).getFileName().toString();
        if (name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_FILE_INVALID_NAME", "Invalid file name");
        }
        return name;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private String databaseName() {
        int marker = jdbcUrl.indexOf("://");
        int pathStart = marker >= 0 ? jdbcUrl.indexOf('/', marker + 3) : -1;
        if (pathStart < 0 || pathStart + 1 >= jdbcUrl.length()) {
            return "";
        }
        int queryStart = jdbcUrl.indexOf('?', pathStart);
        String database = queryStart >= 0 ? jdbcUrl.substring(pathStart + 1, queryStart) : jdbcUrl.substring(pathStart + 1);
        int semicolon = database.indexOf(';');
        return semicolon >= 0 ? database.substring(0, semicolon) : database;
    }

    private void deleteTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    public record ExportFile(String fileName, byte[] bytes) {
    }
}
