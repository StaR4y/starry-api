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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.admin.AdminUserEntity;
import tech.starryapi.admin.AdminUserRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.payment.EasyPayClient.CreatePaymentResult;
import tech.starryapi.payment.EasyPayClient.Notification;
import tech.starryapi.payment.EasyPayClient.OrderQueryResult;
import tech.starryapi.payment.PaymentDtos.CreatePaymentOrderRequest;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;

class PaymentServiceTest {

    private static final AdminPrincipal USER = new AdminPrincipal(
        "user-1",
        "payment-user",
        "USER",
        Instant.now().plusSeconds(3600),
        false
    );

    @Test
    void initializesCreatedAndUpdatedTimestampsForNewOrder() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        Instant now = Instant.parse("2026-06-09T00:00:00Z");

        PaymentService.initializeTimestamps(order, now);

        assertThat(order.getCreatedAt()).isEqualTo(now);
        assertThat(order.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void touchPreservesExistingCreatedAtAndRefreshesUpdatedAt() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        Instant created = Instant.parse("2026-06-09T00:00:00Z");
        Instant updated = Instant.parse("2026-06-09T00:10:00Z");
        order.setCreatedAt(created);

        PaymentService.touch(order, updated);

        assertThat(order.getCreatedAt()).isEqualTo(created);
        assertThat(order.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void touchBackfillsCreatedAtWhenRepositoryReturnedGeneratedDefaultsAsNull() {
        PaymentOrderEntity order = new PaymentOrderEntity();
        Instant now = Instant.parse("2026-06-09T00:00:00Z");

        PaymentService.touch(order, now);

        assertThat(order.getCreatedAt()).isEqualTo(now);
        assertThat(order.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void reservesPendingOrderAtomicallyUsingActualPayAmountForDailyLimit() {
        SqlHarness sql = new SqlHarness("CREATED", null, List.of(), 0L);
        PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
        AdminUserRepository userRepository = enabledUserRepository();
        PaymentSettingsService settingsService = settings(paymentConfig());
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);
        when(easyPayClient.createPayment(any(), any(), anyString(), anyString(), anyString(), any(Boolean.class)))
            .thenAnswer(invocation -> {
                assertThat(sql.transactionCompleted()).isTrue();
                return Mono.just(new CreatePaymentResult("https://pay.example/order", "qr-value", "provider-1"));
            });

        var response = service(repository, userRepository, sql, settingsService, easyPayClient, inviteRebateService)
            .createOrder(USER, request(), exchange())
            .block();

        assertThat(response).isNotNull();
        assertThat(response.order().amount()).isEqualByComparingTo("15.00");
        assertThat(response.order().payAmount()).isEqualByComparingTo("11.00");
        assertThat(sql.creationLockSql()).contains(
            "pg_advisory_xact_lock",
            "hashtextextended(:lockKey, 0)"
        );
        assertThat(sql.reservationSql()).contains(
            "COUNT(*)::bigint AS pending_count",
            "COALESCE(SUM(pay_amount), 0) AS pending_amount",
            "COALESCE(SUM(pay_amount), 0) AS completed_amount",
            "limits.daily_reserved + :payAmount",
            "INSERT INTO payment_orders"
        );
        assertThat(sql.providerSql()).contains(
            "payment_trade_no = COALESCE(payment_trade_no, :paymentTradeNo)",
            "RETURNING payment_trade_no"
        );
        verify(sql.reservationSpec()).bind("amount", new BigDecimal("15.00"));
        verify(sql.reservationSpec()).bind("payAmount", new BigDecimal("11.00"));
        verify(repository, never()).save(any(PaymentOrderEntity.class));
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING_LIMIT,TOO_MANY_PENDING_ORDERS",
        "DAILY_LIMIT,PAYMENT_DAILY_LIMIT_EXCEEDED"
    })
    void rejectsAtomicReservationDecisionBeforeCallingProvider(String decision, String code) {
        SqlHarness sql = new SqlHarness(decision, null, List.of(), 0L);
        PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
        EasyPayClient easyPayClient = mock(EasyPayClient.class);

        assertThatThrownBy(() -> service(
            repository,
            enabledUserRepository(),
            sql,
            settings(paymentConfig()),
            easyPayClient,
            mock(InviteRebateService.class)
        ).createOrder(USER, request(), exchange()).block())
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo(code));

        verify(easyPayClient, never()).createPayment(any(), any(), anyString(), anyString(), anyString(), any(Boolean.class));
        verify(repository, never()).save(any(PaymentOrderEntity.class));
    }

    @Test
    void providerFailureOnlyMarksStillPendingUncreditedOrderFailed() {
        SqlHarness sql = new SqlHarness("CREATED", null, List.of(), 0L);
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        when(easyPayClient.createPayment(any(), any(), anyString(), anyString(), anyString(), any(Boolean.class)))
            .thenReturn(Mono.error(new IllegalStateException("provider unavailable")));

        assertThatThrownBy(() -> service(
            mock(PaymentOrderRepository.class),
            enabledUserRepository(),
            sql,
            settings(paymentConfig()),
            easyPayClient,
            mock(InviteRebateService.class)
        ).createOrder(USER, request(), exchange()).block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getCode()).isEqualTo("PAYMENT_PROVIDER_ERROR");
                assertThat(error.getMessage()).isEqualTo("provider unavailable");
            });

        assertThat(sql.failedSql()).contains(
            "AND status = :pendingStatus",
            "AND credited_at IS NULL"
        );
        verify(sql.failedSpec()).bind("pendingStatus", "PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "FAILED"})
    void verifiedPaidTerminalOrderRecoversAndReturnsSuccessOnlyAfterAtomicCredit(String originalStatus) {
        SqlHarness sql = new SqlHarness("CREATED", "order-1", List.of(), 0L);
        PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
        PaymentOrderEntity original = order(originalStatus, null);
        PaymentOrderEntity credited = order("COMPLETED", Instant.now());
        when(repository.findByOutTradeNo("trade-1")).thenReturn(Mono.just(original));
        when(repository.findByOrderId("order-1")).thenReturn(Mono.just(credited));
        PaymentSettingsService settingsService = settings(paymentConfig());
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        when(easyPayClient.verifyNotification(any(), any(), anyString()))
            .thenReturn(new Notification("trade-1", "provider-1", new BigDecimal("5.00"), true, "paid"));
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);
        when(inviteRebateService.processPending("order-1")).thenReturn(Mono.just(1L));

        String result = service(repository, mock(AdminUserRepository.class), sql, settingsService, easyPayClient, inviteRebateService)
            .handleEasyPayNotification(Map.of("sign", "valid"), "paid")
            .block();

        assertThat(result).isEqualTo("success");
        assertThat(sql.completionSql()).contains(
            "FOR UPDATE OF p, u",
            "INSERT INTO balance_ledger",
            "UPDATE admin_users AS u",
            "SET status = :completedStatus",
            "credited_at = COALESCE(p.credited_at, :now)",
            "payment_trade_no = COALESCE(p.payment_trade_no, :tradeNo)",
            "p.payment_trade_no = :tradeNo",
            "raw_notify = COALESCE(:rawNotify, p.raw_notify)",
            "error_message = NULL",
            "cancelled_at = NULL",
            "reconciliation_claim_token = NULL",
            "reconciliation_claim_until = NULL",
            "WHEN p.rebate_snapshot_at IS NULL THEN :rebateEnabled",
            "candidate.credited_at IS NOT NULL",
            "RETURNING p.order_id",
            "AND :paidAmount >= p.pay_amount"
        ).doesNotContain("status IN");
        verify(sql.completionSpec()).bind("paidAmount", new BigDecimal("5.00"));
        verify(sql.completionSpec()).bind("rebateEnabled", false);
        verify(sql.completionSpec()).bind("rebateMaxOrders", 10);
        verify(inviteRebateService).processPending("order-1");
    }

    @Test
    void rejectsConflictingProviderTradeNumberWithoutMutatingCompletedState() {
        SqlHarness sql = new SqlHarness("CREATED", "order-1", List.of(), 0L);
        PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
        PaymentOrderEntity order = order("PENDING", null);
        order.setPaymentTradeNo("provider-original");
        when(repository.findByOutTradeNo("trade-1")).thenReturn(Mono.just(order));
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        when(easyPayClient.verifyNotification(any(), any(), anyString()))
            .thenReturn(new Notification(
                "trade-1",
                "provider-conflict",
                new BigDecimal("5.00"),
                true,
                "paid"
            ));
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);

        assertThatThrownBy(() -> service(
            repository,
            mock(AdminUserRepository.class),
            sql,
            settings(paymentConfig()),
            easyPayClient,
            inviteRebateService
        ).handleEasyPayNotification(Map.of("sign", "valid"), "paid").block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus().value()).isEqualTo(400);
                assertThat(error.getCode()).isEqualTo("PAYMENT_TRADE_NO_MISMATCH");
            });

        assertThat(sql.completionSql()).isNull();
        verify(inviteRebateService, never()).processPending(anyString());
    }

    @Test
    void verifiedPaymentDoesNotAcknowledgeWhenCreditedStateCannotBeConfirmed() {
        SqlHarness sql = new SqlHarness("CREATED", null, List.of(), 0L);
        PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
        when(repository.findByOutTradeNo("trade-1")).thenReturn(Mono.just(order("CANCELLED", null)));
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        when(easyPayClient.verifyNotification(any(), any(), anyString()))
            .thenReturn(new Notification("trade-1", "provider-1", new BigDecimal("5.00"), true, "paid"));
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);

        assertThatThrownBy(() -> service(
            repository,
            mock(AdminUserRepository.class),
            sql,
            settings(paymentConfig()),
            easyPayClient,
            inviteRebateService
        ).handleEasyPayNotification(Map.of("sign", "valid"), "paid").block())
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getStatus().value()).isEqualTo(503);
                assertThat(error.getCode()).isEqualTo("PAYMENT_CREDIT_PENDING");
            });

        verify(inviteRebateService, never()).processPending(anyString());
    }

    @Test
    void scheduledReconciliationCreditsCompletedUncreditedOrdersInBoundedBatches() {
        SqlHarness sql = new SqlHarness(
            "CREATED",
            null,
            List.of(new PaymentService.ReconciliationCandidate("order-1", "trade-1", true)),
            1L
        );
        PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
        PaymentOrderEntity credited = order("COMPLETED", Instant.now());
        when(repository.findByOutTradeNo("trade-1")).thenReturn(Mono.just(credited));
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);
        when(inviteRebateService.processPending("order-1")).thenReturn(Mono.just(1L));

        Long reconciled = service(
            repository,
            mock(AdminUserRepository.class),
            sql,
            settings(paymentConfig()),
            mock(EasyPayClient.class),
            inviteRebateService
        ).reconcileUncreditedOrders().block();

        assertThat(reconciled).isEqualTo(1L);
        assertThat(sql.reconciliationSql()).contains(
            "status = 'COMPLETED'",
            "rebate_processed_at IS NULL",
            "FOR UPDATE SKIP LOCKED",
            "SET reconciliation_claim_token = :claimToken",
            "reconciliation_claim_until <= CURRENT_TIMESTAMP",
            "CAST(:leaseSeconds AS BIGINT) * INTERVAL '1 second'",
            "ORDER BY reconciliation_claim_until NULLS FIRST, completed_at, id",
            "LIMIT :batchSize"
        ).doesNotContain(":now", ":claimUntil");
        assertThat(sql.creditSql()).contains(
            "FOR UPDATE OF p, u",
            "INSERT INTO balance_ledger",
            "credit_evidence",
            "SET credited_at = :now"
        );
        verify(sql.reconciliationSpec()).bind("batchSize", 100);
        verify(sql.reconciliationSpec()).bind("leaseSeconds", 120L);
        verify(inviteRebateService).processPending("order-1");
    }

    @Test
    void scheduledReconciliationRecoversCreditedOrderWhoseRebateWasNotProcessed() {
        SqlHarness sql = new SqlHarness(
            "CREATED",
            null,
            List.of(new PaymentService.ReconciliationCandidate("order-1", "trade-1", false)),
            0L
        );
        PaymentSettingsService settingsService = mock(PaymentSettingsService.class);
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);
        when(inviteRebateService.processPending("order-1")).thenReturn(Mono.just(1L));

        Long reconciled = service(
            mock(PaymentOrderRepository.class),
            mock(AdminUserRepository.class),
            sql,
            settingsService,
            mock(EasyPayClient.class),
            inviteRebateService
        ).reconcileUncreditedOrders().block();

        assertThat(reconciled).isEqualTo(1L);
        assertThat(sql.creditSql()).isNull();
        verify(settingsService, never()).config();
        verify(inviteRebateService).processPending("order-1");
    }

    @Test
    void providerReconciliationClaimsStaleNonTerminalOrdersInBoundedLeasedBatches() {
        PaymentService.ProviderReconciliationCandidate candidate =
            new PaymentService.ProviderReconciliationCandidate(
                1L,
                "order-1",
                "trade-1",
                new BigDecimal("5.00"),
                "FAILED",
                null,
                "claim-1"
            );
        SqlHarness sql = new SqlHarness(
            "CREATED",
            "order-1",
            List.of(),
            List.of(candidate),
            0L
        );
        PaymentOrderRepository repository = mock(PaymentOrderRepository.class);
        when(repository.findByOutTradeNo("trade-1")).thenReturn(Mono.just(order("FAILED", null)));
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        when(easyPayClient.queryOrder(any(), any())).thenReturn(Mono.just(new OrderQueryResult(
            "trade-1",
            "provider-1",
            new BigDecimal("5.00"),
            true,
            "paid"
        )));
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);
        when(inviteRebateService.processPending("order-1")).thenReturn(Mono.just(1L));

        Long reconciled = service(
            repository,
            mock(AdminUserRepository.class),
            sql,
            settings(paymentConfig()),
            easyPayClient,
            inviteRebateService
        ).reconcileProviderOrders().block();

        assertThat(reconciled).isEqualTo(1L);
        assertThat(sql.providerReconciliationSql()).contains(
            "status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED', 'FAILED')",
            "created_at <= CURRENT_TIMESTAMP",
            "created_at >= CURRENT_TIMESTAMP",
            "provider_reconciliation_attempts < 24",
            "reconciliation_claim_until <= CURRENT_TIMESTAMP",
            "FOR UPDATE SKIP LOCKED",
            "LIMIT :batchSize",
            "CURRENT_TIMESTAMP",
            "ORDER BY reconciliation_claim_until NULLS FIRST, created_at, id",
            "CAST(:leaseSeconds AS BIGINT) * INTERVAL '1 second'"
        ).doesNotContain(":now", ":claimUntil");
        verify(sql.providerReconciliationSpec()).bind("batchSize", 25);
        verify(sql.providerReconciliationSpec()).bind("staleSeconds", 120L);
        verify(sql.providerReconciliationSpec()).bind("maxAgeSeconds", 604800L);
        verify(sql.providerReconciliationSpec()).bind("leaseSeconds", 300L);
        verify(easyPayClient).queryOrder(any(), any());
        verify(inviteRebateService).processPending("order-1");
    }

    @Test
    void providerReconciliationExponentiallyDefersOrdersThatAreStillUnpaid() {
        PaymentService.ProviderReconciliationCandidate candidate =
            new PaymentService.ProviderReconciliationCandidate(
                1L,
                "order-1",
                "trade-1",
                new BigDecimal("5.00"),
                "EXPIRED",
                null,
                "claim-1"
            );
        SqlHarness sql = new SqlHarness(
            "CREATED",
            null,
            List.of(),
            List.of(candidate),
            0L
        );
        EasyPayClient easyPayClient = mock(EasyPayClient.class);
        when(easyPayClient.queryOrder(any(), any())).thenReturn(Mono.just(new OrderQueryResult(
            "trade-1",
            null,
            BigDecimal.ZERO,
            false,
            "unpaid"
        )));
        InviteRebateService inviteRebateService = mock(InviteRebateService.class);

        Long reconciled = service(
            mock(PaymentOrderRepository.class),
            mock(AdminUserRepository.class),
            sql,
            settings(paymentConfig()),
            easyPayClient,
            inviteRebateService
        ).reconcileProviderOrders().block();

        assertThat(reconciled).isZero();
        assertThat(sql.providerBackoffSql()).contains(
            "provider_reconciliation_attempts = p.provider_reconciliation_attempts + 1",
            "pg_catalog.power(",
            "p.reconciliation_claim_token = :claimToken"
        );
        verify(sql.providerBackoffSpec()).bind("initialBackoffSeconds", 300L);
        verify(sql.providerBackoffSpec()).bind("maxBackoffSeconds", 21600L);
        verify(sql.providerBackoffSpec()).bind("id", 1L);
        verify(sql.providerBackoffSpec()).bind("claimToken", "claim-1");
        verify(inviteRebateService, never()).processPending(anyString());
    }

    private PaymentService service(
        PaymentOrderRepository repository,
        AdminUserRepository userRepository,
        SqlHarness sql,
        PaymentSettingsService settingsService,
        EasyPayClient easyPayClient,
        InviteRebateService inviteRebateService
    ) {
        return new PaymentService(
            repository,
            userRepository,
            sql.template(),
            settingsService,
            easyPayClient,
            inviteRebateService,
            sql.transactionalOperator()
        );
    }

    private AdminUserRepository enabledUserRepository() {
        AdminUserEntity user = new AdminUserEntity();
        user.setUserId("user-1");
        user.setUsername("payment-user");
        user.setEmail("payment@example.com");
        user.setRole("USER");
        user.setBalance(BigDecimal.ZERO);
        user.setEnabled(true);
        AdminUserRepository repository = mock(AdminUserRepository.class);
        when(repository.findByUserId("user-1")).thenReturn(Mono.just(user));
        return repository;
    }

    private PaymentOrderEntity order(String status, Instant creditedAt) {
        PaymentOrderEntity order = new PaymentOrderEntity();
        order.setId(1L);
        order.setOrderId("order-1");
        order.setUserId("user-1");
        order.setAmount(new BigDecimal("7.50"));
        order.setPayAmount(new BigDecimal("5.00"));
        order.setStatus(status);
        order.setOutTradeNo("trade-1");
        order.setCreditedAt(creditedAt);
        return order;
    }

    private PaymentSettingsService settings(PaymentConfig config) {
        PaymentSettingsService settings = mock(PaymentSettingsService.class);
        when(settings.config()).thenReturn(Mono.just(config));
        return settings;
    }

    private PaymentConfig paymentConfig() {
        return new PaymentConfig(
            true,
            BigDecimal.ONE,
            new BigDecimal("1000.00"),
            new BigDecimal("100.00"),
            30,
            3,
            List.of("alipay", "wxpay"),
            new BigDecimal("1.50"),
            BigDecimal.ONE,
            new BigDecimal("0.10"),
            "",
            "",
            "",
            "",
            "https://payment.example.com",
            "pid-1",
            "secret",
            "popup",
            false,
            BigDecimal.ZERO,
            10,
            null,
            null
        );
    }

    private CreatePaymentOrderRequest request() {
        return new CreatePaymentOrderRequest(new BigDecimal("10.00"), "alipay", null, false);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("https://api.example.com/payment/orders"));
    }

    @SuppressWarnings("unchecked")
    private static final class SqlHarness {

        private final R2dbcEntityTemplate template = mock(R2dbcEntityTemplate.class);
        private final DatabaseClient databaseClient = mock(DatabaseClient.class);
        private final DatabaseClient.GenericExecuteSpec expirationSpec = executeSpec(Mono.just(0L));
        private final DatabaseClient.GenericExecuteSpec creationLockSpec = executeSpec(Mono.just(0L));
        private final DatabaseClient.GenericExecuteSpec reservationSpec = bindableSpec();
        private final DatabaseClient.GenericExecuteSpec providerSpec = bindableSpec();
        private final DatabaseClient.GenericExecuteSpec failedSpec = executeSpec(Mono.just(0L));
        private final DatabaseClient.GenericExecuteSpec completionSpec = bindableSpec();
        private final DatabaseClient.GenericExecuteSpec reconciliationSpec = bindableSpec();
        private final DatabaseClient.GenericExecuteSpec providerReconciliationSpec = bindableSpec();
        private final DatabaseClient.GenericExecuteSpec providerBackoffSpec = executeSpec(Mono.just(1L));
        private final DatabaseClient.GenericExecuteSpec creditSpec;
        private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
        private final AtomicBoolean transactionCompleted = new AtomicBoolean();
        private final AtomicReference<String> creationLockSql = new AtomicReference<>();
        private final AtomicReference<String> reservationSql = new AtomicReference<>();
        private final AtomicReference<String> providerSql = new AtomicReference<>();
        private final AtomicReference<String> failedSql = new AtomicReference<>();
        private final AtomicReference<String> completionSql = new AtomicReference<>();
        private final AtomicReference<String> reconciliationSql = new AtomicReference<>();
        private final AtomicReference<String> providerReconciliationSql = new AtomicReference<>();
        private final AtomicReference<String> providerBackoffSql = new AtomicReference<>();
        private final AtomicReference<String> creditSql = new AtomicReference<>();

        private SqlHarness(
            String reservationDecision,
            String completedOrderId,
            List<PaymentService.ReconciliationCandidate> uncredited,
            long creditRows
        ) {
            this(reservationDecision, completedOrderId, uncredited, List.of(), creditRows);
        }

        private SqlHarness(
            String reservationDecision,
            String completedOrderId,
            List<PaymentService.ReconciliationCandidate> uncredited,
            List<PaymentService.ProviderReconciliationCandidate> providerCandidates,
            long creditRows
        ) {
            creditSpec = executeSpec(Mono.just(creditRows));
            RowsFetchSpec<String> reservationRows = mock(RowsFetchSpec.class);
            RowsFetchSpec<String> completionRows = mock(RowsFetchSpec.class);
            RowsFetchSpec<PaymentService.ProviderUpdate> providerRows = mock(RowsFetchSpec.class);
            RowsFetchSpec<PaymentService.ReconciliationCandidate> reconciliationRows = mock(RowsFetchSpec.class);
            RowsFetchSpec<PaymentService.ProviderReconciliationCandidate> providerReconciliationRows =
                mock(RowsFetchSpec.class);
            when(reservationSpec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, String>>any()))
                .thenReturn(reservationRows);
            when(completionSpec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, String>>any()))
                .thenReturn(completionRows);
            when(providerSpec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, PaymentService.ProviderUpdate>>any()))
                .thenReturn(providerRows);
            when(reconciliationSpec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, PaymentService.ReconciliationCandidate>>any()))
                .thenReturn(reconciliationRows);
            when(providerReconciliationSpec.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, PaymentService.ProviderReconciliationCandidate>>any()))
                .thenReturn(providerReconciliationRows);
            when(reservationRows.one()).thenReturn(Mono.just(reservationDecision));
            when(completionRows.one()).thenReturn(completedOrderId == null ? Mono.empty() : Mono.just(completedOrderId));
            when(providerRows.one()).thenReturn(Mono.just(new PaymentService.ProviderUpdate("provider-1", true)));
            when(reconciliationRows.all()).thenReturn(Flux.fromIterable(uncredited));
            when(providerReconciliationRows.all()).thenReturn(Flux.fromIterable(providerCandidates));
            when(template.getDatabaseClient()).thenReturn(databaseClient);
            when(transactionalOperator.transactional(
                org.mockito.ArgumentMatchers.<Mono<PaymentOrderEntity>>any()
            )).thenAnswer(invocation -> invocation.<Mono<PaymentOrderEntity>>getArgument(0)
                .doOnSuccess(ignored -> transactionCompleted.set(true)));
            when(databaseClient.sql(anyString())).thenAnswer(invocation -> route(invocation.getArgument(0)));
        }

        private DatabaseClient.GenericExecuteSpec route(String sql) {
            if (sql.contains("pg_advisory_xact_lock")) {
                creationLockSql.set(sql);
                return creationLockSpec;
            }
            if (sql.contains("WITH pending_stats AS MATERIALIZED")) {
                reservationSql.set(sql);
                return reservationSpec;
            }
            if (sql.contains("SET pay_url = :payUrl")) {
                providerSql.set(sql);
                return providerSpec;
            }
            if (sql.contains("error_message = :errorMessage")) {
                failedSql.set(sql);
                return failedSpec;
            }
            if (sql.contains("SET status = :completedStatus")) {
                completionSql.set(sql);
                return completionSpec;
            }
            if (sql.contains("SET reconciliation_claim_token = :claimToken")) {
                if (sql.contains("status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED', 'FAILED')")) {
                    providerReconciliationSql.set(sql);
                    return providerReconciliationSpec;
                }
                reconciliationSql.set(sql);
                return reconciliationSpec;
            }
            if (sql.contains("provider_reconciliation_attempts = p.provider_reconciliation_attempts + 1")) {
                providerBackoffSql.set(sql);
                return providerBackoffSpec;
            }
            if (sql.contains("p.status = :completedStatus")) {
                creditSql.set(sql);
                return creditSpec;
            }
            if (sql.startsWith("UPDATE payment_orders SET status = 'EXPIRED'")) {
                return expirationSpec;
            }
            throw new AssertionError("Unexpected payment SQL: " + sql);
        }

        private static DatabaseClient.GenericExecuteSpec bindableSpec() {
            DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
            when(spec.bind(anyString(), any())).thenReturn(spec);
            when(spec.bindNull(anyString(), any())).thenReturn(spec);
            return spec;
        }

        private static DatabaseClient.GenericExecuteSpec executeSpec(Mono<Long> rowsUpdated) {
            DatabaseClient.GenericExecuteSpec spec = bindableSpec();
            FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
            when(spec.fetch()).thenReturn(fetch);
            when(fetch.rowsUpdated()).thenReturn(rowsUpdated);
            when(fetch.all()).thenReturn(Flux.empty());
            return spec;
        }

        private R2dbcEntityTemplate template() {
            return template;
        }

        private TransactionalOperator transactionalOperator() {
            return transactionalOperator;
        }

        private boolean transactionCompleted() {
            return transactionCompleted.get();
        }

        private DatabaseClient.GenericExecuteSpec reservationSpec() {
            return reservationSpec;
        }

        private DatabaseClient.GenericExecuteSpec failedSpec() {
            return failedSpec;
        }

        private DatabaseClient.GenericExecuteSpec completionSpec() {
            return completionSpec;
        }

        private DatabaseClient.GenericExecuteSpec reconciliationSpec() {
            return reconciliationSpec;
        }

        private DatabaseClient.GenericExecuteSpec providerReconciliationSpec() {
            return providerReconciliationSpec;
        }

        private DatabaseClient.GenericExecuteSpec providerBackoffSpec() {
            return providerBackoffSpec;
        }

        private String reservationSql() {
            return reservationSql.get();
        }

        private String creationLockSql() {
            return creationLockSql.get();
        }

        private String providerSql() {
            return providerSql.get();
        }

        private String failedSql() {
            return failedSql.get();
        }

        private String completionSql() {
            return completionSql.get();
        }

        private String reconciliationSql() {
            return reconciliationSql.get();
        }

        private String providerReconciliationSql() {
            return providerReconciliationSql.get();
        }

        private String providerBackoffSql() {
            return providerBackoffSql.get();
        }

        private String creditSql() {
            return creditSql.get();
        }
    }
}
