package tech.starryapi.proxy.tier;

import java.util.Objects;
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
    String sessionName
) {
    public TierPlanRequest {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(upstreamRequest, "upstreamRequest");
    }

    /** The same request with the name its session goes by. */
    public TierPlanRequest withSessionName(String sessionName) {
        return new TierPlanRequest(requestId, requestedModel, upstreamModel, providerHint, route, preferDeepSeekAccounts,
            openAiCompatibleModel, preferCodexAccounts, isolateCodexPool, fastMode, billed, upstreamRequest,
            responseOwnerAccountId, responseMovable, keyId, sessionName);
    }
}
