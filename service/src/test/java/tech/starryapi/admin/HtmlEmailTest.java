package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.MimeMessageHelper;

class HtmlEmailTest {

    @Test
    void registrationHtmlUsesBrandGradientAndEscapesLink() {
        BrandTheme theme = BrandTheme.current();
        String html = HtmlEmail.registrationHtml(
            "https://example.com/#/login?registrationToken=a<b>&email=user@example.com"
        );

        assertThat(html)
            .contains(theme.headerGradientCss())
            .contains(theme.primary())
            .contains(theme.accent())
            .contains(theme.header())
            .contains("cid:" + HtmlEmail.LOGO_CONTENT_ID)
            .contains("完成账号注册")
            .contains("继续注册")
            .contains("href=\"https://example.com/#/login?registrationToken=a&lt;b&gt;&amp;email=user@example.com\"")
            .doesNotContain("a<b>")
            .doesNotContain("宋体")
            .doesNotContain("letter-spacing");
        assertThat(HtmlEmail.registrationPlain("https://example.com/link"))
            .contains("https://example.com/link")
            .contains("10 分钟");
        assertThat(HtmlEmail.registrationSubject()).isEqualTo("完成 Starry API 账号注册");
    }

    @Test
    void smtpVerifyHtmlUsesBrandGradientAndEscapesLink() {
        BrandTheme theme = BrandTheme.current();
        String html = HtmlEmail.smtpVerifyHtml("https://example.com/#/smtp-verify?token=a<b>");

        assertThat(html)
            .contains(theme.headerGradientCss())
            .contains("验证邮件发送服务")
            .contains("完成验证")
            .contains("href=\"https://example.com/#/smtp-verify?token=a&lt;b&gt;\"")
            .doesNotContain("a<b>");
        assertThat(HtmlEmail.smtpVerifyPlain("https://example.com/link"))
            .contains("https://example.com/link")
            .contains("30 分钟");
        assertThat(HtmlEmail.smtpVerifySubject()).isEqualTo("验证 Starry API SMTP 发信");
    }

    @Test
    void securityHtmlOmitsUntrustedDetailAndEscapesActorFields() {
        DataManagementSecurityAuditEntity audit = audit(3, false);
        audit.setDetail("securityKey=dmsk-secret password=must-not-leak");
        audit.setActorUsername("<script>alert(1)</script>");
        audit.setForwardedAddress("198.51.100.9, dmsk-forwarded-secret");
        audit.setUserAgent("Bearer adm-raw-session dmsk-user-agent-secret");
        audit.setRequestId("dmt-raw-data-management-token");

        String html = HtmlEmail.securityHtml(audit);

        assertThat(html)
            .contains("高危 · 第 3 次失败")
            .contains("账号")
            .contains("请求")
            .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
            .contains("198.51.100.9")
            .contains("sha256:")
            .contains(BrandTheme.current().danger())
            .contains(BrandTheme.current().headerGradientCss())
            .doesNotContain("<script>alert(1)</script>")
            .doesNotContain("dmsk-secret")
            .doesNotContain("must-not-leak")
            .doesNotContain("dmsk-forwarded-secret")
            .doesNotContain("adm-raw-session")
            .doesNotContain("dmt-raw-data-management-token");
    }

    @Test
    void fifthFailureUsesUrgentCopy() {
        String html = HtmlEmail.securityHtml(audit(5, true));

        assertThat(html)
            .contains("紧急告警")
            .contains("后台极可能已遭入侵")
            .contains(">5<")
            .contains(">是<");
    }

    @Test
    void multipartMessageEmbedsLogoAndHtmlAlternative() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        MimeMessageHelper helper = HtmlEmail.multipartHelper(message);
        helper.setFrom("from@example.com", "Starry API");
        helper.setTo("to@example.com");
        helper.setSubject(HtmlEmail.registrationSubject());
        HtmlEmail.write(
            helper,
            HtmlEmail.registrationPlain("https://example.com/link"),
            HtmlEmail.registrationHtml("https://example.com/link")
        );
        message.saveChanges();

        Object content = message.getContent();
        assertThat(content).isInstanceOf(MimeMultipart.class);
        String html = part((MimeMultipart) content, "text/html");
        String plain = part((MimeMultipart) content, "text/plain");
        assertThat(html).isNotNull().contains("cid:" + HtmlEmail.LOGO_CONTENT_ID);
        assertThat(plain).isNotNull().contains("https://example.com/link");
        assertThat(html).contains(Integer.toString(Year.now(ZoneOffset.UTC).getValue()));
    }

    private static String part(MimeMultipart multipart, String mimeType) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("multipart/*")) {
                Object nested = bodyPart.getContent();
                if (nested instanceof MimeMultipart nestedMultipart) {
                    String found = part(nestedMultipart, mimeType);
                    if (found != null) {
                        return found;
                    }
                }
            } else if (bodyPart.isMimeType(mimeType)) {
                return bodyPart.getContent().toString();
            }
        }
        return null;
    }

    private static DataManagementSecurityAuditEntity audit(int failures, boolean locked) {
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
}
