package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class DataManagementSecurityAlertMailerTest {

    @Test
    void fifthFailureUsesExplicitLikelyIntrusionSubject() {
        DataManagementSecurityAuditEntity audit = audit(5, true);

        assertThat(DataManagementSecurityAlertMailer.subject(audit))
            .isEqualTo("[紧急安全告警] Starry API 后台极可能已遭入侵（第5次错误密钥）");
    }

    @Test
    void emailBodyNeverIncludesUntrustedIncidentDetail() {
        DataManagementSecurityAuditEntity audit = audit(3, false);
        audit.setDetail("securityKey=dmsk-secret password=must-not-leak");

        assertThat(DataManagementSecurityAlertMailer.body(audit))
            .contains(
                "连续失败次数：3",
                "账号邮箱：operator@example.com",
                "显示名称：Operations",
                "账号已启用：是",
                "账号创建时间：2026-01-01T00:00:00Z",
                "最近登录时间：2026-08-01T00:00:00Z",
                "发起账号是否为 root：否"
            )
            .doesNotContain("dmsk-secret", "must-not-leak");
    }

    @Test
    void emailBodyFingerprintsLegacyFreeTextRequestMetadata() {
        DataManagementSecurityAuditEntity audit = audit(2, false);
        audit.setForwardedAddress("198.51.100.9, dmsk-forwarded-secret");
        audit.setUserAgent("Bearer adm-raw-session dmsk-user-agent-secret");
        audit.setRequestId("dmt-raw-data-management-token");

        assertThat(DataManagementSecurityAlertMailer.body(audit))
            .contains(
                "代理转发地址：198.51.100.9",
                "User-Agent 指纹：sha256:",
                "请求 ID 指纹：sha256:"
            )
            .doesNotContain(
                "dmsk-forwarded-secret",
                "adm-raw-session",
                "dmsk-user-agent-secret",
                "dmt-raw-data-management-token"
            );
    }

    @Test
    void messageUsesStableSecurityEventIdHeader() throws Exception {
        DataManagementSecurityAuditEntity audit = audit(2, false);
        RegistrationSettingsService.SmtpConfig config = smtpConfig();
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));

        DataManagementSecurityAlertMailer.prepareMessage(
            message,
            config,
            audit.getAlertRecipientEmail(),
            audit
        );

        assertThat(message.getHeader(DataManagementSecurityAlertMailer.SECURITY_EVENT_ID_HEADER, null))
            .isEqualTo("event-1");
        message.saveChanges();
        assertThat(message.getContentType()).contains("multipart");
        assertThat(HtmlEmail.securityHtml(audit))
            .contains("cid:" + HtmlEmail.LOGO_CONTENT_ID)
            .contains(BrandTheme.current().headerGradientCss())
            .doesNotContain("dmsk-secret");
    }

    @Test
    void missingSnapshotAndCurrentRootRecipientRemainRetryableWithoutReadingSmtp() {
        RegistrationSettingsService settings = mock(RegistrationSettingsService.class);
        AdminUserRepository repository = mock(AdminUserRepository.class);
        when(repository.findApplicationRootEmail()).thenReturn(Mono.empty());
        DataManagementSecurityAlertMailer mailer = new DataManagementSecurityAlertMailer(settings, repository);
        DataManagementSecurityAuditEntity audit = audit(1, false);
        audit.setAlertRecipientEmail(null);

        DataManagementSecurityAlertMailer.DeliveryOutcome outcome =
            mailer.send(audit).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.status()).isEqualTo(DataManagementSecurityAlertMailer.Status.NOT_SENT);
        assertThat(outcome.reason()).isEqualTo("ALERT_RECIPIENT_MISSING");
        verify(repository).findApplicationRootEmail();
        verifyNoInteractions(settings);
    }

    @Test
    void snapshotRecipientTakesPriorityWithoutReadingCurrentRoot() {
        RegistrationSettingsService settings = mock(RegistrationSettingsService.class);
        AdminUserRepository repository = mock(AdminUserRepository.class);
        when(settings.smtpConfig()).thenReturn(Mono.just(new RegistrationSettingsService.SmtpConfig(
            "",
            587,
            "",
            "",
            "",
            "",
            false,
            "auto"
        )));
        DataManagementSecurityAlertMailer mailer = new DataManagementSecurityAlertMailer(settings, repository);

        DataManagementSecurityAlertMailer.DeliveryOutcome outcome =
            mailer.send(audit(2, false)).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.status()).isEqualTo(DataManagementSecurityAlertMailer.Status.NOT_SENT);
        assertThat(outcome.reason()).isEqualTo("SMTP_NOT_CONFIGURED");
        verifyNoInteractions(repository);
    }

    @Test
    void newlyAddedRootEmailIsResolvedAgainOnRetry() {
        RegistrationSettingsService settings = mock(RegistrationSettingsService.class);
        AdminUserRepository repository = mock(AdminUserRepository.class);
        when(repository.findApplicationRootEmail())
            .thenReturn(Mono.empty(), Mono.just("root-later@example.com"));
        when(settings.smtpConfig()).thenReturn(Mono.just(new RegistrationSettingsService.SmtpConfig(
            "",
            587,
            "",
            "",
            "",
            "",
            false,
            "auto"
        )));
        DataManagementSecurityAlertMailer mailer = new DataManagementSecurityAlertMailer(settings, repository);
        DataManagementSecurityAuditEntity audit = audit(1, false);
        audit.setAlertRecipientEmail(null);

        DataManagementSecurityAlertMailer.DeliveryOutcome beforeEmail = mailer.send(audit).block();
        DataManagementSecurityAlertMailer.DeliveryOutcome afterEmail = mailer.send(audit).block();

        assertThat(beforeEmail).isNotNull();
        assertThat(beforeEmail.status()).isEqualTo(DataManagementSecurityAlertMailer.Status.NOT_SENT);
        assertThat(beforeEmail.reason()).isEqualTo("ALERT_RECIPIENT_MISSING");
        assertThat(afterEmail).isNotNull();
        assertThat(afterEmail.status()).isEqualTo(DataManagementSecurityAlertMailer.Status.NOT_SENT);
        assertThat(afterEmail.reason()).isEqualTo("SMTP_NOT_CONFIGURED");
        verify(repository, times(2)).findApplicationRootEmail();
        verify(settings).smtpConfig();
    }

    private DataManagementSecurityAuditEntity audit(int failures, boolean locked) {
        DataManagementSecurityAuditEntity audit = new DataManagementSecurityAuditEntity();
        audit.setEventId("event-1");
        audit.setOccurredAt(Instant.parse("2026-08-10T01:02:03Z"));
        audit.setActorUserId("user-1");
        audit.setActorUsername("operator");
        audit.setActorEmail("operator@example.com");
        audit.setActorDisplayName("Operations");
        audit.setActorRole("ADMIN");
        audit.setActorEnabled(true);
        audit.setActorCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        audit.setActorLastLoginAt(Instant.parse("2026-08-01T00:00:00Z"));
        audit.setActorIsRoot(false);
        audit.setAlertRecipientEmail("root@example.com");
        audit.setRemoteAddress("192.0.2.10");
        audit.setForwardedAddress("198.51.100.9");
        audit.setUserAgent("security-test-agent");
        audit.setSessionFingerprint("a".repeat(64));
        audit.setEndpoint("/admin/data-transfer/security/unlock");
        audit.setRequestId("request-1");
        audit.setConsecutiveFailures(failures);
        audit.setLocked(locked);
        return audit;
    }

    private RegistrationSettingsService.SmtpConfig smtpConfig() {
        return new RegistrationSettingsService.SmtpConfig(
            "smtp.example.com",
            587,
            "",
            "",
            "alerts@example.com",
            "Starry API",
            true,
            "starttls"
        );
    }

}
