package tech.starryapi.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolPolicyService;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.kiro.KiroUpstreamClient;
import tech.starryapi.proxy.RateLimiterService;

@Component
public class RuntimeStateInvalidator {

    private static final Logger log = LoggerFactory.getLogger(RuntimeStateInvalidator.class);

    private final AdminSessionService adminSessionService;
    private final AccountPoolService accountPoolService;
    private final AccountPoolPolicyService accountPoolPolicyService;
    private final AccountSettingsService accountSettingsService;
    private final PaymentSettingsService paymentSettingsService;
    private final ModelMappingService modelMappingService;
    private final ModelPricingService modelPricingService;
    private final KiroUpstreamClient kiroUpstreamClient;
    private final RateLimiterService rateLimiterService;

    public RuntimeStateInvalidator(
        AdminSessionService adminSessionService,
        AccountPoolService accountPoolService,
        AccountPoolPolicyService accountPoolPolicyService,
        AccountSettingsService accountSettingsService,
        PaymentSettingsService paymentSettingsService,
        ModelMappingService modelMappingService,
        ModelPricingService modelPricingService,
        KiroUpstreamClient kiroUpstreamClient,
        RateLimiterService rateLimiterService
    ) {
        this.adminSessionService = adminSessionService;
        this.accountPoolService = accountPoolService;
        this.accountPoolPolicyService = accountPoolPolicyService;
        this.accountSettingsService = accountSettingsService;
        this.paymentSettingsService = paymentSettingsService;
        this.modelMappingService = modelMappingService;
        this.modelPricingService = modelPricingService;
        this.kiroUpstreamClient = kiroUpstreamClient;
        this.rateLimiterService = rateLimiterService;
    }

    public void invalidateAll() {
        adminSessionService.invalidateAllSessions();
        accountPoolService.invalidateAllCaches();
        accountPoolPolicyService.invalidateAllCaches();
        accountSettingsService.invalidateAllCaches();
        paymentSettingsService.invalidateAllCaches();
        modelMappingService.invalidateAllCaches();
        modelPricingService.invalidateAllCaches();
        kiroUpstreamClient.invalidateAllCaches();
        rateLimiterService.invalidateAllBuckets();

        Mono.whenDelayError(accountPoolService.refreshCache(), modelMappingService.refresh(), modelPricingService.refresh())
            .subscribe(null, error -> log.warn("Failed to warm runtime caches after database state invalidation", error));
    }

    public void invalidateSetting(String settingKey) {
        accountSettingsService.invalidateCacheForSetting(settingKey);
        paymentSettingsService.invalidateCacheForSetting(settingKey);
        modelPricingService.invalidateCacheForSetting(settingKey);
    }
}
