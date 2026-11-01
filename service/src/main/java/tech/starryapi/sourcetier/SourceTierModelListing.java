package tech.starryapi.sourcetier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.account.CodexOnlyModels;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.account.PoolCell;
import tech.starryapi.account.ProxyAccountEntity;
import tech.starryapi.model.ModelDtos;
import tech.starryapi.model.ModelTierListing;
import tech.starryapi.proxy.tier.TierMembership;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * Prices the public model list under source tiers (design 5.7). A vendor whose requests are planned over tiers lists a
 * model at the price of the first tier that has an account able to serve it, which is the tier a request is first sent
 * to; a model that only Codex accounts serve is listed at the first tier that has Codex accounts, so a tier of official
 * keys in front of it does not set its price. When the vendor lists its tiers publicly, every tier that holds such an
 * account is named next to the price, with its identifier, level, name and factor: nothing of the accounts, the
 * protocol or the relays behind it.
 *
 * <p>A deployment without tiers in use (no vendor switched on, or a release that cannot run them) gets
 * {@link ModelTierListing#NONE} without its accounts being looked at, and the list is the one it always was. The
 * configuration and the account cache are read once per list.
 */
@Component
public class SourceTierModelListing {

    private final SourceTierRegistry registry;
    private final SourceTierRuntimeInfo runtime;
    private final AccountPoolService accountPool;
    private final ModelPoolKeyResolver keyResolver;

    public SourceTierModelListing(
        SourceTierRegistry registry,
        SourceTierRuntimeInfo runtime,
        AccountPoolService accountPool,
        ModelPoolKeyResolver keyResolver
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.accountPool = Objects.requireNonNull(accountPool, "accountPool");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
    }

    /**
     * The listing for one answer. Before the configuration has loaded for the first time nothing is known about any
     * vendor; the list is then the standard one rather than a refusal, since listing a model is not charging for it and
     * the requests themselves are refused until the configuration is there.
     */
    public ModelTierListing current() {
        if (!runtime.nativeTiers() || !registry.loaded()) {
            return ModelTierListing.NONE;
        }
        SourceTierSnapshot snapshot = registry.snapshot();
        Map<String, List<Tier>> vendors = new LinkedHashMap<>();
        for (String vendor : snapshot.vendorIds()) {
            if (snapshot.vendor(vendor).filter(VendorConfig::enabled).isPresent()) {
                List<Tier> enabled = snapshot.tiers(vendor).stream().filter(Tier::enabled).toList();
                if (!enabled.isEmpty()) {
                    vendors.put(vendor, enabled);
                }
            }
        }
        if (vendors.isEmpty()) {
            return ModelTierListing.NONE;
        }
        return new Current(snapshot, vendors, accountPool.cachedAccounts())::of;
    }

    private final class Current {

        private final SourceTierSnapshot snapshot;
        private final Map<String, List<Tier>> vendors;
        private final List<ProxyAccountEntity> accounts;
        /** The accounts each tier holds, by vendor and tier; built when the vendor first has a model. */
        private final Map<String, Map<String, List<ProxyAccountEntity>>> held = new HashMap<>();
        /** The tiers that can serve a kind of model, by vendor and whether only Codex serves it. */
        private final Map<String, List<Tier>> serving = new HashMap<>();

        Current(SourceTierSnapshot snapshot, Map<String, List<Tier>> vendors, List<ProxyAccountEntity> accounts) {
            this.snapshot = snapshot;
            this.vendors = vendors;
            this.accounts = accounts;
        }

        Optional<ModelTierListing.Listing> of(ModelDtos.ModelListItem model) {
            String vendor = keyResolver.resolve(model.id(), model.id(), model.provider()).provider();
            List<Tier> tiers = vendors.get(vendor);
            if (tiers == null) {
                return Optional.empty();
            }
            boolean codexOnly = "openai".equals(vendor) && CodexOnlyModels.isCodexOnly(model.id());
            List<Tier> able = serving.computeIfAbsent(vendor + (codexOnly ? "|codex" : ""), key -> ableToServe(vendor, tiers, codexOnly));
            // A vendor none of whose tiers has an account yet is listed at its first tier, the one a request is planned from.
            BigDecimal headline = (able.isEmpty() ? tiers.getFirst() : able.getFirst()).priceFactor();
            boolean listed = snapshot.vendor(vendor).filter(VendorConfig::publicListing).isPresent();
            List<ModelTierListing.Tier> named = listed
                ? able.stream()
                    .map(tier -> new ModelTierListing.Tier(tier.tierId(), tier.level(), tier.name(), tier.priceFactor(), tier.userSelectable()))
                    .toList()
                : List.of();
            return Optional.of(new ModelTierListing.Listing(headline, named));
        }

        /** The tiers, best first, that hold an enabled account of the kind that serves this kind of model. */
        private List<Tier> ableToServe(String vendor, List<Tier> tiers, boolean codexOnly) {
            Map<String, List<ProxyAccountEntity>> byTier = held.computeIfAbsent(vendor, this::holdings);
            Predicate<ProxyAccountEntity> serves = codexOnly
                ? PoolCell.CODEX.predicate()
                : SourceTierSnapshot.providerFamily(vendor);
            List<Tier> able = new ArrayList<>();
            for (Tier tier : tiers) {
                if (byTier.getOrDefault(tier.tierId(), List.of()).stream().anyMatch(serves)) {
                    able.add(tier);
                }
            }
            return able;
        }

        /** The enabled accounts of the vendor's routes, by the tier they are in. */
        private Map<String, List<ProxyAccountEntity>> holdings(String vendor) {
            Predicate<ProxyAccountEntity> reachable = SourceTierSnapshot.routedFor(vendor);
            Map<String, List<ProxyAccountEntity>> byTier = new HashMap<>();
            for (ProxyAccountEntity account : accounts) {
                if (reachable.test(account)) {
                    TierMembership.nativeTierOf(snapshot, vendor, account)
                        .ifPresent(tier -> byTier.computeIfAbsent(tier.tierId(), id -> new ArrayList<>()).add(account));
                }
            }
            return byTier;
        }
    }
}
