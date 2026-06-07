package xyz.star4y.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class EmailVerificationServiceTest {

    @Test
    void autoSecurityUsesImplicitSslForPort465WithoutStartTls() {
        Properties properties = propertiesFor(config(465, true, "auto"));

        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.socketFactory.class")).isEqualTo("javax.net.ssl.SSLSocketFactory");
        assertThat(properties.getProperty("mail.smtp.socketFactory.port")).isEqualTo("465");
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
}
