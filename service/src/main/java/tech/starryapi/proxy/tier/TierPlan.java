package tech.starryapi.proxy.tier;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * What the planner decides about one request before anything is reserved, and what the request then goes by to the end
 * (design 3, invariant I1): which tiers it may be served from, which kinds of account each tier can hold on this route,
 * the price and the reservation of each tier, and the headers that name the tier. Everything in it is fixed; a
 * configuration change in the middle of a request changes nothing here.
 *
 * <p>{@link #UNTIERED} is the plan of a vendor that has no tiers in use: the request runs the code it ran before tiers
 * existed, and nothing else in this class applies to it.
 */
public final class TierPlan {

    public static final TierPlan UNTIERED = new TierPlan(false);

    /**
     * The plan of a request that named a tier, for a vendor that has a tier configuration but serves from none of it
     * (switched off), or whose tiers were deleted after the selector was taken off the model: the selector is not used,
     * and the response says so. It is {@link #UNTIERED} in every other respect.
     */
    public static final TierPlan UNTIERED_IGNORING_SELECTOR = new TierPlan(true);

    /** How the tier was chosen. */
    public enum Mode {
        /** The tiers in their order: the best one that can serve, the next when it cannot. */
        AUTO("auto"),
        /**
         * The client named the tier (design 6.2); the request is served by it, or by the tiers below it when it named
         * "or below", and by nothing else. When none of them has an account left it is answered differently from an
         * automatic request, with SOURCE_TIER_UNAVAILABLE.
         */
        PINNED("pinned");

        private final String headerValue;

        Mode(String headerValue) {
            this.headerValue = headerValue;
        }

        public String headerValue() {
            return headerValue;
        }
    }

    /**
     * One tier of the plan. {@code cells} are the kinds of account the route can send the request to, in the order the
     * untiered route tries them; which of those accounts belong to this tier is {@link TierPlan#holds}. The cell of the
     * relays comes last, and only in a tier that has relays for this request.
     *
     * @param tierRef the tier as it is recorded with the reservation and the usage: {@code vendor:tierId}
     * @param inputBound the most input tokens the cells can be sent, which the reservation of this tier assumes
     * @param quote the standard price of the model times the tier's factor; null when the request is not billed
     * @param estimate what this tier would reserve for the request; null when the request is not billed
     * @param responseHeaders the headers of a response served by this tier, already encoded
     * @param relays the relays of this tier that may serve this request, each with the dispatch it is sent (design 3,
     *     step 5b); empty for every request that no relay takes part in, which is every request of a vendor without
     *     relays
     */
    public record Level(
        Tier tier,
        String tierRef,
        List<PoolCell> cells,
        long inputBound,
        BillingQuote quote,
        BigDecimal estimate,
        Map<String, String> responseHeaders,
        List<Relay> relays
    ) {
        public Level {
            cells = List.copyOf(cells);
            relays = List.copyOf(relays);
        }

        /** A tier that no relay serves. */
        public Level(
            Tier tier,
            String tierRef,
            List<PoolCell> cells,
            long inputBound,
            BillingQuote quote,
            BigDecimal estimate,
            Map<String, String> responseHeaders
        ) {
            this(tier, tierRef, cells, inputBound, quote, estimate, responseHeaders, List.of());
        }

        /** The place in the order of tiers, 1 being the best. */
        public int level() {
            return tier.level();
        }

        public String tierId() {
            return tier.tierId();
        }

        public String name() {
            return tier.name();
        }

        public BigDecimal factor() {
            return tier.priceFactor();
        }

        /** The relay of this tier that is the account, if it may serve the request. */
        public Optional<Relay> relay(String accountId) {
            return accountId == null
                ? Optional.empty()
                : relays.stream().filter(relay -> relay.accountId().equals(accountId)).findFirst();
        }
    }

    /**
     * A relay that may serve the request from its tier: it passed every condition of design 3, step 5b, and the
     * dispatch is what it is sent when the account is selected. Nothing of the request is looked at again then.
     *
     * @param dispatch how the request is sent to the relay: the mapping's wire name, the body built for the member's
     *     settings and shared with the other members of the same settings, and the soft features it leaves out
     * @param mapping the row of the relay that serves the model, with the window and the image cap the bounds use
     * @param estimatedInput what the body weighs, as it was measured against the window of the mapping
     * @param outputBeyondFallback how many tokens of output the body asks for beyond the {@link
     *     RequestBounds#FALLBACK_OUTPUT_TOKENS} the prompt of every relay is judged beside: the maximum output of the mapping
     *     where the gateway chose the limit and the window holds it (design 7.10); 0 for any other body
     */
    public record Relay(TierDispatch dispatch, MemberModel mapping, long estimatedInput, long outputBeyondFallback) {

        public String accountId() {
            return dispatch.member().accountId();
        }

        /**
         * The most input tokens this relay can be sent: what the reservation of its tier assumes for it, and what the usage
         * it reports is bounded by when it serves (design 5.2, 5.8).
         */
        public long inputBound() {
            return RequestBounds.xpInputTokens(dispatch.wireBody(), mapping.imageTokenCap());
        }

        /**
         * What a relay that refuses the length of this body is taught about its window (design 7.8), in the terms the plans
         * judge a prompt in: beside the fallback output. A body that asked for more output than that was refused for the prompt
         * and the output together, so what it shows is the size the prompt of a fallback body would have to be to weigh as
         * much, which is the estimate and the output beyond the fallback. Teaching the estimate alone would shut the relay out
         * of every prompt that its window holds beside the fallback output and not beside the larger one.
         */
        public long taughtByRefusal() {
            return estimatedInput + outputBeyondFallback;
        }
    }

    /** The account that owns the response chain a request continues, and the tier it sits in. */
    public record Owner(String accountId, Level level) {
    }

    /**
     * The session a request belongs to (design 4.7).
     *
     * @param key what the session is called: the client's own name for it, the response it continues or a fingerprint
     *     of its beginning
     * @param bindingKey the key the session's tier is stored under: the session in this vendor, for this model
     * @param keepsTier whether the request reads the tier its session is bound to and may write it, as an automatic
     *     request does. A request that named its tier is only part of the session: it counts as one of its dispatches
     *     while it runs, so that a refusal it causes is not taken for other load, it makes the next turn of a response
     *     chain the same session and, once served, notes that the session is in use (so that the binding does
     *     not expire in the middle of its work), but it never reads or changes the tier the session is bound to.
     */
    public record Session(String key, String bindingKey, boolean keepsTier) {

        /** The session of an automatic request. */
        public Session(String key, String bindingKey) {
            this(key, bindingKey, true);
        }
    }

    private final boolean tiered;
    private final String vendor;
    private final String modelKey;
    private final Mode mode;
    private final long configVersion;
    private final VendorConfig vendorConfig;
    private final List<Level> levels;
    private final List<Level> holdLevels;
    private final List<Tier> unavailableTiers;
    private final List<Tier> requestBoundTiers;
    private final Set<String> memberlessTiers;
    private final int startIndex;
    private final Session session;
    private final Owner owner;
    private final long outputBound;
    private final BillingQuote standardQuote;
    private final BigDecimal reservation;
    private final AccountPoolRequestContext poolContext;
    private final SourceTierSnapshot snapshot;
    private final boolean selectorIgnored;
    private final Set<String> pinnedScope;
    private final String lowBalanceHint;
    private final boolean unified;
    private final List<XpSkip> xpSkips;
    private final boolean xpOpen;
    private final String relaySession;
    private final boolean nativesAfterLengthRefusal;
    private final boolean nativesAfterOtherRefusal;

    private TierPlan(boolean selectorIgnored) {
        this.selectorIgnored = selectorIgnored;
        this.tiered = false;
        this.vendor = null;
        this.modelKey = null;
        this.mode = null;
        this.configVersion = 0;
        this.vendorConfig = null;
        this.levels = List.of();
        this.holdLevels = List.of();
        this.unavailableTiers = List.of();
        this.requestBoundTiers = List.of();
        this.memberlessTiers = Set.of();
        this.startIndex = 0;
        this.session = null;
        this.owner = null;
        this.outputBound = 0;
        this.standardQuote = null;
        this.reservation = null;
        this.poolContext = null;
        this.snapshot = null;
        this.pinnedScope = Set.of();
        this.lowBalanceHint = null;
        this.unified = false;
        this.xpSkips = List.of();
        this.xpOpen = false;
        this.relaySession = null;
        this.nativesAfterLengthRefusal = false;
        this.nativesAfterOtherRefusal = false;
    }

    /**
     * @param unavailableTiers the tiers left out of {@code levels} for having no account that can serve right now,
     *     from the tier the request starts from down (see {@link #unavailableTiers()})
     * @param memberlessTiers the ids of the tiers in {@code levels} that had no account of the route's cells in the
     *     account cache when the plan was made (see {@link #memberless})
     * @param startIndex the index in {@code levels} of the tier the request starts from: the best one, or the one its
     *     session is bound to
     * @param session the session the request belongs to, or null when it has none; a pinned plan has one only to count
     *     its dispatches (see {@link Session#keepsTier})
     */
    TierPlan(
        String vendor,
        String modelKey,
        Mode mode,
        SourceTierSnapshot snapshot,
        VendorConfig vendorConfig,
        List<Level> levels,
        List<Level> holdLevels,
        List<Tier> unavailableTiers,
        Set<String> memberlessTiers,
        int startIndex,
        Session session,
        Owner owner,
        long outputBound,
        BillingQuote standardQuote,
        AccountPoolRequestContext poolContext
    ) {
        this(vendor, modelKey, mode, snapshot, vendorConfig, levels, holdLevels, unavailableTiers, memberlessTiers,
            startIndex, session, owner, outputBound, standardQuote, poolContext, Set.of(), null, false);
    }

    /**
     * @param pinnedScope for a pinned plan, the ids of all the tiers its selector allows, including those that were
     *     left out of {@code levels}; empty for an automatic plan, which allows them all
     * @param lowBalanceHint what to tell a user whose balance does not cover the reservation about the tiers that would
     *     cost less, or null
     * @param unified whether the vendor's all-endpoints switch moved the request onto the Anthropic route (see
     *     {@link #unified()}); the cells of the levels are then the Anthropic route's
     */
    TierPlan(
        String vendor,
        String modelKey,
        Mode mode,
        SourceTierSnapshot snapshot,
        VendorConfig vendorConfig,
        List<Level> levels,
        List<Level> holdLevels,
        List<Tier> unavailableTiers,
        Set<String> memberlessTiers,
        int startIndex,
        Session session,
        Owner owner,
        long outputBound,
        BillingQuote standardQuote,
        AccountPoolRequestContext poolContext,
        Set<String> pinnedScope,
        String lowBalanceHint,
        boolean unified
    ) {
        this(vendor, modelKey, mode, snapshot, vendorConfig, levels, holdLevels, unavailableTiers, memberlessTiers,
            startIndex, session, owner, outputBound, standardQuote, poolContext, pinnedScope, lowBalanceHint, unified,
            List.of());
    }

    /**
     * @param xpSkips the relays registered in the vendor's tiers that took no part in the request, each with the first
     *     condition it failed (see {@link #xpSkips()})
     */
    TierPlan(
        String vendor,
        String modelKey,
        Mode mode,
        SourceTierSnapshot snapshot,
        VendorConfig vendorConfig,
        List<Level> levels,
        List<Level> holdLevels,
        List<Tier> unavailableTiers,
        Set<String> memberlessTiers,
        int startIndex,
        Session session,
        Owner owner,
        long outputBound,
        BillingQuote standardQuote,
        AccountPoolRequestContext poolContext,
        Set<String> pinnedScope,
        String lowBalanceHint,
        boolean unified,
        List<XpSkip> xpSkips
    ) {
        this(vendor, modelKey, mode, snapshot, vendorConfig, levels, holdLevels, unavailableTiers, memberlessTiers,
            startIndex, session, owner, outputBound, standardQuote, poolContext, pinnedScope, lowBalanceHint, unified,
            xpSkips, false);
    }

    /**
     * @param xpOpen whether a pair of a relay registered in the vendor's tiers is offered and open to this request's key
     *     (see {@link #leftOutRelay})
     */
    TierPlan(
        String vendor,
        String modelKey,
        Mode mode,
        SourceTierSnapshot snapshot,
        VendorConfig vendorConfig,
        List<Level> levels,
        List<Level> holdLevels,
        List<Tier> unavailableTiers,
        Set<String> memberlessTiers,
        int startIndex,
        Session session,
        Owner owner,
        long outputBound,
        BillingQuote standardQuote,
        AccountPoolRequestContext poolContext,
        Set<String> pinnedScope,
        String lowBalanceHint,
        boolean unified,
        List<XpSkip> xpSkips,
        boolean xpOpen
    ) {
        this(vendor, modelKey, mode, snapshot, vendorConfig, levels, holdLevels, unavailableTiers, memberlessTiers,
            startIndex, session, owner, outputBound, standardQuote, poolContext, pinnedScope, lowBalanceHint, unified,
            xpSkips, xpOpen, null);
    }

    /**
     * @param relaySession what the session of the request is called for the relays that broke off its answers (see {@link
     *     #relaySession()}); null when nothing tells the session from others
     */
    TierPlan(
        String vendor,
        String modelKey,
        Mode mode,
        SourceTierSnapshot snapshot,
        VendorConfig vendorConfig,
        List<Level> levels,
        List<Level> holdLevels,
        List<Tier> unavailableTiers,
        Set<String> memberlessTiers,
        int startIndex,
        Session session,
        Owner owner,
        long outputBound,
        BillingQuote standardQuote,
        AccountPoolRequestContext poolContext,
        Set<String> pinnedScope,
        String lowBalanceHint,
        boolean unified,
        List<XpSkip> xpSkips,
        boolean xpOpen,
        String relaySession
    ) {
        this(vendor, modelKey, mode, snapshot, vendorConfig, levels, holdLevels, unavailableTiers, memberlessTiers,
            startIndex, session, owner, outputBound, standardQuote, poolContext, pinnedScope, lowBalanceHint, unified,
            xpSkips, xpOpen, relaySession, List.of());
    }

    /**
     * @param requestBoundTiers the tiers, from the one the session is bound to down, in which a relay was left out for
     *     something about this request or this moment, whether or not the tier is among {@code levels} (see {@link
     *     #requestBoundAbove})
     */
    TierPlan(
        String vendor,
        String modelKey,
        Mode mode,
        SourceTierSnapshot snapshot,
        VendorConfig vendorConfig,
        List<Level> levels,
        List<Level> holdLevels,
        List<Tier> unavailableTiers,
        Set<String> memberlessTiers,
        int startIndex,
        Session session,
        Owner owner,
        long outputBound,
        BillingQuote standardQuote,
        AccountPoolRequestContext poolContext,
        Set<String> pinnedScope,
        String lowBalanceHint,
        boolean unified,
        List<XpSkip> xpSkips,
        boolean xpOpen,
        String relaySession,
        List<Tier> requestBoundTiers
    ) {
        this(vendor, modelKey, mode, snapshot, vendorConfig, levels, holdLevels, unavailableTiers, memberlessTiers,
            startIndex, session, owner, outputBound, standardQuote, poolContext, pinnedScope, lowBalanceHint, unified,
            xpSkips, xpOpen, relaySession, requestBoundTiers, false, false);
    }

    /**
     * @param nativesAfterLengthRefusal whether an account of the route's native cells sits in a tier the request is served
     *     from once a relay has refused the length of its prompt and this node has learned it, whatever state the account is
     *     in (see {@link #retriesAfterRelayRefusal}); always false for a plan that no relay takes part in
     * @param nativesAfterOtherRefusal the same for a refusal that is not learned, where the request keeps the tiers it was
     *     given
     */
    TierPlan(
        String vendor,
        String modelKey,
        Mode mode,
        SourceTierSnapshot snapshot,
        VendorConfig vendorConfig,
        List<Level> levels,
        List<Level> holdLevels,
        List<Tier> unavailableTiers,
        Set<String> memberlessTiers,
        int startIndex,
        Session session,
        Owner owner,
        long outputBound,
        BillingQuote standardQuote,
        AccountPoolRequestContext poolContext,
        Set<String> pinnedScope,
        String lowBalanceHint,
        boolean unified,
        List<XpSkip> xpSkips,
        boolean xpOpen,
        String relaySession,
        List<Tier> requestBoundTiers,
        boolean nativesAfterLengthRefusal,
        boolean nativesAfterOtherRefusal
    ) {
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("A tiered plan needs at least one tier");
        }
        if (startIndex < 0 || startIndex >= levels.size()) {
            throw new IllegalArgumentException("The plan starts at tier " + startIndex + " of " + levels.size());
        }
        this.tiered = true;
        this.vendor = Objects.requireNonNull(vendor, "vendor");
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.configVersion = snapshot.configVersion();
        this.vendorConfig = Objects.requireNonNull(vendorConfig, "vendorConfig");
        this.levels = List.copyOf(levels);
        this.holdLevels = List.copyOf(holdLevels);
        this.unavailableTiers = List.copyOf(unavailableTiers);
        this.requestBoundTiers = List.copyOf(requestBoundTiers);
        this.memberlessTiers = Set.copyOf(memberlessTiers);
        this.startIndex = startIndex;
        this.session = session;
        this.owner = owner;
        this.outputBound = outputBound;
        this.standardQuote = standardQuote;
        this.poolContext = Objects.requireNonNull(poolContext, "poolContext");
        this.selectorIgnored = false;
        this.pinnedScope = Set.copyOf(pinnedScope);
        this.lowBalanceHint = lowBalanceHint;
        this.unified = unified;
        this.xpSkips = List.copyOf(xpSkips);
        this.xpOpen = xpOpen;
        this.relaySession = relaySession;
        this.nativesAfterLengthRefusal = nativesAfterLengthRefusal;
        this.nativesAfterOtherRefusal = nativesAfterOtherRefusal;
        // A reservation is only an upper bound: the most any tier the request may end up in would reserve.
        this.reservation = this.holdLevels.stream()
            .map(Level::estimate)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    }

    /** False for {@link #UNTIERED}. */
    public boolean tiered() {
        return tiered;
    }

    /**
     * Whether the request named a tier that was not used, which the response says in its status header: the vendor has
     * a tier configuration that is switched off ({@link #UNTIERED_IGNORING_SELECTOR}). A tiered plan says it in the
     * headers of its tiers.
     */
    public boolean selectorIgnored() {
        return selectorIgnored;
    }

    /**
     * What to add to the "balance is insufficient" answer of an automatic request, which reserves the largest of the
     * estimates of the tiers it may be served from: the tiers a client may name that would reserve less, with how to name
     * them. Null when there is none, and for a request that named its tier.
     */
    public String lowBalanceHint() {
        return lowBalanceHint;
    }

    /**
     * Whether the vendor's all-endpoints switch moved the request onto the Anthropic route (design 4.9): a Claude model
     * asked through Chat Completions or Responses, which would otherwise be served by Kiro alone, is served by the
     * Anthropic keys and then Kiro, as it is through the Messages endpoint. The request is then dispatched, retried and
     * logged as an Anthropic one; the facade's own route is the one to put aside.
     */
    public boolean unified() {
        return unified;
    }

    /**
     * The relays registered in the vendor's tiers that take no part in this request and why (design 3, step 5b), frozen
     * with the plan. Only the relays of a tier that is enabled and whose account is in the account cache are listed: one
     * that is switched off is no one's concern until it is switched on. Empty for a vendor without relays, and for a
     * request no relay can be given (an entry whose pair this release does not offer).
     */
    public List<XpSkip> xpSkips() {
        return xpSkips;
    }

    /**
     * What the session of the request is called when a relay breaks off an answer for it (design 7.8): the name the
     * client gave the session, or the fingerprint of its beginning ({@link TierSessionKeys#resolve}). The relay is then kept
     * from the same session for a couple of minutes, and the plans of that session's next requests are made without it.
     * Unlike {@link #session()}, which is the session of the tier bindings, it exists whether or not the vendor keeps
     * sessions to their tier. Null for a plan that no relay takes part in (none is open to the key), and when nothing tells
     * the session from others; otherwise it is the session whatever the relays did, or were left out for, this time.
     */
    public String relaySession() {
        return relaySession;
    }

    /**
     * Whether the vendor has a relay registered in its tiers, whether or not one takes part in this request: a history that
     * a request replays can hold what a relay answered earlier (the configuration of the plan, not the state of its relays).
     */
    public boolean vendorHasRelays() {
        return tiered && snapshot.members(vendor).stream().anyMatch(SourceTierSnapshot.Member::crossProtocol);
    }

    /** The vendor whose tiers the request is served from, as the tier configuration names it. */
    public String vendor() {
        return vendor;
    }

    /**
     * The model as tiers see it: the catalog id without {@code starry/}, {@code anthropic/} and {@code -fast}, and an
     * alias such as {@code sonnet} as the model it stands for. Account affinity and session bindings of a tier are
     * kept per model key, since what a tier can serve depends on the model.
     */
    public String modelKey() {
        return modelKey;
    }

    public Mode mode() {
        return mode;
    }

    /** The version of the tier configuration the plan was made from. */
    public long configVersion() {
        return configVersion;
    }

    /** The vendor's switches (sticky sessions, refusal cap, the cross-tier reserved slot) as they were at planning. */
    public VendorConfig vendorConfig() {
        return vendorConfig;
    }

    /** The tiers the request may be served from, best first. A tier with nothing that could serve it right now is not in it. */
    public List<Level> levels() {
        return levels;
    }

    /**
     * The tiers the reservation has to cover, and the only ones the request may be served from: all of them for a
     * request that starts from the best tier. A session that was pushed down starts from its tier and holds that and
     * the tiers below it, plus the tiers above that are not dearer at the output the request is limited to, so that a
     * user who has only enough for the tier the session is in is not refused for a dearer one it is no longer in. A tier
     * above that is held can still reserve more than those at or below the start, when the session's tier holds relays alone
     * and is reserved for the few tokens they are sent: that user is refused for it, and told to pin the tier he can pay.
     */
    public List<Level> holdLevels() {
        return holdLevels;
    }

    /**
     * The tiers that were left out of {@link #levels()} because none of their accounts can serve right now: they have
     * accounts of the route's kinds, and every one of them is out of quota. Only the tiers the request
     * starts from or is below are kept here. They are tiers the request is found to have gone through when a tier
     * under them serves it, as it would have if they had been in the plan and unavailable: such a request pushes its
     * session down (design 4.1 counts the quota of an account as the account being unavailable). A
     * tier with no account of the route's kinds at all is something else, one this request cannot be served from, and
     * is in neither list; that includes a tier whose accounts are all disabled or suspended, since a suspended account
     * is disabled and is not in the account cache. Nor is a tier in which a relay sat this request out for its own sake
     * ({@link #requestBoundAbove}): it was not found with nothing to serve with, however its other members are.
     */
    public List<Tier> unavailableTiers() {
        return unavailableTiers;
    }

    /**
     * Whether the tier had no account of the route's cells in the account cache when the plan was made, though it is in
     * the plan. That only happens when no tier has one, since a plan needs a tier: a request then goes through every
     * tier looking for an account, and one found after a reload of the cache in a lower tier does not mean the session
     * was pushed out of the tiers above, which never had an account to push it out with.
     */
    public boolean memberless(Level level) {
        return memberlessTiers.contains(level.tierId());
    }

    /** Whether a tier left out of the plan for having nothing available sits above {@code level}. */
    public boolean unavailableAbove(Level level) {
        return unavailableTiers.stream().anyMatch(tier -> tier.level() < level.level());
    }

    /**
     * Whether a tier above {@code level}, from the one the session is bound to down, has a relay that was left out for
     * something about this request or this moment: it is too large for its window, it needs what the relay does not carry,
     * the conversion for it was not taken, or it broke off an answer for the session a moment ago. Whatever the other
     * members of that tier then did (they were busy, or out of quota), the session was not found with nothing to be
     * served by in the tier: a smaller or an ordinary request would have been given the relay that is left out. The
     * tier below serving the request is no fall of the session, and does not keep it there (design 0, 4.7, invariant I8),
     * whether the tier is in {@link #levels()} or was left out of it.
     */
    public boolean requestBoundAbove(Level level) {
        return requestBoundTiers.stream().anyMatch(tier -> tier.level() < level.level());
    }

    /**
     * The place of the tier a session is bound to in the order this plan was made with. The tier is found by its id,
     * as a reordering of the tiers since the binding was made moves it; the place it was bound at stands in for a tier
     * that is no longer configured.
     */
    public int levelOf(TierSessionBindings.Binding binding) {
        return snapshot.tier(vendor, binding.tierId()).map(Tier::level).orElse(binding.level());
    }

    /** The index in {@link #levels()} of the tier the request starts from; 0 unless its session is bound to a lower tier. */
    public int startIndex() {
        return startIndex;
    }

    /** The tier the request starts from. */
    public Level startLevel() {
        return levels.get(startIndex);
    }

    /** The session the request belongs to, empty when it has none: sticky sessions off, no session to tell. */
    public Optional<Session> session() {
        return Optional.ofNullable(session);
    }

    /** Whether the reservation covers {@code level}, so that it may serve the request. */
    public boolean covers(Level level) {
        return holdLevels.stream().anyMatch(held -> held.tierRef().equals(level.tierRef()));
    }

    /** The owner of the response chain being continued, when it is served by one of the tiers; null otherwise. */
    public Optional<Owner> owner() {
        return Optional.ofNullable(owner);
    }

    /**
     * The most output the request may ask of a source, as the limit it is held to says (the default of the gateway when the
     * client named none), which the usage of every source is bounded by. A tier that holds relays alone is reserved for less
     * where the gateway chose the limit: a relay is not given that default ({@link Level#estimate}).
     */
    public long outputBound() {
        return outputBound;
    }

    /** The model's price before any tier factor, which is what the same request costs without tiers; null when not billed. */
    public BillingQuote standardQuote() {
        return standardQuote;
    }

    /** Whether the client named the tier, so that the request is served by it or not at all. */
    public boolean pinned() {
        return mode == Mode.PINNED;
    }

    /**
     * Whether a relay's refusal of the request itself is answered with a retryable error and not with the refusal
     * (design 7.8). The refusal (a prompt longer than its window, a conversion it will not take) is what the client is told
     * only when the request has nowhere native left to go; where native accounts exist for it that are only busy, or out of
     * quota, for now, it is told to try again, which is what the identical request is told next, once this node knows the
     * relay does not take it: the relay is then left out of the plan, and the accounts are what is left. A client that was
     * told "prompt is too long" would shorten, lossily, a conversation that an account would have taken as it was.
     *
     * <p>Which accounts count depends on what the retry is served from. A refusal of the length is learned, and a request
     * pinned by a hint (the header or the preference of the key) drops the hint once the tier it names has no relay left
     * for it, so the retry is served in the order of all the tiers, and the accounts of any of them count. A conversion that
     * a relay will not take is not learned, and the hint stays: the retry is refused again by the relay, crosses within the
     * tiers the pin names, and is served by the accounts of those tiers if there are any. A hint to {@code relay+} has
     * Kiro's accounts below the relays, and the client is told to try again; one to {@code relay}, a tier of relays alone, has none,
     * and the client is told the refusal. A pin by the suffix counts the tiers it names in both cases.
     *
     * <p>The plan does not know one thing the answer depends on: a relay that refused the length of the body with the maximum
     * output of its mapping is answered with a retry whatever the plan's accounts are, since the identical request is
     * sent the smaller body its refusal taught and may be served by the relay itself ({@code ProxyFacade}, which knows what
     * the relay was sent).
     *
     * @param contextRefusal whether the relay said the prompt is longer than it takes, which this node learns
     */
    public boolean retriesAfterRelayRefusal(boolean contextRefusal) {
        return contextRefusal ? nativesAfterLengthRefusal : nativesAfterOtherRefusal;
    }

    /**
     * What to reserve: the largest estimate of the {@link #holdLevels}, so that whichever tier serves the request is
     * covered. Null when the request is not billed.
     */
    public BigDecimal reservation() {
        return reservation;
    }

    /**
     * The context the request's account selection runs with. A tiered request has no circuit epoch, so the
     * model-family circuit of the old pool policy never applies to it: it neither sees an open circuit nor feeds one,
     * and its affinity keys stay as they are.
     */
    public AccountPoolRequestContext poolContext() {
        return poolContext;
    }

    /** Whether {@code account} is one of the accounts the configuration puts into {@code level} on its own (not as a relay). */
    public boolean holds(Level level, ProxyAccountEntity account) {
        return TierMembership.holds(snapshot, vendor, level.tier(), account);
    }

    /**
     * Whether {@code level} holds {@code account} as a member of {@code cell}: for the cell of the relays one of the relays
     * the plan found eligible in that tier, for any other cell an account the configuration puts into the tier.
     */
    public boolean holds(Level level, PoolCell cell, ProxyAccountEntity account) {
        return cell == PoolCell.XP_OPENAI_CHAT ? level.relay(account.getAccountId()).isPresent() : holds(level, account);
    }

    /**
     * Whether the plan lets the request be served by {@code account} as one of the accounts of {@code cell} in
     * {@code level}: the tier is one the reservation covers, the cell is one of that tier's, the cell's rule takes the
     * account and the tier holds it (for the cell of the relays, the account is one of the relays the plan found eligible
     * in that tier).
     */
    public boolean permits(Level level, PoolCell cell, ProxyAccountEntity account) {
        if (!covers(level)) {
            return false;
        }
        Level own = levels.stream()
            .filter(candidate -> candidate.tierRef().equals(level.tierRef()) && candidate.cells().contains(cell))
            .findFirst()
            .orElse(null);
        if (own == null || !cell.predicate().test(account)) {
            return false;
        }
        // The relays of a tier are the ones the plan found to be eligible, not the accounts a rule puts into the tier.
        return holds(own, cell, account);
    }

    /**
     * Whether {@code account} is one the vendor's tiers put into a tier that this plan does not have: it is a native
     * member of an enabled tier that is not among {@link #levels()} (the plan left it out because nothing in it could
     * serve, or because every account of it is disabled) and one of the route's cells takes it. The request cannot be
     * served by such an account; the retest tests it all the same, since the tier recovers through it or not at all.
     */
    public boolean leftOut(ProxyAccountEntity account) {
        // Only the native cells: the cell of the relays takes any compatible key, and a key that is no relay of the vendor is
        // not a member of one of its tiers for being compatible.
        if (!tiered || levels.get(0).cells().stream()
            .noneMatch(cell -> cell != PoolCell.XP_OPENAI_CHAT && cell.predicate().test(account))) {
            return false;
        }
        return TierMembership.nativeTierOf(snapshot, vendor, account)
            .filter(Tier::enabled)
            // A request that named its tier has no use for the tiers it left out on purpose.
            .filter(tier -> pinnedScope.isEmpty() || pinnedScope.contains(tier.tierId()))
            .filter(tier -> levels.stream().noneMatch(level -> level.tierId().equals(tier.tierId())))
            .isPresent();
    }

    /**
     * Whether {@code account} is a relay of a tier of the vendor that the plan did not look at because it is out of
     * service (disabled, suspended: it is in no account cache), while the request is one that relays may be given at all.
     * Like a native account of a tier that is left out, it cannot serve this request, and the retest tests it all the
     * same: the tier it is a relay of would otherwise never come back through the one retest a request makes. A relay
     * that the plan did look at is either one of its relays or was left out for what the request asks, which a test of
     * the account does not change.
     */
    public boolean leftOutRelay(ProxyAccountEntity account) {
        String accountId = account == null ? null : account.getAccountId();
        if (!tiered || !xpOpen || accountId == null || !PoolCell.XP_OPENAI_CHAT.predicate().test(account)) {
            return false;
        }
        return snapshot.member(vendor, accountId)
            .filter(SourceTierSnapshot.Member::crossProtocol)
            .flatMap(member -> snapshot.tier(vendor, member.tierId()))
            .filter(Tier::enabled)
            // A request that named its tier has no use for the tiers it left out on purpose.
            .filter(tier -> pinnedScope.isEmpty() || pinnedScope.contains(tier.tierId()))
            .filter(tier -> levels.stream().noneMatch(level -> level.relay(accountId).isPresent())
                && xpSkips.stream().noneMatch(skip -> skip.accountId().equals(accountId)))
            .isPresent();
    }

    /**
     * What a session's bindings of {@code level} are kept under for this request's model, so that an account a
     * session was bound to in one tier does not steer the selection in another, nor one model's bindings another's.
     */
    public String affinityScope(Level level) {
        return "tier:" + level.tierRef() + '|' + modelKey;
    }
}
