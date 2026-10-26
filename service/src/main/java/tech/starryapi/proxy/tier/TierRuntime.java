package tech.starryapi.proxy.tier;

import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * What the proxy facade needs from the tiered-source feature, handed over as one value so its constructor does not
 * grow with every phase. A release that cannot run tiers has {@link #disabled()}, whose planner says
 * {@link TierPlan#UNTIERED} for every request. One that can has the real planner, which says the same for a vendor
 * without tiers in use. Either way the facade runs exactly the code it ran before for that request.
 *
 * @param bindings where the tier each session was pushed down to is kept (design 4.7), and the relays that broke off an
 *     answer for a session (design 7.8); the planner reads it, and the facade writes it when a request is served or a
 *     relay fails
 * @param learnedLimits the context windows that the refusals of relays taught this node (design 7.8); the planner reads
 *     them, and the facade writes them when a relay refuses a prompt for its length
 */
public record TierRuntime(Planner planner, TierSessionBindings bindings, XpLearnedLimits learnedLimits) {

    private static final TierRuntime DISABLED = new TierRuntime(request -> Mono.just(TierPlan.UNTIERED));

    public TierRuntime {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(learnedLimits, "learnedLimits");
    }

    /** A runtime that learns nothing of the relays. */
    public TierRuntime(Planner planner, TierSessionBindings bindings) {
        this(planner, bindings, XpLearnedLimits.disabled());
    }

    /** A runtime that keeps no session to its tier. */
    public TierRuntime(Planner planner) {
        this(planner, TierSessionBindings.disabled());
    }

    public static TierRuntime disabled() {
        return DISABLED;
    }

    /**
     * Where the facade sends a model id: the id the key's model mapping made of it, that id as the upstream is asked
     * for it, and the provider hint the plan names the vendor by when the catalog does not know the model, which is that
     * of the accounts that will serve it. They are what the plan of the request is made with, so the vendor that decides
     * whether an {@code @} is a selector is the one that serves the request. The mapping is asked once, for this: a rule
     * that picks among several targets at random must not be asked again for the model the request goes on with.
     */
    public record Destination(String mappedModel, String upstreamModel, String providerHint) {

        /** A model that no mapping changed. */
        public Destination(String upstreamModel, String providerHint) {
            this(upstreamModel, upstreamModel, providerHint);
        }
    }

    @FunctionalInterface
    public interface Planner {
        /**
         * Plans one request before anything is reserved. An error ends the request there: nothing was reserved yet.
         */
        Mono<TierPlan> plan(TierPlanRequest request);

        /**
         * Takes the tier selector off the end of the model id a client asked for (design 6.1): {@code claude-opus-4.5@kiro}
         * is the model {@code claude-opus-4.5} and the selector {@code kiro}. It is done once, where the request enters, so
         * that mapping, the model checks and the price only ever see the plain id. Whether the {@code @} is a selector is
         * for the vendor that will serve the model to say, which is why the caller tells where the model goes: a catalog
         * model of a vendor with a tier configuration always loses it, and a model the catalog does not list loses it
         * only when the reference names a tier of that vendor. Every other id is left alone, as it was before selectors
         * existed, and so is every id when this release cannot select tiers.
         *
         * @param destination what the route and the model mapping of the key make of the model id that is left of the
         *     {@code @}
         */
        default TierSelectorParser.Stripped stripSelector(String model, Function<String, Destination> destination) {
            return TierSelectorParser.Stripped.plain(model);
        }

        /**
         * Whether a request that comes in through {@code entry} is to be read for the relays that may serve it
         * ({@link TierEntryFacts}). The facade asks before it reads anything, since the reading looks at the whole body: a
         * deployment with no relay, or none whose pair is offered for the entry, or none that a gate of the entry is open
         * to (a relay that is registered but not opened yet, or whose tier or vendor is switched off), never pays for it.
         */
        default boolean wantsEntryFacts(TierEntry entry) {
            return false;
        }
    }
}
