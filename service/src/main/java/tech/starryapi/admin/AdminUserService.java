package tech.starryapi.admin;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;

@Service
public class AdminUserService {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_ROLE = "ADMIN";
    private static final String DEFAULT_USER_ROLE = "USER";
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final char[] AFFILIATE_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final int AFFILIATE_CODE_LENGTH = 8;

    private final AdminUserRepository repository;
    private final PasswordHasher passwordHasher;
    private final AdminSessionService sessionService;
    private final RegistrationSettingsService registrationSettingsService;
    private final EmailVerificationService emailVerificationService;
    private final RegistrationPersistenceService registrationPersistenceService;
    private final TurnstileVerificationService turnstileVerificationService;
    private final R2dbcEntityTemplate template;
    private final SecureRandom random = new SecureRandom();

    public AdminUserService(
        AdminUserRepository repository,
        PasswordHasher passwordHasher,
        AdminSessionService sessionService,
        RegistrationSettingsService registrationSettingsService,
        EmailVerificationService emailVerificationService,
        RegistrationPersistenceService registrationPersistenceService,
        TurnstileVerificationService turnstileVerificationService,
        R2dbcEntityTemplate template
    ) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.sessionService = sessionService;
        this.registrationSettingsService = registrationSettingsService;
        this.emailVerificationService = emailVerificationService;
        this.registrationPersistenceService = registrationPersistenceService;
        this.turnstileVerificationService = turnstileVerificationService;
        this.template = template;
    }

    @Transactional
    public Mono<BootstrapAdmin> ensureDefaultAdmin() {
        return repository.findApplicationRootUserIdWithLock()
            .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                "The application Root identity marker is missing; Flyway V6 must be applied"
            )))
            .flatMap(rootUserId -> rootUserId == null || rootUserId.isBlank()
                ? bootstrapUnassignedRoot()
                : existingBootstrap(rootUserId.trim()));
    }

    private Mono<BootstrapAdmin> bootstrapUnassignedRoot() {
        return repository.findDefaultAdminCandidate()
            .flatMap(candidate -> assignExistingRoot(candidate)
                .thenReturn(existingRootBootstrap(candidate)))
            .switchIfEmpty(Mono.defer(() -> repository.count()
                .flatMap(count -> count == 0
                    ? createAndAssignRoot()
                    : Mono.error(new IllegalStateException(
                        "The application Root identity is missing while other accounts already exist"
                    )))));
    }

    private Mono<BootstrapAdmin> createAndAssignRoot() {
        String password = passwordHasher.randomPassword();
        AdminUserEntity entity = new AdminUserEntity();
        entity.setUserId(Hashing.shortUuid());
        entity.setUsername(DEFAULT_ADMIN_USERNAME);
        entity.setDisplayName("Default Administrator");
        entity.setRole(DEFAULT_ADMIN_ROLE);
        entity.setBalance(BigDecimal.ZERO);
        entity.setEnabled(true);
        entity.setFirstLogin(true);
        assignAffiliateCode(entity);
        applyPassword(entity, password);
        // Only a deployment that starts on an empty database gets here, so only it asks Root for the legal consent.
        return repository.save(entity)
            .flatMap(saved -> assignExistingRoot(saved)
                .then(repository.requireLegalConsent())
                .thenReturn(BootstrapAdmin.created(saved.getUsername(), password)))
            .onErrorMap(DuplicateKeyException.class, error -> new IllegalStateException(
                "The application Root account could not be created atomically",
                error
            ));
    }

    private Mono<Void> assignExistingRoot(AdminUserEntity candidate) {
        existingRootBootstrap(candidate);
        return repository.assignApplicationRoot(candidate.getUserId())
            .filter(updated -> updated == 1)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                "The application Root identity marker could not be assigned"
            )))
            .then();
    }

    public Flux<AdminUserEntity> list() {
        return repository.findAll();
    }

    public Mono<AdminUserEntity> currentUser(AdminPrincipal principal) {
        return repository.findByUserId(principal.userId())
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Invalid or expired admin session")))
            .flatMap(entity -> {
                if (Boolean.FALSE.equals(entity.getEnabled())) {
                    return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Account is disabled"));
                }
                return Mono.just(entity);
            });
    }

    public Mono<AdminDtos.AdminPermissions> permissions(AdminPrincipal principal) {
        if (principal == null || !"ADMIN".equals(principal.role()) || principal.firstLogin()
            || "env-admin".equals(principal.userId())) {
            return Mono.just(new AdminDtos.AdminPermissions(false));
        }
        return repository.findApplicationRoot()
            .map(root -> new AdminDtos.AdminPermissions(
                root.getUserId() != null && root.getUserId().equals(principal.userId())
                    && "ADMIN".equals(root.getRole()) && Boolean.TRUE.equals(root.getEnabled())
                    && !Boolean.TRUE.equals(root.getFirstLogin())))
            .defaultIfEmpty(new AdminDtos.AdminPermissions(false));
    }

    @Transactional
    public Mono<AdminDtos.AdminUserCreatedResponse> create(AdminDtos.CreateAdminUserRequest request) {
        String username = normalizeUsername(request.username());
        rejectReservedRootUsername(username);
        String password = request.password() == null || request.password().isBlank()
            ? passwordHasher.randomPassword()
            : validateNewPassword(request.password());
        BigDecimal initialBalance = normalizeBalance(request.balance());
        AdminUserEntity entity = new AdminUserEntity();
        entity.setUserId(Hashing.shortUuid());
        entity.setUsername(username);
        entity.setEmail(RegistrationEmailPolicy.normalizeEmail(request.email()));
        entity.setDisplayName(request.displayName());
        entity.setRole(normalizeRole(request.role(), DEFAULT_USER_ROLE));
        entity.setBalance(BigDecimal.ZERO);
        entity.setEnabled(true);
        entity.setFirstLogin(true);
        assignAffiliateCode(entity);
        applyPassword(entity, password);
        return repository.save(entity)
            .flatMap(saved -> initialBalance.signum() == 0
                ? Mono.just(saved)
                : setInitialBalance(saved.getUserId(), initialBalance))
            .map(saved -> new AdminDtos.AdminUserCreatedResponse(AdminUserMapper.toResponse(saved), password))
            .onErrorMap(
                DuplicateKeyException.class,
                error -> new ApiException(HttpStatus.CONFLICT, "ADMIN_USER_EXISTS", "Admin username already exists")
            );
    }

    public Mono<AdminDtos.LoginResponse> register(AdminDtos.RegisterRequest request) {
        return register(request, null);
    }

    public Mono<AdminDtos.LoginResponse> register(AdminDtos.RegisterRequest request, String remoteIp) {
        String username = normalizeUsername(request.username());
        rejectReservedRootUsername(username);
        validateNewPassword(request.password());
        String email = RegistrationEmailPolicy.normalizeEmail(request.email());
        String inviteCode = normalizeInviteCode(request.inviteCode());
        String affiliateCode = normalizeAffiliateCode(request.affiliateCode());

        return registrationSettingsService.registrationPolicy()
            .flatMap(policy -> registrationGuard(
                    email,
                    firstNonBlank(request.emailVerificationToken(), request.verifyCode()),
                    inviteCode,
                    affiliateCode,
                    request.turnstileToken(),
                    remoteIp,
                    policy
                )
                .flatMap(verifiedEmailTokenHash -> Mono.fromCallable(() -> registrationUser(
                        request,
                        username,
                        email
                    ))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(entity -> registrationPersistenceService.register(
                        entity,
                        inviteCode,
                        affiliateCode,
                        policy,
                        verifiedEmailTokenHash
                    )))
            )
            .map(this::loginResponse)
            .onErrorMap(
                DuplicateKeyException.class,
                error -> new ApiException(HttpStatus.CONFLICT, "ADMIN_USER_EXISTS", "Username or email already exists")
            );
    }

    public Mono<AdminUserEntity> update(
        AdminPrincipal principal,
        String userId,
        AdminDtos.UpdateAdminUserRequest request
    ) {
        return Mono.defer(() -> {
            String normalizedRole = normalizeOptionalUpdateRole(request.role());
            return authorizeRootUpdate(principal, userId, request, normalizedRole)
                .then(Mono.defer(() -> updateAuthorized(userId, request, normalizedRole)));
        });
    }

    private Mono<AdminUserEntity> updateAuthorized(
        String userId,
        AdminDtos.UpdateAdminUserRequest request,
        String normalizedRole
    ) {
        boolean setEmail = request.email() != null;
        boolean setDisplayName = request.displayName() != null;
        boolean setRole = normalizedRole != null;
        boolean setBalance = request.balance() != null;
        boolean setEnabled = request.enabled() != null;
        BigDecimal balance = setBalance ? normalizeBalance(request.balance()) : BigDecimal.ZERO;
        String transactionId = Hashing.shortUuid();
        String sql = """
            WITH candidate AS MATERIALIZED (
                SELECT user_id, balance AS balance_before
                FROM admin_users
                WHERE user_id = :userId
                FOR UPDATE
            ), ledger_entry AS (
                INSERT INTO balance_ledger (
                    transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                    reference_type, reference_id, idempotency_key, metadata
                )
                SELECT :transactionId,
                       candidate.user_id,
                       'ADMIN_ADJUSTMENT',
                       :balance - candidate.balance_before,
                       candidate.balance_before,
                       :balance,
                       'ADMIN_USER',
                       candidate.user_id,
                       :idempotencyKey,
                       '{"source":"admin"}'::jsonb
                FROM candidate
                WHERE :setBalance
                  AND :balance <> candidate.balance_before
                  AND (CAST(:expectedBalance AS NUMERIC) IS NULL OR candidate.balance_before = CAST(:expectedBalance AS NUMERIC))
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING user_id
            )
            UPDATE admin_users AS u
            SET email = CASE WHEN :setEmail THEN :email ELSE u.email END,
                display_name = CASE WHEN :setDisplayName THEN :displayName ELSE u.display_name END,
                role = CASE WHEN :setRole THEN :role ELSE u.role END,
                balance = CASE WHEN :setBalance THEN :balance ELSE u.balance END,
                enabled = CASE WHEN :setEnabled THEN :enabled ELSE u.enabled END,
                updated_at = CURRENT_TIMESTAMP
            FROM candidate
            LEFT JOIN ledger_entry ON ledger_entry.user_id = candidate.user_id
            WHERE u.user_id = candidate.user_id
              AND (NOT :setBalance
                   OR CAST(:expectedBalance AS NUMERIC) IS NULL
                   OR candidate.balance_before = CAST(:expectedBalance AS NUMERIC))
            RETURNING u.*
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("userId", userId)
            .bind("transactionId", transactionId)
            .bind("idempotencyKey", "admin-adjustment:" + transactionId)
            .bind("setEmail", setEmail)
            .bind("setDisplayName", setDisplayName)
            .bind("setRole", setRole)
            .bind("setBalance", setBalance)
            .bind("balance", balance)
            .bind("setEnabled", setEnabled)
            .bind("enabled", Boolean.TRUE.equals(request.enabled()));
        spec = bindNullable(spec, "email", setEmail ? RegistrationEmailPolicy.normalizeEmail(request.email()) : null);
        spec = bindNullable(spec, "displayName", request.displayName());
        spec = bindNullable(spec, "role", normalizedRole);
        spec = request.expectedBalance() == null
            ? spec.bindNull("expectedBalance", BigDecimal.class)
            : spec.bind("expectedBalance", normalizeBalance(request.expectedBalance()));
        return spec
            .map((row, metadata) -> template.getConverter().read(AdminUserEntity.class, row, metadata))
            .one()
            .switchIfEmpty(Mono.defer(() -> setBalance && request.expectedBalance() != null
                ? repository.findByUserId(userId)
                    .flatMap(current -> Mono.<AdminUserEntity>error(new ApiException(
                        HttpStatus.CONFLICT,
                        "ADMIN_USER_BALANCE_CHANGED",
                        "The balance changed after this form was opened. Reload the user and try again."
                    )))
                    .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "Admin user not found")))
                : Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "Admin user not found"))))
            .onErrorMap(
                DuplicateKeyException.class,
                error -> new ApiException(HttpStatus.CONFLICT, "ADMIN_USER_EXISTS", "Username or email already exists")
            )
            .doOnNext(updated -> {
                if (setRole || setEnabled) {
                    sessionService.invalidateUserSessions(userId);
                }
            });
    }

    private Mono<AdminUserEntity> setInitialBalance(String userId, BigDecimal balance) {
        String sql = """
            WITH candidate AS MATERIALIZED (
                SELECT user_id, balance AS balance_before
                FROM admin_users
                WHERE user_id = :userId
                FOR UPDATE
            ), ledger_entry AS (
                INSERT INTO balance_ledger (
                    transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                    reference_type, reference_id, idempotency_key, metadata
                )
                SELECT :transactionId,
                       candidate.user_id,
                       'INITIAL_BALANCE',
                       :balance - candidate.balance_before,
                       candidate.balance_before,
                       :balance,
                       'ADMIN_USER',
                       candidate.user_id,
                       'initial-balance:' || candidate.user_id,
                       '{"source":"admin"}'::jsonb
                FROM candidate
                WHERE :balance <> candidate.balance_before
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING user_id, balance_after
            )
            UPDATE admin_users AS u
            SET balance = ledger_entry.balance_after,
                updated_at = CURRENT_TIMESTAMP
            FROM ledger_entry
            WHERE u.user_id = ledger_entry.user_id
            RETURNING u.*
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("userId", userId)
            .bind("transactionId", Hashing.shortUuid())
            .bind("balance", balance)
            .map((row, metadata) -> template.getConverter().read(AdminUserEntity.class, row, metadata))
            .one();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    public Mono<AdminDtos.ResetPasswordResponse> resetPassword(AdminPrincipal principal, String userId) {
        return authorizeRootAccess(principal, userId)
            .then(Mono.defer(() -> resetPasswordAuthorized(userId)));
    }

    private Mono<AdminDtos.ResetPasswordResponse> resetPasswordAuthorized(String userId) {
        String password = passwordHasher.randomPassword();
        AdminUserEntity credentials = new AdminUserEntity();
        applyPassword(credentials, password);
        String sql = """
            UPDATE admin_users
            SET password_hash = :passwordHash,
                password_salt = :passwordSalt,
                first_login = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId
            RETURNING user_id, username
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("passwordHash", credentials.getPasswordHash())
            .bind("passwordSalt", credentials.getPasswordSalt())
            .bind("userId", userId)
            .map((row, metadata) -> new AdminDtos.ResetPasswordResponse(
                row.get("user_id", String.class),
                row.get("username", String.class),
                password
            ))
            .one()
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "Admin user not found")))
            .doOnNext(response -> sessionService.invalidateUserSessions(response.userId()));
    }

    private Mono<Void> authorizeRootUpdate(
        AdminPrincipal principal,
        String targetUserId,
        AdminDtos.UpdateAdminUserRequest request,
        String normalizedRole
    ) {
        return repository.findApplicationRoot()
            .flatMap(root -> {
                if (!sameUser(root.getUserId(), targetUserId)) {
                    return Mono.empty();
                }
                if (!sameUser(root.getUserId(), principal == null ? null : principal.userId())) {
                    return rootProtected();
                }
                if (normalizedRole != null && !DEFAULT_ADMIN_ROLE.equals(normalizedRole)) {
                    return Mono.error(new ApiException(
                        HttpStatus.FORBIDDEN,
                        "APPLICATION_ROOT_INVARIANT",
                        "The system Root account must retain the ADMIN role"
                    ));
                }
                if (Boolean.FALSE.equals(request.enabled())) {
                    return Mono.error(new ApiException(
                        HttpStatus.FORBIDDEN,
                        "APPLICATION_ROOT_INVARIANT",
                        "The system Root account cannot be disabled"
                    ));
                }
                return Mono.empty();
            })
            .then();
    }

    private Mono<Void> authorizeRootAccess(AdminPrincipal principal, String targetUserId) {
        return repository.findApplicationRoot()
            .flatMap(root -> sameUser(root.getUserId(), targetUserId)
                && !sameUser(root.getUserId(), principal == null ? null : principal.userId())
                ? rootProtected()
                : Mono.empty())
            .then();
    }

    private Mono<Void> rootProtected() {
        return Mono.error(new ApiException(
            HttpStatus.FORBIDDEN,
            "APPLICATION_ROOT_PROTECTED",
            "Only the system Root account can modify or reset the system Root account"
        ));
    }

    private boolean sameUser(String left, String right) {
        return left != null && left.equals(right);
    }

    public Mono<AdminDtos.LoginResponse> login(AdminDtos.LoginRequest request) {
        return findLoginUser(request.username())
            // Hash a throwaway salt for unknown usernames so the response time does not reveal which accounts exist.
            .switchIfEmpty(Mono.defer(() -> Mono.fromCallable(() -> passwordHasher.hash(request.password(), passwordHasher.newSalt()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.<AdminUserEntity>error(new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "Invalid username or password")))))
            // PBKDF2 is CPU-bound (tens of milliseconds): never run it on a Netty event-loop thread.
            .flatMap(entity -> Mono.fromCallable(() -> passwordHasher.matches(request.password(), entity.getPasswordSalt(), entity.getPasswordHash()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(matches -> matches && !Boolean.FALSE.equals(entity.getEnabled()))
                .flatMap(authenticated -> authenticated ? Mono.just(entity)
                    : Mono.<AdminUserEntity>error(new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "Invalid username or password"))))
            .flatMap(entity -> {
                String sql = """
                    UPDATE admin_users
                    SET last_login_at = :now,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = :userId
                      AND enabled = TRUE
                    RETURNING *
                    """;
                return template.getDatabaseClient().sql(sql)
                    .bind("now", Instant.now())
                    .bind("userId", entity.getUserId())
                    .map((row, metadata) -> template.getConverter().read(AdminUserEntity.class, row, metadata))
                    .one()
                    .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "Invalid username or password")))
                    .map(this::loginResponse);
            });
    }

    public void logout(String token) {
        sessionService.invalidateSession(token);
    }

    public Mono<Void> changePassword(AdminPrincipal principal, AdminDtos.ChangePasswordRequest request) {
        validateNewPassword(request.newPassword());
        return repository.findByUserId(principal.userId())
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "Admin user not found")))
            .flatMap(entity -> Mono.fromCallable(() -> {
                    if (!passwordHasher.matches(request.oldPassword(), entity.getPasswordSalt(), entity.getPasswordHash())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "OLD_PASSWORD_INVALID", "Old password is invalid");
                    }
                    applyPassword(entity, request.newPassword());
                    return entity;
                }).subscribeOn(Schedulers.boundedElastic()))
            .flatMap(entity -> {
                String sql = """
                    UPDATE admin_users
                    SET password_hash = :passwordHash,
                        password_salt = :passwordSalt,
                        first_login = FALSE,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE user_id = :userId
                    """;
                return template.getDatabaseClient().sql(sql)
                    .bind("passwordHash", entity.getPasswordHash())
                    .bind("passwordSalt", entity.getPasswordSalt())
                    .bind("userId", entity.getUserId())
                    .fetch()
                    .rowsUpdated()
                    .filter(rows -> rows > 0)
                    .switchIfEmpty(Mono.error(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "ADMIN_USER_NOT_FOUND",
                        "Admin user not found"
                    )))
                    .doOnNext(rows -> sessionService.invalidateUserSessions(entity.getUserId()))
                    .then();
            });
    }

    private Mono<BootstrapAdmin> existingBootstrap(String rootUserId) {
        return repository.findByUserId(rootUserId)
            .map(this::existingRootBootstrap)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                "The application Root identity marker references a missing account"
            )));
    }

    private BootstrapAdmin existingRootBootstrap(AdminUserEntity entity) {
        if (entity == null
            || !DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(entity.getUsername())
            || !DEFAULT_ADMIN_ROLE.equalsIgnoreCase(entity.getRole())
            || !Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalStateException("The application Root account violates its required identity state");
        }
        return BootstrapAdmin.existing(entity.getUsername(), Boolean.TRUE.equals(entity.getFirstLogin()));
    }

    private void applyPassword(AdminUserEntity entity, String password) {
        String salt = passwordHasher.newSalt();
        entity.setPasswordSalt(salt);
        entity.setPasswordHash(passwordHasher.hash(password, salt));
    }

    private String validateNewPassword(String password) {
        if (password == null
            || password.length() < MIN_PASSWORD_LENGTH
            || password.length() > MAX_PASSWORD_LENGTH) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "PASSWORD_POLICY_VIOLATION",
                "New passwords must contain 12 to 128 characters"
            );
        }
        return password;
    }

    private void assignAffiliateCode(AdminUserEntity entity) {
        if (entity.getAffiliateCode() == null || entity.getAffiliateCode().isBlank()) {
            entity.setAffiliateCode(generateAffiliateCode());
        }
    }

    private Mono<AdminUserEntity> findLoginUser(String usernameOrEmail) {
        String credential = normalizeUsername(usernameOrEmail);
        Mono<AdminUserEntity> byUsername = repository.findByUsername(credential);
        String email = RegistrationEmailPolicy.normalizeEmail(credential);
        if (email == null || !email.contains("@")) {
            return byUsername;
        }
        return byUsername.switchIfEmpty(repository.findByEmail(email));
    }

    private AdminDtos.LoginResponse loginResponse(AdminUserEntity user) {
        AdminSessionService.SessionToken token = sessionService.create(user);
        return new AdminDtos.LoginResponse(
            "Bearer",
            token.token(),
            token.expiresAt(),
            AdminUserMapper.toResponse(user)
        );
    }

    private AdminUserEntity registrationUser(
        AdminDtos.RegisterRequest request,
        String username,
        String email
    ) {
        AdminUserEntity entity = new AdminUserEntity();
        entity.setUserId(Hashing.shortUuid());
        entity.setUsername(username);
        entity.setEmail(email);
        entity.setDisplayName(firstNonBlank(request.displayName(), username));
        entity.setRole(DEFAULT_USER_ROLE);
        entity.setBalance(BigDecimal.ZERO);
        entity.setEnabled(true);
        entity.setFirstLogin(false);
        assignAffiliateCode(entity);
        applyPassword(entity, request.password());
        return entity;
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private void rejectReservedRootUsername(String username) {
        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "APPLICATION_ROOT_USERNAME_RESERVED",
                "The default administrator username is reserved for the system Root account"
            );
        }
    }

    private Mono<String> registrationGuard(
        String email,
        String verificationToken,
        String inviteCode,
        String affiliateCode,
        String turnstileToken,
        String remoteIp,
        RegistrationSettingsService.RegistrationPolicy policy
    ) {
        RegistrationPolicyGuard.VerificationRequirement requirement = RegistrationPolicyGuard.validate(
            email,
            inviteCode,
            affiliateCode,
            policy
        );
        if (requirement == RegistrationPolicyGuard.VerificationRequirement.EMAIL) {
            return emailVerificationService.verifiedRegistrationTokenHash(email, verificationToken);
        }
        return policy.turnstileEnabled()
            ? turnstileVerificationService.verifyRequired(turnstileToken, remoteIp).thenReturn("")
            : Mono.just("");
    }

    private String normalizeInviteCode(String inviteCode) {
        return inviteCode == null ? "" : inviteCode.trim();
    }

    private String normalizeAffiliateCode(String affiliateCode) {
        return affiliateCode == null ? "" : affiliateCode.trim();
    }

    private String generateAffiliateCode() {
        StringBuilder builder = new StringBuilder(AFFILIATE_CODE_LENGTH);
        for (int index = 0; index < AFFILIATE_CODE_LENGTH; index++) {
            builder.append(AFFILIATE_ALPHABET[random.nextInt(AFFILIATE_ALPHABET.length)]);
        }
        return builder.toString();
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeRole(String role, String fallback) {
        if (role == null || role.isBlank()) {
            return fallback == null || fallback.isBlank() ? DEFAULT_USER_ROLE : fallback.trim().toUpperCase();
        }
        String normalized = role.trim().toUpperCase();
        if (DEFAULT_ADMIN_ROLE.equals(normalized) || DEFAULT_USER_ROLE.equals(normalized)) {
            return normalized;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_ROLE", "Account role must be ADMIN or USER");
    }

    private String normalizeOptionalUpdateRole(String role) {
        if (role == null) {
            return null;
        }
        if (role.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_ROLE", "Account role must be ADMIN or USER");
        }
        return normalizeRole(role, null);
    }

    private BigDecimal normalizeBalance(BigDecimal balance) {
        if (balance == null) {
            return BigDecimal.ZERO;
        }
        if (balance.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BALANCE", "Account balance must not be negative");
        }
        return balance;
    }

    public record BootstrapAdmin(boolean created, String username, String password, boolean firstLogin) {
        static BootstrapAdmin created(String username, String password) {
            return new BootstrapAdmin(true, username, password, true);
        }

        static BootstrapAdmin existing(String username, boolean firstLogin) {
            return new BootstrapAdmin(false, username, null, firstLogin);
        }
    }

}
