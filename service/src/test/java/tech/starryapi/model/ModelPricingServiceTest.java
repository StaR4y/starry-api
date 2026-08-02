package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.core.PlatformUsage;

class ModelPricingServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void chargesMatchedModelWithInputAndOutputPricesPerMillionTokens() {
        Map<String, String> store = new ConcurrentHashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        ModelPricingService service = new ModelPricingService(repository, objectMapper);

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
        BigDecimal reloadedCost = new ModelPricingService(repository, objectMapper).cost(
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
        ModelPricingService service = new ModelPricingService(repository, objectMapper);

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
            new PlatformUsage(1_000_000, 1_000_000, BigDecimal.ZERO, 1_000_000, 1_000_000, 0),
            false,
            BigDecimal.ONE
        ).block();

        assertThat(cost).isEqualByComparingTo("20.250000");
    }

    @Test
    void usesPriorityAndTotalMultiplierWhenMultipleRulesMatch() {
        Map<String, String> store = new ConcurrentHashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        ModelPricingService service = new ModelPricingService(repository, objectMapper);

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
        ModelPricingService service = new ModelPricingService(repository, objectMapper);

        ModelDtos.ModelPricingResponse response = service.update(new ModelDtos.UpdateModelPricingRequest(List.of(
            new ModelDtos.UpdateModelPricingRuleRequest(
                null,
                true,
                "*",
                null,
                null,
                PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER,
                null,
                null,
                null,
                100
            )
        ))).block();

        assertThat(response).isNotNull();
        assertThat(response.rules().getFirst().totalMultiplier())
            .isEqualByComparingTo(PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER);

        BigDecimal cost = service.cost(
            "gpt-5.4-mini",
            new PlatformUsage(3_000_000, 3_000_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            BigDecimal.ONE
        ).block();

        assertThat(cost).isEqualByComparingTo("100.000000");
    }

    @Test
    void fallsBackToGlobalMultiplierWhenNoRuleMatches() {
        ModelPricingService service = new ModelPricingService(mockRepository(new ConcurrentHashMap<>()), objectMapper);

        BigDecimal cost = service.cost(
            "unknown-model",
            new PlatformUsage(3_000_000, 3_000_000, BigDecimal.ZERO, 0, 0, 0),
            false,
            PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER
        ).block();

        assertThat(cost).isEqualByComparingTo("100.000000");
    }

    @Test
    void invalidationPreventsInFlightReadFromRepopulatingPreRestorePricing() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        Sinks.One<SystemSettingEntity> staleQuery = Sinks.one();
        when(repository.findById(anyString())).thenReturn(staleQuery.asMono(), Mono.empty());
        ModelPricingService service = new ModelPricingService(repository, objectMapper);
        PlatformUsage usage = new PlatformUsage(1, 1, BigDecimal.ZERO, 0, 0, 0);

        service.cost("gpt-5", usage, false, BigDecimal.ONE).subscribe();
        service.invalidateAllCaches();
        staleQuery.tryEmitValue(setting(
            ModelPricingService.MODEL_PRICING_RULES,
            "[{\"ruleId\":\"stale\",\"enabled\":true,\"modelPattern\":\"*\","
                + "\"totalMultiplier\":2,\"priority\":1}]"
        ));

        assertThat(service.cost("gpt-5", usage, false, BigDecimal.ONE).block())
            .isEqualByComparingTo("2.000000");
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

    private SystemSettingEntity setting(String key, String value) {
        SystemSettingEntity entity = new SystemSettingEntity();
        entity.setSettingKey(key);
        entity.setSettingValue(value);
        return entity;
    }
}
