package tech.starryapi.proxy.tier;

import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * What the proxy facade needs from the tiered-source feature, handed over as one value so its constructor does not
 * grow with every phase. A release that cannot run tiers has {@link #disabled()}, whose planner says
 * {@link TierPlan#UNTIERED} for every request. One that can has the real planner, which says the same for a vendor
 * without tiers in use. Either way the facade runs exactly the code it ran before for that request.
 *
 * @param bindings where the tier each session was pushed down to is kept (design 4.7); the planner reads it, and the
 *     facade writes it when a request is served
 */
public record TierRuntime(Planner planner, TierSessionBindings bindings) {

    private static final TierRuntime DISABLED = new TierRuntime(request -> Mono.just(TierPlan.UNTIERED));

    public TierRuntime {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(bindings, "bindings");
    }

    /** A runtime that keeps no session to its tier. */
    public TierRuntime(Planner planner) {
        this(planner, TierSessionBindings.disabled());
    }

    public static TierRuntime disabled() {
        return DISABLED;
    }

    @FunctionalInterface
    public interface Planner {
        /**
         * Plans one request before anything is reserved. An error ends the request there: nothing was reserved yet.
         */
        Mono<TierPlan> plan(TierPlanRequest request);
    }
}
