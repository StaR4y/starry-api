package tech.starryapi.account;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
public class ChatGptOAuthAdminController implements ChatGptOAuthAdminApi {

    private final OpenAiOAuthLoginService loginService;
    private final AdminAuth adminAuth;

    public ChatGptOAuthAdminController(OpenAiOAuthLoginService loginService, AdminAuth adminAuth) {
        this.loginService = loginService;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<AccountDtos.ChatGptOAuthStartResponse>> start(
        ServerWebExchange exchange,
        @Valid @RequestBody AccountDtos.ChatGptOAuthStartRequest request
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return loginService.start(principal.userId(), request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.ChatGptOAuthStatusResponse>> poll(
        ServerWebExchange exchange,
        @PathVariable String loginId
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return loginService.poll(principal.userId(), loginId).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.ChatGptOAuthStatusResponse>> cancel(
        ServerWebExchange exchange,
        @PathVariable String loginId
    ) {
        AdminPrincipal principal = adminAuth.verifyAdmin(exchange);
        return Mono.just(ApiResponse.ok(loginService.cancel(principal.userId(), loginId)));
    }
}
