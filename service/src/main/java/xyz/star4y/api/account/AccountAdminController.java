package xyz.star4y.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.AdminAuth;
import xyz.star4y.api.common.ApiResponse;

@RestController
public class AccountAdminController implements AccountAdminApi {

    private final AccountPoolService service;
    private final AccountImportParser importParser;
    private final AccountTestService testService;
    private final AccountSettingsService settingsService;
    private final AdminAuth adminAuth;

    public AccountAdminController(
        AccountPoolService service,
        AccountImportParser importParser,
        AccountTestService testService,
        AccountSettingsService settingsService,
        AdminAuth adminAuth
    ) {
        this.service = service;
        this.importParser = importParser;
        this.testService = testService;
        this.settingsService = settingsService;
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
    public Mono<ApiResponse<AccountDtos.AccountSettingsResponse>> updateSettings(
        ServerWebExchange exchange,
        @RequestBody AccountDtos.UpdateAccountSettingsRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return settingsService.update(request).map(ApiResponse::ok);
    }

    @Override
    public Mono<ApiResponse<AccountDtos.AccountResponse>> update(
        ServerWebExchange exchange,
        @PathVariable String accountId,
        @RequestBody AccountDtos.UpdateAccountRequest request
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
    public Mono<ApiResponse<AccountDtos.TestAccountsResponse>> test(
        ServerWebExchange exchange,
        @RequestBody AccountDtos.TestAccountsRequest request
    ) {
        adminAuth.verifyAdmin(exchange);
        return testService.test(request).map(ApiResponse::ok);
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
