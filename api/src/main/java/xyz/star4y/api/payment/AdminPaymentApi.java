package xyz.star4y.api.payment;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiResponse;

@RequestMapping("/admin/payment")
public interface AdminPaymentApi {

    @GetMapping("/settings")
    Mono<ApiResponse<PaymentDtos.PaymentSettingsResponse>> settings(ServerWebExchange exchange);

    @PatchMapping("/settings")
    Mono<ApiResponse<PaymentDtos.PaymentSettingsResponse>> updateSettings(
        ServerWebExchange exchange,
        @RequestBody PaymentDtos.UpdatePaymentSettingsRequest request
    );

    @GetMapping("/orders")
    Mono<ApiResponse<List<PaymentDtos.PaymentOrderResponse>>> orders(ServerWebExchange exchange);

    @GetMapping("/orders/{orderId}")
    Mono<ApiResponse<PaymentDtos.PaymentOrderResponse>> order(
        ServerWebExchange exchange,
        @PathVariable String orderId
    );
}
