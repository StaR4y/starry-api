package tech.starryapi.egress;

/** Local egress failures are not evidence that an upstream credential has expired. */
public class EgressException extends RuntimeException {
    private final boolean retryable;
    public EgressException(String message) { this(message, true); }
    public EgressException(String message, boolean retryable) { super(message); this.retryable = retryable; }
    public boolean retryable() { return retryable; }
}
