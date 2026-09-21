package tech.starryapi.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.payment.PaymentDtos.PaymentSettingsResponse;
import tech.starryapi.payment.PaymentDtos.UpdatePaymentSettingsRequest;

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
                new BigDecimal("1.5"),
                new BigDecimal("0.01"),
                "Prefix",
                "Suffix",
                "https://example.com/help.png",
                "Help text",
                "https://pay.example.com/submit.php",
                "10001",
                "secret",
                "qrcode",
                true,
                new BigDecimal("0.15"),
                10,
                null,
                null
            ))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.enabled()).isTrue();
        assertThat(response.maxAmount()).isEqualByComparingTo("0.00");
        assertThat(response.dailyLimit()).isEqualByComparingTo("0.00");
        assertThat(response.enabledTypes()).containsExactly("alipay");
        assertThat(response.apiBillingMultiplier()).isEqualByComparingTo("1.500000");
        assertThat(response.easypayApiBase()).isEqualTo("https://pay.example.com/submit.php");
        assertThat(response.easypayPid()).isEqualTo("10001");
        assertThat(response.easypayPkeyConfigured()).isTrue();
        assertThat(response.easypayPaymentMode()).isEqualTo("qrcode");
        assertThat(response.inviteRebateEnabled()).isTrue();
        assertThat(response.inviteRebateRate()).isEqualByComparingTo("0.150000");

        assertThat(store.get(PaymentSettingsService.PAYMENT_ENABLED)).isEqualTo("true");
        assertThat(store.get(PaymentSettingsService.MAX_RECHARGE_AMOUNT)).isEqualTo("0.00");
        assertThat(store.get(PaymentSettingsService.MODEL_PRICE_BILLING_MULTIPLIER)).isEqualTo("1.500000");
        assertThat(store.get(PaymentSettingsService.EASYPAY_PKEY)).isEqualTo("secret");
        assertThat(store.get(PaymentSettingsService.INVITE_REBATE_RATE)).isEqualTo("0.150000");
        verify(repository).upsertSettings(any(String[].class), any(String[].class));
    }

    @Test
    void defaultApiBillingMultiplierUsesOfficialPriceWithoutMarkup() {
        Map<String, String> store = new ConcurrentHashMap<>();
        SystemSettingRepository repository = mockRepository(store);

        PaymentSettingsResponse response = new PaymentSettingsService(repository).settings().block();

        assertThat(response).isNotNull();
        assertThat(response.apiBillingMultiplier()).isEqualByComparingTo("1.000000");
    }

    @Test
    void doesNotInterpretLegacyPerTokenSettingAsOfficialPriceMultiplier() {
        Map<String, String> store = new ConcurrentHashMap<>();
        store.put("API_BILLING_MULTIPLIER", "0.0000166666666667");

        PaymentSettingsResponse response = new PaymentSettingsService(mockRepository(store)).settings().block();

        assertThat(response).isNotNull();
        assertThat(response.apiBillingMultiplier()).isEqualByComparingTo("1.000000");
    }

    @Test
    void configLoadsSettingsInOneQueryAndReusesSnapshot() {
        SystemSettingRepository repository = mockRepository(new ConcurrentHashMap<>());
        PaymentSettingsService service = new PaymentSettingsService(repository);

        service.config().block();
        service.config().block();

        verify(repository, times(1)).findAllById(any(Iterable.class));
    }

    @Test
    void invalidationPreventsAnOlderLoadFromRepopulatingTheConfigCache() {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        Sinks.Many<SystemSettingEntity> staleLoad = Sinks.many().unicast().onBackpressureBuffer();
        when(repository.findAllById(any(Iterable.class))).thenReturn(
            staleLoad.asFlux(),
            Flux.just(setting(PaymentSettingsService.PAYMENT_ENABLED, "true"))
        );
        PaymentSettingsService service = new PaymentSettingsService(repository);
        AtomicReference<PaymentSettingsService.PaymentConfig> staleResult = new AtomicReference<>();

        service.config().subscribe(staleResult::set);
        service.invalidateAllCaches();
        staleLoad.tryEmitNext(setting(PaymentSettingsService.PAYMENT_ENABLED, "false"));
        staleLoad.tryEmitComplete();

        assertThat(staleResult.get()).isNotNull();
        assertThat(staleResult.get().enabled()).isFalse();
        assertThat(service.config().block()).isNotNull();
        assertThat(service.config().block().enabled()).isTrue();
        verify(repository, times(2)).findAllById(any(Iterable.class));
    }

    private SystemSettingRepository mockRepository(Map<String, String> store) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.acquireTransactionLock(anyString())).thenReturn(Mono.just(true));
        when(repository.findAllById(any(Iterable.class))).thenAnswer(invocation -> {
            Iterable<String> keys = invocation.getArgument(0);
            return Flux.fromIterable(keys)
                .filter(store::containsKey)
                .map(key -> setting(key, store.get(key)));
        });
        when(repository.upsertSetting(anyString(), anyString())).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return Mono.just(1);
        });
        when(repository.upsertSettings(any(String[].class), any(String[].class))).thenAnswer(invocation -> {
            String[] keys = invocation.getArgument(0);
            String[] values = invocation.getArgument(1);
            for (int index = 0; index < keys.length; index++) {
                store.put(keys[index], values[index]);
            }
            return Mono.just(keys.length);
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
