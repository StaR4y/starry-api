package tech.starryapi.admin;

import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class DataManagementSecurityAlertMailer {

    private static final Logger log = LoggerFactory.getLogger(DataManagementSecurityAlertMailer.class);
    static final String SECURITY_EVENT_ID_HEADER = "X-Starry-Security-Event-Id";

    private final RegistrationSettingsService registrationSettingsService;
    private final AdminUserRepository adminUserRepository;

    public DataManagementSecurityAlertMailer(
        RegistrationSettingsService registrationSettingsService,
        AdminUserRepository adminUserRepository
    ) {
        this.registrationSettingsService = registrationSettingsService;
        this.adminUserRepository = adminUserRepository;
    }

    public Mono<DeliveryOutcome> send(DataManagementSecurityAuditEntity audit) {
        String recipient = clean(audit == null ? null : audit.getAlertRecipientEmail());
        if (!recipient.isBlank()) {
            return sendToRecipient(audit, recipient);
        }
        if (audit == null) {
            return missingRecipient(null);
        }
        return adminUserRepository.findApplicationRootEmail()
            .map(DataManagementSecurityAlertMailer::clean)
            .filter(currentEmail -> !currentEmail.isBlank())
            .flatMap(currentEmail -> sendToRecipient(audit, currentEmail))
            .switchIfEmpty(Mono.defer(() -> missingRecipient(audit)))
            .onErrorResume(error -> {
                logUndelivered(audit, "ROOT_RECIPIENT_LOOKUP_FAILED", error);
                return Mono.just(DeliveryOutcome.failed("ROOT_RECIPIENT_LOOKUP_FAILED"));
            });
    }

    private Mono<DeliveryOutcome> sendToRecipient(
        DataManagementSecurityAuditEntity audit,
        String recipient
    ) {
        return registrationSettingsService.smtpConfig()
            .flatMap(config -> {
                if (!config.configured()) {
                    logUndelivered(audit, "SMTP_NOT_CONFIGURED", null);
                    return Mono.just(DeliveryOutcome.notSent("SMTP_NOT_CONFIGURED"));
                }
                return sendEmail(config, recipient, audit)
                    .thenReturn(DeliveryOutcome.sent())
                    .onErrorResume(error -> {
                        logUndelivered(audit, "SMTP_SEND_FAILED", error);
                        return Mono.just(DeliveryOutcome.failed("SMTP_SEND_FAILED"));
                    });
            })
            .onErrorResume(error -> {
                logUndelivered(audit, "SMTP_CONFIGURATION_UNAVAILABLE", error);
                return Mono.just(DeliveryOutcome.failed("SMTP_CONFIGURATION_UNAVAILABLE"));
            });
    }

    private Mono<DeliveryOutcome> missingRecipient(DataManagementSecurityAuditEntity audit) {
        logUndelivered(audit, "ALERT_RECIPIENT_MISSING", null);
        return Mono.just(DeliveryOutcome.notSent("ALERT_RECIPIENT_MISSING"));
    }

    private Mono<Void> sendEmail(
        RegistrationSettingsService.SmtpConfig config,
        String recipient,
        DataManagementSecurityAuditEntity audit
    ) {
        return Mono.fromRunnable(() -> {
                try {
                    JavaMailSenderImpl sender = mailSender(config);
                    MimeMessage message = sender.createMimeMessage();
                    prepareMessage(message, config, recipient, audit);
                    sender.send(message);
                } catch (Exception error) {
                    throw new IllegalStateException("Unable to send data management security alert", error);
                }
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    static void prepareMessage(
        MimeMessage message,
        RegistrationSettingsService.SmtpConfig config,
        String recipient,
        DataManagementSecurityAuditEntity audit
    ) throws Exception {
        MimeMessageHelper helper = HtmlEmail.multipartHelper(message);
        String fromName = config.fromName().isBlank() ? "Starry API" : config.fromName();
        helper.setFrom(EmailVerificationService.mailFromAddress(config), fromName);
        helper.setTo(recipient);
        helper.setSubject(subject(audit));
        HtmlEmail.write(helper, body(audit), HtmlEmail.securityHtml(audit));
        message.setHeader(SECURITY_EVENT_ID_HEADER, headerValue(audit.getEventId()));
    }

    private JavaMailSenderImpl mailSender(RegistrationSettingsService.SmtpConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        if (!config.username().isBlank()) {
            sender.setUsername(config.username());
        }
        if (!config.password().isBlank()) {
            sender.setPassword(config.password());
        }
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        EmailVerificationService.configureSmtpProperties(properties, config);
        return sender;
    }

    static String subject(DataManagementSecurityAuditEntity audit) {
        int attempt = safeAttempt(audit);
        if (attempt >= 5 || Boolean.TRUE.equals(audit.getLocked())) {
            return "[紧急安全告警] %s 后台极可能已遭入侵（第5次错误密钥）"
                .formatted(AppearanceRuntime.siteName());
        }
        return "[高危安全告警] %s 数据管理安全密钥验证失败（第%d次）"
            .formatted(AppearanceRuntime.siteName(), attempt);
    }

    static String body(DataManagementSecurityAuditEntity audit) {
        return """
            %s 检测到数据管理安全密钥验证失败，请立即确认是否为授权操作。

            事件时间：%s
            连续失败次数：%d
            系统已锁定：%s
            发起账号：%s
            用户 ID：%s
            账号邮箱：%s
            显示名称：%s
            账号角色：%s
            账号已启用：%s
            账号创建时间：%s
            最近登录时间：%s
            发起账号是否为 root：%s
            远端地址：%s
            代理转发地址：%s
            User-Agent 指纹：%s
            会话指纹：%s
            请求端点：%s
            请求 ID 指纹：%s
            """.formatted(
            AppearanceRuntime.siteName(),
            display(audit.getOccurredAt()),
            safeAttempt(audit),
            Boolean.TRUE.equals(audit.getLocked()) ? "是" : "否",
            display(audit.getActorUsername()),
            display(audit.getActorUserId()),
            display(audit.getActorEmail()),
            display(audit.getActorDisplayName()),
            display(audit.getActorRole()),
            displayEnabled(audit.getActorEnabled()),
            display(audit.getActorCreatedAt()),
            display(audit.getActorLastLoginAt()),
            Boolean.TRUE.equals(audit.getActorIsRoot()) ? "是" : "否",
            display(audit.getRemoteAddress()),
            display(DataManagementSecuritySanitizer.forwardedAddresses(audit.getForwardedAddress())),
            display(DataManagementSecuritySanitizer.fingerprint(audit.getUserAgent())),
            display(audit.getSessionFingerprint()),
            display(audit.getEndpoint()),
            display(DataManagementSecuritySanitizer.fingerprint(audit.getRequestId()))
        );
    }

    private void logUndelivered(
        DataManagementSecurityAuditEntity audit,
        String reason,
        Throwable error
    ) {
        String message = "security_event=DATA_MANAGEMENT_KEY_FAILURE alert_delivery=NOT_DELIVERED"
            + " reason={} event_id={} request_id={} actor_user_id={} actor_username={} actor_is_root={}"
            + " remote_address={} forwarded_address={} endpoint={} attempt={} locked={}";
        Object[] arguments = {
            reason,
            display(audit == null ? null : audit.getEventId()),
            display(DataManagementSecuritySanitizer.fingerprint(audit == null ? null : audit.getRequestId())),
            display(audit == null ? null : audit.getActorUserId()),
            display(audit == null ? null : audit.getActorUsername()),
            audit != null && Boolean.TRUE.equals(audit.getActorIsRoot()),
            display(audit == null ? null : audit.getRemoteAddress()),
            display(DataManagementSecuritySanitizer.forwardedAddresses(
                audit == null ? null : audit.getForwardedAddress()
            )),
            display(audit == null ? null : audit.getEndpoint()),
            safeAttempt(audit),
            audit != null && Boolean.TRUE.equals(audit.getLocked()),
            error == null ? "none" : error.getClass().getSimpleName()
        };
        message += " error_type={}";
        var event = log.atError();
        for (Object argument : arguments) {
            event.addArgument(argument);
        }
        event.log(message);
    }

    private static int safeAttempt(DataManagementSecurityAuditEntity audit) {
        return audit == null || audit.getConsecutiveFailures() == null
            ? 0
            : Math.max(0, audit.getConsecutiveFailures());
    }

    private static String display(Object value) {
        String cleaned = clean(value == null ? null : value.toString());
        return cleaned.isBlank() ? "未提供" : cleaned;
    }

    private static String displayEnabled(Boolean enabled) {
        return enabled == null ? "未提供" : Boolean.TRUE.equals(enabled) ? "是" : "否";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String headerValue(String value) {
        String cleaned = clean(value).replace('\r', ' ').replace('\n', ' ');
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Security audit event ID is required");
        }
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64);
    }

    public record DeliveryOutcome(Status status, String reason) {

        static DeliveryOutcome sent() {
            return new DeliveryOutcome(Status.SENT, null);
        }

        static DeliveryOutcome notSent(String reason) {
            return new DeliveryOutcome(Status.NOT_SENT, reason);
        }

        static DeliveryOutcome failed(String reason) {
            return new DeliveryOutcome(Status.FAILED, reason);
        }
    }

    public enum Status {
        SENT,
        NOT_SENT,
        FAILED
    }
}
