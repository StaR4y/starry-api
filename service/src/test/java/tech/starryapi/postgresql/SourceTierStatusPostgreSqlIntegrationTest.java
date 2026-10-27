package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.allocation.AccountCandidateRanker;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.sourcetier.SourceTierAdminService;
import tech.starryapi.sourcetier.SourceTierDtos;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierStatusService;
import tech.starryapi.sourcetier.SourceTierValidator;

/**
 * The status page on a real database: the allocation tables behind the busy and resting layers, the accounts that are
 * switched off, and the request log of the last 15 minutes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "starry.stats.aggregation-enabled=false"
})
class SourceTierStatusPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    @Autowired DatabaseClient db;
    @Autowired ReactiveTransactionManager transactionManager;
    @Autowired SystemSettingRepository settings;
    @Autowired SourceTierRegistry registry;
    @Autowired AccountPoolService accountPool;
    @Autowired AccountCandidateRanker ranker;
    @Autowired ModelPoolKeyResolver resolver;
    @Autowired ModelCatalogProvider catalog;
    @Autowired SourceTierStatusService wired;

    private final List<String> accounts = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final SourceTierRuntimeInfo everything = new SourceTierRuntimeInfo(true, true, true, pair -> PairStatus.VERIFIED);

    @BeforeEach
    void startFromNothingConfigured() {
        // The figures are exact only when the accounts in the database are the ones a test made.
        db.sql("DELETE FROM proxy_accounts").fetch().rowsUpdated().block();
        // The application role may add to the request log but not remove from it.
        executeAsDatabaseOwner("DELETE FROM starry_api.request_logs");
        db.sql("DELETE FROM source_tier_vendors").fetch().rowsUpdated().block();
        db.sql("UPDATE system_settings SET setting_value = '0' WHERE setting_key = 'source_tier_config_version'")
            .fetch().rowsUpdated().block();
        registry.refresh().block();
    }

    @AfterEach
    void removeWhatWasAdded() {
        executeAsDatabaseOwner("DELETE FROM starry_api.request_logs");
        requests.clear();
        for (String id : accounts) {
            db.sql("DELETE FROM proxy_accounts WHERE account_id = :id").bind("id", id).fetch().rowsUpdated().block();
        }
        accounts.clear();
        accountPool.refreshCache().block();
    }

    private SourceTierStatusService status() {
        return new SourceTierStatusService(registry, everything, new SourceTierValidator(catalog, resolver, everything),
            accountPool, ranker, db);
    }

    private void configureAnthropic() {
        new SourceTierAdminService(db, transactionManager, settings, registry,
            new SourceTierValidator(catalog, resolver, everything), everything)
            .replace("anthropic", new SourceTierDtos.ReplaceVendor(0L, true, null, null, null, null, null, List.of(
                new SourceTierDtos.Tier("official", 1, "官方", null, List.of("OFFICIAL_API_KEY"), null, null, null),
                new SourceTierDtos.Tier("kiro", 2, "kiro反向代理", new BigDecimal("0.6"), List.of("KIRO", "CLAUDE_OAUTH"),
                    null, null, null)), List.of(), List.of()))
            .block();
    }

    @Test
    void theServiceTheApplicationWiresAnswersWithTheCountsOfThisNodesPlans() {
        // Built by Spring, with the counts of skipped relays the planner keeps: nothing was planned here, so nothing is counted.
        assertThat(wired.evidence(30).block().skipsSince()).isNotNull();
        assertThat(wired.status().block().vendors()).isEmpty();
    }

    @Test
    void theLayersAreReadFromTheAccountCacheAndTheAllocationTables() {
        String ready = account("Anthropic", "api-key", true);
        String suspended = account("Anthropic", "api-key", true);
        String busy = account("Anthropic", "api-key", true);
        String resting = account("BuilderId", "idc", true);
        String off = account("BuilderId", "idc", false);
        db.sql("UPDATE proxy_accounts SET suspended_at = now() WHERE account_id = :id").bind("id", suspended).fetch().rowsUpdated().block();
        executeAsDatabaseOwner(
            "UPDATE starry_api.account_admission_states SET active_concurrency = (SELECT safe_concurrency FROM starry_api.account_allocation_policies"
                + " WHERE account_id = '" + busy + "') WHERE account_id = '" + busy + "'",
            "UPDATE starry_api.account_allocation_states SET lifecycle_state = 'COOLDOWN', cooldown_until = now() + interval '1 hour' WHERE account_id = '"
                + resting + "'");
        configureAnthropic();
        accountPool.refreshCache().block();

        SourceTierDtos.VendorStatus anthropic = status().status().block().vendors().stream()
            .filter(vendor -> vendor.vendor().equals("anthropic")).findFirst().orElseThrow();

        assertThat(anthropic.serving()).isTrue();
        SourceTierDtos.TierStatus official = anthropic.tiers().get(0);
        SourceTierDtos.TierStatus kiro = anthropic.tiers().get(1);
        assertThat(official.tierId()).isEqualTo("official");
        assertThat(official.accounts()).isEqualTo(new SourceTierDtos.TierAccounts(0, 1, 0, 1, 1));
        assertThat(official.serves()).isTrue();
        assertThat(kiro.accounts()).isEqualTo(new SourceTierDtos.TierAccounts(1, 0, 1, 0, 0));
        assertThat(kiro.serves()).as("its enabled account is cooling down").isFalse();
        assertThat(ready).isNotBlank();
        assertThat(off).isNotBlank();
    }

    @Test
    void anAccountIsSelectableOnlyWhenTheCacheAndTheAllocationLifecycleBothLetSelectionPickIt() {
        String ready = account("Anthropic", "api-key", true);
        String suspended = account("Anthropic", "api-key", true);
        String cooling = account("Anthropic", "api-key", true);
        String cooled = account("Anthropic", "api-key", true);
        String lifecycleDisabled = account("Anthropic", "api-key", true);
        String busy = account("Anthropic", "api-key", true);
        db.sql("UPDATE proxy_accounts SET suspended_at = now() WHERE account_id = :id").bind("id", suspended).fetch().rowsUpdated().block();
        executeAsDatabaseOwner(
            "UPDATE starry_api.account_allocation_states SET lifecycle_state = 'COOLDOWN', cooldown_until = now() + interval '1 hour' WHERE account_id = '"
                + cooling + "'",
            "UPDATE starry_api.account_allocation_states SET lifecycle_state = 'COOLDOWN', cooldown_until = now() - interval '1 minute' WHERE account_id = '"
                + cooled + "'",
            "UPDATE starry_api.account_allocation_states SET lifecycle_state = 'DISABLED' WHERE account_id = '" + lifecycleDisabled + "'",
            "UPDATE starry_api.account_admission_states SET active_concurrency = (SELECT safe_concurrency FROM starry_api.account_allocation_policies"
                + " WHERE account_id = '" + busy + "') WHERE account_id = '" + busy + "'");
        accountPool.refreshCache().block();

        assertThat(selectable(ready)).isTrue();
        assertThat(selectable(cooled)).as("its cooldown has ended").isTrue();
        // Admission is the one layer that only a request meets: a busy account is still one selection can pick.
        assertThat(selectable(busy)).isTrue();
        assertThat(selectable(suspended)).as("suspended in the account cache").isFalse();
        assertThat(selectable(cooling)).as("cooling down in the allocation tables").isFalse();
        assertThat(selectable(lifecycleDisabled)).as("disabled in the allocation tables").isFalse();
        // Several at once are selectable as soon as one of them is.
        assertThat(accountPool.hasSelectableAccount(account -> List.of(suspended, cooling, ready).contains(account.getAccountId())).block()).isTrue();
        assertThat(accountPool.hasSelectableAccount(account -> List.of(suspended, cooling).contains(account.getAccountId())).block()).isFalse();
    }

    private boolean selectable(String accountId) {
        return Boolean.TRUE.equals(accountPool.hasSelectableAccount(account -> accountId.equals(account.getAccountId())).block());
    }

    @Test
    void theWindowIsReadFromTheRequestLogOfTheLastFifteenMinutes() {
        configureAnthropic();
        Instant now = Instant.now();
        log("anthropic:official", "AUTO", true, now.minus(1, ChronoUnit.MINUTES));
        log("anthropic:official", "AUTO", true, now.minus(5, ChronoUnit.MINUTES));
        log("anthropic:official", "AUTO", false, now.minus(5, ChronoUnit.MINUTES));
        log("anthropic:kiro", "AUTO", true, now.minus(2, ChronoUnit.MINUTES));
        log("anthropic:kiro", "PINNED", true, now.minus(2, ChronoUnit.MINUTES));
        log("anthropic:kiro", "AUTO", true, now.minus(40, ChronoUnit.MINUTES));
        logWithoutTier(now.minus(1, ChronoUnit.MINUTES));

        SourceTierDtos.VendorStatus anthropic = status().status().block().vendors().stream()
            .filter(vendor -> vendor.vendor().equals("anthropic")).findFirst().orElseThrow();

        assertThat(anthropic.autoServed()).isEqualTo(3);
        assertThat(anthropic.degraded()).isEqualTo(1);
        SourceTierDtos.TierStatus official = anthropic.tiers().get(0);
        assertThat(official.served()).isEqualTo(2);
        assertThat(official.failed()).isEqualTo(1);
        SourceTierDtos.TierStatus kiro = anthropic.tiers().get(1);
        assertThat(kiro.served()).isEqualTo(2);
        assertThat(kiro.servedPinned()).isEqualTo(1);
        assertThat(kiro.failed()).isZero();
    }

    /** A relay in a tier of its own beside Kiro, opened to one key for the Messages entry and not yet verified. */
    private String configureAnthropicWithRelay() {
        String relay = account("OpenAI-compatible", "api-key", true);
        new SourceTierAdminService(db, transactionManager, settings, registry,
            new SourceTierValidator(catalog, resolver, everything), everything)
            .replace("anthropic", new SourceTierDtos.ReplaceVendor(0L, true, null, null, null, null, null, List.of(
                new SourceTierDtos.Tier("relay", 1, "openai源头", null, List.of(), null, null, null),
                new SourceTierDtos.Tier("kiro", 2, "kiro反向代理", new BigDecimal("0.6"), List.of("KIRO", "CLAUDE_OAUTH"),
                    null, null, null)),
                List.of(new SourceTierDtos.Member(relay, "relay", "OPENAI_CHAT", null, null, null, null, null, null, null, null, true, true,
                    List.of(new SourceTierDtos.MemberModel("claude-sonnet-4.5", "relay/sonnet", 200_000, null, null, null, null)))),
                List.of(new SourceTierDtos.Gate("OPENAI_CHAT", "MESSAGES", "KEYS", List.of("key-1")))))
            .block();
        accountPool.refreshCache().block();
        return relay;
    }

    @Test
    void theAttemptsSentToRelaysAreCountedFromTheLogByRelayPairAndKindOfFailure() {
        String relay = configureAnthropicWithRelay();
        Instant now = Instant.now();
        // Messages: 6 attempts, 4 answered (one estimated, one clamped, two reported; one left an image out), one broken off
        // after the relay may have generated, one refused as converted.
        relayLog(relay, "/v1/messages", true, 1000, "REPORTED", "END_TURN", null, now.minus(1, ChronoUnit.MINUTES));
        relayLog(relay, "/v1/messages", true, 2000, "ESTIMATED", "TOOL_USE", "image,cache_control", now.minus(2, ChronoUnit.MINUTES));
        relayLog(relay, "/v1/messages", true, 3000, "CLAMPED", "END_TURN", null, now.minus(3, ChronoUnit.MINUTES));
        relayLog(relay, "/v1/messages", true, 9000, "REPORTED", "MAX_TOKENS", "image", now.minus(4, ChronoUnit.MINUTES));
        relayLog(relay, "/v1/messages", false, 30000, null, "INTERRUPTED", null, now.minus(5, ChronoUnit.MINUTES));
        relayLog(relay, "/v1/messages", false, 400, null, "CONVERSION_REJECTED", null, now.minus(5, ChronoUnit.MINUTES));
        // Chat: one answered, one that the relay did not serve the model for, one that failed with no kind.
        relayLog(relay, "/v1/chat/completions", true, 500, "REPORTED", "END_TURN", null, now.minus(1, ChronoUnit.MINUTES));
        relayLog(relay, "/v1/chat/completions", false, 300, null, "MODEL_NOT_SERVED", null, now.minus(1, ChronoUnit.MINUTES));
        relayLog(relay, "/v1/chat/completions", false, 100, null, null, null, now.minus(1, ChronoUnit.MINUTES));
        // And one that the relay turned down for the key of its account (D5).
        relayLog(relay, "/v1/chat/completions", false, 100, null, "KEY_REFUSED", null, now.minus(1, ChronoUnit.MINUTES));
        // Not relay attempts: a native attempt of the same tier, and one of Kiro.
        log("anthropic:relay", "AUTO", true, now.minus(1, ChronoUnit.MINUTES));
        log("anthropic:kiro", "AUTO", true, now.minus(1, ChronoUnit.MINUTES));
        // Older than the window of the status, inside the window of the evidence.
        relayLog(relay, "/v1/messages", true, 100, "REPORTED", "END_TURN", null, now.minus(2, ChronoUnit.DAYS));

        SourceTierDtos.VendorStatus anthropic = status().status().block().vendors().stream()
            .filter(vendor -> vendor.vendor().equals("anthropic")).findFirst().orElseThrow();

        SourceTierDtos.TierStatus relayTier = anthropic.tiers().get(0);
        assertThat(relayTier.accounts()).as("registered, not opened for want of a verified mapping")
            .isEqualTo(new SourceTierDtos.TierAccounts(0, 0, 0, 0, 0, 1));
        assertThat(relayTier.serves()).isFalse();
        SourceTierDtos.XpStatus xp = anthropic.xp();
        assertThat(xp.relays()).singleElement().satisfies(view -> {
            assertThat(view.accountId()).isEqualTo(relay);
            assertThat(view.layer()).isEqualTo("unopened");
            assertThat(view.mappings()).isEqualTo(1);
            assertThat(view.verified()).isZero();
            SourceTierDtos.XpFigures figures = view.figures();
            assertThat(figures.attempts()).isEqualTo(10);
            assertThat(figures.served()).isEqualTo(5);
            assertThat(figures.failed()).isEqualTo(5);
            assertThat(figures.interrupted()).isEqualTo(1);
            assertThat(figures.conversionRejected()).isEqualTo(1);
            assertThat(figures.modelNotServed()).isEqualTo(1);
            assertThat(figures.keyRefused()).isEqualTo(1);
            assertThat(figures.contextExceeded()).isZero();
            assertThat(figures.estimated()).isEqualTo(1);
            assertThat(figures.clamped()).isEqualTo(1);
            assertThat(figures.degraded()).isEqualTo(2);
        });
        SourceTierDtos.XpPair messages = xp.pairs().stream().filter(pair -> pair.entryEndpoint().equals("MESSAGES")).findFirst().orElseThrow();
        assertThat(messages.gate()).isEqualTo("KEYS");
        assertThat(messages.allowedKeys()).isEqualTo(1);
        assertThat(messages.figures().attempts()).isEqualTo(6);
        assertThat(messages.figures().served()).isEqualTo(4);
        assertThat(messages.figures().p95Ms()).as("of the answered attempts, 1, 2, 3 and 9 seconds: 3 s and 0.85 of the 6 s to 9 s").isEqualTo(8100L);
        assertThat(messages.degradedFeatures()).containsExactly(new SourceTierDtos.FeatureCount("image", 2),
            new SourceTierDtos.FeatureCount("cache_control", 1));
        SourceTierDtos.XpPair chat = xp.pairs().stream().filter(pair -> pair.entryEndpoint().equals("CHAT")).findFirst().orElseThrow();
        assertThat(chat.figures().attempts()).isEqualTo(4);
        assertThat(chat.figures().p95Ms()).isEqualTo(500L);
        // Native attempts do not count among the relay's, the tier's own served and failed counts do include them all.
        assertThat(relayTier.served()).isEqualTo(5 + 1);
        assertThat(relayTier.failed()).isEqualTo(5);
    }

    @Test
    void theEvidenceIsReadOverTheDaysAskedAndHoldsEachPairToTheStandard() {
        String relay = configureAnthropicWithRelay();
        Instant now = Instant.now();
        for (int i = 0; i < 4; i++) {
            relayLog(relay, "/v1/messages", true, 100, "REPORTED", "END_TURN", null, now.minus(2, ChronoUnit.DAYS));
        }
        relayLog(relay, "/v1/messages", false, 100, null, "INTERRUPTED", null, now.minus(10, ChronoUnit.DAYS));
        relayLog(relay, "/v1/messages", true, 100, "REPORTED", "END_TURN", null, now.minus(40, ChronoUnit.DAYS));
        relayLog(relay, "/v1/responses", true, 100, "REPORTED", "END_TURN", null, now.minus(1, ChronoUnit.DAYS));

        SourceTierDtos.Evidence week = status().evidence(7).block();
        SourceTierDtos.Evidence month = status().evidence(30).block();

        SourceTierDtos.PairEvidence weekMessages = pair(week, "anthropic", "MESSAGES");
        assertThat(weekMessages.figures().attempts()).isEqualTo(4);
        assertThat(weekMessages.members()).singleElement().satisfies(member -> {
            assertThat(member.tierId()).isEqualTo("relay");
            assertThat(member.accountId()).isEqualTo(relay);
            assertThat(member.figures().attempts()).isEqualTo(4);
        });
        SourceTierDtos.PairEvidence monthMessages = pair(month, "anthropic", "MESSAGES");
        assertThat(monthMessages.figures().attempts()).isEqualTo(5);
        assertThat(monthMessages.figures().interrupted()).isEqualTo(1);
        assertThat(monthMessages.meetsStandard()).isFalse();
        // The node was started for this test, so what it counted of the lint is of none of the days asked for.
        assertThat(monthMessages.criteria()).filteredOn(criterion -> !criterion.met()).extracting(SourceTierDtos.Criterion::name)
            .containsExactly("requests", "lintFailures", "unbilled");
        assertThat(monthMessages.criteria().get(1).value()).isNull();
        assertThat(pair(month, "anthropic", "CHAT").figures().attempts()).as("each entry has the attempts of its own path").isZero();
        assertThat(pair(month, "anthropic", "RESPONSES").figures().attempts()).isEqualTo(1);
    }

    private static SourceTierDtos.PairEvidence pair(SourceTierDtos.Evidence evidence, String vendor, String entry) {
        return evidence.pairs().stream().filter(pair -> pair.vendor().equals(vendor) && pair.entryEndpoint().equals(entry))
            .findFirst().orElseThrow();
    }

    @Test
    void thePlacementsAreReadFromTheAccountTable() {
        String official = account("Anthropic", "api-key", true);
        String kiro = account("BuilderId", "idc", false);
        configureAnthropic();

        SourceTierDtos.Placements placements = status().placements().block();

        assertThat(placements.accounts()).filteredOn(account -> account.accountId().equals(official))
            .singleElement().satisfies(account -> assertThat(account.tiers()).singleElement().satisfies(placed -> {
                assertThat(placed.tierId()).isEqualTo("official");
                assertThat(placed.rule()).isEqualTo("CATEGORY");
                assertThat(placed.active()).isTrue();
            }));
        assertThat(placements.accounts()).filteredOn(account -> account.accountId().equals(kiro))
            .singleElement().satisfies(account -> assertThat(account.tiers().getFirst().tierId()).isEqualTo("kiro"));
    }

    private void log(String tierRef, String selection, boolean success, Instant at) {
        String id = "st-" + UUID.randomUUID().toString().substring(0, 8);
        requests.add(id);
        db.sql("""
                INSERT INTO request_logs (request_id, path, status_code, success, created_at, source_tier_ref, source_tier_level,
                    source_tier_name, price_factor, tier_selection)
                VALUES (:id, '/v1/messages', :status, :success, :at, :ref, 1, 'n', 1, :selection)
                """)
            .bind("id", id).bind("status", success ? 200 : 502).bind("success", success).bind("at", at)
            .bind("ref", tierRef).bind("selection", selection).fetch().rowsUpdated().block();
    }

    private void relayLog(String accountId, String path, boolean success, long latencyMs, String usageSource, String stopReason,
        String degraded, Instant at) {
        String id = "st-" + UUID.randomUUID().toString().substring(0, 8);
        requests.add(id);
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO request_logs (request_id, account_id, path, status_code, success, latency_ms, created_at, source_tier_ref,
                    source_tier_level, source_tier_name, price_factor, tier_selection, source_dispatch, wire_model, usage_source,
                    stop_reason, degraded_features)
                VALUES (:id, :account, :path, :status, :success, :latency, :at, 'anthropic:relay', 1, 'n', 1, 'AUTO', 'OPENAI_CHAT',
                    'relay/sonnet', :usageSource, :stopReason, :degraded)
                """)
            .bind("id", id).bind("account", accountId).bind("path", path).bind("status", success ? 200 : 502).bind("success", success)
            .bind("latency", latencyMs).bind("at", at);
        spec = usageSource == null ? spec.bindNull("usageSource", String.class) : spec.bind("usageSource", usageSource);
        spec = stopReason == null ? spec.bindNull("stopReason", String.class) : spec.bind("stopReason", stopReason);
        spec = degraded == null ? spec.bindNull("degraded", String.class) : spec.bind("degraded", degraded);
        spec.fetch().rowsUpdated().block();
    }

    private void logWithoutTier(Instant at) {
        String id = "st-" + UUID.randomUUID().toString().substring(0, 8);
        requests.add(id);
        db.sql("INSERT INTO request_logs (request_id, path, status_code, success, created_at) VALUES (:id, '/v1/messages', 200, true, :at)")
            .bind("id", id).bind("at", at).fetch().rowsUpdated().block();
    }

    private String account(String provider, String authMethod, boolean enabled) {
        String id = "st-" + UUID.randomUUID().toString().substring(0, 8);
        db.sql("INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, enabled)"
                + " VALUES (:id, 'token', :provider, :auth, :enabled)")
            .bind("id", id).bind("provider", provider).bind("auth", authMethod).bind("enabled", enabled)
            .fetch().rowsUpdated().block();
        accounts.add(id);
        return id;
    }
}
