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

    @Test
    void theLoadViewSaysWhetherAnAccountIsAtItsConcurrencyOrTokenLimitAndWhetherSelectionMayPickIt() {
        assertThat(snapshot(0, 1_000, 3, 4).load())
            .isEqualTo(new AccountCandidateRanker.AccountLoad("HEALTHY", true, false));
        assertThat(snapshot(0, 1_000, 4, 4).load().saturated()).isTrue();
        assertThat(snapshot(0, 1_000, 9, 4).load().saturated()).isTrue();
        // A limit of 0 is no limit, as admission reads it.
        assertThat(snapshot(0, 1_000, 50, 0).load().saturated()).isFalse();
        assertThat(load("HEALTHY", 0, 0, 400_000, 600_000).load().saturated()).as("no in-flight token budget left").isTrue();
        assertThat(load("HEALTHY", 0, 0, 400_000, 599_999).load().saturated()).isFalse();
        assertThat(load("WARMING", 0, 0, 0, 0).load().schedulable()).isTrue();
        assertThat(load("DEGRADED", 0, 0, 0, 0).load().schedulable()).isTrue();
        assertThat(load("DISABLED", 0, 0, 0, 0).load().schedulable()).isFalse();
    }

    @Test
    void aCooldownEndedByTheClockIsSchedulableAgainAndOneThatRunsIsNot() {
        Instant later = Instant.now().plusSeconds(3_600);
        Instant earlier = Instant.now().minusSeconds(60);
        for (String state : new String[] {"COOLDOWN", "QUARANTINED"}) {
            assertThat(withCooldown(state, later).load().schedulable()).as(state + " until later").isFalse();
            assertThat(withCooldown(state, earlier).load().schedulable()).as(state + " ended").isTrue();
            assertThat(withCooldown(state, null).load().schedulable()).as(state + " without a deadline").isFalse();
        }
    }

    private AccountCandidateRanker.AllocationSnapshot load(
        String lifecycle,
        int activeConcurrency,
        int safeConcurrency,
        long reservedInput,
        long reservedOutput
    ) {
        return new AccountCandidateRanker.AllocationSnapshot("account-1", 100, safeConcurrency, 1_000_000, lifecycle, 1L, null,
            0, 0, activeConcurrency, reservedInput, reservedOutput);
    }

    private AccountCandidateRanker.AllocationSnapshot withCooldown(String lifecycle, Instant until) {
        return new AccountCandidateRanker.AllocationSnapshot("account-1", 100, 4, 1_000_000, lifecycle, 1L, until, 0, 0, 0, 0, 0);
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
