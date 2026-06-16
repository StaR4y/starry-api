package tech.starryapi.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

@Service
public class RegistrationSettingsService {

    static final String REGISTRATION_ENABLED = "registration_enabled";
    static final String REGISTRATION_EMAIL_SUFFIX_WHITELIST = "registration_email_suffix_whitelist";
    static final String EMAIL_VERIFY_ENABLED = "email_verify_enabled";
    static final String INVITE_CODE_ENABLED = "invite_code_enabled";
    static final String SMTP_HOST = "smtp_host";
    static final String SMTP_PORT = "smtp_port";
    static final String SMTP_USERNAME = "smtp_username";
    static final String SMTP_PASSWORD = "smtp_password";
    static final String SMTP_FROM_EMAIL = "smtp_from_email";
    static final String SMTP_FROM_NAME = "smtp_from_name";
    static final String SMTP_USE_TLS = "smtp_use_tls";
    static final String SMTP_SECURITY = "smtp_security";
    static final String TURNSTILE_ENABLED = "turnstile_enabled";
    static final String TURNSTILE_SITE_KEY = "turnstile_site_key";
    static final String TURNSTILE_SECRET_KEY = "turnstile_secret_key";

    static final String SMTP_SECURITY_AUTO = "auto";
    static final String SMTP_SECURITY_NONE = "none";
    static final String SMTP_SECURITY_SSL = "ssl";
    static final String SMTP_SECURITY_STARTTLS = "starttls";

    private static final List<String> SETTING_KEYS = List.of(
        REGISTRATION_ENABLED,
        EMAIL_VERIFY_ENABLED,
        INVITE_CODE_ENABLED,
        REGISTRATION_EMAIL_SUFFIX_WHITELIST,
        SMTP_HOST,
        SMTP_PORT,
        SMTP_USERNAME,
        SMTP_PASSWORD,
        SMTP_FROM_EMAIL,
        SMTP_FROM_NAME,
        SMTP_USE_TLS,
        SMTP_SECURITY,
        TURNSTILE_ENABLED,
        TURNSTILE_SITE_KEY,
        TURNSTILE_SECRET_KEY
    );

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SystemSettingRepository repository;
    private final ObjectMapper objectMapper;

    public RegistrationSettingsService(SystemSettingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Mono<AdminDtos.RegistrationSettingsResponse> publicSettings() {
        return settingValues().map(values -> new AdminDtos.RegistrationSettingsResponse(
            bool(values, REGISTRATION_ENABLED, false),
            bool(values, EMAIL_VERIFY_ENABLED, false),
            bool(values, INVITE_CODE_ENABLED, false),
            parseWhitelist(values.get(REGISTRATION_EMAIL_SUFFIX_WHITELIST)),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            bool(values, TURNSTILE_ENABLED, false),
            clean(values.get(TURNSTILE_SITE_KEY)),
            null
        ));
    }

    public Mono<AdminDtos.RegistrationSettingsResponse> settings() {
        return settingValues().map(this::settingsResponse);
    }

    public Mono<AdminDtos.RegistrationSettingsResponse> update(AdminDtos.UpdateRegistrationSettingsRequest request) {
        return settingValues()
            .flatMap(previous -> {
                boolean enabled = request.registrationEnabled() == null
                    ? bool(previous, REGISTRATION_ENABLED, false)
                    : Boolean.TRUE.equals(request.registrationEnabled());
                boolean emailVerifyEnabled = request.emailVerifyEnabled() == null
                    ? bool(previous, EMAIL_VERIFY_ENABLED, false)
                    : Boolean.TRUE.equals(request.emailVerifyEnabled());
                boolean inviteCodeEnabled = request.inviteCodeEnabled() == null
                    ? bool(previous, INVITE_CODE_ENABLED, false)
                    : Boolean.TRUE.equals(request.inviteCodeEnabled());
                List<String> whitelist = request.registrationEmailSuffixWhitelist() == null
                    ? parseWhitelist(previous.get(REGISTRATION_EMAIL_SUFFIX_WHITELIST))
                    : RegistrationEmailPolicy.normalizeWhitelist(request.registrationEmailSuffixWhitelist(), true);
                int smtpPort = request.smtpPort() == null
                    ? normalizePort(number(previous, SMTP_PORT, 587))
                    : normalizePort(request.smtpPort());
                String smtpHost = request.smtpHost() == null
                    ? clean(previous.get(SMTP_HOST))
                    : clean(request.smtpHost());
                String smtpUsername = request.smtpUsername() == null
                    ? clean(previous.get(SMTP_USERNAME))
                    : clean(request.smtpUsername());
                String smtpFromEmail = request.smtpFromEmail() == null
                    ? clean(previous.get(SMTP_FROM_EMAIL))
                    : clean(request.smtpFromEmail());
                String smtpFromName = request.smtpFromName() == null
                    ? clean(previous.get(SMTP_FROM_NAME))
                    : clean(request.smtpFromName());
                boolean smtpUseTls = request.smtpUseTls() == null
                    ? bool(previous, SMTP_USE_TLS, false)
                    : Boolean.TRUE.equals(request.smtpUseTls());
                String smtpSecurity = normalizeSmtpSecurity(
                    request.smtpSecurity(),
                    legacySmtpSecurity(previous.get(SMTP_SECURITY), smtpUseTls, smtpPort)
                );
                boolean compatibleUseTls = !SMTP_SECURITY_NONE.equals(smtpSecurity);
                boolean turnstileEnabled = request.turnstileEnabled() == null
                    ? bool(previous, TURNSTILE_ENABLED, false)
                    : Boolean.TRUE.equals(request.turnstileEnabled());
                String turnstileSiteKey = request.turnstileSiteKey() == null
                    ? clean(previous.get(TURNSTILE_SITE_KEY))
                    : clean(request.turnstileSiteKey());

                return Mono.when(
                    set(REGISTRATION_ENABLED, Boolean.toString(enabled)),
                    set(EMAIL_VERIFY_ENABLED, Boolean.toString(emailVerifyEnabled)),
                    set(INVITE_CODE_ENABLED, Boolean.toString(inviteCodeEnabled)),
                    set(REGISTRATION_EMAIL_SUFFIX_WHITELIST, writeWhitelist(whitelist)),
                    set(SMTP_HOST, smtpHost),
                    set(SMTP_PORT, Integer.toString(smtpPort)),
                    set(SMTP_USERNAME, smtpUsername),
                    set(SMTP_PASSWORD, passwordValue(request.smtpPassword(), previous.get(SMTP_PASSWORD))),
                    set(SMTP_FROM_EMAIL, smtpFromEmail),
                    set(SMTP_FROM_NAME, smtpFromName),
                    set(SMTP_USE_TLS, Boolean.toString(compatibleUseTls)),
                    set(SMTP_SECURITY, smtpSecurity),
                    set(TURNSTILE_ENABLED, Boolean.toString(turnstileEnabled)),
                    set(TURNSTILE_SITE_KEY, turnstileSiteKey),
                    set(TURNSTILE_SECRET_KEY, passwordValue(request.turnstileSecretKey(), previous.get(TURNSTILE_SECRET_KEY)))
                )
                .then(settingValues())
                .map(this::settingsResponse);
            });
    }

    public Mono<Void> ensureRegistrationAllowed(String email) {
        return registrationPolicy().flatMap(settings -> {
            if (!settings.registrationEnabled()) {
                return Mono.error(new ApiException(
                    HttpStatus.FORBIDDEN,
                    "REGISTRATION_DISABLED",
                    "User registration is disabled"
                ));
            }
            RegistrationEmailPolicy.validateEmailAllowed(email, settings.registrationEmailSuffixWhitelist());
            return Mono.empty();
        });
    }

    public Mono<RegistrationPolicy> registrationPolicy() {
        return settingValues().map(values -> new RegistrationPolicy(
            bool(values, REGISTRATION_ENABLED, false),
            bool(values, EMAIL_VERIFY_ENABLED, false),
            bool(values, INVITE_CODE_ENABLED, false),
            parseWhitelist(values.get(REGISTRATION_EMAIL_SUFFIX_WHITELIST)),
            bool(values, TURNSTILE_ENABLED, false)
        ));
    }

    public Mono<Boolean> emailVerifyEnabled() {
        return value(EMAIL_VERIFY_ENABLED, "false").map(Boolean::parseBoolean);
    }

    public Mono<SmtpConfig> smtpConfig() {
        return settingValues().map(values -> new SmtpConfig(
            clean(values.get(SMTP_HOST)),
            normalizePort(number(values, SMTP_PORT, 587)),
            clean(values.get(SMTP_USERNAME)),
            clean(values.get(SMTP_PASSWORD)),
            clean(values.get(SMTP_FROM_EMAIL)),
            clean(values.get(SMTP_FROM_NAME)),
            bool(values, SMTP_USE_TLS, false),
            legacySmtpSecurity(
                values.get(SMTP_SECURITY),
                bool(values, SMTP_USE_TLS, false),
                normalizePort(number(values, SMTP_PORT, 587))
            )
        ));
    }

    public Mono<TurnstileConfig> turnstileConfig() {
        return settingValues().map(values -> new TurnstileConfig(
            bool(values, TURNSTILE_ENABLED, false),
            clean(values.get(TURNSTILE_SITE_KEY)),
            clean(values.get(TURNSTILE_SECRET_KEY))
        ));
    }

    private Mono<Map<String, String>> settingValues() {
        return Flux.fromIterable(SETTING_KEYS)
            .flatMap(key -> value(key, defaultValue(key)).map(value -> Map.entry(key, value)))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private AdminDtos.RegistrationSettingsResponse settingsResponse(Map<String, String> values) {
        return new AdminDtos.RegistrationSettingsResponse(
            bool(values, REGISTRATION_ENABLED, false),
            bool(values, EMAIL_VERIFY_ENABLED, false),
            bool(values, INVITE_CODE_ENABLED, false),
            parseWhitelist(values.get(REGISTRATION_EMAIL_SUFFIX_WHITELIST)),
            values.getOrDefault(SMTP_HOST, ""),
            number(values, SMTP_PORT, 587),
            values.getOrDefault(SMTP_USERNAME, ""),
            !clean(values.get(SMTP_PASSWORD)).isBlank(),
            values.getOrDefault(SMTP_FROM_EMAIL, ""),
            values.getOrDefault(SMTP_FROM_NAME, ""),
            bool(values, SMTP_USE_TLS, false),
            legacySmtpSecurity(
                values.get(SMTP_SECURITY),
                bool(values, SMTP_USE_TLS, false),
                normalizePort(number(values, SMTP_PORT, 587))
            ),
            bool(values, TURNSTILE_ENABLED, false),
            values.getOrDefault(TURNSTILE_SITE_KEY, ""),
            !clean(values.get(TURNSTILE_SECRET_KEY)).isBlank()
        );
    }

    private Mono<String> value(String key, String fallback) {
        return repository.findById(key)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty(fallback);
    }

    private Mono<SystemSettingEntity> set(String key, String value) {
        return repository.findById(key)
            .defaultIfEmpty(newSetting(key))
            .flatMap(entity -> {
                entity.setSettingValue(value);
                return repository.save(entity);
            });
    }

    private SystemSettingEntity newSetting(String key) {
        SystemSettingEntity entity = new SystemSettingEntity();
        entity.setSettingKey(key);
        entity.setSettingValue("");
        return entity;
    }

    private List<String> parseWhitelist(String raw) {
        try {
            List<String> values = objectMapper.readValue(raw == null || raw.isBlank() ? "[]" : raw, STRING_LIST);
            return RegistrationEmailPolicy.normalizeWhitelist(values, false);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeWhitelist(List<String> whitelist) {
        try {
            return objectMapper.writeValueAsString(whitelist == null ? List.of() : whitelist);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize registration email whitelist", error);
        }
    }

    private String defaultValue(String key) {
        return switch (key) {
            case REGISTRATION_ENABLED, EMAIL_VERIFY_ENABLED, INVITE_CODE_ENABLED, SMTP_USE_TLS, TURNSTILE_ENABLED -> "false";
            case REGISTRATION_EMAIL_SUFFIX_WHITELIST -> "[]";
            case SMTP_PORT -> "587";
            case SMTP_SECURITY -> SMTP_SECURITY_AUTO;
            default -> "";
        };
    }

    private boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private int number(Map<String, String> values, String key, int fallback) {
        try {
            return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int normalizePort(Integer port) {
        if (port == null || port <= 0 || port > 65535) {
            return 587;
        }
        return port;
    }

    private String passwordValue(String requested, String previous) {
        String cleaned = clean(requested);
        return cleaned.isBlank() ? clean(previous) : cleaned;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static String normalizeSmtpSecurity(String security, String fallback) {
        String normalized = cleanStatic(security).toLowerCase();
        return switch (normalized) {
            case SMTP_SECURITY_NONE, SMTP_SECURITY_SSL, SMTP_SECURITY_STARTTLS, SMTP_SECURITY_AUTO -> normalized;
            default -> {
                String fallbackValue = cleanStatic(fallback).toLowerCase();
                yield switch (fallbackValue) {
                    case SMTP_SECURITY_NONE, SMTP_SECURITY_SSL, SMTP_SECURITY_STARTTLS, SMTP_SECURITY_AUTO -> fallbackValue;
                    default -> SMTP_SECURITY_AUTO;
                };
            }
        };
    }

    static String legacySmtpSecurity(String storedSecurity, boolean smtpUseTls, int smtpPort) {
        String normalized = normalizeSmtpSecurity(storedSecurity, "");
        if (!normalized.equals(SMTP_SECURITY_AUTO) || !cleanStatic(storedSecurity).isBlank()) {
            return normalized;
        }
        if (smtpPort == 465) {
            return SMTP_SECURITY_SSL;
        }
        return smtpUseTls ? SMTP_SECURITY_STARTTLS : SMTP_SECURITY_AUTO;
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }

    public record SmtpConfig(
        String host,
        int port,
        String username,
        String password,
        String fromEmail,
        String fromName,
        boolean useTls,
        String security
    ) {
        boolean configured() {
            return !host.isBlank() && !fromEmail.isBlank();
        }
    }

    public record RegistrationPolicy(
        boolean registrationEnabled,
        boolean emailVerifyEnabled,
        boolean inviteCodeEnabled,
        List<String> registrationEmailSuffixWhitelist,
        boolean turnstileEnabled
    ) {
        public RegistrationPolicy(
            boolean registrationEnabled,
            boolean emailVerifyEnabled,
            boolean inviteCodeEnabled,
            List<String> registrationEmailSuffixWhitelist
        ) {
            this(registrationEnabled, emailVerifyEnabled, inviteCodeEnabled, registrationEmailSuffixWhitelist, false);
        }

        public RegistrationPolicy {
            registrationEmailSuffixWhitelist = registrationEmailSuffixWhitelist == null
                ? List.of()
                : List.copyOf(registrationEmailSuffixWhitelist);
        }
    }

    public record TurnstileConfig(
        boolean enabled,
        String siteKey,
        String secretKey
    ) {
        boolean configured() {
            return !siteKey.isBlank() && !secretKey.isBlank();
        }
    }
}
