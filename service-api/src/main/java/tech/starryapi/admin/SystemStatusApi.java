package tech.starryapi.admin;

import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.model.ModelDtos;

public interface SystemStatusApi {

    Mono<ResponseEntity<ApiResponse<SystemStatusDtos.SystemStatusResponse>>> status();

    Mono<ResponseEntity<ApiResponse<AppearanceDtos.AppearanceResponse>>> appearance();

    Mono<ResponseEntity<ApiResponse<ModelDtos.ModelCatalogViewSettings>>> modelCatalogDisplay();
}
