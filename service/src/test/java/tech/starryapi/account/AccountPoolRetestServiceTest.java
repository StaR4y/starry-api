package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.DataManagementSecurityService;
import tech.starryapi.admin.SystemMaintenanceTaskGate;

class AccountPoolRetestServiceTest {

    @Test
    void scheduledEvaluationSkipsPolicyWorkDuringMaintenance() {
        AccountPoolPolicyService policyService = mock(AccountPoolPolicyService.class);
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));
        AccountPoolRetestService service = new AccountPoolRetestService(
            policyService,
            new SystemMaintenanceTaskGate(securityService)
        );

        service.scheduledEvaluate();

        verifyNoInteractions(policyService);
        assertThat(service.isRunning()).isFalse();
    }
}
