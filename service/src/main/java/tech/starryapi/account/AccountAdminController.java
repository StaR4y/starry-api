package tech.starryapi.account;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;
import tools.jackson.databind.JsonNode;

@RestController
public class AccountAdminController implements AccountAdminApi {

    private final AccountPoolService service;
    private final AccountImportParser importParser;
    private final AccountTestService testService;
    private final DisabledAccountScheduledTestService disabledAccountScheduledTestService;
    private final AccountOpenAiQuotaService openAiQuotaService;
    private final AccountAllocationPolicyService allocationPolicyService;
    private final AccountSettingsService settingsService;
    private final AccountPoolPolicyService poolPolicyService;
    private final AccountPoolRetestService poolRetestService;
    private final AdminAuth adminAuth;

    public AccountAdminController(
        AccountPoolService service,
        AccountImportParser importParser,
        AccountTestService testService,
        DisabledAccountScheduledTestService disabledAccountScheduledTestService,
        AccountOpenAiQuotaService openAiQuotaService,
        AccountAllocationPolicyService allocationPolicyService,
        AccountSettingsService settingsService,
        AccountPoolPolicyService poolPolicyService,
        AccountPoolRetestService poolRetestService,
        AdminAuth adminAuth
    ) {
        this.service = service;
        this.importParser = importParser;
        this.testService = testService;
        this.disabledAccountScheduledTestService = disabledAccountScheduledTestService;
        this.openAiQuotaService = openAiQuotaService;
        this.allocationPolicyService = allocationPolicyService;
        this.settingsService = settingsService;
        this.poolPolicyService = poolPolicyService;
        this.poolRetestService = poolRetestService;
        this.adminAuth = adminAuth;
    }

    @Override
    public Mono<ApiResponse<List<AccountDtos.AccountResponse>>> list(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.list()
            .map(accounts -> accounts.stream().map(AccountMapper::toResponse).toList())
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountResponse>> create(
        ServerWebExchange exchange,
        @Valid @RequestBody AccountDtos.CreateAccountRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.create(request)
            .map(AccountMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.ImportAccountsResponse>> importBatch(
        ServerWebExchange exchange,
        @RequestBody JsonNode request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.importAccounts(importParser.parse(request)).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountSettingsResponse>> settings(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return settingsService.settings().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountPoolStatusResponse>> poolStatus(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return poolPolicyService.status(poolRetestService.isRunning(), service::poolAccountInventory)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountSettingsResponse>> updateSettings(
        ServerWebExchange exchange,
        @RequestBody AccountDtos.UpdateAccountSettingsRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return settingsService.update(request)
            .then(poolPolicyService.refreshSettings())
            .then(settingsService.settings())
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String accountId,
        @Valid @RequestBody AccountDtos.UpdateAccountRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.update(accountId, request)
            .map(AccountMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountResponse>> suspend(
        ServerWebExchange exchange,
        @PathVariable String accountId,
        @Valid @RequestBody AccountDtos.SuspendAccountRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return service.suspend(accountId, request.reason(), request.message())
            .map(AccountMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountResponse>> reset(ServerWebExchange exchange, @PathVariable String accountId) {
        adminAuth.verifyAdmin(exchange);
        return service.resetState(accountId)
            .map(AccountMapper::toResponse)
            .map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.OpenAiQuotaResponse>> openAiQuota(
        ServerWebExchange exchange,
        @PathVariable String accountId
    ) {
        adminAuth.verifyAdmin(exchange);
        return openAiQuotaService.quota(accountId).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountAllocationPolicyResponse>> allocationPolicy(
        ServerWebExchange exchange,
        @PathVariable String accountId
    ) {
        adminAuth.verifyAdmin(exchange);
        return allocationPolicyService.get(accountId).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountAllocationPolicyResponse>> updateAllocationPolicy(
        ServerWebExchange exchange,
        @PathVariable String accountId,
        @Valid @RequestBody AccountDtos.UpdateAccountAllocationPolicyRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return allocationPolicyService.update(accountId, request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.TestAccountsResponse>> test(
        ServerWebExchange exchange,
        @RequestBody AccountDtos.TestAccountsRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return testService.test(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.TestAccountsResponse>> runDisabledScheduledTest(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return disabledAccountScheduledTestService.runNow().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.TestModelOptionsResponse>> testModels(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return Mono.just(ApiResponse.ok(testService.modelOptions()));
    }

    @Override
    public Mono<ApiResponse<AccountDtos.ClearAccountsResponse>> clearAll(ServerWebExchange exchange) {
        adminAuth.verifyAdmin(exchange);
        return service.clearAll().map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<Void>> delete(ServerWebExchange exchange, @PathVariable String accountId) {
        adminAuth.verifyAdmin(exchange);
        return service.delete(accountId).thenReturn(ApiResponse.ok(null));
    }
}
