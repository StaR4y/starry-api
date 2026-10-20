package tech.starryapi.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;

@Service
public class DataManagementSecurityService {

    private static final Logger log = LoggerFactory.getLogger(DataManagementSecurityService.class);

    public static final String SECURITY_TOKEN_HEADER = "X-Data-Management-Token";
    public static final int MAX_FAILED_ATTEMPTS = 5;

    public enum Action {
        VIEW_DATA_MANAGEMENT(false, true),
        EXPORT_DATABASE(false, false),
        EXPORT_BACKUP(false, false),
        CREATE_BACKUP(false, false),
        DOWNLOAD_BACKUP(true, false),
        DELETE_BACKUP(true, false),
        RESTORE_DATABASE(false, true);

        private final boolean targetRequired;
        private final boolean applicationRootRequired;

        Action(boolean targetRequired, boolean applicationRootRequired) {
            this.targetRequired = targetRequired;
            this.applicationRootRequired = applicationRootRequired;
        }
    }

    private static final String ENV_ADMIN_USER_ID = "env-admin";
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String TOKEN_TYPE = "DataManagement";
    private static final String KEY_PREFIX = "dmsk-";
    private static final String TOKEN_PREFIX = "dmt-";
    private static final String INITIALIZATION_MARKER = "starry-data-management-initialized-v1\n";
    private static final String STATE_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int STATE_VERSION = 2;
    private static final int LEGACY_STATE_VERSION = 1;
    private static final int DEFAULT_ITERATIONS = 600_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 32;
    private static final int RANDOM_KEY_BYTES = 32;
    private static final int RANDOM_TOKEN_BYTES = 32;
    private static final int MAX_STATE_BYTES = 64 * 1024;
    private static final int MAX_MARKER_BYTES = 128;
    private static final int MAX_RECOVERY_KEY_BYTES = 1024;
    private static final int MAX_ACTIVE_GRANTS = 4096;
    private static final int MAX_PENDING_INCIDENTS = 16;
    private static final Duration DEFAULT_UNLOCK_TTL = Duration.ofMinutes(5);
    private static final Duration INCIDENT_HANDLER_TIMEOUT = Duration.ofSeconds(15);
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");

    private final AdminUserRepository repository;
    private final ObjectMapper objectMapper;
    private final List<DataManagementSecurityIncidentHandler> incidentHandlers;
    private final Path stateFile;
    private final Path initializationMarkerFile;
    private final Path recoveryKeyFile;
    private final boolean requireInitialized;
    private final int iterations;
    private final Duration unlockTtl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Object fileMonitor = new Object();
    private final Object grantMonitor = new Object();
    private final ConcurrentHashMap<String, UnlockGrant> grants = new ConcurrentHashMap<>();
    private final AtomicBoolean incidentDrainInProgress = new AtomicBoolean();
    private volatile LockSnapshot lockSnapshot = LockSnapshot.closedForFailure();
    private volatile boolean observedInitializedState;
    private volatile boolean recoveryCleanupFailed;

    @Autowired
    public DataManagementSecurityService(
        AdminUserRepository repository,
        ObjectMapper objectMapper,
        List<DataManagementSecurityIncidentHandler> incidentHandlers,
        @Value("${starry.data-management.security-file:${user.home}/.starry-api/security/data-management.json}")
        String stateFile,
        @Value("${starry.data-management.recovery-key-file:}") String recoveryKeyFile,
        @Value("${starry.data-management.require-initialized:false}") boolean requireInitialized
    ) {
        this(
            repository,
            objectMapper,
            incidentHandlers,
            Path.of(stateFile),
            recoveryKeyFile == null || recoveryKeyFile.isBlank() ? null : Path.of(recoveryKeyFile),
            DEFAULT_ITERATIONS,
            DEFAULT_UNLOCK_TTL,
            Clock.systemUTC(),
            requireInitialized
        );
    }

    DataManagementSecurityService(
        AdminUserRepository repository,
        ObjectMapper objectMapper,
        List<DataManagementSecurityIncidentHandler> incidentHandlers,
        Path stateFile,
        Path recoveryKeyFile,
        int iterations,
        Duration unlockTtl,
        Clock clock
    ) {
        this(
            repository,
            objectMapper,
            incidentHandlers,
            stateFile,
            recoveryKeyFile,
            iterations,
            unlockTtl,
            clock,
            false
        );
    }

    DataManagementSecurityService(
        AdminUserRepository repository,
        ObjectMapper objectMapper,
        List<DataManagementSecurityIncidentHandler> incidentHandlers,
        Path stateFile,
        Path recoveryKeyFile,
        int iterations,
        Duration unlockTtl,
        Clock clock,
        boolean requireInitialized
    ) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("Data management security iterations must be positive");
        }
        if (unlockTtl == null || unlockTtl.isZero() || unlockTtl.isNegative()) {
            throw new IllegalArgumentException("Data management unlock TTL must be positive");
        }
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.incidentHandlers = incidentHandlers == null ? List.of() : List.copyOf(incidentHandlers);
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.initializationMarkerFile = this.stateFile.resolveSibling(
            this.stateFile.getFileName() + ".initialized"
        );
        this.recoveryKeyFile = recoveryKeyFile == null ? null : recoveryKeyFile.toAbsolutePath().normalize();
        this.requireInitialized = requireInitialized;
        this.iterations = iterations;
        this.unlockTtl = unlockTtl;
        this.clock = clock;
    }

    /** Every data-management endpoint requires the active, persisted application Root. */
    public Mono<AdminUserEntity> requireDatabaseAdmin(AdminPrincipal principal) {
        if (principal == null || ENV_ADMIN_USER_ID.equals(principal.userId())) {
            return Mono.error(new ApiException(
                HttpStatus.FORBIDDEN,
                "DATA_MANAGEMENT_DATABASE_ADMIN_REQUIRED",
                "Data management requires a database-backed administrator session"
            ));
        }
        return repository.findByUserId(principal.userId())
            .switchIfEmpty(Mono.error(databaseAdminRequired()))
            .flatMap(user -> isActiveAdmin(user)
                ? requireApplicationRoot(user).thenReturn(user)
                : Mono.error(databaseAdminRequired()));
    }

    public Mono<DataTransferDtos.DataManagementSecurityStatusResponse> status(AdminPrincipal principal) {
        return requireDatabaseAdmin(principal)
            .then(Mono.fromCallable(() -> withStateLock(() -> toStatus(readState())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<Boolean> isLocked() {
        return Mono.just(lockSnapshot.blocksRequests());
    }

    public Mono<DataTransferDtos.InitializeDataManagementSecurityResponse> initialize(
        AdminPrincipal principal,
        ServerWebExchange exchange
    ) {
        return requireDatabaseAdmin(principal)
            .then(Mono.fromCallable(() -> withStateLock(this::initializeState))
                .subscribeOn(Schedulers.boundedElastic()))
            .map(initialized -> new DataTransferDtos.InitializeDataManagementSecurityResponse(
                toStatus(initialized.state()),
                initialized.securityKey()
            ));
    }

    public Mono<DataTransferDtos.UnlockDataManagementResponse> unlock(
        AdminPrincipal principal,
        ServerWebExchange exchange,
        String securityKey,
        String requestedAction,
        String requestedTarget
    ) {
        RequestContext context = requestContext(exchange);
        Action action = parseAction(requestedAction);
        String target = normalizeTarget(action, requestedTarget);
        return requireDatabaseAdmin(principal)
            .flatMap(user -> repository.findApplicationRoot()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(root -> requireActionAccess(user, root.orElse(null), action)
                    .then(Mono.fromCallable(() -> withStateLock(() -> verifyUnlockKey(
                            user,
                            root.orElse(null),
                            context,
                            securityKey
                        )))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(attempt -> {
                            if (!attempt.success()) {
                                return Mono.error(attempt.error());
                            }
                            return Mono.just(createGrant(user, context.sessionFingerprint(), action, target));
                        }))));
    }

    public Mono<Void> authorize(
        AdminPrincipal principal,
        ServerWebExchange exchange,
        String securityToken,
        Action expectedAction,
        String expectedTarget,
        boolean consume
    ) {
        RequestContext context = requestContext(exchange);
        String target = normalizeTarget(expectedAction, expectedTarget);
        return requireDatabaseAdmin(principal)
            .flatMap(user -> requireActionAccess(user, expectedAction)
                .then(Mono.<Void>fromRunnable(() -> {
                        ensureSecurityAvailable();
                        verifyGrant(
                            user,
                            context.sessionFingerprint(),
                            securityToken,
                            expectedAction,
                            target,
                            consume
                        );
                    })
                    .subscribeOn(Schedulers.boundedElastic())));
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredGrants() {
        Instant now = clock.instant();
        synchronized (grantMonitor) {
            grants.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        }
    }

    @Scheduled(fixedDelay = 1_000)
    public void schedulePendingIncidentDrain() {
        triggerIncidentDrain();
    }

    @PostConstruct
    void initializeStateCacheAndRecover() {
        RecoveryResult result;
        try {
            result = withStateLock(() -> {
                PersistedState state = readState();
                reconcileStagedRecoveryKey(state);
                refreshLockSnapshot(state);
                if (state != null) {
                    log.warn(
                        "Data-management security is initialized; run exactly one active service instance and persist {}",
                        stateFile.getParent()
                    );
                }
                if (recoveryKeyFile == null) {
                    return new RecoveryResult(null, false);
                }
                if (state == null || !state.locked()) {
                    discardUnusedRecoveryKeyFile();
                    return new RecoveryResult(null, false);
                }
                if (!Files.exists(recoveryKeyFile, LinkOption.NOFOLLOW_LINKS)) {
                    return new RecoveryResult(null, false);
                }
                return recoverFromConfiguredKeyFile();
            });
        } catch (Exception exception) {
            lockSnapshot = LockSnapshot.closedForFailure();
            result = new RecoveryResult(recoveryIncident(
                DataManagementSecurityIncident.Type.RECOVERY_FAILED,
                DataManagementSecurityIncident.Severity.CRITICAL,
                true,
                "Configured data management recovery failed"
            ), false);
        }
        if (result.incident() != null && !result.persisted()) {
            DataManagementSecurityIncident incident = result.incident();
            dispatchIncident(incident)
                .subscribe(
                    ignored -> { },
                    error -> logIncidentDispatchFailure(incident, error)
                );
        }
    }

    @Scheduled(fixedDelay = 5_000)
    public void refreshLockSnapshot() {
        try {
            withStateLock(() -> {
                refreshLockSnapshot(readState());
                return null;
            });
        } catch (Exception exception) {
            lockSnapshot = LockSnapshot.closedForFailure();
        }
    }

    private Mono<Void> requireApplicationRoot(AdminUserEntity caller) {
        return repository.findApplicationRoot()
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.FORBIDDEN,
                "DATA_MANAGEMENT_ROOT_REQUIRED",
                "Only the initial application administrator can perform this Root-only data management action"
            )))
            .flatMap(root -> root.getUserId() != null && root.getUserId().equals(caller.getUserId())
                ? Mono.empty()
                : Mono.error(new ApiException(
                    HttpStatus.FORBIDDEN,
                    "DATA_MANAGEMENT_ROOT_REQUIRED",
                    "Only the initial application administrator can perform this Root-only data management action"
                )));
    }

    private Mono<Void> requireActionAccess(AdminUserEntity caller, Action action) {
        if (action == null || !action.applicationRootRequired) {
            return Mono.empty();
        }
        return requireApplicationRoot(caller);
    }

    private Mono<Void> requireActionAccess(AdminUserEntity caller, AdminUserEntity root, Action action) {
        if (action == null || !action.applicationRootRequired) {
            return Mono.empty();
        }
        return root != null && root.getUserId() != null && root.getUserId().equals(caller.getUserId())
            ? Mono.empty()
            : Mono.error(new ApiException(
                HttpStatus.FORBIDDEN,
                "DATA_MANAGEMENT_ROOT_REQUIRED",
                "Only the initial application administrator can restore the application database"
            ));
    }

    private InitializationResult initializeState() throws Exception {
        if (stateExists()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "DATA_MANAGEMENT_SECURITY_ALREADY_INITIALIZED",
                "Data management security is already initialized"
            );
        }
        if (initializationMarkerExists() || requireInitialized) {
            throw stateUnavailable();
        }
        String securityKey = KEY_PREFIX + Hashing.randomToken(RANDOM_KEY_BYTES);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] verifier = derive(securityKey, salt, iterations);
        Instant now = clock.instant();
        PersistedState state = new PersistedState(
            STATE_VERSION,
            STATE_ALGORITHM,
            iterations,
            KEY_LENGTH_BITS,
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(verifier),
            0,
            false,
            now,
            null,
            null,
            now,
            List.of()
        );
        writeState(state);
        ensureInitializationMarker();
        log.warn(
            "Data-management security initialized; run exactly one active service instance and persist {}",
            stateFile.getParent()
        );
        return new InitializationResult(securityKey, state);
    }

    private UnlockAttempt verifyUnlockKey(
        AdminUserEntity user,
        AdminUserEntity root,
        RequestContext context,
        String securityKey
    ) throws Exception {
        PersistedState state = requireInitializedState();
        if (state.locked()) {
            grants.clear();
            return UnlockAttempt.failed(lockedError(), null);
        }

        byte[] actual = derive(cleanKey(securityKey), decodeSalt(state), state.iterations());
        byte[] expected = decodeVerifier(state);
        if (MessageDigest.isEqual(actual, expected)) {
            PersistedState cleared = state.failedAttempts() == 0
                ? state
                : new PersistedState(
                    STATE_VERSION,
                    state.algorithm(),
                    state.iterations(),
                    state.keyLengthBits(),
                    state.salt(),
                    state.verifier(),
                    0,
                    false,
                    state.initializedAt(),
                    state.lastFailureAt(),
                    null,
                    clock.instant(),
                    pendingIncidents(state)
                );
            if (cleared != state) {
                writeState(cleared);
            }
            return UnlockAttempt.succeeded();
        }

        Instant now = clock.instant();
        int failures = Math.min(MAX_FAILED_ATTEMPTS, state.failedAttempts() + 1);
        boolean locked = failures >= MAX_FAILED_ATTEMPTS;
        DataManagementSecurityIncident incident = incident(
            locked
                ? DataManagementSecurityIncident.Type.SECURITY_LOCKED
                : DataManagementSecurityIncident.Type.UNLOCK_FAILED,
            locked
                ? DataManagementSecurityIncident.Severity.CRITICAL
                : DataManagementSecurityIncident.Severity.WARNING,
            user,
            root,
            context,
            failures,
            locked,
            locked
                ? "Data management security permanently locked after consecutive key failures"
                : "Data management security key verification failed"
        );
        PersistedState failed = new PersistedState(
            STATE_VERSION,
            state.algorithm(),
            state.iterations(),
            state.keyLengthBits(),
            state.salt(),
            state.verifier(),
            failures,
            locked,
            state.initializedAt(),
            now,
            locked ? now : null,
            now,
            appendPendingIncident(state, incident)
        );
        writeState(failed);
        if (locked) {
            grants.clear();
        }
        return UnlockAttempt.failed(locked ? lockedError() : invalidKeyError(failures), incident);
    }

    private DataTransferDtos.UnlockDataManagementResponse createGrant(
        AdminUserEntity user,
        String sessionFingerprint,
        Action action,
        String target
    ) {
        String token = TOKEN_PREFIX + Hashing.randomToken(RANDOM_TOKEN_BYTES);
        Instant expiresAt = clock.instant().plus(unlockTtl);
        UnlockGrant grant = new UnlockGrant(user.getUserId(), sessionFingerprint, action, target, expiresAt);
        synchronized (grantMonitor) {
            Instant now = clock.instant();
            grants.entrySet().removeIf(entry -> {
                UnlockGrant existing = entry.getValue();
                return !existing.expiresAt().isAfter(now) || existing.sameBinding(grant);
            });
            if (grants.size() >= MAX_ACTIVE_GRANTS) {
                throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATA_MANAGEMENT_GRANT_CAPACITY_EXCEEDED",
                    "Data management unlock capacity is temporarily exhausted"
                );
            }
            grants.put(Hashing.sha256(token), grant);
        }
        return new DataTransferDtos.UnlockDataManagementResponse(
            TOKEN_TYPE,
            token,
            expiresAt,
            action.name(),
            target
        );
    }

    private void verifyGrant(
        AdminUserEntity user,
        String sessionFingerprint,
        String securityToken,
        Action expectedAction,
        String expectedTarget,
        boolean consume
    ) {
        if (securityToken == null || securityToken.isBlank()) {
            throw unlockRequired();
        }
        String tokenHash = Hashing.sha256(securityToken);
        synchronized (grantMonitor) {
            UnlockGrant grant = grants.get(tokenHash);
            Instant now = clock.instant();
            if (grant == null
                || !grant.expiresAt().isAfter(now)
                || !constantEquals(grant.userId(), user.getUserId())
                || !constantEquals(grant.sessionFingerprint(), sessionFingerprint)
                || grant.action() != expectedAction
                || !constantNullableEquals(grant.target(), expectedTarget)) {
                if (grant != null && !grant.expiresAt().isAfter(now)) {
                    grants.remove(tokenHash, grant);
                }
                throw unlockRequired();
            }
            if (consume && !grants.remove(tokenHash, grant)) {
                throw unlockRequired();
            }
        }
    }

    private void ensureSecurityAvailable() {
        LockSnapshot snapshot = lockSnapshot;
        if (snapshot.failClosed()) {
            grants.clear();
            throw stateUnavailable();
        }
        if (!snapshot.initialized()) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "DATA_MANAGEMENT_SECURITY_NOT_INITIALIZED",
                "Data management security must be initialized by the initial administrator"
            );
        }
        if (snapshot.locked()) {
            grants.clear();
            throw lockedError();
        }
    }

    private RecoveryResult recoverFromConfiguredKeyFile() throws Exception {
        PersistedState state = readState();
        if (state == null || !state.locked()) {
            return new RecoveryResult(null, false);
        }
        Path stagedRecoveryKey;
        try {
            stagedRecoveryKey = stageRecoveryKeyFile();
        } catch (Exception error) {
            DataManagementSecurityIncident incident = recoveryIncident(
                DataManagementSecurityIncident.Type.RECOVERY_FAILED,
                DataManagementSecurityIncident.Severity.CRITICAL,
                true,
                "Configured data management recovery key file was rejected"
            );
            writeState(withPendingIncident(state, incident));
            return new RecoveryResult(incident, true);
        }
        String recoveryKey;
        try {
            recoveryKey = readRecoveryKey(stagedRecoveryKey);
        } catch (Exception error) {
            restoreRecoveryKeyFile(stagedRecoveryKey, error);
            DataManagementSecurityIncident incident = recoveryIncident(
                DataManagementSecurityIncident.Type.RECOVERY_FAILED,
                DataManagementSecurityIncident.Severity.CRITICAL,
                true,
                "Configured data management recovery key file was rejected"
            );
            writeState(withPendingIncident(state, incident));
            return new RecoveryResult(incident, true);
        }
        DataManagementSecurityIncident incident;
        try {
            byte[] actual = derive(recoveryKey, decodeSalt(state), state.iterations());
            if (!MessageDigest.isEqual(actual, decodeVerifier(state))) {
                incident = recoveryIncident(
                    DataManagementSecurityIncident.Type.RECOVERY_FAILED,
                    DataManagementSecurityIncident.Severity.CRITICAL,
                    true,
                    "Configured data management recovery key was rejected"
                );
                writeState(withPendingIncident(state, incident));
                eraseStagedRecoveryKey(stagedRecoveryKey);
                return new RecoveryResult(incident, true);
            }
            incident = recoveryIncident(
                DataManagementSecurityIncident.Type.RECOVERY_SUCCEEDED,
                DataManagementSecurityIncident.Severity.INFO,
                false,
                "Data management hard lock was cleared from the configured recovery key file"
            );
            List<DataManagementSecurityIncident> pending = appendPendingIncident(state, incident);
            PersistedState recovered = new PersistedState(
                STATE_VERSION,
                state.algorithm(),
                state.iterations(),
                state.keyLengthBits(),
                state.salt(),
                state.verifier(),
                0,
                false,
                state.initializedAt(),
                state.lastFailureAt(),
                null,
                clock.instant(),
                pending
            );
            writeState(recovered);
        } catch (Exception error) {
            restoreRecoveryKeyFile(stagedRecoveryKey, error);
            throw error;
        }
        eraseStagedRecoveryKey(stagedRecoveryKey);
        grants.clear();
        return new RecoveryResult(incident, true);
    }

    private String readRecoveryKey(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Configured recovery key file is unavailable");
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_RECOVERY_KEY_BYTES) {
            throw new IllegalStateException("Configured recovery key file has an invalid size");
        }
        requireOwnerOnlyRecoveryPermissions(path);
        String key = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (key.isEmpty()) {
            throw new IllegalStateException("Configured recovery key file is empty");
        }
        return cleanKey(key);
    }

    private Path stageRecoveryKeyFile() throws Exception {
        Path fileName = recoveryKeyFile.getFileName();
        if (fileName == null || recoveryKeyFile.getParent() == null) {
            throw new IllegalStateException("Configured recovery key file path is invalid");
        }
        Path staged = stagedRecoveryKeyFile();
        if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("A staged data-management recovery key already exists");
        }
        moveRecoveryKeyFile(recoveryKeyFile, staged);
        try {
            if (Files.isSymbolicLink(staged)
                || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Configured recovery key file is invalid");
            }
            requireOwnerOnlyRecoveryPermissions(staged);
        } catch (Exception error) {
            restoreRecoveryKeyFile(staged, error);
            throw error;
        }
        return staged;
    }

    private void discardUnusedRecoveryKeyFile() throws Exception {
        if (!Files.exists(recoveryKeyFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        recoveryCleanupFailed = true;
        Path staged = stageRecoveryKeyFile();
        eraseStagedRecoveryKey(staged);
        log.warn("Discarded an unused data-management recovery key file while security was not locked");
    }

    private void reconcileStagedRecoveryKey(PersistedState state) throws Exception {
        if (recoveryKeyFile == null) {
            return;
        }
        Path staged = stagedRecoveryKeyFile();
        if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (state == null || !state.locked()) {
            recoveryCleanupFailed = true;
        }
        if (Files.isSymbolicLink(staged) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Staged data-management recovery key is invalid");
        }
        requireOwnerOnlyRecoveryPermissions(staged);
        if (state != null && state.locked()) {
            if (Files.exists(recoveryKeyFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Configured and staged recovery key files both exist");
            }
            moveRecoveryKeyFile(staged, recoveryKeyFile);
            enforceOwnerFilePermissions(recoveryKeyFile);
            log.warn("Restored a data-management recovery key transaction interrupted before state commit");
            return;
        }
        eraseStagedRecoveryKey(staged);
    }

    private Path stagedRecoveryKeyFile() {
        return recoveryKeyFile.resolveSibling("." + recoveryKeyFile.getFileName() + ".consuming");
    }

    private void restoreRecoveryKeyFile(Path staged, Exception originalError) {
        try {
            moveRecoveryKeyFile(staged, recoveryKeyFile);
            enforceOwnerFilePermissions(recoveryKeyFile);
        } catch (Exception restoreError) {
            originalError.addSuppressed(restoreError);
            log.error(
                "Data-management recovery state commit failed and the staged recovery key could not be restored: {}",
                staged,
                restoreError
            );
        }
    }

    private void moveRecoveryKeyFile(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
        forceRenamedDirectories(source, target);
    }

    private void eraseStagedRecoveryKey(Path staged) throws Exception {
        recoveryCleanupFailed = true;
        Exception cleanupError = null;
        try (FileChannel channel = FileChannel.open(
            staged,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        )) {
            channel.truncate(0);
            channel.force(true);
        } catch (Exception error) {
            cleanupError = error;
        }
        try {
            deleteStagedRecoveryKeyFile(staged);
            forceDirectory(staged.getParent());
        } catch (Exception error) {
            if (cleanupError == null) {
                cleanupError = error;
            } else {
                cleanupError.addSuppressed(error);
            }
        }
        if (cleanupError != null) {
            log.error(
                "Cleanup of the staged one-time data-management recovery key failed: {}",
                staged,
                cleanupError
            );
            throw cleanupError;
        }
        recoveryCleanupFailed = false;
    }

    void deleteStagedRecoveryKeyFile(Path staged) throws Exception {
        Files.deleteIfExists(staged);
    }

    private void triggerIncidentDrain() {
        if (incidentHandlers.isEmpty() || !incidentDrainInProgress.compareAndSet(false, true)) {
            return;
        }
        drainPendingIncidents()
            .doFinally(signal -> incidentDrainInProgress.set(false))
            .subscribe(
                ignored -> { },
                error -> log.error(
                    "security_event=DATA_MANAGEMENT_INCIDENT drain_status=FAILED pending_retained=true",
                    error
                )
            );
    }

    Mono<Void> drainPendingIncidents() {
        if (incidentHandlers.isEmpty()) {
            return Mono.empty();
        }
        return nextPendingIncident()
            .flatMap(pending -> pending
                .map(incident -> dispatchIncident(incident)
                    .then(acknowledgePendingIncident(incident.eventId()))
                    .then(drainPendingIncidents()))
                .orElseGet(Mono::empty));
    }

    private Mono<Optional<DataManagementSecurityIncident>> nextPendingIncident() {
        return Mono.fromCallable(() -> withStateLock(() -> {
                PersistedState state = readState();
                List<DataManagementSecurityIncident> pending = pendingIncidents(state);
                return pending.isEmpty()
                    ? Optional.<DataManagementSecurityIncident>empty()
                    : Optional.of(pending.get(0));
            }))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> acknowledgePendingIncident(String eventId) {
        return Mono.fromCallable(() -> withStateLock(() -> {
                PersistedState state = readState();
                if (state == null) {
                    return false;
                }
                List<DataManagementSecurityIncident> retained = pendingIncidents(state).stream()
                    .filter(incident -> !constantEquals(incident.eventId(), eventId))
                    .toList();
                if (retained.size() == pendingIncidents(state).size()) {
                    return false;
                }
                writeState(copyState(state, retained, clock.instant()));
                return true;
            }))
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    private Mono<Void> dispatchIncident(DataManagementSecurityIncident incident) {
        if (incident == null || incidentHandlers.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(incidentHandlers)
            .concatMap(handler -> Mono.defer(() -> {
                    Mono<Void> notification = handler.handle(incident);
                    return notification == null
                        ? Mono.error(new IllegalStateException("Incident handler returned null"))
                        : notification;
                })
                .timeout(INCIDENT_HANDLER_TIMEOUT)
                .doOnError(error -> logIncidentDispatchFailure(incident, handler, error)))
            .then();
    }

    private void logIncidentDispatchFailure(
        DataManagementSecurityIncident incident,
        DataManagementSecurityIncidentHandler handler,
        Throwable error
    ) {
        String handlerName = handler == null ? "unavailable" : handler.getClass().getName();
        log.error(
            "security_event={} incident_dispatch=FAILED event_id={} handler={} pending_retained=true",
            incident == null ? null : incident.type(),
            incident == null ? null : incident.eventId(),
            handlerName,
            error
        );
    }

    private void logIncidentDispatchFailure(DataManagementSecurityIncident incident, Throwable error) {
        logIncidentDispatchFailure(incident, null, error);
    }

    private DataManagementSecurityIncident incident(
        DataManagementSecurityIncident.Type type,
        DataManagementSecurityIncident.Severity severity,
        AdminUserEntity user,
        AdminUserEntity root,
        RequestContext context,
        int failures,
        boolean locked,
        String detail
    ) {
        return new DataManagementSecurityIncident(
            UUID.randomUUID().toString(),
            type,
            severity,
            clock.instant(),
            user.getUserId(),
            user.getUsername(),
            user.getEmail(),
            user.getDisplayName(),
            user.getRole(),
            user.getEnabled(),
            user.getCreatedAt(),
            user.getLastLoginAt(),
            root == null ? null : root.getUserId(),
            root == null ? null : root.getEmail(),
            context.remoteAddress(),
            context.forwardedFor(),
            context.userAgent(),
            context.sessionFingerprint(),
            context.endpoint(),
            context.requestId(),
            failures,
            locked,
            detail
        );
    }

    private DataManagementSecurityIncident recoveryIncident(
        DataManagementSecurityIncident.Type type,
        DataManagementSecurityIncident.Severity severity,
        boolean locked,
        String detail
    ) {
        return new DataManagementSecurityIncident(
            UUID.randomUUID().toString(),
            type,
            severity,
            clock.instant(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "startup",
            null,
            MAX_FAILED_ATTEMPTS,
            locked,
            detail
        );
    }

    private RequestContext requestContext(ServerWebExchange exchange) {
        String sessionFingerprint = sessionFingerprint(exchange);
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String remoteAddress = remote == null || remote.getAddress() == null
            ? null
            : remote.getAddress().getHostAddress();
        return new RequestContext(
            remoteAddress,
            DataManagementSecuritySanitizer.forwardedAddresses(cleanHeader(exchange, "X-Forwarded-For")),
            DataManagementSecuritySanitizer.fingerprint(cleanHeader(exchange, HttpHeaders.USER_AGENT)),
            sessionFingerprint,
            exchange.getRequest().getPath().value(),
            DataManagementSecuritySanitizer.fingerprint(cleanHeader(exchange, "X-Request-Id"))
        );
    }

    private String sessionFingerprint(ServerWebExchange exchange) {
        String credential = exchange.getRequest().getHeaders().getFirst("X-Admin-Token");
        if (credential == null || credential.isBlank()) {
            String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.startsWith("Bearer ")) {
                credential = authorization.substring("Bearer ".length());
            }
        }
        if (credential == null || credential.isBlank()) {
            throw new ApiException(
                HttpStatus.UNAUTHORIZED,
                "ADMIN_UNAUTHORIZED",
                "Missing admin credentials"
            );
        }
        return Hashing.sha256(credential);
    }

    private String cleanHeader(ServerWebExchange exchange, String name) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.strip();
        return cleaned.length() <= 1024 ? cleaned : cleaned.substring(0, 1024);
    }

    private PersistedState requireInitializedState() throws Exception {
        PersistedState state = readState();
        if (state == null) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "DATA_MANAGEMENT_SECURITY_NOT_INITIALIZED",
                "Data management security must be initialized by the initial administrator"
            );
        }
        return state;
    }

    private PersistedState readState() throws Exception {
        if (!stateExists()) {
            if (initializationMarkerExists() || requireInitialized) {
                throw stateUnavailable();
            }
            return null;
        }
        if (Files.isSymbolicLink(stateFile) || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            throw stateUnavailable();
        }
        enforceOwnerFilePermissions(stateFile);
        long size = Files.size(stateFile);
        if (size <= 0 || size > MAX_STATE_BYTES) {
            throw stateUnavailable();
        }
        try {
            PersistedState state = objectMapper.readValue(Files.readAllBytes(stateFile), PersistedState.class);
            validateState(state);
            ensureInitializationMarker();
            return state;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw stateUnavailable();
        }
    }

    private void validateState(PersistedState state) {
        if (state == null
            || (state.version() != LEGACY_STATE_VERSION && state.version() != STATE_VERSION)
            || !STATE_ALGORITHM.equals(state.algorithm())
            || state.iterations() <= 0
            || state.keyLengthBits() != KEY_LENGTH_BITS
            || state.failedAttempts() < 0
            || state.failedAttempts() > MAX_FAILED_ATTEMPTS
            || state.locked() != (state.failedAttempts() >= MAX_FAILED_ATTEMPTS)
            || state.initializedAt() == null
            || state.updatedAt() == null) {
            throw stateUnavailable();
        }
        decodeSalt(state);
        decodeVerifier(state);
        List<DataManagementSecurityIncident> pending = pendingIncidents(state);
        if (pending.size() > MAX_PENDING_INCIDENTS) {
            throw stateUnavailable();
        }
        pending.forEach(this::validatePendingIncident);
    }

    private void validatePendingIncident(DataManagementSecurityIncident incident) {
        if (incident == null
            || incident.eventId() == null
            || incident.eventId().isBlank()
            || incident.eventId().length() > 64
            || incident.type() == null
            || incident.severity() == null
            || incident.occurredAt() == null
            || incident.consecutiveFailures() < 0
            || incident.consecutiveFailures() > MAX_FAILED_ATTEMPTS
            || tooLong(incident.userId(), 64)
            || tooLong(incident.username(), 64)
            || tooLong(incident.actorEmail(), 255)
            || tooLong(incident.actorDisplayName(), 128)
            || tooLong(incident.actorRole(), 32)
            || tooLong(incident.rootUserId(), 64)
            || tooLong(incident.rootEmail(), 255)
            || tooLong(incident.remoteAddress(), 128)
            || tooLong(incident.forwardedFor(), 1024)
            || tooLong(incident.userAgent(), 1024)
            || tooLong(incident.sessionFingerprint(), 64)
            || tooLong(incident.endpoint(), 255)
            || tooLong(incident.requestId(), 128)
            || tooLong(incident.detail(), 1024)) {
            throw stateUnavailable();
        }
    }

    private boolean tooLong(String value, int maximumLength) {
        return value != null && value.length() > maximumLength;
    }

    private List<DataManagementSecurityIncident> pendingIncidents(PersistedState state) {
        if (state == null || state.pendingIncidents() == null || state.pendingIncidents().isEmpty()) {
            return List.of();
        }
        return List.copyOf(state.pendingIncidents());
    }

    private List<DataManagementSecurityIncident> appendPendingIncident(
        PersistedState state,
        DataManagementSecurityIncident incident
    ) {
        List<DataManagementSecurityIncident> current = pendingIncidents(state);
        if (current.stream().anyMatch(pending -> constantEquals(pending.eventId(), incident.eventId()))) {
            return current;
        }
        if (current.size() >= MAX_PENDING_INCIDENTS) {
            throw stateUnavailable();
        }
        List<DataManagementSecurityIncident> appended = new java.util.ArrayList<>(current);
        appended.add(incident);
        return List.copyOf(appended);
    }

    private PersistedState withPendingIncident(
        PersistedState state,
        DataManagementSecurityIncident incident
    ) {
        return copyState(state, appendPendingIncident(state, incident), clock.instant());
    }

    private PersistedState copyState(
        PersistedState state,
        List<DataManagementSecurityIncident> pending,
        Instant updatedAt
    ) {
        return new PersistedState(
            STATE_VERSION,
            state.algorithm(),
            state.iterations(),
            state.keyLengthBits(),
            state.salt(),
            state.verifier(),
            state.failedAttempts(),
            state.locked(),
            state.initializedAt(),
            state.lastFailureAt(),
            state.lockedAt(),
            updatedAt,
            List.copyOf(pending)
        );
    }

    private byte[] decodeSalt(PersistedState state) {
        try {
            byte[] salt = Base64.getDecoder().decode(state.salt());
            if (salt.length != SALT_BYTES) {
                throw stateUnavailable();
            }
            return salt;
        } catch (IllegalArgumentException exception) {
            throw stateUnavailable();
        }
    }

    private byte[] decodeVerifier(PersistedState state) {
        try {
            byte[] verifier = Base64.getDecoder().decode(state.verifier());
            if (verifier.length != KEY_LENGTH_BITS / Byte.SIZE) {
                throw stateUnavailable();
            }
            return verifier;
        } catch (IllegalArgumentException exception) {
            throw stateUnavailable();
        }
    }

    private void writeState(PersistedState state) throws Exception {
        prepareStateDirectory();
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
        if (bytes.length <= 0 || bytes.length > MAX_STATE_BYTES) {
            throw stateUnavailable();
        }
        Path temporary = Files.createTempFile(
            stateFile.getParent(),
            ".data-management-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(OWNER_FILE_PERMISSIONS)
        );
        try {
            enforceOwnerFilePermissions(temporary);
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                    temporary,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            enforceOwnerFilePermissions(stateFile);
            forceDirectory(stateFile.getParent());
            refreshLockSnapshot(state);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private <T> T withStateLock(CheckedSupplier<T> operation) throws Exception {
        synchronized (fileMonitor) {
            prepareStateDirectory();
            Path lockFile = stateFile.resolveSibling(stateFile.getFileName() + ".lock");
            if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(lockFile)) {
                throw stateUnavailable();
            }
            try (FileChannel channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                );
                FileLock ignored = channel.lock()) {
                enforceOwnerFilePermissions(lockFile);
                return operation.get();
            }
        }
    }

    private void prepareStateDirectory() throws Exception {
        Path directory = stateFile.getParent();
        if (directory == null) {
            throw stateUnavailable();
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw stateUnavailable();
            }
        } else {
            Files.createDirectories(directory);
        }
        enforcePermissions(directory, OWNER_DIRECTORY_PERMISSIONS);
    }

    private void enforceOwnerFilePermissions(Path path) throws Exception {
        enforcePermissions(path, OWNER_FILE_PERMISSIONS);
    }

    private void enforcePermissions(Path path, Set<PosixFilePermission> permissions) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }

    private void forceRenamedDirectories(Path source, Path target) throws Exception {
        Path sourceDirectory = source.getParent();
        Path targetDirectory = target.getParent();
        forceDirectory(targetDirectory);
        if (sourceDirectory != null && !sourceDirectory.equals(targetDirectory)) {
            forceDirectory(sourceDirectory);
        }
    }

    private void forceDirectory(Path directory) throws Exception {
        if (directory == null) {
            throw new IllegalStateException("A persisted data-management path has no parent directory");
        }
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            log.debug("Directory fsync is unsupported by the file store for {}", directory);
            return;
        }
        try (FileChannel channel = FileChannel.open(
            directory,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        )) {
            channel.force(true);
        } catch (UnsupportedOperationException error) {
            log.debug("Directory fsync is unsupported for {}", directory, error);
        }
    }

    private void requireOwnerOnlyRecoveryPermissions(Path path) throws Exception {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> forbidden = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE
        );
        if (permissions.stream().anyMatch(forbidden::contains)) {
            throw new IllegalStateException("Configured recovery key file is not owner-only");
        }
    }

    private boolean stateExists() {
        return Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean initializationMarkerExists() {
        return Files.exists(initializationMarkerFile, LinkOption.NOFOLLOW_LINKS);
    }

    private void ensureInitializationMarker() throws Exception {
        if (initializationMarkerExists()) {
            validateInitializationMarker();
            return;
        }
        prepareStateDirectory();
        byte[] bytes = INITIALIZATION_MARKER.getBytes(StandardCharsets.US_ASCII);
        Path temporary = Files.createTempFile(
            initializationMarkerFile.getParent(),
            ".data-management-initialized-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(OWNER_FILE_PERMISSIONS)
        );
        try {
            enforceOwnerFilePermissions(temporary);
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                    temporary,
                    initializationMarkerFile,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, initializationMarkerFile);
            }
            forceDirectory(initializationMarkerFile.getParent());
            validateInitializationMarker();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validateInitializationMarker() throws Exception {
        if (Files.isSymbolicLink(initializationMarkerFile)
            || !Files.isRegularFile(initializationMarkerFile, LinkOption.NOFOLLOW_LINKS)) {
            throw stateUnavailable();
        }
        enforceOwnerFilePermissions(initializationMarkerFile);
        long size = Files.size(initializationMarkerFile);
        if (size <= 0 || size > MAX_MARKER_BYTES) {
            throw stateUnavailable();
        }
        String marker = Files.readString(initializationMarkerFile, StandardCharsets.US_ASCII);
        if (!INITIALIZATION_MARKER.equals(marker)) {
            throw stateUnavailable();
        }
    }

    private byte[] derive(String securityKey, byte[] salt, int configuredIterations) throws Exception {
        char[] password = securityKey.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, configuredIterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(STATE_ALGORITHM).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }

    private String cleanKey(String securityKey) {
        if (securityKey == null || securityKey.isBlank() || securityKey.length() > 512) {
            return "";
        }
        return securityKey.strip();
    }

    private boolean constantEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
            first.getBytes(StandardCharsets.UTF_8),
            second.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean constantNullableEquals(String first, String second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        return constantEquals(first, second);
    }

    private Action parseAction(String requestedAction) {
        try {
            return Action.valueOf(requestedAction == null ? "" : requestedAction.strip());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DATA_MANAGEMENT_ACTION_INVALID",
                "Unsupported data management action"
            );
        }
    }

    private String normalizeTarget(Action action, String requestedTarget) {
        if (action == null) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DATA_MANAGEMENT_ACTION_INVALID",
                "Unsupported data management action"
            );
        }
        if (!action.targetRequired) {
            if (requestedTarget != null && !requestedTarget.isBlank()) {
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DATA_MANAGEMENT_TARGET_INVALID",
                    "This data management action does not accept a target"
                );
            }
            return null;
        }
        if (requestedTarget == null || requestedTarget.isBlank() || requestedTarget.length() > 255) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DATA_MANAGEMENT_TARGET_REQUIRED",
                "This data management action requires a specific target"
            );
        }
        return requestedTarget;
    }

    private void refreshLockSnapshot(PersistedState state) {
        if (recoveryCleanupFailed) {
            lockSnapshot = LockSnapshot.closedForFailure();
            return;
        }
        if (state == null) {
            lockSnapshot = observedInitializedState
                ? LockSnapshot.closedForFailure()
                : LockSnapshot.uninitialized();
            return;
        }
        observedInitializedState = true;
        lockSnapshot = new LockSnapshot(true, state.locked(), false);
    }

    private boolean isActiveAdmin(AdminUserEntity user) {
        return user != null
            && Boolean.TRUE.equals(user.getEnabled())
            && !Boolean.TRUE.equals(user.getFirstLogin())
            && ADMIN_ROLE.equalsIgnoreCase(user.getRole());
    }

    private DataTransferDtos.DataManagementSecurityStatusResponse toStatus(PersistedState state) {
        return state == null
            ? new DataTransferDtos.DataManagementSecurityStatusResponse(
                false,
                false,
                0,
                MAX_FAILED_ATTEMPTS,
                unlockTtl.toSeconds(),
                null,
                null,
                null,
                null
            )
            : new DataTransferDtos.DataManagementSecurityStatusResponse(
                true,
                state.locked(),
                state.failedAttempts(),
                MAX_FAILED_ATTEMPTS,
                unlockTtl.toSeconds(),
                state.initializedAt(),
                state.lastFailureAt(),
                state.lockedAt(),
                state.updatedAt()
            );
    }

    private ApiException databaseAdminRequired() {
        return new ApiException(
            HttpStatus.FORBIDDEN,
            "DATA_MANAGEMENT_DATABASE_ADMIN_REQUIRED",
            "Data management requires an active database-backed administrator"
        );
    }

    private ApiException invalidKeyError(int failures) {
        return new ApiException(
            HttpStatus.FORBIDDEN,
            "DATA_MANAGEMENT_SECURITY_KEY_INVALID",
            "Data management security key is invalid; consecutive failures: " + failures
        );
    }

    private ApiException lockedError() {
        return new ApiException(
            HttpStatus.LOCKED,
            "DATA_MANAGEMENT_SECURITY_LOCKED",
            "Data management security is permanently locked and requires operator recovery"
        );
    }

    private ApiException unlockRequired() {
        return new ApiException(
            HttpStatus.FORBIDDEN,
            "DATA_MANAGEMENT_UNLOCK_REQUIRED",
            "A valid data management unlock token is required"
        );
    }

    private ApiException stateUnavailable() {
        return new ApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "DATA_MANAGEMENT_SECURITY_STATE_UNAVAILABLE",
            "Data management security state is unavailable or invalid"
        );
    }

    private record PersistedState(
        int version,
        String algorithm,
        int iterations,
        int keyLengthBits,
        String salt,
        String verifier,
        int failedAttempts,
        boolean locked,
        Instant initializedAt,
        Instant lastFailureAt,
        Instant lockedAt,
        Instant updatedAt,
        List<DataManagementSecurityIncident> pendingIncidents
    ) {
    }

    private record InitializationResult(String securityKey, PersistedState state) {
    }

    private record UnlockGrant(
        String userId,
        String sessionFingerprint,
        Action action,
        String target,
        Instant expiresAt
    ) {

        private boolean sameBinding(UnlockGrant other) {
            return constantValueEquals(userId, other.userId)
                && constantValueEquals(sessionFingerprint, other.sessionFingerprint)
                && action == other.action
                && constantNullableValueEquals(target, other.target);
        }

        private static boolean constantValueEquals(String first, String second) {
            return first != null && first.equals(second);
        }

        private static boolean constantNullableValueEquals(String first, String second) {
            return first == null ? second == null : first.equals(second);
        }
    }

    private record LockSnapshot(boolean initialized, boolean locked, boolean failClosed) {

        private static LockSnapshot uninitialized() {
            return new LockSnapshot(false, false, false);
        }

        private static LockSnapshot closedForFailure() {
            return new LockSnapshot(false, true, true);
        }

        private boolean blocksRequests() {
            return locked || failClosed;
        }
    }

    private record UnlockAttempt(boolean success, ApiException error, DataManagementSecurityIncident incident) {

        private static UnlockAttempt succeeded() {
            return new UnlockAttempt(true, null, null);
        }

        private static UnlockAttempt failed(ApiException error, DataManagementSecurityIncident incident) {
            return new UnlockAttempt(false, error, incident);
        }
    }

    private record RecoveryResult(DataManagementSecurityIncident incident, boolean persisted) {
    }

    private record RequestContext(
        String remoteAddress,
        String forwardedFor,
        String userAgent,
        String sessionFingerprint,
        String endpoint,
        String requestId
    ) {
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {

        T get() throws Exception;
    }
}
