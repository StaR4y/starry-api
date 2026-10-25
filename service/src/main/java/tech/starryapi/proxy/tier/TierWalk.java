package tech.starryapi.proxy.tier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tech.starryapi.proxy.tier.TierPlan.Level;

/**
 * What a tiered request remembers from one account selection to the next (design 4.2 to 4.4): where in the plan its
 * walk starts (and, after a failed dispatch, where the retry resumes), which accounts it has already tried or been
 * refused, how often each tier has refused it, whether the owner of its response chain is still to be tried and whether
 * it has had its one retest of the accounts that are out of service. An untiered request starts every dispatch from
 * nothing; a tiered one keeps all of this for the whole request, so that an account which refused it a moment ago is not
 * offered again and a tier's refusals are not counted afresh after every failed dispatch.
 *
 * <p>It also keeps what the request saw on the way, which decides whether the tier that finally served it is one its
 * session was pushed down to (design 4.7): which tiers the walk went through and found with nothing to offer, how often
 * they refused it and why. Where the session is bound is not the walk's concern; it only answers
 * {@link #realDowngradeTo}, {@link #realClimb} and {@link #climbLevels}.
 *
 * <p>Selections of one request follow each other, but not necessarily on one thread, hence the locking.
 */
public final class TierWalk {

    private final TierPlan plan;
    private final int originStart;
    private int startIndex;
    private Level narrowedTo;
    private final Set<String> excluded = new LinkedHashSet<>();
    private final Set<String> dispatched = new LinkedHashSet<>();
    private final Set<String> passed = new LinkedHashSet<>();
    private final Map<String, Integer> refusalsByTier = new HashMap<>();
    private final Map<String, Integer> sessionRefusalsByTier = new HashMap<>();
    private int refusals;
    private boolean ownerPending;
    private boolean crossedOnBudget;
    private boolean retestTaken;

    /**
     * @param startIndex the index in {@link TierPlan#levels()} of the first tier to offer; the tiers above it are
     *     left out of the walk
     */
    public TierWalk(TierPlan plan, int startIndex) {
        this.plan = Objects.requireNonNull(plan, "plan");
        if (startIndex < 0 || startIndex >= plan.levels().size()) {
            throw new IllegalArgumentException("The walk starts at tier " + startIndex + " of " + plan.levels().size());
        }
        this.originStart = startIndex;
        this.startIndex = startIndex;
        this.ownerPending = plan.owner().isPresent();
    }

    /** The walk of a request that starts from the tier of the plan: its best one, or the one its session is bound to. */
    public TierWalk(TierPlan plan) {
        this(plan, plan.startIndex());
    }

    public TierPlan plan() {
        return plan;
    }

    /** The tiers the request may still be served from, best first: the one it was narrowed to, else the rest of the plan. */
    public synchronized List<Level> levels() {
        return narrowedTo != null ? List.of(narrowedTo) : plan.levels().subList(startIndex, plan.levels().size());
    }

    /**
     * The tiers to offer now: {@link #levels()} without a tier that has refused the request as often as the vendor's
     * cap allows. A tier is only given up that way when there is a tier below it to go to; the last tier, or the only
     * one a narrowed or pinned request has, is limited by the request-wide limit alone.
     */
    public synchronized List<Level> openLevels() {
        List<Level> levels = levels();
        int cap = plan.vendorConfig().refusalCap();
        List<Level> open = new ArrayList<>(levels.size());
        for (int i = 0; i < levels.size(); i++) {
            Level level = levels.get(i);
            boolean hasLowerTier = i < levels.size() - 1;
            if (!hasLowerTier || refusalsByTier.getOrDefault(level.tierRef(), 0) < cap) {
                open.add(level);
            }
        }
        return open;
    }

    /** The accounts the request must not be offered again: those it has tried and those that refused it. */
    public synchronized List<String> excluded() {
        return List.copyOf(excluded);
    }

    /** An account the request was dispatched to. */
    public synchronized void attempted(String accountId) {
        if (accountId != null && !accountId.isBlank()) {
            excluded.add(accountId);
            dispatched.add(accountId);
        }
    }

    /** How many different accounts the request has been dispatched to, which is what the retry budget counts. */
    public synchronized int dispatchedAccounts() {
        return dispatched.size();
    }

    /** An account of {@code level} that was selected and then refused by admission (busy, rate limited, cooling down). */
    void refused(Level level, String accountId) {
        refused(level, accountId, false);
    }

    /**
     * {@link #refused(Level, String)}, saying whether the refusal is the session's own doing: the account is at its
     * limit of concurrent requests, tokens or sessions, and the session has requests running on it. Such a refusal
     * tells nothing about the tier being busy.
     */
    public synchronized void refused(Level level, String accountId, boolean ownSession) {
        if (accountId != null && !accountId.isBlank()) {
            excluded.add(accountId);
        }
        refusalsByTier.merge(level.tierRef(), 1, Integer::sum);
        if (ownSession) {
            sessionRefusalsByTier.merge(level.tierRef(), 1, Integer::sum);
        }
        refusals++;
    }

    /** How many accounts have refused the request in all, whatever their tier. */
    public synchronized int refusals() {
        return refusals;
    }

    /** A tier the walk went through and found with no account to offer: all of its accounts are tried, refused or unavailable. */
    public synchronized void passed(Level level) {
        passed.add(level.tierRef());
    }

    /**
     * An account of the tier was selected after all: the walk had found the tier empty, and the account cache was
     * reloaded before the next round. A tier that served, or refused or failed a request, is not one it went through.
     */
    public synchronized void selected(Level level) {
        passed.remove(level.tierRef());
    }

    /** Whether the walk found the tier with nothing to offer, which includes a tier it left out for having refused too often. */
    public synchronized boolean wasPassed(Level level) {
        return passed.contains(level.tierRef()) || (hasTierBelow(level) && capReached(level));
    }

    private boolean capReached(Level level) {
        return refusalsByTier.getOrDefault(level.tierRef(), 0) >= plan.vendorConfig().refusalCap();
    }

    /**
     * Notes that the request left a tier because the retry budget was spent, not because the tier had nothing left.
     * The tier below served it because of the budget, so the session is not one that was pushed down.
     */
    public synchronized void crossedOnBudget() {
        this.crossedOnBudget = true;
    }

    /**
     * Whether being served by {@code served} means the session was pushed down to it (design 4.7, invariant I8): the
     * request went through every tier from its start to the one above {@code served} and found each with nothing to
     * offer, none of that came from the budget, and the refusals on the way were not all the session's own
     * concurrency. A request that started below the best tier, or was served by the owner of its response chain
     * without the walk looking at the tiers above it, passed nothing and pushed nobody down.
     *
     * <p>A tier the plan left out because none of its accounts can serve ({@link TierPlan#unavailableTiers}) was
     * found empty before the walk began, and counts as one it went through. A tier the plan kept though it never had an
     * account ({@link TierPlan#memberless}) is neither: there was nothing in it to push the session out of, so it is
     * skipped, and a request that went through no other tier above the one that served it pushed nobody down.
     */
    public synchronized boolean realDowngradeTo(Level served) {
        if (crossedOnBudget) {
            return false;
        }
        int servedIndex = indexOf(served);
        boolean unavailableAbove = plan.unavailableAbove(served);
        if (servedIndex <= originStart && !unavailableAbove) {
            return false;
        }
        boolean wentThrough = unavailableAbove;
        boolean refused = false;
        boolean refusedByOthers = false;
        for (int i = originStart; i < servedIndex; i++) {
            Level above = plan.levels().get(i);
            if (plan.memberless(above)) {
                continue;
            }
            if (!wasPassed(above)) {
                return false;
            }
            wentThrough = true;
            int total = refusalsByTier.getOrDefault(above.tierRef(), 0);
            refused |= total > 0;
            refusedByOthers |= total > sessionRefusalsByTier.getOrDefault(above.tierRef(), 0);
        }
        return wentThrough && (!refused || refusedByOthers);
    }

    /**
     * Whether a request that climbed back above its start did so for a reason other than its own session: from its
     * start down, the tiers refused it only because the same session has requests running on them (a client's
     * parallel sub-agents) are not a reason to move the session. The request is served by the higher tier all the same.
     */
    public synchronized boolean realClimb() {
        boolean refused = false;
        boolean refusedByOthers = false;
        for (int i = originStart; i < plan.levels().size(); i++) {
            String tierRef = plan.levels().get(i).tierRef();
            int total = refusalsByTier.getOrDefault(tierRef, 0);
            refused |= total > 0;
            refusedByOthers |= total > sessionRefusalsByTier.getOrDefault(tierRef, 0);
        }
        return !refused || refusedByOthers;
    }

    /** Whether the request may climb back to a tier above its start as a last resort: it started below the best one and is not restricted. */
    public synchronized boolean climbable() {
        return originStart > 0 && narrowedTo == null;
    }

    /**
     * The tiers above the start the request may climb to when nothing from its start down has an account: those the
     * reservation covers (a tier it does not cover could cost more than was reserved), minus the ones that have
     * refused it too often.
     */
    public synchronized List<Level> climbLevels() {
        if (!climbable()) {
            return List.of();
        }
        int cap = plan.vendorConfig().refusalCap();
        List<Level> above = new ArrayList<>();
        for (Level level : plan.levels().subList(0, originStart)) {
            if (plan.covers(level) && refusalsByTier.getOrDefault(level.tierRef(), 0) < cap) {
                above.add(level);
            }
        }
        return above;
    }

    /** Restricts the rest of the request to {@code level}: nothing above or below it is offered any more. */
    public synchronized void narrowTo(Level level) {
        this.narrowedTo = Objects.requireNonNull(level, "level");
    }

    /** Whether the request has been restricted to one tier. It is then no longer climbed back to a better tier either. */
    public synchronized boolean narrowed() {
        return narrowedTo != null;
    }

    /** Whether the tiers the walk may still offer include one besides {@code level}. */
    public synchronized boolean hasTierBesides(Level level) {
        return levels().stream().anyMatch(other -> !other.tierRef().equals(level.tierRef()));
    }

    /** Whether the plan has a tier below {@code level}, whatever the walk still offers. */
    public boolean hasTierBelow(Level level) {
        return indexOf(level) < plan.levels().size() - 1;
    }

    /**
     * Keeps the rest of the request at {@code level} and below: a request that failed in a tier is retried there or in a
     * tier under it, never in a tier above, even if that has recovered since.
     */
    public synchronized void stayAtOrBelow(Level level) {
        startIndex = Math.max(startIndex, indexOf(level));
    }

    /** Keeps the rest of the request below {@code level}: the next retry goes to a tier under it. */
    public synchronized void moveBelow(Level level) {
        int below = indexOf(level) + 1;
        if (below >= plan.levels().size()) {
            throw new IllegalArgumentException("Tier " + level.tierRef() + " has no tier below it");
        }
        startIndex = Math.max(startIndex, below);
    }

    private int indexOf(Level level) {
        List<Level> levels = plan.levels();
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).tierRef().equals(level.tierRef())) {
                return i;
            }
        }
        throw new IllegalArgumentException("Tier " + level.tierRef() + " is not part of the plan");
    }

    /**
     * Whether the request may have the accounts that are out of service tested again (design 4.10): it may, once, so the
     * first caller is told yes and every later one no, whichever selection the round that found nothing belonged to.
     */
    public synchronized boolean takeRetest() {
        if (retestTaken) {
            return false;
        }
        retestTaken = true;
        return true;
    }

    /** The owner of the response chain the request continues, once: only the first selection prefers it. */
    public synchronized Optional<TierPlan.Owner> takeOwner() {
        if (!ownerPending) {
            return Optional.empty();
        }
        ownerPending = false;
        return plan.owner();
    }
}
