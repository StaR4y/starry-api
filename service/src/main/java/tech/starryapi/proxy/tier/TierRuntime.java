package tech.starryapi.proxy.tier;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * What the proxy facade needs from the tiered-source feature, handed over as one value so its constructor does not
 * grow with every phase. While no tiers are configured, or the tiered path is not built yet, this is
 * {@link #disabled()}: the planner says {@link Plan#UNTIERED} for every request and the facade runs exactly the code
 * it ran before.
 */
public record TierRuntime(Planner planner) {

    private static final TierRuntime DISABLED = new TierRuntime((requestedModel, rawEntryBody) -> Plan.UNTIERED);

    public TierRuntime {
        Objects.requireNonNull(planner, "planner");
    }

    public static TierRuntime disabled() {
        return DISABLED;
    }

    /** The planner's verdict for one request. UNTIERED is the only one until the planner builds real plans. */
    public enum Plan {
        UNTIERED
    }

    @FunctionalInterface
    public interface Planner {
        /**
         * @param rawEntryBody the body as the client sent it, before an adapter turned it into a chat request
         */
        Plan plan(String requestedModel, JsonNode rawEntryBody);
    }
}
