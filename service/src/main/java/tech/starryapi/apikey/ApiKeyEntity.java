package tech.starryapi.apikey;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("api_keys")
public class ApiKeyEntity {

    @Id
    private Long id;
    private String keyId;
    private String userId;
    private String name;
    private String keyHash;
    private String keyValue;
    private String keyPrefix;
    private Boolean enabled;
    private BigDecimal creditsLimit;
    private Long totalRequests;
    private BigDecimal totalCredits;
    private Long totalInputTokens;
    private Long totalOutputTokens;
    private Instant lastUsedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public void setKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public BigDecimal getCreditsLimit() {
        return creditsLimit;
    }

    public void setCreditsLimit(BigDecimal creditsLimit) {
        this.creditsLimit = creditsLimit;
    }

    public Long getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(Long totalRequests) {
        this.totalRequests = totalRequests;
    }

    public BigDecimal getTotalCredits() {
        return totalCredits;
    }

    public void setTotalCredits(BigDecimal totalCredits) {
        this.totalCredits = totalCredits;
    }

    public Long getTotalInputTokens() {
        return totalInputTokens;
    }

    public void setTotalInputTokens(Long totalInputTokens) {
        this.totalInputTokens = totalInputTokens;
    }

    public Long getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public void setTotalOutputTokens(Long totalOutputTokens) {
        this.totalOutputTokens = totalOutputTokens;
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
