package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPoolPolicyService;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.ProxyAccountRepository;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionRequest;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
import tech.starryapi.platform.core.PlatformCompletionResult;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.gemini.GeminiRequestAdapter;
import tech.starryapi.platform.gemini.GeminiResponseFactory;
import tech.starryapi.platform.kiro.KiroPayloadFactory;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.platform.openai.OpenAiRequestAdapter;
import tech.starryapi.platform.openai.OpenAiResponseFactory;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;
import tech.starryapi.platform.openai.OpenAiResponseStateStore;
import tech.starryapi.platform.openai.OpenAiUpstreamClient;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Native tiers end to end, the way the owner's example runs (design 0 and 11, decisions D2 to D4): a client request enters
 * the facade, the real planner plans it over official Anthropic keys (tier 1) and Kiro (tier 2, at 0.6 of the standard
 * price), the real account pool and the real session bindings select, and the reservation, the lease, the settlement,
 * the release and the log row are followed to the end. Only the edges are stand-ins: admission answers per account, the
 * upstream clients answer per account, and a ledger takes the place of the billing functions of the database (the SQL
 * side is {@code SourceTierEndToEndPostgreSqlIntegrationTest}).
 *
 * <p>Every test ends on the same promise: what the user is charged is the standard price times the factor of the tier
 * that served, and never more than was reserved.
 */
class ProxyFacadeTieredNativeTest {

    private static final String SESSION_HEADER = "x-claude-code-session-id";
    /** 8,000 tokens in and 1,000 out: 0.039 at the standard price of the quote below, 0.0234 at 0.6 of it. */
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult ANSWER = new PlatformCompletionResult("OK", USAGE);
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    private final ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(modelCatalog);
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AccountAdmissionStore admissionStore = mock(AccountAdmissionStore.class);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final AccountSettingsService accountSettings = mock(AccountSettingsService.class);
    private final ModelMappingService modelMapping = mock(ModelMappingService.class);
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
    private final AnthropicUpstreamClient anthropicClient = mock(AnthropicUpstreamClient.class);
    private final KiroUpstreamClient kiroClient = mock(KiroUpstreamClient.class);
    private final OpenAiUpstreamClient openAiClient = mock(OpenAiUpstreamClient.class);
    private final OpenAiResponseOwnerStore ownerStore = mock(OpenAiResponseOwnerStore.class);
    private final OpenAiResponseStateStore stateStore = new OpenAiResponseStateStore(objectMapper);
    private final StatsService statsService = mock(StatsService.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    private final AtomicLong clock = new AtomicLong();
    private final ProxyProperties properties = new ProxyProperties();
    private final TierSessionBindings bindings = new TierSessionBindings(properties, clock::get);
    private final Ledger ledger = new Ledger();

    /** The accounts the pool loads, which a test may change before it calls the facade. */
    private final List<ProxyAccountEntity> accounts = new ArrayList<>();
    /** The accounts admission refuses as busy. */
    private final Set<String> busy = new HashSet<>();
    /** What each account answers when it is dispatched to; an account that is not listed answers {@link #ANSWER}. */
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> answers = new HashMap<>();
    /** The accounts the upstream clients were called with, in order. */
    private final List<String> dispatched = new ArrayList<>();
    /** The accounts admission was asked about, in order. */
    private final List<String> asked = new ArrayList<>();
    private RoutingPolicySettings routingPolicy = RoutingPolicySettings.defaults();

    private AccountPoolService accountPool;
    private ProxyFacade facade;

    @BeforeEach
    void standInForTheEdges() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenAnswer(invocation -> {
            ledger.reserved.add(new Reserved(invocation.getArgument(2), objectMapper.readTree((String) invocation.getArgument(3))));
            return Mono.empty();
        });
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenAnswer(invocation -> {
            ledger.plainDispatches++;
            return Mono.empty();
        });
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString())).thenAnswer(invocation -> {
            ledger.leasedTo.add(invocation.getArgument(2));
            return Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59)));
        });
        when(apiKeyService.accountUsage(any(), anyString(), any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            ledger.settled.add(new Settled(invocation.getArgument(2), null, null, null));
            return Mono.empty();
        });
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenAnswer(invocation -> {
                ledger.settled.add(new Settled(invocation.getArgument(2), invocation.getArgument(5), invocation.getArgument(6),
                    invocation.getArgument(7)));
                return Mono.empty();
            });
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenAnswer(invocation -> {
            ledger.released.add(invocation.getArgument(2));
            return Mono.empty();
        });
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenAnswer(invocation -> Mono.just(routingPolicy));
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
        when(admissionStore.acquire(any())).thenAnswer(invocation -> {
            AdmissionRequest request = invocation.getArgument(0);
            asked.add(request.accountId());
            return Mono.just(busy.contains(request.accountId())
                ? new AdmissionDecision(false, null, "ACCOUNT_BUSY", null, null)
                : new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600)));
        });
        when(admissionStore.markDispatched(any())).thenReturn(Mono.empty());
        when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
        when(admissionStore.releaseUnsent(any())).thenReturn(Mono.empty());
        when(admissionStore.abandon(any(), anyString())).thenReturn(Mono.empty());
        when(anthropicClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0)));
        when(kiroClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0)));
        when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0)));
        when(openAiClient.completeResponses(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0)));
    }

    // ---- the first tier serves ----

    @Test
    void anOfficialKeyServesTheRequestAndIsChargedTheStandardPrice() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = messages("session-1", "hello");

        assertThat(dispatched).containsExactly("official-1");
        assertServedBy(response, "official", "1", "auto");
        ledger.assertCharged("anthropic:official", 1, "1", "0.039000");
        // The reservation was sized for the dearer of the two tiers that could have served, the first one here.
        assertThat(ledger.reservedCredits()).isEqualByComparingTo(estimateOf("anthropic:official"));
        assertThat(ledger.holdLevels()).containsExactly("anthropic:official", "anthropic:kiro");
        assertThat(ledger.leasedTo).containsExactly("anthropic:official");
        assertThat(ledger.plainDispatches).isZero();
        assertThat(ledger.released).isEmpty();
        assertThat(logged()).singleElement().satisfies(row -> {
            assertThat(row.success()).isTrue();
            assertThat(row.sourceTierRef()).isEqualTo("anthropic:official");
            assertThat(row.sourceTierLevel()).isEqualTo(1);
            assertThat(row.sourceTierName()).isEqualTo("official");
            assertThat(row.priceFactor()).isEqualByComparingTo("1");
            assertThat(row.tierSelection()).isEqualTo("AUTO");
        });
    }

    // ---- busy is unavailable (D2) and a session that fell stays there (D4) ----

    @Test
    void aFirstTierThatIsBusyEverywhereSendsTheRequestToKiroAndChargesKirosPrice() {
        serve(anthropic(), officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        busy.addAll(Set.of("official-1", "official-2"));

        ResponseEntity<?> response = messages("session-1", "hello");

        // Both official keys were asked and turned the request away; nothing was dispatched to them.
        assertThat(asked).containsExactlyInAnyOrder("official-1", "official-2", "kiro-1");
        assertThat(asked.getLast()).isEqualTo("kiro-1");
        assertThat(dispatched).containsExactly("kiro-1");
        assertServedBy(response, "kiro", "2", "auto");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        // What was held for the dearer tier went back: the user pays for the tier that served and no more.
        assertThat(ledger.reservedCredits()).isGreaterThan(ledger.settled.getFirst().credits());
        assertThat(ledger.leasedTo).containsExactly("anthropic:kiro");
        assertThat(ledger.released).isEmpty();
    }

    @Test
    void aSessionThatFellToKiroStaysThereUntilItHasBeenIdleForTenMinutes() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.add("official-1");
        messages("session-1", "hello");

        // The first tier is free again, but the session has fallen: it does not move back and forth between tiers.
        busy.clear();
        asked.clear();
        messages("session-1", "hello again");

        assertThat(asked).containsExactly("kiro-1");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        assertThat(ledger.holdLevels()).as("what the second request reserves for").containsExactly("anthropic:kiro");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("anthropic:kiro", "anthropic:kiro"));

        // Another session of the same key is a new one, and starts from the best tier.
        messages("session-2", "hello");
        assertThat(dispatched).endsWith("official-1");

        advance(Duration.ofMinutes(9));
        messages("session-1", "still here");
        assertThat(dispatched).endsWith("kiro-1");

        advance(Duration.ofMinutes(10).plusSeconds(1));
        messages("session-1", "back again");
        assertThat(dispatched).endsWith("official-1");
        assertThat(ledger.settled.getLast().tierRef()).isEqualTo("anthropic:official");
        assertThat(ledger.settled.getLast().credits()).isEqualByComparingTo("0.039000");
    }

    @Test
    void everyTierBusyIsNoAvailableAccountAndNothingIsCharged() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        busy.addAll(Set.of("official-1", "kiro-1"));

        assertThatThrownBy(() -> messages("session-1", "hello"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));

        assertThat(dispatched).isEmpty();
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).hasSize(1);
        assertThat(ledger.leasedTo).isEmpty();
    }

    // ---- a definite failure moves on, an uncertain one stays (design 4.5) ----

    @Test
    void aDefinitiveFailureOfTheFirstTierIsServedByKiroAndOnlyKiroIsCharged() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));

        ResponseEntity<?> response = messages("session-1", "hello");

        assertThat(dispatched).containsExactly("official-1", "kiro-1");
        assertServedBy(response, "kiro", "2", "auto");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        // The lease followed the request from tier to tier.
        assertThat(ledger.leasedTo).containsExactly("anthropic:official", "anthropic:kiro");
        assertThat(ledger.released).isEmpty();
        verify(accountPool).recordRequestFailure(eq("official-1"), any(AccountPoolService.UpstreamErrorType.class), eq(429),
            anyString(), any(AccountPoolRequestContext.class));
        // One row for each attempt, each with the tier that took it.
        assertThat(logged()).extracting(StatsService.RequestLog::success, StatsService.RequestLog::sourceTierRef)
            .containsExactly(tuple(false, "anthropic:official"), tuple(true, "anthropic:kiro"));
        assertThat(logged().getLast().priceFactor()).isEqualByComparingTo("0.6");
    }

    @Test
    void anUncertainFailureKeepsTheRequestInItsTierAndLeavesTheReservationForReview() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        fails("official-1", new TimeoutException("no answer"));

        assertThatThrownBy(() -> messages("session-1", "hello")).isInstanceOf(ApiException.class);

        // The request may still be running at the official key: it is never generated a second time by Kiro.
        assertThat(dispatched).containsExactly("official-1");
        assertThat(ledger.settled).isEmpty();
        assertThat(ledger.released).containsExactly(false);

        // The session was not pushed down by it: the next request starts from the best tier again.
        answers.clear();
        messages("session-1", "hello again");
        assertThat(dispatched).containsExactly("official-1", "official-1");
        ledger.assertCharged("anthropic:official", 1, "1", "0.039000");
    }

    @Test
    void aRefusalToServeBecauseOfNoQuotaIsDefiniteAndKiroServesTheRequest() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(402, "insufficient_quota", true));

        ResponseEntity<?> response = messages("session-1", "hello");

        assertThat(dispatched).containsExactly("official-1", "kiro-1");
        assertServedBy(response, "kiro", "2", "auto");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        assertThat(ledger.released).isEmpty();
    }

    @Test
    void a402FromEveryTierReleasesTheReservationAsUnbilledInsteadOfLockingTheUserIntoReview() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(402, "insufficient_quota", true));
        fails("kiro-1", new UpstreamException(402, "insufficient_quota", true));

        assertThatThrownBy(() -> messages("session-1", "hello")).isInstanceOf(ApiException.class);

        assertThat(dispatched).containsExactly("official-1", "kiro-1");
        assertThat(ledger.settled).isEmpty();
        // A 402 is a refusal and not a generation, tiered or not.
        assertThat(ledger.released).containsExactly(true);
    }

    // ---- what the route can reach is what the tiers can serve (I6) ----

    @Test
    void aChatRequestForAClaudeModelStaysOnTheKiroRouteAndIsServedFromTheKiroTierAlone() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = chat("claude-sonnet-4.5", "hello");

        // Tiers divide the accounts a route can already use; they do not give the Kiro route an official key.
        assertThat(dispatched).containsExactly("kiro-1");
        verify(anthropicClient, never()).complete(any(), any(), anyString());
        assertServedBy(response, "kiro", "2", "auto");
        ledger.assertCharged("anthropic:kiro", 2, "0.6", "0.023400");
        assertThat(ledger.holdLevels()).containsExactly("anthropic:kiro");
    }

    // ---- Codex as the second tier of OpenAI ----

    @Test
    void aResponsesRequestFallsFromTheOfficialKeyToCodexAndItsChainStaysWithCodex() {
        routingPolicy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        serve(openAi(), officialOpenAi("openai-1"), codex("codex-1"));
        busy.add("openai-1");

        ResponseEntity<?> first = responses(null, "session-1", "start the task");

        assertThat(dispatched).containsExactly("codex-1");
        assertServedBy(first, "codex", "2", "auto");
        ledger.assertCharged("openai:codex", 2, "0.5", "0.019500");
        verify(ownerStore).remember(anyString(), eq("codex-1"));

        // The next turn continues the response at the account that owns it, and the tier follows.
        busy.clear();
        ResponseEntity<?> second = responses(((JsonNode) first.getBody()).path("id").asString(), "session-1", "second turn");

        assertThat(dispatched).containsExactly("codex-1", "codex-1");
        assertServedBy(second, "codex", "2", "auto");
        ledger.assertEveryRequestWasChargedTheTierThatServed(List.of("openai:codex", "openai:codex"));
    }

    // ---- a vendor without tiers is the code of before ----

    @Test
    void aVendorWithoutTiersBesideATieredOneIsServedAndBilledAsBefore() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), officialOpenAi("openai-1"));

        ResponseEntity<?> response = chat("gpt-5.4", "hello");

        assertThat(dispatched).containsExactly("openai-1");
        assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
        // The four-argument settlement and the plain dispatch mark, and a reservation that carries no plan.
        assertThat(ledger.plainDispatches).isEqualTo(1);
        assertThat(ledger.leasedTo).isEmpty();
        assertThat(ledger.settled).singleElement().satisfies(settled -> {
            assertThat(settled.tierRef()).isNull();
            assertThat(settled.credits()).isEqualByComparingTo("0.039000");
        });
        assertThat(ledger.reserved.getFirst().pricing().has("tierPlan")).isFalse();
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
        assertThat(logged()).singleElement().satisfies(row -> {
            assertThat(row.sourceTierRef()).isNull();
            assertThat(row.sourceTierLevel()).isNull();
            assertThat(row.priceFactor()).isNull();
            assertThat(row.tierSelection()).isNull();
        });
    }

    @Test
    void aTieredRequestAndAnUntieredOneOfTheSameKeyDoNotDisturbEachOther() {
        serve(anthropic(), officialKey("official-1"), kiro("kiro-1"), officialOpenAi("openai-1"));
        busy.add("official-1");

        messages("session-1", "tiered");
        chat("gpt-5.4", "untiered");
        messages("session-1", "tiered again");

        assertThat(dispatched).containsExactly("kiro-1", "openai-1", "kiro-1");
        assertThat(ledger.settled).extracting(Settled::tierRef).containsExactly("anthropic:kiro", null, "anthropic:kiro");
        assertThat(ledger.settled.get(0).credits()).isEqualByComparingTo("0.023400");
        assertThat(ledger.settled.get(1).credits()).isEqualByComparingTo("0.039000");
        assertThat(ledger.settled.get(2).credits()).isEqualByComparingTo("0.023400");
    }

    // ---- fixtures ----

    private void advance(Duration time) {
        clock.addAndGet(time.toNanos());
    }

    /** Fills the pool with {@code accounts} and builds the facade over it, the planner and the session bindings. */
    private void serve(SourceTierSnapshot snapshot, ProxyAccountEntity... loaded) {
        accounts.addAll(List.of(loaded));
        AccountSettingsService policySettings = mock(AccountSettingsService.class, invocation ->
            invocation.getMethod().getName().equals("defaultPoolPolicySettings")
                ? new AccountDtos.PoolPolicySettings(false, 5, 2, 50.0, 50.0, 70.0, 5, 1, true)
                : Answers.RETURNS_DEFAULTS.answer(invocation));
        AccountPoolPolicyService policy = new AccountPoolPolicyService(policySettings, mock(DatabaseClient.class), keyResolver);
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenAnswer(invocation -> Flux.fromIterable(List.copyOf(accounts)));
        accountPool = spy(new AccountPoolService(repository, null, properties, policy));
        accountPool.refreshCache().block();
        doReturn(Mono.empty()).when(accountPool).recordSuccess(anyString(), anyLong(), any());
        doReturn(Mono.empty()).when(accountPool).recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class),
            any(), anyString(), any(AccountPoolRequestContext.class));
        when(registry.snapshot()).thenReturn(snapshot);
        facade = new ProxyFacade(
            apiKeyService,
            accountPool,
            mock(AccountAutoSuspendService.class),
            accountSettings,
            mock(DisabledAccountScheduledTestService.class),
            modelCatalog,
            modelMapping,
            rateLimiter,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            kiroClient,
            openAiClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            new OpenAiResponseFactory(objectMapper),
            stateStore,
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper),
            anthropicClient,
            new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper),
            payments,
            pricing,
            statsService,
            admissionStore,
            ownerStore,
            new TierRuntime(new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, accountPool, payments, pricing, bindings),
                bindings)
        );
    }

    /** A Claude Code request: the Messages endpoint, which routes a Claude model to the Anthropic keys and then Kiro. */
    private ResponseEntity<?> messages(String session, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5").put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", text);
        return facade.messages(body, exchange("/v1/messages", SESSION_HEADER, session)).block();
    }

    /** An OpenCode request: the Chat Completions endpoint. */
    private ResponseEntity<?> chat(String model, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", text);
        return facade.chat(body, exchange("/v1/chat/completions", null, null)).block();
    }

    /** A Codex request: the Responses endpoint. */
    private ResponseEntity<?> responses(String previousResponseId, String session, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-5.4").put("input", text);
        if (previousResponseId != null) {
            body.put("previous_response_id", previousResponseId);
        }
        return facade.responses(body, exchange("/v1/responses", "x-session-affinity", session)).block();
    }

    private static MockServerWebExchange exchange(String path, String header, String value) {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post(path);
        if (header != null) {
            request.header(header, value);
        }
        return MockServerWebExchange.from(request);
    }

    private Mono<PlatformCompletionResult> dispatch(ProxyAccountEntity account) {
        dispatched.add(account.getAccountId());
        return answers.getOrDefault(account.getAccountId(), () -> Mono.just(ANSWER)).get();
    }

    private void fails(String accountId, Throwable error) {
        answers.put(accountId, () -> Mono.error(error));
    }

    private List<StatsService.RequestLog> logged() {
        ArgumentCaptor<StatsService.RequestLog> rows = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, atLeastOnce()).log(rows.capture());
        return rows.getAllValues();
    }

    /** What the plan of the latest reservation says the tier would reserve for the request. */
    private BigDecimal estimateOf(String tierRef) {
        for (JsonNode level : ledger.reserved.getLast().pricing().path("tierPlan").path("levels")) {
            if (level.path("tierRef").asString().equals(tierRef)) {
                return level.path("estimate").decimalValue();
            }
        }
        throw new AssertionError("the plan has no tier " + tierRef);
    }

    private static void assertServedBy(ResponseEntity<?> response, String tierId, String level, String mode) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo(tierId);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo(level);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo(mode);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
    }

    /** The billing calls of the facade as the database would have received them. */
    private static final class Ledger {
        final List<Reserved> reserved = new ArrayList<>();
        final List<String> leasedTo = new ArrayList<>();
        final List<Settled> settled = new ArrayList<>();
        final List<Boolean> released = new ArrayList<>();
        int plainDispatches;

        BigDecimal reservedCredits() {
            return reserved.getLast().credits();
        }

        List<String> holdLevels() {
            List<String> hold = new ArrayList<>();
            reserved.getLast().pricing().path("tierPlan").path("holdLevels").forEach(level -> hold.add(level.asString()));
            return hold;
        }

        /** The one settlement of the request: under this tier, level and factor, for the standard price times the factor. */
        void assertCharged(String tierRef, int level, String factor, String credits) {
            assertThat(settled).singleElement().satisfies(charge -> {
                assertThat(charge.tierRef()).isEqualTo(tierRef);
                assertThat(charge.level()).isEqualTo(level);
                assertThat(charge.factor()).isEqualByComparingTo(factor);
                assertThat(charge.credits()).isEqualByComparingTo(credits);
                // Never more than the tier that served would ask, and never more than was held for the request.
                assertThat(charge.credits()).isEqualByComparingTo(QUOTE.scaled(new BigDecimal(factor)).cost(USAGE));
                assertThat(charge.credits()).isLessThanOrEqualTo(reservedCredits());
            });
        }

        void assertEveryRequestWasChargedTheTierThatServed(List<String> tierRefs) {
            assertThat(settled).extracting(Settled::tierRef).containsExactlyElementsOf(tierRefs);
            assertThat(settled).allSatisfy(charge -> assertThat(charge.credits())
                .isEqualByComparingTo(QUOTE.scaled(charge.factor()).cost(USAGE)));
        }
    }

    private record Reserved(BigDecimal credits, JsonNode pricing) {
    }

    private record Settled(BigDecimal credits, String tierRef, Integer level, BigDecimal factor) {
    }

    private static SourceTierSnapshot anthropic() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("anthropic", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("anthropic", "kiro", 2, "0.6", AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of());
    }

    private static SourceTierSnapshot openAi() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, "0.5", AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of());
    }

    private static Tier tier(String vendor, String tierId, int level, String factor, AccountAllocationCategory category) {
        return new Tier(vendor, tierId, level, tierId, new BigDecimal(factor), EnumSet.of(category), true, true, null);
    }

    private static ProxyAccountEntity officialKey(String accountId) {
        return account(accountId, "Anthropic", "api-key");
    }

    private static ProxyAccountEntity kiro(String accountId) {
        ProxyAccountEntity account = account(accountId, "BuilderId", "idc");
        account.setAccessToken("kiro-token");
        account.setRegion("us-east-1");
        return account;
    }

    private static ProxyAccountEntity officialOpenAi(String accountId) {
        return account(accountId, "OpenAI", "api-key");
    }

    private static ProxyAccountEntity codex(String accountId) {
        return account(accountId, "OpenAI", "codex");
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setEnabled(true);
        return account;
    }

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
