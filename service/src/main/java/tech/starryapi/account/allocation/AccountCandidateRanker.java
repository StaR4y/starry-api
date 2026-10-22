package tech.starryapi.account.allocation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.ProxyAccountEntity;

@Service
public class AccountCandidateRanker {

    private static final int OPENAI_TOP_K = 3;

    private final DatabaseClient databaseClient;
    private final Cache<String, AllocationSnapshot> snapshots = Caffeine.newBuilder()
        .maximumSize(20_000)
        .expireAfterWrite(Duration.ofSeconds(2))
        .build();

    public AccountCandidateRanker(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<ProxyAccountEntity> select(List<ProxyAccountEntity> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Mono.empty();
        }
        return snapshots(candidates).flatMap(snapshotMap -> {
            List<RankedCandidate> ranked = candidates.stream()
                .map(account -> ranked(account, snapshotMap.get(account.getAccountId())))
                .filter(candidate -> candidate.snapshot() == null || candidate.snapshot().schedulable())
                .toList();
            if (ranked.isEmpty()) {
                return Mono.empty();
            }
            if (isAnthropicPool(candidates)) {
                return Mono.just(selectAnthropic(ranked).account());
            }
            if (isOpenAiPool(candidates)) {
                return Mono.just(selectOpenAi(ranked).account());
            }
            return Mono.just(selectGeneral(ranked).account());
        });
    }

    static double openAiScore(ProxyAccountEntity account, AllocationSnapshot snapshot) {
        double errorRisk = snapshot == null ? 0 : snapshot.recentErrorEwma() * 60.0;
        double latencyRisk = snapshot == null ? 0 : Math.min(snapshot.recentLatencyEwmaMs() / 30_000.0, 1.0) * 10.0;
        double concurrencyPressure = snapshot == null || snapshot.safeConcurrency() == 0
            ? 0
            : ratio(snapshot.activeConcurrency(), Math.max(snapshot.safeConcurrency(), 1)) * 20.0;
        double tokenPressure = snapshot == null
            ? 0
            : ratio(
                snapshot.reservedInputTokens() + snapshot.reservedOutputTokens(),
                Math.max(snapshot.maxInflightTokens(), 1)
            ) * 10.0;
        double priorityAdjustment = snapshot == null ? 0 : Math.min(snapshot.allocationPriority(), 1000) / 100.0;
        double capacityWeight = Math.log1p(Math.max(Optional.ofNullable(account.getPoolWeight()).orElse(100), 1));
        double quotaHeadroom = quotaHeadroom(account) * 8.0;
        return errorRisk + latencyRisk + concurrencyPressure + tokenPressure + priorityAdjustment
            - capacityWeight - quotaHeadroom;
    }

    static double anthropicScore(ProxyAccountEntity account, AllocationSnapshot snapshot) {
        double errorRisk = snapshot == null ? 0 : snapshot.recentErrorEwma() * 50.0;
        double latencyRisk = snapshot == null ? 0 : Math.min(snapshot.recentLatencyEwmaMs() / 60_000.0, 1.0) * 8.0;
        double concurrencyPressure = snapshot == null || snapshot.safeConcurrency() == 0
            ? 0
            : ratio(snapshot.activeConcurrency(), Math.max(snapshot.safeConcurrency(), 1)) * 30.0;
        double tokenPressure = snapshot == null
            ? 0
            : ratio(
                snapshot.reservedInputTokens() + snapshot.reservedOutputTokens(),
                Math.max(snapshot.maxInflightTokens(), 1)
            ) * 12.0;
        return errorRisk + latencyRisk + concurrencyPressure + tokenPressure - quotaHeadroom(account) * 5.0;
    }

    private Mono<Map<String, AllocationSnapshot>> snapshots(List<ProxyAccountEntity> candidates) {
        Map<String, AllocationSnapshot> result = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (ProxyAccountEntity candidate : candidates) {
            String accountId = candidate.getAccountId();
            AllocationSnapshot cached = snapshots.getIfPresent(accountId);
            if (cached == null) {
                missing.add(accountId);
            } else {
                result.put(accountId, cached);
            }
        }
        if (missing.isEmpty()) {
            return Mono.just(result);
        }

        StringBuilder sql = new StringBuilder("""
            SELECT
                policy.account_id,
                policy.allocation_priority,
                policy.safe_concurrency,
                policy.max_inflight_tokens,
                lifecycle.lifecycle_state,
                lifecycle.state_version,
                lifecycle.cooldown_until,
                lifecycle.recent_error_ewma,
                lifecycle.recent_latency_ewma_ms,
                admission.active_concurrency,
                admission.reserved_input_tokens,
                admission.reserved_output_tokens
            FROM account_allocation_policies policy
            JOIN account_allocation_states lifecycle USING (account_id)
            JOIN account_admission_states admission USING (account_id)
            WHERE policy.account_id IN (
            """);
        for (int index = 0; index < missing.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(":account").append(index);
        }
        sql.append(')');
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString());
        for (int index = 0; index < missing.size(); index++) {
            spec = spec.bind("account" + index, missing.get(index));
        }
        return spec.map((row, metadata) -> new AllocationSnapshot(
                row.get("account_id", String.class),
                integer(row.get("allocation_priority"), 100),
                integer(row.get("safe_concurrency"), 1),
                longValue(row.get("max_inflight_tokens"), 1),
                row.get("lifecycle_state", String.class),
                longObject(row.get("state_version")),
                instant(row.get("cooldown_until")),
                doubleValue(row.get("recent_error_ewma")),
                doubleValue(row.get("recent_latency_ewma_ms")),
                integer(row.get("active_concurrency"), 0),
                longValue(row.get("reserved_input_tokens"), 0),
                longValue(row.get("reserved_output_tokens"), 0)
            ))
            .all()
            .collectList()
            .map(loaded -> {
                loaded.forEach(snapshot -> {
                    snapshots.put(snapshot.accountId(), snapshot);
                    result.put(snapshot.accountId(), snapshot);
                });
                return result;
            });
    }

    private RankedCandidate ranked(ProxyAccountEntity account, AllocationSnapshot snapshot) {
        return new RankedCandidate(account, snapshot);
    }

    private RankedCandidate selectAnthropic(List<RankedCandidate> candidates) {
        int priority = candidates.stream()
            .mapToInt(candidate -> candidate.snapshot() == null ? 100 : candidate.snapshot().allocationPriority())
            .min()
            .orElse(100);
        return candidates.stream()
            .filter(candidate -> (candidate.snapshot() == null ? 100 : candidate.snapshot().allocationPriority()) == priority)
            .min(Comparator
                .comparingDouble((RankedCandidate candidate) -> anthropicScore(candidate.account(), candidate.snapshot()))
                .thenComparing(candidate -> Optional.ofNullable(candidate.account().getLastUsedAt()).orElse(Instant.EPOCH)))
            .orElse(candidates.get(0));
    }

    private RankedCandidate selectOpenAi(List<RankedCandidate> candidates) {
        List<ScoredCandidate> top = candidates.stream()
            .map(candidate -> new ScoredCandidate(
                candidate,
                openAiScore(candidate.account(), candidate.snapshot())
            ))
            .sorted(Comparator.comparingDouble(ScoredCandidate::score))
            .limit(OPENAI_TOP_K)
            .toList();
        if (top.size() == 1) {
            return top.get(0).candidate();
        }
        double best = top.get(0).score();
        double total = top.stream().mapToDouble(candidate -> selectionWeight(candidate.score(), best)).sum();
        double cursor = ThreadLocalRandom.current().nextDouble(total);
        for (ScoredCandidate candidate : top) {
            cursor -= selectionWeight(candidate.score(), best);
            if (cursor <= 0) {
                return candidate.candidate();
            }
        }
        return top.get(0).candidate();
    }

    private RankedCandidate selectGeneral(List<RankedCandidate> candidates) {
        return candidates.stream()
            .min(Comparator
                .comparingDouble((RankedCandidate candidate) -> anthropicScore(candidate.account(), candidate.snapshot()))
                .thenComparing(candidate -> Optional.ofNullable(candidate.account().getLastUsedAt()).orElse(Instant.EPOCH)))
            .orElse(candidates.get(0));
    }

    private static double selectionWeight(double score, double best) {
        return 1.0 / (1.0 + Math.max(0, score - best));
    }

    private static boolean isOpenAiPool(List<ProxyAccountEntity> candidates) {
        return candidates.stream().allMatch(account -> AccountPlatforms.isOpenAi(account) || AccountPlatforms.isDeepSeek(account));
    }

    private static boolean isAnthropicPool(List<ProxyAccountEntity> candidates) {
        return candidates.stream().allMatch(AccountPlatforms::isAnthropicApiKey);
    }

    private static double quotaHeadroom(ProxyAccountEntity account) {
        Long limit = account.getQuotaLimit();
        Long used = account.getQuotaUsed();
        if (limit == null || limit <= 0 || used == null) {
            return 0.5;
        }
        return Math.max(0, Math.min(1, (limit - used) / (double) limit));
    }

    private static double ratio(long numerator, long denominator) {
        return Math.max(0, Math.min(1, numerator / (double) denominator));
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static Long longObject(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        return null;
    }

    record AllocationSnapshot(
        String accountId,
        int allocationPriority,
        int safeConcurrency,
        long maxInflightTokens,
        String lifecycleState,
        Long stateVersion,
        Instant cooldownUntil,
        double recentErrorEwma,
        double recentLatencyEwmaMs,
        int activeConcurrency,
        long reservedInputTokens,
        long reservedOutputTokens
    ) {
        boolean schedulable() {
            if ("HEALTHY".equals(lifecycleState) || "DEGRADED".equals(lifecycleState) || "WARMING".equals(lifecycleState)) {
                return true;
            }
            return ("COOLDOWN".equals(lifecycleState) || "QUARANTINED".equals(lifecycleState))
                && cooldownUntil != null
                && !cooldownUntil.isAfter(Instant.now());
        }
    }

    private record RankedCandidate(ProxyAccountEntity account, AllocationSnapshot snapshot) {
    }

    private record ScoredCandidate(RankedCandidate candidate, double score) {
    }
}
