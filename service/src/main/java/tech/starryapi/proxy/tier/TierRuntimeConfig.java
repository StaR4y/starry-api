package tech.starryapi.proxy.tier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierRuntimeInfo;

@Configuration
public class TierRuntimeConfig {

    /** The tier each session was pushed down to. */
    @Bean
    TierSessionBindings tierSessionBindings(ProxyProperties properties) {
        return new TierSessionBindings(properties);
    }

    /**
     * The facade's only way into the tiered-source feature. A release plans requests over tiers only when it can run
     * them ({@link SourceTierRuntimeInfo#nativeTiers()}); until then the configuration, which the administrator API
     * does not accept either, is not looked at, and every deployment keeps today's routing, prices and settlement. Clients
     * may name the tier they want only where {@link SourceTierRuntimeInfo#selectors()} says the release can serve it, and
     * the all-endpoints switch of a vendor is honoured only where {@link SourceTierRuntimeInfo#unifyEndpoints()} does.
     */
    @Bean
    TierRuntime tierRuntime(
        SourceTierRuntimeInfo runtimeInfo,
        SourceTierRegistry registry,
        ModelPoolKeyResolver keyResolver,
        ModelCatalogProvider modelCatalog,
        AccountPoolService accountPool,
        PaymentSettingsService paymentSettings,
        ModelPricingService modelPricing,
        TierSessionBindings bindings
    ) {
        if (!runtimeInfo.nativeTiers()) {
            return TierRuntime.disabled();
        }
        return new TierRuntime(new DefaultSourceTierPlanner(
            registry, keyResolver, modelCatalog, accountPool, paymentSettings, modelPricing, bindings, runtimeInfo.selectors(),
            runtimeInfo.unifyEndpoints()),
            bindings);
    }
}
