package tech.starryapi.proxy.tier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TierRuntimeConfig {

    /**
     * The facade's only way into the tiered-source feature. Nothing builds a real planner yet, so every deployment gets
     * the disabled runtime and keeps today's routing, prices and settlement.
     */
    @Bean
    TierRuntime tierRuntime() {
        return TierRuntime.disabled();
    }
}
