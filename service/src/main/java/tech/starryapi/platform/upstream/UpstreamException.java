package tech.starryapi.platform.upstream;

public class UpstreamException extends RuntimeException {

    private final int statusCode;
    private final boolean recoverable;
    private final int retryAfterSeconds;

    public UpstreamException(int statusCode, String message, boolean recoverable) {
        this(statusCode, message, recoverable, null);
    }

    public UpstreamException(int statusCode, String message, boolean recoverable, String retryAfter) {
        super(message);
        this.statusCode = statusCode;
        this.recoverable = recoverable;
        this.retryAfterSeconds = parseRetryAfter(retryAfter);
    }

    public static int retryAfterSeconds(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause()) {
            if (error instanceof UpstreamException upstream) return upstream.retryAfterSeconds;
        }
        return 0;
    }

    private static int parseRetryAfter(String value) {
        if (value == null || value.length() > 128) return 0;
        try {
            long seconds = value.trim().matches("[0-9]+") ? Long.parseLong(value.trim())
                : java.time.Duration.between(java.time.Instant.now(), java.time.ZonedDateTime.parse(value,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();
            return (int) Math.max(0, Math.min(seconds, 86400));
        } catch (RuntimeException ignored) { return 0; }
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
