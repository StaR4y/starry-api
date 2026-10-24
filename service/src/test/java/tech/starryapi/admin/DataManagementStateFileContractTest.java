package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.cli.AdminCli;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code starry-api} command reads the data-management state file that {@link DataManagementSecurityService} writes.
 * These tests let the real service write it (initialized, locked after five wrong keys, state removed) and read it back
 * through the command, so a change of the file format or of its validation breaks the build instead of the command.
 */
class DataManagementStateFileContractTest {

    @TempDir
    Path tempDir;

    private Path stateFile;
    private AdminUserEntity root;
    private DataManagementSecurityService service;

    @BeforeEach
    void startService() {
        stateFile = tempDir.resolve("security").resolve("data-management.json");
        AdminUserRepository repository = mock(AdminUserRepository.class);
        root = new AdminUserEntity();
        root.setId(1L);
        root.setUserId("root-1");
        root.setUsername("admin");
        root.setRole("ADMIN");
        root.setEnabled(true);
        root.setFirstLogin(false);
        root.setPasswordHash("password-hash");
        root.setPasswordSalt("password-salt");
        root.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findByUserId(root.getUserId())).thenReturn(Mono.just(root));
        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));
        service = new DataManagementSecurityService(
            repository,
            new ObjectMapper(),
            List.of(),
            stateFile,
            null,
            2_000,
            Duration.ofMinutes(5),
            Clock.systemUTC(),
            false
        );
    }

    @Test
    void theCommandSeesWhatTheServiceWroteAtEachStage() throws Exception {
        assertThat(status().get("data_management")).isEqualTo("not_initialized");

        String securityKey = service.initialize(principal(), exchange()).block().securityKey();

        Map<String, String> initialized = status();
        assertThat(initialized)
            .containsEntry("data_management", "initialized")
            .containsEntry("data_management_failed_attempts", "0")
            .doesNotContainKeys("data_management_error", "data_management_locked_at");
        assertThat(Instant.parse(initialized.get("data_management_initialized_at")))
            .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

        for (int attempt = 1; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            wrongKey("wrong-" + attempt);
        }
        assertThat(status())
            .containsEntry("data_management", "initialized")
            .containsEntry("data_management_failed_attempts", String.valueOf(DataManagementSecurityService.MAX_FAILED_ATTEMPTS - 1));

        wrongKey("wrong-last");
        Map<String, String> locked = status();
        assertThat(locked)
            .containsEntry("data_management", "locked")
            .containsEntry("data_management_failed_attempts", String.valueOf(DataManagementSecurityService.MAX_FAILED_ATTEMPTS));
        assertThat(Instant.parse(locked.get("data_management_locked_at")))
            .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

        Files.delete(stateFile);
        assertThat(status())
            .as("the marker stays when the state file disappears: initialized before, state lost")
            .containsEntry("data_management", "error")
            .containsEntry("data_management_error", "state_missing");

        assertThat(securityKey).startsWith("dmsk-");
    }

    @Test
    void nothingSecretFromTheStateFileOrTheKeyReachesTheOutput() throws Exception {
        String securityKey = service.initialize(principal(), exchange()).block().securityKey();
        JsonNode state = new ObjectMapper().readTree(Files.readAllBytes(stateFile));
        String salt = state.path("salt").stringValue();
        String verifier = state.path("verifier").stringValue();
        assertThat(salt).isNotBlank();
        assertThat(verifier).isNotBlank();
        wrongKey("wrong");

        String output = run("status");

        assertThat(output).doesNotContain(securityKey).doesNotContain(salt).doesNotContain(verifier);
        assertThat(output).doesNotContain("dmsk-").doesNotContain("salt").doesNotContain("verifier");
    }

    @Test
    void theReaderAndTheServiceAgreeOnTheFormatConstants() throws Exception {
        Class<?> reader = Class.forName("tech.starryapi.cli.DataManagementStateReader");

        for (String name : List.of(
            "STATE_ALGORITHM", "KEY_LENGTH_BITS", "SALT_BYTES", "MAX_STATE_BYTES", "INITIALIZATION_MARKER", "MAX_MARKER_BYTES"
        )) {
            assertThat(constant(reader, name)).as(name).isEqualTo(constant(DataManagementSecurityService.class, name));
        }
        assertThat(constant(reader, "MAX_FAILED_ATTEMPTS")).isEqualTo(DataManagementSecurityService.MAX_FAILED_ATTEMPTS);
    }

    /** The persisted record has these fields; the reader depends on every one of them. */
    @Test
    void theServicesStateRecordStillHasTheFieldsTheReaderReads() throws Exception {
        Class<?> record = Class.forName("tech.starryapi.admin.DataManagementSecurityService$PersistedState");

        assertThat(record.getRecordComponents()).extracting("name").contains(
            "version", "algorithm", "iterations", "keyLengthBits", "salt", "verifier",
            "failedAttempts", "locked", "initializedAt", "lockedAt", "updatedAt"
        );
    }

    private static Object constant(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private void wrongKey(String key) {
        try {
            service.unlock(principal(), exchange(), key, "EXPORT_DATABASE", null).block();
        } catch (RuntimeException expected) {
            // A wrong key is rejected; the failure counter is what this test is after.
        }
    }

    /** {@code status} against a database that is not there: the data-management lines only depend on the file. */
    private Map<String, String> status() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : run("status").split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return values;
    }

    private String run(String command) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("POSTGRES_HOST", "127.0.0.1");
        environment.put("POSTGRES_PORT", "1");
        environment.put("STARRY_DATA_MANAGEMENT_SECURITY_FILE", stateFile.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = AdminCli.run(
            new String[] {command},
            environment,
            InputStream.nullInputStream(),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertThat(exit).isZero();
        return out.toString(StandardCharsets.UTF_8);
    }

    private AdminPrincipal principal() {
        return new AdminPrincipal(root.getUserId(), root.getUsername(), root.getRole(), Instant.now().plusSeconds(3600), false);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest
            .get("/admin/data-transfer/security/unlock")
            .header(HttpHeaders.AUTHORIZATION, "Bearer adm-root-session")
            .header(HttpHeaders.USER_AGENT, "contract-test")
            .remoteAddress(new InetSocketAddress("127.0.0.1", 443)));
    }
}
