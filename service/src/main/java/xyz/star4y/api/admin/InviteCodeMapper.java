package xyz.star4y.api.admin;

public final class InviteCodeMapper {

    private InviteCodeMapper() {
    }

    public static AdminDtos.InviteCodeResponse toResponse(InviteCodeEntity entity) {
        return new AdminDtos.InviteCodeResponse(
            entity.getCodeId(),
            entity.getCode(),
            entity.getEnabled(),
            entity.getUsed(),
            entity.getUsedByUserId(),
            entity.getUsedAt(),
            entity.getExpiresAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
