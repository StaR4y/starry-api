package tech.starryapi.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolPolicyService;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.AccountSettingsService;
import tech.starryapi.model.ModelMappingService;
import tech.starryapi.model.ModelCatalogManagementService;
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
    private final ModelCatalogManagementService modelCatalogManagementService;
    private final ModelPricingService modelPricingService;
    private final KiroUpstreamClient kiroUpstreamClient;
    private final RateLimiterService rateLimiterService;
    private final AppearanceSettingsService appearanceSettingsService;

    public RuntimeStateInvalidator(
        AdminSessionService adminSessionService,
        AccountPoolService accountPoolService,
        AccountPoolPolicyService accountPoolPolicyService,
        AccountSettingsService accountSettingsService,
        PaymentSettingsService paymentSettingsService,
        ModelMappingService modelMappingService,
        ModelCatalogManagementService modelCatalogManagementService,
        ModelPricingService modelPricingService,
        KiroUpstreamClient kiroUpstreamClient,
        RateLimiterService rateLimiterService,
        AppearanceSettingsService appearanceSettingsService
    ) {
        this.adminSessionService = adminSessionService;
        this.accountPoolService = accountPoolService;
        this.accountPoolPolicyService = accountPoolPolicyService;
        this.accountSettingsService = accountSettingsService;
        this.paymentSettingsService = paymentSettingsService;
        this.modelMappingService = modelMappingService;
        this.modelCatalogManagementService = modelCatalogManagementService;
        this.modelPricingService = modelPricingService;
        this.kiroUpstreamClient = kiroUpstreamClient;
        this.rateLimiterService = rateLimiterService;
        this.appearanceSettingsService = appearanceSettingsService;
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
        appearanceSettingsService.invalidateCache();

        Mono.whenDelayError(
            accountPoolService.refreshCache(),
            accountPoolPolicyService.refreshSettings(),
            modelMappingService.refresh(),
            modelCatalogManagementService.refresh(),
            modelPricingService.refresh(),
            appearanceSettingsService.settings()
        )
            .subscribe(null, error -> log.warn("Failed to warm runtime caches after database state invalidation", error));
    }

    public void invalidateSetting(String settingKey) {
        accountSettingsService.invalidateCacheForSetting(settingKey);
        paymentSettingsService.invalidateCacheForSetting(settingKey);
        modelCatalogManagementService.invalidateCacheForSetting(settingKey);
        modelPricingService.invalidateCacheForSetting(settingKey);
        if (settingKey != null && settingKey.startsWith("appearance_")) {
            appearanceSettingsService.invalidateCache();
            appearanceSettingsService.settings()
                .subscribe(null, error -> log.warn("Failed to refresh appearance settings", error));
        }
        if (accountPoolPolicyService.managesSetting(settingKey)) {
            accountPoolPolicyService.refreshSettings()
                .subscribe(null, error -> log.warn("Failed to refresh account pool policy settings", error));
        }
    }
}
