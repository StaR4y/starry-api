package tech.starryapi.admin;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;

class AdminUserServiceTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void keepsExternalRegistrationChecksOutsideThePersistenceTransaction() throws Exception {
        assertThat(AdminUserService.class.getMethod(
            "register",
            AdminDtos.RegisterRequest.class,
            String.class
        ).isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(RegistrationPersistenceService.class.getMethod(
            "register",
            AdminUserEntity.class,
            String.class,
            String.class,
            RegistrationSettingsService.RegistrationPolicy.class,
            String.class
        ).isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void registersUserWhenRegistrationPolicyAllowsEmail() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);

        when(settingsService.registrationPolicy()).thenReturn(Mono.just(publicRegistrationPolicy()));
        when(repository.save(any(AdminUserEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        AdminDtos.LoginResponse response = service.register(new AdminDtos.RegisterRequest(
            "new-user",
            "User@Example.COM",
            "New User",
            "secret123",
            "123456",
            null
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.accessToken()).startsWith("adm-");
        assertThat(response.user().username()).isEqualTo("new-user");
        assertThat(response.user().email()).isEqualTo("user@example.com");
        assertThat(response.user().firstLogin()).isFalse();

        ArgumentCaptor<AdminUserEntity> captor = ArgumentCaptor.forClass(AdminUserEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(captor.getValue().getRole()).isEqualTo("USER");
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getEnabled()).isTrue();
        assertThat(captor.getValue().getFirstLogin()).isFalse();
        assertThat(captor.getValue().getAffiliateCode()).isNotBlank();
    }

    @Test
    void registersUserWithAffiliateCodeAndRecordsInviteRelation() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);
        AdminUserEntity inviter = user("inviter-1", "inviter", "inviter@example.com", "secret123");
        inviter.setAffiliateCode("ffOabc");

        when(settingsService.registrationPolicy()).thenReturn(Mono.just(publicRegistrationPolicy()));
        when(repository.findEnabledByAffiliateCodeForShare("ffOabc")).thenReturn(Mono.just(inviter));
        when(repository.save(any(AdminUserEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        AdminDtos.LoginResponse response = service.register(new AdminDtos.RegisterRequest(
            "invited-user",
            "invited@example.com",
            "Invited User",
            "secret123",
            "123456",
            null,
            null,
            null,
            "ffOabc"
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.user().inviteSourceCode()).isEqualTo("ffOabc");
        assertThat(response.user().invitedByUserId()).isEqualTo("inviter-1");

        ArgumentCaptor<AdminUserEntity> captor = ArgumentCaptor.forClass(AdminUserEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getInvitedByUserId()).isEqualTo("inviter-1");
        assertThat(captor.getValue().getInviteSourceCode()).isEqualTo("ffOabc");
        assertThat(captor.getValue().getAffiliateCode()).isNotBlank();
    }

    @Test
    void createsAccountWithRoleAndBalance() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserEntity adjusted = user("created-user", "normal-user", "user@example.com", "secret123");
        adjusted.setRole("USER");
        adjusted.setBalance(new BigDecimal("25.500000"));
        SqlTemplateHarness sql = sqlTemplateReturning(adjusted);
        AdminUserService service = service(repository, settingsService, sql.template());

        when(repository.save(any(AdminUserEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        AdminDtos.AdminUserCreatedResponse response = service.create(new AdminDtos.CreateAdminUserRequest(
            "normal-user",
            "user@example.com",
            "Normal User",
            "USER",
            new BigDecimal("25.500000"),
            "secret123"
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.user().role()).isEqualTo("USER");
        assertThat(response.user().balance()).isEqualByComparingTo("25.500000");

        ArgumentCaptor<AdminUserEntity> captor = ArgumentCaptor.forClass(AdminUserEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sql.sql()).contains("'INITIAL_BALANCE'", "INSERT INTO balance_ledger");
    }

    @Test
    void doesNotCreateUserWhenRegistrationIsDisabled() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);

        when(settingsService.registrationPolicy()).thenReturn(Mono.just(new RegistrationSettingsService.RegistrationPolicy(
            false,
            false,
            false,
            java.util.List.of()
        )));

        assertThatThrownBy(() -> service.register(new AdminDtos.RegisterRequest(
            "new-user",
            "user@example.com",
            null,
            "secret123",
            null,
            null
        )).block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("User registration is disabled");

        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void doesNotCreateUserWhenEmailVerificationFails() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        AdminUserService service = service(repository, settingsService, emailVerificationService);
        ApiException invalidCode = new ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "INVALID_VERIFY_CODE",
            "Email verification code is invalid or expired"
        );

        when(settingsService.registrationPolicy()).thenReturn(Mono.just(publicRegistrationPolicy()));
        when(emailVerificationService.verifiedRegistrationTokenHash("user@example.com", "000000"))
            .thenReturn(Mono.error(invalidCode));

        assertThatThrownBy(() -> service.register(new AdminDtos.RegisterRequest(
            "new-user",
            "user@example.com",
            null,
            "secret123",
            "000000",
            null
        )).block()).isSameAs(invalidCode);

        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void registersUserWithInviteCodeWhenPublicRegistrationIsDisabled() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        InviteCodeService inviteCodeService = mock(InviteCodeService.class);
        AdminUserService service = service(repository, settingsService, emailVerificationService, inviteCodeService);

        when(settingsService.registrationPolicy()).thenReturn(Mono.just(new RegistrationSettingsService.RegistrationPolicy(
            false,
            true,
            true,
            java.util.List.of("@locked.example")
        )));
        when(repository.save(any(AdminUserEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(inviteCodeService.consume(eq("INV-ABCD-EFGH-IJKL"), any())).thenReturn(Mono.empty());

        AdminDtos.LoginResponse response = service.register(new AdminDtos.RegisterRequest(
            "invite-user",
            "outside@example.com",
            null,
            "secret123",
            null,
            "INV-ABCD-EFGH-IJKL"
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.user().username()).isEqualTo("invite-user");
        assertThat(response.user().email()).isEqualTo("outside@example.com");
        verify(emailVerificationService, never()).verifyRegistrationToken(any(), any());
        verify(inviteCodeService).consume(eq("INV-ABCD-EFGH-IJKL"), any());
    }

    @Test
    void loginAcceptsEmailCredential() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserEntity user = user("u-1", "new-user", "user@example.com", "secret123");
        SqlTemplateHarness sql = sqlTemplateReturning(user);
        AdminUserService service = service(repository, settingsService, sql.template());

        when(repository.findByUsername("user@example.com")).thenReturn(Mono.empty());
        when(repository.findByEmail("user@example.com")).thenReturn(Mono.just(user));

        AdminDtos.LoginResponse response = service.login(new AdminDtos.LoginRequest("user@example.com", "secret123"))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.user().username()).isEqualTo("new-user");
        assertThat(response.user().email()).isEqualTo("user@example.com");
        assertThat(sql.sql()).contains("SET last_login_at", "RETURNING *");
        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void updatesUserBalance() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserEntity user = user("u-1", "normal-user", "user@example.com", "secret123");
        user.setRole("USER");
        user.setBalance(new BigDecimal("100.250000"));
        SqlTemplateHarness sql = sqlTemplateReturning(user);
        AdminUserService service = service(repository, settingsService, sql.template());

        AdminUserEntity response = service.update("u-1", new AdminDtos.UpdateAdminUserRequest(
            null,
            null,
            null,
            new BigDecimal("100.250000"),
            null
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.getBalance()).isEqualByComparingTo("100.250000");
        assertThat(sql.sql()).contains("'ADMIN_ADJUSTMENT'", "INSERT INTO balance_ledger", "FOR UPDATE");
        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void currentUserReturnsLatestBalance() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);
        AdminUserEntity user = user("u-1", "normal-user", "user@example.com", "secret123");
        user.setRole("USER");
        user.setBalance(new BigDecimal("42.750000"));

        when(repository.findByUserId("u-1")).thenReturn(Mono.just(user));

        AdminUserEntity response = service.currentUser(new AdminPrincipal(
            "u-1",
            "normal-user",
            "USER",
            java.time.Instant.now().plusSeconds(60),
            false
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.getBalance()).isEqualByComparingTo("42.750000");
    }

    private AdminUserService service(
        AdminUserRepository repository,
        RegistrationSettingsService settingsService
    ) {
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        when(emailVerificationService.verifyRegistrationCode(any(), any())).thenReturn(Mono.empty());
        when(emailVerificationService.verifyRegistrationToken(any(), any())).thenReturn(Mono.empty());
        InviteCodeService inviteCodeService = mock(InviteCodeService.class);
        when(inviteCodeService.consume(any(), any())).thenReturn(Mono.empty());
        return service(repository, settingsService, emailVerificationService, inviteCodeService, mock(R2dbcEntityTemplate.class));
    }

    private AdminUserService service(
        AdminUserRepository repository,
        RegistrationSettingsService settingsService,
        R2dbcEntityTemplate template
    ) {
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        when(emailVerificationService.verifyRegistrationCode(any(), any())).thenReturn(Mono.empty());
        when(emailVerificationService.verifyRegistrationToken(any(), any())).thenReturn(Mono.empty());
        InviteCodeService inviteCodeService = mock(InviteCodeService.class);
        when(inviteCodeService.consume(any(), any())).thenReturn(Mono.empty());
        return service(repository, settingsService, emailVerificationService, inviteCodeService, template);
    }

    private AdminUserService service(
        AdminUserRepository repository,
        RegistrationSettingsService settingsService,
        EmailVerificationService emailVerificationService
    ) {
        InviteCodeService inviteCodeService = mock(InviteCodeService.class);
        when(inviteCodeService.consume(any(), any())).thenReturn(Mono.empty());
        return service(repository, settingsService, emailVerificationService, inviteCodeService, mock(R2dbcEntityTemplate.class));
    }

    private AdminUserService service(
        AdminUserRepository repository,
        RegistrationSettingsService settingsService,
        EmailVerificationService emailVerificationService,
        InviteCodeService inviteCodeService
    ) {
        return service(
            repository,
            settingsService,
            emailVerificationService,
            inviteCodeService,
            mock(R2dbcEntityTemplate.class)
        );
    }

    private AdminUserService service(
        AdminUserRepository repository,
        RegistrationSettingsService settingsService,
        EmailVerificationService emailVerificationService,
        InviteCodeService inviteCodeService,
        R2dbcEntityTemplate template
    ) {
        TurnstileVerificationService turnstileVerificationService = mock(TurnstileVerificationService.class);
        when(turnstileVerificationService.verifyIfRequired(any(), any())).thenReturn(Mono.empty());
        when(turnstileVerificationService.verifyRequired(any(), any())).thenReturn(Mono.empty());
        when(emailVerificationService.verifiedRegistrationTokenHash(any(), any())).thenReturn(Mono.just("verified-token-hash"));
        when(emailVerificationService.consumeRegistrationToken(any(), any())).thenReturn(Mono.empty());
        when(settingsService.lockedRegistrationPolicy()).thenAnswer(invocation -> settingsService.registrationPolicy());
        return new AdminUserService(
            repository,
            passwordHasher,
            new AdminSessionService(new ProxyProperties()),
            settingsService,
            emailVerificationService,
            new RegistrationPersistenceService(repository, inviteCodeService, settingsService, emailVerificationService),
            turnstileVerificationService,
            template
        );
    }

    @SuppressWarnings("unchecked")
    private SqlTemplateHarness sqlTemplateReturning(AdminUserEntity returned) {
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<AdminUserEntity> fetch = mock(RowsFetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(any(String.class))).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(any(String.class), any())).thenReturn(spec);
        when(spec.bindNull(any(String.class), any())).thenReturn(spec);
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, AdminUserEntity>>any()))
            .thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(returned));
        return new SqlTemplateHarness(template, sql);
    }

    private record SqlTemplateHarness(R2dbcEntityTemplate template, AtomicReference<String> capturedSql) {
        String sql() {
            return capturedSql.get();
        }
    }

    private RegistrationSettingsService.RegistrationPolicy publicRegistrationPolicy() {
        return new RegistrationSettingsService.RegistrationPolicy(true, true, false, java.util.List.of());
    }

    private AdminUserEntity user(String userId, String username, String email, String password) {
        AdminUserEntity entity = new AdminUserEntity();
        entity.setUserId(userId);
        entity.setUsername(username);
        entity.setEmail(email);
        entity.setDisplayName(username);
        entity.setRole("ADMIN");
        entity.setBalance(BigDecimal.ZERO);
        entity.setEnabled(true);
        entity.setFirstLogin(false);
        String salt = passwordHasher.newSalt();
        entity.setPasswordSalt(salt);
        entity.setPasswordHash(passwordHasher.hash(password, salt));
        return entity;
    }
}
