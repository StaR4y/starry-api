package tech.starryapi.admin;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

/**
 * Root's consent to the licence (AGPL-3.0-only), the third-party licences and the disclaimer, asked once, on a new
 * deployment: creating Root on an empty database stores {@value #REQUIRED} (see AdminUserService), and the console then
 * shows the terms to Root, and only to Root, until Root agrees. A deployment whose Root existed before is never asked.
 * The consent is the system settings {@value #ACCEPTED_AT} and {@value #ACCEPTED_BY}; the first one is kept, so agreeing
 * again changes nothing.
 */
@Service
public class LegalConsentService {
    static final String REQUIRED = "legal_consent_required";
    static final String ACCEPTED_AT = "legal_consent_accepted_at";
    static final String ACCEPTED_BY = "legal_consent_accepted_by";

    private final AdminUserService users;
    private final SystemSettingRepository settings;

    public LegalConsentService(AdminUserService users, SystemSettingRepository settings) {
        this.users = users;
        this.settings = settings;
    }

    /** The administrator's permissions, with {@code legalConsentRequired} for Root while a new deployment awaits it. */
    public Mono<AdminDtos.AdminPermissions> permissions(AdminPrincipal principal) {
        return users.permissions(principal).flatMap(permissions -> !permissions.root()
            ? Mono.just(permissions)
            : settings.findById(REQUIRED).hasElement()
                .zipWith(settings.findById(ACCEPTED_AT).hasElement())
                .map(asked -> new AdminDtos.AdminPermissions(true, asked.getT1() && !asked.getT2())));
    }

    /** Records Root's consent; anyone else is refused. */
    @Transactional
    public Mono<AdminDtos.AdminPermissions> accept(AdminPrincipal principal) {
        return users.permissions(principal).flatMap(permissions -> {
            if (!permissions.root()) {
                return Mono.error(new ApiException(HttpStatus.FORBIDDEN, "LEGAL_CONSENT_ROOT_REQUIRED",
                    "Only the Root administrator can accept the licence and the disclaimer"));
            }
            return settings.getOrCreateSetting(ACCEPTED_AT, Instant.now().toString())
                .then(settings.getOrCreateSetting(ACCEPTED_BY, principal.userId()))
                .thenReturn(new AdminDtos.AdminPermissions(true, false));
        });
    }
}
