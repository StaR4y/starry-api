package tech.starryapi.account.allocation;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemMaintenanceTaskGate;

@Service
public class AccountAllocationMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(AccountAllocationMaintenanceService.class);

    private final DatabaseClient databaseClient;
    private final boolean enabled;
    private final int leaseRetentionDays;
    private final int eventRetentionDays;
    private final int deleteBatchSize;
    private final int deleteMaxBatches;
    private final AtomicBoolean running = new AtomicBoolean();
    private SystemMaintenanceTaskGate maintenanceTaskGate =
        SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction();

    public AccountAllocationMaintenanceService(
        DatabaseClient databaseClient,
        @Value("${starry.account-allocation.maintenance-enabled:true}") boolean enabled,
        @Value("${starry.account-allocation.lease-retention-days:7}") int leaseRetentionDays,
        @Value("${starry.account-allocation.event-retention-days:90}") int eventRetentionDays,
        @Value("${starry.account-allocation.delete-batch-size:10000}") int deleteBatchSize,
        @Value("${starry.account-allocation.delete-max-batches:20}") int deleteMaxBatches
    ) {
        this.databaseClient = databaseClient;
        this.enabled = enabled;
        this.leaseRetentionDays = clamp(leaseRetentionDays, 1, 3650);
        this.eventRetentionDays = clamp(eventRetentionDays, 30, 3650);
        this.deleteBatchSize = clamp(deleteBatchSize, 100, 100_000);
        this.deleteMaxBatches = clamp(deleteMaxBatches, 1, 100);
    }

    @Autowired
    void setMaintenanceTaskGate(SystemMaintenanceTaskGate maintenanceTaskGate) {
        this.maintenanceTaskGate = maintenanceTaskGate;
    }

    @Scheduled(
        cron = "${starry.account-allocation.maintenance-cron:0 35 * * * *}",
        zone = "UTC"
    )
    public void scheduledMaintenance() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        maintenanceTaskGate.whenOperational(() -> prune(deleteMaxBatches))
            .doOnError(error -> log.warn("Failed account-allocation retention maintenance", error))
            .doFinally(signal -> running.set(false))
            .subscribe();
    }

    Mono<Void> maintain() {
        return prune(deleteMaxBatches);
    }

    private Mono<Void> prune(int remainingBatches) {
        return databaseClient.sql("""
                SELECT *
                FROM starry_api.prune_account_allocation_history(
                    :leaseRetentionDays,
                    :eventRetentionDays,
                    :batchSize
                )
                """)
            .bind("leaseRetentionDays", leaseRetentionDays)
            .bind("eventRetentionDays", eventRetentionDays)
            .bind("batchSize", deleteBatchSize)
            .fetch()
            .one()
            .defaultIfEmpty(Map.of())
            .flatMap(row -> reachedBatchLimit(row) && remainingBatches > 1
                ? prune(remainingBatches - 1)
                : Mono.empty());
    }

    private boolean reachedBatchLimit(Map<String, Object> row) {
        return count(row, "leases_deleted") >= deleteBatchSize
            || count(row, "events_deleted") >= deleteBatchSize
            || count(row, "sessions_deleted") >= deleteBatchSize
            || count(row, "cooldowns_deleted") >= deleteBatchSize
            || count(row, "response_owners_deleted") >= deleteBatchSize;
    }

    private static int count(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
