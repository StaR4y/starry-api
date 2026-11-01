package tech.starryapi.proxy.tier;

/**
 * The endpoint a request came in through, as the tiers see it: the four entry endpoints of the design (the same names a
 * pair gate uses). The route a request takes does not tell it: a Claude model asked through Chat Completions, through
 * Responses and through the Gemini endpoint all take the Kiro route, and only the entry decides what a vendor's switches
 * make of them.
 */
public enum TierEntry {
    CHAT,
    MESSAGES,
    RESPONSES,
    GEMINI
}
