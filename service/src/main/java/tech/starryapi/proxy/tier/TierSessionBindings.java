package tech.starryapi.proxy.tier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;
import tech.starryapi.config.ProxyProperties;

/**
 * The tier each session was pushed down to, kept until the session has been idle for as long as the account affinity
 * is (design 4.7, owner decision D4). A session that really fell to a lower tier stays there instead of going back to
 * the better one on every request, which would move it between kinds of account whenever the better one flaps. Read
 * and write rules are in the planner and in {@link TierWalk}; this is only the store.
 *
 * <p>Like the account affinity it is local to the node and does not outlive a restart. It follows the same switch:
 * with {@code session-affinity-enabled} off nothing is read or written.
 *
 * <p>The store also counts, per session and tier, the dispatches in flight on this node. An account that refuses a
 * request because the same session still has requests running on it (a client that runs sub-agents in parallel) says
 * nothing about the tier being busy, and does not push the session down.
 */
public class TierSessionBindings {

    /**
     * The tier a session is bound to. The level is the tier's position when the binding was made; the tier is found by
     * {@code tierId}, so that a reordering of the tiers does not move the session. The account that served the session
     * is not kept here: the account affinity of the tier does that, and an entry that names an account would have to
     * be rewritten whenever one is removed, which counts as using every binding and keeps idle sessions from expiring.
     */
    public record Binding(String tierId, int level) {
    }

    private static final TierSessionBindings DISABLED =
        new TierSessionBindings(() -> false, Duration.ofMinutes(10), 0, Ticker.systemTicker());

    private final BooleanSupplier enabled;
    private final Cache<String, Binding> bindings;
    private final ConcurrentMap<String, Integer> inFlight = new ConcurrentHashMap<>();

    public TierSessionBindings(ProxyProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    public TierSessionBindings(ProxyProperties properties, Ticker ticker) {
        this(properties::isSessionAffinityEnabled, properties.getSessionAffinityTtl(),
            Math.max(1, properties.getSessionAffinityMaximumSize()), ticker);
    }

    private TierSessionBindings(BooleanSupplier enabled, Duration ttl, long maximumSize, Ticker ticker) {
        this.enabled = enabled;
        this.bindings = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterAccess(ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(10) : ttl)
            .ticker(ticker)
            .build();
    }

    /** The store of a deployment that does not run tiers: it keeps nothing and says so. */
    public static TierSessionBindings disabled() {
        return DISABLED;
    }

    /** Whether sessions are kept to their tier at all. Off when session affinity is off. */
    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    /** The binding of the session; looking at it counts as using it, so a session in use does not expire. */
    public Optional<Binding> find(String key) {
        return enabled() && key != null ? Optional.ofNullable(bindings.getIfPresent(key)) : Optional.empty();
    }

    /**
     * Binds the session to {@code next} if that is a tier below the one it has, or it has none. A binding only ever
     * moves down: a request that started in a better tier before the session was pushed down and finishes after it
     * leaves the binding where it is. Which of two tiers is the lower one is the caller's to say, from the order of
     * the tiers its plan was made with, since the tiers may have been reordered since the binding was made.
     *
     * @param levelOf the place of the tier the session is bound to in that order, 1 being the best
     */
    public void downgrade(String key, Binding next, ToIntFunction<Binding> levelOf) {
        if (enabled() && key != null) {
            bindings.asMap().compute(key, (ignored, current) ->
                current == null || current.tierId().equals(next.tierId()) || levelOf.applyAsInt(current) < next.level()
                    ? next
                    : current);
        }
    }

    /** Binds the session to {@code next} whatever it had: the one way up, after a request climbed back as a last resort. */
    public void climb(String key, Binding next) {
        if (enabled() && key != null) {
            bindings.put(key, next);
        }
    }

    /** Records that the session was served again, which counts as using its binding: a session in use does not expire. */
    public void serve(String key) {
        if (enabled() && key != null) {
            bindings.getIfPresent(key);
        }
    }

    /** Gives the binding of {@code fromKey}, if any, to {@code toKey} too, which is where the next turn of a response chain looks. */
    public void alias(String fromKey, String toKey) {
        if (enabled() && fromKey != null && toKey != null) {
            Binding current = bindings.getIfPresent(fromKey);
            if (current != null) {
                bindings.put(toKey, current);
            }
        }
    }

    /** A request of the session was dispatched in the tier. */
    public void dispatchStarted(String sessionKey, String tierRef) {
        if (sessionKey != null) {
            inFlight.merge(inFlightKey(sessionKey, tierRef), 1, Integer::sum);
        }
    }

    /** The request ended, in whatever way. */
    public void dispatchEnded(String sessionKey, String tierRef) {
        if (sessionKey != null) {
            inFlight.computeIfPresent(inFlightKey(sessionKey, tierRef), (ignored, count) -> count <= 1 ? null : count - 1);
        }
    }

    /** Whether the session has a request being served in the tier on this node right now. */
    public boolean hasInFlight(String sessionKey, String tierRef) {
        return sessionKey != null && inFlight.containsKey(inFlightKey(sessionKey, tierRef));
    }

    private static String inFlightKey(String sessionKey, String tierRef) {
        return tierRef + '|' + sessionKey;
    }

    long size() {
        bindings.cleanUp();
        return bindings.estimatedSize();
    }
}
