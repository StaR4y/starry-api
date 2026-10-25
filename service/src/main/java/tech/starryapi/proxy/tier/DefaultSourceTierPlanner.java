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
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
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
 */
public class DefaultSourceTierPlanner implements TierRuntime.Planner {

    /** The prefixes a route strips from a model id before it looks the model up. */
    private static final List<String> STRIPPED_PREFIXES = List.of("starry/", "anthropic-compatible/", "anthropic/");

    private final SourceTierRegistry registry;
    private final ModelPoolKeyResolver keyResolver;
    private final ModelCatalogProvider modelCatalog;
    private final AccountPoolService accountPool;
    private final PaymentSettingsService paymentSettings;
    private final ModelPricingService modelPricing;
    private final TierSessionBindings bindings;

    /**
     * @param bindings where the tier of each session is kept; {@link TierSessionBindings#disabled()} for a planner that
     *     does not keep sessions to their tier, which starts every request from the best one
     */
    public DefaultSourceTierPlanner(
        SourceTierRegistry registry,
        ModelPoolKeyResolver keyResolver,
        ModelCatalogProvider modelCatalog,
        AccountPoolService accountPool,
        PaymentSettingsService paymentSettings,
        ModelPricingService modelPricing,
        TierSessionBindings bindings
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.accountPool = Objects.requireNonNull(accountPool, "accountPool");
        this.paymentSettings = Objects.requireNonNull(paymentSettings, "paymentSettings");
        this.modelPricing = Objects.requireNonNull(modelPricing, "modelPricing");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    @Override
    public Mono<TierPlan> plan(TierPlanRequest request) {
        return Mono.defer(() -> {
            SourceTierSnapshot snapshot = registry.snapshot();
            String vendor = keyResolver.resolve(request.requestedModel(), request.upstreamModel(), request.providerHint())
                .provider();
            VendorConfig config = snapshot.vendor(vendor).filter(VendorConfig::enabled).orElse(null);
            List<Tier> tiers = config == null
                ? List.of()
                : snapshot.tiers(vendor).stream().filter(Tier::enabled).toList();
            if (tiers.isEmpty()) {
                return Mono.just(TierPlan.UNTIERED);
            }
            List<PoolCell> units = nativeUnits(request);
            // A chain no tier can continue is refused here, before the price of the model is even looked up.
            Tier ownerTier = ownerTier(request, snapshot, vendor, units);
            return standardPrice(request)
                .map(price -> build(request, snapshot, config, vendor, tiers, units, ownerTier, price.orElse(null)));
        });
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
        List<Tier> tiers,
        List<PoolCell> units,
        Tier ownerTier,
        BillingQuote standardPrice
    ) {
        long inputBound = inputBound(request.upstreamRequest(), units);
        long outputBound = RequestBounds.outputTokens(request.upstreamRequest());

        Availability availability = availability(tiers, ownerTier, units, snapshot, vendor);
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
                TierResponseHeaders.served(tier, TierPlan.Mode.AUTO)
            ));
        }
        TierPlan.Owner owner = ownerTier == null
            ? null
            : levels.stream()
                .filter(level -> level.tierId().equals(ownerTier.tierId()))
                .findFirst()
                .map(level -> new TierPlan.Owner(request.responseOwnerAccountId(), level))
                .orElseThrow();
        String modelKey = modelKey(vendor, request.upstreamModel());
        TierPlan.Mode mode = TierPlan.Mode.AUTO;
        TierPlan.Session session = session(request, config, mode, vendor, modelKey);
        TierSessionBindings.Binding bound = session == null ? null : bindings.find(session.bindingKey()).orElse(null);
        // Where the session logically starts: the tier it is bound to, or the best one. A tier it would have been served
        // from that has nothing available is only one it can be pushed out of from there down.
        int origin = bound == null ? 0 : snapshot.tier(vendor, bound.tierId()).map(Tier::level).orElse(bound.level());
        int start = startIndex(levels, bound, origin);
        return new TierPlan(
            vendor,
            modelKey,
            mode,
            snapshot,
            config,
            levels,
            holdLevels(levels, start, owner),
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
                request.providerHint(),
                null,
                null
            )
        );
    }

    /**
     * The session whose tier is kept (design 4.7), if there is one: the vendor keeps sessions in the tier they were
     * pushed down to, the tier was not named by the client, and the deployment keeps sessions at all. The request must
     * also give a name to the session or have a beginning to tell it by.
     */
    private TierPlan.Session session(
        TierPlanRequest request,
        VendorConfig config,
        TierPlan.Mode mode,
        String vendor,
        String modelKey
    ) {
        if (mode != TierPlan.Mode.AUTO || !config.stickySessions() || !bindings.enabled()) {
            return null;
        }
        return TierSessionKeys.resolve(request.keyId(), modelKey, request.sessionName(), request.upstreamRequest())
            .map(key -> new TierPlan.Session(key, TierSessionKeys.bindingKey(vendor, modelKey, key)))
            .orElse(null);
    }

    /**
     * Where the request starts: the tier its session is bound to, found by its id so that a reordering of the tiers
     * is followed. A bound tier that is not in this request's plan (it has nothing to serve with right now) is
     * replaced by the first tier that is not above it; if every tier left is above it, the request starts from the best.
     *
     * @param origin the place of the bound tier in the tiers as they are now
     */
    private int startIndex(List<Level> levels, TierSessionBindings.Binding bound, int origin) {
        if (bound == null) {
            return 0;
        }
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).tierId().equals(bound.tierId())) {
                return i;
            }
        }
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).level() >= origin) {
                return i;
            }
        }
        return 0;
    }

    /**
     * The tiers the reservation covers (design 5.2): all of them for a request that starts from the best. From a lower
     * start, that tier and the ones below it, and of the tiers above, those that would not reserve more; the owner of
     * the response chain stays covered whatever it would reserve, since it is tried first and billed at its tier.
     */
    private static List<Level> holdLevels(List<Level> levels, int start, TierPlan.Owner owner) {
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
            boolean ownerTier = owner != null && owner.level().tierRef().equals(level.tierRef());
            boolean notDearer = level.estimate() == null || ceiling == null || level.estimate().compareTo(ceiling) <= 0;
            if (i >= start || ownerTier || notDearer) {
                held.add(level);
            }
        }
        return held;
    }

    /**
     * The tiers with an account that could serve the request right now: enabled, of one of the route's cells, placed in
     * the tier and available (not suspended, not out of quota). A tier without one is left out, so the reservation is
     * not sized by a tier that cannot serve. The account cache is up to 30 seconds old, and a tier that recovers in
     * the meantime is simply not used by this request. The tier of a response owner stays whatever its accounts are.
     *
     * <p>When no tier has such an account the tiers that have accounts of the route's cells at all stay (they are
     * suspended or out of quota, and the request fails to find one as an untiered one would, or finds one the cache
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
     * the cache, so a tier whose accounts are all disabled is of the second kind.
     */
    private Availability availability(
        List<Tier> tiers,
        Tier ownerTier,
        List<PoolCell> units,
        SourceTierSnapshot snapshot,
        String vendor
    ) {
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
     * enabled account of one of the route's cells and sit in an enabled tier. Otherwise a chain that can be replayed on
     * any account (its history is held here) simply has no owner to prefer, and one that cannot is refused before
     * a reservation, since no tier of this plan could serve it.
     */
    private Tier ownerTier(TierPlanRequest request, SourceTierSnapshot snapshot, String vendor, List<PoolCell> units) {
        String ownerId = request.responseOwnerAccountId();
        if (ownerId == null || request.route() != TierRoute.OPENAI) {
            return null;
        }
        Tier tier = accountPool.cachedAccount(ownerId)
            .filter(account -> units.stream().anyMatch(cell -> cell.predicate().test(account)))
            .flatMap(account -> TierMembership.nativeTierOf(snapshot, vendor, account))
            .filter(Tier::enabled)
            .orElse(null);
        if (tier == null && !request.responseMovable()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "RESPONSE_OWNER_TIER_MISMATCH",
                "The account that owns the previous response is not part of the source tiers that serve this request"
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
