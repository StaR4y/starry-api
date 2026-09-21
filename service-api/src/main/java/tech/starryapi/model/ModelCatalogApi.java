package tech.starryapi.model;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Mono;

public interface ModelCatalogApi {

    @GetMapping({"/v1/models", "/models"})
    Mono<ResponseEntity<ModelDtos.ModelListResponse>> models();
}
