package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.TierSessionKeys;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A session that really fell to a lower tier stays there until it has been idle (design 4.7, owner decision D4,
 * invariant I8), end to end through the facade: what pushes a session down and what does not, where the next request
 * of the session starts and what it reserves, and the one way back up. The upstream clients are stand-ins; the account
 * pool and admission are those of the other tiered facade tests, the planner and the bindings are the real ones.
 */
class ProxyFacadeTieredStickyTest {

    private static final String SESSION_HEADER = "x-claude-code-session-id";
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
    private final OpenAiResponseStateStore stateStore = new OpenAiResponseStateStore(objectMapper);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    private final AtomicLong clock = new AtomicLong();
    private final ProxyProperties properties = new ProxyProperties();
    private final TierSessionBindings bindings = new TierSessionBindings(properties, clock::get);
    private final StatsService statsService = mock(StatsService.class);

    /** The accounts of the pool's cache (enabled), in the order it offers them. */
    private final List<ProxyAccountEntity> cached = new ArrayList<>();
    /** Accounts the pool does not offer although they are in its cache: suspended, out of quota or cooling down. */
    private final Set<String> unavailable = new HashSet<>();
    /** Accounts that are usable (neither suspended nor out of quota) but that selection cannot pick: cooling down or quarantined. */
    private final Set<String> cooling = new HashSet<>();
    /** Accounts the pool's cache only offers once it has been reloaded, which it is when no tier has anything to offer. */
    private final Set<String> hiddenUntilReload = new HashSet<>();
    /** The reason admission refuses an account with; an account that is not listed is admitted. */
    private final Map<String, String> refused = new HashMap<>();
    /** What each account answers when it is dispatched to; an account that is not listed answers {@link #ANSWER}. */
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> answers = new HashMap<>();
    /** The accounts the upstream clients were called with, in order. */
    private final List<String> dispatched = new ArrayList<>();
    /** The accounts admission was asked about, in order. */
    private final List<String> asked = new ArrayList<>();
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
        new TierRuntime(
            new DefaultSourceTierPlanner(registry, new ModelPoolKeyResolver(modelCatalog), modelCatalog, accountPool,
                payments, pricing, bindings),
            bindings)
    );

    @BeforeEach
    void standInForTheCollaborators() {
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatchedTiered(any(), anyString(), anyString()))
            .thenReturn(Mono.just(new ApiKeyService.DispatchLease(Duration.ofSeconds(960), Duration.ofMinutes(59))));
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.empty());
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenAnswer(invocation -> Mono.just(policy));
        when(ownerStore.remember(anyString(), anyString())).thenReturn(Mono.empty());
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            // What the cache holds now: not the accounts it only gets once it has been reloaded.
            return cached.stream().filter(account -> !hiddenUntilReload.contains(account.getAccountId())).filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenAnswer(invocation ->
            !unavailable.contains(invocation.<ProxyAccountEntity>getArgument(0).getAccountId()));
        when(accountPool.recordSuccess(anyString(), anyLong(), any())).thenReturn(Mono.empty());
        when(accountPool.recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class), any(), anyString(),
            any(AccountPoolRequestContext.class))).thenReturn(Mono.empty());
        when(accountPool.cachedAccount(anyString())).thenAnswer(invocation ->
            cached.stream().filter(account -> account.getAccountId().equals(invocation.getArgument(0))).findFirst());
        when(accountPool.selectInScope(any(), any(), anyString(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(1);
            Optional<ProxyAccountEntity> found = offered().stream()
                .filter(account -> !hiddenUntilReload.contains(account.getAccountId()))
                .filter(filter)
                .findFirst();
            return found.<Mono<ProxyAccountEntity>>map(Mono::just).orElseGet(() -> Mono.error(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", invocation.getArgument(2))));
        });
        // What selection could still pick from the cache: the accounts it offers and the cache already holds.
        when(accountPool.hasSelectableAccount(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return Mono.just(offered().stream()
                .filter(account -> !hiddenUntilReload.contains(account.getAccountId()))
                .anyMatch(filter));
        });
        when(accountPool.refreshCacheIfStale()).thenReturn(Mono.fromRunnable(hiddenUntilReload::clear));
        when(admissionStore.acquire(any())).thenAnswer(invocation -> {
            AdmissionRequest request = invocation.getArgument(0);
            asked.add(request.accountId());
            String reason = refused.get(request.accountId());
            return Mono.just(reason != null
                ? new AdmissionDecision(false, null, reason, null, null)
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

    // ---- staying in the tier a session fell to ----

    @Test
    void aSessionIsServedByTheBestTierWhenNothingPushedItDown() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));

        messages("session-1", "hello");
        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("official-1", "official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void aSessionThatFellToTheSecondTierStaysThereUntilItHasBeenIdleForTenMinutes() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");

        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("kiro-1");

        // The better tier is free again, but the session has fallen: it does not go back and forth between tiers.
        refused.clear();
        asked.clear();
        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        assertThat(asked).containsExactly("kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");

        advance(Duration.ofMinutes(9));
        messages("session-1", "still here");
        assertThat(dispatched).endsWith("kiro-1");

        // Ten minutes without a request and the session is a new one.
        advance(Duration.ofMinutes(10).plusSeconds(1));
        messages("session-1", "back again");

        assertThat(dispatched).endsWith("official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void anotherSessionOfTheSameKeyStillStartsFromTheBestTier() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        refused.clear();

        messages("session-2", "hello");

        assertThat(dispatched).containsExactly("kiro-1", "official-1");
    }

    @Test
    void aTierThatRanOutOfAccountsByFailingPushesTheSessionDown() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));

        messages("session-1", "hello");
        answers.clear();
        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("official-1", "kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
    }

    @Test
    void aSessionIsHeldToItsTierPerModel() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        refused.clear();

        // Another model of the same session has its own binding: it starts from the best tier, and being served there
        // does not move the first model's session.
        messages("claude-opus-4.5", "session-1", "hello");
        assertThat(dispatched).containsExactly("kiro-1", "official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");

        messages("session-1", "hello again");
        assertThat(dispatched).containsExactly("kiro-1", "official-1", "kiro-1");
    }

    // ---- what does not push a session down ----

    @Test
    void aTierWithNothingAvailableWhenThePlanWasMadePushesTheSessionDown() {
        // The official key is out of quota when the session begins: the tier is found without an account like a tier that
        // is busy or cooling down is, so the session that Kiro serves in its place stays there.
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        unavailable.add("official-1");

        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("kiro-1");

        // The quota window resets in the middle of the conversation; the session does not move between kinds of account.
        unavailable.clear();
        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");

        advance(Duration.ofMinutes(10).plusSeconds(1));
        messages("session-1", "back again");
        assertThat(dispatched).endsWith("official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void aTierWithNoAccountOfTheRouteAtAllIsNotOneTheSessionWasPushedOutOf() {
        // There is no official key at all when the session begins (the same as a feature only the lower tier can carry):
        // the tier could not have served this request, so it is not one the session was pushed out of.
        loaded(anthropic(true, "1", "1"));
        add(kiro("kiro-1"));

        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("kiro-1");

        add(officialKey("official-1"));
        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("kiro-1", "official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void aSessionPushedFurtherDownAfterTheTiersWereReorderedIsFollowedToTheNewSecondTier() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("kiro-1");

        // The administrator puts Kiro first and the official keys second. The session starts from Kiro, which is full, and
        // is pushed down to the tier it was in the first place above: the second one, as it is counted now.
        loaded(anthropicReordered());
        refused.clear();
        refused.put("kiro-1", "RPM_LIMIT");
        messages("session-1", "hello again");
        assertThat(dispatched).containsExactly("kiro-1", "official-1");

        refused.clear();
        messages("session-1", "the next turn");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
        assertThat(dispatched).endsWith("official-1");
    }

    @Test
    void theOwnerOfAResponseChainServedBelowTheBestTierPushesNobodyDown() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        add(officialOpenAi("openai-1"), codex("codex-1"));
        // The chain was started on an account of another node: only its owner can continue it (a chain that can be
        // replayed anywhere is a preference, and an automatic request does not start from a tier below the best one for it).
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-1"));

        // The owner serves from its own tier without the tiers above it being looked at.
        responses("gpt-5.4", "resp_elsewhere", "session-1", "continue");
        assertThat(dispatched).containsExactly("codex-1");

        responses("gpt-5.4", null, "session-1", "something else");

        assertThat(dispatched).containsExactly("codex-1", "openai-1");
        assertThat(startedFrom()).isEqualTo("openai:official");
    }

    @Test
    void theOwnerOfAChainLeavingItsTierOnTheBudgetWithAccountsLeftDoesNotPushTheSessionDown() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 1, 3, 2, 2);
        add(officialOpenAi("openai-1"), officialOpenAi("openai-2"), codex("codex-1"));
        String first = responseId(responses("gpt-5.4", null, "session-1", "start the task"));
        assertThat(dispatched).containsExactly("openai-1");
        fails("openai-1", new UpstreamException(429, "slow down", true));

        // The owner sits in the tier the walk starts from and fails with the budget of one spent. The slot is for crossing:
        // the other account of the tier is not tried, and the tier was left because of the budget, not used up.
        responses("gpt-5.4", first, "session-1", "second turn");
        assertThat(dispatched).containsExactly("openai-1", "openai-1", "codex-1");

        answers.clear();
        responses("gpt-5.4", null, "session-1", "another task");

        assertThat(startedFrom()).isEqualTo("openai:official");
        assertThat(dispatched).endsWith("openai-1");
    }

    @Test
    void theOwnerOfAChainBeingTheLastAccountOfItsTierMakesLeavingItAPushDown() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 1, 3, 2, 2);
        add(officialOpenAi("openai-1"), codex("codex-1"));
        String first = responseId(responses("gpt-5.4", null, "session-1", "start the task"));
        fails("openai-1", new UpstreamException(429, "slow down", true));

        responses("gpt-5.4", first, "session-1", "second turn");
        assertThat(dispatched).containsExactly("openai-1", "openai-1", "codex-1");

        // The tier had nothing more to offer, so the session stays in the tier that served it until it has been idle.
        answers.clear();
        responses("gpt-5.4", null, "session-1", "another task");

        assertThat(startedFrom()).isEqualTo("openai:codex");
        assertThat(dispatched).endsWith("codex-1");
    }

    @Test
    void aTierThatOnlyHadAccountsAfterTheCacheWasReloadedIsNotOneTheSessionWasPushedOutOf() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), officialKey("official-2"), officialKey("official-3"), kiro("kiro-1"));
        hiddenUntilReload.addAll(Set.of("official-1", "official-2", "official-3", "kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));

        // The first round found nothing anywhere, the reload brought the accounts, two of the first tier failed, and the
        // budget sent the request to the second tier: the first tier still had an account and was not found empty at any
        // point that mattered.
        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1");

        answers.clear();
        messages("session-1", "hello again");

        assertThat(dispatched).endsWith("official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void aTierThatHadNoAccountWhenTheRequestWasPlannedIsNotOneTheSessionWasPushedOutOf() {
        loaded(anthropic(true, "1", "1"));
        add(kiro("kiro-1"));
        // The cache holds nothing when the request is planned, so no tier has an account and both stay in the plan; the
        // reload brings only a Kiro account. The first tier never had one to push the session out of.
        hiddenUntilReload.add("kiro-1");

        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("kiro-1");

        add(officialKey("official-1"));
        messages("session-1", "hello again");

        // The session was not bound to the second tier: it starts from the best one and is served by it.
        assertThat(dispatched).containsExactly("kiro-1", "official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void theSessionsRequestsInFlightAreCountedOnlyWhileTheyRun() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        messages("session-1", "the first request, which is over");
        refused.put("official-1", "CONCURRENCY_LIMIT");

        messages("session-1", "the second");
        messages("session-1", "the third");

        // The account is at its limit for someone else: nothing of this session is running on it any more.
        assertThat(dispatched).containsExactly("official-1", "kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
    }

    @Test
    void aRetryOfARequestIsNotRefusedForTheSessionsOwnEarlierAttempt() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        refused.put("official-2", "CONCURRENCY_LIMIT");

        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("official-1", "kiro-1");

        // The failed attempt is over by the time the retry asks the second account, so its limit is somebody else's load.
        answers.clear();
        refused.clear();
        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("official-1", "kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
    }

    @Test
    void aRefusalCausedByTheSessionsOwnParallelRequestDoesNotPushItDown() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        Sinks.One<PlatformCompletionResult> slow = Sinks.one();
        answers.put("official-1", slow::asMono);
        CompletableFuture<ResponseEntity<?>> first = messagesLater("session-1", "a long running turn");
        assertThat(first).isNotDone();

        // A sub-agent of the same session: the account is at its limit of concurrent requests because of the first one.
        refused.put("official-1", "CONCURRENCY_LIMIT");
        answers.remove("official-1");
        messages("session-1", "a sub-agent");
        assertThat(dispatched).containsExactly("official-1", "kiro-1");

        refused.clear();
        slow.tryEmitValue(ANSWER);
        assertThat(first).isCompleted();
        messages("session-1", "the next turn");

        // The sub-agent was served by the lower tier but did not take the session with it.
        assertThat(dispatched).containsExactly("official-1", "kiro-1", "official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void aRefusalCausedByTheSessionsOwnRequestThatNamedItsTierDoesNotPushItDownEither() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        Sinks.One<PlatformCompletionResult> slow = Sinks.one();
        answers.put("official-1", slow::asMono);
        // The main model of the session is pinned to the official keys (/model claude-sonnet-4.5@official).
        CompletableFuture<ResponseEntity<?>> pinned = messagesLater("claude-sonnet-4.5@official", "session-1", "a long running turn");
        assertThat(pinned).isNotDone();

        // The small model of the same session stays automatic; the account is at its limit because of the pinned request.
        refused.put("official-1", "CONCURRENCY_LIMIT");
        answers.remove("official-1");
        messages("session-1", "a small request");
        assertThat(dispatched).containsExactly("official-1", "kiro-1");

        refused.clear();
        slow.tryEmitValue(ANSWER);
        assertThat(pinned).isCompleted();
        messages("session-1", "the next turn");

        // The small request was served by the lower tier but was not pushed there by anybody else: the session stays put.
        assertThat(dispatched).containsExactly("official-1", "kiro-1", "official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void aRequestThatNamedItsTierNeitherReadsNorWritesTheTierOfTheSession() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "pushed down");
        refused.clear();
        assertThat(dispatched).containsExactly("kiro-1");

        messages("claude-sonnet-4.5@official", "session-1", "pinned to the better tier");
        messages("session-1", "back to automatic");

        // The pinned request was served by the better tier and neither lifted the session there nor started from Kiro.
        assertThat(dispatched).containsExactly("kiro-1", "official-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
    }

    @Test
    void aRefusalForAnotherSessionsLoadDoesPushTheSessionDownEvenWithTheSameLimit() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "CONCURRENCY_LIMIT");

        messages("session-1", "hello");
        refused.clear();
        messages("session-1", "hello again");

        // Nothing of this session was running on the account: someone else is using it.
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
    }

    @Test
    void aRefusalOfAKindTheSessionCannotCauseDoesPushItDownWhileItHasARequestInFlight() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        Sinks.One<PlatformCompletionResult> slow = Sinks.one();
        answers.put("official-1", slow::asMono);
        CompletableFuture<ResponseEntity<?>> first = messagesLater("session-1", "a long running turn");
        refused.put("official-1", "RPM_LIMIT");
        answers.remove("official-1");

        messages("session-1", "a sub-agent");
        messages("session-1", "the next turn");

        // A rate limit is the account's, not the session's own doing, so it counts however the session got there.
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        assertThat(dispatched).containsExactly("official-1", "kiro-1", "kiro-1");
        slow.tryEmitValue(ANSWER);
        assertThat(first).isCompleted();
    }

    @Test
    void aRequestOfTheBetterTierThatFinishesLateDoesNotMoveTheSessionBack() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        Sinks.One<PlatformCompletionResult> slow = Sinks.one();
        answers.put("official-1", slow::asMono);
        CompletableFuture<ResponseEntity<?>> first = messagesLater("session-1", "a long running turn");
        refused.put("official-1", "RPM_LIMIT");
        answers.remove("official-1");
        messages("session-1", "a second request");
        assertThat(dispatched).containsExactly("official-1", "kiro-1");

        // The first request was started before the session fell and succeeds after it.
        slow.tryEmitValue(ANSWER);
        assertThat(first).isCompleted();
        refused.clear();
        messages("session-1", "the next turn");

        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        assertThat(dispatched).containsExactly("official-1", "kiro-1", "kiro-1");
    }

    @Test
    void leavingTheBestTierOnTheReservedSlotWithAccountsLeftDoesNotPushTheSessionDown() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), officialKey("official-2"), officialKey("official-3"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));

        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1");

        // The better tier still had an account; only the budget ran out.
        answers.clear();
        messages("session-1", "hello again");

        assertThat(dispatched).endsWith("official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void leavingTheBestTierOnTheReservedSlotWhenItHadNothingLeftPushesTheSessionDown() {
        // The owner's example of the slot: both sources of the first tier fail definitively with a budget of two.
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), officialKey("official-2"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));

        messages("session-1", "hello");
        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1");

        // Both of the tier's accounts were tried, so it was used up and not left because of the budget. The failures'
        // cooldown of the real pool would have shown that on the next request; the session does not wait for it.
        answers.clear();
        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
    }

    @Test
    void aTierWhoseOtherAccountIsOutOfQuotaHasNothingLeftWhenTheSlotIsUsed() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), officialKey("official-2"), officialKey("official-3"), kiro("kiro-1"));
        unavailable.add("official-3");
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));

        messages("session-1", "hello");
        answers.clear();
        messages("session-1", "hello again");

        // The third key could not have been tried (it has no quota), so the budget did not leave anything behind.
        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
    }

    @Test
    void aTierWhoseOtherAccountIsCoolingDownHasNothingLeftWhenTheSlotIsUsed() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), officialKey("official-2"), officialKey("official-3"), kiro("kiro-1"));
        // The third key is neither suspended nor out of quota, so the plan counts it, but its lifecycle keeps selection
        // from picking it: it is as unavailable as an account without quota.
        cooling.add("official-3");
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));

        messages("session-1", "hello");
        answers.clear();
        messages("session-1", "hello again");

        // The first tier was used up, not left because of the budget: the session fell and stays in the second tier.
        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
    }

    @Test
    void aTierWhoseOtherAccountIsAvailableStillHasSomethingLeftWhenTheSlotIsUsed() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), officialKey("official-2"), officialKey("official-3"), kiro("kiro-1"));
        fails("official-1", new UpstreamException(429, "slow down", true));
        fails("official-2", new UpstreamException(429, "slow down", true));

        messages("session-1", "hello");
        answers.clear();
        messages("session-1", "hello again");

        // The third key could have been tried: the slot crossed because of the budget, and nobody was pushed down.
        assertThat(dispatched).containsExactly("official-1", "official-2", "kiro-1", "official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    // ---- the way back up ----

    @Test
    void aRequestThatFailedInTheLowerTierClimbsBackAsALastResortAndTakesTheSessionWithIt() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        refused.clear();
        fails("kiro-1", new UpstreamException(429, "slow down", true));

        messages("session-1", "the lower tier fails");

        // Nothing from the session's tier down could serve it, so the one tier above that is still covered did.
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "official-1");
        answers.clear();
        messages("session-1", "the next turn");
        assertThat(dispatched).endsWith("official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void aClimbCausedByTheSessionsOwnParallelRequestServesItButDoesNotMoveTheSession() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        refused.clear();
        Sinks.One<PlatformCompletionResult> slow = Sinks.one();
        answers.put("kiro-1", slow::asMono);
        CompletableFuture<ResponseEntity<?>> first = messagesLater("session-1", "a long running turn");
        assertThat(first).isNotDone();

        // A sub-agent of the session: the account of the session's tier is at its limit because of the first request, so
        // nothing from the session's tier down can take it and it is served from the tier above, as a last resort.
        refused.put("kiro-1", "CONCURRENCY_LIMIT");
        answers.remove("kiro-1");
        messages("session-1", "a sub-agent");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "official-1");

        refused.clear();
        slow.tryEmitValue(ANSWER);
        assertThat(first).isCompleted();
        messages("session-1", "the next turn");

        // The sub-agent was served by the tier above but did not take the session with it.
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        assertThat(dispatched).endsWith("kiro-1");
    }

    @Test
    void aRequestThatWasRestrictedToItsTierDoesNotClimbBack() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        refused.clear();
        asked.clear();
        // A timeout may have started a generation: the request stays with the tier it was in.
        fails("kiro-1", new TimeoutException("no answer"));

        assertThatThrownBy(() -> messages("session-1", "the lower tier times out"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        assertThat(asked).containsExactly("kiro-1");
    }

    @Test
    void aTierThatWouldReserveMoreThanWasReservedIsNotClimbedTo() {
        loaded(anthropic(true, "1", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        refused.clear();
        fails("kiro-1", new UpstreamException(429, "slow down", true));

        assertThatThrownBy(() -> messages("session-1", "the lower tier fails"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        // The session reserved for the cheaper tier alone, and the dearer one above is not part of that reservation.
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        assertThat(holdLevels()).containsExactly("anthropic:kiro");
        assertThat(reservations().getLast()).isLessThan(reservations().getFirst());
    }

    // ---- sessions that give no name ----

    @Test
    void withoutASessionHeaderTheBeginningOfTheConversationKeepsTheSessionInItsTier() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages(null, "Fix the build");
        refused.clear();

        messages(null, "Fix the build");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
        assertThat(startedFrom()).isEqualTo("anthropic:kiro");

        // A conversation that begins differently is another session.
        messages(null, "Write the release notes");
        assertThat(dispatched).containsExactly("kiro-1", "kiro-1", "official-1");
        assertThat(startedFrom()).isEqualTo("anthropic:official");
    }

    @Test
    void aResponsesChainWithoutASessionHeaderStaysInTheTierItFellToForEveryTurn() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        add(officialOpenAi("openai-1"), codex("codex-1"));
        refused.put("openai-1", "RPM_LIMIT");

        String first = responseId(responses("gpt-5.4", null, null, "start the task"));
        assertThat(dispatched).containsExactly("codex-1");

        refused.clear();
        String second = responseId(responses("gpt-5.4", first, null, "second turn"));
        assertThat(startedFrom()).isEqualTo("openai:codex");

        String third = responseId(responses("gpt-5.4", second, null, "third turn"));
        assertThat(startedFrom()).isEqualTo("openai:codex");
        assertThat(third).isNotEqualTo(second);
        assertThat(dispatched).containsExactly("codex-1", "codex-1", "codex-1");
    }

    @Test
    void aTurnOfAResponsesChainThatNamedItsTierPassesTheTierOfTheChainOn() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        add(officialOpenAi("openai-1"), codex("codex-1"));
        refused.put("openai-1", "RPM_LIMIT");
        String first = responseId(responses("gpt-5.4", null, null, "start the task"));
        assertThat(startedFrom()).isEqualTo("openai:official");
        refused.clear();

        // The second turn names the tiers it accepts and starts from the official one, which is free again: the owner of
        // the chain, in Codex, is not what it is tried against. It neither reads nor moves the tier the chain fell to ...
        String second = responseId(responses("gpt-5.4@official+", first, null, "second turn"));
        assertThat(startedFrom()).isEqualTo("openai:official");
        // ... but the chain goes on from there: the third turn follows the response of the second, whose owner is the
        // official key, outside the Codex tier the session is bound to, so it is Codex that serves it.
        responses("gpt-5.4", second, null, "third turn");

        assertThat(startedFrom()).isEqualTo("openai:codex");
        assertThat(dispatched).containsExactly("codex-1", "openai-1", "codex-1");
    }

    @Test
    void aResponsesChainThatWasNeverPushedDownStartsFromTheBestTierEveryTurn() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        add(officialOpenAi("openai-1"), codex("codex-1"));

        String first = responseId(responses("gpt-5.4", null, null, "start the task"));
        responses("gpt-5.4", first, null, "second turn");

        assertThat(dispatched).containsExactly("openai-1", "openai-1");
        assertThat(startedFrom()).isEqualTo("openai:official");
    }

    // ---- the session header of the Codex CLI ----

    @Test
    void aCodexSessionIsKnownByItsSessionHeaderWhateverEachTurnBeginsWith() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        add(officialOpenAi("openai-1"), codex("codex-1"));
        refused.put("openai-1", "RPM_LIMIT");

        responsesFromCodex("01a172ab-ca52", "start the task");
        assertThat(dispatched).containsExactly("codex-1");

        // The better tier is free again. The second turn does not begin like the first, so only the header tells the
        // session; it stays where it fell to.
        refused.clear();
        responsesFromCodex("01a172ab-ca52", "something the first turn did not say");

        assertThat(dispatched).containsExactly("codex-1", "codex-1");
        assertThat(startedFrom()).isEqualTo("openai:codex");

        // Another Codex session of the same key starts from the best tier.
        responsesFromCodex("01a172ac-3a25", "start the task");
        assertThat(dispatched).containsExactly("codex-1", "codex-1", "openai-1");
        assertThat(startedFrom()).isEqualTo("openai:official");
    }

    @Test
    void withoutTheSessionHeaderTheSameTurnsAreTwoSessions() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        add(officialOpenAi("openai-1"), codex("codex-1"));
        refused.put("openai-1", "RPM_LIMIT");

        responses("gpt-5.4", null, null, "start the task");
        refused.clear();
        responses("gpt-5.4", null, null, "something the first turn did not say");

        assertThat(dispatched).containsExactly("codex-1", "openai-1");
    }

    @Test
    void theSessionHintOfTheClientIsUsedBeforeTheCodexSessionHeader() {
        loaded(openAi(true));
        policy = new RoutingPolicySettings(false, false, false, false, 1, 3, 3, 2, 2);
        add(officialOpenAi("openai-1"), codex("codex-1"));
        refused.put("openai-1", "RPM_LIMIT");
        MockServerWebExchange first = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/responses")
            .header("x-session-affinity", "session-1").header(TierSessionKeys.CODEX_SESSION_HEADER, "codex-session-a"));
        facade.responses(objectMapper.createObjectNode().put("model", "gpt-5.4").put("input", "start the task"), first).block();
        refused.clear();

        // Another Codex id, the same hint: the hint names the session.
        MockServerWebExchange second = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/responses")
            .header("x-session-affinity", "session-1").header(TierSessionKeys.CODEX_SESSION_HEADER, "codex-session-b"));
        facade.responses(objectMapper.createObjectNode().put("model", "gpt-5.4").put("input", "start the task"), second).block();

        assertThat(dispatched).containsExactly("codex-1", "codex-1");
    }

    @Test
    void theSessionHeaderNamesASessionOfAnotherEntryToo() {
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");

        facade.messages(messagesBody("claude-sonnet-4.5", "Fix the build"),
            exchange("/v1/messages", TierSessionKeys.CODEX_SESSION_HEADER, "01a172ab-ca52")).block();
        refused.clear();
        facade.messages(messagesBody("claude-sonnet-4.5", "Write the release notes"),
            exchange("/v1/messages", TierSessionKeys.CODEX_SESSION_HEADER, "01a172ab-ca52")).block();

        assertThat(dispatched).containsExactly("kiro-1", "kiro-1");
    }

    // ---- when sessions are not kept ----

    @Test
    void aVendorThatDoesNotKeepSessionsInTheirTierStartsEveryRequestFromTheBestOne() {
        loaded(anthropic(false, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        refused.clear();

        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("kiro-1", "official-1");
    }

    @Test
    void withSessionAffinityOffEveryRequestStartsFromTheBestTier() {
        properties.setSessionAffinityEnabled(false);
        loaded(anthropic(true, "1", "1"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        refused.clear();

        messages("session-1", "hello again");

        assertThat(dispatched).containsExactly("kiro-1", "official-1");
    }

    // ---- what the reservation covers ----

    @Test
    void aSessionInTheCheaperTierReservesForItAndTheTiersBelowOnly() {
        loaded(anthropic(true, "1", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));
        refused.put("official-1", "RPM_LIMIT");
        messages("session-1", "hello");
        assertThat(holdLevels()).containsExactly("anthropic:official", "anthropic:kiro");

        refused.clear();
        messages("session-1", "hello again");

        assertThat(startedFrom()).isEqualTo("anthropic:kiro");
        assertThat(holdLevels()).containsExactly("anthropic:kiro");
        assertThat(reservations().getLast()).isLessThan(reservations().getFirst());
    }

    // ---- fixtures ----

    private void advance(Duration time) {
        clock.addAndGet(time.toNanos());
    }

    private ResponseEntity<?> messages(String session, String text) {
        return messages("claude-sonnet-4.5", session, text);
    }

    private ResponseEntity<?> messages(String model, String session, String text) {
        return facade.messages(messagesBody(model, text), exchange("/v1/messages", session == null ? null : SESSION_HEADER, session))
            .block();
    }

    private CompletableFuture<ResponseEntity<?>> messagesLater(String session, String text) {
        return messagesLater("claude-sonnet-4.5", session, text);
    }

    private CompletableFuture<ResponseEntity<?>> messagesLater(String model, String session, String text) {
        return facade.messages(messagesBody(model, text), exchange("/v1/messages", SESSION_HEADER, session))
            .toFuture();
    }

    private ResponseEntity<?> responses(String model, String previousResponseId, String session, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("input", text);
        if (previousResponseId != null) {
            body.put("previous_response_id", previousResponseId);
        }
        return facade.responses(body, exchange("/v1/responses", session == null ? null : "x-session-affinity", session)).block();
    }

    private ResponseEntity<?> responsesFromCodex(String sessionId, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "gpt-5.4").put("input", text).put("store", false);
        return facade.responses(body, exchange("/v1/responses", TierSessionKeys.CODEX_SESSION_HEADER, sessionId)).block();
    }

    private static String responseId(ResponseEntity<?> response) {
        return ((JsonNode) response.getBody()).path("id").asString();
    }

    private ObjectNode messagesBody(String model, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", text);
        return body;
    }

    private static MockServerWebExchange exchange(String path, String header, String value) {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post(path);
        if (header != null) {
            request.header(header, value);
        }
        return MockServerWebExchange.from(request);
    }

    /** The tier the latest reservation says the request started from. */
    private String startedFrom() {
        return lastPlan().path("startTierRef").asString();
    }

    private List<String> holdLevels() {
        List<String> hold = new ArrayList<>();
        lastPlan().path("holdLevels").forEach(level -> hold.add(level.asString()));
        return hold;
    }

    private JsonNode lastPlan() {
        ArgumentCaptor<String> pricingJson = ArgumentCaptor.forClass(String.class);
        verify(apiKeyService, atLeastOnce()).reserveUsage(any(), anyString(), any(), pricingJson.capture());
        return objectMapper.readTree(pricingJson.getAllValues().getLast()).path("tierPlan");
    }

    private List<BigDecimal> reservations() {
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(apiKeyService, atLeastOnce()).reserveUsage(any(), anyString(), credits.capture(), anyString());
        return credits.getAllValues();
    }

    private List<ProxyAccountEntity> offered() {
        return cached.stream()
            .filter(account -> !unavailable.contains(account.getAccountId()) && !cooling.contains(account.getAccountId()))
            .toList();
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

    /** Official keys first and Kiro second, with the factor of each tier. */
    private static SourceTierSnapshot anthropic(boolean sticky, String officialFactor, String kiroFactor) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, sticky, 16, true, 5L)),
            List.of(
                tier("anthropic", "official", 1, officialFactor, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("anthropic", "kiro", 2, kiroFactor, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of());
    }

    /** The tiers of {@link #anthropic} the other way round: Kiro is the first tier and the official keys the second. */
    private static SourceTierSnapshot anthropicReordered() {
        return SourceTierSnapshot.of(6L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 6L)),
            List.of(
                tier("anthropic", "kiro", 1, "1", AccountAllocationCategory.KIRO),
                tier("anthropic", "official", 2, "1", AccountAllocationCategory.OFFICIAL_API_KEY)),
            List.of(), List.of(), List.of());
    }

    private static SourceTierSnapshot openAi(boolean sticky) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, sticky, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, "1", AccountAllocationCategory.CODEX)),
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
