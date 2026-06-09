package xyz.star4y.api.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record PaymentSettingsResponse(
        Boolean enabled,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal dailyLimit,
        Integer orderTimeoutMinutes,
        Integer maxPendingOrders,
        List<String> enabledTypes,
        BigDecimal balanceRechargeMultiplier,
        BigDecimal apiBillingMultiplier,
        BigDecimal rechargeFeeRate,
        String productNamePrefix,
        String productNameSuffix,
        String helpImageUrl,
        String helpText,
        String easypayApiBase,
        String easypayPid,
        Boolean easypayPkeyConfigured,
        String easypayPaymentMode
    ) {
        public PaymentSettingsResponse {
            enabledTypes = enabledTypes == null ? List.of() : List.copyOf(enabledTypes);
        }
    }

    public record UpdatePaymentSettingsRequest(
        Boolean enabled,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal dailyLimit,
        Integer orderTimeoutMinutes,
        Integer maxPendingOrders,
        List<String> enabledTypes,
        BigDecimal balanceRechargeMultiplier,
        BigDecimal apiBillingMultiplier,
        BigDecimal rechargeFeeRate,
        String productNamePrefix,
        String productNameSuffix,
        String helpImageUrl,
        String helpText,
        String easypayApiBase,
        String easypayPid,
        String easypayPkey,
        String easypayPaymentMode
    ) {
    }

    public record CreatePaymentOrderRequest(
        @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank String paymentType,
        String returnUrl,
        Boolean mobile
    ) {
    }

    public record CreatePaymentOrderResponse(
        PaymentOrderResponse order,
        String payUrl,
        String qrCode
    ) {
    }

    public record PaymentOrderResponse(
        String orderId,
        String userId,
        String username,
        String email,
        BigDecimal amount,
        BigDecimal payAmount,
        BigDecimal feeRate,
        String paymentType,
        String status,
        String outTradeNo,
        String paymentTradeNo,
        String payUrl,
        String qrCode,
        String errorMessage,
        Instant expiresAt,
        Instant paidAt,
        Instant completedAt,
        Instant creditedAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
