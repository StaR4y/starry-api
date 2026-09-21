package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

class SystemStatusControllerTest {

    @Test
    void reportsMaintenanceWithoutExposingInternalSecurityState() {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));

        ResponseEntity<ApiResponse<SystemStatusDtos.SystemStatusResponse>> response =
            new SystemStatusController(
                securityService,
                mock(AppearanceSettingsService.class),
                mock(tech.starryapi.model.ModelCatalogManagementService.class)
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
                mock(tech.starryapi.model.ModelCatalogManagementService.class)
            ).status().block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().maintenance()).isTrue();
        assertThat(response.getBody().data().code()).isEqualTo("SYSTEM_MAINTENANCE");
    }
}
