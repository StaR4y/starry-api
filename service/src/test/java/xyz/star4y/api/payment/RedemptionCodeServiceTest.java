package xyz.star4y.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import xyz.star4y.api.admin.AdminPrincipal;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.payment.PaymentDtos.CreateRedemptionCodeRequest;
import xyz.star4y.api.payment.PaymentDtos.RedeemCodeRequest;

class RedemptionCodeServiceTest {

    private static final AdminPrincipal ADMIN = new AdminPrincipal(
        "admin-1",
        "admin",
        "ADMIN",
        Instant.now().plusSeconds(3600),
        false
    );

    @Test
    void createsBatchRedemptionCodesWithConfiguredBalance() {
        RedemptionCodeRepository repository = mock(RedemptionCodeRepository.class);
        when(repository.save(any(RedemptionCodeEntity.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        RedemptionCodeService service = service(repository);

        var response = service.create(ADMIN, new CreateRedemptionCodeRequest(
            new BigDecimal("25.5"),
            2,
            null
        )).block();

        assertThat(response).isNotNull();
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.codes()).hasSize(2);
        assertThat(response.codes()).allSatisfy(code -> {
            assertThat(code.code()).startsWith("BAL-");
            assertThat(code.balance()).isEqualByComparingTo("25.500000");
            assertThat(code.enabled()).isTrue();
            assertThat(code.used()).isFalse();
            assertThat(code.createdByUserId()).isEqualTo("admin-1");
        });

        ArgumentCaptor<RedemptionCodeEntity> captor = ArgumentCaptor.forClass(RedemptionCodeEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(entity -> {
            assertThat(entity.getBalance()).isEqualByComparingTo("25.500000");
            assertThat(entity.getCreatedByUserId()).isEqualTo("admin-1");
        });
    }

    @Test
    void rejectsAdminRedeemingCode() {
        RedemptionCodeRepository repository = mock(RedemptionCodeRepository.class);
        RedemptionCodeService service = service(repository);

        assertThatThrownBy(() -> service.redeem(ADMIN, new RedeemCodeRequest("BAL-AAAA-BBBB-CCCC")).block())
            .isInstanceOf(ApiException.class)
            .hasMessage("Redemption codes are only available to ordinary users");

        verify(repository, never()).save(any(RedemptionCodeEntity.class));
    }

    private RedemptionCodeService service(RedemptionCodeRepository repository) {
        return new RedemptionCodeService(repository, mock(R2dbcEntityTemplate.class));
    }
}
