package tech.starryapi.sourcetier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountCandidateRanker;
import tech.starryapi.account.allocation.AccountCandidateRanker.AccountLoad;
import tech.starryapi.common.ApiException;
import tech.starryapi.proxy.tier.TierDispatch;
import tech.starryapi.proxy.tier.TierMembership;
import tech.starryapi.proxy.tier.XpSkip;
import tech.starryapi.proxy.tier.XpSkipCounts;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.PairGate;
import tech.starryapi.sourcetier.SourceTierSnapshot.Placement;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * What the administrator sees of the tiers while they run (design 8): per tier, the accounts by the layer of design 4.1
 * that keeps them from serving; which tiers this node would serve from; how the last 15 minutes were served; and, for
 * the account list, the tier each account is in. For a vendor that has relays (cross-protocol sources, design 7) it
 * also shows what the relays are doing: the layer of each, the pairs with their gates, how often this node left a relay
 * out of a plan and why, and the figures of the attempts sent to them; and it exports the evidence an upgrade of a pair
 * is decided on (design 7.3). It reads this node's snapshot and account cache and the request log, and changes nothing.
 *
 * <p>The account figures are those of the node that answers, which is the node that would serve a request sent to it:
 * its configuration snapshot, its account cache (up to 30 seconds old) and the allocation tables. The distribution of
 * served requests, and the figures of the relays, are read from the request log and so cover every node; the count of
 * why relays were left out of plans is the node's own, since a skipped relay leaves nothing in the log.
 */
@Service
public class SourceTierStatusService {

    /** How far back the request log is read for how requests were served. */
    static final Duration WINDOW = Duration.ofMinutes(15);

    /** The evidence of an upgrade is read over at most this long: the request log keeps a little over three months. */
    static final int MAX_EVIDENCE_DAYS = 90;

    /** The features a pair's summary of what was left out of requests names. */
    private static final int TOP_FEATURES = 5;

    private static final String AUTO = "AUTO";
    private static final String PINNED = "PINNED";

    /** What the request log's {@code stop_reason} holds for a relay's failure of a known kind (the names of {@code UpstreamFailureClassifier.RelayFailure}). */
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String CONVERSION_REJECTED = "CONVERSION_REJECTED";
    public static final String CONTEXT_EXCEEDED = "CONTEXT_EXCEEDED";
    public static final String MODEL_NOT_SERVED = "MODEL_NOT_SERVED";
    public static final String KEY_REFUSED = "KEY_REFUSED";
    public static final String WITHHELD = "WITHHELD";

    /** The first layer that keeps a relay from serving, as the status names it. */
    static final String LAYER_DISABLED = "disabled";
    static final String LAYER_UNOPENED = "unopened";
    static final String LAYER_UNAVAILABLE = "unavailable";
    static final String LAYER_RESTING = "resting";
    static final String LAYER_BUSY = "busy";
    static final String LAYER_READY = "ready";

    private static final SourceTierDtos.XpFigures NO_FIGURES = new SourceTierDtos.XpFigures(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);

    private final SourceTierRegistry registry;
    private final SourceTierRuntimeInfo runtime;
    private final SourceTierValidator validator;
    private final AccountPoolService accountPool;
    private final AccountCandidateRanker ranker;
    private final XpSkipCounts skipCounts;
    private final Reads reads;
    private final Clock clock;

    @Autowired
    public SourceTierStatusService(
        SourceTierRegistry registry,
        SourceTierRuntimeInfo runtime,
        SourceTierValidator validator,
        AccountPoolService accountPool,
        AccountCandidateRanker ranker,
        DatabaseClient db,
        XpSkipCounts skipCounts
    ) {
        this(registry, runtime, validator, accountPool, ranker, new DatabaseReads(db), Clock.systemUTC(), skipCounts);
    }

    /** For tests and callers that have no plans to count the skips of. */
    public SourceTierStatusService(
        SourceTierRegistry registry,
        SourceTierRuntimeInfo runtime,
        SourceTierValidator validator,
        AccountPoolService accountPool,
        AccountCandidateRanker ranker,
        DatabaseClient db
    ) {
        this(registry, runtime, validator, accountPool, ranker, db, new XpSkipCounts());
    }

    SourceTierStatusService(
        SourceTierRegistry registry,
        SourceTierRuntimeInfo runtime,
        SourceTierValidator validator,
        AccountPoolService accountPool,
        AccountCandidateRanker ranker,
        Reads reads,
        Clock clock
    ) {
        this(registry, runtime, validator, accountPool, ranker, reads, clock, new XpSkipCounts());
    }

    SourceTierStatusService(
        SourceTierRegistry registry,
        SourceTierRuntimeInfo runtime,
        SourceTierValidator validator,
        AccountPoolService accountPool,
        AccountCandidateRanker ranker,
        Reads reads,
        Clock clock,
        XpSkipCounts skipCounts
    ) {
        this.registry = registry;
        this.runtime = runtime;
        this.validator = validator;
        this.accountPool = accountPool;
        this.ranker = ranker;
        this.skipCounts = skipCounts;
        this.reads = reads;
        this.clock = clock;
    }

    // ---- status ----

    /** 503 TIER_CONFIG_UNAVAILABLE before the configuration has ever loaded: nothing is known to report. */
    public Mono<SourceTierDtos.Status> status() {
        return Mono.defer(() -> {
            SourceTierSnapshot snapshot = registry.snapshot();
            Instant now = clock.instant();
            Map<String, String> vendors = tieredVendors(snapshot);
            if (vendors.isEmpty()) {
                return Mono.just(new SourceTierDtos.Status(now, snapshot.configVersion(), minutes(), List.of()));
            }
            List<ProxyAccountEntity> enabled = accountPool.cachedAccounts();
            // The allocation tables are asked about the accounts that sit in a tier, not about every account there is.
            Map<String, Predicate<ProxyAccountEntity>> reachable = new LinkedHashMap<>();
            vendors.keySet().forEach(vendor -> reachable.put(vendor, SourceTierSnapshot.routedFor(vendor)));
            Set<String> relayIds = relayAccountIds(snapshot, vendors.keySet());
            List<ProxyAccountEntity> inTiers = enabled.stream()
                .filter(account -> relayIds.contains(account.getAccountId()) || reachable.entrySet().stream()
                    .anyMatch(vendor -> tierOf(snapshot, vendor.getKey(), vendor.getValue(), account).isPresent()))
                .toList();
            Instant since = now.minus(WINDOW);
            // What the relays did is read only for a deployment that has relays: without them there is nothing to count.
            Mono<RelayReads> relayReads = relayIds.isEmpty() ? Mono.just(RelayReads.NONE) : readRelays(since);
            return Mono.zip(reads.disabledAccounts(), ranker.loads(inTiers), reads.served(since), relayReads)
                .map(read -> new SourceTierDtos.Status(
                    now,
                    snapshot.configVersion(),
                    minutes(),
                    vendors.entrySet().stream()
                        .map(vendor -> vendorStatus(vendor.getKey(), vendor.getValue(), snapshot, enabled, read.getT1(),
                            read.getT2(), read.getT3(), read.getT4()))
                        .toList()
                ));
        });
    }

    private static int minutes() {
        return (int) WINDOW.toMinutes();
    }

    private SourceTierDtos.VendorStatus vendorStatus(
        String vendor,
        String label,
        SourceTierSnapshot snapshot,
        List<ProxyAccountEntity> enabled,
        List<ProxyAccountEntity> disabled,
        Map<String, AccountLoad> loads,
        List<Served> served,
        RelayReads relayReads
    ) {
        VendorConfig config = snapshot.vendor(vendor).orElseThrow();
        List<Tier> tiers = snapshot.tiers(vendor);
        boolean serving = config.enabled() && runtime.nativeTiers() && tiers.stream().anyMatch(Tier::enabled);
        Predicate<ProxyAccountEntity> reachable = SourceTierSnapshot.routedFor(vendor);

        Map<String, Layers> layers = new HashMap<>();
        for (ProxyAccountEntity account : disabled) {
            tierOf(snapshot, vendor, reachable, account).ifPresent(tier -> layers.computeIfAbsent(tier.tierId(), id -> new Layers()).disabled++);
        }
        for (ProxyAccountEntity account : enabled) {
            tierOf(snapshot, vendor, reachable, account).ifPresent(tier -> {
                Layers held = layers.computeIfAbsent(tier.tierId(), id -> new Layers());
                AccountLoad load = loads.get(account.getAccountId());
                if (!accountPool.isUsable(account)) {
                    held.unavailable++;
                } else if (load != null && !load.schedulable()) {
                    held.resting++;
                } else if (load != null && load.saturated()) {
                    held.busy++;
                } else {
                    held.ready++;
                }
            });
        }

        // A relay is counted in its tier like any account, with the one layer that only it has.
        Map<String, ProxyAccountEntity> byId = new HashMap<>();
        enabled.forEach(account -> byId.put(account.getAccountId(), account));
        Set<String> switchedOff = new HashSet<>();
        disabled.forEach(account -> {
            byId.putIfAbsent(account.getAccountId(), account);
            switchedOff.add(account.getAccountId());
        });
        List<SourceTierDtos.XpRelay> relayViews = new ArrayList<>();
        for (Member relay : relays(snapshot, vendor)) {
            ProxyAccountEntity account = byId.get(relay.accountId());
            if (account == null) {
                continue;
            }
            String layer = relayLayer(snapshot, vendor, relay, account, switchedOff.contains(relay.accountId()), loads.get(relay.accountId()));
            layers.computeIfAbsent(relay.tierId(), id -> new Layers()).countRelay(layer);
            List<SourceTierSnapshot.MemberModel> mappings = snapshot.models(vendor, relay.accountId()).stream()
                .filter(SourceTierSnapshot.MemberModel::enabled).toList();
            relayViews.add(new SourceTierDtos.XpRelay(
                relay.tierId(),
                relay.accountId(),
                layer,
                mappings.size(),
                (int) mappings.stream().filter(model -> SourceTierValidator.isVerified(relay, model, account)).count(),
                relayReads.relay(vendor + ":" + relay.tierId(), relay.accountId())
            ));
        }

        Map<String, Counts> counts = new HashMap<>();
        String best = tiers.stream().filter(Tier::enabled).map(Tier::tierId).findFirst().orElse(null);
        long autoServed = 0;
        long degraded = 0;
        for (Served row : served) {
            Tier tier = tiers.stream().filter(candidate -> row.tierRef().equals(vendor + ":" + candidate.tierId())).findFirst().orElse(null);
            if (tier == null) {
                continue;
            }
            Counts count = counts.computeIfAbsent(tier.tierId(), id -> new Counts());
            if (!row.success()) {
                count.failed += row.attempts();
                continue;
            }
            count.served += row.attempts();
            if (PINNED.equals(row.selection())) {
                count.pinned += row.attempts();
            } else if (AUTO.equals(row.selection())) {
                autoServed += row.attempts();
                if (!tier.tierId().equals(best)) {
                    degraded += row.attempts();
                }
            }
        }

        List<SourceTierDtos.TierStatus> tierViews = new ArrayList<>();
        for (Tier tier : tiers) {
            Layers held = layers.getOrDefault(tier.tierId(), new Layers());
            Counts count = counts.getOrDefault(tier.tierId(), new Counts());
            tierViews.add(new SourceTierDtos.TierStatus(
                tier.tierId(),
                tier.level(),
                tier.name(),
                tier.enabled(),
                serving && tier.enabled() && held.ready > 0,
                new SourceTierDtos.TierAccounts(held.disabled, held.unavailable, held.resting, held.busy, held.ready,
                    held.relays ? held.unopened : null),
                count.served,
                count.pinned,
                count.failed
            ));
        }
        SourceTierDtos.XpStatus xp = relayViews.isEmpty() ? null : xpStatus(vendor, snapshot, relayViews, relayReads);
        return new SourceTierDtos.VendorStatus(vendor, label, config.enabled(), serving, autoServed, degraded, tierViews, xp);
    }

    /** The tier a native account is in for the vendor; empty for an account the vendor's models cannot reach. */
    private static Optional<Tier> tierOf(
        SourceTierSnapshot snapshot,
        String vendor,
        Predicate<ProxyAccountEntity> reachable,
        ProxyAccountEntity account
    ) {
        return reachable.test(account) ? TierMembership.nativeTierOf(snapshot, vendor, account) : Optional.empty();
    }

    private static final class Layers {
        int disabled;
        int unavailable;
        int resting;
        int busy;
        int ready;
        /** The relays that cannot be given a request for lack of a gate or a verified mapping; only a relay can be. */
        int unopened;
        /** Whether the tier holds a relay, which is what makes the figure above worth showing. */
        boolean relays;

        void countRelay(String layer) {
            relays = true;
            switch (layer) {
                case LAYER_DISABLED -> disabled++;
                case LAYER_UNOPENED -> unopened++;
                case LAYER_UNAVAILABLE -> unavailable++;
                case LAYER_RESTING -> resting++;
                case LAYER_BUSY -> busy++;
                default -> ready++;
            }
        }
    }

    private static final class Counts {
        long served;
        long pinned;
        long failed;
    }

    /** One group of the request log: the attempts of a tier, how it was chosen, and whether they succeeded. */
    record Served(String tierRef, String selection, boolean success, long attempts) {
    }

    // ---- relays ----

    /** The relays registered in the vendor's tiers, in the order of their accounts. */
    private static List<Member> relays(SourceTierSnapshot snapshot, String vendor) {
        return snapshot.members(vendor).stream().filter(Member::crossProtocol)
            .sorted(Comparator.comparing(Member::accountId)).toList();
    }

    private static Set<String> relayAccountIds(SourceTierSnapshot snapshot, Set<String> vendors) {
        Set<String> ids = new HashSet<>();
        vendors.forEach(vendor -> relays(snapshot, vendor).forEach(relay -> ids.add(relay.accountId())));
        return ids;
    }

    /**
     * The first layer that keeps a relay from serving. A relay that is not opened (no gate lets requests through for a pair
     * this release offers, or no mapping that is switched on is verified) is "unopened" before anything about the account
     * is looked at: that one only changes when the administrator acts, and it is what they have to be told first.
     */
    private String relayLayer(
        SourceTierSnapshot snapshot,
        String vendor,
        Member relay,
        ProxyAccountEntity account,
        boolean switchedOff,
        AccountLoad load
    ) {
        if (switchedOff) {
            return LAYER_DISABLED;
        }
        if (!relayCanServe(snapshot, vendor, relay, account)) {
            return LAYER_UNOPENED;
        }
        if (!accountPool.isUsable(account)) {
            return LAYER_UNAVAILABLE;
        }
        if (load != null && !load.schedulable()) {
            return LAYER_RESTING;
        }
        return load != null && load.saturated() ? LAYER_BUSY : LAYER_READY;
    }

    /**
     * The relays' part of a vendor's status: the pairs this release offers or the administrator has opened, with their
     * gates and the figures and degraded features of the window, and why this node left relays out of plans.
     */
    private SourceTierDtos.XpStatus xpStatus(
        String vendor,
        SourceTierSnapshot snapshot,
        List<SourceTierDtos.XpRelay> relays,
        RelayReads relayReads
    ) {
        List<SourceTierDtos.XpPair> pairs = new ArrayList<>();
        for (SourceTierRuntimeInfo.Pair pair : runtime.pairs()) {
            if (!pair.vendor().equals(vendor)) {
                continue;
            }
            SourceTierRuntimeInfo.PairStatus status = runtime.pairStatus(vendor, pair.sourceProtocol(), pair.entryEndpoint());
            PairGate gate = gate(snapshot, vendor, pair.sourceProtocol(), pair.entryEndpoint());
            SourceTierDtos.XpFigures figures = relayReads.pair(vendor, pair.entryEndpoint());
            // A pair that is not offered and not open is not worth a row, unless relays were somehow sent to it.
            if (status == SourceTierRuntimeInfo.PairStatus.NOT_OFFERED && gate == null && figures.attempts() == 0) {
                continue;
            }
            pairs.add(new SourceTierDtos.XpPair(
                pair.sourceProtocol(),
                pair.entryEndpoint(),
                status.name(),
                gate == null ? "OFF" : gate.state(),
                gate == null ? 0 : gate.allowKeyIds().size(),
                figures,
                relayReads.features(vendor, pair.entryEndpoint())
            ));
        }
        return new SourceTierDtos.XpStatus(skipCounts.since(), relays, pairs, skips(vendor));
    }

    private static PairGate gate(SourceTierSnapshot snapshot, String vendor, String sourceProtocol, String entryEndpoint) {
        return snapshot.gates(vendor).stream()
            .filter(gate -> gate.sourceProtocol().equals(sourceProtocol) && gate.entryEndpoint().equals(entryEndpoint))
            .findFirst().orElse(null);
    }

    /**
     * How often this node has left a relay of the vendor out of a plan, by tier and reason, in the order the reasons are met,
     * over every entry the vendor is asked through (the evidence of a pair is the one that counts one entry's lint skips).
     */
    private List<SourceTierDtos.XpSkipCount> skips(String vendor) {
        Map<TierReason, Long> merged = new TreeMap<>(Comparator.comparing(TierReason::tierId).thenComparing(TierReason::reason));
        skipCounts.snapshot().forEach((key, count) -> {
            if (key.vendor().equals(vendor) && count > 0) {
                merged.merge(new TierReason(key.tierId(), key.reason()), count, Long::sum);
            }
        });
        return merged.entrySet().stream()
            .map(entry -> new SourceTierDtos.XpSkipCount(entry.getKey().tierId(), entry.getKey().reason().name(), entry.getValue()))
            .toList();
    }

    private record TierReason(String tierId, XpSkip.Reason reason) {
    }

    /** What the log says of the relays in a window. */
    private Mono<RelayReads> readRelays(Instant since) {
        return Mono.zip(reads.relayFigures(since), reads.pairFigures(since), reads.relayFeatures(since))
            .map(read -> new RelayReads(read.getT1(), read.getT2(), read.getT3()));
    }

    /**
     * The figures of the window, found by what they are about: the relays (a tier and an account), the pairs (a vendor and
     * an entry), and the features that answered attempts left out.
     */
    private static final class RelayReads {

        static final RelayReads NONE = new RelayReads(List.of(), List.of(), List.of());

        private final Map<String, SourceTierDtos.XpFigures> relays = new HashMap<>();
        private final Map<String, SourceTierDtos.XpFigures> pairs = new HashMap<>();
        private final Map<String, List<SourceTierDtos.FeatureCount>> features = new HashMap<>();

        RelayReads(List<FigureRow> relays, List<FigureRow> pairs, List<FeatureRow> features) {
            relays.forEach(row -> this.relays.put(row.tierRef() + "|" + row.accountId(), row.figures()));
            pairs.forEach(row -> this.pairs.put(row.vendor() + "|" + row.entry(), row.figures()));
            features.stream()
                .collect(Collectors.groupingBy(row -> row.vendor() + "|" + row.entry(), LinkedHashMap::new, Collectors.toList()))
                .forEach((key, rows) -> this.features.put(key, rows.stream()
                    .sorted(Comparator.comparingLong(FeatureRow::count).reversed().thenComparing(FeatureRow::feature))
                    .limit(TOP_FEATURES)
                    .map(row -> new SourceTierDtos.FeatureCount(row.feature(), row.count()))
                    .toList()));
        }

        SourceTierDtos.XpFigures relay(String tierRef, String accountId) {
            return relays.getOrDefault(tierRef + "|" + accountId, NO_FIGURES);
        }

        SourceTierDtos.XpFigures pair(String vendor, String entry) {
            return pairs.getOrDefault(vendor + "|" + entry, NO_FIGURES);
        }

        List<SourceTierDtos.FeatureCount> features(String vendor, String entry) {
            return features.getOrDefault(vendor + "|" + entry, List.of());
        }
    }

    // ---- evidence ----

    /**
     * The evidence an upgrade of a pair is decided on (design 7.3): for every pair this release offers, the attempts sent
     * to relays over the last {@code days} days, held to {@link XpPairStandard}. 400 {@code SOURCE_EVIDENCE_WINDOW_INVALID}
     * for a window the request log cannot answer for; 503 TIER_CONFIG_UNAVAILABLE before the configuration has loaded.
     */
    public Mono<SourceTierDtos.Evidence> evidence(int days) {
        return Mono.defer(() -> {
            if (days < 1 || days > MAX_EVIDENCE_DAYS) {
                return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "SOURCE_EVIDENCE_WINDOW_INVALID",
                    "The evidence covers 1 to " + MAX_EVIDENCE_DAYS + " days of the request log"));
            }
            SourceTierSnapshot snapshot = registry.snapshot();
            Instant now = clock.instant();
            Instant since = now.minus(Duration.ofDays(days));
            Map<String, String> labels = validator.catalogVendors();
            return Mono.zip(readRelays(since), reads.memberFigures(since))
                .map(read -> {
                    RelayReads relayReads = read.getT1();
                    List<SourceTierDtos.PairEvidence> pairs = new ArrayList<>();
                    for (SourceTierRuntimeInfo.Pair pair : runtime.pairs()) {
                        SourceTierRuntimeInfo.PairStatus status = runtime.pairStatus(pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint());
                        if (status == SourceTierRuntimeInfo.PairStatus.NOT_OFFERED) {
                            continue;
                        }
                        PairGate gate = gate(snapshot, pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint());
                        SourceTierDtos.XpFigures figures = relayReads.pair(pair.vendor(), pair.entryEndpoint());
                        long lint = lintFailures(pair.vendor(), pair.entryEndpoint());
                        // This node counted the failures from the day it started, which may be later than the window begins.
                        List<SourceTierDtos.Criterion> criteria = XpPairStandard.evaluate(figures, lint, !skipCounts.since().isAfter(since));
                        pairs.add(new SourceTierDtos.PairEvidence(
                            pair.vendor(),
                            labels.getOrDefault(pair.vendor(), pair.vendor()),
                            pair.sourceProtocol(),
                            pair.entryEndpoint(),
                            status.name(),
                            gate == null ? "OFF" : gate.state(),
                            gate == null ? 0 : gate.allowKeyIds().size(),
                            figures,
                            lint,
                            criteria,
                            XpPairStandard.met(criteria),
                            relayReads.features(pair.vendor(), pair.entryEndpoint()),
                            read.getT2().stream()
                                .filter(row -> row.vendor().equals(pair.vendor()) && row.entry().equals(pair.entryEndpoint()))
                                .sorted(Comparator.comparing(FigureRow::tierRef).thenComparing(FigureRow::accountId))
                                .map(row -> new SourceTierDtos.MemberEvidence(tierId(row.tierRef()), row.accountId(), row.figures()))
                                .toList()
                        ));
                    }
                    return new SourceTierDtos.Evidence(now, since, days, snapshot.configVersion(), skipCounts.since(), pairs);
                });
        });
    }

    /**
     * The relays of the vendor that this node left out of plans of the entry for a body that failed the strict lint (or a
     * conversion that failed): a pair's own, since the pairs of a vendor are promoted one by one and the converter of one
     * entry is not the converter of another.
     */
    private long lintFailures(String vendor, String entryEndpoint) {
        return skipCounts.snapshot().entrySet().stream()
            .filter(entry -> entry.getKey().vendor().equals(vendor) && entry.getKey().entry().equals(entryEndpoint)
                && entry.getKey().reason() == XpSkip.Reason.CONVERSION)
            .mapToLong(Map.Entry::getValue)
            .sum();
    }

    private static String tierId(String tierRef) {
        int colon = tierRef.indexOf(':');
        return colon < 0 ? tierRef : tierRef.substring(colon + 1);
    }

    // ---- reads ----

    /** One group of the attempts sent to relays; which fields are set depends on what they were grouped by. */
    record FigureRow(String tierRef, String vendor, String accountId, String entry, SourceTierDtos.XpFigures figures) {
    }

    /** How many answered attempts of a vendor's entry left a feature of the request out. */
    record FeatureRow(String vendor, String entry, String feature, long count) {
    }

    /** What the status reads from the database. */
    interface Reads {

        /** The accounts that are switched off: not in the account cache, so only the table knows them. */
        Mono<List<ProxyAccountEntity>> disabledAccounts();

        /** Every account, in the order of their ids. */
        Mono<List<ProxyAccountEntity>> accounts();

        /**
         * The attempts since {@code since} that carry a tier. Every attempt of a request served from tiers writes its
         * own row (design 5.7), so a request that failed on a better tier and was served by the next one is a failure of
         * the first and a service of the second.
         */
        Mono<List<Served>> served(Instant since);

        /** The attempts since {@code since} that were sent to a relay, by tier and account (the entry is not set). */
        Mono<List<FigureRow>> relayFigures(Instant since);

        /**
         * The same by vendor and entry. The 95th percentile of a group cannot be put together from the percentiles of its
         * parts, so each grouping is its own read.
         */
        Mono<List<FigureRow>> pairFigures(Instant since);

        /** The same by tier, account and entry, for the evidence. */
        Mono<List<FigureRow>> memberFigures(Instant since);

        /** The features that answered attempts since {@code since} left out of the request, by vendor and entry. */
        Mono<List<FeatureRow>> relayFeatures(Instant since);
    }

    private record DatabaseReads(DatabaseClient db) implements Reads {

        /** The entry of the client's path (the log keeps the path, and the entry is what a gate is about). */
        private static final String ENTRY = """
            CASE
                WHEN path = '/v1/chat/completions' THEN 'CHAT'
                WHEN path = '/v1/messages' THEN 'MESSAGES'
                WHEN path = '/v1/responses' THEN 'RESPONSES'
                WHEN path LIKE '/v1beta/models/%' THEN 'GEMINI'
                ELSE 'OTHER'
            END""";

        private static final String VENDOR = "split_part(source_tier_ref, ':', 1)";

        private static final String FIGURES = """
            count(*) AS attempts,
            count(*) FILTER (WHERE success) AS served,
            count(*) FILTER (WHERE NOT success) AS failed,
            count(*) FILTER (WHERE NOT success AND stop_reason = '%s') AS interrupted,
            count(*) FILTER (WHERE NOT success AND stop_reason = '%s') AS conversion_rejected,
            count(*) FILTER (WHERE NOT success AND stop_reason = '%s') AS context_exceeded,
            count(*) FILTER (WHERE NOT success AND stop_reason = '%s') AS model_not_served,
            count(*) FILTER (WHERE NOT success AND stop_reason = '%s') AS key_refused,
            count(*) FILTER (WHERE NOT success AND stop_reason = '%s') AS withheld,
            COALESCE(sum(withheld_input_tokens) FILTER (WHERE NOT success AND stop_reason = '%s'), 0) AS withheld_input_tokens,
            COALESCE(sum(withheld_output_tokens) FILTER (WHERE NOT success AND stop_reason = '%s'), 0) AS withheld_output_tokens,
            count(*) FILTER (WHERE success AND usage_source = 'ESTIMATED') AS estimated,
            count(*) FILTER (WHERE success AND usage_source = 'CLAMPED') AS clamped,
            count(*) FILTER (WHERE success AND degraded_features IS NOT NULL AND degraded_features <> '') AS degraded,
            percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE success) AS p95""".formatted(
            INTERRUPTED, CONVERSION_REJECTED, CONTEXT_EXCEEDED, MODEL_NOT_SERVED, KEY_REFUSED, WITHHELD, WITHHELD, WITHHELD);

        private static final String RELAY_ATTEMPTS = """
            FROM request_logs
            WHERE created_at >= :since AND source_dispatch = :dispatch AND source_tier_ref IS NOT NULL""";

        @Override
        public Mono<List<ProxyAccountEntity>> disabledAccounts() {
            return db.sql(SourceTierAccounts.SELECT + " WHERE enabled = FALSE")
                .map((row, metadata) -> SourceTierAccounts.read(row))
                .all()
                .collectList();
        }

        @Override
        public Mono<List<ProxyAccountEntity>> accounts() {
            return db.sql(SourceTierAccounts.SELECT + " ORDER BY account_id")
                .map((row, metadata) -> SourceTierAccounts.read(row))
                .all()
                .collectList();
        }

        @Override
        public Mono<List<Served>> served(Instant since) {
            return db.sql("""
                    SELECT source_tier_ref, tier_selection, success, count(*) AS attempts
                    FROM request_logs
                    WHERE created_at >= :since AND source_tier_ref IS NOT NULL
                    GROUP BY source_tier_ref, tier_selection, success
                    """)
                .bind("since", since)
                .map((row, metadata) -> new Served(
                    row.get("source_tier_ref", String.class),
                    row.get("tier_selection", String.class),
                    Boolean.TRUE.equals(row.get("success", Boolean.class)),
                    row.get("attempts", Number.class).longValue()
                ))
                .all()
                .collectList();
        }

        /** What the figures are grouped by, as the columns that name the group and the grouping itself. */
        private enum Grouping {
            RELAY("source_tier_ref, account_id", "1, 2"),
            PAIR(VENDOR + " AS vendor, " + ENTRY + " AS entry", "1, 2"),
            RELAY_AND_ENTRY("source_tier_ref, account_id, " + ENTRY + " AS entry", "1, 2, 3");

            private final String columns;
            private final String groupBy;

            Grouping(String columns, String groupBy) {
                this.columns = columns;
                this.groupBy = groupBy;
            }
        }

        @Override
        public Mono<List<FigureRow>> relayFigures(Instant since) {
            return figures(since, Grouping.RELAY);
        }

        @Override
        public Mono<List<FigureRow>> pairFigures(Instant since) {
            return figures(since, Grouping.PAIR);
        }

        @Override
        public Mono<List<FigureRow>> memberFigures(Instant since) {
            return figures(since, Grouping.RELAY_AND_ENTRY);
        }

        private Mono<List<FigureRow>> figures(Instant since, Grouping grouping) {
            return db.sql("SELECT " + grouping.columns + ", " + FIGURES + "\n" + RELAY_ATTEMPTS + "\nGROUP BY " + grouping.groupBy)
                .bind("since", since)
                .bind("dispatch", TierDispatch.Kind.OPENAI_CHAT.name())
                .map((row, metadata) -> {
                    String tierRef = grouping == Grouping.PAIR ? null : row.get("source_tier_ref", String.class);
                    return new FigureRow(
                        tierRef,
                        grouping == Grouping.PAIR ? row.get("vendor", String.class) : vendorOf(tierRef),
                        grouping == Grouping.PAIR ? null : row.get("account_id", String.class),
                        grouping == Grouping.RELAY ? null : row.get("entry", String.class),
                        figures(row)
                    );
                })
                .all()
                .collectList();
        }

        private static SourceTierDtos.XpFigures figures(io.r2dbc.spi.Readable row) {
            Number p95 = row.get("p95", Number.class);
            return new SourceTierDtos.XpFigures(
                count(row.get("attempts", Number.class)),
                count(row.get("served", Number.class)),
                count(row.get("failed", Number.class)),
                count(row.get("interrupted", Number.class)),
                count(row.get("conversion_rejected", Number.class)),
                count(row.get("context_exceeded", Number.class)),
                count(row.get("model_not_served", Number.class)),
                count(row.get("key_refused", Number.class)),
                count(row.get("withheld", Number.class)),
                count(row.get("withheld_input_tokens", Number.class)),
                count(row.get("withheld_output_tokens", Number.class)),
                count(row.get("estimated", Number.class)),
                count(row.get("clamped", Number.class)),
                count(row.get("degraded", Number.class)),
                p95 == null ? null : Long.valueOf(Math.round(p95.doubleValue()))
            );
        }

        @Override
        public Mono<List<FeatureRow>> relayFeatures(Instant since) {
            return db.sql("SELECT " + VENDOR + " AS vendor, " + ENTRY + " AS entry, f.feature AS feature, count(*) AS attempts\n"
                    + "FROM request_logs CROSS JOIN LATERAL unnest(string_to_array(degraded_features, ',')) AS f(feature)\n"
                    + "WHERE created_at >= :since AND source_dispatch = :dispatch AND source_tier_ref IS NOT NULL AND success\n"
                    + "AND degraded_features IS NOT NULL AND degraded_features <> '' AND f.feature <> ''\n"
                    + "GROUP BY 1, 2, 3")
                .bind("since", since)
                .bind("dispatch", TierDispatch.Kind.OPENAI_CHAT.name())
                .map((row, metadata) -> new FeatureRow(
                    row.get("vendor", String.class),
                    row.get("entry", String.class),
                    row.get("feature", String.class),
                    count(row.get("attempts", Number.class))
                ))
                .all()
                .collectList();
        }

        private static long count(Number value) {
            return value == null ? 0 : value.longValue();
        }

        private static String vendorOf(String tierRef) {
            int colon = tierRef == null ? -1 : tierRef.indexOf(':');
            return colon < 0 ? tierRef : tierRef.substring(0, colon);
        }
    }

    // ---- placements ----

    /** The tier of every account in every vendor that has tiers; accounts that are in none are left out. */
    public Mono<SourceTierDtos.Placements> placements() {
        return Mono.defer(() -> {
            SourceTierSnapshot snapshot = registry.snapshot();
            Map<String, String> vendors = tieredVendors(snapshot);
            if (vendors.isEmpty()) {
                return Mono.just(new SourceTierDtos.Placements(snapshot.configVersion(), List.of()));
            }
            return reads.accounts()
                .map(accounts -> new SourceTierDtos.Placements(snapshot.configVersion(), placements(snapshot, vendors, accounts)));
        });
    }

    private List<SourceTierDtos.AccountPlacement> placements(
        SourceTierSnapshot snapshot,
        Map<String, String> vendors,
        List<ProxyAccountEntity> accounts
    ) {
        Map<String, Predicate<ProxyAccountEntity>> reachable = new LinkedHashMap<>();
        vendors.keySet().forEach(vendor -> reachable.put(vendor, SourceTierSnapshot.routedFor(vendor)));
        List<SourceTierDtos.AccountPlacement> placed = new ArrayList<>();
        for (ProxyAccountEntity account : accounts) {
            List<SourceTierDtos.Placed> tiers = new ArrayList<>();
            reachable.forEach((vendor, reaches) -> {
                Member member = snapshot.member(vendor, account.getAccountId()).orElse(null);
                boolean relay = member != null && member.crossProtocol();
                // A native account that the vendor's models cannot reach serves none of its requests, whatever row it has.
                if (!relay && !reaches.test(account)) {
                    return;
                }
                // An exclusive relay of another vendor serves none of this vendor's requests either (design 7.5).
                if (!relay && snapshot.isExclusiveRelay(account.getAccountId())) {
                    return;
                }
                Placement placement = snapshot.placementOf(vendor, account).orElse(null);
                if (placement == null) {
                    return;
                }
                VendorConfig config = snapshot.vendor(vendor).orElseThrow();
                tiers.add(new SourceTierDtos.Placed(
                    vendor,
                    vendors.get(vendor),
                    placement.tier().tierId(),
                    placement.tier().level(),
                    placement.tier().name(),
                    placement.rule().name(),
                    relay,
                    config.enabled() && runtime.nativeTiers() && placement.tier().enabled()
                        && (!relay || relayCanServe(snapshot, vendor, member, account))
                ));
            });
            if (!tiers.isEmpty()) {
                placed.add(new SourceTierDtos.AccountPlacement(account.getAccountId(), tiers));
            }
        }
        return placed;
    }

    /**
     * Whether a relay is asked for anything now: an entry of the vendor has a gate that is not off for a pair this release
     * offers, and one of its mappings that is switched on is verified for what the relay and the account are now
     * (the planner's conditions 1 and 3, design 3 step 5b). Until then the relay sits in its tier and serves nothing,
     * and an exclusive one is kept out of the pools of untiered traffic as well.
     */
    private boolean relayCanServe(SourceTierSnapshot snapshot, String vendor, Member relay, ProxyAccountEntity account) {
        boolean opened = snapshot.gates(vendor).stream().anyMatch(gate -> !"OFF".equals(gate.state())
            && runtime.pairStatus(vendor, gate.sourceProtocol(), gate.entryEndpoint()) != SourceTierRuntimeInfo.PairStatus.NOT_OFFERED);
        return opened && snapshot.models(vendor, relay.accountId()).stream()
            .anyMatch(model -> model.enabled() && SourceTierValidator.isVerified(relay, model, account));
    }

    // ---- vendors ----

    /**
     * The vendors that have at least one tier, with their display names, in the order of the catalog (a vendor that left
     * the catalog after it was configured follows by id).
     */
    private Map<String, String> tieredVendors(SourceTierSnapshot snapshot) {
        Map<String, String> catalog = validator.catalogVendors();
        List<String> order = new ArrayList<>(catalog.keySet());
        new TreeSet<>(snapshot.vendorIds()).stream().filter(vendor -> !catalog.containsKey(vendor)).forEach(order::add);
        Map<String, String> vendors = new LinkedHashMap<>();
        for (String vendor : order) {
            if (!snapshot.tiers(vendor).isEmpty()) {
                vendors.put(vendor, catalog.getOrDefault(vendor, vendor));
            }
        }
        return vendors;
    }
}
