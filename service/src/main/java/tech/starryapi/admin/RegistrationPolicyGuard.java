package tech.starryapi.admin;

import org.springframework.http.HttpStatus;
import tech.starryapi.common.ApiException;

final class RegistrationPolicyGuard {

    private RegistrationPolicyGuard() {
    }

    static VerificationRequirement validate(
        String email,
        String inviteCode,
        String affiliateCode,
        RegistrationSettingsService.RegistrationPolicy policy
    ) {
        if (!inviteCode.isBlank()) {
            if (!policy.inviteCodeEnabled()) {
                throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "INVITE_REGISTRATION_DISABLED",
                    "Invite code registration is disabled"
                );
            }
            return VerificationRequirement.TURNSTILE;
        }
        if (!policy.registrationEnabled()) {
            if (!affiliateCode.isBlank() && policy.inviteCodeEnabled()) {
                return VerificationRequirement.TURNSTILE;
            }
            throw new ApiException(
                HttpStatus.FORBIDDEN,
                "REGISTRATION_DISABLED",
                "User registration is disabled"
            );
        }
        RegistrationEmailPolicy.validateEmailAllowed(email, policy.registrationEmailSuffixWhitelist());
        return policy.emailVerifyEnabled()
            ? VerificationRequirement.EMAIL
            : VerificationRequirement.TURNSTILE;
    }

    enum VerificationRequirement {
        EMAIL,
        TURNSTILE
    }
}
