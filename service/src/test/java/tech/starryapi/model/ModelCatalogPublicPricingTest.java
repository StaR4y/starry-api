package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tech.starryapi.platform.catalog.ProxyModelCatalog;

class ModelCatalogPublicPricingTest {

    @Test
    void catalogModelsDoNotUseUnknownSafetyFallbackPrice() {
        OfficialModelPricingService official = new OfficialModelPricingService(
            WebClient.builder().build(),
            JsonMapper.builder().build(),
            new OfficialModelPricingProperties()
        );
        ModelPricingService service = new ModelPricingService(
            ModelPricingServiceTestSupport.repository(),
            JsonMapper.builder().findAndAddModules().build(),
            official
        );
        List<String> fallbackIds = new ArrayList<>();
        BigDecimal safety = new BigDecimal("16.6666666667");
        for (ModelDtos.ModelSpec spec : ProxyModelCatalog.models()) {
            ModelDtos.ModelPublicPricing pricing = service.applyManagedPricing(
                List.of(new ModelDtos.ManagedModelResponse(
                    spec.id(),
                    spec.name(),
                    spec.icon(),
                    spec.provider(),
                    spec.family(),
                    spec.targetModelId(),
                    spec.requestFormat(),
                    spec.endpoint(),
                    spec.maxInputTokens(),
                    spec.description(),
                    true,
                    true
                )),
                BigDecimal.ONE
            ).block().getFirst().pricing();
            if (pricing == null
                || "total".equals(pricing.billingMode())
                    && pricing.totalPricePerMillionTokens() != null
                    && pricing.totalPricePerMillionTokens().compareTo(safety) == 0) {
                fallbackIds.add(spec.id());
            }
        }
        assertThat(fallbackIds).isEmpty();
    }

    @Test
    void customCatalogIdsUseTheirVerifiedTargetPrice() {
        OfficialModelPricingService official = new OfficialModelPricingService(
            WebClient.builder().build(),
            JsonMapper.builder().build(),
            new OfficialModelPricingProperties()
        );
        ModelPricingService service = new ModelPricingService(
            ModelPricingServiceTestSupport.repository(),
            JsonMapper.builder().findAndAddModules().build(),
            official
        );
        ModelDtos.ModelPublicPricing pricing = service.applyManagedPricing(
            List.of(new ModelDtos.ManagedModelResponse(
                "or-kimi",
                "Kimi",
                null,
                "OpenRouter",
                "Custom",
                "gpt-5.5",
                "OPENAI_CHAT_COMPLETIONS",
                "/v1/chat/completions",
                128_000,
                null,
                true,
                false
            )),
            BigDecimal.ONE
        ).block().getFirst().pricing();

        assertThat(pricing.billingMode()).isEqualTo("input_output");
        assertThat(pricing.inputPricePerMillionTokens()).isEqualByComparingTo("5");
        assertThat(pricing.outputPricePerMillionTokens()).isEqualByComparingTo("30");
    }

    @Test
    void legacyCatchAllDisplaysThePriceActuallyCharged() {
        OfficialModelPricingService official = new OfficialModelPricingService(
            WebClient.builder().build(),
            JsonMapper.builder().build(),
            new OfficialModelPricingProperties()
        );
        ModelPricingService service = new ModelPricingService(
            ModelPricingServiceTestSupport.repository(),
            JsonMapper.builder().findAndAddModules().build(),
            official
        );
        service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "*",
                null,
                null,
                new BigDecimal("0.0000166666666667"),
                null,
                null,
                null,
                100
            )
        ))).block();

        ModelDtos.ModelPublicPricing pricing = service.applyManagedPricing(
            List.of(new ModelDtos.ManagedModelResponse(
                "sonnet",
                "Claude Sonnet",
                null,
                "Anthropic",
                "Claude 4",
                "claude-sonnet-4.5",
                "OPENAI_CHAT_COMPLETIONS",
                "/v1/chat/completions",
                200_000,
                null,
                true,
                true
            )),
            BigDecimal.ONE
        ).block().getFirst().pricing();

        assertThat(pricing.billingMode()).isEqualTo("total");
        assertThat(pricing.totalPricePerMillionTokens()).isEqualByComparingTo("16.6666666667");
        assertThat(pricing.inputPricePerMillionTokens()).isNull();
        assertThat(pricing.outputPricePerMillionTokens()).isNull();
    }

    @Test
    void verifiedCatalogAliasesShowItemizedPricesWithoutApproximateFallback() {
        OfficialModelPricingService official = new OfficialModelPricingService(
            WebClient.builder().build(),
            JsonMapper.builder().build(),
            new OfficialModelPricingProperties()
        );
        ModelPricingService service = new ModelPricingService(
            ModelPricingServiceTestSupport.repository(),
            JsonMapper.builder().findAndAddModules().build(),
            official
        );
        List<String> aliases = List.of(
            "gpt-6",
            "gpt-6-mini",
            "chatgpt-4o",
            "o1",
            "o1-mini",
            "claude-sonnet-4.5",
            "sonnet",
            "claude-haiku-4.5",
            "haiku",
            "claude-opus-4.5",
            "opus",
            "gemini-1.5-pro"
        );
        BigDecimal safety = new BigDecimal("16.6666666667");
        for (String id : aliases) {
            ModelDtos.ModelPublicPricing pricing = service.publicPricing(List.of(), id, BigDecimal.ONE);
            assertThat(pricing.billingMode())
                .as("%s should use itemized official pricing", id)
                .isEqualTo("input_output");
            assertThat(pricing.totalPricePerMillionTokens())
                .as("%s should not use the unknown-model safety price", id)
                .isNotEqualByComparingTo(safety);
            assertThat(pricing.inputPricePerMillionTokens()).as(id).isPositive();
            assertThat(pricing.outputPricePerMillionTokens()).as(id).isPositive();
        }
    }
}

class ModelPricingServiceTestSupport {
    static tech.starryapi.admin.SystemSettingRepository repository() {
        tech.starryapi.admin.SystemSettingRepository repository =
            org.mockito.Mockito.mock(tech.starryapi.admin.SystemSettingRepository.class);
        java.util.Map<String, String> store = new ConcurrentHashMap<>();
        org.mockito.Mockito.when(repository.findById(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = store.get(key);
                if (value == null) return reactor.core.publisher.Mono.empty();
                var entity = new tech.starryapi.admin.SystemSettingEntity();
                entity.setSettingKey(key);
                entity.setSettingValue(value);
                return reactor.core.publisher.Mono.just(entity);
            });
        org.mockito.Mockito.when(repository.upsertSetting(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()
        )).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return reactor.core.publisher.Mono.just(1);
        });
        return repository;
    }
}
