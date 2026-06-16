package tech.starryapi.apikey;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class ApiKeyAdminController implements ApiKeyAdminApi {

    private static final String ADMIN_ROLE = "ADMIN";

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
        AdminPrincipal principal = adminAuth.verify(exchange);
        return (isAdmin(principal)
            ? repository.findAll()
            : repository.findAllByUserIdOrderByCreatedAtDesc(principal.userId()))
            .map(ApiKeyMapper::toResponse)
            .collectList()
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ApiKeyDtos.CreatedApiKeyResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody ApiKeyDtos.CreateApiKeyRequest request
    ) {
        AdminPrincipal principal = adminAuth.verify(exchange);
        return service.create(principal, request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<ApiKeyDtos.ApiKeyResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String keyId,
        @RequestBody ApiKeyDtos.UpdateApiKeyRequest request
    ) {
        AdminPrincipal principal = adminAuth.verify(exchange);
        return service.update(principal, keyId, request)
            .map(ApiKeyMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @PathVariable String keyId) {
        AdminPrincipal principal = adminAuth.verify(exchange);
        return service.delete(principal, keyId).thenReturn(ApiResponse.ok(null));
    }

    private boolean isAdmin(AdminPrincipal principal) {
        return principal != null && ADMIN_ROLE.equalsIgnoreCase(principal.role());
    }
}
