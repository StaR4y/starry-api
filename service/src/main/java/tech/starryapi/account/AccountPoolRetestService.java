package tech.starryapi.account;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
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
                Map<String, ProxyAccountEntity> byId = accounts.stream()
                    .filter(account -> account.getAccountId() != null && !account.getAccountId().isBlank())
                    .collect(Collectors.toMap(ProxyAccountEntity::getAccountId, Function.identity(), (left, right) -> left));
                if (byId.isEmpty()) {
                    policyService.markRetestCompleted(0.0, 0);
                    return Mono.empty();
                }
                return accountTestService.test(new AccountDtos.TestAccountsRequest(
                        byId.keySet().stream().sorted().toList(),
                        false,
                        "auto",
                        "Reply OK in one short sentence.",
                        settings.retestMaxConcurrency(),
                        true
                    ))
                    .doOnNext(response -> {
                        PrimaryRetestStats stats = primaryRetestStats(response, byId);
                        policyService.markRetestCompleted(stats.failureRatePercent(), stats.samples());
                        log.info(
                            "Account pool retest completed: total={}, success={}, failed={}, skipped={}, primarySamples={}, primaryFailureRate={}%",
                            response.total(),
                            response.success(),
                            response.failed(),
                            response.skipped(),
                            stats.samples(),
                            stats.failureRatePercent()
                        );
                    })
                    .then();
            })
            .doFinally(signal -> running.set(false));
    }

    private PrimaryRetestStats primaryRetestStats(
        AccountDtos.TestAccountsResponse response,
        Map<String, ProxyAccountEntity> accounts
    ) {
        long samples = 0;
        long failures = 0;
        for (AccountDtos.TestAccountItemResponse item : response.items()) {
            ProxyAccountEntity account = accounts.get(item.accountId());
            if (account == null || !AccountPoolPolicyService.PRIMARY_TIER.equals(AccountPoolPolicyService.normalizePoolTier(account.getPoolTier()))) {
                continue;
            }
            if ("SKIPPED".equals(item.status())) {
                continue;
            }
            samples++;
            if (!"SUCCESS".equals(item.status())) {
                failures++;
            }
        }
        double rate = samples <= 0 ? 0.0 : AccountPoolPolicyService.roundPercent(failures * 100.0 / samples);
        return new PrimaryRetestStats(samples, failures, rate);
    }

    private record PrimaryRetestStats(long samples, long failures, double failureRatePercent) {
    }
}
