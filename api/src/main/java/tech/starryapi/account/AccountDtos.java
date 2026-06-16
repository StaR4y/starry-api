package tech.starryapi.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record CreateAccountRequest(
        String accountId,
        String email,
        @NotBlank String accessToken,
        String refreshToken,
        String clientId,
        String clientSecret,
        String region,
        String authMethod,
        String provider,
        String baseUrl,
        String upstreamAccountId,
        String idToken,
        Instant tokenExpiresAt,
        String profileArn,
        String machineId,
        String proxyUrl,
        Long quotaLimit,
        Long quota5hLimit,
        Long quota7dLimit
    ) {
    }

    public record UpdateAccountRequest(
        String email,
        String accessToken,
        String refreshToken,
        String clientId,
        String clientSecret,
        String region,
        String authMethod,
        String provider,
        String baseUrl,
        String upstreamAccountId,
        String idToken,
        Instant tokenExpiresAt,
        String profileArn,
        String machineId,
        String proxyUrl,
        Boolean enabled,
        Long quotaUsed,
        Long quotaLimit,
        Instant quotaResetAt,
        Long quota5hUsed,
        Long quota5hLimit,
        Instant quota5hResetAt,
        Long quota7dUsed,
        Long quota7dLimit,
        Instant quota7dResetAt
    ) {
    }

    public record SuspendAccountRequest(
        @NotBlank String reason,
        String message
    ) {
    }

    public record TestAccountsRequest(
        List<String> accountIds,
        Boolean onlyEnabled,
        String model,
        String prompt,
        Integer maxConcurrency,
        Boolean updateStatus
    ) {
    }

    public record TestModelOption(
        String label,
        String value
    ) {
    }

    public record TestModelOptionsResponse(
        List<TestModelOption> kiro,
        List<TestModelOption> openAi,
        List<TestModelOption> google,
        List<TestModelOption> anthropic,
        List<TestModelOption> codex
    ) {
        public TestModelOptionsResponse {
            kiro = kiro == null ? List.of() : List.copyOf(kiro);
            openAi = openAi == null ? List.of() : List.copyOf(openAi);
            google = google == null ? List.of() : List.copyOf(google);
            anthropic = anthropic == null ? List.of() : List.copyOf(anthropic);
            codex = codex == null ? List.of() : List.copyOf(codex);
        }
    }

    public record AccountSettingsResponse(
        Boolean gptFastModeEnabled,
        DisabledAccountScheduledTestSettings disabledAccountScheduledTest
    ) {
    }

    public record UpdateAccountSettingsRequest(
        Boolean gptFastModeEnabled,
        DisabledAccountScheduledTestSettings disabledAccountScheduledTest
    ) {
    }

    public record DisabledAccountScheduledTestSettings(
        Boolean enabled,
        String scheduleMode,
        Integer intervalMinutes,
        String cronExpression,
        String timezone,
        Integer initialDelayMinutes,
        Integer maxConcurrency,
        Integer maxAccountsPerRun,
        String model,
        String prompt,
        Boolean updateStatus,
        Boolean autoRunWhenNoAvailableAccounts,
        Boolean includeDisabled,
        Boolean includeSuspended,
        Boolean includeQuotaExhausted,
        Integer minAgeMinutes,
        List<String> providers
    ) {
        public DisabledAccountScheduledTestSettings {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }
    }

    public record ImportAccountsRequest(
        @NotEmpty List<@Valid CreateAccountRequest> accounts,
        Boolean upsert
    ) {
    }

    public record ImportAccountsResponse(
        Integer total,
        Integer created,
        Integer updated,
        Integer skipped,
        Integer failed,
        List<ImportAccountItemResponse> items
    ) {
    }

    public record ClearAccountsResponse(
        Long deleted
    ) {
    }

    public record ImportAccountItemResponse(
        Integer index,
        String accountId,
        String email,
        String status,
        String message,
        AccountResponse account
    ) {
    }

    public record TestAccountsResponse(
        Integer total,
        Integer success,
        Integer failed,
        Integer skipped,
        Instant startedAt,
        Instant finishedAt,
        List<TestAccountItemResponse> items
    ) {
    }

    public record TestAccountItemResponse(
        String accountId,
        String email,
        String status,
        String message,
        String detail,
        Integer statusCode,
        Long latencyMs,
        Instant testedAt,
        String model
    ) {
    }

    public record OpenAiQuotaResponse(
        String accountId,
        String email,
        String source,
        Instant checkedAt,
        Double primaryOverSecondaryLimitPercent,
        OpenAiQuotaWindowResponse primary,
        OpenAiQuotaWindowResponse secondary,
        OpenAiQuotaWindowResponse fiveHour,
        OpenAiQuotaWindowResponse sevenDay
    ) {
    }

    public record OpenAiQuotaWindowResponse(
        String key,
        String label,
        Double usedPercent,
        Double remainingPercent,
        Integer resetAfterSeconds,
        Instant resetAt,
        Integer windowMinutes
    ) {
    }

    public record AccountResponse(
        String accountId,
        String email,
        String region,
        String authMethod,
        String provider,
        String baseUrl,
        String upstreamAccountId,
        Instant tokenExpiresAt,
        String profileArn,
        String machineId,
        String proxyUrl,
        Boolean enabled,
        Long requestCount,
        Integer errorCount,
        Long quotaUsed,
        Long quotaLimit,
        Instant quotaExhaustedAt,
        Instant quotaResetAt,
        Long quota5hUsed,
        Long quota5hLimit,
        Instant quota5hResetAt,
        Long quota7dUsed,
        Long quota7dLimit,
        Instant quota7dResetAt,
        Instant suspendedAt,
        String suspendReason,
        String suspendMessage,
        Instant lastUsedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
