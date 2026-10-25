package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyRepository;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.DefaultModelCatalogProvider;

/**
 * What a request served from source tiers does to its reservation, through the service the proxy calls and as the
 * runtime login (design 4.6 and 5.3): the lease is renewed for each dispatch, never shortened and never past an hour
 * after the reservation was made; the tier that served is written with the charge and the rest of the reservation is
 * refunded, to the balance or to the Coding Plan subscriptions the hold was taken from.
 */
@Timeout(240)
class TierBillingPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String DATABASE = "tier_billing";
    private static final String PRICING = """
        {"model":"claude-sonnet-4.5","tierPlan":{"vendor":"anthropic","configVersion":5,"mode":"auto",
         "holdLevels":["anthropic:official","anthropic:kiro"],"startTierRef":"anthropic:official",
         "levels":[{"tierRef":"anthropic:official","level":1,"name":"official","factor":1,"estimate":0.05,"dispatchKinds":["NATIVE"]},
                   {"tierRef":"anthropic:kiro","level":2,"name":"kiro","factor":0.6,"estimate":0.03,"dispatchKinds":["NATIVE"]}]}}
        """;

    private static String url;
    private static ApiKeyService service;

    @BeforeAll
    static void migrateADatabaseAndConnectAsTheRuntime() throws Exception {
        exec("psql", "-U", "starry_api_bootstrap", "-d", "starry_api", "-v", "ON_ERROR_STOP=1",
            "-c", "CREATE DATABASE " + DATABASE + " OWNER starry_api_owner",
            "-c", "REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC",
            "-c", "GRANT CONNECT ON DATABASE " + DATABASE + " TO starry_api_migration, starry_api_runtime");
        exec("psql", "-U", "starry_api_bootstrap", "-d", DATABASE, "-v", "ON_ERROR_STOP=1", "-c", """
            CREATE SCHEMA starry_api AUTHORIZATION starry_api_owner;
            REVOKE ALL ON SCHEMA starry_api FROM PUBLIC;
            GRANT USAGE ON SCHEMA starry_api TO starry_api_runtime;
            REVOKE ALL ON SCHEMA public FROM PUBLIC;
            """);
        url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + DATABASE);
        Flyway.configure().dataSource(url, MIGRATION_USERNAME, MIGRATION_PASSWORD)
            .locations("classpath:db/migration").schemas("starry_api").defaultSchema("starry_api").createSchemas(false)
            .initSql("SET ROLE starry_api_owner").load().migrate();
        ConnectionFactory runtime = ConnectionFactories.get("r2dbc:postgresql://" + APP_USERNAME + ":" + APP_PASSWORD + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DATABASE + "?schema=starry_api");
        service = new ApiKeyService(mock(ApiKeyRepository.class), new R2dbcEntityTemplate(runtime), new ProxyProperties(),
            new DefaultModelCatalogProvider());
    }

    // ---- the lease ----

    @Test
    void aTieredDispatchRecordsItsTierAndRenewsTheLeaseForTheTimeADispatchMayTake() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            // A reservation starts with a lease of 15 minutes.
            assertThat(seconds(db, "SELECT extract(epoch FROM expires_at - created_at) FROM billing_reservations WHERE request_hash = ?",
                caller.hash("req-1"))).isBetween(899.0, 901.0);

            ApiKeyService.DispatchLease lease = service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();

            Map<String, Object> reservation = reservation(db, caller.hash("req-1"));
            assertThat(reservation).containsEntry("status", "DISPATCHED").containsEntry("last_dispatched_tier_ref", "anthropic:official");
            assertThat(reservation.get("last_dispatched_at")).isNotNull();
            // The default window: 300 seconds for each of three tries and a minute over.
            assertThat(lease.window()).isEqualTo(Duration.ofSeconds(960));
            assertThat(seconds(db, "SELECT extract(epoch FROM expires_at - last_dispatched_at) FROM billing_reservations WHERE request_hash = ?",
                caller.hash("req-1"))).isBetween(959.9, 960.5);
            assertThat(lease.untilCap().toSeconds()).isBetween(3_590L, 3_600L);
        }
    }

    @Test
    void aLaterDispatchRenewsTheLeaseAndRecordsTheTierItWentTo() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();
            update(db, "UPDATE billing_reservations SET expires_at = clock_timestamp() + interval '5 seconds' WHERE request_hash = ?",
                caller.hash("req-1"));

            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();

            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
            assertThat(seconds(db, "SELECT extract(epoch FROM expires_at - clock_timestamp()) FROM billing_reservations WHERE request_hash = ?",
                caller.hash("req-1"))).isBetween(950.0, 961.0);
        }
    }

    @Test
    void theLeaseIsNeverShortenedByADispatch() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            update(db, "UPDATE billing_reservations SET expires_at = created_at + interval '50 minutes' WHERE request_hash = ?",
                caller.hash("req-1"));

            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();

            assertThat(scalar(db, "SELECT expires_at = created_at + interval '50 minutes' FROM billing_reservations WHERE request_hash = ?",
                caller.hash("req-1"))).isEqualTo(true);
        }
    }

    @Test
    void theLeaseNeverRunsPastAnHourAfterTheReservationWasMade() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            // A reservation made 50 minutes ago whose lease still has five minutes to run.
            update(db, """
                UPDATE billing_reservations SET created_at = clock_timestamp() - interval '50 minutes',
                    expires_at = clock_timestamp() + interval '5 minutes' WHERE request_hash = ?""", caller.hash("req-1"));

            ApiKeyService.DispatchLease lease = service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();

            // A dispatch needs 960 seconds and the cap is 10 minutes away: the lease is renewed as far as it may go,
            // and the caller learns there is no room for another dispatch after this one.
            assertThat(scalar(db, "SELECT expires_at = created_at + interval '60 minutes' FROM billing_reservations WHERE request_hash = ?",
                caller.hash("req-1"))).isEqualTo(true);
            assertThat(lease.untilCap().toSeconds()).isBetween(595L, 600L);
            assertThat(lease.untilCap()).isLessThan(lease.window());
        }
    }

    @Test
    void aRunningDispatchRenewsTheLeaseItHoldsToTheWindowFromNow() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();
            // Fifteen minutes into the dispatch the lease has five seconds left.
            update(db, "UPDATE billing_reservations SET expires_at = clock_timestamp() + interval '5 seconds' WHERE request_hash = ?",
                caller.hash("req-1"));

            assertThat(service.renewDispatchLease(caller.principal(), "req-1").block()).isTrue();

            assertThat(seconds(db, "SELECT extract(epoch FROM expires_at - clock_timestamp()) FROM billing_reservations WHERE request_hash = ?",
                caller.hash("req-1"))).isBetween(950.0, 961.0);
            // The dispatch is still the one that was marked: its tier and status are not touched by a renewal.
            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("status", "DISPATCHED")
                .containsEntry("last_dispatched_tier_ref", "anthropic:official");
        }
    }

    @Test
    void aRenewalNeverShortensTheLeaseAndNeverRunsItPastAnHourAfterTheReservationWasMade() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();
            update(db, "UPDATE billing_reservations SET expires_at = created_at + interval '50 minutes' WHERE request_hash = ?",
                caller.hash("req-1"));

            // A lease that already reaches further than the window from now is left as it is.
            assertThat(service.renewDispatchLease(caller.principal(), "req-1").block()).isTrue();
            assertThat(scalar(db, "SELECT expires_at = created_at + interval '50 minutes' FROM billing_reservations WHERE request_hash = ?",
                caller.hash("req-1"))).isEqualTo(true);

            // Fifty minutes into the request, the window from now would reach past the hour: it is cut to the hour.
            update(db, """
                UPDATE billing_reservations SET created_at = clock_timestamp() - interval '50 minutes',
                    expires_at = clock_timestamp() + interval '5 seconds' WHERE request_hash = ?""", caller.hash("req-1"));
            assertThat(service.renewDispatchLease(caller.principal(), "req-1").block()).isTrue();
            assertThat(scalar(db, "SELECT expires_at = created_at + interval '60 minutes' FROM billing_reservations WHERE request_hash = ?",
                caller.hash("req-1"))).isEqualTo(true);
        }
    }

    @Test
    void aLeaseThatRanOutIsRenewedWhileTheSweeperHasNotSelectedItYet() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();
            update(db, "UPDATE billing_reservations SET expires_at = clock_timestamp() - interval '1 second' WHERE request_hash = ?",
                caller.hash("req-1"));

            assertThat(service.renewDispatchLease(caller.principal(), "req-1").block()).isTrue();

            // The sweeper selects the reservations whose lease ran out; this one is not among them any more. (One it had
            // already selected would still be released: the release does not look at the expiry again.)
            assertThat(scalar(db, "SELECT count(*) FROM billing_reservations WHERE request_hash = ? AND status IN ('RESERVED','DISPATCHED')"
                + " AND expires_at < clock_timestamp()", caller.hash("req-1"))).isEqualTo(0L);
        }
    }

    @Test
    void aReservationThatIsNoLongerDispatchedIsLeftAsItIsByARenewal() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();
            // The sweeper found the lease run out and turned the reservation into a review.
            service.releaseUsage(caller.principal(), "req-1", false, "Reservation lease expired").block();
            Object leaseBefore = scalar(db, "SELECT expires_at FROM billing_reservations WHERE request_hash = ?", caller.hash("req-1"));

            assertThat(service.renewDispatchLease(caller.principal(), "req-1").block()).isFalse();

            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("status", "REVIEW_REQUIRED");
            assertThat(scalar(db, "SELECT expires_at FROM billing_reservations WHERE request_hash = ?", caller.hash("req-1")))
                .isEqualTo(leaseBefore);
        }
    }

    @Test
    void aReservationThatWasNeverDispatchedIsNotRenewed() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();

            assertThat(service.renewDispatchLease(caller.principal(), "req-1").block()).isFalse();
            assertThat(service.renewDispatchLease(caller.principal(), "unknown").block()).isFalse();
        }
    }

    @Test
    void aStreamThatFinishesAfterTheSweeperMadeAReviewOfItsReservationIsSettledAtItsTier() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();
            // A dispatch that outlived the hour its reservation can be leased for: nothing renews it any more, so the
            // sweeper turns the reservation into a review and keeps the hold.
            service.releaseUsage(caller.principal(), "req-1", false, "Reservation lease expired").block();
            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("status", "REVIEW_REQUIRED");
            assertThat(balance(db, caller)).isEqualByComparingTo("9.95");

            service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.0234"), 8_000, 1_000, "anthropic:kiro", 2,
                new BigDecimal("0.6")).block();

            // The settlement clears the review: charged at the serving tier, the rest of the hold refunded.
            Map<String, Object> reservation = reservation(db, caller.hash("req-1"));
            assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:kiro")
                .containsEntry("served_tier_level", 2);
            assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo("0.0234");
            assertThat(balance(db, caller)).isEqualByComparingTo("9.9766");
            assertThat(service.reserveUsage(caller.principal(), "req-2", new BigDecimal("0.05"), PRICING).block()).isNull();
        }
    }

    @Test
    void aReservationWhoseLeaseRanOutCannotBeDispatched() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            update(db, "UPDATE billing_reservations SET expires_at = clock_timestamp() - interval '1 second' WHERE request_hash = ?",
                caller.hash("req-1"));

            assertThatThrownBy(() -> service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block())
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getCode()).isEqualTo("BILLING_RESERVATION_EXPIRED");
                });

            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("status", "RESERVED")
                .containsEntry("last_dispatched_tier_ref", null);
        }
    }

    @Test
    void aSettledReservationCannotBeDispatchedAgain() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();
            service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.5"), 100, 20, "anthropic:official", 1, BigDecimal.ONE).block();

            assertThatThrownBy(() -> service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block())
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("BILLING_RESERVATION_EXPIRED"));
            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("last_dispatched_tier_ref", "anthropic:official");
        }
    }

    @Test
    void theOriginalDispatchMarkStillLeavesTheLeaseAndTheTierAlone() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("2"), PRICING).block();
            Object leaseBefore = scalar(db, "SELECT expires_at FROM billing_reservations WHERE request_hash = ?", caller.hash("req-1"));

            service.markUsageDispatched(caller.principal(), "req-1").block();

            Map<String, Object> reservation = reservation(db, caller.hash("req-1"));
            assertThat(reservation).containsEntry("status", "DISPATCHED").containsEntry("last_dispatched_tier_ref", null);
            assertThat(reservation.get("last_dispatched_at")).isNull();
            assertThat(reservation.get("expires_at")).isEqualTo(leaseBefore);
        }
    }

    // ---- the settlement ----

    @Test
    void theServingTierIsWrittenWithTheChargeAndTheRestOfTheReservationIsRefunded() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();
            assertThat(balance(db, caller)).isEqualByComparingTo("9.95");
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();

            // The reservation was sized by the dearest tier; the second tier served at 0.6 of the standard price.
            service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.0234"), 8_000, 1_000, "anthropic:kiro", 2,
                new BigDecimal("0.6")).block();

            Map<String, Object> reservation = reservation(db, caller.hash("req-1"));
            assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:kiro")
                .containsEntry("served_tier_level", 2);
            assertThat((BigDecimal) reservation.get("served_price_factor")).isEqualByComparingTo("0.6");
            assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo("0.0234");
            assertThat(balance(db, caller)).isEqualByComparingTo("9.9766");
            Map<String, Object> event = rows(db, "SELECT * FROM api_usage_events WHERE request_hash = ?", caller.hash("req-1")).getFirst();
            assertThat(event).containsEntry("source_tier_ref", "anthropic:kiro");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("0.6");
            assertThat((BigDecimal) event.get("credits")).isEqualByComparingTo("0.0234");
        }
    }

    @Test
    void aRepeatedSettlementIsTheSameOutcomeAndAnotherTierIsAConflict() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();
            service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.0234"), 8_000, 1_000, "anthropic:kiro", 2,
                new BigDecimal("0.6")).block();

            service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.0234"), 8_000, 1_000, "anthropic:kiro", 2,
                new BigDecimal("0.6")).block();

            assertThatThrownBy(() -> service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.0234"), 8_000, 1_000,
                "anthropic:official", 1, BigDecimal.ONE).block())
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
            assertThat(scalar(db, "SELECT count(*) FROM api_usage_events WHERE request_hash = ?", caller.hash("req-1"))).isEqualTo(1L);
            assertThat(balance(db, caller)).isEqualByComparingTo("9.9766");
            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("served_tier_ref", "anthropic:kiro");
        }
    }

    @Test
    void aLevelOutsideOneToNineIsRefusedBeforeAnythingIsCharged() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();

            assertThatThrownBy(() -> service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.0234"), 1, 1,
                "anthropic:kiro", 10, new BigDecimal("0.6")).block())
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("BILLING_TIER_INVALID"));

            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("status", "RESERVED").containsEntry("served_tier_ref", null);
            assertThat(balance(db, caller)).isEqualByComparingTo("9.95");
        }
    }

    // ---- a Coding Plan key ----

    @Test
    void aCodingPlanKeyIsChargedTheServingTiersCostAndTheRestOfTheHoldGoesBackToThePlan() throws Exception {
        try (Connection db = owner()) {
            Caller caller = codingPlanCaller(db, "10", "1");
            String subscription = subscriptionsOf(db, caller).getFirst();
            // The hold is the dearest tier's estimate, taken from the plan's quota and not from the balance.
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();
            assertThat(subscription(db, subscription)).satisfies(held -> {
                assertThat((BigDecimal) held.get("reserved_credits")).isEqualByComparingTo("0.05");
                assertThat((BigDecimal) held.get("used_credits")).isEqualByComparingTo("0");
            });
            assertThat(balance(db, caller)).isEqualByComparingTo("10");
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();

            service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.0234"), 8_000, 1_000, "anthropic:kiro", 2,
                new BigDecimal("0.6")).block();

            // The plan is charged what the second tier costs and gets the rest of the hold back; the balance is untouched.
            assertThat(subscription(db, subscription)).satisfies(settled -> {
                assertThat((BigDecimal) settled.get("used_credits")).isEqualByComparingTo("0.0234");
                assertThat((BigDecimal) settled.get("reserved_credits")).isEqualByComparingTo("0");
            });
            assertThat(balance(db, caller)).isEqualByComparingTo("10");
            assertThat(rows(db, "SELECT * FROM subscription_ledger WHERE subscription_id = ? AND entry_type = 'SETTLE'", subscription))
                .singleElement().satisfies(entry -> {
                    assertThat((BigDecimal) entry.get("amount")).isEqualByComparingTo("-0.0234");
                    assertThat((BigDecimal) entry.get("reserved_delta")).isEqualByComparingTo("-0.05");
                    assertThat(entry.get("reference_id")).isEqualTo(caller.hash("req-1"));
                });
            Map<String, Object> reservation = reservation(db, caller.hash("req-1"));
            assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("billing_source", "CODING_PLAN")
                .containsEntry("served_tier_ref", "anthropic:kiro").containsEntry("served_tier_level", 2);
            Map<String, Object> event = rows(db, "SELECT * FROM api_usage_events WHERE request_hash = ?", caller.hash("req-1")).getFirst();
            assertThat(event).containsEntry("subscription_id", subscription).containsEntry("source_tier_ref", "anthropic:kiro")
                .containsEntry("charge_balance", false);
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("0.6");
            assertThat((BigDecimal) scalar(db, "SELECT reserved_credits FROM api_keys WHERE key_id = ?", caller.principal().keyId()))
                .isEqualByComparingTo("0");
            assertThat((BigDecimal) scalar(db, "SELECT total_credits FROM api_keys WHERE key_id = ?", caller.principal().keyId()))
                .isEqualByComparingTo("0.0234");
        }
    }

    @Test
    void aHoldSpreadOverSeveralSubscriptionsIsSettledOnAllOfThemAtTheServingTiersCost() throws Exception {
        try (Connection db = owner()) {
            Caller caller = codingPlanCaller(db, "10", "0.03", "0.04");
            List<String> subscriptions = subscriptionsOf(db, caller);
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();
            // The hold takes the earlier-ending subscription first (0.03) and the rest from the next one.
            assertThat(rows(db, "SELECT subscription_id, reserved_credits FROM billing_reservation_allocations WHERE request_hash = ?",
                caller.hash("req-1"))).hasSize(2);
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();

            service.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.0234"), 8_000, 1_000, "anthropic:kiro", 2,
                new BigDecimal("0.6")).block();

            BigDecimal used = BigDecimal.ZERO;
            for (String id : subscriptions) {
                Map<String, Object> settled = subscription(db, id);
                assertThat((BigDecimal) settled.get("reserved_credits")).as(id).isEqualByComparingTo("0");
                used = used.add((BigDecimal) settled.get("used_credits"));
            }
            assertThat(used).isEqualByComparingTo("0.0234");
            assertThat(rows(db, "SELECT * FROM subscription_ledger WHERE entry_type = 'SETTLE' AND reference_id = ?",
                caller.hash("req-1"))).hasSize(2);
            assertThat(rows(db, "SELECT * FROM api_usage_events WHERE request_hash = ?", caller.hash("req-1"))).singleElement()
                .satisfies(event -> {
                    assertThat(event.get("subscription_id")).isIn((Object[]) subscriptions.toArray());
                    assertThat(event.get("source_tier_ref")).isEqualTo("anthropic:kiro");
                });
            assertThat(balance(db, caller)).isEqualByComparingTo("10");
        }
    }

    @Test
    void aCodingPlanReviewSettledByAnAdministratorIsCreditedToTheTierDispatchedLast() throws Exception {
        try (Connection db = owner()) {
            Caller caller = codingPlanCaller(db, "10", "1");
            String subscription = subscriptionsOf(db, caller).getFirst();
            service.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();
            service.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();
            // The request was dispatched and nobody can say how it ended: the hold stays, for review.
            service.releaseUsage(caller.principal(), "req-1", false, "lease expired").block();
            assertThat(reservation(db, caller.hash("req-1"))).containsEntry("status", "REVIEW_REQUIRED")
                .containsEntry("served_tier_ref", null).containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
            assertThat((BigDecimal) subscription(db, subscription).get("reserved_credits")).isEqualByComparingTo("0.05");

            assertThat(scalar(db, "SELECT starry_api.resolve_billing_review(?, 'admin', 'SETTLE', 'checked', 0.0234)", caller.hash("req-1")))
                .isEqualTo("ACCOUNTED");

            assertThat(subscription(db, subscription)).satisfies(settled -> {
                assertThat((BigDecimal) settled.get("used_credits")).isEqualByComparingTo("0.0234");
                assertThat((BigDecimal) settled.get("reserved_credits")).isEqualByComparingTo("0");
            });
            // The tier was never written by a settlement, so the event is credited to the one dispatched last, at the factor
            // the reservation froze for it.
            assertThat(rows(db, "SELECT * FROM api_usage_events WHERE request_hash = ?", caller.hash("req-1"))).singleElement()
                .satisfies(event -> {
                    assertThat(event.get("subscription_id")).isEqualTo(subscription);
                    assertThat(event.get("source_tier_ref")).isEqualTo("anthropic:kiro");
                    assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("0.6");
                });
            assertThat(balance(db, caller)).isEqualByComparingTo("10");
        }
    }

    // ---- fixtures ----

    private record Caller(ApiKeyPrincipal principal, String userId) {
        String hash(String requestId) {
            String keyId = principal.keyId();
            return Hashing.sha256(keyId.length() + ":" + keyId + requestId.trim());
        }
    }

    private static Caller caller(Connection db, String balance) throws SQLException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "user-" + suffix;
        String key = "key-" + suffix;
        update(db, "INSERT INTO admin_users(user_id, username, role, balance, password_hash, password_salt, enabled, first_login)"
            + " VALUES (?, ?, 'USER', ?::numeric, repeat('a', 64), repeat('b', 32), TRUE, FALSE)", user, user, balance);
        update(db, "INSERT INTO api_keys(key_id, user_id, name, key_hash, key_prefix, model_provider)"
            + " VALUES (?, ?, 'test', repeat(md5(?), 2), 'sk-test', 'Anthropic')", key, user, key);
        return new Caller(new ApiKeyPrincipal(key, user, "USER", "test", null, BigDecimal.ZERO, new BigDecimal(balance), "Anthropic"), user);
    }

    /**
     * A caller whose key is charged to Coding Plan subscriptions, with one subscription of each of the given quotas (each of
     * its own plan, the later ones ending later), covering the Anthropic provider the key serves.
     */
    private static Caller codingPlanCaller(Connection db, String balance, String... quotas) throws SQLException {
        Caller caller = caller(db, balance);
        update(db, "UPDATE api_keys SET billing_source = 'CODING_PLAN' WHERE key_id = ?", caller.principal().keyId());
        for (String quota : quotas) {
            String plan = "plan-" + UUID.randomUUID().toString().substring(0, 8);
            update(db, "INSERT INTO coding_plans(plan_id, name, price_credits, duration_days, quota_credits, allowed_providers)"
                + " VALUES (?, 'plan', 0, 30, ?::numeric, '[\"Anthropic\"]'::jsonb)", plan, quota);
            assertThat(scalar(db, "SELECT starry_api.activate_coding_plan(?, ?, NULL, 'ADMIN_GRANT', NULL, NULL, 'admin') ->> 'subscriptionId'",
                caller.userId(), plan)).isNotNull();
        }
        return caller;
    }

    /** The caller's subscriptions, the one that ends first leading. */
    private static List<String> subscriptionsOf(Connection db, Caller caller) throws SQLException {
        return rows(db, "SELECT subscription_id FROM coding_plan_subscriptions WHERE user_id = ? ORDER BY ends_at, subscription_id",
            caller.userId()).stream().map(row -> (String) row.get("subscription_id")).toList();
    }

    private static Map<String, Object> subscription(Connection db, String subscriptionId) throws SQLException {
        return rows(db, "SELECT * FROM coding_plan_subscriptions WHERE subscription_id = ?", subscriptionId).getFirst();
    }

    private static Map<String, Object> reservation(Connection db, String hash) throws SQLException {
        return rows(db, "SELECT * FROM billing_reservations WHERE request_hash = ?", hash).getFirst();
    }

    private static BigDecimal balance(Connection db, Caller caller) throws SQLException {
        return (BigDecimal) scalar(db, "SELECT balance FROM admin_users WHERE user_id = ?", caller.userId());
    }

    private static double seconds(Connection db, String sql, Object... args) throws SQLException {
        return ((Number) scalar(db, sql, args)).doubleValue();
    }

    private static Connection owner() throws SQLException {
        Connection connection = DriverManager.getConnection(url, MIGRATION_USERNAME, MIGRATION_PASSWORD);
        try (var statement = connection.createStatement()) {
            statement.execute("SET ROLE starry_api_owner");
            statement.execute("SET search_path TO starry_api");
        }
        return connection;
    }

    private static long update(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            bind(statement, args);
            return statement.execute() ? -1 : statement.getUpdateCount();
        }
    }

    private static List<Map<String, Object>> rows(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            bind(statement, args);
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), result.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private static Object scalar(Connection db, String sql, Object... args) throws SQLException {
        List<Map<String, Object>> rows = rows(db, sql, args);
        assertThat(rows).as(sql).hasSize(1);
        return rows.getFirst().values().iterator().next();
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private static void exec(String... command) throws Exception {
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
    }
}
