package tech.starryapi.account.allocation;

import io.r2dbc.spi.Row;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresAccountAdmissionStore implements AccountAdmissionStore {

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public PostgresAccountAdmissionStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<AdmissionDecision> acquire(AdmissionRequest request) {
        String sql = """
            SELECT accepted, lease_id, rejection_reason, state_version, expires_at
            FROM starry_api.try_acquire_scoped_account_lease(
                :accountId,
                :requestId,
                :expectedStateVersion,
                :sessionHash,
                :enforceSessionLimit,
                :reservedInputTokens,
                :reservedOutputTokens,
                :modelScope,
                :endpointScope,
                :leaseSeconds
            )
            """;
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
            .bind("accountId", request.accountId())
            .bind("requestId", request.requestId())
            .bind("enforceSessionLimit", request.enforceSessionLimit())
            .bind("reservedInputTokens", request.reservedInputTokens())
            .bind("reservedOutputTokens", request.reservedOutputTokens())
            .bind("leaseSeconds", Math.toIntExact(request.leaseTtl().toSeconds()));
        spec = request.expectedStateVersion() == null
            ? spec.bindNull("expectedStateVersion", Long.class)
            : spec.bind("expectedStateVersion", request.expectedStateVersion());
        spec = request.sessionHash() == null || request.sessionHash().isBlank()
            ? spec.bindNull("sessionHash", String.class)
            : spec.bind("sessionHash", request.sessionHash());
        spec = request.modelScope() == null
            ? spec.bindNull("modelScope", String.class)
            : spec.bind("modelScope", request.modelScope());
        spec = request.endpointScope() == null
            ? spec.bindNull("endpointScope", String.class)
            : spec.bind("endpointScope", request.endpointScope());
        return spec.map((row, metadata) -> new AdmissionDecision(
            Boolean.TRUE.equals(row.get("accepted", Boolean.class)),
            row.get("lease_id", UUID.class),
            row.get("rejection_reason", String.class),
            number(row, "state_version"),
            instant(row, "expires_at")
        )).one();
    }

    @Override
    public Mono<Void> markDispatched(UUID leaseId) {
        return databaseClient.sql("SELECT starry_api.mark_account_lease_dispatched(:leaseId) AS marked")
            .bind("leaseId", leaseId)
            .map((row, metadata) -> Boolean.TRUE.equals(row.get("marked", Boolean.class)))
            .one()
            .flatMap(marked -> marked
                ? Mono.empty()
                : Mono.error(new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ADMISSION_LEASE_EXPIRED",
                    "Account admission lease expired before dispatch"
                )));
    }

    @Override
    public Mono<Void> reconcile(AdmissionResult result) {
        String sql = """
            SELECT starry_api.reconcile_account_lease(
                :leaseId,
                :success,
                :actualInputTokens,
                :actualOutputTokens,
                :latencyMs,
                :statusCode,
                :errorScope,
                :cooldownSeconds,
                :permanentCredentialFailure,
                CAST(:evidence AS jsonb)
            ) AS reconciled
            """;
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
            .bind("leaseId", result.leaseId())
            .bind("success", result.success())
            .bind("actualInputTokens", result.actualInputTokens())
            .bind("actualOutputTokens", result.actualOutputTokens())
            .bind("latencyMs", result.latencyMs())
            .bind("errorScope", result.failureScope().name())
            .bind("cooldownSeconds", Math.toIntExact(result.cooldown().toSeconds()))
            .bind("permanentCredentialFailure", result.permanentCredentialFailure())
            .bind("evidence", evidenceJson(result));
        spec = result.statusCode() == null
            ? spec.bindNull("statusCode", Integer.class)
            : spec.bind("statusCode", result.statusCode());
        return spec.map((row, metadata) -> Boolean.TRUE.equals(row.get("reconciled", Boolean.class)))
            .one()
            .then();
    }

    @Override
    public Mono<Void> releaseUnsent(UUID leaseId) {
        return databaseClient.sql("SELECT starry_api.release_unsent_account_lease(:leaseId) AS released")
            .bind("leaseId", leaseId)
            .map((row, metadata) -> Boolean.TRUE.equals(row.get("released", Boolean.class)))
            .one()
            .then();
    }

    @Override
    public Mono<Void> abandon(UUID leaseId, String reason) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                SELECT starry_api.abandon_account_lease(:leaseId, :reason) AS abandoned
                """)
            .bind("leaseId", leaseId);
        spec = reason == null || reason.isBlank()
            ? spec.bind("reason", "PIPELINE_ABANDONED")
            : spec.bind("reason", reason);
        return spec.map((row, metadata) -> Boolean.TRUE.equals(row.get("abandoned", Boolean.class)))
            .one()
            .then();
    }

    private String evidenceJson(AdmissionResult result) {
        try {
            return objectMapper.writeValueAsString(result.evidence());
        } catch (JacksonException error) {
            throw new IllegalArgumentException("Admission evidence is not JSON serializable", error);
        }
    }

    private static Long number(Row row, String name) {
        Number value = row.get(name, Number.class);
        return value == null ? null : value.longValue();
    }

    private static Instant instant(Row row, String name) {
        Object value = row.get(name);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        return null;
    }
}
