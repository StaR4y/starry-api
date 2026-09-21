package tech.starryapi.admin;

import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.model.ModelCatalogManagementService;
import tech.starryapi.model.ModelDtos;

@RestController
@RequestMapping("/system")
public class SystemStatusController implements SystemStatusApi {

    private static final String MAINTENANCE_CODE = "SYSTEM_MAINTENANCE";

    private final DataManagementSecurityService securityService;
    private final AppearanceSettingsService appearanceSettingsService;
    private final ModelCatalogManagementService modelCatalogManagementService;

    public SystemStatusController(
        DataManagementSecurityService securityService,
        AppearanceSettingsService appearanceSettingsService,
        ModelCatalogManagementService modelCatalogManagementService
    ) {
        this.securityService = securityService;
        this.appearanceSettingsService = appearanceSettingsService;
        this.modelCatalogManagementService = modelCatalogManagementService;
    }

    @Override
    @GetMapping("/status")
    public Mono<ResponseEntity<ApiResponse<SystemStatusDtos.SystemStatusResponse>>> status() {
        return securityService.isLocked()
            .onErrorReturn(true)
            .map(maintenance -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new SystemStatusDtos.SystemStatusResponse(
                    maintenance ? "maintenance" : "operational",
                    maintenance,
                    maintenance ? MAINTENANCE_CODE : null,
                    maintenance ? "System maintenance is in progress" : "System is operational",
                    Instant.now()
                ))));
    }

    @Override
    @GetMapping("/appearance")
    public Mono<ResponseEntity<ApiResponse<AppearanceDtos.AppearanceResponse>>> appearance() {
        return appearanceSettingsService.settings()
            .map(appearance -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(appearance)));
    }

    @Override
    @GetMapping("/model-catalog-display")
    public Mono<ResponseEntity<ApiResponse<ModelDtos.ModelCatalogViewSettings>>> modelCatalogDisplay() {
        return modelCatalogManagementService.viewSettings()
            .map(view -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(view)));
    }
}
