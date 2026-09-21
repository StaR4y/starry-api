package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

class AccountAdminControllerTest {

    @Test
    void allocationPolicyRequiresAdminAndReturnsSnapshot() {
        AccountAllocationPolicyService policyService = mock(AccountAllocationPolicyService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        AccountAdminController controller = controller(policyService, adminAuth);
        MockServerWebExchange exchange = exchange("/admin/accounts/account-1/allocation-policy");
        AccountDtos.AccountAllocationPolicyResponse policy = policy("account-1", 3L);
        when(policyService.get("account-1")).thenReturn(Mono.just(policy));

        ApiResponse<AccountDtos.AccountAllocationPolicyResponse> response = controller
            .allocationPolicy(exchange, "account-1")
            .block();

        assertThat(response).isNotNull();
        assertThat(response.data()).isEqualTo(policy);
        verify(adminAuth).verifyAdmin(exchange);
        verify(policyService).get("account-1");
    }

    @Test
    void updateAllocationPolicyPassesValidatedRequestToService() {
        AccountAllocationPolicyService policyService = mock(AccountAllocationPolicyService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        AccountAdminController controller = controller(policyService, adminAuth);
        MockServerWebExchange exchange = exchange("/admin/accounts/account-1/allocation-policy");
        AccountDtos.UpdateAccountAllocationPolicyRequest request = new AccountDtos.UpdateAccountAllocationPolicyRequest(
            10,
            8,
            120,
            8,
            0,
            900,
            2_000_000L
        );
        AccountDtos.AccountAllocationPolicyResponse updated = policy("account-1", 4L);
        when(policyService.update("account-1", request)).thenReturn(Mono.just(updated));

        ApiResponse<AccountDtos.AccountAllocationPolicyResponse> response = controller
            .updateAllocationPolicy(exchange, "account-1", request)
            .block();

        assertThat(response).isNotNull();
        assertThat(response.data().policyVersion()).isEqualTo(4L);
        verify(adminAuth).verifyAdmin(exchange);
        verify(policyService).update("account-1", request);
    }

    private AccountAdminController controller(
        AccountAllocationPolicyService policyService,
        AdminAuth adminAuth
    ) {
        return new AccountAdminController(
            mock(AccountPoolService.class),
            mock(AccountImportParser.class),
            mock(AccountTestService.class),
            mock(DisabledAccountScheduledTestService.class),
            mock(AccountOpenAiQuotaService.class),
            policyService,
            mock(AccountSettingsService.class),
            mock(AccountPoolPolicyService.class),
            mock(AccountPoolRetestService.class),
            adminAuth
        );
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    private AccountDtos.AccountAllocationPolicyResponse policy(String accountId, Long version) {
        return new AccountDtos.AccountAllocationPolicyResponse(
            accountId,
            100,
            4,
            60,
            4,
            0,
            600,
            1_000_000L,
            version,
            "HEALTHY",
            2L,
            4,
            null,
            0.01,
            800.0,
            1,
            100L,
            200L,
            Instant.parse("2026-08-28T00:00:00Z")
        );
    }
}
