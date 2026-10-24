package tech.starryapi.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.starryapi.cli.DataManagementStateReader.Problem;
import tech.starryapi.cli.DataManagementStateReader.State;
import tech.starryapi.cli.DataManagementStateReader.Status;

class DataManagementStateReaderTest {

    private static final String SALT = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String VERIFIER = Base64.getEncoder().encodeToString(new byte[32]);

    @TempDir
    Path directory;

    private Path stateFile() {
        return directory.resolve("security").resolve("data-management.json");
    }

    private Path markerFile() {
        return stateFile().resolveSibling("data-management.json.initialized");
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void writeMarker() throws Exception {
        write(markerFile(), DataManagementStateReader.INITIALIZATION_MARKER);
    }

    @Test
    void neitherStateNorMarkerMeansNotInitialized() {
        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.state()).isEqualTo(State.NOT_INITIALIZED);
        assertThat(status.problem()).isNull();
        assertThat(status.initializedAt()).isNull();
        assertThat(status.failedAttempts()).isNull();
    }

    @Test
    void aMarkerWithoutAStateFileIsAnErrorStateNotAFreshInstall() throws Exception {
        writeMarker();

        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.state()).isEqualTo(State.ERROR);
        assertThat(status.problem()).isEqualTo(Problem.STATE_MISSING);
    }

    @Test
    void aMissingStateFileIsAnErrorWhenInitializationIsRequired() {
        Status status = DataManagementStateReader.read(stateFile(), true);

        assertThat(status.state()).isEqualTo(State.ERROR);
        assertThat(status.problem()).isEqualTo(Problem.STATE_MISSING);
    }

    @Test
    void anInitializedStateReportsWhenAndHowManyFailures() throws Exception {
        write(stateFile(), state(2, false, "null"));
        writeMarker();

        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.state()).isEqualTo(State.INITIALIZED);
        assertThat(status.initializedAt()).isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
        assertThat(status.failedAttempts()).isEqualTo(2);
        assertThat(status.lockedAt()).isNull();
        assertThat(status.problem()).isNull();
    }

    @Test
    void aLockedStateReportsTheLockTime() throws Exception {
        write(stateFile(), state(5, true, "\"2026-09-03T10:30:00.123Z\""));

        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.state()).isEqualTo(State.LOCKED);
        assertThat(status.failedAttempts()).isEqualTo(DataManagementStateReader.MAX_FAILED_ATTEMPTS);
        assertThat(status.lockedAt()).isEqualTo(Instant.parse("2026-09-03T10:30:00.123Z"));
    }

    @Test
    void numericInstantsAreAcceptedToo() throws Exception {
        write(stateFile(), state(0, false, "null").replace("\"2026-09-01T08:00:00Z\"", "1788249600.5"));

        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.state()).isEqualTo(State.INITIALIZED);
        assertThat(status.initializedAt()).isEqualTo(Instant.ofEpochSecond(1788249600L, 500_000_000));
    }

    @Test
    void aLockFlagThatContradictsTheFailureCountIsInvalid() throws Exception {
        write(stateFile(), state(5, false, "null"));
        assertThat(DataManagementStateReader.read(stateFile(), false).problem()).isEqualTo(Problem.STATE_INVALID);

        write(stateFile(), state(1, true, "\"2026-09-03T10:30:00Z\""));
        assertThat(DataManagementStateReader.read(stateFile(), false).problem()).isEqualTo(Problem.STATE_INVALID);

        write(stateFile(), state(6, true, "\"2026-09-03T10:30:00Z\""));
        assertThat(DataManagementStateReader.read(stateFile(), false).problem()).isEqualTo(Problem.STATE_INVALID);
    }

    @Test
    void malformedStateFilesAreInvalidAndNeverThrow() throws Exception {
        for (String content : new String[] {
            "not json",
            "[]",
            "{}",
            "\"text\"",
            state(0, false, "null").replace("PBKDF2WithHmacSHA256", "MD5"),
            state(0, false, "null").replace("\"version\" : 2", "\"version\" : 9"),
            state(0, false, "null").replace(SALT, "not base64!"),
            state(0, false, "null").replace(VERIFIER, Base64.getEncoder().encodeToString(new byte[8])),
            state(0, false, "null").replace("\"initializedAt\" : \"2026-09-01T08:00:00Z\"", "\"initializedAt\" : null"),
            state(0, false, "null").replace("\"keyLengthBits\" : 256", "\"keyLengthBits\" : 128"),
            state(0, false, "null").replace("\"failedAttempts\" : 0", "\"failedAttempts\" : \"zero\""),
        }) {
            write(stateFile(), content);

            Status status = DataManagementStateReader.read(stateFile(), false);

            assertThat(status.state()).as(content).isEqualTo(State.ERROR);
            assertThat(status.problem()).as(content).isEqualTo(Problem.STATE_INVALID);
        }
    }

    @Test
    void anEmptyOrOversizedStateFileIsInvalid() throws Exception {
        write(stateFile(), "");
        assertThat(DataManagementStateReader.read(stateFile(), false).problem()).isEqualTo(Problem.STATE_INVALID);

        write(stateFile(), " ".repeat(DataManagementStateReader.MAX_STATE_BYTES + 1));
        assertThat(DataManagementStateReader.read(stateFile(), false).problem()).isEqualTo(Problem.STATE_INVALID);
    }

    @Test
    void aSymlinkedStateFileIsNotFollowed() throws Exception {
        Path real = directory.resolve("elsewhere.json");
        Files.writeString(real, state(0, false, "null"));
        Files.createDirectories(stateFile().getParent());
        Files.createSymbolicLink(stateFile(), real);

        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.state()).isEqualTo(State.ERROR);
        assertThat(status.problem()).isEqualTo(Problem.STATE_INVALID);
    }

    @Test
    void aTamperedMarkerMakesTheStateInvalid() throws Exception {
        write(stateFile(), state(0, false, "null"));
        write(markerFile(), "something else\n");

        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.state()).isEqualTo(State.ERROR);
        assertThat(status.problem()).isEqualTo(Problem.STATE_INVALID);
    }

    @Test
    void aStateFileThatIsADirectoryIsInvalid() throws Exception {
        Files.createDirectories(stateFile());

        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.problem()).isEqualTo(Problem.STATE_INVALID);
    }

    @Test
    void theStatusNeverCarriesTheVerifierOrTheSalt() throws Exception {
        write(stateFile(), state(0, false, "null"));

        Status status = DataManagementStateReader.read(stateFile(), false);

        assertThat(status.toString()).doesNotContain(SALT).doesNotContain(VERIFIER);
        assertThat(Status.class.getRecordComponents()).extracting("name")
            .containsExactly("state", "problem", "initializedAt", "failedAttempts", "lockedAt");
    }

    private static String state(int failedAttempts, boolean locked, String lockedAt) {
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
            """.formatted(SALT, VERIFIER, failedAttempts, locked, lockedAt);
    }
}
