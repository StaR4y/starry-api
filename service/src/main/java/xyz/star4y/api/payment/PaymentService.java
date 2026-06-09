package xyz.star4y.api.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import xyz.star4y.api.admin.AdminPrincipal;
import xyz.star4y.api.admin.AdminUserEntity;
import xyz.star4y.api.admin.AdminUserRepository;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.common.Hashing;
import xyz.star4y.api.payment.EasyPayClient.CreatePaymentResult;
import xyz.star4y.api.payment.EasyPayClient.Notification;
import xyz.star4y.api.payment.PaymentDtos.CreatePaymentOrderRequest;
import xyz.star4y.api.payment.PaymentDtos.CreatePaymentOrderResponse;
import xyz.star4y.api.payment.PaymentDtos.PaymentOrderResponse;
import xyz.star4y.api.payment.PaymentSettingsService.PaymentConfig;

@Service
public class PaymentService {

    private static final String USER_ROLE = "USER";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_FAILED = "FAILED";

    private final PaymentOrderRepository repository;
    private final AdminUserRepository userRepository;
    private final R2dbcEntityTemplate template;
    private final PaymentSettingsService settingsService;
    private final EasyPayClient easyPayClient;

    public PaymentService(
        PaymentOrderRepository repository,
        AdminUserRepository userRepository,
        R2dbcEntityTemplate template,
        PaymentSettingsService settingsService,
        EasyPayClient easyPayClient
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.template = template;
        this.settingsService = settingsService;
        this.easyPayClient = easyPayClient;
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
                    .then(checkPendingOrders(user.getUserId(), config.maxPendingOrders()))
                    .then(checkDailyLimit(user.getUserId(), request.amount(), config.dailyLimit()))
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
                .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "Payment order not found"))))
            .map(PaymentOrderMapper::toResponse);
    }

    public Mono<PaymentOrderResponse> cancelUserOrder(AdminPrincipal principal, String orderId) {
        return user(principal)
            .flatMap(user -> repository.findByOrderId(orderId)
                .filter(order -> user.getUserId().equals(order.getUserId()))
                .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "Payment order not found"))))
            .flatMap(order -> {
                if (!STATUS_PENDING.equals(order.getStatus())) {
                    return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_ORDER_NOT_PENDING", "Only pending orders can be cancelled"));
                }
                order.setStatus(STATUS_CANCELLED);
                Instant now = Instant.now();
                order.setCancelledAt(now);
                touch(order, now);
                return repository.save(order);
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
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "Payment order not found")))
            .map(PaymentOrderMapper::toResponse);
    }

    public Mono<String> handleEasyPayNotification(Map<String, String> params, String rawBody) {
        return settingsService.config()
            .flatMap(config -> {
                Notification notification = easyPayClient.verifyNotification(config, params, rawBody);
                if (!notification.paid()) {
                    return Mono.just("fail");
                }
                return completePaidOrder(notification).thenReturn("success");
            });
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
        return repository.save(order)
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

    private Mono<PaymentOrderEntity> attachProviderResult(PaymentOrderEntity order, CreatePaymentResult result) {
        order.setPayUrl(result.payUrl());
        order.setQrCode(result.qrCode());
        if (result.tradeNo() != null && !result.tradeNo().isBlank()) {
            order.setPaymentTradeNo(result.tradeNo());
        }
        touch(order, Instant.now());
        return repository.save(order);
    }

    private Mono<Void> markFailed(PaymentOrderEntity order, Throwable error) {
        order.setStatus(STATUS_FAILED);
        order.setErrorMessage(error.getMessage());
        touch(order, Instant.now());
        return repository.save(order).then();
    }

    private Mono<Void> completePaidOrder(Notification notification) {
        if (notification.outTradeNo().isBlank()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_NOTIFY_INVALID", "out_trade_no is required"));
        }
        return repository.findByOutTradeNo(notification.outTradeNo())
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "Payment order not found")))
            .flatMap(order -> {
                if (STATUS_COMPLETED.equals(order.getStatus())) {
                    return Mono.empty();
                }
                if (notification.amount().compareTo(order.getPayAmount().setScale(2, RoundingMode.HALF_UP)) < 0) {
                    return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_MISMATCH", "Paid amount is lower than order amount"));
                }
                Instant now = Instant.now();
                String sql = """
                    UPDATE payment_orders
                    SET status = 'COMPLETED',
                        payment_trade_no = :tradeNo,
                        paid_at = :now,
                        completed_at = :now,
                        raw_notify = :rawNotify,
                        updated_at = :now
                    WHERE out_trade_no = :outTradeNo
                      AND status IN ('PENDING', 'EXPIRED', 'PAID')
                    """;
                return template.getDatabaseClient().sql(sql)
                    .bind("tradeNo", notification.tradeNo())
                    .bind("now", now)
                    .bind("rawNotify", notification.rawBody())
                    .bind("outTradeNo", notification.outTradeNo())
                    .fetch()
                    .rowsUpdated()
                    .flatMap(rows -> rows > 0 ? creditBalance(order) : Mono.empty());
            });
    }

    private Mono<Void> creditBalance(PaymentOrderEntity order) {
        String sql = """
            UPDATE admin_users
            SET balance = balance + :amount
            WHERE user_id = :userId
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("amount", order.getAmount())
            .bind("userId", order.getUserId())
            .fetch()
            .rowsUpdated()
            .then();
    }

    private Mono<AdminUserEntity> user(AdminPrincipal principal) {
        if (!USER_ROLE.equalsIgnoreCase(principal.role())) {
            return Mono.error(new ApiException(HttpStatus.FORBIDDEN, "USER_ROLE_REQUIRED", "Payment is only available to ordinary users"));
        }
        return userRepository.findByUserId(principal.userId())
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "Invalid or expired session")))
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

    private Mono<Void> checkPendingOrders(String userId, int maxPendingOrders) {
        String sql = """
            SELECT COUNT(*) AS total
            FROM payment_orders
            WHERE user_id = :userId
              AND status = 'PENDING'
              AND expires_at > CURRENT_TIMESTAMP(3)
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("userId", userId)
            .map((row, metadata) -> ((Number) row.get("total")).longValue())
            .one()
            .defaultIfEmpty(0L)
            .flatMap(count -> count >= maxPendingOrders
                ? Mono.error(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_PENDING_ORDERS", "Too many pending payment orders"))
                : Mono.empty());
    }

    private Mono<Void> checkDailyLimit(String userId, BigDecimal amount, BigDecimal dailyLimit) {
        if (dailyLimit == null || dailyLimit.signum() <= 0) {
            return Mono.empty();
        }
        String sql = """
            SELECT COALESCE(SUM(pay_amount), 0) AS total
            FROM payment_orders
            WHERE user_id = :userId
              AND status = 'COMPLETED'
              AND DATE(completed_at) = CURRENT_DATE()
            """;
        return template.getDatabaseClient().sql(sql)
            .bind("userId", userId)
            .map((row, metadata) -> (BigDecimal) row.get("total"))
            .one()
            .defaultIfEmpty(BigDecimal.ZERO)
            .flatMap(total -> total.add(money(amount)).compareTo(dailyLimit) > 0
                ? Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_DAILY_LIMIT_EXCEEDED", "Daily payment limit exceeded"))
                : Mono.empty());
    }

    private Mono<Void> expirePendingOrders(String userId) {
        String sql = userId == null
            ? "UPDATE payment_orders SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP(3) WHERE status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP(3)"
            : "UPDATE payment_orders SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = :userId AND status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP(3)";
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
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return origin(exchange) + "/#/payment";
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
