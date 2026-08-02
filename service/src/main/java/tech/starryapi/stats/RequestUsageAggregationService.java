package tech.starryapi.stats;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RequestUsageAggregationService {

    private static final Logger log = LoggerFactory.getLogger(RequestUsageAggregationService.class);

    private final DatabaseClient databaseClient;
    private final boolean enabled;
    private final int aggregationMaxAdvanceHours;
    private final int aggregationLookbackHours;
    private final int aggregationMaxBatchesPerRun;
    private final int partitionMonthsAhead;
    private final int rawRetentionMonths;
    private final int rawDefaultDeleteBatchSize;
    private final int rawDefaultDeleteMaxBatches;
    private final int hourlyRetentionDays;
    private final int hourlyDeleteBatchSize;
    private final int hourlyDeleteMaxBatches;
    private final int usageEventRetentionDays;
    private final int usageEventDeleteBatchSize;
    private final int usageEventDeleteMaxBatches;
    private final AtomicBoolean running = new AtomicBoolean();

    public RequestUsageAggregationService(
        DatabaseClient databaseClient,
        @Value("${starry.stats.aggregation-enabled:true}") boolean enabled,
        @Value("${starry.stats.aggregation-max-advance-hours:1}") int aggregationMaxAdvanceHours,
        @Value("${starry.stats.aggregation-lookback-hours:6}") int aggregationLookbackHours,
        @Value("${starry.stats.aggregation-max-batches-per-run:6}") int aggregationMaxBatchesPerRun,
        @Value("${starry.stats.partition-months-ahead:12}") int partitionMonthsAhead,
        @Value("${starry.stats.raw-retention-months:4}") int rawRetentionMonths,
        @Value("${starry.stats.raw-default-delete-batch-size:10000}") int rawDefaultDeleteBatchSize,
        @Value("${starry.stats.raw-default-delete-max-batches:20}") int rawDefaultDeleteMaxBatches,
        @Value("${starry.stats.hourly-retention-days:90}") int hourlyRetentionDays,
        @Value("${starry.stats.hourly-delete-batch-size:10000}") int hourlyDeleteBatchSize,
        @Value("${starry.stats.hourly-delete-max-batches:20}") int hourlyDeleteMaxBatches,
        @Value("${starry.stats.usage-event-retention-days:400}") int usageEventRetentionDays,
        @Value("${starry.stats.usage-event-delete-batch-size:10000}") int usageEventDeleteBatchSize,
        @Value("${starry.stats.usage-event-delete-max-batches:20}") int usageEventDeleteMaxBatches
    ) {
        this.databaseClient = databaseClient;
        this.enabled = enabled;
        this.aggregationMaxAdvanceHours = clamp(aggregationMaxAdvanceHours, 1, 168);
        this.aggregationLookbackHours = clamp(aggregationLookbackHours, 1, 168);
        this.aggregationMaxBatchesPerRun = clamp(aggregationMaxBatchesPerRun, 1, 100);
        this.partitionMonthsAhead = clamp(partitionMonthsAhead, 1, 60);
        this.rawRetentionMonths = clamp(rawRetentionMonths, 4, 120);
        this.rawDefaultDeleteBatchSize = clamp(rawDefaultDeleteBatchSize, 100, 100_000);
        this.rawDefaultDeleteMaxBatches = clamp(rawDefaultDeleteMaxBatches, 1, 100);
        this.hourlyRetentionDays = clamp(hourlyRetentionDays, 7, 3_650);
        this.hourlyDeleteBatchSize = clamp(hourlyDeleteBatchSize, 100, 100_000);
        this.hourlyDeleteMaxBatches = clamp(hourlyDeleteMaxBatches, 1, 100);
        this.usageEventRetentionDays = clamp(usageEventRetentionDays, 30, 3_650);
        this.usageEventDeleteBatchSize = clamp(usageEventDeleteBatchSize, 100, 100_000);
        this.usageEventDeleteMaxBatches = clamp(usageEventDeleteMaxBatches, 1, 100);
    }

    @Scheduled(
        fixedDelayString = "${starry.stats.aggregation-interval:PT1M}",
        initialDelayString = "${starry.stats.aggregation-initial-delay:PT30S}"
    )
    public void scheduledAggregate() {
        if (enabled) {
            run("request usage aggregation", this::refresh);
        }
    }

    @Scheduled(cron = "${starry.stats.maintenance-cron:0 25 3 * * *}", zone = "UTC")
    public void scheduledMaintenance() {
        if (enabled) {
            run("request log maintenance", this::maintain);
        }
    }

    Mono<Void> refresh() {
        return refreshBatches(aggregationMaxBatchesPerRun).then();
    }

    private Mono<Boolean> refreshBatches(int remainingBatches) {
        return databaseClient.sql("""
                WITH refreshed AS MATERIALIZED (
                    SELECT starry_api.refresh_request_usage_aggregates(
                        :maxAdvanceHours,
                        :lookbackHours
                    ) AS processed_until
                )
                SELECT COALESCE(
                    processed_until < pg_catalog.date_trunc(
                        'hour',
                        CURRENT_TIMESTAMP - INTERVAL '5 minutes',
                        'UTC'
                    ),
                    TRUE
                ) AS backlog_remaining
                FROM refreshed
                """)
            .bind("maxAdvanceHours", aggregationMaxAdvanceHours)
            .bind("lookbackHours", aggregationLookbackHours)
            .fetch()
            .one()
            .map(row -> Boolean.TRUE.equals(row.get("backlog_remaining")))
            .defaultIfEmpty(false)
            .flatMap(backlogRemaining -> {
                if (!backlogRemaining) {
                    return Mono.just(true);
                }
                if (remainingBatches <= 1) {
                    log.debug(
                        "Request usage aggregation backlog remains after {} batch(es)",
                        aggregationMaxBatchesPerRun
                    );
                    return Mono.just(false);
                }
                return refreshBatches(remainingBatches - 1);
            });
    }

    Mono<Void> maintain() {
        return databaseClient.sql("SELECT starry_api.ensure_request_log_partitions(:monthsAhead, 1)")
                .bind("monthsAhead", partitionMonthsAhead)
                .fetch()
                .all()
                .then()
            .then(refreshBatches(aggregationMaxBatchesPerRun))
            .flatMap(caughtUp -> dropExpiredRequestLogPartitions(caughtUp)
            .then(pruneExpiredHourlyAggregates(hourlyDeleteMaxBatches))
            .then(pruneExpiredUsageEvents(usageEventDeleteMaxBatches)));
    }

    private Mono<Void> dropExpiredRequestLogPartitions(boolean aggregationCaughtUp) {
        if (!aggregationCaughtUp) {
            log.warn("Skipping raw request-log retention because aggregation backlog remains");
            return Mono.empty();
        }
        return databaseClient.sql("SELECT starry_api.drop_expired_request_log_partitions(:monthsToKeep)")
            .bind("monthsToKeep", rawRetentionMonths)
            .fetch()
            .all()
            .then()
            .then(pruneExpiredRequestLogsDefault(rawDefaultDeleteMaxBatches));
    }

    private Mono<Void> pruneExpiredRequestLogsDefault(int remainingBatches) {
        return databaseClient.sql("""
                SELECT starry_api.prune_expired_request_logs_default(
                    :monthsToKeep,
                    :batchSize
                ) AS deleted_count
                """)
            .bind("monthsToKeep", rawRetentionMonths)
            .bind("batchSize", rawDefaultDeleteBatchSize)
            .fetch()
            .one()
            .map(row -> ((Number) row.get("deleted_count")).intValue())
            .defaultIfEmpty(0)
            .flatMap(deletedRows -> deletedRows >= rawDefaultDeleteBatchSize && remainingBatches > 1
                ? pruneExpiredRequestLogsDefault(remainingBatches - 1)
                : Mono.empty());
    }

    private Mono<Void> pruneExpiredHourlyAggregates(int remainingBatches) {
        return databaseClient.sql("""
                SELECT starry_api.prune_request_usage_hourly(
                    :retentionDays,
                    :batchSize
                ) AS deleted_count
                """)
            .bind("retentionDays", hourlyRetentionDays)
            .bind("batchSize", hourlyDeleteBatchSize)
            .fetch()
            .one()
            .map(row -> ((Number) row.get("deleted_count")).intValue())
            .defaultIfEmpty(0)
            .flatMap(deletedRows -> deletedRows >= hourlyDeleteBatchSize && remainingBatches > 1
                ? pruneExpiredHourlyAggregates(remainingBatches - 1)
                : Mono.empty());
    }

    private Mono<Void> pruneExpiredUsageEvents(int remainingBatches) {
        return databaseClient.sql("""
                SELECT starry_api.prune_api_usage_events(:retentionDays, :batchSize) AS deleted_count
                """)
            .bind("retentionDays", usageEventRetentionDays)
            .bind("batchSize", usageEventDeleteBatchSize)
            .fetch()
            .one()
            .map(row -> ((Number) row.get("deleted_count")).intValue())
            .defaultIfEmpty(0)
            .flatMap(deletedRows -> deletedRows >= usageEventDeleteBatchSize && remainingBatches > 1
                ? pruneExpiredUsageEvents(remainingBatches - 1)
                : Mono.empty());
    }

    private void run(String taskName, Supplier<Mono<Void>> task) {
        if (!running.compareAndSet(false, true)) {
            log.debug("Skipping {} because another stats maintenance task is running", taskName);
            return;
        }
        task.get()
            .doOnSuccess(ignored -> log.debug("Completed {}", taskName))
            .doOnError(error -> log.warn("Failed {}", taskName, error))
            .doFinally(signal -> running.set(false))
            .subscribe();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
