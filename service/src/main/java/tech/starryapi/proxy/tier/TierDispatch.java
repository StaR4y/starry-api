package tech.starryapi.proxy.tier;

import java.util.List;
import java.util.Objects;
import tech.starryapi.platform.xproto.PairRegistry.PairKey;
import tech.starryapi.platform.xproto.PairRegistry.SourceKind;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tools.jackson.databind.JsonNode;

/**
 * How the request is sent to the account a tier selected, fixed when the account is selected (design 7.5). A native
 * dispatch calls the same client with the same arguments as an untiered request does and carries nothing else. A
 * dispatch to an OpenAI-format relay carries what the cross-protocol dispatcher needs and what the rest of the request
 * has to know about the attempt; untiered requests never get one.
 *
 * @param pair the pair (kind of source, entry endpoint, vendor) the attempt serves
 * @param modelId the id of the mapping row that was found for the requested model. It, and not the model name the client
 *     asked for, keys the model cooldown and the admission of the attempt, so that every alias of a model shares the
 *     relay's "model not served" answer instead of meeting it once each
 * @param wireModel the model name the relay is asked for, written to the request exactly as stored
 * @param member the relay's registration, with the switches the answer is read by
 * @param wireBody the request as the member's settings produce it, built once per distinct settings of the plan and
 *     shared: it has no model name, the dispatcher writes {@link #wireModel} into a copy, and nobody edits it
 * @param outgoingEndpoint the relay endpoint the body goes to
 * @param degraded the features of the client's request that the body does not carry (the soft ones of design 7.9), which
 *     the result of the attempt reports
 */
public record TierDispatch(
    Kind kind,
    PairKey pair,
    String modelId,
    String wireModel,
    Member member,
    JsonNode wireBody,
    String outgoingEndpoint,
    List<String> degraded
) {

    public enum Kind {
        NATIVE,
        OPENAI_CHAT
    }

    /** The endpoint of an OpenAI-format relay that a Chat Completions body is sent to. */
    public static final String CHAT_COMPLETIONS = "/chat/completions";

    private static final String ENDPOINT_SCOPE_PREFIX = "xp:";

    private static final TierDispatch NATIVE = new TierDispatch(Kind.NATIVE, null, null, null, null, null, null, List.of());

    public TierDispatch {
        Objects.requireNonNull(kind, "kind");
        degraded = degraded == null ? List.of() : List.copyOf(degraded);
        if (kind == Kind.NATIVE) {
            if (pair != null || modelId != null || wireModel != null || member != null || wireBody != null
                || outgoingEndpoint != null || !degraded.isEmpty()) {
                throw new IllegalArgumentException("A native dispatch carries nothing of a relay");
            }
        } else {
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(member, "member");
            if (pair.kind() != SourceKind.OPENAI_CHAT_RELAY || !SourceKind.OPENAI_CHAT_RELAY.sourceProtocol().equals(member.protocol())) {
                throw new IllegalArgumentException("A dispatch to an OpenAI-format relay needs a member that is registered as one");
            }
            if (modelId == null || modelId.isBlank() || wireModel == null || wireModel.isBlank()
                || outgoingEndpoint == null || outgoingEndpoint.isBlank()) {
                throw new IllegalArgumentException("A dispatch to a relay names the mapping, the wire model and the endpoint");
            }
            if (wireBody == null || !wireBody.isObject()) {
                throw new IllegalArgumentException("The body of a dispatch to a relay must be a JSON object");
            }
        }
    }

    public static TierDispatch nativeDispatch() {
        return NATIVE;
    }

    /** A Chat Completions request to the relay of {@code member}; see the record's components. */
    public static TierDispatch openAiChat(
        PairKey pair,
        String modelId,
        String wireModel,
        Member member,
        JsonNode wireBody,
        List<String> degraded
    ) {
        return new TierDispatch(Kind.OPENAI_CHAT, pair, modelId, wireModel, member, wireBody, CHAT_COMPLETIONS, degraded);
    }

    /**
     * The output limit the body asks the relay for: {@code max_completion_tokens}, else {@code max_tokens}, which is the one
     * the relay is told it may write and so the most it can be charged for (design 5.8). 0 for a native dispatch, which
     * has no body of its own, and for a body that names none.
     */
    public long forwardedOutputLimit() {
        if (wireBody == null) {
            return 0;
        }
        long limit = wireBody.path("max_completion_tokens").asLong(0);
        return limit > 0 ? limit : Math.max(0, wireBody.path("max_tokens").asLong(0));
    }

    /**
     * What the model cooldown and the admission of the attempt are keyed by, or null for a native dispatch, which uses
     * the model of the pool context as it always has.
     */
    public String modelScope() {
        return modelId;
    }

    /**
     * The endpoint scope of the attempt's admission and of the cooldown a failure puts on it, or null for a native
     * dispatch, which keeps the path of the client's request. A relay's endpoints are told apart from the native ones
     * of the same name (design 7.5).
     */
    public String endpointScope() {
        return outgoingEndpoint == null ? null : ENDPOINT_SCOPE_PREFIX + outgoingEndpoint;
    }
}
