package tech.starryapi.proxy.tier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;

/**
 * The context windows this node has learned from the answers of relays (design 7.8). The window an administrator writes
 * for a mapping is a claim about a relay, and a relay that is put in front of a model with a smaller window than the
 * claim says only tells so with an error. When one refuses a prompt for its length, this node keeps the size of the
 * prompt for the (relay, mapping), and the plans of later requests leave the relay out of every request that is as large:
 * a compaction request that does not fit goes straight to the tier below, instead of meeting the same error again at
 * each turn of the session, and a short request is still the relay's.
 *
 * <p>What is kept is the smallest size a relay refused, measured as the plan measures a request against the window (the
 * estimate of the body the relay is sent, {@code RelayUsageNormalizer.estimateInputTokens}). A request of that size has
 * been refused once, so it is not sent a second time, which is also why a request that is exactly as large is left out
 * and not only a larger one. The size only goes down while the knowledge lasts: a relay's real window is somewhere
 * below the smallest prompt it refused, and every refusal narrows where. The estimate counts the input only, so a
 * refusal that came from the input and the output limit together teaches a limit that is a little low for requests that
 * ask for less output, which only costs those requests the relay.
 *
 * <p>The knowledge is that of the mapping as it was configured: a mapping whose window, wire name or verification was
 * changed since is another mapping, and begins with nothing. It is local to the node, does not outlive a restart and
 * is forgotten after {@link #TTL}, when the next request that is too large for the relay is allowed to ask it again.
 */
public class XpLearnedLimits {

    /** How long a node keeps what a relay's refusal taught it. */
    public static final Duration TTL = Duration.ofHours(6);

    private static final long MAXIMUM_SIZE = 10_000;
    private static final XpLearnedLimits DISABLED = new XpLearnedLimits(0, Ticker.systemTicker());

    /** A mapping as it was configured: the row, the name the relay is asked for, its window and what it was verified for. */
    private record Key(String vendor, String accountId, String modelId, String upstreamModel, int window, String verifiedHash) {

        static Key of(MemberModel mapping) {
            return new Key(mapping.vendor(), mapping.accountId(), mapping.modelId().toLowerCase(Locale.ROOT),
                mapping.upstreamModel(), mapping.maxContextTokens(), mapping.verifiedHash());
        }
    }

    private final Cache<Key, Long> refused;
    /** Off for the store that keeps nothing: a cache of no size still shows an entry until its eviction has run. */
    private final boolean keeps;

    public XpLearnedLimits() {
        this(Ticker.systemTicker());
    }

    public XpLearnedLimits(Ticker ticker) {
        this(MAXIMUM_SIZE, ticker);
    }

    private XpLearnedLimits(long maximumSize, Ticker ticker) {
        this.keeps = maximumSize > 0;
        this.refused = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterWrite(TTL)
            .ticker(ticker)
            .build();
    }

    /** The store of a deployment that does not run tiers: it learns nothing and says so. */
    public static XpLearnedLimits disabled() {
        return DISABLED;
    }

    /**
     * A relay refused a prompt for its length.
     *
     * @param estimatedInput the estimate of the body the relay was sent; a prompt of at least this size is not sent to the
     *     relay for this mapping again, until it is forgotten. Nothing is learned from a size below 1.
     */
    public void learn(MemberModel mapping, long estimatedInput) {
        if (!keeps || estimatedInput < 1) {
            return;
        }
        refused.asMap().merge(Key.of(mapping), estimatedInput, Math::min);
    }

    /** The smallest estimated size the relay refused for the mapping, if this node has seen it refuse one. */
    public OptionalLong refusedFrom(MemberModel mapping) {
        Long size = keeps ? refused.getIfPresent(Key.of(mapping)) : null;
        return size == null ? OptionalLong.empty() : OptionalLong.of(size);
    }

    long size() {
        refused.cleanUp();
        return refused.estimatedSize();
    }
}
