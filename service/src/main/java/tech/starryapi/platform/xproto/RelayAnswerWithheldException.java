package tech.starryapi.platform.xproto;

import tech.starryapi.platform.core.PlatformUsage;

/**
 * An OpenAI-format relay answered, and the answer is one the entry cannot tell its client: the gateway holds it back
 * (design 7.10). The relay did nothing wrong. The answer is complete as the relay gave it, or ends at the output limit
 * the gateway itself set (the client named none, so the relay was held to the limit the gateway chose for it, 4,096 or the
 * maximum output of its mapping, D15), or its filter stopped it; the Responses entry can say none of that to a client that matters but as {@code incomplete}, which
 * Codex takes for the failure of a stream and answers by sending the same request again, five more times, each of which
 * a relay answers and is paid for.
 *
 * <p>It is an interruption in everything that concerns the request: the relay was written to and may have generated
 * more, so nobody else is asked for the answer (the same 502 with {@code generationStarted}), the reservation is
 * released without charge (the operator carries the cost, D8), and the relay is kept from the session's next requests
 * for a couple of minutes, so that the client's own retry goes to another source. It is no interruption in what it says of
 * the relay: no cooldown, nothing added to the error count of the account or to its admission error rate, no automatic
 * health test, and the request log and the status page count it apart (design 7.3).
 *
 * <p>The answer was paid for by the operator, and what it cost is what the relay reported of it, read as the usage of a
 * served answer is ({@link #usage}): the request log keeps it on the row of the attempt for the operator to see (D11),
 * apart from the tokens of the user's own usage, which stay 0 because nothing is charged.
 */
public final class RelayAnswerWithheldException extends RelayAbortedAfterDispatchException {

    private final PlatformUsage usage;

    public RelayAnswerWithheldException(String message) {
        this(message, null);
    }

    /**
     * @param usage what the relay reported of the answer, bounded by the limits the tier reserved as a served answer's
     *     is; null when it could not be read
     */
    public RelayAnswerWithheldException(String message, PlatformUsage usage) {
        super(message);
        this.usage = usage;
    }

    /** What the relay reported of the answer that was held back, or null when that is not known. */
    public PlatformUsage usage() {
        return usage;
    }
}
