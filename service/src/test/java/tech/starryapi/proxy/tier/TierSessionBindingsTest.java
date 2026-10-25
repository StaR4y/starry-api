package tech.starryapi.proxy.tier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import tech.starryapi.config.ProxyProperties;
import tech.starryapi.proxy.tier.TierSessionBindings.Binding;

/**
 * Where the tier each session was pushed down to is kept (design 4.7): it only moves down, in the order of the tiers as
 * they are now, except for the one climb back, and it expires when the session has been idle for as long as the
 * account affinity does, whatever else happens to the other sessions or to the accounts.
 */
class TierSessionBindingsTest {

    private static final String KEY = "tier:anthropic|claude-sonnet-4.5|key-1:session-1";
    private static final Binding OFFICIAL = new Binding("official", 1);
    private static final Binding KIRO = new Binding("kiro", 2);
    private static final Binding LAST = new Binding("last", 3);

    private final AtomicLong nanos = new AtomicLong();
    private final ProxyProperties properties = new ProxyProperties();
    private final TierSessionBindings bindings = new TierSessionBindings(properties, nanos::get);

    // ---- moving down only ----

    @Test
    void aSessionWithoutABindingHasNone() {
        assertThat(bindings.find(KEY)).isEmpty();
    }

    @Test
    void aSessionIsBoundToTheTierItWasPushedDownTo() {
        bindings.downgrade(KEY, KIRO, Binding::level);

        assertThat(bindings.find(KEY)).contains(KIRO);
    }

    @Test
    void aBindingMovesDownAndNeverUp() {
        bindings.downgrade(KEY, KIRO, Binding::level);

        bindings.downgrade(KEY, OFFICIAL, Binding::level);
        assertThat(bindings.find(KEY)).contains(KIRO);

        bindings.downgrade(KEY, LAST, Binding::level);
        assertThat(bindings.find(KEY)).contains(LAST);
    }

    @Test
    void theSameTierIsRestampedWithItsLevelOfNow() {
        bindings.downgrade(KEY, KIRO, Binding::level);

        bindings.downgrade(KEY, new Binding("kiro", 1), Binding::level);

        assertThat(bindings.find(KEY)).contains(new Binding("kiro", 1));
    }

    @Test
    void whichTierIsLowerIsTheOrderTheCallerSaysAndNotTheLevelStoredWithTheBinding() {
        // Bound as the second tier. The tiers were reordered since: kiro is the first now and official the second.
        bindings.downgrade(KEY, new Binding("kiro", 2), Binding::level);
        ToIntFunction<Binding> now = binding -> "kiro".equals(binding.tierId()) ? 1 : 2;

        bindings.downgrade(KEY, new Binding("official", 2), now);

        assertThat(bindings.find(KEY)).contains(new Binding("official", 2));
        // And it is still only down: the first tier of the new order does not take the session back.
        bindings.downgrade(KEY, new Binding("kiro", 1), now);
        assertThat(bindings.find(KEY)).contains(new Binding("official", 2));
    }

    @Test
    void aClimbIsTheOneWayBackUp() {
        bindings.downgrade(KEY, KIRO, Binding::level);

        bindings.climb(KEY, OFFICIAL);

        assertThat(bindings.find(KEY)).contains(OFFICIAL);
    }

    @Test
    void servingTheSessionCountsAsUsingItsBinding() {
        bindings.downgrade(KEY, KIRO, Binding::level);

        nanos.addAndGet(Duration.ofMinutes(9).toNanos());
        bindings.serve(KEY);
        nanos.addAndGet(Duration.ofMinutes(9).toNanos());

        assertThat(bindings.find(KEY)).contains(KIRO);
    }

    @Test
    void servingASessionThatIsNotBoundBindsNothing() {
        bindings.serve(KEY);

        assertThat(bindings.find(KEY)).isEmpty();
    }

    // ---- idle expiry ----

    @Test
    void aSessionGoesBackToTheBestTierWhenItHasBeenIdleForTheAffinityTtl() {
        bindings.downgrade(KEY, KIRO, Binding::level);

        nanos.addAndGet(Duration.ofMinutes(9).toNanos());
        assertThat(bindings.find(KEY)).contains(KIRO);

        nanos.addAndGet(Duration.ofMinutes(10).plusSeconds(1).toNanos());
        assertThat(bindings.find(KEY)).isEmpty();
    }

    @Test
    void usingTheBindingKeepsItAlive() {
        bindings.downgrade(KEY, KIRO, Binding::level);

        for (int i = 0; i < 5; i++) {
            nanos.addAndGet(Duration.ofMinutes(9).toNanos());
            assertThat(bindings.find(KEY)).contains(KIRO);
        }
    }

    @Test
    void theIdleTimeIsTheConfiguredAffinityTtl() {
        properties.setSessionAffinityTtl(Duration.ofMinutes(2));
        TierSessionBindings shortLived = new TierSessionBindings(properties, nanos::get);
        shortLived.downgrade(KEY, KIRO, Binding::level);

        nanos.addAndGet(Duration.ofMinutes(3).toNanos());

        assertThat(shortLived.find(KEY)).isEmpty();
    }

    @Test
    void aTtlThatIsNotPositiveFallsBackToTenMinutesLikeTheAffinity() {
        properties.setSessionAffinityTtl(Duration.ZERO);
        TierSessionBindings fallback = new TierSessionBindings(properties, nanos::get);
        fallback.downgrade(KEY, KIRO, Binding::level);

        nanos.addAndGet(Duration.ofMinutes(9).toNanos());

        assertThat(fallback.find(KEY)).contains(KIRO);
    }

    // ---- the next turn of a response chain ----

    @Test
    void aBindingIsAlsoRegisteredUnderTheKeyTheNextTurnLooksUnder() {
        String next = "tier:anthropic|claude-sonnet-4.5|key-1:resp:resp_2";
        bindings.downgrade(KEY, KIRO, Binding::level);

        bindings.alias(KEY, next);

        assertThat(bindings.find(next)).contains(KIRO);
        assertThat(bindings.find(KEY)).contains(KIRO);
    }

    @Test
    void aSessionWithoutABindingGivesNothingToTheNextTurn() {
        bindings.alias(KEY, "tier:anthropic|claude-sonnet-4.5|key-1:resp:resp_2");

        assertThat(bindings.find("tier:anthropic|claude-sonnet-4.5|key-1:resp:resp_2")).isEmpty();
    }

    // ---- what the other sessions do ----

    @Test
    void whatHappensToTheOtherSessionsDoesNotKeepAnIdleSessionFromExpiring() {
        bindings.downgrade(KEY, KIRO, Binding::level);

        for (int i = 0; i < 3; i++) {
            nanos.addAndGet(Duration.ofMinutes(4).toNanos());
            bindings.downgrade("other", OFFICIAL, Binding::level);
            bindings.serve("other");
            bindings.alias("other", "another");
            bindings.dispatchStarted("other", "anthropic:official");
        }

        assertThat(bindings.find(KEY)).isEmpty();
    }

    // ---- requests of the session in flight ----

    @Test
    void aSessionHasRequestsInFlightInATierUntilTheyAllEnd() {
        assertThat(bindings.hasInFlight("key-1:session-1", "anthropic:official")).isFalse();

        bindings.dispatchStarted("key-1:session-1", "anthropic:official");
        bindings.dispatchStarted("key-1:session-1", "anthropic:official");
        assertThat(bindings.hasInFlight("key-1:session-1", "anthropic:official")).isTrue();
        assertThat(bindings.hasInFlight("key-1:session-1", "anthropic:kiro")).isFalse();
        assertThat(bindings.hasInFlight("key-1:session-2", "anthropic:official")).isFalse();

        bindings.dispatchEnded("key-1:session-1", "anthropic:official");
        assertThat(bindings.hasInFlight("key-1:session-1", "anthropic:official")).isTrue();

        bindings.dispatchEnded("key-1:session-1", "anthropic:official");
        assertThat(bindings.hasInFlight("key-1:session-1", "anthropic:official")).isFalse();
    }

    @Test
    void endingMoreThanWasStartedCountsNothingBelowZero() {
        bindings.dispatchEnded("key-1:session-1", "anthropic:official");
        bindings.dispatchStarted("key-1:session-1", "anthropic:official");

        assertThat(bindings.hasInFlight("key-1:session-1", "anthropic:official")).isTrue();
    }

    // ---- switched off ----

    @Test
    void withSessionAffinityOffNothingIsReadOrWritten() {
        properties.setSessionAffinityEnabled(false);

        bindings.downgrade(KEY, KIRO, Binding::level);
        bindings.climb(KEY, OFFICIAL);
        bindings.alias(KEY, "next");

        assertThat(bindings.enabled()).isFalse();
        assertThat(bindings.find(KEY)).isEmpty();
        assertThat(bindings.size()).isZero();
    }

    @Test
    void theSwitchIsReadAtEveryUseLikeTheAffinityDoes() {
        bindings.downgrade(KEY, KIRO, Binding::level);
        properties.setSessionAffinityEnabled(false);
        assertThat(bindings.find(KEY)).isEmpty();

        properties.setSessionAffinityEnabled(true);
        assertThat(bindings.find(KEY)).contains(KIRO);
    }

    @Test
    void theDisabledBindingsKeepNothing() {
        TierSessionBindings disabled = TierSessionBindings.disabled();

        disabled.downgrade(KEY, KIRO, Binding::level);

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.find(KEY)).isEmpty();
        assertThat(TierSessionBindings.disabled()).isSameAs(disabled);
    }
}
