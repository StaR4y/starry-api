package tech.starryapi.proxy.tier;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import tech.starryapi.account.PoolCell;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a tiered request is reserved and charged by (design 5.2 and 5.3), taken from its plan and fixed with it. It
 * takes the place of the single quote an untiered request keeps in the Reactor context: the reservation is the largest
 * that any tier the request may be served from would need, and a settlement asks for the price of the tier that
 * actually served. A tier the plan did not price is an error rather than a fallback to the largest quote, so a user is
 * never charged more than the tier that served them.
 */
public final class TierBilling {

    private final TierPlan plan;
    private final String model;

    private TierBilling(TierPlan plan, String model) {
        this.plan = plan;
        this.model = model;
    }

    /**
     * @param model the model the price was looked up under, which is the one the client asked for
     * @throws IllegalStateException if the plan has no price, as the plan of a request that is not billed has none
     */
    public static TierBilling of(TierPlan plan, String model) {
        if (!plan.tiered()) {
            throw new IllegalArgumentException("Only a tiered plan has a tiered billing");
        }
        if (plan.reservation() == null || plan.standardQuote() == null) {
            throw new IllegalStateException("The plan of a billed request carries no price");
        }
        return new TierBilling(plan, model);
    }

    /** What to reserve: the largest estimate of the tiers the request may be served from. */
    public BigDecimal reservation() {
        return plan.reservation();
    }

    /**
     * The pricing snapshot stored with the reservation. The fields an untiered snapshot has describe the standard
     * price; {@code tierPlan} adds the tiers, so that a reservation which ends up in review, or whose lease expired, can
     * still be priced at the tier it was last dispatched to.
     */
    public String pricing() {
        BillingQuote quote = plan.standardQuote();
        ObjectNode pricing = JsonNodeFactory.instance.objectNode();
        pricing.put("model", model).put("input", quote.input()).put("output", quote.output())
            .put("cacheRead", quote.cacheRead()).put("cacheWrite", quote.cacheWrite()).put("fallback", quote.fallback());
        ObjectNode tierPlan = pricing.putObject("tierPlan");
        tierPlan.put("vendor", plan.vendor()).put("configVersion", plan.configVersion()).put("mode", plan.mode().headerValue());
        ArrayNode hold = tierPlan.putArray("holdLevels");
        plan.holdLevels().forEach(level -> hold.add(level.tierRef()));
        tierPlan.put("startTierRef", plan.startLevel().tierRef());
        ArrayNode levels = tierPlan.putArray("levels");
        for (Level level : plan.levels()) {
            ObjectNode entry = levels.addObject();
            entry.put("tierRef", level.tierRef()).put("level", level.level()).put("name", level.name())
                .put("factor", level.factor()).put("estimate", level.estimate());
            ArrayNode kinds = entry.putArray("dispatchKinds");
            dispatchKinds(level).forEach(kinds::add);
        }
        return pricing.toString();
    }

    /**
     * The charge for {@code usage} served by {@code served}: the tier's frozen price, exactly the one its estimate was
     * made from.
     *
     * @throws IllegalStateException if the tier is not one the plan priced
     */
    public BigDecimal cost(Level served, PlatformUsage usage) {
        Level priced = plan.levels().stream()
            .filter(level -> level.tierRef().equals(served.tierRef()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Tier " + served.tierRef() + " served a request whose plan did not price it"));
        return priced.quote().cost(usage);
    }

    private static Set<String> dispatchKinds(Level level) {
        Set<String> kinds = new LinkedHashSet<>();
        for (PoolCell cell : level.cells()) {
            kinds.add(cell == PoolCell.XP_OPENAI_CHAT ? "OPENAI_CHAT" : TierDispatch.Kind.NATIVE.name());
        }
        return kinds;
    }
}
