package xyz.star4y.api.payment;

import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;
import xyz.star4y.api.payment.PaymentDtos.PaymentOrderResponse;
import xyz.star4y.api.payment.PaymentDtos.PaymentSettingsResponse;
import xyz.star4y.api.payment.PaymentDtos.UpdatePaymentSettingsRequest;

@RestController
public class AdminPaymentController implements AdminPaymentApi {

    private final PaymentSettingsService settingsService;
    private final PaymentService paymentService;
    private final AdminAuth adminAuth;

    public AdminPaymentController(
        PaymentSettingsService settingsService,
        PaymentService paymentService,
        AdminAuth adminAuth
    ) {
        this.settingsService = settingsService;
        this.paymentService = paymentService;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<PaymentSettingsResponse>> settings(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return settingsService.settings().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<PaymentSettingsResponse>> updateSettings(
        ServerWebExchange exchange,
        @RequestBody UpdatePaymentSettingsRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return settingsService.update(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<List<PaymentOrderResponse>>> orders(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return paymentService.listAdminOrders().collectList().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<PaymentOrderResponse>> order(ServerWebExchange exchange, @PathVariable String orderId) {
        adminAuth.verifyAdmin(exchange);
        return paymentService.getAdminOrder(orderId).map(ApiResponse::ok);
    }
}
