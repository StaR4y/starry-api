package xyz.star4y.api.admin;

import jakarta.mail.internet.MimeMessage;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xyz.star4y.api.common.ApiException;

@Service
public class EmailVerificationService {

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegistrationSettingsService registrationSettingsService;
    private final Map<String, VerificationCode> codes = new ConcurrentHashMap<>();

    public EmailVerificationService(RegistrationSettingsService registrationSettingsService) {
        this.registrationSettingsService = registrationSettingsService;
    }

    public Mono<AdminDtos.SendVerifyCodeResponse> sendRegistrationCode(String email) {
        String normalizedEmail = RegistrationEmailPolicy.normalizeEmail(email);
        if (normalizedEmail == null) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "Email is invalid"));
        }
        return registrationSettingsService.ensureRegistrationAllowed(normalizedEmail)
            .then(registrationSettingsService.settings())
            .flatMap(settings -> {
                if (!Boolean.TRUE.equals(settings.emailVerifyEnabled())) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "EMAIL_VERIFY_DISABLED",
                        "Email verification is disabled"
                    ));
                }
                VerificationCode existing = codes.get(normalizedEmail);
                Instant now = Instant.now();
                if (existing != null && existing.nextSendAt().isAfter(now)) {
                    long seconds = Math.max(1, Duration.between(now, existing.nextSendAt()).toSeconds());
                    return Mono.error(new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "VERIFY_CODE_COOLDOWN",
                        "Please wait " + seconds + " seconds before requesting another verification code"
                    ));
                }
                String code = code();
                VerificationCode pending = new VerificationCode(
                    code,
                    now.plus(CODE_TTL),
                    now.plus(SEND_COOLDOWN)
                );
                return registrationSettingsService.smtpConfig()
                    .flatMap(config -> {
                        if (!config.configured()) {
                            return Mono.error(new ApiException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "SMTP_NOT_CONFIGURED",
                                "SMTP settings are not configured"
                            ));
                        }
                        return sendEmail(config, normalizedEmail, code)
                            .doOnSuccess(ignored -> codes.put(normalizedEmail, pending))
                            .thenReturn(new AdminDtos.SendVerifyCodeResponse("Verification code sent", (int) SEND_COOLDOWN.toSeconds()));
                    });
            });
    }

    public Mono<Void> verifyRegistrationCode(String email, String code) {
        String normalizedEmail = RegistrationEmailPolicy.normalizeEmail(email);
        return registrationSettingsService.emailVerifyEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    return Mono.empty();
                }
                if (code == null || code.isBlank()) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "EMAIL_VERIFY_REQUIRED",
                        "Email verification code is required"
                    ));
                }
                VerificationCode stored = normalizedEmail == null ? null : codes.get(normalizedEmail);
                if (stored == null || stored.expiresAt().isBefore(Instant.now())) {
                    if (normalizedEmail != null) {
                        codes.remove(normalizedEmail);
                    }
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_VERIFY_CODE",
                        "Email verification code is invalid or expired"
                    ));
                }
                if (!stored.code().equals(code.trim())) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_VERIFY_CODE",
                        "Email verification code is invalid or expired"
                    ));
                }
                codes.remove(normalizedEmail);
                return Mono.empty();
            });
    }

    private Mono<Void> sendEmail(
        RegistrationSettingsService.SmtpConfig config,
        String email,
        String code
    ) {
        return Mono.fromRunnable(() -> {
                try {
                    JavaMailSenderImpl sender = mailSender(config);
                    MimeMessage message = sender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
                    String fromName = config.fromName().isBlank() ? "Starry API" : config.fromName();
                    helper.setFrom(config.fromEmail(), fromName);
                    helper.setTo(email);
                    helper.setSubject("Starry API verification code");
                    helper.setText("""
                        Your Starry API verification code is %s.

                        This code expires in 10 minutes.
                        """.formatted(code), false);
                    sender.send(message);
                } catch (Exception error) {
                    throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "EMAIL_SEND_FAILED",
                        "Failed to send verification email: " + error.getMessage()
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
        properties.put("mail.smtp.auth", Boolean.toString(!config.username().isBlank() || !config.password().isBlank()));
        properties.put("mail.smtp.starttls.enable", Boolean.toString(config.useTls()));
        properties.put("mail.smtp.starttls.required", Boolean.toString(config.useTls()));
        if (config.useTls() && config.port() == 465) {
            properties.put("mail.smtp.ssl.enable", "true");
        }
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    private String code() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private record VerificationCode(
        String code,
        Instant expiresAt,
        Instant nextSendAt
    ) {
    }
}
