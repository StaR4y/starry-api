package tech.starryapi.admin;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "data_management_security_audit_events", schema = "starry_security")
public class DataManagementSecurityAuditEntity {

    @Id
    private Long id;
    private String eventId;
    private String eventType;
    private String severity;
    private Instant occurredAt;
    private String actorUserId;
    private String actorUsername;
    private String actorEmail;
    private String actorDisplayName;
    private String actorRole;
    private Boolean actorEnabled;
    private Instant actorCreatedAt;
    private Instant actorLastLoginAt;
    private Boolean actorIsRoot;
    private String rootUserId;
    private String alertRecipientEmail;
    private String remoteAddress;
    private String forwardedAddress;
    private String userAgent;
    private String sessionFingerprint;
    private String endpoint;
    private String requestId;
    private Integer consecutiveFailures;
    private Boolean locked;
    private String detail;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public void setActorEmail(String actorEmail) {
        this.actorEmail = actorEmail;
    }

    public String getActorDisplayName() {
        return actorDisplayName;
    }

    public void setActorDisplayName(String actorDisplayName) {
        this.actorDisplayName = actorDisplayName;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public Boolean getActorEnabled() {
        return actorEnabled;
    }

    public void setActorEnabled(Boolean actorEnabled) {
        this.actorEnabled = actorEnabled;
    }

    public Instant getActorCreatedAt() {
        return actorCreatedAt;
    }

    public void setActorCreatedAt(Instant actorCreatedAt) {
        this.actorCreatedAt = actorCreatedAt;
    }

    public Instant getActorLastLoginAt() {
        return actorLastLoginAt;
    }

    public void setActorLastLoginAt(Instant actorLastLoginAt) {
        this.actorLastLoginAt = actorLastLoginAt;
    }

    public Boolean getActorIsRoot() {
        return actorIsRoot;
    }

    public void setActorIsRoot(Boolean actorIsRoot) {
        this.actorIsRoot = actorIsRoot;
    }

    public String getRootUserId() {
        return rootUserId;
    }

    public void setRootUserId(String rootUserId) {
        this.rootUserId = rootUserId;
    }

    public String getAlertRecipientEmail() {
        return alertRecipientEmail;
    }

    public void setAlertRecipientEmail(String alertRecipientEmail) {
        this.alertRecipientEmail = alertRecipientEmail;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public String getForwardedAddress() {
        return forwardedAddress;
    }

    public void setForwardedAddress(String forwardedAddress) {
        this.forwardedAddress = forwardedAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getSessionFingerprint() {
        return sessionFingerprint;
    }

    public void setSessionFingerprint(String sessionFingerprint) {
        this.sessionFingerprint = sessionFingerprint;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Integer getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(Integer consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Boolean getLocked() {
        return locked;
    }

    public void setLocked(Boolean locked) {
        this.locked = locked;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
