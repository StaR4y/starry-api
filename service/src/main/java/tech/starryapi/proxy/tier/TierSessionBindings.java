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
 * is (design 4.7). A session that really fell to a lower tier stays there instead of going back to
 * the better one on every request, which would move it between kinds of account whenever the better one flaps. Read
 * and write rules are in the planner and in {@link TierWalk}; this is only the store.
 *
 * <p>Like the account affinity it is local to the node and does not outlive a restart. It follows the same switch:
 * with {@code session-affinity-enabled} off nothing is read or written.
 *
 * <p>What counts as the session being used is any request of it that was served, whichever endpoint it came in through
 * and however it chose its tier: an automatic request reads and may write the binding, and one that named its tier
 * leaves the binding as it is and {@link #serve notes} that the session is in use. A session that keeps working with
 * pinned requests is not idle, so it is not sent back to the best tier by the time its next automatic request comes.
 *
 * <p>The store also counts, per session and tier, the dispatches in flight on this node. An account that refuses a
 * request because the same session still has requests running on it (a client that runs sub-agents in parallel) says
 * nothing about the tier being busy, and does not push the session down.
 *
 * <p>And it keeps the relays that broke off an answer for a session (design 7.8), for {@link #RELAY_EXCLUSION}. That is no
 * tier binding and moves no session between tiers: it is about not paying twice for an answer that may still be running,
 * so it holds whether or not the vendor keeps sessions to their tier, and the switch of the account affinity leaves it
 * alone. It only shapes the plans of that session's next requests, which leave the relay out as they leave out a relay
 * that cannot carry the request.
 *
 * <p>A chain of Responses that the client tells apart only by the response each turn continues would be a new session
 * every turn, with no tier binding and no exclusion carried from one turn to the next, and a turn of another model would
 * cut the chain's bindings in two. So the store also keeps which session each response belongs to ({@link #follow}): the
 * next turn looks the name it was given up ({@link #sessionOf}) and is the session of the first turn, with every binding
 * the session has for the models it asked for and every relay that was kept from it.
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

    /**
     * How long a relay that broke off an answer is kept from the same session: long enough for the client's own retry of
     * the 502 it was answered with (an SDK retries a few times within seconds), short enough that the relay is back for
     * the session when the interruption was a passing one.
     */
    public static final Duration RELAY_EXCLUSION = Duration.ofMinutes(2);

    private static final TierSessionBindings DISABLED =
        new TierSessionBindings(() -> false, Duration.ofMinutes(10), 0, Ticker.systemTicker());

    private final BooleanSupplier enabled;
    private final Cache<String, Binding> bindings;
    private final Cache<String, Boolean> interrupted;
    /** The session each response of a chain belongs to, by the name its next turn is given. */
    private final Cache<String, String> chains;
    /** Off for the store that keeps nothing: a cache of no size still shows an entry until its eviction has run. */
    private final boolean keepsSessions;
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
        Duration idle = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(10) : ttl;
        this.bindings = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterAccess(idle)
            .ticker(ticker)
            .build();
        this.chains = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterAccess(idle)
            .ticker(ticker)
            .build();
        this.keepsSessions = maximumSize > 0;
        this.interrupted = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterWrite(RELAY_EXCLUSION)
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

    /**
     * Records that the session was served again, which counts as using its binding: a session in use does not expire.
     * Any request of the session does this, one that named its tier too.
     */
    public void serve(String key) {
        if (enabled() && key != null) {
            bindings.getIfPresent(key);
        }
    }

    /**
     * Says that the next turn of a chain of Responses, which the client names by the response {@code next} it continues, is
     * the session {@code session} that this turn was made under. Done when a turn has been served and the response it
     * produced is known. It is no tier binding and is kept whatever the switch of the account affinity says, as the
     * exclusions are.
     *
     * @param next the name the next turn is given ({@link TierSessionKeys#ofResponse})
     * @param session the name of the session of this turn, which already is the first turn's when the chain is known
     */
    public void follow(String next, String session) {
        if (keepsSessions && next != null && session != null && !next.equals(session)) {
            chains.put(next, session);
        }
    }

    /**
     * The session a name stands for: the session of the first turn when the name is that of a turn that was followed, else
     * the name itself. Looking a name up counts as using it.
     */
    public String sessionOf(String name) {
        if (!keepsSessions || name == null) {
            return name;
        }
        String session = chains.getIfPresent(name);
        return session != null ? session : name;
    }

    /**
     * A relay broke off an answer for the session: the request was written, and the relay may be generating the answer
     * still. The session's retry of that request is not sent to the relay again for {@link #RELAY_EXCLUSION}, whatever
     * the relay's tier, so that it goes to another relay or tier and the operator does not pay for the same answer twice.
     *
     * @param sessionKey what the session is called ({@link TierPlan#relaySession}); nothing is kept for a request that
     *     has no session to tell it by
     */
    public void excludeRelay(String vendor, String sessionKey, String accountId) {
        if (keepsSessions && vendor != null && sessionKey != null && accountId != null) {
            interrupted.put(exclusionKey(vendor, sessionKey, accountId), Boolean.TRUE);
        }
    }

    /** Whether the relay broke off an answer for the session less than {@link #RELAY_EXCLUSION} ago. */
    public boolean relayExcluded(String vendor, String sessionKey, String accountId) {
        return keepsSessions && vendor != null && sessionKey != null && accountId != null
            && interrupted.getIfPresent(exclusionKey(vendor, sessionKey, accountId)) != null;
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

    /** A relay is a member of a vendor's tier, and the same account may be one in two vendors. */
    private static String exclusionKey(String vendor, String sessionKey, String accountId) {
        return vendor + '|' + accountId + '|' + sessionKey;
    }

    long size() {
        bindings.cleanUp();
        return bindings.estimatedSize();
    }
}
