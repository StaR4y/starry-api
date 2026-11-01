package tech.starryapi.sourcetier;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;

/**
 * Holds the tiered-source configuration for the request path. It is read once before the web server takes requests
 * (an ApplicationRunner would be too late: Netty is already listening by then), refreshed whenever the configuration
 * version changes (RuntimeStateInvalidator) and every 30 seconds as a safety net. A failed refresh keeps the snapshot
 * in use; a first load that failed leaves {@link #loaded()} false, and {@link #snapshot()} then refuses to answer, which
 * the planner turns into a refusal before any reservation rather than pricing at factor 1 by accident. Until that first
 * load succeeds every request is refused, untiered vendors included, so it is not left to the 30-second refresh: it is
 * retried within a second or two, backing off to a few seconds, and the ordinary schedule takes over once it has worked.
 *
 * <p>Unlike the lazily reloaded caches, a restore or a reconnect of the notification listener does not drop the
 * snapshot: it only asks for a read at once. Dropping it would make every request refuse, untiered ones included, for
 * as long as that read takes (or fails). A read that started before the restore is followed by another one, so it cannot
 * leave pre-restore data behind; if the next read fails, the snapshot in use stays until a later one succeeds (30
 * seconds at most).
 */
@Service
public class SourceTierRegistry implements SmartInitializingSingleton {

    /** The system setting every configuration write bumps; its NOTIFY is what makes the nodes refresh. */
    public static final String CONFIG_VERSION_SETTING = "source_tier_config_version";

    private static final Logger log = LoggerFactory.getLogger(SourceTierRegistry.class);
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(15);
    /** How soon a failed first load is tried again, and the longest wait between two such tries. */
    private static final Duration FIRST_LOAD_RETRY = Duration.ofSeconds(1);
    private static final Duration FIRST_LOAD_RETRY_MAX = Duration.ofSeconds(5);

    private final Supplier<Mono<SourceTierSnapshot>> loader;
    private final SystemMaintenanceTaskGate maintenanceTaskGate;
    private final Scheduler retryScheduler;

    /** Null until a load has succeeded. */
    private volatile SourceTierSnapshot current;
    private Mono<Void> inFlightRefresh;
    private long refreshRequests;
    private final Disposable.Swap firstLoadRetry = Disposables.swap();

    @Autowired
    public SourceTierRegistry(
        DatabaseClient databaseClient,
        ReactiveTransactionManager transactionManager,
        SystemMaintenanceTaskGate maintenanceTaskGate
    ) {
        this(new SourceTierLoader(databaseClient, transactionManager)::load, maintenanceTaskGate);
    }

    SourceTierRegistry(Supplier<Mono<SourceTierSnapshot>> loader, SystemMaintenanceTaskGate maintenanceTaskGate) {
        this(loader, maintenanceTaskGate, Schedulers.parallel());
    }

    /** @param retryScheduler what the waits between the tries of a failed first load run on */
    SourceTierRegistry(Supplier<Mono<SourceTierSnapshot>> loader, SystemMaintenanceTaskGate maintenanceTaskGate, Scheduler retryScheduler) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.maintenanceTaskGate = Objects.requireNonNull(maintenanceTaskGate, "maintenanceTaskGate");
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            refresh().block();
        } catch (RuntimeException error) {
            log.error("Failed to load the source tier configuration; every request is refused until a load succeeds, so it is retried", error);
            retryFirstLoad(FIRST_LOAD_RETRY);
        }
    }

    /**
     * Tries the first load again, quickly, until one succeeds: with nothing loaded all traffic is refused, and the 30
     * second refresh would leave it so for that long after a transient database error at startup. The wait doubles
     * after each failed try up to {@link #FIRST_LOAD_RETRY_MAX}. It stops as soon as anything has loaded, whichever read
     * that was, and at shutdown.
     */
    private void retryFirstLoad(Duration wait) {
        firstLoadRetry.update(retryScheduler.schedule(() -> {
            if (loaded()) {
                return;
            }
            refresh().subscribe(
                null,
                error -> {
                    log.warn("The source tier configuration is still not loaded, trying again in {}: {}", wait, error.toString());
                    if (!loaded()) {
                        retryFirstLoad(wait.multipliedBy(2).compareTo(FIRST_LOAD_RETRY_MAX) > 0 ? FIRST_LOAD_RETRY_MAX : wait.multipliedBy(2));
                    }
                },
                () -> log.info("The source tier configuration loaded after a failed first attempt")
            );
        }, wait.toMillis(), TimeUnit.MILLISECONDS));
    }

    @jakarta.annotation.PreDestroy
    void stopFirstLoadRetry() {
        firstLoadRetry.dispose();
    }

    @Scheduled(fixedDelay = 30_000)
    public void scheduledRefresh() {
        maintenanceTaskGate.whenOperational(this::refresh)
            .subscribe(null, error -> log.warn("Failed to refresh the source tier configuration", error));
    }

    /** Whether a load has ever succeeded. Until it has, {@link #snapshot()} refuses to answer because nothing is known. */
    public boolean loaded() {
        return current != null;
    }

    /**
     * The configuration in use. Before the first successful load nothing is known, which is not the same as nothing
     * being configured: an empty answer would price a configured vendor at factor 1 and let exclusive relays serve
     * untiered traffic. So this fails with 503 TIER_CONFIG_UNAVAILABLE instead; {@link #tierOf} and
     * {@link #exclusiveXpAccountIds()} fail the same way.
     */
    public SourceTierSnapshot snapshot() {
        SourceTierSnapshot snapshot = current;
        if (snapshot == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TIER_CONFIG_UNAVAILABLE",
                "The source tier configuration has not been loaded yet");
        }
        return snapshot;
    }

    public Optional<Tier> tierOf(String vendor, ProxyAccountEntity account) {
        return snapshot().tierOf(vendor, account);
    }

    public Set<String> exclusiveXpAccountIds() {
        return snapshot().exclusiveXpAccountIds();
    }

    public boolean managesSetting(String settingKey) {
        return CONFIG_VERSION_SETTING.equals(settingKey);
    }

    /**
     * Reads the configuration again. One read runs at a time; a request that arrives while one is running makes it run
     * once more afterwards, since that read may have started before the change the request is about. The returned Mono
     * fails when the last read failed, in which case the snapshot in use is kept.
     */
    public Mono<Void> refresh() {
        return Mono.defer(this::singleFlightRefresh);
    }

    private synchronized Mono<Void> singleFlightRefresh() {
        refreshRequests++;
        if (inFlightRefresh != null) {
            return inFlightRefresh;
        }
        // cache() does not cancel the read when a subscriber gives up: a caller that goes away leaves it running, bounded
        // by LOAD_TIMEOUT, and later callers join it like any other.
        Mono<Void> created = Mono.defer(this::refreshPass).cache();
        inFlightRefresh = created;
        return created;
    }

    private Mono<Void> refreshPass() {
        long requests;
        synchronized (this) {
            requests = refreshRequests;
        }
        return Mono.defer(loader)
            .timeout(LOAD_TIMEOUT)
            .doOnNext(this::install)
            .then()
            .materialize()
            .flatMap(outcome -> afterPass(requests, outcome.getThrowable()));
    }

    private synchronized Mono<Void> afterPass(long passRequests, Throwable failure) {
        if (refreshRequests > passRequests) {
            return refreshPass();
        }
        inFlightRefresh = null;
        return failure == null ? Mono.empty() : Mono.error(failure);
    }

    private void install(SourceTierSnapshot snapshot) {
        SourceTierSnapshot previous = current;
        current = snapshot;
        if (!snapshot.isEmpty() && (previous == null || previous.configVersion() != snapshot.configVersion())) {
            log.info("Source tier configuration version {} is in use", snapshot.configVersion());
        }
    }
}
