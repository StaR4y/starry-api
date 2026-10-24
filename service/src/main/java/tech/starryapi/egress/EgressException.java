package tech.starryapi.egress;

/** Local egress failures are not evidence that an upstream credential has expired. */
public class EgressException extends RuntimeException {
    private final boolean retryable;
    private final EgressFailure failure;
    public EgressException(String message) { this(message, true); }
    public EgressException(String message, boolean retryable) { super(message); this.retryable = retryable; this.failure = null; }
    public EgressException(EgressFailure failure) { super(failure.message()); this.retryable = true; this.failure = failure; }
    public boolean retryable() { return retryable; }
    public EgressFailure failure() { return failure; }
}
