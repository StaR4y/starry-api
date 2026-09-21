package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

class AppearanceSettingsServiceTest {

    private SystemSettingRepository repository;
    private AppearanceSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(SystemSettingRepository.class);
        BrandingStorageService storage = mock(BrandingStorageService.class);
        service = new AppearanceSettingsService(repository, storage);
        when(repository.findAllById(any(Iterable.class))).thenReturn(Flux.empty());
        when(repository.acquireTransactionLock(any())).thenReturn(Mono.just(true));
        when(repository.upsertSettings(any(), any())).thenReturn(Mono.just(1));
        service.invalidateCache();
        AppearanceRuntime.replace(AppearanceSettingsService.defaults());
    }

    @AfterEach
    void tearDown() {
        AppearanceRuntime.replace(AppearanceSettingsService.defaults());
    }

    @Test
    void emptyStoreReturnsFactoryDefaultTheme() {
        AppearanceDtos.AppearanceResponse appearance = service.settings().block();

        assertThat(appearance).isNotNull();
        assertThat(appearance.siteName()).isEqualTo("Starry API");
        assertThat(appearance.preset()).isEqualTo(AppearanceDtos.PRESET_DEFAULT);
        assertThat(appearance.primary()).isEqualTo("#5b8def");
        assertThat(appearance.primaryMid()).isEqualTo("#7b7cff");
        assertThat(appearance.accent()).isEqualTo("#b387ff");
        assertThat(appearance.logoUrl()).isNull();
        assertThat(appearance.sidebarOpacity()).isEqualTo(0.62);
        assertThat(appearance.chrome()).isEqualTo(AppearanceDtos.CHROME_CLASSIC);
    }

    @Test
    void persistsEditorialChromeWithoutChangingColorPreset() {
        AppearanceDtos.AppearanceResponse appearance = service.update(new AppearanceDtos.UpdateAppearanceRequest(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            AppearanceDtos.CHROME_EDITORIAL
        )).block();

        assertThat(appearance).isNotNull();
        assertThat(appearance.chrome()).isEqualTo(AppearanceDtos.CHROME_EDITORIAL);
        assertThat(appearance.preset()).isEqualTo(AppearanceDtos.PRESET_DEFAULT);
        assertThat(appearance.primary()).isEqualTo("#5b8def");
    }

    @Test
    void roundTripPersistsEditorialChromeInSettingsStore() {
        Map<String, String> store = new HashMap<>();
        SystemSettingRepository repository = mockStore(store);
        BrandingStorageService storage = mock(BrandingStorageService.class);
        AppearanceSettingsService persisted = new AppearanceSettingsService(repository, storage);
        persisted.invalidateCache();

        persisted.update(new AppearanceDtos.UpdateAppearanceRequest(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            AppearanceDtos.CHROME_EDITORIAL
        )).block();
        persisted.invalidateCache();

        AppearanceDtos.AppearanceResponse loaded = persisted.settings().block();

        assertThat(store.get(AppearanceSettingsService.CHROME)).isEqualTo(AppearanceDtos.CHROME_EDITORIAL);
        assertThat(loaded).isNotNull();
        assertThat(loaded.chrome()).isEqualTo(AppearanceDtos.CHROME_EDITORIAL);
    }

    @Test
    void presetNameDoesNotRewriteColorsOrGlass() {
        AppearanceDtos.AppearanceResponse appearance = service.update(new AppearanceDtos.UpdateAppearanceRequest(
            null,
            "liquid-glass",
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null
        )).block();

        assertThat(appearance).isNotNull();
        assertThat(appearance.preset()).isEqualTo(AppearanceDtos.PRESET_CUSTOM);
        assertThat(appearance.primary()).isEqualTo("#5b8def");
        assertThat(appearance.sidebarOpacity()).isEqualTo(0.62);
        assertThat(appearance.surfaceOpacity()).isEqualTo(0.62);
        assertThat(appearance.blurPx()).isEqualTo(18);
    }

    @Test
    void resetRestoresFactoryDefaultsAndSiteName() {
        service.update(new AppearanceDtos.UpdateAppearanceRequest(
            "Custom Gate",
            AppearanceDtos.PRESET_CUSTOM,
            "#112233",
            null, null, null, null, null, null, null, null, null,
            0.3, null, null, null, null, null, null, null
        )).block();

        AppearanceDtos.AppearanceResponse restored = service.reset().block();

        assertThat(restored).isNotNull();
        assertThat(restored.siteName()).isEqualTo("Starry API");
        assertThat(restored.primary()).isEqualTo("#5b8def");
        assertThat(restored.preset()).isEqualTo(AppearanceDtos.PRESET_DEFAULT);
        assertThat(restored.chrome()).isEqualTo(AppearanceDtos.CHROME_CLASSIC);
        assertThat(AppearanceRuntime.siteName()).isEqualTo("Starry API");
        assertThat(BrandTheme.current().primary()).isEqualTo("#5b8def");
    }

    @Test
    void rejectsInvalidHexColor() {
        assertThatThrownBy(() -> service.update(new AppearanceDtos.UpdateAppearanceRequest(
            null, null, "blue", null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null
        )).block())
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("APPEARANCE_COLOR_INVALID")
            );
    }

    @Test
    void customColorMarksPresetCustomAndUpdatesMailTheme() {
        AppearanceDtos.AppearanceResponse appearance = service.update(new AppearanceDtos.UpdateAppearanceRequest(
            "Aurora",
            null,
            "#00aabb",
            "#11bbcc",
            "#22ccdd",
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null
        )).block();

        assertThat(appearance).isNotNull();
        assertThat(appearance.siteName()).isEqualTo("Aurora");
        assertThat(appearance.preset()).isEqualTo(AppearanceDtos.PRESET_CUSTOM);
        assertThat(BrandTheme.current().primary()).isEqualTo("#00aabb");
        assertThat(AppearanceRuntime.siteName()).isEqualTo("Aurora");
    }

    @Test
    void restoreDefaultFlagResetsThroughUpdate() {
        service.update(new AppearanceDtos.UpdateAppearanceRequest(
            "Aurora",
            null,
            "#00aabb",
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null
        )).block();

        AppearanceDtos.AppearanceResponse restored = service.update(new AppearanceDtos.UpdateAppearanceRequest(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, true
        )).block();

        assertThat(restored).isNotNull();
        assertThat(restored.siteName()).isEqualTo("Starry API");
        assertThat(restored.primary()).isEqualTo("#5b8def");
        assertThat(restored.preset()).isEqualTo(AppearanceDtos.PRESET_DEFAULT);
    }

    private static SystemSettingRepository mockStore(Map<String, String> store) {
        SystemSettingRepository repository = mock(SystemSettingRepository.class);
        when(repository.acquireTransactionLock(anyString())).thenReturn(Mono.just(true));
        when(repository.findAllById(any(Iterable.class))).thenAnswer(invocation -> {
            Iterable<String> keys = invocation.getArgument(0);
            return Flux.fromIterable(keys)
                .filter(store::containsKey)
                .map(key -> {
                    SystemSettingEntity entity = new SystemSettingEntity();
                    entity.setSettingKey(key);
                    entity.setSettingValue(store.get(key));
                    return entity;
                });
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
}
