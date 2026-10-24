package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import tech.starryapi.account.AccountAllocationDefaultsService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AllocationDefaultsDtos.Policy;
import tech.starryapi.account.AllocationDefaultsDtos.ResolveRequest;
import tech.starryapi.account.AllocationDefaultsDtos.UpdateDefaults;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.common.ApiException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "starry.stats.aggregation-enabled=false"
})
class AccountAllocationDefaultsPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
    @Autowired DatabaseClient db;
    @Autowired ObjectMapper json;
    @Autowired AccountAllocationDefaultsService defaults;
    @Autowired tech.starryapi.account.AccountPoolService accounts;

    /** Provider, auth method, base URL and the category a new account with these properties belongs to. */
    private static final List<Object[]> CLASSIFICATION = List.of(
        new Object[] {"OpenAI", "api-key", null, AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"OpenAI", "api-key", "", AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://api.openai.com/v1", AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://API.OPENAI.COM:443/v1/", AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://api.openai.com", AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"OpenAI", "api-key", "  https://api.openai.com/v1  ", AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"OpenAI", "apikey", null, AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"OpenAI", "openai-api-key", null, AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"OpenAI", "api-key", "http://api.openai.com/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://api.openai.com:8443/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://user@api.openai.com/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://user:pass@api.openai.com/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://api.openai.com.evil.example/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://evil-api.openai.com/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://api.openai.com/v1?x=1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://api.openai.com/v1#fragment", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "api-key", "https://api.anthropic.com/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI-compatible", "api-key", "https://api.openai.com/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"DeepSeek", "api-key", "https://api.deepseek.com/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"Google", "api-key", null, AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"my-gateway", "api-key", "https://gateway.example/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"Anthropic", "api-key", null, AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"Anthropic", "apikey", "https://api.anthropic.com/v1", AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"claude", "api-key", "https://api.anthropic.com", AccountAllocationCategory.OFFICIAL_API_KEY},
        new Object[] {"Anthropic", "api-key", "https://api.openai.com/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"Anthropic", "api-key", "https://proxy.example/v1", AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"codex", "api-key", null, AccountAllocationCategory.COMPATIBLE_API_KEY},
        new Object[] {"OpenAI", "codex", "https://chatgpt.com/backend-api/codex", AccountAllocationCategory.CODEX},
        new Object[] {"OpenAI", "codex", null, AccountAllocationCategory.CODEX},
        new Object[] {"codex", null, null, AccountAllocationCategory.CODEX},
        new Object[] {"Anthropic", "oauth", null, AccountAllocationCategory.CLAUDE_OAUTH},
        new Object[] {"claude", "setup-token", null, AccountAllocationCategory.CLAUDE_OAUTH},
        new Object[] {"Anthropic", null, null, AccountAllocationCategory.CLAUDE_OAUTH},
        new Object[] {"BuilderId", "idc", null, AccountAllocationCategory.KIRO},
        new Object[] {"Kiro", "social", null, AccountAllocationCategory.KIRO},
        new Object[] {"OpenAI", null, null, AccountAllocationCategory.KIRO},
        new Object[] {null, null, null, AccountAllocationCategory.KIRO});

    @BeforeEach void builtIns() {
        executeAsDatabaseOwner("DELETE FROM starry_api.system_settings WHERE setting_key LIKE 'account_allocation_default_%'");
    }

    @Test void theJavaCategoriesAndTheDatabaseClassifyEveryCaseTheSame() throws Exception {
        for (Object[] row : CLASSIFICATION) {
            var expected = (AccountAllocationCategory) row[3];
            String label = row[0] + "/" + row[1] + "/" + row[2];
            assertThat(AccountAllocationCategory.classify((String) row[0], (String) row[1], (String) row[2])).as(label).isEqualTo(expected);
            assertThat(ownerQuery("SELECT starry_api.classify_account_allocation(?, ?, ?)", (String) row[0], (String) row[1], (String) row[2]))
                .as(label).isEqualTo(expected.name());
        }
    }

    @Test void theBuiltInValuesOfTheDatabaseAreThoseOfTheJavaCategoriesAndOfThePreviousTrigger() throws Exception {
        for (var category : AccountAllocationCategory.values()) {
            var fromDatabase = json.readValue(ownerQuery("SELECT starry_api.account_allocation_builtin_defaults(?)", category.name()), Policy.class);
            assertThat(fromDatabase).as(category.name()).isEqualTo(category.builtIn());
            assertThat(ownerQuery("SELECT starry_api.account_allocation_defaults(?)", category.name()))
                .as("no setting means the built-ins").isNotNull();
            assertThat(json.readValue(ownerQuery("SELECT starry_api.account_allocation_defaults(?)", category.name()), Policy.class))
                .isEqualTo(category.builtIn());
        }
        // What V26 hard-coded.
        assertThat(AccountAllocationCategory.OFFICIAL_API_KEY.builtIn()).extracting(Policy::safeConcurrency, Policy::safeRpm, Policy::burstCapacity, Policy::maxSessions).containsExactly(14, 0, 0, 0);
        assertThat(AccountAllocationCategory.COMPATIBLE_API_KEY.builtIn()).extracting(Policy::safeConcurrency, Policy::safeRpm, Policy::burstCapacity, Policy::maxSessions).containsExactly(0, 0, 0, 0);
        assertThat(AccountAllocationCategory.CODEX.builtIn()).extracting(Policy::safeConcurrency, Policy::safeRpm, Policy::burstCapacity, Policy::maxSessions).containsExactly(4, 60, 4, 0);
        assertThat(AccountAllocationCategory.KIRO.builtIn()).extracting(Policy::safeConcurrency, Policy::safeRpm, Policy::burstCapacity, Policy::maxSessions).containsExactly(2, 30, 2, 0);
        assertThat(AccountAllocationCategory.CLAUDE_OAUTH.builtIn()).extracting(Policy::safeConcurrency, Policy::safeRpm, Policy::burstCapacity, Policy::maxSessions).containsExactly(2, 30, 2, 4);
    }

    @Test void newAccountsStartWithTheBuiltInsOfTheirCategoryAndTheAdmissionBucketsStartFull() {
        for (Object[] row : CLASSIFICATION) {
            var category = (AccountAllocationCategory) row[3];
            String id = insertAccount(row);
            var policy = storedPolicy(id);
            assertThat(policy).as(row[0] + "/" + row[1] + "/" + row[2]).isEqualTo(appliedTo(category.builtIn(), (String) row[0]));
            var buckets = db.sql("SELECT rpm_tokens,burst_tokens FROM account_admission_states WHERE account_id=:id").bind("id", id)
                .map((r, m) -> List.of(r.get("rpm_tokens", Number.class).doubleValue(), r.get("burst_tokens", Number.class).doubleValue())).one().block();
            assertThat(buckets).containsExactly((double) policy.safeRpm(), (double) policy.burstCapacity());
        }
    }

    @Test void configuredDefaultsApplyToEveryCreationPathAndNeverToExistingAccounts() throws Exception {
        String existing = insertAccount(new Object[] {"OpenAI", "api-key", null});
        var before = storedPolicy(existing);

        Map<String, Policy> configured = new LinkedHashMap<>();
        int index = 0;
        for (var category : AccountAllocationCategory.values()) {
            index++;
            int rpm = category == AccountAllocationCategory.CODEX || category == AccountAllocationCategory.KIRO ? 90 + index : index % 2 == 0 ? 40 + index : 0;
            configured.put(category.name(), new Policy(100 + index, rpm, rpm == 0 ? 0 : 3 + index,
                category.anthropicSettings() ? 7 + index : 0, 120 + index, index * 10, 5_000_000L + index, 200 + index,
                category.anthropicSettings() ? "safari18_4" : ""));
        }
        var saved = defaults.update(new UpdateDefaults(configured)).block();
        assertThat(saved.categories()).allSatisfy(entry -> {
            assertThat(entry.configured()).isEqualTo(configured.get(entry.category()));
            assertThat(entry.effective()).isEqualTo(configured.get(entry.category()));
            assertThat(entry.builtIn()).isEqualTo(AccountAllocationCategory.valueOf(entry.category()).builtIn());
        });

        // The matrix: the database applies the configured values of exactly the category Java computes.
        for (Object[] row : CLASSIFICATION) {
            var category = AccountAllocationCategory.classify((String) row[0], (String) row[1], (String) row[2]);
            assertThat(storedPolicy(insertAccount(row))).as(row[0] + "/" + row[1] + "/" + row[2])
                .isEqualTo(appliedTo(configured.get(category.name()), (String) row[0]));
        }
        // The creation API, the import and the Codex login (a plain repository save) all insert into proxy_accounts.
        var created = accounts.create(json.readValue("""
            {"accountId":"cfg-create","email":"cfg-create@example.test","accessToken":"t","provider":"OpenAI","authMethod":"api-key"}
            """, AccountDtos.CreateAccountRequest.class)).block();
        assertThat(storedPolicy(created.getAccountId()).safeConcurrency()).isEqualTo(configured.get("OFFICIAL_API_KEY").safeConcurrency());
        var imported = accounts.importAccounts(new AccountDtos.ImportAccountsRequest(List.of(json.readValue("""
            {"accountId":"cfg-import","email":"cfg-import@example.test","accessToken":"t","provider":"OpenAI","authMethod":"codex"}
            """, AccountDtos.CreateAccountRequest.class)), false)).block();
        assertThat(imported.created()).isEqualTo(1);
        assertThat(storedPolicy("cfg-import").safeConcurrency()).isEqualTo(configured.get("CODEX").safeConcurrency());
        // An explicit policy in the request still wins over the defaults.
        var explicit = accounts.create(json.readValue("""
            {"accountId":"cfg-explicit","email":"cfg-explicit@example.test","accessToken":"t","provider":"OpenAI","authMethod":"api-key",
             "allocationPolicy":{"safeConcurrency":3}}
            """, AccountDtos.CreateAccountRequest.class)).block();
        assertThat(storedPolicy(explicit.getAccountId()).safeConcurrency()).isEqualTo(3);

        assertThat(storedPolicy(existing)).as("existing accounts keep their policy").isEqualTo(before);

        // Replacing with nothing puts every category back to its built-in values.
        var reset = defaults.update(new UpdateDefaults(Map.of())).block();
        assertThat(reset.categories()).allSatisfy(entry -> {
            assertThat(entry.configured()).isNull();
            assertThat(entry.effective()).isEqualTo(entry.builtIn());
        });
        assertThat(storedPolicy(insertAccount(new Object[] {"OpenAI", "api-key", null}))).isEqualTo(AccountAllocationCategory.OFFICIAL_API_KEY.builtIn());
    }

    @Test void sessionLimitsAndTlsProfilesOnlyReachAnthropicAccounts() {
        var official = AccountAllocationCategory.OFFICIAL_API_KEY.builtIn();
        defaults.update(new UpdateDefaults(Map.of("OFFICIAL_API_KEY", new Policy(official.safeConcurrency(), 0, 0, 7, 600, 0,
            1_000_000L, 100, "chrome136")))).block();
        var openAi = storedPolicy(insertAccount(new Object[] {"OpenAI", "api-key", null}));
        var anthropic = storedPolicy(insertAccount(new Object[] {"Anthropic", "api-key", null}));
        assertThat(openAi.maxSessions()).isZero();
        assertThat(openAi.tlsProfile()).isEmpty();
        assertThat(anthropic.maxSessions()).isEqualTo(7);
        assertThat(anthropic.tlsProfile()).isEqualTo("chrome136");
        assertThat(defaults.resolve(new ResolveRequest("OpenAI", "api-key", null)).block().policy().maxSessions()).isZero();
        assertThat(defaults.resolve(new ResolveRequest("Anthropic", "api-key", "")).block().policy().tlsProfile()).isEqualTo("chrome136");
    }

    @Test void aMissingOrInvalidSettingFallsBackToTheBuiltInsOfThatCategoryOnly() {
        String valid = "{\"safeConcurrency\":9,\"safeRpm\":0,\"burstCapacity\":0,\"maxSessions\":0,\"sessionIdleSeconds\":600,"
            + "\"cooldownSeconds\":0,\"maxInflightTokens\":1000000,\"allocationPriority\":100,\"tlsProfile\":\"\"}";
        Map<AccountAllocationCategory, String> stored = new LinkedHashMap<>();
        stored.put(AccountAllocationCategory.OFFICIAL_API_KEY, valid);
        stored.put(AccountAllocationCategory.COMPATIBLE_API_KEY, "not json at all");
        stored.put(AccountAllocationCategory.CODEX, valid.replace("\"safeConcurrency\":9", "\"safeConcurrency\":10001"));
        stored.put(AccountAllocationCategory.CLAUDE_OAUTH, valid.replace("\"safeRpm\":0", "\"safeRpm\":10"));
        stored.put(AccountAllocationCategory.KIRO, valid.replace("\"tlsProfile\":\"\"", "\"tlsProfile\":\"firefox\""));
        stored.forEach((category, value) -> executeAsDatabaseOwner("INSERT INTO starry_api.system_settings(setting_key,setting_value) VALUES('"
            + category.settingKey() + "','" + value.replace("'", "''") + "')"));

        assertThat(storedPolicy(insertAccount(new Object[] {"OpenAI", "api-key", null})).safeConcurrency()).isEqualTo(9);
        assertThat(storedPolicy(insertAccount(new Object[] {"x", "api-key", "https://gw.example"}))).isEqualTo(AccountAllocationCategory.COMPATIBLE_API_KEY.builtIn());
        assertThat(storedPolicy(insertAccount(new Object[] {"OpenAI", "codex", null}))).isEqualTo(AccountAllocationCategory.CODEX.builtIn());
        assertThat(storedPolicy(insertAccount(new Object[] {"Anthropic", "oauth", null}))).as("burst 0 with a rate limit").isEqualTo(AccountAllocationCategory.CLAUDE_OAUTH.builtIn());
        assertThat(storedPolicy(insertAccount(new Object[] {"BuilderId", "idc", null}))).as("unknown TLS profile").isEqualTo(AccountAllocationCategory.KIRO.builtIn());
        // The API shows the same: invalid values count as unset, and the valid one is what is configured.
        var shown = defaults.get().block().categories();
        assertThat(shown).filteredOn(entry -> entry.configured() != null).extracting(entry -> entry.category()).containsExactly("OFFICIAL_API_KEY");
    }

    @Test void savingValidatesTheRangesTheRateLimitPairAndTheCategoryOfTheSessionSettings() {
        var base = AccountAllocationCategory.OFFICIAL_API_KEY.builtIn();
        assertSaveRejected("OFFICIAL_API_KEY", new Policy(base.safeConcurrency(), 10, 0, 0, 600, 0, 1_000_000L, 100, ""), "burstCapacity must be positive");
        assertSaveRejected("OFFICIAL_API_KEY", new Policy(base.safeConcurrency(), 0, 5, 0, 600, 0, 1_000_000L, 100, ""), "burstCapacity must be 0");
        assertSaveRejected("OFFICIAL_API_KEY", new Policy(10001, 0, 0, 0, 600, 0, 1_000_000L, 100, ""), "safeConcurrency");
        assertSaveRejected("OFFICIAL_API_KEY", new Policy(1, 0, 0, 0, 59, 0, 1_000_000L, 100, ""), "sessionIdleSeconds");
        assertSaveRejected("OFFICIAL_API_KEY", new Policy(1, 0, 0, 0, 600, 86401, 1_000_000L, 100, ""), "cooldownSeconds");
        assertSaveRejected("OFFICIAL_API_KEY", new Policy(1, 0, 0, 0, 600, 0, 0L, 100, ""), "maxInflightTokens");
        assertSaveRejected("OFFICIAL_API_KEY", new Policy(1, 0, 0, 0, 600, 0, 1_000_000L, 0, ""), "allocationPriority");
        assertSaveRejected("OFFICIAL_API_KEY", new Policy(1, 0, 0, 0, 600, 0, 1_000_000L, 100, "firefox"), "tlsProfile");
        assertSaveRejected("KIRO", new Policy(1, 0, 0, 3, 600, 0, 1_000_000L, 100, ""), "only apply to Anthropic accounts");
        assertSaveRejected("CODEX", new Policy(1, 0, 0, 0, 600, 0, 1_000_000L, 100, "chrome136"), "only apply to Anthropic accounts");
        assertSaveRejected("MYSTERY", base, "Unknown account category");
        // Nothing was stored by the rejected attempts.
        assertThat(defaults.get().block().categories()).allSatisfy(entry -> assertThat(entry.configured()).isNull());
    }

    @Test void resolveReportsTheCategoryAndThePolicyAFormWouldProduce() {
        defaults.update(new UpdateDefaults(Map.of("COMPATIBLE_API_KEY", new Policy(25, 0, 0, 0, 600, 30, 1_000_000L, 100, "")))).block();
        var gateway = defaults.resolve(new ResolveRequest("OpenAI-compatible", "api-key", "https://gateway.example/v1")).block();
        assertThat(gateway.category()).isEqualTo("COMPATIBLE_API_KEY");
        assertThat(gateway.policy().safeConcurrency()).isEqualTo(25);
        assertThat(gateway.policy().cooldownSeconds()).isEqualTo(30);
        // The form's own wording is normalised the way the create path does it before classifying.
        assertThat(defaults.resolve(new ResolveRequest("OpenAI", "api-key", "")).block().category()).isEqualTo("OFFICIAL_API_KEY");
        assertThat(defaults.resolve(new ResolveRequest("OpenAI", "api-key", "https://api.openai.com/v1")).block().policy().safeConcurrency()).isEqualTo(14);
        assertThat(defaults.resolve(new ResolveRequest("Anthropic", null, null)).block().category()).isEqualTo("OFFICIAL_API_KEY");
        assertThat(defaults.resolve(new ResolveRequest("DeepSeek", null, null)).block().category()).isEqualTo("COMPATIBLE_API_KEY");
        assertThat(defaults.resolve(new ResolveRequest("OpenAI", "codex", null)).block().category()).isEqualTo("CODEX");
        assertThat(defaults.resolve(new ResolveRequest("BuilderId", "idc", null)).block().category()).isEqualTo("KIRO");
        assertThat(defaults.resolve(new ResolveRequest("Anthropic", "oauth", null)).block().category()).isEqualTo("CLAUDE_OAUTH");
    }

    @Test void theSettingsSurviveAnExportAndRestoreOfTheSchema() throws Exception {
        var saved = new Policy(33, 120, 12, 0, 300, 45, 2_000_000L, 150, "");
        defaults.update(new UpdateDefaults(Map.of("KIRO", saved))).block();
        // The same pg_dump the data export runs: the schema, custom format, without owners.
        exec("pg_dump", "-U", "starry_api_bootstrap", "-d", "starry_api", "--schema", "starry_api", "--format", "custom", "--no-owner",
            "-f", "/tmp/defaults.dump");
        exec("createdb", "-U", "starry_api_bootstrap", "restore_check");
        exec("pg_restore", "-U", "starry_api_bootstrap", "-d", "restore_check", "--no-owner", "--exit-on-error", "/tmp/defaults.dump");
        String restored = exec("psql", "-U", "starry_api_bootstrap", "-d", "restore_check", "-At", "-c",
            "SELECT setting_key || '=' || setting_value FROM starry_api.system_settings WHERE setting_key LIKE 'account_allocation_default_%' ORDER BY 1");
        assertThat(restored).contains("account_allocation_default_kiro=" + json.writeValueAsString(saved));
        // A category that is back to its built-ins is stored as an empty value, and restored as one.
        assertThat(restored).contains("account_allocation_default_codex=\n");
        // ...and the restored database applies them: the trigger functions came with the schema.
        String applied = exec("psql", "-U", "starry_api_bootstrap", "-d", "restore_check", "-At", "-c",
            "SELECT starry_api.account_allocation_defaults('KIRO')::jsonb ->> 'safeConcurrency'");
        assertThat(applied.trim()).isEqualTo("33");
    }

    // ---- helpers ----

    private void assertSaveRejected(String category, Policy policy, String message) {
        assertThatThrownBy(() -> defaults.update(new UpdateDefaults(Map.of(category, policy))).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getCode()).isEqualTo("ALLOCATION_DEFAULTS_INVALID");
                assertThat(error.getMessage()).contains(message);
            });
    }

    /** What the trigger stores for an account: session limits and TLS profiles only exist for Anthropic. */
    private static Policy appliedTo(Policy policy, String provider) {
        boolean anthropic = provider != null && List.of("anthropic", "claude").contains(provider.toLowerCase());
        return new Policy(policy.safeConcurrency(), policy.safeRpm(), policy.burstCapacity(), anthropic ? policy.maxSessions() : 0,
            policy.sessionIdleSeconds(), policy.cooldownSeconds(), policy.maxInflightTokens(), policy.allocationPriority(),
            anthropic ? policy.tlsProfile() : "");
    }

    private String insertAccount(Object[] row) {
        String id = "alloc-" + UUID.randomUUID();
        var spec = db.sql("INSERT INTO proxy_accounts(account_id,access_token,provider,auth_method,base_url) VALUES(:id,'t',:provider,:auth,:url)")
            .bind("id", id);
        spec = row[0] == null ? spec.bindNull("provider", String.class) : spec.bind("provider", row[0]);
        spec = row[1] == null ? spec.bindNull("auth", String.class) : spec.bind("auth", row[1]);
        spec = row[2] == null ? spec.bindNull("url", String.class) : spec.bind("url", row[2]);
        spec.fetch().rowsUpdated().block();
        return id;
    }

    private Policy storedPolicy(String accountId) {
        return db.sql("""
            SELECT safe_concurrency,safe_rpm,burst_capacity,max_sessions,session_idle_seconds,cooldown_seconds,
                   max_inflight_tokens,allocation_priority,tls_profile
            FROM account_allocation_policies WHERE account_id=:id
            """).bind("id", accountId).map((r, m) -> new Policy(r.get("safe_concurrency", Integer.class), r.get("safe_rpm", Integer.class),
                r.get("burst_capacity", Integer.class), r.get("max_sessions", Integer.class), r.get("session_idle_seconds", Integer.class),
                r.get("cooldown_seconds", Integer.class), r.get("max_inflight_tokens", Long.class), r.get("allocation_priority", Integer.class),
                r.get("tls_profile", String.class))).one().block();
    }

    /** Runs a single-value query as the schema owner, the only role that may execute the trigger helper functions. */
    private static String ownerQuery(String sql, String... arguments) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATION_USERNAME, MIGRATION_PASSWORD)) {
            try (var role = connection.createStatement()) { role.execute("SET ROLE starry_api_owner"); }
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < arguments.length; i++) statement.setString(i + 1, arguments[i]);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    return result.getString(1);
                }
            }
        }
    }

    private static String exec(String... command) throws Exception {
        var result = POSTGRES.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + "\n" + result.getStderr()).isZero();
        return result.getStdout();
    }
}
