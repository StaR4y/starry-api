package tech.starryapi.proxy.tier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountPoolRequestContext;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.BillingQuote;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.xproto.PairRegistry;
import tech.starryapi.platform.xproto.RelayErrorException;
import tech.starryapi.proxy.tier.TierPlan.Level;
import tech.starryapi.proxy.tier.TierRuntime.Destination;
import tech.starryapi.proxy.tier.TierSelectorParser.Selector;
import tech.starryapi.sourcetier.SourceTierCatalogModels;
import tech.starryapi.sourcetier.SourceTierRegistry;
import tech.starryapi.sourcetier.SourceTierSnapshot;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;
import tools.jackson.databind.JsonNode;

/**
 * Plans a request over the tiers of its vendor (design section 3) for the native route: which tiers can serve it,
 * which kinds of account each tier holds, what each tier would charge and reserve. It reads the configuration through
 * the registry snapshot and the accounts through the account cache, and does not select an account, reserve or charge
 * anything; the plan is what the facade then selects, reserves and settles by.
 *
 * <p>A vendor that is not switched on, or has no tier switched on, gets {@link TierPlan#UNTIERED} without a price
 * being looked up, so the request is the one that ran before tiers existed. Before the registry has loaded for the
 * first time nothing is known about any vendor; then every request is refused (503 TIER_CONFIG_UNAVAILABLE) before
 * a reservation, rather than being priced as if its vendor had no tiers.
 *
 * <p>A client may name the tier it wants (design 6): the plan then holds only that tier, or that tier and the ones below
 * it, and the request is served by those or not at all. What the client cannot have is refused here, before anything
 * is reserved, when it asked by the model suffix: a pool that does not exist (400), one the route has nothing in (400),
 * one the administrator keeps to itself (403), a response chain that would have to leave the pool (409). The suffix is a
 * demand, written for this one request. The header and the key's preference are hints: each is a standing choice made
 * once for a client or a key, which then calls from other endpoints, with other models and in the middle of response
 * chains that the choice knows nothing about (the client configurations the console writes carry the header for every
 * request the client makes). When a hint cannot be followed, whatever the reason (the pool does not exist, nobody may
 * choose it, the route of this request has nothing in it, the response chain would have to leave its owner's pool), the
 * request is served in the tiers' order and the response says that the selector was ignored.
 *
 * <p>A tier may also hold relays, accounts of another protocol that a request is converted for (design 7). They join a
 * tier's cells as the last of them, and only the relays that {@link XpEligibility} finds fit for this request: the pair is
 * offered and its gate is open for the key, the model is mapped and verified, the request has nothing the relay cannot
 * carry, it converts and fits, it has not broken off an answer for the session a moment ago, and no prompt as large was
 * refused by it for its length. A tier whose relays are all left out is as it would be without them, and a request
 * that nothing else can serve because of a feature a relay cannot carry is refused with that feature named (400
 * SOURCE_TIER_FEATURE_UNSUPPORTED), before anything is reserved. A vendor without relays is planned exactly as it was.
 *
 * <p>The one thing that changes where a request is sent is the all-endpoints switch of the anthropic vendor (design 4.9),
 * which the plan reports ({@link TierPlan#unified}): a Claude model asked through Chat Completions or Responses is planned
 * and served on the Anthropic route, as it is through the Messages endpoint, and not by Kiro alone.
 */
public class DefaultSourceTierPlanner implements TierRuntime.Planner {

    /** The vendor whose endpoints the all-endpoints switch unifies: the one whose Claude models two routes serve. */
    private static final String UNIFIED_VENDOR = "anthropic";
    /** The prefixes a route strips from a model id before it looks the model up. */
    private static final List<String> STRIPPED_PREFIXES = List.of("starry/", "anthropic-compatible/", "anthropic/");

    private final SourceTierRegistry registry;
    private final ModelPoolKeyResolver keyResolver;
    private final ModelCatalogProvider modelCatalog;
    private final AccountPoolService accountPool;
    private final PaymentSettingsService paymentSettings;
    private final ModelPricingService modelPricing;
    private final TierSessionBindings bindings;
    private final boolean selectors;
    private final boolean unifyEndpoints;
    private final XpEligibility relays;
    private final XpSkipCounts skipCounts;
    /** The entries for which the registry lists a pair: for any other, no request is read for relays. */
    private final Set<TierEntry> relayEntries;

    /** A planner that lets clients name the tier they want, and honours the all-endpoints switch. */
    public DefaultSourceTierPlanner(
        SourceTierRegistry registry,
        ModelPoolKeyResolver keyResolver,
        ModelCatalogProvider modelCatalog,
        AccountPoolService accountPool,
        PaymentSettingsService paymentSettings,
        ModelPricingService modelPricing,
        TierSessionBindings bindings
    ) {
        this(registry, keyResolver, modelCatalog, accountPool, paymentSettings, modelPricing, bindings, true);
    }

    public DefaultSourceTierPlanner(
        SourceTierRegistry registry,
        ModelPoolKeyResolver keyResolver,
        ModelCatalogProvider modelCatalog,
        AccountPoolService accountPool,
        PaymentSettingsService paymentSettings,
        ModelPricingService modelPricing,
        TierSessionBindings bindings,
        boolean selectors
    ) {
        this(registry, keyResolver, modelCatalog, accountPool, paymentSettings, modelPricing, bindings, selectors, true);
    }

    /**
     * @param bindings where the tier of each session is kept; {@link TierSessionBindings#disabled()} for a planner that
     *     does not keep sessions to their tier, which starts every request from the best one
     * @param selectors whether a client may name the tier it wants; when it may not, a model id keeps its {@code @} and
     *     every selector is left unread
     * @param unifyEndpoints whether the all-endpoints switch of the anthropic vendor is honoured; when it is not, a vendor
     *     that has the switch on is planned over the routes of the endpoints as they are
     */
    public DefaultSourceTierPlanner(
        SourceTierRegistry registry,
        ModelPoolKeyResolver keyResolver,
        ModelCatalogProvider modelCatalog,
        AccountPoolService accountPool,
        PaymentSettingsService paymentSettings,
        ModelPricingService modelPricing,
        TierSessionBindings bindings,
        boolean selectors,
        boolean unifyEndpoints
    ) {
        this(registry, keyResolver, modelCatalog, accountPool, paymentSettings, modelPricing, bindings, selectors, unifyEndpoints,
            PairRegistry.defaults(), new XpSkipCounts());
    }

    /** A planner that has learned nothing of the relays. */
    public DefaultSourceTierPlanner(
        SourceTierRegistry registry,
        ModelPoolKeyResolver keyResolver,
        ModelCatalogProvider modelCatalog,
        AccountPoolService accountPool,
        PaymentSettingsService paymentSettings,
        ModelPricingService modelPricing,
        TierSessionBindings bindings,
        boolean selectors,
        boolean unifyEndpoints,
        PairRegistry pairs,
        XpSkipCounts skipCounts
    ) {
        this(registry, keyResolver, modelCatalog, accountPool, paymentSettings, modelPricing, bindings, selectors, unifyEndpoints,
            pairs, skipCounts, XpLearnedLimits.disabled());
    }

    /**
     * @param pairs the cross-protocol pairs this release offers, which decide whether a relay can take part in a request
     * @param skipCounts where the reasons relays were left out of plans are counted
     * @param learnedLimits the context windows that the refusals of relays taught this node, which a relay is also left out
     *     of the requests that are as large as one it refused for (design 7.8)
     */
    public DefaultSourceTierPlanner(
        SourceTierRegistry registry,
        ModelPoolKeyResolver keyResolver,
        ModelCatalogProvider modelCatalog,
        AccountPoolService accountPool,
        PaymentSettingsService paymentSettings,
        ModelPricingService modelPricing,
        TierSessionBindings bindings,
        boolean selectors,
        boolean unifyEndpoints,
        PairRegistry pairs,
        XpSkipCounts skipCounts,
        XpLearnedLimits learnedLimits
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.accountPool = Objects.requireNonNull(accountPool, "accountPool");
        this.paymentSettings = Objects.requireNonNull(paymentSettings, "paymentSettings");
        this.modelPricing = Objects.requireNonNull(modelPricing, "modelPricing");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.selectors = selectors;
        this.unifyEndpoints = unifyEndpoints;
        this.relays = new XpEligibility(Objects.requireNonNull(pairs, "pairs"), accountPool,
            new XpModelResolver(modelCatalog, keyResolver), bindings, Objects.requireNonNull(learnedLimits, "learnedLimits"));
        this.skipCounts = Objects.requireNonNull(skipCounts, "skipCounts");
        Set<TierEntry> entries = EnumSet.noneOf(TierEntry.class);
        pairs.offeredPairs().forEach(pair -> entries.add(TierEntry.valueOf(pair.entry().name())));
        this.relayEntries = Set.copyOf(entries);
    }

    @Override
    public boolean wantsEntryFacts(TierEntry entry) {
        if (entry == null || !relayEntries.contains(entry)) {
            return false;
        }
        try {
            return registry.snapshot().hasOpenRelayFor(entry.name());
        } catch (ApiException notLoaded) {
            return false;
        }
    }

    @Override
    public TierSelectorParser.Stripped stripSelector(String model, Function<String, Destination> destination) {
        Optional<TierSelectorParser.Suffix> suffix = selectors ? TierSelectorParser.splitSuffix(model) : Optional.empty();
        // A bare "@kiro" names no model for a tier to serve.
        if (suffix.isEmpty() || suffix.get().model().isBlank()) {
            return TierSelectorParser.Stripped.plain(model);
        }
        SourceTierSnapshot snapshot;
        try {
            snapshot = registry.snapshot();
        } catch (ApiException notLoaded) {
            // Before the first load nothing is known: the id is left as it is and the planner refuses the request.
            return TierSelectorParser.Stripped.plain(model);
        }
        String base = suffix.get().model();
        Selector selector = TierSelectorParser.parse(TierSelectorParser.Source.SUFFIX, suffix.get().reference());
        Destination where = destination.apply(base);
        return takesSuffix(snapshot, base, selector, where)
            ? new TierSelectorParser.Stripped(base, selector, where)
            : TierSelectorParser.Stripped.plain(model);
    }

    /**
     * Whether the {@code @reference} on the end of {@code base@reference} is a tier selector and not part of the model id.
     * Anything else is a model id that means whatever it meant before selectors existed, and the vendor of a model
     * nobody configured does not know tiers at all (I4), nor does one whose configuration holds no tier.
     *
     * <p>The vendor is the one that serves the request: the plan names it from the model after the key's mapping and the
     * provider hint of the accounts that serve it, and so does this, from where the model goes. A catalog model has no
     * {@code @} of its own to lose (the aliases clients use, such as {@code sonnet}, are catalog models too), so it loses
     * any suffix, and a suffix that names no tier is then refused. A model the catalog does not list (a Codex model, a
     * dated Claude id newer than the catalog) is served on the vendor its accounts name all the same, and its suffix is
     * taken off when it names a tier of that vendor; an {@code @} that names none, such as the {@code @20250514} of a
     * Vertex-style id, stays part of the id.
     */
    private boolean takesSuffix(SourceTierSnapshot snapshot, String base, Selector selector, Destination destination) {
        String vendor = keyResolver.resolve(base, destination.upstreamModel(), destination.providerHint()).provider();
        if (!hasTiers(snapshot, vendor)) {
            return false;
        }
        String id = normalize(base);
        return modelCatalog.models().stream().anyMatch(spec -> normalize(spec.id()).equals(id))
            || namesTierOf(snapshot, vendor, selector);
    }

    /**
     * Whether the vendor has a tier configuration: a row of its own with at least one tier. A vendor whose tiers were all
     * deleted keeps its row, and has nothing a selector could name, so it is the vendor nobody configured.
     */
    private static boolean hasTiers(SourceTierSnapshot snapshot, String vendor) {
        return snapshot.vendor(vendor).isPresent() && !snapshot.tiers(vendor).isEmpty();
    }

    /** Whether the selector is the automatic choice or refers to a tier of the vendor (switched on or not). */
    private static boolean namesTierOf(SourceTierSnapshot snapshot, String vendor, Selector selector) {
        return switch (selector.kind()) {
            case AUTO -> true;
            case TIER -> snapshot.tier(vendor, selector.tierId()).isPresent();
            case LEVEL -> snapshot.tiers(vendor).stream().anyMatch(tier -> tier.level() == selector.level());
            case INVALID -> false;
        };
    }

    @Override
    public Mono<TierPlan> plan(TierPlanRequest request) {
        return Mono.defer(() -> {
            SourceTierSnapshot snapshot = registry.snapshot();
            String vendor = keyResolver.resolve(request.requestedModel(), request.upstreamModel(), request.providerHint())
                .provider();
            boolean configured = hasTiers(snapshot, vendor);
            VendorConfig config = snapshot.vendor(vendor).filter(VendorConfig::enabled).orElse(null);
            List<Tier> tiers = config == null
                ? List.of()
                : snapshot.tiers(vendor).stream().filter(Tier::enabled).toList();
            Selector selector = selectors ? request.selector() : null;
            if (tiers.isEmpty()) {
                // A vendor nobody configured does not know selectors, nor does one whose configuration holds no tier; one
                // that is configured but switched off drops them, and says so. So does a suffix that reaches a vendor
                // without tiers: it was taken off the model while the vendor still had them (the configuration changed
                // in between), and the request is not served by them.
                boolean named = selector != null && !selector.auto();
                return Mono.just(named && (configured || selector.source() == TierSelectorParser.Source.SUFFIX)
                    ? TierPlan.UNTIERED_IGNORING_SELECTOR
                    : TierPlan.UNTIERED);
            }
            Choice chosen = choose(selector, snapshot, vendor, tiers);
            // The plan is made for the route the request is served on, which the all-endpoints switch may change.
            boolean unified = unifies(request, config, vendor);
            TierPlanRequest routed = unified ? request.withRoute(TierRoute.ANTHROPIC) : request;
            List<PoolCell> units = nativeUnits(routed);
            String modelKey = modelKey(vendor, routed.upstreamModel());
            XpEligibility.Found xp = relays.evaluate(routed, snapshot, vendor, modelKey, units, tiers);
            skipCounts.add(vendor, xp.skips());
            // A chain no tier can continue is refused here, before the price of the model is even looked up.
            return resolve(routed, snapshot, config, vendor, modelKey, units, xp, chosen, tiers)
                .flatMap(resolved -> standardPrice(routed)
                    .map(price -> build(routed, snapshot, config, vendor, modelKey, units, xp, resolved, price.orElse(null), unified)));
        });
    }

    /**
     * Whether the all-endpoints switch moves the request onto the Anthropic route (design 4.9). The switch is the one
     * thing that may send a request to accounts its route does not use (I6), so it is narrow: the anthropic vendor has to
     * have it on, the request has to come through Chat Completions or Responses, which route a Claude model to Kiro
     * alone (the Messages endpoint is on the Anthropic route already, and the Gemini endpoint is left as it is), and the
     * model has to be a Claude model of the catalog. Neither an id of another vendor (a Gemini id is an alias the Kiro route
     * serves with a Claude model, but it is Google's), nor a routing preset such as {@code auto}, nor a model that a
     * route prefix or a name already placed on another route is.
     */
    private boolean unifies(TierPlanRequest request, VendorConfig config, String vendor) {
        return unifyEndpoints
            && config.unifyEndpoints()
            && UNIFIED_VENDOR.equals(vendor)
            && request.route() == TierRoute.KIRO
            && (request.entry() == TierEntry.CHAT || request.entry() == TierEntry.RESPONSES)
            && SourceTierCatalogModels.isModelOf(modelCatalog, keyResolver, UNIFIED_VENDOR, normalize(request.upstreamModel()));
    }

    /**
     * What a choice leaves of the request: the session it belongs to and the tier that session is bound to, the tier of
     * the response owner and the tiers that have an account to serve.
     */
    private record Resolved(
        Choice choice,
        TierPlan.Session session,
        TierSessionBindings.Binding bound,
        Tier ownerTier,
        Availability availability
    ) {
    }

    /**
     * Works out what the choice leaves of the request, and refuses the request when the choice cannot be met. A hint
     * (the header or the preference of a key) is not refused: it is dropped, and the request is planned over all the
     * tiers, with the response saying that the selector was ignored. The hints that are not dropped for want of an
     * account are those of a pool whose accounts are all switched off ({@link #hasSwitchedOffMember}) and of one whose
     * relay broke off an answer a moment ago: the client asked for that pool alone, and it is the pool that cannot serve
     * for now, so the request is kept to it and answered with SOURCE_TIER_UNAVAILABLE, as it is when the accounts are only
     * busy, and not served by another pool at another price.
     *
     * <p>Where no tier has anything to serve with, and a relay was left out for a feature of the request that the native
     * accounts of the route carry, or for the length of its prompt, the request is refused for that ({@link #servable}),
     * whether it asked for the tier by the suffix or by a hint that was dropped, or for none. Where the only thing missing
     * is a relay that broke off an answer a moment ago, nothing is refused: the tier can serve again shortly, so a request
     * that named it by the suffix keeps it and is answered with SOURCE_TIER_UNAVAILABLE, which a client retries, and not with
     * the SOURCE_TIER_NOT_OFFERED of a tier that has no account for it at all.
     */
    private Mono<Resolved> resolve(
        TierPlanRequest request,
        SourceTierSnapshot snapshot,
        VendorConfig config,
        String vendor,
        String modelKey,
        List<PoolCell> units,
        XpEligibility.Found xp,
        Choice choice,
        List<Tier> enabled
    ) {
        return Mono.defer(() -> {
            Resolved met;
            try {
                met = meet(request, snapshot, config, vendor, modelKey, units, xp, choice);
            } catch (ApiException refused) {
                if (!choice.yields()) {
                    throw refused;
                }
                return Mono.just(servable(
                    meet(request, snapshot, config, vendor, modelKey, units, xp, new Choice(enabled, null, true, false)), xp));
            }
            List<Tier> memberless = met.availability().memberless();
            if (memberless.isEmpty()) {
                return Mono.just(met);
            }
            if (!choice.pinned()) {
                return Mono.just(servable(met, xp));
            }
            if (!choice.yields()) {
                servable(met, xp);
                if (xp.temporarilyOut(memberless)) {
                    return Mono.just(met);
                }
                throw notOffered(choice);
            }
            if (xp.temporarilyOut(memberless)) {
                // A relay that broke off an answer a moment ago is the tier being unable to serve for now, as one that is
                // switched off is: the hint keeps the request to it, as the suffix does, and is not served at another price.
                return Mono.just(met);
            }
            return hasSwitchedOffMember(request, memberless, units, xp, snapshot, vendor)
                .map(switchedOff -> switchedOff
                    ? met
                    : servable(meet(request, snapshot, config, vendor, modelKey, units, xp, new Choice(enabled, null, true, false)),
                        xp));
        });
    }

    /**
     * Refuses a request that has nowhere to go because of what it asks for (design 3, step 6): none of the tiers it may use
     * has an account or a relay for it, and a relay of them was left out for a feature that is beyond it, or because the
     * prompt is longer than it takes. The client is told the feature, which is its own to take out, or what a relay that
     * refuses the prompt at dispatch is answered with (design 7.8: "prompt is too long", which clients such as Claude Code
     * shorten the conversation for), the same on the first request and on every later one, whether the window is the one the
     * administrator wrote or the one a refusal showed. It is not told about the relays.
     *
     * <p>Nothing is refused while a relay is only out for a moment ({@link XpSkip.Reason#temporary}): the request may be
     * served by it shortly, and what is refused for good is not a request that would be.
     */
    private static Resolved servable(Resolved resolved, XpEligibility.Found xp) {
        List<Tier> memberless = resolved.availability().memberless();
        if (memberless.isEmpty() || xp.temporarilyOut(memberless)) {
            return resolved;
        }
        String feature = xp.hardFeature(memberless);
        if (feature != null) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "SOURCE_TIER_FEATURE_UNSUPPORTED",
                "The request uses " + feature + ", which no source of the tiers it may be served from supports",
                Map.of("feature", feature)
            );
        }
        if (xp.contextRefused(memberless)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UPSTREAM_ERROR", RelayErrorException.PROMPT_TOO_LONG);
        }
        return resolved;
    }

    private Resolved meet(
        TierPlanRequest request,
        SourceTierSnapshot snapshot,
        VendorConfig config,
        String vendor,
        String modelKey,
        List<PoolCell> units,
        XpEligibility.Found xp,
        Choice choice
    ) {
        Tier ownerTier = ownerTier(request, snapshot, vendor, units, choice);
        TierPlan.Mode mode = choice.pinned() ? TierPlan.Mode.PINNED : TierPlan.Mode.AUTO;
        TierPlan.Session session = session(request, config, mode, vendor, modelKey);
        TierSessionBindings.Binding bound = session == null || !session.keepsTier()
            ? null
            : bindings.find(session.bindingKey()).orElse(null);
        Availability availability;
        if (ownerTier != null && request.responseMovable()) {
            // The owner of a chain that can be replayed anywhere is a preference, and it is the preference of the walk the
            // request takes (design 4.7): an automatic request starts from the tier its session is bound to, or from the
            // best one, and a request pinned to "tier+" from that tier (a strict pin has that tier alone). The owner is
            // tried first only when it sits in the tier the walk starts from. An owner anywhere else is a lower tier's
            // doing (a D2 fall inside a "+" range, a strict pin to a lower tier, or what is left of a fall the session has
            // been idle for too long to keep); keeping it would hold the chain in that tier, and pay its factor, for as
            // long as the chain lasts, whatever the named or the best tier could serve. A chain that cannot leave its
            // owner keeps it.
            Availability walked = availability(choice.candidates(), null, false, units, xp, snapshot, vendor);
            if (ownerTier.tierId().equals(startTier(walked.serving(), snapshot, vendor, bound).tierId())) {
                availability = availability(choice.candidates(), ownerTier, false, units, xp, snapshot, vendor);
            } else {
                ownerTier = null;
                availability = walked;
            }
        } else {
            availability = availability(choice.candidates(), ownerTier, !request.responseMovable(), units, xp, snapshot, vendor);
        }
        return new Resolved(choice, session, bound, ownerTier, availability);
    }

    /** The place of the tier a session is bound to in the order the tiers have now; the place it was bound at if it is gone. */
    private static int levelOf(SourceTierSnapshot snapshot, String vendor, TierSessionBindings.Binding bound) {
        return snapshot.tier(vendor, bound.tierId()).map(Tier::level).orElse(bound.level());
    }

    /**
     * A client that names a tier by the suffix gets it or an answer: when the tiers it named hold no account of any kind
     * this route can use (as opposed to accounts that cannot serve right now, which is for the selection to find, and
     * answered with SOURCE_TIER_UNAVAILABLE), there is nothing to wait for. Accounts that are disabled or suspended are
     * in no cache, so a tier whose accounts are all of them is of the first kind for the suffix.
     */
    private static ApiException notOffered(Choice choice) {
        return new ApiException(
            HttpStatus.BAD_REQUEST,
            "SOURCE_TIER_NOT_OFFERED",
            "The tier \"" + choice.named().tierId() + "\" has no account that serves this model on this endpoint"
        );
    }

    /**
     * Whether one of the tiers has an account of the route's cells that is switched off, disabled or suspended (a
     * suspended account is disabled with its suspension). Such an account is in no account cache, so a tier made of them
     * looks like a tier the route has nothing in; it is not, it is a tier that cannot serve for as long as they are out
     * of service. Only a request that pinned the tier by a hint needs to tell the two apart, and only when the cache has
     * nothing of the tier, so the table is read rarely (and the read is kept as long as the cache is).
     */
    private Mono<Boolean> hasSwitchedOffMember(
        TierPlanRequest request,
        List<Tier> tiers,
        List<PoolCell> units,
        XpEligibility.Found xp,
        SourceTierSnapshot snapshot,
        String vendor
    ) {
        Mono<List<ProxyAccountEntity>> read = accountPool.switchedOffAccounts();
        if (read == null) {
            return Mono.just(false);
        }
        Set<String> ids = tiers.stream().map(Tier::tierId).collect(Collectors.toSet());
        return read
            .map(switchedOff -> switchedOff.stream()
                .filter(account -> units.stream().anyMatch(cell -> cell.predicate().test(account)))
                .anyMatch(account -> TierMembership.nativeTierOf(snapshot, vendor, account)
                    .filter(tier -> ids.contains(tier.tierId()))
                    .isPresent())
                // A relay of those tiers that is switched off is out of service in the same way, provided it is one this
                // request would be given if it were back: the pair is open to the key, the model is mapped and verified for it,
                // and the request carries nothing it cannot. One that could not serve it anyway (the small model of Claude
                // Code that the relay has no mapping for, a web search) is no reason to keep the request to a tier that has
                // nothing for it: that request is served in the order of the tiers, as it is when the relay is in service.
                || (xp.open() && switchedOff.stream()
                    .filter(account -> PoolCell.XP_OPENAI_CHAT.predicate().test(account))
                    .anyMatch(account -> snapshot.member(vendor, account.getAccountId())
                        .filter(member -> member.crossProtocol() && ids.contains(member.tierId()))
                        .filter(member -> relays.couldServeWhenBack(request, snapshot, vendor, units, member, account))
                        .isPresent())))
            .defaultIfEmpty(false);
    }

    /**
     * Which of the vendor's tiers the request may be served from, as far as the client has a say (design 6.2 and 6.3).
     *
     * @param candidates the enabled tiers the request may use, best first: all of them for an automatic request
     * @param named the tier the client named, or null for an automatic request
     * @param ignored whether the client named a tier that could not be used (a header or a preference), so that the
     *     request is automatic and the response says so
     * @param yields whether the tier named comes from a hint (the header or the preference of the key), which gives way
     *     to what the route and the response chain allow instead of being refused for it
     */
    private record Choice(List<Tier> candidates, Tier named, boolean ignored, boolean yields) {

        boolean pinned() {
            return named != null;
        }

        boolean allows(Tier tier) {
            return candidates.stream().anyMatch(candidate -> candidate.tierId().equals(tier.tierId()));
        }
    }

    /**
     * The tier a selector names, if it names one that can be used. The suffix is the client's explicit demand, so a
     * name that cannot be used is an error; the header and the preference are hints and are dropped (6.3).
     */
    private static Choice choose(Selector selector, SourceTierSnapshot snapshot, String vendor, List<Tier> enabled) {
        if (selector == null || selector.auto()) {
            return new Choice(enabled, null, false, false);
        }
        boolean demanded = selector.source() == TierSelectorParser.Source.SUFFIX;
        Tier named = selector.kind() == TierSelectorParser.Kind.INVALID
            ? null
            : snapshot.tiers(vendor).stream().filter(tier -> names(selector, tier)).findFirst().orElse(null);
        if (named == null) {
            return refuse(demanded, enabled, HttpStatus.BAD_REQUEST, "SOURCE_TIER_UNKNOWN",
                "The tier \"" + clip(selector.text()) + "\" does not exist");
        }
        if (!named.enabled()) {
            return refuse(demanded, enabled, HttpStatus.BAD_REQUEST, "SOURCE_TIER_NOT_OFFERED",
                "The tier \"" + named.tierId() + "\" is not offered at the moment");
        }
        if (!named.userSelectable()) {
            return refuse(demanded, enabled, HttpStatus.FORBIDDEN, "SOURCE_TIER_NOT_SELECTABLE",
                "The tier \"" + named.tierId() + "\" cannot be chosen by clients");
        }
        List<Tier> candidates = selector.orBelow()
            ? enabled.stream().filter(tier -> tier.level() >= named.level()).toList()
            : List.of(named);
        return new Choice(candidates, named, false, !demanded);
    }

    private static Choice refuse(boolean demanded, List<Tier> enabled, HttpStatus status, String code, String message) {
        if (demanded) {
            throw new ApiException(status, code, message);
        }
        return new Choice(enabled, null, true, false);
    }

    private static boolean names(Selector selector, Tier tier) {
        return switch (selector.kind()) {
            case TIER -> tier.tierId().equals(selector.tierId());
            case LEVEL -> tier.level() == selector.level();
            case AUTO, INVALID -> false;
        };
    }

    /** A reference as long as a client may send is not worth echoing whole. */
    private static String clip(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }

    /**
     * The kinds of account the route sends a request to, in the order it tries them: the cells of the
     * {@code select*ForPool} chain that {@code ProxyFacade.selectAccount} walks for the same facts, and nothing else.
     * Codex with isolation off is the one route that tries a second cell, which also holds the Codex tokens again.
     */
    public static List<PoolCell> nativeUnits(TierPlanRequest request) {
        if (request.preferDeepSeekAccounts()) {
            return List.of(PoolCell.DEEPSEEK);
        }
        if (request.route() == TierRoute.OPENAI && request.openAiCompatibleModel()) {
            return List.of(PoolCell.OPENAI_COMPATIBLE);
        }
        if (request.preferCodexAccounts()) {
            return request.isolateCodexPool()
                ? List.of(PoolCell.CODEX)
                : List.of(PoolCell.CODEX, PoolCell.OFFICIAL_OR_CODEX);
        }
        return switch (request.route()) {
            case OPENAI -> request.isolateCodexPool()
                ? List.of(PoolCell.OFFICIAL_OPENAI)
                : List.of(PoolCell.OFFICIAL_OR_CODEX);
            case ANTHROPIC -> List.of(PoolCell.ANTHROPIC_KEY, PoolCell.KIRO_COMPATIBLE);
            case KIRO -> List.of(PoolCell.KIRO_COMPATIBLE);
        };
    }

    /** The model's price before the tier factor: what the same request costs without tiers. Empty when nothing is charged. */
    private Mono<Optional<BillingQuote>> standardPrice(TierPlanRequest request) {
        if (!request.billed()) {
            return Mono.just(Optional.empty());
        }
        return paymentSettings.config()
            .flatMap(config -> modelPricing.billingQuote(request.requestedModel(), request.fastMode(), config.apiBillingMultiplier()))
            .map(Optional::of)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("No price was resolved for " + request.requestedModel())));
    }

    private TierPlan build(
        TierPlanRequest request,
        SourceTierSnapshot snapshot,
        VendorConfig config,
        String vendor,
        String modelKey,
        List<PoolCell> units,
        XpEligibility.Found xp,
        Resolved resolved,
        BillingQuote standardPrice,
        boolean unified
    ) {
        Choice choice = resolved.choice();
        Availability availability = resolved.availability();
        Tier ownerTier = resolved.ownerTier();
        TierPlan.Session session = resolved.session();
        TierSessionBindings.Binding bound = resolved.bound();
        long nativeInputBound = inputBound(request.upstreamRequest(), units);
        long outputBound = RequestBounds.outputTokens(request.upstreamRequest());
        TierPlan.Mode mode = choice.pinned() ? TierPlan.Mode.PINNED : TierPlan.Mode.AUTO;
        NativeReach reach = nativesInReach(snapshot, vendor, units, xp, choice);

        List<Level> levels = new ArrayList<>();
        for (Tier tier : availability.serving()) {
            // One multiplication per tier, exact and rounded only when a cost or an estimate is taken from it.
            BillingQuote quote = standardPrice == null ? null : standardPrice.scaled(tier.priceFactor());
            List<TierPlan.Relay> relays = xp.relays(tier.tierId());
            // The reservation of a tier covers the dearest thing it can be sent: what the native cells are, and what each
            // relay is, since a relay is sent its own body and counts an image as its mapping says. A tier that the
            // configuration cannot put a native account in is sent the relays' body alone, so an inline image on a route that
            // passes it through (which lifts the native bound to the floor for hidden input) does not size its reservation;
            // that is the configuration of the plan, and no account the cache reloads can change it. It is the tier's
            // configuration, not this request's relays: a tier of relays that none can serve for now (one that broke off an
            // answer a moment ago) is kept for a request pinned to it, which is answered with a retry, and is reserved for
            // no more than the relays would have been. That is for a request the relays of the vendor take part in; where none
            // does (an entry that is not read for them, a gate that is shut to the key) a tier is reserved for as it always was.
            boolean natives = !xp.open() || !snapshot.hasRelayMembers(vendor, tier.tierId())
                || snapshot.canHoldNativeAccounts(vendor, tier.tierId())
                || (ownerTier != null && ownerTier.tierId().equals(tier.tierId()));
            long inputBound = natives ? nativeInputBound : 0L;
            for (TierPlan.Relay relay : relays) {
                inputBound = Math.max(inputBound, relay.inputBound());
            }
            levels.add(new Level(
                tier,
                vendor + ":" + tier.tierId(),
                relays.isEmpty() ? units : withRelayCell(units),
                inputBound,
                quote,
                quote == null ? null : quote.estimate(inputBound, outputBound),
                TierResponseHeaders.served(tier, mode, choice.ignored()),
                relays
            ));
        }
        TierPlan.Owner owner = ownerTier == null
            ? null
            : levels.stream()
                .filter(level -> level.tierId().equals(ownerTier.tierId()))
                .findFirst()
                .map(level -> new TierPlan.Owner(request.responseOwnerAccountId(), level))
                .orElseThrow();
        // Where the session logically starts: the tier it is bound to, or the best one. A tier it would have been served
        // from that has nothing available is only one it can be pushed out of from there down.
        int origin = bound == null ? 0 : levelOf(snapshot, vendor, bound);
        int start = startIndex(availability.serving(), bound, origin);
        List<Level> hold = holdLevels(levels, start);
        // The tiers where a relay sat this request out for its own sake: whatever the rest of the tier did, serving it from
        // below is not a fall of the session (design 4.7). Those above the tier the session is bound to are not its concern.
        List<Tier> requestBound = xp.requestBoundTiers().stream()
            .map(tierId -> snapshot.tier(vendor, tierId))
            .flatMap(Optional::stream)
            .filter(tier -> tier.level() >= origin)
            .toList();
        return new TierPlan(
            vendor,
            modelKey,
            mode,
            snapshot,
            config,
            levels,
            hold,
            availability.unavailable().stream().filter(tier -> tier.level() >= origin).toList(),
            availability.memberless().stream().map(Tier::tierId).collect(Collectors.toSet()),
            start,
            session,
            owner,
            outputBound,
            standardPrice,
            // No circuit epoch: the model-family circuit of the old pool policy only ever applies to untiered vendors.
            new AccountPoolRequestContext(
                request.requestId(),
                request.requestedModel(),
                request.upstreamModel(),
                unified ? AccountPlatforms.ANTHROPIC_PROVIDER : request.providerHint(),
                null,
                null
            ),
            choice.pinned() ? choice.candidates().stream().map(Tier::tierId).collect(Collectors.toSet()) : Set.of(),
            choice.pinned() ? null : lowBalanceHint(config, request, levels, hold),
            unified,
            xp.skips(),
            xp.open(),
            xp.session(),
            requestBound,
            reach.afterLengthRefusal(),
            reach.afterOtherRefusal()
        );
    }

    /**
     * Whether an account of the route's native cells sits in a tier this request could be served from if no relay took it,
     * whatever state it is in (busy, out of quota): a relay that refuses the request itself, with such an account in
     * reach, leaves the client with an error it can retry, and not with the refusal (see {@link TierPlan#retriesAfterRelayRefusal}).
     * The tiers in reach are those the client's choice allows. A hint is dropped once a relay's refusal of the length is
     * learned, and the retry is then served in the order of all the tiers, so for that refusal all of them count; any other
     * refusal is not learned, the hint stays, and so do the tiers it names. Only a request a relay takes part in needs to know.
     */
    private NativeReach nativesInReach(
        SourceTierSnapshot snapshot,
        String vendor,
        List<PoolCell> units,
        XpEligibility.Found xp,
        Choice choice
    ) {
        if (xp.byTier().isEmpty()) {
            return new NativeReach(false, false);
        }
        Predicate<ProxyAccountEntity> onRoute = account -> units.stream().anyMatch(cell -> cell.predicate().test(account));
        Set<String> nativeTiers = accountPool.cachedAccounts(onRoute).stream()
            .map(account -> TierMembership.nativeTierOf(snapshot, vendor, account))
            .flatMap(Optional::stream)
            .map(Tier::tierId)
            .collect(Collectors.toSet());
        boolean inChoice = choice.candidates().stream().anyMatch(tier -> nativeTiers.contains(tier.tierId()));
        boolean afterLength = choice.pinned() && choice.yields()
            ? snapshot.tiers(vendor).stream().filter(Tier::enabled).anyMatch(tier -> nativeTiers.contains(tier.tierId()))
            : inChoice;
        return new NativeReach(afterLength, inChoice);
    }

    /** Whether natives are in reach of a request whose relay refused the length of its prompt, and of one whose relay refused it otherwise. */
    private record NativeReach(boolean afterLengthRefusal, boolean afterOtherRefusal) {
    }

    /** The cells of a tier that has relays: the route's, and the relays' after them (design 4.3, "native first"). */
    private static List<PoolCell> withRelayCell(List<PoolCell> units) {
        List<PoolCell> cells = new ArrayList<>(units);
        cells.add(PoolCell.XP_OPENAI_CHAT);
        return cells;
    }

    /**
     * What to tell a user who cannot pay the reservation of an automatic request, which is the dearest of the tiers it
     * may be served from: the tiers a client may name that would have reserved less, and how to name them. The user can
     * then pin one of them, which reserves for that tier alone. A vendor that keeps its pool names to itself
     * ({@code publicListing} off) gets a sentence that names none, and no pointer to the key's settings, where the
     * choice is not offered either. Nor does a key that cannot hold a preference (an old key not tied to one provider),
     * whose settings have no such choice and whose preference is never read.
     *
     * <p>The {@code model@tier} spelling is the one the suffix is taken off for (see {@link #takesSuffix}): the model as
     * the request wrote it, on the vendor that serves it, which is the vendor of this plan. It is not for an id that a
     * mapping rule of the key is written around (a pattern such as {@code claude-sonnet-4@*} takes the spelling whole),
     * and then the tiers are named by their ids, for the header and the preference of the key to be given.
     *
     * @return null when no such tier exists, when the request is not billed, or when the release cannot select tiers
     */
    private String lowBalanceHint(VendorConfig config, TierPlanRequest request, List<Level> levels, List<Level> hold) {
        BigDecimal reserved = hold.stream()
            .map(Level::estimate)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
        if (reserved == null || !selectors) {
            return null;
        }
        List<Level> cheaper = levels.stream()
            .filter(level -> level.tier().userSelectable() && level.estimate() != null && level.estimate().compareTo(reserved) < 0)
            .toList();
        if (cheaper.isEmpty()) {
            return null;
        }
        if (!config.publicListing()) {
            return "；预估费用按可能服务本次请求的最贵号池计算，实际按服务它的号池计价";
        }
        // The suffix is advised only when every spelling of it would be read as one: a user who follows the advice must
        // get a request that is pinned, not the same automatic one again.
        boolean suffix = cheaper.stream().allMatch(level -> request.takesSuffix(request.requestedModel() + "@" + level.tierId()));
        String header = "设置请求头 " + TierSelectorParser.REQUEST_HEADER;
        String ways = suffix
            ? request.keyHoldsPreference()
                ? "在模型名后加后缀，" + header + "，或在 API Key 的设置里选择号源偏好"
                : "在模型名后加后缀，或" + header
            : request.keyHoldsPreference()
                ? header + "，或在 API Key 的设置里选择号源偏好"
                : header;
        return "；可以改选更便宜的号池（" + ways + "）："
            + cheaper.stream()
                .map(level -> (suffix ? request.requestedModel() + "@" + level.tierId() : level.tierId()) + "（系数 "
                    + level.factor().stripTrailingZeros().toPlainString() + "）")
                .collect(Collectors.joining("、"));
    }

    /**
     * The session the request belongs to (design 4.7), if there is one: the vendor keeps sessions in the tier they were
     * pushed down to and the deployment keeps sessions at all. The request must also give a name to the session or have
     * a beginning to tell it by. Only an automatic request reads and keeps the tier of its session; one that named its
     * tier is still part of the session, which counts its dispatches and follows a response chain, but leaves the tier
     * it is bound to alone.
     */
    private TierPlan.Session session(
        TierPlanRequest request,
        VendorConfig config,
        TierPlan.Mode mode,
        String vendor,
        String modelKey
    ) {
        if (!config.stickySessions() || !bindings.enabled()) {
            return null;
        }
        return TierSessionKeys.resolve(request.keyId(), modelKey, request.sessionName(), request.upstreamRequest())
            .map(key -> new TierPlan.Session(key, TierSessionKeys.bindingKey(vendor, modelKey, key), mode == TierPlan.Mode.AUTO))
            .orElse(null);
    }

    /**
     * Where the request starts: the tier its session is bound to, found by its id so that a reordering of the tiers
     * is followed. A bound tier that is not in this request's plan (it has nothing to serve with right now) is
     * replaced by the first tier that is not above it; if every tier left is above it, the request starts from the best.
     *
     * @param tiers the tiers of the plan, best first
     * @param origin the place of the bound tier in the tiers as they are now
     */
    private static int startIndex(List<Tier> tiers, TierSessionBindings.Binding bound, int origin) {
        if (bound == null) {
            return 0;
        }
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).tierId().equals(bound.tierId())) {
                return i;
            }
        }
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).level() >= origin) {
                return i;
            }
        }
        return 0;
    }

    /** The tier the walk of an automatic request starts from, among the tiers of its plan. */
    private static Tier startTier(List<Tier> tiers, SourceTierSnapshot snapshot, String vendor, TierSessionBindings.Binding bound) {
        return tiers.get(startIndex(tiers, bound, bound == null ? 0 : levelOf(snapshot, vendor, bound)));
    }

    /**
     * The tiers the reservation covers (design 5.2): all of them for a request that starts from the best. From a lower
     * start, that tier and the ones below it, and of the tiers above, those that would not reserve more. The owner of a
     * response chain needs no cover of its own: one that can be replayed elsewhere is tried first only when it sits in
     * the tier the walk starts from (an owner anywhere else is left out of the plan, see {@link #meet}), and one that
     * cannot be left is the only tier.
     */
    private static List<Level> holdLevels(List<Level> levels, int start) {
        if (start == 0) {
            return levels;
        }
        BigDecimal ceiling = levels.subList(start, levels.size()).stream()
            .map(Level::estimate)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
        List<Level> held = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            Level level = levels.get(i);
            boolean notDearer = level.estimate() == null || ceiling == null || level.estimate().compareTo(ceiling) <= 0;
            if (i >= start || notDearer) {
                held.add(level);
            }
        }
        return held;
    }

    /**
     * The tiers with an account that could serve the request right now: enabled, of one of the route's cells, placed in
     * the tier and available (not out of quota). A tier without one is left out, so the reservation is
     * not sized by a tier that cannot serve. The account cache is up to 30 seconds old, and a tier that recovers in
     * the meantime is simply not used by this request. The tier of a response owner stays whatever its accounts are.
     *
     * <p>When no tier has such an account the tiers that have accounts of the route's cells at all stay (they are
     * out of quota, and the request fails to find one as an untiered one would, or finds one the cache
     * reload or the retest brought back), but not a tier without any: no account of it can serve this request, so it
     * would only enlarge the reservation, and a lower tier serving the request would be taken for having pushed the
     * session out of it. If there is no tier with an account at all, every tier stays, since a plan needs one, and each
     * is marked as having none ({@link TierPlan#memberless}): whichever tier a reload of the cache then finds an
     * account in, no tier above it was one the session could have been pushed out of.
     *
     * <p>A tier that is left out has either accounts of the route's cells, all of them unavailable, or none. The
     * first is a tier the request found with nothing to offer, as the walk would have, and the plan says so
     * ({@link TierPlan#unavailableTiers}); the second is a tier this request cannot be served from at all. One walk of
     * the route's accounts tells which, placing each account in its tier once. An account that is disabled is not in
     * the cache, and a suspended one is disabled with its suspension ({@code AccountPoolService.suspend}), so a tier
     * whose accounts are all disabled or suspended is of the second kind: a request that named it by the suffix gets
     * SOURCE_TIER_NOT_OFFERED, not SOURCE_TIER_UNAVAILABLE, and a session it would have served is not pushed down by it.
     *
     * <p>A response chain that cannot leave its owner ({@code ownerOnly}) is served by the owner's tier and by no other:
     * the owner is tried first, nothing else may serve the chain, and the retries stop, so no other tier is planned, held
     * for, or offered as a cheaper way. The tiers it does not use are not ones the session was pushed out of either.
     *
     * <p>The relays that may serve the request count with the native accounts: a tier with such a relay has an account of
     * the route (cached and enabled), and is available when one of them is.
     */
    private Availability availability(
        List<Tier> tiers,
        Tier ownerTier,
        boolean ownerOnly,
        List<PoolCell> units,
        XpEligibility.Found xp,
        SourceTierSnapshot snapshot,
        String vendor
    ) {
        if (ownerTier != null && ownerOnly) {
            return new Availability(List.of(ownerTier), List.of(), List.of());
        }
        Predicate<ProxyAccountEntity> onRoute = account -> units.stream().anyMatch(cell -> cell.predicate().test(account));
        Set<String> placed = new HashSet<>();
        Set<String> available = new HashSet<>();
        for (ProxyAccountEntity account : accountPool.cachedAccounts(onRoute)) {
            TierMembership.nativeTierOf(snapshot, vendor, account).ifPresent(tier -> {
                placed.add(tier.tierId());
                if (accountPool.isUsable(account)) {
                    available.add(tier.tierId());
                }
            });
        }
        // A relay that may serve the request places its tier like a native account does. One that was left out does not: a
        // tier made of such relays has nothing to serve this request with, which is the structural kind of empty.
        xp.byTier().forEach((tierId, relays) -> {
            placed.add(tierId);
            if (relays.stream().anyMatch(eligible -> accountPool.isUsable(eligible.account()))) {
                available.add(tierId);
            }
        });
        List<Tier> serving = tiers.stream()
            .filter(tier -> (ownerTier != null && tier.tierId().equals(ownerTier.tierId())) || available.contains(tier.tierId()))
            .toList();
        if (serving.isEmpty()) {
            List<Tier> withAccounts = tiers.stream().filter(tier -> placed.contains(tier.tierId())).toList();
            return withAccounts.isEmpty()
                ? new Availability(tiers, List.of(), tiers)
                : new Availability(withAccounts, List.of(), List.of());
        }
        // A tier that has a relay sat out for this request is not one the request found with nothing to offer, even when
        // the members that did take part cannot serve now (design 4.2: only the layers 1 to 4 count; 4.7).
        Set<String> requestBound = xp.requestBoundTiers();
        return new Availability(
            serving,
            tiers.stream()
                .filter(tier -> !serving.contains(tier) && placed.contains(tier.tierId()) && !requestBound.contains(tier.tierId()))
                .toList(),
            List.of()
        );
    }

    /**
     * The tiers that stay in the plan, those left out for having accounts of the route that are all unavailable, and
     * those that stay although none has an account of the route at all.
     */
    private record Availability(List<Tier> serving, List<Tier> unavailable, List<Tier> memberless) {
    }

    /**
     * The tier of the account that owns the response chain, if the plan can send the request to it: it must be an
     * enabled account of one of the route's cells and sit in an enabled tier that the client's selector allows.
     * Otherwise a chain that can be replayed on any account (its history is held here) simply has no owner to prefer,
     * and one that cannot is refused before a reservation, since no tier of this plan could serve it.
     */
    private Tier ownerTier(
        TierPlanRequest request,
        SourceTierSnapshot snapshot,
        String vendor,
        List<PoolCell> units,
        Choice choice
    ) {
        String ownerId = request.responseOwnerAccountId();
        if (ownerId == null || request.route() != TierRoute.OPENAI) {
            return null;
        }
        Tier tier = accountPool.cachedAccount(ownerId)
            .filter(account -> units.stream().anyMatch(cell -> cell.predicate().test(account)))
            .flatMap(account -> TierMembership.nativeTierOf(snapshot, vendor, account))
            .filter(Tier::enabled)
            .filter(choice::allows)
            .orElse(null);
        if (tier == null && !request.responseMovable()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "RESPONSE_OWNER_TIER_MISMATCH",
                choice.pinned()
                    ? "The account that owns the previous response is not part of the tier you chose"
                    : "The account that owns the previous response is not part of the tiers that serve this request"
            );
        }
        return tier;
    }

    /**
     * The most input tokens any cell of the route can be sent. A cell that is sent the whole request is bounded by the
     * request and by the floor for hidden input; a Kiro cell only ever sees text, which is never more than that, so
     * it only decides the bound when it is the route's sole cell.
     */
    private static long inputBound(JsonNode body, List<PoolCell> units) {
        return units.stream().anyMatch(cell -> cell != PoolCell.KIRO_COMPATIBLE)
            ? RequestBounds.nativeInputTokens(body)
            : RequestBounds.kiroInputTokens(body);
    }

    /**
     * The model as tiers see it. Aliases the catalog maps to a model of the same vendor (the Claude Code
     * {@code sonnet} to the default Sonnet) share that model's key; everything else is its own lower-case id without the
     * prefixes the routes strip.
     */
    private String modelKey(String vendor, String upstreamModel) {
        String id = normalize(upstreamModel);
        return modelCatalog.targetFor(id)
            .map(DefaultSourceTierPlanner::normalize)
            .filter(target -> !target.equals(id))
            .filter(target -> vendor.equals(keyResolver.resolve(target, target, null).provider()))
            .orElse(id);
    }

    static String normalize(String model) {
        String id = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : STRIPPED_PREFIXES) {
                if (id.startsWith(prefix)) {
                    id = id.substring(prefix.length());
                    stripped = true;
                }
            }
        }
        return id.endsWith("-fast") ? id.substring(0, id.length() - "-fast".length()) : id;
    }
}
