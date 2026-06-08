package xyz.star4y.api.account;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AccountAutoSuspendServiceTest {

    @Test
    void skipsBeforeFailureThreshold() {
        AccountTestService testService = mock(AccountTestService.class);
        AccountAutoSuspendService service = new AccountAutoSuspendService(
            testService,
            mock(AccountPoolService.class)
        );

        service.testIfNeeded(new AccountPoolService.RequestFailureRecord("acc-1", 1, 500, false)).block();

        verify(testService, never()).test(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testsOnceWhenFailureThresholdIsReached() {
        AccountTestService testService = mock(AccountTestService.class);
        AccountAutoSuspendService service = new AccountAutoSuspendService(
            testService,
            mock(AccountPoolService.class)
        );
        when(testService.test(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(new AccountDtos.TestAccountsResponse(
                1,
                0,
                1,
                0,
                Instant.now(),
                Instant.now(),
                List.of()
            )));

        service.testIfNeeded(new AccountPoolService.RequestFailureRecord("acc-1", 2, 500, false)).block();

        verify(testService).test(argThat(request ->
            request.accountIds().equals(List.of("acc-1"))
                && Boolean.FALSE.equals(request.onlyEnabled())
                && Boolean.TRUE.equals(request.updateStatus())
                && request.maxConcurrency() == 1
        ));
    }
}
