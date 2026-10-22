package tech.starryapi.account;

import io.r2dbc.spi.Row;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

@Service
public class AccountAllocationPolicyService {

    private static final String POLICY_NOT_FOUND = "ACCOUNT_ALLOCATION_POLICY_NOT_FOUND";

    private final DatabaseClient databaseClient;

    public AccountAllocationPolicyService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<AccountDtos.AccountAllocationPolicyResponse> get(String accountId) {
        return databaseClient.sql("""
                SELECT policy.account_id,
                       policy.allocation_priority,
                       policy.safe_concurrency,
                       policy.safe_rpm,
                       policy.burst_capacity,
                       policy.max_sessions,
                       policy.session_idle_seconds,
                       policy.max_inflight_tokens,
                       policy.cooldown_seconds,
                       policy.tls_profile,
                       policy.policy_version,
                       lifecycle.lifecycle_state,
                       lifecycle.state_version,
                       lifecycle.warmup_stage,
                       lifecycle.cooldown_until,
                       lifecycle.recent_error_ewma,
                       lifecycle.recent_latency_ewma_ms,
                       admission.active_concurrency,
                       admission.reserved_input_tokens,
                       admission.reserved_output_tokens,
                       GREATEST(policy.updated_at, lifecycle.updated_at, admission.updated_at) AS updated_at
                FROM proxy_accounts account
                JOIN account_allocation_policies policy USING (account_id)
                JOIN account_allocation_states lifecycle USING (account_id)
                JOIN account_admission_states admission USING (account_id)
                WHERE account.account_id = :accountId
                """)
            .bind("accountId", accountId)
            .map((row, metadata) -> response(row))
            .one()
            .switchIfEmpty(notFound(accountId));
    }

    @Transactional
    public Mono<AccountDtos.AccountAllocationPolicyResponse> update(
        String accountId,
        AccountDtos.UpdateAccountAllocationPolicyRequest request
    ) {
        AccountDtos.UpdateAccountAllocationPolicyRequest values = request == null
            ? new AccountDtos.UpdateAccountAllocationPolicyRequest(null, null, null, null, null, null, null)
            : request;
        String sql = """
            WITH target AS (
                SELECT account_id,
                       lower(coalesce(provider, '')) AS provider,
                       lower(coalesce(auth_method, '')) AS auth_method
                FROM proxy_accounts
                WHERE account_id = :accountId
            )
            UPDATE account_allocation_policies policy
            SET allocation_priority = COALESCE(:allocationPriority, policy.allocation_priority),
                safe_concurrency = COALESCE(:safeConcurrency, policy.safe_concurrency),
                safe_rpm = COALESCE(:safeRpm, policy.safe_rpm),
                burst_capacity = COALESCE(:burstCapacity, policy.burst_capacity),
                max_sessions = CASE
                    WHEN target.provider NOT IN ('anthropic', 'claude') THEN 0
                    ELSE COALESCE(:maxSessions, policy.max_sessions)
                END,
                session_idle_seconds = COALESCE(:sessionIdleSeconds, policy.session_idle_seconds),
                max_inflight_tokens = COALESCE(:maxInflightTokens, policy.max_inflight_tokens),
                cooldown_seconds = COALESCE(:cooldownSeconds, policy.cooldown_seconds),
                tls_profile = CASE WHEN target.provider IN ('anthropic','claude')
                    THEN COALESCE(:tlsProfile, policy.tls_profile) ELSE '' END,
                policy_version = policy.policy_version + 1,
                updated_at = clock_timestamp()
            FROM target
            WHERE policy.account_id = target.account_id
            RETURNING policy.account_id
            """;
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql)
            .bind("accountId", accountId);
        spec = bindNullable(spec, "allocationPriority", values.allocationPriority(), Integer.class);
        spec = bindNullable(spec, "safeConcurrency", values.safeConcurrency(), Integer.class);
        spec = bindNullable(spec, "safeRpm", values.safeRpm(), Integer.class);
        spec = bindNullable(spec, "burstCapacity", values.burstCapacity(), Integer.class);
        spec = bindNullable(spec, "maxSessions", values.maxSessions(), Integer.class);
        spec = bindNullable(spec, "sessionIdleSeconds", values.sessionIdleSeconds(), Integer.class);
        spec = bindNullable(spec, "maxInflightTokens", values.maxInflightTokens(), Long.class);
        spec = bindNullable(spec, "cooldownSeconds", values.cooldownSeconds(), Integer.class);
        spec = bindNullable(spec, "tlsProfile", values.tlsProfile(), String.class);
        return spec.map((row, metadata) -> row.get("account_id", String.class))
            .one()
            .switchIfEmpty(notFound(accountId))
            .then(databaseClient.sql("UPDATE account_active_sessions s SET expires_at=s.last_seen_at + make_interval(secs=>p.session_idle_seconds) FROM account_allocation_policies p WHERE s.account_id=p.account_id AND s.account_id=:id")
                .bind("id", accountId).fetch().rowsUpdated())
            .then(get(accountId));
    }

    private AccountDtos.AccountAllocationPolicyResponse response(Row row) {
        return new AccountDtos.AccountAllocationPolicyResponse(
            row.get("account_id", String.class),
            integer(row, "allocation_priority"),
            integer(row, "safe_concurrency"),
            integer(row, "safe_rpm"),
            integer(row, "burst_capacity"),
            integer(row, "max_sessions"),
            integer(row, "session_idle_seconds"),
            number(row, "max_inflight_tokens"),
            number(row, "policy_version"),
            row.get("lifecycle_state", String.class),
            number(row, "state_version"),
            integer(row, "warmup_stage"),
            instant(row, "cooldown_until"),
            decimal(row, "recent_error_ewma"),
            decimal(row, "recent_latency_ewma_ms"),
            integer(row, "active_concurrency"),
            number(row, "reserved_input_tokens"),
            number(row, "reserved_output_tokens"),
            instant(row, "updated_at"),
            integer(row, "cooldown_seconds"),
            row.get("tls_profile", String.class)
        );
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(
        DatabaseClient.GenericExecuteSpec spec,
        String name,
        T value,
        Class<T> type
    ) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static Integer integer(Row row, String name) {
        Number value = row.get(name, Number.class);
        return value == null ? null : value.intValue();
    }

    private static Long number(Row row, String name) {
        Number value = row.get(name, Number.class);
        return value == null ? null : value.longValue();
    }

    private static Double decimal(Row row, String name) {
        Number value = row.get(name, Number.class);
        return value == null ? null : value.doubleValue();
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

    private static <T> Mono<T> notFound(String accountId) {
        return Mono.error(new ApiException(
            HttpStatus.NOT_FOUND,
            POLICY_NOT_FOUND,
            "Account allocation policy not found: " + accountId
        ));
    }
}
