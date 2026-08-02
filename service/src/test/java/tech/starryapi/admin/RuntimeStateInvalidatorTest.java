package tech.starryapi.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolPolicyService;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.model.ModelMappingService;
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
        ModelPricingService modelPricing = mock(ModelPricingService.class);
        KiroUpstreamClient kiro = mock(KiroUpstreamClient.class);
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        when(accountPool.refreshCache()).thenReturn(Mono.empty());
        when(modelMappings.refresh()).thenReturn(Mono.empty());
        when(modelPricing.refresh()).thenReturn(Mono.empty());

        RuntimeStateInvalidator invalidator = new RuntimeStateInvalidator(
            adminSessions,
            accountPool,
            poolPolicy,
            accountSettings,
            paymentSettings,
            modelMappings,
            modelPricing,
            kiro,
            rateLimiter
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
        verify(accountPool).refreshCache();
        verify(modelMappings).refresh();
        verify(modelPricing).refresh();
    }

    @Test
    void settingNotificationKeepsItsNarrowInvalidationPath() {
        AccountSettingsService accountSettings = mock(AccountSettingsService.class);
        PaymentSettingsService paymentSettings = mock(PaymentSettingsService.class);
        ModelPricingService modelPricing = mock(ModelPricingService.class);
        RuntimeStateInvalidator invalidator = invalidator(accountSettings, paymentSettings, modelPricing);

        invalidator.invalidateSetting("payment_enabled");

        verify(accountSettings).invalidateCacheForSetting("payment_enabled");
        verify(paymentSettings).invalidateCacheForSetting("payment_enabled");
        verify(modelPricing).invalidateCacheForSetting("payment_enabled");
    }

    private RuntimeStateInvalidator invalidator(
        AccountSettingsService accountSettings,
        PaymentSettingsService paymentSettings,
        ModelPricingService modelPricing
    ) {
        return new RuntimeStateInvalidator(
            mock(AdminSessionService.class),
            mock(AccountPoolService.class),
            mock(AccountPoolPolicyService.class),
            accountSettings,
            paymentSettings,
            mock(ModelMappingService.class),
            modelPricing,
            mock(KiroUpstreamClient.class),
            mock(RateLimiterService.class)
        );
    }
}
