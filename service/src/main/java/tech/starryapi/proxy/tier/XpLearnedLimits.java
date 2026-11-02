package tech.starryapi.proxy.tier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * below the smallest prompt it refused, and every refusal narrows where. The estimate counts the input only. A body that
 * asks for more output than the 4,096 the plans judge a prompt beside (a relay whose mapping states a maximum output)
 * may have been refused for the output it asked for and not for the prompt, which the gateway's estimate, often
 * low, does not tell apart: it is kept as the size its prompt would be beside 4,096 ({@link
 * TierPlan.Relay#taughtByRefusal}), and the plans compare such a body in the same terms. A body with a limit the client
 * wrote is compared on its prompt alone, whatever output it asks for, so that value does not tell it from the refused
 * body: the refusal is kept as it was too, the prompt and the output that were refused ({@link #learnWide}), and a body
 * of at least that prompt and at least that output is not sent either, the client's own included, a byte-identical one
 * with the rest. One refusal of the larger body then keeps the relay from bodies that ask for as much, and the 4,096 body
 * of the same request is still the relay's.
 *
 * <p>The knowledge is that of the mapping as it was configured: a mapping whose window, wire name or verification was
 * changed since is another mapping, and begins with nothing. Its maximum output is not part of that: what is kept is in
 * the terms of the body with the 4,096 output, which the maximum does not change. It is local to the node, does not
 * outlive a restart and is forgotten after {@link #TTL}, when the next request that is too large for the relay is allowed
 * to ask it again.
 */
public class XpLearnedLimits {

    /** How long a node keeps what a relay's refusal taught it. */
    public static final Duration TTL = Duration.ofHours(6);

    private static final long MAXIMUM_SIZE = 10_000;
    /** How many refusals of a body with a large output are kept for one mapping; one is the usual number. */
    private static final int MAXIMUM_WIDE = 8;
    private static final XpLearnedLimits DISABLED = new XpLearnedLimits(0, Ticker.systemTicker());

    /** A mapping as it was configured: the row, the name the relay is asked for, its window and what it was verified for. */
    private record Key(String vendor, String accountId, String modelId, String upstreamModel, int window, String verifiedHash) {

        static Key of(MemberModel mapping) {
            return new Key(mapping.vendor(), mapping.accountId(), mapping.modelId().toLowerCase(Locale.ROOT),
                mapping.upstreamModel(), mapping.maxContextTokens(), mapping.verifiedHash());
        }
    }

    /** A body that asked for more output than the plans judge a prompt beside, and was refused for its length. */
    private record Wide(long input, long output) {

        /** Whether this refusal had no more prompt and no more output than a body of {@code otherInput} and {@code otherOutput}. */
        boolean noLargerThan(long otherInput, long otherOutput) {
            return input <= otherInput && output <= otherOutput;
        }
    }

    private final Cache<Key, Long> refused;
    private final Cache<Key, List<Wide>> refusedWide;
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
        this.refusedWide = Caffeine.newBuilder()
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
     * @param estimatedInput the estimate of the body the relay was sent, in the terms of the body with the 4,096 output (the
     *     output it asked for beyond that is added, {@link TierPlan.Relay#taughtByRefusal}); a prompt of at least this
     *     size is not sent to the relay for this mapping again, until it is forgotten. Nothing is learned from a size
     *     below 1.
     */
    public void learn(MemberModel mapping, long estimatedInput) {
        if (!keeps || estimatedInput < 1) {
            return;
        }
        refused.asMap().merge(Key.of(mapping), estimatedInput, Math::min);
    }

    /**
     * A relay refused the length of the body it was sent: what {@link #learn(MemberModel, long)} keeps of its size in the terms
     * the plans compare in, and, where the body asked for more output than the plans judge a prompt beside, the prompt and the
     * output of it as well ({@link #learnWide}).
     */
    public void learn(TierPlan.Relay relay) {
        learn(relay.mapping(), relay.taughtByRefusal());
        if (relay.outputBeyondFallback() > 0) {
            learnWide(relay.mapping(), relay.estimatedInput(), RequestBounds.FALLBACK_OUTPUT_TOKENS + relay.outputBeyondFallback());
        }
    }

    /**
     * A relay refused the length of a body that asked for {@code output} tokens of output, more than the 4,096 the plans
     * judge a prompt beside, with a prompt of {@code estimatedInput}. A body of at least that prompt and at least that output
     * is not sent to the relay for this mapping again, whoever wrote its limit, until it is forgotten; only the refusals that
     * no other body is smaller than in both are kept (a body that was refused with less of both says more), and no more than
     * a few of them, since the size the relay is taught by {@link #learn(MemberModel, long)} still holds for the rest. Nothing
     * is learned from a size or an output below 1.
     */
    public void learnWide(MemberModel mapping, long estimatedInput, long output) {
        if (!keeps || estimatedInput < 1 || output < 1) {
            return;
        }
        Wide wide = new Wide(estimatedInput, output);
        refusedWide.asMap().merge(Key.of(mapping), List.of(wide), (known, added) -> {
            if (known.stream().anyMatch(other -> other.noLargerThan(wide.input(), wide.output()))) {
                return known;
            }
            List<Wide> kept = new ArrayList<>(known.stream().filter(other -> !wide.noLargerThan(other.input(), other.output())).toList());
            if (kept.size() < MAXIMUM_WIDE) {
                kept.add(wide);
            }
            return List.copyOf(kept);
        });
    }

    /** The smallest estimated size the relay refused for the mapping, if this node has seen it refuse one. */
    public OptionalLong refusedFrom(MemberModel mapping) {
        Long size = keeps ? refused.getIfPresent(Key.of(mapping)) : null;
        return size == null ? OptionalLong.empty() : OptionalLong.of(size);
    }

    /**
     * Whether this node has seen the relay refuse, for the mapping, a body that asked for more output than the plans judge a
     * prompt beside ({@link #learnWide}) and was no larger than this one in its prompt or in its output.
     *
     * @param estimatedInput the estimate of the body that is about to be sent
     * @param output the output the body asks for
     */
    public boolean refusedAsMuch(MemberModel mapping, long estimatedInput, long output) {
        List<Wide> known = keeps ? refusedWide.getIfPresent(Key.of(mapping)) : null;
        return known != null && known.stream().anyMatch(wide -> wide.noLargerThan(estimatedInput, output));
    }

    long size() {
        refused.cleanUp();
        refusedWide.cleanUp();
        return refused.estimatedSize() + refusedWide.estimatedSize();
    }
}
