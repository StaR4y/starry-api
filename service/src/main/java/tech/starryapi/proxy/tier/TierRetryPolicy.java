package tech.starryapi.proxy.tier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.starryapi.platform.upstream.UpstreamException;
import tech.starryapi.proxy.tier.TierPlan.Level;

/**
 * What a tiered request does after an account it was dispatched to failed (design 4.5). The budget of different
 * accounts and the rules of what may be retried at all stay as they are for an untiered request; this adds where the
 * retry may go: staying in the tier that failed, or crossing to the next one.
 *
 * <ul>
 *   <li>A request crosses into another tier only if every failure so far was definitive (known not to have been billed)
 *       and had not started generating. The first failure that was not narrows the request to the tier it happened in
 *       for good: its retries stay there and, once that tier is used up, the original error is returned. Otherwise a
 *       Kiro stream cut off after its first content, or a request that timed out after it was sent, would be
 *       generated a second time by the next tier, and the user would pay for both.
 *   <li>A retry stays in the tier that failed while it has an account to offer, and goes to the tier below it when it
 *       has none. It never goes back up. The owner of a response chain is no exception: a chain that can be replayed
 *       keeps its owner only when the owner sits in the tier the walk starts from, and it is tried there ahead of the
 *       other accounts of that tier, so its failure is a failure of that tier like any other (a chain that cannot be
 *       replayed is never retried).
 *   <li>The retry budget is the one of an untiered request. A tier with accounts left but a spent budget is still left
 *       for the tier below by one reserved slot, which only a crossing can use and only once per request (switched off
 *       by the vendor's {@code cross_tier_reserved_slot}). With two accounts in the first tier and a budget of two, a
 *       request whose two first-tier dispatches both failed definitively can still reach the second tier; that is one
 *       dispatch more than an untiered request makes, and none of the dispatches before it generated anything. When
 *       the tier had no account left to try the crossing is not the budget's doing: the tier was used up, and the
 *       request is counted as having gone through it.
 *   <li>A request more than {@link #CROSS_TIER_DEADLINE} old does not cross any more: a native attempt in the next tier
 *       can run for minutes, and a client that gives up meanwhile ends the request in a state that is billed for
 *       review. It is narrowed like a request that failed in a way that may have generated.
 *   <li>A relay that refuses the request itself (a prompt longer than its window, a conversion it will not take) is
 *       not asked again, in its tier or by another relay, since another relay would be given the same request. The request
 *       may cross once, and only to the native accounts of a tier below ({@link #onRelayRefusal}); it counts against the
 *       budget like any dispatch, or takes the reserved slot when the budget is spent.
 *   <li>A request does not go to another account when the lease on its reservation could not be renewed to cover one more
 *       dispatch: a reservation cannot be leased past an hour after it was made, and a dispatch that is still running
 *       when its lease cannot be renewed any more is turned into a billing review by the sweeper. The error of the
 *       failed attempt is returned instead.
 * </ul>
 *
 * <p>One instance serves one request. It positions the request's {@link TierWalk}, so that the next selection starts
 * where this policy decided.
 */
public final class TierRetryPolicy {

    /** How old a request may be and still cross into another tier. */
    public static final Duration CROSS_TIER_DEADLINE = Duration.ofSeconds(240);

    private static final Logger log = LoggerFactory.getLogger(TierRetryPolicy.class);

    /** What to do next. */
    public enum Outcome {
        /** Return the error of the failed attempt. */
        STOP,
        /** Select again; the walk is positioned at the tier that failed or below it. */
        RETRY,
        /** Select again in a tier below the one that failed, on the dispatch that only a crossing may use. */
        RETRY_ON_RESERVED_SLOT
    }

    /**
     * An attempt that failed.
     *
     * @param level the tier the account was selected from
     * @param error the failure as the upstream client raised it
     * @param definitive whether the failure is known not to have been billed ({@code tierDefinitive})
     * @param retryable whether the failure may be retried on another account at all, as for an untiered request
     * @param accountLimit how many different accounts a request may be dispatched to for this kind of failure
     * @param generationStarted whether the upstream client reported output of the dispatch, which its error may not
     *     say itself (a retry of its own that failed to connect after a stream was cut off)
     * @param accountsLeftInTier whether the tier still has an account the request could be sent to: one that selection
     *     can pick right now (cached, available, allowed by the allocation lifecycle) and that the request has neither
     *     tried, including this one, nor been refused by. Only read when the budget is spent and the request is about to
     *     leave the tier on the reserved slot.
     */
    public record FailedAttempt(
        Level level,
        String accountId,
        Throwable error,
        boolean definitive,
        boolean retryable,
        int accountLimit,
        boolean generationStarted,
        boolean accountsLeftInTier
    ) {
        public FailedAttempt {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(error, "error");
        }
    }

    private final TierWalk walk;
    private final Instant start;
    private final Clock clock;
    private final boolean requestMovable;
    private boolean reservedSlotUsed;
    private boolean nativeCrossingUsed;
    private Duration leaseWindow;
    private Instant leaseCapAt;

    /**
     * @param start when the request began; the deadline for crossing counts from here
     * @param requestMovable whether the request may be sent to another account at all (a response chain the gateway
     *     cannot replay stays with its owner)
     */
    public TierRetryPolicy(TierWalk walk, Instant start, boolean requestMovable) {
        this(walk, start, requestMovable, Clock.systemUTC());
    }

    TierRetryPolicy(TierWalk walk, Instant start, boolean requestMovable, Clock clock) {
        this.walk = Objects.requireNonNull(walk, "walk");
        this.start = Objects.requireNonNull(start, "start");
        this.requestMovable = requestMovable;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether the failure happened before the upstream produced anything: no content, tool call or usage was parsed. */
    public static boolean notStarted(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof UpstreamException upstream && upstream.isGenerationStarted()) {
                return false;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return true;
    }

    /**
     * Records the failure, positions the walk for the next selection and says whether there is one. Not idempotent:
     * call it once per failed attempt.
     */
    public synchronized Outcome onFailure(FailedAttempt failed) {
        Level level = failed.level();
        walk.attempted(failed.accountId());
        if (!(failed.definitive() && !failed.generationStarted() && notStarted(failed.error()))) {
            narrow(level, "the failure may have started a generation or been billed");
        } else if (pastDeadline()) {
            narrow(level, "the request is older than " + CROSS_TIER_DEADLINE.toSeconds() + " seconds");
        }
        if (!failed.retryable() || !requestMovable) {
            return Outcome.STOP;
        }
        if (!leaseHasRoomForAnotherDispatch()) {
            log.info("Tiered request is not dispatched again from tier {}: its billing lease cannot cover another dispatch", level.tierRef());
            return Outcome.STOP;
        }
        if (walk.dispatchedAccounts() < failed.accountLimit()) {
            walk.stayAtOrBelow(level);
            return Outcome.RETRY;
        }
        if (reservedSlotAvailable(failed)) {
            reservedSlotUsed = true;
            if (failed.accountsLeftInTier()) {
                // The tier below is reached because the budget ran out, not because this tier had nothing left to offer.
                walk.crossedOnBudget();
            } else {
                // The budget ended on the last account of the tier, which the walk will not look at again.
                walk.passed(level);
            }
            walk.moveBelow(level);
            log.info("Tiered request leaves tier {} on its reserved slot after {} dispatches", level.tierRef(), walk.dispatchedAccounts());
            return Outcome.RETRY_ON_RESERVED_SLOT;
        }
        return Outcome.STOP;
    }

    /**
     * Records the failure of a relay that refused the request itself ({@code crossTierOnly} of the failure
     * classification: it said the prompt is longer than its window, or would not take the conversion) and says whether
     * the request goes on, once, to the native accounts of a tier below (design 7.8). Not idempotent either.
     *
     * <p>The refusal is a definitive failure that cost nothing, so what stops a crossing is the request's own state: it
     * has been narrowed, is a chain that must stay with its owner, is older than {@link #CROSS_TIER_DEADLINE}, has crossed
     * this way already, has no tier below, or the lease on its reservation cannot cover another dispatch. A crossing is a
     * dispatch like any other: it takes the budget, or the reserved slot when the budget is spent. The walk is moved below
     * the tier and loses the relays, so that no relay is asked the same again and the session is not pushed down by it.
     */
    public synchronized Outcome onRelayRefusal(FailedAttempt failed) {
        Level level = failed.level();
        walk.attempted(failed.accountId());
        if (!(failed.definitive() && !failed.generationStarted() && notStarted(failed.error()))) {
            narrow(level, "the failure may have started a generation or been billed");
            return Outcome.STOP;
        }
        if (pastDeadline()) {
            narrow(level, "the request is older than " + CROSS_TIER_DEADLINE.toSeconds() + " seconds");
            return Outcome.STOP;
        }
        if (!requestMovable || walk.narrowed() || nativeCrossingUsed || !walk.hasTierBelow(level)) {
            return Outcome.STOP;
        }
        if (!leaseHasRoomForAnotherDispatch()) {
            log.info("Tiered request is not dispatched again from tier {}: its billing lease cannot cover another dispatch", level.tierRef());
            return Outcome.STOP;
        }
        boolean onReservedSlot = walk.dispatchedAccounts() >= failed.accountLimit();
        if (onReservedSlot && !reservedSlotAvailable(failed)) {
            return Outcome.STOP;
        }
        reservedSlotUsed |= onReservedSlot;
        nativeCrossingUsed = true;
        walk.crossedToNative(level);
        log.info("Tiered request leaves tier {} for the native accounts below it: a relay refused the request", level.tierRef());
        return onReservedSlot ? Outcome.RETRY_ON_RESERVED_SLOT : Outcome.RETRY;
    }

    /**
     * Records the lease the latest dispatch holds on the reservation (design 4.6), from the time the dispatch was marked.
     *
     * @param window how long one dispatch may run
     * @param untilCap how long remains until the reservation can no longer be leased
     */
    public synchronized void leaseGranted(Duration window, Duration untilCap) {
        this.leaseWindow = Objects.requireNonNull(window, "window");
        this.leaseCapAt = clock.instant().plus(Objects.requireNonNull(untilCap, "untilCap"));
    }

    /** Whether the request has been restricted to one tier; it then no longer crosses, climbs or uses the reserved slot. */
    boolean narrowed() {
        return walk.narrowed();
    }

    /** Whether the request has gone to a lower tier on the slot that only a crossing may use. */
    synchronized boolean reservedSlotUsed() {
        return reservedSlotUsed;
    }

    private boolean reservedSlotAvailable(FailedAttempt failed) {
        return !reservedSlotUsed
            && !walk.narrowed()
            && walk.plan().vendorConfig().crossTierReservedSlot()
            // There has to be somewhere else to go: a tier below the one that failed.
            && walk.hasTierBelow(failed.level());
    }

    private boolean leaseHasRoomForAnotherDispatch() {
        return leaseCapAt == null || !clock.instant().plus(leaseWindow).isAfter(leaseCapAt);
    }

    private boolean pastDeadline() {
        return Duration.between(start, clock.instant()).compareTo(CROSS_TIER_DEADLINE) > 0;
    }

    private void narrow(Level level, String reason) {
        if (!walk.narrowed()) {
            log.debug("Tiered request is narrowed to tier {}: {}", level.tierRef(), reason);
            walk.narrowTo(level);
        }
    }
}
