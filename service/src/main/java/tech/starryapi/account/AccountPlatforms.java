package tech.starryapi.account;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tech.starryapi.common.ApiException;

public final class AccountPlatforms {

    public static final String OPENAI_PROVIDER = "OpenAI";
    public static final String OPENAI_COMPATIBLE_PROVIDER = "OpenAI-compatible";
    public static final String GOOGLE_PROVIDER = "Google";
    public static final String ANTHROPIC_PROVIDER = "Anthropic";
    public static final String DEEPSEEK_PROVIDER = "DeepSeek";
    public static final String OPENAI_API_KEY_AUTH = "api-key";
    public static final String OPENAI_CODEX_AUTH = "codex";
    public static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";
    public static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1";
    public static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";
    public static final String DEFAULT_KIRO_REGION = "us-east-1";
    private static final Pattern BADGE_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Set<String> FIRST_CLASS_PROVIDER_KEYS = Set.of(
        "openai",
        "openai-compatible",
        "openaicompatibility",
        "google",
        "gemini",
        "anthropic",
        "claude",
        "deepseek",
        "codex",
        "builderid",
        "builder_id",
        "kiro"
    );

    private AccountPlatforms() {
    }

    public static boolean isOpenAi(ProxyAccountEntity account) {
        return account != null
            && !isDeepSeek(account)
            && isOpenAi(account.getProvider(), account.getAuthMethod());
    }

    /** Official or compatible API-key accounts, excluding Codex OAuth tokens. */
    public static boolean isOpenAiApiKey(ProxyAccountEntity account) {
        return isOpenAi(account) && !isCodex(account);
    }

    public static boolean isOfficialOpenAiApiKey(ProxyAccountEntity account) {
        if (!isOpenAiApiKey(account)) {
            return false;
        }
        String providerKey = key(account.getProvider());
        if ("openai-compatible".equals(providerKey) || "openaicompatibility".equals(providerKey)) {
            return false;
        }
        return (providerKey.isEmpty() || "openai".equals(providerKey))
            && isOfficialOpenAiBaseUrl(account.getBaseUrl());
    }

    public static boolean isOpenAiCompatibleApiKey(ProxyAccountEntity account) {
        return isOpenAiApiKey(account) && !isOfficialOpenAiApiKey(account);
    }

    public static boolean isOpenAi(String provider, String authMethod) {
        String providerKey = key(provider);
        String authKey = key(authMethod);
        if (isGoogleProvider(providerKey) || isAnthropicProvider(providerKey) || "deepseek".equals(providerKey)) {
            return false;
        }
        return "openai".equals(providerKey)
            || "codex".equals(providerKey)
            || "openai-compatible".equals(providerKey)
            || "openaicompatibility".equals(providerKey)
            || OPENAI_API_KEY_AUTH.equals(authKey)
            || "apikey".equals(authKey)
            || "openai-api-key".equals(authKey)
            || OPENAI_CODEX_AUTH.equals(authKey);
    }

    public static boolean isDeepSeek(ProxyAccountEntity account) {
        if (account == null) {
            return false;
        }
        if (isDeepSeek(account.getProvider())) {
            return true;
        }
        String providerKey = key(account.getProvider());
        boolean legacyCompatibleProvider = "openai-compatible".equals(providerKey)
            || "openaicompatibility".equals(providerKey);
        return legacyCompatibleProvider && isOfficialDeepSeekBaseUrl(account.getBaseUrl());
    }

    public static boolean isDeepSeek(String provider) {
        return "deepseek".equals(key(provider));
    }

    public static boolean isCodex(ProxyAccountEntity account) {
        return account != null && isCodex(account.getProvider(), account.getAuthMethod());
    }

    public static boolean isCodex(String provider, String authMethod) {
        return "codex".equals(key(provider)) || OPENAI_CODEX_AUTH.equals(key(authMethod));
    }

    public static boolean isKiroCompatible(ProxyAccountEntity account) {
        return account != null
            && !isOpenAi(account)
            && !isDeepSeek(account)
            && !isGoogleApiKey(account)
            && !isAnthropicApiKey(account);
    }

    public static boolean isGoogleApiKey(ProxyAccountEntity account) {
        return account != null && isGoogleProvider(key(account.getProvider())) && isApiKey(account.getAuthMethod());
    }

    public static boolean isGoogleApiKey(String provider, String authMethod) {
        return isGoogleProvider(key(provider)) && isApiKey(authMethod);
    }

    public static boolean isAnthropicApiKey(ProxyAccountEntity account) {
        return account != null && isAnthropicProvider(key(account.getProvider())) && isApiKey(account.getAuthMethod());
    }

    public static boolean isAnthropicApiKey(String provider, String authMethod) {
        return isAnthropicProvider(key(provider)) && isApiKey(authMethod);
    }

    public static boolean isFirstClassAccountProvider(String provider) {
        String providerKey = key(provider);
        if (providerKey.isEmpty() || FIRST_CLASS_PROVIDER_KEYS.contains(providerKey)) {
            return true;
        }
        return providerKey.contains("builder")
            || providerKey.contains("kiro")
            || providerKey.contains("enterprise")
            || providerKey.contains("sso")
            || providerKey.contains("idc");
    }

    public static boolean isLegacyOpenAiCompatibleProvider(String provider) {
        String providerKey = key(provider);
        return "openai-compatible".equals(providerKey) || "openaicompatibility".equals(providerKey);
    }

    public static boolean isCustomOpenAiCompatibleProvider(String provider, String authMethod) {
        if (isFirstClassAccountProvider(provider) || isGoogleProvider(key(provider))
            || isAnthropicProvider(key(provider)) || isDeepSeek(provider)) {
            return false;
        }
        String authKey = key(authMethod);
        return authKey.isEmpty() || isApiKey(authMethod);
    }

    public static boolean isCustomOpenAiCompatibleAccount(ProxyAccountEntity account) {
        return account != null && isCustomOpenAiCompatibleProvider(account.getProvider(), account.getAuthMethod());
    }

    public static boolean requiresExplicitBaseUrl(String provider, String authMethod) {
        return isLegacyOpenAiCompatibleProvider(provider) || isCustomOpenAiCompatibleProvider(provider, authMethod);
    }

    public static String normalizeBadgeColor(String color) {
        String cleaned = clean(color);
        if (cleaned == null) {
            return null;
        }
        if (!BADGE_COLOR.matcher(cleaned).matches()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "ACCOUNT_BADGE_COLOR_INVALID",
                "Account badge color must be a 6-digit hex value such as #4D6BFE"
            );
        }
        return cleaned;
    }

    public static String normalizeProvider(String provider, String authMethod) {
        String cleaned = clean(provider);
        String providerKey = key(cleaned);
        if ("google".equals(providerKey) || "gemini".equals(providerKey)) {
            return GOOGLE_PROVIDER;
        }
        if ("anthropic".equals(providerKey) || "claude".equals(providerKey)) {
            return ANTHROPIC_PROVIDER;
        }
        if ("openai-compatible".equals(providerKey) || "openaicompatibility".equals(providerKey)) {
            return OPENAI_COMPATIBLE_PROVIDER;
        }
        if ("deepseek".equals(providerKey)) {
            return DEEPSEEK_PROVIDER;
        }
        if (isCustomOpenAiCompatibleProvider(cleaned, authMethod)) {
            return cleaned;
        }
        if (isOpenAi(cleaned, authMethod)) {
            return OPENAI_PROVIDER;
        }
        if (cleaned == null) {
            return null;
        }
        if ("builderid".equalsIgnoreCase(cleaned) || "builder_id".equalsIgnoreCase(cleaned)) {
            return "BuilderId";
        }
        return cleaned;
    }

    public static String normalizeAuthMethod(String authMethod, String provider) {
        String cleaned = clean(authMethod);
        if (isDeepSeek(provider)) {
            return OPENAI_API_KEY_AUTH;
        }
        if (isCodex(provider, cleaned)) {
            return OPENAI_CODEX_AUTH;
        }
        String providerKey = key(provider);
        if (cleaned == null && (isGoogleProvider(providerKey) || isAnthropicProvider(providerKey))) {
            return OPENAI_API_KEY_AUTH;
        }
        if (isCustomOpenAiCompatibleProvider(provider, cleaned)) {
            return OPENAI_API_KEY_AUTH;
        }
        if (isOpenAi(provider, cleaned)) {
            return OPENAI_API_KEY_AUTH;
        }
        if (cleaned == null) {
            String normalizedProvider = normalizeProvider(provider, null);
            if (normalizedProvider == null) {
                return null;
            }
            String lower = normalizedProvider.toLowerCase(Locale.ROOT);
            return (lower.contains("builder") || lower.contains("enterprise") || lower.contains("sso") || lower.contains("idc"))
                ? "idc"
                : "social";
        }
        return "idc".equalsIgnoreCase(cleaned) || "idc".equals(cleaned.replaceAll("[^A-Za-z0-9]", ""))
            ? "idc"
            : cleaned.toLowerCase(Locale.ROOT);
    }

    public static String defaultBaseUrl(String provider, String authMethod, String baseUrl) {
        String cleaned = clean(baseUrl);
        if (cleaned != null) {
            return cleaned;
        }
        if (isCodex(provider, authMethod)) {
            return DEFAULT_CODEX_BASE_URL;
        }
        if (isDeepSeek(provider)) {
            return DEFAULT_DEEPSEEK_BASE_URL;
        }
        if (requiresExplicitBaseUrl(provider, authMethod)) {
            return cleaned;
        }
        if (isOpenAi(provider, authMethod)) {
            return DEFAULT_OPENAI_BASE_URL;
        }
        if (isAnthropicApiKey(provider, authMethod)) {
            return DEFAULT_ANTHROPIC_BASE_URL;
        }
        return null;
    }

    public static String key(String value) {
        String cleaned = clean(value);
        return cleaned == null ? "" : cleaned.toLowerCase(Locale.ROOT);
    }

    private static boolean isGoogleProvider(String providerKey) {
        return "google".equals(providerKey) || "gemini".equals(providerKey);
    }

    private static boolean isAnthropicProvider(String providerKey) {
        return "anthropic".equals(providerKey) || "claude".equals(providerKey);
    }

    private static boolean isApiKey(String authMethod) {
        String authKey = key(authMethod);
        return OPENAI_API_KEY_AUTH.equals(authKey) || "apikey".equals(authKey);
    }

    private static boolean isOfficialDeepSeekBaseUrl(String baseUrl) {
        String cleaned = clean(baseUrl);
        if (cleaned == null) {
            return false;
        }
        try {
            return "api.deepseek.com".equalsIgnoreCase(URI.create(cleaned).getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isOfficialOpenAiBaseUrl(String baseUrl) {
        String cleaned = clean(baseUrl);
        if (cleaned == null) {
            return true;
        }
        try {
            return "api.openai.com".equalsIgnoreCase(URI.create(cleaned).getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
