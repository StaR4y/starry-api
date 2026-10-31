package tech.starryapi.proxy.tier;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import tech.starryapi.common.JsonNodes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * How much a request can ask the upstream to read and write, as the upper bound a reservation is sized from. The
 * facade's untiered reservation and the tiered plan use the same formulas, so a vendor without tiers keeps its
 * reservation and a tier is sized the same way for the same request.
 */
public final class RequestBounds {

    /** Room for what the upstream adds around the request (the request itself is counted in bytes, not tokens). */
    private static final long ENVELOPE = 1024L;
    /** Server-side response chains and remote media can hide input tokens from the local request. */
    private static final long HIDDEN_INPUT_FLOOR = 1_000_000L;
    /** What a request that names no output limit is held to by every entry but Responses, and by Responses for a model of unknown maximum. */
    public static final long FALLBACK_OUTPUT_TOKENS = 4096L;
    /** The most output tokens a request may ask for; the facade turns a client's larger limit away. */
    public static final long MAX_OUTPUT_TOKENS = 1_000_000L;
    private static final Set<String> MEDIA_TYPES = Set.of("input_image", "input_file", "input_audio", "image", "document");
    /** The parts whose text a Kiro message keeps; any other part is dropped. */
    private static final Set<String> TEXT_PARTS = Set.of("text", "input_text", "output_text", "refusal");

    private RequestBounds() {
    }

    /**
     * The most output the model writes, as far as the gateway knows (D14): the smaller of the figure the model list shows
     * ({@code listed}) and the one the official price catalog lists ({@code priced}) when both are known, whichever is known
     * when only one is, and {@link #FALLBACK_OUTPUT_TOKENS} for a model that neither gives a figure for. A figure below 1 is
     * no figure.
     */
    public static long modelMaximumOutput(OptionalInt listed, OptionalInt priced) {
        boolean listedFigure = listed.isPresent() && listed.getAsInt() > 0;
        boolean pricedFigure = priced.isPresent() && priced.getAsInt() > 0;
        if (listedFigure && pricedFigure) {
            return Math.min(listed.getAsInt(), priced.getAsInt());
        }
        if (listedFigure) {
            return listed.getAsInt();
        }
        return pricedFigure ? priced.getAsInt() : FALLBACK_OUTPUT_TOKENS;
    }

    /**
     * The limit a request that names none is given (D14): the default the operator configured ({@code configured}), no more
     * than the model writes ({@link #modelMaximumOutput}) and no more than {@link #MAX_OUTPUT_TOKENS}.
     */
    public static long defaultOutputTokens(long configured, OptionalInt listed, OptionalInt priced) {
        return Math.min(Math.min(configured, modelMaximumOutput(listed, priced)), MAX_OUTPUT_TOKENS);
    }

    /**
     * What a relay is given for a request whose output limit the gateway chose (D15): the maximum output its mapping states,
     * no more than {@code gatewayDefault}, the limit that the reservation and the native sources hold the request to (D14); or
     * {@link #FALLBACK_OUTPUT_TOKENS} when the mapping states none, since the gateway does not know what the relay's upstream
     * can write and a relay that is sent more than it can write refuses the request. Never more than {@code gatewayDefault}:
     * the reservation covers no more, and a model that writes less than the fallback is not asked for it.
     *
     * @param mappingMaximum the {@code max_output_tokens} of the relay's mapping of the model; null when it states none
     */
    public static long relayOutputTokens(long gatewayDefault, Integer mappingMaximum) {
        return Math.min(gatewayDefault, mappingMaximum == null ? FALLBACK_OUTPUT_TOKENS : mappingMaximum);
    }

    /**
     * The Chat body of a relay with its output limit set to {@code limit}: the limit it names in {@code max_completion_tokens},
     * {@code max_tokens} or {@code max_output_tokens}, whichever the body has. The body it is given is not changed.
     */
    public static JsonNode withOutputLimit(JsonNode chatBody, long limit) {
        ObjectNode sized = JsonNodes.shallowCopy((ObjectNode) chatBody);
        for (String field : List.of("max_completion_tokens", "max_tokens", "max_output_tokens")) {
            JsonNode value = sized.path(field);
            if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0) {
                sized.put(field, limit);
            }
        }
        return sized;
    }

    /** The most output tokens the request allows; the facade has put the limit it is held to onto every request that named none. */
    public static long outputTokens(JsonNode request) {
        return Math.max(request.path("max_completion_tokens").asLong(0),
            Math.max(request.path("max_tokens").asLong(0), request.path("max_output_tokens").asLong(FALLBACK_OUTPUT_TOKENS)));
    }

    /**
     * The bound for an upstream that is sent the whole request, media included: the size of the request, and the
     * floor of {@link #HIDDEN_INPUT_FLOOR} when a response chain the gateway cannot expand or any remote or inline
     * media is in it.
     */
    public static long nativeInputTokens(JsonNode request) {
        long bound = JsonNodes.utf8Length(request) + ENVELOPE;
        if (request.hasNonNull("previous_response_id") || containsRemoteBillingInput(request)) {
            bound = Math.max(bound, HIDDEN_INPUT_FLOOR);
        }
        return bound;
    }

    /**
     * The bound for a Kiro account, which is sent the text of the request and nothing else: KiroPayloadFactory keeps the
     * text parts of a message and drops the others, so an inline base64 image in a content part does not lift the
     * reservation to {@link #HIDDEN_INPUT_FLOOR}. A response chain the gateway cannot expand still does.
     *
     * <p>Only payloads in the places that are dropped are left out: the non-text parts of the content lists of
     * {@code messages} and {@code input} items, and of a function call output given as a list of parts (the conversion
     * of Responses to chat keeps their text only). Everything else is sent as it is and counts in full, whatever it
     * holds: a content that is a single object or a function call output that is one (both become their JSON as text),
     * the parameter schemas of the tools, {@code thinking}.
     */
    public static long kiroInputTokens(JsonNode request) {
        long bound = JsonNodes.utf8Length(request) - droppedMedia(request.path("messages"))
            - droppedMedia(request.path("input")) + ENVELOPE;
        if (request.hasNonNull("previous_response_id")) {
            bound = Math.max(bound, HIDDEN_INPUT_FLOOR);
        }
        return bound;
    }

    /**
     * The bound for a relay that is sent {@code wireBody}, the request exactly as its settings produce it (design 5.2): its
     * size without the inline base64, which is no part of what the relay reads as text, and {@code imageTokenCap} tokens
     * for each inline image in its place, which the mapping states as the most one image costs there. Media the body only
     * points at (a URL, a file id) has no size to count, so it is the {@link #HIDDEN_INPUT_FLOOR} as it is for the native
     * accounts. A body with no media is bounded by its size, as the native accounts' is.
     */
    public static long xpInputTokens(JsonNode wireBody, int imageTokenCap) {
        JsonNodes.MediaStats media = JsonNodes.mediaStats(wireBody);
        long bound = media.bytesWithoutBase64() + ENVELOPE + media.inlineImages() * imageTokenCap;
        return media.remoteMedia() > 0 ? Math.max(bound, HIDDEN_INPUT_FLOOR) : bound;
    }

    /** The inline base64 of the items of a {@code messages} or {@code input} list that never reaches the Kiro payload. */
    private static long droppedMedia(JsonNode items) {
        if (!items.isArray()) {
            return 0;
        }
        long dropped = 0;
        for (JsonNode item : items) {
            dropped += droppedMediaOfParts(item.path("content"));
            if ("function_call_output".equals(item.path("type").asString(""))) {
                dropped += droppedMediaOfParts(item.path("output"));
            }
        }
        return dropped;
    }

    private static long droppedMediaOfParts(JsonNode parts) {
        if (!parts.isArray()) {
            return 0;
        }
        long dropped = 0;
        for (JsonNode part : parts) {
            if (!TEXT_PARTS.contains(part.path("type").asString(""))) {
                dropped += JsonNodes.base64PayloadBytes(part);
            }
        }
        return dropped;
    }

    private static boolean containsRemoteBillingInput(JsonNode node) {
        if (node.isObject() && (node.hasNonNull("image_url") || node.hasNonNull("file_id")
            || node.hasNonNull("file_url") || MEDIA_TYPES.contains(node.path("type").asString("")))) {
            return true;
        }
        if (node.isContainer()) {
            for (JsonNode child : node) {
                if (containsRemoteBillingInput(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
