package tech.starryapi.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.admin.AdminUserEntity;
import tech.starryapi.admin.AdminUserRepository;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.payment.EasyPayClient.CreatePaymentResult;
import tech.starryapi.payment.EasyPayClient.Notification;
import tech.starryapi.payment.EasyPayClient.OrderQueryResult;
import tech.starryapi.payment.PaymentDtos.CreatePaymentOrderRequest;
import tech.starryapi.payment.PaymentDtos.CreatePaymentOrderResponse;
import tech.starryapi.payment.PaymentDtos.PaymentOrderResponse;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String USER_ROLE = "USER";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int RECONCILIATION_BATCH_SIZE = 100;
    private static final Duration RECONCILIATION_LEASE = Duration.ofMinutes(2);
    private static final int PROVIDER_RECONCILIATION_BATCH_SIZE = 25;
    private static final int PROVIDER_RECONCILIATION_CONCURRENCY = 4;
    private static final int PROVIDER_RECONCILIATION_MAX_ATTEMPTS = 24;
    private static final Duration PROVIDER_RECONCILIATION_STALE_AFTER = Duration.ofMinutes(2);
    private static final Duration PROVIDER_RECONCILIATION_LEASE = Duration.ofMinutes(5);
    private static final Duration PROVIDER_RECONCILIATION_MAX_AGE = Duration.ofDays(7);
    private static final Duration PROVIDER_RECONCILIATION_INITIAL_BACKOFF = Duration.ofMinutes(5);
    private static final Duration PROVIDER_RECONCILIATION_MAX_BACKOFF = Duration.ofHours(6);

    private final PaymentOrderRepository repository;
    private final AdminUserRepository userRepository;
    private final R2dbcEntityTemplate template;
    private final PaymentSettingsService settingsService;
    private final EasyPayClient easyPayClient;
    private final InviteRebateService inviteRebateService;
    private final TransactionalOperator transactionalOperator;
    private final SystemMaintenanceTaskGate maintenanceTaskGate;
    private final AtomicBoolean reconciliationRunning = new AtomicBoolean();
    private final AtomicBoolean providerReconciliationRunning = new AtomicBoolean();

    public PaymentService(
        PaymentOrderRepository repository,
        AdminUserRepository userRepository,
        R2dbcEntityTemplate template,
        PaymentSettingsService settingsService,
        EasyPayClient easyPayClient,
        InviteRebateService inviteRebateService
    ) {
        this(
            repository,
            userRepository,
            template,
            settingsService,
            easyPayClient,
            inviteRebateService,
            TransactionalOperator.create(new R2dbcTransactionManager(
                template.getDatabaseClient().getConnectionFactory()
            )),
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction()
        );
    }

    @Autowired
    public PaymentService(
        PaymentOrderRepository repository,
        AdminUserRepository userRepository,
        R2dbcEntityTemplate template,
        PaymentSettingsService settingsService,
        EasyPayClient easyPayClient,
        InviteRebateService inviteRebateService,
        SystemMaintenanceTaskGate maintenanceTaskGate
    ) {
        this(
            repository,
            userRepository,
            template,
            settingsService,
            easyPayClient,
            inviteRebateService,
            TransactionalOperator.create(new R2dbcTransactionManager(
                template.getDatabaseClient().getConnectionFactory()
            )),
            maintenanceTaskGate
        );
    }

    PaymentService(
        PaymentOrderRepository repository,
        AdminUserRepository userRepository,
        R2dbcEntityTemplate template,
        PaymentSettingsService settingsService,
        EasyPayClient easyPayClient,
        InviteRebateService inviteRebateService,
        TransactionalOperator transactionalOperator
    ) {
        this(
            repository,
            userRepository,
            template,
            settingsService,
            easyPayClient,
            inviteRebateService,
            transactionalOperator,
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction()
        );
    }

    PaymentService(
        PaymentOrderRepository repository,
        AdminUserRepository userRepository,
        R2dbcEntityTemplate template,
        PaymentSettingsService settingsService,
        EasyPayClient easyPayClient,
        InviteRebateService inviteRebateService,
        TransactionalOperator transactionalOperator,
        SystemMaintenanceTaskGate maintenanceTaskGate
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.template = template;
        this.settingsService = settingsService;
        this.easyPayClient = easyPayClient;
        this.inviteRebateService = inviteRebateService;
        this.transactionalOperator = transactionalOperator;
        this.maintenanceTaskGate = maintenanceTaskGate;
    }

    public Mono<CreatePaymentOrderResponse> createOrder(
        AdminPrincipal principal,
        CreatePaymentOrderRequest request,
        ServerWebExchange exchange
    ) {
        return user(principal)
            .zipWith(settingsService.config())
            .flatMap(tuple -> {
                AdminUserEntity user = tuple.getT1();
                PaymentConfig config = tuple.getT2();
                validateCreateRequest(config, request);
                return expirePendingOrders(user.getUserId())
                    .then(Mono.defer(() -> createPendingOrder(user, config, request, exchange)));
            });
    }

    public Flux<PaymentOrderResponse> listUserOrders(AdminPrincipal principal) {
        return user(principal)
            .flatMapMany(user -> expirePendingOrders(user.getUserId())
                .thenMany(repository.findTop50ByUserIdOrderByCreatedAtDesc(user.getUserId())))
            .map(PaymentOrderMapper::toResponse);
    }

    public Mono<PaymentOrderResponse> getUserOrder(AdminPrincipal principal, String orderId) {
        return user(principal)
            .flatMap(user -> repository.findByOrderId(orderId)
                .filter(order -> user.getUserId().equals(order.getUserId()))
                .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "Payment order not found"))))
            .flatMap(this::syncOrderWithProviderIfNeeded)
            .map(PaymentOrderMapper::toResponse);
    }

    public Mono<PaymentOrderResponse> cancelUserOrder(AdminPrincipal principal, String orderId) {
        return user(principal)
            .flatMap(user -> repository.findByOrderId(orderId)
                .filter(order -> user.getUserId().equals(order.getUserId()))
                .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "Payment order not found"))))
            .flatMap(order -> {
                if (!STATUS_PENDING.equals(order.getStatus())) {
                    return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_ORDER_NOT_PENDING", "Only pending orders can be cancelled"));
                }
                order.setStatus(STATUS_CANCELLED);
                Instant now = Instant.now();
                order.setCancelledAt(now);
                touch(order, now);
                return updateCancelledOrder(order, now);
            })
            .map(PaymentOrderMapper::toResponse);
    }

    public Flux<PaymentOrderResponse> listAdminOrders() {
        return expirePendingOrders(null)
            .thenMany(repository.findTop100ByOrderByCreatedAtDesc())
            .map(PaymentOrderMapper::toResponse);
    }

    public Mono<PaymentOrderResponse> getAdminOrder(String orderId) {
        return repository.findByOrderId(orderId)
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "Payment order not found")))
            .flatMap(this::syncOrderWithProviderIfNeeded)
            .map(PaymentOrderMapper::toResponse);
    }

    public Mono<String> handleEasyPayNotification(Map<String, String> params, String rawBody) {
        return settingsService.config()
            .flatMap(config -> {
                // Without a merchant key the signature is a plain MD5 of the parameters, which anyone can compute.
                if (!config.easypayConfigured()) {
                    log.warn("Ignoring an EasyPay notification because EasyPay is not configured");
                    return Mono.just("fail");
                }
                Notification notification = easyPayClient.verifyNotification(config, params, rawBody);
                if (!notification.paid()) {
                    return Mono.just("fail");
                }
                return completePaidOrder(notification, config).thenReturn("success");
            });
    }

    @Scheduled(
        fixedDelayString = "${starry.payment.reconciliation-interval:PT30S}",
        initialDelayString = "${starry.payment.reconciliation-initial-delay:PT30S}"
    )
    public void scheduledReconcileUncreditedOrders() {
        maintenanceTaskGate.whenOperational(() -> {
            if (!reconciliationRunning.compareAndSet(false, true)) {
                return Mono.empty();
            }
            return reconcileUncreditedOrders()
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Reconciled {} uncredited payment order(s)", count);
                    }
                })
                .doOnError(error -> log.warn("Could not reconcile uncredited payment orders", error))
                .doFinally(signal -> reconciliationRunning.set(false));
        }).subscribe();
    }

    @Scheduled(
        fixedDelayString = "${starry.payment.provider-reconciliation-interval:PT30S}",
        initialDelayString = "${starry.payment.provider-reconciliation-initial-delay:PT45S}"
    )
    public void scheduledReconcileProviderOrders() {
        maintenanceTaskGate.whenOperational(() -> {
            if (!providerReconciliationRunning.compareAndSet(false, true)) {
                return Mono.empty();
            }
            return reconcileProviderOrders()
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Recovered {} paid order(s) through provider reconciliation", count);
                    }
                })
                .doOnError(error -> log.warn("Could not reconcile stale payment orders with provider", error))
                .doFinally(signal -> providerReconciliationRunning.set(false));
        }).subscribe();
    }

    private Mono<CreatePaymentOrderResponse> createPendingOrder(
        AdminUserEntity user,
        PaymentConfig config,
        CreatePaymentOrderRequest request,
        ServerWebExchange exchange
    ) {
        BigDecimal requestedAmount = money(request.amount());
        BigDecimal creditedAmount = money(requestedAmount.multiply(config.balanceRechargeMultiplier()));
        BigDecimal payAmount = money(requestedAmount.multiply(BigDecimal.ONE.add(config.rechargeFeeRate())));
        Instant now = Instant.now();
        PaymentOrderEntity order = new PaymentOrderEntity();
        order.setOrderId("pay_" + Hashing.shortUuid());
        order.setUserId(user.getUserId());
        order.setUsername(user.getUsername());
        order.setEmail(user.getEmail());
        order.setAmount(creditedAmount);
        order.setPayAmount(payAmount);
        order.setFeeRate(config.rechargeFeeRate());
        order.setPaymentType(normalizePaymentType(request.paymentType()));
        order.setStatus(STATUS_PENDING);
        order.setOutTradeNo(outTradeNo());
        order.setExpiresAt(now.plus(Duration.ofMinutes(config.orderTimeoutMinutes())));
        initializeTimestamps(order, now);
        return transactionalOperator.transactional(
                acquireOrderCreationLock(order.getUserId())
                    .then(reservePendingOrder(order, config))
            )
            .flatMap(saved -> easyPayClient.createPayment(
                    config,
                    saved,
                    notifyUrl(exchange),
                    returnUrl(exchange, request.returnUrl()),
                    clientIp(exchange),
                    Boolean.TRUE.equals(request.mobile())
                )
                .flatMap(result -> attachProviderResult(saved, result))
                .map(savedWithProvider -> new CreatePaymentOrderResponse(
                    PaymentOrderMapper.toResponse(savedWithProvider),
                    savedWithProvider.getPayUrl(),
                    savedWithProvider.getQrCode()
                ))
                .onErrorResume(error -> markFailed(saved, error)
                    .then(Mono.error(new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "PAYMENT_PROVIDER_ERROR",
                        error.getMessage()
                    )))));
    }

    private Mono<Void> acquireOrderCreationLock(String userId) {
        String sql = """
            SELECT pg_catalog.pg_advisory_xact_lock(
                pg_catalog.hashtextextended(:lockKey, 0)
            )
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("lockKey", "starry_api:payment:create:" + userId)
            .fetch()
            .all()
            .then();
    }

    private Mono<PaymentOrderEntity> reservePendingOrder(PaymentOrderEntity order, PaymentConfig config) {
        String sql = """
            WITH pending_stats AS MATERIALIZED (
                SELECT COUNT(*)::bigint AS pending_count,
                       COALESCE(SUM(pay_amount), 0) AS pending_amount
                FROM payment_orders
                WHERE user_id = :userId
                  AND status = 'PENDING'
                  AND expires_at > CURRENT_TIMESTAMP
            ), completed_stats AS MATERIALIZED (
                SELECT COALESCE(SUM(pay_amount), 0) AS completed_amount
                FROM payment_orders
                WHERE user_id = :userId
                  AND status = 'COMPLETED'
                  AND completed_at >= CURRENT_DATE
                  AND completed_at < CURRENT_DATE + INTERVAL '1 day'
            ), limits AS MATERIALIZED (
                SELECT pending_stats.pending_count,
                       pending_stats.pending_amount + completed_stats.completed_amount AS daily_reserved
                FROM pending_stats
                CROSS JOIN completed_stats
            ), inserted AS (
                INSERT INTO payment_orders (
                    order_id, user_id, username, email, amount, pay_amount, fee_rate,
                    payment_type, status, out_trade_no, expires_at, created_at, updated_at
                )
                SELECT :orderId, :userId, :username, :email, :amount, :payAmount, :feeRate,
                       :paymentType, :pendingStatus, :outTradeNo, :expiresAt, :createdAt, :updatedAt
                FROM limits
                WHERE limits.pending_count < :maxPendingOrders
                  AND (
                      :dailyLimit <= 0
                      OR limits.daily_reserved + :payAmount <= :dailyLimit
                  )
                RETURNING order_id
            )
            SELECT CASE
                       WHEN limits.pending_count >= :maxPendingOrders THEN 'PENDING_LIMIT'
                       WHEN :dailyLimit > 0
                            AND limits.daily_reserved + :payAmount > :dailyLimit THEN 'DAILY_LIMIT'
                       WHEN inserted.order_id IS NOT NULL THEN 'CREATED'
                       ELSE 'REJECTED'
                   END AS decision
            FROM limits
            LEFT JOIN inserted ON TRUE
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("userId", order.getUserId())
            .bind("orderId", order.getOrderId())
            .bind("amount", order.getAmount())
            .bind("payAmount", order.getPayAmount())
            .bind("feeRate", order.getFeeRate())
            .bind("paymentType", order.getPaymentType())
            .bind("pendingStatus", STATUS_PENDING)
            .bind("outTradeNo", order.getOutTradeNo())
            .bind("expiresAt", order.getExpiresAt())
            .bind("createdAt", order.getCreatedAt())
            .bind("updatedAt", order.getUpdatedAt())
            .bind("maxPendingOrders", config.maxPendingOrders())
            .bind("dailyLimit", config.dailyLimit());
        spec = bindNullable(spec, "username", order.getUsername());
        spec = bindNullable(spec, "email", order.getEmail());
        return spec.map((row, metadata) -> row.get("decision", String.class))
            .one()
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("Payment order reservation returned no decision")))
            .flatMap(decision -> switch (decision) {
                case "CREATED" -> Mono.just(order);
                case "PENDING_LIMIT" -> Mono.error(new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "TOO_MANY_PENDING_ORDERS",
                    "Too many pending payment orders"
                ));
                case "DAILY_LIMIT" -> Mono.error(new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PAYMENT_DAILY_LIMIT_EXCEEDED",
                    "Daily payment limit exceeded"
                ));
                default -> Mono.error(new IllegalStateException("Payment order reservation was rejected"));
            });
    }

    private Mono<PaymentOrderEntity> attachProviderResult(PaymentOrderEntity order, CreatePaymentResult result) {
        order.setPayUrl(result.payUrl());
        order.setQrCode(result.qrCode());
        if (result.tradeNo() != null && !result.tradeNo().isBlank()) {
            order.setPaymentTradeNo(result.tradeNo());
        }
        Instant now = Instant.now();
        touch(order, now);
        return updateProviderResult(order, now);
    }

    private Mono<Void> markFailed(PaymentOrderEntity order, Throwable error) {
        order.setErrorMessage(error.getMessage());
        Instant now = Instant.now();
        touch(order, now);
        return updateFailedOrder(order, now).then();
    }

    private Mono<PaymentOrderEntity> updateCancelledOrder(PaymentOrderEntity order, Instant now) {
        String sql = """
            UPDATE payment_orders
            SET status = :status,
                cancelled_at = :cancelledAt,
                updated_at = :now
            WHERE order_id = :orderId
              AND status = :pendingStatus
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("status", STATUS_CANCELLED)
            .bind("cancelledAt", now)
            .bind("now", now)
            .bind("orderId", order.getOrderId())
            .bind("pendingStatus", STATUS_PENDING)
            .fetch()
            .rowsUpdated()
            .flatMap(rows -> rows > 0
                ? Mono.just(order)
                : Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_ORDER_NOT_PENDING", "Only pending orders can be cancelled")));
    }

    private Mono<PaymentOrderEntity> updateProviderResult(PaymentOrderEntity order, Instant now) {
        String sql = """
            UPDATE payment_orders
            SET pay_url = :payUrl,
                qr_code = :qrCode,
                payment_trade_no = COALESCE(payment_trade_no, :paymentTradeNo),
                updated_at = :now
            WHERE order_id = :orderId
            RETURNING payment_trade_no,
                      (:paymentTradeNo IS NULL OR payment_trade_no = :paymentTradeNo) AS trade_no_matches
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("now", now)
            .bind("orderId", order.getOrderId());
        spec = bindNullable(spec, "payUrl", order.getPayUrl());
        spec = bindNullable(spec, "qrCode", order.getQrCode());
        spec = bindNullable(spec, "paymentTradeNo", order.getPaymentTradeNo());
        return spec.map((row, metadata) -> new ProviderUpdate(
                row.get("payment_trade_no", String.class),
                Boolean.TRUE.equals(row.get("trade_no_matches", Boolean.class))
            ))
            .one()
            .doOnNext(update -> {
                if (!update.tradeNoMatches()) {
                    log.warn(
                        "Ignored conflicting payment trade number for order {}. existing={} incoming={}",
                        order.getOrderId(),
                        update.paymentTradeNo(),
                        order.getPaymentTradeNo()
                    );
                }
                order.setPaymentTradeNo(update.paymentTradeNo());
            })
            .thenReturn(order);
    }

    private Mono<PaymentOrderEntity> updateFailedOrder(PaymentOrderEntity order, Instant now) {
        String sql = """
            UPDATE payment_orders
            SET status = :status,
                error_message = :errorMessage,
                updated_at = :now
            WHERE order_id = :orderId
              AND status = :pendingStatus
              AND credited_at IS NULL
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("status", STATUS_FAILED)
            .bind("now", now)
            .bind("orderId", order.getOrderId())
            .bind("pendingStatus", STATUS_PENDING);
        spec = bindNullable(spec, "errorMessage", order.getErrorMessage());
        return spec.fetch()
            .rowsUpdated()
            .thenReturn(order);
    }

    private Mono<Void> completePaidOrder(Notification notification, PaymentConfig config) {
        if (notification.outTradeNo() == null || notification.outTradeNo().isBlank()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_NOTIFY_INVALID", "out_trade_no is required"));
        }
        return repository.findByOutTradeNo(notification.outTradeNo())
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "Payment order not found")))
            .flatMap(order -> {
                if (notification.amount().compareTo(order.getPayAmount().setScale(2, RoundingMode.HALF_UP)) < 0) {
                    return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_MISMATCH", "Paid amount is lower than order amount"));
                }
                String recordedTradeNo = blankToNull(order.getPaymentTradeNo());
                String notifiedTradeNo = blankToNull(notification.tradeNo());
                if (recordedTradeNo != null
                    && notifiedTradeNo != null
                    && !recordedTradeNo.equals(notifiedTradeNo)) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "PAYMENT_TRADE_NO_MISMATCH",
                        "Payment provider trade number does not match the order"
                    ));
                }
                Instant now = Instant.now();
                return completeAndCreditPaidOrder(notification, config, now)
                    .switchIfEmpty(Mono.error(() -> new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "PAYMENT_CREDIT_PENDING",
                        "Payment was verified but balance credit is not confirmed"
                    )))
                    .flatMap(inviteRebateService::processPending)
                    .then();
            });
    }

    private static final long PROVIDER_QUERY_MIN_INTERVAL_MILLIS = 10_000;
    private static final int PROVIDER_QUERY_TRACKED_ORDERS = 5_000;
    private final java.util.Map<String, Long> lastProviderQuery = new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
            return size() > PROVIDER_QUERY_TRACKED_ORDERS;
        }
    };

    /**
     * Reading an order can trigger a query to the payment provider. Without a limit, repeatedly opening or
     * polling one unpaid order sends the provider a request each time, using the merchant's credentials.
     */
    private boolean providerQueryAllowed(String orderId) {
        if (orderId == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (lastProviderQuery) {
            Long last = lastProviderQuery.get(orderId);
            if (last != null && now - last < PROVIDER_QUERY_MIN_INTERVAL_MILLIS) {
                return false;
            }
            lastProviderQuery.put(orderId, now);
            return true;
        }
    }

    private Mono<PaymentOrderEntity> syncOrderWithProviderIfNeeded(PaymentOrderEntity order) {
        if (order.getOutTradeNo() == null || order.getOutTradeNo().isBlank()) {
            return Mono.just(order);
        }
        if (STATUS_COMPLETED.equals(order.getStatus())) {
            Mono<Long> recovery = order.getCreditedAt() == null
                ? settingsService.config().flatMap(config -> creditAndProcessOnce(
                    order.getOrderId(),
                    order.getOutTradeNo(),
                    Instant.now(),
                    config
                ))
                : inviteRebateService.processPending(order.getOrderId());
            return recovery
                .then(reloadOrder(order));
        }
        if (!shouldQueryProvider(order) || !providerQueryAllowed(order.getOrderId())) {
            return Mono.just(order);
        }
        return settingsService.config()
            .filter(PaymentConfig::easypayConfigured)
            .flatMap(config -> easyPayClient.queryOrder(config, order)
                .flatMap(result -> applyOrderQueryResult(order, result, config)))
            .switchIfEmpty(Mono.just(order))
            .onErrorResume(error -> {
                log.warn("Could not sync payment order {} with EasyPay: {}", order.getOutTradeNo(), error.getMessage());
                return Mono.just(order);
            });
    }

    private boolean shouldQueryProvider(PaymentOrderEntity order) {
        String status = order.getStatus();
        return STATUS_PENDING.equals(status)
            || STATUS_EXPIRED.equals(status)
            || STATUS_CANCELLED.equals(status)
            || STATUS_FAILED.equals(status)
            || "PAID".equals(status)
            || (STATUS_COMPLETED.equals(status) && order.getCreditedAt() == null);
    }

    private Mono<PaymentOrderEntity> applyOrderQueryResult(
        PaymentOrderEntity order,
        OrderQueryResult result,
        PaymentConfig config
    ) {
        if (!isMatchingPaidResult(order, result)) {
            return Mono.just(order);
        }
        Notification notification = notification(result);
        return completePaidOrder(notification, config).then(reloadOrder(order));
    }

    private boolean isMatchingPaidResult(PaymentOrderEntity order, OrderQueryResult result) {
        if (!result.paid()) {
            return false;
        }
        if (!order.getOutTradeNo().equals(result.outTradeNo())) {
            log.warn("EasyPay order query returned mismatched out_trade_no. expected={} actual={}", order.getOutTradeNo(), result.outTradeNo());
            return false;
        }
        if (result.amount().compareTo(order.getPayAmount().setScale(2, RoundingMode.HALF_UP)) < 0) {
            log.warn("EasyPay order query amount mismatch for {}. expected={} actual={}", order.getOutTradeNo(), order.getPayAmount(), result.amount());
            return false;
        }
        return true;
    }

    private Notification notification(OrderQueryResult result) {
        return new Notification(
            result.outTradeNo(),
            result.tradeNo(),
            result.amount(),
            true,
            result.rawBody()
        );
    }

    private Mono<PaymentOrderEntity> reloadOrder(PaymentOrderEntity order) {
        return repository.findByOrderId(order.getOrderId()).defaultIfEmpty(order);
    }

    private Mono<String> completeAndCreditPaidOrder(
        Notification notification,
        PaymentConfig config,
        Instant now
    ) {
        String transactionId = Hashing.shortUuid();
        String sql = """
            WITH candidate AS MATERIALIZED (
                SELECT p.id,
                       p.order_id,
                       p.user_id,
                       p.amount,
                       p.status,
                       p.credited_at,
                       u.balance AS balance_before,
                       u.balance + p.amount AS balance_after
                FROM payment_orders AS p
                INNER JOIN admin_users AS u ON u.user_id = p.user_id
                WHERE p.out_trade_no = :outTradeNo
                  AND :paidAmount >= p.pay_amount
                  AND (
                      p.payment_trade_no IS NULL
                      OR :tradeNo IS NULL
                      OR p.payment_trade_no = :tradeNo
                  )
                FOR UPDATE OF p, u
            ), ledger_entry AS (
                INSERT INTO balance_ledger (
                    transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                    reference_type, reference_id, idempotency_key, metadata
                )
                SELECT :transactionId,
                       candidate.user_id,
                       'PAYMENT_CREDIT',
                       candidate.amount,
                       candidate.balance_before,
                       candidate.balance_after,
                       'PAYMENT_ORDER',
                       candidate.order_id,
                       'payment-order:' || candidate.order_id,
                       '{}'::jsonb
                FROM candidate
                WHERE candidate.credited_at IS NULL
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING user_id, reference_id, balance_after
            ), credited_user AS (
                UPDATE admin_users AS u
                SET balance = ledger_entry.balance_after,
                    updated_at = :now
                FROM ledger_entry
                WHERE u.user_id = ledger_entry.user_id
                RETURNING ledger_entry.reference_id
            ), credit_evidence AS (
                SELECT candidate.order_id AS reference_id
                FROM candidate
                WHERE candidate.status = :completedStatus
                  AND candidate.credited_at IS NOT NULL
                UNION ALL
                SELECT credited_user.reference_id
                FROM credited_user
                UNION ALL
                SELECT existing.reference_id
                FROM balance_ledger AS existing
                INNER JOIN candidate
                    ON existing.idempotency_key = 'payment-order:' || candidate.order_id
                WHERE NOT EXISTS (SELECT 1 FROM credited_user)
            )
            UPDATE payment_orders AS p
            SET status = :completedStatus,
                payment_trade_no = COALESCE(p.payment_trade_no, :tradeNo),
                paid_at = COALESCE(p.paid_at, :now),
                completed_at = COALESCE(p.completed_at, :now),
                credited_at = COALESCE(p.credited_at, :now),
                rebate_snapshot_at = COALESCE(p.rebate_snapshot_at, :now),
                rebate_enabled = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateEnabled
                    ELSE p.rebate_enabled
                END,
                rebate_rate = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateRate
                    ELSE p.rebate_rate
                END,
                rebate_max_orders = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateMaxOrders
                    ELSE p.rebate_max_orders
                END,
                rebate_campaign_start_at = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateCampaignStartAt
                    ELSE p.rebate_campaign_start_at
                END,
                rebate_campaign_end_at = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateCampaignEndAt
                    ELSE p.rebate_campaign_end_at
                END,
                raw_notify = COALESCE(:rawNotify, p.raw_notify),
                error_message = NULL,
                cancelled_at = NULL,
                reconciliation_claim_token = NULL,
                reconciliation_claim_until = NULL,
                updated_at = :now
            FROM candidate
            WHERE p.id = candidate.id
              AND EXISTS (
                  SELECT 1
                  FROM credit_evidence
                  WHERE credit_evidence.reference_id = candidate.order_id
              )
            RETURNING p.order_id
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("transactionId", transactionId)
            .bind("completedStatus", STATUS_COMPLETED)
            .bind("now", now)
            .bind("outTradeNo", notification.outTradeNo())
            .bind("paidAmount", money(notification.amount()))
            .bind("rebateEnabled", config.inviteRebateEnabled())
            .bind("rebateRate", config.inviteRebateRate())
            .bind("rebateMaxOrders", config.inviteRebateMaxOrders());
        spec = bindNullable(spec, "tradeNo", blankToNull(notification.tradeNo()));
        spec = bindNullable(spec, "rawNotify", notification.rawBody());
        spec = bindNullable(spec, "rebateCampaignStartAt", config.inviteRebateStartAt());
        spec = bindNullable(spec, "rebateCampaignEndAt", config.inviteRebateEndAt());
        return spec.map((row, metadata) -> row.get("order_id", String.class)).one();
    }

    private Mono<Long> creditBalanceOnce(
        String outTradeNo,
        Instant now,
        PaymentConfig config
    ) {
        String transactionId = Hashing.shortUuid();
        String sql = """
            WITH candidate AS MATERIALIZED (
                SELECT p.id,
                       p.order_id,
                       u.user_id,
                       p.amount,
                       u.balance AS balance_before,
                       u.balance + p.amount AS balance_after
                FROM payment_orders AS p
                INNER JOIN admin_users AS u ON u.user_id = p.user_id
                WHERE p.out_trade_no = :outTradeNo
                  AND p.status = :completedStatus
                  AND p.credited_at IS NULL
                FOR UPDATE OF p, u
            ), ledger_entry AS (
                INSERT INTO balance_ledger (
                    transaction_id, user_id, entry_type, amount, balance_before, balance_after,
                    reference_type, reference_id, idempotency_key, metadata
                )
                SELECT :transactionId,
                       candidate.user_id,
                       'PAYMENT_CREDIT',
                       candidate.amount,
                       candidate.balance_before,
                       candidate.balance_after,
                       'PAYMENT_ORDER',
                       candidate.order_id,
                       'payment-order:' || candidate.order_id,
                       '{}'::jsonb
                FROM candidate
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING user_id, reference_id, balance_after
            ), credited_user AS (
                UPDATE admin_users AS u
                SET balance = ledger_entry.balance_after,
                    updated_at = :now
                FROM ledger_entry
                WHERE u.user_id = ledger_entry.user_id
                RETURNING ledger_entry.reference_id
            ), credit_evidence AS (
                SELECT credited_user.reference_id
                FROM credited_user
                UNION ALL
                SELECT existing.reference_id
                FROM balance_ledger AS existing
                INNER JOIN candidate
                    ON existing.idempotency_key = 'payment-order:' || candidate.order_id
                WHERE NOT EXISTS (SELECT 1 FROM credited_user)
            )
            UPDATE payment_orders AS p
            SET credited_at = :now,
                rebate_snapshot_at = COALESCE(p.rebate_snapshot_at, :now),
                rebate_enabled = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateEnabled
                    ELSE p.rebate_enabled
                END,
                rebate_rate = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateRate
                    ELSE p.rebate_rate
                END,
                rebate_max_orders = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateMaxOrders
                    ELSE p.rebate_max_orders
                END,
                rebate_campaign_start_at = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateCampaignStartAt
                    ELSE p.rebate_campaign_start_at
                END,
                rebate_campaign_end_at = CASE
                    WHEN p.rebate_snapshot_at IS NULL THEN :rebateCampaignEndAt
                    ELSE p.rebate_campaign_end_at
                END,
                updated_at = :now
            FROM candidate
            WHERE p.order_id = candidate.order_id
              AND p.credited_at IS NULL
              AND EXISTS (
                  SELECT 1
                  FROM credit_evidence
                  WHERE credit_evidence.reference_id = candidate.order_id
              )
            """;
        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
            .bind("transactionId", transactionId)
            .bind("now", now)
            .bind("outTradeNo", outTradeNo)
            .bind("completedStatus", STATUS_COMPLETED)
            .bind("rebateEnabled", config.inviteRebateEnabled())
            .bind("rebateRate", config.inviteRebateRate())
            .bind("rebateMaxOrders", config.inviteRebateMaxOrders());
        spec = bindNullable(spec, "rebateCampaignStartAt", config.inviteRebateStartAt());
        spec = bindNullable(spec, "rebateCampaignEndAt", config.inviteRebateEndAt());
        return spec.fetch().rowsUpdated();
    }

    private Mono<Long> creditAndProcessOnce(
        String orderId,
        String outTradeNo,
        Instant now,
        PaymentConfig config
    ) {
        return creditBalanceOnce(outTradeNo, now, config)
            .then(inviteRebateService.processPending(orderId));
    }

    Mono<Long> reconcileUncreditedOrders() {
        String sql = """
            WITH candidates AS MATERIALIZED (
                SELECT id
                FROM payment_orders
                WHERE status = 'COMPLETED'
                  AND (
                      credited_at IS NULL
                      OR rebate_processed_at IS NULL
                  )
                  AND (
                      reconciliation_claim_until IS NULL
                      OR reconciliation_claim_until <= CURRENT_TIMESTAMP
                  )
                ORDER BY reconciliation_claim_until NULLS FIRST, completed_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            UPDATE payment_orders AS p
            SET reconciliation_claim_token = :claimToken,
                reconciliation_claim_until = CURRENT_TIMESTAMP
                    + (CAST(:leaseSeconds AS BIGINT) * INTERVAL '1 second')
            FROM candidates
            WHERE p.id = candidates.id
            RETURNING p.order_id,
                      p.out_trade_no,
                      p.credited_at IS NULL AS requires_credit
            """;
        Instant now = Instant.now();
        String claimToken = Hashing.shortUuid();
        return template.getDatabaseClient().sql(sql)
            .bind("batchSize", RECONCILIATION_BATCH_SIZE)
            .bind("claimToken", claimToken)
            .bind("leaseSeconds", RECONCILIATION_LEASE.toSeconds())
            .map((row, metadata) -> new ReconciliationCandidate(
                row.get("order_id", String.class),
                row.get("out_trade_no", String.class),
                Boolean.TRUE.equals(row.get("requires_credit", Boolean.class))
            ))
            .all()
            .flatMap(candidate -> reconcilePayment(candidate, now)
                .onErrorResume(error -> {
                    log.warn("Could not reconcile payment order {}", candidate.orderId(), error);
                    return Mono.just(0L);
                }), 4)
            .reduce(0L, Long::sum);
    }

    Mono<Long> reconcileProviderOrders() {
        return settingsService.config()
            .filter(PaymentConfig::easypayConfigured)
            .flatMapMany(config -> claimProviderReconciliationCandidates()
                .flatMap(candidate -> reconcileProviderPayment(candidate, config)
                    .onErrorResume(error -> {
                        log.warn(
                            "Could not query payment provider for stale order {}",
                            candidate.orderId(),
                            error
                        );
                        return Mono.just(0L);
                    }), PROVIDER_RECONCILIATION_CONCURRENCY))
            .reduce(0L, Long::sum);
    }

    private Flux<ProviderReconciliationCandidate> claimProviderReconciliationCandidates() {
        String sql = """
            WITH candidates AS MATERIALIZED (
                SELECT id
                FROM payment_orders
                WHERE status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED', 'FAILED')
                  AND created_at <= CURRENT_TIMESTAMP
                      - (CAST(:staleSeconds AS BIGINT) * INTERVAL '1 second')
                  AND created_at >= CURRENT_TIMESTAMP
                      - (CAST(:maxAgeSeconds AS BIGINT) * INTERVAL '1 second')
                  AND provider_reconciliation_attempts < %d
                  AND (
                      reconciliation_claim_until IS NULL
                      OR reconciliation_claim_until <= CURRENT_TIMESTAMP
                  )
                ORDER BY reconciliation_claim_until NULLS FIRST, created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            UPDATE payment_orders AS p
            SET reconciliation_claim_token = :claimToken,
                reconciliation_claim_until = CURRENT_TIMESTAMP
                    + (CAST(:leaseSeconds AS BIGINT) * INTERVAL '1 second')
            FROM candidates
            WHERE p.id = candidates.id
            RETURNING p.id,
                      p.order_id,
                      p.out_trade_no,
                      p.pay_amount,
                      p.status,
                      p.payment_trade_no,
                      p.reconciliation_claim_token
            """.formatted(PROVIDER_RECONCILIATION_MAX_ATTEMPTS);
        return template.getDatabaseClient().sql(sql)
            .bind("staleSeconds", PROVIDER_RECONCILIATION_STALE_AFTER.toSeconds())
            .bind("maxAgeSeconds", PROVIDER_RECONCILIATION_MAX_AGE.toSeconds())
            .bind("batchSize", PROVIDER_RECONCILIATION_BATCH_SIZE)
            .bind("claimToken", Hashing.shortUuid())
            .bind("leaseSeconds", PROVIDER_RECONCILIATION_LEASE.toSeconds())
            .map((row, metadata) -> new ProviderReconciliationCandidate(
                row.get("id", Long.class),
                row.get("order_id", String.class),
                row.get("out_trade_no", String.class),
                row.get("pay_amount", BigDecimal.class),
                row.get("status", String.class),
                row.get("payment_trade_no", String.class),
                row.get("reconciliation_claim_token", String.class)
            ))
            .all();
    }

    private Mono<Long> reconcileProviderPayment(
        ProviderReconciliationCandidate candidate,
        PaymentConfig config
    ) {
        PaymentOrderEntity order = candidate.toOrder();
        return easyPayClient.queryOrder(config, order)
            .onErrorResume(error -> deferProviderReconciliationAfterError(candidate, error))
            .flatMap(result -> {
                if (isMatchingPaidResult(order, result)) {
                    return completePaidOrder(notification(result), config)
                        .thenReturn(1L)
                        .onErrorResume(error -> deferProviderReconciliationAfterError(candidate, error));
                }
                return deferProviderReconciliation(candidate).thenReturn(0L);
            });
    }

    private Mono<Void> deferProviderReconciliation(ProviderReconciliationCandidate candidate) {
        String sql = """
            UPDATE payment_orders AS p
            SET provider_reconciliation_attempts = p.provider_reconciliation_attempts + 1,
                reconciliation_claim_until = CURRENT_TIMESTAMP + pg_catalog.make_interval(
                    secs => LEAST(
                        CAST(:maxBackoffSeconds AS double precision),
                        CAST(:initialBackoffSeconds AS double precision)
                            * pg_catalog.power(
                                2.0,
                                CAST(LEAST(p.provider_reconciliation_attempts, 20) AS double precision)
                            )
                    )
                )
            WHERE p.id = :id
              AND p.reconciliation_claim_token = :claimToken
              AND p.status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED', 'FAILED')
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("initialBackoffSeconds", PROVIDER_RECONCILIATION_INITIAL_BACKOFF.toSeconds())
            .bind("maxBackoffSeconds", PROVIDER_RECONCILIATION_MAX_BACKOFF.toSeconds())
            .bind("id", candidate.id())
            .bind("claimToken", candidate.claimToken())
            .fetch()
            .rowsUpdated()
            .then();
    }

    private <T> Mono<T> deferProviderReconciliationAfterError(
        ProviderReconciliationCandidate candidate,
        Throwable error
    ) {
        return deferProviderReconciliation(candidate)
            .onErrorResume(deferError -> {
                error.addSuppressed(deferError);
                return Mono.empty();
            })
            .then(Mono.error(error));
    }

    private Mono<Long> reconcilePayment(ReconciliationCandidate candidate, Instant now) {
        Mono<Long> processing = candidate.requiresCredit()
            ? settingsService.config().flatMap(config -> creditAndProcessOnce(
                candidate.orderId(),
                candidate.outTradeNo(),
                now,
                config
            ))
            : inviteRebateService.processPending(candidate.orderId());
        return processing.map(rows -> rows > 0 ? 1L : 0L);
    }

    private Mono<AdminUserEntity> user(AdminPrincipal principal) {
        if (!USER_ROLE.equalsIgnoreCase(principal.role())) {
            return Mono.error(new ApiException(HttpStatus.FORBIDDEN, "USER_ROLE_REQUIRED", "Payment is only available to ordinary users"));
        }
        return userRepository.findByUserId(principal.userId())
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Invalid or expired session")))
            .flatMap(entity -> Boolean.FALSE.equals(entity.getEnabled())
                ? Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Account is disabled"))
                : Mono.just(entity));
    }

    private void validateCreateRequest(PaymentConfig config, CreatePaymentOrderRequest request) {
        if (!config.enabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PAYMENT_DISABLED", "Payment system is disabled");
        }
        if (!config.easypayConfigured()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_PROVIDER_NOT_CONFIGURED", "EasyPay provider is not configured");
        }
        BigDecimal amount = money(request.amount());
        if (amount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_AMOUNT", "Payment amount must be positive");
        }
        if (amount.compareTo(config.minAmount()) < 0 || (config.maxAmount().signum() > 0 && amount.compareTo(config.maxAmount()) > 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_AMOUNT", "Payment amount is outside the configured range");
        }
        String paymentType = normalizePaymentType(request.paymentType());
        if (!config.enabledTypes().contains(paymentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_TYPE_DISABLED", "Payment type is disabled");
        }
    }

    private Mono<Void> expirePendingOrders(String userId) {
        String sql = userId == null
            ? "UPDATE payment_orders SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP WHERE status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP"
            : "UPDATE payment_orders SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId AND status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP";
        var spec = template.getDatabaseClient().sql(sql);
        if (userId != null) {
            spec = spec.bind("userId", userId);
        }
        return spec.fetch().rowsUpdated().then();
    }

    private String notifyUrl(ServerWebExchange exchange) {
        return origin(exchange) + "/payment/webhook/easypay";
    }

    private String returnUrl(ServerWebExchange exchange, String requested) {
        String site = origin(exchange);
        // The payment provider sends the buyer to this address afterwards, so only same-site addresses are honoured;
        // anything else would turn the payment page into an open redirect.
        if (requested != null && !requested.isBlank()
            && (requested.trim().equals(site) || requested.trim().startsWith(site + "/"))) {
            return requested.trim();
        }
        return site + "/#/payment";
    }

    private String origin(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String proto = firstNonBlank(headers.getFirst("X-Forwarded-Proto"), exchange.getRequest().getURI().getScheme());
        String host = firstNonBlank(headers.getFirst("X-Forwarded-Host"), headers.getFirst(HttpHeaders.HOST));
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        return proto + "://" + host;
    }

    private String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() == null
            ? ""
            : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    private String outTradeNo() {
        String suffix = URLEncoder.encode(Hashing.shortUuid(), StandardCharsets.UTF_8).replaceAll("[^A-Za-z0-9]", "");
        return "SA" + System.currentTimeMillis() + suffix.substring(0, Math.min(8, suffix.length()));
    }

    private String normalizePaymentType(String paymentType) {
        String value = paymentType == null ? "" : paymentType.trim().toLowerCase();
        if (value.equals("alipay") || value.equals("wxpay")) {
            return value;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_TYPE", "Payment type must be alipay or wxpay");
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    static void initializeTimestamps(PaymentOrderEntity order, Instant now) {
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
    }

    static void touch(PaymentOrderEntity order, Instant now) {
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(now);
        }
        order.setUpdatedAt(now);
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, Instant value) {
        return value == null ? spec.bindNull(name, Instant.class) : spec.bind(name, value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    record ProviderUpdate(String paymentTradeNo, boolean tradeNoMatches) {
    }

    record ReconciliationCandidate(
        String orderId,
        String outTradeNo,
        boolean requiresCredit
    ) {
    }

    record ProviderReconciliationCandidate(
        Long id,
        String orderId,
        String outTradeNo,
        BigDecimal payAmount,
        String status,
        String paymentTradeNo,
        String claimToken
    ) {

        PaymentOrderEntity toOrder() {
            PaymentOrderEntity order = new PaymentOrderEntity();
            order.setId(id);
            order.setOrderId(orderId);
            order.setOutTradeNo(outTradeNo);
            order.setPayAmount(payAmount);
            order.setStatus(status);
            order.setPaymentTradeNo(paymentTradeNo);
            return order;
        }
    }
}
