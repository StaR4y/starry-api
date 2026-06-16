package tech.starryapi.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
    ) {
    }

    public record LoginResponse(
        String tokenType,
        String accessToken,
        Instant expiresAt,
        AdminUserResponse user
    ) {
    }

    public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank String newPassword
    ) {
    }

    public record RegisterRequest(
        @NotBlank String username,
        @Email @NotBlank String email,
        String displayName,
        @NotBlank @Size(min = 6) String password,
        String verifyCode,
        String inviteCode,
        String emailVerificationToken,
        String turnstileToken
    ) {
        public RegisterRequest(
            String username,
            String email,
            String displayName,
            String password,
            String verifyCode,
            String inviteCode
        ) {
            this(username, email, displayName, password, verifyCode, inviteCode, null, null);
        }
    }

    public record SendVerifyCodeRequest(
        @Email @NotBlank String email,
        String turnstileToken
    ) {
        public SendVerifyCodeRequest(String email) {
            this(email, null);
        }
    }

    public record SendVerifyCodeResponse(
        String message,
        Integer countdown
    ) {
    }

    public record CreateAdminUserRequest(
        @NotBlank String username,
        @Email String email,
        String displayName,
        String role,
        BigDecimal balance,
        String password
    ) {
    }

    public record UpdateAdminUserRequest(
        @Email String email,
        String displayName,
        String role,
        BigDecimal balance,
        Boolean enabled
    ) {
    }

    public record AdminUserCreatedResponse(
        AdminUserResponse user,
        String password
    ) {
    }

    public record ResetPasswordResponse(
        String userId,
        String username,
        String password
    ) {
    }

    public record RegistrationSettingsResponse(
        Boolean registrationEnabled,
        Boolean emailVerifyEnabled,
        Boolean inviteCodeEnabled,
        List<String> registrationEmailSuffixWhitelist,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        Boolean smtpPasswordConfigured,
        String smtpFromEmail,
        String smtpFromName,
        Boolean smtpUseTls,
        String smtpSecurity,
        Boolean turnstileEnabled,
        String turnstileSiteKey,
        Boolean turnstileSecretConfigured
    ) {
        public RegistrationSettingsResponse(
            Boolean registrationEnabled,
            Boolean emailVerifyEnabled,
            Boolean inviteCodeEnabled,
            List<String> registrationEmailSuffixWhitelist,
            String smtpHost,
            Integer smtpPort,
            String smtpUsername,
            Boolean smtpPasswordConfigured,
            String smtpFromEmail,
            String smtpFromName,
            Boolean smtpUseTls,
            String smtpSecurity
        ) {
            this(
                registrationEnabled,
                emailVerifyEnabled,
                inviteCodeEnabled,
                registrationEmailSuffixWhitelist,
                smtpHost,
                smtpPort,
                smtpUsername,
                smtpPasswordConfigured,
                smtpFromEmail,
                smtpFromName,
                smtpUseTls,
                smtpSecurity,
                false,
                null,
                false
            );
        }

        public RegistrationSettingsResponse {
            registrationEmailSuffixWhitelist = registrationEmailSuffixWhitelist == null
                ? List.of()
                : List.copyOf(registrationEmailSuffixWhitelist);
        }
    }

    public record UpdateRegistrationSettingsRequest(
        Boolean registrationEnabled,
        Boolean emailVerifyEnabled,
        Boolean inviteCodeEnabled,
        List<String> registrationEmailSuffixWhitelist,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String smtpPassword,
        String smtpFromEmail,
        String smtpFromName,
        Boolean smtpUseTls,
        String smtpSecurity,
        Boolean turnstileEnabled,
        String turnstileSiteKey,
        String turnstileSecretKey
    ) {
    }

    public record AdminUserResponse(
        String userId,
        String username,
        String email,
        String displayName,
        String role,
        BigDecimal balance,
        Boolean enabled,
        Boolean firstLogin,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record CreateInviteCodeRequest(
        Instant expiresAt,
        @Min(1) @Max(100) Integer count
    ) {
    }

    public record CreateInviteCodesResponse(
        Integer total,
        List<InviteCodeResponse> codes
    ) {
        public CreateInviteCodesResponse {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }
    }

    public record InviteCodeResponse(
        String codeId,
        String code,
        Boolean enabled,
        Boolean used,
        String usedByUserId,
        Instant usedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
