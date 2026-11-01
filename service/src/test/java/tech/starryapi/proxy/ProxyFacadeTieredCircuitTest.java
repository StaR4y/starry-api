package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
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
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.proxy.tier.TierResponseHeaders;
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
 * Tiers and the old model-family circuit policy are independent (design 4.12): with the policy switched on and a circuit
 * open, a tiered request neither sees the circuit nor feeds it, and its request log carries no circuit column. The pool
 * and the policy here are the real ones, so the isolation is the one of the running service and not an assumption of a
 * stand-in.
 */
class ProxyFacadeTieredCircuitTest {

    private static final String ENDPOINT = "/v1/messages";
    private static final PlatformCompletionResult ANSWER =
        new PlatformCompletionResult("OK", new PlatformUsage(4, 6, BigDecimal.ZERO, 0, 0, 0));
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
    private final StatsService statsService = mock(StatsService.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    private AccountPoolPolicyService policy;
    private AccountPoolService accountPool;
    private ProxyAccountEntity officialPrimary;
    private ProxyAccountEntity kiroSecondary;
    private AccountPoolRequestContext untiered;
    private ProxyFacade facade;

    @BeforeEach
    void aRealPoolWithTheOldCircuitPolicySwitchedOn() {
        // The policy starts from the settings' default (package-private there), which here is the policy switched off.
        AccountSettingsService policySettings = mock(AccountSettingsService.class, invocation ->
            invocation.getMethod().getName().equals("defaultPoolPolicySettings")
                ? poolPolicy(false)
                : Answers.RETURNS_DEFAULTS.answer(invocation));
        policy = new AccountPoolPolicyService(policySettings, mock(DatabaseClient.class), keyResolver);
        policy.replaceSettings(poolPolicy(true));
        officialPrimary = officialKey("official-primary", "primary");
        kiroSecondary = kiro("kiro-secondary", "secondary");
        // What an untiered request for the same model runs with: the circuit epoch of the policy that is on.
        untiered = new AccountPoolRequestContext("untiered", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            policy.currentCircuitEpoch());
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
        when(accountSettings.routingPolicy()).thenReturn(Mono.just(RoutingPolicySettings.defaults()));
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(admissionStore.acquire(any())).thenReturn(
            Mono.just(new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600))));
        when(admissionStore.markDispatched(any())).thenReturn(Mono.empty());
        when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
        when(admissionStore.releaseUnsent(any())).thenReturn(Mono.empty());
        when(admissionStore.abandon(any(), anyString())).thenReturn(Mono.empty());
        when(anthropicClient.complete(any(), any(), anyString())).thenReturn(Mono.just(ANSWER));
        when(kiroClient.complete(any(), any(), anyString())).thenReturn(Mono.just(ANSWER));
    }

    // ---- the circuit does not reach a tiered request ----

    @Test
    void aTieredRequestIsServedByAnAccountTheOpenCircuitWouldHaveKeptAnUntieredRequestFrom() {
        pool(officialPrimary, kiroSecondary);
        openTheCircuit();
        // The control: the same model without tiers is sent to the secondary accounts only.
        assertThat(policy.applyTierPolicy(List.of(officialPrimary, kiroSecondary), untiered)).containsExactly(kiroSecondary);

        ResponseEntity<?> response = facade.messages(messagesBody(), exchange()).block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
        verify(anthropicClient).complete(any(), any(), anyString());
        verify(kiroClient, never()).complete(any(), any(), anyString());
    }

    @Test
    void aTieredRequestIsNotRefusedForAnOpenCircuitWithNoSecondaryAccountToSendItTo() {
        pool(officialPrimary);
        openTheCircuit();
        // The control: without tiers the pool answers the open circuit with its own error.
        assertThatThrownBy(() -> accountPool.selectInScope("key-1:session", account -> true, "none", untiered, null, true).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("ACCOUNT_POOL_CIRCUIT_OPEN"));

        ResponseEntity<?> response = facade.messages(messagesBody(), exchange()).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("official");
    }

    @Test
    void theLogRowsOfATieredRequestCarryNoCircuitColumnOnSuccessOrFailure() {
        pool(officialPrimary, kiroSecondary);
        openTheCircuit();
        // The control: for the context an untiered request has, the policy is active and names the pool.
        assertThat(accountPool.circuitKey(untiered)).isNotNull();
        assertThat(accountPool.circuitTier(officialPrimary, untiered)).isEqualTo("primary");
        assertThat(accountPool.circuitEpoch(untiered)).isNotNull();
        when(anthropicClient.complete(any(), any(), anyString()))
            .thenReturn(Mono.error(new UpstreamException(503, "overloaded", true)));

        facade.messages(messagesBody(), exchange()).block();

        ArgumentCaptor<StatsService.RequestLog> logs = ArgumentCaptor.forClass(StatsService.RequestLog.class);
        verify(statsService, times(2)).log(logs.capture());
        assertThat(logs.getAllValues()).extracting(StatsService.RequestLog::success).containsExactly(false, true);
        assertThat(logs.getAllValues()).allSatisfy(row -> {
            assertThat(row.circuitKey()).isNull();
            assertThat(row.circuitTier()).isNull();
            assertThat(row.circuitEpoch()).isNull();
            // The source columns are the ones of the tier that took the attempt, which is all the row says about the pool.
            assertThat(row.sourceTierRef()).startsWith("anthropic:");
        });
    }

    @Test
    void theFailuresOfTieredRequestsAreNotCountedAgainstTheCircuit() {
        pool(officialPrimary, kiroSecondary);
        when(anthropicClient.complete(any(), any(), anyString()))
            .thenReturn(Mono.error(new UpstreamException(503, "overloaded", true)));

        // Four requests, each failing on the primary account (a failure that counts against a circuit) and then served by
        // the second tier: enough samples to open the circuit if they were counted.
        for (int i = 0; i < 4; i++) {
            ResponseEntity<?> response = facade.messages(messagesBody(), exchange()).block();
            assertThat(response).isNotNull();
            assertThat(response.getHeaders().getFirst(TierResponseHeaders.TIER)).isEqualTo("kiro");
        }

        // The circuit is still closed: an untiered request for the model is still sent to the primary accounts.
        assertThat(policy.applyTierPolicy(List.of(officialPrimary, kiroSecondary), untiered)).containsExactly(officialPrimary);
        // Each failure was offered to the circuit, with a context that has no epoch, which the policy does not count.
        ArgumentCaptor<AccountPoolRequestContext> contexts = ArgumentCaptor.forClass(AccountPoolRequestContext.class);
        verify(accountPool, times(4)).recordCircuitFailure(contexts.capture(), any(ProxyAccountEntity.class));
        assertThat(contexts.getAllValues()).allSatisfy(context -> assertThat(context.circuitEpoch()).isNull());
    }

    // ---- fixtures ----

    /** Fills the pool and builds the facade over it and the real policy, with two tiers: official keys, then Kiro. */
    private void pool(ProxyAccountEntity... accounts) {
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenReturn(Flux.just(accounts));
        accountPool = spy(new AccountPoolService(repository, null, new ProxyProperties(), policy));
        accountPool.refreshCache().block();
        doReturn(Mono.empty()).when(accountPool).recordSuccess(anyString(), anyLong(), any());
        doReturn(Mono.empty()).when(accountPool).recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class),
            any(), anyString(), any(AccountPoolRequestContext.class));
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("anthropic", "official", 1, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("anthropic", "kiro", 2, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of()));
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
            mock(OpenAiUpstreamClient.class),
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
            mock(OpenAiResponseOwnerStore.class),
            new TierRuntime(new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, accountPool, payments, pricing, TierSessionBindings.disabled()))
        );
    }

    /** Two failures of the primary accounts of the model, which is as many samples as the policy needs to open its circuit. */
    private void openTheCircuit() {
        policy.recordFailure(untiered, officialPrimary);
        policy.recordFailure(untiered, officialPrimary);
    }

    private JsonNode messagesBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return body;
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post(ENDPOINT));
    }

    private static AccountDtos.PoolPolicySettings poolPolicy(boolean enabled) {
        return new AccountDtos.PoolPolicySettings(enabled, 5, 2, 50.0, 50.0, 70.0, 5, 1, true);
    }

    private static Tier tier(String vendor, String tierId, int level, AccountAllocationCategory category) {
        return new Tier(vendor, tierId, level, tierId, BigDecimal.ONE, EnumSet.of(category), true, true, null);
    }

    private static ProxyAccountEntity officialKey(String accountId, String poolTier) {
        return account(accountId, "Anthropic", "api-key", poolTier);
    }

    private static ProxyAccountEntity kiro(String accountId, String poolTier) {
        ProxyAccountEntity account = account(accountId, "BuilderId", "idc", poolTier);
        account.setAccessToken("kiro-token");
        account.setRegion("us-east-1");
        return account;
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod, String poolTier) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setPoolTier(poolTier);
        account.setPoolWeight(100);
        account.setEnabled(true);
        return account;
    }

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
