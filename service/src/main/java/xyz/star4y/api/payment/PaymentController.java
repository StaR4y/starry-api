package xyz.star4y.api.payment;

import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;
import xyz.star4y.api.payment.PaymentDtos.CreatePaymentOrderRequest;
import xyz.star4y.api.payment.PaymentDtos.CreatePaymentOrderResponse;
import xyz.star4y.api.payment.PaymentDtos.PaymentOrderResponse;
import xyz.star4y.api.payment.PaymentDtos.PaymentSettingsResponse;
import xyz.star4y.api.payment.PaymentDtos.RedeemCodeRequest;
import xyz.star4y.api.payment.PaymentDtos.RedeemCodeResponse;

@RestController
public class PaymentController implements PaymentApi {

    private final PaymentSettingsService settingsService;
    private final PaymentService paymentService;
    private final RedemptionCodeService redemptionCodeService;
    private final AdminAuth adminAuth;

    public PaymentController(
        PaymentSettingsService settingsService,
        PaymentService paymentService,
        RedemptionCodeService redemptionCodeService,
        AdminAuth adminAuth
    ) {
        this.settingsService = settingsService;
        this.paymentService = paymentService;
        this.redemptionCodeService = redemptionCodeService;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<PaymentSettingsResponse>> settings(ServerWebExchange exchange) {
        adminAuth.verify(exchange);
        return settingsService.publicSettings().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<List<PaymentOrderResponse>>> orders(ServerWebExchange exchange) {
        return paymentService.listUserOrders(adminAuth.verify(exchange))
            .collectList()
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<CreatePaymentOrderResponse>> createOrder(
        ServerWebExchange exchange,
        @Valid @RequestBody CreatePaymentOrderRequest request
    ) {
        return paymentService.createOrder(adminAuth.verify(exchange), request, exchange).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<PaymentOrderResponse>> order(ServerWebExchange exchange, @PathVariable String orderId) {
        return paymentService.getUserOrder(adminAuth.verify(exchange), orderId).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<PaymentOrderResponse>> cancelOrder(ServerWebExchange exchange, @PathVariable String orderId) {
        return paymentService.cancelUserOrder(adminAuth.verify(exchange), orderId).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<RedeemCodeResponse>> redeemCode(
        ServerWebExchange exchange,
        @Valid @RequestBody RedeemCodeRequest request
    ) {
        return redemptionCodeService.redeem(adminAuth.verify(exchange), request).map(ApiResponse::ok);
    }

    @GetMapping(value = "/payment/webhook/easypay", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> easyPayGetNotify(ServerWebExchange exchange) {
        Map<String, String> params = flatten(exchange.getRequest().getQueryParams());
        return paymentService.handleEasyPayNotification(params, encode(params))
            .map(body -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body));
    }

    @PostMapping(value = "/payment/webhook/easypay", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> easyPayPostNotify(ServerWebExchange exchange) {
        return exchange.getFormData()
            .map(PaymentController::flatten)
            .flatMap(params -> paymentService.handleEasyPayNotification(params, encode(params)))
            .map(body -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body));
    }

    private static Map<String, String> flatten(MultiValueMap<String, String> values) {
        Map<String, String> params = new LinkedHashMap<>();
        values.forEach((key, value) -> params.put(key, value == null || value.isEmpty() ? "" : value.get(0)));
        return params;
    }

    private static String encode(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        params.forEach((key, value) -> {
            if (!builder.isEmpty()) builder.append('&');
            builder.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            builder.append('=');
            builder.append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        return builder.toString();
    }
}
