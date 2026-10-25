package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos.RoutingPolicySettings;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionDecision;
import tech.starryapi.account.allocation.AccountAdmissionStore.AdmissionRequest;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
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
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.RequestBounds;
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierRoute;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a tiered request reserves and is charged (design 5.2 to 5.4, 4.6): the largest estimate of the tiers it may be
 * served from, then the price of the tier that served it, settled under that tier. The upstream clients are stand-ins
 * that answer per account; the account pool and admission are those of the retry test. Also what must not change for a
 * vendor without tiers.
 */
class ProxyFacadeTieredBillingTest {

    private static final String ENDPOINT = "/v1/messages";
    private static final String REQUEST_ID = "request-1";
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult ANSWER = new PlatformCompletionResult("OK", USAGE);
    /** claude-sonnet-4.5: $3 per million tokens in, $15 out, $0.30 cache read. */
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
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
    private final StatsService statsService = mock(StatsService.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);
    private final DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(
        registry, new ModelPoolKeyResolver(modelCatalog), modelCatalog, accountPool, payments, pricing, TierSessionBindings.disabled());

    /** The accounts of the pool's cache, in the order it offers them. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    private final Set<String> busy = new HashSet<>();
    /** What each account answers when it is dispatched to; an account that is not listed answers {@link #ANSWER}. */
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> answers = new HashMap<>();
    private final List<String> dispatched = new ArrayList<>();
    private TierRuntime tierRuntime = new TierRuntime(planner);

    @BeforeEach
    void standInForTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString()))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59))));
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(), anyString(), any(), anyLong(), anyLong())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.empty());
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenReturn(Mono.just(RoutingPolicySettings.defaults()));
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
        when(accountPool.recordSuccess(anyString(), anyLong(), any())).thenReturn(Mono.empty());
        when(accountPool.recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(),
            any(AccountPoolRequestContext.class))).thenReturn(Mono.empty());
        when(accountPool.selectInScope(any(), any(), anyString(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(1);
            Optional<ProxyAccountEntity> found = cached.stream().filter(filter).findFirst();
            return found.<Mono<ProxyAccountEntity>>map(Mono::just).orElseGet(() -> Mono.error(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", invocation.getArgument(2))));
        });
        when(accountPool.hasSelectableAccount(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return Mono.just(cached.stream().anyMatch(filter));
        });
        when(accountPool.refreshCacheIfStale()).thenReturn(Mono.empty());
        when(admissionStore.acquire(any())).thenAnswer(invocation -> {
            AdmissionRequest request = invocation.getArgument(0);
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
        when(openAiClient.completeResponses(any(), any(), anyString())).thenAnswer(invocation -> dispatch(invocation.getArgument(0)));
    }

    // ---- what is reserved ----

    @Test
    void theReservationIsTheLargestEstimateOfTheTiersAndTheSnapshotDescribesThem() throws Exception {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));

        serve(anthropicPlan());

        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(apiKeyService).reserveUsage(eq(principal), eq(REQUEST_ID), reserved.capture(), snapshot.capture());
        long input = RequestBounds.nativeInputTokens(chatBody());
        // The first tier costs the most, so it sets the reservation; the second is covered by it.
        assertThat(reserved.getValue()).isEqualByComparingTo(QUOTE.estimate(input, 4096));
        assertThat(reserved.getValue()).isGreaterThan(QUOTE.scaled(new BigDecimal("0.6")).estimate(input, 4096));

        JsonNode pricingSnapshot = objectMapper.readTree(snapshot.getValue());
        // The fields of an untiered snapshot describe the standard price.
        assertThat(pricingSnapshot.path("model").asString()).isEqualTo("claude-sonnet-4.5");
        assertThat(pricingSnapshot.path("input").decimalValue()).isEqualByComparingTo("0.000003");
        assertThat(pricingSnapshot.path("output").decimalValue()).isEqualByComparingTo("0.000015");
        JsonNode plan = pricingSnapshot.path("tierPlan");
        assertThat(plan.path("vendor").asString()).isEqualTo("anthropic");
        assertThat(plan.path("configVersion").asLong()).isEqualTo(5L);
        assertThat(plan.path("mode").asString()).isEqualTo("auto");
        assertThat(plan.path("startTierRef").asString()).isEqualTo("anthropic:official");
        assertThat(plan.path("holdLevels")).extracting(JsonNode::asString).containsExactly("anthropic:official", "anthropic:kiro");
        JsonNode levels = plan.path("levels");
        assertThat(levels).hasSize(2);
        assertThat(levels.path(0).path("tierRef").asString()).isEqualTo("anthropic:official");
        assertThat(levels.path(0).path("level").asInt()).isEqualTo(1);
        assertThat(levels.path(0).path("name").asString()).isEqualTo("official");
        assertThat(levels.path(0).path("factor").decimalValue()).isEqualByComparingTo("1");
        assertThat(levels.path(0).path("estimate").decimalValue()).isEqualByComparingTo(QUOTE.estimate(input, 4096));
        assertThat(levels.path(0).path("dispatchKinds")).extracting(JsonNode::asString).containsExactly("NATIVE");
        assertThat(levels.path(1).path("tierRef").asString()).isEqualTo("anthropic:kiro");
        assertThat(levels.path(1).path("level").asInt()).isEqualTo(2);
        assertThat(levels.path(1).path("factor").decimalValue()).isEqualByComparingTo("0.6");
        assertThat(levels.path(1).path("estimate").decimalValue())
            .isEqualByComparingTo(QUOTE.scaled(new BigDecimal("0.6")).estimate(input, 4096));
    }

    @Test
    void aSecondTierThatCostsMoreThanTheFirstSetsTheReservationSoTheFallbackIsCovered() {
        loaded(anthropicTiers("0.5", "1.5"));
        add(kiro("kiro-1"));

        serve(anthropicPlan());

        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).reserveUsage(eq(principal), eq(REQUEST_ID), reserved.capture(), anyString());
        long input = RequestBounds.nativeInputTokens(chatBody());
        assertThat(reserved.getValue()).isEqualByComparingTo(QUOTE.scaled(new BigDecimal("1.5")).estimate(input, 4096));
    }

    @Test
    void theCallerWithoutAKeyIsNeitherReservedNorSettled() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));
        ApiKeyPrincipal anonymous = new ApiKeyPrincipal("anonymous", "whale-assistant", "ADMIN", "whale-assistant", null,
            BigDecimal.ZERO, BigDecimal.ZERO, "*");
        TierPlan plan = plan(new TierPlanRequest(REQUEST_ID, "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, false, chatBody(), null, false, null, null));

        ResponseEntity<?> response = facade().serveTieredChat(run(plan, anonymous, chatBody()), ProxyFacade.ResponseMode.OPENAI_CHAT).block();

        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("kiro-1");
        verify(apiKeyService, never()).reserveUsage(any(), anyString(), any(), anyString());
        // The service itself does nothing for the anonymous caller; what the facade asks it to settle is nothing.
        verify(apiKeyService).accountUsageServed(eq(anonymous), eq(REQUEST_ID), eq(BigDecimal.ZERO), anyLong(), anyLong(),
            eq("anthropic:kiro"), eq(2), any());
    }

    // ---- what is charged ----

    @Test
    void aRequestServedByTheSecondTierIsChargedItsPriceAndSettledUnderThatTier() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));

        serve(anthropicPlan());

        // 8,000 in and 1,000 out: $0.039 at the standard price, 0.0234 at 0.6.
        assertThat(QUOTE.cost(USAGE)).isEqualByComparingTo("0.039");
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> factor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).accountUsageServed(eq(principal), eq(REQUEST_ID), credits.capture(), eq(8_000L), eq(1_000L),
            eq("anthropic:kiro"), eq(2), factor.capture());
        assertThat(credits.getValue()).isEqualByComparingTo("0.0234");
        assertThat(factor.getValue()).isEqualByComparingTo("0.6");
        verify(apiKeyService, never()).accountUsage(any(), anyString(), any(), anyLong(), anyLong());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void aRequestServedByTheFirstTierIsChargedTheStandardPriceUnderThatTier() {
        loaded(anthropicTiers("1", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));

        serve(anthropicPlan());

        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).accountUsageServed(eq(principal), eq(REQUEST_ID), credits.capture(), eq(8_000L), eq(1_000L),
            eq("anthropic:official"), eq(1), any());
        assertThat(credits.getValue()).isEqualByComparingTo("0.039");
        assertThat(dispatched).containsExactly("official-1");
    }

    @Test
    void aRequestThatFellToACheaperTierIsNeverChargedThePriceOfTheTierThatFailed() {
        loaded(anthropicTiers("1.5", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));
        answers.put("official-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));

        serve(anthropicPlan());

        assertThat(dispatched).containsExactly("official-1", "kiro-1");
        // The reservation was sized by the dearer first tier; the charge is the cheaper tier that answered.
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).accountUsageServed(eq(principal), eq(REQUEST_ID), credits.capture(), anyLong(), anyLong(),
            eq("anthropic:kiro"), eq(2), any());
        assertThat(credits.getValue()).isEqualByComparingTo("0.0234");
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(),
            eq("anthropic:official"), anyInt(), any());
    }

    @Test
    void aRequestThatFellToADearerTierIsChargedWhatThatTierCosts() {
        loaded(anthropicTiers("0.6", "1.5"));
        add(officialKey("official-1"), kiro("kiro-1"));
        answers.put("official-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));

        serve(anthropicPlan());

        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).accountUsageServed(eq(principal), eq(REQUEST_ID), credits.capture(), anyLong(), anyLong(),
            eq("anthropic:kiro"), eq(2), any());
        // 0.039 x 1.5: within the reservation, which was sized for the dearest tier.
        assertThat(credits.getValue()).isEqualByComparingTo("0.0585");
        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).reserveUsage(eq(principal), eq(REQUEST_ID), reserved.capture(), anyString());
        assertThat(reserved.getValue()).isGreaterThanOrEqualTo(credits.getValue());
    }

    @Test
    void nothingIsSettledWhenNoTierServed() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));
        answers.put("kiro-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));

        assertThatThrownBy(() -> serve(anthropicPlan())).isInstanceOf(ApiException.class);

        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
        verify(apiKeyService, never()).accountUsage(any(), anyString(), any(), anyLong(), anyLong());
        verify(apiKeyService).releaseUsage(eq(principal), eq(REQUEST_ID), eq(true), anyString());
    }

    // ---- the lease ----

    @Test
    void eachDispatchRenewsTheLeaseForTheTierItWentTo() {
        loaded(anthropicTiers("1", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));
        answers.put("official-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));

        serve(anthropicPlan());

        InOrder order = inOrder(apiKeyService);
        order.verify(apiKeyService).markUsageDispatchedTiered(principal, REQUEST_ID, "anthropic:official");
        order.verify(apiKeyService).markUsageDispatchedTiered(principal, REQUEST_ID, "anthropic:kiro");
        verify(apiKeyService, never()).markUsageDispatched(any(), anyString());
    }

    @Test
    void noOtherAccountIsTriedWhenTheLeaseCouldNotCoverAnotherDispatch() {
        loaded(anthropicTiers("1", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));
        answers.put("official-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));
        // A reservation 59 minutes old: 100 seconds remain until it can no longer be leased, and a dispatch needs 960.
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString()))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofSeconds(100))));

        assertThatThrownBy(() -> serve(anthropicPlan()))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(dispatched).containsExactly("official-1");
        verify(apiKeyService, times(1)).markUsageDispatchedTiered(any(), anyString(), anyString());
        verify(apiKeyService).releaseUsage(eq(principal), eq(REQUEST_ID), eq(true), anyString());
    }

    // ---- through the endpoints ----

    @Test
    void aMessagesRequestIsServedByTheTierItFindsAnAccountIn() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));

        ResponseEntity<?> response = facade().messages(messagesRequest(), exchange(ENDPOINT)).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dispatched).containsExactly("kiro-1");
        verify(apiKeyService).reserveUsage(eq(principal), anyString(), any(), contains("\"tierPlan\""));
        verify(apiKeyService).markUsageDispatchedTiered(eq(principal), anyString(), eq("anthropic:kiro"));
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).accountUsageServed(eq(principal), anyString(), credits.capture(), eq(8_000L), eq(1_000L),
            eq("anthropic:kiro"), eq(2), any());
        assertThat(credits.getValue()).isEqualByComparingTo("0.0234");
    }

    @Test
    void aResponsesRequestIsServedAndSettledUnderTheTierThatServedIt() {
        loaded(openAiTiers("1", "0.5"));
        add(codex("codex-1"));
        MockServerWebExchange codexClient = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses").header("User-Agent", "codex_cli_rs/0.50"));

        ResponseEntity<?> response = facade().responses(responsesBody(), codexClient).block();

        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("codex-1");
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).accountUsageServed(eq(principal), anyString(), credits.capture(), eq(8_000L), eq(1_000L),
            eq("openai:codex"), eq(2), any());
        assertThat(credits.getValue()).isEqualByComparingTo("0.0195");
        verify(ownerStore).remember(anyString(), eq("codex-1"));
    }

    // ---- a source pool the client chose ----

    @Test
    void aPinnedSourcePoolWithoutAnAccountAnswers503WithRetryAfterAndTheReservationIsGivenBack() {
        loaded(anthropicTiers("1", "0.6"));
        tierRuntime = new TierRuntime(request -> planner.plan(request).map(this::pinned));
        MockServerWebExchange exchange = exchange(ENDPOINT);

        assertThatThrownBy(() -> facade().messages(messagesRequest(), exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("SOURCE_TIER_UNAVAILABLE");
            });

        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");
        verify(apiKeyService).reserveUsage(any(), anyString(), any(), anyString());
        verify(apiKeyService).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString());
    }

    @Test
    void anAutomaticRequestWithoutAnAccountStaysTheUnavailabilityOfAnUntieredOne() {
        loaded(anthropicTiers("1", "0.6"));
        MockServerWebExchange exchange = exchange(ENDPOINT);

        assertThatThrownBy(() -> facade().messages(messagesRequest(), exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
            });

        assertThat(exchange.getResponse().getHeaders().containsHeader("Retry-After")).isFalse();
    }

    @Test
    void aPinnedRequestThatWasDispatchedAndFailedReturnsTheUpstreamErrorNotTheUnavailability() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));
        answers.put("kiro-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));
        tierRuntime = new TierRuntime(request -> planner.plan(request).map(this::pinned));
        MockServerWebExchange exchange = exchange(ENDPOINT);

        assertThatThrownBy(() -> facade().messages(messagesRequest(), exchange).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("UPSTREAM_ERROR"));

        assertThat(exchange.getResponse().getHeaders().containsHeader("Retry-After")).isFalse();
    }

    // ---- a vendor without tiers ----

    @Test
    void anUntieredRequestKeepsEveryBillingCallItAlwaysMade() throws Exception {
        loaded(SourceTierSnapshot.EMPTY);
        when(accountPool.selectKiroAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(kiro("kiro-1")));

        ResponseEntity<?> response = facade().chat(chatBody(), exchange("/v1/chat/completions")).block();

        assertThat(response).isNotNull();
        ArgumentCaptor<BigDecimal> reserved = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(apiKeyService).reserveUsage(eq(principal), anyString(), reserved.capture(), snapshot.capture());
        // The reservation and the snapshot are the ones of today: the price of the model, byte for byte, and nothing
        // about tiers.
        assertThat(reserved.getValue()).isEqualTo(QUOTE.estimate(RequestBounds.nativeInputTokens(chatBody()), 4096));
        ObjectNode today = objectMapper.createObjectNode();
        today.put("model", "claude-sonnet-4.5").put("input", QUOTE.input()).put("output", QUOTE.output())
            .put("cacheRead", QUOTE.cacheRead()).put("cacheWrite", QUOTE.cacheWrite()).put("fallback", QUOTE.fallback());
        assertThat(snapshot.getValue()).isEqualTo(today.toString());
        verify(apiKeyService).markUsageDispatched(eq(principal), anyString());
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService).accountUsage(eq(principal), anyString(), credits.capture(), eq(8_000L), eq(1_000L));
        assertThat(credits.getValue()).isEqualByComparingTo("0.039");
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString());
        verify(apiKeyService, never()).accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    // ---- fixtures ----

    private ProxyFacade facade() {
        return new ProxyFacade(
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
            new OpenAiResponseStateStore(objectMapper),
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
            tierRuntime
        );
    }

    private ResponseEntity<?> serve(TierPlan plan) {
        return facade().serveTieredChat(run(plan, principal, chatBody()), ProxyFacade.ResponseMode.OPENAI_CHAT).block();
    }

    private ProxyFacade.TieredRequest run(TierPlan plan, ApiKeyPrincipal caller, JsonNode body) {
        String model = body.path("model").asString();
        return new ProxyFacade.TieredRequest(plan, caller, REQUEST_ID, ENDPOINT, model, model, "key-1:session-1", false,
            ProxyFacade.UpstreamRoute.ANTHROPIC, false, false, false, RoutingPolicySettings.defaults(), body, body,
            Instant.now(), true, true);
    }

    /** The plan with the client having named its tier, which nothing yet makes the planner say by itself. */
    private TierPlan pinned(TierPlan plan) {
        TierPlan pinned = spy(plan);
        doReturn(true).when(pinned).pinned();
        return pinned;
    }

    private Mono<PlatformCompletionResult> dispatch(ProxyAccountEntity account) {
        dispatched.add(account.getAccountId());
        return answers.getOrDefault(account.getAccountId(), () -> Mono.just(ANSWER)).get();
    }

    private void add(ProxyAccountEntity... accounts) {
        cached.addAll(List.of(accounts));
    }

    private void loaded(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    private TierPlan anthropicPlan() {
        return plan(new TierPlanRequest(REQUEST_ID, "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, chatBody(), null, false, null, null));
    }

    private TierPlan plan(TierPlanRequest request) {
        return planner.plan(request).block();
    }

    private JsonNode chatBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private JsonNode messagesRequest() {
        return chatBody();
    }

    private JsonNode responsesBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-5.4").put("input", "continue");
        body.put("max_output_tokens", 4096);
        return body;
    }

    private static SourceTierSnapshot anthropicTiers(String firstFactor, String secondFactor) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("anthropic", "official", 1, firstFactor, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("anthropic", "kiro", 2, secondFactor, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of());
    }

    private static SourceTierSnapshot openAiTiers(String firstFactor, String secondFactor) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, firstFactor, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, secondFactor, AccountAllocationCategory.CODEX)),
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

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path));
    }

    private static String contains(String fragment) {
        return org.mockito.ArgumentMatchers.contains(fragment);
    }

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
