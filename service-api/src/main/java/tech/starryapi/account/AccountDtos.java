package tech.starryapi.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        String poolTier,
        Integer poolWeight,
        Long quotaLimit,
        Long quota5hLimit,
        Long quota7dLimit,
        String badgeColor,
        UUID proxyChannelId,
        @Valid UpdateAccountAllocationPolicyRequest allocationPolicy
    ) {
        public CreateAccountRequest(
            String accountId,
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
            String poolTier,
            Integer poolWeight,
            Long quotaLimit,
            Long quota5hLimit,
            Long quota7dLimit
        ) {
            this(
                accountId,
                email,
                accessToken,
                refreshToken,
                clientId,
                clientSecret,
                region,
                authMethod,
                provider,
                baseUrl,
                upstreamAccountId,
                idToken,
                tokenExpiresAt,
                profileArn,
                machineId,
                proxyUrl,
                poolTier,
                poolWeight,
                quotaLimit,
                quota5hLimit,
                quota7dLimit,
                null,
                null,
                null
            );
        }
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
        String poolTier,
        Integer poolWeight,
        Boolean enabled,
        Long quotaUsed,
        Long quotaLimit,
        Instant quotaResetAt,
        Long quota5hUsed,
        Long quota5hLimit,
        Instant quota5hResetAt,
        Long quota7dUsed,
        Long quota7dLimit,
        Instant quota7dResetAt,
        String badgeColor,
        UUID proxyChannelId,
        Boolean clearProxyChannel,
        @Valid UpdateAccountAllocationPolicyRequest allocationPolicy
    ) {
        public UpdateAccountRequest(
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
            String poolTier,
            Integer poolWeight,
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
            this(
                email,
                accessToken,
                refreshToken,
                clientId,
                clientSecret,
                region,
                authMethod,
                provider,
                baseUrl,
                upstreamAccountId,
                idToken,
                tokenExpiresAt,
                profileArn,
                machineId,
                proxyUrl,
                poolTier,
                poolWeight,
                enabled,
                quotaUsed,
                quotaLimit,
                quotaResetAt,
                quota5hUsed,
                quota5hLimit,
                quota5hResetAt,
                quota7dUsed,
                quota7dLimit,
                quota7dResetAt,
                null,
                null,
                null,
                null
            );
        }
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

    public record CompatibleVendorModels(
        String name,
        String icon,
        String color,
        List<TestModelOption> models
    ) {
        public CompatibleVendorModels {
            models = models == null ? List.of() : List.copyOf(models);
        }

        public CompatibleVendorModels(String name, String icon, List<TestModelOption> models) {
            this(name, icon, null, models);
        }
    }

    public record TestModelOptionsResponse(
        List<TestModelOption> kiro,
        List<TestModelOption> openAi,
        List<TestModelOption> google,
        List<TestModelOption> anthropic,
        List<TestModelOption> codex,
        List<TestModelOption> deepSeek,
        List<CompatibleVendorModels> compatibleVendors
    ) {
        public TestModelOptionsResponse {
            kiro = kiro == null ? List.of() : List.copyOf(kiro);
            openAi = openAi == null ? List.of() : List.copyOf(openAi);
            google = google == null ? List.of() : List.copyOf(google);
            anthropic = anthropic == null ? List.of() : List.copyOf(anthropic);
            codex = codex == null ? List.of() : List.copyOf(codex);
            deepSeek = deepSeek == null ? List.of() : List.copyOf(deepSeek);
            compatibleVendors = compatibleVendors == null ? List.of() : List.copyOf(compatibleVendors);
        }

        public TestModelOptionsResponse(
            List<TestModelOption> kiro,
            List<TestModelOption> openAi,
            List<TestModelOption> google,
            List<TestModelOption> anthropic,
            List<TestModelOption> codex,
            List<TestModelOption> deepSeek
        ) {
            this(kiro, openAi, google, anthropic, codex, deepSeek, List.of());
        }
    }

    public record AccountSettingsResponse(
        Boolean gptFastModeEnabled,
        DisabledAccountScheduledTestSettings disabledAccountScheduledTest,
        PoolPolicySettings poolPolicy,
        RoutingPolicySettings routingPolicy
    ) {
        public AccountSettingsResponse(
            Boolean gptFastModeEnabled,
            DisabledAccountScheduledTestSettings disabledAccountScheduledTest,
            PoolPolicySettings poolPolicy
        ) {
            this(gptFastModeEnabled, disabledAccountScheduledTest, poolPolicy, RoutingPolicySettings.defaults());
        }
    }

    public record UpdateAccountSettingsRequest(
        Boolean gptFastModeEnabled,
        DisabledAccountScheduledTestSettings disabledAccountScheduledTest,
        PoolPolicySettings poolPolicy,
        RoutingPolicySettings routingPolicy
    ) {
        public UpdateAccountSettingsRequest(
            Boolean gptFastModeEnabled,
            DisabledAccountScheduledTestSettings disabledAccountScheduledTest,
            PoolPolicySettings poolPolicy
        ) {
            this(gptFastModeEnabled, disabledAccountScheduledTest, poolPolicy, null);
        }
    }

    /**
     * Account selection, session affinity, and cross-account retry limits.
     *
     * <p>Defaults keep Codex OAuth accounts separate, route ordinary GPT traffic to official
     * OpenAI API keys, and bind only explicit conversations after a successful upstream result.
     * Per-account concurrency and RPM stay on the allocation policy.
     */
    public record RoutingPolicySettings(
        Boolean isolateCodexPool,
        Boolean gptUsesCodexAccounts,
        Boolean bindSessionOnAdmission,
        Boolean bindSessionByApiKey,
        Integer codexMaxRetryAccounts,
        Integer openAiMaxRetryAccounts,
        Integer deepSeekMaxRetryAccounts,
        Integer anthropicMaxRetryAccounts,
        Integer kiroMaxRetryAccounts
    ) {
        public static final int MIN_RETRY_ACCOUNTS = 1;
        public static final int MAX_RETRY_ACCOUNTS = 5;

        public static RoutingPolicySettings defaults() {
            return new RoutingPolicySettings(true, false, false, false, 1, 3, 3, 2, 2);
        }

        public boolean isolateCodexPoolEnabled() {
            return Boolean.TRUE.equals(isolateCodexPool);
        }

        public boolean gptUsesCodexAccountsEnabled() {
            return Boolean.TRUE.equals(gptUsesCodexAccounts);
        }

        public boolean bindSessionOnAdmissionEnabled() {
            return Boolean.TRUE.equals(bindSessionOnAdmission);
        }

        public boolean bindSessionByApiKeyEnabled() {
            return Boolean.TRUE.equals(bindSessionByApiKey);
        }

        public int maxRetryAccounts(boolean preferDeepSeek, boolean preferCodex, String routeName) {
            if (preferDeepSeek) {
                return boundedRetryAccounts(deepSeekMaxRetryAccounts, 3);
            }
            if (preferCodex) {
                return boundedRetryAccounts(codexMaxRetryAccounts, 1);
            }
            if ("ANTHROPIC".equals(routeName)) {
                return boundedRetryAccounts(anthropicMaxRetryAccounts, 2);
            }
            if ("KIRO".equals(routeName)) {
                return boundedRetryAccounts(kiroMaxRetryAccounts, 2);
            }
            return boundedRetryAccounts(openAiMaxRetryAccounts, 3);
        }

        private static int boundedRetryAccounts(Integer value, int fallback) {
            int safe = value == null ? fallback : value;
            return Math.max(MIN_RETRY_ACCOUNTS, Math.min(MAX_RETRY_ACCOUNTS, safe));
        }
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

    public record PoolPolicySettings(
        Boolean enabled,
        Integer windowMinutes,
        Integer minSampleSize,
        Double retestThresholdPercent,
        Double failoverThresholdPercent,
        Double recoveryThresholdPercent,
        Integer retestIntervalMinutes,
        Integer retestMaxConcurrency,
        Boolean autoWeightEnabled,
        UUID circuitEpoch,
        Instant activationStartedAt
    ) {
        public PoolPolicySettings(
            Boolean enabled,
            Integer windowMinutes,
            Integer minSampleSize,
            Double retestThresholdPercent,
            Double failoverThresholdPercent,
            Double recoveryThresholdPercent,
            Integer retestIntervalMinutes,
            Integer retestMaxConcurrency,
            Boolean autoWeightEnabled
        ) {
            this(
                enabled,
                windowMinutes,
                minSampleSize,
                retestThresholdPercent,
                failoverThresholdPercent,
                recoveryThresholdPercent,
                retestIntervalMinutes,
                retestMaxConcurrency,
                autoWeightEnabled,
                Boolean.TRUE.equals(enabled) ? new UUID(0L, 0L) : null,
                Boolean.TRUE.equals(enabled) ? Instant.EPOCH : null
            );
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

    /** Starts a ChatGPT device authorization, or reauthorizes an existing Codex account. */
    public record ChatGptOAuthStartRequest(
        @Size(max = 64) String accountId
    ) {
    }

    public record ChatGptOAuthStartResponse(
        String loginId,
        String verificationUrl,
        String userCode,
        Instant expiresAt,
        Integer pollIntervalSeconds
    ) {
    }

    public record ChatGptOAuthStatusResponse(
        String loginId,
        String status,
        String message,
        Integer retryAfterSeconds,
        AccountResponse account
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
        String poolTier,
        Integer poolWeight,
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
        Instant updatedAt,
        String badgeColor,
        UUID proxyChannelId
    ) {
        public AccountResponse(
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
            String poolTier,
            Integer poolWeight,
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
            this(
                accountId,
                email,
                region,
                authMethod,
                provider,
                baseUrl,
                upstreamAccountId,
                tokenExpiresAt,
                profileArn,
                machineId,
                proxyUrl,
                poolTier,
                poolWeight,
                enabled,
                requestCount,
                errorCount,
                quotaUsed,
                quotaLimit,
                quotaExhaustedAt,
                quotaResetAt,
                quota5hUsed,
                quota5hLimit,
                quota5hResetAt,
                quota7dUsed,
                quota7dLimit,
                quota7dResetAt,
                suspendedAt,
                suspendReason,
                suspendMessage,
                lastUsedAt,
                createdAt,
                updatedAt,
                null,
                null
            );
        }
    }

    public record AccountAllocationPolicyResponse(
        String accountId,
        Integer allocationPriority,
        Integer safeConcurrency,
        Integer safeRpm,
        Integer burstCapacity,
        Integer maxSessions,
        Integer sessionIdleSeconds,
        Long maxInflightTokens,
        Long policyVersion,
        String lifecycleState,
        Long stateVersion,
        Integer warmupStage,
        Instant cooldownUntil,
        Double recentErrorRate,
        Double recentLatencyMs,
        Integer activeConcurrency,
        Long reservedInputTokens,
        Long reservedOutputTokens,
        Instant updatedAt,
        Integer cooldownSeconds,
        String tlsProfile
    ) {
        public AccountAllocationPolicyResponse(String accountId, Integer allocationPriority, Integer safeConcurrency,
            Integer safeRpm, Integer burstCapacity, Integer maxSessions, Integer sessionIdleSeconds, Long maxInflightTokens,
            Long policyVersion, String lifecycleState, Long stateVersion, Integer warmupStage, Instant cooldownUntil,
            Double recentErrorRate, Double recentLatencyMs, Integer activeConcurrency, Long reservedInputTokens,
            Long reservedOutputTokens, Instant updatedAt) {
            this(accountId, allocationPriority, safeConcurrency, safeRpm, burstCapacity, maxSessions, sessionIdleSeconds,
                maxInflightTokens, policyVersion, lifecycleState, stateVersion, warmupStage, cooldownUntil, recentErrorRate,
                recentLatencyMs, activeConcurrency, reservedInputTokens, reservedOutputTokens, updatedAt, 0, "");
        }
    }

    public record UpdateAccountAllocationPolicyRequest(
        @Min(1) @Max(10000) Integer allocationPriority,
        @Min(0) @Max(10000) Integer safeConcurrency,
        @Min(0) @Max(1000000) Integer safeRpm,
        @Min(0) @Max(100000) Integer burstCapacity,
        @Min(0) @Max(100000) Integer maxSessions,
        @Min(60) @Max(86400) Integer sessionIdleSeconds,
        @Min(1) @Max(1000000000) Long maxInflightTokens,
        @Min(0) @Max(86400) Integer cooldownSeconds,
        @jakarta.validation.constraints.Pattern(regexp = "|chrome136|safari18_4") String tlsProfile
    ) {
        public UpdateAccountAllocationPolicyRequest(Integer allocationPriority, Integer safeConcurrency, Integer safeRpm,
            Integer burstCapacity, Integer maxSessions, Integer sessionIdleSeconds, Long maxInflightTokens) {
            this(allocationPriority, safeConcurrency, safeRpm, burstCapacity, maxSessions, sessionIdleSeconds, maxInflightTokens, null, null);
        }
    }

    public record AccountFailureRankingResponse(
        String accountId,
        String email,
        String provider,
        String authMethod,
        String poolTier,
        Long requests,
        Long failures,
        Double failureRatePercent,
        Integer errorCount,
        Integer configuredWeight,
        Integer effectiveWeight,
        Instant lastFailureAt
    ) {
    }

    public record AccountPoolStatusResponse(
        Boolean enabled,
        Long accountCount,
        Long availableAccountCount,
        String activeTier,
        Boolean primaryDisabled,
        Double primaryFailureRatePercent,
        Long primaryRequests,
        Long primaryFailures,
        Instant windowStartedAt,
        Instant lastEvaluatedAt,
        Instant lastRetestAt,
        Double lastRetestFailureRatePercent,
        Double lastRetestRecoveryRatePercent,
        Boolean retestRunning,
        PoolPolicySettings settings,
        List<AccountFailureRankingResponse> failureRanking,
        List<ModelPoolCircuitStatusResponse> modelPools
    ) {
        public AccountPoolStatusResponse {
            failureRanking = failureRanking == null ? List.of() : List.copyOf(failureRanking);
            modelPools = modelPools == null ? List.of() : List.copyOf(modelPools);
        }
    }

    public record ModelPoolCircuitStatusResponse(
        String key,
        String provider,
        String modelFamily,
        String representativeModel,
        String state,
        String activeTier,
        Long primaryRequests,
        Long primaryFailures,
        Double primaryFailureRatePercent,
        Long secondaryRequests,
        Long secondaryFailures,
        Double secondaryFailureRatePercent,
        Integer primaryAccountCount,
        Integer secondaryAccountCount,
        Instant windowStartedAt,
        Instant lastTransitionAt,
        Instant openedAt,
        Instant nextProbeAt,
        Instant lastOutcomeAt,
        Integer halfOpenInFlight,
        Integer halfOpenCompleted,
        Integer halfOpenSucceeded
    ) {
    }
}
