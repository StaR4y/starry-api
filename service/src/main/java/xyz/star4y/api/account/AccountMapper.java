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
            entity.getTokenExpiresAt(),
            entity.getProfileArn(),
            entity.getMachineId(),
            entity.getProxyUrl(),
            entity.getEnabled(),
            entity.getRequestCount(),
            entity.getErrorCount(),
            entity.getQuotaUsed(),
            entity.getQuotaLimit(),
            entity.getQuotaExhaustedAt(),
            entity.getQuotaResetAt(),
            entity.getSuspendedAt(),
            entity.getSuspendReason(),
            entity.getSuspendMessage(),
            entity.getLastUsedAt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
