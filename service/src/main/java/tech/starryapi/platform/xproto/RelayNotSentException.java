package tech.starryapi.platform.xproto;

/**
 * The request to an OpenAI-format relay provably never left: the connection was not ready when the transport failed (no
 * route, a refused connect, a handshake the relay or a firewall reset or closed), so nothing was generated and another
 * source may be asked (design 7.5). The relay client knows this and no list of error classes can: it saw whether the
 * request was ever handed to the connection. The transport's error is the cause, for the classification of a failure that
 * cost nothing to apply to; its words can name the relay's host and address, so the caller that hands it on takes them out.
 */
public final class RelayNotSentException extends RuntimeException {

    public RelayNotSentException(Throwable cause) {
        super("The request was not sent to the source", cause);
    }
}
