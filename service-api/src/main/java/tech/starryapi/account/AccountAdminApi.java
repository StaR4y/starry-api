package tech.starryapi.account;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiResponse;

@RequestMapping("/admin/accounts")
public interface AccountAdminApi {

    @GetMapping
    Mono<ApiResponse<List<AccountDtos.AccountResponse>>> list(ServerWebExchange exchange);

    @PostMapping
    Mono<ApiResponse<AccountDtos.AccountResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody AccountDtos.CreateAccountRequest request
    );

    @PostMapping("/import")
    Mono<ApiResponse<AccountDtos.ImportAccountsResponse>> importBatch(
        ServerWebExchange exchange,
        @RequestBody JsonNode request
    );

    @GetMapping("/settings")
    Mono<ApiResponse<AccountDtos.AccountSettingsResponse>> settings(ServerWebExchange exchange);

    @GetMapping("/pool-status")
    Mono<ApiResponse<AccountDtos.AccountPoolStatusResponse>> poolStatus(ServerWebExchange exchange);

    @PatchMapping("/settings")
    Mono<ApiResponse<AccountDtos.AccountSettingsResponse>> updateSettings(
        ServerWebExchange exchange,
        @RequestBody AccountDtos.UpdateAccountSettingsRequest request
    );

    @PatchMapping("/{accountId}")
    Mono<ApiResponse<AccountDtos.AccountResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String accountId,
        @Valid @RequestBody AccountDtos.UpdateAccountRequest request
    );

    @PostMapping("/{accountId}/suspend")
    Mono<ApiResponse<AccountDtos.AccountResponse>> suspend(
        ServerWebExchange exchange,
        @PathVariable String accountId,
        @Valid @RequestBody AccountDtos.SuspendAccountRequest request
    );

    @PostMapping("/{accountId}/reset")
    Mono<ApiResponse<AccountDtos.AccountResponse>> reset(ServerWebExchange exchange, @PathVariable String accountId);

    @GetMapping("/{accountId}/openai-quota")
    Mono<ApiResponse<AccountDtos.OpenAiQuotaResponse>> openAiQuota(
        ServerWebExchange exchange,
        @PathVariable String accountId
    );

    @GetMapping("/{accountId}/allocation-policy")
    Mono<ApiResponse<AccountDtos.AccountAllocationPolicyResponse>> allocationPolicy(
        ServerWebExchange exchange,
        @PathVariable String accountId
    );

    @PatchMapping("/{accountId}/allocation-policy")
    Mono<ApiResponse<AccountDtos.AccountAllocationPolicyResponse>> updateAllocationPolicy(
        ServerWebExchange exchange,
        @PathVariable String accountId,
        @Valid @RequestBody AccountDtos.UpdateAccountAllocationPolicyRequest request
    );

    @PostMapping("/test")
    Mono<ApiResponse<AccountDtos.TestAccountsResponse>> test(
        ServerWebExchange exchange,
        @RequestBody AccountDtos.TestAccountsRequest request
    );

    @PostMapping("/disabled-scheduled-test/run")
    Mono<ApiResponse<AccountDtos.TestAccountsResponse>> runDisabledScheduledTest(ServerWebExchange exchange);

    @GetMapping("/test-models")
    Mono<ApiResponse<AccountDtos.TestModelOptionsResponse>> testModels(ServerWebExchange exchange);

    @DeleteMapping
    Mono<ApiResponse<AccountDtos.ClearAccountsResponse>> clearAll(ServerWebExchange exchange);

    @DeleteMapping("/{accountId}")
    Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @PathVariable String accountId);
}
