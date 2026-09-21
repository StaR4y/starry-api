package tech.starryapi.admin;

import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.common.Hashing;

@Service
public class DataManagementSecurityAuditService implements DataManagementSecurityIncidentHandler {

    private static final Logger log = LoggerFactory.getLogger(DataManagementSecurityAuditService.class);
    private static final int MAX_SMALLINT = 32_767;

    private final DataManagementSecurityAuditStore auditStore;

    public DataManagementSecurityAuditService(DataManagementSecurityAuditStore auditStore) {
        this.auditStore = auditStore;
    }

    @Override
    public Mono<Void> handle(DataManagementSecurityIncident incident) {
        if (incident == null) {
            return Mono.empty();
        }
        return Mono.fromSupplier(() -> audit(incident))
            .flatMap(audit -> auditStore.persist(audit)
                .doOnNext(result -> {
                    audit.setId(result.auditId());
                    logPersisted(audit, result);
                }))
            .then()
            .doOnError(error -> logAuditFailure(incident, error));
    }

    private DataManagementSecurityAuditEntity audit(DataManagementSecurityIncident incident) {
        String eventId = safe(incident.eventId(), 64);
        if (eventId == null) {
            eventId = Hashing.shortUuid();
        }

        DataManagementSecurityAuditEntity audit = new DataManagementSecurityAuditEntity();
        audit.setEventId(eventId);
        audit.setEventType(incident.type() == null
            ? DataManagementSecurityIncident.Type.UNLOCK_FAILED.name()
            : incident.type().name());
        audit.setSeverity(incident.severity() == null
            ? DataManagementSecurityIncident.Severity.WARNING.name()
            : incident.severity().name());
        audit.setOccurredAt(incident.occurredAt() == null ? Instant.now() : incident.occurredAt());
        audit.setActorUserId(safe(incident.userId(), 64));
        audit.setActorUsername(safe(incident.username(), 64));
        audit.setActorEmail(safe(incident.actorEmail(), 255));
        audit.setActorDisplayName(safe(incident.actorDisplayName(), 128));
        audit.setActorRole(safe(incident.actorRole(), 32));
        audit.setActorEnabled(incident.actorEnabled());
        audit.setActorCreatedAt(incident.actorCreatedAt());
        audit.setActorLastLoginAt(incident.actorLastLoginAt());
        String rootUserId = incident.rootUserId();
        audit.setActorIsRoot(isRoot(audit.getActorUserId(), rootUserId));
        audit.setRootUserId(safe(rootUserId, 64));
        audit.setAlertRecipientEmail(safe(incident.rootEmail(), 255));
        audit.setRemoteAddress(safe(incident.remoteAddress(), 128));
        audit.setForwardedAddress(safe(
            DataManagementSecuritySanitizer.forwardedAddresses(incident.forwardedFor()),
            1024
        ));
        audit.setUserAgent(safe(DataManagementSecuritySanitizer.fingerprint(incident.userAgent()), 1024));
        audit.setSessionFingerprint(fingerprint(incident.sessionFingerprint()));
        audit.setEndpoint(required(incident.endpoint(), "/admin/data-transfer/security/unlock", 255));
        audit.setRequestId(required(DataManagementSecuritySanitizer.fingerprint(incident.requestId()), eventId, 128));
        audit.setConsecutiveFailures(Math.min(MAX_SMALLINT, Math.max(0, incident.consecutiveFailures())));
        audit.setLocked(incident.locked());
        audit.setDetail(safeDetail(incident.type()));
        return audit;
    }

    private boolean isRoot(String actorUserId, String rootUserId) {
        return actorUserId != null && actorUserId.equals(rootUserId);
    }

    private String safeDetail(DataManagementSecurityIncident.Type type) {
        if (type == null) {
            return "Data management security verification failed";
        }
        return switch (type) {
            case UNLOCK_FAILED -> "Data management security key verification failed";
            case SECURITY_LOCKED -> "Data management security controls entered locked state";
            case RECOVERY_FAILED -> "Data management security recovery failed";
            case RECOVERY_SUCCEEDED -> "Data management security recovery succeeded";
        };
    }

    private String fingerprint(String value) {
        String normalized = clean(value).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.matches("[0-9a-f]{64}") ? normalized : Hashing.sha256(normalized);
    }

    private String required(String value, String fallback, int maxLength) {
        String safe = safe(value, maxLength);
        return safe == null ? safe(fallback, maxLength) : safe;
    }

    private String safe(String value, int maxLength) {
        String cleaned = clean(value)
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\0', ' ');
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private void logPersisted(
        DataManagementSecurityAuditEntity audit,
        DataManagementSecurityAuditStore.PersistResult result
    ) {
        var event = switch (audit.getSeverity()) {
            case "INFO" -> log.atInfo();
            case "CRITICAL" -> log.atError();
            default -> log.atWarn();
        };
        event.addArgument(audit.getEventType())
            .addArgument(result.inserted() ? "PERSISTED" : "ALREADY_PERSISTED")
            .addArgument(result.outboxEnqueued() ? "ENQUEUED" : "UNCHANGED")
            .addArgument(audit.getEventId())
            .addArgument(audit.getRequestId())
            .addArgument(audit.getActorUserId())
            .addArgument(audit.getActorUsername())
            .addArgument(Boolean.TRUE.equals(audit.getActorIsRoot()))
            .addArgument(audit.getRemoteAddress())
            .addArgument(audit.getForwardedAddress())
            .addArgument(audit.getEndpoint())
            .addArgument(audit.getConsecutiveFailures())
            .addArgument(Boolean.TRUE.equals(audit.getLocked()))
            .log(
                "security_event={} audit_status={} alert_outbox={} event_id={} request_id={} actor_user_id={}"
                    + " actor_username={} actor_is_root={} remote_address={} forwarded_address={}"
                    + " endpoint={} attempt={} locked={}"
            );
    }

    private void logAuditFailure(DataManagementSecurityIncident incident, Throwable error) {
        log.error(
            "security_event={} audit_status=NOT_PERSISTED alert_outbox=NOT_ENQUEUED"
                + " reason=AUDIT_PERSISTENCE_FAILED event_id={} request_id={} actor_user_id={}"
                + " actor_username={} remote_address={} forwarded_address={} endpoint={} attempt={} locked={}",
            incident.type(),
            safe(incident.eventId(), 64),
            DataManagementSecuritySanitizer.fingerprint(incident.requestId()),
            safe(incident.userId(), 64),
            safe(incident.username(), 64),
            safe(incident.remoteAddress(), 128),
            DataManagementSecuritySanitizer.forwardedAddresses(incident.forwardedFor()),
            safe(incident.endpoint(), 255),
            Math.max(0, incident.consecutiveFailures()),
            incident.locked(),
            error
        );
    }

}
