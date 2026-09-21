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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;

class AdminUserServiceTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void permissionsUsePersistedRootIdentityRatherThanUsernameOrAdminRole() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        AdminUserService service = service(repository, mock(RegistrationSettingsService.class));
        AdminUserEntity root = user("root-1", "initial-admin", "root@example.com", "secret123456");
        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));

        assertThat(service.permissions(new AdminPrincipal("root-1", "initial-admin", "ADMIN", null, false))
            .block().root()).isTrue();
        assertThat(service.permissions(new AdminPrincipal("other-admin", "admin", "ADMIN", null, false))
            .block().root()).isFalse();
        verify(repository, never()).findByUsername(any());
    }

    @Test
    void missingOrInactiveRootCannotReceiveRootPermissions() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        AdminUserService service = service(repository, mock(RegistrationSettingsService.class));
        AdminPrincipal principal = new AdminPrincipal("root-1", "admin", "ADMIN", null, false);
        when(repository.findApplicationRoot()).thenReturn(Mono.empty());
        assertThat(service.permissions(principal).block().root()).isFalse();
        for (String state : java.util.List.of("disabled", "demoted", "firstLogin")) {
            AdminUserEntity root = user("root-1", "admin", "root@example.com", "secret123456");
            if (state.equals("disabled")) root.setEnabled(false);
            if (state.equals("demoted")) root.setRole("USER");
            if (state.equals("firstLogin")) root.setFirstLogin(true);
            when(repository.findApplicationRoot()).thenReturn(Mono.just(root));
            assertThat(service.permissions(principal).block().root()).as(state).isFalse();
        }
    }

    @Test
    void staticTokenAndUserSessionsNeverReceiveRootPermissions() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        AdminUserService service = service(repository, mock(RegistrationSettingsService.class));
        for (AdminPrincipal principal : java.util.List.of(
            new AdminPrincipal("env-admin", "env-admin", "ADMIN", null, false),
            new AdminPrincipal("root-1", "admin", "USER", null, false),
            new AdminPrincipal("root-1", "admin", "ADMIN", null, true))) {
            assertThat(service.permissions(principal).block().root()).isFalse();
        }
        verify(repository, never()).findApplicationRoot();
    }

    @Test
    void createsAndAnchorsTheApplicationRootOnlyInAnEmptyDatabase() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);
        when(repository.findApplicationRootUserIdWithLock()).thenReturn(Mono.just(""));
        when(repository.findDefaultAdminCandidate()).thenReturn(Mono.empty());
        when(repository.count()).thenReturn(Mono.just(0L));
        when(repository.save(any(AdminUserEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(repository.assignApplicationRoot(any())).thenReturn(Mono.just(1));

        AdminUserService.BootstrapAdmin bootstrap = service.ensureDefaultAdmin().block();

        assertThat(bootstrap).isNotNull();
        assertThat(bootstrap.created()).isTrue();
        assertThat(bootstrap.username()).isEqualTo("admin");
        assertThat(bootstrap.password()).isNotBlank();
        ArgumentCaptor<AdminUserEntity> captor = ArgumentCaptor.forClass(AdminUserEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("admin");
        assertThat(captor.getValue().getRole()).isEqualTo("ADMIN");
        verify(repository).assignApplicationRoot(captor.getValue().getUserId());
        verify(repository).count();
    }

    @Test
    void refusesToRecreateTheRootWhenOtherAccountsAlreadyExist() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);
        when(repository.findApplicationRootUserIdWithLock()).thenReturn(Mono.just(""));
        when(repository.findDefaultAdminCandidate()).thenReturn(Mono.empty());
        when(repository.count()).thenReturn(Mono.just(2L));

        assertThatThrownBy(() -> service.ensureDefaultAdmin().block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Root identity is missing");

        verify(repository, never()).save(any(AdminUserEntity.class));
        verify(repository, never()).assignApplicationRoot(any());
    }

    @Test
    void anchorsAHealthyLegacyRootWhenTheMarkerIsEmpty() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);
        AdminUserEntity root = user("root-1", "admin", "root@example.com", "secret123456");
        when(repository.findApplicationRootUserIdWithLock()).thenReturn(Mono.just(""));
        when(repository.findDefaultAdminCandidate()).thenReturn(Mono.just(root));
        when(repository.assignApplicationRoot("root-1")).thenReturn(Mono.just(1));

        AdminUserService.BootstrapAdmin bootstrap = service.ensureDefaultAdmin().block();

        assertThat(bootstrap).isNotNull();
        assertThat(bootstrap.created()).isFalse();
        assertThat(bootstrap.username()).isEqualTo("admin");
        verify(repository).assignApplicationRoot("root-1");
        verify(repository, never()).count();
        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void rejectsAStoredRootIdentityThatHasLostItsRequiredAdminState() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);
        AdminUserEntity corruptedRoot = user("root-1", "admin", "root@example.com", "secret123456");
        corruptedRoot.setRole("USER");
        when(repository.findApplicationRootUserIdWithLock()).thenReturn(Mono.just("root-1"));
        when(repository.findByUserId("root-1")).thenReturn(Mono.just(corruptedRoot));

        assertThatThrownBy(() -> service.ensureDefaultAdmin().block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Root account violates");

        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void rejectsAMissingOrDanglingRootIdentityMarker() {
        AdminUserRepository missingMarkerRepository = mock(AdminUserRepository.class);
        AdminUserService missingMarkerService = service(
            missingMarkerRepository,
            mock(RegistrationSettingsService.class)
        );
        when(missingMarkerRepository.findApplicationRootUserIdWithLock()).thenReturn(Mono.empty());

        assertThatThrownBy(() -> missingMarkerService.ensureDefaultAdmin().block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("identity marker is missing");

        AdminUserRepository danglingMarkerRepository = mock(AdminUserRepository.class);
        AdminUserService danglingMarkerService = service(
            danglingMarkerRepository,
            mock(RegistrationSettingsService.class)
        );
        when(danglingMarkerRepository.findApplicationRootUserIdWithLock()).thenReturn(Mono.just("missing-root"));
        when(danglingMarkerRepository.findByUserId("missing-root")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> danglingMarkerService.ensureDefaultAdmin().block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("references a missing account");
    }

    @Test
    void reservesTheApplicationRootUsernameAcrossAdminCreationAndRegistration() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);

        assertThatThrownBy(() -> service.create(new AdminDtos.CreateAdminUserRequest(
            " AdMiN ",
            "other@example.com",
            null,
            "ADMIN",
            BigDecimal.ZERO,
            "secret123456"
        )))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("APPLICATION_ROOT_USERNAME_RESERVED"));

        assertThatThrownBy(() -> service.register(new AdminDtos.RegisterRequest(
            "ADMIN",
            "other@example.com",
            null,
            "secret123456",
            null,
            null
        )))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("APPLICATION_ROOT_USERNAME_RESERVED"));

        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void enforcesTheNewPasswordPolicyAtTheServiceBoundary() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);

        assertThatThrownBy(() -> service.create(new AdminDtos.CreateAdminUserRequest(
            "normal-user",
            "normal@example.com",
            null,
            "USER",
            BigDecimal.ZERO,
            "too-short"
        )))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("PASSWORD_POLICY_VIOLATION"));

        assertThatThrownBy(() -> service.register(new AdminDtos.RegisterRequest(
            "normal-user",
            "normal@example.com",
            null,
            "too-short",
            null,
            null
        )))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("PASSWORD_POLICY_VIOLATION"));

        assertThatThrownBy(() -> service.changePassword(
            principal("user-1", "normal-user"),
            new AdminDtos.ChangePasswordRequest("legacy", "too-short")
        ))
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("PASSWORD_POLICY_VIOLATION"));

        verify(repository, never()).save(any(AdminUserEntity.class));
        verify(repository, never()).findByUserId(any());
    }

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
            "secret123456",
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
        AdminUserEntity inviter = user("inviter-1", "inviter", "inviter@example.com", "secret123456");
        inviter.setAffiliateCode("ffOabc");

        when(settingsService.registrationPolicy()).thenReturn(Mono.just(publicRegistrationPolicy()));
        when(repository.findEnabledByAffiliateCodeForShare("ffOabc")).thenReturn(Mono.just(inviter));
        when(repository.save(any(AdminUserEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        AdminDtos.LoginResponse response = service.register(new AdminDtos.RegisterRequest(
            "invited-user",
            "invited@example.com",
            "Invited User",
            "secret123456",
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
        AdminUserEntity adjusted = user("created-user", "normal-user", "user@example.com", "secret123456");
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
            "secret123456"
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
            "secret123456",
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
            "secret123456",
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
            "secret123456",
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
        AdminUserEntity user = user("u-1", "new-user", "user@example.com", "secret123456");
        SqlTemplateHarness sql = sqlTemplateReturning(user);
        AdminUserService service = service(repository, settingsService, sql.template());

        when(repository.findByUsername("user@example.com")).thenReturn(Mono.empty());
        when(repository.findByEmail("user@example.com")).thenReturn(Mono.just(user));

        AdminDtos.LoginResponse response = service.login(new AdminDtos.LoginRequest("user@example.com", "secret123456"))
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
        AdminUserEntity user = user("u-1", "normal-user", "user@example.com", "secret123456");
        user.setRole("USER");
        user.setBalance(new BigDecimal("100.250000"));
        SqlTemplateHarness sql = sqlTemplateReturning(user);
        AdminUserService service = service(repository, settingsService, sql.template());

        when(repository.findApplicationRoot()).thenReturn(Mono.empty());

        AdminUserEntity response = service.update(principal("admin-1", "manager"), "u-1", new AdminDtos.UpdateAdminUserRequest(
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
    void roleOrEnablementChangesInvalidateAllExistingTargetSessions() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminSessionService sessionService = mock(AdminSessionService.class);
        AdminUserEntity user = user("u-1", "normal-user", "user@example.com", "secret123456");
        user.setRole("USER");
        SqlTemplateHarness sql = sqlTemplateReturning(user);
        AdminUserService service = service(repository, settingsService, sql.template(), sessionService);
        when(repository.findApplicationRoot()).thenReturn(Mono.empty());

        service.update(
            principal("admin-1", "manager"),
            "u-1",
            new AdminDtos.UpdateAdminUserRequest(null, null, "USER", null, null)
        ).block();

        verify(sessionService).invalidateUserSessions("u-1");
    }

    @Test
    void nonRootAdminCannotUpdateApplicationRootEvenWithAdminSessionUsername() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        AdminUserService service = service(repository, settingsService, template);
        AdminUserEntity root = user("root-1", "admin", "root@example.com", "secret123456");

        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));

        assertThatThrownBy(() -> service.update(
            principal("admin-2", "admin"),
            "root-1",
            new AdminDtos.UpdateAdminUserRequest("attacker@example.com", null, null, null, null)
        ).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                assertThat(error.getCode()).isEqualTo("APPLICATION_ROOT_PROTECTED");
            });

        verify(template, never()).getDatabaseClient();
    }

    @Test
    void applicationRootCannotRemoveOwnAdminRoleOrDisableItself() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        AdminUserService service = service(repository, settingsService, template);
        AdminUserEntity root = user("root-1", "admin", "root@example.com", "secret123456");
        AdminPrincipal rootPrincipal = principal("root-1", "stale-session-name");

        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));

        assertThatThrownBy(() -> service.update(
            rootPrincipal,
            "root-1",
            new AdminDtos.UpdateAdminUserRequest(null, null, "USER", null, null)
        ).block())
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("APPLICATION_ROOT_INVARIANT"));

        assertThatThrownBy(() -> service.update(
            rootPrincipal,
            "root-1",
            new AdminDtos.UpdateAdminUserRequest(null, null, null, null, false)
        ).block())
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("APPLICATION_ROOT_INVARIANT"));

        verify(template, never()).getDatabaseClient();
    }

    @Test
    void blankUpdateRoleIsRejectedBeforeRootAuthorizationOrSqlBinding() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        AdminUserService service = service(repository, settingsService, template);

        assertThatThrownBy(() -> service.update(
            principal("root-1", "admin"),
            "root-1",
            new AdminDtos.UpdateAdminUserRequest(null, null, "   ", null, null)
        ).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("INVALID_ADMIN_ROLE");
            });

        verify(repository, never()).findApplicationRoot();
        verify(template, never()).getDatabaseClient();
    }

    @Test
    void rootRoleAuthorizationAndSqlBindingUseTheSameNormalizedValue() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserEntity root = user("root-1", "admin", "root@example.com", "secret123456");
        SqlTemplateHarness sql = sqlTemplateReturning(root);
        AdminUserService service = service(repository, settingsService, sql.template());
        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));

        AdminUserEntity response = service.update(
            principal("root-1", "admin"),
            "root-1",
            new AdminDtos.UpdateAdminUserRequest(null, null, " admin ", null, null)
        ).block();

        assertThat(response).isSameAs(root);
        assertThat(sql.binding("role")).isEqualTo("ADMIN");
    }

    @Test
    void applicationRootCanUpdateOwnProfileUsingDatabaseIdentity() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserEntity root = user("root-1", "admin", "new-root@example.com", "secret123456");
        root.setDisplayName("System Owner");
        SqlTemplateHarness sql = sqlTemplateReturning(root);
        AdminUserService service = service(repository, settingsService, sql.template());

        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));

        AdminUserEntity response = service.update(
            principal("root-1", "stale-session-name"),
            "root-1",
            new AdminDtos.UpdateAdminUserRequest(
                "new-root@example.com",
                "System Owner",
                null,
                null,
                null
            )
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo("new-root@example.com");
        assertThat(response.getDisplayName()).isEqualTo("System Owner");
    }

    @Test
    void nonRootAdminCannotResetApplicationRootPassword() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        AdminSessionService sessionService = mock(AdminSessionService.class);
        AdminUserService service = service(repository, settingsService, template, sessionService);
        AdminUserEntity root = user("root-1", "admin", "root@example.com", "secret123456");

        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));

        assertThatThrownBy(() -> service.resetPassword(
            principal("admin-2", "admin"),
            "root-1"
        ).block())
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("APPLICATION_ROOT_PROTECTED"));

        verify(template, never()).getDatabaseClient();
        verify(sessionService, never()).invalidateUserSessions(any());
    }

    @Test
    void successfulPasswordResetInvalidatesEverySessionForTargetUser() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminSessionService sessionService = mock(AdminSessionService.class);
        AdminDtos.ResetPasswordResponse reset = new AdminDtos.ResetPasswordResponse(
            "root-1",
            "admin",
            "generated-password"
        );
        ResetSqlTemplateHarness sql = resetSqlTemplateReturning(reset);
        AdminUserService service = service(repository, settingsService, sql.template(), sessionService);
        AdminUserEntity root = user("root-1", "admin", "root@example.com", "secret123456");

        when(repository.findApplicationRoot()).thenReturn(Mono.just(root));

        AdminDtos.ResetPasswordResponse response = service.resetPassword(
            principal("root-1", "stale-session-name"),
            "root-1"
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo("root-1");
        verify(sessionService).invalidateUserSessions("root-1");
    }

    @Test
    void changingPasswordRevokesEveryExistingSessionForTheUser() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminSessionService sessionService = new AdminSessionService(new ProxyProperties());
        AdminUserEntity root = user("root-1", "admin", "root@example.com", "old-password");
        String firstToken = sessionService.create(root).token();
        String secondToken = sessionService.create(root).token();
        R2dbcEntityTemplate template = updateTemplateReturningRows(1);
        AdminUserService service = service(repository, settingsService, template, sessionService);
        when(repository.findByUserId("root-1")).thenReturn(Mono.just(root));

        service.changePassword(
            principal("root-1", "admin"),
            new AdminDtos.ChangePasswordRequest("old-password", "new-password-123")
        ).block();

        assertThatThrownBy(() -> sessionService.verify(firstToken)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sessionService.verify(secondToken)).isInstanceOf(ApiException.class);
    }

    private AdminPrincipal principal(String userId, String username) {
        return new AdminPrincipal(
            userId,
            username,
            "ADMIN",
            java.time.Instant.now().plusSeconds(60),
            false
        );
    }

    @Test
    void currentUserReturnsLatestBalance() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        AdminUserService service = service(repository, settingsService);
        AdminUserEntity user = user("u-1", "normal-user", "user@example.com", "secret123456");
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
        return service(
            repository,
            settingsService,
            emailVerificationService,
            inviteCodeService,
            template,
            new AdminSessionService(new ProxyProperties())
        );
    }

    private AdminUserService service(
        AdminUserRepository repository,
        RegistrationSettingsService settingsService,
        R2dbcEntityTemplate template,
        AdminSessionService sessionService
    ) {
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        when(emailVerificationService.verifyRegistrationCode(any(), any())).thenReturn(Mono.empty());
        when(emailVerificationService.verifyRegistrationToken(any(), any())).thenReturn(Mono.empty());
        InviteCodeService inviteCodeService = mock(InviteCodeService.class);
        when(inviteCodeService.consume(any(), any())).thenReturn(Mono.empty());
        return service(
            repository,
            settingsService,
            emailVerificationService,
            inviteCodeService,
            template,
            sessionService
        );
    }

    private AdminUserService service(
        AdminUserRepository repository,
        RegistrationSettingsService settingsService,
        EmailVerificationService emailVerificationService,
        InviteCodeService inviteCodeService,
        R2dbcEntityTemplate template,
        AdminSessionService sessionService
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
            sessionService,
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
        Map<String, Object> bindings = new java.util.HashMap<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(any(String.class))).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(any(String.class), any())).thenAnswer(invocation -> {
            bindings.put(invocation.getArgument(0), invocation.getArgument(1));
            return spec;
        });
        when(spec.bindNull(any(String.class), any())).thenReturn(spec);
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, AdminUserEntity>>any()))
            .thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(returned));
        return new SqlTemplateHarness(template, sql, bindings);
    }

    @SuppressWarnings("unchecked")
    private ResetSqlTemplateHarness resetSqlTemplateReturning(AdminDtos.ResetPasswordResponse returned) {
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<AdminDtos.ResetPasswordResponse> fetch = mock(RowsFetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(any(String.class))).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(any(String.class), any())).thenReturn(spec);
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, AdminDtos.ResetPasswordResponse>>any()))
            .thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.just(returned));
        return new ResetSqlTemplateHarness(template, sql);
    }

    @SuppressWarnings("unchecked")
    private R2dbcEntityTemplate updateTemplateReturningRows(long rows) {
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(any(String.class))).thenReturn(spec);
        when(spec.bind(any(String.class), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.just(rows));
        return template;
    }

    private record SqlTemplateHarness(
        R2dbcEntityTemplate template,
        AtomicReference<String> capturedSql,
        Map<String, Object> bindings
    ) {
        String sql() {
            return capturedSql.get();
        }

        Object binding(String name) {
            return bindings.get(name);
        }
    }

    private record ResetSqlTemplateHarness(R2dbcEntityTemplate template, AtomicReference<String> capturedSql) {
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
