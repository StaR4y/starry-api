package tech.starryapi.account;

import java.util.UUID;

/** Request-scoped identity used to keep model-pool routing stable across retries. */
public record AccountPoolRequestContext(
    String requestToken,
    String requestedModel,
    String upstreamModel,
    String providerHint,
    UUID circuitEpoch,
    ModelPoolKey modelPoolKey
) {

    public AccountPoolRequestContext(
        String requestToken,
        String requestedModel,
        String upstreamModel,
        String providerHint
    ) {
        this(requestToken, requestedModel, upstreamModel, providerHint, null, null);
    }

    public AccountPoolRequestContext(
        String requestToken,
        String requestedModel,
        String upstreamModel,
        String providerHint,
        UUID circuitEpoch
    ) {
        this(requestToken, requestedModel, upstreamModel, providerHint, circuitEpoch, null);
    }

    public AccountPoolRequestContext {
        requestToken = clean(requestToken);
        requestedModel = clean(requestedModel);
        upstreamModel = clean(upstreamModel);
        providerHint = clean(providerHint);
        modelPoolKey = circuitEpoch == null ? null : modelPoolKey;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
