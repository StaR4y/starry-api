package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

class AdminUserControllerTest {

    @Test
    void updatePassesVerifiedPrincipalToRootAuthorization() {
        AdminUserService userService = mock(AdminUserService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        AdminUserController controller = controller(userService, adminAuth);
        MockServerWebExchange exchange = exchange("/admin/users/root-1");
        AdminPrincipal principal = principal("admin-2");
        AdminDtos.UpdateAdminUserRequest request = new AdminDtos.UpdateAdminUserRequest(
            "owner@example.com",
            null,
            null,
            null,
            null
        );
        AdminUserEntity updated = user("root-1", "admin");

        when(adminAuth.verifyAdmin(exchange)).thenReturn(principal);
        when(userService.update(principal, "root-1", request)).thenReturn(Mono.just(updated));

        ApiResponse<AdminDtos.AdminUserResponse> response = controller.update(
            exchange,
            "root-1",
            request
        ).block();

        assertThat(response).isNotNull();
        verify(userService).update(principal, "root-1", request);
    }

    @Test
    void resetPasswordPassesVerifiedPrincipalToRootAuthorization() {
        AdminUserService userService = mock(AdminUserService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        AdminUserController controller = controller(userService, adminAuth);
        MockServerWebExchange exchange = exchange("/admin/users/root-1/reset-password");
        AdminPrincipal principal = principal("admin-2");
        AdminDtos.ResetPasswordResponse reset = new AdminDtos.ResetPasswordResponse(
            "root-1",
            "admin",
            "generated-password"
        );

        when(adminAuth.verifyAdmin(exchange)).thenReturn(principal);
        when(userService.resetPassword(principal, "root-1")).thenReturn(Mono.just(reset));

        ApiResponse<AdminDtos.ResetPasswordResponse> response = controller.resetPassword(
            exchange,
            "root-1"
        ).block();

        assertThat(response).isNotNull();
        verify(userService).resetPassword(principal, "root-1");
    }

    private AdminUserController controller(AdminUserService userService, AdminAuth adminAuth) {
        return new AdminUserController(
            userService,
            adminAuth,
            mock(RegistrationSettingsService.class),
            mock(InviteCodeService.class)
        );
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    private AdminPrincipal principal(String userId) {
        return new AdminPrincipal(
            userId,
            "admin",
            "ADMIN",
            Instant.now().plusSeconds(60),
            false
        );
    }

    private AdminUserEntity user(String userId, String username) {
        AdminUserEntity user = new AdminUserEntity();
        user.setUserId(userId);
        user.setUsername(username);
        user.setRole("ADMIN");
        user.setEnabled(true);
        user.setFirstLogin(false);
        return user;
    }
}
