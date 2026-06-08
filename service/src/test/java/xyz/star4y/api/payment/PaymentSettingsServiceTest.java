package xyz.star4y.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import xyz.star4y.api.admin.SystemSettingEntity;
import xyz.star4y.api.admin.SystemSettingRepository;
import xyz.star4y.api.payment.PaymentDtos.PaymentSettingsResponse;
import xyz.star4y.api.payment.PaymentDtos.UpdatePaymentSettingsRequest;

class PaymentSettingsServiceTest {

    @Test
    void updateUpsertsMissingPaymentSettingsAndReturnsSavedValues() {
        Map<String, String> store = new ConcurrentHashMap<>();
        SystemSettingRepository repository = mockRepository(store);

        PaymentSettingsResponse response = new PaymentSettingsService(repository)
            .update(new UpdatePaymentSettingsRequest(
                true,
                new BigDecimal("5"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                45,
                5,
                List.of("alipay"),
                new BigDecimal("2"),
                new BigDecimal("0.01"),
                "Prefix",
                "Suffix",
                "https://example.com/help.png",
                "Help text",
                "https://pay.example.com/submit.php",
                "10001",
                "secret",
                "qrcode"
            ))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.enabled()).isTrue();
        assertThat(response.maxAmount()).isEqualByComparingTo("0.00");
        assertThat(response.dailyLimit()).isEqualByComparingTo("0.00");
        assertThat(response.enabledTypes()).containsExactly("alipay");
        assertThat(response.easypayApiBase()).isEqualTo("https://pay.example.com/submit.php");
        assertThat(response.easypayPid()).isEqualTo("10001");
        assertThat(response.easypayPkeyConfigured()).isTrue();
        assertThat(response.easypayPaymentMode()).isEqualTo("qrcode");

        verify(repository).upsertSetting(PaymentSettingsService.PAYMENT_ENABLED, "true");
        verify(repository).upsertSetting(PaymentSettingsService.MAX_RECHARGE_AMOUNT, "0.00");
        verify(repository).upsertSetting(PaymentSettingsService.EASYPAY_PKEY, "secret");
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
