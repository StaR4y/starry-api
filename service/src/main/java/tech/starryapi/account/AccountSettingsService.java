package tech.starryapi.account;

import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;

@Service
public class AccountSettingsService {

    static final String GPT_FAST_MODE_ENABLED = "gpt_fast_mode_enabled";
    static final String DISABLED_TEST_ENABLED = "disabled_account_scheduled_test_enabled";
    static final String DISABLED_TEST_SCHEDULE_MODE = "disabled_account_scheduled_test_schedule_mode";
    static final String DISABLED_TEST_INTERVAL_MINUTES = "disabled_account_scheduled_test_interval_minutes";
    static final String DISABLED_TEST_CRON_EXPRESSION = "disabled_account_scheduled_test_cron_expression";
    static final String DISABLED_TEST_TIMEZONE = "disabled_account_scheduled_test_timezone";
    static final String DISABLED_TEST_INITIAL_DELAY_MINUTES = "disabled_account_scheduled_test_initial_delay_minutes";
    static final String DISABLED_TEST_MAX_CONCURRENCY = "disabled_account_scheduled_test_max_concurrency";
    static final String DISABLED_TEST_MAX_ACCOUNTS_PER_RUN = "disabled_account_scheduled_test_max_accounts_per_run";
    static final String DISABLED_TEST_MODEL = "disabled_account_scheduled_test_model";
    static final String DISABLED_TEST_PROMPT = "disabled_account_scheduled_test_prompt";
    static final String DISABLED_TEST_UPDATE_STATUS = "disabled_account_scheduled_test_update_status";
    static final String DISABLED_TEST_AUTO_RUN_WHEN_NO_AVAILABLE = "disabled_account_scheduled_test_auto_run_when_no_available";
    static final String DISABLED_TEST_INCLUDE_DISABLED = "disabled_account_scheduled_test_include_disabled";
    static final String DISABLED_TEST_INCLUDE_SUSPENDED = "disabled_account_scheduled_test_include_suspended";
    static final String DISABLED_TEST_INCLUDE_QUOTA_EXHAUSTED = "disabled_account_scheduled_test_include_quota_exhausted";
    static final String DISABLED_TEST_MIN_AGE_MINUTES = "disabled_account_scheduled_test_min_age_minutes";
    static final String DISABLED_TEST_PROVIDERS = "disabled_account_scheduled_test_providers";
    static final String POOL_POLICY_ENABLED = "account_pool_policy_enabled";
    static final String POOL_POLICY_WINDOW_MINUTES = "account_pool_policy_window_minutes";
    static final String POOL_POLICY_MIN_SAMPLE_SIZE = "account_pool_policy_min_sample_size";
    static final String POOL_POLICY_RETEST_THRESHOLD_PERCENT = "account_pool_policy_retest_threshold_percent";
    static final String POOL_POLICY_FAILOVER_THRESHOLD_PERCENT = "account_pool_policy_failover_threshold_percent";
    static final String POOL_POLICY_RECOVERY_THRESHOLD_PERCENT = "account_pool_policy_recovery_threshold_percent";
    static final String POOL_POLICY_RETEST_INTERVAL_MINUTES = "account_pool_policy_retest_interval_minutes";
    static final String POOL_POLICY_RETEST_MAX_CONCURRENCY = "account_pool_policy_retest_max_concurrency";
    static final String POOL_POLICY_AUTO_WEIGHT_ENABLED = "account_pool_policy_auto_weight_enabled";

    private static final List<String> ACCOUNT_SETTING_KEYS = List.of(
        GPT_FAST_MODE_ENABLED,
        DISABLED_TEST_ENABLED,
        DISABLED_TEST_SCHEDULE_MODE,
        DISABLED_TEST_INTERVAL_MINUTES,
        DISABLED_TEST_CRON_EXPRESSION,
        DISABLED_TEST_TIMEZONE,
        DISABLED_TEST_INITIAL_DELAY_MINUTES,
        DISABLED_TEST_MAX_CONCURRENCY,
        DISABLED_TEST_MAX_ACCOUNTS_PER_RUN,
        DISABLED_TEST_MODEL,
        DISABLED_TEST_PROMPT,
        DISABLED_TEST_UPDATE_STATUS,
        DISABLED_TEST_AUTO_RUN_WHEN_NO_AVAILABLE,
        DISABLED_TEST_INCLUDE_DISABLED,
        DISABLED_TEST_INCLUDE_SUSPENDED,
        DISABLED_TEST_INCLUDE_QUOTA_EXHAUSTED,
        DISABLED_TEST_MIN_AGE_MINUTES,
        DISABLED_TEST_PROVIDERS,
        POOL_POLICY_ENABLED,
        POOL_POLICY_WINDOW_MINUTES,
        POOL_POLICY_MIN_SAMPLE_SIZE,
        POOL_POLICY_RETEST_THRESHOLD_PERCENT,
        POOL_POLICY_FAILOVER_THRESHOLD_PERCENT,
        POOL_POLICY_RECOVERY_THRESHOLD_PERCENT,
        POOL_POLICY_RETEST_INTERVAL_MINUTES,
        POOL_POLICY_RETEST_MAX_CONCURRENCY,
        POOL_POLICY_AUTO_WEIGHT_ENABLED
    );

    private final SystemSettingRepository repository;

    public AccountSettingsService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    public Mono<AccountDtos.AccountSettingsResponse> settings() {
        return values().map(this::response);
    }

    public Mono<AccountDtos.AccountSettingsResponse> update(AccountDtos.UpdateAccountSettingsRequest request) {
        return values()
            .flatMap(currentValues -> {
                AccountDtos.AccountSettingsResponse current = response(currentValues);
                boolean gptFastMode = request == null || request.gptFastModeEnabled() == null
                    ? Boolean.TRUE.equals(current.gptFastModeEnabled())
                    : Boolean.TRUE.equals(request.gptFastModeEnabled());
                AccountDtos.DisabledAccountScheduledTestSettings disabledTest = mergeDisabledTestSettings(
                    current.disabledAccountScheduledTest(),
                    request == null ? null : request.disabledAccountScheduledTest()
                );
                AccountDtos.PoolPolicySettings poolPolicy = mergePoolPolicySettings(
                    current.poolPolicy(),
                    request == null ? null : request.poolPolicy()
                );
                return saveSettings(gptFastMode, disabledTest, poolPolicy)
                    .thenReturn(new AccountDtos.AccountSettingsResponse(gptFastMode, disabledTest, poolPolicy));
            });
    }

    public Mono<Boolean> gptFastModeEnabled() {
        return value(GPT_FAST_MODE_ENABLED, "false").map(Boolean::parseBoolean);
    }

    public Mono<AccountDtos.DisabledAccountScheduledTestSettings> disabledAccountScheduledTestSettings() {
        return values().map(values -> response(values).disabledAccountScheduledTest());
    }

    public Mono<Void> ensureGptFastModeAllowed() {
        return gptFastModeEnabled()
            .flatMap(enabled -> Boolean.TRUE.equals(enabled)
                ? Mono.empty()
                : Mono.error(new ApiException(
                    HttpStatus.FORBIDDEN,
                    "GPT_FAST_MODE_DISABLED",
                    "GPT fast mode is not allowed by administrator"
                )));
    }

    private Mono<String> value(String key, String fallback) {
        return repository.findById(key)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty(fallback);
    }

    private Mono<Map<String, String>> values() {
        return repository.findAllById(ACCOUNT_SETTING_KEYS)
            .collectMap(SystemSettingEntity::getSettingKey, SystemSettingEntity::getSettingValue);
    }

    private AccountDtos.AccountSettingsResponse response(Map<String, String> values) {
        return new AccountDtos.AccountSettingsResponse(
            bool(values, GPT_FAST_MODE_ENABLED, false),
            disabledTestSettings(values),
            poolPolicySettings(values)
        );
    }

    private AccountDtos.DisabledAccountScheduledTestSettings disabledTestSettings(Map<String, String> values) {
        return new AccountDtos.DisabledAccountScheduledTestSettings(
            bool(values, DISABLED_TEST_ENABLED, false),
            mode(values.get(DISABLED_TEST_SCHEDULE_MODE)),
            intValue(values, DISABLED_TEST_INTERVAL_MINUTES, 60, 1, 7 * 24 * 60),
            text(values.get(DISABLED_TEST_CRON_EXPRESSION), "0 0 * * * *"),
            text(values.get(DISABLED_TEST_TIMEZONE), "Asia/Shanghai"),
            intValue(values, DISABLED_TEST_INITIAL_DELAY_MINUTES, 5, 0, 24 * 60),
            intValue(values, DISABLED_TEST_MAX_CONCURRENCY, 2, 1, 16),
            intValue(values, DISABLED_TEST_MAX_ACCOUNTS_PER_RUN, 20, 1, 1000),
            text(values.get(DISABLED_TEST_MODEL), "auto"),
            emptyToNull(values.get(DISABLED_TEST_PROMPT)),
            bool(values, DISABLED_TEST_UPDATE_STATUS, true),
            bool(values, DISABLED_TEST_AUTO_RUN_WHEN_NO_AVAILABLE, false),
            bool(values, DISABLED_TEST_INCLUDE_DISABLED, true),
            bool(values, DISABLED_TEST_INCLUDE_SUSPENDED, true),
            bool(values, DISABLED_TEST_INCLUDE_QUOTA_EXHAUSTED, false),
            intValue(values, DISABLED_TEST_MIN_AGE_MINUTES, 10, 0, 30 * 24 * 60),
            providers(values.get(DISABLED_TEST_PROVIDERS))
        );
    }

    AccountDtos.PoolPolicySettings defaultPoolPolicySettings() {
        return poolPolicySettings(Map.of());
    }

    public Mono<AccountDtos.PoolPolicySettings> poolPolicySettings() {
        return values().map(values -> response(values).poolPolicy());
    }

    private AccountDtos.PoolPolicySettings poolPolicySettings(Map<String, String> values) {
        return new AccountDtos.PoolPolicySettings(
            bool(values, POOL_POLICY_ENABLED, true),
            intValue(values, POOL_POLICY_WINDOW_MINUTES, 5, 1, 24 * 60),
            intValue(values, POOL_POLICY_MIN_SAMPLE_SIZE, 10, 1, 100000),
            doubleValue(values, POOL_POLICY_RETEST_THRESHOLD_PERCENT, 50.0, 0.0, 100.0),
            doubleValue(values, POOL_POLICY_FAILOVER_THRESHOLD_PERCENT, 70.0, 0.0, 100.0),
            doubleValue(values, POOL_POLICY_RECOVERY_THRESHOLD_PERCENT, 70.0, 0.0, 100.0),
            intValue(values, POOL_POLICY_RETEST_INTERVAL_MINUTES, 5, 1, 24 * 60),
            intValue(values, POOL_POLICY_RETEST_MAX_CONCURRENCY, 4, 1, 16),
            bool(values, POOL_POLICY_AUTO_WEIGHT_ENABLED, true)
        );
    }

    private AccountDtos.DisabledAccountScheduledTestSettings mergeDisabledTestSettings(
        AccountDtos.DisabledAccountScheduledTestSettings current,
        AccountDtos.DisabledAccountScheduledTestSettings request
    ) {
        if (request == null) {
            return current;
        }
        String scheduleMode = request.scheduleMode() == null ? current.scheduleMode() : mode(request.scheduleMode());
        String cronExpression = text(firstNonNull(request.cronExpression(), current.cronExpression()), "0 0 * * * *");
        String timezone = text(firstNonNull(request.timezone(), current.timezone()), "Asia/Shanghai");
        validateSchedule(scheduleMode, cronExpression, timezone);
        return new AccountDtos.DisabledAccountScheduledTestSettings(
            request.enabled() == null ? current.enabled() : Boolean.TRUE.equals(request.enabled()),
            scheduleMode,
            clamp(firstNonNull(request.intervalMinutes(), current.intervalMinutes()), 1, 7 * 24 * 60),
            cronExpression,
            timezone,
            clamp(firstNonNull(request.initialDelayMinutes(), current.initialDelayMinutes()), 0, 24 * 60),
            clamp(firstNonNull(request.maxConcurrency(), current.maxConcurrency()), 1, 16),
            clamp(firstNonNull(request.maxAccountsPerRun(), current.maxAccountsPerRun()), 1, 1000),
            text(firstNonNull(request.model(), current.model()), "auto"),
            emptyToNull(firstNonNull(request.prompt(), current.prompt())),
            request.updateStatus() == null ? current.updateStatus() : Boolean.TRUE.equals(request.updateStatus()),
            request.autoRunWhenNoAvailableAccounts() == null
                ? current.autoRunWhenNoAvailableAccounts()
                : Boolean.TRUE.equals(request.autoRunWhenNoAvailableAccounts()),
            request.includeDisabled() == null ? current.includeDisabled() : Boolean.TRUE.equals(request.includeDisabled()),
            request.includeSuspended() == null ? current.includeSuspended() : Boolean.TRUE.equals(request.includeSuspended()),
            request.includeQuotaExhausted() == null ? current.includeQuotaExhausted() : Boolean.TRUE.equals(request.includeQuotaExhausted()),
            clamp(firstNonNull(request.minAgeMinutes(), current.minAgeMinutes()), 0, 30 * 24 * 60),
            request.providers() == null ? current.providers() : providers(String.join(",", request.providers()))
        );
    }

    private AccountDtos.PoolPolicySettings mergePoolPolicySettings(
        AccountDtos.PoolPolicySettings current,
        AccountDtos.PoolPolicySettings request
    ) {
        if (request == null) {
            return current;
        }
        return normalizePoolPolicySettings(new AccountDtos.PoolPolicySettings(
            request.enabled() == null ? current.enabled() : Boolean.TRUE.equals(request.enabled()),
            firstNonNull(request.windowMinutes(), current.windowMinutes()),
            firstNonNull(request.minSampleSize(), current.minSampleSize()),
            firstNonNull(request.retestThresholdPercent(), current.retestThresholdPercent()),
            firstNonNull(request.failoverThresholdPercent(), current.failoverThresholdPercent()),
            firstNonNull(request.recoveryThresholdPercent(), current.recoveryThresholdPercent()),
            firstNonNull(request.retestIntervalMinutes(), current.retestIntervalMinutes()),
            firstNonNull(request.retestMaxConcurrency(), current.retestMaxConcurrency()),
            request.autoWeightEnabled() == null ? current.autoWeightEnabled() : Boolean.TRUE.equals(request.autoWeightEnabled())
        ));
    }

    private AccountDtos.PoolPolicySettings normalizePoolPolicySettings(AccountDtos.PoolPolicySettings settings) {
        double retest = clamp(firstNonNull(settings.retestThresholdPercent(), 50.0), 0.0, 100.0);
        double failover = clamp(firstNonNull(settings.failoverThresholdPercent(), 70.0), 0.0, 100.0);
        double recovery = clamp(firstNonNull(settings.recoveryThresholdPercent(), 70.0), 0.0, 100.0);
        if (failover < retest) {
            failover = retest;
        }
        return new AccountDtos.PoolPolicySettings(
            Boolean.TRUE.equals(settings.enabled()),
            clamp(firstNonNull(settings.windowMinutes(), 5), 1, 24 * 60),
            clamp(firstNonNull(settings.minSampleSize(), 10), 1, 100000),
            retest,
            failover,
            recovery,
            clamp(firstNonNull(settings.retestIntervalMinutes(), 5), 1, 24 * 60),
            clamp(firstNonNull(settings.retestMaxConcurrency(), 4), 1, 16),
            Boolean.TRUE.equals(settings.autoWeightEnabled())
        );
    }

    private void validateSchedule(String scheduleMode, String cronExpression, String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (ZoneRulesException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE", "Invalid disabled account test timezone");
        }
        if ("cron".equals(scheduleMode)) {
            try {
                CronExpression.parse(cronExpression);
            } catch (IllegalArgumentException error) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CRON_EXPRESSION", "Invalid disabled account test cron expression");
            }
        }
    }

    private Mono<Void> saveSettings(
        boolean gptFastMode,
        AccountDtos.DisabledAccountScheduledTestSettings disabledTest,
        AccountDtos.PoolPolicySettings poolPolicy
    ) {
        return Flux.concat(
            set(GPT_FAST_MODE_ENABLED, Boolean.toString(gptFastMode)),
            set(DISABLED_TEST_ENABLED, Boolean.toString(Boolean.TRUE.equals(disabledTest.enabled()))),
            set(DISABLED_TEST_SCHEDULE_MODE, disabledTest.scheduleMode()),
            set(DISABLED_TEST_INTERVAL_MINUTES, Integer.toString(disabledTest.intervalMinutes())),
            set(DISABLED_TEST_CRON_EXPRESSION, disabledTest.cronExpression()),
            set(DISABLED_TEST_TIMEZONE, disabledTest.timezone()),
            set(DISABLED_TEST_INITIAL_DELAY_MINUTES, Integer.toString(disabledTest.initialDelayMinutes())),
            set(DISABLED_TEST_MAX_CONCURRENCY, Integer.toString(disabledTest.maxConcurrency())),
            set(DISABLED_TEST_MAX_ACCOUNTS_PER_RUN, Integer.toString(disabledTest.maxAccountsPerRun())),
            set(DISABLED_TEST_MODEL, disabledTest.model()),
            set(DISABLED_TEST_PROMPT, disabledTest.prompt() == null ? "" : disabledTest.prompt()),
            set(DISABLED_TEST_UPDATE_STATUS, Boolean.toString(Boolean.TRUE.equals(disabledTest.updateStatus()))),
            set(DISABLED_TEST_AUTO_RUN_WHEN_NO_AVAILABLE, Boolean.toString(Boolean.TRUE.equals(disabledTest.autoRunWhenNoAvailableAccounts()))),
            set(DISABLED_TEST_INCLUDE_DISABLED, Boolean.toString(Boolean.TRUE.equals(disabledTest.includeDisabled()))),
            set(DISABLED_TEST_INCLUDE_SUSPENDED, Boolean.toString(Boolean.TRUE.equals(disabledTest.includeSuspended()))),
            set(DISABLED_TEST_INCLUDE_QUOTA_EXHAUSTED, Boolean.toString(Boolean.TRUE.equals(disabledTest.includeQuotaExhausted()))),
            set(DISABLED_TEST_MIN_AGE_MINUTES, Integer.toString(disabledTest.minAgeMinutes())),
            set(DISABLED_TEST_PROVIDERS, String.join(",", disabledTest.providers())),
            set(POOL_POLICY_ENABLED, Boolean.toString(Boolean.TRUE.equals(poolPolicy.enabled()))),
            set(POOL_POLICY_WINDOW_MINUTES, Integer.toString(poolPolicy.windowMinutes())),
            set(POOL_POLICY_MIN_SAMPLE_SIZE, Integer.toString(poolPolicy.minSampleSize())),
            set(POOL_POLICY_RETEST_THRESHOLD_PERCENT, Double.toString(poolPolicy.retestThresholdPercent())),
            set(POOL_POLICY_FAILOVER_THRESHOLD_PERCENT, Double.toString(poolPolicy.failoverThresholdPercent())),
            set(POOL_POLICY_RECOVERY_THRESHOLD_PERCENT, Double.toString(poolPolicy.recoveryThresholdPercent())),
            set(POOL_POLICY_RETEST_INTERVAL_MINUTES, Integer.toString(poolPolicy.retestIntervalMinutes())),
            set(POOL_POLICY_RETEST_MAX_CONCURRENCY, Integer.toString(poolPolicy.retestMaxConcurrency())),
            set(POOL_POLICY_AUTO_WEIGHT_ENABLED, Boolean.toString(Boolean.TRUE.equals(poolPolicy.autoWeightEnabled())))
        ).then();
    }

    private Mono<Integer> set(String key, String value) {
        return repository.upsertSetting(key, value == null ? "" : value);
    }

    private boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private int intValue(Map<String, String> values, String key, int fallback, int min, int max) {
        try {
            return clamp(Integer.parseInt(text(values.get(key), Integer.toString(fallback))), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int clamp(Integer value, int min, int max) {
        int safeValue = value == null ? min : value;
        return Math.max(min, Math.min(max, safeValue));
    }

    private double clamp(Double value, double min, double max) {
        double safeValue = value == null ? min : value;
        return Math.max(min, Math.min(max, safeValue));
    }

    private double doubleValue(Map<String, String> values, String key, double fallback, double min, double max) {
        try {
            return clamp(Double.parseDouble(text(values.get(key), Double.toString(fallback))), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String mode(String value) {
        String normalized = text(value, "interval").toLowerCase(Locale.ROOT);
        return "cron".equals(normalized) ? "cron" : "interval";
    }

    private String text(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private String emptyToNull(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private List<String> providers(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private <T> T firstNonNull(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
