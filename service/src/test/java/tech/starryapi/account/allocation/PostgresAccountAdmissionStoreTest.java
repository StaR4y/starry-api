package tech.starryapi.account.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class PostgresAccountAdmissionStoreTest {

    @Test
    void acquireUsesScopedFunctionAndBindsEveryAdmissionConstraint() {
        Harness harness = harness(new AccountAdmissionStore.AdmissionDecision(
            true,
            UUID.randomUUID(),
            null,
            18L,
            Instant.parse("2026-09-04T00:10:00Z")
        ));
        AccountAdmissionStore.AdmissionRequest request = new AccountAdmissionStore.AdmissionRequest(
            "account-1",
            "request-1",
            17L,
            "session-hash",
            true,
            1_200,
            800,
            Duration.ofSeconds(75),
            "openai:gpt-5",
            "/v1/responses"
        );

        AccountAdmissionStore.AdmissionDecision decision = harness.store().acquire(request).block();

        assertThat(decision).isNotNull();
        assertThat(harness.sql()).contains(
            "starry_api.try_acquire_scoped_account_lease(",
            ":modelScope",
            ":endpointScope"
        );
        verify(harness.spec()).bind("accountId", "account-1");
        verify(harness.spec()).bind("requestId", "request-1");
        verify(harness.spec()).bind("expectedStateVersion", 17L);
        verify(harness.spec()).bind("sessionHash", "session-hash");
        verify(harness.spec()).bind("enforceSessionLimit", true);
        verify(harness.spec()).bind("reservedInputTokens", 1_200L);
        verify(harness.spec()).bind("reservedOutputTokens", 800L);
        verify(harness.spec()).bind("modelScope", "openai:gpt-5");
        verify(harness.spec()).bind("endpointScope", "/v1/responses");
        verify(harness.spec()).bind("leaseSeconds", 75);
    }

    @Test
    void acquireBindsOptionalAdmissionInputsAsTypedNulls() {
        Harness harness = harness(new AccountAdmissionStore.AdmissionDecision(
            false,
            null,
            "ACCOUNT_COOLDOWN",
            4L,
            null
        ));
        AccountAdmissionStore.AdmissionRequest request = new AccountAdmissionStore.AdmissionRequest(
            "account-2",
            "request-2",
            null,
            " ",
            false,
            0,
            0,
            Duration.ofMinutes(10),
            " ",
            null
        );

        harness.store().acquire(request).block();

        verify(harness.spec()).bindNull("expectedStateVersion", Long.class);
        verify(harness.spec()).bindNull("sessionHash", String.class);
        verify(harness.spec()).bindNull("modelScope", String.class);
        verify(harness.spec()).bindNull("endpointScope", String.class);
    }

    @Test
    void reconcileBindsFailureScopeCooldownAndStructuredEvidence() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Harness harness = harness(true, objectMapper);
        UUID leaseId = UUID.randomUUID();
        AccountAdmissionStore.AdmissionResult result = new AccountAdmissionStore.AdmissionResult(
            leaseId,
            false,
            12,
            34,
            56,
            null,
            AccountFailureScope.CAPACITY_MODEL,
            Duration.ofSeconds(90),
            false,
            Map.of("model", "gpt-5", "endpoint", "/v1/responses")
        );

        harness.store().reconcile(result).block();

        assertThat(harness.sql()).contains("starry_api.reconcile_account_lease(", "CAST(:evidence AS jsonb)");
        verify(harness.spec()).bind("leaseId", leaseId);
        verify(harness.spec()).bind("success", false);
        verify(harness.spec()).bind("actualInputTokens", 12L);
        verify(harness.spec()).bind("actualOutputTokens", 34L);
        verify(harness.spec()).bind("latencyMs", 56L);
        verify(harness.spec()).bindNull("statusCode", Integer.class);
        verify(harness.spec()).bind("errorScope", "CAPACITY_MODEL");
        verify(harness.spec()).bind("cooldownSeconds", 90);
        verify(harness.spec()).bind("permanentCredentialFailure", false);
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(harness.spec()).bind(eq("evidence"), evidence.capture());
        assertThat(objectMapper.readTree(evidence.getValue()))
            .isEqualTo(objectMapper.readTree("{\"model\":\"gpt-5\",\"endpoint\":\"/v1/responses\"}"));
    }

    @Test
    void abandonUsesSafeReasonWhenCallerProvidesOnlyWhitespace() {
        Harness harness = harness(true);
        UUID leaseId = UUID.randomUUID();

        harness.store().abandon(leaseId, " ").block();

        assertThat(harness.sql()).contains("starry_api.abandon_account_lease(:leaseId, :reason)");
        verify(harness.spec()).bind("leaseId", leaseId);
        verify(harness.spec()).bind("reason", "PIPELINE_ABANDONED");
    }

    private static Harness harness(Object mappedResult) {
        return harness(mappedResult, new ObjectMapper());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Harness harness(Object mappedResult, ObjectMapper objectMapper) {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec rows = mock(RowsFetchSpec.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(databaseClient.sql(sql.capture())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.bindNull(anyString(), any())).thenReturn(spec);
        when(spec.map(any(BiFunction.class))).thenReturn(rows);
        when(rows.one()).thenReturn(Mono.just(mappedResult));
        return new Harness(
            new PostgresAccountAdmissionStore(databaseClient, objectMapper),
            spec,
            sql
        );
    }

    private record Harness(
        PostgresAccountAdmissionStore store,
        DatabaseClient.GenericExecuteSpec spec,
        ArgumentCaptor<String> capturedSql
    ) {
        private String sql() {
            return capturedSql.getValue();
        }
    }
}
