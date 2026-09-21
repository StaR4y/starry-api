package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;

class AccountSettingsServiceTest {

    @Test
    void productionConstructorIsTheOnlyExplicitSpringInjectionPoint() {
        var injectionConstructors = Arrays.stream(AccountSettingsService.class.getDeclaredConstructors())
            .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
            .toList();

        assertThat(injectionConstructors)
            .singleElement()
            .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                .containsExactly(SystemSettingRepository.class));
    }

    @Test
    void settingsDefaultsGptFastModeToDisabled() {
        SystemSettingRepository repository = mockRepository(new HashMap<>());

        AccountDtos.AccountSettingsResponse response = new AccountSettingsService(repository)
            .settings()
            .block();

        assertThat(response).isNotNull();
        assertThat(response.gptFastModeEnabled()).isFalse();
        assertThat(response.disabledAccountScheduledTest().enabled()).isFalse();
        assertThat(response.disabledAccountScheduledTest().scheduleMode()).isEqualTo("interval");
        assertThat(response.disabledAccountScheduledTest().intervalMinutes()).isEqualTo(60);
        assertThat(response.disabledAccountScheduledTest().autoRunWhenNoAvailableAccounts()).isFalse();
        assertThat(response.poolPolicy().enabled()).isFalse();
        assertThat(response.routingPolicy().isolateCodexPoolEnabled()).isTrue();
        assertThat(response.routingPolicy().gptUsesCodexAccountsEnabled()).isFalse();
        assertThat(response.routingPolicy().bindSessionOnAdmissionEnabled()).isFalse();
        assertThat(response.routingPolicy().bindSessionByApiKeyEnabled()).isFalse();
        assertThat(response.routingPolicy().codexMaxRetryAccounts()).isEqualTo(1);
        assertThat(response.routingPolicy().openAiMaxRetryAccounts()).isEqualTo(3);
    }

    @Test
    void updateStoresGptFastModeEnabled() {
        Map<String, String> store = new HashMap<>();
        SystemSettingRepository repository = mockRepository(store);

        AccountDtos.AccountSettingsResponse response = new AccountSettingsService(repository)
            .update(new AccountDtos.UpdateAccountSettingsRequest(true, null, null))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.gptFastModeEnabled()).isTrue();
        assertThat(store.get(AccountSettingsService.GPT_FAST_MODE_ENABLED)).isEqualTo("true");
    }

    @Test
    void enablingPolicyCreatesANewPersistentEpochAndActivationBoundary() {
        Map<String, String> store = new HashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        Instant activatedAt = Instant.parse("2026-08-11T02:00:00Z");
        AccountSettingsService service = new AccountSettingsService(
            repository,
            Clock.fixed(activatedAt, ZoneOffset.UTC)
        );
        AccountDtos.PoolPolicySettings enable = new AccountDtos.PoolPolicySettings(
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );

        AccountDtos.PoolPolicySettings first = service
            .update(new AccountDtos.UpdateAccountSettingsRequest(null, null, enable))
            .block()
            .poolPolicy();
        service.update(new AccountDtos.UpdateAccountSettingsRequest(
            null,
            null,
            new AccountDtos.PoolPolicySettings(false, null, null, null, null, null, null, null, null)
        )).block();
        AccountDtos.PoolPolicySettings second = service
            .update(new AccountDtos.UpdateAccountSettingsRequest(null, null, enable))
            .block()
            .poolPolicy();

        assertThat(first.circuitEpoch()).isNotNull();
        assertThat(first.activationStartedAt()).isEqualTo(activatedAt);
        assertThat(second.circuitEpoch()).isNotEqualTo(first.circuitEpoch());
        assertThat(store.get(AccountSettingsService.POOL_POLICY_EPOCH))
            .isEqualTo(second.circuitEpoch().toString());
        assertThat(store.get(AccountSettingsService.POOL_POLICY_ACTIVATION_STARTED_AT))
            .isEqualTo(activatedAt.toString());
    }

    @Test
    void updateStoresDisabledAccountScheduledTestSettings() {
        Map<String, String> store = new HashMap<>();
        SystemSettingRepository repository = mockRepository(store);

        AccountDtos.DisabledAccountScheduledTestSettings request = new AccountDtos.DisabledAccountScheduledTestSettings(
            true,
            "cron",
            15,
            "0 */15 * * * *",
            "Asia/Shanghai",
            1,
            4,
            50,
            "claude-sonnet-4-5-20250929",
            "Reply OK",
            true,
            true,
            true,
            true,
            true,
            30,
            java.util.List.of("Anthropic", "OpenAI-compatible")
        );

        AccountDtos.AccountSettingsResponse response = new AccountSettingsService(repository)
            .update(new AccountDtos.UpdateAccountSettingsRequest(null, request, null))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.disabledAccountScheduledTest().enabled()).isTrue();
        assertThat(response.disabledAccountScheduledTest().scheduleMode()).isEqualTo("cron");
        assertThat(response.disabledAccountScheduledTest().autoRunWhenNoAvailableAccounts()).isTrue();
        assertThat(response.disabledAccountScheduledTest().providers()).containsExactly("Anthropic", "OpenAI-compatible");
        assertThat(store.get(AccountSettingsService.DISABLED_TEST_ENABLED)).isEqualTo("true");
        assertThat(store.get(AccountSettingsService.DISABLED_TEST_AUTO_RUN_WHEN_NO_AVAILABLE)).isEqualTo("true");
        assertThat(store.get(AccountSettingsService.DISABLED_TEST_CRON_EXPRESSION)).isEqualTo("0 */15 * * * *");
        assertThat(store.get(AccountSettingsService.DISABLED_TEST_PROVIDERS)).isEqualTo("Anthropic,OpenAI-compatible");
    }

    @Test
    void updateStoresRoutingPolicySettingsAndMergesPartialPatches() {
        Map<String, String> store = new HashMap<>();
        SystemSettingRepository repository = mockRepository(store);
        AccountSettingsService service = new AccountSettingsService(repository);

        AccountDtos.AccountSettingsResponse created = service
            .update(new AccountDtos.UpdateAccountSettingsRequest(
                null,
                null,
                null,
                new AccountDtos.RoutingPolicySettings(true, true, true, true, 1, 3, 3, 2, 2)
            ))
            .block();
        AccountDtos.AccountSettingsResponse patched = service
            .update(new AccountDtos.UpdateAccountSettingsRequest(
                null,
                null,
                null,
                new AccountDtos.RoutingPolicySettings(false, null, null, null, 2, null, null, null, null)
            ))
            .block();

        assertThat(created).isNotNull();
        assertThat(created.routingPolicy().isolateCodexPoolEnabled()).isTrue();
        assertThat(patched).isNotNull();
        assertThat(patched.routingPolicy().isolateCodexPoolEnabled()).isFalse();
        assertThat(patched.routingPolicy().gptUsesCodexAccountsEnabled()).isTrue();
        assertThat(patched.routingPolicy().codexMaxRetryAccounts()).isEqualTo(2);
        assertThat(patched.routingPolicy().openAiMaxRetryAccounts()).isEqualTo(3);
        assertThat(store.get(AccountSettingsService.ROUTING_POLICY_ISOLATE_CODEX)).isEqualTo("false");
        assertThat(store.get(AccountSettingsService.ROUTING_POLICY_CODEX_MAX_RETRY_ACCOUNTS)).isEqualTo("2");
    }

    @Test
    void ensureGptFastModeAllowedRejectsWhenDisabled() {
        SystemSettingRepository repository = mockRepository(new HashMap<>());
        when(repository.findById(AccountSettingsService.GPT_FAST_MODE_ENABLED)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> new AccountSettingsService(repository)
            .ensureGptFastModeAllowed()
            .block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.getCode()).isEqualTo("GPT_FAST_MODE_DISABLED");
            });
    }

    @Test
    void gptFastModeHotPathReusesCachedValue() {
        Map<String, String> store = new HashMap<>();
        store.put(AccountSettingsService.GPT_FAST_MODE_ENABLED, "true");
        SystemSettingRepository repository = mockRepository(store);
        AccountSettingsService service = new AccountSettingsService(repository);

        assertThat(service.gptFastModeEnabled().block()).isTrue();
        assertThat(service.gptFastModeEnabled().block()).isTrue();

        verify(repository, times(1)).findById(AccountSettingsService.GPT_FAST_MODE_ENABLED);
    }

    @Test
    void invalidationPreventsAnOlderLoadFromRepopulatingTheCache() {
        SystemSettingRepository repository = org.mockito.Mockito.mock(SystemSettingRepository.class);
        Sinks.One<SystemSettingEntity> staleLoad = Sinks.one();
        when(repository.findById(AccountSettingsService.GPT_FAST_MODE_ENABLED))
            .thenReturn(staleLoad.asMono(), Mono.just(setting(AccountSettingsService.GPT_FAST_MODE_ENABLED, "true")));
        AccountSettingsService service = new AccountSettingsService(repository);
        AtomicReference<Boolean> staleResult = new AtomicReference<>();

        service.gptFastModeEnabled().subscribe(staleResult::set);
        service.invalidateAllCaches();
        staleLoad.tryEmitValue(setting(AccountSettingsService.GPT_FAST_MODE_ENABLED, "false"));

        assertThat(staleResult.get()).isFalse();
        assertThat(service.gptFastModeEnabled().block()).isTrue();
        assertThat(service.gptFastModeEnabled().block()).isTrue();
        verify(repository, times(2)).findById(AccountSettingsService.GPT_FAST_MODE_ENABLED);
    }

    private SystemSettingRepository mockRepository(Map<String, String> store) {
        SystemSettingRepository repository = org.mockito.Mockito.mock(SystemSettingRepository.class);
        when(repository.acquireTransactionLock(anyString())).thenReturn(Mono.just(true));
        when(repository.findAllById(any(Iterable.class))).thenAnswer(invocation -> {
            Iterable<String> keys = invocation.getArgument(0);
            return Flux.fromIterable(keys)
                .filter(store::containsKey)
                .map(key -> setting(key, store.get(key)));
        });
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return Mono.justOrEmpty(store.get(key)).map(value -> setting(key, value));
        });
        when(repository.save(any(SystemSettingEntity.class))).thenAnswer(invocation -> {
            SystemSettingEntity entity = invocation.getArgument(0);
            store.put(entity.getSettingKey(), entity.getSettingValue());
            return Mono.just(entity);
        });
        when(repository.upsertSetting(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            store.put(key, value);
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
