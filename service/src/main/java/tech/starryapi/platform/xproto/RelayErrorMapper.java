package tech.starryapi.platform.xproto;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes the error an OpenAI-format relay reported into the status the gateway deals with, and into a message that is
 * the gateway's own words (design 7.11). It reads both places a relay says no: a response with an error status, and
 * the {@code error} member inside a successful response ({@link #fromInBand}, which is what the accumulator's error
 * hook is).
 *
 * <p>What the relay said decides, in this order; the first that applies wins:
 *
 * <ol>
 *   <li>It has run out of money (type or code {@code insufficient_quota}, billing; the words "insufficient quota",
 *       "exceeded your current quota" or "credit balance is too low", which is how Anthropic says it, with a 400):
 *       402. OpenAI itself sends this with a 429, which would be taken for a busy account that is back in a minute.
 *   <li>The request does not fit the model's context window ({@code context_length_exceeded}, "maximum context
 *       length", "prompt is too long", "input is too long"): 400, marked {@link
 *       RelayErrorException#isContextExceeded()}.
 *   <li>It does not serve the model ({@code model_not_found}, "no available channel", the other phrases relays use):
 *       404, whatever status it came with, because the classification of a model that is not served looks at the
 *       status and no longer has the relay's text to look at.
 *   <li>A response whose status is 408, 504 or 524: a timeout, of the relay or of the gateway in front of it, while the
 *       relay may have been generating. It keeps its status, whatever its body calls it, because the caller must not
 *       treat it as a refusal (the dispatcher turns it into an interruption).
 *   <li>The kind of error: rate limit 429; authentication 401; permission 403; invalid request 400; timeout 504;
 *       overloaded or server error 503 (recoverable). The code is read before the type, because OpenAI answers a bad
 *       key with the type {@code invalid_request_error} and the code {@code invalid_api_key}. The generic invalid
 *       request gives way to a status of 402 or 429, which says more, whether it is the status of the response or the
 *       number in {@code error.code} (a relay that always answers 200 can say it nowhere else): relays name the type of
 *       everything they refuse {@code invalid_request_error} (DeepSeek says so of a balance that is spent, with a 402),
 *       and a 400 would be taken for a request no relay can answer, where this one is a relay that cannot, now or for a
 *       minute.
 *   <li>A numeric {@code error.code} from 400 to 599 is taken as the status.
 *   <li>For a response with an error status, that status. It carries what no name above says: 408, 409, 413, 422 and a
 *       plain 5xx from a proxy in front of the relay are what the failure classification expects to see.
 *   <li>Anything else: a recoverable 502.
 * </ol>
 *
 * <p>The message is {@code Source error <status> (<type>)}, where the type is the relay's {@code code} or {@code type}
 * if it is lower case letters and underscores (at most 40, so it can hold no host, URL or prompt text), and the
 * parentheses are left out when neither is. The relay's own text (at most 2 KB, control characters flattened) goes to
 * the server log at WARN level and nowhere else: not into the exception, so neither the client's error nor the
 * user-readable request log can show it. A relay may echo the key it was given, or any token, in its message, so what
 * is logged has the account's own key, and anything that has the shape of a credential, replaced by a mark first. Only
 * the start of the text is looked at for that, and for everything else the relay said: an error inside a successful
 * response is bounded by nothing but the size of the answer, and scanning megabytes for credentials to log two
 * kilobytes would hold the thread that reads the answer.
 *
 * <p>A relay's words are data: they select a branch above and are never obeyed or repeated.
 */
public final class RelayErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(RelayErrorMapper.class);

    /** The most of the relay's own text that is written to the server log, in UTF-8 bytes. */
    static final int LOG_LIMIT_BYTES = 2048;

    /** An error body longer than this is read as text from its start; no relay writes a longer error. */
    private static final int READ_LIMIT_CHARS = 64 * 1024;

    /**
     * The start of a text that is looked at to be logged: more than the log holds, since replacing a credential can make
     * text longer or much shorter. What is past it is not logged, and the cut is made where a credential is not split.
     */
    private static final int LOG_SCAN_CHARS = 4 * LOG_LIMIT_BYTES;

    /** How far back from the cut a white space is looked for, so that a credential is not cut in the middle. */
    private static final int CUT_SEARCH_CHARS = 128;

    /** What replaces a credential in the log. */
    static final String REDACTED = "[redacted]";

    /** A key the account itself holds is only masked when it is long enough not to be a word of the message. */
    private static final int MIN_SECRET_LENGTH = 6;

    /** No key is longer than this; a longer secret is not looked for at the cut of a long text (it is still masked in the part scanned). */
    private static final int MAX_SECRET_LENGTH = 4096;

    /**
     * The shapes of credentials: the keys of OpenAI, Anthropic and OpenRouter ({@code sk-}), Google's ({@code AIza}), a
     * bearer token or an assignment of a key, token, secret or password as a relay echoes a request back, and any other
     * run of 40 or more letters, digits and the characters of base64 and hashes.
     */
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?:\\bsk-[A-Za-z0-9_\\-]{6,}"
            + "|\\bAIza[A-Za-z0-9_\\-]{20,}"
            + "|\\b[Bb]earer\\s+[^\\s\"',;]+"
            // The quotes may be escaped: the relay's text is mostly a JSON body, or JSON inside a JSON string.
            + "|\\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password)\\b\\\\?[\"']?\\s*[:=]\\s*\\\\?[\"']?[^\\s\"',;&}\\\\]+"
            + "|[A-Za-z0-9_+/\\-]{40,}={0,2})",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SAFE_TOKEN = Pattern.compile("^[a-z_]{1,40}$");
    private static final Pattern STATUS_NUMBER = Pattern.compile("^[0-9]{3}$");

    // The patterns are matched after case, '_' and '-' are flattened (see flatten), so one entry covers
    // "model_not_found", "model-not-found" and "Model not found".
    private static final List<String> QUOTA_IN_NAMES = List.of(
        "insufficient quota", "insufficient user quota", "insufficient balance", "insufficient credit", "billing",
        "payment required"
    );
    private static final List<String> QUOTA_IN_TEXT = List.of(
        "insufficient quota", "exceeded your current quota", "credit balance is too low", "insufficient credit",
        "purchase credits", "out of credits", "insufficient balance", "insufficient account balance",
        "balance is not enough", "余额不足", "额度不足"
    );
    private static final List<String> CONTEXT_IN_NAMES = List.of("context length exceeded");
    private static final List<String> CONTEXT_IN_TEXT = List.of(
        "context length exceeded", "maximum context length", "prompt is too long", "input is too long"
    );
    // The union of what the OpenAI client retries on (OpenAiUpstreamClient.isModelNotFound) and what the failure
    // classifier reads as a model the account may not use (UpstreamFailureClassifier.forbiddenFailure).
    private static final List<String> MODEL_NOT_SERVED = List.of(
        "model not found", "no available channel", "model does not exist", "does not support model",
        "does not exist or you do not have access", "do not have access to the model",
        "does not have access to model", "not allowed to use this model", "not allowed to use model",
        "access to this model", "unsupported model", "model is not supported"
    );

    private final ObjectMapper mapper;

    public RelayErrorMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The relay answered with an error status.
     *
     * @param httpStatus the status of the response
     * @param body       what the relay sent as the body, if anything
     * @param retryAfter the {@code Retry-After} header, if any; kept on the exception for the client's answer
     * @param source     names the relay for the server log (an account id); it is never part of the exception
     * @param secret     the key the account sent the relay, which is masked wherever the relay's words repeat it in the
     *                   log; null only when no key was sent. There is no call without it, so that no caller leaves
     *                   the account's own key in the log by not passing it: the shapes of a credential cannot know
     *                   a key that has none
     */
    public RelayErrorException fromResponse(int httpStatus, String body, String retryAfter, String source, String secret) {
        String text = body == null ? "" : body.strip();
        Fields fields = fieldsOfBody(text);
        Verdict verdict = verdict(fields, httpStatus);
        log.warn("Relay error: source={} httpStatus={} mappedStatus={} relayText={}",
            source, httpStatus, verdict.status(), forLog(text, secret));
        return exception(verdict, fields, retryAfter);
    }

    /**
     * The relay answered with success and put an error in the body.
     *
     * @param error  the {@code error} member the relay sent (an object, or just its text)
     * @param source names the relay for the server log; it is never part of the exception
     * @param secret the key of the account to mask in the log (see {@link #fromResponse})
     */
    public RelayErrorException fromInBand(JsonNode error, String source, String secret) {
        Fields fields = fieldsOfError(error);
        Verdict verdict = verdict(fields, 0);
        log.warn("Relay error inside a successful response: source={} mappedStatus={} relayText={}",
            source, verdict.status(),
            forLog(error == null ? "" : error.isString() ? error.asString("") : error.toString(), secret));
        return exception(verdict, fields, null);
    }

    private static RelayErrorException exception(Verdict verdict, Fields fields, String retryAfter) {
        String token = fields.safeToken();
        String message = "Source error " + verdict.status() + (token == null ? "" : " (" + token + ")");
        return new RelayErrorException(verdict.status(), message, verdict.recoverable(), retryAfter, verdict.contextExceeded());
    }

    // --- what the relay said ---

    /** The type, the code, the text and the numeric code (0 if the code is none) of an error, as the relay wrote them. */
    private record Fields(String type, String code, String message, int numericCode) {

        static final Fields NONE = new Fields("", "", "", 0);

        static Fields ofText(String text) {
            return new Fields("", "", text, 0);
        }

        /** The code, or else the type, if it can be shown to the client as it is. */
        String safeToken() {
            for (String candidate : new String[] {code, type}) {
                String lower = candidate.strip().toLowerCase(Locale.ROOT);
                if (SAFE_TOKEN.matcher(lower).matches() && !lower.equals("none") && !lower.equals("null")) {
                    return lower;
                }
            }
            return null;
        }
    }

    private Fields fieldsOfBody(String body) {
        if (body.isEmpty()) {
            return Fields.NONE;
        }
        String readable = body.length() > READ_LIMIT_CHARS ? body.substring(0, READ_LIMIT_CHARS) : body;
        JsonNode root;
        try {
            root = mapper.readTree(readable);
        } catch (JacksonException notJson) {
            return Fields.ofText(readable);
        }
        if (root != null && root.isArray() && root.size() > 0) {
            root = root.get(0);
        }
        if (root == null || !root.isObject()) {
            return Fields.ofText(readable);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            return fieldsOfError(error);
        }
        // Some relays put the error at the top: {"message": …, "code": …}.
        if (root.has("message") || root.has("type") || root.has("code")) {
            return fieldsOfError(root);
        }
        return Fields.ofText(readable);
    }

    private static Fields fieldsOfError(JsonNode error) {
        if (error == null || error.isNull() || error.isMissingNode()) {
            return Fields.NONE;
        }
        if (!error.isObject()) {
            return Fields.ofText(limited(error.isString() ? error.asString("") : error.toString()));
        }
        String message = text(error.get("message"));
        if (message.isEmpty()) {
            // An unusual shape: the whole object is the text to look at.
            message = error.toString();
        }
        JsonNode code = error.get("code");
        return new Fields(limited(text(error.get("type"))), limited(text(code)), limited(message), numericCode(code));
    }

    /** What is read of any text of the relay: no more than it is for a body (see {@link #fieldsOfBody}). */
    private static String limited(String text) {
        return text.length() > READ_LIMIT_CHARS ? text.substring(0, READ_LIMIT_CHARS) : text;
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        return value.isString() ? value.asString("") : value.isObject() || value.isArray() ? value.toString() : value.asString("");
    }

    private static int numericCode(JsonNode code) {
        if (code == null) {
            return 0;
        }
        int number = 0;
        if (code.isIntegralNumber()) {
            number = code.asInt(0);
        } else if (code.isString() && STATUS_NUMBER.matcher(code.asString("").strip()).matches()) {
            number = Integer.parseInt(code.asString("").strip());
        }
        return number >= 400 && number <= 599 ? number : 0;
    }

    // --- what it means ---

    private record Verdict(int status, boolean recoverable, boolean contextExceeded) {

        static Verdict of(int status) {
            return new Verdict(status, isRecoverableStatus(status), false);
        }
    }

    /** The statuses the other upstream clients treat as worth another try (OpenAiUpstreamClient.isRecoverableStatus). */
    private static boolean isRecoverableStatus(int status) {
        return status == 402 || status == 429 || status >= 500;
    }

    private static Verdict verdict(Fields fields, int httpStatus) {
        String code = flatten(fields.code());
        String type = flatten(fields.type());
        String message = flatten(fields.message());
        String names = code + "\n" + type;
        String everything = names + "\n" + message;

        if (containsAny(names, QUOTA_IN_NAMES) || containsAny(message, QUOTA_IN_TEXT)) {
            return Verdict.of(402);
        }
        if (containsAny(names, CONTEXT_IN_NAMES) || containsAny(message, CONTEXT_IN_TEXT)) {
            return new Verdict(400, false, true);
        }
        if (containsAny(everything, MODEL_NOT_SERVED)) {
            return Verdict.of(404);
        }
        if (isTimeoutStatus(httpStatus)) {
            return Verdict.of(httpStatus);
        }
        for (String name : new String[] {code, type}) {
            Verdict byKind = byKind(name);
            if (byKind != null && !(byKind.status() == 400
                && (saysMoreThanInvalidRequest(httpStatus) || saysMoreThanInvalidRequest(fields.numericCode())))) {
                return byKind;
            }
        }
        if (fields.numericCode() != 0) {
            return Verdict.of(fields.numericCode());
        }
        if (httpStatus >= 400 && httpStatus <= 599) {
            return Verdict.of(httpStatus);
        }
        return new Verdict(502, true, false);
    }

    /**
     * The statuses that mean more than the invalid request their relay's body calls them (402 and 429): the status of the
     * response, or the number a relay that always answers 200 puts into {@code error.code}.
     */
    private static boolean saysMoreThanInvalidRequest(int status) {
        return status == 402 || status == 429;
    }

    /** The statuses with which a relay or the gateway in front of it says that the time ran out. */
    public static boolean isTimeoutStatus(int status) {
        return status == 408 || status == 504 || status == 524;
    }

    private static Verdict byKind(String name) {
        if (name.isEmpty()) {
            return null;
        }
        if (name.contains("rate limit") || name.contains("too many requests")) {
            return Verdict.of(429);
        }
        if (name.contains("authentication") || name.contains("invalid api key") || name.contains("incorrect api key")
            || name.contains("unauthorized")) {
            return Verdict.of(401);
        }
        if (name.contains("permission") || name.contains("forbidden") || name.contains("access denied")) {
            return Verdict.of(403);
        }
        if (name.contains("invalid request") || name.contains("bad request") || name.contains("invalid parameter")
            || name.contains("invalid argument")) {
            return Verdict.of(400);
        }
        if (name.contains("timeout") || name.contains("timed out")) {
            return Verdict.of(504);
        }
        // "api_error" only when it is the whole name: new-api calls everything it says "new_api_error".
        if (name.contains("overloaded") || name.contains("server error") || name.contains("service unavailable")
            || name.contains("internal error") || name.equals("api error")) {
            return new Verdict(503, true, false);
        }
        return null;
    }

    private static String flatten(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').strip();
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // --- the server log ---

    /**
     * The text with the account's own key, and whatever has the shape of a credential, replaced by {@link #REDACTED}. The
     * key is masked as it is; JSON escapes or other encodings of it are left to the shapes.
     */
    static String redact(String text, String secret) {
        String masked = text;
        if (secret != null && secret.strip().length() >= MIN_SECRET_LENGTH) {
            masked = masked.replace(secret.strip(), REDACTED);
        }
        return CREDENTIAL.matcher(masked).replaceAll(REDACTED);
    }

    /**
     * The text as it is to be logged: its start, redacted and clipped. Only {@link #LOG_SCAN_CHARS} of it are scanned for
     * credentials, and the cut falls on white space when there is some near the end, so that the start of a credential is
     * not left in the log without the rest that would have made it known for one.
     */
    static String forLog(String text, String secret) {
        if (text.length() <= LOG_SCAN_CHARS) {
            return clip(redact(text, secret));
        }
        int cut = LOG_SCAN_CHARS;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        for (int i = cut; i > Math.max(0, cut - CUT_SEARCH_CHARS); i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                cut = i;
                break;
            }
        }
        String key = secret == null ? "" : secret.strip();
        if (key.length() >= MIN_SECRET_LENGTH && key.length() <= MAX_SECRET_LENGTH) {
            // The account's own key is masked whole or not at all: the cut does not fall inside one.
            for (int start = Math.max(0, cut - key.length() + 1); start < cut; start++) {
                if (text.startsWith(key, start)) {
                    cut = start;
                    break;
                }
            }
        }
        return clip(redact(text.substring(0, cut), secret) + "...");
    }

    /** At most {@link #LOG_LIMIT_BYTES} of the text, whole characters, every control character (a line break too) a space. */
    static String clip(String text) {
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > LOG_LIMIT_BYTES) {
                out.append("...");
                break;
            }
            bytes += size;
            out.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
        }
        return out.toString();
    }
}
