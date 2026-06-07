package xyz.star4y.api.apikey;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;

@RestController
public class ApiKeyAdminController implements ApiKeyAdminApi {

    private final ApiKeyRepository repository;
    private final ApiKeyService service;
    private final AdminAuth adminAuth;

    public ApiKeyAdminController(ApiKeyRepository repository, ApiKeyService service, AdminAuth adminAuth) {
        this.repository = repository;
        this.service = service;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<List<ApiKeyDtos.ApiKeyResponse>>> list(ServerWebExchange exchange) {
        adminAuth.verify(exchange);
        return repository.findAll()
            .map(ApiKeyMapper::toResponse)
            .collectList()
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ApiKeyDtos.CreatedApiKeyResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody ApiKeyDtos.CreateApiKeyRequest request
    ) {
        adminAuth.verify(exchange);
        return service.create(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ApiKeyDtos.ApiKeyResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String keyId,
        @RequestBody ApiKeyDtos.UpdateApiKeyRequest request
    ) {
        adminAuth.verify(exchange);
        return service.update(keyId, request)
            .map(ApiKeyMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @PathVariable String keyId) {
        adminAuth.verify(exchange);
        return service.delete(keyId).thenReturn(ApiResponse.ok(null));
    }
}
