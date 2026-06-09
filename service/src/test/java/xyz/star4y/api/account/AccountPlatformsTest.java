package xyz.star4y.api.account;

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
    void openAiCompatibleProviderKeepsDisplayNameAndUsesOpenAiRouting() {
        ProxyAccountEntity account = account("openai-compatible", AccountPlatforms.OPENAI_API_KEY_AUTH);

        assertThat(AccountPlatforms.normalizeProvider("openai-compatible", AccountPlatforms.OPENAI_API_KEY_AUTH))
            .isEqualTo(AccountPlatforms.OPENAI_COMPATIBLE_PROVIDER);
        assertThat(AccountPlatforms.isOpenAi(account)).isTrue();
        assertThat(AccountPlatforms.isKiroCompatible(account)).isFalse();
    }

    private ProxyAccountEntity account(String provider, String authMethod) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        return account;
    }
}
