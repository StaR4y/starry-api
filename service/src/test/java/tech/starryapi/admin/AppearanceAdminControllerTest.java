package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;

class AppearanceAdminControllerTest {

    @Test
    void patchForwardsChromeAndReturnsItOnTheResponse() {
        AppearanceSettingsService service = mock(AppearanceSettingsService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(
            new AdminPrincipal("admin-1", "admin", "ADMIN", null, false)
        );
        ArgumentCaptor<AppearanceDtos.UpdateAppearanceRequest> captor =
            ArgumentCaptor.forClass(AppearanceDtos.UpdateAppearanceRequest.class);
        when(service.update(captor.capture())).thenAnswer(invocation -> {
            AppearanceDtos.UpdateAppearanceRequest request = invocation.getArgument(0);
            AppearanceDtos.AppearanceResponse base = AppearanceSettingsService.defaults();
            return Mono.just(new AppearanceDtos.AppearanceResponse(
                base.siteName(),
                base.logoUrl(),
                base.faviconUrl(),
                base.backgroundImageUrl(),
                base.preset(),
                base.primary(),
                base.primaryMid(),
                base.accent(),
                base.success(),
                base.warning(),
                base.danger(),
                base.header(),
                base.text(),
                base.textSecondary(),
                base.textMuted(),
                base.sidebarOpacity(),
                base.surfaceOpacity(),
                base.blurPx(),
                base.backgroundBlurPx(),
                request.chrome()
            ));
        });

        WebTestClient.bindToController(new AppearanceAdminController(service, adminAuth))
            .build()
            .patch()
            .uri("/admin/appearance")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "siteName": "Starry API",
                  "primary": "#5b8def",
                  "chrome": "editorial"
                }
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data.chrome").isEqualTo("editorial");

        assertThat(captor.getValue().chrome()).isEqualTo("editorial");
    }

    @Test
    void getIncludesChromeOnThePublicAdminPayload() {
        AppearanceSettingsService service = mock(AppearanceSettingsService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        when(adminAuth.verifyAdmin(any())).thenReturn(
            new AdminPrincipal("admin-1", "admin", "ADMIN", null, false)
        );
        when(service.settings()).thenReturn(Mono.just(AppearanceSettingsService.defaults()));

        WebTestClient.bindToController(new AppearanceAdminController(service, adminAuth))
            .build()
            .get()
            .uri("/admin/appearance")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data.chrome").isEqualTo("classic");
    }
}
