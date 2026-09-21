package tech.starryapi.account;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tech.starryapi.common.Hashing;
import tech.starryapi.model.ModelCatalogProvider;
import tech.starryapi.model.ModelDtos.ModelSpec;

@Component
public class ModelPoolKeyResolver {

    private static final String OTHER_FAMILY = "other";
    private static final String OTHER_FAMILY_LABEL = "Other";
    private static final ProviderDescriptor UNKNOWN_PROVIDER = new ProviderDescriptor("unknown", "Unknown");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9.]+");
    private static final Pattern REPEATED_SEPARATOR = Pattern.compile("[.-]{2,}");
    private static final int HASH_SUFFIX_LENGTH = 20;
    private static final List<String> MODEL_VENDOR_PREFIXES = List.of(
        "openai/",
        "anthropic/",
        "google/",
        "gemini/",
        "deepseek/"
    );

    private final ModelCatalogProvider modelCatalog;
    private volatile CatalogIndex catalogIndex;

    public ModelPoolKeyResolver(ModelCatalogProvider modelCatalog) {
        this.modelCatalog = modelCatalog;
        this.catalogIndex = buildIndex(modelsSnapshot());
    }

    private CatalogIndex buildIndex(List<ModelSpec> models) {
        Map<String, KnownModel> exact = new HashMap<>();
        Map<String, KnownModel> local = new HashMap<>();
        Set<String> ambiguousExact = new HashSet<>();
        Set<String> ambiguousLocal = new HashSet<>();
        Map<String, ProviderDescriptor> providers = defaultFallbackProviders();
        Map<ModelPoolKey, String> representatives = new LinkedHashMap<>();

        for (ModelSpec model : models) {
            KnownModel known = knownModel(model);
            if (known == null) {
                continue;
            }
            String modelId = normalizeModelId(model.id());
            if (modelId.isEmpty()) {
                continue;
            }
            putIfUnambiguous(exact, ambiguousExact, modelId, known);
            putIfUnambiguous(local, ambiguousLocal, stripVendorPrefix(modelId), known);
            registerProviderAliases(providers, model.provider(), known.pool());
            representatives.putIfAbsent(known.pool(), model.id().trim());
        }

        return new CatalogIndex(
            models,
            Map.copyOf(exact),
            Map.copyOf(local),
            Map.copyOf(providers),
            Map.copyOf(representatives),
            representatives.keySet().stream().sorted().toList()
        );
    }

    public ModelPoolKey resolve(String requestedModel, String upstreamModel, String providerHint) {
        CatalogIndex index = index();
        KnownModel known = findKnown(index, upstreamModel)
            .orElseGet(() -> findKnown(index, requestedModel).orElse(null));
        if (known != null) {
            return known.pool();
        }
        ProviderDescriptor provider = fallbackProvider(index, providerHint, upstreamModel, requestedModel);
        return new ModelPoolKey(
            provider.slug(),
            OTHER_FAMILY,
            provider.label(),
            OTHER_FAMILY_LABEL
        );
    }

    public List<ModelPoolKey> knownPools() {
        return index().knownPools();
    }

    public Optional<String> representativeModel(ModelPoolKey pool) {
        if (pool == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(index().representativeModels().get(pool));
    }

    private Optional<KnownModel> findKnown(CatalogIndex index, String model) {
        String normalized = normalizeModelId(model);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String constrainedProvider = explicitProviderPrefix(normalized);
        for (String candidate : lookupCandidates(normalized)) {
            KnownModel exact = index.exactModels().get(candidate);
            if (matchesProvider(exact, constrainedProvider)) {
                return Optional.of(exact);
            }
            KnownModel local = index.localModels().get(candidate);
            if (matchesProvider(local, constrainedProvider)) {
                return Optional.of(local);
            }
        }
        return Optional.empty();
    }

    private List<String> lookupCandidates(String model) {
        String normalized = normalizeModelId(model);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);
        String compatible = stripPrefix(normalized, "openai-compatible/");
        candidates.add(compatible);
        candidates.add(stripVendorPrefix(normalized));
        candidates.add(stripVendorPrefix(compatible));
        return List.copyOf(candidates);
    }

    private String explicitProviderPrefix(String normalizedModel) {
        if (normalizedModel.startsWith("openai-compatible/")) {
            return null;
        }
        for (String prefix : MODEL_VENDOR_PREFIXES) {
            if (normalizedModel.startsWith(prefix)) {
                String provider = prefix.substring(0, prefix.length() - 1);
                return "gemini".equals(provider) ? "google" : provider;
            }
        }
        return null;
    }

    private boolean matchesProvider(KnownModel model, String constrainedProvider) {
        return model != null
            && (constrainedProvider == null || constrainedProvider.equals(model.pool().provider()));
    }

    private ProviderDescriptor fallbackProvider(
        CatalogIndex index,
        String providerHint,
        String upstreamModel,
        String requestedModel
    ) {
        ProviderDescriptor upstream = providerFromModelPrefix(index, upstreamModel);
        if (upstream != null) {
            return upstream;
        }
        ProviderDescriptor requested = providerFromModelPrefix(index, requestedModel);
        if (requested != null) {
            return requested;
        }
        ProviderDescriptor hinted = index.fallbackProviders().get(normalizeHint(providerHint));
        return hinted == null ? UNKNOWN_PROVIDER : hinted;
    }

    private ProviderDescriptor providerFromModelPrefix(CatalogIndex index, String model) {
        String normalized = normalizeModelId(model);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("openai-compatible/")) {
            return index.fallbackProviders().get("openai-compatible");
        }
        int slash = normalized.indexOf('/');
        return slash <= 0
            ? null
            : index.fallbackProviders().get(normalizeHint(normalized.substring(0, slash)));
    }

    private CatalogIndex index() {
        List<ModelSpec> models = modelsSnapshot();
        CatalogIndex current = catalogIndex;
        if (current.sourceModels() == models) {
            return current;
        }
        return refreshIndex(models);
    }

    private synchronized CatalogIndex refreshIndex(List<ModelSpec> models) {
        CatalogIndex current = catalogIndex;
        if (current.sourceModels() == models) {
            return current;
        }
        CatalogIndex refreshed = buildIndex(models);
        catalogIndex = refreshed;
        return refreshed;
    }

    private List<ModelSpec> modelsSnapshot() {
        if (modelCatalog == null) {
            return List.of();
        }
        List<ModelSpec> models = modelCatalog.models();
        return models == null ? List.of() : models;
    }

    private KnownModel knownModel(ModelSpec model) {
        if (model == null || isBlank(model.id()) || isBlank(model.provider()) || isBlank(model.family())) {
            return null;
        }
        ProviderDescriptor provider = providerDescriptor(model.provider());
        String family = slug(model.family());
        if (family.isEmpty()) {
            family = OTHER_FAMILY;
        }
        return new KnownModel(new ModelPoolKey(
            provider.slug(),
            family,
            provider.label(),
            model.family().trim()
        ));
    }

    private ProviderDescriptor providerDescriptor(String provider) {
        String normalized = normalizeHint(provider);
        return switch (normalized) {
            case "anthropic", "claude" -> new ProviderDescriptor("anthropic", "Anthropic");
            case "deepseek" -> new ProviderDescriptor("deepseek", "DeepSeek");
            case "gemini", "google" -> new ProviderDescriptor("google", "Google");
            case "openai" -> new ProviderDescriptor("openai", "OpenAI");
            default -> {
                String providerSlug = slug(provider);
                yield providerSlug.isEmpty()
                    ? UNKNOWN_PROVIDER
                    : new ProviderDescriptor(providerSlug, provider.trim());
            }
        };
    }

    private Map<String, ProviderDescriptor> defaultFallbackProviders() {
        Map<String, ProviderDescriptor> providers = new HashMap<>();
        registerAliases(providers, new ProviderDescriptor("openai", "OpenAI"),
            "openai", "codex", "openai-api-key");
        registerAliases(providers, new ProviderDescriptor("openai-compatible", "OpenAI Compatible"),
            "openai-compatible", "openaicompatibility");
        registerAliases(providers, new ProviderDescriptor("anthropic", "Anthropic"),
            "anthropic", "claude");
        registerAliases(providers, new ProviderDescriptor("google", "Google"),
            "google", "gemini");
        registerAliases(providers, new ProviderDescriptor("deepseek", "DeepSeek"),
            "deepseek");
        registerAliases(providers, new ProviderDescriptor("kiro", "Kiro"),
            "kiro", "codewhisperer", "builderid", "builder-id", "idc", "social");
        registerAliases(providers, UNKNOWN_PROVIDER, "unknown");
        return providers;
    }

    private void registerProviderAliases(
        Map<String, ProviderDescriptor> providers,
        String catalogProvider,
        ModelPoolKey pool
    ) {
        ProviderDescriptor descriptor = new ProviderDescriptor(pool.provider(), pool.providerLabel());
        providers.putIfAbsent(normalizeHint(catalogProvider), descriptor);
        providers.putIfAbsent(pool.provider(), descriptor);
    }

    private void registerAliases(
        Map<String, ProviderDescriptor> providers,
        ProviderDescriptor descriptor,
        String... aliases
    ) {
        for (String alias : aliases) {
            providers.put(normalizeHint(alias), descriptor);
        }
    }

    private void putIfUnambiguous(
        Map<String, KnownModel> models,
        Set<String> ambiguous,
        String modelId,
        KnownModel candidate
    ) {
        if (modelId.isEmpty() || ambiguous.contains(modelId)) {
            return;
        }
        KnownModel existing = models.putIfAbsent(modelId, candidate);
        if (existing != null && !existing.pool().equals(candidate.pool())) {
            models.remove(modelId);
            ambiguous.add(modelId);
        }
    }

    private String normalizeModelId(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("starry/")) {
            normalized = normalized.substring("starry/".length());
        }
        return normalized.endsWith("-fast")
            ? normalized.substring(0, normalized.length() - "-fast".length())
            : normalized;
    }

    private String stripVendorPrefix(String model) {
        for (String prefix : MODEL_VENDOR_PREFIXES) {
            if (model.startsWith(prefix)) {
                return model.substring(prefix.length());
            }
        }
        return model;
    }

    private String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private String normalizeHint(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String slug(String value) {
        if (value == null) {
            return "";
        }
        String ascii = DIACRITICS.matcher(Normalizer.normalize(value, Normalizer.Form.NFKD))
            .replaceAll("")
            .toLowerCase(Locale.ROOT);
        String normalized = NON_SLUG.matcher(ascii).replaceAll("-");
        normalized = REPEATED_SEPARATOR.matcher(normalized).replaceAll("-");
        int start = 0;
        int end = normalized.length();
        while (start < end && isSeparator(normalized.charAt(start))) {
            start++;
        }
        while (end > start && isSeparator(normalized.charAt(end - 1))) {
            end--;
        }
        String result = normalized.substring(start, end);
        if (result.length() <= ModelPoolKey.MAX_COMPONENT_LENGTH) {
            return result;
        }
        int prefixLength = ModelPoolKey.MAX_COMPONENT_LENGTH - HASH_SUFFIX_LENGTH - 1;
        int prefixEnd = prefixLength;
        while (prefixEnd > 0 && isSeparator(result.charAt(prefixEnd - 1))) {
            prefixEnd--;
        }
        return result.substring(0, prefixEnd)
            + '-'
            + Hashing.sha256(result).substring(0, HASH_SUFFIX_LENGTH);
    }

    private boolean isSeparator(char value) {
        return value == '-' || value == '.';
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record KnownModel(ModelPoolKey pool) {
    }

    private record ProviderDescriptor(String slug, String label) {
    }

    private record CatalogIndex(
        List<ModelSpec> sourceModels,
        Map<String, KnownModel> exactModels,
        Map<String, KnownModel> localModels,
        Map<String, ProviderDescriptor> fallbackProviders,
        Map<ModelPoolKey, String> representativeModels,
        List<ModelPoolKey> knownPools
    ) {
    }
}
