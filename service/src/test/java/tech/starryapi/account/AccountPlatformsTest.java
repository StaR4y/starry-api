package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountPlatformsTest {

    @Test
    void apiKeyAuthDoesNotMakeGoogleOrAnthropicAccountsOpenAi() {
        ProxyAccountEntity google = account(AccountPlatforms.GOOGLE_PROVIDER, AccountPlatforms.OPENAI_API_KEY_AUTH);
        ProxyAccountEntity anthropic = account(AccountPlatforms.ANTHROPIC_PROVIDER, AccountPlatforms.OPENAI_API_KEY_AUTH);

        assertThat(AccountPlatforms.isOpenAi(google)).isFalse();
        assertThat(AccountPlatforms.isOpenAi(anthropic)).isFalse();
        assertThat(AccountPlatforms.isGoogleApiKey(google)).isTrue();
        assertThat(AccountPlatforms.isAnthropicApiKey(anthropic)).isTrue();
        assertThat(AccountPlatforms.isKiroCompatible(google)).isFalse();
        assertThat(AccountPlatforms.isKiroCompatible(anthropic)).isFalse();
    }

    @Test
    void kiroSocialGoogleAccountRemainsKiroCompatible() {
        ProxyAccountEntity account = account(AccountPlatforms.GOOGLE_PROVIDER, "social");

        assertThat(AccountPlatforms.isOpenAi(account)).isFalse();
        assertThat(AccountPlatforms.isGoogleApiKey(account)).isFalse();
        assertThat(AccountPlatforms.isKiroCompatible(account)).isTrue();
    }

    @Test
    void customOpenAiCompatibleVendorKeepsCatalogNameAndUsesCompatibleRouting() {
        ProxyAccountEntity account = account("OpenRouter", AccountPlatforms.OPENAI_API_KEY_AUTH);
        account.setBaseUrl("https://openrouter.ai/api/v1");

        assertThat(AccountPlatforms.normalizeProvider("OpenRouter", AccountPlatforms.OPENAI_API_KEY_AUTH))
            .isEqualTo("OpenRouter");
        assertThat(AccountPlatforms.normalizeAuthMethod(null, "OpenRouter"))
            .isEqualTo(AccountPlatforms.OPENAI_API_KEY_AUTH);
        assertThat(AccountPlatforms.isCustomOpenAiCompatibleAccount(account)).isTrue();
        assertThat(AccountPlatforms.isOpenAiCompatibleApiKey(account)).isTrue();
        assertThat(AccountPlatforms.isOfficialOpenAiApiKey(account)).isFalse();
        assertThat(AccountPlatforms.defaultBaseUrl("OpenRouter", AccountPlatforms.OPENAI_API_KEY_AUTH, null))
            .isNull();
        assertThat(AccountPlatforms.requiresExplicitBaseUrl("OpenRouter", AccountPlatforms.OPENAI_API_KEY_AUTH))
            .isTrue();
    }

    @Test
    void openAiCompatibleProviderKeepsDisplayNameAndUsesOpenAiRouting() {
        ProxyAccountEntity account = account("openai-compatible", AccountPlatforms.OPENAI_API_KEY_AUTH);

        assertThat(AccountPlatforms.normalizeProvider("openai-compatible", AccountPlatforms.OPENAI_API_KEY_AUTH))
            .isEqualTo(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        assertThat(AccountPlatforms.isOpenAi(account)).isTrue();
        assertThat(AccountPlatforms.isOpenAiApiKey(account)).isTrue();
        assertThat(AccountPlatforms.isKiroCompatible(account)).isFalse();
    }

    @Test
    void openAiApiKeyFilterExcludesCodexOauthTokens() {
        ProxyAccountEntity apiKey = account(AccountPlatforms.OPENAI_PROVIDER, AccountPlatforms.OPENAI_API_KEY_AUTH);
        ProxyAccountEntity codex = account(AccountPlatforms.OPENAI_PROVIDER, AccountPlatforms.OPENAI_CODEX_AUTH);

        assertThat(AccountPlatforms.isOpenAi(apiKey)).isTrue();
        assertThat(AccountPlatforms.isOpenAi(codex)).isTrue();
        assertThat(AccountPlatforms.isOpenAiApiKey(apiKey)).isTrue();
        assertThat(AccountPlatforms.isOpenAiApiKey(codex)).isFalse();
        assertThat(AccountPlatforms.isCodex(codex)).isTrue();
    }

    @Test
    void anthropicProviderDefaultsToApiKeyAndOfficialBaseUrl() {
        String authMethod = AccountPlatforms.normalizeAuthMethod(null, "claude");
        String provider = AccountPlatforms.normalizeProvider("claude", authMethod);

        assertThat(provider).isEqualTo(AccountPlatforms.ANTHROPIC_PROVIDER);
        assertThat(authMethod).isEqualTo(AccountPlatforms.OPENAI_API_KEY_AUTH);
        assertThat(AccountPlatforms.defaultBaseUrl(provider, authMethod, null))
            .isEqualTo(AccountPlatforms.DEFAULT_ANTHROPIC_BASE_URL);
        assertThat(AccountPlatforms.isAnthropicApiKey(provider, authMethod)).isTrue();
    }

    @Test
    void deepSeekProviderDefaultsToApiKeyAndOfficialBaseUrl() {
        String authMethod = AccountPlatforms.normalizeAuthMethod(null, " deepseek ");
        String provider = AccountPlatforms.normalizeProvider(" deepseek ", authMethod);
        ProxyAccountEntity account = account(provider, authMethod);

        assertThat(provider).isEqualTo(AccountPlatforms.DEEPSEEK_PROVIDER);
        assertThat(authMethod).isEqualTo(AccountPlatforms.OPENAI_API_KEY_AUTH);
        assertThat(AccountPlatforms.defaultBaseUrl(provider, authMethod, null))
            .isEqualTo(AccountPlatforms.DEFAULT_DEEPSEEK_BASE_URL);
        assertThat(AccountPlatforms.isDeepSeek(account)).isTrue();
        assertThat(AccountPlatforms.isOpenAi(account)).isFalse();
        assertThat(AccountPlatforms.isKiroCompatible(account)).isFalse();
    }

    @Test
    void onlyLegacyOpenAiCompatibleAccountsUseHostBasedDeepSeekDetection() {
        ProxyAccountEntity legacy = account(
            AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER,
            AccountPlatforms.OPENAI_API_KEY_AUTH
        );
        legacy.setBaseUrl("https://api.deepseek.com/v1/");
        ProxyAccountEntity anthropic = account(
            AccountPlatforms.ANTHROPIC_PROVIDER,
            AccountPlatforms.OPENAI_API_KEY_AUTH
        );
        anthropic.setBaseUrl("https://api.deepseek.com/v1");

        assertThat(AccountPlatforms.isDeepSeek(legacy)).isTrue();
        assertThat(AccountPlatforms.isOpenAi(legacy)).isFalse();
        assertThat(AccountPlatforms.isDeepSeek(anthropic)).isFalse();
    }

    private ProxyAccountEntity account(String provider, String authMethod) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        return account;
    }
}
