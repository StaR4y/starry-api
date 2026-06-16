package tech.starryapi.account;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("proxy_accounts")
public class ProxyAccountEntity {

    @Id
    private Long id;
    private String accountId;
    private String email;
    private String accessToken;
    private String refreshToken;
    private String clientId;
    private String clientSecret;
    private String region;
    private String authMethod;
    private String provider;
    private String baseUrl;
    private String upstreamAccountId;
    private String idToken;
    private Instant tokenExpiresAt;
    private String profileArn;
    private String machineId;
    private String proxyUrl;
    private Boolean enabled;
    private Long requestCount;
    private Integer errorCount;
    private Long quotaUsed;
    private Long quotaLimit;
    private Instant quotaExhaustedAt;
    private Instant quotaResetAt;
    @Column("quota_5h_used")
    private Long quota5hUsed;
    @Column("quota_5h_limit")
    private Long quota5hLimit;
    @Column("quota_5h_reset_at")
    private Instant quota5hResetAt;
    @Column("quota_7d_used")
    private Long quota7dUsed;
    @Column("quota_7d_limit")
    private Long quota7dLimit;
    @Column("quota_7d_reset_at")
    private Instant quota7dResetAt;
    private Instant suspendedAt;
    private String suspendReason;
    private String suspendMessage;
    private Instant lastUsedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(String authMethod) {
        this.authMethod = authMethod;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUpstreamAccountId() {
        return upstreamAccountId;
    }

    public void setUpstreamAccountId(String upstreamAccountId) {
        this.upstreamAccountId = upstreamAccountId;
    }

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(Instant tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public String getProfileArn() {
        return profileArn;
    }

    public void setProfileArn(String profileArn) {
        this.profileArn = profileArn;
    }

    public String getMachineId() {
        return machineId;
    }

    public void setMachineId(String machineId) {
        this.machineId = machineId;
    }

    public String getProxyUrl() {
        return proxyUrl;
    }

    public void setProxyUrl(String proxyUrl) {
        this.proxyUrl = proxyUrl;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Long getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(Long requestCount) {
        this.requestCount = requestCount;
    }

    public Integer getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(Integer errorCount) {
        this.errorCount = errorCount;
    }

    public Long getQuotaUsed() {
        return quotaUsed;
    }

    public void setQuotaUsed(Long quotaUsed) {
        this.quotaUsed = quotaUsed;
    }

    public Long getQuotaLimit() {
        return quotaLimit;
    }

    public void setQuotaLimit(Long quotaLimit) {
        this.quotaLimit = quotaLimit;
    }

    public Instant getQuotaExhaustedAt() {
        return quotaExhaustedAt;
    }

    public void setQuotaExhaustedAt(Instant quotaExhaustedAt) {
        this.quotaExhaustedAt = quotaExhaustedAt;
    }

    public Instant getQuotaResetAt() {
        return quotaResetAt;
    }

    public void setQuotaResetAt(Instant quotaResetAt) {
        this.quotaResetAt = quotaResetAt;
    }

    public Long getQuota5hUsed() {
        return quota5hUsed;
    }

    public void setQuota5hUsed(Long quota5hUsed) {
        this.quota5hUsed = quota5hUsed;
    }

    public Long getQuota5hLimit() {
        return quota5hLimit;
    }

    public void setQuota5hLimit(Long quota5hLimit) {
        this.quota5hLimit = quota5hLimit;
    }

    public Instant getQuota5hResetAt() {
        return quota5hResetAt;
    }

    public void setQuota5hResetAt(Instant quota5hResetAt) {
        this.quota5hResetAt = quota5hResetAt;
    }

    public Long getQuota7dUsed() {
        return quota7dUsed;
    }

    public void setQuota7dUsed(Long quota7dUsed) {
        this.quota7dUsed = quota7dUsed;
    }

    public Long getQuota7dLimit() {
        return quota7dLimit;
    }

    public void setQuota7dLimit(Long quota7dLimit) {
        this.quota7dLimit = quota7dLimit;
    }

    public Instant getQuota7dResetAt() {
        return quota7dResetAt;
    }

    public void setQuota7dResetAt(Instant quota7dResetAt) {
        this.quota7dResetAt = quota7dResetAt;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public void setSuspendedAt(Instant suspendedAt) {
        this.suspendedAt = suspendedAt;
    }

    public String getSuspendReason() {
        return suspendReason;
    }

    public void setSuspendReason(String suspendReason) {
        this.suspendReason = suspendReason;
    }

    public String getSuspendMessage() {
        return suspendMessage;
    }

    public void setSuspendMessage(String suspendMessage) {
        this.suspendMessage = suspendMessage;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
