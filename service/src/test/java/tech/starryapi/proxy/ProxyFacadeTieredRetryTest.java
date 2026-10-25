package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
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
import tech.starryapi.proxy.tier.TierResponseHeaders;
import tech.starryapi.proxy.tier.TierSessionBindings;
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
 * What a tiered request does when the account it was dispatched to fails (design 4.5), end to end through the facade's
 * dispatch loop: which tier is tried next, when the request is held to the tier it is in, and how the reservation is
 * released when it ends. The upstream clients are stand-ins that answer per account; the account pool and admission are
 * the ones of the selection test.
 */
class ProxyFacadeTieredRetryTest {

    private static final String SESSION = "key-1:session-1";
    private static final String ENDPOINT = "/v1/chat/completions";
    private static final String REQUEST_ID = "request-1";
    private static final PlatformCompletionResult ANSWER =
        new PlatformCompletionResult("OK", new PlatformUsage(4, 6, BigDecimal.ZERO, 0, 0, 0));
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
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    /** The accounts of the pool's cache, in the order it offers them. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** Accounts admission refuses. */
    private final Set<String> busy = new HashSet<>();
    /** Accounts in the cache that selection cannot pick: cooling down or quarantined. */
    private final Set<String> cooling = new HashSet<>();
    /** What each account answers when it is dispatched to; an account that is not listed answers {@link #ANSWER}. */
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> answers = new HashMap<>();
    /** The accounts the upstream clients were called with, in order. */
    private final List<String> dispatched = new ArrayList<>();
    private RoutingPolicySettings policy = RoutingPolicySettings.defaults();

    private final ProxyFacade facade = new ProxyFacade(
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
        mock(StatsService.class),
        admissionStore,
        ownerStore,
        TierRuntime.disabled()
    );

    @BeforeEach
    void standInForTheCollaborators() {
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString()))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59))));
        when(apiKeyService.renewDispatchLease(any(), anyString())).thenReturn(Mono.just(true));
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(), anyString(), any(), anyLong(), anyLong())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.empty());
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenAnswer(invocation -> Mono.just(policy));
        when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
        when(accountPool.recordSuccess(anyString(), anyLong(), any())).thenReturn(Mono.empty());
        when(accountPool.recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(),
            any(AccountPoolRequestContext.class))).thenReturn(Mono.empty());
        when(accountPool.cachedAccount(anyString())).thenAnswer(invocation ->
            cached.stream().filter(account -> account.getAccountId().equals(invocation.getArgument(0))).findFirst());
        when(accountPool.selectInScope(any(), any(), anyString(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(1);
            Optional<ProxyAccountEntity> found = cached.stream().filter(filter).findFirst();
            return found.<Mono<ProxyAccountEntity>>map(Mono::just).orElseGet(() -> Mono.error(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", invocation.getArgument(2))));
        });
        // What the planner finds in the cache: every account, usable or not, so that no tier is left out of a plan.
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenReturn(true);
        // Whether the tier that failed has another account to offer: what selection could still pick from the cache.
        when(accountPool.hasSelectableAccount(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return Mono.just(cached.stream().filter(account -> !cooling.contains(account.getAccountId())).anyMatch(filter));
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

    // ---- crossing to the next tier ----

    @Test
    void aRefusalToServeFromTheFirstTierAndARateLimitFromTheSecondReleaseTheReservationAsUnbilled() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(402, "insufficient_quota", true));
        fails("kiro-1", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                // The error of the last attempt, as an untiered request ends with.
                assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(error.getCode()).isEqualTo("UPSTREAM_ERROR");
            });

        assertThat(dispatched).containsExactly("official-1", "kiro-1");
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(true), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(accountPool).recordRequestFailure(eq("official-1"), any(AccountPoolService.UpstreamErrorType.class), eq(402),
            anyString(), any(AccountPoolRequestContext.class));
    }

    @Test
    void aDefinitiveFailureOfTheFirstTierIsServedByTheSecondTier() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));

        ResponseEntity<?> response = chat(anthropicPlan(), true).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(dispatched).containsExactly("official-1", "kiro-1");
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void theFirstTierIsRetriedWithinItBeforeTheSecondOneIsTried() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));

        ResponseEntity<?> response = chat(anthropicPlan(), true).block();

        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("official-1", "official-2");
    }

    // ---- narrowing ----

    @Test
    void aTimeoutKeepsTheRequestInItsTierAndLeavesTheReservationForReview() {
        policy = new RoutingPolicySettings(true, false, false, false, 1, 3, 3, 3, 2);
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        fails("official-1", new TimeoutException("no answer"));
        fails("official-2", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        // The timed-out request may still be running at the first account: the second tier is never asked, and a
        // later definitive failure does not make the whole request free.
        assertThat(dispatched).containsExactly("official-1", "official-2");
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(false), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(true), anyString());
    }

    @Test
    void aNarrowedRequestGetsNoDispatchBeyondTheBudget() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        fails("official-1", new IOException("connection reset"));
        fails("official-2", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block()).isInstanceOf(ApiException.class);

        // The reserved slot is only for requests that cannot have generated anything; this one may have.
        assertThat(dispatched).containsExactly("official-1", "official-2");
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(false), anyString());
    }

    @Test
    void aKiroStreamThatStoppedAfterItsFirstContentIsNotGeneratedAgainByTheNextTier() {
        loaded(kiroThenOfficial());
        add(kiro("kiro-1"), officialKey("official-1"));
        fails("kiro-1", new UpstreamException(502, "stream ended early", true, null, true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void aKiroRetryThatCannotConnectAfterACutOffStreamIsNotGeneratedAgainByTheNextTier() {
        loaded(kiroThenOfficial());
        add(kiro("kiro-1"), officialKey("official-1"));
        // The client retried the stream that was cut off after its first content, and the retry did not connect: the error
        // is a plain ConnectException, and the flag the facade gave the client is all that remembers the first attempt.
        answers.put("kiro-1", () -> Mono.deferContextual(context -> {
            context.<AtomicBoolean>get(UpstreamException.GENERATION_STARTED_CONTEXT_KEY).set(true);
            return Mono.error(new ConnectException("Connection refused"));
        }));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(dispatched).containsExactly("kiro-1");
    }

    @Test
    void aKiroConnectionRefusedWithNoContentBeforeItStillCrossesToTheNextTier() {
        loaded(kiroThenOfficial());
        add(kiro("kiro-1"), officialKey("official-1"));
        fails("kiro-1", new ConnectException("Connection refused"));

        ResponseEntity<?> response = chat(anthropicPlan(), true).block();

        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("kiro-1", "official-1");
    }

    @Test
    void aKiroFailureBeforeAnyContentStillCrossesToTheNextTier() {
        loaded(kiroThenOfficial());
        add(kiro("kiro-1"), officialKey("official-1"));
        fails("kiro-1", new UpstreamException(502, "stream ended early", true));

        ResponseEntity<?> response = chat(anthropicPlan(), true).block();

        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("kiro-1", "official-1");
    }

    // ---- the reserved slot ----

    @Test
    void twoFailuresOfTheFirstTierStillReachTheSecondOneOnTheReservedSlot() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));

        ResponseEntity<?> response = chat(anthropicPlan(), true).block();

        assertThat(response).isNotNull();
        // Three dispatches against a budget of two: the third could only go to the tier below.
        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1");
    }

    @Test
    void theReservedSlotIsUsedOnceAndOnlyToCross() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"), kiro("kiro-2"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));
        fails("kiro-1", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block()).isInstanceOf(ApiException.class);

        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1");
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(true), anyString());
    }

    @Test
    void withTheReservedSlotSwitchedOffTheBudgetIsTheEnd() {
        loaded(anthropicTiers(false));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(dispatched).containsExactly("official-1", "official-2");
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(true), anyString());
    }

    // ---- the time guard ----

    @Test
    void aRequestOlderThanFourMinutesIsNotSentToTheNextTier() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true, Instant.now().minus(Duration.ofSeconds(250))).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(dispatched).containsExactly("official-1");
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(true), anyString());
    }

    // ---- running out of accounts on a retry ----

    @Test
    void aRetryThatFindsNoAdmissionCapacityReturnsTheErrorThatSentItThere() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        // The second tier has nothing but busy accounts: the retry spends its 64 refusals and runs out of capacity.
        for (int i = 0; i < 70; i++) {
            add(kiro("kiro-" + i));
            busy.add("kiro-" + i);
        }

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(error.getCode()).isEqualTo("UPSTREAM_ERROR");
            });

        assertThat(dispatched).containsExactly("official-1");
        // Its own 503 would not be definitive and would send the dispatched reservation to review.
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(true), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(false), anyString());
    }

    @Test
    void aRetryWithNoOtherAccountReturnsTheErrorThatSentItThere() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("UPSTREAM_ERROR"));

        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(true), anyString());
    }

    @Test
    void aFirstSelectionThatFindsNoAccountIsStillTheUnavailabilityItIs() {
        loaded(anthropicTiers(true));

        assertThatThrownBy(() -> chat(anthropicPlan(), true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));

        assertThat(dispatched).isEmpty();
    }

    // ---- responses ----

    @Test
    void aResponseChainThatCannotMoveIsNotRetriedOnAnotherAccount() {
        loaded(openAiTiers());
        add(officialOpenAi("official-1"), codex("codex-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> responses(openAiPlan(), false).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(dispatched).containsExactly("official-1");
        verify(apiKeyService).releaseUsage(any(), eq(REQUEST_ID), eq(true), anyString());
    }

    @Test
    void aResponseThatCanMoveIsServedByTheNextTierAndItsOwnerRemembered() {
        loaded(openAiTiers());
        add(officialOpenAi("official-1"), codex("codex-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));

        ResponseEntity<?> response = responses(openAiPlan(), true).block();

        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("official-1", "codex-1");
        verify(ownerStore).remember(anyString(), eq("codex-1"));
    }

    @Test
    void theOwnerOfAChainThatFailsDefinitivelyIsFollowedByTheWalkFromTheStartOfTheRequest() {
        loaded(openAiTiers());
        add(officialOpenAi("official-1"), codex("codex-owner"));
        fails("codex-owner", new UpstreamException(429, "slow down", true));

        ResponseEntity<?> response = responses(openAiPlan("codex-owner"), true).block();

        // The owner sits in the second tier and was tried before the tiers were looked at; the first tier was not
        // passed and is still offered once the owner has failed.
        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("codex-owner", "official-1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void theOwnerFailingWithTheBudgetSpentStillLetsTheRequestStartTheWalkOnTheReservedSlot() {
        policy = new RoutingPolicySettings(true, false, false, false, 1, 1, 3, 2, 2);
        loaded(openAiTiers());
        add(officialOpenAi("official-1"), codex("codex-owner"));
        fails("codex-owner", new UpstreamException(429, "slow down", true));

        ResponseEntity<?> response = responses(openAiPlan("codex-owner"), true).block();

        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("codex-owner", "official-1");
    }

    @Test
    void aChainWhoseOwnerFailedThatCannotMoveIsNotRetriedOnAnotherAccount() {
        loaded(openAiTiers());
        add(officialOpenAi("official-1"), codex("codex-owner"));
        fails("codex-owner", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> responses(openAiPlan("codex-owner"), false).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(dispatched).containsExactly("codex-owner");
    }

    @Test
    void aFailureOfTheOwnerThatMayHaveBeenBilledKeepsTheRequestInTheOwnersTier() {
        loaded(openAiTiers());
        add(officialOpenAi("official-1"), codex("codex-owner"), codex("codex-2"));
        fails("codex-owner", new TimeoutException("no answer"));

        ResponseEntity<?> response = responses(openAiPlan("codex-owner"), true).block();

        // The owner may still be generating the answer: the first tier is not asked to generate it again.
        assertThat(response).isNotNull();
        assertThat(dispatched).containsExactly("codex-owner", "codex-2");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("codex");
    }

    // ---- the lease ----

    @Test
    void aStreamThatRunsPastTheWindowKeepsItsLeaseAndIsSettledAtItsTier() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), kiro("kiro-1"));
        // 21 minutes, longer than the window of 960 seconds: the answer arrives, trickling in all the while.
        answers.put("official-1", () -> Mono.delay(Duration.ofMinutes(21)).thenReturn(ANSWER));
        TierPlan plan = anthropicPlan();

        StepVerifier.withVirtualTime(() -> chat(plan, true))
            .expectSubscription()
            .expectNoEvent(Duration.ofMinutes(21).minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext(response -> {
                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
            })
            .verifyComplete();

        assertThat(dispatched).containsExactly("official-1");
        // A quarter of the window (240 seconds) apart: at 240, 480, 720, 960 and 1 200 seconds, and the dispatch ended before the next.
        verify(apiKeyService, times(5)).renewDispatchLease(any(), eq(REQUEST_ID));
        // The reservation is neither released nor left for review: the answer is charged at the tier that gave it.
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        verify(apiKeyService).accountUsageServed(any(), eq(REQUEST_ID), any(), anyLong(), anyLong(), eq("anthropic:official"), eq(1), any());
    }

    @Test
    void aStreamOfTheOpenAiRouteThatRunsPastTheWindowIsNotCutOffEither() {
        loaded(openAiTiers());
        add(officialOpenAi("official-1"), codex("codex-1"));
        answers.put("official-1", () -> Mono.delay(Duration.ofMinutes(21)).thenReturn(ANSWER));
        TierPlan plan = openAiPlan();

        StepVerifier.withVirtualTime(() -> responses(plan, true))
            .expectSubscription()
            .expectNoEvent(Duration.ofMinutes(21).minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(200))
            .verifyComplete();

        assertThat(dispatched).containsExactly("official-1");
        verify(apiKeyService, times(5)).renewDispatchLease(any(), eq(REQUEST_ID));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void theLeaseIsRenewedAQuarterOfTheWindowAfterTheDispatchAndNotBefore() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        answers.put("official-1", Mono::never);
        TierPlan plan = anthropicPlan();
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            Disposable running = chat(plan, true).subscribe(response -> {}, error -> {});

            scheduler.advanceTimeBy(Duration.ofSeconds(239));
            verify(apiKeyService, never()).renewDispatchLease(any(), anyString());
            scheduler.advanceTimeBy(Duration.ofSeconds(1));
            verify(apiKeyService, times(1)).renewDispatchLease(any(), eq(REQUEST_ID));
            scheduler.advanceTimeBy(Duration.ofSeconds(240));
            verify(apiKeyService, times(2)).renewDispatchLease(any(), eq(REQUEST_ID));
            running.dispose();
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void theRenewalEndsWithTheDispatchWhenItFinishes() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        answers.put("official-1", () -> Mono.delay(Duration.ofSeconds(400)).thenReturn(ANSWER));
        TierPlan plan = anthropicPlan();
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            chat(plan, true).subscribe(response -> {}, error -> {});

            // Renewed once, at 240 seconds, and never after the answer came at 400 (the next would have been at 480).
            scheduler.advanceTimeBy(Duration.ofHours(2));
            verify(apiKeyService, times(1)).renewDispatchLease(any(), eq(REQUEST_ID));
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void theRenewalEndsWithTheDispatchWhenTheClientGivesUp() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        answers.put("official-1", Mono::never);
        TierPlan plan = anthropicPlan();
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            Disposable running = chat(plan, true).subscribe(response -> {}, error -> {});
            scheduler.advanceTimeBy(Duration.ofSeconds(330));
            running.dispose();

            scheduler.advanceTimeBy(Duration.ofHours(2));
            verify(apiKeyService, times(1)).renewDispatchLease(any(), eq(REQUEST_ID));
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void theRenewalEndsWithTheDispatchWhenItFails() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        answers.put("official-1", () -> Mono.delay(Duration.ofSeconds(400)).then(Mono.error(new UpstreamException(500, "broken", false))));
        TierPlan plan = anthropicPlan();
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.getOrSet();
        try {
            chat(plan, true).subscribe(response -> {}, error -> {});

            scheduler.advanceTimeBy(Duration.ofHours(2));
            verify(apiKeyService, times(1)).renewDispatchLease(any(), eq(REQUEST_ID));
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    void aFailedRenewalDoesNotEndTheDispatchAndIsTriedAgainAtTheNextTick() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        answers.put("official-1", () -> Mono.delay(Duration.ofSeconds(700)).thenReturn(ANSWER));
        when(apiKeyService.renewDispatchLease(any(), anyString()))
            .thenReturn(Mono.error(new IllegalStateException("database unavailable")), Mono.just(true));
        TierPlan plan = anthropicPlan();

        StepVerifier.withVirtualTime(() -> chat(plan, true))
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(699))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(200))
            .verifyComplete();

        verify(apiKeyService, times(2)).renewDispatchLease(any(), eq(REQUEST_ID));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void aReservationThatIsNoLongerDispatchedIsNotRenewedAgain() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        answers.put("official-1", () -> Mono.delay(Duration.ofMinutes(20)).thenReturn(ANSWER));
        // The sweeper turned it into a review (or it was settled): there is nothing left to renew.
        when(apiKeyService.renewDispatchLease(any(), anyString())).thenReturn(Mono.just(false));
        TierPlan plan = anthropicPlan();

        StepVerifier.withVirtualTime(() -> chat(plan, true))
            .expectSubscription()
            .expectNoEvent(Duration.ofMinutes(20).minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(200))
            .verifyComplete();

        // Settled at its tier all the same: the settlement clears the review.
        verify(apiKeyService, times(1)).renewDispatchLease(any(), eq(REQUEST_ID));
        verify(apiKeyService).accountUsageServed(any(), eq(REQUEST_ID), any(), anyLong(), anyLong(), eq("anthropic:official"), eq(1), any());
    }

    @Test
    void theLeaseIsOnlyRenewedUpToTheHourAReservationCanBeLeasedForAndTheDispatchGoesOn() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"), kiro("kiro-1"));
        answers.put("official-1", () -> Mono.delay(Duration.ofMinutes(30)).thenReturn(ANSWER));
        // Only 700 seconds remain until the reservation can no longer be leased: ticks at 240 and 480 can still extend it.
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString()))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofSeconds(700))));
        TierPlan plan = anthropicPlan();

        StepVerifier.withVirtualTime(() -> chat(plan, true))
            .expectSubscription()
            .expectNoEvent(Duration.ofMinutes(30).minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(200))
            .verifyComplete();

        // Past the hour the dispatch is not given up: the sweeper may make a review of the reservation, and the
        // settlement at the end of the stream clears it.
        assertThat(dispatched).containsExactly("official-1");
        verify(apiKeyService, times(2)).renewDispatchLease(any(), eq(REQUEST_ID));
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), anyBoolean(), anyString());
        verify(apiKeyService).accountUsageServed(any(), eq(REQUEST_ID), any(), anyLong(), anyLong(), eq("anthropic:official"), eq(1), any());
    }

    @Test
    void aKiroDispatchIsBoundedByTheClientItselfAndItsLeaseIsNotRenewed() {
        loaded(kiroThenOfficial());
        add(kiro("kiro-1"), officialKey("official-1"));
        // The Kiro client bounds each of its tries by the request timeout, which the window covers; the facade adds nothing.
        answers.put("kiro-1", () -> Mono.delay(Duration.ofSeconds(1_500)).thenReturn(ANSWER));
        TierPlan plan = anthropicPlan();

        StepVerifier.withVirtualTime(() -> chat(plan, true))
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(1_499))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(200))
            .verifyComplete();

        assertThat(dispatched).containsExactly("kiro-1");
        verify(apiKeyService, never()).renewDispatchLease(any(), anyString());
    }

    @Test
    void aRequestWithoutAReservationHasNoLeaseToRenewAndNoLimit() {
        loaded(anthropicTiers(true));
        add(officialKey("official-1"));
        // The anonymous caller has no reservation to lease.
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString())).thenReturn(Mono.empty());
        answers.put("official-1", () -> Mono.delay(Duration.ofHours(2)).thenReturn(ANSWER));
        TierPlan plan = anthropicPlan();

        StepVerifier.withVirtualTime(() -> chat(plan, true))
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(7_199))
            .thenAwait(Duration.ofSeconds(1))
            .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(200))
            .verifyComplete();

        verify(apiKeyService, never()).renewDispatchLease(any(), anyString());
    }

    // ---- what counts as unbilled ----

    @Test
    void tierDefinitiveCountsA402AsUnbilledAndKeepsWhatMayHaveBeenGeneratedUncertain() {
        UpstreamException noQuota = new UpstreamException(402, "insufficient_quota", true);
        ApiException mapped = (ApiException) new ApiException(HttpStatus.TOO_MANY_REQUESTS, "UPSTREAM_ERROR", "insufficient_quota")
            .initCause(noQuota);

        assertThat(facade.tierDefinitive(noQuota)).isTrue();
        assertThat(facade.tierDefinitive(mapped)).isTrue();
        assertThat(facade.tierDefinitive(new UpstreamException(429, "slow down", true))).isTrue();
        assertThat(facade.tierDefinitive(new UpstreamException(503, "overloaded", true))).isTrue();
        assertThat(facade.tierDefinitive(new ConnectException("refused"))).isTrue();
        // Whatever may have been generated stays uncertain.
        assertThat(facade.tierDefinitive(new TimeoutException("no answer"))).isFalse();
        assertThat(facade.tierDefinitive(new IOException("connection reset"))).isFalse();
        assertThat(facade.tierDefinitive(new UpstreamException(500, "broken", false))).isFalse();
    }

    @Test
    void anUntieredRequestRejectedWith402IsStillLeftForReview() {
        when(accountPool.selectKiroAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(kiro("kiro-1")));
        fails("kiro-1", new UpstreamException(402, "insufficient_quota", true));

        assertThatThrownBy(() -> facade.chat(chatBody(), MockServerWebExchange.from(MockServerHttpRequest.post(ENDPOINT))).block())
            .isInstanceOf(ApiException.class);

        // Unchanged for a vendor without tiers (O7 corrects it for everyone, separately).
        verify(apiKeyService).releaseUsage(any(), anyString(), eq(false), anyString());
        verify(apiKeyService, never()).releaseUsage(any(), anyString(), eq(true), anyString());
        verify(apiKeyService, times(1)).reserveUsage(any(), anyString(), any(), anyString());
    }

    // ---- fixtures ----

    private Mono<ResponseEntity<?>> chat(TierPlan plan, boolean movable) {
        return chat(plan, movable, Instant.now());
    }

    private Mono<ResponseEntity<?>> chat(TierPlan plan, boolean movable, Instant start) {
        return facade.serveTieredChat(run(plan, ProxyFacade.UpstreamRoute.ANTHROPIC, chatBody(), movable, start),
            ProxyFacade.ResponseMode.OPENAI_CHAT);
    }

    private Mono<ResponseEntity<?>> responses(TierPlan plan, boolean movable) {
        return facade.serveTieredResponses(run(plan, ProxyFacade.UpstreamRoute.OPENAI, responsesBody(), movable, Instant.now()));
    }

    private ProxyFacade.TieredRequest run(
        TierPlan plan,
        ProxyFacade.UpstreamRoute route,
        JsonNode body,
        boolean movable,
        Instant start
    ) {
        String model = body.path("model").asString();
        return new ProxyFacade.TieredRequest(plan, principal, REQUEST_ID, ENDPOINT, model, model, SESSION, false, route,
            false, false, false, policy, body, body, start, movable, movable);
    }

    private Mono<PlatformCompletionResult> dispatch(ProxyAccountEntity account) {
        dispatched.add(account.getAccountId());
        return answers.getOrDefault(account.getAccountId(), () -> Mono.just(ANSWER)).get();
    }

    private void fails(String accountId, Throwable error) {
        answers.put(accountId, () -> Mono.error(error));
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

    private TierPlan openAiPlan() {
        return plan(new TierPlanRequest(REQUEST_ID, "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, false, false, true, responsesBody(), null, false, null, null));
    }

    /** The plan of a request that continues a response chain whose owner is {@code owner}; the chain can be replayed elsewhere. */
    private TierPlan openAiPlan(String owner) {
        return plan(new TierPlanRequest(REQUEST_ID, "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, false, false, true, responsesBody(), owner, true, null, null));
    }

    private TierPlan plan(TierPlanRequest request) {
        ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(modelCatalog);
        return new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, accountPool, payments, pricing, TierSessionBindings.disabled())
            .plan(request).block();
    }

    private JsonNode chatBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private JsonNode responsesBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-5.5").put("input", "continue");
        body.put("max_output_tokens", 4096);
        return body;
    }

    private static SourceTierSnapshot anthropicTiers(boolean reservedSlot) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, reservedSlot, 5L)),
            List.of(
                tier("anthropic", "official", 1, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("anthropic", "kiro", 2, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of());
    }

    private static SourceTierSnapshot kiroThenOfficial() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("anthropic", "kiro", 1, AccountAllocationCategory.KIRO),
                tier("anthropic", "official", 2, AccountAllocationCategory.OFFICIAL_API_KEY)),
            List.of(), List.of(), List.of());
    }

    private static SourceTierSnapshot openAiTiers() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, AccountAllocationCategory.CODEX)),
            List.of(), List.of(), List.of());
    }

    private static Tier tier(String vendor, String tierId, int level, AccountAllocationCategory category) {
        return new Tier(vendor, tierId, level, tierId, BigDecimal.ONE, EnumSet.of(category), true, true, null);
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
