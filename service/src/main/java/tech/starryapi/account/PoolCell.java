package tech.starryapi.account;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * The kinds of account a route can send a request to, each with the one predicate that says which accounts belong to
 * it. {@link AccountPoolService}'s select*ForPool methods and the tiered-source planner share these, so a tier is made
 * of exactly the accounts the route would have picked from anyway. A cell holds no state: what belongs to one request,
 * such as the accounts already tried, is passed in.
 */
public enum PoolCell {
    /** DeepSeek keys, including legacy OpenAI-compatible accounts that point at api.deepseek.com. */
    DEEPSEEK(AccountPlatforms::isDeepSeek),
    /** OpenAI-compatible API keys other than official OpenAI and DeepSeek: relays, gateways, custom catalog vendors. */
    OPENAI_COMPATIBLE(AccountPlatforms::isOpenAiCompatibleApiKey),
    /** Codex OAuth tokens. */
    CODEX(AccountPlatforms::isCodex),
    /** Official OpenAI API keys. */
    OFFICIAL_OPENAI(AccountPlatforms::isOfficialOpenAiApiKey),
    /** Official OpenAI API keys and Codex tokens: the OpenAI pool while Codex isolation is off. */
    OFFICIAL_OR_CODEX(account -> AccountPlatforms.isOfficialOpenAiApiKey(account) || AccountPlatforms.isCodex(account)),
    /** Anthropic API keys, official or third-party. */
    ANTHROPIC_KEY(AccountPlatforms::isAnthropicApiKey),
    /** Kiro, BuilderID and Claude OAuth: every account that is not OpenAI, DeepSeek or a Google or Anthropic API key. */
    KIRO_COMPATIBLE(AccountPlatforms::isKiroCompatible),
    /**
     * The accounts that can serve as an OpenAI Chat relay for another protocol. This only says which accounts could;
     * the ones registered as cross-protocol members are decided by the tier configuration, not here.
     */
    XP_OPENAI_CHAT(AccountPlatforms::isOpenAiCompatibleApiKey);

    private final Predicate<ProxyAccountEntity> members;

    PoolCell(Predicate<ProxyAccountEntity> members) {
        this.members = members;
    }

    public Predicate<ProxyAccountEntity> predicate() {
        return members;
    }

    /** The cell's accounts except the excluded ones (account ids already tried or refused); null excludes none. */
    public Predicate<ProxyAccountEntity> predicate(Collection<String> excluded) {
        return account -> members.test(account) && !isExcluded(account, excluded);
    }

    private static boolean isExcluded(ProxyAccountEntity account, Collection<String> excluded) {
        return account != null
            && account.getAccountId() != null
            && excluded != null
            && excluded.contains(account.getAccountId());
    }
}
