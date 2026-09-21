package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

class DataManagementSecurityServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void ordinaryAdminCannotReadDataManagementState() {
        TestContext context = initializedContext();
        assertApiError(() -> context.service().requireDatabaseAdmin(context.adminPrincipal()).block(),
            "DATA_MANAGEMENT_ROOT_REQUIRED");
        assertApiError(() -> context.service().status(context.adminPrincipal()).block(),
            "DATA_MANAGEMENT_ROOT_REQUIRED");
        assertThat(context.service().status(context.rootPrincipal()).block().failedAttempts()).isZero();
    }

    @ParameterizedTest
    @EnumSource(DataManagementSecurityService.Action.class)
    void everyDataOperationRejectsOrdinaryAdminEvenWithTheKeyOrARootGrant(DataManagementSecurityService.Action action) {
        TestContext context = initializedContext();
        String target = action == DataManagementSecurityService.Action.DOWNLOAD_BACKUP
            || action == DataManagementSecurityService.Action.DELETE_BACKUP ? "backup.zip" : null;
        var grant = context.service().unlock(context.rootPrincipal(), context.rootExchange(),
            context.securityKey(), action.name(), target).block();
        assertThat(grant).isNotNull();

        assertApiError(() -> context.service().unlock(context.adminPrincipal(), context.adminExchange(),
            context.securityKey(), action.name(), target).block(), "DATA_MANAGEMENT_ROOT_REQUIRED");
        assertApiError(() -> context.service().authorize(context.adminPrincipal(), context.adminExchange(),
            grant.accessToken(), action, target, false).block(), "DATA_MANAGEMENT_ROOT_REQUIRED");
        context.service().authorize(context.rootPrincipal(), context.rootExchange(),
            grant.accessToken(), action, target, false).block();
        assertThat(context.service().status(context.rootPrincipal()).block().failedAttempts()).isZero();
    }

    @Test
    void onlyInitialApplicationRootCanInitializeAndKeyIsReturnedOnce() throws Exception {
        TestContext context = context(null, List.of());

        assertApiError(
            () -> context.service().initialize(context.adminPrincipal(), context.adminExchange()).block(),
            "DATA_MANAGEMENT_ROOT_REQUIRED"
        );

        DataTransferDtos.InitializeDataManagementSecurityResponse initialized = context.service()
            .initialize(context.rootPrincipal(), context.rootExchange())
            .block();

        assertThat(initialized).isNotNull();
        assertThat(initialized.securityKey()).matches("dmsk-[0-9a-f]{64}");
        assertThat(initialized.status().initialized()).isTrue();
        String stateJson = Files.readString(context.stateFile());
        assertThat(stateJson)
            .contains("PBKDF2WithHmacSHA256", "\"salt\"", "\"verifier\"")
            .doesNotContain(initialized.securityKey());
        assertThat(context.stateFile().resolveSibling(
            context.stateFile().getFileName() + ".initialized"
        )).hasContent("starry-data-management-initialized-v1\n");
        if (Files.getFileStore(context.stateFile()).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(context.stateFile()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        assertApiError(
            () -> context.service().initialize(context.rootPrincipal(), context.rootExchange()).block(),
            "DATA_MANAGEMENT_SECURITY_ALREADY_INITIALIZED"
        );
        verify(context.repository(), times(3)).findApplicationRoot();
        verify(context.repository(), never()).findAll();
        verify(context.repository(), never()).findByUsername("admin");
    }

    @Test
    void grantIsBoundToDatabaseAdminSessionActionAndTarget() {
        TestContext context = initializedContext();
        DataTransferDtos.UnlockDataManagementResponse first = context.service().unlock(
            context.rootPrincipal(),
            context.rootExchange(),
            context.securityKey(),
            "DOWNLOAD_BACKUP",
            "backup-a.zip"
        ).block();

        assertThat(first).isNotNull();
        assertThat(first.action()).isEqualTo("DOWNLOAD_BACKUP");
        assertThat(first.target()).isEqualTo("backup-a.zip");
        context.service().authorize(
            context.rootPrincipal(),
            context.rootExchange(),
            first.accessToken(),
            DataManagementSecurityService.Action.DOWNLOAD_BACKUP,
            "backup-a.zip",
            false
        ).block();
        context.service().authorize(
            context.rootPrincipal(),
            context.rootExchange(),
            first.accessToken(),
            DataManagementSecurityService.Action.DOWNLOAD_BACKUP,
            "backup-a.zip",
            false
        ).block();

        assertUnlockRequired(() -> context.service().authorize(
            context.rootPrincipal(),
            exchange("adm-other-session"),
            first.accessToken(),
            DataManagementSecurityService.Action.DOWNLOAD_BACKUP,
            "backup-a.zip",
            false
        ).block());
        assertUnlockRequired(() -> context.service().authorize(
            context.rootPrincipal(),
            context.rootExchange(),
            first.accessToken(),
            DataManagementSecurityService.Action.DOWNLOAD_BACKUP,
            "backup-b.zip",
            false
        ).block());
        assertUnlockRequired(() -> context.service().authorize(
            context.rootPrincipal(),
            context.rootExchange(),
            first.accessToken(),
            DataManagementSecurityService.Action.EXPORT_DATABASE,
            null,
            false
        ).block());
    }

    @Test
    void issuingSameBindingReplacesOldGrantAndDestructiveGrantIsSingleUse() {
        TestContext context = initializedContext();
        DataTransferDtos.UnlockDataManagementResponse first = context.service().unlock(
            context.rootPrincipal(),
            context.rootExchange(),
            context.securityKey(),
            "DELETE_BACKUP",
            "backup.zip"
        ).block();
        DataTransferDtos.UnlockDataManagementResponse replacement = context.service().unlock(
            context.rootPrincipal(),
            context.rootExchange(),
            context.securityKey(),
            "DELETE_BACKUP",
            "backup.zip"
        ).block();

        assertThat(first).isNotNull();
        assertThat(replacement).isNotNull();
        assertThat(replacement.accessToken()).isNotEqualTo(first.accessToken());
        assertUnlockRequired(() -> authorizeDelete(context, first.accessToken()));

        authorizeDelete(context, replacement.accessToken());
        assertUnlockRequired(() -> authorizeDelete(context, replacement.accessToken()));
    }

    @Test
    void expiredGrantCannotBeUsed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        TestContext context = initializedContext(clock, null, List.of());
        DataTransferDtos.UnlockDataManagementResponse grant = context.service().unlock(
            context.rootPrincipal(),
            context.rootExchange(),
            context.securityKey(),
            "EXPORT_DATABASE",
            null
        ).block();

        clock.advance(Duration.ofMinutes(5));

        assertThat(grant).isNotNull();
        assertUnlockRequired(() -> context.service().authorize(
            context.rootPrincipal(),
            context.rootExchange(),
            grant.accessToken(),
            DataManagementSecurityService.Action.EXPORT_DATABASE,
            null,
            false
        ).block());
    }

    @Test
    void successfulUnlockClearsGlobalConsecutiveFailureCount() {
        TestContext context = initializedContext();
        failUnlock(context, "wrong-1");
        failUnlock(context, "wrong-2");
        assertThat(context.service().status(context.rootPrincipal()).block().failedAttempts()).isEqualTo(2);

        DataTransferDtos.UnlockDataManagementResponse unlocked = context.service().unlock(
            context.rootPrincipal(),
            context.rootExchange(),
            context.securityKey(),
            "CREATE_BACKUP",
            null
        ).block();

        assertThat(unlocked).isNotNull();
        assertThat(context.service().status(context.rootPrincipal()).block().failedAttempts()).isZero();
    }

    @Test
    void fifthFailureLocksImmediatelyAndReplaysPendingIncidentsAfterHandlerRecovery() throws Exception {
        List<DataManagementSecurityIncident> incidents = new ArrayList<>();
        AtomicBoolean handlerAvailable = new AtomicBoolean();
        DataManagementSecurityIncidentHandler handler = incident -> {
            if (!handlerAvailable.get()) {
                return Mono.error(new IllegalStateException("audit unavailable"));
            }
            incidents.add(incident);
            return Mono.empty();
        };
        TestContext context = initializedContext(Clock.systemUTC(), null, List.of(handler));

        for (int attempt = 1; attempt <= DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(context, "wrong-" + attempt);
        }

        DataTransferDtos.DataManagementSecurityStatusResponse status = context.service()
            .status(context.rootPrincipal())
            .block();
        assertThat(status).isNotNull();
        assertThat(status.locked()).isTrue();
        assertThat(status.failedAttempts()).isEqualTo(DataManagementSecurityService.MAX_FAILED_ATTEMPTS);
        assertThat(context.service().isLocked().block()).isTrue();
        assertThat(incidents).isEmpty();
        assertThat(new ObjectMapper().readTree(Files.readAllBytes(context.stateFile()))
            .path("pendingIncidents")).hasSize(DataManagementSecurityService.MAX_FAILED_ATTEMPTS);
        assertThat(Files.readString(context.stateFile()))
            .contains("admin@example.com")
            .doesNotContain("wrong-1", "password-hash-secret", "password-salt-secret");

        assertThatThrownBy(() -> context.service().drainPendingIncidents().block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("audit unavailable");
        assertThat(context.service().isLocked().block()).isTrue();

        handlerAvailable.set(true);
        context.service().drainPendingIncidents().block();

        assertThat(incidents).hasSize(DataManagementSecurityService.MAX_FAILED_ATTEMPTS);
        DataManagementSecurityIncident locked = incidents.get(incidents.size() - 1);
        assertThat(locked.type()).isEqualTo(DataManagementSecurityIncident.Type.SECURITY_LOCKED);
        assertThat(locked.locked()).isTrue();
        assertThat(locked.sessionFingerprint()).hasSize(64);
        assertThat(locked.endpoint()).isEqualTo("/admin/data-transfer/security/unlock");
        assertThat(new ObjectMapper().readTree(Files.readAllBytes(context.stateFile()))
            .path("pendingIncidents")).isEmpty();
        assertApiError(
            () -> context.service().unlock(
                context.rootPrincipal(),
                context.rootExchange(),
                context.securityKey(),
                "EXPORT_BACKUP",
                null
            ).block(),
            "DATA_MANAGEMENT_SECURITY_LOCKED"
        );
    }

    @Test
    void startupRecoveryConsumesFileAndCannotAutomaticallyRecoverFutureLock() throws Exception {
        List<DataManagementSecurityIncident> incidents = new ArrayList<>();
        DataManagementSecurityIncidentHandler capture = incident -> {
            incidents.add(incident);
            return Mono.empty();
        };
        Path recoveryFile = tempDir.resolve("recovery.key");
        TestContext lockedContext = initializedContext(Clock.systemUTC(), null, List.of());
        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(lockedContext, "wrong-recovery-" + attempt);
        }
        Files.writeString(recoveryFile, lockedContext.securityKey() + "\n");
        if (Files.getFileStore(recoveryFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(recoveryFile, PosixFilePermissions.fromString("rw-------"));
        }

        DataManagementSecurityService recovered = service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of(capture)
        );
        recovered.initializeStateCacheAndRecover();
        recovered.drainPendingIncidents().block();

        DataTransferDtos.DataManagementSecurityStatusResponse status = recovered
            .status(lockedContext.rootPrincipal())
            .block();
        assertThat(status).isNotNull();
        assertThat(status.locked()).isFalse();
        assertThat(status.failedAttempts()).isZero();
        assertThat(recovered.isLocked().block()).isFalse();
        assertThat(recoveryFile).doesNotExist();
        assertThat(incidents).extracting(DataManagementSecurityIncident::type)
            .containsExactly(
                DataManagementSecurityIncident.Type.UNLOCK_FAILED,
                DataManagementSecurityIncident.Type.UNLOCK_FAILED,
                DataManagementSecurityIncident.Type.UNLOCK_FAILED,
                DataManagementSecurityIncident.Type.UNLOCK_FAILED,
                DataManagementSecurityIncident.Type.SECURITY_LOCKED,
                DataManagementSecurityIncident.Type.RECOVERY_SUCCEEDED
            );

        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            String invalidKey = "wrong-after-recovery-" + attempt;
            assertThatThrownBy(() -> recovered.unlock(
                lockedContext.rootPrincipal(),
                lockedContext.rootExchange(),
                invalidKey,
                "EXPORT_DATABASE",
                null
            ).block()).isInstanceOf(ApiException.class);
        }

        DataManagementSecurityService restarted = service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        restarted.initializeStateCacheAndRecover();

        assertThat(recoveryFile).doesNotExist();
        assertThat(restarted.status(lockedContext.rootPrincipal()).block().locked()).isTrue();
        assertThat(restarted.isLocked().block()).isTrue();
    }

    @Test
    void configuredRecoveryPathWithoutAFileLeavesTheHardLockUnchanged() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        TestContext lockedContext = initializedContext(Clock.systemUTC(), null, List.of());
        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(lockedContext, "wrong-missing-recovery-" + attempt);
        }
        String stateBeforeRestart = Files.readString(lockedContext.stateFile());

        DataManagementSecurityService restarted = service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        restarted.initializeStateCacheAndRecover();

        assertThat(restarted.isLocked().block()).isTrue();
        assertThat(recoveryFile).doesNotExist();
        assertThat(Files.readString(lockedContext.stateFile())).isEqualTo(stateBeforeRestart);
    }

    @Test
    void rejectedRecoveryKeyIsAuditedAndSecurelyConsumed() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        TestContext lockedContext = initializedContext(Clock.systemUTC(), null, List.of());
        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(lockedContext, "wrong-consumed-recovery-" + attempt);
        }
        String rejectedKey = "dmsk-" + "0".repeat(64);
        Files.writeString(recoveryFile, rejectedKey + "\n");
        if (Files.getFileStore(recoveryFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(recoveryFile, PosixFilePermissions.fromString("rw-------"));
        }

        DataManagementSecurityService restarted = service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        restarted.initializeStateCacheAndRecover();

        assertThat(restarted.isLocked().block()).isTrue();
        assertThat(recoveryFile).doesNotExist();
        assertThat(recoveryFile.resolveSibling(".recovery.key.consuming")).doesNotExist();
        assertThat(Files.readString(lockedContext.stateFile()))
            .contains("RECOVERY_FAILED", "Configured data management recovery key was rejected")
            .doesNotContain(rejectedKey);
    }

    @Test
    void startupSecurelyDiscardsARecoveryKeyWhenSecurityIsNotLocked() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        TestContext context = initializedContext(Clock.systemUTC(), null, List.of());
        Files.writeString(recoveryFile, context.securityKey() + "\n");
        if (Files.getFileStore(recoveryFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(recoveryFile, PosixFilePermissions.fromString("rw-------"));
        }

        DataManagementSecurityService restarted = service(
            context.repository(),
            context.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        restarted.initializeStateCacheAndRecover();

        assertThat(restarted.isLocked().block()).isFalse();
        assertThat(recoveryFile).doesNotExist();
        assertThat(recoveryFile.resolveSibling(".recovery.key.consuming")).doesNotExist();
    }

    @Test
    void failedRecoveryStateCommitRestoresTheOneTimeRecoveryFile() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        TestContext lockedContext = initializedContext(Clock.systemUTC(), null, List.of());
        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(lockedContext, "wrong-recovery-commit-" + attempt);
        }
        Files.writeString(recoveryFile, lockedContext.securityKey() + "\n");
        if (Files.getFileStore(recoveryFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(recoveryFile, PosixFilePermissions.fromString("rw-------"));
        }

        ObjectMapper failingMapper = spy(new ObjectMapper().findAndRegisterModules());
        ObjectWriter failingWriter = mock(ObjectWriter.class);
        when(failingMapper.writerWithDefaultPrettyPrinter()).thenReturn(failingWriter);
        when(failingWriter.writeValueAsBytes(any())).thenThrow(
            new IllegalStateException("forced recovery state write failure")
        );
        DataManagementSecurityService failedRecovery = new DataManagementSecurityService(
            lockedContext.repository(),
            failingMapper,
            List.of(),
            lockedContext.stateFile(),
            recoveryFile,
            2_000,
            Duration.ofMinutes(5),
            Clock.systemUTC(),
            false
        );

        failedRecovery.initializeStateCacheAndRecover();

        assertThat(failedRecovery.isLocked().block()).isTrue();
        assertThat(recoveryFile).hasContent(lockedContext.securityKey() + "\n");
        assertThat(Files.readString(lockedContext.stateFile())).contains(
            "\"failedAttempts\" : 5",
            "\"locked\" : true"
        );
        try (var files = Files.list(recoveryFile.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()))
                .noneMatch(name -> name.endsWith(".consuming"));
        }
    }

    @Test
    void startupResumesRecoveryInterruptedBeforeTheStateCommit() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        Path stagedRecoveryFile = recoveryFile.resolveSibling(".recovery.key.consuming");
        TestContext lockedContext = initializedContext(Clock.systemUTC(), null, List.of());
        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(lockedContext, "wrong-interrupted-recovery-" + attempt);
        }
        Files.writeString(recoveryFile, lockedContext.securityKey() + "\n");
        if (Files.getFileStore(recoveryFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(recoveryFile, PosixFilePermissions.fromString("rw-------"));
        }
        Files.move(recoveryFile, stagedRecoveryFile);

        DataManagementSecurityService recovered = service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        recovered.initializeStateCacheAndRecover();

        assertThat(recovered.isLocked().block()).isFalse();
        assertThat(recovered.status(lockedContext.rootPrincipal()).block().failedAttempts()).isZero();
        assertThat(recoveryFile).doesNotExist();
        assertThat(stagedRecoveryFile).doesNotExist();
    }

    @Test
    void startupRejectsARecoveryKeySymlinkWithoutTouchingItsTarget() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        Path sentinel = tempDir.resolve("recovery-symlink-target");
        TestContext lockedContext = initializedContext(Clock.systemUTC(), null, List.of());
        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(lockedContext, "wrong-symlink-recovery-" + attempt);
        }
        Files.writeString(sentinel, "must-remain-unchanged\n");
        Files.createSymbolicLink(recoveryFile, sentinel);

        DataManagementSecurityService restarted = service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        restarted.initializeStateCacheAndRecover();

        assertThat(restarted.isLocked().block()).isTrue();
        assertThat(recoveryFile).isSymbolicLink();
        assertThat(sentinel).hasContent("must-remain-unchanged\n");
        assertThat(Files.readString(lockedContext.stateFile())).contains(
            "RECOVERY_FAILED",
            "Configured data management recovery key file was rejected"
        );
    }

    @Test
    void stagedRecoverySymlinkKeepsAnUnlockedStateFailClosedAcrossRefresh() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        Path stagedRecoveryFile = recoveryFile.resolveSibling(".recovery.key.consuming");
        Path sentinel = tempDir.resolve("staged-recovery-symlink-target");
        TestContext context = initializedContext();
        Files.writeString(sentinel, "must-remain-unchanged\n");
        Files.createSymbolicLink(stagedRecoveryFile, sentinel);

        DataManagementSecurityService restarted = service(
            context.repository(),
            context.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        restarted.initializeStateCacheAndRecover();

        assertThat(restarted.isLocked().block()).isTrue();
        restarted.refreshLockSnapshot();
        assertThat(restarted.isLocked().block()).isTrue();
        assertThat(stagedRecoveryFile).isSymbolicLink();
        assertThat(sentinel).hasContent("must-remain-unchanged\n");
    }

    @Test
    void startupRejectsRecoveryFileWithNonOwnerPermissions() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        TestContext lockedContext = initializedContext(Clock.systemUTC(), null, List.of());
        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(lockedContext, "wrong-permission-recovery-" + attempt);
        }
        Files.writeString(recoveryFile, lockedContext.securityKey() + "\n");
        assumeTrue(Files.getFileStore(recoveryFile).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(recoveryFile, PosixFilePermissions.fromString("rw-r-----"));

        DataManagementSecurityService restarted = service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        restarted.initializeStateCacheAndRecover();

        assertThat(restarted.isLocked().block()).isTrue();
        assertThat(recoveryFile).hasContent(lockedContext.securityKey() + "\n");
        assertThat(Files.getPosixFilePermissions(recoveryFile))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readString(lockedContext.stateFile())).contains(
            "RECOVERY_FAILED",
            "Configured data management recovery key file was rejected"
        );
    }

    @Test
    void recoveryCleanupFailureRemainsFailClosedUntilRestartCleansTheResidualFile() throws Exception {
        Path recoveryFile = tempDir.resolve("recovery.key");
        Path stagedRecoveryFile = recoveryFile.resolveSibling(".recovery.key.consuming");
        TestContext lockedContext = initializedContext(Clock.systemUTC(), null, List.of());
        for (int attempt = 0; attempt < DataManagementSecurityService.MAX_FAILED_ATTEMPTS; attempt++) {
            failUnlock(lockedContext, "wrong-cleanup-recovery-" + attempt);
        }
        Files.writeString(recoveryFile, lockedContext.securityKey() + "\n");
        if (Files.getFileStore(recoveryFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(recoveryFile, PosixFilePermissions.fromString("rw-------"));
        }
        DataManagementSecurityService failedCleanup = spy(service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        ));
        doThrow(new IOException("forced staged recovery deletion failure"))
            .when(failedCleanup)
            .deleteStagedRecoveryKeyFile(any());

        failedCleanup.initializeStateCacheAndRecover();

        assertThat(Files.readString(lockedContext.stateFile())).contains(
            "\"failedAttempts\" : 0",
            "\"locked\" : false"
        );
        assertThat(failedCleanup.isLocked().block()).isTrue();
        assertThat(recoveryFile).doesNotExist();
        assertThat(stagedRecoveryFile).exists().isEmptyFile();
        failedCleanup.refreshLockSnapshot();
        assertThat(failedCleanup.isLocked().block()).isTrue();

        DataManagementSecurityService restarted = service(
            lockedContext.repository(),
            lockedContext.stateFile(),
            recoveryFile,
            Clock.systemUTC(),
            List.of()
        );
        restarted.initializeStateCacheAndRecover();

        assertThat(recoveryFile).doesNotExist();
        assertThat(stagedRecoveryFile).doesNotExist();
        assertThat(restarted.isLocked().block()).isFalse();
    }

    @Test
    void stateLockSymlinkIsRejectedWithoutChangingItsTarget() throws Exception {
        TestContext context = context(null, List.of());
        Files.createDirectories(context.stateFile().getParent());
        Path sentinel = tempDir.resolve("state-lock-symlink-target");
        Files.writeString(sentinel, "must-remain-unchanged\n");
        Path lockFile = context.stateFile().resolveSibling(context.stateFile().getFileName() + ".lock");
        Files.createSymbolicLink(lockFile, sentinel);

        assertApiError(
            () -> context.service().initialize(context.rootPrincipal(), context.rootExchange()).block(),
            "DATA_MANAGEMENT_SECURITY_STATE_UNAVAILABLE"
        );
        assertThat(lockFile).isSymbolicLink();
        assertThat(sentinel).hasContent("must-remain-unchanged\n");
    }

    @Test
    void envAdminCannotUseDataManagementSecurity() {
        TestContext context = context(null, List.of());
        AdminPrincipal envAdmin = new AdminPrincipal("env-admin", "env-admin", "ADMIN", null, false);

        assertApiError(
            () -> context.service().status(envAdmin).block(),
            "DATA_MANAGEMENT_DATABASE_ADMIN_REQUIRED"
        );
    }

    @Test
    void databaseRestoreGrantCanOnlyBeIssuedAndUsedByApplicationRoot() {
        TestContext context = initializedContext();

        assertApiError(
            () -> context.service().unlock(
                context.adminPrincipal(),
                context.adminExchange(),
                context.securityKey(),
                "RESTORE_DATABASE",
                null
            ).block(),
            "DATA_MANAGEMENT_ROOT_REQUIRED"
        );

        DataTransferDtos.UnlockDataManagementResponse grant = context.service().unlock(
            context.rootPrincipal(),
            context.rootExchange(),
            context.securityKey(),
            "RESTORE_DATABASE",
            null
        ).block();
        assertThat(grant).isNotNull();

        assertApiError(
            () -> context.service().authorize(
                context.adminPrincipal(),
                context.adminExchange(),
                grant.accessToken(),
                DataManagementSecurityService.Action.RESTORE_DATABASE,
                null,
                true
            ).block(),
            "DATA_MANAGEMENT_ROOT_REQUIRED"
        );

        context.service().authorize(
            context.rootPrincipal(),
            context.rootExchange(),
            grant.accessToken(),
            DataManagementSecurityService.Action.RESTORE_DATABASE,
            null,
            true
        ).block();
    }

    @Test
    void untrustedHeadersCannotEnterIncidentStateAsRawSecrets() throws Exception {
        List<DataManagementSecurityIncident> incidents = new ArrayList<>();
        TestContext context = initializedContext(Clock.systemUTC(), null, List.of(incident -> {
            incidents.add(incident);
            return Mono.empty();
        }));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
            .get("/admin/data-transfer/security/unlock")
            .header(HttpHeaders.AUTHORIZATION, "Bearer adm-admin-session")
            .header("X-Forwarded-For", "203.0.113.10, dmsk-forwarded-secret, 999.1.1.1")
            .header(HttpHeaders.USER_AGENT, "Bearer adm-raw-session dmsk-user-agent-secret")
            .header("X-Request-Id", "dmt-raw-data-management-token")
            .remoteAddress(new InetSocketAddress("127.0.0.1", 443)));

        assertThatThrownBy(() -> context.service().unlock(
            context.rootPrincipal(),
            exchange,
            "wrong-key-value",
            "EXPORT_DATABASE",
            null
        ).block()).isInstanceOf(ApiException.class);

        assertThat(Files.readString(context.stateFile()))
            .doesNotContain(
                "dmsk-forwarded-secret",
                "adm-raw-session",
                "dmsk-user-agent-secret",
                "dmt-raw-data-management-token",
                "wrong-key-value"
            );

        context.service().drainPendingIncidents().block();

        assertThat(incidents).hasSize(1);
        DataManagementSecurityIncident incident = incidents.get(0);
        assertThat(incident.forwardedFor()).isEqualTo("203.0.113.10");
        assertThat(incident.userAgent()).matches("sha256:[0-9a-f]{64}");
        assertThat(incident.requestId()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void defaultAdminIdentityStillRequiresActiveDatabaseAdminState() {
        for (String invalidState : List.of("role", "enabled", "firstLogin")) {
            TestContext context = context(null, List.of());
            switch (invalidState) {
                case "role" -> context.root().setRole("USER");
                case "enabled" -> context.root().setEnabled(false);
                case "firstLogin" -> context.root().setFirstLogin(true);
                default -> throw new IllegalStateException("Unexpected test state");
            }
            AdminPrincipal staleAdminSession = new AdminPrincipal(
                context.root().getUserId(),
                "admin",
                "ADMIN",
                Instant.now().plusSeconds(3600),
                false
            );

            assertApiError(
                () -> context.service().initialize(staleAdminSession, context.rootExchange()).block(),
                "DATA_MANAGEMENT_DATABASE_ADMIN_REQUIRED"
            );
            verify(context.repository(), never()).findApplicationRoot();
        }
    }

    @Test
    void corruptedInitializedStateFailsClosedWithoutPerRequestFileRead() throws Exception {
        TestContext context = initializedContext();
        assertThat(context.service().isLocked().block()).isFalse();
        Files.writeString(context.stateFile(), "{invalid-json");

        context.service().refreshLockSnapshot();

        assertThat(context.service().isLocked().block()).isTrue();
    }

    @Test
    void deletingInitializedStateCannotTurnRestartIntoFirstTimeInitialization() throws Exception {
        TestContext context = initializedContext();
        Files.delete(context.stateFile());
        DataManagementSecurityService restarted = service(
            context.repository(),
            context.stateFile(),
            null,
            Clock.systemUTC(),
            List.of()
        );

        restarted.initializeStateCacheAndRecover();

        assertThat(restarted.isLocked().block()).isTrue();
        assertApiError(
            () -> restarted.initialize(context.rootPrincipal(), context.rootExchange()).block(),
            "DATA_MANAGEMENT_SECURITY_STATE_UNAVAILABLE"
        );
    }

    @Test
    void deploymentGuardFailsClosedWhenTheEntireSecurityVolumeIsMissing() {
        TestContext context = context(null, List.of());
        DataManagementSecurityService guarded = service(
            context.repository(),
            context.stateFile(),
            null,
            Clock.systemUTC(),
            List.of(),
            true
        );

        guarded.initializeStateCacheAndRecover();

        assertThat(guarded.isLocked().block()).isTrue();
        assertApiError(
            () -> guarded.initialize(context.rootPrincipal(), context.rootExchange()).block(),
            "DATA_MANAGEMENT_SECURITY_STATE_UNAVAILABLE"
        );
    }

    private void authorizeDelete(TestContext context, String token) {
        context.service().authorize(
            context.rootPrincipal(),
            context.rootExchange(),
            token,
            DataManagementSecurityService.Action.DELETE_BACKUP,
            "backup.zip",
            true
        ).block();
    }

    private void failUnlock(TestContext context, String key) {
        assertThatThrownBy(() -> context.service().unlock(
            context.rootPrincipal(),
            context.rootExchange(),
            key,
            "EXPORT_DATABASE",
            null
        ).block()).isInstanceOf(ApiException.class);
    }

    private void assertUnlockRequired(Runnable operation) {
        assertApiError(operation, "DATA_MANAGEMENT_UNLOCK_REQUIRED");
    }

    private void assertApiError(Runnable operation, String expectedCode) {
        assertThatThrownBy(operation::run)
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo(expectedCode)
            );
    }

    private TestContext initializedContext() {
        return initializedContext(Clock.systemUTC(), null, List.of());
    }

    private TestContext initializedContext(
        Clock clock,
        Path recoveryFile,
        List<DataManagementSecurityIncidentHandler> handlers
    ) {
        TestContext context = context(clock, recoveryFile, handlers);
        DataTransferDtos.InitializeDataManagementSecurityResponse initialized = context.service()
            .initialize(context.rootPrincipal(), context.rootExchange())
            .block();
        assertThat(initialized).isNotNull();
        return context.withSecurityKey(initialized.securityKey());
    }

    private TestContext context(Path recoveryFile, List<DataManagementSecurityIncidentHandler> handlers) {
        return context(Clock.systemUTC(), recoveryFile, handlers);
    }

    private TestContext context(
        Clock clock,
        Path recoveryFile,
        List<DataManagementSecurityIncidentHandler> handlers
    ) {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        AdminUserEntity root = user(1L, "root-1", "admin", Instant.parse("2026-01-01T00:00:00Z"));
        AdminUserEntity admin = user(2L, "admin-2", "operator", Instant.parse("2026-02-01T00:00:00Z"));
        when(repository.findByUserId(root.getUserId())).thenReturn(Mono.just(root));
        when(repository.findByUserId(admin.getUserId())).thenReturn(Mono.just(admin));
        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));
        Path stateFile = tempDir.resolve("security").resolve("data-management.json");
        return new TestContext(
            service(repository, stateFile, recoveryFile, clock, handlers),
            repository,
            stateFile,
            root,
            admin,
            null
        );
    }

    private DataManagementSecurityService service(
        AdminUserRepository repository,
        Path stateFile,
        Path recoveryFile,
        Clock clock,
        List<DataManagementSecurityIncidentHandler> handlers
    ) {
        return service(repository, stateFile, recoveryFile, clock, handlers, false);
    }

    private DataManagementSecurityService service(
        AdminUserRepository repository,
        Path stateFile,
        Path recoveryFile,
        Clock clock,
        List<DataManagementSecurityIncidentHandler> handlers,
        boolean requireInitialized
    ) {
        return new DataManagementSecurityService(
            repository,
            new ObjectMapper().findAndRegisterModules(),
            handlers,
            stateFile,
            recoveryFile,
            2_000,
            Duration.ofMinutes(5),
            clock,
            requireInitialized
        );
    }

    private AdminUserEntity user(Long id, String userId, String username, Instant createdAt) {
        AdminUserEntity user = new AdminUserEntity();
        user.setId(id);
        user.setUserId(userId);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setDisplayName(username + " display");
        user.setRole("ADMIN");
        user.setEnabled(true);
        user.setFirstLogin(false);
        user.setPasswordHash("password-hash-secret");
        user.setPasswordSalt("password-salt-secret");
        user.setCreatedAt(createdAt);
        return user;
    }

    private MockServerWebExchange exchange(String token) {
        return MockServerWebExchange.from(MockServerHttpRequest
            .get("/admin/data-transfer/security/unlock")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("X-Forwarded-For", "203.0.113.10")
            .header(HttpHeaders.USER_AGENT, "security-test")
            .header("X-Request-Id", "request-1")
            .remoteAddress(new InetSocketAddress("127.0.0.1", 443)));
    }

    private record TestContext(
        DataManagementSecurityService service,
        AdminUserRepository repository,
        Path stateFile,
        AdminUserEntity root,
        AdminUserEntity admin,
        String securityKey
    ) {

        private TestContext withSecurityKey(String key) {
            return new TestContext(service, repository, stateFile, root, admin, key);
        }

        private AdminPrincipal rootPrincipal() {
            return principal(root);
        }

        private AdminPrincipal adminPrincipal() {
            return principal(admin);
        }

        private MockServerWebExchange rootExchange() {
            return exchangeFor("adm-root-session");
        }

        private MockServerWebExchange adminExchange() {
            return exchangeFor("adm-admin-session");
        }

        private MockServerWebExchange exchangeFor(String token) {
            return MockServerWebExchange.from(MockServerHttpRequest
                .get("/admin/data-transfer/security/unlock")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Forwarded-For", "203.0.113.10")
                .header(HttpHeaders.USER_AGENT, "security-test")
                .header("X-Request-Id", "request-1")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 443)));
        }

        private static AdminPrincipal principal(AdminUserEntity user) {
            return new AdminPrincipal(
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                Instant.now().plusSeconds(3600),
                false
            );
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
