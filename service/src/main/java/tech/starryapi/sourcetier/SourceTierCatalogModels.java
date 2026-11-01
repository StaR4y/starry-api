package tech.starryapi.sourcetier;

import java.util.Set;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos.ModelSpec;

/**
 * Which catalog entries are models of a vendor, for the places where tiers must not take an entry for a model it is not:
 * the mappings a relay serves (the validator) and the models the all-endpoints switch moves to another route (the planner).
 * The catalog also holds entries that name no model, the routing presets such as {@code auto} and the internal ids of
 * CodeWhisperer; they belong to a vendor's family in the catalog and are served by whatever the route has.
 */
public final class SourceTierCatalogModels {

    private static final Set<String> NON_MODEL_FAMILIES = Set.of("Routing Preset", "CodeWhisperer Internal");

    private SourceTierCatalogModels() {
    }

    /**
     * Whether {@code modelId} is the id of a catalog entry (compared without regard to case) that names a model of
     * {@code vendor}: not a routing preset, not an internal id, and a vendor the entry's provider resolves to.
     */
    public static boolean isModelOf(ModelCatalogProvider catalog, ModelPoolKeyResolver resolver, String vendor, String modelId) {
        for (ModelSpec spec : catalog.models()) {
            if (spec.id() != null && spec.id().equalsIgnoreCase(modelId)) {
                return !NON_MODEL_FAMILIES.contains(spec.family())
                    && vendor.equals(resolver.resolve(spec.id(), spec.id(), null).provider());
            }
        }
        return false;
    }
}
