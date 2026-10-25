package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.account.allocation.AccountAllocationCategory;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.DefaultModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.proxy.tier.TierRetryPolicy.FailedAttempt;
import tech.starryapi.proxy.tier.TierRetryPolicy.Outcome;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where a tiered request goes after an account failed (design 4.5): the tier it stays in, the tier below, and when it
 * may go no further than the tier it is in.
 */
class TierRetryPolicyTest {

    private static final Instant START = Instant.parse("2026-10-25T10:00:00Z");
    /** Two accounts per request, which is what the Anthropic and Kiro routes allow by default. */
    private static final int TWO = 2;
    private static final int THREE = 3;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultModelCatalogProvider catalog = new DefaultModelCatalogProvider();
    private final SourceTierRegistry registry = mock(SourceTierRegistry.class);
    private final AccountPoolService accountPool = mock(AccountPoolService.class);
    private final PaymentSettingsService payments = mock(PaymentSettingsService.class);
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final DefaultSourceTierPlanner planner = new DefaultSourceTierPlanner(
        registry, new ModelPoolKeyResolver(catalog), catalog, accountPool, payments, pricing, TierSessionBindings.disabled());
    private final MovableClock clock = new MovableClock(START);
    /** What the facade would answer when asked whether the tier that failed has an account the request has not tried. */
    private boolean accountsLeftInTier = true;
    /** The accounts of the account cache: one of each kind of tier, so that no tier is left out of a plan for having none. */
    private final List<ProxyAccountEntity> cached = List.of(
        account("official-1", "Anthropic", "api-key"), account("oauth-1", "Anthropic", "oauth"), account("kiro-1", "BuilderId", "idc"),
        account("openai-1", "OpenAI", "api-key"), account("official-owner", "OpenAI", "api-key"),
        account("codex-owner", "OpenAI", "codex"));

    @BeforeEach
    void stubTheCollaborators() {
        when(accountPool.cachedAccounts(any())).thenAnswer(invocation -> {
            Predicate<ProxyAccountEntity> filter = invocation.getArgument(0);
            return cached.stream().filter(filter).toList();
        });
        when(accountPool.isUsable(any())).thenReturn(true);
        when(accountPool.cachedAccount(anyString())).thenAnswer(invocation ->
            cached.stream().filter(account -> account.getAccountId().equals(invocation.getArgument(0))).findFirst());
        when(payments.config()).thenReturn(Mono.just(paymentConfig()));
        when(pricing.billingQuote(anyString(), anyBoolean(), any())).thenReturn(Mono.just(new BillingQuote(
            new BigDecimal("0.000003"), new BigDecimal("0.000015"), new BigDecimal("0.0000003"),
            new BigDecimal("0.00000375"), new BigDecimal("0.000003"))));
    }

    // ---- what counts as not having started ----

    @Test
    void aFailureIsNotStartedUnlessAnUpstreamErrorInItsCausesSaysGenerationBegan() {
        assertThat(TierRetryPolicy.notStarted(new UpstreamException(429, "slow down", true))).isTrue();
        assertThat(TierRetryPolicy.notStarted(new TimeoutException("no answer"))).isTrue();
        assertThat(TierRetryPolicy.notStarted(new UpstreamException(502, "cut off", true, null, true))).isFalse();
        // Wrapped by whatever the client or the pipeline put around it.
        assertThat(TierRetryPolicy.notStarted(
            new IllegalStateException("wrapped", new UpstreamException(502, "cut off", true, null, true)))).isFalse();
        assertThat(TierRetryPolicy.notStarted(new UpstreamException(502, "cut off", true).withGenerationStarted())).isFalse();
    }

    // ---- staying in a tier and going below it ----

    @Test
    void aDefinitiveFailureWithinTheBudgetIsRetriedFromTheTierThatFailed() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, TWO));

        assertThat(outcome).isEqualTo(Outcome.RETRY);
        assertThat(tierIds(walk)).containsExactly("official", "oauth", "kiro");
        assertThat(policy.narrowed()).isFalse();
        assertThat(walk.excluded()).containsExactly("official-1");
    }

    @Test
    void aRetryNeverGoesBackToATierAboveTheOneThatFailed() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);

        Outcome outcome = policy.onFailure(failure(walk, 1, "oauth-1", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.RETRY);
        assertThat(tierIds(walk)).containsExactly("oauth", "kiro");
    }

    @Test
    void aWalkThatStartedLowStaysThere() {
        TierWalk walk = new TierWalk(plan(true), 2);
        TierRetryPolicy policy = policy(walk, true);

        policy.onFailure(failure(walk, 2, "kiro-1", rejected(), true, THREE));

        assertThat(tierIds(walk)).containsExactly("kiro");
    }

    // ---- crossing needs every failure to be definitive and not started ----

    @Test
    void aFailureThatMayHaveBeenBilledNarrowsTheRequestToItsTierForGood() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", new TimeoutException("no answer"), false, THREE));

        // Still retried, as it is untiered, but only within the tier.
        assertThat(outcome).isEqualTo(Outcome.RETRY);
        assertThat(policy.narrowed()).isTrue();
        assertThat(tierIds(walk)).containsExactly("official");
    }

    @Test
    void aDefinitiveFailureThatHadAlreadyStartedGeneratingNarrowsToo() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        // A Kiro stream cut off after its first content counts as unbilled, but the answer was begun.
        UpstreamException truncated = new UpstreamException(502, "stream ended", true, null, true);

        Outcome outcome = policy.onFailure(failure(walk, 0, "kiro-1", truncated, true, THREE));

        assertThat(outcome).isEqualTo(Outcome.RETRY);
        assertThat(tierIds(walk)).containsExactly("official");
    }

    @Test
    void aDefinitiveFailureWhoseErrorDoesNotSayItButWhoseClientDidStillNarrows() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        // The client retried a stream that was cut off, and the retry failed to connect: the error is a plain
        // ConnectException, and the client's flag is the only thing that remembers the first attempt's content.
        FailedAttempt failed = new FailedAttempt(walk.plan().levels().get(0), "kiro-1",
            new java.net.ConnectException("Connection refused"), true, true, THREE, true, true);

        Outcome outcome = policy.onFailure(failed);

        assertThat(outcome).isEqualTo(Outcome.RETRY);
        assertThat(policy.narrowed()).isTrue();
        assertThat(tierIds(walk)).containsExactly("official");
    }

    @Test
    void theSameFailureWithoutTheFlagCrossesAsItDid() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        FailedAttempt failed = new FailedAttempt(walk.plan().levels().get(0), "kiro-1",
            new java.net.ConnectException("Connection refused"), true, true, THREE, false, true);

        policy.onFailure(failed);

        assertThat(policy.narrowed()).isFalse();
    }

    @Test
    void narrowingHoldsWhenALaterFailureIsDefinitiveAgain() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        policy.onFailure(failure(walk, 0, "official-1", new IOException("reset"), false, THREE));

        policy.onFailure(failure(walk, 0, "official-2", rejected(), true, THREE));

        assertThat(policy.narrowed()).isTrue();
        assertThat(tierIds(walk)).containsExactly("official");
    }

    @Test
    void aNarrowedRequestWhoseBudgetIsSpentStopsInsteadOfUsingTheReservedSlot() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        policy.onFailure(failure(walk, 0, "official-1", new IOException("reset"), false, TWO));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-2", rejected(), true, TWO));

        assertThat(outcome).isEqualTo(Outcome.STOP);
        assertThat(policy.reservedSlotUsed()).isFalse();
    }

    // ---- the reserved slot ----

    @Test
    void aSpentBudgetStillReachesTheTierBelowOnTheReservedSlotOnce() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);

        assertThat(policy.onFailure(failure(walk, 0, "official-1", rejected(), true, TWO))).isEqualTo(Outcome.RETRY);
        Outcome second = policy.onFailure(failure(walk, 0, "official-2", rejected(), true, TWO));

        assertThat(second).isEqualTo(Outcome.RETRY_ON_RESERVED_SLOT);
        assertThat(policy.reservedSlotUsed()).isTrue();
        assertThat(tierIds(walk)).containsExactly("oauth", "kiro");
        assertThat(walk.dispatchedAccounts()).isEqualTo(2);
        // One dispatch more than the budget, and no more: the slot is not given twice.
        Outcome third = policy.onFailure(failure(walk, 1, "oauth-1", rejected(), true, TWO));
        assertThat(third).isEqualTo(Outcome.STOP);
    }

    @Test
    void aSlotCrossingFromATierWithAccountsLeftIsTheBudgetsDoing() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        accountsLeftInTier = true;

        policy.onFailure(failure(walk, 0, "official-1", rejected(), true, TWO));
        policy.onFailure(failure(walk, 0, "official-2", rejected(), true, TWO));

        // The first tier still had an account to try: being served by the next one is no push down.
        assertThat(walk.wasPassed(walk.plan().levels().get(0))).isFalse();
        assertThat(walk.realDowngradeTo(walk.plan().levels().get(1))).isFalse();
    }

    @Test
    void aSlotCrossingFromATierThatWasUsedUpIsARealDowngrade() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        accountsLeftInTier = false;

        policy.onFailure(failure(walk, 0, "official-1", rejected(), true, TWO));
        Outcome second = policy.onFailure(failure(walk, 0, "official-2", rejected(), true, TWO));

        // Both accounts of the tier failed, so it had nothing more to offer when the slot took the request out of it.
        assertThat(second).isEqualTo(Outcome.RETRY_ON_RESERVED_SLOT);
        assertThat(walk.wasPassed(walk.plan().levels().get(0))).isTrue();
        assertThat(walk.realDowngradeTo(walk.plan().levels().get(1))).isTrue();
    }

    // ---- the owner of a response chain ----
    //
    // A chain that can be replayed keeps its owner only when the owner sits in the tier the walk starts from, and the owner
    // is tried ahead of the other accounts of that tier. Its failure is a failure of that tier, like any other account's.

    @Test
    void aFailureOfTheOwnerKeepsTheRetryInTheOwnersTierWhileTheBudgetHolds() {
        TierWalk walk = new TierWalk(openAiPlan(true, "official-owner"));
        TierRetryPolicy policy = policy(walk, true);
        assertThat(walk.plan().owner()).hasValueSatisfying(owner -> assertThat(owner.level().tierId()).isEqualTo("official"));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-owner", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.RETRY);
        assertThat(tierIds(walk)).containsExactly("official", "codex");
        assertThat(walk.excluded()).containsExactly("official-owner");
    }

    @Test
    void theOwnerFailingWithTheBudgetSpentCrossesOnTheReservedSlotInsteadOfSpendingItInItsOwnTier() {
        TierWalk walk = new TierWalk(openAiPlan(true, "official-owner"));
        TierRetryPolicy policy = policy(walk, true);
        // The Codex route allows one account, and the owner's tier has another one left.
        accountsLeftInTier = true;

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-owner", rejected(), true, 1));

        // The slot is for crossing: the retry goes to the tier below, not to the other account of the owner's tier.
        assertThat(outcome).isEqualTo(Outcome.RETRY_ON_RESERVED_SLOT);
        assertThat(policy.reservedSlotUsed()).isTrue();
        assertThat(tierIds(walk)).containsExactly("codex");
        assertThat(walk.dispatchedAccounts()).isEqualTo(1);
        // The budget took the request out of the tier, which had an account left: nobody is pushed down by it.
        assertThat(walk.wasPassed(walk.plan().levels().get(0))).isFalse();
        assertThat(walk.realDowngradeTo(walk.plan().levels().get(1))).isFalse();
    }

    @Test
    void theOwnerBeingTheLastAccountOfItsTierMakesTheCrossingARealDowngrade() {
        TierWalk walk = new TierWalk(openAiPlan(true, "official-owner"));
        TierRetryPolicy policy = policy(walk, true);
        accountsLeftInTier = false;

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-owner", rejected(), true, 1));

        assertThat(outcome).isEqualTo(Outcome.RETRY_ON_RESERVED_SLOT);
        assertThat(tierIds(walk)).containsExactly("codex");
        // The owner's tier had nothing more to offer when the slot took the request out of it.
        assertThat(walk.wasPassed(walk.plan().levels().get(0))).isTrue();
        assertThat(walk.realDowngradeTo(walk.plan().levels().get(1))).isTrue();
    }

    @Test
    void aFailureOfTheOwnerThatMayHaveBeenBilledNarrowsTheRequestToTheOwnersTierAndLeavesNoSlot() {
        TierWalk walk = new TierWalk(openAiPlan(true, "official-owner"));
        TierRetryPolicy policy = policy(walk, true);

        Outcome first = policy.onFailure(failure(walk, 0, "official-owner", new TimeoutException("no answer"), false, 2));
        Outcome second = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, 2));

        assertThat(first).isEqualTo(Outcome.RETRY);
        assertThat(policy.narrowed()).isTrue();
        assertThat(tierIds(walk)).containsExactly("official");
        assertThat(second).isEqualTo(Outcome.STOP);
        assertThat(policy.reservedSlotUsed()).isFalse();
    }

    @Test
    void theOwnerFailingWithTheReservedSlotSwitchedOffEndsTheRequestWhenTheBudgetIsSpent() {
        TierWalk walk = new TierWalk(openAiPlan(false, "official-owner"));
        TierRetryPolicy policy = policy(walk, true);

        assertThat(policy.onFailure(failure(walk, 0, "official-owner", rejected(), true, 1))).isEqualTo(Outcome.STOP);
        assertThat(policy.reservedSlotUsed()).isFalse();
    }

    @Test
    void withTheReservedSlotSwitchedOffTheBudgetIsTheEnd() {
        TierWalk walk = new TierWalk(plan(false));
        TierRetryPolicy policy = policy(walk, true);
        policy.onFailure(failure(walk, 0, "official-1", rejected(), true, TWO));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-2", rejected(), true, TWO));

        assertThat(outcome).isEqualTo(Outcome.STOP);
        assertThat(policy.reservedSlotUsed()).isFalse();
        assertThat(tierIds(walk)).containsExactly("official", "oauth", "kiro");
    }

    @Test
    void thereIsNoSlotToUseFromTheLastTier() {
        TierWalk walk = new TierWalk(plan(true), 2);
        TierRetryPolicy policy = policy(walk, true);
        policy.onFailure(failure(walk, 2, "kiro-1", rejected(), true, TWO));

        Outcome outcome = policy.onFailure(failure(walk, 2, "kiro-2", rejected(), true, TWO));

        assertThat(outcome).isEqualTo(Outcome.STOP);
        assertThat(policy.reservedSlotUsed()).isFalse();
    }

    @Test
    void aBudgetOfOneStillLetsTheFirstFailureCrossOnTheSlot() {
        // The Codex route allows a single account: its 429 stays with that token, but a tier below may still be tried.
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, 1));

        assertThat(outcome).isEqualTo(Outcome.RETRY_ON_RESERVED_SLOT);
        assertThat(tierIds(walk)).containsExactly("oauth", "kiro");
    }

    @Test
    void theSlotIsLeftUnusedWhileTheBudgetHoldsAndTheNextTierIsReachedOnTheBudget() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.RETRY);
        assertThat(policy.reservedSlotUsed()).isFalse();
    }

    // ---- what is never retried ----

    @Test
    void aFailureThatIsNotRetryableEndsTheRequestWhateverTheBudget() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);

        Outcome outcome = policy.onFailure(new FailedAttempt(walk.plan().levels().get(0), "official-1", rejected(), true, false, THREE, false, true));

        assertThat(outcome).isEqualTo(Outcome.STOP);
    }

    @Test
    void aRequestThatCannotLeaveItsAccountIsNeverRetried() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, false);

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.STOP);
        assertThat(policy.reservedSlotUsed()).isFalse();
    }

    // ---- the time guard ----

    @Test
    void aRequestOlderThanFourMinutesDoesNotCrossAnyMore() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        clock.advance(Duration.ofSeconds(241));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));

        // Retries within the tier go on as they do untiered; the tier below is out of reach.
        assertThat(outcome).isEqualTo(Outcome.RETRY);
        assertThat(policy.narrowed()).isTrue();
        assertThat(tierIds(walk)).containsExactly("official");
    }

    @Test
    void anOldRequestWhoseBudgetIsSpentDoesNotUseTheReservedSlot() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        clock.advance(Duration.ofSeconds(300));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, 1));

        assertThat(outcome).isEqualTo(Outcome.STOP);
        assertThat(policy.reservedSlotUsed()).isFalse();
    }

    @Test
    void aRequestExactlyFourMinutesOldStillCrosses() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        clock.advance(TierRetryPolicy.CROSS_TIER_DEADLINE);

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, 1));

        assertThat(outcome).isEqualTo(Outcome.RETRY_ON_RESERVED_SLOT);
        assertThat(policy.narrowed()).isFalse();
    }

    @Test
    void theTimeIsReadAtEachFailureSoARequestThatTurnsOldInTheMiddleIsNarrowedThen() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));
        assertThat(policy.narrowed()).isFalse();

        clock.advance(Duration.ofSeconds(250));
        policy.onFailure(failure(walk, 0, "official-2", rejected(), true, THREE));

        assertThat(policy.narrowed()).isTrue();
        assertThat(tierIds(walk)).containsExactly("official");
    }

    // ---- the billing lease ----

    @Test
    void aRetryGoesOnWhileTheLeaseCanStillCoverAnotherDispatch() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        policy.leaseGranted(Duration.ofSeconds(960), Duration.ofSeconds(961));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.RETRY);
    }

    @Test
    void aRetryStopsWhenTheLeaseCannotCoverAnotherDispatch() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        policy.leaseGranted(Duration.ofSeconds(960), Duration.ofSeconds(959));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.STOP);
        assertThat(walk.excluded()).containsExactly("official-1");
    }

    @Test
    void theTimeASlowDispatchTookIsCountedAgainstTheLease() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        // Granted with plenty of room, but the dispatch ran for two minutes before it failed.
        policy.leaseGranted(Duration.ofSeconds(960), Duration.ofSeconds(1_000));
        clock.advance(Duration.ofMinutes(2));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.STOP);
    }

    @Test
    void theLeaseAlsoClosesTheReservedSlot() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        policy.leaseGranted(Duration.ofSeconds(960), Duration.ofSeconds(500));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, 1));

        assertThat(outcome).isEqualTo(Outcome.STOP);
        assertThat(policy.reservedSlotUsed()).isFalse();
    }

    @Test
    void aRequestWhoseLeaseIsNotKnownIsNotLimitedByIt() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.RETRY);
    }

    @Test
    void theLatestLeaseIsTheOneThatCounts() {
        TierWalk walk = new TierWalk(plan(true));
        TierRetryPolicy policy = policy(walk, true);
        policy.leaseGranted(Duration.ofSeconds(960), Duration.ofSeconds(100));
        policy.leaseGranted(Duration.ofSeconds(960), Duration.ofSeconds(3_000));

        Outcome outcome = policy.onFailure(failure(walk, 0, "official-1", rejected(), true, THREE));

        assertThat(outcome).isEqualTo(Outcome.RETRY);
    }

    // ---- fixtures ----

    private TierRetryPolicy policy(TierWalk walk, boolean movable) {
        return new TierRetryPolicy(walk, START, movable, clock);
    }

    private FailedAttempt failure(TierWalk walk, int levelIndex, String accountId, Throwable error, boolean definitive, int limit) {
        return new FailedAttempt(walk.plan().levels().get(levelIndex), accountId, error, definitive, true, limit, false,
            accountsLeftInTier);
    }

    private static UpstreamException rejected() {
        return new UpstreamException(429, "rate limited", true);
    }

    private static List<String> tierIds(TierWalk walk) {
        return walk.levels().stream().map(Level::tierId).toList();
    }

    /** Three tiers of the Anthropic route; the slot switch is the only thing the vendor configuration varies here. */
    private TierPlan plan(boolean reservedSlot) {
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("anthropic", true, false, false, true, 16, reservedSlot, 7L)),
            List.of(
                tier("official", 1, AccountAllocationCategory.OFFICIAL_API_KEY),
                tier("oauth", 2, AccountAllocationCategory.CLAUDE_OAUTH),
                tier("kiro", 3, AccountAllocationCategory.KIRO)),
            List.of(), List.of(), List.of()));
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "claude-sonnet-4.5");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        body.put("max_tokens", 4096);
        return planner.plan(new TierPlanRequest("request-1", "claude-sonnet-4.5", "claude-sonnet-4.5", "Anthropic",
            TierRoute.ANTHROPIC, false, false, false, true, false, true, body, null, false, null, null)).block();
    }

    /** Two tiers of the OpenAI route, official keys and Codex tokens, for a chain whose owner is {@code owner}. */
    private TierPlan openAiPlan(boolean reservedSlot, String owner) {
        when(registry.snapshot()).thenReturn(SourceTierSnapshot.of(7L,
            List.of(new VendorConfig("openai", true, false, false, true, 16, reservedSlot, 7L)),
            List.of(
                new Tier("openai", "official", 1, "official", BigDecimal.ONE, EnumSet.of(AccountAllocationCategory.OFFICIAL_API_KEY),
                    true, true, null),
                new Tier("openai", "codex", 2, "codex", BigDecimal.ONE, EnumSet.of(AccountAllocationCategory.CODEX), true, true, null)),
            List.of(), List.of(), List.of()));
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "gpt-5.5").put("input", "continue");
        body.put("max_output_tokens", 4096);
        return planner.plan(new TierPlanRequest("request-1", "gpt-5.5", "gpt-5.5", "OpenAI", TierRoute.OPENAI,
            false, false, false, false, false, true, body, owner, true, null, null)).block();
    }

    private static ProxyAccountEntity account(String accountId, String provider, String authMethod) {
        ProxyAccountEntity account = new ProxyAccountEntity();
        account.setAccountId(accountId);
        account.setProvider(provider);
        account.setAuthMethod(authMethod);
        account.setEnabled(true);
        return account;
    }

    private static Tier tier(String tierId, int level, AccountAllocationCategory category) {
        return new Tier("anthropic", tierId, level, tierId, BigDecimal.ONE, EnumSet.of(category), true, true, null);
    }

    private static PaymentConfig paymentConfig() {
        return new PaymentConfig(false, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 30, 3,
            List.of("alipay", "wxpay"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "", "", "",
            "popup", false, new BigDecimal("0.10"), 10, null, null);
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
