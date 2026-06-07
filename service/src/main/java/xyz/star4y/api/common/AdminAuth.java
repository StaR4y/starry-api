package xyz.star4y.api.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import xyz.star4y.api.admin.AdminPrincipal;
import xyz.star4y.api.admin.AdminSessionService;
import xyz.star4y.api.config.ProxyProperties;

@Component
public class AdminAuth {

    private static final String ADMIN_ROLE = "ADMIN";

    private final ProxyProperties properties;
    private final AdminSessionService sessionService;

    public AdminAuth(ProxyProperties properties, AdminSessionService sessionService) {
        this.properties = properties;
        this.sessionService = sessionService;
    }

    public AdminPrincipal verify(ServerWebExchange exchange) {
        AdminPrincipal principal = verifyAllowFirstLogin(exchange);
        if (principal.firstLogin()) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED",
                "Login succeeded, but the default admin password must be changed with POST /auth/password before using admin APIs"
            );
        }
        return principal;
    }

    public AdminPrincipal verifyAdmin(ServerWebExchange exchange) {
        AdminPrincipal principal = verify(exchange);
        if (!ADMIN_ROLE.equalsIgnoreCase(principal.role())) {
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "ADMIN_ROLE_REQUIRED",
                "Administrator role is required"
            );
        }
        return principal;
    }

    public AdminPrincipal verifyAllowFirstLogin(ServerWebExchange exchange) {
        String configured = properties.getAdminToken();
        String provided = exchange.getRequest().getHeaders().getFirst("X-Admin-Token");
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (provided == null && authorization != null && authorization.startsWith("Bearer ")) {
            provided = authorization.substring("Bearer ".length());
        }

        if (provided != null && !provided.isBlank()) {
            if (configured != null && !configured.isBlank() && configured.equals(provided)) {
                return new AdminPrincipal("env-admin", "env-admin", "ADMIN", null, false);
            }
            return sessionService.verify(provided);
        }

        throw new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Missing admin credentials");
    }
}
