package tech.starryapi.sourcetier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.starryapi.platform.xproto.PairRegistry;

/**
 * What this release can run of the tiered-source feature. The configuration is stored in the database and outlives the
 * release, so the administrator API refuses a setting whose capability is false here (409
 * SOURCE_TIER_RUNTIME_UNSUPPORTED): nothing is stored that a later upgrade would switch on without anyone deciding to.
 *
 * <p>A capability is true only once the task that completes its runtime has landed: {@link #nativeTiers()} was set by
 * the end-to-end task of native tiering (P2-09) and {@link #selectors()} by the selector task (P3-01), which the key
 * preference (P3-02) joined; {@link #unifyEndpoints()} by the all-endpoints switch (P3-03); {@link #xpMembers()} by the
 * exclusive-relay task (P4-10), which keeps a registered relay out of the pools of untiered traffic. The status of a
 * pair is what the code's {@link PairRegistry} lists, and it lists none until the converters and fixtures of the first
 * pairs are written, so a relay can be registered but no pair that would send it requests can be opened yet.
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
        this(true, true, true, true, statusesOf(PairRegistry.defaults()));
    }

    /** For tests that need another answer than the registry's. */
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

    /** The status of a pair as {@code registry} lists it, in the terms of the gate validation. */
    public static Function<Pair, PairStatus> statusesOf(PairRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return pair -> PairStatus.valueOf(registry.status(pair.vendor(), pair.sourceProtocol(), pair.entryEndpoint()).name());
    }

    /** Whether a request is planned over tiers at all, which is what a vendor's {@code enabled} asks for. */
    public boolean nativeTiers() {
        return nativeTiers;
    }

    /**
     * Whether a client can name the tier it wants: the {@code model@tier} suffix, the {@code X-Starry-Tier} header and
     * the preference of the API key. Nothing about the tiers is stored for it, so it refuses no setting; the console only
     * offers to copy the suffix form, and to choose a pool for a key, when it works.
     */
    public boolean selectors() {
        return selectors;
    }

    /**
     * Whether the anthropic vendor's all-endpoints switch works: with it on, a Claude model asked through Chat
     * Completions or Responses is served by the Anthropic keys and then Kiro, as it is through the Messages endpoint
     * (design 4.9).
     */
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
