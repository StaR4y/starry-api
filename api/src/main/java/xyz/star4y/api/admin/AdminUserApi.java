package xyz.star4y.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiResponse;

@RequestMapping("/admin/users")
public interface AdminUserApi {

    @GetMapping
    Mono<ApiResponse<List<AdminDtos.AdminUserResponse>>> list(ServerWebExchange exchange);

    @PostMapping
    Mono<ApiResponse<AdminDtos.AdminUserCreatedResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody AdminDtos.CreateAdminUserRequest request
    );

    @PatchMapping("/{userId}")
    Mono<ApiResponse<AdminDtos.AdminUserResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String userId,
        @RequestBody AdminDtos.UpdateAdminUserRequest request
    );

    @PostMapping("/{userId}/reset-password")
    Mono<ApiResponse<AdminDtos.ResetPasswordResponse>> resetPassword(
        ServerWebExchange exchange,
        @PathVariable String userId
    );

    @GetMapping("/registration-settings")
    Mono<ApiResponse<AdminDtos.RegistrationSettingsResponse>> registrationSettings(ServerWebExchange exchange);

    @PatchMapping("/registration-settings")
    Mono<ApiResponse<AdminDtos.RegistrationSettingsResponse>> updateRegistrationSettings(
        ServerWebExchange exchange,
        @RequestBody AdminDtos.UpdateRegistrationSettingsRequest request
    );
}
