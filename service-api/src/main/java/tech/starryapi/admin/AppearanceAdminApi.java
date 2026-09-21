package tech.starryapi.admin;

import jakarta.validation.Valid;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

public interface AppearanceAdminApi {

    Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> get(ServerWebExchange exchange);

    Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> update(
        ServerWebExchange exchange,
        @Valid AppearanceDtos.UpdateAppearanceRequest request
    );

    Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadLogo(
        ServerWebExchange exchange,
        Mono<FilePart> file
    );

    Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadFavicon(
        ServerWebExchange exchange,
        Mono<FilePart> file
    );

    Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadBackground(
        ServerWebExchange exchange,
        Mono<FilePart> file
    );

    Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteLogo(ServerWebExchange exchange);

    Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteFavicon(ServerWebExchange exchange);

    Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteBackground(ServerWebExchange exchange);
}
