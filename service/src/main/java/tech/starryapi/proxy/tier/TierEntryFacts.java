package tech.starryapi.proxy.tier;

import java.util.Objects;
import java.util.function.UnaryOperator;
import tech.starryapi.platform.xproto.ChatWireSanitizer;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.RequestFeatureDetector;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Detected;
import tools.jackson.databind.JsonNode;

/**
 * What a relay needs to know of the request as the client sent it (design 3, step 5b and 7.9): the features it asks for,
 * and what the sanitizer reads of the original body. The body of a Messages or Gemini request is converted to a Chat body
 * before anything is planned, and that conversion drops what the policy has to name (images, server tools,
 * {@code output_config}), so this is taken from the original and the original is let go: neither part refers to the body,
 * and a body with large base64 images is not kept alive for the minutes an upstream call can take.
 *
 * <p>The Responses entry is planned over its own body: that is what its native accounts are sent, what the reservation is
 * sized from (it counts the reasoning of the history, which a relay is not given) and what names the session. A relay is
 * given a Chat body made from it (design 7.6), which {@code chatConversion} makes when a relay is looked at.
 *
 * @param detected the features of the request, with the entry they were found for
 * @param entryDigest what {@link ChatWireSanitizer#sanitize} reads of the original body ({@link
 *     ChatWireSanitizer#entryDigest}); null for the Chat entry, whose Chat body is the client's own
 * @param chatConversion how the body the request is planned over is made into the Chat body a relay is given; null when
 *     that body already is one (the Chat entry, and Messages, whose conversion came before the plan)
 * @param gatewayOutput whether the output limit of the planned body is one the gateway chose and not the client: the
 *     default of the model, which a Responses request that names no limit is given (D14, design 7.10). A relay is not
 *     given it: its output is the maximum its mapping states, or 4,096 (D15, {@link XpEligibility}). A limit the client
 *     wrote, and the 4,096 the other entries give, are sent to a relay as they are
 */
public record TierEntryFacts(Detected detected, JsonNode entryDigest, UnaryOperator<JsonNode> chatConversion, boolean gatewayOutput) {

    public TierEntryFacts {
        Objects.requireNonNull(detected, "detected");
    }

    /** Facts for a request whose output limit is not the gateway's own, planned over a body that is converted for a relay. */
    public TierEntryFacts(Detected detected, JsonNode entryDigest, UnaryOperator<JsonNode> chatConversion) {
        this(detected, entryDigest, chatConversion, false);
    }

    /** Facts for a request that is planned over a Chat body. */
    public TierEntryFacts(Detected detected, JsonNode entryDigest) {
        this(detected, entryDigest, null);
    }

    /** @param clientBody the request as the client sent it; the Chat entry's body is its own Chat body */
    public static TierEntryFacts of(TierEntry entry, JsonNode clientBody) {
        PairRegistry.Entry registryEntry = PairRegistry.Entry.valueOf(entry.name());
        Detected detected = RequestFeatureDetector.detect(registryEntry, clientBody);
        return new TierEntryFacts(detected, registryEntry == PairRegistry.Entry.CHAT ? null : ChatWireSanitizer.entryDigest(clientBody));
    }

    /** The same facts, with the conversion that makes the planned body into the Chat body of a relay. */
    public TierEntryFacts withChatConversion(UnaryOperator<JsonNode> conversion) {
        return new TierEntryFacts(detected, entryDigest, conversion, gatewayOutput);
    }

    /** The same facts, saying whether the output limit of the planned body is the gateway's own (D14). */
    public TierEntryFacts withGatewayOutput(boolean gatewayOutput) {
        return new TierEntryFacts(detected, entryDigest, chatConversion, gatewayOutput);
    }

    /**
     * The Chat body a relay is given of the request: the body it was planned over when that is one, else its conversion.
     * It may fail; the caller treats a body that cannot be made as a conversion the relay cannot serve.
     */
    JsonNode chatBody(JsonNode planned) {
        return chatConversion == null ? planned : chatConversion.apply(planned);
    }
}
