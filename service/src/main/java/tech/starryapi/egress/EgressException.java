package tech.starryapi.egress;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;

/**
 * A local failure of an account's outbound route. It is never evidence about the upstream credential, it never
 * falls back to a direct connection, and the message is one of the fixed {@link EgressFailure} texts; the cause is
 * kept for diagnostics only because transport messages can contain proxy credentials.
 *
 * <p>The one thing callers must tell apart is whether the request may have reached the upstream. A failure before
 * anything was written (route lookup, inactive node, proxy or TLS handshake) is known unbilled and may fail over to
 * another account; after the request was written (response timeout, reset mid-request) the upstream may still have
 * generated and billed it, exactly like a timeout on a direct connection.
 */
public class EgressException extends RuntimeException {
    private final EgressFailure failure;
    private final boolean requestMayHaveBeenSent;

    public EgressException(EgressFailure failure) { this(failure, null, false); }
    private EgressException(EgressFailure failure, Throwable cause, boolean requestMayHaveBeenSent) {
        super(failure.message(), cause);
        this.failure = failure;
        this.requestMayHaveBeenSent = requestMayHaveBeenSent;
    }

    public static EgressException notSent(EgressFailure failure, Throwable cause) { return new EgressException(failure, cause, false); }
    public static EgressException maybeSent(EgressFailure failure, Throwable cause) { return new EgressException(failure, cause, true); }

    /**
     * Maps a failure of the connection to a proxy or bridge. {@code requestStarted} is whether the HTTP request had
     * begun to be written; a connection that failed earlier never carried it. A failed connect, proxy handshake or
     * TLS handshake proves the request was not written even when the transport reports it late.
     */
    static EgressException transport(Throwable cause, boolean requestStarted, EgressFailure beforeSend, EgressFailure afterSend) {
        return !requestStarted || failedBeforeRequest(cause) ? notSent(beforeSend, cause) : maybeSent(afterSend, cause);
    }

    public EgressFailure failure() { return failure; }
    public boolean requestMayHaveBeenSent() { return requestMayHaveBeenSent; }

    /** The first egress failure in a cause chain, or null. */
    public static EgressException find(Throwable error) {
        for (int depth = 0; error != null && depth < 32; depth++, error = error.getCause()) {
            if (error instanceof EgressException egress) return egress;
        }
        return null;
    }

    /** The innermost cause, but never past an egress failure: its cause is a transport detail, not the error. */
    public static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && !(current instanceof EgressException) && depth < 32; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) break;
            current = cause;
        }
        return current;
    }

    static boolean failedBeforeRequest(Throwable error) {
        for (int depth = 0; error != null && depth < 32; depth++, error = error.getCause()) {
            String name = error.getClass().getSimpleName();
            if (error instanceof ConnectException || error instanceof UnknownHostException
                || error instanceof NoRouteToHostException || error instanceof SSLHandshakeException
                || error instanceof io.netty.handler.proxy.ProxyConnectException
                || error instanceof io.netty.channel.ConnectTimeoutException
                || name.equals("SslHandshakeTimeoutException") || name.startsWith("PoolAcquire")) return true;
        }
        return false;
    }
}
