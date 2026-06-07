package xyz.star4y.api.admin;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.common.Hashing;

@Service
public class AdminUserService {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_ROLE = "ADMIN";
    private static final String DEFAULT_USER_ROLE = "USER";

    private final AdminUserRepository repository;
    private final PasswordHasher passwordHasher;
    private final AdminSessionService sessionService;
    private final RegistrationSettingsService registrationSettingsService;
    private final EmailVerificationService emailVerificationService;

    public AdminUserService(
        AdminUserRepository repository,
        PasswordHasher passwordHasher,
        AdminSessionService sessionService,
        RegistrationSettingsService registrationSettingsService,
        EmailVerificationService emailVerificationService
    ) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.sessionService = sessionService;
        this.registrationSettingsService = registrationSettingsService;
        this.emailVerificationService = emailVerificationService;
    }

    public Mono<BootstrapAdmin> ensureDefaultAdmin() {
        return repository.count()
            .flatMap(count -> {
                if (count > 0) {
                    return existingBootstrap();
                }
                String password = passwordHasher.randomPassword();
                AdminUserEntity entity = new AdminUserEntity();
                entity.setUserId(Hashing.shortUuid());
                entity.setUsername(DEFAULT_ADMIN_USERNAME);
                entity.setDisplayName("Default Administrator");
                entity.setRole(DEFAULT_ADMIN_ROLE);
                entity.setBalance(BigDecimal.ZERO);
                entity.setEnabled(true);
                entity.setFirstLogin(true);
                applyPassword(entity, password);
                return repository.save(entity)
                    .map(saved -> BootstrapAdmin.created(saved.getUsername(), password))
                    .onErrorResume(DuplicateKeyException.class, error -> existingBootstrap());
            });
    }

    public Flux<AdminUserEntity> list() {
        return repository.findAll();
    }

    public Mono<AdminUserEntity> currentUser(AdminPrincipal principal) {
        return repository.findByUserId(principal.userId())
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Invalid or expired admin session")))
            .flatMap(entity -> {
                if (Boolean.FALSE.equals(entity.getEnabled())) {
                    return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Account is disabled"));
                }
                return Mono.just(entity);
            });
    }

    public Mono<AdminDtos.AdminUserCreatedResponse> create(AdminDtos.CreateAdminUserRequest request) {
        String password = request.password() == null || request.password().isBlank()
            ? passwordHasher.randomPassword()
            : request.password();
        AdminUserEntity entity = new AdminUserEntity();
        entity.setUserId(Hashing.shortUuid());
        entity.setUsername(normalizeUsername(request.username()));
        entity.setEmail(RegistrationEmailPolicy.normalizeEmail(request.email()));
        entity.setDisplayName(request.displayName());
        entity.setRole(normalizeRole(request.role(), DEFAULT_USER_ROLE));
        entity.setBalance(normalizeBalance(request.balance()));
        entity.setEnabled(true);
        entity.setFirstLogin(true);
        applyPassword(entity, password);
        return repository.save(entity)
            .map(saved -> new AdminDtos.AdminUserCreatedResponse(AdminUserMapper.toResponse(saved), password))
            .onErrorMap(
                DuplicateKeyException.class,
                error -> new ApiException(HttpStatus.CONFLICT, "ADMIN_USER_EXISTS", "Admin username already exists")
            );
    }

    public Mono<AdminDtos.LoginResponse> register(AdminDtos.RegisterRequest request) {
        String username = normalizeUsername(request.username());
        String email = RegistrationEmailPolicy.normalizeEmail(request.email());
        AdminUserEntity entity = new AdminUserEntity();
        entity.setUserId(Hashing.shortUuid());
        entity.setUsername(username);
        entity.setEmail(email);
        entity.setDisplayName(firstNonBlank(request.displayName(), username));
        entity.setRole(DEFAULT_USER_ROLE);
        entity.setBalance(BigDecimal.ZERO);
        entity.setEnabled(true);
        entity.setFirstLogin(false);
        applyPassword(entity, request.password());

        return registrationSettingsService.ensureRegistrationAllowed(email)
            .then(emailVerificationService.verifyRegistrationCode(email, request.verifyCode()))
            .then(Mono.defer(() -> repository.save(entity)))
            .map(this::loginResponse)
            .onErrorMap(
                DuplicateKeyException.class,
                error -> new ApiException(HttpStatus.CONFLICT, "ADMIN_USER_EXISTS", "Username or email already exists")
            );
    }

    public Mono<AdminUserEntity> update(String userId, AdminDtos.UpdateAdminUserRequest request) {
        return repository.findByUserId(userId)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "Admin user not found")))
            .flatMap(entity -> {
                if (request.email() != null) entity.setEmail(RegistrationEmailPolicy.normalizeEmail(request.email()));
                if (request.displayName() != null) entity.setDisplayName(request.displayName());
                if (request.role() != null) entity.setRole(normalizeRole(request.role(), entity.getRole()));
                if (request.balance() != null) entity.setBalance(normalizeBalance(request.balance()));
                if (request.enabled() != null) entity.setEnabled(request.enabled());
                return repository.save(entity);
            })
            .onErrorMap(
                DuplicateKeyException.class,
                error -> new ApiException(HttpStatus.CONFLICT, "ADMIN_USER_EXISTS", "Username or email already exists")
            );
    }

    public Mono<AdminDtos.ResetPasswordResponse> resetPassword(String userId) {
        return repository.findByUserId(userId)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "Admin user not found")))
            .flatMap(entity -> {
                String password = passwordHasher.randomPassword();
                applyPassword(entity, password);
                entity.setFirstLogin(true);
                return repository.save(entity)
                    .map(saved -> new AdminDtos.ResetPasswordResponse(saved.getUserId(), saved.getUsername(), password));
            });
    }

    public Mono<AdminDtos.LoginResponse> login(AdminDtos.LoginRequest request) {
        return findLoginUser(request.username())
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "Invalid username or password")))
            .flatMap(entity -> {
                if (Boolean.FALSE.equals(entity.getEnabled())
                    || !passwordHasher.matches(request.password(), entity.getPasswordSalt(), entity.getPasswordHash())) {
                    return Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "Invalid username or password"));
                }
                entity.setLastLoginAt(Instant.now());
                return repository.save(entity).map(this::loginResponse);
            });
    }

    public Mono<Void> changePassword(AdminPrincipal principal, AdminDtos.ChangePasswordRequest request) {
        return repository.findByUserId(principal.userId())
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ADMIN_USER_NOT_FOUND", "Admin user not found")))
            .flatMap(entity -> {
                if (!passwordHasher.matches(request.oldPassword(), entity.getPasswordSalt(), entity.getPasswordHash())) {
                    return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "OLD_PASSWORD_INVALID", "Old password is invalid"));
                }
                applyPassword(entity, request.newPassword());
                entity.setFirstLogin(false);
                return repository.save(entity)
                    .doOnSuccess(saved -> sessionService.clearFirstLogin(saved.getUserId()))
                    .then();
            });
    }

    private Mono<BootstrapAdmin> existingBootstrap() {
        return repository.findByUsername(DEFAULT_ADMIN_USERNAME)
            .map(entity -> BootstrapAdmin.existing(entity.getUsername(), Boolean.TRUE.equals(entity.getFirstLogin())))
            .defaultIfEmpty(BootstrapAdmin.existing(null, false));
    }

    private void applyPassword(AdminUserEntity entity, String password) {
        String salt = passwordHasher.newSalt();
        entity.setPasswordSalt(salt);
        entity.setPasswordHash(passwordHasher.hash(password, salt));
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

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
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
