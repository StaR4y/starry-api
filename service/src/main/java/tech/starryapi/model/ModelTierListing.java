package tech.starryapi.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The source tiers a model of the public list is priced under (design 5.7). The list shows the price of the tier a
 * request would first be served from, and names the tiers when the vendor lists them publicly. What a deployment
 * without tiers answers is {@link #NONE}, which leaves every model as it was.
 */
@FunctionalInterface
public interface ModelTierListing {

    /** No model is under tiers. */
    ModelTierListing NONE = model -> Optional.empty();

    /** How one model of the list is priced; empty when its vendor has no tiers in use. */
    Optional<Listing> of(ModelDtos.ModelListItem model);

    /**
     * @param headlineFactor what the standard price of the model is multiplied by in the list
     * @param tiers the tiers to name next to it, or empty when the vendor does not list its tiers
     */
    record Listing(BigDecimal headlineFactor, List<Tier> tiers) {
        public Listing {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }

    record Tier(String id, int level, String name, BigDecimal factor, boolean selectable) {
    }
}
