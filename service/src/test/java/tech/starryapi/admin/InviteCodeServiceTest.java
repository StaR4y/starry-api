package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

class InviteCodeServiceTest {

    @Test
    void createsBatchInviteCodes() {
        InviteCodeRepository repository = mock(InviteCodeRepository.class);
        when(repository.save(any(InviteCodeEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        InviteCodeService service = service(repository);
        Instant expiresAt = Instant.now().plusSeconds(3600);

        AdminDtos.CreateInviteCodesResponse response = service
            .createBatch(new AdminDtos.CreateInviteCodeRequest(expiresAt, 3))
            .block();

        assertThat(response).isNotNull();
        assertThat(response.total()).isEqualTo(3);
        assertThat(response.codes()).hasSize(3);
        assertThat(response.codes()).allSatisfy(code -> {
            assertThat(code.code()).startsWith("INV-");
            assertThat(code.enabled()).isTrue();
            assertThat(code.used()).isFalse();
            assertThat(code.expiresAt()).isEqualTo(expiresAt);
        });

        ArgumentCaptor<InviteCodeEntity> captor = ArgumentCaptor.forClass(InviteCodeEntity.class);
        verify(repository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(entity ->
            assertThat(entity.getExpiresAt()).isEqualTo(expiresAt)
        );
    }

    @Test
    void rejectsInvalidBatchCount() {
        InviteCodeService service = service(mock(InviteCodeRepository.class));

        assertThatThrownBy(() -> service.createBatch(new AdminDtos.CreateInviteCodeRequest(null, 101)).block())
            .isInstanceOf(ApiException.class)
            .hasMessage("Invite code count must be between 1 and 100");
    }

    @Test
    void revokesWithPartialDatabaseUpdateInsteadOfSavingAStaleEntity() {
        InviteCodeRepository repository = mock(InviteCodeRepository.class);
        InviteCodeEntity revoked = new InviteCodeEntity();
        revoked.setCodeId("invite-1");
        revoked.setCode("INV-TEST-CODE");
        revoked.setEnabled(false);
        revoked.setUsed(true);
        revoked.setUsedByUserId("registered-user");
        when(repository.revokeByCodeId("invite-1")).thenReturn(Mono.just(revoked));
        InviteCodeService service = service(repository);

        AdminDtos.InviteCodeResponse response = service.revoke(" invite-1 ").block();

        assertThat(response).isNotNull();
        assertThat(response.enabled()).isFalse();
        assertThat(response.used()).isTrue();
        assertThat(response.usedByUserId()).isEqualTo("registered-user");
        verify(repository).revokeByCodeId("invite-1");
        verify(repository, never()).save(any(InviteCodeEntity.class));
    }

    private InviteCodeService service(InviteCodeRepository repository) {
        return new InviteCodeService(repository, mock(R2dbcEntityTemplate.class));
    }
}
