package tech.starryapi.payment;

import tech.starryapi.payment.PaymentDtos.PaymentOrderResponse;

final class PaymentOrderMapper {

    private PaymentOrderMapper() {
    }

    static PaymentOrderResponse toResponse(PaymentOrderEntity entity) {
        return new PaymentOrderResponse(
            entity.getOrderId(),
            entity.getUserId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getAmount(),
            entity.getPayAmount(),
            entity.getFeeRate(),
            entity.getPaymentType(),
            entity.getStatus(),
            entity.getOutTradeNo(),
            entity.getPaymentTradeNo(),
            entity.getPayUrl(),
            entity.getQrCode(),
            entity.getErrorMessage(),
            entity.getExpiresAt(),
            entity.getPaidAt(),
            entity.getCompletedAt(),
            entity.getCreditedAt(),
            entity.getCancelledAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
