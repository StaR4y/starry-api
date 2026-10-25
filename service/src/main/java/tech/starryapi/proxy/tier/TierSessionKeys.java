package tech.starryapi.proxy.tier;

import java.util.Optional;
import tech.starryapi.common.Hashing;
import tools.jackson.databind.JsonNode;

/**
 * What a tiered request calls its session when it keeps the tier a session was pushed down to (design 4.7). It is used
 * only for that binding: the session hint of the request, and the account affinity built on it, stay as they are for
 * every request.
 *
 * <p>A session is named by, in this order:
 * <ol>
 *   <li>the hint the client gave (a session header or a conversation id in the body), if it is one that tells sessions
 *       apart, which {@link #named} decides. A hint that is only the key id would put all of the key's traffic into
 *       one session, and one that is only the id of the previous response changes every turn;
 *   <li>the response it continues, for a Responses request ({@link #ofResponse}). The binding of the session is also
 *       registered under the id of the response the request produces, so that the next turn finds it;
 *   <li>what the conversation starts with ({@link #fingerprint}), for a client that sends neither: the first system
 *       text and the first user text. Sessions that begin alike share a binding.
 * </ol>
 */
public final class TierSessionKeys {

    /** How much of the system text and of the first user text the fingerprint is made from. */
    static final int FINGERPRINT_TEXT_CHARS = 2048;

    private TierSessionKeys() {
    }

    /**
     * The hint when it names a session. A session hint is the key id alone when the client sent nothing, and
     * {@code keyId:hint} when it did; only the latter is reliable (the facade's own test for the account affinity).
     *
     * @return null when the hint does not name a session
     */
    public static String named(String sessionHint) {
        return sessionHint != null && sessionHint.indexOf(':') > 0 ? sessionHint : null;
    }

    /**
     * The name of the session of a Responses request: its own hint if it has one, else the response it continues.
     *
     * @param namedHint the session hint computed without the previous response id as a fallback, or null
     * @return null when the request names neither
     */
    public static String ofResponses(String keyId, String namedHint, String previousResponseId) {
        String named = named(namedHint);
        if (named != null) {
            return named;
        }
        return previousResponseId == null || previousResponseId.isBlank() ? null : ofResponse(keyId, previousResponseId);
    }

    /** The name the session of a Responses chain is known by after the response {@code responseId}. */
    public static String ofResponse(String keyId, String responseId) {
        return keyId + ":resp:" + responseId;
    }

    /**
     * What the session is known by when the client named none: the key, the model and the beginning of the
     * conversation, hashed. Image and file parts do not take part, so a large attachment costs nothing.
     *
     * @return null when the request has neither a system text nor a user text to tell it from others
     */
    public static String fingerprint(String keyId, String modelKey, JsonNode body) {
        String system = systemText(body);
        String user = firstUserText(body);
        if (system.isEmpty() && user.isEmpty()) {
            return null;
        }
        return "fp:" + Hashing.sha256(keyId + '\u0000' + modelKey + '\u0000' + system + '\u0000' + user);
    }

    /**
     * The name of the session of a request: what the client named, else the fingerprint.
     *
     * @param keyId the API key; a session is never shared across keys
     * @param named the result of {@link #named} or {@link #ofResponses}
     * @return empty when nothing tells the session from others
     */
    public static Optional<String> resolve(String keyId, String modelKey, String named, JsonNode body) {
        if (keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(named != null ? named : fingerprint(keyId, modelKey, body));
    }

    /** The key the tier of a session is kept under: per vendor and model, since what a tier can serve depends on the model. */
    public static String bindingKey(String vendor, String modelKey, String sessionKey) {
        return "tier:" + vendor + '|' + modelKey + '|' + sessionKey;
    }

    private static String systemText(JsonNode body) {
        StringBuilder text = new StringBuilder();
        append(text, body.path("instructions"));
        for (JsonNode message : messages(body)) {
            String role = message.path("role").asString("");
            if (role.equals("system") || role.equals("developer")) {
                append(text, message.path("content"));
            }
            if (full(text)) {
                break;
            }
        }
        return clip(text);
    }

    private static String firstUserText(JsonNode body) {
        for (JsonNode message : messages(body)) {
            if ("user".equals(message.path("role").asString(""))) {
                StringBuilder text = new StringBuilder();
                append(text, message.path("content"));
                return clip(text);
            }
        }
        // A Responses request whose input is the text itself.
        StringBuilder text = new StringBuilder();
        JsonNode input = body.path("input");
        if (input.isString()) {
            append(text, input);
        }
        return clip(text);
    }

    /** The conversation as a list of messages: Chat {@code messages}, or the message items of a Responses {@code input}. */
    private static Iterable<JsonNode> messages(JsonNode body) {
        JsonNode messages = body.path("messages");
        if (messages.isArray()) {
            return messages;
        }
        JsonNode input = body.path("input");
        return input.isArray() ? input : java.util.List.of();
    }

    /** Adds the text of a message content, which is a string or a list of parts; the parts that are not text add nothing. */
    private static void append(StringBuilder text, JsonNode content) {
        if (full(text)) {
            return;
        }
        if (content.isString()) {
            text.append(content.asString());
        } else if (content.isArray()) {
            for (JsonNode part : content) {
                JsonNode value = part.path("text");
                if (value.isString()) {
                    text.append(value.asString());
                }
                if (full(text)) {
                    return;
                }
            }
        }
    }

    private static boolean full(StringBuilder text) {
        return text.length() >= FINGERPRINT_TEXT_CHARS;
    }

    private static String clip(StringBuilder text) {
        return text.length() <= FINGERPRINT_TEXT_CHARS ? text.toString() : text.substring(0, FINGERPRINT_TEXT_CHARS);
    }
}
