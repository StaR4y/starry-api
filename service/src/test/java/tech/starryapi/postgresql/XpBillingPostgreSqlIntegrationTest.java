package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.apikey.BillingReviewController;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiChatRelayClient;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.platform.xproto.ChatCompletionAccumulator.Completion;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.RelayAbortedAfterDispatchException;
import tech.starryapi.platform.xproto.RelayErrorMapper;
import tech.starryapi.proxy.ProxyFacade;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.RequestBounds;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.XpLearnedLimits;
import tech.starryapi.proxy.tier.XpSkipCounts;
import tech.starryapi.sourcetier.SourceTierAdminService;
import tech.starryapi.sourcetier.SourceTierDtos;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo.PairStatus;
import tech.starryapi.sourcetier.SourceTierValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The money of a request that a relay serves, on the beans of the application and a real database (design 5.2, 5.3 and
 * 7.8, invariants I2, I3 and I7): the owner's example with the relay as the first tier and Kiro as the second, where the
 * relay is the only stand-in besides the upstream clients. What is looked at is the reservation and what becomes of it.
 * A relay's attempt is generated once and the operator carries it: it is charged at the price of its tier and never
 * above what the tier reserved, and it never ends in a review, whether it is answered, fails, is cut off, or the client
 * gives up at any moment from the dispatch to the end of the settlement. An upstream account that has the request when the
 * client gives up is left for review, as it always was.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "spring.task.scheduling.enabled=false",
    "starry.stats.aggregation-enabled=false"
})
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class XpBillingPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String MODEL = "claude-sonnet-4.5";
    private static final String CLIENT_DISCONNECTED = "Client disconnected; cross-protocol attempt released";
    /**
     * What a Responses request that names no limit is held to (D14): the default of the gateway, 16,000 tokens, which MODEL
     * writes (the model list shows 64,000 for it) and the application settings leave as they are.
     */
    private static final int DEFAULT_OUTPUT = 16_000;
    /** What a small request (a few hundred bytes of input) is reserved for as its input. */
    private static final int SMALL_INPUT = 5_000;
    /** What Kiro reports: 8,000 tokens in and 1,000 out. */
    private static final PlatformUsage KIRO_USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult KIRO_ANSWER = new PlatformCompletionResult("from kiro", KIRO_USAGE);
    /** What the relay reports, within the bounds of the request. */
    private static final PlatformUsage RELAY_USAGE = new PlatformUsage(500, 200, BigDecimal.ZERO, 0, 0, 0);

    /** The release offers the pairs of the first cross-protocol phase on trial, so that the relay can serve in this test. */
    @TestConfiguration
    static class OffersRelays {

        @Bean
        @Primary
        TierRuntime relayTierRuntime(
            SourceTierRegistry registry,
            ModelPoolKeyResolver keyResolver,
            ModelCatalogProvider modelCatalog,
            AccountPoolService accountPool,
            PaymentSettingsService paymentSettings,
            ModelPricingService modelPricing,
            TierSessionBindings bindings,
            XpSkipCounts skipCounts,
            XpLearnedLimits learnedLimits
        ) {
            PairRegistry offered = new PairRegistry(List.of(
                new PairRegistry.Registration(PairRegistry.SourceKind.OPENAI_CHAT_RELAY, PairRegistry.Entry.CHAT, "anthropic",
                    PairRegistry.Status.EXPERIMENTAL),
                new PairRegistry.Registration(PairRegistry.SourceKind.OPENAI_CHAT_RELAY, PairRegistry.Entry.MESSAGES, "anthropic",
                    PairRegistry.Status.EXPERIMENTAL),
                new PairRegistry.Registration(PairRegistry.SourceKind.OPENAI_CHAT_RELAY, PairRegistry.Entry.RESPONSES, "anthropic",
                    PairRegistry.Status.EXPERIMENTAL),
                new PairRegistry.Registration(PairRegistry.SourceKind.OPENAI_CHAT_RELAY, PairRegistry.Entry.CHAT, "openai",
                    PairRegistry.Status.EXPERIMENTAL)));
            return new TierRuntime(new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, accountPool, paymentSettings,
                modelPricing, bindings, true, true, offered, skipCounts, learnedLimits), bindings, learnedLimits);
        }
    }

    @MockitoBean KiroUpstreamClient kiroClient;
    @MockitoBean AnthropicUpstreamClient anthropicClient;
    @MockitoBean OpenAiUpstreamClient openAiClient;
    @MockitoBean OpenAiChatRelayClient relayClient;
    @MockitoSpyBean ApiKeyService apiKeys;

    @Autowired ProxyFacade facade;
    @Autowired DatabaseClient db;
    @Autowired ReactiveTransactionManager transactionManager;
    @Autowired SystemSettingRepository settings;
    @Autowired SourceTierRegistry registry;
    @Autowired AccountPoolService accountPool;
    @Autowired ModelPoolKeyResolver resolver;
    @Autowired ModelCatalogProvider catalog;
    @Autowired ModelPricingService pricing;
    @Autowired PaymentSettingsService payments;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RelayErrorMapper relayErrors = new RelayErrorMapper(objectMapper);
    private final List<String> accounts = new ArrayList<>();
    /** The bodies the relay was asked, in order. */
    private final List<JsonNode> relayBodies = new CopyOnWriteArrayList<>();
    private BillingQuote standard;
    private String relayId;
    private String kiroId;

    @BeforeEach
    void startFromNothingConfigured() {
        db.sql("DELETE FROM proxy_accounts").fetch().rowsUpdated().block();
        db.sql("DELETE FROM source_tier_vendors").fetch().rowsUpdated().block();
        db.sql("UPDATE system_settings SET setting_value = '0' WHERE setting_key = 'source_tier_config_version'")
            .fetch().rowsUpdated().block();
        registry.refresh().block();
        standard = pricing.billingQuote(MODEL, false, payments.config().block().apiBillingMultiplier()).block();
        kiroAnswers(() -> Mono.just(KIRO_ANSWER));
        relayAnswers(() -> Mono.just(relayAnswer(RELAY_USAGE)));
    }

    @AfterEach
    void removeAccounts() {
        db.sql("DELETE FROM proxy_accounts").fetch().rowsUpdated().block();
        accounts.clear();
        accountPool.refreshCache().block();
    }

    // ---- answered ----

    @Test
    void aRelaysAnswerIsChargedAtThePriceOfItsTierAndTheRestOfTheReservationGoesBack() {
        Client client = client("10");
        ownersExample(client, false, 8192);

        ResponseEntity<?> response = chat(client, chatBody("hello"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(relayBodies).hasSize(1);
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay")
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(standard.cost(RELAY_USAGE));
        assertThat((BigDecimal) reservation.get("reserved_credits")).isGreaterThan((BigDecimal) reservation.get("actual_credits"));
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(standard.cost(RELAY_USAGE)));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(events(client)).singleElement().satisfies(event -> {
            assertThat(event.get("source_tier_ref")).isEqualTo("anthropic:relay");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("1");
        });
        assertThat(logs(client)).singleElement().satisfies(row -> {
            assertThat(row.get("source_dispatch")).isEqualTo("OPENAI_CHAT");
            assertThat(row.get("wire_model")).isEqualTo("relay-sonnet");
            assertThat(row.get("usage_source")).isEqualTo("REPORTED");
        });
        assertThat(reviews(client)).isEmpty();
    }

    @Test
    void aRelayThatReportsMoreThanItsTierReservedIsNeverChargedAboveTheReservation() {
        Client client = client("10");
        ownersExample(client, false, 8192);
        relayAnswers(() -> Mono.just(relayAnswer(new PlatformUsage(900_000_000L, 900_000_000L, BigDecimal.ZERO, 0, 0, 0))));

        chat(client, chatBody("hello"));

        // The usage is held to the bounds of the tier, and the cost of that to the estimate it was reserved by.
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay");
        BigDecimal reserved = (BigDecimal) reservation.get("reserved_credits");
        BigDecimal charged = (BigDecimal) reservation.get("actual_credits");
        assertThat(charged).isPositive().isLessThanOrEqualTo(reserved);
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(charged));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(logs(client)).singleElement().satisfies(row -> assertThat(row.get("usage_source")).isEqualTo("CLAMPED"));
        assertThat(reviews(client)).isEmpty();
    }

    // ---- the Responses entry ----

    @Test
    void aRelaysAnswerToTheResponsesEntryIsChargedAtThePriceOfItsTierAndTheRestOfTheReservationGoesBack() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);

        ResponseEntity<?> response = responses(client, responsesBody("hello"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        JsonNode answer = (JsonNode) response.getBody();
        assertThat(answer.path("status").asString()).isEqualTo("completed");
        assertThat(answer.path("output_text").asString()).isEqualTo("from the relay");
        assertThat(relayBodies).hasSize(1);
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay")
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(standard.cost(RELAY_USAGE));
        assertThat((BigDecimal) reservation.get("reserved_credits")).isGreaterThan((BigDecimal) reservation.get("actual_credits"));
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(standard.cost(RELAY_USAGE)));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(events(client)).singleElement().satisfies(event -> {
            assertThat(event.get("source_tier_ref")).isEqualTo("anthropic:relay");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("1");
        });
        assertThat(logs(client)).singleElement().satisfies(row -> {
            assertThat(row.get("source_dispatch")).isEqualTo("OPENAI_CHAT");
            assertThat(row.get("wire_model")).isEqualTo("relay-sonnet");
            assertThat(row.get("usage_source")).isEqualTo("REPORTED");
        });
        assertThat(reviews(client)).isEmpty();
    }

    /**
     * Codex names no output limit, so the limit a relay is held to is the gateway's own bound, and an answer that reaches it
     * is a finished one to the client (an incomplete response is a failed stream to Codex, which sends the request again): what
     * the relay wrote is charged at the price of its tier, and not above what the tier reserved.
     */
    @Test
    void anAnswerOfTheResponsesEntryCutAtTheGatewaysLimitIsFinishedForTheClientAndChargedForWhatTheRelayWrote() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        relayAnswers(() -> {
            ObjectNode reported = objectMapper.createObjectNode();
            reported.put("prompt_tokens", 500).put("completion_tokens", 900_000);
            return Mono.just(new Completion("cut off in the mi", "", List.of(), PlatformCompletionResult.StopReason.MAX_TOKENS, reported,
                "length"));
        });

        ResponseEntity<?> response = responses(client, responsesBody("hello"));

        JsonNode answer = (JsonNode) response.getBody();
        assertThat(answer.path("status").asString()).isEqualTo("completed");
        assertThat(answer.path("output_text").asString()).isEqualTo("cut off in the mi");
        assertThat(relayBodies.getFirst().path("max_tokens").asInt()).isEqualTo(DEFAULT_OUTPUT);
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay");
        BigDecimal charged = (BigDecimal) reservation.get("actual_credits");
        // What the relay says it wrote is held to the maximum output it was told it may write.
        assertThat(charged).isEqualByComparingTo(standard.cost(new PlatformUsage(500, DEFAULT_OUTPUT, BigDecimal.ZERO, 0, 0, 0)));
        assertThat(charged).isLessThanOrEqualTo((BigDecimal) reservation.get("reserved_credits"));
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(charged));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(logs(client)).singleElement().satisfies(row -> assertThat(row.get("usage_source")).isEqualTo("CLAMPED"));
        assertThat(reviews(client)).isEmpty();
    }

    /**
     * A request that names no limit is reserved for the output of the default, which is what it is held to (D11, D14), and one
     * that names its own limit for that: a user pays for the room the request may use, and the rest goes back with the settlement.
     */
    @Test
    void aResponsesRequestThatNamesNoLimitIsReservedForTheDefaultOutputAndOneThatNamesItsOwnForThat() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        BigDecimal defaultOutput = standard.output().multiply(BigDecimal.valueOf(DEFAULT_OUTPUT));
        BigDecimal named = standard.output().multiply(BigDecimal.valueOf(2000));
        BigDecimal anyInput = standard.estimate(SMALL_INPUT, 0);

        responses(client, responsesBody("hello"));
        responses(client, responsesBody("hello again").put("max_output_tokens", 2000));

        List<Map<String, Object>> reservations = reservations(client);
        assertThat(reservations).hasSize(2).allSatisfy(reservation -> assertThat(reservation).containsEntry("status", "SETTLED"));
        BigDecimal unlimited = (BigDecimal) reservations.get(0).get("reserved_credits");
        BigDecimal limited = (BigDecimal) reservations.get(1).get("reserved_credits");
        assertThat(unlimited).isGreaterThan(defaultOutput).isLessThan(defaultOutput.add(anyInput));
        assertThat(limited).isGreaterThan(named).isLessThan(named.add(anyInput));
        assertThat(relayBodies.get(0).path("max_tokens").asInt()).isEqualTo(DEFAULT_OUTPUT);
        assertThat(relayBodies.get(1).path("max_tokens").asInt()).isEqualTo(2000);
        // Only what the relay wrote is charged, whatever was reserved.
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(standard.cost(RELAY_USAGE).multiply(BigDecimal.valueOf(2))));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
    }

    /**
     * The larger reservation is what a user with little balance meets: told it is out of balance, with the usual 402 and before
     * anything is dispatched or reserved, where 4,096 tokens would have been reserved and the request served. Naming a limit of its
     * own is how that user gets the request through.
     */
    @Test
    void aUserWhoCannotPayForTheDefaultOutputIsToldSoBeforeAnythingIsDispatchedAndCanNameALimitInstead() {
        BigDecimal defaultReservation = standard.output().multiply(BigDecimal.valueOf(DEFAULT_OUTPUT));
        BigDecimal oldReservation = standard.output().multiply(BigDecimal.valueOf(4096)).add(standard.estimate(SMALL_INPUT, 0));
        // Enough for what the request was reserved for before the default, and not for the output of the default.
        BigDecimal balance = defaultReservation.divide(BigDecimal.valueOf(2), 6, java.math.RoundingMode.HALF_UP);
        assertThat(balance).isGreaterThan(oldReservation).isLessThan(defaultReservation);
        Client client = client(balance.toPlainString());
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);

        assertThatThrownBy(() -> responses(client, responsesBody("hello"))).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(error.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
        });

        assertThat(relayBodies).isEmpty();
        verify(kiroClient, never()).complete(any(), any(), anyString());
        assertThat(reservations(client)).isEmpty();
        assertThat(logs(client)).isEmpty();
        assertThat(balance(client)).isEqualByComparingTo(balance);
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();

        // The same request with a limit of its own is reserved for that, and served.
        ResponseEntity<?> served = responses(client, responsesBody("hello").put("max_output_tokens", 4096));
        assertThat(served.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(relayBodies).hasSize(1);
        assertThat(relayBodies.getFirst().path("max_tokens").asInt()).isEqualTo(4096);
    }

    /**
     * The same bound, when the relay lost the only call of its answer to it (a big file written by one command, as the first
     * action of a turn): told as an incomplete response it would be asked for five more times, each charged. It is an
     * interruption instead: released for good, nothing is charged, nothing is left for review, and the client's retry of the same
     * request is Kiro's, with the relay kept from the session.
     */
    @Test
    void anAnswerOfTheResponsesEntryThatLostItsOnlyCallToTheGatewaysLimitIsReleasedForGoodAndTheRetryIsKiros() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        relayAnswers(() -> {
            ObjectNode reported = objectMapper.createObjectNode();
            reported.put("prompt_tokens", 500).put("completion_tokens", 900_000);
            return Mono.just(new Completion("", "", List.of(), PlatformCompletionResult.StopReason.MAX_TOKENS, reported, "length", true));
        });
        kiroAnswers(() -> Mono.just(KIRO_ANSWER));

        assertThatThrownBy(() -> responses(client, responsesBody("hello")))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(relayBodies.getFirst().path("max_tokens").asInt()).isEqualTo(DEFAULT_OUTPUT);
        verify(kiroClient, never()).complete(any(), any(), anyString());
        assertThat(reservations(client).getLast()).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat(balance(client)).isEqualByComparingTo("10");
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();
        assertThat(events(client)).isEmpty();
        assertThat(logs(client)).singleElement().satisfies(row -> {
            assertThat(row.get("success")).isEqualTo(false);
            assertThat(row.get("stop_reason")).as("counted apart from the relay's breakdowns").isEqualTo("WITHHELD");
            // The relay was paid for the answer, and the row says what it reported of it (held to what it was told it may write):
            // the operator's cost, in columns of its own. The user's tokens and credits are nothing, because nothing is charged.
            assertThat(row.get("withheld_input_tokens")).isEqualTo(500L);
            assertThat(row.get("withheld_output_tokens")).isEqualTo((long) DEFAULT_OUTPUT);
            assertThat(row.get("input_tokens")).isEqualTo(0L);
            assertThat(row.get("output_tokens")).isEqualTo(0L);
            assertThat((BigDecimal) row.get("credits")).isEqualByComparingTo("0");
        });

        // The retry is Kiro's, once, at the price of Kiro's tier; the relay is not asked again.
        for (int retry = 0; retry < 2; retry++) {
            ResponseEntity<?> response = responses(client, responsesBody("hello"));
            assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        }
        assertThat(relayBodies).hasSize(1);
        assertThat(balance(client)).isEqualByComparingTo(
            new BigDecimal("10").subtract(standard.scaled(new BigDecimal("0.6")).cost(KIRO_USAGE).multiply(BigDecimal.valueOf(2))));
    }

    /**
     * Answers the gateway holds back are not the relay's failures. A relay whose filter stops a few prompts, or whose answers
     * to a few long writes reach the gateway's bound, answered every time: it must not be counted against, cooled down, or
     * tested at the operator's expense, for every other session of every other user. Four from four sessions is enough to
     * cool a relay down when they are breakdowns (the control below).
     */
    @Test
    void fourAnswersTheRelaysFilterStoppedLeaveTheRelayInServiceForTheOtherSessions() {
        assertTheRelayStaysInService(() -> new Completion("I can't help with th", "", List.of(),
            PlatformCompletionResult.StopReason.CONTENT_FILTER, reported(500, 0), "content_filter"));
    }

    @Test
    void fourAnswersThatLostTheirCallToTheGatewaysLimitLeaveTheRelayInServiceForTheOtherSessions() {
        assertTheRelayStaysInService(() -> new Completion("", "", List.of(), PlatformCompletionResult.StopReason.MAX_TOKENS,
            reported(500, 900_000), "length", true));
    }

    private void assertTheRelayStaysInService(Supplier<Completion> held) {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        relayAnswers(() -> Mono.just(held.get()));
        Map<String, Object> before = allocationState(relayId);

        for (int session = 0; session < 4; session++) {
            int number = session;
            assertThatThrownBy(() -> responses(client, responsesBody("session number " + number)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }

        // The relay was asked each time, and kept from the session it answered for; nothing else was done to it.
        assertThat(relayBodies).hasSize(4);
        Map<String, Object> after = allocationState(relayId);
        assertThat(after.get("lifecycle_state")).as("%s", after).isEqualTo(before.get("lifecycle_state")).isNotIn("COOLDOWN", "DEGRADED");
        assertThat(after.get("cooldown_until")).isNull();
        assertThat(((Number) after.get("recent_error_ewma")).doubleValue()).isZero();
        assertThat(db.sql("SELECT enabled, error_count FROM proxy_accounts WHERE account_id = :id").bind("id", relayId).fetch().one().block())
            .containsEntry("enabled", true).containsEntry("error_count", 0);
        assertThat(logs(client)).hasSize(4).allSatisfy(row -> {
            assertThat(row.get("success")).isEqualTo(false);
            assertThat(row.get("stop_reason")).isEqualTo("WITHHELD");
        });
        assertThat(reservations(client)).allSatisfy(reservation -> assertThat(reservation).containsEntry("status", "RELEASED"));
        assertThat(balance(client)).isEqualByComparingTo("10");
        assertThat(reviews(client)).isEmpty();

        // Another session is served by the relay.
        relayAnswers(() -> Mono.just(relayAnswer(RELAY_USAGE)));
        ResponseEntity<?> other = responses(client, responsesBody("a healthy turn of another session"));
        assertThat(other.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(relayBodies).hasSize(5);
    }

    /** The control: the same four requests, each broken off by the relay after it was written, do cool the relay down. */
    @Test
    void fourBreakdownsOfTheRelayFromFourSessionsStillCoolItDown() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        relayAnswers(() -> Mono.error(new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent")));

        for (int session = 0; session < 4; session++) {
            int number = session;
            assertThatThrownBy(() -> responses(client, responsesBody("session number " + number)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }

        assertThat(allocationState(relayId).get("lifecycle_state")).isEqualTo("COOLDOWN");
        assertThat(logs(client)).allSatisfy(row -> assertThat(row.get("stop_reason")).isEqualTo("INTERRUPTED"));
        relayAnswers(() -> Mono.just(relayAnswer(RELAY_USAGE)));
        ResponseEntity<?> other = responses(client, responsesBody("a healthy turn of another session"));
        assertThat(other.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
    }

    @Test
    void aClientOfTheResponsesEntryThatGivesUpWhileTheRelayIsAskedIsReleasedForGoodAndNothingIsLeftForReview() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        relayAnswers(Mono::never);

        Disposable request = responsesRequest(client, responsesBody("hello")).subscribe(response -> { }, error -> { });
        await().atMost(Duration.ofSeconds(20)).until(() -> !relayBodies.isEmpty());
        await().atMost(Duration.ofSeconds(20)).until(() -> status(client, "DISPATCHED"));
        request.dispose();
        await().atMost(Duration.ofSeconds(20)).until(() -> !status(client, "DISPATCHED"));

        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat(balance(client)).isEqualByComparingTo("10");
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();
        verify(apiKeys).releaseUsage(any(), anyString(), eq(true), eq(CLIENT_DISCONNECTED));
    }

    @Test
    void aClientOfTheResponsesEntryThatGivesUpWhileKiroIsAskedAfterTheRelayRefusedIsLeftForReviewAsItAlwaysWas() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        relayAnswers(() -> Mono.error(relayErrors.fromResponse(404, error("model_not_found", "no such model"), null, "relay", "sk-secret")));
        List<Object> kiroCalls = new CopyOnWriteArrayList<>();
        kiroAnswers(() -> {
            kiroCalls.add("asked");
            return Mono.never();
        });

        Disposable request = responsesRequest(client, responsesBody("hello")).subscribe(response -> { }, error -> { });
        await().atMost(Duration.ofSeconds(20)).until(() -> !kiroCalls.isEmpty());
        request.dispose();
        await().atMost(Duration.ofSeconds(20)).until(() -> !status(client, "DISPATCHED"));

        assertThat(reservations(client).getLast()).containsEntry("status", "REVIEW_REQUIRED")
            .containsEntry("reason", "Client disconnected after dispatch").containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
        assertThat(reviews(client)).hasSize(1);
        verify(apiKeys, never()).releaseUsage(any(), anyString(), eq(true), eq(CLIENT_DISCONNECTED));
    }

    // ---- the bound of what a relay is sent ----

    @Test
    void anInlineImageCountsAsTheCapOfItsMappingAndDoesNotRaiseTheReservationToTheMillionTokenFloor() {
        Client client = client("100");
        ownersExample(client, true, 5_000);

        chat(client, chatBodyWithImage("data:image/png;base64," + "A".repeat(200_000)));

        // The relay was sent the image, and the tier reserved for it as its mapping says an image costs.
        assertThat(relayBodies).singleElement().satisfies(body -> assertThat(body.toString()).contains("image_url"));
        Map<String, Object> reservation = reservations(client).getLast();
        BigDecimal reserved = (BigDecimal) reservation.get("reserved_credits");
        long bound = RequestBounds.xpInputTokens(relayBodies.getFirst(), 5_000);
        assertThat(bound).isBetween(5_000L, 20_000L);
        // The body the plan measured names the model of the client where the one sent names the relay's: a few bytes apart.
        assertThat(reserved).isBetween(standard.estimate(bound - 100, 4096), standard.estimate(bound + 100, 4096));
        assertThat(reserved).isLessThan(standard.estimate(1_000_000L, 4096));
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay");
    }

    @Test
    void aTierOfRelaysAloneIsReservedForByWhatItsRelayIsSentWhereTheOfficialKeysOfTheSameRequestAreSentTheWholeImage() {
        Client client = client("100");
        BigDecimal multiplier = payments.config().block().apiBillingMultiplier();
        BigDecimal gptFactor = new BigDecimal("0.5");
        BillingQuote gpt = pricing.billingQuote("gpt-5.5", false, multiplier).block();
        openAiOwnersExample(client, 5_000);
        ObjectNode image = gptBodyWithImage("data:image/png;base64," + "A".repeat(200_000));

        // Pinned to the relay's tier: the reservation is that tier's, for what the relay is sent and not for the whole image
        // that the official keys of the other tier would be handed (which lifts a native bound to the floor for hidden input).
        ObjectNode pinned = image.deepCopy();
        pinned.put("model", "gpt-5.5@relay");
        ResponseEntity<?> pinnedResponse = chat(client, pinned);

        assertThat(pinnedResponse.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        long bound = RequestBounds.xpInputTokens(relayBodies.getFirst(), 5_000);
        assertThat(bound).isBetween(5_000L, 20_000L);
        BigDecimal pinnedReserved = (BigDecimal) reservations(client).getLast().get("reserved_credits");
        assertThat(pinnedReserved).isBetween(gpt.estimate(bound - 100, 4096), gpt.estimate(bound + 100, 4096));
        assertThat(pinnedReserved).isLessThan(gpt.estimate(1_000_000L, 4096));

        // Automatic: the dearer of the two tiers, which is the official keys' at their factor for the whole request.
        chat(client, image);

        BigDecimal autoReserved = (BigDecimal) reservations(client).getLast().get("reserved_credits");
        assertThat(autoReserved).isEqualByComparingTo(gpt.scaled(gptFactor).estimate(1_000_000L, 4096));
        assertThat(autoReserved).isGreaterThan(pinnedReserved);
        assertThat(reservations(client)).allSatisfy(reservation -> assertThat(reservation)
            .containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "openai:relay"));
        assertThat(reviews(client)).isEmpty();
    }

    @Test
    void aRequestKeptToATierOfRelaysWhoseOnlyRelayBrokeOffTheSessionIsHeldForNoMoreThanTheRelayNeededAndToldToTryAgain() {
        // Too little to pay for the floor for hidden input that the official keys of the other tier would be reserved for.
        Client client = client("1");
        BillingQuote gpt = pricing.billingQuote("gpt-5.5", false, payments.config().block().apiBillingMultiplier()).block();
        openAiOwnersExample(client, 5_000);
        relayAnswers(() -> Mono.error(new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent")));
        ObjectNode pinned = gptBodyWithImage("data:image/png;base64," + "A".repeat(200_000));
        pinned.put("model", "gpt-5.5@relay");
        assertThatThrownBy(() -> chat(client, pinned.deepCopy())).isInstanceOf(ApiException.class);
        assertThat(relayBodies).hasSize(1);

        // The client's own retry: the relay is kept from the session for two minutes, whether the tier is named by the suffix
        // or by the header, and nobody is asked. The request waits for the relay; it is not told it is out of money.
        ObjectNode retry = gptBodyWithImage("data:image/png;base64," + "A".repeat(200_000));
        for (String named : List.of("suffix", "header")) {
            MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("Authorization", "Bearer " + client.token());
            ObjectNode body = retry.deepCopy();
            if (named.equals("suffix")) {
                body.put("model", "gpt-5.5@relay");
            } else {
                request.header("X-Starry-Tier", "relay");
            }
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            assertThatThrownBy(() -> facade.chat(body, exchange).block()).as(named)
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
                    assertThat(error.getStatus().value()).isEqualTo(503);
                });
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).as(named).isNotNull();
        }

        assertThat(relayBodies).hasSize(1);
        List<Map<String, Object>> reservations = reservations(client);
        assertThat(reservations).hasSize(3).allSatisfy(reservation -> assertThat(reservation).containsEntry("status", "RELEASED"));
        BigDecimal floor = gpt.estimate(1_000_000L, 4096);
        assertThat((BigDecimal) reservations.get(1).get("reserved_credits")).isLessThan(floor).isLessThan(new BigDecimal("1"));
        assertThat((BigDecimal) reservations.get(2).get("reserved_credits")).isLessThan(floor).isLessThan(new BigDecimal("1"));
        assertThat(balance(client)).isEqualByComparingTo("1");
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();
    }

    @Test
    void anImageTheRelayIsOnlyPointedAtHasNothingToCountAndRaisesTheReservationToTheFloor() {
        Client client = client("100");
        ownersExample(client, true, 5_000);

        chat(client, chatBodyWithImage("https://images.example/cat.png"));

        BigDecimal reserved = (BigDecimal) reservations(client).getLast().get("reserved_credits");
        assertThat(reserved).isEqualByComparingTo(standard.estimate(1_000_000L, 4096));
    }

    // ---- the client gives up ----

    @Test
    void aClientThatGivesUpWhileTheRelayIsAskedIsReleasedForGoodAndNothingIsLeftForReview() {
        Client client = client("10");
        ownersExample(client, false, 8192);
        relayAnswers(Mono::never);

        Disposable request = chatRequest(client, chatBody("hello")).subscribe(response -> { }, error -> { });
        await().atMost(Duration.ofSeconds(20)).until(() -> !relayBodies.isEmpty());
        await().atMost(Duration.ofSeconds(20)).until(() -> status(client, "DISPATCHED"));
        request.dispose();
        await().atMost(Duration.ofSeconds(20)).until(() -> !status(client, "DISPATCHED"));

        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat(balance(client)).isEqualByComparingTo("10");
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();
        verify(apiKeys).releaseUsage(any(), anyString(), eq(true), eq(CLIENT_DISCONNECTED));
    }

    @Test
    void aClientThatGivesUpAfterTheRelayAnsweredAndBeforeTheSettlementReturnedIsReleasedForGood() {
        Client client = client("10");
        ownersExample(client, false, 8192);
        // The settlement never gets as far as the database: the answer is in, and nobody has been charged for it.
        doReturn(Mono.never()).when(apiKeys).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());

        Disposable request = chatRequest(client, chatBody("hello")).subscribe(response -> { }, error -> { });
        await().atMost(Duration.ofSeconds(20)).until(() -> !relayBodies.isEmpty());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            verify(apiKeys).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), eq("anthropic:relay"), eq(1), any()));
        request.dispose();
        await().atMost(Duration.ofSeconds(20)).until(() -> !status(client, "DISPATCHED"));

        assertThat(reservations(client).getLast()).containsEntry("status", "RELEASED").containsEntry("actual_credits", null);
        assertThat(balance(client)).isEqualByComparingTo("10");
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(events(client)).isEmpty();
        assertThat(reviews(client)).isEmpty();
    }

    @Test
    void aClientThatGivesUpAfterTheSettlementWasCommittedButBeforeItsResultReturnedKeepsTheCharge() {
        Client client = client("10");
        ownersExample(client, false, 8192);
        // The charge is made and the word of it is lost on the way back: the release that follows finds the books closed.
        doAnswer(invocation -> ((Mono<?>) invocation.callRealMethod()).then(Mono.never()))
            .when(apiKeys).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());

        Disposable request = chatRequest(client, chatBody("hello")).subscribe(response -> { }, error -> { });
        await().atMost(Duration.ofSeconds(20)).until(() -> status(client, "SETTLED"));
        request.dispose();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            verify(apiKeys).releaseUsage(any(), anyString(), eq(true), eq(CLIENT_DISCONNECTED)));

        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay");
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(standard.cost(RELAY_USAGE));
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(standard.cost(RELAY_USAGE)));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(events(client)).hasSize(1);
        assertThat(reviews(client)).isEmpty();
    }

    @Test
    void aClientThatGivesUpWhileKiroIsAskedAfterTheRelayRefusedIsLeftForReviewAsItAlwaysWas() {
        Client client = client("10");
        ownersExample(client, false, 8192);
        relayAnswers(() -> Mono.error(relayErrors.fromResponse(404, error("model_not_found", "no such model"), null, "relay", "sk-secret")));
        List<Object> kiroCalls = new CopyOnWriteArrayList<>();
        kiroAnswers(() -> {
            kiroCalls.add("asked");
            return Mono.never();
        });

        Disposable request = chatRequest(client, chatBody("hello")).subscribe(response -> { }, error -> { });
        await().atMost(Duration.ofSeconds(20)).until(() -> !kiroCalls.isEmpty());
        request.dispose();
        await().atMost(Duration.ofSeconds(20)).until(() -> !status(client, "DISPATCHED"));

        // Kiro has the request and may be generating: a native attempt is not the operator's loss, and a person looks at it.
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "REVIEW_REQUIRED").containsEntry("reason", "Client disconnected after dispatch")
            .containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract((BigDecimal) reservation.get("reserved_credits")));
        assertThat(reviews(client)).hasSize(1);
        verify(apiKeys, never()).releaseUsage(any(), anyString(), eq(true), eq(CLIENT_DISCONNECTED));
    }

    @Test
    void aClientThatGivesUpWhileKiroIsAskedForARequestTheRelayDoesNotTakeIsLeftForReviewAsItAlwaysWas() {
        Client client = client("10");
        // The gate lists another key: the relay is left out of the request, and Kiro is the only source.
        ownersExample(client, "key-of-somebody-else", false, 8192);
        List<Object> kiroCalls = new CopyOnWriteArrayList<>();
        kiroAnswers(() -> {
            kiroCalls.add("asked");
            return Mono.never();
        });

        Disposable request = chatRequest(client, chatBody("hello")).subscribe(response -> { }, error -> { });
        await().atMost(Duration.ofSeconds(20)).until(() -> !kiroCalls.isEmpty());
        request.dispose();
        await().atMost(Duration.ofSeconds(20)).until(() -> !status(client, "DISPATCHED"));

        assertThat(reservations(client).getLast()).containsEntry("status", "REVIEW_REQUIRED")
            .containsEntry("reason", "Client disconnected after dispatch").containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
        assertThat(relayBodies).isEmpty();
        assertThat(reviews(client)).hasSize(1);
    }

    @Test
    void aClientThatGivesUpOnAVendorWithoutTiersIsLeftForReviewAsItAlwaysWas() {
        Client client = client("10");
        account("OpenAI", "api-key", null);
        accountPool.refreshCache().block();
        List<String> calls = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            calls.add("openai");
            return Mono.never();
        }).when(openAiClient).complete(any(), any(), anyString());

        Disposable request = chatRequest(client, chatBody("gpt-5.4", "hello")).subscribe(response -> { }, error -> { });
        await().atMost(Duration.ofSeconds(20)).until(() -> !calls.isEmpty());
        request.dispose();
        await().atMost(Duration.ofSeconds(20)).until(() -> !status(client, "DISPATCHED"));

        assertThat(reservations(client).getLast()).containsEntry("status", "REVIEW_REQUIRED")
            .containsEntry("reason", "Client disconnected after dispatch");
        verify(apiKeys).releaseUsage(any(), anyString(), eq(false), eq("Client disconnected after dispatch"));
    }

    /**
     * The default output is for every Responses request that names no limit, a vendor without tiers included: it is
     * reserved for, sent to the official key, and a user who cannot pay for it is turned away with the usual 402 before anything is
     * dispatched or reserved. Chat requests of the same vendor keep their 4,096.
     */
    @Test
    void aResponsesRequestOfAVendorWithoutTiersIsReservedForTheDefaultOutputTooAndAChatRequestIsNot() {
        BillingQuote gpt = pricing.billingQuote("gpt-5.4", false, payments.config().block().apiBillingMultiplier()).block();
        BigDecimal defaultReservation = gpt.output().multiply(BigDecimal.valueOf(DEFAULT_OUTPUT));
        BigDecimal oldReservation = gpt.output().multiply(BigDecimal.valueOf(4096)).add(gpt.estimate(SMALL_INPUT, 0));
        BigDecimal poor = defaultReservation.divide(BigDecimal.valueOf(2), 6, java.math.RoundingMode.HALF_UP);
        assertThat(poor).isGreaterThan(oldReservation).isLessThan(defaultReservation);
        account("OpenAI", "api-key", null);
        accountPool.refreshCache().block();
        List<JsonNode> official = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            official.add(invocation.getArgument(1));
            return Mono.just(new PlatformCompletionResult("from openai", KIRO_USAGE));
        }).when(openAiClient).completeResponses(any(), any(), anyString());
        doAnswer(invocation -> {
            official.add(invocation.getArgument(1));
            return Mono.just(new PlatformCompletionResult("from openai", KIRO_USAGE));
        }).when(openAiClient).complete(any(), any(), anyString());
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "gpt-5.4").put("input", "hello").put("stream", false);

        Client client = client(poor.toPlainString());
        assertThatThrownBy(() -> responses(client, request.deepCopy())).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(error.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
        });
        assertThat(official).isEmpty();
        assertThat(reservations(client)).isEmpty();
        assertThat(balance(client)).isEqualByComparingTo(poor);

        // Naming a limit of its own is reserved for that, and a Chat request that names none is still held to 4,096.
        responses(client, request.deepCopy().put("max_output_tokens", 4096));
        chat(client, chatBody("gpt-5.4", "hello").without("max_tokens"));
        assertThat(official.get(0).path("max_output_tokens").asInt()).isEqualTo(4096);
        assertThat(official.get(1).path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(reservations(client)).hasSize(2).allSatisfy(reservation -> assertThat(reservation).containsEntry("status", "SETTLED"));

        // A user who can pay is reserved for the default and charged for what was used, and the upstream is sent the default.
        Client rich = client("10");
        responses(rich, request.deepCopy());
        assertThat(official.get(2).path("max_output_tokens").asInt()).isEqualTo(DEFAULT_OUTPUT);
        Map<String, Object> reservation = reservations(rich).getLast();
        assertThat((BigDecimal) reservation.get("reserved_credits")).isGreaterThan(defaultReservation)
            .isLessThan(defaultReservation.add(gpt.estimate(SMALL_INPUT, 0)));
        assertThat(reservation).containsEntry("status", "SETTLED");
        assertThat(balance(rich)).isEqualByComparingTo(new BigDecimal("10").subtract(gpt.cost(KIRO_USAGE)));
    }

    // ---- failed ----

    @Test
    void aRelayThatWasCutOffReleasesTheReservationAndNothingElseIsAsked() {
        Client client = client("10");
        ownersExample(client, false, 8192);
        relayAnswers(() -> Mono.error(new RelayAbortedAfterDispatchException("The exchange with the source failed after the request was sent")));

        assertThatThrownBy(() -> chat(client, chatBody("hello"))).isInstanceOf(ApiException.class);

        // It may be generating the answer: Kiro is not asked, and the operator carries the cost.
        verify(kiroClient, never()).complete(any(), any(), anyString());
        assertThat(reservations(client).getLast()).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat(balance(client)).isEqualByComparingTo("10");
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();
        assertThat(logs(client)).singleElement().satisfies(row -> {
            assertThat(row.get("success")).isEqualTo(false);
            assertThat(row.get("source_dispatch")).isEqualTo("OPENAI_CHAT");
        });
    }

    @Test
    void aRelayWithoutQuotaThenAKiroRequestTheyRefuseReleasesTheReservationAndTheUserCarriesOn() {
        Client client = client("10");
        ownersExample(client, false, 8192);
        relayAnswers(() -> Mono.error(relayErrors.fromResponse(402, error("insufficient_quota", "You exceeded your current quota"), null,
            "relay", "sk-secret")));
        kiroAnswers(() -> Mono.error(new UpstreamException(400, "Improperly formed request", false)));

        assertThatThrownBy(() -> chat(client, chatBody("hello"))).isInstanceOf(ApiException.class);

        // The relay refused to generate, and the second tier refused the request: neither is a reason to hold the money.
        assertThat(relayBodies).hasSize(1);
        assertThat(reservations(client).getLast()).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
        assertThat(balance(client)).isEqualByComparingTo("10");
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();

        // The user is not locked out by it: the next request is served and charged.
        kiroAnswers(() -> Mono.just(KIRO_ANSWER));
        ResponseEntity<?> next = chat(client, chatBody("hello again"));
        assertThat(next.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(standard.scaled(new BigDecimal("0.6")).cost(KIRO_USAGE)));
    }

    // ---- the relay is asked again with the output it had before D11 ----

    /**
     * A relay that counts the prompt as {@code counts} times what the gateway estimates and refuses what that and the output limit
     * it is told come to over its window of 200,000.
     */
    private void relayCounts(double counts) {
        doAnswer(invocation -> {
            JsonNode body = invocation.getArgument(1);
            relayBodies.add(body);
            long counted = Math.round(tech.starryapi.platform.xproto.RelayUsageNormalizer.estimateInputTokens(body) * counts);
            long output = body.path("max_tokens").asLong();
            if (counted + output > 200_000) {
                return Mono.error(relayErrors.fromResponse(400, error("context_length_exceeded", "maximum context length is 200000 tokens"
                    + " ... (" + counted + " in the messages, " + output + " in the completion)"), null, relayId, "token"));
            }
            return Mono.just(relayAnswer(RELAY_USAGE));
        }).when(relayClient).complete(any(), any(), any());
    }

    /** A Codex turn of about 180,000 tokens, pinned to the relay: the only source, whose count of the prompt is more than the estimate. */
    private ObjectNode longTurnPinnedToTheRelay() {
        return responsesBody("a".repeat(180_000 * 4)).put("model", MODEL + "@relay");
    }

    /**
     * The relay counts the turn at 5% more than the estimate and refuses the 16,000 of the default, then serves the 4,096 it had
     * before D11: one reservation, one settlement at the price of its tier for what it reported, nothing left for review.
     */
    @Test
    void aRelayAskedAgainWithTheOutputItHadBeforeD11IsChargedOnceAtThePriceOfItsTierAndNothingIsLeftForReview() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        relayCounts(1.05);

        ResponseEntity<?> response = responses(client, longTurnPinnedToTheRelay());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("relay");
        assertThat(relayBodies).extracting(body -> body.path("max_tokens").asInt()).containsExactly(DEFAULT_OUTPUT, 4096);
        verify(kiroClient, never()).complete(any(), any(), anyString());
        assertThat(reservations(client)).singleElement().satisfies(reservation -> {
            assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay");
            assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(standard.cost(RELAY_USAGE));
            assertThat((BigDecimal) reservation.get("reserved_credits"))
                .as("the default output is what is reserved").isGreaterThan(standard.output().multiply(BigDecimal.valueOf(DEFAULT_OUTPUT)));
        });
        assertThat(balance(client)).isEqualByComparingTo(new BigDecimal("10").subtract(standard.cost(RELAY_USAGE)));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(events(client)).singleElement().satisfies(event -> assertThat(event.get("source_tier_ref")).isEqualTo("anthropic:relay"));
        assertThat(logs(client)).singleElement().satisfies(row -> {
            assertThat(row.get("success")).isEqualTo(true);
            assertThat(row.get("source_dispatch")).isEqualTo("OPENAI_CHAT");
        });
        assertThat(reviews(client)).isEmpty();
    }

    /** The relay refuses the 4,096 too: the reservation is released for good, nothing is charged and nothing is left for review. */
    @Test
    void aRelayThatRefusesTheOutputItHadBeforeD11TooReleasesTheReservationAndTheUserIsNotCharged() {
        Client client = client("10");
        ownersExample(client, "RESPONSES", client.keyId(), false, 8192);
        relayCounts(1.2);

        assertThatThrownBy(() -> responses(client, longTurnPinnedToTheRelay())).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getMessage()).contains("prompt is too long"));

        assertThat(relayBodies).extracting(body -> body.path("max_tokens").asInt()).containsExactly(DEFAULT_OUTPUT, 4096);
        verify(kiroClient, never()).complete(any(), any(), anyString());
        assertThat(reservations(client).getLast()).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat(balance(client)).isEqualByComparingTo("10");
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();
    }

    // ---- fixtures ----

    /** The owner's example for this client: the relay as the first tier, Kiro as the second at 0.6. */
    private void ownersExample(Client client, boolean vision, int imageTokenCap) {
        ownersExample(client, client.keyId(), vision, imageTokenCap);
    }

    private void ownersExample(Client client, String gatedKey, boolean vision, int imageTokenCap) {
        ownersExample(client, "CHAT", gatedKey, vision, imageTokenCap);
    }

    private void ownersExample(Client client, String entry, String gatedKey, boolean vision, int imageTokenCap) {
        relayId = account("OpenAI-compatible", "api-key", "https://relay.example/v1");
        kiroId = account("BuilderId", "idc", null);
        accountPool.refreshCache().block();
        SourceTierRuntimeInfo everything = new SourceTierRuntimeInfo(true, true, true, pair -> PairStatus.VERIFIED);
        new SourceTierAdminService(db, transactionManager, settings, registry,
            new SourceTierValidator(catalog, resolver, everything), everything)
            .replace("anthropic", new SourceTierDtos.ReplaceVendor(0L, true, null, null, null, null, null, List.of(
                new SourceTierDtos.Tier("relay", 1, "relay", null, List.of(), null, null, null),
                new SourceTierDtos.Tier("kiro", 2, "kiro", new BigDecimal("0.6"), List.of("KIRO", "CLAUDE_OAUTH"), null, null, null)),
                List.of(new SourceTierDtos.Member(relayId, "relay", "OPENAI_CHAT", null, vision, null, null, null, null, "NONE", null,
                    true, true, List.of(new SourceTierDtos.MemberModel(MODEL, "relay-sonnet", 200_000, imageTokenCap, null, null, null)))),
                List.of(new SourceTierDtos.Gate(null, entry, "KEYS", List.of(gatedKey)))))
            .block();
        // A mapping is verified by a probe of the relay: what a probe that passed leaves in the table.
        var member = registry.snapshot().member("anthropic", relayId).orElseThrow();
        var mapping = registry.snapshot().models("anthropic", relayId).getFirst();
        String fingerprint = SourceTierValidator.mappingFingerprint(member, mapping, accountPool.cachedAccount(relayId).orElseThrow());
        db.sql("UPDATE source_tier_member_models SET verified_at = now(), verified_hash = :hash"
                + " WHERE vendor = 'anthropic' AND account_id = :id")
            .bind("hash", fingerprint).bind("id", relayId).fetch().rowsUpdated().block();
        registry.refresh().block();
    }

    /** The same for the openai vendor: the relay as the first tier, official keys as the second at half the price. */
    private void openAiOwnersExample(Client client, int imageTokenCap) {
        relayId = account("OpenAI-compatible", "api-key", "https://relay.example/v1");
        account("OpenAI", "api-key", null);
        accountPool.refreshCache().block();
        SourceTierRuntimeInfo everything = new SourceTierRuntimeInfo(true, true, true, pair -> PairStatus.VERIFIED);
        new SourceTierAdminService(db, transactionManager, settings, registry,
            new SourceTierValidator(catalog, resolver, everything), everything)
            .replace("openai", new SourceTierDtos.ReplaceVendor(0L, true, null, null, null, null, null, List.of(
                new SourceTierDtos.Tier("relay", 1, "relay", null, List.of(), null, null, null),
                new SourceTierDtos.Tier("official", 2, "official", new BigDecimal("0.5"), List.of("OFFICIAL_API_KEY"), null, null, null)),
                List.of(new SourceTierDtos.Member(relayId, "relay", "OPENAI_CHAT", null, true, null, null, null, null, "NONE", null,
                    true, true, List.of(new SourceTierDtos.MemberModel("gpt-5.5", "relay-gpt", 400_000, imageTokenCap, null, null, null)))),
                List.of(new SourceTierDtos.Gate(null, "CHAT", "KEYS", List.of(client.keyId())))))
            .block();
        var member = registry.snapshot().member("openai", relayId).orElseThrow();
        var mapping = registry.snapshot().models("openai", relayId).getFirst();
        String fingerprint = SourceTierValidator.mappingFingerprint(member, mapping, accountPool.cachedAccount(relayId).orElseThrow());
        db.sql("UPDATE source_tier_member_models SET verified_at = now(), verified_hash = :hash"
                + " WHERE vendor = 'openai' AND account_id = :id")
            .bind("hash", fingerprint).bind("id", relayId).fetch().rowsUpdated().block();
        registry.refresh().block();
    }

    private ObjectNode reported(int promptTokens, int completionTokens) {
        ObjectNode reported = objectMapper.createObjectNode();
        reported.put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens);
        return reported;
    }

    private Map<String, Object> allocationState(String accountId) {
        return db.sql("SELECT lifecycle_state, cooldown_until, recent_error_ewma FROM account_allocation_states WHERE account_id = :id")
            .bind("id", accountId).fetch().one().block();
    }

    /** What the relay answers to each request, which is noted as it comes. Stubbed again with {@code doAnswer}, which asks nothing. */
    private void relayAnswers(Supplier<Mono<Completion>> answer) {
        doAnswer(invocation -> {
            relayBodies.add(invocation.getArgument(1));
            return answer.get();
        }).when(relayClient).complete(any(), any(), any());
    }

    private void kiroAnswers(Supplier<Mono<PlatformCompletionResult>> answer) {
        doAnswer(invocation -> answer.get()).when(kiroClient).complete(any(), any(), anyString());
    }

    private Completion relayAnswer(PlatformUsage usage) {
        ObjectNode reported = objectMapper.createObjectNode();
        reported.put("prompt_tokens", usage.inputTokens()).put("completion_tokens", usage.outputTokens());
        return new Completion("from the relay", "", List.of(), PlatformCompletionResult.StopReason.END_TURN, reported, "stop");
    }

    private String error(String code, String message) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("error").put("message", message).put("type", "invalid_request_error").put("code", code);
        return body.toString();
    }

    private String account(String provider, String authMethod, String baseUrl) {
        String id = "xp-" + UUID.randomUUID().toString().substring(0, 8);
        var insert = db.sql("INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, base_url, enabled)"
                + " VALUES (:id, 'token', :provider, :auth, :url, true)")
            .bind("id", id).bind("provider", provider).bind("auth", authMethod);
        (baseUrl == null ? insert.bindNull("url", String.class) : insert.bind("url", baseUrl)).fetch().rowsUpdated().block();
        // A new account is limited to a few requests a minute until it has warmed up; these tests are about the money.
        db.sql("UPDATE account_allocation_policies SET safe_concurrency = 100, safe_rpm = 0, burst_capacity = 0 WHERE account_id = :id")
            .bind("id", id).fetch().rowsUpdated().block();
        accounts.add(id);
        return id;
    }

    private record Client(String userId, String keyId, String token) {
    }

    private Client client(String balance) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Client client = new Client("user-" + suffix, "key-" + suffix, "sk-xp-" + UUID.randomUUID());
        executeAsDatabaseOwner(
            "INSERT INTO starry_api.admin_users(user_id, username, role, balance, password_hash, password_salt, enabled, first_login)"
                + " VALUES ('" + client.userId() + "', '" + client.userId() + "', 'USER', " + balance
                + ", repeat('a', 64), repeat('b', 32), TRUE, FALSE)",
            "INSERT INTO starry_api.api_keys(key_id, user_id, name, key_hash, key_prefix) VALUES ('" + client.keyId() + "', '"
                + client.userId() + "', 'xp', '" + Hashing.sha256(client.token()) + "', 'sk-xp')");
        return client;
    }

    private ObjectNode chatBody(String text) {
        return chatBody(MODEL, text);
    }

    private ObjectNode chatBody(String model, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", text);
        return body;
    }

    private ObjectNode chatBodyWithImage(String url) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", MODEL).put("max_tokens", 4096);
        var content = body.putArray("messages").addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", "what is in this picture");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", url);
        return body;
    }

    private ObjectNode gptBodyWithImage(String url) {
        ObjectNode body = chatBodyWithImage(url);
        body.put("model", "gpt-5.5");
        return body;
    }

    private ObjectNode responsesBody(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", MODEL).put("input", text).put("stream", false);
        return body;
    }

    private Mono<ResponseEntity<?>> responsesRequest(Client client, ObjectNode body) {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("/v1/responses")
            .header("Authorization", "Bearer " + client.token());
        return facade.responses(body, MockServerWebExchange.from(request));
    }

    private ResponseEntity<?> responses(Client client, ObjectNode body) {
        return responsesRequest(client, body).block();
    }

    private Mono<ResponseEntity<?>> chatRequest(Client client, ObjectNode body) {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("/v1/chat/completions")
            .header("Authorization", "Bearer " + client.token());
        return facade.chat(body, MockServerWebExchange.from(request));
    }

    private ResponseEntity<?> chat(Client client, ObjectNode body) {
        return chatRequest(client, body).block();
    }

    private List<Map<String, Object>> reviews(Client client) {
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(new AdminPrincipal("root", "root", "ADMIN", null, true));
        return new BillingReviewController(db, adminAuth).reviews(null).block().data().stream()
            .filter(review -> client.userId().equals(review.get("user_id"))).toList();
    }

    private boolean status(Client client, String status) {
        List<Map<String, Object>> rows = reservations(client);
        return !rows.isEmpty() && status.equals(rows.getLast().get("status"));
    }

    private List<Map<String, Object>> reservations(Client client) {
        return db.sql("""
                SELECT request_hash, status, reason, reserved_credits, actual_credits, served_tier_ref, served_tier_level,
                       served_price_factor, last_dispatched_tier_ref, pricing::text AS pricing
                FROM billing_reservations WHERE user_id = :user ORDER BY created_at, request_hash
                """).bind("user", client.userId()).fetch().all().collectList().block();
    }

    private List<Map<String, Object>> events(Client client) {
        return db.sql("SELECT source_tier_ref, price_factor, credits FROM api_usage_events WHERE key_id = :key ORDER BY created_at")
            .bind("key", client.keyId()).fetch().all().collectList().block();
    }

    private List<Map<String, Object>> logs(Client client) {
        return db.sql("""
                SELECT success, source_tier_ref, source_dispatch, wire_model, usage_source, stop_reason, input_tokens, output_tokens,
                       credits, withheld_input_tokens, withheld_output_tokens
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
