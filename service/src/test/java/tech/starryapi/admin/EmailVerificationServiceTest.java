package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

class EmailVerificationServiceTest {

    @Test
    void autoSecurityUsesImplicitSslForPort465WithoutStartTls() {
        Properties properties = propertiesFor(config(465, true, "auto"));

        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.socketFactory.class")).isNull();
        assertThat(properties.getProperty("mail.smtp.socketFactory.port")).isNull();
    }

    @Test
    void autoSecurityUsesStartTlsForPort587() {
        Properties properties = propertiesFor(config(587, false, "auto"));

        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.socketFactory.class")).isNull();
    }

    @Test
    void explicitNoneDisablesEncryptedTransport() {
        Properties properties = propertiesFor(config(465, true, "none"));

        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
    }

    @Test
    void normalizesLegacySmtpSecurityFromPortAndTlsFlag() {
        assertThat(RegistrationSettingsService.legacySmtpSecurity("", true, 465)).isEqualTo("ssl");
        assertThat(RegistrationSettingsService.legacySmtpSecurity("", true, 587)).isEqualTo("starttls");
        assertThat(RegistrationSettingsService.legacySmtpSecurity("", false, 25)).isEqualTo("auto");
        assertThat(RegistrationSettingsService.legacySmtpSecurity("none", true, 465)).isEqualTo("none");
    }

    @Test
    void detectsProxyFakeIpAddresses() throws Exception {
        assertThat(EmailVerificationService.isFakeIpAddress(InetAddress.getByName("198.18.0.234"))).isTrue();
        assertThat(EmailVerificationService.isFakeIpAddress(InetAddress.getByName("198.19.255.255"))).isTrue();
        assertThat(EmailVerificationService.isFakeIpAddress(InetAddress.getByName("198.20.0.1"))).isFalse();
        assertThat(EmailVerificationService.isFakeIpAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
    }

    @Test
    void signedRegistrationTokenSurvivesServiceRecreation() {
        Map<String, String> store = new HashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        EmailVerificationService issuer = service(repository);

        String token = issuer.registrationToken("user@example.com", Instant.now().plusSeconds(600)).block();

        assertThat(token).startsWith("v2.");
        EmailVerificationService verifier = service(repository);
        verifier.verifyRegistrationToken("USER@example.com", token).block();
    }

    @Test
    void signedRegistrationTokenRejectsDifferentEmail() {
        Map<String, String> store = new HashMap<>();
        EmailVerificationService service = service(mockRepository(store));
        String token = service.registrationToken("user@example.com", Instant.now().plusSeconds(600)).block();

        assertThatThrownBy(() -> service.verifyRegistrationToken("other@example.com", token).block())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Email verification link is invalid or expired");
    }

    private Properties propertiesFor(RegistrationSettingsService.SmtpConfig config) {
        Properties properties = new Properties();
        EmailVerificationService.configureSmtpProperties(properties, config);
        return properties;
    }

    private RegistrationSettingsService.SmtpConfig config(int port, boolean useTls, String security) {
        return new RegistrationSettingsService.SmtpConfig(
            "smtp.exmail.qq.com",
            port,
            "sender@example.com",
            "secret",
            "sender@example.com",
            "Starry API",
            useTls,
            security
        );
    }

    private EmailVerificationService service(SystemSettingRepository repository) {
        RegistrationSettingsService settingsService = mock(RegistrationSettingsService.class);
        when(settingsService.emailVerifyEnabled()).thenReturn(Mono.just(true));
        return new EmailVerificationService(
            settingsService,
            mock(TurnstileVerificationService.class),
            repository
        );
    }

    private SystemSettingRepository mockRepository(Map<String, String> store) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return Mono.justOrEmpty(store.get(key)).map(value -> {
                SystemSettingEntity entity = new SystemSettingEntity();
                entity.setSettingKey(key);
                entity.setSettingValue(value);
                return entity;
            });
        });
        when(repository.upsertSetting(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            store.put(key, value);
            return Mono.just(1);
        });
        return repository;
    }
}
