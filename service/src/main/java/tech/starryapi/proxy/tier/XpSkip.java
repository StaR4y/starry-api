package tech.starryapi.proxy.tier;

import java.util.Objects;

/**
 * Why a relay registered in a tier takes no part in one request: the first of the conditions of design 3, step 5b that it
 * does not meet. The plan keeps them (they are the reasons the status page counts), and the facade never shows them to a
 * client, which is told what its own request asked for and not how the relays are set up.
 *
 * @param tierId the tier the relay is registered in
 * @param accountId the relay
 * @param detail what the reason is about, for the logs and the status page: the features for {@link Reason#FEATURE} (the
 *     labels of {@code RequestFeatureDetector.Feature}, separated by commas), the first problem of the lint for {@link
 *     Reason#CONVERSION}, the estimated input and the window, or the size a relay refused, for {@link Reason#CONTEXT};
 *     empty for the others
 */
public record XpSkip(String tierId, String accountId, Reason reason, String detail) {

    /** In the order the conditions are met; a relay is skipped for the first it fails. */
    public enum Reason {
        /** The registry or the pair gate of the vendor does not let this key's request through to relays. */
        GATE,
        /** The account is no longer an OpenAI-compatible API key. */
        NOT_A_RELAY,
        /** The relay has no enabled mapping for the model asked for. */
        NO_MAPPING,
        /** The mapping was never verified, or what it was verified for has changed since. */
        UNVERIFIED,
        /** The request asks for a feature the relay cannot carry and the native accounts of the route can (design 7.9). */
        FEATURE,
        /**
         * The relay broke off an answer for this session a moment ago and may be generating it still (design 7.8): the
         * client's retry of that request is not given to the same relay.
         */
        INTERRUPTED,
        /** The request, converted for this relay, fails the strict lint. */
        CONVERSION,
        /**
         * The request is larger than the window of the mapping, or than a prompt that this relay refused for its length
         * (design 7.8). Both are about the request and the relay, not about how busy the relay is.
         */
        CONTEXT;

        /**
         * Whether the relay is left out for something about this request or this moment, as opposed to something that holds
         * for every request of the session and the model: a gate that is closed to the key, an account that is no relay, a
         * mapping that is missing or was never verified. The tiers of a session are bound per session and model (design
         * 4.7), so such a relay is no member of its tier for the session at all, whereas one that is left out for what the
         * request carries (a feature, a conversion, its size) or for a minute (it broke off an answer) is a member that
         * another request of the same session would be given. A session served by the tier below it because of the latter
         * has not been pushed out of the tier (design 0, 4.7).
         */
        public boolean ofTheRequest() {
            return this == FEATURE || this == INTERRUPTED || this == CONVERSION || this == CONTEXT;
        }

        /** Whether the relay is back after a while without anyone changing anything: only an interruption is (design 7.8). */
        public boolean temporary() {
            return this == INTERRUPTED;
        }
    }

    public XpSkip {
        Objects.requireNonNull(tierId, "tierId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(reason, "reason");
        detail = detail == null ? "" : detail;
    }

    /** The feature of a {@link Reason#FEATURE} skip that the client is told, the first it asked for; empty for any other. */
    public String firstFeature() {
        if (reason != Reason.FEATURE) {
            return "";
        }
        int comma = detail.indexOf(',');
        return comma < 0 ? detail : detail.substring(0, comma);
    }
}
