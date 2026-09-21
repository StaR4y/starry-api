package tech.starryapi.admin;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tech.starryapi.common.Hashing;

final class DataManagementSecuritySanitizer {

    private static final String FINGERPRINT_PREFIX = "sha256:";
    private static final int MAX_HEADER_LENGTH = 1024;
    private static final int MAX_FORWARDED_ADDRESSES = 8;

    private DataManagementSecuritySanitizer() {
    }

    static String fingerprint(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            return null;
        }
        String normalized = cleaned.toLowerCase(Locale.ROOT);
        if (normalized.matches("sha256:[0-9a-f]{64}")) {
            return normalized;
        }
        return FINGERPRINT_PREFIX + Hashing.sha256(limit(cleaned));
    }

    static String forwardedAddresses(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            return null;
        }
        List<String> addresses = new ArrayList<>();
        for (String candidate : limit(cleaned).split(",")) {
            String address = canonicalAddress(candidate.strip());
            if (address != null && !addresses.contains(address)) {
                addresses.add(address);
            }
            if (addresses.size() >= MAX_FORWARDED_ADDRESSES) {
                break;
            }
        }
        return addresses.isEmpty() ? null : String.join(", ", addresses);
    }

    private static String canonicalAddress(String value) {
        if (value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) {
            String[] octets = value.split("\\.");
            for (String octet : octets) {
                int parsed = Integer.parseInt(octet);
                if (parsed > 255 || (!octet.equals("0") && octet.startsWith("0"))) {
                    return null;
                }
            }
            return value;
        }
        if (!value.contains(":") || !value.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            InetAddress parsed = InetAddress.getByName(value);
            return parsed instanceof Inet6Address ? parsed.getHostAddress() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String limit(String value) {
        return value.length() <= MAX_HEADER_LENGTH ? value : value.substring(0, MAX_HEADER_LENGTH);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
