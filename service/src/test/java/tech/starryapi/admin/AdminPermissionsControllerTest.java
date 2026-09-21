package tech.starryapi.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.GlobalExceptionHandler;
import tech.starryapi.config.ProxyProperties;

class AdminPermissionsControllerTest {
    private AdminUserService users;
    private AdminSessionService sessions;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        var properties = new ProxyProperties();
        sessions = new AdminSessionService(properties);
        users = mock(AdminUserService.class);
        client = WebTestClient.bindToController(new AdminAuthController(users,
            new AdminAuth(properties, sessions), mock(RegistrationSettingsService.class),
            mock(EmailVerificationService.class)))
            .controllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void permissionsRequireALoggedInSession() {
        client.get().uri("/auth/permissions").exchange().expectStatus().isUnauthorized();
        verify(users, never()).permissions(any());
    }

    @Test
    void permissionsAreNotCachedAndReflectTheServerDecision() {
        when(users.permissions(any())).thenReturn(Mono.just(new AdminDtos.AdminPermissions(true)),
            Mono.just(new AdminDtos.AdminPermissions(false)));
        String token = session(false);
        for (boolean root : new boolean[] { true, false }) {
            client.get().uri("/auth/permissions").headers(headers -> headers.setBearerAuth(token))
                .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.data.root").isEqualTo(root);
        }
    }

    @Test
    void initialPasswordMustBeChangedBeforePermissionsAreGranted() {
        client.get().uri("/auth/permissions").headers(headers -> headers.setBearerAuth(session(true)))
            .exchange().expectStatus().isForbidden().expectBody()
            .jsonPath("$.error.code").isEqualTo("FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED");
        verify(users, never()).permissions(any());
    }

    private String session(boolean firstLogin) {
        var user = new AdminUserEntity();
        user.setUserId("root-1");
        user.setUsername("admin");
        user.setRole("ADMIN");
        user.setFirstLogin(firstLogin);
        return sessions.create(user).token();
    }
}
