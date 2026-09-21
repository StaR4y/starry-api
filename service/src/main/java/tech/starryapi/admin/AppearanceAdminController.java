package tech.starryapi.admin;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
@RequestMapping("/admin/appearance")
public class AppearanceAdminController implements AppearanceAdminApi {

    private final AppearanceSettingsService appearanceSettingsService;
    private final AdminAuth adminAuth;

    public AppearanceAdminController(
        AppearanceSettingsService appearanceSettingsService,
        AdminAuth adminAuth
    ) {
        this.appearanceSettingsService = appearanceSettingsService;
        this.adminAuth = adminAuth;
    }

    @Override
    @GetMapping
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> get(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return appearanceSettingsService.settings().map(ApiResponse::ok);
    }

    @Override
    @PatchMapping
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> update(
        ServerWebExchange exchange,
        @Valid @RequestBody AppearanceDtos.UpdateAppearanceRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return appearanceSettingsService.update(request).map(ApiResponse::ok);
    }

    @Override
    @PostMapping(path = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadLogo(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file
    ) {
        adminAuth.verifyAdmin(exchange);
        return appearanceSettingsService.upload("logo", file).map(ApiResponse::ok);
    }

    @Override
    @PostMapping(path = "/favicon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadFavicon(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file
    ) {
        adminAuth.verifyAdmin(exchange);
        return appearanceSettingsService.upload("favicon", file).map(ApiResponse::ok);
    }

    @Override
    @PostMapping(path = "/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadBackground(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file
    ) {
        adminAuth.verifyAdmin(exchange);
        return appearanceSettingsService.upload("background", file).map(ApiResponse::ok);
    }

    @Override
    @DeleteMapping("/logo")
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteLogo(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return appearanceSettingsService.clearAsset("logo").map(ApiResponse::ok);
    }

    @Override
    @DeleteMapping("/favicon")
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteFavicon(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return appearanceSettingsService.clearAsset("favicon").map(ApiResponse::ok);
    }

    @Override
    @DeleteMapping("/background")
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteBackground(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return appearanceSettingsService.clearAsset("background").map(ApiResponse::ok);
    }
}
