package xyz.star4y.api.platform.upstream;

public class UpstreamException extends RuntimeException {

    private final int statusCode;
    private final boolean recoverable;

    public UpstreamException(int statusCode, String message, boolean recoverable) {
        super(message);
        this.statusCode = statusCode;
        this.recoverable = recoverable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
