package xyz.star4y.api.admin;

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
public class AdminUserController implements AdminUserApi {

    private final AdminUserService adminUserService;
    private final AdminAuth adminAuth;
    private final RegistrationSettingsService registrationSettingsService;
    private final InviteCodeService inviteCodeService;

    public AdminUserController(
        AdminUserService adminUserService,
        AdminAuth adminAuth,
        RegistrationSettingsService registrationSettingsService,
        InviteCodeService inviteCodeService
    ) {
        this.adminUserService = adminUserService;
        this.adminAuth = adminAuth;
        this.registrationSettingsService = registrationSettingsService;
        this.inviteCodeService = inviteCodeService;
    }

    @Override
    public Mono<ApiResponse<List<AdminDtos.AdminUserResponse>>> list(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return adminUserService.list()
            .map(AdminUserMapper::toResponse)
            .collectList()
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.AdminUserCreatedResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.CreateAdminUserRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return adminUserService.create(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.AdminUserResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String userId,
        @RequestBody AdminDtos.UpdateAdminUserRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return adminUserService.update(userId, request)
            .map(AdminUserMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.ResetPasswordResponse>> resetPassword(
        ServerWebExchange exchange,
        @PathVariable String userId
    ) {
        adminAuth.verifyAdmin(exchange);
        return adminUserService.resetPassword(userId).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.RegistrationSettingsResponse>> registrationSettings(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return registrationSettingsService.settings().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.RegistrationSettingsResponse>> updateRegistrationSettings(
        ServerWebExchange exchange,
        AdminDtos.UpdateRegistrationSettingsRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return registrationSettingsService.update(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<List<AdminDtos.InviteCodeResponse>>> inviteCodes(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return inviteCodeService.list()
            .collectList()
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.InviteCodeResponse>> createInviteCode(
        ServerWebExchange exchange,
        AdminDtos.CreateInviteCodeRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return inviteCodeService.create(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.CreateInviteCodesResponse>> createInviteCodes(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.CreateInviteCodeRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return inviteCodeService.createBatch(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.InviteCodeResponse>> revokeInviteCode(
        ServerWebExchange exchange,
        String codeId
    ) {
        adminAuth.verifyAdmin(exchange);
        return inviteCodeService.revoke(codeId).map(ApiResponse::ok);
    }
}
