package tech.starryapi.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolPolicyService;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.assistant.AssistantDocsIndex;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelCatalogManagementService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.proxy.RateLimiterService;

class RuntimeStateInvalidatorTest {

    @Test
    void clearsSessionsAndCachesThenWarmsDatabaseBackedCaches() {
        AdminSessionService adminSessions = mock(AdminSessionService.class);
        AccountPoolService accountPool = mock(AccountPoolService.class);
        AccountPoolPolicyService poolPolicy = mock(AccountPoolPolicyService.class);
        AccountSettingsService accountSettings = mock(AccountSettingsService.class);
        PaymentSettingsService paymentSettings = mock(PaymentSettingsService.class);
        ModelMappingService modelMappings = mock(ModelMappingService.class);
        ModelCatalogManagementService modelCatalog = mock(ModelCatalogManagementService.class);
        ModelPricingService modelPricing = mock(ModelPricingService.class);
        KiroUpstreamClient kiro = mock(KiroUpstreamClient.class);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        AppearanceSettingsService appearance = mock(AppearanceSettingsService.class);
        AssistantDocsIndex assistantDocs = mock(AssistantDocsIndex.class);
        when(accountPool.refreshCache()).thenReturn(Mono.empty());
        when(poolPolicy.refreshSettings()).thenReturn(Mono.empty());
        when(modelMappings.refresh()).thenReturn(Mono.empty());
        when(modelCatalog.refresh()).thenReturn(Mono.empty());
        when(modelPricing.refresh()).thenReturn(Mono.empty());
        when(appearance.settings()).thenReturn(Mono.just(AppearanceSettingsService.defaults()));

        RuntimeStateInvalidator invalidator = new RuntimeStateInvalidator(
            adminSessions,
            accountPool,
            poolPolicy,
            accountSettings,
            paymentSettings,
            modelMappings,
            modelCatalog,
            modelPricing,
            kiro,
            rateLimiter,
            appearance,
            assistantDocs
        );

        invalidator.invalidateAll();

        verify(adminSessions).invalidateAllSessions();
        verify(accountPool).invalidateAllCaches();
        verify(poolPolicy).invalidateAllCaches();
        verify(accountSettings).invalidateAllCaches();
        verify(paymentSettings).invalidateAllCaches();
        verify(modelMappings).invalidateAllCaches();
        verify(modelPricing).invalidateAllCaches();
        verify(kiro).invalidateAllCaches();
        verify(rateLimiter).invalidateAllBuckets();
        verify(appearance).invalidateCache();
        verify(assistantDocs).invalidate();
        verify(accountPool).refreshCache();
        verify(poolPolicy).refreshSettings();
        verify(modelMappings).refresh();
        verify(modelCatalog).refresh();
        verify(modelPricing).refresh();
    }

    @Test
    void settingNotificationKeepsItsNarrowInvalidationPath() {
        AccountSettingsService accountSettings = mock(AccountSettingsService.class);
        PaymentSettingsService paymentSettings = mock(PaymentSettingsService.class);
        ModelPricingService modelPricing = mock(ModelPricingService.class);
        ModelCatalogManagementService modelCatalog = mock(ModelCatalogManagementService.class);
        RuntimeStateInvalidator invalidator = invalidator(
            accountSettings,
            paymentSettings,
            modelCatalog,
            modelPricing
        );

        invalidator.invalidateSetting("payment_enabled");

        verify(accountSettings).invalidateCacheForSetting("payment_enabled");
        verify(paymentSettings).invalidateCacheForSetting("payment_enabled");
        verify(modelCatalog).invalidateCacheForSetting("payment_enabled");
        verify(modelPricing).invalidateCacheForSetting("payment_enabled");
    }

    @Test
    void poolPolicySettingNotificationRefreshesCircuitSettingsImmediately() {
        AccountSettingsService accountSettings = mock(AccountSettingsService.class);
        PaymentSettingsService paymentSettings = mock(PaymentSettingsService.class);
        ModelPricingService modelPricing = mock(ModelPricingService.class);
        AccountPoolPolicyService poolPolicy = mock(AccountPoolPolicyService.class);
        when(poolPolicy.managesSetting("account_pool_policy_enabled")).thenReturn(true);
        when(poolPolicy.refreshSettings()).thenReturn(Mono.empty());
        RuntimeStateInvalidator invalidator = new RuntimeStateInvalidator(
            mock(AdminSessionService.class),
            mock(AccountPoolService.class),
            poolPolicy,
            accountSettings,
            paymentSettings,
            mock(ModelMappingService.class),
            mock(ModelCatalogManagementService.class),
            modelPricing,
            mock(KiroUpstreamClient.class),
            mock(RateLimiterService.class),
            mock(AppearanceSettingsService.class),
            mock(AssistantDocsIndex.class)
        );

        invalidator.invalidateSetting("account_pool_policy_enabled");

        verify(poolPolicy).refreshSettings();
    }

    private RuntimeStateInvalidator invalidator(
        AccountSettingsService accountSettings,
        PaymentSettingsService paymentSettings,
        ModelCatalogManagementService modelCatalog,
        ModelPricingService modelPricing
    ) {
        return new RuntimeStateInvalidator(
            mock(AdminSessionService.class),
            mock(AccountPoolService.class),
            mock(AccountPoolPolicyService.class),
            accountSettings,
            paymentSettings,
            mock(ModelMappingService.class),
            modelCatalog,
            modelPricing,
            mock(KiroUpstreamClient.class),
            mock(RateLimiterService.class),
            mock(AppearanceSettingsService.class),
            mock(AssistantDocsIndex.class)
        );
    }
}
