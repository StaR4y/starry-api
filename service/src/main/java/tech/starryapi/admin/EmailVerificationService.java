package tech.starryapi.admin;

import jakarta.mail.internet.MimeMessage;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
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
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;

@Service
public class EmailVerificationService {

    private static final Duration LINK_TTL = Duration.ofMinutes(10);
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);

    private final RegistrationSettingsService registrationSettingsService;
    private final TurnstileVerificationService turnstileVerificationService;
    private final Map<String, VerificationToken> tokensByEmail = new ConcurrentHashMap<>();
    private final Map<String, VerificationToken> tokensByHash = new ConcurrentHashMap<>();

    public EmailVerificationService(
        RegistrationSettingsService registrationSettingsService,
        TurnstileVerificationService turnstileVerificationService
    ) {
        this.registrationSettingsService = registrationSettingsService;
        this.turnstileVerificationService = turnstileVerificationService;
    }

    public Mono<AdminDtos.SendVerifyCodeResponse> sendRegistrationLink(
        String email,
        String turnstileToken,
        String registrationBaseUrl,
        String remoteIp
    ) {
        String normalizedEmail = RegistrationEmailPolicy.normalizeEmail(email);
        if (normalizedEmail == null) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "Email is invalid"));
        }
        return registrationSettingsService.ensureRegistrationAllowed(normalizedEmail)
            .then(turnstileVerificationService.verifyIfRequired(turnstileToken, remoteIp))
            .then(registrationSettingsService.settings())
            .flatMap(settings -> {
                if (!Boolean.TRUE.equals(settings.emailVerifyEnabled())) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "EMAIL_VERIFY_DISABLED",
                        "Email verification is disabled"
                    ));
                }
                VerificationToken existing = tokensByEmail.get(normalizedEmail);
                Instant now = Instant.now();
                if (existing != null && existing.nextSendAt().isAfter(now)) {
                    long seconds = Math.max(1, Duration.between(now, existing.nextSendAt()).toSeconds());
                    return Mono.error(new ApiException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "VERIFY_CODE_COOLDOWN",
                        "Please wait " + seconds + " seconds before requesting another verification code"
                    ));
                }
                String rawToken = Hashing.randomToken(32);
                String tokenHash = Hashing.sha256(rawToken);
                VerificationToken pending = new VerificationToken(
                    tokenHash,
                    normalizedEmail,
                    now.plus(LINK_TTL),
                    now.plus(SEND_COOLDOWN)
                );
                String link = registrationLink(registrationBaseUrl, rawToken, normalizedEmail);
                return registrationSettingsService.smtpConfig()
                    .flatMap(config -> {
                        if (!config.configured()) {
                            return Mono.error(new ApiException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "SMTP_NOT_CONFIGURED",
                                "SMTP settings are not configured"
                            ));
                        }
                        return sendEmail(config, normalizedEmail, link)
                            .doOnSuccess(ignored -> {
                                VerificationToken previous = tokensByEmail.put(normalizedEmail, pending);
                                if (previous != null) {
                                    tokensByHash.remove(previous.tokenHash());
                                }
                                tokensByHash.put(tokenHash, pending);
                            })
                            .thenReturn(new AdminDtos.SendVerifyCodeResponse("Registration link sent", (int) SEND_COOLDOWN.toSeconds()));
                    });
            });
    }

    public Mono<AdminDtos.SendVerifyCodeResponse> sendRegistrationCode(String email) {
        return sendRegistrationLink(email, null, null, null);
    }

    public Mono<Void> verifyRegistrationToken(String email, String token) {
        String normalizedEmail = RegistrationEmailPolicy.normalizeEmail(email);
        return registrationSettingsService.emailVerifyEnabled()
            .flatMap(enabled -> {
                if (!enabled) {
                    return Mono.empty();
                }
                if (token == null || token.isBlank()) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "EMAIL_VERIFY_REQUIRED",
                        "Email verification link token is required"
                    ));
                }
                String tokenHash = Hashing.sha256(token.trim());
                VerificationToken stored = tokensByHash.get(tokenHash);
                if (stored == null || stored.expiresAt().isBefore(Instant.now())) {
                    if (stored != null) {
                        removeToken(stored);
                    }
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_VERIFY_CODE",
                        "Email verification link is invalid or expired"
                    ));
                }
                if (normalizedEmail == null || !stored.email().equals(normalizedEmail)) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_VERIFY_CODE",
                        "Email verification link is invalid or expired"
                    ));
                }
                removeToken(stored);
                return Mono.empty();
            });
    }

    public Mono<Void> verifyRegistrationCode(String email, String code) {
        return verifyRegistrationToken(email, code);
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
                    MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
                    String fromName = config.fromName().isBlank() ? "Starry API" : config.fromName();
                    helper.setFrom(config.fromEmail(), fromName);
                    helper.setTo(email);
                    helper.setSubject("Starry API registration link");
                    helper.setText("""
                        Use this link to continue registering your Starry API account:

                        %s

                        This link expires in 10 minutes and can only be used once.
                        """.formatted(link), false);
                    sender.send(message);
                } catch (Exception error) {
                    throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "EMAIL_SEND_FAILED",
                        "Failed to send verification email: " + error.getMessage() + smtpFailureHint(config)
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
        configureSmtpProperties(properties, config);
        return sender;
    }

    static void configureSmtpProperties(Properties properties, RegistrationSettingsService.SmtpConfig config) {
        SmtpTransportSecurity security = smtpTransportSecurity(config);
        properties.put("mail.smtp.auth", Boolean.toString(!config.username().isBlank() || !config.password().isBlank()));
        properties.put("mail.smtp.ssl.enable", Boolean.toString(security == SmtpTransportSecurity.SSL));
        properties.put("mail.smtp.starttls.enable", Boolean.toString(security == SmtpTransportSecurity.STARTTLS));
        properties.put("mail.smtp.starttls.required", Boolean.toString(security == SmtpTransportSecurity.STARTTLS));
        if (security == SmtpTransportSecurity.SSL || security == SmtpTransportSecurity.STARTTLS) {
            properties.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        }
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
    }

    static SmtpTransportSecurity smtpTransportSecurity(RegistrationSettingsService.SmtpConfig config) {
        String security = RegistrationSettingsService.normalizeSmtpSecurity(config.security(), RegistrationSettingsService.SMTP_SECURITY_AUTO);
        return switch (security) {
            case RegistrationSettingsService.SMTP_SECURITY_SSL -> SmtpTransportSecurity.SSL;
            case RegistrationSettingsService.SMTP_SECURITY_STARTTLS -> SmtpTransportSecurity.STARTTLS;
            case RegistrationSettingsService.SMTP_SECURITY_NONE -> SmtpTransportSecurity.NONE;
            default -> {
                if (config.port() == 465) {
                    yield SmtpTransportSecurity.SSL;
                }
                if (config.useTls() || config.port() == 587) {
                    yield SmtpTransportSecurity.STARTTLS;
                }
                yield SmtpTransportSecurity.NONE;
            }
        };
    }

    static String smtpFailureHint(RegistrationSettingsService.SmtpConfig config) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(config.host());
            for (InetAddress address : addresses) {
                if (isFakeIpAddress(address)) {
                    return " SMTP host resolves to " + address.getHostAddress()
                        + ", which is in the 198.18.0.0/15 fake-IP range often used by local proxy/DNS tools. "
                        + "Disable fake-IP/proxy handling for this SMTP domain or add a DIRECT/fake-ip-filter rule for "
                        + config.host() + ".";
                }
            }
        } catch (UnknownHostException ignored) {
            return "";
        }
        return "";
    }

    static boolean isFakeIpAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            return false;
        }
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first == 198 && (second == 18 || second == 19);
    }

    private void removeToken(VerificationToken token) {
        tokensByHash.remove(token.tokenHash());
        tokensByEmail.remove(token.email(), token);
    }

    private String registrationLink(String registrationBaseUrl, String token, String email) {
        String base = registrationBaseUrl == null || registrationBaseUrl.isBlank()
            ? "/#/login"
            : registrationBaseUrl.trim();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "registrationToken=" + encode(token)
            + "&email=" + encode(email);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    enum SmtpTransportSecurity {
        NONE,
        SSL,
        STARTTLS
    }

    private record VerificationToken(
        String tokenHash,
        String email,
        Instant expiresAt,
        Instant nextSendAt
    ) {
    }
}
