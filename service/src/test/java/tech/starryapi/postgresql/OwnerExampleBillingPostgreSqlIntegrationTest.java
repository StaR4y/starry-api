package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.Disposable;
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
import tech.starryapi.proxy.OwnerExampleRelays;
import tech.starryapi.proxy.OwnerExampleRelays.Reply;
import tech.starryapi.proxy.ProxyFacade;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.sourcetier.SourceTierAdminService;
import tech.starryapi.sourcetier.SourceTierDtos;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The money of the owner's example (design 5, 7.8 and 11, invariants I2, I3 and I7) on the beans of the application and a
 * real database, with the relay a server on a loopback port that the real relay client asks: the anthropic vendor with an
 * OpenAI-format relay as its first tier at the standard price and Kiro as its second at 0.6 of it, set up through the
 * administrator API as an operator would, served to Claude Code (the Messages endpoint) and to OpenCode (the Chat
 * endpoint) by the same scenarios. Only the upstream clients of Kiro, Anthropic and OpenAI are stand-ins. The facade side
 * of the same scenarios is {@code OwnerExampleTieredXpTest}; here the question is what the books say.
 *
 * <p>Every scenario ends on the same four promises: a user is charged the standard price times the factor of the tier
 * that served, never more than was reserved; a request that nobody served is released and its money is back; nothing is
 * left for review, since an attempt at a relay is generated once and the operator carries it; and the balance is the
 * starting balance less what the settled requests were charged, with nothing left reserved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.main.lazy-initialization=true", "spring.jmx.enabled=false", "spring.task.scheduling.enabled=false",
    "starry.stats.aggregation-enabled=false"
})
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class OwnerExampleBillingPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    private static final String MODEL = "claude-sonnet-4.5";
    private static final BigDecimal START = new BigDecimal("10");
    /** What the relay reports, and what Kiro reports: 800 tokens in and 100 out, whatever the request. */
    private static final PlatformUsage REPORTED = new PlatformUsage(
        OwnerExampleRelays.INPUT_TOKENS, OwnerExampleRelays.OUTPUT_TOKENS, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult KIRO_ANSWER = new PlatformCompletionResult("from kiro", REPORTED);

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
    /** Every dispatch, the relays' and the native accounts', in the order the sources were asked. */
    private final List<String> dispatched = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> nativeAnswers = new HashMap<>();
    private final List<String> accounts = new ArrayList<>();
    private OwnerExampleRelays relays;
    private BillingQuote standard;
    private String suffix;

    private enum Entry {
        CHAT("/v1/chat/completions", "x-opencode-session"),
        MESSAGES("/v1/messages", "x-claude-code-session-id");

        private final String path;
        private final String sessionHeader;

        Entry(String path, String sessionHeader) {
            this.path = path;
            this.sessionHeader = sessionHeader;
        }

        @Override
        public String toString() {
            return this == MESSAGES ? "Claude Code" : "OpenCode";
        }
    }

    @BeforeEach
    void startFromNothingConfigured() throws IOException {
        db.sql("DELETE FROM proxy_accounts").fetch().rowsUpdated().block();
        db.sql("DELETE FROM source_tier_vendors").fetch().rowsUpdated().block();
        db.sql("UPDATE system_settings SET setting_value = '0' WHERE setting_key = 'source_tier_config_version'")
            .fetch().rowsUpdated().block();
        registry.refresh().block();
        suffix = UUID.randomUUID().toString().substring(0, 6);
        relays = new OwnerExampleRelays(dispatched::add);
        standard = pricing.billingQuote(MODEL, false, payments.config().block().apiBillingMultiplier()).block();
        when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            String id = ((ProxyAccountEntity) invocation.getArgument(0)).getAccountId();
            dispatched.add(id);
            return nativeAnswers.getOrDefault(id, () -> Mono.just(KIRO_ANSWER)).get();
        });
    }

    @AfterEach
    void removeAccounts() {
        relays.close();
        db.sql("DELETE FROM proxy_accounts").fetch().rowsUpdated().block();
        accounts.clear();
        accountPool.refreshCache().block();
    }

    // ---- the relay serves ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void theRelayIsChargedTheStandardPriceAndTheRestOfTheReservationGoesBack(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);

        ResponseEntity<?> response = ask(client, entry, body(entry, MODEL, "hello"), "session-a");

        assertServed(response, "relay", "1");
        assertThat(dispatched).containsExactly(id("relay-1"));
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay")
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat(reservation.get("served_tier_level")).isEqualTo((short) 1);
        assertThat((BigDecimal) reservation.get("served_price_factor")).isEqualByComparingTo("1");
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(cost("1"));
        // The reservation covered the dearest of the tiers that could have served; everything above the charge went back.
        assertThat((BigDecimal) reservation.get("reserved_credits")).isGreaterThan(cost("1"));
        assertThat(events(client)).singleElement().satisfies(event -> {
            assertThat(event.get("source_tier_ref")).isEqualTo("anthropic:relay");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("1");
        });
        assertThat(logs(client)).singleElement().satisfies(row -> {
            assertThat(row.get("success")).isEqualTo(true);
            assertThat(row.get("source_dispatch")).isEqualTo("OPENAI_CHAT");
            assertThat(row.get("wire_model")).isEqualTo("relay-sonnet");
            assertThat(row.get("usage_source")).isEqualTo("REPORTED");
            assertThat(row.get("path")).isEqualTo(entry.path);
        });
        assertTheBooksBalance(client);
    }

    // ---- the relay cannot serve, and the request goes to Kiro, charged at the price of Kiro ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aRelayThatDoesNotServeTheModelLeavesTheRequestToKiroAndKiroIsWhatIsCharged(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);
        relays.always(id("relay-1"), Reply.failure(404, "model_not_found"));

        ResponseEntity<?> response = ask(client, entry, body(entry, MODEL, "hello"), "session-a");

        assertServed(response, "kiro", "2");
        assertThat(dispatched).containsExactly(id("relay-1"), id("kiro-1"));
        assertChargedAtKiro(client);
        // The failed attempt is a row of its own at its own tier, the one that answered another.
        assertThat(logs(client)).extracting(row -> row.get("success"), row -> row.get("source_tier_ref"))
            .containsExactlyInAnyOrder(tuple(false, "anthropic:relay"), tuple(true, "anthropic:kiro"));
        // And the revenue is the pool that served: Kiro's 0.6 of the price, nothing at the relay's.
        assertThat(revenuePerPool(client)).containsOnlyKeys("anthropic:kiro");
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void anErrorInsideA200LeavesTheRequestToKiroAndKiroIsWhatIsCharged(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);
        relays.always(id("relay-1"), Reply.inBand("model_not_found", "no available channel for model " + MODEL));

        ResponseEntity<?> response = ask(client, entry, body(entry, MODEL, "hello"), "session-a");

        assertServed(response, "kiro", "2");
        assertThat(dispatched).containsExactly(id("relay-1"), id("kiro-1"));
        assertChargedAtKiro(client);
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aStreamThatEndedBeforeAnythingWasGeneratedLeavesTheRequestToKiroAndKiroIsWhatIsCharged(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);
        relays.always(id("relay-1"), Reply.cutBeforeAnything());

        ResponseEntity<?> response = ask(client, entry, body(entry, MODEL, "hello"), "session-a");

        assertServed(response, "kiro", "2");
        assertThat(dispatched).containsExactly(id("relay-1"), id("kiro-1"));
        assertChargedAtKiro(client);
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aBusyRelayLeavesTheRequestToKiroTheSessionStaysThereAndANewSessionStartsFromTheRelay(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);
        // The relay takes one request at a time and one is being served: admission turns the next one away (design 4.2).
        makeBusy(id("relay-1"));

        ResponseEntity<?> fallen = ask(client, entry, body(entry, MODEL, "hello"), "session-a");

        assertServed(fallen, "kiro", "2");
        assertThat(dispatched).containsExactly(id("kiro-1"));
        assertThat(relays.seen()).isEmpty();
        assertChargedAtKiro(client);

        // The relay is free again, but the session that fell stays where it is and pays the price of where it is; a new
        // session starts from the best tier.
        free(id("relay-1"));
        assertServed(ask(client, entry, body(entry, MODEL, "hello again"), "session-a"), "kiro", "2");
        assertServed(ask(client, entry, body(entry, MODEL, "hello"), "session-b"), "relay", "1");
        assertThat(dispatched).containsExactly(id("kiro-1"), id("kiro-1"), id("relay-1"));
        List<Map<String, Object>> reservations = reservations(client);
        assertThat(reservations).extracting(row -> row.get("served_tier_ref"))
            .containsExactly("anthropic:kiro", "anthropic:kiro", "anthropic:relay");
        assertThat(reservations).extracting(row -> (BigDecimal) row.get("actual_credits"))
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(cost("0.6"), cost("0.6"), cost("1"));
        // The second request of the session that fell held money for Kiro alone: the relay is not what it could be charged.
        assertThat((BigDecimal) reservations.get(1).get("reserved_credits"))
            .isLessThan((BigDecimal) reservations.get(0).get("reserved_credits"));
        assertThat(revenuePerPool(client).get("anthropic:kiro")).isEqualByComparingTo(cost("0.6").multiply(new BigDecimal("2")));
        assertThat(revenuePerPool(client).get("anthropic:relay")).isEqualByComparingTo(cost("1"));
        assertTheBooksBalance(client);
    }

    // ---- two relays in the first tier, and a budget of two accounts ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aFailureOfTheFirstRelayIsRetriedOnTheSecondAndTheRelayIsWhatIsCharged(Entry entry) {
        Client client = client();
        ownersExample(client, 2, 200_000);
        // The ranker says which relay is asked first; whichever it is, it fails and the other is asked.
        relays.nextAny(Reply.failure(404, "model_not_found"));

        ResponseEntity<?> response = ask(client, entry, body(entry, MODEL, "hello"), "session-a");

        assertServed(response, "relay", "1");
        assertThat(dispatched).containsExactlyInAnyOrder(id("relay-1"), id("relay-2"));
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay");
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(cost("1"));
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void whenBothRelaysFailTheReservedSlotTakesTheRequestToKiroAndKiroIsWhatIsCharged(Entry entry) {
        Client client = client();
        ownersExample(client, 2, 200_000);
        relays.always(id("relay-1"), Reply.failure(404, "model_not_found"));
        relays.always(id("relay-2"), Reply.failure(404, "model_not_found"));

        ResponseEntity<?> response = ask(client, entry, body(entry, MODEL, "hello"), "session-a");

        assertServed(response, "kiro", "2");
        assertThat(dispatched).hasSize(3).containsExactlyInAnyOrder(id("relay-1"), id("relay-2"), id("kiro-1"));
        assertThat(dispatched.getLast()).isEqualTo(id("kiro-1"));
        assertChargedAtKiro(client);
        assertThat(logs(client)).extracting(row -> row.get("success"), row -> row.get("source_tier_ref"))
            .containsExactlyInAnyOrder(tuple(false, "anthropic:relay"), tuple(false, "anthropic:relay"), tuple(true, "anthropic:kiro"));
        assertTheBooksBalance(client);
    }

    // ---- the relay may have generated: the operator carries it ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aStreamThatBrokeOffAfterItStartedReleasesTheReservationAndTheRetryIsServedByKiro(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);
        relays.next(id("relay-1"), Reply.cutAfterStart("I will run the"));

        assertThatThrownBy(() -> ask(client, entry, body(entry, MODEL, "hello"), "session-a"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        // It may be generating the answer: Kiro is not asked, the reservation is released for good and nothing is charged.
        assertThat(dispatched).containsExactly(id("relay-1"));
        Map<String, Object> released = reservations(client).getLast();
        assertThat(released).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat(balance(client)).isEqualByComparingTo(START);
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();
        assertThat(logs(client)).singleElement().satisfies(row -> {
            assertThat(row.get("success")).isEqualTo(false);
            assertThat(row.get("stop_reason")).isEqualTo("INTERRUPTED");
        });

        // The client's retry is not given to the relay that may still be generating the first answer; Kiro serves it and
        // is what the user is charged.
        ResponseEntity<?> retry = ask(client, entry, body(entry, MODEL, "hello"), "session-a");
        assertServed(retry, "kiro", "2");
        List<Map<String, Object>> reservations = reservations(client);
        assertThat(reservations).extracting(row -> row.get("status")).containsExactly("RELEASED", "SETTLED");
        assertThat((BigDecimal) reservations.getLast().get("actual_credits")).isEqualByComparingTo(cost("0.6"));
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aClientThatGivesUpWhileTheRelayIsGeneratingGetsTheMoneyBackAndNothingWaitsForAReview(Entry entry)
        throws InterruptedException {
        Client client = client();
        ownersExample(client, 1, 200_000);
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        relays.next(id("relay-1"), Reply.holding(arrived, letGo, Reply.text("too late")));

        Disposable request = start(client, entry, body(entry, MODEL, "hello"), "session-a").subscribe(response -> { }, error -> { });
        assertThat(arrived.await(30, TimeUnit.SECONDS)).as("the relay has the request").isTrue();
        request.dispose();
        letGo.countDown();
        await().atMost(Duration.ofSeconds(20)).until(() -> !"DISPATCHED".equals(reservations(client).getLast().get("status")));

        // The relay may be generating the answer and nobody is charged for it: the operator carries that, the money is
        // back at once, and a person has nothing to look at (design 7.8, invariant I7).
        assertThat(reservations(client).getLast()).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:relay");
        assertThat(balance(client)).isEqualByComparingTo(START);
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(events(client)).isEmpty();
        assertThat(reviews(client)).isEmpty();

        // The session is where it was: its next request is the relay's again, at the relay's price.
        assertServed(ask(client, entry, body(entry, MODEL, "hello again"), "session-a"), "relay", "1");
        assertThat(reservations(client).getLast()).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay");
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aRelayWithoutQuotaAndAKiroThatRefusesTheRequestReleaseTheReservationAndTheUserCarriesOn(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);
        relays.always(id("relay-1"), Reply.failure(402, "insufficient_quota"));
        nativeAnswers.put(id("kiro-1"), () -> Mono.error(new UpstreamException(400, "Improperly formed request", false)));

        assertThatThrownBy(() -> ask(client, entry, body(entry, MODEL, "hello"), "session-a"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // The relay refused to generate and the second tier refused the request: neither is a reason to hold the money, or
        // to lock the user into a review.
        assertThat(dispatched).containsExactly(id("relay-1"), id("kiro-1"));
        assertThat(reservations(client).getLast()).containsEntry("status", "RELEASED").containsEntry("actual_credits", null)
            .containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
        assertThat(balance(client)).isEqualByComparingTo(START);
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();

        // The user is free to go on: the next request is served, and charged.
        nativeAnswers.clear();
        ResponseEntity<?> next = ask(client, entry, body(entry, MODEL, "hello again"), "session-b");
        assertThat(next.getStatusCode().value()).isEqualTo(200);
        assertThat(balance(client)).isLessThan(START);
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aRelayThatReportsFarMoreInputThanTheRequestCouldHaveHadIsChargedNoMoreThanWasReserved(Entry entry) {
        assertChargedNoMoreThanReservedForAnswerClaiming(entry, 90_000_000L, OwnerExampleRelays.OUTPUT_TOKENS);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aRelayThatReportsFarMoreOutputThanTheRequestAllowedIsChargedNoMoreThanWasReserved(Entry entry) {
        assertChargedNoMoreThanReservedForAnswerClaiming(entry, OwnerExampleRelays.INPUT_TOKENS, 9_000_000L);
    }

    /**
     * The relay's word about its usage is trusted only as far as the request could have gone: the user is charged for
     * that at the price of the tier, never above the reservation, and the books close as for any other request.
     */
    private void assertChargedNoMoreThanReservedForAnswerClaiming(Entry entry, long claimedIn, long claimedOut) {
        Client client = client();
        ownersExample(client, 1, 200_000);
        relays.always(id("relay-1"), Reply.textReporting("from relay-1", claimedIn, claimedOut));

        ResponseEntity<?> response = ask(client, entry, body(entry, MODEL, "hello"), "session-a");

        assertServed(response, "relay", "1");
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:relay");
        BigDecimal claimed = standard.cost(new PlatformUsage(claimedIn, claimedOut, BigDecimal.ZERO, 0, 0, 0));
        assertThat((BigDecimal) reservation.get("actual_credits")).isLessThanOrEqualTo((BigDecimal) reservation.get("reserved_credits"))
            .isLessThan(claimed).isGreaterThan(cost("1"));
        assertThat(logs(client)).singleElement().satisfies(row -> assertThat(row.get("usage_source")).isEqualTo("CLAMPED"));
        assertTheBooksBalance(client);
    }

    // ---- what the relay is not asked ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aRequestWithAServerSideSearchIsKirosToServeAndTheMainLoopStaysWithTheRelay(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);

        ask(client, entry, body(entry, MODEL, "Run echo hello"), "session-a");
        ResponseEntity<?> search = ask(client, entry, webSearch(entry), "session-a");
        ask(client, entry, body(entry, MODEL, "Run echo world"), "session-a");

        assertServed(search, "kiro", "2");
        assertThat(dispatched).containsExactly(id("relay-1"), id("kiro-1"), id("relay-1"));
        assertThat(reservations(client)).extracting(row -> row.get("served_tier_ref"), row -> (BigDecimal) row.get("actual_credits"))
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(tuple("anthropic:relay", cost("1")), tuple("anthropic:kiro", cost("0.6")), tuple("anthropic:relay", cost("1")));
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aRequestLongerThanTheWindowOfTheRelayIsKirosToServeAndTheReservationIsBoundedByTheLongRequest(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 20_000);

        ResponseEntity<?> response = ask(client, entry, history(entry, 20, 6_000), "session-a");

        assertServed(response, "kiro", "2");
        assertThat(dispatched).containsExactly(id("kiro-1"));
        assertThat(relays.seen()).isEmpty();
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:kiro");
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(cost("0.6"));
        assertThat((BigDecimal) reservation.get("reserved_credits")).isGreaterThanOrEqualTo(cost("0.6"));
        // The window is the request's business and not a fall of the session: the next short request is the relay's.
        assertServed(ask(client, entry, body(entry, MODEL, "hello"), "session-a"), "relay", "1");
        assertTheBooksBalance(client);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    void aPromptTheRelayRefusedForItsLengthIsKirosToServeAndOnlyTheLongOnesGoThereAfterIt(Entry entry) {
        Client client = client();
        ownersExample(client, 1, 200_000);
        relays.next(id("relay-1"), Reply.failure(400, "context_length_exceeded"));

        ResponseEntity<?> refused = ask(client, entry, history(entry, 10, 6_000), "session-a");

        assertServed(refused, "kiro", "2");
        assertThat(dispatched).containsExactly(id("relay-1"), id("kiro-1"));
        assertChargedAtKiro(client);
        // A short request still goes to the relay, a long one does not.
        assertServed(ask(client, entry, body(entry, MODEL, "hello"), "session-a"), "relay", "1");
        assertServed(ask(client, entry, history(entry, 10, 6_000), "session-a"), "kiro", "2");
        assertThat(dispatched).containsExactly(id("relay-1"), id("kiro-1"), id("relay-1"), id("kiro-1"));
        assertThat(reservations(client)).extracting(row -> row.get("served_tier_ref"))
            .containsExactly("anthropic:kiro", "anthropic:relay", "anthropic:kiro");
        assertTheBooksBalance(client);
    }

    // ---- a relay kept for Claude does not take the traffic of the others (design 7.5) ----

    @Test
    void aRelayKeptForClaudeDoesNotServeGlmWhoseKeyIsTheOnlyOneThatDoes() {
        Client client = client();
        ownersExample(client, 1, 200_000);
        // A model the compatible keys serve, as GLM is, priced like the official model it is named after. The relay is the
        // one the ranker prefers: if it were not left out of the pool, it would be given the request.
        String glm = account(id("z-glm"), "OpenAI-compatible", "api-key", "https://glm.example/v1", "sk-glm");
        prefer(id("relay-1"), 1);
        prefer(id("z-glm"), 1000);
        accountPool.refreshCache().block();
        when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            dispatched.add(((ProxyAccountEntity) invocation.getArgument(0)).getAccountId());
            return Mono.just(new PlatformCompletionResult("from glm", REPORTED));
        });

        for (int i = 0; i < 3; i++) {
            ResponseEntity<?> response = ask(client, Entry.CHAT, body(Entry.CHAT, "openai-compatible/gpt-5.4", "hello"), "session-" + i);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // A vendor without tiers is served and settled as it always was.
            assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isNull();
        }

        assertThat(dispatched).containsExactly(glm, glm, glm);
        assertThat(relays.seen()).isEmpty();
        assertThat(reservations(client)).hasSize(3).allSatisfy(row -> {
            assertThat(row).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", null);
            assertThat(row.get("last_dispatched_tier_ref")).isNull();
        });
        assertTheBooksBalance(client);
    }

    // ---- fixtures ----

    /** The ids of a scenario's accounts carry a suffix, so that no account's state outlives the scenario that made it. */
    private String id(String name) {
        return name + "-" + suffix;
    }

    private BigDecimal cost(String factor) {
        return standard.scaled(new BigDecimal(factor)).cost(REPORTED);
    }

    private static void assertServed(ResponseEntity<?> response, String tierId, String level) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo(tierId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo(level);
    }

    /** The one request the user made, served by Kiro: settled at 0.6 of the standard price, reserved for more. */
    private void assertChargedAtKiro(Client client) {
        Map<String, Object> reservation = reservations(client).getLast();
        assertThat(reservation).containsEntry("status", "SETTLED").containsEntry("served_tier_ref", "anthropic:kiro")
            .containsEntry("last_dispatched_tier_ref", "anthropic:kiro");
        assertThat(reservation.get("served_tier_level")).isEqualTo((short) 2);
        assertThat((BigDecimal) reservation.get("served_price_factor")).isEqualByComparingTo("0.6");
        assertThat((BigDecimal) reservation.get("actual_credits")).isEqualByComparingTo(cost("0.6"));
        assertThat((BigDecimal) reservation.get("actual_credits")).isLessThan(cost("1"));
        assertThat((BigDecimal) reservation.get("reserved_credits")).isGreaterThan((BigDecimal) reservation.get("actual_credits"));
        assertThat(events(client)).last().satisfies(event -> {
            assertThat(event.get("source_tier_ref")).isEqualTo("anthropic:kiro");
            assertThat((BigDecimal) event.get("price_factor")).isEqualByComparingTo("0.6");
        });
    }

    /** What the books must say whatever happened: no money held, nothing in review, the balance the sum of what was charged. */
    private void assertTheBooksBalance(Client client) {
        List<Map<String, Object>> reservations = reservations(client);
        assertThat(reservations).extracting(row -> row.get("status")).allMatch(status -> status.equals("SETTLED") || status.equals("RELEASED"));
        BigDecimal charged = reservations.stream().map(row -> (BigDecimal) row.get("actual_credits"))
            .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Map<String, Object> reservation : reservations) {
            if (reservation.get("actual_credits") != null) {
                // Never more than was held for the request, and never more than the tier that served would ask.
                assertThat((BigDecimal) reservation.get("actual_credits")).isLessThanOrEqualTo((BigDecimal) reservation.get("reserved_credits"));
            }
        }
        assertThat(balance(client)).isEqualByComparingTo(START.subtract(charged));
        assertThat(keyReserved(client)).isEqualByComparingTo("0");
        assertThat(reviews(client)).isEmpty();
        assertThat(events(client).stream().map(event -> (BigDecimal) event.get("credits")).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo(charged);
    }

    /**
     * The owner's example as an operator sets it up through the administrator API: the relay (or {@code relays} of them) as
     * the first tier at the standard price, mapped for Sonnet and Opus and open to the key of the client on both
     * endpoints, and Kiro as the second at 0.6, with the accounts the pools find them as.
     */
    private void ownersExample(Client client, int relayCount, int window) {
        List<String> relayIds = new ArrayList<>();
        for (int i = 1; i <= relayCount; i++) {
            relayIds.add(account(id("relay-" + i), "OpenAI-compatible", "api-key", relays.url(id("relay-" + i)), "sk-relay-" + i));
        }
        account(id("kiro-1"), "BuilderId", "idc", null, "token");
        accountPool.refreshCache().block();
        List<SourceTierDtos.Member> members = relayIds.stream().map(relayId -> new SourceTierDtos.Member(relayId, "relay", "OPENAI_CHAT",
            null, null, null, null, null, null, "NONE", null, true, true,
            List.of(new SourceTierDtos.MemberModel(MODEL, "relay-sonnet", window, 8192, null, null, null),
                new SourceTierDtos.MemberModel("claude-opus-4.5", "relay-opus", window, 8192, null, null, null)))).toList();
        tierAdmin.replace("anthropic", new SourceTierDtos.ReplaceVendor(0L, true, null, null, null, null, null, List.of(
                new SourceTierDtos.Tier("relay", 1, "relay", null, List.of(), null, null, null),
                new SourceTierDtos.Tier("kiro", 2, "kiro", new BigDecimal("0.6"), List.of("KIRO", "CLAUDE_OAUTH"), null, null, null)),
                members,
                List.of(new SourceTierDtos.Gate(null, "CHAT", "KEYS", List.of(client.keyId())),
                    new SourceTierDtos.Gate(null, "MESSAGES", "KEYS", List.of(client.keyId())))))
            .block();
        // A mapping is verified by a probe of the relay: what a probe that passed leaves in the table.
        for (String relayId : relayIds) {
            var member = registry.snapshot().member("anthropic", relayId).orElseThrow();
            for (var mapping : registry.snapshot().models("anthropic", relayId)) {
                String fingerprint = SourceTierValidator.mappingFingerprint(member, mapping, accountPool.cachedAccount(relayId).orElseThrow());
                db.sql("UPDATE source_tier_member_models SET verified_at = now(), verified_hash = :hash"
                        + " WHERE vendor = 'anthropic' AND account_id = :id AND model_id = :model")
                    .bind("hash", fingerprint).bind("id", relayId).bind("model", mapping.modelId()).fetch().rowsUpdated().block();
            }
        }
        registry.refresh().block();
    }

    private String account(String id, String provider, String authMethod, String baseUrl, String token) {
        var insert = db.sql("INSERT INTO proxy_accounts(account_id, access_token, provider, auth_method, base_url, enabled)"
                + " VALUES (:id, :token, :provider, :auth, :url, true)")
            .bind("id", id).bind("token", token).bind("provider", provider).bind("auth", authMethod);
        (baseUrl == null ? insert.bindNull("url", String.class) : insert.bind("url", baseUrl)).fetch().rowsUpdated().block();
        // A new account is limited to a few requests a minute until it has warmed up; these tests are about the money, so the
        // only account that is ever busy is the one a test makes busy.
        db.sql("UPDATE account_allocation_policies SET safe_concurrency = 100, safe_rpm = 0, burst_capacity = 0 WHERE account_id = :id")
            .bind("id", id).fetch().rowsUpdated().block();
        accounts.add(id);
        return id;
    }

    /** Lower is preferred by the ranker (allocation priority, 1 to 10000). */
    private void prefer(String accountId, int priority) {
        db.sql("UPDATE account_allocation_policies SET allocation_priority = :priority WHERE account_id = :id")
            .bind("priority", priority).bind("id", accountId).fetch().rowsUpdated().block();
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

    private Client client() {
        String name = UUID.randomUUID().toString().substring(0, 8);
        Client client = new Client("user-" + name, "key-" + name, "sk-owner-" + UUID.randomUUID());
        executeAsDatabaseOwner(
            "INSERT INTO starry_api.admin_users(user_id, username, role, balance, password_hash, password_salt, enabled, first_login)"
                + " VALUES ('" + client.userId() + "', '" + client.userId() + "', 'USER', " + START
                + ", repeat('a', 64), repeat('b', 32), TRUE, FALSE)",
            "INSERT INTO starry_api.api_keys(key_id, user_id, name, key_hash, key_prefix) VALUES ('" + client.keyId() + "', '"
                + client.userId() + "', 'owner', '" + Hashing.sha256(client.token()) + "', 'sk-owner')");
        return client;
    }

    // ---- the requests of the two clients ----

    private ObjectNode body(Entry entry, String model, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", text);
        return body;
    }

    private ObjectNode webSearch(Entry entry) {
        ObjectNode body = body(entry, MODEL, "Perform a web search for the query: starry");
        if (entry == Entry.MESSAGES) {
            body.putArray("tools").addObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 8);
        } else {
            body.putObject("web_search_options").put("search_context_size", "medium");
        }
        return body;
    }

    /** The request that compacts a conversation: its whole history, {@code pairs} exchanges of {@code size} characters, and an instruction. */
    private ObjectNode history(Entry entry, int pairs, int size) {
        ObjectNode body = body(entry, MODEL, "Let us start");
        ArrayNode messages = body.withArray("messages");
        for (int i = 0; i < pairs; i++) {
            messages.addObject().put("role", "assistant").put("content", filler(size, i));
            messages.addObject().put("role", "user").put("content", filler(size, i + pairs));
        }
        ObjectNode last = (ObjectNode) messages.get(messages.size() - 1);
        last.put("content", last.path("content").asString() + "\n\nYour task is to create a detailed summary of the conversation so far.");
        return body;
    }

    private static String filler(int size, int seed) {
        StringBuilder text = new StringBuilder(size);
        for (int i = 0; text.length() < size; i++) {
            text.append("the quick brown fox ").append(seed).append(' ').append(i).append(" jumps over the lazy dog. ");
        }
        return text.substring(0, size);
    }

    private ResponseEntity<?> ask(Client client, Entry entry, ObjectNode body, String session) {
        return start(client, entry, body, session).block(Duration.ofSeconds(60));
    }

    /** The request as the client's connection holds it: it is not made until subscribed to, and ends when disposed of. */
    private Mono<ResponseEntity<?>> start(Client client, Entry entry, ObjectNode body, String session) {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post(entry.path)
            .header("Authorization", "Bearer " + client.token()).header(entry.sessionHeader, client.keyId() + "-" + session);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        return entry == Entry.MESSAGES ? facade.messages(body, exchange) : facade.chat(body, exchange);
    }

    // ---- what the database says ----

    private List<Map<String, Object>> reviews(Client client) {
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(new AdminPrincipal("root", "root", "ADMIN", null, true));
        return new BillingReviewController(db, adminAuth).reviews(null).block().data().stream()
            .filter(review -> client.userId().equals(review.get("user_id"))).toList();
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

    private Map<String, BigDecimal> revenuePerPool(Client client) {
        Map<String, BigDecimal> revenue = new HashMap<>();
        db.sql("SELECT COALESCE(source_tier_ref, '(no pool)') AS pool, sum(credits) AS credits FROM api_usage_events"
                + " WHERE key_id = :key GROUP BY 1 ORDER BY 1")
            .bind("key", client.keyId()).fetch().all().toStream()
            .forEach(row -> revenue.put((String) row.get("pool"), (BigDecimal) row.get("credits")));
        return revenue;
    }

    private List<Map<String, Object>> logs(Client client) {
        return db.sql("""
                SELECT success, path, source_tier_ref, source_dispatch, wire_model, usage_source, stop_reason
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
