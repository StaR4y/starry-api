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
