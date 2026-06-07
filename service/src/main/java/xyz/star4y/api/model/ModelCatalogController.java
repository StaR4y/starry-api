package xyz.star4y.api.model;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ModelCatalogController implements ModelCatalogApi {

    private final ModelCatalogProvider modelCatalog;

    public ModelCatalogController(ModelCatalogProvider modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    @Override
    public Mono<ResponseEntity<ModelDtos.ModelListResponse>> models() {
        return Mono.just(ResponseEntity.ok(modelCatalog.modelList(Instant.now().getEpochSecond())));
    }
}
