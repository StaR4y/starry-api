package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tools.jackson.databind.ObjectMapper;

class SmtpVerificationServiceTest {

    @Test
    void verifiesStoredChallengeToken() {
        Map<String, String> store = new HashMap<>();
        store.put(RegistrationSettingsService.SMTP_HOST, "smtp.example.com");
        store.put(RegistrationSettingsService.SMTP_FROM_EMAIL, "noreply@example.com");
        store.put(RegistrationSettingsService.SMTP_VERIFY_ENABLED, "true");
        store.put(RegistrationSettingsService.SMTP_VERIFIED, "false");
        String token = "smtp-verify-token";
        store.put(RegistrationSettingsService.SMTP_VERIFY_TOKEN_HASH, Hashing.sha256(token));
        store.put(RegistrationSettingsService.SMTP_VERIFY_EXPIRES_AT, Instant.now().plusSeconds(600).toString());
        RegistrationSettingsService settingsService = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );
        SmtpVerificationService service = new SmtpVerificationService(
            settingsService,
            mock(AdminUserRepository.class)
        );

        AdminDtos.SmtpVerifyResponse response = service.verify(token).block();

        assertThat(response).isNotNull();
        assertThat(response.verified()).isTrue();
        assertThat(store.get(RegistrationSettingsService.SMTP_VERIFIED)).isEqualTo("true");
    }

    @Test
    void rejectsExpiredOrUnknownToken() {
        Map<String, String> store = new HashMap<>();
        store.put(RegistrationSettingsService.SMTP_VERIFY_ENABLED, "true");
        store.put(RegistrationSettingsService.SMTP_VERIFY_TOKEN_HASH, Hashing.sha256("other"));
        store.put(RegistrationSettingsService.SMTP_VERIFY_EXPIRES_AT, Instant.now().minusSeconds(10).toString());
        RegistrationSettingsService settingsService = new RegistrationSettingsService(
            mockRepository(store),
            new ObjectMapper()
        );
        SmtpVerificationService service = new SmtpVerificationService(
            settingsService,
            mock(AdminUserRepository.class)
        );

        assertThatThrownBy(() -> service.verify("token").block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("invalid or expired");
    }

    @Test
    void recipientDoesNotUseFromAddressWhenAdministratorEmailIsMissing() {
        AdminUserEntity user = new AdminUserEntity();
        user.setUserId("admin-1");
        user.setEmail(null);
        AdminUserRepository users = mock(AdminUserRepository.class);
        when(users.findByUserId("admin-1")).thenReturn(Mono.just(user));
        when(users.findApplicationRootEmail()).thenReturn(Mono.just(""));
        SmtpVerificationService service = new SmtpVerificationService(
            mock(RegistrationSettingsService.class),
            users
        );
        RegistrationSettingsService.SmtpConfig config = new RegistrationSettingsService.SmtpConfig(
            "smtp.example.com",
            587,
            "",
            "",
            "noreply@example.com",
            "Starry",
            false,
            "starttls"
        );

        assertThatThrownBy(() -> service.recipient(
            new AdminPrincipal("admin-1", "admin", "ADMIN", Instant.now().plusSeconds(60), false),
            config
        ).block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("未绑定邮箱");
    }

    @Test
    void recipientUsesAdministratorEmailNotFromAddress() {
        AdminUserEntity user = new AdminUserEntity();
        user.setUserId("admin-1");
        user.setEmail("admin@company.com");
        AdminUserRepository users = mock(AdminUserRepository.class);
        when(users.findByUserId("admin-1")).thenReturn(Mono.just(user));
        when(users.findApplicationRootEmail()).thenReturn(Mono.empty());
        SmtpVerificationService service = new SmtpVerificationService(
            mock(RegistrationSettingsService.class),
            users
        );
        RegistrationSettingsService.SmtpConfig config = new RegistrationSettingsService.SmtpConfig(
            "smtp.example.com",
            587,
            "smtp-user@company.com",
            "secret",
            "noreply@example.com",
            "Starry",
            false,
            "starttls"
        );

        String email = service.recipient(
            new AdminPrincipal("admin-1", "admin", "ADMIN", Instant.now().plusSeconds(60), false),
            config
        ).block();

        assertThat(email).isEqualTo("admin@company.com");
    }

    private SystemSettingRepository mockRepository(Map<String, String> store) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.acquireTransactionLock(anyString())).thenReturn(Mono.just(true));
        when(repository.findAllById(any(Iterable.class))).thenAnswer(invocation -> {
            Iterable<String> keys = invocation.getArgument(0);
            return Flux.fromIterable(keys)
                .filter(store::containsKey)
                .map(key -> setting(key, store.get(key)));
        });
        when(repository.upsertSettings(any(String[].class), any(String[].class))).thenAnswer(invocation -> {
            String[] keys = invocation.getArgument(0);
            String[] values = invocation.getArgument(1);
            for (int index = 0; index < keys.length; index++) {
                store.put(keys[index], values[index]);
            }
            return Mono.just(keys.length);
        });
        return repository;
    }

    private SystemSettingEntity setting(String key, String value) {
        SystemSettingEntity entity = new SystemSettingEntity();
        entity.setSettingKey(key);
        entity.setSettingValue(value);
        return entity;
    }
}
