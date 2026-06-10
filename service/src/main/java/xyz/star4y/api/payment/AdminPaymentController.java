package xyz.star4y.api.payment;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;
import xyz.star4y.api.payment.PaymentDtos.CreateRedemptionCodeRequest;
import xyz.star4y.api.payment.PaymentDtos.CreateRedemptionCodesResponse;
import xyz.star4y.api.payment.PaymentDtos.PaymentOrderResponse;
import xyz.star4y.api.payment.PaymentDtos.PaymentSettingsResponse;
import xyz.star4y.api.payment.PaymentDtos.RedemptionCodeResponse;
import xyz.star4y.api.payment.PaymentDtos.UpdatePaymentSettingsRequest;

@RestController
public class AdminPaymentController implements AdminPaymentApi {

    private final PaymentSettingsService settingsService;
    private final PaymentService paymentService;
    private final RedemptionCodeService redemptionCodeService;
    private final AdminAuth adminAuth;

    public AdminPaymentController(
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

    @Override
    public Mono<ApiResponse<List<RedemptionCodeResponse>>> redemptionCodes(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return redemptionCodeService.list().collectList().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<CreateRedemptionCodesResponse>> createRedemptionCodes(
        ServerWebExchange exchange,
        @Valid @RequestBody CreateRedemptionCodeRequest request
    ) {
        return redemptionCodeService.create(adminAuth.verifyAdmin(exchange), request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<RedemptionCodeResponse>> revokeRedemptionCode(
        ServerWebExchange exchange,
        @PathVariable String codeId
    ) {
        adminAuth.verifyAdmin(exchange);
        return redemptionCodeService.revoke(codeId).map(ApiResponse::ok);
    }
}
