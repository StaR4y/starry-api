package tech.starryapi.platform.xproto;

/**
 * An OpenAI-format relay answered, and the answer is one the entry cannot tell its client: the gateway holds it back
 * (design 7.10). The relay did nothing wrong. The answer is complete as the relay gave it, or ends at the output limit
 * the gateway itself set (the client named none, so the relay was held to the default the reservation assumes), or its
 * filter stopped it; the Responses entry can say none of that to a client that matters but as {@code incomplete}, which
 * Codex takes for the failure of a stream and answers by sending the same request again, five more times, each of which
 * a relay answers and is paid for.
 *
 * <p>It is an interruption in everything that concerns the request: the relay was written to and may have generated
 * more, so nobody else is asked for the answer (the same 502 with {@code generationStarted}), the reservation is
 * released without charge (the operator carries the cost, D8), and the relay is kept from the session's next requests
 * for a couple of minutes, so that the client's own retry goes to another source. It is no interruption in what it says of
 * the relay: no cooldown, nothing added to the error count of the account or to its admission error rate, no automatic
 * health test, and the request log and the status page count it apart (design 7.3).
 */
public final class RelayAnswerWithheldException extends RelayAbortedAfterDispatchException {

    public RelayAnswerWithheldException(String message) {
        super(message);
    }
}
