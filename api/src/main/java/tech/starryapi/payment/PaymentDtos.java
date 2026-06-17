package tech.starryapi.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        String easypayPaymentMode,
        Boolean inviteRebateEnabled,
        BigDecimal inviteRebateRate,
        Integer inviteRebateMaxOrders,
        Instant inviteRebateStartAt,
        Instant inviteRebateEndAt
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
        String easypayPaymentMode,
        Boolean inviteRebateEnabled,
        BigDecimal inviteRebateRate,
        Integer inviteRebateMaxOrders,
        Instant inviteRebateStartAt,
        Instant inviteRebateEndAt
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

    public record InviteRebateRecordResponse(
        String rebateId,
        String affiliateCode,
        String paymentOrderId,
        String inviterUserId,
        String invitedUserId,
        BigDecimal baseAmount,
        BigDecimal rebateRate,
        BigDecimal rebateAmount,
        Integer rebateOrderNo,
        Instant createdAt
    ) {
    }

    public record CreateRedemptionCodeRequest(
        @DecimalMin(value = "0.000001") BigDecimal balance,
        @Min(1) @Max(100) Integer count,
        Instant expiresAt
    ) {
    }

    public record RedemptionCodeResponse(
        String codeId,
        String code,
        BigDecimal balance,
        Boolean enabled,
        Boolean used,
        String usedByUserId,
        String usedByUsername,
        Instant usedAt,
        Instant expiresAt,
        String createdByUserId,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record CreateRedemptionCodesResponse(
        Integer total,
        List<RedemptionCodeResponse> codes
    ) {
        public CreateRedemptionCodesResponse {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }
    }

    public record RedeemCodeRequest(
        @NotBlank String code
    ) {
    }

    public record RedeemCodeResponse(
        String codeId,
        BigDecimal redeemedBalance,
        BigDecimal balance,
        Instant redeemedAt
    ) {
    }
}
