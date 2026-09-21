package tech.starryapi.account.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tech.starryapi.account.ProxyAccountEntity;

class AccountCandidateRankerTest {

    @Test
    void openAiRecentErrorRateHasMoreInfluenceThanLatency() {
        ProxyAccountEntity account = account();
        AccountCandidateRanker.AllocationSnapshot stableButSlow = snapshot(0.05, 15_000, 0, 8);
        AccountCandidateRanker.AllocationSnapshot fastButFailing = snapshot(0.50, 1_000, 0, 8);

        assertThat(AccountCandidateRanker.openAiScore(account, fastButFailing))
            .isGreaterThan(AccountCandidateRanker.openAiScore(account, stableButSlow));
    }

    @Test
    void anthropicCapacityPressureRaisesRiskWithinOnePriorityTier() {
        ProxyAccountEntity account = account();
        AccountCandidateRanker.AllocationSnapshot idle = snapshot(0.02, 2_000, 0, 4);
        AccountCandidateRanker.AllocationSnapshot saturated = snapshot(0.02, 2_000, 4, 4);

        assertThat(AccountCandidateRanker.anthropicScore(account, saturated))
            .isGreaterThan(AccountCandidateRanker.anthropicScore(account, idle));
    }

    private ProxyAccountEntity account() {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId("account-1");
        account.setPoolWeight(100);
        return account;
    }

    private AccountCandidateRanker.AllocationSnapshot snapshot(
        double errorRate,
        double latencyMs,
        int activeConcurrency,
        int safeConcurrency
    ) {
        return new AccountCandidateRanker.AllocationSnapshot(
            "account-1",
            100,
            safeConcurrency,
            1_000_000,
            "HEALTHY",
            1L,
            null,
            errorRate,
            latencyMs,
            activeConcurrency,
            0,
            0
        );
    }
}
