package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.ReactiveTransactionManager;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.proxy.ProxyFacade;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierEntry;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierRoute;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSelectorParser;
import tech.starryapi.sourcetier.SourceTierAdminService;
import tech.starryapi.sourcetier.SourceTierDtos;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The planner on the beans of the application: the runtime wired into the facade follows the capability of the release,
 * and a planner over the real registry and account cache plans the owner's example from the stored configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "starry.stats.aggregation-enabled=false"
})
class SourceTierPlannerPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    @Autowired DatabaseClient db;
    @Autowired ReactiveTransactionManager transactionManager;
    @Autowired SystemSettingRepository settings;
    @Autowired SourceTierRegistry registry;
    @Autowired SourceTierRuntimeInfo thisRelease;
    @Autowired TierRuntime tierRuntime;
    @Autowired ProxyFacade proxyFacade;
    @Autowired AccountPoolService accountPool;
    @Autowired ModelPoolKeyResolver resolver;
    @Autowired ModelCatalogProvider catalog;
    @Autowired PaymentSettingsService payments;
    @Autowired ModelPricingService pricing;

    private final List<String> accounts = new ArrayList<>();

    @BeforeEach
    void startFromNothingConfigured() {
        db.sql("DELETE FROM source_tier_vendors").fetch().rowsUpdated().block();
        db.sql("UPDATE system_settings SET setting_value = '0' WHERE setting_key = 'source_tier_config_version'")
            .fetch().rowsUpdated().block();
        registry.refresh().block();
    }

    @AfterEach
    void removeAccounts() {
        for (String id : accounts) {
            db.sql("DELETE FROM proxy_accounts WHERE account_id = :id").bind("id", id).fetch().rowsUpdated().block();
        }
        accounts.clear();
        accountPool.refreshCache().block();
    }

    @Test
    void theApplicationPlansOverTiersBecauseTheReleaseCanRunThem() {
        assertThat(thisRelease.nativeTiers()).isTrue();
        assertThat(tierRuntime).isNotSameAs(TierRuntime.disabled());
        assertThat(tierRuntime.planner()).isInstanceOf(DefaultSourceTierPlanner.class);
        assertThat(ReflectionTestUtils.getField(proxyFacade, "tierRuntime")).isSameAs(tierRuntime);
    }

    @Test
    void aPlannerOverTheRealRegistryAndAccountCachePlansTheOwnersExample() {
        DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(registry, resolver, catalog, accountPool, payments, pricing, TierSessionBindings.disabled());
        String official = account("Anthropic", "api-key", null);
        String kiro = account("BuilderId", "idc", null);
        accountPool.refreshCache().block();

        assertThat(planner.plan(request()).block()).as("nothing configured").isSameAs(TierPlan.UNTIERED);

        SourceTierRuntimeInfo everything = new SourceTierRuntimeInfo(true, true, true, pair -> PairStatus.VERIFIED);
        new SourceTierAdminService(db, transactionManager, settings, registry,
            new SourceTierValidator(catalog, resolver, everything), everything)
            .replace("anthropic", new SourceTierDtos.ReplaceVendor(0L, true, null, null, null, null, null, List.of(
                new SourceTierDtos.Tier("official", 1, "官方", null, List.of("OFFICIAL_API_KEY"), null, null, null),
                new SourceTierDtos.Tier("kiro", 2, "kiro反向代理", new java.math.BigDecimal("0.6"),
                    List.of("KIRO", "CLAUDE_OAUTH"), null, null, null)), List.of(), List.of()))
            .block();

        TierPlan plan = planner.plan(request()).block();

        assertThat(plan.tiered()).isTrue();
        assertThat(plan.configVersion()).isEqualTo(1L);
        assertThat(plan.levels()).extracting(TierPlan.Level::tierRef).containsExactly("anthropic:official", "anthropic:kiro");
        assertThat(plan.levels().get(1).factor()).isEqualByComparingTo("0.6");
        assertThat(plan.levels().get(1).responseHeaders())
            .containsEntry("X-Starry-Tier-Name", "kiro%E5%8F%8D%E5%90%91%E4%BB%A3%E7%90%86");
        assertThat(accountPool.cachedAccount(official)).isPresent();
        assertThat(plan.holds(plan.levels().get(0), accountPool.cachedAccount(official).orElseThrow())).isTrue();
        assertThat(plan.holds(plan.levels().get(1), accountPool.cachedAccount(kiro).orElseThrow())).isTrue();
    }

    @Test
    void theAllEndpointsSwitchSavedThroughTheAdministratorApiMovesAChatRequestToTheAnthropicRoute() {
        account("Anthropic", "api-key", null);
        account("BuilderId", "idc", null);
        accountPool.refreshCache().block();
        assertThat(thisRelease.unifyEndpoints()).isTrue();
        // The planner the application runs, over the real registry and account cache.
        TierRuntime.Planner planner = tierRuntime.planner();

        saveOwnersExample(false);
        TierPlan off = planner.plan(chatRequest()).block();
        assertThat(off.tiered()).isTrue();
        assertThat(off.unified()).isFalse();
        assertThat(off.levels()).extracting(TierPlan.Level::tierId).containsExactly("kiro");
        assertThat(off.levels().get(0).cells()).containsExactly(PoolCell.KIRO_COMPATIBLE);

        saveOwnersExample(true);
        TierPlan on = planner.plan(chatRequest()).block();
        assertThat(on.unified()).isTrue();
        assertThat(on.levels()).extracting(TierPlan.Level::tierId).containsExactly("official", "kiro");
        assertThat(on.levels().get(0).cells()).containsExactly(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE);
        // The configuration travelled through the table: the next version of it is the one the plan was made from.
        assertThat(on.configVersion()).isGreaterThan(off.configVersion());
    }

    @Test
    void aHintForATierWhoseAccountsAreSuspendedStaysPinnedBecauseTheTableStillKnowsThem() {
        String official = account("Anthropic", "api-key", null);
        String kiro = account("BuilderId", "idc", null);
        accountPool.refreshCache().block();
        saveOwnersExample(false);
        TierRuntime.Planner planner = tierRuntime.planner();
        // The health tests of the pool suspend the only official key: it is disabled, and it leaves the account cache.
        accountPool.suspend(official, "AUTO_TEST_FAILED", "health test failed").block();
        assertThat(accountPool.cachedAccount(official)).isEmpty();

        // The table gives the accounts that are out of service, with what tells their kind and nothing else.
        assertThat(accountPool.switchedOffAccounts().block()).extracting(account -> account.getAccountId())
            .contains(official).doesNotContain(kiro);
        assertThat(accountPool.switchedOffAccounts().block()).filteredOn(account -> official.equals(account.getAccountId()))
            .singleElement().satisfies(account -> {
                assertThat(account.getProvider()).isEqualTo("Anthropic");
                assertThat(account.getAuthMethod()).isEqualTo("api-key");
                assertThat(account.getAccessToken()).isNull();
            });

        // The pool the hint names has an account the route uses, and it cannot serve: the request is kept to that pool.
        TierPlan plan = planner.plan(request().withSelector(TierSelectorParser.parse(TierSelectorParser.Source.HEADER, "official"))).block();
        assertThat(plan.pinned()).isTrue();
        assertThat(plan.levels()).extracting(TierPlan.Level::tierId).containsExactly("official");
        // A hint for a pool the route has no account in at all is dropped, as ever, and so is the same hint with the account back.
        TierPlan chat = planner.plan(chatRequest().withSelector(TierSelectorParser.parse(TierSelectorParser.Source.HEADER, "official"))).block();
        assertThat(chat.pinned()).isFalse();
        assertThat(chat.levels()).extracting(TierPlan.Level::tierId).containsExactly("kiro");
        // The suffix is a demand, and is refused for the same pool.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> planner.plan(request()
            .withSelector(TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, "official"))).block()))
            .isInstanceOfSatisfying(tech.starryapi.common.ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SOURCE_TIER_NOT_OFFERED"));

        accountPool.resetState(official).block();
        TierPlan back = planner.plan(request().withSelector(TierSelectorParser.parse(TierSelectorParser.Source.HEADER, "official"))).block();
        assertThat(back.pinned()).isTrue();
        assertThat(back.memberless(back.levels().get(0))).isFalse();
    }

    /** The owner's example on the Anthropic vendor, with the all-endpoints switch as given. */
    private void saveOwnersExample(boolean unify) {
        SourceTierAdminService admin = new SourceTierAdminService(db, transactionManager, settings, registry,
            new SourceTierValidator(catalog, resolver, thisRelease), thisRelease);
        long version = admin.overview().block().vendors().stream().filter(vendor -> vendor.vendor().equals("anthropic"))
            .findFirst().orElseThrow().version();
        admin.replace("anthropic", new SourceTierDtos.ReplaceVendor(version, true, unify, null, null, null, null, List.of(
                new SourceTierDtos.Tier("official", 1, "官方", null, List.of("OFFICIAL_API_KEY"), null, null, null),
                new SourceTierDtos.Tier("kiro", 2, "kiro反向代理", new java.math.BigDecimal("0.6"),
                    List.of("KIRO", "CLAUDE_OAUTH"), null, null, null)), List.of(), List.of()))
            .block();
    }

    /** What the facade hands the planner for a Claude model asked through Chat Completions: the Kiro route. */
    private TierPlanRequest chatRequest() {
        ObjectNode body = new ObjectMapper().createObjectNode().put("model", "claude-sonnet-4.5").put("max_tokens", 100);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Kiro", TierRoute.KIRO,
            false, false, false, true, false, false, body, null, false, null, null).withEntry(TierEntry.CHAT);
    }

    private TierPlanRequest request() {
        ObjectNode body = new ObjectMapper().createObjectNode().put("model", "claude-sonnet-4.5").put("max_tokens", 100);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic", TierRoute.ANTHROPIC,
            false, false, false, true, false, false, body, null, false, null, null);
    }

    private String account(String provider, String authMethod, String baseUrl) {
        String id = "tp-" + UUID.randomUUID().toString().substring(0, 8);
        var insert = db.sql("INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, base_url, enabled)"
                + " VALUES (:id, 'token', :provider, :auth, :url, true)")
            .bind("id", id).bind("provider", provider).bind("auth", authMethod);
        (baseUrl == null ? insert.bindNull("url", String.class) : insert.bind("url", baseUrl)).fetch().rowsUpdated().block();
        accounts.add(id);
        return id;
    }
}
