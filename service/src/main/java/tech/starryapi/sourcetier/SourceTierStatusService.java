package tech.starryapi.sourcetier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountCandidateRanker;
import tech.starryapi.account.allocation.AccountCandidateRanker.AccountLoad;
import tech.starryapi.proxy.tier.TierMembership;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Placement;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * What the administrator sees of the tiers while they run (design 8): per tier, the accounts by the layer of design 4.1
 * that keeps them from serving; which tiers this node would serve from; how the last 15 minutes were served; and, for
 * the account list, the tier each account is in. It reads this node's snapshot and account cache and the request log, and
 * changes nothing.
 *
 * <p>The account figures are those of the node that answers, which is the node that would serve a request sent to it:
 * its configuration snapshot, its account cache (up to 30 seconds old) and the allocation tables. The distribution of
 * served requests is read from the request log and so covers every node.
 */
@Service
public class SourceTierStatusService {

    /** How far back the request log is read for how requests were served. */
    static final Duration WINDOW = Duration.ofMinutes(15);

    private static final String AUTO = "AUTO";
    private static final String PINNED = "PINNED";

    private final SourceTierRegistry registry;
    private final SourceTierRuntimeInfo runtime;
    private final SourceTierValidator validator;
    private final AccountPoolService accountPool;
    private final AccountCandidateRanker ranker;
    private final Reads reads;
    private final Clock clock;

    @Autowired
    public SourceTierStatusService(
        SourceTierRegistry registry,
        SourceTierRuntimeInfo runtime,
        SourceTierValidator validator,
        AccountPoolService accountPool,
        AccountCandidateRanker ranker,
        DatabaseClient db
    ) {
        this(registry, runtime, validator, accountPool, ranker, new DatabaseReads(db), Clock.systemUTC());
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
        this.registry = registry;
        this.runtime = runtime;
        this.validator = validator;
        this.accountPool = accountPool;
        this.ranker = ranker;
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
            List<ProxyAccountEntity> inTiers = enabled.stream()
                .filter(account -> reachable.entrySet().stream()
                    .anyMatch(vendor -> tierOf(snapshot, vendor.getKey(), vendor.getValue(), account).isPresent()))
                .toList();
            return Mono.zip(reads.disabledAccounts(), ranker.loads(inTiers), reads.served(now.minus(WINDOW)))
                .map(read -> new SourceTierDtos.Status(
                    now,
                    snapshot.configVersion(),
                    minutes(),
                    vendors.entrySet().stream()
                        .map(vendor -> vendorStatus(vendor.getKey(), vendor.getValue(), snapshot, enabled, read.getT1(),
                            read.getT2(), read.getT3()))
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
        List<Served> served
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
                new SourceTierDtos.TierAccounts(held.disabled, held.unavailable, held.resting, held.busy, held.ready),
                count.served,
                count.pinned,
                count.failed
            ));
        }
        return new SourceTierDtos.VendorStatus(vendor, label, config.enabled(), serving, autoServed, degraded, tierViews);
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
    }

    private static final class Counts {
        long served;
        long pinned;
        long failed;
    }

    /** One group of the request log: the attempts of a tier, how it was chosen, and whether they succeeded. */
    record Served(String tierRef, String selection, boolean success, long attempts) {
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
    }

    private record DatabaseReads(DatabaseClient db) implements Reads {

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
     * Whether a relay is asked for anything now: an entry of the vendor has a gate that is not off for a pair this
     * release offers, and one of its mappings that is switched on is verified for what the relay and the account are now
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
