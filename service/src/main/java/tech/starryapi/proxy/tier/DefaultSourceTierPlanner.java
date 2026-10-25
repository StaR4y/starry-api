package tech.starryapi.proxy.tier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        this.registry = Objects.requireNonNull(registry, "registry");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.accountPool = Objects.requireNonNull(accountPool, "accountPool");
        this.paymentSettings = Objects.requireNonNull(paymentSettings, "paymentSettings");
        this.modelPricing = Objects.requireNonNull(modelPricing, "modelPricing");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.selectors = selectors;
        this.unifyEndpoints = unifyEndpoints;
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
            // A chain no tier can continue is refused here, before the price of the model is even looked up.
            return resolve(routed, snapshot, config, vendor, modelKey, units, chosen, tiers)
                .flatMap(resolved -> standardPrice(routed)
                    .map(price -> build(routed, snapshot, config, vendor, modelKey, units, resolved, price.orElse(null), unified)));
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
     * tiers, with the response saying that the selector was ignored. The one hint that is not dropped for want of an
     * account is a pool whose accounts are all switched off ({@link #hasSwitchedOffMember}): the client asked for that
     * pool alone, and it is the pool that cannot serve for now, so the request is kept to it and answered with
     * SOURCE_TIER_UNAVAILABLE, as it is when the accounts are only busy, and not served by another pool at another price.
     */
    private Mono<Resolved> resolve(
        TierPlanRequest request,
        SourceTierSnapshot snapshot,
        VendorConfig config,
        String vendor,
        String modelKey,
        List<PoolCell> units,
        Choice choice,
        List<Tier> enabled
    ) {
        return Mono.defer(() -> {
            Resolved met;
            try {
                met = meet(request, snapshot, config, vendor, modelKey, units, choice);
            } catch (ApiException refused) {
                if (!choice.yields()) {
                    throw refused;
                }
                return Mono.just(meet(request, snapshot, config, vendor, modelKey, units, new Choice(enabled, null, true, false)));
            }
            List<Tier> memberless = met.availability().memberless();
            if (!choice.pinned() || memberless.isEmpty()) {
                return Mono.just(met);
            }
            if (!choice.yields()) {
                throw notOffered(choice);
            }
            return hasSwitchedOffMember(memberless, units, snapshot, vendor)
                .map(switchedOff -> switchedOff
                    ? met
                    : meet(request, snapshot, config, vendor, modelKey, units, new Choice(enabled, null, true, false)));
        });
    }

    private Resolved meet(
        TierPlanRequest request,
        SourceTierSnapshot snapshot,
        VendorConfig config,
        String vendor,
        String modelKey,
        List<PoolCell> units,
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
            Availability walked = availability(choice.candidates(), null, false, units, snapshot, vendor);
            if (ownerTier.tierId().equals(startTier(walked.serving(), snapshot, vendor, bound).tierId())) {
                availability = availability(choice.candidates(), ownerTier, false, units, snapshot, vendor);
            } else {
                ownerTier = null;
                availability = walked;
            }
        } else {
            availability = availability(choice.candidates(), ownerTier, !request.responseMovable(), units, snapshot, vendor);
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
    private Mono<Boolean> hasSwitchedOffMember(List<Tier> tiers, List<PoolCell> units, SourceTierSnapshot snapshot, String vendor) {
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
                    .isPresent()))
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
        Resolved resolved,
        BillingQuote standardPrice,
        boolean unified
    ) {
        Choice choice = resolved.choice();
        Availability availability = resolved.availability();
        Tier ownerTier = resolved.ownerTier();
        TierPlan.Session session = resolved.session();
        TierSessionBindings.Binding bound = resolved.bound();
        long inputBound = inputBound(request.upstreamRequest(), units);
        long outputBound = RequestBounds.outputTokens(request.upstreamRequest());
        TierPlan.Mode mode = choice.pinned() ? TierPlan.Mode.PINNED : TierPlan.Mode.AUTO;

        List<Level> levels = new ArrayList<>();
        for (Tier tier : availability.serving()) {
            // One multiplication per tier, exact and rounded only when a cost or an estimate is taken from it.
            BillingQuote quote = standardPrice == null ? null : standardPrice.scaled(tier.priceFactor());
            levels.add(new Level(
                tier,
                vendor + ":" + tier.tierId(),
                units,
                inputBound,
                quote,
                quote == null ? null : quote.estimate(inputBound, outputBound),
                TierResponseHeaders.served(tier, mode, choice.ignored())
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
            unified
        );
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
     */
    private Availability availability(
        List<Tier> tiers,
        Tier ownerTier,
        boolean ownerOnly,
        List<PoolCell> units,
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
        List<Tier> serving = tiers.stream()
            .filter(tier -> (ownerTier != null && tier.tierId().equals(ownerTier.tierId())) || available.contains(tier.tierId()))
            .toList();
        if (serving.isEmpty()) {
            List<Tier> withAccounts = tiers.stream().filter(tier -> placed.contains(tier.tierId())).toList();
            return withAccounts.isEmpty()
                ? new Availability(tiers, List.of(), tiers)
                : new Availability(withAccounts, List.of(), List.of());
        }
        return new Availability(
            serving,
            tiers.stream().filter(tier -> !serving.contains(tier) && placed.contains(tier.tierId())).toList(),
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

    private static String normalize(String model) {
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
