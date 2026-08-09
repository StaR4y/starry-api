package tech.starryapi.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

@Service
public class ModelPricingService {

    static final String MODEL_PRICING_RULES = "MODEL_PRICING_RULES";
    static final int MAX_RULES = 200;
    static final int MAX_MODEL_PATTERN_LENGTH = 256;

    private static final Logger log = LoggerFactory.getLogger(ModelPricingService.class);
    private static final TypeReference<List<ModelPricingRule>> RULE_LIST = new TypeReference<>() {};
    private static final int MULTIPLIER_SCALE = 16;
    private static final int PRICE_PER_MILLION_SCALE = 10;
    private static final int TOKENS_PER_100_BALANCE_SCALE = 0;
    private static final BigDecimal DISPLAY_BALANCE_UNIT = BigDecimal.valueOf(100);
    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal FAST_MODE_MULTIPLIER = BigDecimal.valueOf(2);
    private static final BigDecimal DEFAULT_CACHE_READ_RATIO = new BigDecimal("0.1");
    private static final String BILLING_MODE_TOTAL = "total";
    private static final String BILLING_MODE_INPUT_OUTPUT = "input_output";

    private final SystemSettingRepository repository;
    private final ObjectMapper objectMapper;
    private final AtomicLong cacheGeneration = new AtomicLong();
    private volatile List<ModelPricingRule> cachedRules = List.of();
    private volatile boolean cacheLoaded = false;

    public ModelPricingService(SystemSettingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
        long generation = cacheGeneration.get();
        return readRules()
            .doOnNext(rules -> replaceCache(rules, generation))
            .map(this::toResponse);
    }

    public Mono<ModelDtos.ModelPricingResponse> update(ModelDtos.UpdateModelPricingRequest request) {
        long generation = cacheGeneration.get();
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
                Instant now = Instant.now();
                Map<String, ModelPricingRule> previousById = previous.stream()
                    .collect(Collectors.toMap(ModelPricingRule::ruleId, Function.identity(), (left, right) -> left));
                List<ModelPricingRule> next = requestedRules.stream()
                    .map(rule -> normalizeRule(rule, previousById, now))
                    .sorted(ruleComparator())
                    .toList();
                String json = toJson(next);
                return repository.upsertSetting(MODEL_PRICING_RULES, json)
                    .then(Mono.fromRunnable(() -> replaceCache(next, generation)))
                    .thenReturn(toResponse(next));
            });
    }

    public Mono<BigDecimal> cost(
        String model,
        PlatformUsage usage,
        boolean fastMode,
        BigDecimal fallbackMultiplier
    ) {
        if (cacheLoaded) {
            return Mono.just(cost(cachedRules, model, usage, fastMode, fallbackMultiplier));
        }
        long generation = cacheGeneration.get();
        return readRules()
            .doOnNext(rules -> replaceCache(rules, generation))
            .map(rules -> cost(rules, model, usage, fastMode, fallbackMultiplier));
    }

    public Mono<ModelDtos.ModelListResponse> applyPublicPricing(
        ModelDtos.ModelListResponse response,
        BigDecimal fallbackMultiplier
    ) {
        ModelDtos.ModelListResponse safeResponse = response == null
            ? new ModelDtos.ModelListResponse("list", List.of(), List.of())
            : response;
        if (cacheLoaded) {
            return Mono.just(applyPublicPricing(safeResponse, cachedRules, fallbackMultiplier));
        }
        long generation = cacheGeneration.get();
        return readRules()
            .doOnNext(rules -> replaceCache(rules, generation))
            .map(rules -> applyPublicPricing(safeResponse, rules, fallbackMultiplier));
    }

    public Mono<Void> refresh() {
        return Mono.defer(() -> {
            long generation = cacheGeneration.get();
            return readRules()
                .doOnNext(rules -> replaceCache(rules, generation))
                .then();
        });
    }

    public void invalidateAllCaches() {
        cacheGeneration.incrementAndGet();
        cachedRules = List.of();
        cacheLoaded = false;
    }

    public void invalidateCacheForSetting(String settingKey) {
        if (MODEL_PRICING_RULES.equals(settingKey)) {
            invalidateAllCaches();
        }
    }

    BigDecimal cost(
        List<ModelPricingRule> rules,
        String model,
        PlatformUsage usage,
        boolean fastMode,
        BigDecimal fallbackMultiplier
    ) {
        PlatformUsage safeUsage = usage == null ? PlatformUsage.zero() : usage;
        String canonicalModel = canonicalPricingModel(model);
        return rules.stream()
            .filter(rule -> rule.enabled() == null || rule.enabled())
            .filter(rule -> matchesPricingModel(rule.modelPattern(), model, canonicalModel))
            .findFirst()
            .map(rule -> ruleCost(rule, safeUsage, fastMode, fallbackMultiplier))
            .orElseGet(() -> defaultModelCost(canonicalModel, safeUsage, fastMode, fallbackMultiplier));
    }

    ModelDtos.ModelPublicPricing publicPricing(
        List<ModelPricingRule> rules,
        String model,
        BigDecimal fallbackMultiplier
    ) {
        String canonicalModel = canonicalPricingModel(model);
        ModelPricingRule matchedRule = rules.stream()
            .filter(rule -> rule.enabled() == null || rule.enabled())
            .filter(rule -> matchesPricingModel(rule.modelPattern(), model, canonicalModel))
            .findFirst()
            .orElse(null);
        if (matchedRule == null) {
            return DeepSeekModelCatalog.defaultPricing(canonicalModel)
                .map(this::defaultPublicPricing)
                .orElseGet(() -> totalPublicPricing(fallbackMultiplier));
        }
        if (matchedRule != null && positive(matchedRule.totalMultiplier())) {
            return totalPublicPricing(matchedRule.totalMultiplier());
        }
        if (matchedRule != null && positive(matchedRule.inputMultiplier()) && positive(matchedRule.outputMultiplier())) {
            return new ModelDtos.ModelPublicPricing(
                BILLING_MODE_INPUT_OUTPUT,
                null,
                tokensPer100Balance(matchedRule.inputMultiplier()),
                tokensPer100Balance(matchedRule.outputMultiplier()),
                tokensPer100Balance(effectiveCacheWriteMultiplier(matchedRule)),
                tokensPer100Balance(effectiveCacheReadMultiplier(matchedRule))
            );
        }
        return totalPublicPricing(fallbackMultiplier);
    }

    private ModelDtos.ModelListResponse applyPublicPricing(
        ModelDtos.ModelListResponse response,
        List<ModelPricingRule> rules,
        BigDecimal fallbackMultiplier
    ) {
        List<ModelDtos.ModelListItem> items = response.data().stream()
            .map(item -> item.withPricing(publicPricing(rules, item.id(), fallbackMultiplier)))
            .toList();
        return new ModelDtos.ModelListResponse(response.object(), items, items);
    }

    private BigDecimal ruleCost(
        ModelPricingRule rule,
        PlatformUsage usage,
        boolean fastMode,
        BigDecimal fallbackMultiplier
    ) {
        if (positive(rule.totalMultiplier())) {
            return totalCost(usage, fastMode, rule.totalMultiplier());
        }
        if (billableTokenUnits(usage).signum() <= 0) {
            return totalCost(usage, fastMode, fallbackMultiplier);
        }
        return itemizedCost(
            usage,
            rule.inputMultiplier(),
            rule.outputMultiplier(),
            effectiveCacheWriteMultiplier(rule),
            effectiveCacheReadMultiplier(rule),
            fastMode
        );
    }

    private BigDecimal defaultModelCost(
        String model,
        PlatformUsage usage,
        boolean fastMode,
        BigDecimal fallbackMultiplier
    ) {
        return DeepSeekModelCatalog.defaultPricing(model)
            .map(pricing -> itemizedCost(
                usage,
                perTokenMultiplier(pricing.inputPricePerMillionTokens()),
                perTokenMultiplier(pricing.outputPricePerMillionTokens()),
                perTokenMultiplier(pricing.cacheWritePricePerMillionTokens()),
                perTokenMultiplier(pricing.cacheReadPricePerMillionTokens()),
                fastMode
            ))
            .orElseGet(() -> totalCost(usage, fastMode, fallbackMultiplier));
    }

    private BigDecimal itemizedCost(
        PlatformUsage usage,
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier,
        BigDecimal cacheWriteMultiplier,
        BigDecimal cacheReadMultiplier,
        boolean fastMode
    ) {
        long cacheReadTokens = Math.max(0, usage.cacheReadTokens());
        long cacheWriteTokens = Math.max(0, usage.cacheWriteTokens());
        long uncachedInputTokens = Math.max(0, usage.inputTokens() - cacheReadTokens - cacheWriteTokens);
        BigDecimal inputCost = tokenCost(uncachedInputTokens, inputMultiplier);
        BigDecimal outputCost = tokenCost(usage.outputTokens(), outputMultiplier);
        BigDecimal cacheWriteCost = tokenCost(cacheWriteTokens, cacheWriteMultiplier);
        BigDecimal cacheReadCost = tokenCost(cacheReadTokens, cacheReadMultiplier);
        BigDecimal cost = inputCost.add(outputCost).add(cacheWriteCost).add(cacheReadCost);
        if (fastMode) {
            cost = cost.multiply(FAST_MODE_MULTIPLIER);
        }
        return cost.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal totalCost(PlatformUsage usage, boolean fastMode, BigDecimal multiplier) {
        BigDecimal units = billableTokenUnits(usage).signum() > 0
            ? billableTokenUnits(usage)
            : usage.credits() == null ? BigDecimal.ZERO : usage.credits();
        if (fastMode) {
            units = units.multiply(FAST_MODE_MULTIPLIER);
        }
        BigDecimal safeMultiplier = positive(multiplier)
            ? multiplier
            : PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER;
        return units.multiply(safeMultiplier).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal tokenCost(long tokens, BigDecimal multiplier) {
        if (tokens <= 0 || !positive(multiplier)) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens).multiply(multiplier);
    }

    private BigDecimal billableTokenUnits(PlatformUsage usage) {
        if (usage == null) {
            return BigDecimal.ZERO;
        }
        long units = Math.max(0, usage.inputTokens())
            + Math.max(0, usage.outputTokens());
        return BigDecimal.valueOf(units);
    }

    private BigDecimal perTokenMultiplier(BigDecimal pricePerMillionTokens) {
        return pricePerMillionTokens.divide(TOKENS_PER_MILLION, MULTIPLIER_SCALE, RoundingMode.HALF_UP);
    }

    private ModelDtos.ModelPublicPricing defaultPublicPricing(DeepSeekModelCatalog.DefaultPricing pricing) {
        return new ModelDtos.ModelPublicPricing(
            BILLING_MODE_INPUT_OUTPUT,
            null,
            tokensPer100Balance(perTokenMultiplier(pricing.inputPricePerMillionTokens())),
            tokensPer100Balance(perTokenMultiplier(pricing.outputPricePerMillionTokens())),
            tokensPer100Balance(perTokenMultiplier(pricing.cacheWritePricePerMillionTokens())),
            tokensPer100Balance(perTokenMultiplier(pricing.cacheReadPricePerMillionTokens()))
        );
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
        return new ModelDtos.ModelPublicPricing(
            BILLING_MODE_TOTAL,
            tokensPer100Balance(safeMultiplier(multiplier)),
            null,
            null
        );
    }

    private BigDecimal safeMultiplier(BigDecimal multiplier) {
        return positive(multiplier) ? multiplier : PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER;
    }

    private Mono<List<ModelPricingRule>> readRules() {
        return repository.findById(MODEL_PRICING_RULES)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty("[]")
            .map(this::parseRules);
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
        } catch (JsonProcessingException e) {
            log.warn("Could not parse model pricing rules; falling back to empty rules", e);
            return List.of();
        }
    }

    private ModelPricingRule sanitizeStoredRule(ModelPricingRule rule) {
        String pattern = clean(rule.modelPattern());
        if (pattern.isBlank() || pattern.length() > MAX_MODEL_PATTERN_LENGTH) {
            return null;
        }
        BigDecimal totalMultiplier = normalizeStoredMultiplier(rule.totalMultiplier());
        BigDecimal inputMultiplier = normalizeStoredMultiplier(rule.inputMultiplier());
        BigDecimal outputMultiplier = normalizeStoredMultiplier(rule.outputMultiplier());
        BigDecimal cacheWriteMultiplier = normalizeStoredMultiplier(rule.cacheWriteMultiplier());
        BigDecimal cacheReadMultiplier = normalizeStoredMultiplier(rule.cacheReadMultiplier());
        if (!positive(totalMultiplier) && (!positive(inputMultiplier) || !positive(outputMultiplier))) {
            return null;
        }
        return new ModelPricingRule(
            clean(rule.ruleId()).isBlank() ? Hashing.shortUuid() : clean(rule.ruleId()),
            rule.enabled(),
            pattern,
            inputMultiplier,
            outputMultiplier,
            cacheWriteMultiplier,
            cacheReadMultiplier,
            totalMultiplier,
            rule.priority() == null ? 100 : rule.priority(),
            rule.createdAt(),
            rule.updatedAt()
        );
    }

    private ModelPricingRule normalizeRule(
        ModelDtos.UpdateModelPricingRuleRequest request,
        Map<String, ModelPricingRule> previousById,
        Instant now
    ) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_PATTERN_REQUIRED", "Model pattern is required");
        }
        String pattern = clean(request.modelPattern());
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
        if (!positive(totalMultiplier) && (!positive(inputMultiplier) || !positive(outputMultiplier))) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_PRICE_REQUIRED",
                "Configure totalMultiplier, or both inputPricePerMillionTokens and outputPricePerMillionTokens"
            );
        }
        String requestedRuleId = clean(request.ruleId());
        String ruleId = requestedRuleId.isBlank() ? Hashing.shortUuid() : requestedRuleId;
        ModelPricingRule previous = previousById.get(ruleId);
        return new ModelPricingRule(
            ruleId,
            request.enabled() == null || Boolean.TRUE.equals(request.enabled()),
            pattern,
            inputMultiplier,
            outputMultiplier,
            cacheWriteMultiplier,
            cacheReadMultiplier,
            totalMultiplier,
            request.priority() == null ? 100 : request.priority(),
            previous == null || previous.createdAt() == null ? now : previous.createdAt(),
            now
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
                    rule.updatedAt()
                ))
                .toList()
        );
    }

    private void replaceCache(List<ModelPricingRule> rules, long generation) {
        if (cacheGeneration.get() != generation) {
            return;
        }
        cachedRules = List.copyOf(rules == null ? List.of() : rules);
        cacheLoaded = true;
    }

    private Comparator<ModelPricingRule> ruleComparator() {
        return Comparator
            .comparing((ModelPricingRule rule) -> Objects.requireNonNullElse(rule.priority(), 100))
            .thenComparing(ModelPricingRule::modelPattern, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ModelPricingRule::ruleId);
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

    private boolean matchesPricingModel(String pattern, String model, String canonicalModel) {
        return matchesModel(pattern, model)
            || (!clean(model).equalsIgnoreCase(clean(canonicalModel)) && matchesModel(pattern, canonicalModel));
    }

    private String canonicalPricingModel(String model) {
        String canonicalModel = DeepSeekModelCatalog.canonicalModelId(model);
        return DeepSeekModelCatalog.defaultPricing(canonicalModel).isPresent() ? canonicalModel : model;
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

    private BigDecimal pricePerMillion(BigDecimal multiplier) {
        return positive(multiplier)
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
        } catch (JsonProcessingException e) {
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
        BigDecimal inputMultiplier,
        BigDecimal outputMultiplier,
        BigDecimal cacheWriteMultiplier,
        BigDecimal cacheReadMultiplier,
        BigDecimal totalMultiplier,
        Integer priority,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
