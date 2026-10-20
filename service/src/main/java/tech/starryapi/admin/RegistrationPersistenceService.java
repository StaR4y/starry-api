package tech.starryapi.admin;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

@Service
public class RegistrationPersistenceService {

    private final AdminUserRepository repository;
    private final InviteCodeService inviteCodeService;
    private final RegistrationSettingsService registrationSettingsService;
    private final EmailVerificationService emailVerificationService;

    public RegistrationPersistenceService(
        AdminUserRepository repository,
        InviteCodeService inviteCodeService,
        RegistrationSettingsService registrationSettingsService,
        EmailVerificationService emailVerificationService
    ) {
        this.repository = repository;
        this.inviteCodeService = inviteCodeService;
        this.registrationSettingsService = registrationSettingsService;
        this.emailVerificationService = emailVerificationService;
    }

    @Transactional
    public Mono<AdminUserEntity> register(
        AdminUserEntity user,
        String inviteCode,
        String affiliateCode,
        RegistrationSettingsService.RegistrationPolicy verifiedPolicy,
        String verifiedEmailTokenHash
    ) {
        return registrationSettingsService.lockedRegistrationPolicy()
            .flatMap(currentPolicy -> {
                RegistrationPolicyGuard.VerificationRequirement requirement = RegistrationPolicyGuard.validate(
                    user.getEmail(),
                    inviteCode,
                    affiliateCode,
                    currentPolicy
                );
                if (!currentPolicy.equals(verifiedPolicy)) {
                    return Mono.error(new ApiException(
                        HttpStatus.CONFLICT,
                        "REGISTRATION_POLICY_CHANGED",
                        "Registration policy changed during verification; restart registration"
                    ));
                }
                Mono<Void> consumeEmailToken = requirement == RegistrationPolicyGuard.VerificationRequirement.EMAIL
                    ? emailVerificationService.consumeRegistrationToken(user.getEmail(), verifiedEmailTokenHash)
                    : Mono.empty();
                return consumeEmailToken
                    .then(resolveAffiliateInvite(affiliateCode))
                    .flatMap(inviter -> save(user, inviter, inviteCode, currentPolicy.inviteCodeEnabled()));
            });
    }

    private Mono<AdminUserEntity> save(
        AdminUserEntity user,
        AffiliateInvite inviter,
        String inviteCode,
        boolean inviteCodeEnabled
    ) {
        if (inviter.present()) {
            user.setInvitedByUserId(inviter.userId());
            user.setInviteSourceCode(inviter.affiliateCode());
        }
        return repository.save(user)
            .flatMap(saved -> !inviteCode.isBlank() && inviteCodeEnabled
                ? inviteCodeService.consume(inviteCode, saved.getUserId()).thenReturn(saved)
                : Mono.just(saved));
    }

    private Mono<AffiliateInvite> resolveAffiliateInvite(String affiliateCode) {
        if (affiliateCode.isBlank()) {
            return Mono.just(AffiliateInvite.none());
        }
        return repository.findEnabledByAffiliateCodeForShare(affiliateCode)
            .map(inviter -> new AffiliateInvite(inviter.getUserId(), inviter.getAffiliateCode(), true))
            .switchIfEmpty(Mono.error(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_AFFILIATE_CODE",
                "Affiliate code is invalid"
            )));
    }

    private record AffiliateInvite(String userId, String affiliateCode, boolean present) {
        static AffiliateInvite none() {
            return new AffiliateInvite(null, null, false);
        }
    }
}
