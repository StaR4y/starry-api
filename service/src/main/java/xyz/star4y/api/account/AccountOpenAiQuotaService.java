package xyz.star4y.api.account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.platform.openai.OpenAiAccountTestClient;

@Service
public class AccountOpenAiQuotaService {

    private static final String SOURCE = "chatgpt-codex-response-headers";

    private final ProxyAccountRepository repository;
    private final OpenAiAccountTestClient openAiAccountTestClient;

    public AccountOpenAiQuotaService(
        ProxyAccountRepository repository,
        OpenAiAccountTestClient openAiAccountTestClient
    ) {
        this.repository = repository;
        this.openAiAccountTestClient = openAiAccountTestClient;
    }

    public Mono<AccountDtos.OpenAiQuotaResponse> quota(String accountId) {
        return repository.findByAccountId(accountId)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found")))
            .flatMap(account -> {
                validateAccount(account);
                return openAiAccountTestClient.quota(account).map(snapshot -> response(account, snapshot));
            });
    }

    private void validateAccount(ProxyAccountEntity account) {
        if (!AccountPlatforms.isCodex(account)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "OPENAI_QUOTA_UNSUPPORTED_ACCOUNT",
                "OpenAI official quota probe only supports OpenAI Codex OAuth accounts"
            );
        }
        if (account.getAccessToken() == null || account.getAccessToken().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OPENAI_QUOTA_TOKEN_REQUIRED", "Access token is empty");
        }
    }

    private AccountDtos.OpenAiQuotaResponse response(
        ProxyAccountEntity account,
        OpenAiAccountTestClient.OpenAiQuotaSnapshot snapshot
    ) {
        return new AccountDtos.OpenAiQuotaResponse(
            account.getAccountId(),
            account.getEmail(),
            SOURCE,
            snapshot.checkedAt(),
            snapshot.primaryOverSecondaryLimitPercent(),
            window(snapshot.primary()),
            window(snapshot.secondary()),
            window(snapshot.fiveHour()),
            window(snapshot.sevenDay())
        );
    }

    private AccountDtos.OpenAiQuotaWindowResponse window(OpenAiAccountTestClient.OpenAiQuotaWindow window) {
        if (window == null) {
            return null;
        }
        return new AccountDtos.OpenAiQuotaWindowResponse(
            window.key(),
            window.label(),
            window.usedPercent(),
            window.remainingPercent(),
            window.resetAfterSeconds(),
            window.resetAt(),
            window.windowMinutes()
        );
    }
}
