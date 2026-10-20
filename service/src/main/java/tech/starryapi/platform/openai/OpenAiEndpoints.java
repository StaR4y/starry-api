package tech.starryapi.platform.openai;

import java.net.URI;
import java.util.regex.Pattern;
import tech.starryapi.account.AccountPlatforms;

final class OpenAiEndpoints {

    private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+(?:beta)?");
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");

    private OpenAiEndpoints() {
    }

    static String openAiApi(String baseUrl, String suffix) {
        return resolve(baseUrl, AccountPlatforms.DEFAULT_OPENAI_BASE_URL, suffix, true);
    }

    static String codexApi(String baseUrl, String suffix) {
        return resolve(baseUrl, AccountPlatforms.DEFAULT_CODEX_BASE_URL, suffix, false);
    }

    private static String resolve(String baseUrl, String fallback, String suffix, boolean defaultVersionedApi) {
        String base = clean(baseUrl);
        if (base == null) {
            base = fallback;
        }

        String normalizedBase = TRAILING_SLASHES.matcher(base).replaceAll("");
        String normalizedSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
        if (normalizedBase.endsWith(normalizedSuffix)) {
            return normalizedBase;
        }
        if (defaultVersionedApi && !endsWithVersionSegment(normalizedBase)) {
            return normalizedBase + "/v1" + normalizedSuffix;
        }
        return normalizedBase + normalizedSuffix;
    }

    private static boolean endsWithVersionSegment(String baseUrl) {
        try {
            String path = URI.create(baseUrl).getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return false;
            }
            String trimmed = TRAILING_SLASHES.matcher(path).replaceAll("");
            int lastSlash = trimmed.lastIndexOf('/');
            String lastSegment = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
            return VERSION_SEGMENT.matcher(lastSegment.toLowerCase()).matches();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
