package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolPolicyService;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.admin.AdminSessionService;
import tech.starryapi.admin.AppearanceSettingsService;
import tech.starryapi.admin.RuntimeStateInvalidator;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.admin.SystemSettingNotificationListener;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.model.ModelCatalogManagementService;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.proxy.RateLimiterService;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;

/**
 * The registry against the real tables: every column is read with its type, the five tables and the version are read as
 * one snapshot while an administrator's transactions commit around the read, and a change of the version setting
 * reaches the registry through the NOTIFY the database sends.
 */
@Timeout(240)
class SourceTierRegistryPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String DATABASE = "tier_registry";

    private static String jdbcUrl;
    private static SourceTierRegistry registry;

    @BeforeAll
    static void migrateAndConnect() throws Exception {
        createDatabase(DATABASE);
        jdbcUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + DATABASE);
        migrate(jdbcUrl);
        ConnectionFactory connectionFactory = ConnectionFactories.get("r2dbc:postgresql://" + APP_USERNAME + ":" + APP_PASSWORD
            + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DATABASE + "?schema=starry_api");
        registry = new SourceTierRegistry(
            DatabaseClient.create(connectionFactory),
            new R2dbcTransactionManager(connectionFactory),
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction()
        );
    }

    @BeforeEach
    void startFromNothingConfigured() throws Exception {
        try (Connection db = owner()) {
            update(db, "DELETE FROM source_tier_vendors");
            update(db, "UPDATE system_settings SET setting_value = '0' WHERE setting_key = 'source_tier_config_version'");
        }
    }

    // ---- reading ----

    @Test
    void emptyTablesGiveAnEmptySnapshotThatIsLoaded() {
        registry.refresh().block(Duration.ofSeconds(20));

        assertThat(registry.loaded()).isTrue();
        assertThat(registry.snapshot().isEmpty()).isTrue();
        assertThat(registry.snapshot().configVersion()).isZero();
        assertThat(registry.exclusiveXpAccountIds()).isEmpty();
    }

    @Test
    void everyColumnIsReadWithItsType() throws Exception {
        String relay = "acct-" + suffix();
        String pinned = "acct-" + suffix();
        try (Connection db = owner()) {
            update(db, "INSERT INTO source_tier_vendors(vendor, enabled, unify_endpoints, public_listing, sticky_sessions,"
                + " refusal_cap, cross_tier_reserved_slot) VALUES ('anthropic', TRUE, TRUE, TRUE, FALSE, 24, FALSE)");
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name, price_factor, member_categories, user_selectable,"
                + " enabled, description) VALUES ('anthropic', 'relay', 1, 'openai source', 1.25, '{}', FALSE, TRUE, 'first')");
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name, price_factor, member_categories)"
                + " VALUES ('anthropic', 'kiro', 2, 'kiro proxy', 0.600000, ARRAY['KIRO', 'CLAUDE_OAUTH'])");
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name, enabled) VALUES ('anthropic', 'spare', 3, 'spare', FALSE)");
            account(db, relay);
            account(db, pinned);
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, protocol, exclusive, vision, json_schema,"
                + " priority_tier, cache_control_passthrough, upstream_stream, reasoning_mode, max_duration_seconds,"
                + " usage_cache_read_in_prompt, usage_cache_write_in_prompt)"
                + " VALUES ('anthropic', ?, 'relay', 'OPENAI_CHAT', FALSE, TRUE, TRUE, TRUE, TRUE, FALSE, 'EFFORT', 120, TRUE, FALSE)", relay);
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES ('anthropic', ?, 'kiro')", pinned);
            update(db, "INSERT INTO source_tier_member_models(vendor, account_id, model_id, upstream_model, max_context_tokens,"
                + " image_token_cap, max_output_tokens, enabled, verified_at, verified_hash, verify_summary)"
                + " VALUES ('anthropic', ?, 'claude-sonnet-4.5', 'relay/sonnet', 200000, 4096, 8192, TRUE,"
                + " TIMESTAMPTZ '2026-10-24 08:30:00+00', ?, '{\"stream\":{\"ok\":true}}')", relay, "0123456789abcdef".repeat(4));
            update(db, "INSERT INTO source_tier_member_models(vendor, account_id, model_id, upstream_model, max_context_tokens, enabled)"
                + " VALUES ('anthropic', ?, 'claude-haiku-4.5', 'relay/haiku', 100000, FALSE)", relay);
            update(db, "INSERT INTO source_tier_pair_gates(vendor, source_protocol, entry_endpoint, state, allow_key_ids)"
                + " VALUES ('anthropic', 'OPENAI_CHAT', 'MESSAGES', 'KEYS', ARRAY['key-1', 'key-2'])");
            update(db, "INSERT INTO source_tier_pair_gates(vendor, entry_endpoint, state) VALUES ('anthropic', 'CHAT', 'OFF')");
            update(db, "UPDATE system_settings SET setting_value = '42' WHERE setting_key = 'source_tier_config_version'");
        }

        registry.refresh().block(Duration.ofSeconds(20));
        SourceTierSnapshot snapshot = registry.snapshot();

        assertThat(snapshot.configVersion()).isEqualTo(42L);
        assertThat(snapshot.isEmpty()).isFalse();
        assertThat(snapshot.vendor("anthropic")).hasValueSatisfying(vendor -> {
            assertThat(vendor.enabled()).isTrue();
            assertThat(vendor.unifyEndpoints()).isTrue();
            assertThat(vendor.publicListing()).isTrue();
            assertThat(vendor.stickySessions()).isFalse();
            assertThat(vendor.refusalCap()).isEqualTo(24);
            assertThat(vendor.crossTierReservedSlot()).isFalse();
            assertThat(vendor.version()).isEqualTo(1L);
        });
        assertThat(snapshot.tiers("anthropic")).extracting(SourceTierSnapshot.Tier::tierId).containsExactly("relay", "kiro", "spare");
        SourceTierSnapshot.Tier relayTier = snapshot.tier("anthropic", "relay").orElseThrow();
        assertThat(relayTier.level()).isEqualTo(1);
        assertThat(relayTier.name()).isEqualTo("openai source");
        assertThat(relayTier.priceFactor()).isEqualByComparingTo(new BigDecimal("1.25"));
        assertThat(relayTier.memberCategories()).isEmpty();
        assertThat(relayTier.userSelectable()).isFalse();
        assertThat(relayTier.enabled()).isTrue();
        assertThat(relayTier.description()).isEqualTo("first");
        SourceTierSnapshot.Tier kiroTier = snapshot.tier("anthropic", "kiro").orElseThrow();
        assertThat(kiroTier.priceFactor()).isEqualByComparingTo(new BigDecimal("0.6"));
        assertThat(kiroTier.memberCategories()).containsExactlyInAnyOrder(AccountAllocationCategory.KIRO,
            AccountAllocationCategory.CLAUDE_OAUTH);
        assertThat(kiroTier.userSelectable()).isTrue();
        assertThat(kiroTier.description()).isNull();
        assertThat(snapshot.tier("anthropic", "spare").orElseThrow().enabled()).isFalse();

        SourceTierSnapshot.Member relayMember = snapshot.member("anthropic", relay).orElseThrow();
        assertThat(relayMember.tierId()).isEqualTo("relay");
        assertThat(relayMember.protocol()).isEqualTo("OPENAI_CHAT");
        assertThat(relayMember.crossProtocol()).isTrue();
        assertThat(relayMember.exclusive()).isFalse();
        assertThat(relayMember.vision()).isTrue();
        assertThat(relayMember.jsonSchema()).isTrue();
        assertThat(relayMember.priorityTier()).isTrue();
        assertThat(relayMember.cacheControlPassthrough()).isTrue();
        assertThat(relayMember.upstreamStream()).isFalse();
        assertThat(relayMember.reasoningMode()).isEqualTo("EFFORT");
        assertThat(relayMember.maxDurationSeconds()).isEqualTo(120);
        assertThat(relayMember.usageCacheReadInPrompt()).isTrue();
        assertThat(relayMember.usageCacheWriteInPrompt()).isFalse();
        SourceTierSnapshot.Member pinnedMember = snapshot.member("anthropic", pinned).orElseThrow();
        assertThat(pinnedMember.protocol()).isNull();
        assertThat(pinnedMember.crossProtocol()).isFalse();
        assertThat(pinnedMember.exclusive()).isTrue();
        assertThat(pinnedMember.upstreamStream()).isTrue();
        assertThat(pinnedMember.reasoningMode()).isEqualTo("NONE");
        assertThat(pinnedMember.maxDurationSeconds()).isEqualTo(540);
        assertThat(pinnedMember.usageCacheReadInPrompt()).isNull();
        assertThat(pinnedMember.usageCacheWriteInPrompt()).isNull();

        assertThat(snapshot.models("anthropic", relay)).hasSize(2);
        SourceTierSnapshot.MemberModel sonnet = snapshot.models("anthropic", relay).stream()
            .filter(model -> model.modelId().equals("claude-sonnet-4.5")).findFirst().orElseThrow();
        assertThat(sonnet.upstreamModel()).isEqualTo("relay/sonnet");
        assertThat(sonnet.maxContextTokens()).isEqualTo(200_000);
        assertThat(sonnet.imageTokenCap()).isEqualTo(4096);
        assertThat(sonnet.maxOutputTokens()).isEqualTo(8192);
        assertThat(sonnet.enabled()).isTrue();
        assertThat(sonnet.verifiedAt()).isEqualTo(Instant.parse("2026-10-24T08:30:00Z"));
        assertThat(sonnet.verifiedHash()).isEqualTo("0123456789abcdef".repeat(4));
        SourceTierSnapshot.MemberModel haiku = snapshot.models("anthropic", relay).stream()
            .filter(model -> model.modelId().equals("claude-haiku-4.5")).findFirst().orElseThrow();
        assertThat(haiku.imageTokenCap()).isEqualTo(8192);
        assertThat(haiku.maxOutputTokens()).as("a mapping that states none").isNull();
        assertThat(haiku.enabled()).isFalse();
        assertThat(haiku.verifiedAt()).isNull();
        assertThat(haiku.verifiedHash()).isNull();

        assertThat(snapshot.gates("anthropic")).hasSize(2);
        SourceTierSnapshot.PairGate messages = snapshot.gates("anthropic").stream()
            .filter(gate -> gate.entryEndpoint().equals("MESSAGES")).findFirst().orElseThrow();
        assertThat(messages.sourceProtocol()).isEqualTo("OPENAI_CHAT");
        assertThat(messages.state()).isEqualTo("KEYS");
        assertThat(messages.allowKeyIds()).containsExactly("key-1", "key-2");
        SourceTierSnapshot.PairGate chat = snapshot.gates("anthropic").stream()
            .filter(gate -> gate.entryEndpoint().equals("CHAT")).findFirst().orElseThrow();
        assertThat(chat.state()).isEqualTo("OFF");
        assertThat(chat.allowKeyIds()).isEmpty();

        assertThat(snapshot.exclusiveXpAccountIds()).isEmpty();
        ProxyAccountEntity kiroAccount = new ProxyAccountEntity();
        kiroAccount.setAccountId("some-kiro");
        kiroAccount.setProvider("BuilderId");
        kiroAccount.setAuthMethod("idc");
        assertThat(registry.tierOf("anthropic", kiroAccount)).hasValueSatisfying(tier -> assertThat(tier.tierId()).isEqualTo("kiro"));
    }

    @Test
    void anExclusiveRelayShowsUpInTheExclusionSet() throws Exception {
        String relay = "acct-" + suffix();
        try (Connection db = owner()) {
            update(db, "INSERT INTO source_tier_vendors(vendor) VALUES ('anthropic')");
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES ('anthropic', 'relay', 1, 'relay')");
            account(db, relay);
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, protocol, usage_cache_read_in_prompt,"
                + " usage_cache_write_in_prompt) VALUES ('anthropic', ?, 'relay', 'OPENAI_CHAT', TRUE, TRUE)", relay);
        }

        registry.refresh().block(Duration.ofSeconds(20));

        assertThat(registry.exclusiveXpAccountIds()).containsExactly(relay);
    }

    @Test
    void aVersionThatIsNotANumberReadsAsZero() throws Exception {
        try (Connection db = owner()) {
            update(db, "INSERT INTO source_tier_vendors(vendor) VALUES ('anthropic')");
            update(db, "UPDATE system_settings SET setting_value = 'not-a-number' WHERE setting_key = 'source_tier_config_version'");
        }

        registry.refresh().block(Duration.ofSeconds(20));

        assertThat(registry.snapshot().configVersion()).isZero();
        assertThat(registry.snapshot().vendor("anthropic")).isPresent();
    }

    // ---- consistency ----

    @Test
    void aSnapshotIsNeverAMixOfTwoCommits() throws Exception {
        String first = "acct-" + suffix();
        String second = "acct-" + suffix();
        try (Connection db = owner()) {
            account(db, first);
            account(db, second);
            replaceChurnConfiguration(db, first, second, 0);
        }
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try {
            Future<?> writing = writer.submit(() -> {
                try (Connection db = owner()) {
                    for (int version = 1; version <= 300; version++) {
                        replaceChurnConfiguration(db, first, second, version);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });

            int reads = 0;
            long lastVersion = -1;
            while (!writing.isDone() || reads < 20) {
                registry.refresh().block(Duration.ofSeconds(20));
                SourceTierSnapshot snapshot = registry.snapshot();
                String prefix = snapshot.configVersion() % 2 == 0 ? "even" : "odd";
                assertThat(snapshot.tiers("churn")).as("tiers at version %d", snapshot.configVersion())
                    .extracting(SourceTierSnapshot.Tier::tierId).containsExactly(prefix + "-1", prefix + "-2");
                assertThat(snapshot.member("churn", first).orElseThrow().tierId()).isEqualTo(prefix + "-1");
                assertThat(snapshot.member("churn", second).orElseThrow().tierId()).isEqualTo(prefix + "-2");
                assertThat(snapshot.models("churn", first)).as("models at version %d", snapshot.configVersion()).hasSize(1);
                assertThat(snapshot.models("churn", first).getFirst().modelId()).isEqualTo(prefix + "-model");
                assertThat(snapshot.vendor("churn").orElseThrow().version()).isEqualTo(snapshot.configVersion() + 1);
                assertThat(snapshot.configVersion()).isGreaterThanOrEqualTo(lastVersion);
                lastVersion = snapshot.configVersion();
                reads++;
            }
            writing.get(30, TimeUnit.SECONDS);
            assertThat(reads).isGreaterThan(20);
        } finally {
            writer.shutdownNow();
        }
    }

    /** One administrator write: the whole configuration of a vendor replaced and the version bumped, in one commit. */
    private static void replaceChurnConfiguration(Connection db, String first, String second, int version) throws SQLException {
        String prefix = version % 2 == 0 ? "even" : "odd";
        db.setAutoCommit(false);
        try {
            update(db, "DELETE FROM source_tier_vendors WHERE vendor = 'churn'");
            update(db, "INSERT INTO source_tier_vendors(vendor, enabled, version) VALUES ('churn', TRUE, ?)", version + 1L);
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES ('churn', ?, 1, ?), ('churn', ?, 2, ?)",
                prefix + "-1", prefix + "-1", prefix + "-2", prefix + "-2");
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES ('churn', ?, ?), ('churn', ?, ?)",
                first, prefix + "-1", second, prefix + "-2");
            update(db, "INSERT INTO source_tier_member_models(vendor, account_id, model_id, upstream_model, max_context_tokens)"
                + " VALUES ('churn', ?, ?, 'wire', 1000)", first, prefix + "-model");
            update(db, "UPDATE system_settings SET setting_value = ? WHERE setting_key = 'source_tier_config_version'",
                String.valueOf(version));
            db.commit();
        } catch (SQLException | RuntimeException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }

    // ---- NOTIFY ----

    @Test
    void aChangeOfTheVersionSettingReachesTheRegistryThroughNotify() throws Exception {
        registry.refresh().block(Duration.ofSeconds(20));
        assertThat(registry.snapshot().isEmpty()).isTrue();
        RuntimeStateInvalidator invalidator = spy(invalidatorAround(registry));
        SystemSettingNotificationListener listener = new SystemSettingNotificationListener(r2dbcProperties(), invalidator);
        listener.start();
        try {
            awaitListening();
            // The reconnect refresh of a new listener has been asked for; what follows must come from the NOTIFY.
            verify(invalidator, timeout(10_000)).invalidateAll();
            Thread.sleep(500);

            try (Connection db = owner()) {
                db.setAutoCommit(false);
                update(db, "INSERT INTO source_tier_vendors(vendor, enabled) VALUES ('openai', TRUE)");
                update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name, member_categories)"
                    + " VALUES ('openai', 'official', 1, 'official', ARRAY['OFFICIAL_API_KEY'])");
                update(db, "UPDATE system_settings SET setting_value = '11' WHERE setting_key = 'source_tier_config_version'");
                db.commit();
            }

            verify(invalidator, timeout(10_000)).invalidateSetting("source_tier_config_version");
            awaitSnapshot(11L);
            assertThat(registry.snapshot().tiers("openai")).extracting(SourceTierSnapshot.Tier::tierId).containsExactly("official");

            try (Connection db = owner()) {
                update(db, "DELETE FROM source_tier_vendors WHERE vendor = 'openai'");
                update(db, "UPDATE system_settings SET setting_value = '12' WHERE setting_key = 'source_tier_config_version'");
            }
            awaitSnapshot(12L);
            assertThat(registry.snapshot().isEmpty()).isTrue();
        } finally {
            listener.stop();
        }
    }

    private static void awaitSnapshot(long version) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (registry.snapshot().configVersion() != version && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(registry.snapshot().configVersion()).isEqualTo(version);
    }

    private static void awaitListening() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection db = owner();
                 PreparedStatement statement = db.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM pg_catalog.pg_stat_activity
                         WHERE datname = ? AND usename = ? AND state = 'idle' AND query = 'LISTEN starry_api_system_settings'
                     )
                     """)) {
                statement.setString(1, DATABASE);
                statement.setString(2, APP_USERNAME);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The notification listener never started listening");
    }

    /** The real invalidator around the registry under test; every other cache it knows is a stub that does nothing. */
    private static RuntimeStateInvalidator invalidatorAround(SourceTierRegistry sourceTiers) {
        AccountPoolService accountPool = mock(AccountPoolService.class);
        AccountPoolPolicyService poolPolicy = mock(AccountPoolPolicyService.class);
        ModelMappingService modelMappings = mock(ModelMappingService.class);
        ModelCatalogManagementService modelCatalog = mock(ModelCatalogManagementService.class);
        ModelPricingService modelPricing = mock(ModelPricingService.class);
        AppearanceSettingsService appearance = mock(AppearanceSettingsService.class);
        when(accountPool.refreshCache()).thenReturn(Mono.empty());
        when(poolPolicy.refreshSettings()).thenReturn(Mono.empty());
        when(modelMappings.refresh()).thenReturn(Mono.empty());
        when(modelCatalog.refresh()).thenReturn(Mono.empty());
        when(modelPricing.refresh()).thenReturn(Mono.empty());
        when(appearance.settings()).thenReturn(Mono.empty());
        return new RuntimeStateInvalidator(
            mock(AdminSessionService.class),
            accountPool,
            poolPolicy,
            mock(AccountSettingsService.class),
            mock(PaymentSettingsService.class),
            modelMappings,
            modelCatalog,
            modelPricing,
            mock(KiroUpstreamClient.class),
            mock(RateLimiterService.class),
            appearance,
            sourceTiers
        );
    }

    private static R2dbcProperties r2dbcProperties() {
        R2dbcProperties properties = new R2dbcProperties();
        properties.setUrl("r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DATABASE
            + "?schema=starry_api");
        properties.setUsername(APP_USERNAME);
        properties.setPassword(APP_PASSWORD);
        return properties;
    }

    // ---- database ----

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void account(Connection db, String accountId) throws SQLException {
        update(db, "INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, base_url) VALUES (?, 'token',"
            + " 'OpenAI', 'api-key', 'https://relay.example/v1')", accountId);
    }

    private static Connection owner() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, MIGRATION_USERNAME, MIGRATION_PASSWORD);
        try (var statement = connection.createStatement()) {
            statement.execute("SET ROLE starry_api_owner");
            statement.execute("SET search_path TO starry_api");
        }
        return connection;
    }

    private static void update(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static void createDatabase(String name) throws Exception {
        // Each -c is its own transaction, which CREATE DATABASE needs.
        exec("psql", "-U", "starry_api_bootstrap", "-d", "starry_api", "-v", "ON_ERROR_STOP=1",
            "-c", "CREATE DATABASE " + name + " OWNER starry_api_owner",
            "-c", "REVOKE ALL ON DATABASE " + name + " FROM PUBLIC",
            "-c", "GRANT CONNECT ON DATABASE " + name + " TO starry_api_migration, starry_api_runtime");
        exec("psql", "-U", "starry_api_bootstrap", "-d", name, "-v", "ON_ERROR_STOP=1", "-c", """
            CREATE SCHEMA starry_api AUTHORIZATION starry_api_owner;
            REVOKE ALL ON SCHEMA starry_api FROM PUBLIC;
            GRANT USAGE ON SCHEMA starry_api TO starry_api_runtime;
            REVOKE ALL ON SCHEMA public FROM PUBLIC;
            """);
    }

    private static void migrate(String url) {
        Flyway.configure().dataSource(url, MIGRATION_USERNAME, MIGRATION_PASSWORD)
            .locations("classpath:db/migration").schemas("starry_api").defaultSchema("starry_api").createSchemas(false)
            .initSql("SET ROLE starry_api_owner")
            .load().migrate();
    }

    private static void exec(String... command) throws Exception {
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
    }
}
