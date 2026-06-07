package xyz.star4y.api.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiException;

@Service
public class RegistrationSettingsService {

    static final String REGISTRATION_ENABLED = "registration_enabled";
    static final String REGISTRATION_EMAIL_SUFFIX_WHITELIST = "registration_email_suffix_whitelist";
    static final String EMAIL_VERIFY_ENABLED = "email_verify_enabled";
    static final String SMTP_HOST = "smtp_host";
    static final String SMTP_PORT = "smtp_port";
    static final String SMTP_USERNAME = "smtp_username";
    static final String SMTP_PASSWORD = "smtp_password";
    static final String SMTP_FROM_EMAIL = "smtp_from_email";
    static final String SMTP_FROM_NAME = "smtp_from_name";
    static final String SMTP_USE_TLS = "smtp_use_tls";

    private static final List<String> SETTING_KEYS = List.of(
        REGISTRATION_ENABLED,
        EMAIL_VERIFY_ENABLED,
        REGISTRATION_EMAIL_SUFFIX_WHITELIST,
        SMTP_HOST,
        SMTP_PORT,
        SMTP_USERNAME,
        SMTP_PASSWORD,
        SMTP_FROM_EMAIL,
        SMTP_FROM_NAME,
        SMTP_USE_TLS
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
            parseWhitelist(values.get(REGISTRATION_EMAIL_SUFFIX_WHITELIST)),
            null,
            null,
            null,
            null,
            null,
            null,
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

                return Mono.when(
                    set(REGISTRATION_ENABLED, Boolean.toString(enabled)),
                    set(EMAIL_VERIFY_ENABLED, Boolean.toString(emailVerifyEnabled)),
                    set(REGISTRATION_EMAIL_SUFFIX_WHITELIST, writeWhitelist(whitelist)),
                    set(SMTP_HOST, smtpHost),
                    set(SMTP_PORT, Integer.toString(smtpPort)),
                    set(SMTP_USERNAME, smtpUsername),
                    set(SMTP_PASSWORD, passwordValue(request.smtpPassword(), previous.get(SMTP_PASSWORD))),
                    set(SMTP_FROM_EMAIL, smtpFromEmail),
                    set(SMTP_FROM_NAME, smtpFromName),
                    set(SMTP_USE_TLS, Boolean.toString(smtpUseTls))
                )
                .then(settingValues())
                .map(this::settingsResponse);
            });
    }

    public Mono<Void> ensureRegistrationAllowed(String email) {
        return settings().flatMap(settings -> {
            if (!Boolean.TRUE.equals(settings.registrationEnabled())) {
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
            bool(values, SMTP_USE_TLS, false)
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
            parseWhitelist(values.get(REGISTRATION_EMAIL_SUFFIX_WHITELIST)),
            values.getOrDefault(SMTP_HOST, ""),
            number(values, SMTP_PORT, 587),
            values.getOrDefault(SMTP_USERNAME, ""),
            !clean(values.get(SMTP_PASSWORD)).isBlank(),
            values.getOrDefault(SMTP_FROM_EMAIL, ""),
            values.getOrDefault(SMTP_FROM_NAME, ""),
            bool(values, SMTP_USE_TLS, false)
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
            case REGISTRATION_ENABLED, EMAIL_VERIFY_ENABLED, SMTP_USE_TLS -> "false";
            case REGISTRATION_EMAIL_SUFFIX_WHITELIST -> "[]";
            case SMTP_PORT -> "587";
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

    public record SmtpConfig(
        String host,
        int port,
        String username,
        String password,
        String fromEmail,
        String fromName,
        boolean useTls
    ) {
        boolean configured() {
            return !host.isBlank() && !fromEmail.isBlank();
        }
    }
}
