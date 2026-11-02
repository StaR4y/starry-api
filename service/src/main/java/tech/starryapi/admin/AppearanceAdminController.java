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
import tech.starryapi.config.SourceCodeProperties;

@RestController
@RequestMapping("/admin/appearance")
public class AppearanceAdminController implements AppearanceAdminApi {

    private final AppearanceSettingsService appearanceSettingsService;
    private final AdminAuth adminAuth;
    private final SourceCodeProperties sourceCode;

    public AppearanceAdminController(
        AppearanceSettingsService appearanceSettingsService,
        AdminAuth adminAuth,
        SourceCodeProperties sourceCode
    ) {
        this.appearanceSettingsService = appearanceSettingsService;
        this.adminAuth = adminAuth;
        this.sourceCode = sourceCode;
    }

    /** The console replaces its public appearance with what an admin call returns, so these carry the source address too. */
    private Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> respond(Mono<AppearanceDtos.AppearanceResponse> appearance) {
        return appearance.map(value -> ApiResponse.ok(value.withSourceUrl(sourceCode.effectiveUrl())));
    }

    @Override
    @GetMapping
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> get(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return respond(appearanceSettingsService.settings());
    }

    @Override
    @PatchMapping
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> update(
        ServerWebExchange exchange,
        @Valid @RequestBody AppearanceDtos.UpdateAppearanceRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return respond(appearanceSettingsService.update(request));
    }

    @Override
    @PostMapping(path = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadLogo(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file
    ) {
        adminAuth.verifyAdmin(exchange);
        return respond(appearanceSettingsService.upload("logo", file));
    }

    @Override
    @PostMapping(path = "/favicon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadFavicon(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file
    ) {
        adminAuth.verifyAdmin(exchange);
        return respond(appearanceSettingsService.upload("favicon", file));
    }

    @Override
    @PostMapping(path = "/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> uploadBackground(
        ServerWebExchange exchange,
        @RequestPart("file") Mono<FilePart> file
    ) {
        adminAuth.verifyAdmin(exchange);
        return respond(appearanceSettingsService.upload("background", file));
    }

    @Override
    @DeleteMapping("/logo")
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteLogo(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return respond(appearanceSettingsService.clearAsset("logo"));
    }

    @Override
    @DeleteMapping("/favicon")
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteFavicon(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return respond(appearanceSettingsService.clearAsset("favicon"));
    }

    @Override
    @DeleteMapping("/background")
    public Mono<ApiResponse<AppearanceDtos.AppearanceResponse>> deleteBackground(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return respond(appearanceSettingsService.clearAsset("background"));
    }
}
