package tech.starryapi.payment;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/payment")
public interface PaymentApi {

    @GetMapping("/settings")
    Mono<ApiResponse<PaymentDtos.PaymentSettingsResponse>> settings(ServerWebExchange exchange);

    @GetMapping("/orders")
    Mono<ApiResponse<List<PaymentDtos.PaymentOrderResponse>>> orders(ServerWebExchange exchange);

    @PostMapping("/orders")
    Mono<ApiResponse<PaymentDtos.CreatePaymentOrderResponse>> createOrder(
        ServerWebExchange exchange,
        @Valid @RequestBody PaymentDtos.CreatePaymentOrderRequest request
    );

    @GetMapping("/orders/{orderId}")
    Mono<ApiResponse<PaymentDtos.PaymentOrderResponse>> order(
        ServerWebExchange exchange,
        @PathVariable String orderId
    );

    @PostMapping("/orders/{orderId}/cancel")
    Mono<ApiResponse<PaymentDtos.PaymentOrderResponse>> cancelOrder(
        ServerWebExchange exchange,
        @PathVariable String orderId
    );

    @PostMapping("/redeem-code")
    Mono<ApiResponse<PaymentDtos.RedeemCodeResponse>> redeemCode(
        ServerWebExchange exchange,
        @Valid @RequestBody PaymentDtos.RedeemCodeRequest request
    );
}
