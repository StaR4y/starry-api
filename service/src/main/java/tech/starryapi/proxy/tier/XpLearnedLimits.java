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
 * <p>That holds while the output is small beside the prompt. A request whose output limit the gateway chose (the default
 * that a Responses request which names none is given, D11 and D14) can be refused for the prompt and that limit together
 * by a relay that would take the same prompt with the limit of a Chat or Messages request, and the default is some
 * thousands of tokens more. What such a refusal teaches is kept apart, as the size of the prompt plus that limit, and is
 * applied only to requests whose limit the gateway chose; the other requests are not turned away by it, and what they
 * taught is applied to every request, since a prompt as large as one that was refused with a small output is refused
 * with any.
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

    /**
     * A mapping as it was configured: the row, the name the relay is asked for, its window and what it was verified for;
     * and whether what is kept is for requests whose output limit the gateway chose.
     */
    private record Key(String vendor, String accountId, String modelId, String upstreamModel, int window, String verifiedHash,
                       boolean gatewayOutput) {

        static Key of(MemberModel mapping, boolean gatewayOutput) {
            return new Key(mapping.vendor(), mapping.accountId(), mapping.modelId().toLowerCase(Locale.ROOT),
                mapping.upstreamModel(), mapping.maxContextTokens(), mapping.verifiedHash(), gatewayOutput);
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
        learn(mapping, estimatedInput, 0);
    }

    /**
     * A relay refused a prompt for its length when the output limit it was sent was the gateway's own.
     *
     * @param estimatedInput as {@link #learn(MemberModel, long)}
     * @param gatewayOutput the output limit the gateway chose for the request, or 0 when the client wrote its own, or the
     *     relay was given the 4,096 the request had before the gateway chose one (then this is {@link #learn(MemberModel,
     *     long)}). What is learned is the size of the prompt together with that limit, and holds for requests whose limit
     *     the gateway chose, not for the others.
     */
    public void learn(MemberModel mapping, long estimatedInput, long gatewayOutput) {
        if (!keeps || estimatedInput < 1) {
            return;
        }
        long output = Math.max(0, gatewayOutput);
        refused.asMap().merge(Key.of(mapping, output > 0), estimatedInput + output, Math::min);
    }

    /**
     * The smallest estimated size the relay refused for the mapping, if this node has seen it refuse one, whatever the
     * output limit of the request was: a request with a prompt as large is refused again. Not the size a refusal of a
     * request with the gateway's own limit taught ({@link #refusedFromWithGatewayOutput}).
     */
    public OptionalLong refusedFrom(MemberModel mapping) {
        return refused(Key.of(mapping, false));
    }

    /**
     * The smallest size of the prompt and the gateway's own output limit together that the relay refused for the mapping,
     * if this node has seen it refuse one: a request whose limit the gateway chose is not sent when its prompt and that limit
     * come to as much.
     */
    public OptionalLong refusedFromWithGatewayOutput(MemberModel mapping) {
        return refused(Key.of(mapping, true));
    }

    private OptionalLong refused(Key key) {
        Long size = keeps ? refused.getIfPresent(key) : null;
        return size == null ? OptionalLong.empty() : OptionalLong.of(size);
    }

    long size() {
        refused.cleanUp();
        return refused.estimatedSize();
    }
}
