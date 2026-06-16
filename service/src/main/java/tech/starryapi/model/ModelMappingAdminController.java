package tech.starryapi.model;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class ModelMappingAdminController implements ModelMappingAdminApi {

    private final ModelMappingService service;
    private final AdminAuth adminAuth;

    public ModelMappingAdminController(ModelMappingService service, AdminAuth adminAuth) {
        this.service = service;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<List<ModelDtos.MappingResponse>>> list(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.list().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.MappingResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody ModelDtos.CreateMappingRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.create(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ModelDtos.MappingResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String mappingId,
        @RequestBody ModelDtos.UpdateMappingRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.update(mappingId, request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @PathVariable String mappingId) {
        adminAuth.verifyAdmin(exchange);
        return service.delete(mappingId).thenReturn(ApiResponse.ok(null));
    }
}
