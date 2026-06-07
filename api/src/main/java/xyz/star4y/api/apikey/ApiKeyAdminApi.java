package xyz.star4y.api.apikey;

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

@RequestMapping("/admin/api-keys")
public interface ApiKeyAdminApi {

    @GetMapping
    Mono<ApiResponse<List<ApiKeyDtos.ApiKeyResponse>>> list(ServerWebExchange exchange);

    @PostMapping
    Mono<ApiResponse<ApiKeyDtos.CreatedApiKeyResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody ApiKeyDtos.CreateApiKeyRequest request
    );

    @PatchMapping("/{keyId}")
    Mono<ApiResponse<ApiKeyDtos.ApiKeyResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String keyId,
        @RequestBody ApiKeyDtos.UpdateApiKeyRequest request
    );

    @DeleteMapping("/{keyId}")
    Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @PathVariable String keyId);
}
