package tech.starryapi.sourcetier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What this release can run of the tiered-source feature. The configuration is stored in the database and outlives the
 * release, so the administrator API refuses a setting whose capability is false here (409
 * SOURCE_TIER_RUNTIME_UNSUPPORTED): nothing is stored that a later upgrade would switch on without anyone deciding to.
 *
 * <p>A capability is true only once the task that completes its runtime has landed: {@link #nativeTiers()} was set by
 * the end-to-end task of native tiering (P2-09), {@link #selectors()} is for the selector task (P3-01),
 * {@link #unifyEndpoints()} for the all-endpoints switch (P3-03) and {@link #xpMembers()} for the exclusive-relay task
 * (P4-10); those three are still false. Until the pair registry (P4-01) exists no pair is offered.
 */
@Component
public class SourceTierRuntimeInfo {

    /** How far a (source protocol, entry endpoint, vendor) pair has come: a gate may be ON only for VERIFIED. */
    public enum PairStatus {
        NOT_OFFERED, EXPERIMENTAL, VERIFIED
    }

    public record Pair(String vendor, String sourceProtocol, String entryEndpoint) {
    }

    /** The only source protocol with a client in this release. */
    public static final String OPENAI_CHAT = "OPENAI_CHAT";
    public static final List<String> ENTRY_ENDPOINTS = List.of("CHAT", "MESSAGES", "RESPONSES", "GEMINI");
    /** A relay can only serve the vendors whose own accounts are not already OpenAI-format keys (design 7.3). */
    public static final List<String> CROSS_PROTOCOL_VENDORS = List.of("anthropic", "openai", "google");

    private final boolean nativeTiers;
    private final boolean selectors;
    private final boolean unifyEndpoints;
    private final boolean xpMembers;
    private final Function<Pair, PairStatus> pairStatuses;

    @Autowired
    public SourceTierRuntimeInfo() {
        this(true, false, false, false, pair -> PairStatus.NOT_OFFERED);
    }

    /** For tests, and for the day the pair registry decides {@code pairStatuses}. */
    public SourceTierRuntimeInfo(
        boolean nativeTiers,
        boolean unifyEndpoints,
        boolean xpMembers,
        Function<Pair, PairStatus> pairStatuses
    ) {
        this(nativeTiers, false, unifyEndpoints, xpMembers, pairStatuses);
    }

    public SourceTierRuntimeInfo(
        boolean nativeTiers,
        boolean selectors,
        boolean unifyEndpoints,
        boolean xpMembers,
        Function<Pair, PairStatus> pairStatuses
    ) {
        this.nativeTiers = nativeTiers;
        this.selectors = selectors;
        this.unifyEndpoints = unifyEndpoints;
        this.xpMembers = xpMembers;
        this.pairStatuses = Objects.requireNonNull(pairStatuses, "pairStatuses");
    }

    /** Whether a request is planned over tiers at all, which is what a vendor's {@code enabled} asks for. */
    public boolean nativeTiers() {
        return nativeTiers;
    }

    /**
     * Whether a client can name the tier it wants: the {@code model@tier} suffix, the {@code X-Starry-Tier} header and
     * the key's preference. Nothing is stored for it, so it refuses no setting; the console only offers to copy the
     * suffix form when it works.
     */
    public boolean selectors() {
        return selectors;
    }

    public boolean unifyEndpoints() {
        return unifyEndpoints;
    }

    /** Whether cross-protocol member rows, and gates that open a pair, can run. */
    public boolean xpMembers() {
        return xpMembers;
    }

    public PairStatus pairStatus(String vendor, String sourceProtocol, String entryEndpoint) {
        PairStatus status = pairStatuses.apply(new Pair(vendor, sourceProtocol, entryEndpoint));
        return status == null ? PairStatus.NOT_OFFERED : status;
    }

    /** Every pair a gate can open, in the order the console lists them. */
    public List<Pair> pairs() {
        List<Pair> pairs = new ArrayList<>();
        for (String vendor : CROSS_PROTOCOL_VENDORS) {
            for (String entry : ENTRY_ENDPOINTS) {
                pairs.add(new Pair(vendor, OPENAI_CHAT, entry));
            }
        }
        return pairs;
    }
}
