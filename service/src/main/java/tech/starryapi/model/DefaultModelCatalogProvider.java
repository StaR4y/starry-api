package tech.starryapi.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport;
import tech.starryapi.platform.catalog.ProxyModelCatalog;

@Service
public class DefaultModelCatalogProvider implements ModelCatalogProvider {

    private static final List<ModelDtos.ModelSpec> BUILT_IN_MODELS = ProxyModelCatalog.models();

    private volatile List<ModelDtos.ModelSpec> models = BUILT_IN_MODELS;
    private volatile Map<String, String> targetById = targetMap(BUILT_IN_MODELS);
    private volatile List<ModelDtos.ManagedModelResponse> managedModels = BUILT_IN_MODELS.stream()
        .map(spec -> response(spec, true, true, null))
        .toList();
    private volatile List<ModelDtos.ModelSpec> syncedBuiltIns = List.of();
    private List<ModelCatalogCustomization> lastCustomizations = List.of();
    private List<String> lastOrder = List.of();

    @Override
    public List<ModelDtos.ModelSpec> models() {
        return models;
    }

    @Override
    public Optional<String> targetFor(String modelId) {
        if (modelId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(targetById.get(key(modelId)));
    }

    public synchronized void applyCustomizations(List<ModelCatalogCustomization> customizations) {
        applyCustomizations(customizations, List.of());
    }

    synchronized void applySyncedBuiltIns(List<ModelDtos.ModelSpec> synced) {
        syncedBuiltIns = synced == null ? List.of() : List.copyOf(synced);
        applyCustomizations(lastCustomizations, lastOrder);
    }

    synchronized void applyCustomizations(
        List<ModelCatalogCustomization> customizations,
        List<String> order
    ) {
        lastCustomizations = customizations == null ? List.of() : List.copyOf(customizations);
        lastOrder = order == null ? List.of() : List.copyOf(order);
        Map<String, ModelCatalogCustomization> byId = new LinkedHashMap<>();
        if (customizations != null) {
            for (ModelCatalogCustomization customization : customizations) {
                if (customization != null && customization.id() != null && !customization.id().isBlank()) {
                    byId.put(key(customization.id()), customization);
                }
            }
        }

        List<ModelDtos.ModelSpec> effective = new ArrayList<>();
        List<ModelDtos.ManagedModelResponse> managed = new ArrayList<>();
        LinkedHashMap<String, ModelDtos.ModelSpec> builtIns = new LinkedHashMap<>();
        for (ModelDtos.ModelSpec builtIn : BUILT_IN_MODELS) {
            builtIns.putIfAbsent(key(builtIn.id()), builtIn);
        }
        for (ModelDtos.ModelSpec synced : syncedBuiltIns) {
            if (synced != null && synced.id() != null) {
                builtIns.putIfAbsent(key(synced.id()), synced);
            }
        }
        for (ModelDtos.ModelSpec builtIn : builtIns.values()) {
            ModelCatalogCustomization customization = byId.remove(key(builtIn.id()));
            ModelDtos.ModelSpec merged = customization == null ? builtIn : merge(builtIn, customization);
            boolean enabled = customization == null || !Boolean.FALSE.equals(customization.enabled());
            managed.add(response(merged, enabled, true, customization == null ? null : customization.badgeColor()));
            if (enabled) {
                effective.add(merged);
            }
        }

        byId.values().stream()
            .sorted(Comparator.comparing(customization -> key(customization.id())))
            .forEach(customization -> {
                ModelDtos.ModelSpec custom = customSpec(customization);
                boolean enabled = !Boolean.FALSE.equals(customization.enabled());
                managed.add(response(custom, enabled, false, customization.badgeColor()));
                if (enabled) {
                    effective.add(custom);
                }
            });

        List<ModelDtos.ModelSpec> orderedModels = applyOrder(effective, ModelDtos.ModelSpec::id, order);
        List<ModelDtos.ManagedModelResponse> orderedManaged = applyOrder(
            managed,
            ModelDtos.ManagedModelResponse::id,
            order
        );
        models = List.copyOf(orderedModels);
        targetById = targetMap(models);
        managedModels = List.copyOf(orderedManaged);
    }

    public List<ModelDtos.ManagedModelResponse> managedModels() {
        return managedModels;
    }

    boolean isBuiltIn(String modelId) {
        String modelKey = key(modelId);
        if (BUILT_IN_MODELS.stream().anyMatch(spec -> key(spec.id()).equals(modelKey))) {
            return true;
        }
        return syncedBuiltIns.stream().anyMatch(spec -> key(spec.id()).equals(modelKey));
    }

    boolean isCataloged(String modelId) {
        String modelKey = key(modelId);
        return managedModels.stream().anyMatch(model -> key(model.id()).equals(modelKey));
    }

    private static ModelDtos.ModelSpec merge(
        ModelDtos.ModelSpec builtIn,
        ModelCatalogCustomization customization
    ) {
        return new ModelDtos.ModelSpec(
            builtIn.id(),
            value(customization.name(), builtIn.name()),
            value(canonicalProvider(customization.provider()), builtIn.provider()),
            value(customization.family(), builtIn.family()),
            builtIn.ownedBy(),
            value(customization.description(), builtIn.description()),
            builtIn.supportedInputTypes(),
            positive(customization.contextWindow(), builtIn.maxInputTokens()),
            builtIn.maxOutputTokens(),
            value(customization.targetModelId(), builtIn.targetModelId()),
            value(customization.icon(), builtIn.icon()),
            value(customization.requestFormat(), builtIn.requestFormat()),
            value(customization.endpoint(), builtIn.endpoint())
        );
    }

    private static ModelDtos.ModelSpec customSpec(ModelCatalogCustomization customization) {
        return new ModelDtos.ModelSpec(
            customization.id().trim(),
            value(customization.name(), customization.id()),
            value(canonicalProvider(customization.provider()), "Custom"),
            value(customization.family(), "Custom"),
            ADMIN_OWNER,
            value(customization.description(), "Administrator-managed model"),
            TEXT_ONLY,
            positive(customization.contextWindow(), DEFAULT_INPUT_TOKENS),
            DEFAULT_OUTPUT_TOKENS,
            value(customization.targetModelId(), customization.id()),
            customization.icon(),
            value(customization.requestFormat(), "OPENAI_CHAT_COMPLETIONS"),
            value(customization.endpoint(), "/v1/chat/completions")
        );
    }

    private static ModelDtos.ManagedModelResponse response(
        ModelDtos.ModelSpec spec,
        boolean enabled,
        boolean builtIn,
        String badgeColor
    ) {
        return new ModelDtos.ManagedModelResponse(
            spec.id(),
            spec.name(),
            spec.icon(),
            spec.provider(),
            spec.family(),
            spec.targetModelId(),
            spec.requestFormat(),
            spec.endpoint(),
            spec.maxInputTokens(),
            spec.description(),
            enabled,
            builtIn,
            badgeColor,
            null
        );
    }

    private static <T> List<T> applyOrder(List<T> items, Function<T, String> idGetter, List<String> order) {
        if (items == null || items.isEmpty() || order == null || order.isEmpty()) {
            return items == null ? List.of() : items;
        }
        Map<String, Integer> rank = new HashMap<>();
        int nextRank = 0;
        for (String id : order) {
            if (id == null || id.isBlank()) {
                continue;
            }
            rank.putIfAbsent(key(id), nextRank++);
        }
        if (rank.isEmpty()) {
            return items;
        }
        record Ranked<T>(T item, int originalIndex, int rank) {
        }
        List<Ranked<T>> ranked = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            ranked.add(new Ranked<>(
                item,
                index,
                rank.getOrDefault(key(idGetter.apply(item)), Integer.MAX_VALUE)
            ));
        }
        ranked.sort(Comparator.comparingInt(Ranked<T>::rank).thenComparingInt(Ranked::originalIndex));
        return ranked.stream().map(Ranked::item).toList();
    }

    private static Map<String, String> targetMap(List<ModelDtos.ModelSpec> specs) {
        return specs.stream().collect(Collectors.toUnmodifiableMap(
            spec -> key(spec.id()),
            spec -> Objects.requireNonNullElse(spec.targetModelId(), spec.id()),
            (left, right) -> left
        ));
    }

    private static String canonicalProvider(String provider) {
        return PlatformModelCatalogSupport.canonicalCatalogProvider(provider);
    }

    private static String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.trim();
    }

    private static int positive(Integer candidate, int fallback) {
        return candidate == null || candidate <= 0 ? fallback : candidate;
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
