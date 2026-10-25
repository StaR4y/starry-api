package tech.starryapi.platform.upstream;

public class UpstreamException extends RuntimeException {

    private final int statusCode;
    private final boolean recoverable;
    private final int retryAfterSeconds;
    private final boolean generationStarted;

    public UpstreamException(int statusCode, String message, boolean recoverable) {
        this(statusCode, message, recoverable, null);
    }

    public UpstreamException(int statusCode, String message, boolean recoverable, String retryAfter) {
        this(statusCode, message, recoverable, retryAfter, false);
    }

    /**
     * {@code generationStarted} says the upstream had already produced output (content, tool calls or usage) before it
     * failed, so a retry elsewhere would generate the answer a second time. The status code and {@code recoverable} are
     * not affected; only tiered routing reads it.
     */
    public UpstreamException(int statusCode, String message, boolean recoverable, String retryAfter, boolean generationStarted) {
        super(message);
        this.statusCode = statusCode;
        this.recoverable = recoverable;
        this.retryAfterSeconds = parseRetryAfter(retryAfter);
        this.generationStarted = generationStarted;
    }

    private UpstreamException(UpstreamException source) {
        super(source.getMessage());
        this.statusCode = source.statusCode;
        this.recoverable = source.recoverable;
        this.retryAfterSeconds = source.retryAfterSeconds;
        this.generationStarted = true;
        setStackTrace(source.getStackTrace());
    }

    /**
     * The same failure with {@code generationStarted} set: status, recoverability, message and Retry-After are kept.
     * For a client that retries on its own, so that the error it finally throws still says an earlier attempt already
     * produced output.
     */
    public UpstreamException withGenerationStarted() {
        return generationStarted ? this : new UpstreamException(this);
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

    public boolean isGenerationStarted() {
        return generationStarted;
    }
}
