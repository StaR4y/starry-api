package tech.starryapi.account.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.DataManagementSecurityService;
import tech.starryapi.admin.SystemMaintenanceTaskGate;

class AccountAllocationMaintenanceServiceTest {

    @Test
    void repeatsBoundedCleanupWhileAnyTableReturnsAFullBatch() {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        AtomicInteger calls = new AtomicInteger();
        when(databaseClient.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.one()).thenReturn(Mono.defer(() -> {
            int call = calls.incrementAndGet();
            return Mono.just(Map.of(
                "leases_deleted", call < 3 ? 100 : 0,
                "events_deleted", 0,
                "sessions_deleted", 0,
                "cooldowns_deleted", 0,
                "response_owners_deleted", 0
            ));
        }));
        AccountAllocationMaintenanceService service = service(databaseClient, true, 5);

        service.maintain().block();

        assertThat(calls).hasValue(3);
        verify(databaseClient, times(3)).sql(anyString());
        verify(spec, times(3)).bind("leaseRetentionDays", 7);
        verify(spec, times(3)).bind("eventRetentionDays", 90);
        verify(spec, times(3)).bind("batchSize", 100);
    }

    @Test
    void scheduledCleanupSkipsSqlDuringMaintenance() {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));
        AccountAllocationMaintenanceService service = service(databaseClient, true, 5);
        service.setMaintenanceTaskGate(new SystemMaintenanceTaskGate(securityService));

        service.scheduledMaintenance();

        verifyNoInteractions(databaseClient);
    }

    @Test
    void disabledMaintenanceDoesNotQueryTheDatabase() {
        DatabaseClient databaseClient = mock(DatabaseClient.class);

        service(databaseClient, false, 5).scheduledMaintenance();

        verifyNoInteractions(databaseClient);
    }

    private AccountAllocationMaintenanceService service(
        DatabaseClient databaseClient,
        boolean enabled,
        int maxBatches
    ) {
        return new AccountAllocationMaintenanceService(
            databaseClient,
            enabled,
            7,
            90,
            100,
            maxBatches
        );
    }
}
