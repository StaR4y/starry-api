package tech.starryapi.account;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemMaintenanceTaskGate;

/** Periodically reconciles local circuit state with the shared request-log window. */
@Service
public class AccountPoolRetestService {

    private static final Logger log = LoggerFactory.getLogger(AccountPoolRetestService.class);

    private final AccountPoolPolicyService policyService;
    private final SystemMaintenanceTaskGate maintenanceTaskGate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AccountPoolRetestService(AccountPoolPolicyService policyService) {
        this(policyService, SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction());
    }

    @Autowired
    public AccountPoolRetestService(
        AccountPoolPolicyService policyService,
        SystemMaintenanceTaskGate maintenanceTaskGate
    ) {
        this.policyService = policyService;
        this.maintenanceTaskGate = maintenanceTaskGate;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledEvaluate() {
        maintenanceTaskGate.whenOperational(this::evaluateAndRetestIfNeeded).subscribe(
            null,
            error -> log.warn("Account pool circuit evaluation failed unexpectedly", error)
        );
    }

    public boolean isRunning() {
        return running.get();
    }

    Mono<Void> evaluateAndRetestIfNeeded() {
        if (!running.compareAndSet(false, true)) {
            return Mono.empty();
        }
        return policyService.evaluate()
            .then()
            .doFinally(signal -> running.set(false));
    }
}
