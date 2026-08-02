package tech.starryapi.payment;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.ApiException;
import tech.starryapi.payment.PaymentDtos.CreateRedemptionCodeRequest;
import tech.starryapi.payment.PaymentDtos.RedeemCodeRequest;

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

    @Test
    void revokeUpdatesOnlyAdministrativeStateAndPreservesConcurrentRedemptionFields() {
        RedemptionCodeEntity redeemed = new RedemptionCodeEntity();
        redeemed.setCodeId("code-1");
        redeemed.setCode("BAL-AAAA-BBBB-CCCC");
        redeemed.setBalance(BigDecimal.TEN);
        redeemed.setEnabled(false);
        redeemed.setUsed(true);
        redeemed.setUsedByUserId("user-1");
        redeemed.setUsedByUsername("alice");
        redeemed.setUsedAt(Instant.now());
        RedemptionUpdateHarness harness = redemptionUpdateService(Mono.just(redeemed));

        var response = harness.service().revoke("code-1").block();

        assertThat(response).isNotNull();
        assertThat(response.used()).isTrue();
        assertThat(response.usedByUserId()).isEqualTo("user-1");
        String setClause = harness.sql().substring(harness.sql().indexOf("SET"), harness.sql().indexOf("WHERE"));
        assertThat(setClause)
            .contains("enabled = FALSE", "updated_at = clock_timestamp()")
            .doesNotContain("used =", "used_by_user_id", "used_by_username", "used_at", "balance =");
        assertThat(harness.sql()).contains("RETURNING *");
        verify(harness.repository(), never()).save(any(RedemptionCodeEntity.class));
    }

    private RedemptionCodeService service(RedemptionCodeRepository repository) {
        return new RedemptionCodeService(repository, mock(R2dbcEntityTemplate.class));
    }

    @SuppressWarnings("unchecked")
    private RedemptionUpdateHarness redemptionUpdateService(Mono<RedemptionCodeEntity> result) {
        RedemptionCodeRepository repository = mock(RedemptionCodeRepository.class);
        R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<RedemptionCodeEntity> fetch = mock(RowsFetchSpec.class);
        AtomicReference<String> sql = new AtomicReference<>();
        when(template.getDatabaseClient()).thenReturn(databaseClient);
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, RedemptionCodeEntity>>any()))
            .thenReturn(fetch);
        when(fetch.one()).thenReturn(result);
        return new RedemptionUpdateHarness(new RedemptionCodeService(repository, template), repository, sql);
    }

    private record RedemptionUpdateHarness(
        RedemptionCodeService service,
        RedemptionCodeRepository repository,
        AtomicReference<String> capturedSql
    ) {
        String sql() {
            return capturedSql.get();
        }
    }
}
