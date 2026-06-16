package tech.starryapi.admin;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/auth")
public interface AdminAuthApi {

    @PostMapping("/login")
    Mono<ApiResponse<AdminDtos.LoginResponse>> login(@Valid @RequestBody AdminDtos.LoginRequest request);

    @GetMapping("/me")
    Mono<ApiResponse<AdminDtos.AdminUserResponse>> me(ServerWebExchange exchange);

    @PostMapping("/register")
    Mono<ApiResponse<AdminDtos.LoginResponse>> register(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.RegisterRequest request
    );

    @PostMapping("/send-verify-code")
    Mono<ApiResponse<AdminDtos.SendVerifyCodeResponse>> sendVerifyCode(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.SendVerifyCodeRequest request
    );

    @GetMapping("/registration-settings")
    Mono<ApiResponse<AdminDtos.RegistrationSettingsResponse>> registrationSettings();

    @PostMapping("/password")
    Mono<ApiResponse<Void>> changePassword(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.ChangePasswordRequest request
    );
}
