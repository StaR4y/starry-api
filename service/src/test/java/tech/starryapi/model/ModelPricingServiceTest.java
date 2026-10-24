package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.core.PlatformUsage;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ModelPricingServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final OfficialModelPricingService officialModelPricingService = new OfficialModelPricingService(
        WebClient.builder().build(),
        objectMapper,
        new OfficialModelPricingProperties()
    );

    @Test
    void chargesMatchedModelWithInputAndOutputPricesPerMillionTokens() {
        Map<String, String> store = new ConcurrentHashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        ModelPricingService service = new ModelPricingService(repository, objectMapper, officialModelPricingService);

        ModelDtos.ModelPricingResponse response = service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "gpt-5*",
                null,
                null,
                null,
                new BigDecimal("10"),
                new BigDecimal("30"),
                null,
                10
            )
        ))).block();

        assertThat(response).isNotNull();
        assertThat(response.rules()).hasSize(1);
        assertThat(response.rules().getFirst().inputPricePerMillionTokens()).isEqualByComparingTo("10");
        assertThat(response.rules().getFirst().outputPricePerMillionTokens()).isEqualByComparingTo("30");
        BigDecimal cost = service.cost(
            "gpt-5.2",
            new PlatformUsage(1_000, 2_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            BigDecimal.ONE
        ).block();
        BigDecimal reloadedCost = new ModelPricingService(repository, objectMapper, officialModelPricingService).cost(
            "gpt-5.2",
            new PlatformUsage(1_000, 2_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            BigDecimal.ONE
        ).block();

        assertThat(cost).isEqualByComparingTo("0.070000");
        assertThat(reloadedCost).isEqualByComparingTo("0.070000");
        verify(repository).upsertSetting(anyString(), anyString());
    }

    @Test
    void chargesCacheReadAndWriteWithSub2ApiStyleItemizedPrices() {
        Map<String, String> store = new ConcurrentHashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        ModelPricingService service = new ModelPricingService(repository, objectMapper, officialModelPricingService);

        service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "gpt-5.4",
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("2.5"),
                new BigDecimal("15"),
                new BigDecimal("2.5"),
                new BigDecimal("0.25"),
                null,
                10
            )
        ))).block();

        BigDecimal cost = service.cost(
            "gpt-5.4",
            new PlatformUsage(3_000_000, 1_000_000, BigDecimal.ZERO, 1_000_000, 1_000_000, 0),
            false,
            BigDecimal.ONE
        ).block();

        assertThat(cost).isEqualByComparingTo("20.250000");
    }

    @Test
    void usesDeepSeekOfficialDefaultPriceWithoutDoubleChargingCachedInput() {
        ModelPricingService service = new ModelPricingService(
            mockRepository(new ConcurrentHashMap<>()),
            objectMapper,
            officialModelPricingService
        );

        BigDecimal cost = service.cost(
            "deepseek-v4-flash",
            new PlatformUsage(1_000_000, 1_000_000, BigDecimal.ZERO, 900_000, 0, 0),
            false,
            BigDecimal.ONE
        ).block();

        assertThat(cost).isEqualByComparingTo("0.296520");
    }

    @Test
    void multipliesOfficialUsdCostByConfiguredBillingMultiplier() {
        ModelPricingService service = new ModelPricingService(
            mockRepository(new ConcurrentHashMap<>()),
            objectMapper,
            officialModelPricingService
        );

        BigDecimal cost = service.cost(
            "openai/gpt-5.5",
            new PlatformUsage(1_000_000, 1_000_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            new BigDecimal("2")
        ).block();

        assertThat(cost).isEqualByComparingTo("70.000000");
    }

    @Test
    void modelPriceMultiplierReplacesGlobalMultiplierWithDirectOfficialPriceRatio() {
        ModelPricingService service = new ModelPricingService(
            mockRepository(new ConcurrentHashMap<>()),
            objectMapper,
            officialModelPricingService
        );
        ModelDtos.ModelPricingResponse response = service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "gpt-5.5",
                new BigDecimal("0.1"),
                10
            )
        ))).block();

        BigDecimal cost = service.cost(
            "gpt-5.5",
            new PlatformUsage(1_000_000, 1_000_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            new BigDecimal("2")
        ).block();
        List<ModelDtos.ManagedModelResponse> models = service.applyManagedPricing(
            List.of(managedModel("gpt-5.5", true)),
            new BigDecimal("2")
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.rules().getFirst().priceMultiplier()).isEqualByComparingTo("0.1");
        assertThat(response.rules().getFirst().totalMultiplier()).isNull();
        assertThat(cost).isEqualByComparingTo("3.500000");
        assertThat(models).isNotNull();
        assertThat(models.getFirst().pricing().inputPricePerMillionTokens())
            .isEqualByComparingTo("0.5");
        assertThat(models.getFirst().pricing().outputPricePerMillionTokens())
            .isEqualByComparingTo("3");
        assertThat(models.getFirst().pricing().totalPricePerMillionTokens())
            .isEqualByComparingTo("1.75");
    }

    @Test
    void exposesExactEffectivePricesPerMillionTokens() {
        ModelPricingService service = new ModelPricingService(
            mockRepository(new ConcurrentHashMap<>()),
            objectMapper,
            officialModelPricingService
        );

        ModelDtos.ModelPublicPricing pricing = service.publicPricing(
            List.of(),
            "gpt-5.5",
            new BigDecimal("2")
        );

        assertThat(pricing.billingMode()).isEqualTo("input_output");
        assertThat(pricing.inputPricePerMillionTokens()).isEqualByComparingTo("10");
        assertThat(pricing.outputPricePerMillionTokens()).isEqualByComparingTo("60");
        assertThat(pricing.cacheWritePricePerMillionTokens()).isEqualByComparingTo("10");
        assertThat(pricing.cacheReadPricePerMillionTokens()).isEqualByComparingTo("1");
        assertThat(pricing.totalPricePerMillionTokens()).isEqualByComparingTo("35");

        ModelDtos.ModelPublicPricing officialPricing = service.publicPricing(
            List.of(),
            "gpt-5.5",
            BigDecimal.ONE
        );
        assertThat(officialPricing.inputPricePerMillionTokens()).isEqualByComparingTo("5");
        assertThat(officialPricing.outputPricePerMillionTokens()).isEqualByComparingTo("30");
        assertThat(officialPricing.totalPricePerMillionTokens()).isEqualByComparingTo("17.5");
    }

    @Test
    void appliesPricingToCompleteManagedCatalogIncludingDisabledModels() {
        Map<String, String> store = new ConcurrentHashMap<>();
        ModelPricingService service = new ModelPricingService(
            mockRepository(store),
            objectMapper,
            officialModelPricingService
        );
        service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "disabled-model",
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("3"),
                10
            )
        ))).block();

        List<ModelDtos.ManagedModelResponse> priced = service.applyManagedPricing(
            List.of(
                managedModel("gpt-5.5", true),
                managedModel("disabled-model", false)
            ),
            new BigDecimal("2")
        ).block();

        assertThat(priced).isNotNull();
        assertThat(priced).extracting(ModelDtos.ManagedModelResponse::id)
            .containsExactly("gpt-5.5", "disabled-model");
        assertThat(priced.getFirst().pricing().inputPricePerMillionTokens())
            .isEqualByComparingTo("10");
        assertThat(priced.getFirst().pricing().outputPricePerMillionTokens())
            .isEqualByComparingTo("60");
        assertThat(priced.get(1).enabled()).isFalse();
        assertThat(priced.get(1).pricing().billingMode()).isEqualTo("total");
        assertThat(priced.get(1).pricing().totalPricePerMillionTokens())
            .isEqualByComparingTo("3");
    }

    @Test
    void matchesDeepSeekExactPriceRuleForSupportedModelPrefixesBeforeGlobalFallback() {
        ModelPricingService service = new ModelPricingService(
            mockRepository(new ConcurrentHashMap<>()),
            objectMapper,
            officialModelPricingService
        );
        service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "deepseek-v4-flash",
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("0.14"),
                new BigDecimal("0.28"),
                new BigDecimal("0.14"),
                new BigDecimal("0.0028"),
                null,
                20
            ),
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "*",
                null,
                null,
                new BigDecimal("16.6666666667"),
                null,
                null,
                null,
                1_000
            )
        ))).block();
        PlatformUsage usage = new PlatformUsage(1_000_000, 1_000_000, BigDecimal.ZERO, 900_000, 0, 0);

        BigDecimal providerPrefixedCost = service.cost(
            "deepseek/deepseek-v4-flash",
            usage,
            false,
            BigDecimal.ONE
        ).block();
        BigDecimal proxyPrefixedCost = service.cost(
            "starry/deepseek-v4-flash",
            usage,
            false,
            BigDecimal.ONE
        ).block();

        assertThat(providerPrefixedCost).isEqualByComparingTo("0.296520");
        assertThat(proxyPrefixedCost).isEqualByComparingTo("0.296520");
    }

    @Test
    void usesPriorityAndTotalMultiplierWhenMultipleRulesMatch() {
        Map<String, String> store = new ConcurrentHashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        ModelPricingService service = new ModelPricingService(repository, objectMapper, officialModelPricingService);

        service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(null, true, "*", null, null, new BigDecimal("1"), null, null, null, 100),
            new ModelDtos.UpdateModelPricingRuleRequest(null, true, "anthropic/*", null, null, new BigDecimal("0.5"), null, null, null, 10)
        ))).block();

        BigDecimal cost = service.cost(
            "anthropic/claude-sonnet-4.5",
            new PlatformUsage(2, 2, BigDecimal.ZERO, 0, 0, 0),
            false,
            BigDecimal.ONE
        ).block();

        assertThat(cost).isEqualByComparingTo("2.000000");
    }

    @Test
    void chargesTotalMultiplier() {
        Map<String, String> store = new ConcurrentHashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        ModelPricingService service = new ModelPricingService(repository, objectMapper, officialModelPricingService);

        BigDecimal perTokenMultiplier = new BigDecimal("0.0000166666666667");
        ModelDtos.ModelPricingResponse response = service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "*",
                null,
                null,
                perTokenMultiplier,
                null,
                null,
                null,
                100
            )
        ))).block();

        assertThat(response).isNotNull();
        assertThat(response.rules().getFirst().totalMultiplier())
            .isEqualByComparingTo(perTokenMultiplier);

        BigDecimal cost = service.cost(
            "gpt-5.5",
            new PlatformUsage(3_000_000, 3_000_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            BigDecimal.ONE
        ).block();

        assertThat(cost).isEqualByComparingTo("100.000000");
    }

    @Test
    void pricesCatalogAliasesWithOfficialTokenCosts() {
        ModelPricingService service = new ModelPricingService(
            mockRepository(new ConcurrentHashMap<>()),
            objectMapper,
            officialModelPricingService
        );

        ModelDtos.ModelPublicPricing sonnet = service.applyManagedPricing(
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

        assertThat(sonnet).isNotNull();
        assertThat(sonnet.billingMode()).isEqualTo("input_output");
        assertThat(sonnet.inputPricePerMillionTokens()).isPositive();
        assertThat(sonnet.totalPricePerMillionTokens()).isNotEqualByComparingTo("16.6666666667");
    }

    @Test
    void rejectsUnpricedModelsInsteadOfGuessingAnUnrelatedPrice() {
        ModelPricingService service = new ModelPricingService(
            mockRepository(new ConcurrentHashMap<>()),
            objectMapper,
            officialModelPricingService
        );

        assertThatThrownBy(() -> service.cost(
            "unknown-model",
            new PlatformUsage(3_000_000, 3_000_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER
        ).block()).isInstanceOf(tech.starryapi.common.ApiException.class)
            .hasMessageContaining("Model price is unavailable");
        assertThat(service.publicPricing(List.of(), "unknown-model", BigDecimal.ONE).billingMode())
            .isEqualTo("unavailable");
    }

    @Test
    void preservesZeroCostFromOfficialCatalog() {
        ModelPricingService service = new ModelPricingService(
            mockRepository(new ConcurrentHashMap<>()),
            objectMapper,
            officialModelPricingService
        );

        BigDecimal cost = service.cost(
            "deepseek-v3-2-251201",
            new PlatformUsage(1_000_000, 1_000_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            BigDecimal.ONE
        ).block();

        assertThat(cost).isEqualByComparingTo("0.000000");
    }

    @Test
    void invalidationPreventsInFlightReadFromRepopulatingPreRestorePricing() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        Sinks.One<SystemSettingEntity> staleQuery = Sinks.one();
        when(repository.findById(anyString())).thenReturn(staleQuery.asMono(), Mono.empty());
        ModelPricingService service = new ModelPricingService(repository, objectMapper, officialModelPricingService);
        PlatformUsage usage = new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0);

        service.cost("gpt-5", usage, false, BigDecimal.ONE).subscribe();
        service.invalidateAllCaches();
        staleQuery.tryEmitValue(setting(
            ModelPricingService.MODEL_PRICING_RULES,
            "[{\"ruleId\":\"stale\",\"enabled\":true,\"modelPattern\":\"*\","
                + "\"totalMultiplier\":2,\"priority\":1}]"
        ));

        assertThat(service.cost("gpt-5", usage, false, BigDecimal.ONE).block())
            .isEqualByComparingTo("0.000011");
    }

    private SystemSettingRepository mockRepository(Map<String, String> store) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = store.get(key);
            return value == null ? Mono.empty() : Mono.just(setting(key, value));
        });
        when(repository.upsertSetting(anyString(), anyString())).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return Mono.just(1);
        });
        return repository;
    }

    private ModelDtos.ManagedModelResponse managedModel(String id, boolean enabled) {
        return new ModelDtos.ManagedModelResponse(
            id,
            id,
            null,
            "Test",
            "Test",
            id,
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            128_000,
            null,
            enabled,
            true
        );
    }

    private SystemSettingEntity setting(String key, String value) {
        SystemSettingEntity entity = new SystemSettingEntity();
        entity.setSettingKey(key);
        entity.setSettingValue(value);
        return entity;
    }
}
