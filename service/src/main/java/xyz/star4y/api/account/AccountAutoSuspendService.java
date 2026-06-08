package xyz.star4y.api.account;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AccountAutoSuspendService {

    static final int FAILURE_THRESHOLD = 2;

    private static final Logger log = LoggerFactory.getLogger(AccountAutoSuspendService.class);

    private final AccountTestService accountTestService;
    private final AccountPoolService accountPoolService;
    private final Set<String> runningAccountIds = ConcurrentHashMap.newKeySet();

    public AccountAutoSuspendService(AccountTestService accountTestService, AccountPoolService accountPoolService) {
        this.accountTestService = accountTestService;
        this.accountPoolService = accountPoolService;
    }

    public void scheduleIfNeeded(AccountPoolService.RequestFailureRecord record) {
        testIfNeeded(record).subscribe(
            null,
            error -> log.warn("Automatic account health test failed unexpectedly", error)
        );
    }

    Mono<Void> testIfNeeded(AccountPoolService.RequestFailureRecord record) {
        if (record == null || record.accountId() == null || record.accountId().isBlank()) {
            return Mono.empty();
        }
        if (record.consecutiveFailures() != FAILURE_THRESHOLD) {
            return Mono.empty();
        }
        if (!runningAccountIds.add(record.accountId())) {
            return Mono.empty();
        }

        AccountDtos.TestAccountsRequest request = new AccountDtos.TestAccountsRequest(
            List.of(record.accountId()),
            false,
            "auto",
            null,
            1,
            true
        );
        return accountTestService.test(request)
            .doOnNext(response -> log.info(
                "Automatic account health test completed for {}: success={}, failed={}, skipped={}",
                record.accountId(),
                response.success(),
                response.failed(),
                response.skipped()
            ))
            .onErrorResume(error -> {
                String message = "Automatic account health test failed: " + error.getMessage();
                log.warn("Suspending account {} because automatic health test errored", record.accountId(), error);
                return accountPoolService.suspend(record.accountId(), "AUTO_TEST_FAILED", message).then(Mono.empty());
            })
            .doFinally(signal -> runningAccountIds.remove(record.accountId()))
            .then();
    }
}
