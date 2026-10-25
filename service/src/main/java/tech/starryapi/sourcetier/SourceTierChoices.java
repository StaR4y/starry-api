package tech.starryapi.sourcetier;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos;
import tech.starryapi.model.ModelPricingService;
import tech.starryapi.model.ModelTierListing;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.sourcetier.SourceTierSnapshot.Tier;
import tech.starryapi.sourcetier.SourceTierSnapshot.VendorConfig;

/**
 * What a user may choose from when setting the source pool preference of an API key (design 8): the pools of the key
 * provider's vendor that clients may name, each with its name, level, factor and price, and nothing of the accounts or
 * the protocol behind it. The price is that of the provider's first model in the public model list that has one, priced
 * the way {@code /v1/models} prices it, so the figures agree with what the model list shows.
 *
 * <p>The pools are listed under the same switch as in the public model list ({@code publicListing} of the vendor): a
 * vendor that keeps its pool names to itself shows a user none. An administrator, who sets keys for others as well, is
 * always shown them. A provider without source tiers in use, or a release that cannot run them, has none to show.
 */
@Component
public class SourceTierChoices {

    private static final String ADMIN_ROLE = "ADMIN";

    private final SourceTierRegistry registry;
    private final SourceTierRuntimeInfo runtime;
    private final ModelPoolKeyResolver keyResolver;
    private final ModelCatalogProvider modelCatalog;
    private final ModelPricingService modelPricing;
    private final PaymentSettingsService paymentSettings;

    public SourceTierChoices(
        SourceTierRegistry registry,
        SourceTierRuntimeInfo runtime,
        ModelPoolKeyResolver keyResolver,
        ModelCatalogProvider modelCatalog,
        ModelPricingService modelPricing,
        PaymentSettingsService paymentSettings
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.modelPricing = Objects.requireNonNull(modelPricing, "modelPricing");
        this.paymentSettings = Objects.requireNonNull(paymentSettings, "paymentSettings");
    }

    /** The pools {@code principal} may choose for a key of {@code provider}, best first. */
    public Mono<SourceTierDtos.MyTiers> forProvider(AdminPrincipal principal, String provider) {
        String name = provider == null ? "" : provider.trim();
        String vendor = name.isEmpty() ? "" : keyResolver.resolve(null, null, name).provider();
        List<Tier> tiers = choosable(principal, vendor);
        if (tiers.isEmpty()) {
            return Mono.just(new SourceTierDtos.MyTiers(name, null, List.of()));
        }
        List<ModelDtos.ModelListItem> models = modelCatalog.modelList(0L).data().stream()
            .filter(model -> keyResolver.resolve(model.id(), model.id(), model.provider()).provider().equals(vendor))
            .toList();
        return priced(models, tiers)
            .map(item -> new SourceTierDtos.MyTiers(name, item.id(), item.sourceTiers().stream().map(SourceTierChoices::choice).toList()))
            .defaultIfEmpty(new SourceTierDtos.MyTiers(name, null, tiers.stream().map(SourceTierChoices::unpriced).toList()));
    }

    /**
     * The vendor's pools that a client may name: switched on, selectable, and visible to the caller. Before the
     * configuration has loaded nothing is known; a form that offers no choice is better than a refusal there, since
     * choosing is not what the requests wait for.
     */
    private List<Tier> choosable(AdminPrincipal principal, String vendor) {
        if (vendor.isEmpty() || !runtime.nativeTiers() || !runtime.selectors() || !registry.loaded()) {
            return List.of();
        }
        SourceTierSnapshot snapshot = registry.snapshot();
        Optional<VendorConfig> config = snapshot.vendor(vendor).filter(VendorConfig::enabled);
        if (config.isEmpty() || !(config.get().publicListing() || isAdmin(principal))) {
            return List.of();
        }
        return snapshot.tiers(vendor).stream()
            .filter(Tier::enabled)
            .filter(Tier::userSelectable)
            .sorted(Comparator.comparingInt(Tier::level))
            .toList();
    }

    /**
     * The first of {@code models} that has a price, listed with the pools at their factors by the machinery of the public
     * model list. The first model of a provider is not always priced (the routing presets are not), hence not the first
     * one only. Empty when none has a price.
     */
    private Mono<ModelDtos.ModelListItem> priced(List<ModelDtos.ModelListItem> models, List<Tier> tiers) {
        if (models.isEmpty()) {
            return Mono.empty();
        }
        List<ModelTierListing.Tier> named = tiers.stream()
            .map(tier -> new ModelTierListing.Tier(tier.tierId(), tier.level(), tier.name(), tier.priceFactor(), tier.userSelectable()))
            .toList();
        ModelTierListing listing = model -> Optional.of(new ModelTierListing.Listing(tiers.getFirst().priceFactor(), named));
        ModelDtos.ModelListResponse list = new ModelDtos.ModelListResponse("list", models, models);
        return paymentSettings.config()
            .flatMap(config -> modelPricing.applyPublicPricing(list, config.apiBillingMultiplier(), listing))
            .flatMapIterable(ModelDtos.ModelListResponse::data)
            .filter(SourceTierChoices::hasPrice)
            .next();
    }

    private static boolean hasPrice(ModelDtos.ModelListItem item) {
        if (item.sourceTiers() == null) {
            return false;
        }
        ModelDtos.ModelPublicPricing price = item.sourceTiers().getFirst().pricing();
        return price != null && (price.totalPricePerMillionTokens() != null || price.inputPricePerMillionTokens() != null);
    }

    private static SourceTierDtos.MyTier choice(ModelDtos.ModelSourceTier tier) {
        return new SourceTierDtos.MyTier(tier.id(), tier.level(), tier.name(), tier.priceFactor(), tier.pricing());
    }

    private static SourceTierDtos.MyTier unpriced(Tier tier) {
        return new SourceTierDtos.MyTier(tier.tierId(), tier.level(), tier.name(), tier.priceFactor(), null);
    }

    private static boolean isAdmin(AdminPrincipal principal) {
        return principal != null && ADMIN_ROLE.equalsIgnoreCase(principal.role());
    }
}
