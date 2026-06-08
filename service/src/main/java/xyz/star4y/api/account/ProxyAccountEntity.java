package xyz.star4y.api.account;

import java.time.Instant;
import org.springframework.data.annotation.Id;
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
