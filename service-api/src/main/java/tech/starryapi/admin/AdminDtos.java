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
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 128) String password
    ) {
    }

    public record LoginResponse(
        String tokenType,
        String accessToken,
        Instant expiresAt,
        AdminUserResponse user
    ) {
    }

    public record AdminPermissions(boolean root) {
    }

    public record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String oldPassword,
        @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {
    }

    public record RegisterRequest(
        @NotBlank String username,
        @Email @NotBlank String email,
        String displayName,
        @NotBlank @Size(min = 12, max = 128) String password,
        String verifyCode,
        String inviteCode,
        String emailVerificationToken,
        String turnstileToken,
        String affiliateCode
    ) {
        public RegisterRequest(
            String username,
            String email,
            String displayName,
            String password,
            String verifyCode,
            String inviteCode
        ) {
            this(username, email, displayName, password, verifyCode, inviteCode, null, null, null);
        }
    }

    public record SendVerifyCodeRequest(
        @Email @NotBlank String email,
        String turnstileToken,
        String affiliateCode
    ) {
        public SendVerifyCodeRequest(String email) {
            this(email, null, null);
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
        @Size(max = 128) String password
    ) {
    }

    /**
     * {@code expectedBalance} is the balance the editor was looking at. When {@code balance} is sent, the update
     * only applies if the stored balance still equals it, so a stale form can never overwrite credits that were
     * added or reserved in the meantime. Omit both to leave the balance alone.
     */
    public record UpdateAdminUserRequest(
        @Email String email,
        String displayName,
        String role,
        BigDecimal balance,
        Boolean enabled,
        BigDecimal expectedBalance
    ) {
        public UpdateAdminUserRequest(String email, String displayName, String role, BigDecimal balance, Boolean enabled) {
            this(email, displayName, role, balance, enabled, null);
        }
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
        Boolean turnstileSecretConfigured,
        Boolean smtpVerifyEnabled,
        String smtpVerifyStatus,
        String smtpVerifySentTo,
        Instant smtpVerifyExpiresAt,
        String smtpVerifyLastError
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
                false,
                true,
                "UNCONFIGURED",
                null,
                null,
                null
            );
        }

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
            String smtpSecurity,
            Boolean turnstileEnabled,
            String turnstileSiteKey,
            Boolean turnstileSecretConfigured
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
                turnstileEnabled,
                turnstileSiteKey,
                turnstileSecretConfigured,
                true,
                "UNCONFIGURED",
                null,
                null,
                null
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
        String turnstileSecretKey,
        Boolean smtpVerifyEnabled,
        Boolean smtpVerifySendNow
    ) {
        public UpdateRegistrationSettingsRequest(
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
            this(
                registrationEnabled,
                emailVerifyEnabled,
                inviteCodeEnabled,
                registrationEmailSuffixWhitelist,
                smtpHost,
                smtpPort,
                smtpUsername,
                smtpPassword,
                smtpFromEmail,
                smtpFromName,
                smtpUseTls,
                smtpSecurity,
                turnstileEnabled,
                turnstileSiteKey,
                turnstileSecretKey,
                null,
                null
            );
        }
    }

    public record SmtpVerifyRequest(
        @NotBlank String token
    ) {
    }

    public record SmtpVerifyResponse(
        Boolean verified,
        String status,
        String message
    ) {
    }

    public record AdminUserResponse(
        String userId,
        String affiliateCode,
        String invitedByUserId,
        String inviteSourceCode,
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
