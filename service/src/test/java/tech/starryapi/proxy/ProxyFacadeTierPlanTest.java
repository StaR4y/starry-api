package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
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
import tech.starryapi.proxy.tier.DefaultSourceTierPlanner;
import tech.starryapi.proxy.tier.TierSessionBindings;
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
 * The facade's side of planning: the planner is asked before anything is reserved, a refusal ends the request there,
 * and a vendor without tiers is handled by exactly the code it had before the planner existed.
 */
class ProxyFacadeTierPlanTest {

    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultModelCatalogProvider modelCatalog = new DefaultModelCatalogProvider();
    private final ModelPoolKeyResolver keyResolver = new ModelPoolKeyResolver(modelCatalog);
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final AccountSettingsService accountSettings = mock(AccountSettingsService.class);
    private final ModelMappingService modelMappingService = mock(ModelMappingService.class);
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);
    private final KiroUpstreamClient kiroUpstream = mock(KiroUpstreamClient.class);
    private final StatsService statsService = mock(StatsService.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final OpenAiResponseStateStore stateStore = new OpenAiResponseStateStore(objectMapper);
    private final OpenAiResponseOwnerStore ownerStore = mock(OpenAiResponseOwnerStore.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    @BeforeEach
    void stubTheCollaborators() {
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(), anyString(), any(), anyLong(),
            anyLong())).thenReturn(Mono.empty());
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMappingService.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenReturn(Mono.just(AccountDtos.RoutingPolicySettings.defaults()));
        when(payments.config()).thenReturn(Mono.just(paymentConfig(BigDecimal.ONE)));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(accountPool.recordSuccess(anyString(), anyLong(), any())).thenReturn(Mono.empty());
    }

    // ---- fail closed (I5) ----

    @Test
    void chatIsRefusedBeforeAnythingIsReservedWhileTheConfigurationIsNotLoaded() {
        ProxyFacade facade = facade(new TierRuntime(planner(neverLoaded())));

        assertThatThrownBy(() -> facade.chat(chatRequest("claude-sonnet-4.5"), exchange("/v1/chat/completions")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE");
            });

        assertNothingReservedOrSelected();
    }

    @Test
    void messagesAreRefusedBeforeAnythingIsReservedWhileTheConfigurationIsNotLoaded() {
        ProxyFacade facade = facade(new TierRuntime(planner(neverLoaded())));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", "claude-sonnet-4.5").put("max_tokens", 16);
        request.putArray("messages").addObject().put("role", "user").put("content", "hi");

        assertThatThrownBy(() -> facade.messages(request, exchange("/v1/messages")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE"));

        assertNothingReservedOrSelected();
    }

    @Test
    void responsesAreRefusedBeforeAnythingIsReservedWhileTheConfigurationIsNotLoaded() {
        ProxyFacade facade = facade(new TierRuntime(planner(neverLoaded())));

        assertThatThrownBy(() -> facade.responses(responsesRequest("gpt-5.4", null), exchange("/v1/responses")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE"));

        assertNothingReservedOrSelected();
    }

    @Test
    void theAnonymousCallerIsRefusedToo() {
        ProxyFacade facade = facade(new TierRuntime(planner(neverLoaded())));

        assertThatThrownBy(() -> facade.completeAnonymousChat(chatRequest("claude-sonnet-4.5"), exchange("/v1/chat/completions")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("TIER_CONFIG_UNAVAILABLE"));

        assertNothingReservedOrSelected();
    }

    // ---- a response chain no tier can continue ----

    @Test
    void aChainThatCannotBeReplayedAndWhoseOwnerNoTierServesIsRefusedBeforeAnythingIsReserved() {
        loaded(openAiConfiguration());
        // The owner is a Codex token; the Codex pool is isolated by default, so the OpenAI route takes official keys only.
        when(ownerStore.findOwner("resp_elsewhere")).thenReturn(Mono.just("codex-owner"));
        when(accountPool.cachedAccount("codex-owner")).thenReturn(Optional.of(codexAccount()));
        ProxyFacade facade = facade(new TierRuntime(planner(registry)));

        assertThatThrownBy(() -> facade.responses(responsesRequest("gpt-5.4", "resp_elsewhere"), exchange("/v1/responses")).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getCode()).isEqualTo("RESPONSE_OWNER_TIER_MISMATCH");
            });

        assertNothingReservedOrSelected();
    }

    @Test
    void aChainThatCanBeReplayedIsNotRefusedForItsOwner() {
        loaded(openAiConfiguration());
        ObjectNode earlier = objectMapper.createObjectNode().put("id", "resp_known");
        earlier.putArray("output");
        stateStore.remember(earlier, objectMapper.createObjectNode().put("input", "first"), "codex-owner");
        when(accountPool.cachedAccount("codex-owner")).thenReturn(Optional.of(codexAccount()));
        when(accountPool.selectInScope(any(), any(), anyString(), any(), any(), anyBoolean())).thenReturn(Mono.error(
            new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_ACCOUNT", "No available upstream account")));
        when(accountPool.refreshCacheIfStale()).thenReturn(Mono.empty());
        ProxyFacade facade = facade(new TierRuntime(planner(registry)));

        // The owner is not part of the plan, but the history is here, so the request goes on to the tiered path: it is
        // reserved by the plan and looks for an account among the tiers, which the pool has none to offer.
        assertThatThrownBy(() -> facade.responses(responsesRequest("gpt-5.4", "resp_known"), exchange("/v1/responses")).block())
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));

        verify(apiKeyService).reserveUsage(any(), anyString(), any(), anyString());
        verify(apiKeyService, never()).markUsageDispatchedTiered(any(), anyString(), anyString());
    }

    // ---- no tiers in use ----

    @Test
    void aVendorWithoutTiersIsServedAndReservedExactlyAsWithoutAPlanner() {
        loaded(SourceTierSnapshot.EMPTY);

        Served untiered = serveKiroChat(new TierRuntime(planner(registry)));
        Served baseline = serveKiroChat(TierRuntime.disabled());

        assertThat(untiered.status()).isEqualTo(baseline.status()).isEqualTo(HttpStatus.OK);
        assertThat(untiered.reserved()).isEqualTo(baseline.reserved());
        assertThat(untiered.pricing()).isEqualTo(baseline.pricing());
        assertThat(untiered.pricing()).doesNotContain("tierPlan");
        assertThat(untiered.selections()).isEqualTo(baseline.selections()).containsExactly("selectKiroAccountForPool");
        assertThat(untiered.circuitEpochCaptures()).isEqualTo(baseline.circuitEpochCaptures()).isEqualTo(1);
    }

    @Test
    void aVendorThatIsSwitchedOffIsServedAsWithoutAPlanner() {
        loaded(SourceTierSnapshot.of(3L,
            List.of(new VendorConfig("anthropic", false, false, false, true, 16, true, 3L)),
            anthropicTiers(), List.of(), List.of(), List.of()));

        Served served = serveKiroChat(new TierRuntime(planner(registry)));

        assertThat(served.status()).isEqualTo(HttpStatus.OK);
        assertThat(served.pricing()).doesNotContain("tierPlan");
        assertThat(served.selections()).containsExactly("selectKiroAccountForPool");
    }

    // ---- fixtures ----

    private record Served(HttpStatus status, String reserved, String pricing, List<String> selections,
        long circuitEpochCaptures) {
    }

    private Served serveKiroChat(TierRuntime runtime) {
        AccountPoolService pool = mock(AccountPoolService.class);
        ProxyAccountEntity kiro = new ProxyAccountEntity();
        kiro.setAccountId("kiro-main");
        kiro.setProvider("BuilderId");
        kiro.setAccessToken("kiro-token");
        kiro.setRegion("us-east-1");
        kiro.setEnabled(true);
        when(pool.selectKiroAccountForPool(eq("key-1"), any(AccountPoolRequestContext.class))).thenReturn(Mono.just(kiro));
        when(pool.recordSuccess(anyString(), anyLong(), any())).thenReturn(Mono.empty());
        KiroUpstreamClient upstream = mock(KiroUpstreamClient.class);
        when(upstream.complete(eq(kiro), any(ObjectNode.class), eq("claude-sonnet-4.5")))
            .thenReturn(Mono.just(new PlatformCompletionResult("OK", new PlatformUsage(4, 6, BigDecimal.ZERO, 0, 0, 0))));
        ApiKeyService keys = mock(ApiKeyService.class);
        when(keys.authenticate(any())).thenReturn(Mono.just(principal));
        when(keys.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(keys.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(keys.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenReturn(Mono.empty());
        when(keys.accountUsage(any(), anyString(), any(), anyLong(),
            anyLong())).thenReturn(Mono.empty());

        ResponseEntity<?> response = facade(keys, pool, upstream, runtime)
            .chat(chatRequest("claude-sonnet-4.5"), exchange("/v1/chat/completions")).block();

        ArgumentCaptor<BigDecimal> estimate = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> pricingJson = ArgumentCaptor.forClass(String.class);
        verify(keys).reserveUsage(any(), anyString(), estimate.capture(), pricingJson.capture());
        List<String> selections = mockingDetails(pool).getInvocations().stream()
            .map(invocation -> invocation.getMethod().getName())
            .filter(name -> name.startsWith("select"))
            .toList();
        long epochCaptures = mockingDetails(pool).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("captureCircuitEpoch"))
            .count();
        return new Served(HttpStatus.valueOf(response.getStatusCode().value()), estimate.getValue().toPlainString(),
            pricingJson.getValue(), selections, epochCaptures);
    }

    private void assertNothingReservedOrSelected() {
        verify(apiKeyService, never()).reserveUsage(any(), anyString(), any(), anyString());
        verify(apiKeyService, never()).markUsageDispatched(any(), anyString());
        assertThat(mockingDetails(accountPool).getInvocations())
            .extracting(invocation -> invocation.getMethod().getName())
            .noneMatch(name -> name.startsWith("select"));
    }

    private void loaded(SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
    }

    private SourceTierRegistry neverLoaded() {
        return new SourceTierRegistry(
            mock(DatabaseClient.class),
            mock(ReactiveTransactionManager.class),
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction()
        );
    }

    private DefaultSourceTierPlanner planner(SourceTierRegistry source) {
        return new DefaultSourceTierPlanner(source, keyResolver, modelCatalog, accountPool, payments, pricing, TierSessionBindings.disabled());
    }

    private ProxyFacade facade(TierRuntime runtime) {
        return facade(apiKeyService, accountPool, kiroUpstream, runtime);
    }

    private ProxyFacade facade(ApiKeyService keys, AccountPoolService pool, KiroUpstreamClient kiro, TierRuntime runtime) {
        return new ProxyFacade(
            keys,
            pool,
            mock(AccountAutoSuspendService.class),
            accountSettings,
            mock(DisabledAccountScheduledTestService.class),
            modelCatalog,
            modelMappingService,
            rateLimiter,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            kiro,
            mock(OpenAiUpstreamClient.class),
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            new OpenAiResponseFactory(objectMapper),
            stateStore,
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper),
            mock(AnthropicUpstreamClient.class),
            new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper),
            payments,
            pricing,
            statsService,
            AccountAdmissionStore.permissive(),
            ownerStore,
            runtime
        );
    }

    private SourceTierSnapshot anthropicConfiguration() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            anthropicTiers(), List.of(), List.of(), List.of());
    }

    private List<Tier> anthropicTiers() {
        return List.of(
            tier("anthropic", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
            tier("anthropic", "kiro", 2, "0.6", AccountAllocationCategory.KIRO, AccountAllocationCategory.CLAUDE_OAUTH)
        );
    }

    private SourceTierSnapshot openAiConfiguration() {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, true, 5L)),
            List.of(
                tier("openai", "official", 1, "1", AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("openai", "codex", 2, "0.5", AccountAllocationCategory.CODEX)
            ), List.of(), List.of(), List.of());
    }

    private static Tier tier(String vendor, String tierId, int level, String factor, AccountAllocationCategory... categories) {
        Set<AccountAllocationCategory> listed = EnumSet.copyOf(List.of(categories));
        return new Tier(vendor, tierId, level, tierId, new BigDecimal(factor), listed, true, true, null);
    }

    private ProxyAccountEntity codexAccount() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("codex-owner");
        account.setProvider("OpenAI");
        account.setAuthMethod("codex");
        account.setEnabled(true);
        return account;
    }

    private JsonNode chatRequest(String model) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return request;
    }

    private JsonNode responsesRequest(String model, String previousResponseId) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model).put("input", "continue");
        if (previousResponseId != null) {
            request.put("previous_response_id", previousResponseId);
        }
        return request;
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path));
    }

    private static PaymentConfig paymentConfig(BigDecimal apiBillingMultiplier) {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, apiBillingMultiplier, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }
}
