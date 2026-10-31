package tech.starryapi.apikey;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an administrator needs to settle a reservation held for review when the request was served from source tiers
 * (design 8): the plan it was reserved by, the tier that served it and the tier it was last dispatched to, each with
 * the quote of that tier. The reservation itself is sized for the tier that reserves the most of those the request could
 * have been served from, so settling by it would charge a user for a tier that did not serve them.
 *
 * <p>A reservation of a request that was not served from tiers has none of this, and its row comes out as it always did.
 */
final class BillingReviewTiers {

    /** Rates are read as decimals: a snapshot's numbers are exact, and a double would round them. */
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    /** The rates of a pricing snapshot, which a tier's factor multiplies. */
    private static final List<String> RATES = List.of("input", "output", "cacheRead", "cacheWrite", "fallback");
    /** The tier columns of the reservation: they stay out of the row unless the request has a value for them. */
    private static final Set<String> TIER_COLUMNS = Set.of(
        "served_tier_ref", "served_tier_level", "served_price_factor", "last_dispatched_tier_ref", "last_dispatched_at");
    private static final String PRICING_COLUMN = "tier_pricing";

    private BillingReviewTiers() {
    }

    /**
     * The row as it is returned: the columns of the reservation and, for a request served from tiers, its tier columns
     * with {@code tier_plan}, {@code served} and {@code last_dispatched} added. The pricing snapshot
     * ({@code tier_pricing}, which is only selected for such a request) is read to build them and not returned.
     */
    static Map<String, Object> describe(Map<String, Object> row) {
        Map<String, Object> described = new LinkedHashMap<>();
        row.forEach((column, value) -> {
            if (!PRICING_COLUMN.equals(column) && (value != null || !TIER_COLUMNS.contains(column))) {
                described.put(column, value);
            }
        });
        JsonNode pricing = parse(row.get(PRICING_COLUMN));
        JsonNode plan = pricing == null ? null : pricing.path("tierPlan");
        if (plan != null && plan.isObject()) {
            described.put("tier_plan", plan);
        }
        if (row.get("served_tier_ref") instanceof String servedRef && !servedRef.isBlank()) {
            described.put("served", tier(servedRef, integer(row.get("served_tier_level")),
                decimal(row.get("served_price_factor")), null, pricing));
        }
        if (row.get("last_dispatched_tier_ref") instanceof String dispatchedRef && !dispatchedRef.isBlank()) {
            described.put("last_dispatched", tier(dispatchedRef, null, null, row.get("last_dispatched_at"), pricing));
        }
        return described;
    }

    /**
     * One tier of the reservation. The level and the factor of the tier that served are the ones it was settled with,
     * which is what the user was charged by; for any other they are the plan's. The quote is the standard price of the
     * model multiplied by the factor, which is how the tier priced the request when it was reserved. Money is written
     * as decimal strings, like the other administrator pages do.
     */
    private static Map<String, Object> tier(String tierRef, Integer level, BigDecimal factor, Object at, JsonNode pricing) {
        JsonNode entry = entry(pricing, tierRef);
        Integer resolvedLevel = level;
        if (resolvedLevel == null && entry != null && entry.path("level").isNumber()) {
            resolvedLevel = entry.path("level").intValue();
        }
        BigDecimal resolvedFactor = factor != null ? factor : entry == null ? null : decimal(entry.path("factor"));
        BigDecimal estimate = entry == null ? null : decimal(entry.path("estimate"));
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("tierRef", tierRef);
        if (resolvedLevel != null) {
            tier.put("level", resolvedLevel);
        }
        if (entry != null && entry.path("name").isString()) {
            tier.put("name", entry.path("name").asString());
        }
        if (resolvedFactor != null) {
            tier.put("factor", plain(resolvedFactor));
        }
        if (estimate != null) {
            tier.put("estimate", plain(estimate));
        }
        if (at != null) {
            tier.put("at", at);
        }
        Map<String, String> quote = resolvedFactor == null ? null : quote(pricing, resolvedFactor);
        if (quote != null) {
            tier.put("quote", quote);
        }
        return tier;
    }

    /** The rates of the snapshot times the factor, as {@code BillingQuote.scaled} multiplies them; null if a rate is missing. */
    private static Map<String, String> quote(JsonNode pricing, BigDecimal factor) {
        if (pricing == null) {
            return null;
        }
        Map<String, String> quote = new LinkedHashMap<>();
        for (String rate : RATES) {
            BigDecimal value = decimal(pricing.path(rate));
            if (value == null) {
                return null;
            }
            quote.put(rate, plain(value.multiply(factor)));
        }
        return quote;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static JsonNode entry(JsonNode pricing, String tierRef) {
        if (pricing == null) {
            return null;
        }
        for (JsonNode level : pricing.path("tierPlan").path("levels")) {
            if (tierRef.equals(level.path("tierRef").asString(null))) {
                return level;
            }
        }
        return null;
    }

    private static JsonNode parse(Object pricing) {
        if (!(pricing instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (RuntimeException e) {
            // A snapshot that cannot be read costs the row its tier details, not the whole list of reviews.
            return null;
        }
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : null;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }
}
