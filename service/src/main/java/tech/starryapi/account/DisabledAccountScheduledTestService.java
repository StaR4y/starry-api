package tech.starryapi.account;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

@Service
public class DisabledAccountScheduledTestService {

    private static final Logger log = LoggerFactory.getLogger(DisabledAccountScheduledTestService.class);

    private final AccountSettingsService settingsService;
    private final ProxyAccountRepository repository;
    private final AccountTestService accountTestService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant nextRunAt;
    private volatile String scheduleFingerprint = "";

    public DisabledAccountScheduledTestService(
        AccountSettingsService settingsService,
        ProxyAccountRepository repository,
        AccountTestService accountTestService
    ) {
        this.settingsService = settingsService;
        this.repository = repository;
        this.accountTestService = accountTestService;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledTick() {
        tick().subscribe(
            null,
            error -> log.warn("Scheduled disabled account test failed unexpectedly", error)
        );
    }

    Mono<Void> tick() {
        return settingsService.disabledAccountScheduledTestSettings()
            .flatMap(settings -> {
                if (!Boolean.TRUE.equals(settings.enabled())) {
                    nextRunAt = null;
                    return Mono.empty();
                }
                if (!due(settings, Instant.now())) {
                    return Mono.empty();
                }
                return runWithLock(settings, false, false).then();
            });
    }

    public Mono<AccountDtos.TestAccountsResponse> runNow() {
        return settingsService.disabledAccountScheduledTestSettings()
            .flatMap(settings -> runWithLock(settings, true, true));
    }

    private boolean due(AccountDtos.DisabledAccountScheduledTestSettings settings, Instant now) {
        String fingerprint = fingerprint(settings);
        if (!Objects.equals(fingerprint, scheduleFingerprint) || nextRunAt == null) {
            scheduleFingerprint = fingerprint;
            nextRunAt = now.plus(Duration.ofMinutes(Math.max(0, settings.initialDelayMinutes())));
        }
        if (now.isBefore(nextRunAt)) {
            return false;
        }
        nextRunAt = nextAfter(settings, now);
        return true;
    }

    private Instant nextAfter(AccountDtos.DisabledAccountScheduledTestSettings settings, Instant now) {
        if ("cron".equalsIgnoreCase(settings.scheduleMode())) {
            try {
                ZoneId zone = ZoneId.of(settings.timezone());
                ZonedDateTime next = CronExpression.parse(settings.cronExpression()).next(now.atZone(zone));
                if (next != null) {
                    return next.toInstant();
                }
            } catch (RuntimeException error) {
                log.warn("Invalid disabled account scheduled test cron settings; falling back to interval", error);
            }
        }
        int minutes = Math.max(1, settings.intervalMinutes());
        return now.plus(Duration.ofMinutes(minutes));
    }

    private Mono<AccountDtos.TestAccountsResponse> runWithLock(
        AccountDtos.DisabledAccountScheduledTestSettings settings,
        boolean failWhenRunning,
        boolean manualRun
    ) {
        if (!running.compareAndSet(false, true)) {
            if (failWhenRunning) {
                return Mono.error(new ApiException(
                    HttpStatus.CONFLICT,
                    "DISABLED_ACCOUNT_TEST_RUNNING",
                    "Disabled account scheduled test is already running"
                ));
            }
            return Mono.empty();
        }
        return targetAccountIds(settings, manualRun)
            .flatMap(ids -> ids.isEmpty()
                ? Mono.just(emptyResponse())
                : accountTestService.test(new AccountDtos.TestAccountsRequest(
                    ids,
                    false,
                    settings.model(),
                    settings.prompt(),
                    settings.maxConcurrency(),
                    settings.updateStatus()
                )))
            .doOnNext(response -> log.info(
                "Disabled account scheduled test completed: total={}, success={}, failed={}, skipped={}",
                response.total(),
                response.success(),
                response.failed(),
                response.skipped()
            ))
            .doFinally(signal -> running.set(false));
    }

    private Mono<List<String>> targetAccountIds(
        AccountDtos.DisabledAccountScheduledTestSettings settings,
        boolean manualRun
    ) {
        Instant now = Instant.now();
        var targets = repository.findAll()
            .filter(account -> manualRun || matchesState(account, settings))
            .filter(account -> matchesProvider(account, settings.providers()))
            .filter(account -> manualRun || oldEnough(account, settings.minAgeMinutes(), now))
            .map(ProxyAccountEntity::getAccountId)
            .filter(accountId -> accountId != null && !accountId.isBlank());
        if (!manualRun) {
            targets = targets.take(settings.maxAccountsPerRun());
        }
        return targets.collectList();
    }

    private boolean matchesState(
        ProxyAccountEntity account,
        AccountDtos.DisabledAccountScheduledTestSettings settings
    ) {
        boolean disabled = Boolean.FALSE.equals(account.getEnabled());
        boolean suspended = account.getSuspendedAt() != null;
        boolean quotaExhausted = account.getQuotaExhaustedAt() != null;
        return (Boolean.TRUE.equals(settings.includeDisabled()) && disabled)
            || (Boolean.TRUE.equals(settings.includeSuspended()) && suspended)
            || (Boolean.TRUE.equals(settings.includeQuotaExhausted()) && quotaExhausted);
    }

    private boolean matchesProvider(ProxyAccountEntity account, List<String> providers) {
        if (providers == null || providers.isEmpty()) {
            return true;
        }
        String provider = key(account.getProvider());
        String authMethod = key(account.getAuthMethod());
        return providers.stream()
            .map(this::key)
            .anyMatch(item -> item.equals(provider) || item.equals(authMethod));
    }

    private boolean oldEnough(ProxyAccountEntity account, Integer minAgeMinutes, Instant now) {
        int minutes = minAgeMinutes == null ? 0 : Math.max(0, minAgeMinutes);
        if (minutes == 0) {
            return true;
        }
        Instant since = firstNonNull(
            account.getSuspendedAt(),
            account.getQuotaExhaustedAt(),
            account.getUpdatedAt(),
            account.getCreatedAt(),
            account.getLastUsedAt()
        );
        return since == null || !since.plus(Duration.ofMinutes(minutes)).isAfter(now);
    }

    private AccountDtos.TestAccountsResponse emptyResponse() {
        Instant now = Instant.now();
        return new AccountDtos.TestAccountsResponse(0, 0, 0, 0, now, now, List.of());
    }

    private String fingerprint(AccountDtos.DisabledAccountScheduledTestSettings settings) {
        return String.join(
            "|",
            settings.scheduleMode(),
            Integer.toString(settings.intervalMinutes()),
            settings.cronExpression(),
            settings.timezone(),
            Integer.toString(settings.initialDelayMinutes())
        );
    }

    private String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Instant firstNonNull(Instant... values) {
        for (Instant value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
