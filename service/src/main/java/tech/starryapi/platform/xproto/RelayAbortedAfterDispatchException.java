package tech.starryapi.platform.xproto;

import tech.starryapi.platform.upstream.UpstreamException;

/**
 * An OpenAI-format relay was asked and the exchange broke after the request was written: the relay may be generating
 * the answer, or already has, and a second attempt elsewhere would pay for it twice (design 7.7, invariant I7).
 * Everything that cannot be told apart from that is this: a read or total time limit, a connection that dropped, a
 * stream that ended or turned unreadable once something had been received, a broken response after the first
 * visible output.
 *
 * <p>It is a recoverable 502 with {@code generationStarted} set, so the code that reads the flag never moves the
 * request on to another account or tier. Only the classification of the cross-protocol dispatcher gives it
 * its other consequences: a one-minute transport cooldown, a short-term exclusion of the member for the session, a
 * release of the reservation that is not a review. {@link RelayAnswerWithheldException} is the one kind that has the
 * exclusion and the release and none of the rest.
 */
public class RelayAbortedAfterDispatchException extends UpstreamException {

    public RelayAbortedAfterDispatchException(String message) {
        this(message, null);
    }

    public RelayAbortedAfterDispatchException(String message, Throwable cause) {
        super(502, message, true, null, true);
        if (cause != null) {
            initCause(cause);
        }
    }
}
