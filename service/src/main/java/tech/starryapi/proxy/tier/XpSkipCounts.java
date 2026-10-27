package tech.starryapi.proxy.tier;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * How often relays were left out of a plan and why, per vendor, entry, tier and reason (design 3, step 5b), since this node
 * started. A relay skipped for a request is not logged anywhere else, so this is the status page's only source for why a
 * tier that has relays was not served by them; the count of a (vendor, entry, tier, reason) is the number of relays
 * skipped, not of requests. The entry is the one the request came in through: a relay is skipped for a pair, and the
 * pairs of a vendor are promoted one by one (design 7.3), so a skip of the Responses entry is nothing of the Chat entry's.
 * The keys are bounded by the configuration: vendors, entries, tiers and the reasons.
 */
public class XpSkipCounts {

    /** @param entry the entry of the request, as {@code PairRegistry.Entry} names it */
    public record Key(String vendor, String entry, String tierId, XpSkip.Reason reason) {
    }

    private final ConcurrentMap<Key, LongAdder> counts = new ConcurrentHashMap<>();
    private final Instant since = Instant.now();

    void add(String vendor, String entry, Collection<XpSkip> skips) {
        for (XpSkip skip : skips) {
            counts.computeIfAbsent(new Key(vendor, entry, skip.tierId(), skip.reason()), ignored -> new LongAdder()).increment();
        }
    }

    /** When this node began to count: the figures are of the time since, and of this node alone. */
    public Instant since() {
        return since;
    }

    /** The counts so far; a combination that never happened is absent. */
    public Map<Key, Long> snapshot() {
        Map<Key, Long> copy = new LinkedHashMap<>();
        counts.forEach((key, count) -> copy.put(key, count.sum()));
        return Map.copyOf(copy);
    }
}
