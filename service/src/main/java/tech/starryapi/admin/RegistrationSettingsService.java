package tech.starryapi.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;

@Service
public class RegistrationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationSettingsService.class);
    private static final String REGISTRATION_SETTINGS_LOCK = "starry_api:system_settings:registration";

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
    private static final List<String> REGISTRATION_POLICY_KEYS = List.of(
        REGISTRATION_ENABLED,
        EMAIL_VERIFY_ENABLED,
        INVITE_CODE_ENABLED,
        REGISTRATION_EMAIL_SUFFIX_WHITELIST,
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
            policyBool(values, REGISTRATION_ENABLED, false),
            policyBool(values, EMAIL_VERIFY_ENABLED, false),
            policyBool(values, INVITE_CODE_ENABLED, false),
            parseWhitelist(values.get(REGISTRATION_EMAIL_SUFFIX_WHITELIST)),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            policyBool(values, TURNSTILE_ENABLED, false),
            clean(values.get(TURNSTILE_SITE_KEY)),
            null
        ));
    }

    public Mono<AdminDtos.RegistrationSettingsResponse> settings() {
        return settingValues().map(this::settingsResponse);
    }

    @Transactional
    public Mono<AdminDtos.RegistrationSettingsResponse> update(AdminDtos.UpdateRegistrationSettingsRequest request) {
        return repository.acquireTransactionLock(REGISTRATION_SETTINGS_LOCK)
            .then(settingValues())
            .flatMap(previous -> {
                boolean enabled = request.registrationEnabled() == null
                    ? policyBool(previous, REGISTRATION_ENABLED, false)
                    : Boolean.TRUE.equals(request.registrationEnabled());
                boolean emailVerifyEnabled = request.emailVerifyEnabled() == null
                    ? policyBool(previous, EMAIL_VERIFY_ENABLED, false)
                    : Boolean.TRUE.equals(request.emailVerifyEnabled());
                boolean inviteCodeEnabled = request.inviteCodeEnabled() == null
                    ? policyBool(previous, INVITE_CODE_ENABLED, false)
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
                    ? policyBool(previous, TURNSTILE_ENABLED, false)
                    : Boolean.TRUE.equals(request.turnstileEnabled());
                String turnstileSiteKey = request.turnstileSiteKey() == null
                    ? clean(previous.get(TURNSTILE_SITE_KEY))
                    : clean(request.turnstileSiteKey());

                Map<String, String> settings = new LinkedHashMap<>();
                settings.put(REGISTRATION_ENABLED, Boolean.toString(enabled));
                settings.put(EMAIL_VERIFY_ENABLED, Boolean.toString(emailVerifyEnabled));
                settings.put(INVITE_CODE_ENABLED, Boolean.toString(inviteCodeEnabled));
                settings.put(REGISTRATION_EMAIL_SUFFIX_WHITELIST, writeWhitelist(whitelist));
                settings.put(SMTP_HOST, smtpHost);
                settings.put(SMTP_PORT, Integer.toString(smtpPort));
                settings.put(SMTP_USERNAME, smtpUsername);
                settings.put(SMTP_PASSWORD, passwordValue(request.smtpPassword(), previous.get(SMTP_PASSWORD)));
                settings.put(SMTP_FROM_EMAIL, smtpFromEmail);
                settings.put(SMTP_FROM_NAME, smtpFromName);
                settings.put(SMTP_USE_TLS, Boolean.toString(compatibleUseTls));
                settings.put(SMTP_SECURITY, smtpSecurity);
                settings.put(TURNSTILE_ENABLED, Boolean.toString(turnstileEnabled));
                settings.put(TURNSTILE_SITE_KEY, turnstileSiteKey);
                settings.put(TURNSTILE_SECRET_KEY, passwordValue(request.turnstileSecretKey(), previous.get(TURNSTILE_SECRET_KEY)));
                return repository.upsertSettings(
                        settings.keySet().toArray(String[]::new),
                        settings.values().toArray(String[]::new)
                    )
                    .thenReturn(settingsResponse(settings));
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
        return settingValues(REGISTRATION_POLICY_KEYS).map(this::registrationPolicy);
    }

    Mono<RegistrationPolicy> lockedRegistrationPolicy() {
        return repository.findAllByIdWithSharedTransactionLock(
                REGISTRATION_SETTINGS_LOCK,
                REGISTRATION_POLICY_KEYS.toArray(String[]::new)
            )
            .collectMap(SystemSettingEntity::getSettingKey, SystemSettingEntity::getSettingValue)
            .map(stored -> registrationPolicy(withDefaults(REGISTRATION_POLICY_KEYS, stored)));
    }

    private RegistrationPolicy registrationPolicy(Map<String, String> values) {
        boolean turnstileEnabled = policyBool(values, TURNSTILE_ENABLED, false);
        return new RegistrationPolicy(
            policyBool(values, REGISTRATION_ENABLED, false),
            policyBool(values, EMAIL_VERIFY_ENABLED, false),
            policyBool(values, INVITE_CODE_ENABLED, false),
            parseWhitelist(values.get(REGISTRATION_EMAIL_SUFFIX_WHITELIST)),
            turnstileEnabled,
            turnstileFingerprint(values, turnstileEnabled)
        );
    }

    public Mono<Boolean> emailVerifyEnabled() {
        return registrationPolicy().map(RegistrationPolicy::emailVerifyEnabled);
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
            policyBool(values, TURNSTILE_ENABLED, false),
            clean(values.get(TURNSTILE_SITE_KEY)),
            clean(values.get(TURNSTILE_SECRET_KEY))
        ));
    }

    private Mono<Map<String, String>> settingValues() {
        return settingValues(SETTING_KEYS);
    }

    private Mono<Map<String, String>> settingValues(List<String> keys) {
        return repository.findAllById(keys)
            .collectMap(SystemSettingEntity::getSettingKey, SystemSettingEntity::getSettingValue)
            .map(stored -> withDefaults(keys, stored));
    }

    private Map<String, String> withDefaults(List<String> keys, Map<String, String> stored) {
        Map<String, String> values = new LinkedHashMap<>();
        keys.forEach(key -> values.put(key, defaultValue(key)));
        values.putAll(stored);
        return values;
    }

    private AdminDtos.RegistrationSettingsResponse settingsResponse(Map<String, String> values) {
        return new AdminDtos.RegistrationSettingsResponse(
            policyBool(values, REGISTRATION_ENABLED, false),
            policyBool(values, EMAIL_VERIFY_ENABLED, false),
            policyBool(values, INVITE_CODE_ENABLED, false),
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
            policyBool(values, TURNSTILE_ENABLED, false),
            values.getOrDefault(TURNSTILE_SITE_KEY, ""),
            !clean(values.get(TURNSTILE_SECRET_KEY)).isBlank()
        );
    }

    private Mono<String> value(String key, String fallback) {
        return repository.findById(key)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty(fallback);
    }

    private List<String> parseWhitelist(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(raw, STRING_LIST);
            if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("Whitelist must be a JSON array of non-blank email suffixes");
            }
            return RegistrationEmailPolicy.normalizeWhitelist(values, true);
        } catch (Exception error) {
            log.error("Invalid registration email suffix whitelist configuration", error);
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "REGISTRATION_EMAIL_POLICY_INVALID",
                "Registration email whitelist configuration is invalid"
            );
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

    private boolean policyBool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        log.error("Invalid boolean registration policy setting: {}", key);
        throw new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "REGISTRATION_POLICY_INVALID",
            "Registration policy configuration is invalid"
        );
    }

    private String turnstileFingerprint(Map<String, String> values, boolean enabled) {
        if (!enabled) {
            return "";
        }
        String siteKey = clean(values.get(TURNSTILE_SITE_KEY));
        String secretKey = clean(values.get(TURNSTILE_SECRET_KEY));
        return Hashing.sha256(siteKey.length() + ":" + siteKey + secretKey.length() + ":" + secretKey);
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
        boolean turnstileEnabled,
        String turnstileConfigFingerprint
    ) {
        public RegistrationPolicy(
            boolean registrationEnabled,
            boolean emailVerifyEnabled,
            boolean inviteCodeEnabled,
            List<String> registrationEmailSuffixWhitelist
        ) {
            this(registrationEnabled, emailVerifyEnabled, inviteCodeEnabled, registrationEmailSuffixWhitelist, false, "");
        }

        public RegistrationPolicy(
            boolean registrationEnabled,
            boolean emailVerifyEnabled,
            boolean inviteCodeEnabled,
            List<String> registrationEmailSuffixWhitelist,
            boolean turnstileEnabled
        ) {
            this(registrationEnabled, emailVerifyEnabled, inviteCodeEnabled, registrationEmailSuffixWhitelist, turnstileEnabled, "");
        }

        public RegistrationPolicy {
            registrationEmailSuffixWhitelist = registrationEmailSuffixWhitelist == null
                ? List.of()
                : List.copyOf(registrationEmailSuffixWhitelist);
            turnstileConfigFingerprint = turnstileConfigFingerprint == null ? "" : turnstileConfigFingerprint;
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
