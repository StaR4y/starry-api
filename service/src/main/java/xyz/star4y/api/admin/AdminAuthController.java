package xyz.star4y.api.admin;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;

@RestController
public class AdminAuthController implements AdminAuthApi {

    private final AdminUserService adminUserService;
    private final AdminAuth adminAuth;
    private final RegistrationSettingsService registrationSettingsService;
    private final EmailVerificationService emailVerificationService;

    public AdminAuthController(
        AdminUserService adminUserService,
        AdminAuth adminAuth,
        RegistrationSettingsService registrationSettingsService,
        EmailVerificationService emailVerificationService
    ) {
        this.adminUserService = adminUserService;
        this.adminAuth = adminAuth;
        this.registrationSettingsService = registrationSettingsService;
        this.emailVerificationService = emailVerificationService;
    }

    @Override
    public Mono<ApiResponse<AdminDtos.LoginResponse>> login(@Valid @RequestBody AdminDtos.LoginRequest request) {
        return adminUserService.login(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.AdminUserResponse>> me(ServerWebExchange exchange) {
        AdminPrincipal principal = adminAuth.verify(exchange);
        return adminUserService.currentUser(principal)
            .map(AdminUserMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.LoginResponse>> register(@Valid @RequestBody AdminDtos.RegisterRequest request) {
        return adminUserService.register(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.SendVerifyCodeResponse>> sendVerifyCode(
        @Valid @RequestBody AdminDtos.SendVerifyCodeRequest request
    ) {
        return emailVerificationService.sendRegistrationCode(request.email()).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.RegistrationSettingsResponse>> registrationSettings() {
        return registrationSettingsService.publicSettings().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<Void>> changePassword(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.ChangePasswordRequest request
    ) {
        AdminPrincipal principal = adminAuth.verifyAllowFirstLogin(exchange);
        return adminUserService.changePassword(principal, request).thenReturn(ApiResponse.ok(null));
    }
}
