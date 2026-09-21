package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.AdminAuth;

class ChatGptOAuthAdminControllerTest {

    @Test
    void startBindsAuthorizationToVerifiedAdministrator() {
        OpenAiOAuthLoginService loginService = mock(OpenAiOAuthLoginService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        ChatGptOAuthAdminController controller = new ChatGptOAuthAdminController(loginService, adminAuth);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/admin/accounts/chatgpt-oauth/start")
        );
        AdminPrincipal principal = new AdminPrincipal("admin-1", "admin", "ADMIN", null, false);
        AccountDtos.ChatGptOAuthStartRequest request = new AccountDtos.ChatGptOAuthStartRequest(null);
        AccountDtos.ChatGptOAuthStartResponse started = new AccountDtos.ChatGptOAuthStartResponse(
            "login-1",
            "https://auth.openai.com/codex/device",
            "ABCD-EFGH",
            Instant.parse("2026-09-08T00:15:00Z"),
            5
        );
        when(adminAuth.verifyAdmin(exchange)).thenReturn(principal);
        when(loginService.start("admin-1", request)).thenReturn(Mono.just(started));

        var response = controller.start(exchange, request).block();

        assertThat(response).isNotNull();
        assertThat(response.data()).isEqualTo(started);
        verify(adminAuth).verifyAdmin(exchange);
        verify(loginService).start("admin-1", request);
    }

    @Test
    void exposesDeviceAuthorizationRoutesFromApiContract() {
        OpenAiOAuthLoginService loginService = mock(OpenAiOAuthLoginService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        ChatGptOAuthAdminController controller = new ChatGptOAuthAdminController(loginService, adminAuth);
        AdminPrincipal principal = new AdminPrincipal("admin-1", "admin", "ADMIN", null, false);
        AccountDtos.ChatGptOAuthStartResponse started = new AccountDtos.ChatGptOAuthStartResponse(
            "login-1",
            "https://auth.openai.com/codex/device",
            "ABCD-EFGH",
            Instant.parse("2026-09-08T00:15:00Z"),
            5
        );
        AccountDtos.ChatGptOAuthStatusResponse pending = new AccountDtos.ChatGptOAuthStatusResponse(
            "login-1",
            "PENDING",
            "Waiting",
            5,
            null
        );
        when(adminAuth.verifyAdmin(any())).thenReturn(principal);
        when(loginService.start("admin-1", new AccountDtos.ChatGptOAuthStartRequest(null)))
            .thenReturn(Mono.just(started));
        when(loginService.poll("admin-1", "login-1")).thenReturn(Mono.just(pending));
        when(loginService.cancel("admin-1", "login-1")).thenReturn(pending);
        WebTestClient client = WebTestClient.bindToController(controller).build();

        client.post()
            .uri("/admin/accounts/chatgpt-oauth/start")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data.loginId").isEqualTo("login-1")
            .jsonPath("$.data.userCode").isEqualTo("ABCD-EFGH");
        client.post()
            .uri("/admin/accounts/chatgpt-oauth/login-1/poll")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data.status").isEqualTo("PENDING");
        client.delete()
            .uri("/admin/accounts/chatgpt-oauth/login-1")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data.loginId").isEqualTo("login-1");
    }
}
