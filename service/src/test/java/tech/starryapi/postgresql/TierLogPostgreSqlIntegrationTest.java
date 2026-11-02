package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
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
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyRepository;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.apikey.BillingReviewController;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.stats.AdminStatsController;
import tech.starryapi.stats.PerformanceMetricService;
import tech.starryapi.stats.RequestLogRepository;
import tech.starryapi.stats.StatsAnalyticsService;
import tech.starryapi.stats.StatsDtos;
import tech.starryapi.stats.StatsService;

/**
 * What a request served from source tiers leaves in the database, through the services the proxy calls and as the
 * runtime login (design 5.7 and 8): the source columns of its request log, who may read them, and the tier details of
 * a reservation that is held for review.
 */
@Timeout(240)
class TierLogPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String DATABASE = "tier_logs";
    private static final String PRICING = """
        {"model":"claude-sonnet-4.5","input":0.000003,"output":0.000015,"cacheRead":0.0000003,"cacheWrite":0.00000375,
         "fallback":0.000003,
         "tierPlan":{"vendor":"anthropic","configVersion":5,"mode":"auto",
         "holdLevels":["anthropic:official","anthropic:kiro"],"startTierRef":"anthropic:official",
         "levels":[{"tierRef":"anthropic:official","level":1,"name":"official","factor":1,"estimate":0.05,"dispatchKinds":["NATIVE"]},
                   {"tierRef":"anthropic:kiro","level":2,"name":"kiro","factor":0.6,"estimate":0.03,"dispatchKinds":["NATIVE"]}]}}
        """;
    private static final String UNTIERED_PRICING = """
        {"model":"claude-sonnet-4.5","input":0.000003,"output":0.000015,"cacheRead":0.0000003,"cacheWrite":0.00000375,"fallback":0.000003}
        """;

    private static String url;
    private static ApiKeyService apiKeys;
    private static StatsService stats;
    private static RequestLogRepository logs;
    private static StatsAnalyticsService analytics;
    private static DatabaseClient databaseClient;

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
        R2dbcEntityTemplate template = new R2dbcEntityTemplate(runtime);
        apiKeys = new ApiKeyService(mock(ApiKeyRepository.class), template, new ProxyProperties(), new DefaultModelCatalogProvider());
        logs = new R2dbcRepositoryFactory(template).getRepository(RequestLogRepository.class);
        stats = new StatsService(logs);
        databaseClient = DatabaseClient.create(runtime);
        analytics = new StatsAnalyticsService(databaseClient);
    }

    // ---- the request log ----

    @Test
    void theSourceColumnsOfARowAreStoredAndAnUntieredRowStoresNone() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        StatsService.RequestLog base = row("served-" + suffix, "user-" + suffix);
        StatsService.RequestLog untiered = row("untiered-" + suffix, "user-" + suffix);

        stats.log(base.withSource(new StatsService.SourceColumns(
            "anthropic:kiro", 2, "kiro", new BigDecimal("0.6"), "AUTO", null, null, null, null, null))).block();
        stats.log(untiered).block();
        stats.log(row("relay-" + suffix, "user-" + suffix).withSource(new StatsService.SourceColumns(
            "anthropic:relay", 1, "relay", BigDecimal.ONE, "PINNED", "OPENAI_CHAT", "claude-sonnet-4.5@relay", "ESTIMATED",
            "MAX_TOKENS", "images,cache_control"))).block();

        try (Connection db = owner()) {
            Map<String, Object> served = onlyRow(db, "served-" + suffix);
            assertThat(served).containsEntry("source_tier_ref", "anthropic:kiro").containsEntry("source_tier_level", 2)
                .containsEntry("source_tier_name", "kiro").containsEntry("tier_selection", "AUTO")
                .containsEntry("source_dispatch", null).containsEntry("wire_model", null)
                .containsEntry("usage_source", null).containsEntry("stop_reason", null).containsEntry("degraded_features", null);
            assertThat((BigDecimal) served.get("price_factor")).isEqualByComparingTo("0.6");
            Map<String, Object> none = onlyRow(db, "untiered-" + suffix);
            assertThat(none).containsEntry("source_tier_ref", null).containsEntry("source_tier_level", null)
                .containsEntry("source_tier_name", null).containsEntry("price_factor", null)
                .containsEntry("tier_selection", null).containsEntry("source_dispatch", null).containsEntry("wire_model", null)
                .containsEntry("usage_source", null).containsEntry("stop_reason", null).containsEntry("degraded_features", null);
            // The columns a row always had are the same either way.
            assertThat(none).containsEntry("model", "claude-sonnet-4.5").containsEntry("status_code", 200);
            Map<String, Object> relay = onlyRow(db, "relay-" + suffix);
            assertThat(relay).containsEntry("source_dispatch", "OPENAI_CHAT").containsEntry("wire_model", "claude-sonnet-4.5@relay")
                .containsEntry("usage_source", "ESTIMATED").containsEntry("stop_reason", "MAX_TOKENS")
                .containsEntry("degraded_features", "images,cache_control").containsEntry("tier_selection", "PINNED");
        }
    }

    @Test
    void aUserSeesTheNameLevelAndFactorOfTheTierAndNothingElseOfItsSource() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "reader-" + suffix;
        stats.log(row("mine-" + suffix, user).withSource(new StatsService.SourceColumns(
            "anthropic:relay", 1, "relay", new BigDecimal("1.5"), "AUTO", "OPENAI_CHAT", "wire", "ESTIMATED", "MAX_TOKENS", "images")))
            .block();
        stats.log(row("plain-" + suffix, user)).block();

        StatsDtos.RequestLogPageResponse page = analytics.logsForUser(user, null, null, null, 50).block();

        assertThat(page).isNotNull();
        assertThat(page.items()).extracting(StatsDtos.RequestLogResponse::requestId).containsExactlyInAnyOrder("mine-" + suffix, "plain-" + suffix);
        StatsDtos.RequestLogResponse mine = page.items().stream().filter(item -> item.requestId().equals("mine-" + suffix)).findFirst().orElseThrow();
        assertThat(mine.sourceTierLevel()).isEqualTo(1);
        assertThat(mine.sourceTierName()).isEqualTo("relay");
        assertThat(mine.priceFactor()).isEqualByComparingTo("1.5");
        assertThat(mine.sourceTierRef()).isNull();
        assertThat(mine.tierSelection()).isNull();
        assertThat(mine.sourceDispatch()).isNull();
        assertThat(mine.wireModel()).isNull();
        assertThat(mine.usageSource()).isNull();
        assertThat(mine.stopReason()).isNull();
        assertThat(mine.degradedFeatures()).isNull();
        assertThat(mine.credits()).isEqualByComparingTo("0.0234");
        StatsDtos.RequestLogResponse plain = page.items().stream().filter(item -> item.requestId().equals("plain-" + suffix)).findFirst().orElseThrow();
        assertThat(plain.sourceTierLevel()).isNull();
        assertThat(plain.sourceTierName()).isNull();
        assertThat(plain.priceFactor()).isNull();
    }

    @Test
    void theAdministratorSeesEverySourceColumn() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        stats.log(row("admin-" + suffix, "user-" + suffix).withSource(new StatsService.SourceColumns(
            "anthropic:relay", 1, "relay", new BigDecimal("1.5"), "AUTO", "OPENAI_CHAT", "wire", "ESTIMATED", "MAX_TOKENS", "images")))
            .block();
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(new AdminPrincipal("root", "root", "ADMIN", null, true));
        AdminStatsController controller = new AdminStatsController(logs, mock(PerformanceMetricService.class), analytics, adminAuth);

        List<StatsDtos.RequestLogResponse> list = controller.logs(null).block().data();

        StatsDtos.RequestLogResponse row = list.stream().filter(item -> item.requestId().equals("admin-" + suffix)).findFirst().orElseThrow();
        assertThat(row.sourceTierRef()).isEqualTo("anthropic:relay");
        assertThat(row.sourceTierLevel()).isEqualTo(1);
        assertThat(row.sourceTierName()).isEqualTo("relay");
        assertThat(row.priceFactor()).isEqualByComparingTo("1.5");
        assertThat(row.tierSelection()).isEqualTo("AUTO");
        assertThat(row.sourceDispatch()).isEqualTo("OPENAI_CHAT");
        assertThat(row.wireModel()).isEqualTo("wire");
        assertThat(row.usageSource()).isEqualTo("ESTIMATED");
        assertThat(row.stopReason()).isEqualTo("MAX_TOKENS");
        assertThat(row.degradedFeatures()).isEqualTo("images");
    }

    /**
     * The relay was paid for an answer the gateway held back and the user was not: the row keeps what the relay reported
     * in columns of their own, only the administrator reads them, and the tokens the user's statistics sum stay what the user
     * was served, which is nothing.
     */
    @Test
    void aHeldBackAnswerKeepsWhatTheRelayReportedApartFromTheUsageOfTheUser() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String user = "held-" + suffix;
        StatsService.RequestLog failed = new StatsService.RequestLog("held-" + suffix, "key-1", user, "relay-1", "/v1/responses",
            "claude-sonnet-4.5", 502, false, 0, 0, BigDecimal.ZERO, 120, "The source's answer was cut off before it could be used");
        stats.log(failed.withSource(new StatsService.SourceColumns("anthropic:relay", 1, "relay", BigDecimal.ONE, "AUTO",
            "OPENAI_CHAT", "wire", null, "WITHHELD", "builtin_tool", 60_000L, 4_096L))).block();

        try (Connection db = owner()) {
            Map<String, Object> stored = onlyRow(db, "held-" + suffix);
            assertThat(stored).containsEntry("withheld_input_tokens", 60_000L).containsEntry("withheld_output_tokens", 4_096L)
                .containsEntry("input_tokens", 0L).containsEntry("output_tokens", 0L).containsEntry("stop_reason", "WITHHELD");
            assertThat((BigDecimal) stored.get("credits")).isEqualByComparingTo("0");
        }

        // The user's own log does not select the columns, and nothing the user's statistics sum is more than it was.
        StatsDtos.RequestLogResponse mine = analytics.logsForUser(user, null, null, null, 50).block().items().getFirst();
        assertThat(mine.withheldInputTokens()).isNull();
        assertThat(mine.withheldOutputTokens()).isNull();
        assertThat(mine.inputTokens()).isZero();
        assertThat(mine.outputTokens()).isZero();
        StatsDtos.UsageSummaryResponse usage = analytics.usageSummaryForUser(user).block();
        assertThat(usage.totalRequests()).isEqualTo(1L);
        assertThat(usage.failedRequests()).isEqualTo(1L);
        assertThat(usage.totalInputTokens()).isZero();
        assertThat(usage.totalOutputTokens()).isZero();
        assertThat(usage.totalTokens()).isZero();

        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(new AdminPrincipal("root", "root", "ADMIN", null, true));
        AdminStatsController controller = new AdminStatsController(logs, mock(PerformanceMetricService.class), analytics, adminAuth);
        StatsDtos.RequestLogResponse admin = controller.logs(null).block().data().stream()
            .filter(item -> item.requestId().equals("held-" + suffix)).findFirst().orElseThrow();
        assertThat(admin.withheldInputTokens()).isEqualTo(60_000L);
        assertThat(admin.withheldOutputTokens()).isEqualTo(4_096L);
        assertThat(admin.inputTokens()).isZero();
    }

    // ---- the billing review ----

    @Test
    void aReservationHeldForReviewShowsThePlanAndTheTierItWasLastDispatchedTo() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            apiKeys.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();
            apiKeys.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:kiro").block();
            // A failure that cannot be told from a charge: the reservation is kept for an administrator.
            apiKeys.releaseUsage(caller.principal(), "req-1", false, "Client disconnected").block();

            Map<String, Object> review = review(caller.hash("req-1"));

            assertThat(review).containsEntry("reason", "Client disconnected");
            assertThat((BigDecimal) review.get("reserved_credits")).isEqualByComparingTo("0.05");
            assertThat(review).containsEntry("last_dispatched_tier_ref", "anthropic:kiro").doesNotContainKey("served")
                .doesNotContainKey("served_tier_ref").doesNotContainKey("tier_pricing");
            assertThat(review.get("tier_plan")).isNotNull();
            assertThat(review.get("tier_plan").toString()).contains("holdLevels").contains("anthropic:official");
            @SuppressWarnings("unchecked")
            Map<String, Object> dispatched = (Map<String, Object>) review.get("last_dispatched");
            assertThat(dispatched).containsEntry("tierRef", "anthropic:kiro").containsEntry("level", 2)
                .containsEntry("factor", "0.6").containsEntry("estimate", "0.03");
            assertThat(dispatched.get("at")).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, String> quote = (Map<String, String>) dispatched.get("quote");
            assertThat(quote).containsEntry("input", "0.0000018").containsEntry("output", "0.000009")
                .containsEntry("cacheRead", "0.00000018");
        }
    }

    @Test
    void aReservationThatWasSettledAtATierShowsWhatItWasSettledAt() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "0.05");
            apiKeys.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), PRICING).block();
            apiKeys.markUsageDispatchedTiered(caller.principal(), "req-1", "anthropic:official").block();

            // The charge is more than what is reserved and more than the balance can top up: the reservation is held.
            assertThatThrownBy(() -> apiKeys.accountUsageServed(caller.principal(), "req-1", new BigDecimal("0.5"), 9_000, 2_000,
                "anthropic:kiro", 2, new BigDecimal("0.6")).block()).isInstanceOf(ApiException.class);

            Map<String, Object> review = review(caller.hash("req-1"));

            assertThat(review).containsEntry("served_tier_ref", "anthropic:kiro").containsEntry("served_tier_level", (short) 2);
            @SuppressWarnings("unchecked")
            Map<String, Object> served = (Map<String, Object>) review.get("served");
            assertThat(served).containsEntry("tierRef", "anthropic:kiro").containsEntry("level", 2).containsEntry("factor", "0.6")
                .containsEntry("name", "kiro");
            @SuppressWarnings("unchecked")
            Map<String, Object> dispatched = (Map<String, Object>) review.get("last_dispatched");
            assertThat(dispatched).containsEntry("tierRef", "anthropic:official").containsEntry("factor", "1");
        }
    }

    @Test
    void aReservationOfARequestWithoutTiersComesOutWithTheColumnsItAlwaysHad() throws Exception {
        try (Connection db = owner()) {
            Caller caller = caller(db, "10");
            apiKeys.reserveUsage(caller.principal(), "req-1", new BigDecimal("0.05"), UNTIERED_PRICING).block();
            apiKeys.markUsageDispatched(caller.principal(), "req-1").block();
            apiKeys.releaseUsage(caller.principal(), "req-1", false, "Client disconnected").block();

            Map<String, Object> review = review(caller.hash("req-1"));

            assertThat(review.keySet()).containsExactlyInAnyOrder("request_hash", "request_id", "user_id", "key_id",
                "billing_source", "reserved_credits", "actual_credits", "reason", "created_at");
        }
    }

    // ---- fixtures ----

    private static Map<String, Object> review(String hash) {
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(new AdminPrincipal("root", "root", "ADMIN", null, true));
        List<Map<String, Object>> reviews = new BillingReviewController(databaseClient, adminAuth).reviews(null).block().data();
        return reviews.stream().filter(review -> hash.equals(review.get("request_hash"))).findFirst()
            .orElseThrow(() -> new AssertionError("The reservation is not in the list of reviews: " + reviews));
    }

    private static StatsService.RequestLog row(String requestId, String userId) {
        return new StatsService.RequestLog(requestId, "key-1", userId, "account-1", "/v1/messages", "claude-sonnet-4.5", 200,
            true, 8_000, 1_000, new BigDecimal("0.0234"), 120, null);
    }

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

    private static Map<String, Object> onlyRow(Connection db, String requestId) throws SQLException {
        List<Map<String, Object>> rows = rows(db, "SELECT * FROM request_logs WHERE request_id = ?", requestId);
        assertThat(rows).as(requestId).hasSize(1);
        return rows.getFirst();
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
