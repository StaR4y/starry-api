package tech.starryapi.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.starryapi.config.ProductionJson;
import tools.jackson.databind.JsonNode;

/**
 * What the billing review list tells about the tier of a request (design 8): the plan the reservation was made by, the
 * tier that served and the tier that was last dispatched to, each with its own quote, so that an administrator settles
 * by what the user was charged for and not by the reservation, which covers the dearest tier.
 */
class BillingReviewTiersTest {

    private static final OffsetDateTime DISPATCHED_AT = OffsetDateTime.parse("2026-10-25T01:02:03Z");
    private static final String PRICING = """
        {"model":"claude-sonnet-4.5","input":0.000003,"output":0.000015,"cacheRead":0.0000003,"cacheWrite":0.00000375,
         "fallback":0.000003,
         "tierPlan":{"vendor":"anthropic","configVersion":5,"mode":"auto",
          "holdLevels":["anthropic:official","anthropic:kiro"],"startTierRef":"anthropic:official",
          "levels":[{"tierRef":"anthropic:official","level":1,"name":"official","factor":1,"estimate":0.05,"dispatchKinds":["NATIVE"]},
                    {"tierRef":"anthropic:kiro","level":2,"name":"kiro","factor":0.6,"estimate":0.03,"dispatchKinds":["NATIVE"]}]}}
        """;

    @Test
    void aReservationOfARequestWithoutTiersComesOutAsItWas() {
        Map<String, Object> row = reservation();
        row.put("served_tier_ref", null);
        row.put("served_tier_level", null);
        row.put("served_price_factor", null);
        row.put("last_dispatched_tier_ref", null);
        row.put("last_dispatched_at", null);
        row.put("tier_pricing", null);

        Map<String, Object> described = BillingReviewTiers.describe(row);

        assertThat(described).isEqualTo(reservation());
        assertThat(described.keySet()).containsExactlyElementsOf(reservation().keySet());
    }

    @Test
    void aReservationLastDispatchedToATierShowsThePlanAndThatTiersQuote() {
        Map<String, Object> row = reservation();
        row.put("last_dispatched_tier_ref", "anthropic:kiro");
        row.put("last_dispatched_at", DISPATCHED_AT);
        row.put("tier_pricing", PRICING);

        Map<String, Object> described = BillingReviewTiers.describe(row);

        assertThat(described).doesNotContainKey("tier_pricing").doesNotContainKey("served");
        assertThat(described).containsEntry("request_hash", "hash-1").containsEntry("reason", "Client disconnected");
        JsonNode plan = (JsonNode) described.get("tier_plan");
        assertThat(plan.path("vendor").asString()).isEqualTo("anthropic");
        assertThat(plan.path("levels")).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> dispatched = (Map<String, Object>) described.get("last_dispatched");
        assertThat(dispatched).containsEntry("tierRef", "anthropic:kiro").containsEntry("level", 2)
            .containsEntry("name", "kiro").containsEntry("factor", "0.6").containsEntry("estimate", "0.03")
            .containsEntry("at", DISPATCHED_AT);
        // The standard price times the factor: $3 per million in becomes $1.8, and so on.
        @SuppressWarnings("unchecked")
        Map<String, String> quote = (Map<String, String>) dispatched.get("quote");
        assertThat(quote).containsExactly(
            Map.entry("input", "0.0000018"),
            Map.entry("output", "0.000009"),
            Map.entry("cacheRead", "0.00000018"),
            Map.entry("cacheWrite", "0.00000225"),
            Map.entry("fallback", "0.0000018"));
    }

    @Test
    void theTierThatServedIsShownWithWhatItWasSettledAtAndTheTierLastDispatchedToApart() {
        Map<String, Object> row = reservation();
        row.put("served_tier_ref", "anthropic:kiro");
        row.put("served_tier_level", (short) 2);
        row.put("served_price_factor", new BigDecimal("0.600000"));
        row.put("last_dispatched_tier_ref", "anthropic:official");
        row.put("last_dispatched_at", DISPATCHED_AT);
        row.put("tier_pricing", PRICING);

        Map<String, Object> described = BillingReviewTiers.describe(row);

        assertThat(described).containsEntry("served_tier_ref", "anthropic:kiro").containsEntry("served_tier_level", (short) 2)
            .containsEntry("last_dispatched_tier_ref", "anthropic:official");
        @SuppressWarnings("unchecked")
        Map<String, Object> served = (Map<String, Object>) described.get("served");
        // The level and the factor are the ones the settlement wrote, which the user was charged by.
        assertThat(served).containsEntry("tierRef", "anthropic:kiro").containsEntry("level", 2)
            .containsEntry("factor", "0.6").containsEntry("name", "kiro").containsEntry("estimate", "0.03")
            .doesNotContainKey("at");
        @SuppressWarnings("unchecked")
        Map<String, String> servedQuote = (Map<String, String>) served.get("quote");
        assertThat(servedQuote).containsEntry("input", "0.0000018").containsEntry("output", "0.000009");
        @SuppressWarnings("unchecked")
        Map<String, Object> dispatched = (Map<String, Object>) described.get("last_dispatched");
        assertThat(dispatched).containsEntry("tierRef", "anthropic:official").containsEntry("level", 1)
            .containsEntry("factor", "1").containsEntry("estimate", "0.05");
        @SuppressWarnings("unchecked")
        Map<String, String> dispatchedQuote = (Map<String, String>) dispatched.get("quote");
        assertThat(dispatchedQuote).containsEntry("input", "0.000003").containsEntry("output", "0.000015");
    }

    @Test
    void aTierThePlanDoesNotPriceIsShownWithoutAQuoteUnlessItWasSettled() {
        Map<String, Object> row = reservation();
        row.put("last_dispatched_tier_ref", "anthropic:somewhere-else");
        row.put("tier_pricing", PRICING);
        row.put("served_tier_ref", "anthropic:other");
        row.put("served_tier_level", (short) 3);
        row.put("served_price_factor", new BigDecimal("2"));

        Map<String, Object> described = BillingReviewTiers.describe(row);

        @SuppressWarnings("unchecked")
        Map<String, Object> dispatched = (Map<String, Object>) described.get("last_dispatched");
        assertThat(dispatched).containsOnlyKeys("tierRef");
        @SuppressWarnings("unchecked")
        Map<String, Object> served = (Map<String, Object>) described.get("served");
        // The settlement wrote its own factor, so the standard price can still be scaled by it.
        assertThat(served).containsEntry("level", 3).containsEntry("factor", "2").doesNotContainKey("name")
            .doesNotContainKey("estimate");
        @SuppressWarnings("unchecked")
        Map<String, String> quote = (Map<String, String>) served.get("quote");
        assertThat(quote).containsEntry("input", "0.000006");
    }

    @Test
    void aRowWithTierDetailsIsWrittenAsJsonThroughTheProductionCodec() {
        Map<String, Object> row = reservation();
        row.put("served_tier_ref", "anthropic:kiro");
        row.put("served_tier_level", (short) 2);
        row.put("served_price_factor", new BigDecimal("0.600000"));
        row.put("last_dispatched_tier_ref", "anthropic:kiro");
        row.put("last_dispatched_at", DISPATCHED_AT);
        row.put("tier_pricing", PRICING);

        ProductionJson.run(json -> {
            String viaCodec = json.write(BillingReviewTiers.describe(row));

            assertThat(viaCodec).isEqualTo(json.writeWithMapper(BillingReviewTiers.describe(row)));
            assertThat(viaCodec).startsWith("{\"request_hash\":\"hash-1\"")
                .contains("\"reserved_credits\":0.050000")
                .contains("\"served_tier_ref\":\"anthropic:kiro\",\"served_tier_level\":2,\"served_price_factor\":0.600000")
                .contains("\"tier_plan\":{\"vendor\":\"anthropic\",\"configVersion\":5,\"mode\":\"auto\"")
                .contains("\"served\":{\"tierRef\":\"anthropic:kiro\",\"level\":2,\"name\":\"kiro\",\"factor\":\"0.6\",\"estimate\":\"0.03\","
                    + "\"quote\":{\"input\":\"0.0000018\",\"output\":\"0.000009\",\"cacheRead\":\"0.00000018\",\"cacheWrite\":\"0.00000225\","
                    + "\"fallback\":\"0.0000018\"}}")
                .contains("\"last_dispatched\":{\"tierRef\":\"anthropic:kiro\",\"level\":2,\"name\":\"kiro\",\"factor\":\"0.6\","
                    + "\"estimate\":\"0.03\",\"at\":\"2026-10-25T01:02:03Z\",\"quote\"")
                .doesNotContain("tier_pricing");
        });
    }

    @Test
    void aSnapshotThatCannotBeReadCostsOnlyTheTierDetails() {
        Map<String, Object> row = reservation();
        row.put("last_dispatched_tier_ref", "anthropic:kiro");
        row.put("tier_pricing", "{not json");

        Map<String, Object> described = BillingReviewTiers.describe(row);

        assertThat(described).containsEntry("request_hash", "hash-1").doesNotContainKey("tier_plan").doesNotContainKey("tier_pricing");
        @SuppressWarnings("unchecked")
        Map<String, Object> dispatched = (Map<String, Object>) described.get("last_dispatched");
        assertThat(dispatched).containsOnlyKeys("tierRef");
    }

    private static Map<String, Object> reservation() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_hash", "hash-1");
        row.put("request_id", "request-1");
        row.put("user_id", "user-1");
        row.put("key_id", "key-1");
        row.put("billing_source", "BALANCE");
        row.put("reserved_credits", new BigDecimal("0.050000"));
        row.put("actual_credits", null);
        row.put("reason", "Client disconnected");
        row.put("created_at", DISPATCHED_AT);
        return row;
    }
}
