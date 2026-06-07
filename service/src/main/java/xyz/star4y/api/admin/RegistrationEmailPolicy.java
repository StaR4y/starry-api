package xyz.star4y.api.admin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import xyz.star4y.api.common.ApiException;

final class RegistrationEmailPolicy {

    private static final Pattern EMAIL_DOMAIN_PATTERN = Pattern.compile(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$"
    );

    private RegistrationEmailPolicy() {
    }

    static List<String> normalizeWhitelist(List<String> raw, boolean strict) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : raw) {
            String value = normalizeSuffix(item, strict);
            if (value != null && !value.isBlank()) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    static boolean isAllowed(String email, List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) {
            return true;
        }
        String domain = emailDomain(email);
        if (domain == null) {
            return false;
        }
        String suffix = "@" + domain;
        for (String allowed : whitelist) {
            if (allowed.startsWith("@") && suffix.equals(allowed)) {
                return true;
            }
            if (allowed.startsWith("*.") && wildcardMatches(domain, allowed)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    static void validateEmailAllowed(String email, List<String> whitelist) {
        if (isAllowed(email, whitelist)) {
            return;
        }
        String allowed = String.join(", ", whitelist == null ? List.of() : whitelist);
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "EMAIL_SUFFIX_NOT_ALLOWED",
            allowed.isBlank()
                ? "Email suffix is not allowed"
                : "Email suffix is not allowed. Allowed suffixes: " + allowed
        );
    }

    private static String normalizeSuffix(String raw, boolean strict) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }

        String normalized;
        if (value.startsWith("*.")) {
            String domain = value.substring(2);
            normalized = isValidDomain(domain) ? "*." + domain : null;
        } else {
            String domain = value;
            if (value.contains("@")) {
                if (!value.startsWith("@") || value.indexOf('@') != value.lastIndexOf('@')) {
                    normalized = null;
                    return invalid(raw, strict);
                }
                domain = value.substring(1);
            }
            normalized = isValidDomain(domain) ? "@" + domain : null;
        }

        if (normalized == null) {
            return invalid(raw, strict);
        }
        return normalized;
    }

    private static String invalid(String raw, boolean strict) {
        if (!strict) {
            return null;
        }
        throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_REGISTRATION_EMAIL_SUFFIX",
            "Invalid registration email suffix: " + raw
        );
    }

    private static boolean wildcardMatches(String domain, String allowed) {
        String base = allowed.substring(2);
        return isValidDomain(base) && (domain.equals(base) || domain.endsWith("." + base));
    }

    private static String emailDomain(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return null;
        }
        int at = normalized.lastIndexOf('@');
        if (at <= 0 || at != normalized.indexOf('@') || at == normalized.length() - 1) {
            return null;
        }
        String domain = normalized.substring(at + 1);
        return isValidDomain(domain) ? domain : null;
    }

    private static boolean isValidDomain(String domain) {
        return domain != null && EMAIL_DOMAIN_PATTERN.matcher(domain).matches();
    }
}
