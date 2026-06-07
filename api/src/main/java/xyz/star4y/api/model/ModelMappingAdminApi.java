package xyz.star4y.api.model;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiResponse;

@RequestMapping("/admin/model-mappings")
public interface ModelMappingAdminApi {

    @GetMapping
    Mono<ApiResponse<List<ModelDtos.MappingResponse>>> list(ServerWebExchange exchange);

    @PostMapping
    Mono<ApiResponse<ModelDtos.MappingResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.CreateMappingRequest request
    );

    @PatchMapping("/{mappingId}")
    Mono<ApiResponse<ModelDtos.MappingResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String mappingId,
        @RequestBody ModelDtos.UpdateMappingRequest request
    );

    @DeleteMapping("/{mappingId}")
    Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @PathVariable String mappingId);
}
