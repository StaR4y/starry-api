package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountAutoSuspendService;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.account.DisabledAccountScheduledTestService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.apikey.ApiKeyService;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.anthropic.AnthropicRequestAdapter;
import tech.starryapi.platform.anthropic.AnthropicResponseFactory;
import tech.starryapi.platform.anthropic.AnthropicUpstreamClient;
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
import tech.starryapi.proxy.tier.TierPlan;
import tech.starryapi.proxy.tier.TierRuntime;
import tech.starryapi.proxy.tier.TierRuntimeConfig;
import tech.starryapi.proxy.tier.TierSessionBindings;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo;
import tech.starryapi.stats.StatsService;

/**
 * ProxyFacade has several constructors, so unit tests that call one by hand do not show which one Spring uses. Two
 * constructors marked for injection, or none, only fail when the application starts.
 */
class ProxyFacadeTierWiringTest {

    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final AccountPoolService accountPoolService = mock(AccountPoolService.class);
    private final AccountAutoSuspendService accountAutoSuspendService = mock(AccountAutoSuspendService.class);
    private final AccountSettingsService accountSettingsService = mock(AccountSettingsService.class);
    private final DisabledAccountScheduledTestService disabledAccountScheduledTestService =
        mock(DisabledAccountScheduledTestService.class);
    private final ModelCatalogProvider modelCatalog = mock(ModelCatalogProvider.class);
    private final ModelMappingService modelMappingService = mock(ModelMappingService.class);
    private final RateLimiterService rateLimiterService = mock(RateLimiterService.class);
    private final KiroPayloadFactory payloadFactory = mock(KiroPayloadFactory.class);
    private final KiroUpstreamClient kiroUpstreamClient = mock(KiroUpstreamClient.class);
    private final OpenAiUpstreamClient openAiUpstreamClient = mock(OpenAiUpstreamClient.class);
    private final OpenAiRequestAdapter openAiRequestAdapter = mock(OpenAiRequestAdapter.class);
    private final OpenAiResponseFactory openAiResponseFactory = mock(OpenAiResponseFactory.class);
    private final OpenAiResponseStateStore openAiResponseStateStore = mock(OpenAiResponseStateStore.class);
    private final AnthropicRequestAdapter anthropicRequestAdapter = mock(AnthropicRequestAdapter.class);
    private final AnthropicResponseFactory anthropicResponseFactory = mock(AnthropicResponseFactory.class);
    private final AnthropicUpstreamClient anthropicUpstreamClient = mock(AnthropicUpstreamClient.class);
    private final GeminiRequestAdapter geminiRequestAdapter = mock(GeminiRequestAdapter.class);
    private final GeminiResponseFactory geminiResponseFactory = mock(GeminiResponseFactory.class);
    private final PaymentSettingsService paymentSettingsService = mock(PaymentSettingsService.class);
    private final ModelPricingService modelPricingService = mock(ModelPricingService.class);
    private final StatsService statsService = mock(StatsService.class);
    private final AccountAdmissionStore admissionStore = mock(AccountAdmissionStore.class);
    private final OpenAiResponseOwnerStore responseOwnerStore = mock(OpenAiResponseOwnerStore.class);

    @Test
    void springBuildsTheFacadeWithTheTierRuntimeBean() {
        TierRuntime configured = new TierRuntime(request -> Mono.just(TierPlan.UNTIERED));

        try (AnnotationConfigApplicationContext context = context()) {
            context.getBeanFactory().registerSingleton("tierRuntime", configured);
            context.refresh();

            ProxyFacade facade = context.getBean(ProxyFacade.class);
            assertThat(facade.tierRuntime()).isSameAs(configured);
            assertThat(ReflectionTestUtils.getField(facade, "admissionStore")).isSameAs(admissionStore);
            assertThat(ReflectionTestUtils.getField(facade, "openAiResponseOwnerStore")).isSameAs(responseOwnerStore);
        }
    }

    @Test
    void theRuntimeFactoryGivesEveryDeploymentTheDisabledRuntimeForAReleaseThatCannotRunTiers() {
        SourceTierRuntimeInfo cannotRunTiers = new SourceTierRuntimeInfo(false, false, false, pair -> SourceTierRuntimeInfo.PairStatus.NOT_OFFERED);

        try (AnnotationConfigApplicationContext context = context()) {
            context.getBeanFactory().registerSingleton("sourceTierRuntimeInfo", cannotRunTiers);
            context.register(TierRuntimeConfig.class);
            context.refresh();

            assertThat(context.getBean(TierRuntime.class)).isSameAs(TierRuntime.disabled());
            assertThat(context.getBean(ProxyFacade.class).tierRuntime()).isSameAs(TierRuntime.disabled());
        }
    }

    @Test
    void theRuntimeFactoryPlansOverTiersInTheReleaseAsItIsShipped() {
        try (AnnotationConfigApplicationContext context = context()) {
            // The capabilities as the application declares them: native tiering is complete in this release.
            context.getBeanFactory().registerSingleton("sourceTierRuntimeInfo", new SourceTierRuntimeInfo());
            context.register(TierRuntimeConfig.class);
            context.refresh();

            TierRuntime runtime = context.getBean(TierRuntime.class);
            assertThat(runtime).isNotSameAs(TierRuntime.disabled());
            assertThat(runtime.planner()).isInstanceOf(DefaultSourceTierPlanner.class);
            assertThat(runtime.bindings()).isSameAs(context.getBean(TierSessionBindings.class));
            assertThat(context.getBean(ProxyFacade.class).tierRuntime()).isSameAs(runtime);
        }
    }

    @Test
    void theConstructorWithoutStoresKeepsThePermissiveAdmissionAndNoResponseOwners() {
        ProxyFacade facade = new ProxyFacade(
            apiKeyService, accountPoolService, accountAutoSuspendService, accountSettingsService,
            disabledAccountScheduledTestService, modelCatalog, modelMappingService, rateLimiterService,
            payloadFactory, kiroUpstreamClient, openAiUpstreamClient, openAiRequestAdapter, openAiResponseFactory,
            openAiResponseStateStore, anthropicRequestAdapter, anthropicResponseFactory, anthropicUpstreamClient,
            geminiRequestAdapter, geminiResponseFactory, paymentSettingsService, modelPricingService, statsService
        );

        assertThat(facade.tierRuntime()).isSameAs(TierRuntime.disabled());
        Object admission = ReflectionTestUtils.getField(facade, "admissionStore");
        assertThat(admission).isNotNull();
        assertThat(mockingDetails(admission).isMock()).isFalse();
        assertThat(ReflectionTestUtils.getField(facade, "openAiResponseOwnerStore")).isNull();
    }

    @Test
    void theConstructorWithStoresKeepsThemAndDoesNotEnableTiers() {
        ProxyFacade facade = new ProxyFacade(
            apiKeyService, accountPoolService, accountAutoSuspendService, accountSettingsService,
            disabledAccountScheduledTestService, modelCatalog, modelMappingService, rateLimiterService,
            payloadFactory, kiroUpstreamClient, openAiUpstreamClient, openAiRequestAdapter, openAiResponseFactory,
            openAiResponseStateStore, anthropicRequestAdapter, anthropicResponseFactory, anthropicUpstreamClient,
            geminiRequestAdapter, geminiResponseFactory, paymentSettingsService, modelPricingService, statsService,
            admissionStore, responseOwnerStore
        );

        assertThat(facade.tierRuntime()).isSameAs(TierRuntime.disabled());
        assertThat(ReflectionTestUtils.getField(facade, "admissionStore")).isSameAs(admissionStore);
        assertThat(ReflectionTestUtils.getField(facade, "openAiResponseOwnerStore")).isSameAs(responseOwnerStore);
    }

    private AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("apiKeyService", apiKeyService);
        context.getBeanFactory().registerSingleton("accountPoolService", accountPoolService);
        context.getBeanFactory().registerSingleton("accountAutoSuspendService", accountAutoSuspendService);
        context.getBeanFactory().registerSingleton("accountSettingsService", accountSettingsService);
        context.getBeanFactory().registerSingleton(
            "disabledAccountScheduledTestService", disabledAccountScheduledTestService);
        context.getBeanFactory().registerSingleton("modelCatalog", modelCatalog);
        context.getBeanFactory().registerSingleton("modelMappingService", modelMappingService);
        context.getBeanFactory().registerSingleton("rateLimiterService", rateLimiterService);
        context.getBeanFactory().registerSingleton("kiroPayloadFactory", payloadFactory);
        context.getBeanFactory().registerSingleton("kiroUpstreamClient", kiroUpstreamClient);
        context.getBeanFactory().registerSingleton("openAiUpstreamClient", openAiUpstreamClient);
        context.getBeanFactory().registerSingleton("openAiRequestAdapter", openAiRequestAdapter);
        context.getBeanFactory().registerSingleton("openAiResponseFactory", openAiResponseFactory);
        context.getBeanFactory().registerSingleton("openAiResponseStateStore", openAiResponseStateStore);
        context.getBeanFactory().registerSingleton("anthropicRequestAdapter", anthropicRequestAdapter);
        context.getBeanFactory().registerSingleton("anthropicResponseFactory", anthropicResponseFactory);
        context.getBeanFactory().registerSingleton("anthropicUpstreamClient", anthropicUpstreamClient);
        context.getBeanFactory().registerSingleton("geminiRequestAdapter", geminiRequestAdapter);
        context.getBeanFactory().registerSingleton("geminiResponseFactory", geminiResponseFactory);
        context.getBeanFactory().registerSingleton("paymentSettingsService", paymentSettingsService);
        context.getBeanFactory().registerSingleton("modelPricingService", modelPricingService);
        context.getBeanFactory().registerSingleton("statsService", statsService);
        context.getBeanFactory().registerSingleton("admissionStore", admissionStore);
        context.getBeanFactory().registerSingleton("openAiResponseOwnerStore", responseOwnerStore);
        context.getBeanFactory().registerSingleton("proxyProperties", new ProxyProperties());
        context.getBeanFactory().registerSingleton("sourceTierRegistry", mock(SourceTierRegistry.class));
        context.getBeanFactory().registerSingleton("modelPoolKeyResolver", mock(ModelPoolKeyResolver.class));
        context.register(ProxyFacade.class);
        return context;
    }
}
