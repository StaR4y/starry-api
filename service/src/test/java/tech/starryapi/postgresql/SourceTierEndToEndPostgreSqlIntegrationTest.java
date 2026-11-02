package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.apikey.BillingReviewController;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.proxy.ProxyFacade;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.sourcetier.SourceTierAdminService;
import tech.starryapi.sourcetier.SourceTierDtos;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reference scenario from the client to the books, on the beans of the application and a real database (design 0, 5
 * and 11): a request enters the facade as a client sends it, the stored tier configuration is planned and selected over
 * the real account cache and the real admission, the reservation is made, leased to each tier it is dispatched to and
 * settled by {@code settle_api_usage_served}, the usage event takes the tier from the reservation, and the revenue
 * adds up per source pool. Only the three upstream clients are stand-ins.
 *
 * <p>Every test follows the money: what the user is charged is the standard price times the factor of the tier that
 * served, the rest of the reservation goes back, and a reservation that cannot be settled yet is attributed to the
 * tier it was last dispatched to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "spring.task.scheduling.enabled=false",
    "starry.stats.aggregation-enabled=false"
})
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class SourceTierEndToEndPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String MODEL = "claude-sonnet-4.5";
    /** 8,000 tokens in and 1,000 out. */
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult ANSWER = new PlatformCompletionResult("OK", USAGE);

    @MockitoBean KiroUpstreamClient kiroClient;
    @MockitoBean AnthropicUpstreamClient anthropicClient;
    @MockitoBean OpenAiUpstreamClient openAiClient;

    @Autowired ProxyFacade facade;
    @Autowired DatabaseClient db;
    @Autowired SourceTierAdminService tierAdmin;
    @Autowired SourceTierRegistry registry;
    @Autowired AccountPoolService accountPool;
    @Autowired AccountAdmissionStore admissionStore;
    @Autowired ModelPricingService pricing;
    @Autowired PaymentSettingsService payments;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> accounts = new ArrayList<>();
    /** The accounts the upstream clients were called with, in order. */
    private final List<String> dispatched = new ArrayList<>();
    private final Map<String, Throwable> failures = new LinkedHashMap<>();
    private BillingQuote standard;

    @BeforeEach
    void startFromNothingConfigured() {
        db.sql("DELETE FROM proxy_accounts").fetch().rowsUpdated().block();
        db.sql("DELETE FROM source_tier_vendors").fetch().rowsUpdated().block();
        db.sql("UPDATE system_settings SET setting_value = '0' WHERE setting_key = 'source_tier_config_version'")
            .fetch().rowsUpdated().block();
        registry.refresh().block();
        accountPool.refreshCache().block();
        standard = pricing.billingQuote(MODEL, false, payments.config().block().apiBillingMultiplier()).block();
        when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> upstream(invocation.getArgument(0)));
        when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> upstream(invocation.getArgument(0)));
        when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> upstream(invocation.getArgument(0)));
    }

    @AfterEach
    void removeAccounts() {
        db.sql("DELETE FROM proxy_accounts").fetch().rowsUpdated().block();
        accounts.clear();
        accountPool.refreshCache().block();
    }

    // ---- the reference scenario ----

    @Test
    void anOfficialKeyThenKiroFromTheRequestToTheRevenuePerSourcePool() {
        configureAnthropic();
        String official = account("Anthropic", "api-key");
        String kiro = account("BuilderId", "idc");
        accountPool.refreshCache().block();
        Client client = client("10");

        // The best tier serves, at the standard price.
        ResponseEntity<?> first = messages(client, "session-a");
        assertThat(dispatched).containsExactly(official);
        assertServed(first, "official", "1");
        Map<String, Object> firstReservation = reservations(client).getLast();
        assertThat(firstReservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:official")
            .containsEntry("last_dispatched_tier_ref", "anthropic:official");
        assertThat(firstReservation.get("served_tier_level")).isEqualTo((short) 1);
        assertThat((BigDecimal) firstReservation.get("actual_credits")).isEqualByComparingTo(cost("1"));
        // The reservation covered the dearest tier and everything above what was charged went back.
        assertThat((BigDecimal) firstReservation.get("reserved_credits")).isEqualByComparingTo(estimate(firstReservation, "anthropic:official"));
        assertThat((BigDecimal) firstReservation.get("reserved_credits")).isGreaterThan(cost("1"));
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(cost("1")));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");

        // The first tier is full: busy is unavailable, and Kiro serves at 0.6 of the standard price.
        makeBusy(official);
        ResponseEntity<?> second = messages(client, "session-b");
        assertThat(dispatched).containsExactly(official, kiro);
        assertServed(second, "kiro", "2");
        Map<String, Object> secondReservation = reservations(client).getLast();
        assertThat(secondReservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:kiro")
            .containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
        assertThat((BigDecimal) secondReservation.get("served_price_factor")).isEqualByComparingTo("0.6");
        assertThat((BigDecimal) secondReservation.get("actual_credits")).isEqualByComparingTo(cost("0.6"));
        assertThat((BigDecimal) secondReservation.get("actual_credits")).isLessThan(cost("1"));
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(cost("1")).subtract(cost("0.6")));

        // The better tier is free again, but the session that fell stays where it is; a new session starts from the top.
        free(official);
        messages(client, "session-b");
        messages(client, "session-c");
        assertThat(dispatched).containsExactly(official, kiro, kiro, official);

        // Revenue per source pool: the pool that served is the pool that is paid.
        Map<String, BigDecimal> perPool = revenuePerPool(client);
        assertThat(perPool).containsOnlyKeys("anthropic:official", "anthropic:kiro");
        assertThat(perPool.get("anthropic:official")).isEqualByComparingTo(cost("1").multiply(new BigDecimal("2")));
        assertThat(perPool.get("anthropic:kiro")).isEqualByComparingTo(cost("0.6").multiply(new BigDecimal("2")));
        assertThat(events(client)).extracting(event -> event.get("source_tier_ref"))
            .containsExactlyInAnyOrder("anthropic:official", "anthropic:kiro", "anthropic:kiro", "anthropic:official");
        // Nothing is left reserved and nobody was charged more than the pool that served.
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10")
            .subtract(cost("1").multiply(new BigDecimal("2"))).subtract(cost("0.6").multiply(new BigDecimal("2"))));
        // Each attempt is one row of the request log, with the pool that took it.
        assertThat(logs(client)).extracting(row -> row.get("source_tier_ref")).containsExactlyInAnyOrder(
            "anthropic:official", "anthropic:kiro", "anthropic:kiro", "anthropic:official");
        assertThat(logs(client)).allSatisfy(row -> assertThat(row.get("tier_selection")).isEqualTo("AUTO"));
    }

    // ---- a definite failure moves on; an uncertain one stays and is attributed ----

    @Test
    void aRequestThatFailedDefinitelyInTheFirstTierIsServedByKiroAndNothingIsHeldForReview() {
        configureAnthropic();
        String official = account("Anthropic", "api-key");
        String kiro = account("BuilderId", "idc");
        accountPool.refreshCache().block();
        failures.put(official, new UpstreamException(429, "slow down", true));
        Client client = client("10");

        ResponseEntity<?> response = messages(client, "session-a");

        assertThat(dispatched).containsExactly(official, kiro);
        assertServed(response, "kiro", "2");
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:kiro");
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(cost("0.6"));
        assertThat(reviews(client)).isEmpty();
        assertThat(logs(client)).extracting(row -> row.get("success"), row -> row.get("source_tier_ref"))
            .containsExactlyInAnyOrder(tuple(false, "anthropic:official"), tuple(true, "anthropic:kiro"));
    }

    @Test
    void aRefusalForWantOfQuotaIsNotAGenerationSoItNeverLocksTheUserIntoReview() {
        configureAnthropic();
        String official = account("Anthropic", "api-key");
        String kiro = account("BuilderId", "idc");
        accountPool.refreshCache().block();
        failures.put(official, new UpstreamException(402, "insufficient_quota", true));
        Client client = client("10");

        // The first tier refuses with 402 and the second one serves.
        messages(client, "session-a");
        assertThat(dispatched).containsExactly(official, kiro);
        assertThat(reservations(client).getLast()).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:kiro");

        // Every tier refuses with 402: the reservation is released as unbilled and the user can go on.
        failures.put(kiro, new UpstreamException(402, "insufficient_quota", true));
        assertThatThrownBy(() -> messages(client, "session-z")).isInstanceOf(ApiException.class);
        assertThat(reservations(client).getLast()).containsEntry("status", "RELEASED").containsEntry("actual_credits", null);
        assertThat(reviews(client)).isEmpty();
        // Both accounts were set aside for having no quota left; an account that has some serves the user at once.
        failures.clear();
        account("BuilderId", "idc");
        accountPool.refreshCache().block();
        messages(client, "session-y");
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(cost("0.6")).subtract(cost("0.6")));
    }

    @Test
    void anUncertainFailureLeavesTheReservationForReviewAttributedToTheTierItWasDispatchedTo() {
        configureAnthropic();
        String official = account("Anthropic", "api-key");
        account("BuilderId", "idc");
        accountPool.refreshCache().block();
        failures.put(official, new TimeoutException("no answer"));
        Client client = client("10");

        assertThatThrownBy(() -> messages(client, "session-a")).isInstanceOf(ApiException.class);

        // The request may still be running at the official key, so Kiro was never asked and the money stays held.
        assertThat(dispatched).containsExactly(official);
        Map<String, Object> held = reservations(client).getLast();
        assertThat(held).containsEntry("status", "REVIEW_REQUIRED").containsEntry("served_tier_ref", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:official");
        assertThat(held.get("last_dispatched_at")).isNotNull();
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract((BigDecimal) held.get("reserved_credits")));

        // The review list names the pool and its quote, so that the reservation, which covers the dearest pool, is not
        // what gets settled.
        Map<String, Object> review = reviews(client).getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> lastDispatched = (Map<String, Object>) review.get("last_dispatched");
        assertThat(lastDispatched).containsEntry("tierRef", "anthropic:official").containsEntry("factor", "1");
        assertThat(review).containsKey("tier_plan");

        // The administrator settles it for what the pool charges; the event is credited to the pool dispatched last.
        settleReview(String.valueOf(held.get("request_hash")), cost("1"));
        assertThat(reservations(client).getLast()).containsEntry("status", "SETTLED");
        assertThat(events(client)).singleElement().satisfies(event -> {
            assertThat(event.get("source_tier_ref")).isEqualTo("anthropic:official");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("1");
        });
        assertThat(revenuePerPool(client)).containsOnlyKeys("anthropic:official");
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(cost("1")));
        // And the user is free to go on.
        failures.clear();
        messages(client, "session-b");
    }

    // ---- a vendor without tiers is settled as before ----

    @Test
    void aVendorWithoutTiersIsReservedAndSettledAsBeforeAndItsRevenueHasNoPool() {
        configureAnthropic();
        String openai = account("OpenAI", "api-key");
        accountPool.refreshCache().block();
        Client client = client("10");
        BillingQuote gpt = pricing.billingQuote("gpt-5.4", false, payments.config().block().apiBillingMultiplier()).block();

        ResponseEntity<?> response = chat(client, "gpt-5.4");

        assertThat(dispatched).containsExactly(openai);
        assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", null)
            .containsEntry("served_tier_level", null).containsEntry("served_price_factor", null)
            .containsEntry("last_dispatched_tier_ref", null).containsEntry("last_dispatched_at", null);
        assertThat(objectMapper.readTree((String) reservation.get("pricing")).has("tierPlan")).isFalse();
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(gpt.cost(USAGE));
        assertThat(events(client)).singleElement().satisfies(event -> {
            assertThat(event.get("source_tier_ref")).isNull();
            assertThat(event.get("price_factor")).isNull();
        });
        assertThat(revenuePerPool(client)).containsOnlyKeys("(no pool)");
        assertThat(logs(client)).singleElement().satisfies(row -> {
            assertThat(row.get("source_tier_ref")).isNull();
            assertThat(row.get("price_factor")).isNull();
            assertThat(row.get("tier_selection")).isNull();
        });
    }

    // ---- fixtures ----

    private Mono<PlatformCompletionResult> upstream(ProxyAccountEntity account) {
        dispatched.add(account.getAccountId());
        Throwable failure = failures.get(account.getAccountId());
        return failure == null ? Mono.just(ANSWER) : Mono.error(failure);
    }

    /** What the standard price times {@code factor} charges for the usage the upstream clients report. */
    private BigDecimal cost(String factor) {
        return standard.scaled(new BigDecimal(factor)).cost(USAGE);
    }

    private BigDecimal estimate(Map<String, Object> reservation, String tierRef) {
        for (JsonNode level : objectMapper.readTree((String) reservation.get("pricing")).path("tierPlan").path("levels")) {
            if (level.path("tierRef").asString().equals(tierRef)) {
                return level.path("estimate").decimalValue();
            }
        }
        throw new AssertionError("The reservation has no plan for " + tierRef);
    }

    private void configureAnthropic() {
        tierAdmin.replace("anthropic", new SourceTierDtos.ReplaceVendor(0L, true, null, null, null, null, null, List.of(
                new SourceTierDtos.Tier("official", 1, "official", null, List.of("OFFICIAL_API_KEY"), null, null, null),
                new SourceTierDtos.Tier("kiro", 2, "kiro", new BigDecimal("0.6"), List.of("KIRO", "CLAUDE_OAUTH"), null, null, null)),
                List.of(), List.of()))
            .block();
    }

    private String account(String provider, String authMethod) {
        String id = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        db.sql("INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, enabled)"
                + " VALUES (:id, 'token', :provider, :auth, true)")
            .bind("id", id).bind("provider", provider).bind("auth", authMethod).fetch().rowsUpdated().block();
        // A new account is limited to a few requests a minute and a burst of two (Kiro) until it has warmed up. These tests
        // are about the tiers, so the only account that is ever busy is the one a test makes busy.
        db.sql("UPDATE account_allocation_policies SET safe_concurrency = 100, safe_rpm = 0, burst_capacity = 0 WHERE account_id = :id")
            .bind("id", id).fetch().rowsUpdated().block();
        accounts.add(id);
        return id;
    }

    /** The account takes one request at a time and one is being served: admission turns the next one away. */
    private void makeBusy(String accountId) {
        db.sql("UPDATE account_allocation_policies SET safe_concurrency = 1 WHERE account_id = :id").bind("id", accountId)
            .fetch().rowsUpdated().block();
        AccountAdmissionStore.AdmissionDecision held = admissionStore.acquire(new AccountAdmissionStore.AdmissionRequest(
            accountId, "hold-" + UUID.randomUUID(), null, null, false, 100, 100, Duration.ofMinutes(10), null, null)).block();
        assertThat(held.accepted()).as("the slot of " + accountId).isTrue();
    }

    private void free(String accountId) {
        db.sql("UPDATE account_allocation_policies SET safe_concurrency = 100 WHERE account_id = :id").bind("id", accountId)
            .fetch().rowsUpdated().block();
    }

    private record Client(String userId, String keyId, String token) {
    }

    private Client client(String balance) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Client client = new Client("user-" + suffix, "key-" + suffix, "sk-e2e-" + UUID.randomUUID());
        executeAsDatabaseOwner(
            "INSERT INTO starry_api.admin_users(user_id, username, role, balance, password_hash, password_salt, enabled, first_login)"
                + " VALUES ('" + client.userId() + "', '" + client.userId() + "', 'USER', " + balance
                + ", repeat('a', 64), repeat('b', 32), TRUE, FALSE)",
            "INSERT INTO starry_api.api_keys(key_id, user_id, name, key_hash, key_prefix) VALUES ('" + client.keyId() + "', '"
                + client.userId() + "', 'e2e', '" + Hashing.sha256(client.token()) + "', 'sk-e2e')");
        return client;
    }

    /** A Claude Code request: the Messages endpoint with the session header Claude Code sends. */
    private ResponseEntity<?> messages(Client client, String session) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", MODEL).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", "hello " + session);
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("/v1/messages")
            .header("Authorization", "Bearer " + client.token()).header("x-claude-code-session-id", client.keyId() + "-" + session);
        return facade.messages(body, MockServerWebExchange.from(request)).block();
    }

    private ResponseEntity<?> chat(Client client, String model) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", "hello");
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("/v1/chat/completions")
            .header("Authorization", "Bearer " + client.token());
        return facade.chat(body, MockServerWebExchange.from(request)).block();
    }

    private static void assertServed(ResponseEntity<?> response, String tierId, String level) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo(tierId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo(level);
    }

    private void settleReview(String requestHash, BigDecimal credits) {
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(new AdminPrincipal("root", "root", "ADMIN", null, true));
        new BillingReviewController(db, adminAuth)
            .resolve(null, requestHash, new BillingReviewController.Resolution("SETTLE", "Verified upstream usage", credits)).block();
    }

    private List<Map<String, Object>> reviews(Client client) {
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(new AdminPrincipal("root", "root", "ADMIN", null, true));
        return new BillingReviewController(db, adminAuth).reviews(null).block().data().stream()
            .filter(review -> client.userId().equals(review.get("user_id"))).toList();
    }

    private List<Map<String, Object>> reservations(Client client) {
        return db.sql("""
                SELECT request_hash, status, reserved_credits, actual_credits, served_tier_ref, served_tier_level, served_price_factor,
                       last_dispatched_tier_ref, last_dispatched_at, pricing::text AS pricing
                FROM billing_reservations WHERE user_id = :user ORDER BY created_at, request_hash
                """).bind("user", client.userId()).fetch().all().collectList().block();
    }

    private List<Map<String, Object>> events(Client client) {
        return db.sql("SELECT source_tier_ref, price_factor, credits FROM api_usage_events WHERE key_id = :key ORDER BY created_at")
            .bind("key", client.keyId()).fetch().all().collectList().block();
    }

    private Map<String, BigDecimal> revenuePerPool(Client client) {
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        db.sql("SELECT COALESCE(source_tier_ref, '(no pool)') AS pool, sum(credits) AS credits FROM api_usage_events"
                + " WHERE key_id = :key GROUP BY 1 ORDER BY 1")
            .bind("key", client.keyId()).fetch().all().toStream()
            .forEach(row -> revenue.put((String) row.get("pool"), (BigDecimal) row.get("credits")));
        return revenue;
    }

    private List<Map<String, Object>> logs(Client client) {
        return db.sql("""
                SELECT success, source_tier_ref, source_tier_level, source_tier_name, price_factor, tier_selection
                FROM request_logs WHERE user_id = :user ORDER BY created_at
                """).bind("user", client.userId()).fetch().all().collectList().block();
    }

    private BigDecimal balance(Client client) {
        return db.sql("SELECT balance FROM admin_users WHERE user_id = :user").bind("user", client.userId())
            .map((row, metadata) -> row.get("balance", BigDecimal.class)).one().block();
    }

    private BigDecimal keyReserved(Client client) {
        return db.sql("SELECT reserved_credits FROM api_keys WHERE key_id = :key").bind("key", client.keyId())
            .map((row, metadata) -> row.get("reserved_credits", BigDecimal.class)).one().block();
    }
}
