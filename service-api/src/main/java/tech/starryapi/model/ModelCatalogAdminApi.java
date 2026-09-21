package tech.starryapi.model;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin/models")
public interface ModelCatalogAdminApi {

    @GetMapping
    Mono<ApiResponse<List<ModelDtos.ManagedModelResponse>>> list(ServerWebExchange exchange);

    @PutMapping
    Mono<ApiResponse<ModelDtos.ManagedModelResponse>> upsert(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpsertManagedModelRequest request
    );

    @PostMapping(path = "/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Mono<ApiResponse<ModelDtos.ModelIconUploadResponse>> uploadIcon(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file
    );

    @DeleteMapping
    Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @RequestParam String modelId);

    @DeleteMapping("/vendors")
    Mono<ApiResponse<Void>> deleteVendor(ServerWebExchange exchange, @RequestParam String provider);

    @PutMapping("/order")
    Mono<ApiResponse<Void>> reorder(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.ReorderManagedModelsRequest request
    );

    @GetMapping("/display")
    Mono<ApiResponse<ModelDtos.ModelCatalogDisplaySettings>> getDisplay(ServerWebExchange exchange);

    @PatchMapping("/display")
    Mono<ApiResponse<ModelDtos.ModelCatalogDisplaySettings>> updateDisplay(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.UpdateModelCatalogDisplayRequest request
    );
}
