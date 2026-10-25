package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountDtos;
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
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.apikey.ApiKeyPrincipal;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.common.ApiException;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelCatalogCustomization;
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
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Member;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tech.starryapi.stats.StatsService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An OpenAI-format relay that is registered for Claude requests as an exclusive member (design 7.5) does not take the
 * untiered traffic that compatible keys serve, GLM here, while one registered as not exclusive keeps serving it, and a
 * model it does not know is the request's problem (404: no retry) as it is for any compatible key. The facade runs over
 * the real account pool, with the real planner on a configuration that has tiers for the Anthropic vendor.
 */
class ProxyFacadeExclusiveXpTest {

    private static final PlatformCompletionResult ANSWER = new PlatformCompletionResult("OK",
        new PlatformUsage(1_000, 100, BigDecimal.ZERO, 0, 0, 0));
    private static final BillingQuote QUOTE = new BillingQuote(
        new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
        new BigDecimal("0.00000375"), new BigDecimal("0.000003"));
    /** Sorts before the GLM key, so a pool that takes its accounts in turn starts with it. */
    private static final String RELAY = "a-relay";
    private static final String GLM = "b-glm";

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
    private final OpenAiUpstreamClient openAiClient = mock(OpenAiUpstreamClient.class);
    private final StatsService statsService = mock(StatsService.class);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal("key-1", "test", null, BigDecimal.ZERO);

    private final List<String> dispatched = new ArrayList<>();
    private final Map<String, Supplier<Mono<PlatformCompletionResult>>> answers = new HashMap<>();
    private final List<String> released = new ArrayList<>();
    private final List<BigDecimal> settled = new ArrayList<>();

    private ProxyFacade facade;

    @BeforeEach
    void standInForTheEdges() {
        modelCatalog.applyCustomizations(List.of(new ModelCatalogCustomization("glm5.3", "GLM 5.3", null, "GLM", "GLM 5",
            "glm5.3", "OPENAI_CHAT_COMPLETIONS", "/v1/chat/completions", 128000, "GLM", true, null)));
        when(payments.config()).thenReturn(Mono.just(new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
            30, 3, List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null)));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(QUOTE));
        when(pricing.cost(any(), any(), anyBoolean(), any())).thenReturn(Mono.just(new BigDecimal("0.001000")));
        when(apiKeyService.authenticate(any())).thenReturn(Mono.just(principal));
        when(apiKeyService.reserveUsage(any(), anyString(), any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.markUsageDispatched(any(), anyString())).thenReturn(Mono.empty());
        when(apiKeyService.accountUsage(any(), anyString(), any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            settled.add(invocation.getArgument(2));
            return Mono.empty();
        });
        when(apiKeyService.releaseUsage(any(), anyString(), anyBoolean(), anyString())).thenAnswer(invocation -> {
            released.add(invocation.getArgument(3));
            return Mono.empty();
        });
        when(rateLimiter.check("key-1")).thenReturn(new RateLimiterService.RateLimitResult(true, 0, 0, 0));
        when(modelMapping.apply(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountSettings.routingPolicy()).thenReturn(Mono.just(AccountDtos.RoutingPolicySettings.defaults()));
        when(statsService.log(any())).thenReturn(Mono.empty());
        when(admissionStore.acquire(any())).thenReturn(Mono.just(
            new AdmissionDecision(true, UUID.randomUUID(), null, null, Instant.now().plusSeconds(600))));
        when(admissionStore.markDispatched(any())).thenReturn(Mono.empty());
        when(admissionStore.reconcile(any())).thenReturn(Mono.empty());
        when(admissionStore.releaseUnsent(any())).thenReturn(Mono.empty());
        when(admissionStore.abandon(any(), anyString())).thenReturn(Mono.empty());
        when(openAiClient.complete(any(), any(), anyString())).thenAnswer(invocation -> {
            ProxyAccountEntity account = invocation.getArgument(0);
            dispatched.add(account.getAccountId());
            return answers.getOrDefault(account.getAccountId(), () -> Mono.just(ANSWER)).get();
        });
    }

    // ---- an exclusive relay ----

    @Test
    void anExclusiveRelayDoesNotServeGlm() {
        serve(configuration(true), relay(RELAY), glm(GLM));

        for (int i = 0; i < 4; i++) {
            assertThat(chat("glm5.3").getStatusCode().value()).isEqualTo(200);
        }

        assertThat(dispatched).containsExactly(GLM, GLM, GLM, GLM);
        assertThat(settled).hasSize(4);
        assertThat(released).isEmpty();
    }

    @Test
    void withNoOtherKeyGlmHasNoAccountAndTheRelayIsNotAskedAnything() {
        serve(configuration(true), relay(RELAY));

        assertThatThrownBy(() -> chat("glm5.3")).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getCode()).isEqualTo("NO_AVAILABLE_ACCOUNT"));

        assertThat(dispatched).isEmpty();
        assertThat(settled).isEmpty();
    }

    // ---- a relay that is not exclusive ----

    @Test
    void aRelayThatIsNotExclusiveServesGlmAndAModelItDoesNotKnowIsNotRetried() {
        serve(configuration(false), relay(RELAY), glm(GLM));
        answers.put(RELAY, () -> Mono.error(new UpstreamException(404, "OpenAI upstream error 404: model not found", false)));

        assertThatThrownBy(() -> chat("glm5.3")).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // The 404 is a problem of the request, so the other key is not tried for it and nothing is charged.
        assertThat(dispatched).containsExactly(RELAY);
        assertThat(settled).isEmpty();
        assertThat(released).hasSize(1);
    }

    @Test
    void aRelayThatIsNotExclusiveTakesItsTurnWithTheOtherGlmKey() {
        serve(configuration(false), relay(RELAY), glm(GLM));

        chat("glm5.3");
        chat("glm5.3");

        assertThat(dispatched).containsExactlyInAnyOrder(RELAY, GLM);
    }

    // ---- the retest of the pool ----

    @Test
    void theRetestOfTheCompatiblePoolSkipsAnExclusiveRelay() throws Exception {
        serve(configuration(true), relay(RELAY), glm(GLM));

        Predicate<ProxyAccountEntity> filter = retestFilterOfTheCompatiblePool();

        assertThat(filter.test(relay(RELAY))).isFalse();
        assertThat(filter.test(glm(GLM))).isTrue();
        // The other kinds of account are not in this pool, whoever they are.
        assertThat(filter.test(account("official-1", "OpenAI", "api-key", null))).isFalse();
        assertThat(filter.test(account("kiro-1", "BuilderId", "idc", null))).isFalse();
    }

    @Test
    void theRetestOfTheCompatiblePoolKeepsARelayThatIsNotExclusive() throws Exception {
        serve(configuration(false), relay(RELAY), glm(GLM));

        Predicate<ProxyAccountEntity> filter = retestFilterOfTheCompatiblePool();

        assertThat(filter.test(relay(RELAY))).isTrue();
        assertThat(filter.test(glm(GLM))).isTrue();
    }

    @Test
    void theRetestFilterAsksNothingUntilItTestsAnAccount() throws Exception {
        AccountPoolService pool = mock(AccountPoolService.class);
        ProxyFacade onAMock = facadeOver(pool);

        @SuppressWarnings("unchecked")
        Predicate<ProxyAccountEntity> filter = (Predicate<ProxyAccountEntity>) retestFilterMethod().invoke(onAMock,
            route("OPENAI"), false, false, AccountDtos.RoutingPolicySettings.defaults(), compatibleContext());

        verifyNoInteractions(pool);
        when(pool.isExclusiveRelay(any())).thenReturn(false);
        assertThat(filter.test(glm(GLM))).isTrue();
        verify(pool).isExclusiveRelay(any());
    }

    // ---- fixtures ----

    /** The Anthropic vendor with two tiers and one relay registered for Claude requests. */
    private SourceTierSnapshot configuration(boolean exclusive) {
        return SourceTierSnapshot.of(5L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, true, 5L)),
            List.of(
                new Tier("anthropic", "official", 1, "official", BigDecimal.ONE,
                    EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY), true, true, null),
                new Tier("anthropic", "kiro", 2, "kiro", new BigDecimal("0.6"),
                    EnumSet.of(AccountAllocationCategory.KIRO), true, true, null)),
            List.of(new Member("anthropic", RELAY, "official", "OPENAI_CHAT", exclusive, false, false, false, false, true, "NONE",
                540, true, true)),
            List.of(), List.of());
    }

    private void serve(SourceTierSnapshot snapshot, ProxyAccountEntity... accounts) {
        facade = facadeOver(poolOf(snapshot, accounts), snapshot);
    }

    private AccountPoolService poolOf(SourceTierSnapshot snapshot, ProxyAccountEntity... accounts) {
        when(registry.snapshot()).thenReturn(snapshot);
        when(registry.exclusiveXpAccountIds()).thenAnswer(invocation -> snapshot.exclusiveXpAccountIds());
        ProxyAccountRepository repository = mock(ProxyAccountRepository.class);
        when(repository.findAllByEnabledTrue()).thenAnswer(invocation -> Flux.fromIterable(List.of(accounts)));
        AccountPoolService pool = spy(new AccountPoolService(repository, null, new ProxyProperties(), null,
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(), null, registry));
        pool.refreshCache().block();
        doReturn(Mono.empty()).when(pool).recordSuccess(anyString(), anyLong(), any());
        doReturn(Mono.empty()).when(pool).recordRequestFailure(anyString(), any(AccountPoolService.UpstreamErrorType.class),
            any(), anyString(), any(AccountPoolRequestContext.class));
        return pool;
    }

    private ProxyFacade facadeOver(AccountPoolService pool) {
        return facadeOver(pool, SourceTierSnapshot.EMPTY);
    }

    private ProxyFacade facadeOver(AccountPoolService pool, SourceTierSnapshot snapshot) {
        when(registry.snapshot()).thenReturn(snapshot);
        TierSessionBindings bindings = new TierSessionBindings(new ProxyProperties());
        return new ProxyFacade(
            apiKeyService,
            pool,
            mock(AccountAutoSuspendService.class),
            accountSettings,
            mock(DisabledAccountScheduledTestService.class),
            modelCatalog,
            modelMapping,
            rateLimiter,
            new KiroPayloadFactory(objectMapper, modelCatalog),
            mock(KiroUpstreamClient.class),
            openAiClient,
            new OpenAiRequestAdapter(objectMapper, modelCatalog),
            new OpenAiResponseFactory(objectMapper),
            new OpenAiResponseStateStore(objectMapper),
            new AnthropicRequestAdapter(objectMapper, modelCatalog),
            new AnthropicResponseFactory(objectMapper),
            mock(AnthropicUpstreamClient.class),
            new GeminiRequestAdapter(objectMapper),
            new GeminiResponseFactory(objectMapper),
            payments,
            pricing,
            statsService,
            admissionStore,
            mock(OpenAiResponseOwnerStore.class),
            new TierRuntime(new DefaultSourceTierPlanner(registry, keyResolver, modelCatalog, pool, payments, pricing, bindings),
                bindings)
        );
    }

    private ResponseEntity<?> chat(String model) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model).put("max_tokens", 100);
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return facade.chat(body, MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"))).block();
    }

    @SuppressWarnings("unchecked")
    private Predicate<ProxyAccountEntity> retestFilterOfTheCompatiblePool() throws Exception {
        return (Predicate<ProxyAccountEntity>) retestFilterMethod().invoke(facade, route("OPENAI"), false, false,
            AccountDtos.RoutingPolicySettings.defaults(), compatibleContext());
    }

    private static AccountPoolRequestContext compatibleContext() {
        return new AccountPoolRequestContext("request-1", "openai-compatible/some-model", "openai-compatible/some-model", "OpenAI");
    }

    private static Method retestFilterMethod() {
        Method found = Arrays.stream(ProxyFacade.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("accountFilterFor"))
            .findFirst()
            .orElseThrow();
        found.setAccessible(true);
        return found;
    }

    private static Object route(String name) {
        Class<?> type = Arrays.stream(ProxyFacade.class.getDeclaredClasses())
            .filter(candidate -> candidate.getSimpleName().equals("UpstreamRoute"))
            .findFirst()
            .orElseThrow();
        return Arrays.stream(type.getEnumConstants())
            .filter(constant -> ((Enum<?>) constant).name().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static ProxyAccountEntity relay(String accountId) {
        return account(accountId, "OpenAI-compatible", "api-key", "https://relay.example/v1");
    }

    private static ProxyAccountEntity glm(String accountId) {
        return account(accountId, "GLM", "api-key", "https://open.bigmodel.cn/api/paas/v4");
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod, String baseUrl) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setBaseUrl(baseUrl);
        account.setAccessToken("sk-test");
        account.setEnabled(true);
        return account;
    }
}
