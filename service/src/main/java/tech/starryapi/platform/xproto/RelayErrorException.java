package tech.starryapi.platform.xproto;

import tech.starryapi.platform.upstream.UpstreamException;

/**
 * An error an OpenAI-format relay reported (an error status, or an {@code error} member inside a successful response),
 * made into the status the gateway deals with by {@link RelayErrorMapper} (design 7.11). Nothing the relay wrote is in
 * it: the message is the fixed {@code Source error <status> (<type>)}.
 *
 * <p>It is only made when the relay said no before anything was generated (an error after the first output is a {@link
 * RelayAbortedAfterDispatchException}), so {@code generationStarted} is false. The one thing it carries beyond the
 * status is {@link #isContextExceeded()}: a 400 that says the request does not fit the model's context window, which
 * the dispatcher answers differently from any other 400 (design 7.8: one crossing to a native tier, and the limit is
 * learned).
 */
public final class RelayErrorException extends UpstreamException {

    private final boolean contextExceeded;

    RelayErrorException(int statusCode, String message, boolean recoverable, String retryAfter, boolean contextExceeded) {
        super(statusCode, message, recoverable, retryAfter);
        this.contextExceeded = contextExceeded;
    }

    /** The relay said the prompt is longer than the model's context window (a 400). */
    public boolean isContextExceeded() {
        return contextExceeded;
    }

    /** True when {@code error}, or the failure it wraps, is a relay error that says the context window was exceeded. */
    public static boolean contextExceeded(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause()) {
            if (error instanceof RelayErrorException relay) return relay.contextExceeded;
        }
        return false;
    }
}
