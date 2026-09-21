package tech.starryapi.payment;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

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

    @GetMapping("/redemption-codes")
    Mono<ApiResponse<List<PaymentDtos.RedemptionCodeResponse>>> redemptionCodes(ServerWebExchange exchange);

    @GetMapping("/invite-rebates")
    Mono<ApiResponse<List<PaymentDtos.InviteRebateRecordResponse>>> inviteRebates(ServerWebExchange exchange);

    @PostMapping("/redemption-codes")
    Mono<ApiResponse<PaymentDtos.CreateRedemptionCodesResponse>> createRedemptionCodes(
        ServerWebExchange exchange,
        @Valid @RequestBody PaymentDtos.CreateRedemptionCodeRequest request
    );

    @PostMapping("/redemption-codes/{codeId}/revoke")
    Mono<ApiResponse<PaymentDtos.RedemptionCodeResponse>> revokeRedemptionCode(
        ServerWebExchange exchange,
        @PathVariable String codeId
    );
}
