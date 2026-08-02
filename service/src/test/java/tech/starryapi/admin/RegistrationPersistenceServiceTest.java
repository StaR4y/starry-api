package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

class RegistrationPersistenceServiceTest {

    @Test
    void rejectsRegistrationWhenRegistrationWasClosedAfterExternalVerification() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settings = mock(RegistrationSettingsService.class);
        RegistrationPersistenceService service = service(repository, settings);
        RegistrationSettingsService.RegistrationPolicy verified = policy(true, false, false);

        when(settings.lockedRegistrationPolicy()).thenReturn(Mono.just(policy(false, false, false)));

        assertThatThrownBy(() -> service.register(user(), "", "", verified, "verified-token-hash").block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("registration is disabled");
        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void rejectsRegistrationWhenEmailVerificationWasEnabledAfterExternalVerification() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settings = mock(RegistrationSettingsService.class);
        RegistrationPersistenceService service = service(repository, settings);
        RegistrationSettingsService.RegistrationPolicy verified = policy(true, false, false);

        when(settings.lockedRegistrationPolicy()).thenReturn(Mono.just(policy(true, true, false)));

        assertThatThrownBy(() -> service.register(user(), "", "", verified, "verified-token-hash").block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("policy changed");
        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void rejectsAffiliateRelationWhenInviterWasDisabledBeforePersistence() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settings = mock(RegistrationSettingsService.class);
        RegistrationPersistenceService service = service(repository, settings);
        RegistrationSettingsService.RegistrationPolicy verified = policy(true, true, true);

        when(settings.lockedRegistrationPolicy()).thenReturn(Mono.just(verified));
        when(repository.findEnabledByAffiliateCodeForShare("Affiliate8")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service.register(user(), "", "Affiliate8", verified, "verified-token-hash").block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Affiliate code is invalid");
        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    @Test
    void rejectsRotatedTurnstileConfigurationBeforePersistingUser() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settings = mock(RegistrationSettingsService.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        RegistrationPersistenceService service = new RegistrationPersistenceService(
            repository,
            mock(InviteCodeService.class),
            settings,
            emailVerificationService
        );
        RegistrationSettingsService.RegistrationPolicy verified = new RegistrationSettingsService.RegistrationPolicy(
            true,
            false,
            false,
            List.of(),
            true,
            "turnstile-config-a"
        );
        when(settings.lockedRegistrationPolicy()).thenReturn(Mono.just(new RegistrationSettingsService.RegistrationPolicy(
            true,
            false,
            false,
            List.of(),
            true,
            "turnstile-config-b"
        )));

        assertThatThrownBy(() -> service.register(user(), "", "", verified, "").block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("policy changed");
        verify(repository, never()).save(any(AdminUserEntity.class));
        verify(emailVerificationService, never()).consumeRegistrationToken(any(), any());
    }

    @Test
    void rejectsAlreadyConsumedEmailTokenBeforePersistingUser() {
        AdminUserRepository repository = mock(AdminUserRepository.class);
        RegistrationSettingsService settings = mock(RegistrationSettingsService.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        RegistrationSettingsService.RegistrationPolicy verified = policy(true, true, false);
        RegistrationPersistenceService service = new RegistrationPersistenceService(
            repository,
            mock(InviteCodeService.class),
            settings,
            emailVerificationService
        );
        when(settings.lockedRegistrationPolicy()).thenReturn(Mono.just(verified));
        when(emailVerificationService.consumeRegistrationToken("register@example.com", "already-consumed"))
            .thenReturn(Mono.error(new ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_VERIFY_CODE",
                "Email verification link is invalid or expired"
            )));

        assertThatThrownBy(() -> service.register(user(), "", "", verified, "already-consumed").block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("invalid or expired");
        verify(repository, never()).save(any(AdminUserEntity.class));
    }

    private RegistrationPersistenceService service(
        AdminUserRepository repository,
        RegistrationSettingsService settings
    ) {
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        when(emailVerificationService.consumeRegistrationToken(any(), any())).thenReturn(Mono.empty());
        return new RegistrationPersistenceService(
            repository,
            mock(InviteCodeService.class),
            settings,
            emailVerificationService
        );
    }

    private RegistrationSettingsService.RegistrationPolicy policy(
        boolean registrationEnabled,
        boolean emailVerifyEnabled,
        boolean inviteCodeEnabled
    ) {
        return new RegistrationSettingsService.RegistrationPolicy(
            registrationEnabled,
            emailVerifyEnabled,
            inviteCodeEnabled,
            List.of(),
            false
        );
    }

    private AdminUserEntity user() {
        AdminUserEntity user = new AdminUserEntity();
        user.setUserId("register-user");
        user.setUsername("register-user");
        user.setEmail("register@example.com");
        user.setRole("USER");
        user.setBalance(BigDecimal.ZERO);
        user.setEnabled(true);
        user.setFirstLogin(false);
        user.setPasswordHash("a".repeat(64));
        user.setPasswordSalt("b".repeat(32));
        user.setAffiliateCode("OwnCode8");
        return user;
    }
}
