package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.config.SourceCodeProperties;

class SystemStatusControllerTest {

    @Test
    void reportsMaintenanceWithoutExposingInternalSecurityState() {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));

        ResponseEntity<ApiResponse<SystemStatusDtos.SystemStatusResponse>> response =
            new SystemStatusController(
                securityService,
                mock(AppearanceSettingsService.class),
                mock(tech.starryapi.model.ModelCatalogManagementService.class),
                new SourceCodeProperties()
            ).status().block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().maintenance()).isTrue();
        assertThat(response.getBody().data().status()).isEqualTo("maintenance");
        assertThat(response.getBody().data().code()).isEqualTo("SYSTEM_MAINTENANCE");
    }

    @Test
    void failsClosedWhenTheLockSnapshotCannotBeRead() {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.error(new IllegalStateException("state unavailable")));

        ResponseEntity<ApiResponse<SystemStatusDtos.SystemStatusResponse>> response =
            new SystemStatusController(
                securityService,
                mock(AppearanceSettingsService.class),
                mock(tech.starryapi.model.ModelCatalogManagementService.class),
                new SourceCodeProperties()
            ).status().block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().maintenance()).isTrue();
        assertThat(response.getBody().data().code()).isEqualTo("SYSTEM_MAINTENANCE");
    }

    /** AGPL-3.0, section 13: the console learns from this public endpoint where the source of the running version is. */
    @Test
    void appearanceCarriesTheConfiguredSourceAddress() {
        AppearanceSettingsService appearanceSettings = mock(AppearanceSettingsService.class);
        when(appearanceSettings.settings()).thenReturn(Mono.just(AppearanceSettingsService.defaults()));
        SourceCodeProperties sourceCode = new SourceCodeProperties();
        sourceCode.setUrl("https://git.example.com/me/starry-api-fork");

        ResponseEntity<ApiResponse<AppearanceDtos.AppearanceResponse>> response = controller(appearanceSettings, sourceCode)
            .appearance().block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getBody().data().sourceUrl()).isEqualTo("https://git.example.com/me/starry-api-fork");
        assertThat(response.getBody().data().siteName()).isEqualTo(AppearanceDtos.DEFAULT_SITE_NAME);
    }

    @Test
    void appearanceFallsBackToTheUpstreamSourceWhenTheSettingIsUnusable() {
        AppearanceSettingsService appearanceSettings = mock(AppearanceSettingsService.class);
        when(appearanceSettings.settings()).thenReturn(Mono.just(AppearanceSettingsService.defaults()));
        SourceCodeProperties sourceCode = new SourceCodeProperties();
        sourceCode.setUrl("javascript:alert(1)");

        ResponseEntity<ApiResponse<AppearanceDtos.AppearanceResponse>> response = controller(appearanceSettings, sourceCode)
            .appearance().block();

        assertThat(response.getBody().data().sourceUrl()).isEqualTo(SourceCodeProperties.DEFAULT_URL);
    }

    private static SystemStatusController controller(AppearanceSettingsService appearanceSettings, SourceCodeProperties sourceCode) {
        return new SystemStatusController(
            mock(DataManagementSecurityService.class),
            appearanceSettings,
            mock(tech.starryapi.model.ModelCatalogManagementService.class),
            sourceCode
        );
    }
}
