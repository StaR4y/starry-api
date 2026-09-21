package tech.starryapi.stats;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("request_logs")
public class RequestLogEntity {

    @Id
    private Long id;
    private String requestId;
    private String apiKeyId;
    private String userId;
    private String accountId;
    private String path;
    private String model;
    private Integer statusCode;
    private Boolean success;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal credits;
    private Long latencyMs;
    private String errorMessage;
    private String circuitKey;
    private String circuitTier;
    private String upstreamModel;
    private Boolean upstreamSuccess;
    private UUID circuitEpoch;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getApiKeyId() {
        return apiKeyId;
    }

    public void setApiKeyId(String apiKeyId) {
        this.apiKeyId = apiKeyId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public BigDecimal getCredits() {
        return credits;
    }

    public void setCredits(BigDecimal credits) {
        this.credits = credits;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getCircuitKey() {
        return circuitKey;
    }

    public void setCircuitKey(String circuitKey) {
        this.circuitKey = circuitKey;
    }

    public String getCircuitTier() {
        return circuitTier;
    }

    public void setCircuitTier(String circuitTier) {
        this.circuitTier = circuitTier;
    }

    public String getUpstreamModel() {
        return upstreamModel;
    }

    public void setUpstreamModel(String upstreamModel) {
        this.upstreamModel = upstreamModel;
    }

    public Boolean getUpstreamSuccess() {
        return upstreamSuccess;
    }

    public void setUpstreamSuccess(Boolean upstreamSuccess) {
        this.upstreamSuccess = upstreamSuccess;
    }

    public UUID getCircuitEpoch() {
        return circuitEpoch;
    }

    public void setCircuitEpoch(UUID circuitEpoch) {
        this.circuitEpoch = circuitEpoch;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
