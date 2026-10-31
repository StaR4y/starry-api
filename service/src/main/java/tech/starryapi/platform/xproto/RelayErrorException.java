package tech.starryapi.platform.xproto;

import tech.starryapi.platform.upstream.UpstreamException;

/**
 * An error an OpenAI-format relay reported (an error status, or an {@code error} member inside a successful response),
 * made into the status the gateway deals with by {@link RelayErrorMapper} (design 7.11). Nothing the relay wrote is in
 * it: the message is the fixed {@code Source error <status> (<type>)}.
 *
 * <p>It is only made when the relay said no before anything was generated (an error after the first output is a {@link
 * RelayAbortedAfterDispatchException}), so {@code generationStarted} is false. What it carries beyond the status is
 * {@link #isContextExceeded()}: a 400 that says the request does not fit the model's context window, which the
 * dispatcher answers differently from any other 400 (design 7.8: one crossing to a native tier, and the limit is
 * learned), and {@link #isBesideOutput()}: a 400 that says the prompt fits but not beside the output asked for, which is
 * read as a refusal of the length only where the gateway chose the output limit.
 */
public final class RelayErrorException extends UpstreamException {

    /**
     * What a client is told when a prompt does not fit the context window of a relay and no native source is left to try
     * (design 7.8): the words that clients such as Claude Code look for to shorten what they send. The relay's own words
     * are not passed on. It is the answer both of a relay that refused the prompt and of a plan that left the relay out for
     * the prompt's length, so that the first request and every later one are told the same.
     */
    public static final String PROMPT_TOO_LONG = "prompt is too long: the request does not fit the context window of the source";

    private final boolean contextExceeded;
    private final boolean besideOutput;

    RelayErrorException(
        int statusCode,
        String message,
        boolean recoverable,
        String retryAfter,
        boolean contextExceeded,
        boolean besideOutput
    ) {
        super(statusCode, message, recoverable, retryAfter);
        this.contextExceeded = contextExceeded;
        this.besideOutput = besideOutput;
    }

    /** The relay said the prompt is longer than the model's context window (a 400). */
    public boolean isContextExceeded() {
        return contextExceeded;
    }

    /**
     * The relay said the prompt fits the window but not beside the output the request asked it to write (a 400): Anthropic's
     * "input length and `max_tokens` exceed context limit: 188240 + 21333 > 200000", which a relay in front of it passes on.
     * It is not {@link #isContextExceeded()}: it says nothing of the prompt alone, and what a node may learn of it depends on whose
     * the output limit was, which the caller knows and the words do not ({@link #asContextExceeded()}). Left as it is, it is
     * any other 400 the relay gave to the converted request.
     */
    public boolean isBesideOutput() {
        return besideOutput;
    }

    /**
     * The same refusal read as one for length, for a request whose output limit the gateway chose: the output it was refused
     * with is a number this node knows (4,096, or the maximum output of the mapping), so the refusal says how large a prompt
     * the relay will not take beside it.
     */
    public RelayErrorException asContextExceeded() {
        int seconds = UpstreamException.retryAfterSeconds(this);
        String retryAfter = seconds > 0 ? Integer.toString(seconds) : null;
        return new RelayErrorException(getStatusCode(), getMessage(), isRecoverable(), retryAfter, true, false);
    }

    /** True when {@code error}, or the failure it wraps, is a relay error that says the context window was exceeded. */
    public static boolean contextExceeded(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause()) {
            if (error instanceof RelayErrorException relay) return relay.contextExceeded;
        }
        return false;
    }
}
