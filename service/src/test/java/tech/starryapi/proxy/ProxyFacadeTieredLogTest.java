package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierPlanRequest;
import tech.starryapi.proxy.tier.TierResponseHeaders;
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
 * What a request served from source tiers leaves in the request log and puts in its response (design 5.7 and 6.4):
 * every attempt writes the tier it went to, and the response names the tier that served it, in a streamed answer as
 * well. Also that a request without tiers writes and sends nothing of the kind.
 */
class ProxyFacadeTieredLogTest {

    private static final String ENDPOINT = "/v1/messages";
    private static final String REQUEST_ID = "request-1";
    private static final PlatformUsage USAGE = new PlatformUsage(8_000, 1_000, BigDecimal.ZERO, 0, 0, 0);
    private static final PlatformCompletionResult ANSWER = new PlatformCompletionResult("OK", USAGE);
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

    // ---- the request log ----

    @Test
    void aRequestServedBySecondTierLogsTheTierAndItsPriceFactor() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));

        serve(anthropicPlan());

        StatsService.RequestLog row = onlyLog();
        assertThat(row.success()).isTrue();
        assertThat(row.accountId()).isEqualTo("kiro-1");
        assertThat(row.credits()).isEqualByComparingTo("0.0234");
        assertThat(row.sourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(row.sourceTierLevel()).isEqualTo(2);
        assertThat(row.sourceTierName()).isEqualTo("kiro");
        assertThat(row.priceFactor()).isEqualByComparingTo("0.6");
        assertThat(row.tierSelection()).isEqualTo("AUTO");
        // A native source has nothing to add: the rest describes a source reached through a conversion.
        assertThat(row.sourceDispatch()).isNull();
        assertThat(row.wireModel()).isNull();
        assertThat(row.usageSource()).isNull();
        assertThat(row.stopReason()).isNull();
        assertThat(row.degradedFeatures()).isNull();
        // What the row says about the model is what it always said.
        assertThat(row.model()).isEqualTo("claude-sonnet-4.5");
        assertThat(row.path()).isEqualTo(ENDPOINT);
    }

    @Test
    void everyAttemptLogsTheTierItWentTo() {
        loaded(anthropicTiers("1", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));
        answers.put("official-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));

        serve(anthropicPlan());

        assertThat(dispatched).containsExactly("official-1", "kiro-1");
        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, times(2)).log(logs.capture());
        StatsService.RequestLog failed = logs.getAllValues().getFirst();
        assertThat(failed.success()).isFalse();
        assertThat(failed.statusCode()).isEqualTo(429);
        assertThat(failed.credits()).isEqualByComparingTo("0");
        assertThat(failed.sourceTierRef()).isEqualTo("anthropic:official");
        assertThat(failed.sourceTierLevel()).isEqualTo(1);
        assertThat(failed.sourceTierName()).isEqualTo("official");
        assertThat(failed.priceFactor()).isEqualByComparingTo("1");
        assertThat(failed.tierSelection()).isEqualTo("AUTO");
        StatsService.RequestLog served = logs.getAllValues().get(1);
        assertThat(served.success()).isTrue();
        assertThat(served.sourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(served.sourceTierLevel()).isEqualTo(2);
        assertThat(served.priceFactor()).isEqualByComparingTo("0.6");
    }

    @Test
    void aSettlementThatWasRefusedStillLogsTheTierThatServed() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));
        when(apiKeyService.accountUsageServed(any(), anyString(), any(), anyLong(), anyLong(), anyString(), anyInt(), any()))
            .thenReturn(Mono.error(new ApiException(HttpStatus.CONFLICT, "BILLING_REVIEW_REQUIRED", "review")));

        assertThatThrownBy(() -> serve(anthropicPlan())).isInstanceOf(ApiException.class);

        StatsService.RequestLog row = onlyLog();
        assertThat(row.success()).isFalse();
        assertThat(row.statusCode()).isEqualTo(409);
        assertThat(row.accountId()).isEqualTo("kiro-1");
        assertThat(row.sourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(row.sourceTierLevel()).isEqualTo(2);
        assertThat(row.priceFactor()).isEqualByComparingTo("0.6");
        assertThat(row.tierSelection()).isEqualTo("AUTO");
    }

    @Test
    void theCallerWithoutAKeyLogsItsTierToo() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));
        ApiKeyPrincipal anonymous = new ApiKeyPrincipal("anonymous", "whale-assistant", "ADMIN", "whale-assistant", null,
            BigDecimal.ZERO, BigDecimal.ZERO, "*");

        facade().serveTieredChat(run(anthropicPlan(), anonymous, chatBody(), false), ProxyFacade.ResponseMode.OPENAI_CHAT).block();

        StatsService.RequestLog row = onlyLog();
        assertThat(row.credits()).isEqualByComparingTo("0");
        assertThat(row.sourceTierRef()).isEqualTo("anthropic:kiro");
        assertThat(row.priceFactor()).isEqualByComparingTo("0.6");
    }

    @Test
    void aRequestOfAVendorWithoutTiersLogsNoSourceColumns() {
        loaded(SourceTierSnapshot.EMPTY);
        when(accountPool.selectKiroAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(kiro("kiro-1")));

        facade().chat(chatBody(), exchange("/v1/chat/completions")).block();

        StatsService.RequestLog row = onlyLog();
        assertThat(row.success()).isTrue();
        assertThat(row.sourceTierRef()).isNull();
        assertThat(row.sourceTierLevel()).isNull();
        assertThat(row.sourceTierName()).isNull();
        assertThat(row.priceFactor()).isNull();
        assertThat(row.tierSelection()).isNull();
        assertThat(row.sourceDispatch()).isNull();
        assertThat(row.wireModel()).isNull();
        assertThat(row.usageSource()).isNull();
        assertThat(row.stopReason()).isNull();
        assertThat(row.degradedFeatures()).isNull();
    }

    @Test
    void aFailedAttemptOfAVendorWithoutTiersLogsNoSourceColumnsEither() {
        loaded(SourceTierSnapshot.EMPTY);
        when(accountPool.selectKiroAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(kiro("kiro-1")));
        answers.put("kiro-1", () -> Mono.error(new UpstreamException(400, "bad request", false)));

        assertThatThrownBy(() -> facade().chat(chatBody(), exchange("/v1/chat/completions")).block())
            .isInstanceOf(ApiException.class);

        StatsService.RequestLog row = onlyLog();
        assertThat(row.success()).isFalse();
        assertThat(row.sourceTierRef()).isNull();
        assertThat(row.priceFactor()).isNull();
        assertThat(row.tierSelection()).isNull();
    }

    // ---- the response headers ----

    @Test
    void aServedResponseNamesTheTierThatServedIt() {
        loaded(anthropicTiers("1", "0.6"));
        add(kiro("kiro-1"));

        ResponseEntity<?> response = facade().messages(messagesRequest(), exchange(ENDPOINT)).block();

        assertThat(response).isNotNull();
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(headers.getFirst(TierResponseHeaders.LEVEL)).isEqualTo("2");
        assertThat(headers.getFirst(TierResponseHeaders.NAME)).isEqualTo("kiro");
        assertThat(headers.getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
        assertThat(headers.getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void theHeadersNameTheTierThatServedNotTheOneThatFailedFirst() {
        loaded(anthropicTiers("1", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));
        answers.put("official-1", () -> Mono.error(new UpstreamException(429, "slow down", true)));

        ResponseEntity<?> response = serve(anthropicPlan());

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("2");
    }

    @Test
    void aStreamedResponseCarriesTheHeadersToo() {
        loaded(anthropicTiers("1", "0.6"));
        add(officialKey("official-1"), kiro("kiro-1"));

        ResponseEntity<?> response = facade()
            .serveTieredChat(run(anthropicPlan(), principal, chatBody(), true), ProxyFacade.ResponseMode.ANTHROPIC_MESSAGES)
            .block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("1");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.STATUS)).isEqualTo("served");
    }

    @Test
    void aResponsesAnswerCarriesTheHeadersAndStillRemembersItsOwner() {
        loaded(openAiTiers("1", "0.5"));
        add(codex("codex-1"));
        MockServerWebExchange codexClient = MockServerWebExchange.from(
            MockServerHttpRequest.post("/v1/responses").header("User-Agent", "codex_cli_rs/0.50"));

        ResponseEntity<?> response = facade().responses(responsesBody(), codexClient).block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("codex");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.LEVEL)).isEqualTo("2");
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.MODE)).isEqualTo("auto");
        assertThat(response.getBody()).isNotNull();
        verify(ownerStore).remember(anyString(), org.mockito.ArgumentMatchers.eq("codex-1"));
    }

    @Test
    void aTierNameThatIsNotAsciiTravelsPercentEncoded() {
        loaded(SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(
                new Tier("anthropic", "official", 1, "官方", BigDecimal.ONE,
                    EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null),
                new Tier("anthropic", "kiro", 2, "反代 kiro", new BigDecimal("0.6"),
                    EnumSet.of(AccountAllocationCategory.KIRO), true, true, null)),
            List.of(), List.of(), List.of()));
        add(kiro("kiro-1"));

        ResponseEntity<?> response = serve(anthropicPlan());

        assertThat(response.getHeaders().getFirst(TierResponseHeaders.NAME)).isEqualTo("%E5%8F%8D%E4%BB%A3%20kiro");
    }

    @Test
    void aResponseOfAVendorWithoutTiersHasNoneOfTheHeaders() {
        loaded(SourceTierSnapshot.EMPTY);
        when(accountPool.selectKiroAccountForPool(anyString(), any(AccountPoolRequestContext.class)))
            .thenReturn(Mono.just(kiro("kiro-1")));

        ResponseEntity<?> response = facade().chat(chatBody(), exchange("/v1/chat/completions")).block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().headerNames()).noneMatch(name -> name.toLowerCase().startsWith("x-starry-"));
    }

    // ---- fixtures ----

    private StatsService.RequestLog onlyLog() {
        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService).log(logs.capture());
        return logs.getValue();
    }

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
            new TierRuntime(planner)
        );
    }

    private ResponseEntity<?> serve(TierPlan plan) {
        return facade().serveTieredChat(run(plan, principal, chatBody(), false), ProxyFacade.ResponseMode.OPENAI_CHAT).block();
    }

    private ProxyFacade.TieredRequest run(TierPlan plan, ApiKeyPrincipal caller, JsonNode body, boolean stream) {
        String model = body.path("model").asString();
        return new ProxyFacade.TieredRequest(plan, caller, REQUEST_ID, ENDPOINT, model, model, "key-1:session-1", stream,
            ProxyFacade.UpstreamRoute.ANTHROPIC, false, false, false, RoutingPolicySettings.defaults(), body, body,
            Instant.now(), true, true);
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
        return planner.plan(new TierPlanRequest(REQUEST_ID, "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, chatBody(), null, false, null, null)).block();
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

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
