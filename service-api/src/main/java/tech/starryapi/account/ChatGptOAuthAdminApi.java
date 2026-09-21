package tech.starryapi.account;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin/accounts/chatgpt-oauth")
public interface ChatGptOAuthAdminApi {

    @PostMapping("/start")
    Mono<ApiResponse<AccountDtos.ChatGptOAuthStartResponse>> start(
        ServerWebExchange exchange,
        @Valid @RequestBody AccountDtos.ChatGptOAuthStartRequest request
    );

    @PostMapping("/{loginId}/poll")
    Mono<ApiResponse<AccountDtos.ChatGptOAuthStatusResponse>> poll(
        ServerWebExchange exchange,
        @PathVariable String loginId
    );

    @DeleteMapping("/{loginId}")
    Mono<ApiResponse<AccountDtos.ChatGptOAuthStatusResponse>> cancel(
        ServerWebExchange exchange,
        @PathVariable String loginId
    );
}
