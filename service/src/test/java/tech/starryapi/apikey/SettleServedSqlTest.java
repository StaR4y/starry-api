package tech.starryapi.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.DefaultModelCatalogProvider;

/**
 * The SQL a request served from source tiers settles and leases its reservation with, without a database (design 5.3
 * and 4.6): the function that records the serving tier with the charge, how its arguments are bound (the level must
 * reach PostgreSQL as an int4, which is the only type the function resolves), and the statement that renews the
 * lease. What the statements do in PostgreSQL is the integration tests' part.
 */
class SettleServedSqlTest {

    private static final ApiKeyPrincipal USER = new ApiKeyPrincipal("key-1", "user-1", "USER", "default", null,
        BigDecimal.ZERO, BigDecimal.TEN);
    private static final ApiKeyPrincipal ANONYMOUS = new ApiKeyPrincipal("anonymous", null, "ANONYMOUS", "anonymous", null,
        BigDecimal.ZERO, BigDecimal.ZERO, "*");

    // ---- accountUsageServed ----

    @Test
    void settlesThroughTheServedFunctionWithTheTierItsLevelAndItsFactor() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        harness.service().accountUsageServed(USER, "request-123", new BigDecimal("0.0234"), 8_000, 1_000,
            "anthropic:kiro", 2, new BigDecimal("0.600000")).block();

        assertThat(harness.sql()).contains(
            "starry_api.settle_api_usage_served(",
            ":requestHash", ":credits", ":inputTokens", ":outputTokens", ":tierRef", ":level", ":priceFactor");
        // One statement is the whole settlement, so the tier is recorded in the transaction that charges.
        assertThat(harness.statements()).isEqualTo(1);
        assertThat(harness.binds()).containsEntry("requestHash", Hashing.sha256("5:key-1request-123"));
        assertThat(harness.binds()).containsEntry("tierRef", "anthropic:kiro");
        assertThat(harness.binds().get("credits")).isEqualTo(new BigDecimal("0.0234"));
        assertThat(harness.binds().get("priceFactor")).isEqualTo(new BigDecimal("0.600000"));
        assertThat(harness.binds().get("inputTokens")).isEqualTo(8_000L);
        assertThat(harness.binds().get("outputTokens")).isEqualTo(1_000L);
    }

    @Test
    void bindsTheLevelAsAJavaIntegerSoItReachesPostgreSqlAsAnInt4() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        harness.service().accountUsageServed(USER, "request-123", BigDecimal.ONE, 1, 1, "anthropic:official", 1, BigDecimal.ONE).block();

        // R2DBC maps Integer to int4; the function takes INTEGER and has no implicit conversion from int2 or to it from int8.
        assertThat(harness.binds().get("level")).isInstanceOf(Integer.class).isEqualTo(1);
        assertThat(harness.sql()).doesNotContainIgnoringCase("::smallint").doesNotContainIgnoringCase("as smallint");
    }

    @Test
    void theServedSettlementUsesTheSameRequestHashAsTheFourArgumentOne() {
        Harness served = harness("ACCOUNTED", 3_000L);
        Harness plain = harness("ACCOUNTED", 3_000L);

        served.service().accountUsageServed(USER, "request-123", BigDecimal.ONE, 1, 1, "anthropic:official", 1, BigDecimal.ONE).block();
        plain.service().accountUsage(USER, "request-123", BigDecimal.ONE, 1, 1).block();

        assertThat(served.binds().get("requestHash")).isEqualTo(plain.binds().get("requestHash"));
    }

    @Test
    void theFourArgumentSettlementIsLeftExactlyAsItWas() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        harness.service().accountUsage(USER, "request-123", new BigDecimal("0.25"), 2, 3).block();

        assertThat(harness.sql()).contains("starry_api.settle_api_usage(:requestHash, :credits, :inputTokens, :outputTokens)")
            .doesNotContain("_served", ":tierRef", ":level");
        assertThat(harness.binds()).containsOnlyKeys("requestHash", "credits", "inputTokens", "outputTokens");
    }

    @Test
    void negativeAmountsAreSettledAsZero() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        harness.service().accountUsageServed(USER, "request-123", new BigDecimal("-1"), -5, -6, "anthropic:official", 1, BigDecimal.ONE).block();

        assertThat(harness.binds().get("credits")).isEqualTo(BigDecimal.ZERO);
        assertThat(harness.binds().get("inputTokens")).isEqualTo(0L);
        assertThat(harness.binds().get("outputTokens")).isEqualTo(0L);
    }

    @Test
    void theAnonymousCallerIsNotSettled() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        harness.service().accountUsageServed(ANONYMOUS, "request-123", BigDecimal.ONE, 1, 1, "anthropic:official", 1, BigDecimal.ONE).block();

        verify(harness.databaseClient(), never()).sql(anyString());
    }

    @Test
    void aSettlementNeedsARequestId() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        assertThatThrownBy(() -> harness.service()
            .accountUsageServed(USER, " ", BigDecimal.ONE, 1, 1, "anthropic:official", 1, BigDecimal.ONE).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("REQUEST_ID_REQUIRED"));
    }

    @Test
    void theOutcomesAreTheOnesOfTheFourArgumentSettlementPlusTheTwoOfTheWrapper() {
        for (String ok : new String[] {"ACCOUNTED", "ALREADY_ACCOUNTED"}) {
            Harness harness = harness(ok, 3_000L);
            harness.service().accountUsageServed(USER, "request-123", BigDecimal.ONE, 1, 1, "anthropic:official", 1, BigDecimal.ONE).block();
        }
        assertSettlementRejected("INSUFFICIENT_BALANCE", HttpStatus.PAYMENT_REQUIRED);
        assertSettlementRejected("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT);
        assertSettlementRejected("BILLING_TIER_INVALID", HttpStatus.CONFLICT);
        assertSettlementRejected("BILLING_RESERVATION_REQUIRED", HttpStatus.CONFLICT);
    }

    // ---- markUsageDispatchedTiered ----

    @Test
    void marksTheReservationDispatchedToTheTierAndRenewsItsLeaseWithinTheHour() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        ApiKeyService.DispatchLease lease = harness.service().markUsageDispatchedTiered(USER, "request-123", "anthropic:kiro").block();

        String sql = harness.sql();
        assertThat(sql).contains(
            "UPDATE billing_reservations",
            "status = 'DISPATCHED'",
            "last_dispatched_tier_ref = :tierRef",
            "last_dispatched_at = clock_timestamp()",
            // Renewed to cover one dispatch, never shortened, never past an hour after the reservation was made.
            "LEAST(created_at + INTERVAL '60 minutes'",
            "GREATEST(expires_at, clock_timestamp() + make_interval(secs => CAST(:windowSeconds AS double precision)))",
            "WHERE request_hash = :hash AND status IN ('RESERVED', 'DISPATCHED') AND expires_at > clock_timestamp()",
            "RETURNING");
        assertThat(harness.binds()).containsEntry("hash", Hashing.sha256("5:key-1request-123"))
            .containsEntry("tierRef", "anthropic:kiro")
            .containsEntry("windowSeconds", 960L);
        assertThat(lease).isEqualTo(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofSeconds(3_000)));
    }

    @Test
    void theOriginalMarkOfADispatchIsLeftExactlyAsItWas() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        harness.service().markUsageDispatched(USER, "request-123").block();

        assertThat(harness.sql()).contains("UPDATE billing_reservations SET status = 'DISPATCHED', updated_at = clock_timestamp()")
            .doesNotContain("last_dispatched", "expires_at =", "LEAST", "GREATEST");
    }

    @Test
    void aReservationThatIsNoLongerLeasedCannotBeDispatched() {
        Harness harness = harness("ACCOUNTED", null);

        assertThatThrownBy(() -> harness.service().markUsageDispatchedTiered(USER, "request-123", "anthropic:kiro").block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("BILLING_RESERVATION_EXPIRED");
            });
    }

    @Test
    void whatRemainsUntilTheCapIsNeverNegative() {
        Harness harness = harness("ACCOUNTED", -5L);

        ApiKeyService.DispatchLease lease = harness.service().markUsageDispatchedTiered(USER, "request-123", "anthropic:kiro").block();

        assertThat(lease.untilCap()).isEqualTo(Duration.ZERO);
    }

    @Test
    void theAnonymousCallerHasNoLease() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        assertThat(harness.service().markUsageDispatchedTiered(ANONYMOUS, "request-123", "anthropic:kiro").block()).isNull();

        verify(harness.databaseClient(), never()).sql(anyString());
    }

    // ---- the window ----

    @Test
    void aDispatchMayRunForTheClientTimeoutOnEachOfItsTriesAndAMinuteOver() {
        // The defaults: 300 seconds, two retries.
        assertThat(harness("ACCOUNTED", 1L).service().leaseWindow()).isEqualTo(Duration.ofSeconds(960));
    }

    @Test
    void theWindowIsNeverShorterThanTheLeaseAReservationStartsWith() {
        ProxyProperties properties = new ProxyProperties();
        properties.setRequestTimeout(Duration.ofSeconds(60));
        properties.setMaxRetries(0);

        assertThat(harness("ACCOUNTED", 1L, properties).service().leaseWindow()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theWindowFollowsTheConfiguredTimeoutAndRetries() {
        ProxyProperties properties = new ProxyProperties();
        properties.setRequestTimeout(Duration.ofSeconds(600));
        properties.setMaxRetries(3);

        assertThat(harness("ACCOUNTED", 1L, properties).service().leaseWindow()).isEqualTo(Duration.ofSeconds(600 * 4 + 60));
    }

    // ---- renewing the lease of a running dispatch ----

    @Test
    void renewsTheLeaseOfAReservationThatIsStillDispatchedWithinTheHour() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        Boolean renewed = harness.service().renewDispatchLease(USER, "request-123").block();

        assertThat(renewed).isTrue();
        assertThat(harness.sql()).contains(
            "UPDATE billing_reservations",
            // The same renewal as a dispatch makes: never shortened, never past an hour after the reservation was made.
            "LEAST(created_at + INTERVAL '60 minutes'",
            "GREATEST(expires_at, clock_timestamp() + make_interval(secs => CAST(:windowSeconds AS double precision)))",
            // Only a reservation that is still dispatched: not one the sweeper made a review of, nor a settled or released one.
            "WHERE request_hash = :hash AND status = 'DISPATCHED'")
            .doesNotContain("last_dispatched", "status = 'RESERVED'", "SET status");
        assertThat(harness.binds()).containsEntry("hash", Hashing.sha256("5:key-1request-123"))
            .containsEntry("windowSeconds", 960L);
        assertThat(harness.statements()).isEqualTo(1);
    }

    @Test
    void aReservationThatIsNoLongerDispatchedIsNotRenewed() {
        Harness harness = harness("ACCOUNTED", 3_000L);
        harness.updatedRows().set(0L);

        assertThat(harness.service().renewDispatchLease(USER, "request-123").block()).isFalse();
    }

    @Test
    void theAnonymousCallerHasNoLeaseToRenew() {
        Harness harness = harness("ACCOUNTED", 3_000L);

        assertThat(harness.service().renewDispatchLease(ANONYMOUS, "request-123").block()).isFalse();

        verify(harness.databaseClient(), never()).sql(anyString());
    }

    @Test
    void theLeaseOfARunningDispatchIsRenewedAQuarterOfTheWindowApart() {
        assertThat(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59)).renewalPeriod())
            .isEqualTo(Duration.ofSeconds(240));
        assertThat(new ApiKeyService.DispatchLease(Duration.ofMinutes(15), Duration.ofMinutes(59)).renewalPeriod())
            .isEqualTo(Duration.ofSeconds(225));
    }

    @Test
    void twoFailedRenewalsInARowStillLeaveTheLeaseAQuarterOfTheWindowToSpare() {
        Duration window = Duration.ofSeconds(960);
        Duration period = new ApiKeyService.DispatchLease(window, Duration.ofMinutes(59)).renewalPeriod();

        // A renewal that succeeded leaves the lease a window from then; the third one after it comes three periods later.
        assertThat(window.minus(period.multipliedBy(3))).isEqualTo(window.dividedBy(4)).isPositive();
    }

    @Test
    void theRenewalPeriodIsNeverShorterThanASecond() {
        assertThat(new ApiKeyService.DispatchLease(Duration.ZERO, Duration.ofMinutes(59)).renewalPeriod()).isEqualTo(Duration.ofSeconds(1));
        assertThat(new ApiKeyService.DispatchLease(Duration.ofSeconds(2), Duration.ofMinutes(59)).renewalPeriod()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void onlyTheRenewalsBeforeTheHourAReservationCanBeLeasedForCanStillExtendTheLease() {
        // 59 minutes at 240 seconds apart: 14 of them (the last, at 3 360 seconds, takes the lease to the hour).
        assertThat(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59)).renewalsUntilCap()).isEqualTo(14);
        assertThat(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofSeconds(700)).renewalsUntilCap()).isEqualTo(2);
        assertThat(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofSeconds(239)).renewalsUntilCap()).isZero();
        assertThat(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ZERO).renewalsUntilCap()).isZero();
    }

    // ---- fixtures ----

    private void assertSettlementRejected(String outcome, HttpStatus status) {
        Harness harness = harness(outcome, 3_000L);
        assertThatThrownBy(() -> harness.service()
            .accountUsageServed(USER, "request-123", BigDecimal.ONE, 1, 1, "anthropic:official", 1, BigDecimal.ONE).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(status);
                assertThat(error.getCode()).isEqualTo(outcome);
            });
    }

    private Harness harness(String accountingStatus, Long secondsToCap) {
        return harness(accountingStatus, secondsToCap, new ProxyProperties());
    }

    /**
     * The service over a database client that records the statement and what is bound to it. A settlement answers
     * {@code accountingStatus}; a lease update answers {@code secondsToCap}, or no row when that is null.
     */
    @SuppressWarnings("unchecked")
    private Harness harness(String accountingStatus, Long secondsToCap, ProxyProperties properties) {
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        Map<String, Object> binds = new LinkedHashMap<>();
        int[] statements = {0};
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            statements[0]++;
            return spec;
        });
        when(spec.bind(anyString(), any())).thenAnswer(invocation -> {
            binds.put(invocation.getArgument(0), invocation.getArgument(1));
            return spec;
        });
        when(spec.bindNull(anyString(), any())).thenReturn(spec);
        when(fetch.one()).thenReturn(Mono.just(Map.of("accounting_status", accountingStatus)));
        AtomicLong updatedRows = new AtomicLong(1L);
        when(fetch.rowsUpdated()).thenAnswer(invocation -> Mono.just(updatedRows.get()));
        when(spec.fetch()).thenReturn(fetch);
        when(spec.map(ArgumentMatchers.<BiFunction<Row, RowMetadata, Object>>any())).thenAnswer(invocation -> {
            BiFunction<Row, RowMetadata, Object> mapper = invocation.getArgument(0);
            Row row = mock(Row.class);
            when(row.get("seconds_to_cap", Long.class)).thenReturn(secondsToCap);
            RowsFetchSpec<Object> rows = mock(RowsFetchSpec.class);
            when(rows.one()).thenReturn(secondsToCap == null
                ? Mono.empty()
                : Mono.fromSupplier(() -> mapper.apply(row, mock(RowMetadata.class))));
            return rows;
        });
        return new Harness(
            new ApiKeyService(mock(ApiKeyRepository.class), template, properties, new DefaultModelCatalogProvider()),
            databaseClient, sql, binds, statements, updatedRows);
    }

    private record Harness(
        ApiKeyService service,
        DatabaseClient databaseClient,
        AtomicReference<String> capturedSql,
        Map<String, Object> binds,
        int[] statementCount,
        AtomicLong updatedRows
    ) {
        String sql() {
            return capturedSql.get();
        }

        int statements() {
            return statementCount[0];
        }
    }
}
