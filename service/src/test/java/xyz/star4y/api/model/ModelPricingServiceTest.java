package xyz.star4y.api.model;

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
import xyz.star4y.api.admin.SystemSettingEntity;
import xyz.star4y.api.admin.SystemSettingRepository;
import xyz.star4y.api.payment.PaymentSettingsService;
import xyz.star4y.api.platform.core.PlatformUsage;

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
    void chargesTotalPricePerMillionTokens() {
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
                null,
                null,
                null,
                new BigDecimal("16.6666666667"),
                100
            )
        ))).block();

        assertThat(response).isNotNull();
        assertThat(response.rules().getFirst().totalPricePerMillionTokens()).isEqualByComparingTo("16.6666666667");

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
