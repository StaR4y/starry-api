package xyz.star4y.api.admin;

import java.math.BigDecimal;

final class AdminUserMapper {

    private AdminUserMapper() {
    }

    static AdminDtos.AdminUserResponse toResponse(AdminUserEntity entity) {
        return new AdminDtos.AdminUserResponse(
            entity.getUserId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getDisplayName(),
            entity.getRole(),
            entity.getBalance() == null ? BigDecimal.ZERO : entity.getBalance(),
            entity.getEnabled(),
            entity.getFirstLogin(),
            entity.getLastLoginAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
