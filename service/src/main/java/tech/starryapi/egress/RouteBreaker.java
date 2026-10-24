package tech.starryapi.egress;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Skips a route whose proxy failed to connect several times in a row. A route failure is never held against the
 * account (it says nothing about the credential), so without this every request that picks an account bound to a dead
 * node waits out the connect timeout (5 to 10 seconds) before it fails over, again and again. While a route is
 * suspended its requests fail at once, before anything is sent, and move to another account; when the pause is over
 * exactly one request is let through to try the route again, and any response through it clears the state.
 */
final class RouteBreaker {
    static final int FAILURES_TO_SUSPEND = 2;
    static final Duration SUSPENSION = Duration.ofSeconds(15);

    private final int threshold;
    private final long suspensionNanos;
    private final LongSupplier clock;
    private final Cache<String, State> states = Caffeine.newBuilder()
        .maximumSize(10_000).expireAfterAccess(Duration.ofMinutes(10)).build();

    RouteBreaker(int threshold, Duration suspension, LongSupplier clock) {
        this.threshold = threshold;
        this.suspensionNanos = suspension.toNanos();
        this.clock = clock;
    }

    /** Whether a request may use the route now; after a pause the first caller is the one that tries it again. */
    boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        boolean[] allowed = {true};
        states.asMap().computeIfPresent(key, (ignored, state) -> {
            if (state.failures() < threshold) return state;
            if (state.suspendedUntil() - now > 0) {
                allowed[0] = false;
                return state;
            }
            // The others keep failing fast until this trial either clears the route or suspends it again.
            return new State(state.failures(), now + suspensionNanos);
        });
        return allowed[0];
    }

    void failed(String key) {
        long now = clock.getAsLong();
        states.asMap().compute(key, (ignored, state) -> {
            int failures = state == null ? 1 : state.failures() + 1;
            return new State(failures, failures >= threshold ? now + suspensionNanos : now);
        });
    }

    void succeeded(String key) { states.invalidate(key); }

    private record State(int failures, long suspendedUntil) {}
}
