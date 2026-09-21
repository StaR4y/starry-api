package tech.starryapi.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.DataManagementSecurityService;
import tech.starryapi.admin.SystemMaintenanceTaskGate;

class RequestUsageAggregationServiceTest {

    @Test
    void scheduledAggregationAndMaintenanceSkipSqlDuringMaintenance() {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));
        RequestUsageAggregationService service = service(databaseClient, 6);
        service.setMaintenanceTaskGate(new SystemMaintenanceTaskGate(securityService));

        service.scheduledAggregate();
        service.scheduledMaintenance();

        verifyNoInteractions(databaseClient);
    }

    @Test
    void runsSequentialBatchesUntilTheDatabaseReportsThatItIsCaughtUp() {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        AtomicInteger calls = new AtomicInteger();
        FetchSpec<Map<String, Object>> fetchSpec = mockFetchSpec(calls, 3);

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        RequestUsageAggregationService service = service(databaseClient, 6);

        service.refresh().block();

        assertThat(calls).hasValue(3);
        verify(databaseClient, times(3)).sql(anyString());
        verify(executeSpec, times(3)).bind("maxAdvanceHours", 1);
        verify(executeSpec, times(3)).bind("lookbackHours", 6);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(databaseClient, times(3)).sql(sql.capture());
        assertThat(sql.getAllValues()).allSatisfy(statement ->
            assertThat(statement).contains("SELECT COALESCE(", "TRUE", ") AS backlog_remaining")
        );
    }

    @Test
    void stopsAtTheConfiguredBatchLimitWhileBacklogRemains() {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        AtomicInteger calls = new AtomicInteger();
        FetchSpec<Map<String, Object>> fetchSpec = mockFetchSpec(calls, Integer.MAX_VALUE);

        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        RequestUsageAggregationService service = service(databaseClient, 4);

        service.refresh().block();

        assertThat(calls).hasValue(4);
        verify(databaseClient, times(4)).sql(anyString());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(databaseClient, times(4)).sql(sql.capture());
        assertThat(sql.getAllValues()).allSatisfy(statement ->
            assertThat(statement).contains("refresh_request_usage_aggregates(")
        );
    }

    @Test
    void maintenanceNeverDropsRawPartitionsWhileAggregationBacklogRemains() {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec ensureSpec = emptyAllSpec();
        DatabaseClient.GenericExecuteSpec refreshSpec = singleRowSpec(Map.of("backlog_remaining", true));
        DatabaseClient.GenericExecuteSpec hourlyPruneSpec = singleRowSpec(Map.of("deleted_count", 0));
        DatabaseClient.GenericExecuteSpec usagePruneSpec = singleRowSpec(Map.of("deleted_count", 0));

        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            String statement = invocation.getArgument(0);
            if (statement.contains("ensure_request_log_partitions")) {
                return ensureSpec;
            }
            if (statement.contains("refresh_request_usage_aggregates")) {
                return refreshSpec;
            }
            if (statement.contains("prune_request_usage_hourly")) {
                return hourlyPruneSpec;
            }
            if (statement.contains("prune_api_usage_events")) {
                return usagePruneSpec;
            }
            throw new AssertionError("Unexpected SQL: " + statement);
        });

        service(databaseClient, 2).maintain().block();

        verify(databaseClient, times(2)).sql(org.mockito.ArgumentMatchers.contains(
            "refresh_request_usage_aggregates"
        ));
        verify(databaseClient, never()).sql(org.mockito.ArgumentMatchers.contains(
            "drop_expired_request_log_partitions"
        ));
        verify(databaseClient, never()).sql(org.mockito.ArgumentMatchers.contains(
            "prune_expired_request_logs_default"
        ));
    }

    private static FetchSpec<Map<String, Object>> mockFetchSpec(
        AtomicInteger calls,
        int caughtUpOnCall
    ) {
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);
        when(fetchSpec.one()).thenReturn(Mono.defer(() -> {
            int call = calls.incrementAndGet();
            return Mono.just(Map.of("backlog_remaining", call < caughtUpOnCall));
        }));
        return fetchSpec;
    }

    private static DatabaseClient.GenericExecuteSpec emptyAllSpec() {
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.all()).thenReturn(Flux.empty());
        return executeSpec;
    }

    private static DatabaseClient.GenericExecuteSpec singleRowSpec(Map<String, Object> row) {
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> fetchSpec = mock(FetchSpec.class);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Mono.just(row));
        return executeSpec;
    }

    private static RequestUsageAggregationService service(DatabaseClient databaseClient, int maxBatchesPerRun) {
        return new RequestUsageAggregationService(
            databaseClient,
            true,
            1,
            6,
            maxBatchesPerRun,
            12,
            4,
            10_000,
            20,
            90,
            10_000,
            20,
            400,
            10_000,
            20
        );
    }
}
