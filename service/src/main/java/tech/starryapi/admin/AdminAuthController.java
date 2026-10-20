package tech.starryapi.admin;

import jakarta.validation.Valid;
import java.net.InetSocketAddress;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SmtpVerificationService smtpVerificationService;
    private final LoginThrottle loginThrottle;
    private tech.starryapi.config.PublicSiteUrl publicSiteUrl;

    public AdminAuthController(
        AdminUserService adminUserService,
        AdminAuth adminAuth,
        RegistrationSettingsService registrationSettingsService,
        EmailVerificationService emailVerificationService
    ) {
        this(adminUserService, adminAuth, registrationSettingsService, emailVerificationService, null, null);
    }

    public AdminAuthController(
        AdminUserService adminUserService,
        AdminAuth adminAuth,
        RegistrationSettingsService registrationSettingsService,
        EmailVerificationService emailVerificationService,
        SmtpVerificationService smtpVerificationService
    ) {
        this(adminUserService, adminAuth, registrationSettingsService, emailVerificationService, smtpVerificationService, null);
    }

    @Autowired
    public AdminAuthController(
        AdminUserService adminUserService,
        AdminAuth adminAuth,
        RegistrationSettingsService registrationSettingsService,
        EmailVerificationService emailVerificationService,
        SmtpVerificationService smtpVerificationService,
        LoginThrottle loginThrottle
    ) {
        this.loginThrottle = loginThrottle;
        this.adminUserService = adminUserService;
        this.adminAuth = adminAuth;
        this.registrationSettingsService = registrationSettingsService;
        this.emailVerificationService = emailVerificationService;
        this.smtpVerificationService = smtpVerificationService;
    }

    @Override
    public Mono<ApiResponse<AdminDtos.LoginResponse>> login(
        @Valid @RequestBody AdminDtos.LoginRequest request,
        ServerWebExchange exchange
    ) {
        if (loginThrottle != null) {
            loginThrottle.check(exchange);
        }
        return adminUserService.login(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<Void>> logout(ServerWebExchange exchange) {
        adminUserService.logout(adminAuth.presentedToken(exchange));
        return Mono.just(ApiResponse.ok(null));
    }

    @Override
    public Mono<ApiResponse<AdminDtos.AdminUserResponse>> me(ServerWebExchange exchange) {
        AdminPrincipal principal = adminAuth.verify(exchange);
        return adminUserService.currentUser(principal)
            .map(AdminUserMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AdminDtos.AdminPermissions>> permissions(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl(org.springframework.http.CacheControl.noStore());
        return adminUserService.permissions(adminAuth.verify(exchange)).map(ApiResponse::ok);
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
    public Mono<ApiResponse<AdminDtos.SmtpVerifyResponse>> verifySmtp(
        @Valid @RequestBody AdminDtos.SmtpVerifyRequest request
    ) {
        if (smtpVerificationService == null) {
            return Mono.error(new tech.starryapi.common.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "SMTP_VERIFY_UNAVAILABLE",
                "SMTP verification is unavailable"
            ));
        }
        return smtpVerificationService.verify(request == null ? null : request.token()).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<Void>> changePassword(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.ChangePasswordRequest request
    ) {
        AdminPrincipal principal = adminAuth.verifyAllowFirstLogin(exchange);
        return adminUserService.changePassword(principal, request).thenReturn(ApiResponse.ok(null));
    }

    @Autowired(required = false)
    void setPublicSiteUrl(tech.starryapi.config.PublicSiteUrl publicSiteUrl) {
        this.publicSiteUrl = publicSiteUrl;
    }

    private String registrationBaseUrl(ServerWebExchange exchange) {
        if (publicSiteUrl != null) {
            String configured = publicSiteUrl.configuredBase();
            if (configured != null) {
                return configured + "/#/login";
            }
            publicSiteUrl.warnHeaderFallback();
        }
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
