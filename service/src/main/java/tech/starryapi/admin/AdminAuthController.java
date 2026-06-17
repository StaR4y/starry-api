package tech.starryapi.admin;

import jakarta.validation.Valid;
import java.net.InetSocketAddress;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

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
    public Mono<ApiResponse<AdminDtos.LoginResponse>> register(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.RegisterRequest request
    ) {
        return adminUserService.register(request, clientIp(exchange)).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.SendVerifyCodeResponse>> sendVerifyCode(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.SendVerifyCodeRequest request
    ) {
        return emailVerificationService.sendRegistrationLink(
            request.email(),
            request.turnstileToken(),
            request.affiliateCode(),
            registrationBaseUrl(exchange),
            clientIp(exchange)
        ).map(ApiResponse::ok);
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

    private String registrationBaseUrl(ServerWebExchange exchange) {
        String proto = firstHeader(exchange, "X-Forwarded-Proto", exchange.getRequest().getURI().getScheme());
        String host = firstHeader(exchange, "X-Forwarded-Host", exchange.getRequest().getHeaders().getFirst("Host"));
        if (host.isBlank()) {
            return "/#/login";
        }
        String prefix = normalizePrefix(firstHeader(exchange, "X-Forwarded-Prefix", ""));
        return clean(proto, "http") + "://" + host + prefix + "/#/login";
    }

    private String clientIp(ServerWebExchange exchange) {
        String forwarded = firstHeader(exchange, "X-Forwarded-For", "");
        if (!forwarded.isBlank()) {
            return forwarded;
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress == null || remoteAddress.getAddress() == null
            ? ""
            : remoteAddress.getAddress().getHostAddress();
    }

    private String firstHeader(ServerWebExchange exchange, String header, String fallback) {
        String value = exchange.getRequest().getHeaders().getFirst(header);
        if (value == null || value.isBlank()) {
            return clean(fallback, "");
        }
        int comma = value.indexOf(',');
        return clean(comma >= 0 ? value.substring(0, comma) : value, "");
    }

    private String normalizePrefix(String prefix) {
        String cleaned = clean(prefix, "");
        if (cleaned.isBlank() || "/".equals(cleaned)) {
            return "";
        }
        if (!cleaned.startsWith("/")) {
            cleaned = "/" + cleaned;
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
