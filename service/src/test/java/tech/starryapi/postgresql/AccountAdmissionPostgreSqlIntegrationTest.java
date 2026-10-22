package tech.starryapi.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import tech.starryapi.account.allocation.AccountAdmissionStore;
import tech.starryapi.account.allocation.AccountFailureScope;
import tech.starryapi.account.allocation.PostgresAccountAdmissionStore;
import tech.starryapi.account.AccountAllocationPolicyService;
import tech.starryapi.account.AccountDtos;
import tech.starryapi.common.ApiException;
import tech.starryapi.platform.openai.OpenAiResponseOwnerStore;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.main.lazy-initialization=true",
        "spring.jmx.enabled=false",
        "spring.task.scheduling.enabled=false",
        "starry.stats.aggregation-enabled=false"
    }
)
@Timeout(value = 3, unit = java.util.concurrent.TimeUnit.MINUTES)
class AccountAdmissionPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

    @Autowired
    private PostgresAccountAdmissionStore admissionStore;

    @Autowired
    private OpenAiResponseOwnerStore responseOwnerStore;

    @Autowired
    private AccountAllocationPolicyService allocationPolicyService;

    @Autowired
    private DatabaseClient databaseClient;

    @Test
    void migrationProvidesAtomicAdmissionReconciliationAndResponseOwnership() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String accountId = "allocation-" + suffix;
        databaseClient.sql("""
                INSERT INTO proxy_accounts (account_id, access_token, provider, auth_method, enabled)
                VALUES (:accountId, 'test-token', 'Anthropic', 'api-key', TRUE)
                """)
            .bind("accountId", accountId)
            .fetch()
            .rowsUpdated()
            .block();

        databaseClient.sql("UPDATE account_allocation_policies SET safe_concurrency=1 WHERE account_id=:id")
            .bind("id", accountId).fetch().rowsUpdated().block();
        AccountAdmissionStore.AdmissionDecision first = admissionStore.acquire(request(accountId, "request-a-" + suffix)).block();
        AccountAdmissionStore.AdmissionDecision second = admissionStore.acquire(request(accountId, "request-b-" + suffix)).block();

        assertThat(first).isNotNull();
        assertThat(first.accepted()).isTrue();
        assertThat(first.leaseId()).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.accepted()).isFalse();
        assertThat(second.rejectionReason()).isEqualTo("CONCURRENCY_LIMIT");

        admissionStore.markDispatched(first.leaseId()).block();
        admissionStore.reconcile(new AccountAdmissionStore.AdmissionResult(
            first.leaseId(),
            true,
            90,
            180,
            1200,
            200,
            AccountFailureScope.UNKNOWN,
            Duration.ZERO,
            false,
            Map.of("model", "claude-test")
        )).block();

        AdmissionState state = databaseClient.sql("""
                SELECT admission.active_concurrency,
                       admission.reserved_input_tokens,
                       admission.reserved_output_tokens,
                       lifecycle.lifecycle_state,
                       lifecycle.warmup_stage
                FROM account_admission_states admission
                JOIN account_allocation_states lifecycle USING (account_id)
                WHERE account_id = :accountId
                """)
            .bind("accountId", accountId)
            .map((row, metadata) -> new AdmissionState(
                row.get("active_concurrency", Integer.class),
                row.get("reserved_input_tokens", Long.class),
                row.get("reserved_output_tokens", Long.class),
                row.get("lifecycle_state", String.class),
                row.get("warmup_stage", Short.class)
            ))
            .one()
            .block();
        assertThat(state).isEqualTo(new AdmissionState(0, 0L, 0L, "HEALTHY", (short) 4));

        String responseId = "resp_" + suffix;
        responseOwnerStore.remember(responseId, accountId).block();
        assertThat(responseOwnerStore.findOwner(responseId).block()).isEqualTo(accountId);
    }

    @Test
    void expiredLeaseCountersArePersistedEvenWhenTheNextAdmissionIsRejected() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String accountId = "expired-reclaim-" + suffix;
        insertAccount(accountId, "OpenAI", "api-key");
        AccountAdmissionStore.AdmissionDecision first = admissionStore.acquire(scopedRequest(
            accountId,
            "expired-first-" + suffix,
            null,
            "gpt-5",
            "/v1/chat/completions"
        )).block();
        assertThat(first).isNotNull();
        assertThat(first.accepted()).isTrue();

        databaseClient.sql("""
                UPDATE account_admission_leases
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE lease_id = :leaseId
                """)
            .bind("leaseId", first.leaseId())
            .fetch()
            .rowsUpdated()
            .block();
        databaseClient.sql("UPDATE proxy_accounts SET enabled = FALSE WHERE account_id = :accountId")
            .bind("accountId", accountId)
            .fetch()
            .rowsUpdated()
            .block();

        AccountAdmissionStore.AdmissionDecision rejected = admissionStore.acquire(scopedRequest(
            accountId,
            "expired-rejected-" + suffix,
            null,
            "gpt-5",
            "/v1/chat/completions"
        )).block();

        assertThat(rejected).isNotNull();
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.rejectionReason()).isEqualTo("ACCOUNT_DISABLED");
        ReclaimedLeaseState state = databaseClient.sql("""
                SELECT admission.active_concurrency,
                       admission.reserved_input_tokens,
                       admission.reserved_output_tokens,
                       lease.dispatch_state
                FROM account_admission_states admission
                JOIN account_admission_leases lease ON lease.account_id = admission.account_id
                WHERE lease.lease_id = :leaseId
                """)
            .bind("leaseId", first.leaseId())
            .map((row, metadata) -> new ReclaimedLeaseState(
                row.get("active_concurrency", Integer.class),
                row.get("reserved_input_tokens", Long.class),
                row.get("reserved_output_tokens", Long.class),
                row.get("dispatch_state", String.class)
            ))
            .one()
            .block();
        assertThat(state).isEqualTo(new ReclaimedLeaseState(0, 0L, 0L, "EXPIRED"));
    }

    @Test
    void abandoningALeaseReleasesReservationsAndRecordsItsTerminalState() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String accountId = "abandon-" + suffix;
        insertAccount(accountId, "OpenAI", "api-key");
        AccountAdmissionStore.AdmissionDecision decision = admissionStore.acquire(scopedRequest(
            accountId,
            "abandon-request-" + suffix,
            null,
            "gpt-5",
            "/v1/chat/completions"
        )).block();
        assertThat(decision).isNotNull();
        assertThat(decision.accepted()).isTrue();

        admissionStore.abandon(decision.leaseId(), "CLIENT_CANCELLED").block();

        ReclaimedLeaseState state = databaseClient.sql("""
                SELECT admission.active_concurrency,
                       admission.reserved_input_tokens,
                       admission.reserved_output_tokens,
                       lease.dispatch_state
                FROM account_admission_states admission
                JOIN account_admission_leases lease ON lease.account_id = admission.account_id
                WHERE lease.lease_id = :leaseId
                """)
            .bind("leaseId", decision.leaseId())
            .map((row, metadata) -> new ReclaimedLeaseState(
                row.get("active_concurrency", Integer.class),
                row.get("reserved_input_tokens", Long.class),
                row.get("reserved_output_tokens", Long.class),
                row.get("dispatch_state", String.class)
            ))
            .one()
            .block();
        assertThat(state).isEqualTo(new ReclaimedLeaseState(0, 0L, 0L, "ABANDONED"));
        Integer events = databaseClient.sql("""
                SELECT count(*)::INTEGER AS event_count
                FROM account_allocation_events
                WHERE account_id = :accountId AND event_type = 'ADMISSION_ABANDONED'
                """)
            .bind("accountId", accountId)
            .map((row, metadata) -> row.get("event_count", Integer.class))
            .one()
            .block();
        assertThat(events).isEqualTo(1);
    }

    @Test
    void requestFailureReconciliationDoesNotMutateAccountWideHealth() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String accountId = "request-scope-" + suffix;
        insertAccount(accountId, "OpenAI", "api-key");
        LifecycleSnapshot before = lifecycle(accountId);
        AccountAdmissionStore.AdmissionDecision decision = admissionStore.acquire(scopedRequest(
            accountId,
            "request-scope-" + suffix,
            null,
            "gpt-5",
            "/v1/chat/completions"
        )).block();
        admissionStore.markDispatched(decision.leaseId()).block();

        admissionStore.reconcile(new AccountAdmissionStore.AdmissionResult(
            decision.leaseId(),
            false,
            0,
            0,
            900,
            400,
            AccountFailureScope.REQUEST,
            Duration.ZERO,
            false,
            Map.of("message", "invalid request")
        )).block();

        assertThat(lifecycle(accountId)).isEqualTo(before);
        Integer activeConcurrency = databaseClient.sql("""
                SELECT active_concurrency
                FROM account_admission_states
                WHERE account_id = :accountId
                """)
            .bind("accountId", accountId)
            .map((row, metadata) -> row.get("active_concurrency", Integer.class))
            .one()
            .block();
        assertThat(activeConcurrency).isZero();
    }

    @Test
    void nullExpectedVersionUsesTheLockedLifecycleStateInsteadOfAStaleSnapshot() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String accountId = "live-version-" + suffix;
        insertAccount(accountId, "OpenAI", "api-key");
        databaseClient.sql("""
                UPDATE account_allocation_states
                SET state_version = state_version + 5
                WHERE account_id = :accountId
                """)
            .bind("accountId", accountId)
            .fetch()
            .rowsUpdated()
            .block();

        AccountAdmissionStore.AdmissionDecision stale = admissionStore.acquire(scopedRequest(
            accountId,
            "stale-version-" + suffix,
            1L,
            "gpt-5",
            "/v1/chat/completions"
        )).block();
        AccountAdmissionStore.AdmissionDecision current = admissionStore.acquire(scopedRequest(
            accountId,
            "live-version-" + suffix,
            null,
            "gpt-5",
            "/v1/chat/completions"
        )).block();

        assertThat(stale).isNotNull();
        assertThat(stale.accepted()).isFalse();
        assertThat(stale.rejectionReason()).isEqualTo("STALE_STATE");
        assertThat(current).isNotNull();
        assertThat(current.accepted()).isTrue();
        admissionStore.abandon(current.leaseId(), "TEST_COMPLETE").block();
    }

    @Test
    void modelScopedRateLimitBlocksOnlyThatAccountAndModel() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String accountId = "model-cooldown-" + suffix;
        insertAccount(accountId, "OpenAI", "api-key");
        LifecycleSnapshot before = lifecycle(accountId);
        AccountAdmissionStore.AdmissionDecision first = admissionStore.acquire(scopedRequest(
            accountId,
            "model-limited-" + suffix,
            null,
            "gpt-limited",
            "/v1/chat/completions"
        )).block();
        admissionStore.markDispatched(first.leaseId()).block();
        admissionStore.reconcile(new AccountAdmissionStore.AdmissionResult(
            first.leaseId(),
            false,
            0,
            0,
            100,
            429,
            AccountFailureScope.CAPACITY_MODEL,
            Duration.ofMinutes(1),
            false,
            Map.of(
                "model", "gpt-limited",
                "upstreamModel", "gpt-limited",
                "endpoint", "/v1/chat/completions",
                "cooldownSeconds", 60
            )
        )).block();

        AccountAdmissionStore.AdmissionDecision sameModel = admissionStore.acquire(scopedRequest(
            accountId,
            "same-model-" + suffix,
            null,
            "gpt-limited",
            "/v1/chat/completions"
        )).block();
        databaseClient.sql("""
                UPDATE account_admission_states
                SET rpm_tokens = 1,
                    burst_tokens = 1,
                    rpm_refilled_at = CURRENT_TIMESTAMP,
                    burst_refilled_at = CURRENT_TIMESTAMP
                WHERE account_id = :accountId
                """)
            .bind("accountId", accountId)
            .fetch()
            .rowsUpdated()
            .block();
        AccountAdmissionStore.AdmissionDecision otherModel = admissionStore.acquire(scopedRequest(
            accountId,
            "other-model-" + suffix,
            null,
            "gpt-available",
            "/v1/chat/completions"
        )).block();

        assertThat(lifecycle(accountId)).isEqualTo(before);
        assertThat(sameModel).isNotNull();
        assertThat(sameModel.accepted()).isFalse();
        assertThat(sameModel.rejectionReason()).isEqualTo("MODEL_COOLDOWN");
        assertThat(sameModel.expiresAt()).isAfter(java.time.Instant.now());
        assertThat(otherModel).isNotNull();
        assertThat(otherModel.accepted()).isTrue();
        admissionStore.abandon(otherModel.leaseId(), "TEST_COMPLETE").block();
    }

    @Test
    void allocationPolicyApiUpdatesAnthropicLimitsAndKeepsOpenAiSessionsStateless() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String anthropicAccountId = "anthropic-policy-" + suffix;
        String openAiAccountId = "openai-policy-" + suffix;
        insertAccount(anthropicAccountId, "Anthropic", "api-key");
        insertAccount(openAiAccountId, "OpenAI", "api-key");

        AccountDtos.AccountAllocationPolicyResponse initial = allocationPolicyService.get(anthropicAccountId).block();
        assertThat(initial).isNotNull();
        assertThat(initial.safeConcurrency()).isEqualTo(14);
        assertThat(initial.maxSessions()).isZero();

        AccountDtos.AccountAllocationPolicyResponse anthropic = allocationPolicyService.update(
            anthropicAccountId,
            new AccountDtos.UpdateAccountAllocationPolicyRequest(25, 6, null, null, 12, 900, null)
        ).block();
        assertThat(anthropic).isNotNull();
        assertThat(anthropic.allocationPriority()).isEqualTo(25);
        assertThat(anthropic.safeConcurrency()).isEqualTo(6);
        assertThat(anthropic.safeRpm()).isEqualTo(initial.safeRpm());
        assertThat(anthropic.maxSessions()).isEqualTo(12);
        assertThat(anthropic.sessionIdleSeconds()).isEqualTo(900);
        assertThat(anthropic.policyVersion()).isEqualTo(initial.policyVersion() + 1);

        AccountDtos.AccountAllocationPolicyResponse openAi = allocationPolicyService.update(
            openAiAccountId,
            new AccountDtos.UpdateAccountAllocationPolicyRequest(null, null, null, null, 99, null, null)
        ).block();
        assertThat(openAi).isNotNull();
        assertThat(openAi.maxSessions()).isZero();

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> allocationPolicyService.get("missing-" + suffix).block()
        )
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getCode())
            .isEqualTo("ACCOUNT_ALLOCATION_POLICY_NOT_FOUND");
    }

    @Test
    void officialDefaultsAndUnlimitedThirdPartyAdmission() {
        String official = "official-" + UUID.randomUUID();
        insertAccount(official, "OpenAI", "api-key");
        var decisions = reactor.core.publisher.Flux.range(0, 20)
            .flatMap(i -> admissionStore.acquire(scopedRequest(official, official + i, null, "gpt-test", "/v1/responses")), 8)
            .collectList().block();
        assertThat(decisions.stream().filter(AccountAdmissionStore.AdmissionDecision::accepted)).hasSize(14);
        assertThat(decisions.stream().filter(d -> !d.accepted())).allMatch(d -> "CONCURRENCY_LIMIT".equals(d.rejectionReason()));
        decisions.stream().filter(AccountAdmissionStore.AdmissionDecision::accepted).forEach(d -> admissionStore.abandon(d.leaseId(), "TEST").block());

        for (String url : java.util.List.of("https://api.openai.com.evil.example/v1", "http://api.openai.com/v1", "https://relay.example/v1")) {
            String id = "thirdparty-" + UUID.randomUUID();
            databaseClient.sql("INSERT INTO proxy_accounts(account_id,access_token,provider,auth_method,base_url,enabled) VALUES(:id,'test','OpenAI','api-key',:url,TRUE)")
                .bind("id", id).bind("url", url).fetch().rowsUpdated().block();
            assertThat(allocationPolicyService.get(id).block().safeConcurrency()).isZero();
            var unlimited = reactor.core.publisher.Flux.range(0, 20)
                .concatMap(i -> admissionStore.acquire(scopedRequest(id, id + i, null, "gpt-test", "/v1/responses"))).collectList().block();
            assertThat(unlimited).allMatch(AccountAdmissionStore.AdmissionDecision::accepted);
            unlimited.forEach(d -> admissionStore.abandon(d.leaseId(), "TEST").block());
        }
    }

    @Test
    void sessionWindowCountsDistinctSessionsAndHonorsWindowChanges() {
        String id = "session-window-" + UUID.randomUUID();
        insertAccount(id, "Anthropic", "api-key");
        databaseClient.sql("UPDATE account_allocation_policies SET max_sessions=2,session_idle_seconds=600 WHERE account_id=:id")
            .bind("id", id).fetch().rowsUpdated().block();
        for (String session : java.util.List.of("a", "b", "a")) {
            var admitted = admissionStore.acquire(sessionRequest(id, session)).block();
            assertThat(admitted.accepted()).isTrue();
            admissionStore.abandon(admitted.leaseId(), "TEST").block();
        }
        assertThat(admissionStore.acquire(sessionRequest(id, "c")).block().rejectionReason()).isEqualTo("SESSION_LIMIT");
        databaseClient.sql("UPDATE account_active_sessions SET last_seen_at=clock_timestamp()-INTERVAL '2 minutes' WHERE account_id=:id")
            .bind("id", id).fetch().rowsUpdated().block();
        databaseClient.sql("UPDATE account_allocation_policies SET session_idle_seconds=60 WHERE account_id=:id")
            .bind("id", id).fetch().rowsUpdated().block();
        assertThat(admissionStore.acquire(sessionRequest(id, "c")).block().accepted()).isTrue();
    }

    @Test
    void customCooldownOverridesAutomaticDelayAndLateSuccessDoesNotClearIt() {
        String id = "custom-cooldown-" + UUID.randomUUID();
        insertAccount(id, "Anthropic", "api-key");
        databaseClient.sql("UPDATE account_allocation_policies SET cooldown_seconds=5 WHERE account_id=:id")
            .bind("id", id).fetch().rowsUpdated().block();
        var failure = admissionStore.acquire(sessionRequest(id, "a")).block();
        var success = admissionStore.acquire(sessionRequest(id, "b")).block();
        admissionStore.reconcile(new AccountAdmissionStore.AdmissionResult(failure.leaseId(), false, 0, 0, 100, 429,
            AccountFailureScope.CAPACITY_ACCOUNT, Duration.ofSeconds(45), false, Map.of())).block();
        var cooled = allocationPolicyService.get(id).block();
        assertThat(cooled.cooldownUntil()).isBefore(java.time.Instant.now().plusSeconds(6)).isAfter(java.time.Instant.now());
        admissionStore.reconcile(new AccountAdmissionStore.AdmissionResult(success.leaseId(), true, 0, 0, 100, 200,
            AccountFailureScope.UNKNOWN, Duration.ZERO, false, Map.of())).block();
        var afterSuccess = allocationPolicyService.get(id).block();
        assertThat(afterSuccess.cooldownUntil()).isEqualTo(cooled.cooldownUntil());
        assertThat(afterSuccess.lifecycleState()).isEqualTo("COOLDOWN");
    }

    @Test
    void upstreamRetryAfterIsMinimumCooldown() {
        String id = "retry-after-" + UUID.randomUUID();
        insertAccount(id, "Anthropic", "api-key");
        databaseClient.sql("UPDATE account_allocation_policies SET cooldown_seconds=5 WHERE account_id=:id")
            .bind("id", id).fetch().rowsUpdated().block();
        var lease = admissionStore.acquire(sessionRequest(id, "a")).block();
        admissionStore.reconcile(new AccountAdmissionStore.AdmissionResult(lease.leaseId(), false, 0, 0, 100, 429,
            AccountFailureScope.CAPACITY_ACCOUNT, Duration.ofSeconds(45), false, Map.of("retryAfterSeconds", 30))).block();
        assertThat(allocationPolicyService.get(id).block().cooldownUntil()).isAfter(java.time.Instant.now().plusSeconds(25));
    }

    private AccountAdmissionStore.AdmissionRequest sessionRequest(String id, String session) {
        return new AccountAdmissionStore.AdmissionRequest(id, UUID.randomUUID().toString(), null,
            tech.starryapi.common.Hashing.sha256(session), true, 100, 200, Duration.ofMinutes(10));
    }

    private void insertAccount(String accountId, String provider, String authMethod) {
        databaseClient.sql("""
                INSERT INTO proxy_accounts (account_id, access_token, provider, auth_method, enabled)
                VALUES (:accountId, 'test-token', :provider, :authMethod, TRUE)
                """)
            .bind("accountId", accountId)
            .bind("provider", provider)
            .bind("authMethod", authMethod)
            .fetch()
            .rowsUpdated()
            .block();
    }

    private AccountAdmissionStore.AdmissionRequest request(String accountId, String requestId) {
        return new AccountAdmissionStore.AdmissionRequest(
            accountId,
            requestId,
            1L,
            "a".repeat(64),
            true,
            100,
            200,
            Duration.ofMinutes(10)
        );
    }

    private AccountAdmissionStore.AdmissionRequest scopedRequest(
        String accountId,
        String requestId,
        Long expectedStateVersion,
        String modelScope,
        String endpointScope
    ) {
        return new AccountAdmissionStore.AdmissionRequest(
            accountId,
            requestId,
            expectedStateVersion,
            null,
            false,
            100,
            200,
            Duration.ofMinutes(10),
            modelScope,
            endpointScope
        );
    }

    private LifecycleSnapshot lifecycle(String accountId) {
        return databaseClient.sql("""
                SELECT lifecycle_state,
                       state_version,
                       warmup_stage,
                       recent_error_ewma,
                       recent_latency_ewma_ms,
                       risk_score,
                       state_reason
                FROM account_allocation_states
                WHERE account_id = :accountId
                """)
            .bind("accountId", accountId)
            .map((row, metadata) -> new LifecycleSnapshot(
                row.get("lifecycle_state", String.class),
                ((Number) row.get("state_version")).longValue(),
                ((Number) row.get("warmup_stage")).shortValue(),
                ((Number) row.get("recent_error_ewma")).doubleValue(),
                ((Number) row.get("recent_latency_ewma_ms")).doubleValue(),
                ((Number) row.get("risk_score")).doubleValue(),
                row.get("state_reason", String.class)
            ))
            .one()
            .block();
    }

    private record AdmissionState(
        Integer activeConcurrency,
        Long reservedInputTokens,
        Long reservedOutputTokens,
        String lifecycleState,
        Short warmupStage
    ) {
    }

    private record ReclaimedLeaseState(
        Integer activeConcurrency,
        Long reservedInputTokens,
        Long reservedOutputTokens,
        String dispatchState
    ) {
    }

    private record LifecycleSnapshot(
        String lifecycleState,
        long stateVersion,
        short warmupStage,
        double recentErrorEwma,
        double recentLatencyEwmaMs,
        double riskScore,
        String stateReason
    ) {
    }
}
