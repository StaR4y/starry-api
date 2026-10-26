package tech.starryapi.proxy.tier;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tech.starryapi.account.ModelPoolKeyResolver;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.sourcetier.SourceTierSnapshot.MemberModel;

/**
 * Finds the mapping of a relay that serves a model (design 7.4): the row, among the ones a relay is registered with, whose
 * model the request is for. A relay is mapped by catalog model, and a client names the model in more ways than the catalog
 * has rows: the aliases ({@code sonnet} is {@code claude-sonnet-4.5}), the prefixes a route strips, the {@code -fast}
 * spelling.
 *
 * <p>The row of the model asked for is taken when there is one (compared without regard to case), so that an alias that
 * is mapped on its own keeps its own wire name. Otherwise the model the catalog says the id is an alias of is looked for,
 * if that is a model of the same vendor. The same vendor, because the catalog has aliases across vendors (a Gemini id is
 * an alias of a Claude model for the route that serves it), and a mapping of Google's models must not answer for a Claude
 * model of the same name in another vendor, nor the other way round. A row that was found is the answer even when it is
 * switched off: the administrator turned that model off for the relay, and the row of another model must not stand in.
 */
final class XpModelResolver {

    private final ModelCatalogProvider modelCatalog;
    private final ModelPoolKeyResolver keyResolver;

    XpModelResolver(ModelCatalogProvider modelCatalog, ModelPoolKeyResolver keyResolver) {
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
    }

    /**
     * @param mappings the rows the relay is registered with in the vendor
     * @param model the model of the request without the prefixes and the {@code -fast} suffix routes strip, in lower case
     *     ({@link DefaultSourceTierPlanner#normalize})
     */
    Optional<MemberModel> find(List<MemberModel> mappings, String vendor, String model) {
        Optional<MemberModel> exact = named(mappings, model);
        if (exact.isPresent()) {
            return exact;
        }
        return modelCatalog.targetFor(model)
            .map(DefaultSourceTierPlanner::normalize)
            .filter(target -> !target.equals(model))
            .filter(target -> vendor.equals(keyResolver.resolve(target, target, null).provider()))
            .flatMap(target -> named(mappings, target));
    }

    private static Optional<MemberModel> named(List<MemberModel> mappings, String model) {
        return mappings.stream().filter(mapping -> mapping.modelId().equalsIgnoreCase(model)).findFirst();
    }
}
