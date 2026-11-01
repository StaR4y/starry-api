package tech.starryapi.proxy.tier;

import java.util.Objects;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * What the planner is told about one request: the facts the facade has settled by the time it reserves (model,
 * route, which pool it prefers, the body the reservation is sized from), so the planner never re-derives routing.
 *
 * @param requestId the request token of the pool context the request runs with
 * @param requestedModel the model the client asked for; the price is looked up under it
 * @param upstreamModel the model after mapping, as the pool context carries it
 * @param providerHint the provider hint of the pool context, for a model the catalog does not know
 * @param route the upstream route the facade chose
 * @param preferDeepSeekAccounts the request goes to DeepSeek accounts
 * @param openAiCompatibleModel the model is served by OpenAI-compatible keys ({@code route} is then OPENAI)
 * @param preferCodexAccounts the request prefers Codex accounts
 * @param isolateCodexPool Codex tokens and official OpenAI keys stay in separate pools
 * @param fastMode the billed fast mode
 * @param billed false for the anonymous caller, which is never charged, so no price is looked up for it
 * @param upstreamRequest the body the reservation is sized from, with its output limit forced
 * @param responseOwnerAccountId the account that owns the response chain being continued, or null
 * @param responseMovable whether the chain can be replayed on any account, so its owner is only a preference
 * @param keyId the API key the request is made with; a session's tier is never shared across keys
 * @param sessionName what the client calls its session, or the response chain it continues
 *     ({@link TierSessionKeys#named}, {@link TierSessionKeys#ofResponses}); null when it gives none, and the session is
 *     then told by the beginning of its conversation
 * @param selector the tier the client named, by the channel that wins (design 6.1); null when no channel names one
 * @param entry the endpoint the request came in through; null when the caller does not say, which no switch that
 *     depends on the endpoint applies to
 * @param keyHoldsPreference whether the key of the request can have a tier preference, which only a key tied to one
 *     provider can; it decides whether the advice to a user who cannot pay points to the key's settings
 * @param suffixTakenOff whether the entry takes the tier suffix off a spelling such as {@code model@tier}: it does not
 *     for an id that a mapping rule of the key is written around, such as {@code claude-sonnet-4@*}. The advice to a user
 *     who cannot pay offers the suffix only for a spelling that works; null when the caller does not say, which offers
 *     it for every spelling
 * @param entryFacts what the original request asks for and what the sanitizer reads of it, for the relays that may serve
 *     it (design 3, step 5b); null when the caller took none, and then no relay does
 */
public record TierPlanRequest(
    String requestId,
    String requestedModel,
    String upstreamModel,
    String providerHint,
    TierRoute route,
    boolean preferDeepSeekAccounts,
    boolean openAiCompatibleModel,
    boolean preferCodexAccounts,
    boolean isolateCodexPool,
    boolean fastMode,
    boolean billed,
    JsonNode upstreamRequest,
    String responseOwnerAccountId,
    boolean responseMovable,
    String keyId,
    String sessionName,
    TierSelectorParser.Selector selector,
    TierEntry entry,
    boolean keyHoldsPreference,
    Predicate<String> suffixTakenOff,
    TierEntryFacts entryFacts
) {
    public TierPlanRequest {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(upstreamRequest, "upstreamRequest");
    }

    /** A request that no relay is given. */
    public TierPlanRequest(
        String requestId,
        String requestedModel,
        String upstreamModel,
        String providerHint,
        TierRoute route,
        boolean preferDeepSeekAccounts,
        boolean openAiCompatibleModel,
        boolean preferCodexAccounts,
        boolean isolateCodexPool,
        boolean fastMode,
        boolean billed,
        JsonNode upstreamRequest,
        String responseOwnerAccountId,
        boolean responseMovable,
        String keyId,
        String sessionName,
        TierSelectorParser.Selector selector,
        TierEntry entry,
        boolean keyHoldsPreference,
        Predicate<String> suffixTakenOff
    ) {
        this(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts, openAiCompatibleModel,
            preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest, responseOwnerAccountId,
            responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, suffixTakenOff, null);
    }

    /** A request whose key may or may not have a tier preference, and for which the suffix is advised as it always was. */
    public TierPlanRequest(
        String requestId,
        String requestedModel,
        String upstreamModel,
        String providerHint,
        TierRoute route,
        boolean preferDeepSeekAccounts,
        boolean openAiCompatibleModel,
        boolean preferCodexAccounts,
        boolean isolateCodexPool,
        boolean fastMode,
        boolean billed,
        JsonNode upstreamRequest,
        String responseOwnerAccountId,
        boolean responseMovable,
        String keyId,
        String sessionName,
        TierSelectorParser.Selector selector,
        TierEntry entry,
        boolean keyHoldsPreference
    ) {
        this(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts, openAiCompatibleModel,
            preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest, responseOwnerAccountId,
            responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, null);
    }

    /** A request in which the client named no tier. */
    public TierPlanRequest(
        String requestId,
        String requestedModel,
        String upstreamModel,
        String providerHint,
        TierRoute route,
        boolean preferDeepSeekAccounts,
        boolean openAiCompatibleModel,
        boolean preferCodexAccounts,
        boolean isolateCodexPool,
        boolean fastMode,
        boolean billed,
        JsonNode upstreamRequest,
        String responseOwnerAccountId,
        boolean responseMovable,
        String keyId,
        String sessionName
    ) {
        this(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts, openAiCompatibleModel,
            preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest, responseOwnerAccountId,
            responseMovable, keyId, sessionName, null);
    }

    /** A request that gives no endpoint. */
    public TierPlanRequest(
        String requestId,
        String requestedModel,
        String upstreamModel,
        String providerHint,
        TierRoute route,
        boolean preferDeepSeekAccounts,
        boolean openAiCompatibleModel,
        boolean preferCodexAccounts,
        boolean isolateCodexPool,
        boolean fastMode,
        boolean billed,
        JsonNode upstreamRequest,
        String responseOwnerAccountId,
        boolean responseMovable,
        String keyId,
        String sessionName,
        TierSelectorParser.Selector selector
    ) {
        this(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts, openAiCompatibleModel,
            preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest, responseOwnerAccountId,
            responseMovable, keyId, sessionName, selector, null);
    }

    /** A request whose key can have a tier preference, as the keys tied to one provider can. */
    public TierPlanRequest(
        String requestId,
        String requestedModel,
        String upstreamModel,
        String providerHint,
        TierRoute route,
        boolean preferDeepSeekAccounts,
        boolean openAiCompatibleModel,
        boolean preferCodexAccounts,
        boolean isolateCodexPool,
        boolean fastMode,
        boolean billed,
        JsonNode upstreamRequest,
        String responseOwnerAccountId,
        boolean responseMovable,
        String keyId,
        String sessionName,
        TierSelectorParser.Selector selector,
        TierEntry entry
    ) {
        this(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts, openAiCompatibleModel,
            preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest, responseOwnerAccountId,
            responseMovable, keyId, sessionName, selector, entry, true, null);
    }

    /** The same request with the name its session goes by. */
    public TierPlanRequest withSessionName(String sessionName) {
        return new TierPlanRequest(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts,
            openAiCompatibleModel, preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest,
            responseOwnerAccountId, responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, suffixTakenOff, entryFacts);
    }

    /** The same request with the tier the client named. */
    public TierPlanRequest withSelector(TierSelectorParser.Selector selector) {
        return new TierPlanRequest(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts,
            openAiCompatibleModel, preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest,
            responseOwnerAccountId, responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, suffixTakenOff, entryFacts);
    }

    /** The same request with the endpoint it came in through. */
    public TierPlanRequest withEntry(TierEntry entry) {
        return new TierPlanRequest(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts,
            openAiCompatibleModel, preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest,
            responseOwnerAccountId, responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, suffixTakenOff, entryFacts);
    }

    /** The same request, for a key that can have a tier preference or cannot. */
    public TierPlanRequest withKeyHoldsPreference(boolean keyHoldsPreference) {
        return new TierPlanRequest(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts,
            openAiCompatibleModel, preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest,
            responseOwnerAccountId, responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, suffixTakenOff, entryFacts);
    }

    /** The same request, for which the tier suffix is taken off the spellings that {@code suffixTakenOff} accepts. */
    public TierPlanRequest withSuffixTakenOff(Predicate<String> suffixTakenOff) {
        return new TierPlanRequest(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts,
            openAiCompatibleModel, preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest,
            responseOwnerAccountId, responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, suffixTakenOff, entryFacts);
    }

    /** The same request with what the original body asks for, which the relays that may serve it are judged by. */
    public TierPlanRequest withEntryFacts(TierEntryFacts entryFacts) {
        return new TierPlanRequest(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts,
            openAiCompatibleModel, preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest,
            responseOwnerAccountId, responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, suffixTakenOff,
            entryFacts);
    }

    /** Whether the entry takes the tier suffix off {@code spelling}, so that advising it to the user would work. */
    public boolean takesSuffix(String spelling) {
        return suffixTakenOff == null || suffixTakenOff.test(spelling);
    }

    /**
     * The same request on another route, which is what the planner works from when the vendor's all-endpoints switch
     * moves a request off the route the facade chose.
     */
    TierPlanRequest withRoute(TierRoute route) {
        return new TierPlanRequest(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts,
            openAiCompatibleModel, preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest,
            responseOwnerAccountId, responseMovable, keyId, sessionName, selector, entry, keyHoldsPreference, suffixTakenOff, entryFacts);
    }
}
