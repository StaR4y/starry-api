package tech.starryapi.account.allocation;

import java.util.Locale;
import java.util.regex.Pattern;
import tech.starryapi.account.AllocationDefaultsDtos.Policy;

/**
 * The kinds of account that start with different routing limits. {@link #classify} is the one Java classification;
 * the account-creation trigger applies the same rules in SQL (starry_api.classify_account_allocation), and a
 * PostgreSQL test keeps the two identical, as it does for the built-in values.
 */
public enum AccountAllocationCategory {
    /** An official OpenAI or Anthropic API key. */
    OFFICIAL_API_KEY(true, new Policy(14, 0, 0, 0, 600, 0, 1_000_000L, 100, "")),
    /** Any other API key: OpenAI-compatible gateways, DeepSeek, third-party Anthropic and the like. */
    COMPATIBLE_API_KEY(true, new Policy(0, 0, 0, 0, 600, 0, 1_000_000L, 100, "")),
    CODEX(false, new Policy(4, 60, 4, 0, 600, 0, 1_000_000L, 100, "")),
    /** An Anthropic account that is not an API key: Claude OAuth or a setup token. */
    CLAUDE_OAUTH(true, new Policy(2, 30, 2, 4, 600, 0, 1_000_000L, 100, "")),
    /** Kiro / BuilderID and every other account that is not an API key. */
    KIRO(false, new Policy(2, 30, 2, 0, 600, 0, 1_000_000L, 100, ""));

    private static final String SETTING_PREFIX = "account_allocation_default_";
    // Exact host, HTTPS on the default port, no user info, no query or fragment: the rule of the SQL classification.
    private static final Pattern OFFICIAL_OPENAI = Pattern.compile("https://api[.]openai[.]com(:443)?(/[^?#]*)?");
    private static final Pattern OFFICIAL_ANTHROPIC = Pattern.compile("https://api[.]anthropic[.]com(:443)?(/[^?#]*)?");

    private final boolean anthropicSettings;
    private final Policy builtIn;

    AccountAllocationCategory(boolean anthropicSettings, Policy builtIn) {
        this.anthropicSettings = anthropicSettings;
        this.builtIn = builtIn;
    }

    /** Whether session limits and TLS profiles can apply: they exist for Anthropic accounts only. */
    public boolean anthropicSettings() { return anthropicSettings; }

    public Policy builtIn() { return builtIn; }

    /** The system setting that holds the administrator's values for this category. */
    public String settingKey() { return SETTING_PREFIX + name().toLowerCase(Locale.ROOT); }

    /** Classifies the stored values of an account; a missing Base URL means the provider's official default. */
    public static AccountAllocationCategory classify(String provider, String authMethod, String baseUrl) {
        String normalizedProvider = lower(provider);
        String normalizedAuth = lower(authMethod);
        boolean anthropic = anthropic(normalizedProvider);
        if (normalizedAuth.equals("api-key") || normalizedAuth.equals("apikey") || normalizedAuth.equals("openai-api-key")) {
            String url = lower(baseUrl == null ? null : stripSpaces(baseUrl));
            boolean official = normalizedProvider.equals("openai")
                    && OFFICIAL_OPENAI.matcher(url.isEmpty() ? "https://api.openai.com/v1" : url).matches()
                || anthropic && OFFICIAL_ANTHROPIC.matcher(url.isEmpty() ? "https://api.anthropic.com/v1" : url).matches();
            return official ? OFFICIAL_API_KEY : COMPATIBLE_API_KEY;
        }
        if (normalizedProvider.equals("codex") || normalizedAuth.equals("codex")) return CODEX;
        return anthropic ? CLAUDE_OAUTH : KIRO;
    }

    public static boolean anthropic(String provider) {
        String normalized = lower(provider);
        return normalized.equals("anthropic") || normalized.equals("claude");
    }

    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    // SQL btrim removes spaces only.
    private static String stripSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        return value.substring(start, end);
    }
}
