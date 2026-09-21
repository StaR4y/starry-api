package tech.starryapi.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class AdminUserController implements AdminUserApi {

    private final AdminUserService adminUserService;
    private final AdminAuth adminAuth;
    private final RegistrationSettingsService registrationSettingsService;
    private final InviteCodeService inviteCodeService;
    private final SmtpVerificationService smtpVerificationService;

    public AdminUserController(
        AdminUserService adminUserService,
        AdminAuth adminAuth,
        RegistrationSettingsService registrationSettingsService,
        InviteCodeService inviteCodeService
    ) {
        this(adminUserService, adminAuth, registrationSettingsService, inviteCodeService, null);
    }

    @Autowired
    public AdminUserController(
        AdminUserService adminUserService,
        AdminAuth adminAuth,
        RegistrationSettingsService registrationSettingsService,
        InviteCodeService inviteCodeService,
        SmtpVerificationService smtpVerificationService
    ) {
        this.adminUserService = adminUserService;
        this.adminAuth = adminAuth;
        this.registrationSettingsService = registrationSettingsService;
        this.inviteCodeService = inviteCodeService;
        this.smtpVerificationService = smtpVerificationService;
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
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return adminUserService.update(principal, userId, request)
            .map(AdminUserMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.ResetPasswordResponse>> resetPassword(
        ServerWebExchange exchange,
        @PathVariable String userId
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return adminUserService.resetPassword(principal, userId).map(ApiResponse::ok);
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
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return registrationSettingsService.update(request)
            .flatMap(settings -> smtpVerificationService == null
                ? Mono.just(settings)
                : smtpVerificationService.afterSave(settings, request, principal, smtpVerifyBaseUrl(exchange)))
            .map(ApiResponse::ok);
    }

    private String smtpVerifyBaseUrl(ServerWebExchange exchange) {
        String proto = firstHeader(exchange, "X-Forwarded-Proto", exchange.getRequest().getURI().getScheme());
        String host = firstHeader(exchange, "X-Forwarded-Host", exchange.getRequest().getHeaders().getFirst(HttpHeaders.HOST));
        if (host == null || host.isBlank()) {
            return "/#/smtp-verify";
        }
        String prefix = firstHeader(exchange, "X-Forwarded-Prefix", "");
        if (prefix == null) {
            prefix = "";
        }
        prefix = prefix.trim();
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String scheme = proto == null || proto.isBlank() ? "http" : proto.trim();
        return scheme + "://" + host.trim() + prefix + "/#/smtp-verify";
    }

    private String firstHeader(ServerWebExchange exchange, String name, String fallback) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        return value == null || value.isBlank() ? fallback : value.trim();
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
