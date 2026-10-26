package tech.starryapi.proxy.tier;

import java.util.Objects;
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
 * @param detected the features of the request, with the entry they were found for
 * @param entryDigest what {@link ChatWireSanitizer#sanitize} reads of the original body ({@link
 *     ChatWireSanitizer#entryDigest}); null for the Chat entry, whose Chat body is the client's own
 */
public record TierEntryFacts(Detected detected, JsonNode entryDigest) {

    public TierEntryFacts {
        Objects.requireNonNull(detected, "detected");
    }

    /** @param clientBody the request as the client sent it; the Chat entry's body is its own Chat body */
    public static TierEntryFacts of(TierEntry entry, JsonNode clientBody) {
        PairRegistry.Entry registryEntry = PairRegistry.Entry.valueOf(entry.name());
        Detected detected = RequestFeatureDetector.detect(registryEntry, clientBody);
        return new TierEntryFacts(detected, registryEntry == PairRegistry.Entry.CHAT ? null : ChatWireSanitizer.entryDigest(clientBody));
    }
}
