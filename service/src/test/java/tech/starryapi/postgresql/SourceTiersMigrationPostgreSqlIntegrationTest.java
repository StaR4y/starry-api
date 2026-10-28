package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.*;

import io.r2dbc.spi.ConnectionFactories;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;

/**
 * V29 (tiered source pools): the five tables with their constraints and cascades, the columns that carry the serving
 * tier through the reservation, the usage event and the request log, the settlement wrapper called the way the runtime
 * calls it, the usage-event trigger, and the move to V29 from a V28 database that already holds data.
 */
@Timeout(240)
class SourceTiersMigrationPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String FRESH = "tiers_fresh";
    private static final String UPGRADED = "tiers_upgraded";
    private static final String WIRE_FUNCTION = "starry_api.settle_api_usage_served";

    private static String freshUrl;
    private static DatabaseClient runtime;

    @BeforeAll
    static void migrateAFreshDatabase() throws Exception {
        createDatabase(FRESH);
        freshUrl = jdbcUrl(FRESH);
        migrate(freshUrl, null);
        runtime = runtimeClient(FRESH);
    }

    // ---- tables ----

    @Test
    void aFreshDatabaseHasTheTablesColumnsAndDefaultsOfTheDesign() throws Exception {
        try (Connection db = owner(freshUrl)) {
            assertThat(rows(db, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'starry_api' AND table_name LIKE 'source\\_tier%' ORDER BY table_name
                """)).extracting(row -> row.get("table_name")).containsExactlyInAnyOrder(
                "source_tier_member_models", "source_tier_members", "source_tier_pair_gates", "source_tier_vendors",
                "source_tiers");

            assertThat(columns(db, "request_logs")).contains("source_tier_ref", "source_tier_level", "source_tier_name",
                "price_factor", "tier_selection", "source_dispatch", "wire_model", "usage_source", "stop_reason",
                "degraded_features", "withheld_input_tokens", "withheld_output_tokens");
            assertThat(columns(db, "billing_reservations")).contains("served_tier_ref", "served_tier_level",
                "served_price_factor", "last_dispatched_tier_ref", "last_dispatched_at");
            assertThat(columns(db, "api_usage_events")).contains("source_tier_ref", "price_factor");
            assertThat(columns(db, "api_keys")).contains("tier_preference");
            assertThat(scalar(db, "SELECT setting_value FROM system_settings WHERE setting_key = 'source_tier_config_version'"))
                .isEqualTo("0");
            assertThat(scalar(db, "SELECT success FROM flyway_schema_history WHERE version = '29'")).isEqualTo(true);
            assertThat(scalar(db, "SELECT success FROM flyway_schema_history WHERE version = '31'")).isEqualTo(true);

            String vendor = vendor(db);
            Map<String, Object> vendorRow = rows(db, "SELECT * FROM source_tier_vendors WHERE vendor = ?", vendor).getFirst();
            assertThat(vendorRow).containsEntry("enabled", false).containsEntry("unify_endpoints", false)
                .containsEntry("public_listing", false).containsEntry("sticky_sessions", true)
                .containsEntry("refusal_cap", 16).containsEntry("cross_tier_reserved_slot", true)
                .containsEntry("version", 1L);

            tier(db, vendor, "official", 1, "official");
            Map<String, Object> tierRow = rows(db, "SELECT * FROM source_tiers WHERE vendor = ?", vendor).getFirst();
            assertThat((BigDecimal) tierRow.get("price_factor")).isEqualByComparingTo("1");
            assertThat(tierRow).containsEntry("user_selectable", true).containsEntry("enabled", true);
            assertThat(scalar(db, "SELECT cardinality(member_categories) FROM source_tiers WHERE vendor = ?", vendor)).isEqualTo(0);

            String account = account(db);
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES (?, ?, 'official')", vendor, account);
            Map<String, Object> member = rows(db, "SELECT * FROM source_tier_members WHERE vendor = ?", vendor).getFirst();
            assertThat(member).containsEntry("protocol", null).containsEntry("exclusive", true)
                .containsEntry("vision", false).containsEntry("json_schema", false).containsEntry("priority_tier", false)
                .containsEntry("cache_control_passthrough", false).containsEntry("upstream_stream", true)
                .containsEntry("reasoning_mode", "NONE").containsEntry("max_duration_seconds", 540)
                .containsEntry("usage_cache_read_in_prompt", null).containsEntry("usage_cache_write_in_prompt", null);

            relayMember(db, vendor, "official", account);
            update(db, "INSERT INTO source_tier_member_models(vendor, account_id, model_id, upstream_model, max_context_tokens)"
                + " VALUES (?, ?, 'claude-sonnet-4.5', 'relay/sonnet', 200000)", vendor, account);
            Map<String, Object> model = rows(db, "SELECT * FROM source_tier_member_models WHERE vendor = ?", vendor).getFirst();
            assertThat(model).containsEntry("image_token_cap", 8192).containsEntry("enabled", true)
                .containsEntry("verified_at", null).containsEntry("verified_hash", null);
            assertThat(scalar(db, "SELECT verify_summary::text FROM source_tier_member_models WHERE vendor = ?", vendor))
                .isEqualTo("{}");

            update(db, "INSERT INTO source_tier_pair_gates(vendor, entry_endpoint) VALUES (?, 'MESSAGES')", vendor);
            Map<String, Object> gate = rows(db, "SELECT * FROM source_tier_pair_gates WHERE vendor = ?", vendor).getFirst();
            assertThat(gate).containsEntry("source_protocol", "OPENAI_CHAT").containsEntry("state", "OFF");
            assertThat(scalar(db, "SELECT cardinality(allow_key_ids) FROM source_tier_pair_gates WHERE vendor = ?", vendor))
                .isEqualTo(0);

            Billing billing = billingUser(db, "5");
            assertThat(scalar(db, "SELECT tier_preference FROM api_keys WHERE key_id = ?", billing.keyId())).isEqualTo("auto");
            assertThat(reservation(db, reserve(db, billing, "1", "{}"))).containsEntry("served_tier_ref", null)
                .containsEntry("served_tier_level", null).containsEntry("served_price_factor", null)
                .containsEntry("last_dispatched_tier_ref", null).containsEntry("last_dispatched_at", null);
        }
    }

    @Test
    void vendorAndTierConstraintsRejectInvalidRows() throws Exception {
        try (Connection db = owner(freshUrl)) {
            String vendor = vendor(db);
            assertRejected(db, "INSERT INTO source_tier_vendors(vendor, refusal_cap) VALUES (?, 3)", "23514",
                "ck_source_tier_vendors_refusal_cap", "v-" + suffix());
            assertRejected(db, "INSERT INTO source_tier_vendors(vendor, refusal_cap) VALUES (?, 65)", "23514",
                "ck_source_tier_vendors_refusal_cap", "v-" + suffix());
            assertRejected(db, "INSERT INTO source_tier_vendors(vendor) VALUES ('  ')", "23514", "ck_source_tier_vendors_vendor");
            assertRejected(db, "INSERT INTO source_tier_vendors(vendor) VALUES (?)", "23505", null, vendor);
            update(db, "INSERT INTO source_tier_vendors(vendor, refusal_cap) VALUES (?, 4)", "v-" + suffix());
            update(db, "INSERT INTO source_tier_vendors(vendor, refusal_cap) VALUES (?, 64)", "v-" + suffix());

            // A tier id must not read as a selector: auto, L2, a bare number, and it must be a lower-case slug.
            for (String id : List.of("auto", "l2", "2", "42", "L", "Official", "-x", "x_y", "x y", "")) {
                assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, ?, 1, ?)",
                    "23514", "ck_source_tiers_tier_id", vendor, id, "name-" + suffix());
            }
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, ?, 1, ?)", "22001", null,
                vendor, "a".repeat(33), "name-" + suffix());
            int level = 1;
            for (String id : List.of("relay", "l", "l-2", "x2", "9a", "a".repeat(32))) {
                update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, ?, ?, ?)",
                    vendor, id, level, "n-" + level);
                level++;
            }
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'lv0', 0, 'zero')", "23514",
                "ck_source_tiers_level", vendor);
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'lv10', 10, 'ten')", "23514",
                "ck_source_tiers_level", vendor);
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'lv9', 9, 'nine')", vendor);
            update(db, "DELETE FROM source_tiers WHERE vendor = ? AND tier_id = 'lv9'", vendor);
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'blank', 8, '  ')", "23514",
                "ck_source_tiers_name", vendor);
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'null-name', 8, NULL)", "23502",
                null, vendor);
            // The name is 32 characters, not 32 bytes.
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'cjk', 8, ?)", vendor, "\u4e2d".repeat(32));
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'cjk-long', 7, ?)", "22001", null,
                vendor, "\u4e2d".repeat(33));

            for (String factor : List.of("0", "-0.5", "1000.000001", "1001")) {
                assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name, price_factor) VALUES (?, 'pf', 7, 'pf', ?::numeric)",
                    "23514", "ck_source_tiers_price_factor", vendor, factor);
            }
            for (String factor : List.of("0.000001", "1000", "0.6")) {
                String id = "pf-" + factor.replace(".", "-");
                update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name, price_factor) VALUES (?, ?, 7, ?, ?::numeric)",
                    vendor, id, id, factor);
                update(db, "DELETE FROM source_tiers WHERE vendor = ? AND tier_id = ?", vendor, id);
            }
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name, member_categories) VALUES (?, 'bad-cat', 7, 'bad-cat',"
                + " ARRAY['KIRO', 'BOGUS'])", "23514", "ck_source_tiers_member_categories", vendor);
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name, member_categories) VALUES (?, 'all-cat', 7, 'all-cat',"
                + " ARRAY['OFFICIAL_API_KEY', 'COMPATIBLE_API_KEY', 'CODEX', 'CLAUDE_OAUTH', 'KIRO'])", vendor);
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'dup-level', 1, 'dup-level')",
                "23505", "uq_source_tiers_level", vendor);
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'dup-name', 9, 'n-1')",
                "23505", "uq_source_tiers_name", vendor);
            assertRejected(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES ('no-such-vendor', 'x', 1, 'x')",
                "23503", null);
            // Another vendor may reuse ids, levels and names.
            String other = vendor(db);
            update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'relay', 1, 'n-1')", other);
        }
    }

    @Test
    void memberModelAndGateConstraintsRejectInvalidRows() throws Exception {
        try (Connection db = owner(freshUrl)) {
            String vendor = vendor(db);
            tier(db, vendor, "relay", 1, "relay");
            tier(db, vendor, "kiro", 2, "kiro");
            String account = account(db);

            assertRejected(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES (?, ?, 'missing')", "23503",
                null, vendor, account);
            assertRejected(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES (?, 'no-such-account', 'relay')",
                "23503", null, vendor);
            assertRejected(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, protocol) VALUES (?, ?, 'relay', 'BOGUS')",
                "23514", "ck_source_tier_members_protocol", vendor, account);
            // A cross-protocol member must state both cache bases; a native override row need not.
            for (String flags : List.of("NULL, NULL", "TRUE, NULL", "NULL, FALSE")) {
                assertRejected(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, protocol,"
                    + " usage_cache_read_in_prompt, usage_cache_write_in_prompt) VALUES (?, ?, 'relay', 'OPENAI_CHAT', " + flags + ")",
                    "23514", "ck_source_tier_members_usage_basis", vendor, account);
            }
            assertRejected(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, reasoning_mode) VALUES (?, ?, 'relay', 'HIGH')",
                "23514", "ck_source_tier_members_reasoning_mode", vendor, account);
            for (int seconds : List.of(29, 901, 0, -1)) {
                assertRejected(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, max_duration_seconds) VALUES (?, ?, 'relay', ?)",
                    "23514", "ck_source_tier_members_max_duration", vendor, account, seconds);
            }
            // The protocol column is wide enough for every later stage even though only OPENAI_CHAT is implemented.
            for (String protocol : List.of("OPENAI_CHAT", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES")) {
                String other = account(db);
                update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, protocol, usage_cache_read_in_prompt,"
                    + " usage_cache_write_in_prompt, max_duration_seconds) VALUES (?, ?, 'relay', ?, TRUE, FALSE, 30)", vendor, other, protocol);
            }
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, max_duration_seconds) VALUES (?, ?, 'relay', 900)",
                vendor, account);
            assertRejected(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES (?, ?, 'kiro')", "23505", null,
                vendor, account);
            // The same account may belong to another vendor.
            String otherVendor = vendor(db);
            tier(db, otherVendor, "relay", 1, "relay");
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES (?, ?, 'relay')", otherVendor, account);

            String model = "INSERT INTO source_tier_member_models(vendor, account_id, model_id, upstream_model, max_context_tokens%s)"
                + " VALUES (?, ?, ?, ?, ?%s)";
            String relay = account(db);
            relayMember(db, vendor, "kiro", relay);
            for (String upstream : List.of("", "-bad", ".bad", "has space", "semi;colon", "quote'd", "bad\"", "a".repeat(256))) {
                String sqlState = upstream.length() > 255 ? "22001" : "23514";
                assertRejected(db, model.formatted("", ""), sqlState, sqlState.equals("23514") ? "ck_source_tier_member_models_upstream_model" : null,
                    vendor, relay, "m-" + suffix(), upstream, 1000);
            }
            for (String upstream : List.of("a", "a".repeat(255), "org/model:v1@2+x.y_z-1", "9", "Claude-Sonnet-4.5")) {
                String id = "ok-" + suffix();
                update(db, model.formatted("", ""), vendor, relay, id, upstream, 1000);
            }
            for (int tokens : List.of(0, -5)) {
                assertRejected(db, model.formatted("", ""), "23514", "ck_source_tier_member_models_context", vendor, relay,
                    "m-" + suffix(), "wire", tokens);
            }
            assertRejected(db, model.formatted("", ""), "23502", null, vendor, relay, "m-" + suffix(), "wire", null);
            for (int cap : List.of(0, 1000001)) {
                assertRejected(db, model.formatted(", image_token_cap", ", ?"), "23514", "ck_source_tier_member_models_image_cap",
                    vendor, relay, "m-" + suffix(), "wire", 1000, cap);
            }
            update(db, model.formatted(", image_token_cap", ", ?"), vendor, relay, "cap-low", "wire", 1000, 1);
            update(db, model.formatted(", image_token_cap", ", ?"), vendor, relay, "cap-high", "wire", 1000, 1000000);
            assertRejected(db, model.formatted("", ""), "23514", "ck_source_tier_member_models_model_id", vendor, relay, "  ", "wire", 1000);
            for (String hash : List.of("xyz", "A".repeat(64), "a".repeat(63))) {
                assertRejected(db, model.formatted(", verified_hash", ", ?"), "23514", "ck_source_tier_member_models_verified_hash",
                    vendor, relay, "m-" + suffix(), "wire", 1000, hash);
            }
            update(db, model.formatted(", verified_hash", ", ?"), vendor, relay, "hashed", "wire", 1000, "0123456789abcdef".repeat(4));
            for (String summary : List.of("[]", "null", "1", "\"x\"")) {
                assertRejected(db, model.formatted(", verify_summary", ", ?::jsonb"), "23514", "ck_source_tier_member_models_summary",
                    vendor, relay, "m-" + suffix(), "wire", 1000, summary);
            }
            update(db, model.formatted(", verify_summary", ", ?::jsonb"), vendor, relay, "summarised", "wire", 1000,
                "{\"stream\":{\"ok\":true}}");
            update(db, model.formatted("", ""), vendor, relay, "Claude-X", "wire", 1000);
            assertRejected(db, model.formatted("", ""), "23505", "uq_source_tier_member_models_lower", vendor, relay, "claude-x", "wire", 1000);
            assertRejected(db, model.formatted("", ""), "23505", null, vendor, relay, "Claude-X", "wire", 1000);
            assertRejected(db, model.formatted("", ""), "23503", null, vendor, account(db), "orphan", "wire", 1000);
            assertRejected(db, model.formatted("", ""), "23503", null, vendor, "no-such-account", "orphan", "wire", 1000);
            // The model id may be as long as the longest id the proxy accepts.
            update(db, model.formatted("", ""), vendor, relay, "m".repeat(256), "wire", 1000);
            assertRejected(db, model.formatted("", ""), "22001", null, vendor, relay, "m".repeat(257), "wire", 1000);

            String gate = "INSERT INTO source_tier_pair_gates(vendor, source_protocol, entry_endpoint, state) VALUES (?, ?, ?, ?)";
            assertRejected(db, gate, "23514", "ck_source_tier_pair_gates_protocol", vendor, "BOGUS", "CHAT", "OFF");
            assertRejected(db, gate, "23514", "ck_source_tier_pair_gates_endpoint", vendor, "OPENAI_CHAT", "FOO", "OFF");
            assertRejected(db, gate, "23514", "ck_source_tier_pair_gates_state", vendor, "OPENAI_CHAT", "CHAT", "MAYBE");
            assertRejected(db, gate, "23503", null, "no-such-vendor", "OPENAI_CHAT", "CHAT", "OFF");
            for (String protocol : List.of("OPENAI_CHAT", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES")) {
                for (String endpoint : List.of("CHAT", "MESSAGES", "RESPONSES", "GEMINI")) {
                    update(db, gate, vendor, protocol, endpoint, "KEYS");
                }
            }
            assertRejected(db, gate, "23505", null, vendor, "OPENAI_CHAT", "CHAT", "ON");
            update(db, "UPDATE source_tier_pair_gates SET state = 'ON' WHERE vendor = ?", vendor);
            update(db, "UPDATE source_tier_pair_gates SET allow_key_ids = (SELECT array_agg('key-' || n) FROM generate_series(1, 50) n)"
                + " WHERE vendor = ?", vendor);
            assertRejected(db, "UPDATE source_tier_pair_gates SET allow_key_ids = (SELECT array_agg('key-' || n) FROM generate_series(1, 51) n)"
                + " WHERE vendor = ?", "23514", "ck_source_tier_pair_gates_keys", vendor);
        }
    }

    @Test
    void theApiKeyTierPreferenceAcceptsOnlyTheSelectorSyntax() throws Exception {
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "1");
            for (String preference : List.of("auto", "kiro", "kiro+", "relay-2", "L2", "l2", "L2+", "3", "3+", "l10", "a".repeat(32))) {
                update(db, "UPDATE api_keys SET tier_preference = ? WHERE key_id = ?", preference, billing.keyId());
            }
            for (String preference : List.of("", "AUTO", "Kiro", "L0", "L10", "2++", "+", "a b", "a_b", "-x", "a".repeat(33), "L2 ")) {
                assertRejected(db, "UPDATE api_keys SET tier_preference = ? WHERE key_id = ?", "23514", "ck_api_keys_tier_preference",
                    preference, billing.keyId());
            }
            assertRejected(db, "UPDATE api_keys SET tier_preference = NULL WHERE key_id = ?", "23502", null, billing.keyId());
            update(db, "UPDATE api_keys SET tier_preference = 'auto' WHERE key_id = ?", billing.keyId());
        }
    }

    @Test
    void deletingAParentRemovesWhatDependsOnIt() throws Exception {
        try (Connection db = owner(freshUrl)) {
            String vendor = vendor(db);
            tier(db, vendor, "relay", 1, "relay");
            tier(db, vendor, "kiro", 2, "kiro");
            String relayAccount = account(db);
            String nativeAccount = account(db);
            relayMember(db, vendor, "relay", relayAccount);
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES (?, ?, 'kiro')", vendor, nativeAccount);
            model(db, vendor, relayAccount, "claude-sonnet-4.5");
            model(db, vendor, relayAccount, "claude-haiku-4.5");
            update(db, "INSERT INTO source_tier_pair_gates(vendor, entry_endpoint, state) VALUES (?, 'MESSAGES', 'KEYS')", vendor);

            // A member and its mappings go with the account's removal from the pool ...
            update(db, "DELETE FROM source_tier_members WHERE vendor = ? AND account_id = ?", vendor, relayAccount);
            assertThat(count(db, "source_tier_member_models", vendor)).isZero();
            relayMember(db, vendor, "relay", relayAccount);
            model(db, vendor, relayAccount, "claude-sonnet-4.5");

            // ... with the tier ...
            update(db, "DELETE FROM source_tiers WHERE vendor = ? AND tier_id = 'relay'", vendor);
            assertThat(count(db, "source_tier_members", vendor)).isEqualTo(1);
            assertThat(count(db, "source_tier_member_models", vendor)).isZero();
            assertThat(count(db, "source_tiers", vendor)).isEqualTo(1);

            // ... with the account ...
            relayMember(db, vendor, "kiro", relayAccount);
            model(db, vendor, relayAccount, "claude-sonnet-4.5");
            String otherVendor = vendor(db);
            tier(db, otherVendor, "main", 1, "main");
            update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id) VALUES (?, ?, 'main')", otherVendor, relayAccount);
            update(db, "DELETE FROM proxy_accounts WHERE account_id = ?", relayAccount);
            assertThat(count(db, "source_tier_members", vendor)).isEqualTo(1);
            assertThat(count(db, "source_tier_members", otherVendor)).isZero();
            assertThat(count(db, "source_tier_member_models", vendor)).isZero();

            // ... and with the vendor.
            model(db, vendor, nativeAccount, "gone-with-the-vendor");
            update(db, "DELETE FROM source_tier_vendors WHERE vendor = ?", vendor);
            for (String table : List.of("source_tiers", "source_tier_members", "source_tier_member_models", "source_tier_pair_gates")) {
                assertThat(count(db, table, vendor)).as(table).isZero();
            }
            assertThat(count(db, "source_tiers", otherVendor)).isEqualTo(1);
            assertThat(scalar(db, "SELECT count(*) FROM proxy_accounts WHERE account_id = ?", nativeAccount)).isEqualTo(1L);
        }
    }

    @Test
    void levelsAndNamesCanBeExchangedAcrossStatementsOnlyWhenDeferred() throws Exception {
        try (Connection db = owner(freshUrl)) {
            String vendor = vendor(db);
            tier(db, vendor, "alpha", 1, "first");
            tier(db, vendor, "beta", 2, "second");

            // The checks are immediate by default, at the end of each statement ...
            db.setAutoCommit(false);
            try {
                assertRejected(db, "UPDATE source_tiers SET level = 2 WHERE vendor = ? AND tier_id = 'alpha'", "23505", "uq_source_tiers_level", vendor);
                db.rollback();
                assertRejected(db, "UPDATE source_tiers SET name = 'second' WHERE vendor = ? AND tier_id = 'alpha'", "23505", "uq_source_tiers_name", vendor);
                db.rollback();

                // ... so a reorder written as several statements needs them deferred to the commit.
                update(db, "SET CONSTRAINTS ALL DEFERRED");
                update(db, "UPDATE source_tiers SET level = 2, name = 'second' WHERE vendor = ? AND tier_id = 'alpha'", vendor);
                update(db, "UPDATE source_tiers SET level = 1, name = 'first' WHERE vendor = ? AND tier_id = 'beta'", vendor);
                db.commit();
            } finally {
                db.rollback();
                db.setAutoCommit(true);
            }
            assertThat(rows(db, "SELECT tier_id, level, name FROM source_tiers WHERE vendor = ? ORDER BY level", vendor))
                .extracting(row -> row.get("tier_id") + ":" + row.get("level") + ":" + row.get("name"))
                .containsExactly("beta:1:first", "alpha:2:second");

            // A single statement may exchange values without deferring: the check runs once it has finished.
            update(db, "UPDATE source_tiers SET level = 3 - level WHERE vendor = ?", vendor);
            assertThat(rows(db, "SELECT tier_id FROM source_tiers WHERE vendor = ? ORDER BY level", vendor))
                .extracting(row -> row.get("tier_id")).containsExactly("alpha", "beta");

            // Deferred does not mean unchecked: a duplicate that is still there at commit fails the commit.
            db.setAutoCommit(false);
            try {
                update(db, "SET CONSTRAINTS ALL DEFERRED");
                update(db, "UPDATE source_tiers SET level = 2 WHERE vendor = ? AND tier_id = 'alpha'", vendor);
                assertThatThrownBy(db::commit).isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo("23505");
                    assertThat(e.getMessage()).contains("uq_source_tiers_level");
                });
            } finally {
                db.rollback();
                db.setAutoCommit(true);
            }
            assertThat(rows(db, "SELECT tier_id FROM source_tiers WHERE vendor = ? ORDER BY level", vendor))
                .extracting(row -> row.get("tier_id")).containsExactly("alpha", "beta");
        }
    }

    // ---- request logs ----

    @Test
    void requestLogRowsMovedOutOfTheDefaultPartitionKeepEveryColumnEvenWithConcurrentInserts() throws Exception {
        String logColumns = """
            SELECT count(*) FROM request_logs
            WHERE request_id LIKE ? AND source_tier_ref = 'ref-' || request_id AND source_tier_level = 3
              AND source_tier_name = 'relay' AND price_factor = 0.6 AND tier_selection = 'AUTO'
              AND source_dispatch = 'OPENAI_CHAT' AND wire_model = 'wire-model' AND usage_source = 'REPORTED'
              AND stop_reason = 'END_TURN' AND degraded_features = 'cache_control,top_k'
              AND circuit_key = 'anthropic:claude' AND circuit_tier = 'primary' AND upstream_model = 'claude-sonnet-4.5'
              AND upstream_success AND circuit_epoch = '00000000-0000-0000-0000-000000000042'::uuid AND credits = 0.5
              AND withheld_input_tokens = 60000 AND withheld_output_tokens = 4096
            """;
        try (Connection owner = owner(freshUrl); Connection app = runtimeConnection(freshUrl)) {
            // Months beyond the twelve the baseline creates land in the default partition until a partition exists.
            for (int i = 0; i < 3; i++) {
                insertTierLog(owner, "move-" + i, 14, i * 60);
            }
            assertThat(rows(owner, "SELECT tableoid::regclass::text AS part FROM request_logs WHERE request_id LIKE 'move-%'"))
                .extracting(row -> row.get("part")).containsOnly("request_logs_default");

            update(app, "SELECT starry_api.ensure_request_log_partitions(15, 1)");

            assertThat(rows(owner, "SELECT tableoid::regclass::text AS part FROM request_logs WHERE request_id LIKE 'move-%'"))
                .hasSize(3).extracting(row -> row.get("part").toString()).allSatisfy(part -> assertThat(part)
                    .startsWith("request_logs_y").isNotEqualTo("request_logs_default"));
            assertThat(scalar(owner, logColumns, "move-%")).isEqualTo(3L);
            assertThat(scalar(owner, "SELECT count(*) FROM request_logs_default WHERE request_id LIKE 'move-%'")).isEqualTo(0L);

            // Rows keep arriving while the function takes the table lock, detaches the default partition and moves rows.
            int rowsToInsert = 600;
            CountDownLatch inserting = new CountDownLatch(40);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try (Connection producer = runtimeConnection(freshUrl)) {
                Future<?> inserts = executor.submit(() -> {
                    for (int i = 0; i < rowsToInsert; i++) {
                        insertTierLog(producer, "race-" + i, 17, i);
                        inserting.countDown();
                    }
                    return null;
                });
                while (!inserting.await(100, TimeUnit.MILLISECONDS)) {
                    if (inserts.isDone()) {
                        inserts.get(); // the producer stopped early: report why
                        break;
                    }
                }
                // Some rows are in the default partition now, more arrive while the function holds the table lock.
                assertThat(scalar(owner, "SELECT count(*) FROM request_logs_default WHERE request_id LIKE 'race-%'"))
                    .isInstanceOfSatisfying(Long.class, inDefault -> assertThat(inDefault).isGreaterThanOrEqualTo(40L));
                update(app, "SELECT starry_api.ensure_request_log_partitions(18, 1)");
                inserts.get(120, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            assertThat(scalar(owner, "SELECT count(*) FROM request_logs WHERE request_id LIKE 'race-%'")).isEqualTo((long) rowsToInsert);
            assertThat(scalar(owner, logColumns, "race-%")).isEqualTo((long) rowsToInsert);
            assertThat(scalar(owner, "SELECT count(*) FROM request_logs_default WHERE request_id LIKE 'race-%'")).isEqualTo(0L);
        }
    }

    // ---- settlement ----

    @Test
    void theSettlementWrapperRecordsTheServingTierOnceAndIsIdempotent() throws Exception {
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "10");
            String hash = reserve(db, billing, "2", "{}");
            assertThat(wireSettle(hash, "0.5", 100, 20, "anthropic:relay", 1, "1.000000")).isEqualTo("ACCOUNTED");

            Map<String, Object> reservation = reservation(db, hash);
            assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay")
                .containsEntry("served_tier_level", 1);
            assertThat((BigDecimal) reservation.get("served_price_factor")).isEqualByComparingTo("1");
            assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo("0.5");
            assertThat(balance(db, billing)).isEqualByComparingTo("9.5");
            Map<String, Object> event = event(db, hash);
            assertThat(event).containsEntry("source_tier_ref", "anthropic:relay");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("1");
            assertThat((BigDecimal) event.get("credits")).isEqualByComparingTo("0.5");

            // The same call again is the same outcome as for the four-argument function and changes nothing.
            assertThat(wireSettle(hash, "0.5", 100, 20, "anthropic:relay", 1, "1")).isEqualTo("ALREADY_ACCOUNTED");
            assertThat(wireSettle(hash, "0.5", 100, 20, "anthropic:relay", 1, "1.0000004")).isEqualTo("ALREADY_ACCOUNTED");
            // A different tier, level or factor, or a different amount, is a conflict.
            assertThat(wireSettle(hash, "0.5", 100, 20, "anthropic:kiro", 1, "1")).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(wireSettle(hash, "0.5", 100, 20, "anthropic:relay", 2, "1")).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(wireSettle(hash, "0.5", 100, 20, "anthropic:relay", 1, "0.6")).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(wireSettle(hash, "0.7", 100, 20, "anthropic:relay", 1, "1")).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(reservation(db, hash)).containsEntry("served_tier_ref", "anthropic:relay").containsEntry("served_tier_level", 1);
            assertThat(scalar(db, "SELECT count(*) FROM api_usage_events WHERE request_hash = ?", hash)).isEqualTo(1L);
            assertThat(balance(db, billing)).isEqualByComparingTo("9.5");
        }
    }

    @Test
    void theSettlementWrapperRejectsInvalidArgumentsBeforeTouchingTheReservation() throws Exception {
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "10");
            String hash = reserve(db, billing, "2", "{}");
            assertThat(wireSettle(hash, "-1", 1, 1, "v:t", 1, "1")).isEqualTo("BILLING_AMOUNT_INVALID");
            assertThat(wireSettle(hash, "1", -1, 1, "v:t", 1, "1")).isEqualTo("BILLING_AMOUNT_INVALID");
            assertThat(wireSettle(hash, "1", 1, -1, "v:t", 1, "1")).isEqualTo("BILLING_AMOUNT_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, null, 1, "1")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "  ", 1, "1")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v".repeat(129), 1, "1")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v:t", 0, "1")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v:t", 10, "1")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v:t", null, "1")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v:t", 1, "0")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v:t", 1, "-2")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v:t", 1, "1000.5")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v:t", 1, "100000000000000000000")).isEqualTo("BILLING_TIER_INVALID");
            assertThat(wireSettle(hash, "1", 1, 1, "v:t", 1, null)).isEqualTo("BILLING_TIER_INVALID");
            assertThat(reservation(db, hash)).containsEntry("status", "RESERVED").containsEntry("served_tier_ref", null)
                .containsEntry("actual_credits", null);
            assertThat(wireSettle("0".repeat(64), "1", 1, 1, "v:t", 1, "1")).isEqualTo("BILLING_RESERVATION_REQUIRED");
            // The boundaries themselves are valid.
            assertThat(wireSettle(hash, "1", 1, 1, "v".repeat(128), 9, "1000")).isEqualTo("ACCOUNTED");
            assertThat(reservation(db, hash)).containsEntry("served_tier_level", 9);
        }
    }

    @Test
    void theSettlementWrapperRespectsReleasedAndAlreadySettledReservations() throws Exception {
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "10");
            String released = reserve(db, billing, "1", "{}");
            assertThat(scalar(db, "SELECT starry_api.release_api_reservation(?, TRUE, 'test')", released)).isEqualTo("RELEASED");
            assertThat(wireSettle(released, "0.5", 1, 1, "v:t", 1, "1")).isEqualTo("BILLING_REQUEST_FINISHED");
            assertThat(reservation(db, released)).containsEntry("status", "RELEASED").containsEntry("served_tier_ref", null);

            // Settled by a node that does not know tiers: the tier is not added afterwards.
            String legacy = reserve(db, billing, "1", "{}");
            assertThat(scalar(db, "SELECT starry_api.settle_api_usage(?, 0.5, 3, 4)", legacy)).isEqualTo("ACCOUNTED");
            assertThat(wireSettle(legacy, "0.5", 3, 4, "v:t", 2, "0.5")).isEqualTo("ALREADY_ACCOUNTED");
            assertThat(wireSettle(legacy, "0.6", 3, 4, "v:t", 2, "0.5")).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(reservation(db, legacy)).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", null)
                .containsEntry("served_tier_level", null).containsEntry("served_price_factor", null);
            assertThat(event(db, legacy)).containsEntry("source_tier_ref", null).containsEntry("price_factor", null);
        }
    }

    @Test
    void aSettlementThatNeedsReviewAlreadyCarriesTheTierAndTheReviewKeepsIt() throws Exception {
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "1");
            String hash = reserve(db, billing, "1", "{}");
            assertThat(balance(db, billing)).isEqualByComparingTo("0");
            // Actual usage above the hold cannot be topped up from an empty balance.
            assertThat(wireSettle(hash, "5", 10, 10, "v:relay", 1, "0.6")).isEqualTo("BILLING_REVIEW_REQUIRED");
            assertThat(reservation(db, hash)).containsEntry("status", "REVIEW_REQUIRED").containsEntry("served_tier_ref", "v:relay")
                .containsEntry("served_tier_level", 1);
            assertThat(scalar(db, "SELECT count(*) FROM api_usage_events WHERE request_hash = ?", hash)).isEqualTo(0L);
            assertThat(wireSettle(hash, "5", 10, 10, "v:relay", 1, "0.6")).isEqualTo("BILLING_REVIEW_REQUIRED");
            assertThat(wireSettle(hash, "5", 10, 10, "v:kiro", 2, "0.6")).isEqualTo("IDEMPOTENCY_CONFLICT");

            // The administrator settles through the four-argument inner function; the event still gets the tier.
            assertThat(scalar(db, "SELECT starry_api.resolve_billing_review(?, 'admin', 'SETTLE', 'checked', 1)", hash))
                .isEqualTo("ACCOUNTED");
            Map<String, Object> event = event(db, hash);
            assertThat(event).containsEntry("source_tier_ref", "v:relay");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("0.6");
            assertThat((BigDecimal) event.get("credits")).isEqualByComparingTo("1");
        }
    }

    @Test
    void concurrentSettlementsOfOneRequestAgreeOnASingleTier() throws Exception {
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "10");
            String hash = reserve(db, billing, "1", "{}");
            List<String[]> attempts = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                attempts.add(new String[] {i % 2 == 0 ? "v:relay" : "v:kiro", i % 2 == 0 ? "1" : "2", i % 2 == 0 ? "1" : "0.6"});
            }
            List<String> results = Flux.fromIterable(attempts).flatMap(attempt -> runtime
                .sql("SELECT starry_api.settle_api_usage_served(:hash, :credits, :input, :output, :ref, :level, :factor) AS outcome")
                .bind("hash", hash).bind("credits", new BigDecimal("0.4")).bind("input", 10L).bind("output", 5L)
                .bind("ref", attempt[0]).bind("level", Integer.parseInt(attempt[1])).bind("factor", new BigDecimal(attempt[2]))
                .map((row, metadata) -> attempt[0] + "=" + row.get("outcome", String.class)).one(), 12).collectList().block();

            assertThat(results).hasSize(12);
            assertThat(results.stream().filter(result -> result.endsWith("=ACCOUNTED")).count()).isEqualTo(1);
            String winner = results.stream().filter(result -> result.endsWith("=ACCOUNTED")).findFirst().orElseThrow().split("=")[0];
            assertThat(results).allSatisfy(result -> assertThat(result.endsWith("=ACCOUNTED")
                || result.equals(winner + "=ALREADY_ACCOUNTED")
                || (!result.startsWith(winner) && result.endsWith("=IDEMPOTENCY_CONFLICT"))).as(result).isTrue());
            assertThat(reservation(db, hash)).containsEntry("served_tier_ref", winner);
            assertThat(event(db, hash)).containsEntry("source_tier_ref", winner);
            assertThat(scalar(db, "SELECT count(*) FROM api_usage_events WHERE request_hash = ?", hash)).isEqualTo(1L);
            assertThat(balance(db, billing)).isEqualByComparingTo("9.6");
        }
    }

    @Test
    void theWrapperTakesAnIntegerLevelSoABoundJavaIntFindsIt() throws Exception {
        try (Connection db = owner(freshUrl)) {
            assertThat(scalar(db, "SELECT pg_get_function_identity_arguments('" + WIRE_FUNCTION
                + "(character varying, numeric, bigint, bigint, character varying, integer, numeric)'::regprocedure)"))
                .isEqualTo("p_hash character varying, p_credits numeric, p_input bigint, p_output bigint, p_tier_ref character varying,"
                    + " p_level integer, p_factor numeric");
            assertThat(scalar(db, "SELECT count(*) FROM pg_proc WHERE proname = 'settle_api_usage_served'"
                + " AND pronamespace = 'starry_api'::regnamespace")).isEqualTo(1L);

            Billing billing = billingUser(db, "3");
            String hash = reserve(db, billing, "1", "{}");
            // The runtime binds a Java int as int4 and an unqualified numeric literal as numeric: no casts in the call.
            String outcome = runtime.sql("SELECT starry_api.settle_api_usage_served(:hash, :credits, :input, :output, :ref, :level, :factor) AS outcome")
                .bind("hash", hash).bind("credits", new BigDecimal("0.25")).bind("input", 7L).bind("output", 3L)
                .bind("ref", "openai:official").bind("level", 4).bind("factor", new BigDecimal("2.5"))
                .map((row, metadata) -> row.get("outcome", String.class)).one().block();
            assertThat(outcome).isEqualTo("ACCOUNTED");
            assertThat(reservation(db, hash)).containsEntry("served_tier_level", 4);
        }
    }

    // ---- trigger ----

    @Test
    void theUsageEventTriggerAttributesAnEventSettledWithoutTierInformation() throws Exception {
        String pricing = """
            {"tierPlan":{"vendor":"anthropic","levels":[
              {"tierRef":"anthropic:relay","level":1,"factor":1,"estimate":2},
              {"tierRef":"anthropic:kiro","level":2,"factor":0.6,"estimate":1.2}]}}
            """;
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "50");

            // Last dispatched tier, factor from the frozen pricing: how an administrator's review or an expired lease ends.
            String dispatched = reserve(db, billing, "1", pricing);
            update(db, "UPDATE billing_reservations SET last_dispatched_tier_ref = 'anthropic:kiro', last_dispatched_at = clock_timestamp()"
                + " WHERE request_hash = ?", dispatched);
            assertThat(scalar(db, "SELECT starry_api.settle_api_usage(?, 0.3, 1, 1)", dispatched)).isEqualTo("ACCOUNTED");
            Map<String, Object> event = event(db, dispatched);
            assertThat(event).containsEntry("source_tier_ref", "anthropic:kiro");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("0.6");

            // Served wins over last dispatched, and its own factor over the frozen one.
            String served = reserve(db, billing, "1", pricing);
            update(db, "UPDATE billing_reservations SET last_dispatched_tier_ref = 'anthropic:kiro' WHERE request_hash = ?", served);
            assertThat(wireSettle(served, "0.3", 1, 1, "anthropic:relay", 1, "0.9")).isEqualTo("ACCOUNTED");
            event = event(db, served);
            assertThat(event).containsEntry("source_tier_ref", "anthropic:relay");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("0.9");

            // A dispatched tier the snapshot does not list still gets attributed, without a factor.
            String unlisted = reserve(db, billing, "1", pricing);
            update(db, "UPDATE billing_reservations SET last_dispatched_tier_ref = 'anthropic:other' WHERE request_hash = ?", unlisted);
            assertThat(scalar(db, "SELECT starry_api.settle_api_usage(?, 0.3, 1, 1)", unlisted)).isEqualTo("ACCOUNTED");
            assertThat(event(db, unlisted)).containsEntry("source_tier_ref", "anthropic:other").containsEntry("price_factor", null);

            // Untiered requests are untouched, whatever their pricing holds.
            for (String untiered : List.of("{}", "{\"inputRate\":1}", "[]", "{\"tierPlan\":null}")) {
                String hash = reserve(db, billing, "1", untiered);
                assertThat(scalar(db, "SELECT starry_api.settle_api_usage(?, 0.3, 1, 1)", hash)).isEqualTo("ACCOUNTED");
                assertThat(event(db, hash)).containsEntry("source_tier_ref", null).containsEntry("price_factor", null);
            }
        }
    }

    @Test
    void theUsageEventTriggerNeverBreaksASettlementOnUnreadablePricing() throws Exception {
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "50");
            List<String> unreadable = List.of(
                "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\",\"factor\":\"abc\"}]}}",
                "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\",\"factor\":\"0.6\"}]}}",
                "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\",\"factor\":null}]}}",
                "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\",\"factor\":-5}]}}",
                "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\",\"factor\":0}]}}",
                "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\",\"factor\":5000}]}}",
                "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\",\"factor\":1e400}]}}",
                "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\"}]}}",
                "{\"tierPlan\":{\"levels\":[1,null,\"x\",[],{\"tierRef\":7,\"factor\":1}]}}",
                "{\"tierPlan\":{\"levels\":\"x\"}}",
                "{\"tierPlan\":{\"levels\":{\"tierRef\":\"v:t\",\"factor\":1}}}",
                "{\"tierPlan\":[1,2]}",
                "{\"tierPlan\":\"x\"}");
            for (String pricing : unreadable) {
                String hash = reserve(db, billing, "1", pricing);
                update(db, "UPDATE billing_reservations SET last_dispatched_tier_ref = 'v:t' WHERE request_hash = ?", hash);
                assertThat(scalar(db, "SELECT starry_api.settle_api_usage(?, 0.1, 1, 1)", hash)).as(pricing).isEqualTo("ACCOUNTED");
                assertThat(event(db, hash)).as(pricing).containsEntry("source_tier_ref", "v:t").containsEntry("price_factor", null);
            }
            // A long fraction is rounded to the six places of the column.
            String hash = reserve(db, billing, "1", "{\"tierPlan\":{\"levels\":[{\"tierRef\":\"v:t\",\"factor\":0.12345678}]}}");
            update(db, "UPDATE billing_reservations SET last_dispatched_tier_ref = 'v:t' WHERE request_hash = ?", hash);
            assertThat(scalar(db, "SELECT starry_api.settle_api_usage(?, 0.1, 1, 1)", hash)).isEqualTo("ACCOUNTED");
            assertThat((BigDecimal) event(db, hash).get("price_factor")).isEqualByComparingTo("0.123457");
        }
    }

    @Test
    void anEventInsertedWithItsOwnTierKeepsIt() throws Exception {
        try (Connection db = owner(freshUrl)) {
            Billing billing = billingUser(db, "5");
            String hash = reserve(db, billing, "1", "{}");
            update(db, "UPDATE billing_reservations SET last_dispatched_tier_ref = 'v:dispatched' WHERE request_hash = ?", hash);
            update(db, "INSERT INTO api_usage_events(request_hash, request_id, key_id, user_id, usage_date, credits, input_tokens,"
                + " output_tokens, charge_balance, source_tier_ref, price_factor) VALUES (?, 'manual', ?, ?, CURRENT_DATE, 0, 0, 0, TRUE,"
                + " 'v:explicit', 0.25)", hash, billing.keyId(), billing.userId());
            Map<String, Object> event = event(db, hash);
            assertThat(event).containsEntry("source_tier_ref", "v:explicit");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("0.25");
            // An event for a request without any reservation is stored as given.
            String orphan = sha256("orphan-" + suffix());
            update(db, "INSERT INTO api_usage_events(request_hash, request_id, key_id, usage_date, credits, input_tokens, output_tokens,"
                + " charge_balance) VALUES (?, 'orphan', 'k', CURRENT_DATE, 0, 0, 0, FALSE)", orphan);
            assertThat(event(db, orphan)).containsEntry("source_tier_ref", null).containsEntry("price_factor", null);
        }
    }

    // ---- privileges ----

    @Test
    void theRuntimeRoleHasExactlyTheAccessTheFeatureNeeds() throws Exception {
        try (Connection db = owner(freshUrl); Connection app = runtimeConnection(freshUrl)) {
            for (String table : List.of("source_tier_vendors", "source_tiers", "source_tier_members", "source_tier_member_models",
                "source_tier_pair_gates")) {
                for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                    assertThat(scalar(db, "SELECT has_table_privilege('starry_api_runtime', 'starry_api." + table + "', '" + privilege + "')"))
                        .as(table + " " + privilege).isEqualTo(true);
                }
                assertThat(scalar(db, "SELECT has_table_privilege('starry_api_runtime', 'starry_api." + table + "', 'TRUNCATE')"))
                    .as(table).isEqualTo(false);
                assertThat(scalar(db, "SELECT count(*) FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl, acldefault('r', c.relowner))) a"
                    + " WHERE c.oid = 'starry_api." + table + "'::regclass AND a.grantee = 0")).as(table).isEqualTo(0L);
                assertThat(scalar(db, "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'starry_api." + table + "'::regclass"))
                    .isEqualTo("starry_api_owner");
            }
            String wrapper = WIRE_FUNCTION + "(character varying, numeric, bigint, bigint, character varying, integer, numeric)";
            String trigger = "starry_api.fill_usage_event_tier()";
            assertThat(scalar(db, "SELECT has_function_privilege('starry_api_runtime', '" + wrapper + "', 'EXECUTE')")).isEqualTo(true);
            assertThat(scalar(db, "SELECT has_function_privilege('starry_api_runtime', '" + trigger + "', 'EXECUTE')")).isEqualTo(false);
            for (String function : List.of(wrapper, trigger)) {
                assertThat(scalar(db, "SELECT count(*) FROM pg_proc p CROSS JOIN LATERAL aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) a"
                    + " WHERE p.oid = '" + function + "'::regprocedure AND a.grantee = 0 AND a.privilege_type = 'EXECUTE'")).as(function)
                    .isEqualTo(0L);
                assertThat(scalar(db, "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid = '" + function + "'::regprocedure"))
                    .isEqualTo("starry_api_owner");
            }
            assertThatThrownBy(() -> scalar(app, "SELECT starry_api.fill_usage_event_tier()")).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).isEqualTo("42501"));

            // What the runtime does with the new columns and tables works under its own role.
            String vendor = "rt-" + suffix();
            update(app, "INSERT INTO source_tier_vendors(vendor, enabled) VALUES (?, TRUE)", vendor);
            update(app, "UPDATE source_tier_vendors SET sticky_sessions = FALSE, version = version + 1 WHERE vendor = ?", vendor);
            assertThat(scalar(app, "SELECT updated_at >= created_at FROM source_tier_vendors WHERE vendor = ?", vendor)).isEqualTo(true);
            update(app, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, 'a', 1, 'a')", vendor);
            update(app, "UPDATE source_tiers SET price_factor = 0.5 WHERE vendor = ?", vendor);
            update(app, "DELETE FROM source_tier_vendors WHERE vendor = ?", vendor);
            assertThat(scalar(app, "SELECT count(*) FROM source_tiers WHERE vendor = ?", vendor)).isEqualTo(0L);
            Billing billing = billingUser(db, "2");
            update(app, "UPDATE api_keys SET tier_preference = 'kiro' WHERE key_id = ?", billing.keyId());
            insertTierLog(app, "runtime-" + suffix(), 0, 5);
            update(app, "UPDATE billing_reservations SET last_dispatched_tier_ref = 'v:t', last_dispatched_at = clock_timestamp()"
                + " WHERE request_hash = ?", reserve(db, billing, "1", "{}"));
        }
    }

    // ---- upgrading ----

    @Test
    void aV28DatabaseWithDataMovesToV29WithoutLosingOrChangingAnything() throws Exception {
        createDatabase(UPGRADED);
        String url = jdbcUrl(UPGRADED);
        migrate(url, "28");
        String settledHash;
        String openHash;
        Billing billing;
        try (Connection db = owner(url)) {
            assertThat(columns(db, "billing_reservations")).doesNotContain("served_tier_ref");
            assertThat(scalar(db, "SELECT count(*) FROM pg_proc WHERE proname = 'settle_api_usage_served'")).isEqualTo(0L);
            billing = billingUser(db, "20");
            settledHash = reserve(db, billing, "2", "{\"inputRate\":1}");
            assertThat(scalar(db, "SELECT starry_api.settle_api_usage(?, 0.75, 11, 12)", settledHash)).isEqualTo("ACCOUNTED");
            openHash = reserve(db, billing, "3", "{}");
            update(db, "INSERT INTO request_logs(request_id, path, model, status_code, success, credits, circuit_key, circuit_tier,"
                + " upstream_model, upstream_success, circuit_epoch, created_at) VALUES ('legacy-now', '/v1/chat/completions', 'gpt-5.5', 200,"
                + " TRUE, 0.75, 'openai:gpt', 'primary', 'gpt-5.5', TRUE, '00000000-0000-0000-0000-000000000007'::uuid, now())");
            update(db, "INSERT INTO request_logs(request_id, path, model, status_code, success, credits, created_at)"
                + " VALUES ('legacy-default', '/v1/messages', 'claude', 200, TRUE, 0.1, " + monthStart(14) + ")");
            assertThat(rows(db, "SELECT tableoid::regclass::text AS part FROM request_logs WHERE request_id = 'legacy-default'"))
                .extracting(row -> row.get("part")).containsExactly("request_logs_default");
            update(db, "UPDATE api_keys SET credits_limit = 100 WHERE key_id = ?", billing.keyId());
            update(db, "INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method) VALUES ('legacy-account', 't', 'OpenAI', 'api-key')");
        }

        migrate(url, null);

        DatabaseClient upgraded = runtimeClient(UPGRADED);
        try (Connection db = owner(url); Connection app = runtimeConnection(url)) {
            assertThat(scalar(db, "SELECT success FROM flyway_schema_history WHERE version = '29'")).isEqualTo(true);
            assertThat(scalar(db, "SELECT setting_value FROM system_settings WHERE setting_key = 'source_tier_config_version'")).isEqualTo("0");
            for (String table : List.of("source_tier_vendors", "source_tiers", "source_tier_members", "source_tier_member_models",
                "source_tier_pair_gates")) {
                assertThat(scalar(db, "SELECT count(*) FROM " + table)).as(table).isEqualTo(0L);
            }
            // Existing rows are unchanged and the new columns are empty; keys start on "auto".
            assertThat(scalar(db, "SELECT tier_preference FROM api_keys WHERE key_id = ?", billing.keyId())).isEqualTo("auto");
            assertThat((BigDecimal) rows(db, "SELECT credits_limit FROM api_keys WHERE key_id = ?", billing.keyId()).getFirst().get("credits_limit"))
                .isEqualByComparingTo("100");
            Map<String, Object> settled = reservation(db, settledHash);
            assertThat(settled).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", null)
                .containsEntry("served_tier_level", null).containsEntry("served_price_factor", null)
                .containsEntry("last_dispatched_tier_ref", null).containsEntry("last_dispatched_at", null);
            assertThat((BigDecimal) settled.get("actual_credits")).isEqualByComparingTo("0.75");
            Map<String, Object> event = event(db, settledHash);
            assertThat(event).containsEntry("source_tier_ref", null).containsEntry("price_factor", null);
            assertThat((BigDecimal) event.get("credits")).isEqualByComparingTo("0.75");
            Map<String, Object> log = rows(db, "SELECT * FROM request_logs WHERE request_id = 'legacy-now'").getFirst();
            assertThat(log).containsEntry("circuit_key", "openai:gpt").containsEntry("source_tier_ref", null)
                .containsEntry("source_tier_level", null).containsEntry("source_tier_name", null).containsEntry("price_factor", null)
                .containsEntry("tier_selection", null).containsEntry("source_dispatch", null).containsEntry("wire_model", null)
                .containsEntry("usage_source", null).containsEntry("stop_reason", null).containsEntry("degraded_features", null)
                .containsEntry("withheld_input_tokens", null).containsEntry("withheld_output_tokens", null);

            // The old four-argument function keeps working for a request reserved before the upgrade ...
            String legacy = reserve(db, billing, "1", "{}");
            assertThat(scalar(app, "SELECT starry_api.settle_api_usage(?, 0.5, 1, 1)", legacy)).isEqualTo("ACCOUNTED");
            assertThat(event(db, legacy)).containsEntry("source_tier_ref", null);
            // ... and the new one settles it with a tier.
            assertThat(upgraded.sql("SELECT starry_api.settle_api_usage_served(:hash, :credits, :input, :output, :ref, :level, :factor) AS outcome")
                .bind("hash", openHash).bind("credits", new BigDecimal("1")).bind("input", 5L).bind("output", 6L)
                .bind("ref", "openai:codex").bind("level", 2).bind("factor", new BigDecimal("0.5"))
                .map((row, metadata) -> row.get("outcome", String.class)).one().block()).isEqualTo("ACCOUNTED");
            assertThat(event(db, openHash)).containsEntry("source_tier_ref", "openai:codex");

            // The rewritten partition function moves the legacy default-partition row without losing it.
            update(app, "SELECT starry_api.ensure_request_log_partitions(15, 1)");
            Map<String, Object> moved = rows(db, "SELECT tableoid::regclass::text AS part, * FROM request_logs WHERE request_id = 'legacy-default'")
                .getFirst();
            assertThat(moved.get("part").toString()).startsWith("request_logs_y");
            assertThat(moved).containsEntry("model", "claude").containsEntry("source_tier_ref", null).containsEntry("price_factor", null);
            assertThat(scalar(db, "SELECT count(*) FROM request_logs_default")).isEqualTo(0L);
            assertThat(scalar(db, "SELECT count(*) FROM proxy_accounts WHERE account_id = 'legacy-account'")).isEqualTo(1L);
        }
    }

    // ---- fixtures ----

    private record Billing(String userId, String keyId) {}

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String vendor(Connection db) throws SQLException {
        String vendor = "vendor-" + suffix();
        update(db, "INSERT INTO source_tier_vendors(vendor) VALUES (?)", vendor);
        return vendor;
    }

    private static void tier(Connection db, String vendor, String tierId, int level, String name) throws SQLException {
        update(db, "INSERT INTO source_tiers(vendor, tier_id, level, name) VALUES (?, ?, ?, ?)", vendor, tierId, level, name);
    }

    private static String account(Connection db) throws SQLException {
        String id = "acct-" + suffix();
        update(db, "INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, base_url) VALUES (?, 'token', 'OpenAI',"
            + " 'api-key', 'https://relay.example/v1')", id);
        return id;
    }

    private static void relayMember(Connection db, String vendor, String tierId, String accountId) throws SQLException {
        update(db, "INSERT INTO source_tier_members(vendor, account_id, tier_id, protocol, usage_cache_read_in_prompt,"
            + " usage_cache_write_in_prompt) VALUES (?, ?, ?, 'OPENAI_CHAT', TRUE, TRUE) ON CONFLICT (vendor, account_id)"
            + " DO UPDATE SET tier_id = EXCLUDED.tier_id, protocol = EXCLUDED.protocol, usage_cache_read_in_prompt = TRUE,"
            + " usage_cache_write_in_prompt = TRUE", vendor, accountId, tierId);
    }

    private static void model(Connection db, String vendor, String accountId, String modelId) throws SQLException {
        update(db, "INSERT INTO source_tier_member_models(vendor, account_id, model_id, upstream_model, max_context_tokens)"
            + " VALUES (?, ?, ?, 'wire', 100000)", vendor, accountId, modelId);
    }

    private static long count(Connection db, String table, String vendor) throws SQLException {
        return (Long) scalar(db, "SELECT count(*) FROM " + table + " WHERE vendor = ?", vendor);
    }

    private static Billing billingUser(Connection db, String balance) throws SQLException {
        String user = "user-" + suffix();
        String key = "key-" + suffix();
        update(db, "INSERT INTO admin_users(user_id, username, role, balance, password_hash, password_salt, enabled, first_login)"
            + " VALUES (?, ?, 'USER', ?::numeric, repeat('a', 64), repeat('b', 32), TRUE, FALSE)", user, user, balance);
        update(db, "INSERT INTO api_keys(key_id, user_id, name, key_hash, key_prefix, model_provider)"
            + " VALUES (?, ?, 'test', repeat(md5(?), 2), 'sk-test', 'OpenAI')", key, user, key);
        return new Billing(user, key);
    }

    private static String reserve(Connection db, Billing billing, String credits, String pricing) throws Exception {
        String hash = sha256(UUID.randomUUID().toString());
        assertThat(scalar(db, "SELECT starry_api.reserve_api_usage(?, ?, ?, ?::numeric, ?::jsonb)", billing.keyId(), hash,
            "req-" + UUID.randomUUID(), credits, pricing)).isEqualTo("RESERVED");
        return hash;
    }

    /** Calls the wrapper the way the runtime will: bound parameters, a Java Integer level, no casts. */
    private static String wireSettle(String hash, String credits, long input, long output, String ref, Integer level, String factor) {
        DatabaseClient.GenericExecuteSpec spec = runtime
            .sql("SELECT starry_api.settle_api_usage_served(:hash, :credits, :input, :output, :ref, :level, :factor) AS outcome")
            .bind("hash", hash).bind("credits", new BigDecimal(credits)).bind("input", input).bind("output", output);
        spec = ref == null ? spec.bindNull("ref", String.class) : spec.bind("ref", ref);
        spec = level == null ? spec.bindNull("level", Integer.class) : spec.bind("level", level);
        spec = factor == null ? spec.bindNull("factor", BigDecimal.class) : spec.bind("factor", new BigDecimal(factor));
        return spec.map((row, metadata) -> row.get("outcome", String.class)).one().block();
    }

    private static Map<String, Object> reservation(Connection db, String hash) throws SQLException {
        return rows(db, "SELECT * FROM billing_reservations WHERE request_hash = ?", hash).getFirst();
    }

    private static Map<String, Object> event(Connection db, String hash) throws SQLException {
        return rows(db, "SELECT * FROM api_usage_events WHERE request_hash = ?", hash).getFirst();
    }

    private static BigDecimal balance(Connection db, Billing billing) throws SQLException {
        return (BigDecimal) scalar(db, "SELECT balance FROM admin_users WHERE user_id = ?", billing.userId());
    }

    private static List<String> columns(Connection db, String table) throws SQLException {
        return rows(db, "SELECT column_name FROM information_schema.columns WHERE table_schema = 'starry_api' AND table_name = ?", table)
            .stream().map(row -> row.get("column_name").toString()).toList();
    }

    /** First instant of the UTC month that starts the given number of months from now, as a timestamptz expression. */
    private static String monthStart(int monthsAhead) {
        return "((date_trunc('month', now() AT TIME ZONE 'UTC') + interval '" + monthsAhead + " months') AT TIME ZONE 'UTC')";
    }

    /** A request log row with every tier column and every circuit column set; the tier reference is derived from the id. */
    private static void insertTierLog(Connection db, String requestId, int monthsAhead, int secondsIntoMonth) throws SQLException {
        update(db, """
            INSERT INTO request_logs(request_id, path, model, status_code, success, credits, circuit_key, circuit_tier, upstream_model,
                upstream_success, circuit_epoch, source_tier_ref, source_tier_level, source_tier_name, price_factor, tier_selection,
                source_dispatch, wire_model, usage_source, stop_reason, degraded_features, withheld_input_tokens, withheld_output_tokens,
                created_at)
            VALUES (?, '/v1/messages', 'claude-sonnet-4.5', 200, TRUE, 0.5, 'anthropic:claude', 'primary', 'claude-sonnet-4.5',
                TRUE, '00000000-0000-0000-0000-000000000042'::uuid, 'ref-' || ?, 3, 'relay', 0.6, 'AUTO', 'OPENAI_CHAT', 'wire-model',
                'REPORTED', 'END_TURN', 'cache_control,top_k', 60000, 4096, %s + make_interval(secs => ?))
            """.formatted(monthStart(monthsAhead)), requestId, requestId, secondsIntoMonth);
    }

    // ---- JDBC helpers ----

    private static Connection owner(String url) throws SQLException {
        Connection connection = DriverManager.getConnection(url, MIGRATION_USERNAME, MIGRATION_PASSWORD);
        try (var statement = connection.createStatement()) {
            statement.execute("SET ROLE starry_api_owner");
            statement.execute("SET search_path TO starry_api");
        }
        return connection;
    }

    /** The login the application uses: it only inherits the runtime role. */
    private static Connection runtimeConnection(String url) throws SQLException {
        Connection connection = DriverManager.getConnection(url, APP_USERNAME, APP_PASSWORD);
        try (var statement = connection.createStatement()) {
            statement.execute("SET search_path TO starry_api");
        }
        return connection;
    }

    private static long update(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            bind(statement, args);
            if (statement.execute()) {
                return -1;
            }
            return statement.getUpdateCount();
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

    private static void assertRejected(Connection db, String sql, String sqlState, String constraint, Object... args) {
        assertThatThrownBy(() -> update(db, sql, args)).as(sql + " " + Arrays.asList(args)).isInstanceOfSatisfying(SQLException.class, e -> {
            assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
            if (constraint != null) {
                assertThat(e.getMessage()).contains(constraint);
            }
        });
    }

    // ---- database lifecycle ----

    private static String jdbcUrl(String database) {
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
    }

    private static DatabaseClient runtimeClient(String database) {
        return DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://" + APP_USERNAME + ":" + APP_PASSWORD + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database + "?schema=starry_api"));
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

    private static void migrate(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, MIGRATION_USERNAME, MIGRATION_PASSWORD)
            .locations("classpath:db/migration").schemas("starry_api").defaultSchema("starry_api").createSchemas(false)
            .initSql("SET ROLE starry_api_owner");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static void exec(String... command) throws Exception {
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
    }
}
