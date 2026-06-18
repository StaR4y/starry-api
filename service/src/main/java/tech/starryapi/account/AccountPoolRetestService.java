package tech.starryapi.account;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AccountPoolRetestService {

    private static final Logger log = LoggerFactory.getLogger(AccountPoolRetestService.class);

    private final AccountPoolPolicyService policyService;
    private final ProxyAccountRepository repository;
    private final AccountTestService accountTestService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AccountPoolRetestService(
        AccountPoolPolicyService policyService,
        ProxyAccountRepository repository,
        AccountTestService accountTestService
    ) {
        this.policyService = policyService;
        this.repository = repository;
        this.accountTestService = accountTestService;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledEvaluate() {
        evaluateAndRetestIfNeeded().subscribe(
            null,
            error -> log.warn("Account pool policy evaluation failed unexpectedly", error)
        );
    }

    public boolean isRunning() {
        return running.get();
    }

    Mono<Void> evaluateAndRetestIfNeeded() {
        return policyService.evaluate()
            .flatMap(evaluation -> evaluation.retestRecommended()
                ? runRetest(evaluation.settings())
                : Mono.empty());
    }

    private Mono<Void> runRetest(AccountDtos.PoolPolicySettings settings) {
        if (!running.compareAndSet(false, true)) {
            return Mono.empty();
        }
        policyService.markRetestStarted();
        return repository.findAll()
            .collectList()
            .flatMap(accounts -> {
                List<String> accountIds = accounts.stream()
                    .map(ProxyAccountEntity::getAccountId)
                    .filter(accountId -> accountId != null && !accountId.isBlank())
                    .sorted()
                    .toList();
                if (accountIds.isEmpty()) {
                    policyService.markRetestCompleted(0.0, 0);
                    return Mono.empty();
                }
                return accountTestService.test(new AccountDtos.TestAccountsRequest(
                        accountIds,
                        false,
                        "auto",
                        "Reply OK in one short sentence.",
                        settings.retestMaxConcurrency(),
                        true
                    ))
                    .doOnNext(response -> {
                        RetestRecoveryStats stats = retestRecoveryStats(response);
                        policyService.markRetestCompleted(stats.recoveryRatePercent(), stats.samples());
                        log.info(
                            "Account pool retest completed: total={}, success={}, failed={}, skipped={}, samples={}, recoveryRate={}%",
                            response.total(),
                            response.success(),
                            response.failed(),
                            response.skipped(),
                            stats.samples(),
                            stats.recoveryRatePercent()
                        );
                    })
                    .then();
            })
            .doFinally(signal -> running.set(false));
    }

    private RetestRecoveryStats retestRecoveryStats(AccountDtos.TestAccountsResponse response) {
        long samples = 0;
        long successes = 0;
        for (AccountDtos.TestAccountItemResponse item : response.items()) {
            if ("SKIPPED".equals(item.status())) {
                continue;
            }
            samples++;
            if ("SUCCESS".equals(item.status())) {
                successes++;
            }
        }
        double rate = samples <= 0 ? 0.0 : AccountPoolPolicyService.roundPercent(successes * 100.0 / samples);
        return new RetestRecoveryStats(samples, successes, rate);
    }

    private record RetestRecoveryStats(long samples, long successes, double recoveryRatePercent) {
    }
}
