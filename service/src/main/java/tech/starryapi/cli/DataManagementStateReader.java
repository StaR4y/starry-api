package tech.starryapi.cli;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the status fields of the data-management security state file without touching anything else in it.
 *
 * <p>The file is written by {@code tech.starryapi.admin.DataManagementSecurityService}. Next to the status fields it
 * holds a salted PBKDF2 verifier of the security key; this reader checks that the verifier and the salt are well formed,
 * as the service does, but never returns, prints or logs either of them. It takes no lock and writes nothing, so it can
 * run next to the service without competing for the state lock (the service replaces the file atomically).
 *
 * <p>{@code DataManagementStateFileContractTest} runs it against files written by the real service, so a change of the
 * file format cannot silently break the {@code starry-api} command.
 */
final class DataManagementStateReader {

    /** Same values as {@code DataManagementSecurityService}; the contract test compares them. */
    static final String STATE_ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final int KEY_LENGTH_BITS = 256;
    static final int SALT_BYTES = 32;
    static final int MAX_STATE_BYTES = 64 * 1024;
    static final String INITIALIZATION_MARKER = "starry-data-management-initialized-v1\n";
    static final int MAX_MARKER_BYTES = 128;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    enum State { NOT_INITIALIZED, INITIALIZED, LOCKED, ERROR }

    /** Why the state cannot be trusted. {@code STATE_MISSING} is "initialized before, but the file is gone". */
    enum Problem { STATE_MISSING, STATE_UNREADABLE, STATE_INVALID }

    record Status(
        State state,
        Problem problem,
        Instant initializedAt,
        Integer failedAttempts,
        Instant lockedAt
    ) {

        static Status notInitialized() {
            return new Status(State.NOT_INITIALIZED, null, null, null, null);
        }

        static Status error(Problem problem) {
            return new Status(State.ERROR, problem, null, null, null);
        }
    }

    private DataManagementStateReader() {
    }

    /**
     * @param stateFile the configured state file ({@code STARRY_DATA_MANAGEMENT_SECURITY_FILE})
     * @param requireInitialized the service's {@code STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED}: a missing file is then
     *     an error even when no initialization marker exists
     */
    static Status read(Path stateFile, boolean requireInitialized) {
        Path file = stateFile.toAbsolutePath().normalize();
        Path marker = file.resolveSibling(file.getFileName() + ".initialized");
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (markerExists(marker) || requireInitialized) {
                    return Status.error(Problem.STATE_MISSING);
                }
                return Status.notInitialized();
            }
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Status.error(Problem.STATE_INVALID);
            }
            long size = Files.size(file);
            if (size <= 0 || size > MAX_STATE_BYTES) {
                return Status.error(Problem.STATE_INVALID);
            }
            Status status = parse(Files.readAllBytes(file));
            if (status.state() != State.ERROR && markerExists(marker) && !markerIsValid(marker)) {
                // The service refuses a state whose marker was tampered with, so it is not "initialized".
                return Status.error(Problem.STATE_INVALID);
            }
            return status;
        } catch (java.io.IOException | SecurityException exception) {
            return Status.error(Problem.STATE_UNREADABLE);
        }
    }

    private static boolean markerExists(Path marker) {
        return Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean markerIsValid(Path marker) throws java.io.IOException {
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        long size = Files.size(marker);
        return size > 0
            && size <= MAX_MARKER_BYTES
            && INITIALIZATION_MARKER.equals(Files.readString(marker, java.nio.charset.StandardCharsets.US_ASCII));
    }

    static Status parse(byte[] content) {
        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (RuntimeException exception) {
            return Status.error(Problem.STATE_INVALID);
        }
        if (root == null || !root.isObject()) {
            return Status.error(Problem.STATE_INVALID);
        }
        int version = root.path("version").asInt(-1);
        int iterations = root.path("iterations").asInt(-1);
        int keyLengthBits = root.path("keyLengthBits").asInt(-1);
        JsonNode attemptsNode = root.path("failedAttempts");
        JsonNode lockedNode = root.path("locked");
        if ((version != 1 && version != 2)
            || !STATE_ALGORITHM.equals(root.path("algorithm").asString(""))
            || iterations <= 0
            || keyLengthBits != KEY_LENGTH_BITS
            || !attemptsNode.isIntegralNumber()
            || !lockedNode.isBoolean()
            || !encodedLengthIs(root.path("salt"), SALT_BYTES)
            || !encodedLengthIs(root.path("verifier"), KEY_LENGTH_BITS / Byte.SIZE)) {
            return Status.error(Problem.STATE_INVALID);
        }
        int failedAttempts = attemptsNode.asInt(-1);
        boolean locked = lockedNode.booleanValue();
        Instant initializedAt = instant(root.path("initializedAt"));
        Instant updatedAt = instant(root.path("updatedAt"));
        Instant lockedAt = instant(root.path("lockedAt"));
        if (failedAttempts < 0
            || failedAttempts > MAX_FAILED_ATTEMPTS
            || locked != (failedAttempts >= MAX_FAILED_ATTEMPTS)
            || initializedAt == null
            || updatedAt == null) {
            return Status.error(Problem.STATE_INVALID);
        }
        return new Status(
            locked ? State.LOCKED : State.INITIALIZED,
            null,
            initializedAt,
            failedAttempts,
            locked ? lockedAt : null
        );
    }

    private static boolean encodedLengthIs(JsonNode node, int expectedBytes) {
        if (!node.isString()) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(node.stringValue()).length == expectedBytes;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** ISO-8601 text (what the service writes) or epoch seconds; {@code null} when absent or unreadable. */
    private static Instant instant(JsonNode node) {
        try {
            if (node.isString()) {
                return Instant.parse(node.stringValue());
            }
            if (node.isNumber()) {
                BigDecimal seconds = node.decimalValue();
                long whole = seconds.setScale(0, RoundingMode.FLOOR).longValueExact();
                long nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue();
                return Instant.ofEpochSecond(whole, nanos);
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return null;
    }
}
