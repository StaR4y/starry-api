package xyz.star4y.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import xyz.star4y.api.admin.AdminPrincipal;
import xyz.star4y.api.admin.AdminSessionService;
import xyz.star4y.api.config.ProxyProperties;

class AdminAuthTest {

    @Test
    void blocksFirstLoginSessionForAdminApis() {
        AdminSessionService sessionService = Mockito.mock(AdminSessionService.class);
        when(sessionService.verify("adm-first"))
            .thenReturn(new AdminPrincipal("user-1", "admin", "ADMIN", Instant.now().plusSeconds(60), true));
        AdminAuth auth = new AdminAuth(new ProxyProperties(), sessionService);
        MockServerWebExchange exchange = exchange("adm-first");

        assertThat(auth.verifyAllowFirstLogin(exchange).firstLogin()).isTrue();
        assertThatThrownBy(() -> auth.verify(exchange))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(exception.getCode()).isEqualTo("FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED");
            });
    }

    @Test
    void allowsChangedPasswordSessionForAdminApis() {
        AdminSessionService sessionService = Mockito.mock(AdminSessionService.class);
        when(sessionService.verify("adm-ready"))
            .thenReturn(new AdminPrincipal("user-1", "admin", "ADMIN", Instant.now().plusSeconds(60), false));
        AdminAuth auth = new AdminAuth(new ProxyProperties(), sessionService);

        assertThat(auth.verify(exchange("adm-ready")).username()).isEqualTo("admin");
    }

    @Test
    void rejectsUserRoleForAdminOnlyApis() {
        AdminSessionService sessionService = Mockito.mock(AdminSessionService.class);
        when(sessionService.verify("adm-user"))
            .thenReturn(new AdminPrincipal("user-2", "user", "USER", Instant.now().plusSeconds(60), false));
        AdminAuth auth = new AdminAuth(new ProxyProperties(), sessionService);

        assertThatThrownBy(() -> auth.verifyAdmin(exchange("adm-user")))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(exception.getCode()).isEqualTo("ADMIN_ROLE_REQUIRED");
            });
    }

    private MockServerWebExchange exchange(String token) {
        return MockServerWebExchange.from(
            MockServerHttpRequest.get("/admin/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        );
    }
}
