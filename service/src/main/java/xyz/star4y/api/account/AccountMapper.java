package xyz.star4y.api.account;

final class AccountMapper {

    private AccountMapper() {}

    static AccountDtos.AccountResponse toResponse(ProxyAccountEntity entity) {
        return new AccountDtos.AccountResponse(
            entity.getAccountId(),
            entity.getEmail(),
            entity.getRegion(),
            entity.getAuthMethod(),
            entity.getProvider(),
            entity.getBaseUrl(),
            entity.getUpstreamAccountId(),
            entity.getProfileArn(),
            entity.getMachineId(),
            entity.getProxyUrl(),
            entity.getEnabled(),
            entity.getRequestCount(),
            entity.getErrorCount(),
            windowUsed(entity.getQuotaUsed(), entity.getQuotaResetAt()),
            entity.getQuotaLimit(),
            entity.getQuotaExhaustedAt(),
            entity.getQuotaResetAt(),
            windowUsed(entity.getQuota5hUsed(), entity.getQuota5hResetAt()),
            entity.getQuota5hLimit(),
            entity.getQuota5hResetAt(),
            windowUsed(entity.getQuota7dUsed(), entity.getQuota7dResetAt()),
            entity.getQuota7dLimit(),
            entity.getQuota7dResetAt(),
            entity.getSuspendedAt(),
            entity.getSuspendReason(),
            entity.getSuspendMessage(),
            entity.getLastUsedAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private static Long windowUsed(Long used, java.time.Instant resetAt) {
        if (resetAt != null && !resetAt.isAfter(java.time.Instant.now())) {
            return 0L;
        }
        return used;
    }
}
