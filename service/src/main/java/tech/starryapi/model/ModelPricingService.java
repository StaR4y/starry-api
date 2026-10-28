package tech.starryapi.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tech.starryapi.payment.PaymentSettingsService;
import tech.starryapi.platform.core.PlatformUsage;
import tech.starryapi.platform.deepseek.DeepSeekModelCatalog;
import tech.starryapi.platform.catalog.PlatformModelCatalogSupport;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ModelPricingService {

    static final String MODEL_PRICING_RULES = "MODEL_PRICING_RULES";
    static final int MAX_RULES = 200;
    static final int MAX_MODEL_PATTERN_LENGTH = 256;
    /** As many factors as a vendor has tiers. */
    static final int MAX_PREVIEW_FACTORS = 9;

    private static final Logger log = LoggerFactory.getLogger(ModelPricingService.class);
    private static final TypeReference<List<ModelPricingRule>> RULE_LIST = new TypeReference<>() {};
    private static final int MULTIPLIER_SCALE = 16;
    private static final int PRICE_PER_MILLION_SCALE = 10;
    private static final int TOKENS_PER_100_BALANCE_SCALE = 0;
    private static final BigDecimal DISPLAY_BALANCE_UNIT = BigDecimal.valueOf(100);
    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal FAST_MODE_MULTIPLIER = BigDecimal.valueOf(2);
    private static final BigDecimal DEFAULT_CACHE_READ_RATIO = new BigDecimal("0.1");
    private static final BigDecimal UNKNOWN_MODEL_FALLBACK_MULTIPLIER = new BigDecimal("0.0000166666666667");
    private static final BigDecimal MAX_PRICE_MULTIPLIER = new BigDecimal("1000");
    private static final String BILLING_MODE_TOTAL = "total";
    private static final String BILLING_MODE_INPUT_OUTPUT = "input_output";

    private final SystemSettingRepository repository;
    private final ObjectMapper objectMapper;
    private final OfficialModelPricingService officialModelPricingService;
    private final DefaultModelCatalogProvider catalogProvider;
    private final AtomicLong cacheGeneration = new AtomicLong();
    private volatile List<ModelPricingRule> cachedRules;
    private volatile long cachedRulesAtNanos;
    /** Upper bound on how long rules changed outside this process (another instance, a manual SQL edit) stay unseen. */
    private static final long RULES_CACHE_TTL_NANOS = java.time.Duration.ofSeconds(15).toNanos();
    private volatile CatalogIndex catalogIndex;

    public ModelPricingService(
        SystemSettingRepository repository,
        ObjectMapper objectMapper,
        OfficialModelPricingService officialModelPricingService
    ) {
        this(repository, objectMapper, officialModelPricingService, new DefaultModelCatalogProvider());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ModelPricingService(
        SystemSettingRepository repository,
        ObjectMapper objectMapper,
        OfficialModelPricingService officialModelPricingService,
        DefaultModelCatalogProvider catalogProvider
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.officialModelPricingService = officialModelPricingService;
        this.catalogProvider = catalogProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCache() {
        refresh().subscribe(null, error -> log.warn("Failed to warm up model pricing cache", error));
    }

    @Scheduled(fixedDelay = 30_000)
    public void scheduledRefresh() {
        refresh().subscribe(null, error -> log.warn("Failed to refresh model pricing cache", error));
    }

    public Mono<ModelDtos.ModelPricingResponse> settings() {
        return Mono.defer(() -> {
            long generation = cacheGeneration.get();
            return readRules()
            .doOnNext(rules -> replaceCache(rules, generation))
            .map(this::toResponse);
        });
    }

    public Mono<ModelDtos.ModelPricingResponse> update(ModelDtos.UpdateModelPricingRequest request) {
        List<ModelDtos.UpdateModelPricingRuleRequest> requestedRules = request == null ? List.of() : request.rules();
        if (requestedRules.size() > MAX_RULES) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_PRICE_RULE_LIMIT_EXCEEDED",
                "Model pricing rules must not exceed " + MAX_RULES
            ));
        }
        return readRules()
            .flatMap(previous -> {
                Map<String, ModelPricingRule> previousById = previous.stream()
                    .collect(Collectors.toMap(ModelPricingRule::ruleId, Function.identity(), (left, right) -> left));
                List<ModelPricingRule> next = normalizeRules(requestedRules, previousById);
                String json = toJson(next);
                return repository.upsertSetting(MODEL_PRICING_RULES, json)
                    .then(Mono.fromRunnable(this::invalidateAllCaches))
                    .then(settings());
            });
    }

    public Mono<BigDecimal> cost(
        String model,
        PlatformUsage usage,
        boolean fastMode,
        BigDecimal billingMultiplier
    ) {
        return rulesSnapshot().map(rules -> cost(rules, model, usage, fastMode, billingMultiplier));
    }

    public Mono<ModelDtos.ModelListResponse> applyPublicPricing(
        ModelDtos.ModelListResponse response,
        BigDecimal billingMultiplier
    ) {
        ModelDtos.ModelListResponse safeResponse = response == null
            ? new ModelDtos.ModelListResponse("list", List.of(), List.of())
            : response;
        return rulesSnapshot().map(rules -> applyPublicPricing(safeResponse, rules, billingMultiplier));
    }

    /**
     * The public list priced under source tiers: a model whose vendor is tiered shows the price of the tier it is first
     * served from, and the tiers themselves when the vendor lists them. A model that is not under tiers is priced as the
     * two-argument form prices it.
     */
    public Mono<ModelDtos.ModelListResponse> applyPublicPricing(
        ModelDtos.ModelListResponse response,
        BigDecimal billingMultiplier,
        ModelTierListing tiers
    ) {
        ModelDtos.ModelListResponse safeResponse = response == null
            ? new ModelDtos.ModelListResponse("list", List.of(), List.of())
            : response;
        return rulesSnapshot().map(rules -> applyPublicPricing(safeResponse, rules, billingMultiplier, tiers));
    }

    public Mono<List<ModelDtos.ManagedModelResponse>> applyManagedPricing(
        List<ModelDtos.ManagedModelResponse> models,
        BigDecimal billingMultiplier
    ) {
        List<ModelDtos.ManagedModelResponse> safeModels = models == null
            ? List.of()
            : List.copyOf(models);
        return rulesSnapshot().map(rules -> applyManagedPricing(safeModels, rules, billingMultiplier));
    }

    public Mono<Void> refresh() {
        return Mono.defer(() -> {
            long generation = cacheGeneration.get();
            return readRules()
                .doOnNext(rules -> replaceCache(rules, generation))
                .then();
        });
    }

    public synchronized void invalidateAllCaches() {
        cacheGeneration.incrementAndGet();
        cachedRules = null;
    }

    public void invalidateCacheForSetting(String settingKey) {
        if (MODEL_PRICING_RULES.equals(settingKey)) {
            invalidateAllCaches();
        }
    }

    public Mono<BillingQuote> billingQuote(String model, boolean fastMode, BigDecimal multiplier) {
        // Runs for every proxied request. It used to query and re-parse the rules each time while cost() already
        // used the snapshot; the snapshot is invalidated on every local change and expires after a few seconds.
        return rulesSnapshot().map(rules -> quote(resolvePricing(rules, model, null, multiplier), fastMode));
    }

    /**
     * The most output tokens the official price catalog lists for the model, which is what the upstream of that model
     * itself holds a request to; empty when the catalog does not list the model or lists it with none.
     */
    public OptionalInt maxOutputTokens(String model) {
        return officialModelPricingService.maxOutputTokens(model);
    }

    BigDecimal cost(List<ModelPricingRule> rules, String model, PlatformUsage usage,
                    boolean fastMode, BigDecimal multiplier) {
        return quote(resolvePricing(rules, model, null, multiplier), fastMode)
            .cost(usage == null ? PlatformUsage.zero() : usage);
    }

    ModelDtos.ModelPublicPricing publicPricing(List<ModelPricingRule> rules, String model, BigDecimal multiplier) {
        return displayPricing(resolvePricing(rules, model, null, multiplier));
    }

    public ModelDtos.ModelPricingPreview preview(ModelDtos.ModelPricingPreviewRequest request, BigDecimal multiplier) {
        if (request == null || clean(request.model()).isBlank() || request.model().length() > MAX_MODEL_PATTERN_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_MODEL_INVALID", "Invalid model ID");
        }
        var rules = normalizeRules(request.rules(), Map.of());
        var price = resolvePricing(rules, request.model(), null, multiplier);
        var baseline = resolvePricing(List.of(), request.model(), null, BigDecimal.ONE);
        return new ModelDtos.ModelPricingPreview(request.model(), price.model(), price.source(), price.matchedPriceModel(),
            price.multiplier(), price.matches().stream().map(ModelPricingRule::ruleId).toList(),
            displayPricing(baseline), displayPricing(price), factorPricing(price, request.factors()));
    }

    /** The preview priced at each source-tier factor, or null when none was asked for. */
    private List<ModelDtos.FactorPricing> factorPricing(ResolvedPricing price, List<BigDecimal> factors) {
        if (factors.isEmpty()) {
            return null;
        }
        if (factors.size() > MAX_PREVIEW_FACTORS) {
            throw invalidFactor("At most " + MAX_PREVIEW_FACTORS + " price factors can be previewed at once");
        }
        return factors.stream().map(factor -> {
            if (factor == null || factor.signum() <= 0 || factor.compareTo(MAX_PRICE_MULTIPLIER) > 0) {
                throw invalidFactor("A price factor must be greater than 0 and at most " + MAX_PRICE_MULTIPLIER);
            }
            return new ModelDtos.FactorPricing(factor, displayPricing(scaled(price, factor)));
        }).toList();
    }

    private static ApiException invalidFactor(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_FACTOR_INVALID", message);
    }

    /** The resolved price multiplied by a source-tier factor; a price that is unavailable stays so. */
    private static ResolvedPricing scaled(ResolvedPricing price, BigDecimal factor) {
        if (price.rates() == null) {
            return price;
        }
        return new ResolvedPricing(price.model(), price.source(), price.matchedPriceModel(), price.multiplier(),
            price.matches(), price.rates().scaled(factor), price.total());
    }

    private ModelDtos.ModelListResponse applyPublicPricing(
        ModelDtos.ModelListResponse response, List<ModelPricingRule> rules, BigDecimal multiplier
    ) {
        var items = response.data().stream()
            .map(item -> item.withPricing(publicPricing(rules, item.id(), multiplier))).toList();
        return new ModelDtos.ModelListResponse(response.object(), items, items);
    }

    private ModelDtos.ModelListResponse applyPublicPricing(
        ModelDtos.ModelListResponse response, List<ModelPricingRule> rules, BigDecimal multiplier, ModelTierListing tiers
    ) {
        var items = response.data().stream().map(item -> tiers.of(item)
            .map(listing -> {
                // The rule is found once; the factors multiply what it resolves to, the way a tiered request is charged.
                var resolved = resolvePricing(rules, item.id(), null, multiplier);
                return item
                    .withPricing(displayPricing(scaled(resolved, listing.headlineFactor())))
                    .withSourceTiers(listing.tiers().stream().map(tier -> new ModelDtos.ModelSourceTier(
                        tier.id(), tier.level(), tier.name(), tier.factor(), tier.selectable(),
                        displayPricing(scaled(resolved, tier.factor())))).toList());
            })
            .orElseGet(() -> item.withPricing(publicPricing(rules, item.id(), multiplier)))).toList();
        return new ModelDtos.ModelListResponse(response.object(), items, items);
    }

    private List<ModelDtos.ManagedModelResponse> applyManagedPricing(
        List<ModelDtos.ManagedModelResponse> models, List<ModelPricingRule> rules, BigDecimal multiplier
    ) {
        return models.stream().map(model -> model.withPricing(
            displayPricing(resolvePricing(rules, model.id(), model.targetModelId(), multiplier)))).toList();
    }

    private ResolvedPricing resolvePricing(List<ModelPricingRule> rules, String model, String targetHint, BigDecimal multiplier) {
        String requested = normalizeModelId(model);
        String target = pricingTarget(model, targetHint);
        var names = pricingNames(model, requested, target);
        var catalogModel = catalogModel(model);
        var matched = rules.stream().filter(rule -> !Boolean.FALSE.equals(rule.enabled()))
            .filter(rule -> matchesRule(rule, names, requested, target, catalogModel))
            .sorted(ruleComparator().thenComparing(rule -> normalizeModelId(rule.modelPattern()).equals(requested) ? 0 : 1)
                .thenComparing(ModelPricingRule::modelPattern, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ModelPricingRule::ruleId)).toList();
        var rule = matched.isEmpty() ? null : matched.getFirst();
        if (rule != null && !positive(rule.priceMultiplier())) {
            if (positive(rule.totalMultiplier())) {
                BigDecimal rate = rule.totalMultiplier();
                return new ResolvedPricing(target, "legacy_total", null, null, matched,
                    new BillingQuote(rate, rate, rate, rate, rate), true);
            }
            return new ResolvedPricing(target, "legacy_itemized", null, null, matched,
                new BillingQuote(rule.inputMultiplier(), rule.outputMultiplier(), effectiveCacheReadMultiplier(rule),
                    effectiveCacheWriteMultiplier(rule), UNKNOWN_MODEL_FALLBACK_MULTIPLIER), false);
        }
        BigDecimal ratio = rule != null ? rule.priceMultiplier() : safeBillingMultiplier(multiplier);
        // Only exact/normalized catalog keys may establish a billable official price.
        var official = officialModelPricingService.resolve(target, false);
        if (official.isEmpty()) return new ResolvedPricing(target, "unavailable", null, ratio, matched, null, false);
        var found = official.get();
        var price = found.price();
        return new ResolvedPricing(target, found.source(), found.matchedId(), ratio, matched,
            new BillingQuote(applyBillingMultiplier(price.inputCostPerToken(), ratio),
                applyBillingMultiplier(price.outputCostPerToken(), ratio),
                applyBillingMultiplier(price.cacheReadCostPerToken(), ratio),
                applyBillingMultiplier(price.cacheWriteCostPerToken(), ratio), fallbackTokenMultiplier(ratio)), false);
    }

    private boolean matchesRule(ModelPricingRule rule, List<String> names, String requested, String target,
                                ModelDtos.ManagedModelResponse model) {
        if ("provider".equals(rule.scope()) || "family".equals(rule.scope())) {
            // Ownership comes from the requested catalog entry, never its cross-provider routing target.
            return model != null && PlatformModelCatalogSupport.sameCatalogProvider(rule.provider(), model.provider())
                && (!"family".equals(rule.scope()) || rule.family().equalsIgnoreCase(model.family()));
        }
        return names.stream().anyMatch(name -> matchesModel(rule.modelPattern(), name))
            || (!rule.modelPattern().contains("*") && (normalizeModelId(rule.modelPattern()).equals(requested)
                || normalizeModelId(rule.modelPattern()).equals(target)));
    }

    private ModelDtos.ManagedModelResponse catalogModel(String id) {
        if (catalogProvider == null) return null;
        var models = catalogProvider.managedModels();
        var index = catalogIndex;
        if (index == null || index.models() != models) {
            var exact = new java.util.HashMap<String, ModelDtos.ManagedModelResponse>();
            var normalized = new java.util.HashMap<String, ModelDtos.ManagedModelResponse>();
            for (var model : models) {
                exact.put(clean(model.id()).toLowerCase(Locale.ROOT), model);
                normalized.putIfAbsent(normalizeModelId(model.id()), model);
            }
            index = new CatalogIndex(models, Map.copyOf(exact), Map.copyOf(normalized));
            catalogIndex = index;
        }
        var direct = index.exact().get(clean(id).toLowerCase(Locale.ROOT));
        return direct != null ? direct : index.normalized().get(normalizeModelId(id));
    }

    private record CatalogIndex(List<ModelDtos.ManagedModelResponse> models,
                                Map<String, ModelDtos.ManagedModelResponse> exact,
                                Map<String, ModelDtos.ManagedModelResponse> normalized) {}

    private BillingQuote quote(ResolvedPricing resolved, boolean fastMode) {
        var rates = resolved.rates();
        if (rates == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_PRICE_UNAVAILABLE",
            "Model price is unavailable; configure an explicit price or synchronize the official catalog");
        if (!fastMode) return rates;
        return new BillingQuote(rates.input().multiply(FAST_MODE_MULTIPLIER), rates.output().multiply(FAST_MODE_MULTIPLIER),
            rates.cacheRead().multiply(FAST_MODE_MULTIPLIER), rates.cacheWrite().multiply(FAST_MODE_MULTIPLIER),
            rates.fallback().multiply(FAST_MODE_MULTIPLIER));
    }

    private ModelDtos.ModelPublicPricing displayPricing(ResolvedPricing resolved) {
        if (resolved.rates() == null) return new ModelDtos.ModelPublicPricing("unavailable", null, null, null);
        var rates = resolved.rates();
        return resolved.total() ? totalPublicPricing(rates.input())
            : itemizedPublicPricing(rates.input(), rates.output(), rates.cacheWrite(), rates.cacheRead());
    }

    private String pricingTarget(String original, String targetHint) {
        String requested = normalizeModelId(original);
        String target = requested;
        String lookupId = clean(original);
        var visited = new java.util.HashSet<String>();
        while (visited.add(target)) {
            // Built-in routing targets may be compatibility fallbacks, not pricing aliases.
            if (officialModelPricingService.resolve(target, false).isPresent()) return target;
            String next = catalogPricingTarget(lookupId, target);
            if (target.equals(requested) && next.equals(target) && targetHint != null && !targetHint.isBlank()) next = targetHint;
            lookupId = next;
            next = normalizeModelId(next);
            if (next.equals(target)) return target;
            target = next;
        }
        return requested;
    }

    private String catalogPricingTarget(String original, String normalized) {
        if (catalogProvider == null) return normalized;
        var direct = catalogProvider.targetFor(original).or(() -> catalogProvider.targetFor(normalized));
        if (direct.isPresent()) return direct.get();
        // Include disabled models so admin previews and the managed catalog agree.
        return catalogProvider.managedModels().stream()
            .filter(model -> normalizeModelId(model.id()).equals(normalized))
            .map(ModelDtos.ManagedModelResponse::targetModelId)
            .filter(Objects::nonNull).findFirst().orElse(normalized);
    }

    private List<String> pricingNames(String original, String requested, String target) {
        var names = new java.util.LinkedHashSet<String>(List.of(clean(original).toLowerCase(Locale.ROOT), requested, target));
        for (String id : List.of(requested, target)) {
            names.add("starry/" + id);
            if (id.startsWith("gpt-") || id.startsWith("chatgpt-") || id.matches("o\\d.*") || id.startsWith("codex")) {
                names.add("openai/" + id);
            } else if (id.startsWith("claude-") || List.of("sonnet", "opus", "haiku").contains(id)) {
                names.add("anthropic/" + id);
            } else if (id.startsWith("deepseek")) {
                names.add("deepseek/" + id);
            } else if (id.startsWith("gemini") || id.startsWith("gemma")) {
                names.add("google/" + id);
                names.add("gemini/" + id);
            }
        }
        return List.copyOf(names);
    }

    private String normalizeModelId(String value) {
        String id = clean(value).toLowerCase(Locale.ROOT);
        var prefixes = List.of("starry/", "openai/", "anthropic/", "deepseek/", "google/", "gemini/", "openai-compatible/");
        boolean stripped;
        do {
            stripped = false;
            for (String prefix : prefixes) {
                if (id.startsWith(prefix)) { id = id.substring(prefix.length()); stripped = true; break; }
            }
        } while (stripped);
        if (id.endsWith("-fast") && (id.startsWith("gpt-") || id.startsWith("chatgpt-") || id.matches("o\\d.*"))) {
            id = id.substring(0, id.length() - 5);
        }
        String deepSeek = DeepSeekModelCatalog.canonicalModelId(id);
        return DeepSeekModelCatalog.defaultPricing(deepSeek).isPresent() ? deepSeek : id;
    }

    private record ResolvedPricing(String model, String source, String matchedPriceModel, BigDecimal multiplier,
                                   List<ModelPricingRule> matches, BillingQuote rates, boolean total) {}

    private ModelDtos.ModelPublicPricing itemizedPublicPricing(
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier,
        BigDecimal cacheWriteMultiplier,
        BigDecimal cacheReadMultiplier
    ) {
        return new ModelDtos.ModelPublicPricing(
            BILLING_MODE_INPUT_OUTPUT,
            null,
            tokensPer100Balance(inputMultiplier),
            tokensPer100Balance(outputMultiplier),
            tokensPer100Balance(cacheWriteMultiplier),
            tokensPer100Balance(cacheReadMultiplier),
            referenceTotalPricePerMillion(inputMultiplier, outputMultiplier),
            effectivePricePerMillion(inputMultiplier),
            effectivePricePerMillion(outputMultiplier),
            effectivePricePerMillion(cacheWriteMultiplier),
            effectivePricePerMillion(cacheReadMultiplier)
        );
    }

    private BigDecimal referenceTotalPricePerMillion(
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier
    ) {
        BigDecimal inputPrice = effectivePricePerMillion(inputMultiplier);
        BigDecimal outputPrice = effectivePricePerMillion(outputMultiplier);
        if (inputPrice == null) {
            return outputPrice;
        }
        if (outputPrice == null) {
            return inputPrice;
        }
        return inputPrice.add(outputPrice)
            .divide(BigDecimal.valueOf(2), PRICE_PER_MILLION_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal applyBillingMultiplier(BigDecimal officialCostPerToken, BigDecimal billingMultiplier) {
        return officialCostPerToken
            .multiply(safeBillingMultiplier(billingMultiplier))
            .setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal safeBillingMultiplier(BigDecimal billingMultiplier) {
        return positive(billingMultiplier)
            ? billingMultiplier
            : PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER;
    }

    private BigDecimal effectiveCacheWriteMultiplier(ModelPricingRule rule) {
        if (rule == null) {
            return null;
        }
        return positive(rule.cacheWriteMultiplier()) ? rule.cacheWriteMultiplier() : rule.inputMultiplier();
    }

    private BigDecimal effectiveCacheReadMultiplier(ModelPricingRule rule) {
        if (rule == null) {
            return null;
        }
        if (positive(rule.cacheReadMultiplier())) {
            return rule.cacheReadMultiplier();
        }
        return positive(rule.inputMultiplier())
            ? rule.inputMultiplier().multiply(DEFAULT_CACHE_READ_RATIO).setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP)
            : null;
    }

    private ModelDtos.ModelPublicPricing totalPublicPricing(BigDecimal multiplier) {
        BigDecimal safe = safeMultiplier(multiplier);
        return new ModelDtos.ModelPublicPricing(
            BILLING_MODE_TOTAL,
            tokensPer100Balance(safe),
            null,
            null,
            null,
            null,
            effectivePricePerMillion(safe),
            null,
            null,
            null,
            null
        );
    }

    private BigDecimal fallbackTokenMultiplier(BigDecimal priceMultiplier) {
        return UNKNOWN_MODEL_FALLBACK_MULTIPLIER
            .multiply(safeBillingMultiplier(priceMultiplier))
            .setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal safeMultiplier(BigDecimal multiplier) {
        return positive(multiplier) ? multiplier : UNKNOWN_MODEL_FALLBACK_MULTIPLIER;
    }

    private Mono<List<ModelPricingRule>> readRules() {
        return Mono.defer(() -> repository.findById(MODEL_PRICING_RULES))
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty("[]")
            .map(this::parseRules);
    }

    private Mono<List<ModelPricingRule>> rulesSnapshot() {
        return Mono.defer(() -> {
            long generation = cacheGeneration.get();
            List<ModelPricingRule> snapshot = cachedRules;
            if (snapshot != null && System.nanoTime() - cachedRulesAtNanos < RULES_CACHE_TTL_NANOS) return Mono.just(snapshot);
            return readRules().doOnNext(rules -> replaceCache(rules, generation));
        });
    }

    private List<ModelPricingRule> parseRules(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, RULE_LIST).stream()
                .filter(Objects::nonNull)
                .map(this::sanitizeStoredRule)
                .filter(Objects::nonNull)
                .sorted(ruleComparator())
                .toList();
        } catch (JacksonException e) {
            log.warn("Could not parse model pricing rules; falling back to empty rules", e);
            return List.of();
        }
    }

    private ModelPricingRule sanitizeStoredRule(ModelPricingRule rule) {
        String scope = clean(rule.scope()).isBlank() ? "model" : clean(rule.scope()).toLowerCase(Locale.ROOT);
        if (!List.of("model", "provider", "family").contains(scope)) return null;
        String provider = scopeProvider(rule.provider());
        String family = scopeFamily(provider, rule.family());
        if (!"model".equals(scope) && (provider.isBlank() || provider.length() > MAX_MODEL_PATTERN_LENGTH
            || ("family".equals(scope) && (family.isBlank() || family.length() > MAX_MODEL_PATTERN_LENGTH)))) return null;
        String pattern = "model".equals(scope) ? clean(rule.modelPattern()) : "*";
        if (pattern.isBlank() || pattern.length() > MAX_MODEL_PATTERN_LENGTH) {
            return null;
        }
        BigDecimal totalMultiplier = normalizeStoredMultiplier(rule.totalMultiplier());
        BigDecimal inputMultiplier = normalizeStoredMultiplier(rule.inputMultiplier());
        BigDecimal outputMultiplier = normalizeStoredMultiplier(rule.outputMultiplier());
        BigDecimal cacheWriteMultiplier = normalizeStoredMultiplier(rule.cacheWriteMultiplier());
        BigDecimal cacheReadMultiplier = normalizeStoredMultiplier(rule.cacheReadMultiplier());
        BigDecimal priceMultiplier = normalizeStoredPriceMultiplier(rule.priceMultiplier());
        if (!"model".equals(scope) && !positive(priceMultiplier)) return null;
        if (
            !positive(priceMultiplier)
                && !positive(totalMultiplier)
                && (!positive(inputMultiplier) || !positive(outputMultiplier))
        ) {
            return null;
        }
        return new ModelPricingRule(
            clean(rule.ruleId()).isBlank() ? Hashing.shortUuid() : clean(rule.ruleId()),
            rule.enabled(),
            pattern,
            priceMultiplier,
            inputMultiplier,
            outputMultiplier,
            cacheWriteMultiplier,
            cacheReadMultiplier,
            totalMultiplier,
            rule.priority() == null ? 100 : rule.priority(),
            rule.createdAt(),
            rule.updatedAt(),
            scope,
            "model".equals(scope) ? null : provider,
            "family".equals(scope) ? family : null
        );
    }

    private List<ModelPricingRule> normalizeRules(List<ModelDtos.UpdateModelPricingRuleRequest> requested,
                                                 Map<String, ModelPricingRule> previous) {
        if (requested.size() > MAX_RULES) throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_RULE_LIMIT_EXCEEDED", "Too many pricing rules");
        var patterns = new java.util.HashSet<List<String>>();
        var ids = new java.util.HashSet<String>();
        Instant now = Instant.now();
        return requested.stream().map(input -> {
            var rule = normalizeRule(input, previous, now);
            List<String> identity = "model".equals(rule.scope())
                ? List.of("model", rule.modelPattern().contains("*") ? clean(rule.modelPattern()).toLowerCase(Locale.ROOT)
                    : normalizeModelId(rule.modelPattern()))
                : List.of(rule.scope(), rule.provider().toLowerCase(Locale.ROOT), clean(rule.family()).toLowerCase(Locale.ROOT));
            if (!patterns.add(identity) || !ids.add(rule.ruleId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_RULE_DUPLICATE", "Duplicate pricing pattern or rule ID");
            }
            return rule;
        }).sorted(ruleComparator()).toList();
    }

    private ModelPricingRule normalizeRule(
        ModelDtos.UpdateModelPricingRuleRequest request,
        Map<String, ModelPricingRule> previousById,
        Instant now
    ) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_PATTERN_REQUIRED", "Model pattern is required");
        }
        String scope = clean(request.scope()).isBlank() ? "model" : clean(request.scope()).toLowerCase(Locale.ROOT);
        if (!List.of("model", "provider", "family").contains(scope)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_SCOPE_INVALID", "Unknown pricing scope");
        }
        String provider = scopeProvider(request.provider());
        String family = scopeFamily(provider, request.family());
        if (!"model".equals(scope) && (provider.isBlank() || provider.length() > MAX_MODEL_PATTERN_LENGTH
            || ("family".equals(scope) && (family.isBlank() || family.length() > MAX_MODEL_PATTERN_LENGTH)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_SCOPE_INVALID", "Provider and family must identify the pricing scope");
        }
        String pattern = "model".equals(scope) ? clean(request.modelPattern()) : "*";
        if (pattern.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_PATTERN_REQUIRED", "Model pattern is required");
        }
        if (pattern.length() > MAX_MODEL_PATTERN_LENGTH) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_PRICE_PATTERN_TOO_LONG",
                "Model pattern must be at most " + MAX_MODEL_PATTERN_LENGTH + " characters"
            );
        }
        BigDecimal priceMultiplier = normalizePriceMultiplier(request.priceMultiplier());
        if (!"model".equals(scope) && !positive(priceMultiplier)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_REQUIRED", "Scoped rules require priceMultiplier");
        }
        BigDecimal totalMultiplier = normalizeTotalRequestMultiplier(
            request.totalMultiplier(),
            request.totalPricePerMillionTokens()
        );
        BigDecimal inputMultiplier = normalizeRequestMultiplier(
            request.inputPricePerMillionTokens(),
            request.inputMultiplier(),
            "inputPricePerMillionTokens"
        );
        BigDecimal outputMultiplier = normalizeRequestMultiplier(
            request.outputPricePerMillionTokens(),
            request.outputMultiplier(),
            "outputPricePerMillionTokens"
        );
        BigDecimal cacheWriteMultiplier = normalizeRequestMultiplier(
            request.cacheWritePricePerMillionTokens(),
            request.cacheWriteMultiplier(),
            "cacheWritePricePerMillionTokens"
        );
        BigDecimal cacheReadMultiplier = normalizeRequestMultiplier(
            request.cacheReadPricePerMillionTokens(),
            request.cacheReadMultiplier(),
            "cacheReadPricePerMillionTokens"
        );
        if (
            !positive(priceMultiplier)
                && !positive(totalMultiplier)
                && (!positive(inputMultiplier) || !positive(outputMultiplier))
        ) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_PRICE_REQUIRED",
                "Configure priceMultiplier"
            );
        }
        if (positive(priceMultiplier)) {
            totalMultiplier = null;
            inputMultiplier = null;
            outputMultiplier = null;
            cacheWriteMultiplier = null;
            cacheReadMultiplier = null;
        }
        String requestedRuleId = clean(request.ruleId());
        if (request.priority() != null && (request.priority() < 0 || request.priority() > 10000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_PRIORITY_INVALID", "Priority must be between 0 and 10000");
        }
        String ruleId = requestedRuleId.isBlank() ? Hashing.shortUuid() : requestedRuleId;
        ModelPricingRule previous = previousById.get(ruleId);
        return new ModelPricingRule(
            ruleId,
            request.enabled() == null || Boolean.TRUE.equals(request.enabled()),
            pattern,
            priceMultiplier,
            inputMultiplier,
            outputMultiplier,
            cacheWriteMultiplier,
            cacheReadMultiplier,
            totalMultiplier,
            request.priority() == null ? 100 : request.priority(),
            previous == null || previous.createdAt() == null ? now : previous.createdAt(),
            now,
            scope,
            "model".equals(scope) ? null : provider,
            "family".equals(scope) ? family : null
        );
    }

    private ModelDtos.ModelPricingResponse toResponse(List<ModelPricingRule> rules) {
        return new ModelDtos.ModelPricingResponse(
            rules.stream()
                .sorted(ruleComparator())
                .map(rule -> new ModelDtos.ModelPricingRuleResponse(
                    rule.ruleId(),
                    rule.enabled(),
                    rule.modelPattern(),
                    rule.priceMultiplier(),
                    rule.inputMultiplier(),
                    rule.outputMultiplier(),
                    rule.cacheWriteMultiplier(),
                    rule.cacheReadMultiplier(),
                    rule.totalMultiplier(),
                    pricePerMillion(rule.inputMultiplier()),
                    pricePerMillion(rule.outputMultiplier()),
                    pricePerMillion(rule.cacheWriteMultiplier()),
                    pricePerMillion(rule.cacheReadMultiplier()),
                    pricePerMillion(rule.totalMultiplier()),
                    rule.priority(),
                    rule.createdAt(),
                    rule.updatedAt(),
                    rule.scope(),
                    rule.provider(),
                    rule.family()
                ))
                .toList()
        );
    }

    private String scopeProvider(String value) {
        String provider = PlatformModelCatalogSupport.canonicalCatalogProvider(clean(value));
        if (catalogProvider == null || provider.isBlank()) return provider;
        return catalogProvider.managedModels().stream().map(ModelDtos.ManagedModelResponse::provider)
            .filter(candidate -> PlatformModelCatalogSupport.sameCatalogProvider(candidate, provider))
            .findFirst().orElse(provider);
    }

    private String scopeFamily(String provider, String value) {
        String family = clean(value);
        if (catalogProvider == null || family.isBlank()) return family;
        return catalogProvider.managedModels().stream()
            .filter(model -> PlatformModelCatalogSupport.sameCatalogProvider(model.provider(), provider))
            .map(ModelDtos.ManagedModelResponse::family).filter(family::equalsIgnoreCase)
            .findFirst().orElse(family);
    }

    private synchronized void replaceCache(List<ModelPricingRule> rules, long generation) {
        if (cacheGeneration.get() != generation) {
            return;
        }
        cachedRules = List.copyOf(rules == null ? List.of() : rules);
        cachedRulesAtNanos = System.nanoTime();
    }

    private Comparator<ModelPricingRule> ruleComparator() {
        return Comparator
            .comparing((ModelPricingRule rule) -> Objects.requireNonNullElse(rule.priority(), 100))
            .thenComparingInt(rule -> switch (Objects.requireNonNullElse(rule.scope(), "model")) {
                case "family" -> 1;
                case "provider" -> 2;
                default -> rule.modelPattern().contains("*") ? 3 : 0;
            })
            .thenComparingInt(rule -> rule.modelPattern().contains("*") ? -rule.modelPattern().replace("*", "").length() : 0);
    }

    private boolean matchesModel(String pattern, String model) {
        String safePattern = clean(pattern);
        String safeModel = clean(model);
        if (safePattern.isBlank() || safeModel.isBlank()) {
            return false;
        }
        if ("*".equals(safePattern)) {
            return true;
        }
        if (!safePattern.contains("*")) {
            return safePattern.equalsIgnoreCase(safeModel);
        }
        return wildcardMatches(safePattern.toLowerCase(Locale.ROOT), safeModel.toLowerCase(Locale.ROOT));
    }

    private boolean wildcardMatches(String pattern, String model) {
        int patternIndex = 0;
        int modelIndex = 0;
        int starIndex = -1;
        int matchAfterStar = 0;

        while (modelIndex < model.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex++;
                matchAfterStar = modelIndex;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == model.charAt(modelIndex)) {
                patternIndex++;
                modelIndex++;
            } else if (starIndex != -1) {
                patternIndex = starIndex + 1;
                modelIndex = ++matchAfterStar;
            } else {
                return false;
            }
        }

        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    private BigDecimal normalizeRequestMultiplier(BigDecimal pricePerMillion, BigDecimal multiplier, String fieldName) {
        BigDecimal value = pricePerMillion == null ? multiplier : pricePerMillion.divide(TOKENS_PER_MILLION, MULTIPLIER_SCALE, RoundingMode.HALF_UP);
        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        if (value.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_INVALID", fieldName + " must not be negative");
        }
        return value.setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeTotalRequestMultiplier(BigDecimal multiplier, BigDecimal legacyPricePerMillion) {
        if (multiplier != null) {
            return normalizeRequestMultiplier(null, multiplier, "totalMultiplier");
        }
        return normalizeRequestMultiplier(legacyPricePerMillion, null, "totalPricePerMillionTokens");
    }

    private BigDecimal normalizeStoredMultiplier(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0
            ? null
            : value.setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizePriceMultiplier(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(MAX_PRICE_MULTIPLIER) > 0) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_PRICE_MULTIPLIER_INVALID",
                "priceMultiplier must be greater than 0 and at most " + MAX_PRICE_MULTIPLIER
            );
        }
        BigDecimal rounded = value.setScale(6, RoundingMode.HALF_UP);
        if (rounded.signum() == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_MULTIPLIER_INVALID", "Minimum multiplier is 0.000001");
        return rounded;
    }

    private BigDecimal normalizeStoredPriceMultiplier(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(MAX_PRICE_MULTIPLIER) > 0) {
            return null;
        }
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal pricePerMillion(BigDecimal multiplier) {
        return positive(multiplier)
            ? multiplier.multiply(TOKENS_PER_MILLION).setScale(PRICE_PER_MILLION_SCALE, RoundingMode.HALF_UP)
            : null;
    }

    private BigDecimal effectivePricePerMillion(BigDecimal multiplier) {
        return multiplier != null && multiplier.signum() >= 0
            ? multiplier.multiply(TOKENS_PER_MILLION).setScale(PRICE_PER_MILLION_SCALE, RoundingMode.HALF_UP)
            : null;
    }

    private BigDecimal tokensPer100Balance(BigDecimal multiplier) {
        if (!positive(multiplier)) {
            return null;
        }
        return DISPLAY_BALANCE_UNIT.divide(multiplier, TOKENS_PER_100_BALANCE_SCALE, RoundingMode.HALF_UP);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String toJson(List<ModelPricingRule> rules) {
        try {
            return objectMapper.writeValueAsString(rules == null ? List.of() : rules);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MODEL_PRICE_SERIALIZE_FAILED", "Could not save model pricing rules");
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    record ModelPricingRule(
        String ruleId,
        Boolean enabled,
        String modelPattern,
        BigDecimal priceMultiplier,
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier,
        BigDecimal cacheWriteMultiplier,
        BigDecimal cacheReadMultiplier,
        BigDecimal totalMultiplier,
        Integer priority,
        Instant createdAt,
        Instant updatedAt,
        String scope,
        String provider,
        String family
    ) {
    }
}
