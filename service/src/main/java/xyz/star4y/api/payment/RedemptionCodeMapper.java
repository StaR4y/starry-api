package xyz.star4y.api.payment;

import xyz.star4y.api.payment.PaymentDtos.RedemptionCodeResponse;

public final class RedemptionCodeMapper {

    private RedemptionCodeMapper() {
    }

    public static RedemptionCodeResponse toResponse(RedemptionCodeEntity entity) {
        return new RedemptionCodeResponse(
            entity.getCodeId(),
            entity.getCode(),
            entity.getBalance(),
            entity.getEnabled(),
            entity.getUsed(),
            entity.getUsedByUserId(),
            entity.getUsedByUsername(),
            entity.getUsedAt(),
            entity.getExpiresAt(),
            entity.getCreatedByUserId(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
