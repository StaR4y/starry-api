package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

class LegalConsentServiceTest {
    private AdminUserService users;
    private SystemSettingRepository settings;
    private LegalConsentService consent;
    private final AdminPrincipal principal = new AdminPrincipal("root-1", "admin", "ADMIN", null, false);

    @BeforeEach
    void setUp() {
        users = mock(AdminUserService.class);
        settings = mock(SystemSettingRepository.class);
        consent = new LegalConsentService(users, settings);
        when(settings.getOrCreateSetting(anyString(), anyString()))
            .thenAnswer(invocation -> Mono.just(invocation.<String>getArgument(1)));
    }

    @Test
    void rootIsAskedUntilAConsentIsStoredAndNobodyElseIsAsked() {
        when(users.permissions(any())).thenReturn(Mono.just(new AdminDtos.AdminPermissions(true)));
        when(settings.findById(LegalConsentService.ACCEPTED_AT)).thenReturn(Mono.empty());
        assertThat(consent.permissions(principal).block()).isEqualTo(new AdminDtos.AdminPermissions(true, true));

        when(settings.findById(LegalConsentService.ACCEPTED_AT)).thenReturn(Mono.just(new SystemSettingEntity()));
        assertThat(consent.permissions(principal).block()).isEqualTo(new AdminDtos.AdminPermissions(true, false));

        when(users.permissions(any())).thenReturn(Mono.just(new AdminDtos.AdminPermissions(false)));
        when(settings.findById(LegalConsentService.ACCEPTED_AT)).thenReturn(Mono.empty());
        assertThat(consent.permissions(principal).block()).isEqualTo(new AdminDtos.AdminPermissions(false, false));
    }

    @Test
    void rootsConsentIsStoredOnceWithWhenAndWho() {
        when(users.permissions(any())).thenReturn(Mono.just(new AdminDtos.AdminPermissions(true)));
        Instant before = Instant.now();

        assertThat(consent.accept(principal).block()).isEqualTo(new AdminDtos.AdminPermissions(true, false));

        // getOrCreateSetting keeps a value already stored, so the first consent is the one that stays.
        ArgumentCaptor<String> when = ArgumentCaptor.forClass(String.class);
        verify(settings).getOrCreateSetting(eq(LegalConsentService.ACCEPTED_AT), when.capture());
        assertThat(Instant.parse(when.getValue())).isBetween(before, Instant.now());
        verify(settings).getOrCreateSetting(LegalConsentService.ACCEPTED_BY, "root-1");
    }

    @Test
    void anyoneButRootIsRefusedAndNothingIsStored() {
        when(users.permissions(any())).thenReturn(Mono.just(new AdminDtos.AdminPermissions(false)));

        assertThatThrownBy(() -> consent.accept(principal).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("LEGAL_CONSENT_ROOT_REQUIRED"));
        verify(settings, never()).getOrCreateSetting(anyString(), anyString());
    }
}
