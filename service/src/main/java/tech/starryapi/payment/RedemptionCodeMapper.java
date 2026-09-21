package tech.starryapi.payment;

import tech.starryapi.payment.PaymentDtos.RedemptionCodeResponse;

public final class RedemptionCodeMapper {

    private RedemptionCodeMapper() {
    }

    public static RedemptionCodeResponse toResponse(RedemptionCodeEntity entity) {
        return new RedemptionCodeResponse(
            entity.getCodeId(),
            entity.getCode(),
            entity.getBalance(),
            entity.getPlanId(),
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
