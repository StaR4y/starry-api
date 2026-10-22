package tech.starryapi.admin;

import java.time.Instant;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
    static final String SMTP_VERIFY_ENABLED = "smtp_verify_enabled";
    static final String SMTP_VERIFIED = "smtp_verified";
    static final String SMTP_VERIFY_FINGERPRINT = "smtp_verify_fingerprint";
    static final String SMTP_VERIFY_TOKEN_HASH = "smtp_verify_token_hash";
    static final String SMTP_VERIFY_EXPIRES_AT = "smtp_verify_expires_at";
    static final String SMTP_VERIFY_NEXT_SEND_AT = "smtp_verify_next_send_at";
    static final String SMTP_VERIFY_SENT_TO = "smtp_verify_sent_to";
    static final String SMTP_VERIFY_LAST_ERROR = "smtp_verify_last_error";
    static final String SMTP_VERIFY_STATUS_UNCONFIGURED = "UNCONFIGURED";
    static final String SMTP_VERIFY_STATUS_PENDING = "PENDING";
    static final String SMTP_VERIFY_STATUS_VERIFIED = "VERIFIED";
    static final String SMTP_VERIFY_STATUS_SKIPPED = "SKIPPED";
    static final String SMTP_VERIFY_STATUS_FAILED = "FAILED";

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
        TURNSTILE_SECRET_KEY,
        SMTP_VERIFY_ENABLED,
        SMTP_VERIFIED,
        SMTP_VERIFY_FINGERPRINT,
        SMTP_VERIFY_TOKEN_HASH,
        SMTP_VERIFY_EXPIRES_AT,
        SMTP_VERIFY_NEXT_SEND_AT,
        SMTP_VERIFY_SENT_TO,
        SMTP_VERIFY_LAST_ERROR
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

                String smtpPassword = settings.get(SMTP_PASSWORD);
                String fingerprint = smtpFingerprint(smtpHost, smtpPort, smtpUsername, smtpPassword, smtpFromEmail);
                boolean configured = !smtpHost.isBlank() && !smtpFromEmail.isBlank();
                boolean verifyEnabled = request.smtpVerifyEnabled() == null
                    ? bool(previous, SMTP_VERIFY_ENABLED, true)
                    : Boolean.TRUE.equals(request.smtpVerifyEnabled());
                boolean fingerprintChanged = !fingerprint.equals(clean(previous.get(SMTP_VERIFY_FINGERPRINT)));
                boolean verified = configured
                    && !fingerprintChanged
                    && bool(previous, SMTP_VERIFIED, false);
                settings.put(SMTP_VERIFY_ENABLED, Boolean.toString(verifyEnabled));
                settings.put(SMTP_VERIFIED, Boolean.toString(verified));
                settings.put(SMTP_VERIFY_FINGERPRINT, fingerprint);
                if (!configured || fingerprintChanged) {
                    settings.put(SMTP_VERIFY_TOKEN_HASH, "");
                    settings.put(SMTP_VERIFY_EXPIRES_AT, "");
                    settings.put(SMTP_VERIFY_NEXT_SEND_AT, "");
                    settings.put(SMTP_VERIFY_SENT_TO, "");
                    settings.put(SMTP_VERIFY_LAST_ERROR, "");
                } else if (verified) {
                    settings.put(SMTP_VERIFY_TOKEN_HASH, "");
                    settings.put(SMTP_VERIFY_EXPIRES_AT, "");
                    settings.put(SMTP_VERIFY_NEXT_SEND_AT, clean(previous.get(SMTP_VERIFY_NEXT_SEND_AT)));
                    settings.put(SMTP_VERIFY_SENT_TO, clean(previous.get(SMTP_VERIFY_SENT_TO)));
                    settings.put(SMTP_VERIFY_LAST_ERROR, "");
                } else {
                    settings.put(SMTP_VERIFY_TOKEN_HASH, clean(previous.get(SMTP_VERIFY_TOKEN_HASH)));
                    settings.put(SMTP_VERIFY_EXPIRES_AT, clean(previous.get(SMTP_VERIFY_EXPIRES_AT)));
                    settings.put(SMTP_VERIFY_NEXT_SEND_AT, clean(previous.get(SMTP_VERIFY_NEXT_SEND_AT)));
                    settings.put(SMTP_VERIFY_SENT_TO, clean(previous.get(SMTP_VERIFY_SENT_TO)));
                    settings.put(SMTP_VERIFY_LAST_ERROR, clean(previous.get(SMTP_VERIFY_LAST_ERROR)));
                }
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

    public Mono<AdminDtos.RegistrationSettingsResponse> recordSmtpChallenge(
        String email,
        String tokenHash,
        Instant expiresAt,
        Instant nextSendAt
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SMTP_VERIFIED, "false");
        values.put(SMTP_VERIFY_TOKEN_HASH, clean(tokenHash));
        values.put(SMTP_VERIFY_EXPIRES_AT, expiresAt == null ? "" : expiresAt.toString());
        values.put(SMTP_VERIFY_NEXT_SEND_AT, nextSendAt == null ? "" : nextSendAt.toString());
        values.put(SMTP_VERIFY_SENT_TO, clean(email));
        values.put(SMTP_VERIFY_LAST_ERROR, "");
        return upsertAndRespond(values);
    }

    public Mono<AdminDtos.RegistrationSettingsResponse> recordSmtpChallengeFailure(String error) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SMTP_VERIFIED, "false");
        values.put(SMTP_VERIFY_TOKEN_HASH, "");
        values.put(SMTP_VERIFY_EXPIRES_AT, "");
        values.put(SMTP_VERIFY_LAST_ERROR, clean(error));
        return upsertAndRespond(values);
    }

    public Mono<AdminDtos.RegistrationSettingsResponse> markSmtpVerified(String tokenHash) {
        String expected = clean(tokenHash);
        if (expected.isBlank()) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SMTP_VERIFY_TOKEN",
                "SMTP verification link is invalid or expired"
            ));
        }
        return settingValues().flatMap(values -> {
            if (!bool(values, SMTP_VERIFY_ENABLED, true)) {
                return Mono.just(settingsResponse(values));
            }
            if (bool(values, SMTP_VERIFIED, false)) {
                return Mono.just(settingsResponse(values));
            }
            Instant expiresAt = parseInstant(values.get(SMTP_VERIFY_EXPIRES_AT));
            if (!expected.equals(clean(values.get(SMTP_VERIFY_TOKEN_HASH)))
                || expiresAt == null
                || expiresAt.isBefore(Instant.now())) {
                return Mono.error(new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SMTP_VERIFY_TOKEN",
                    "SMTP verification link is invalid or expired"
                ));
            }
            Map<String, String> updates = new LinkedHashMap<>();
            updates.put(SMTP_VERIFIED, "true");
            updates.put(SMTP_VERIFY_TOKEN_HASH, "");
            updates.put(SMTP_VERIFY_EXPIRES_AT, "");
            updates.put(SMTP_VERIFY_LAST_ERROR, "");
            return upsertAndRespond(updates);
        });
    }

    Instant smtpNextSendAt(Map<String, String> values) {
        return parseInstant(values.get(SMTP_VERIFY_NEXT_SEND_AT));
    }

    private Mono<AdminDtos.RegistrationSettingsResponse> upsertAndRespond(Map<String, String> updates) {
        return repository.upsertSettings(
                updates.keySet().toArray(String[]::new),
                updates.values().toArray(String[]::new)
            )
            .then(settings());
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
            !clean(values.get(TURNSTILE_SECRET_KEY)).isBlank(),
            bool(values, SMTP_VERIFY_ENABLED, true),
            smtpVerifyStatus(values),
            clean(values.get(SMTP_VERIFY_SENT_TO)),
            parseInstant(values.get(SMTP_VERIFY_EXPIRES_AT)),
            clean(values.get(SMTP_VERIFY_LAST_ERROR))
        );
    }

    String smtpVerifyStatus(Map<String, String> values) {
        SmtpConfig config = new SmtpConfig(
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
        );
        if (!config.configured()) {
            return SMTP_VERIFY_STATUS_UNCONFIGURED;
        }
        if (!bool(values, SMTP_VERIFY_ENABLED, true)) {
            return SMTP_VERIFY_STATUS_SKIPPED;
        }
        if (bool(values, SMTP_VERIFIED, false)) {
            return SMTP_VERIFY_STATUS_VERIFIED;
        }
        if (!clean(values.get(SMTP_VERIFY_LAST_ERROR)).isBlank()
            && clean(values.get(SMTP_VERIFY_TOKEN_HASH)).isBlank()) {
            return SMTP_VERIFY_STATUS_FAILED;
        }
        return SMTP_VERIFY_STATUS_PENDING;
    }

    static String smtpFingerprint(String host, int port, String username, String password, String fromEmail) {
        return Hashing.sha256(
            cleanStatic(host) + '\n'
                + port + '\n'
                + cleanStatic(username) + '\n'
                + cleanStatic(password) + '\n'
                + cleanStatic(fromEmail)
        );
    }

    Instant parseInstant(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(cleaned);
        } catch (Exception ignored) {
            return null;
        }
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
            case REGISTRATION_ENABLED, EMAIL_VERIFY_ENABLED, INVITE_CODE_ENABLED, SMTP_USE_TLS, TURNSTILE_ENABLED, SMTP_VERIFIED -> "false";
            case SMTP_VERIFY_ENABLED -> "true";
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
