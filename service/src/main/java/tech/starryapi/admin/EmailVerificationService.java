package tech.starryapi.admin;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
    private static final String SIGNING_SECRET_SETTING_KEY = "registration_email_link_signing_secret";
    private static final String SIGNED_TOKEN_VERSION = "v2";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final RegistrationSettingsService registrationSettingsService;
    private final TurnstileVerificationService turnstileVerificationService;
    private final SystemSettingRepository systemSettingRepository;
    private final Map<String, VerificationToken> tokensByEmail = new ConcurrentHashMap<>();
    private final Map<String, VerificationToken> tokensByHash = new ConcurrentHashMap<>();

    public EmailVerificationService(
        RegistrationSettingsService registrationSettingsService,
        TurnstileVerificationService turnstileVerificationService,
        SystemSettingRepository systemSettingRepository
    ) {
        this.registrationSettingsService = registrationSettingsService;
        this.turnstileVerificationService = turnstileVerificationService;
        this.systemSettingRepository = systemSettingRepository;
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
                Instant expiresAt = now.plus(LINK_TTL);
                return registrationToken(normalizedEmail, expiresAt)
                    .flatMap(rawToken -> registrationSettingsService.smtpConfig().flatMap(config -> {
                        if (!config.configured()) {
                            return Mono.error(new ApiException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "SMTP_NOT_CONFIGURED",
                                "SMTP settings are not configured"
                            ));
                        }
                        String tokenHash = Hashing.sha256(rawToken);
                        VerificationToken pending = new VerificationToken(
                            tokenHash,
                            normalizedEmail,
                            expiresAt,
                            now.plus(SEND_COOLDOWN)
                        );
                        String link = registrationLink(registrationBaseUrl, rawToken, normalizedEmail);
                        return sendEmail(config, normalizedEmail, link)
                            .doOnSuccess(ignored -> {
                                VerificationToken previous = tokensByEmail.put(normalizedEmail, pending);
                                if (previous != null) {
                                    tokensByHash.remove(previous.tokenHash());
                                }
                                tokensByHash.put(tokenHash, pending);
                            })
                            .thenReturn(new AdminDtos.SendVerifyCodeResponse("Registration link sent", (int) SEND_COOLDOWN.toSeconds()));
                    }));
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
                String normalizedToken = token.trim();
                String tokenHash = Hashing.sha256(normalizedToken);
                VerificationToken stored = tokensByHash.get(tokenHash);
                if (stored != null) {
                    if (stored.expiresAt().isBefore(Instant.now())) {
                        removeToken(stored);
                        return invalidVerificationLink();
                    }
                    if (normalizedEmail == null || !stored.email().equals(normalizedEmail)) {
                        return invalidVerificationLink();
                    }
                    removeToken(stored);
                    return Mono.empty();
                }
                if (normalizedEmail == null) {
                    return invalidVerificationLink();
                }
                return verifySignedRegistrationToken(normalizedEmail, normalizedToken);
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

    Mono<String> registrationToken(String normalizedEmail, Instant expiresAt) {
        return signingSecret()
            .map(secret -> signedRegistrationToken(normalizedEmail, expiresAt, Hashing.randomToken(16), secret));
    }

    private Mono<Void> verifySignedRegistrationToken(String normalizedEmail, String token) {
        SignedRegistrationToken parsed = parseSignedRegistrationToken(token);
        if (parsed == null || parsed.expiresAt().isBefore(Instant.now()) || !parsed.email().equals(normalizedEmail)) {
            return invalidVerificationLink();
        }
        return signingSecret()
            .flatMap(secret -> constantEquals(signature(parsed.signingInput(), secret), parsed.signature())
                ? Mono.empty()
                : invalidVerificationLink());
    }

    private SignedRegistrationToken parseSignedRegistrationToken(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 5 || !SIGNED_TOKEN_VERSION.equals(parts[0])) {
                return null;
            }
            String signingInput = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
            String email = new String(BASE64_URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
            if (parts[3].isBlank() || parts[4].isBlank()) {
                return null;
            }
            return new SignedRegistrationToken(email, expiresAt, signingInput, parts[4]);
        } catch (IllegalArgumentException | DateTimeException error) {
            return null;
        }
    }

    private String signedRegistrationToken(String email, Instant expiresAt, String nonce, String secret) {
        String emailPart = base64Url(email);
        String expiresPart = Long.toString(expiresAt.getEpochSecond());
        String noncePart = base64Url(nonce);
        String signingInput = SIGNED_TOKEN_VERSION + "." + emailPart + "." + expiresPart + "." + noncePart;
        return signingInput + "." + signature(signingInput, secret);
    }

    private Mono<String> signingSecret() {
        return systemSettingRepository.findById(SIGNING_SECRET_SETTING_KEY)
            .map(SystemSettingEntity::getSettingValue)
            .filter(value -> value != null && !value.isBlank())
            .switchIfEmpty(Mono.defer(() -> {
                String secret = Hashing.randomToken(32);
                return systemSettingRepository.upsertSetting(SIGNING_SECRET_SETTING_KEY, secret).thenReturn(secret);
            }));
    }

    private String signature(String signingInput, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return BASE64_URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("HmacSHA256 is unavailable", error);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String base64Url(String value) {
        return BASE64_URL_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Mono<Void> invalidVerificationLink() {
        return Mono.error(new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_VERIFY_CODE",
            "Email verification link is invalid or expired"
        ));
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

    private record SignedRegistrationToken(
        String email,
        Instant expiresAt,
        String signingInput,
        String signature
    ) {
    }
}
