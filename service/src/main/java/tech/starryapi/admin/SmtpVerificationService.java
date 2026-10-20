package tech.starryapi.admin;

import jakarta.mail.internet.MimeMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;

@Service
public class SmtpVerificationService {

    private static final Logger log = LoggerFactory.getLogger(SmtpVerificationService.class);
    private static final Duration LINK_TTL = Duration.ofMinutes(30);
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);

    private final RegistrationSettingsService registrationSettingsService;
    private final AdminUserRepository adminUserRepository;

    public SmtpVerificationService(
        RegistrationSettingsService registrationSettingsService,
        AdminUserRepository adminUserRepository
    ) {
        this.registrationSettingsService = registrationSettingsService;
        this.adminUserRepository = adminUserRepository;
    }

    public Mono<AdminDtos.RegistrationSettingsResponse> afterSave(
        AdminDtos.RegistrationSettingsResponse settings,
        AdminDtos.UpdateRegistrationSettingsRequest request,
        AdminPrincipal principal,
        String publicBaseUrl
    ) {
        if (settings == null) {
            return Mono.empty();
        }
        if (!Boolean.TRUE.equals(settings.smtpVerifyEnabled()) || !smtpTouched(request)) {
            return Mono.just(settings);
        }
        if (RegistrationSettingsService.SMTP_VERIFY_STATUS_UNCONFIGURED.equals(settings.smtpVerifyStatus())
            || RegistrationSettingsService.SMTP_VERIFY_STATUS_VERIFIED.equals(settings.smtpVerifyStatus())
            || RegistrationSettingsService.SMTP_VERIFY_STATUS_SKIPPED.equals(settings.smtpVerifyStatus())) {
            return Mono.just(settings);
        }
        return send(principal, publicBaseUrl, Boolean.TRUE.equals(request.smtpVerifySendNow()))
            .onErrorResume(error -> {
                if (error instanceof ApiException apiException
                    && "SMTP_VERIFY_COOLDOWN".equals(apiException.getCode())) {
                    return registrationSettingsService.settings();
                }
                log.warn("Failed to send SMTP verification email", error);
                String message = error instanceof ApiException apiException
                    ? apiException.getMessage()
                    : error.getMessage();
                return registrationSettingsService.recordSmtpChallengeFailure(message);
            });
    }

    public Mono<AdminDtos.RegistrationSettingsResponse> send(
        AdminPrincipal principal,
        String publicBaseUrl,
        boolean ignoreCooldown
    ) {
        return registrationSettingsService.settings().flatMap(settings -> {
            if (!Boolean.TRUE.equals(settings.smtpVerifyEnabled())) {
                return Mono.just(settings);
            }
            return registrationSettingsService.smtpConfig().flatMap(config -> {
                if (!config.configured()) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "SMTP_NOT_CONFIGURED",
                        "SMTP settings are not configured"
                    ));
                }
                Instant now = Instant.now();
                if (!ignoreCooldown && settings.smtpVerifyExpiresAt() != null) {
                    Instant nextSendAt = settings.smtpVerifyExpiresAt().minus(LINK_TTL).plus(SEND_COOLDOWN);
                    if (settings.smtpVerifySentTo() != null
                        && !settings.smtpVerifySentTo().isBlank()
                        && nextSendAt.isAfter(now)
                        && RegistrationSettingsService.SMTP_VERIFY_STATUS_PENDING.equals(settings.smtpVerifyStatus())) {
                        long seconds = Math.max(1, Duration.between(now, nextSendAt).toSeconds());
                        return Mono.error(new ApiException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "SMTP_VERIFY_COOLDOWN",
                            "Please wait " + seconds + " seconds before sending another verification email"
                        ));
                    }
                }
                return recipient(principal, config).flatMap(email -> {
                    Instant expiresAt = now.plus(LINK_TTL);
                    String rawToken = Hashing.randomToken(32);
                    String tokenHash = Hashing.sha256(rawToken);
                    String link = verificationLink(publicBaseUrl, rawToken);
                    return registrationSettingsService.recordSmtpChallenge(
                            email,
                            tokenHash,
                            expiresAt,
                            now.plus(SEND_COOLDOWN)
                        )
                        .then(sendEmail(config, email, link))
                        .onErrorResume(error -> {
                            String message = error instanceof ApiException apiException
                                ? apiException.getMessage()
                                : "Failed to send verification email: " + error.getMessage();
                            return registrationSettingsService.recordSmtpChallengeFailure(message)
                                .then(Mono.error(error instanceof ApiException
                                    ? error
                                    : new ApiException(
                                        HttpStatus.SERVICE_UNAVAILABLE,
                                        "EMAIL_SEND_FAILED",
                                        message
                                    )));
                        })
                        .then(registrationSettingsService.settings());
                });
            });
        });
    }

    public Mono<AdminDtos.SmtpVerifyResponse> verify(String token) {
        String cleaned = token == null ? "" : token.trim();
        if (cleaned.isBlank()) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SMTP_VERIFY_TOKEN",
                "SMTP verification link is invalid or expired"
            ));
        }
        return registrationSettingsService.markSmtpVerified(Hashing.sha256(cleaned))
            .map(settings -> new AdminDtos.SmtpVerifyResponse(
                true,
                settings.smtpVerifyStatus(),
                "SMTP 发信已验证"
            ));
    }

    private boolean smtpTouched(AdminDtos.UpdateRegistrationSettingsRequest request) {
        if (request == null) {
            return false;
        }
        return request.smtpHost() != null
            || request.smtpPort() != null
            || request.smtpUsername() != null
            || request.smtpPassword() != null
            || request.smtpFromEmail() != null
            || request.smtpFromName() != null
            || request.smtpUseTls() != null
            || request.smtpSecurity() != null
            || request.smtpVerifyEnabled() != null
            || Boolean.TRUE.equals(request.smtpVerifySendNow());
    }

    Mono<String> recipient(AdminPrincipal principal, RegistrationSettingsService.SmtpConfig config) {
        Mono<String> fromUser = principal == null || principal.userId() == null
            ? Mono.empty()
            : adminUserRepository.findByUserId(principal.userId()).mapNotNull(SmtpVerificationService::usableEmail);
        return fromUser
            .switchIfEmpty(Mono.defer(() ->
                adminUserRepository.findApplicationRootEmail().mapNotNull(SmtpVerificationService::usableEmail)
            ))
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "SMTP_VERIFY_RECIPIENT_MISSING",
                "当前管理员账号未绑定邮箱，验证信无法发送。请先绑定管理员邮箱，或关闭 SMTP 验证后保存。"
            )));
    }

    private static String usableEmail(AdminUserEntity user) {
        return user == null ? null : usableEmail(user.getEmail());
    }

    private static String usableEmail(String value) {
        String email = clean(value);
        return email.isBlank() ? null : email;
    }

    private Mono<Void> sendEmail(
        RegistrationSettingsService.SmtpConfig config,
        String email,
        String link
    ) {
        return Mono.fromRunnable(() -> {
                try {
                    JavaMailSenderImpl sender = mailSender(config);
                    MimeMessage message = sender.createMimeMessage();
                    MimeMessageHelper helper = HtmlEmail.multipartHelper(message);
                    String fromName = config.fromName().isBlank() ? AppearanceRuntime.siteName() : config.fromName();
                    helper.setFrom(EmailVerificationService.mailFromAddress(config), fromName);
                    helper.setTo(email);
                    helper.setSubject(HtmlEmail.smtpVerifySubject());
                    HtmlEmail.write(
                        helper,
                        HtmlEmail.smtpVerifyPlain(link),
                        HtmlEmail.smtpVerifyHtml(link)
                    );
                    sender.send(message);
                } catch (Exception error) {
                    throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "EMAIL_SEND_FAILED",
                        EmailVerificationService.smtpSendFailureMessage(config, error)
                    );
                }
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
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

    private String verificationLink(String publicBaseUrl, String token) {
        String base = publicBaseUrl == null || publicBaseUrl.isBlank()
            ? "/#/smtp-verify"
            : publicBaseUrl.trim();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
