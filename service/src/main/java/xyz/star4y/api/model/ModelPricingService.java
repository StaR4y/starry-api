package xyz.star4y.api.model;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import xyz.star4y.api.admin.SystemSettingEntity;
import xyz.star4y.api.admin.SystemSettingRepository;
import xyz.star4y.api.common.ApiException;
import xyz.star4y.api.common.Hashing;
import xyz.star4y.api.payment.PaymentSettingsService;
import xyz.star4y.api.platform.core.PlatformUsage;

@Service
public class ModelPricingService {

    static final String MODEL_PRICING_RULES = "MODEL_PRICING_RULES";
    static final int MAX_RULES = 200;
    static final int MAX_MODEL_PATTERN_LENGTH = 256;

    private static final Logger log = LoggerFactory.getLogger(ModelPricingService.class);
    private static final TypeReference<List<ModelPricingRule>> RULE_LIST = new TypeReference<>() {};
    private static final int MULTIPLIER_SCALE = 16;

    private final SystemSettingRepository repository;
    private final ObjectMapper objectMapper;
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
        return readRules()
            .doOnNext(this::replaceCache)
            .map(this::toResponse);
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
                Instant now = Instant.now();
                Map<String, ModelPricingRule> previousById = previous.stream()
                    .collect(Collectors.toMap(ModelPricingRule::ruleId, Function.identity(), (left, right) -> left));
                List<ModelPricingRule> next = requestedRules.stream()
                    .map(rule -> normalizeRule(rule, previousById, now))
                    .sorted(ruleComparator())
                    .toList();
                String json = toJson(next);
                return repository.upsertSetting(MODEL_PRICING_RULES, json)
                    .then(Mono.fromRunnable(() -> replaceCache(next)))
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
        return readRules()
            .doOnNext(this::replaceCache)
            .map(rules -> cost(rules, model, usage, fastMode, fallbackMultiplier));
    }

    Mono<Void> refresh() {
        return readRules()
            .doOnNext(this::replaceCache)
            .then();
    }

    BigDecimal cost(
        List<ModelPricingRule> rules,
        String model,
        PlatformUsage usage,
        boolean fastMode,
        BigDecimal fallbackMultiplier
    ) {
        PlatformUsage safeUsage = usage == null ? PlatformUsage.zero() : usage;
        return rules.stream()
            .filter(rule -> rule.enabled() == null || rule.enabled())
            .filter(rule -> matchesModel(rule.modelPattern(), model))
            .findFirst()
            .map(rule -> ruleCost(rule, safeUsage, fastMode, fallbackMultiplier))
            .orElseGet(() -> totalCost(safeUsage, fastMode, fallbackMultiplier));
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
        if (usage.totalTokens() <= 0) {
            return totalCost(usage, fastMode, fallbackMultiplier);
        }
        BigDecimal inputCost = BigDecimal.valueOf(Math.max(0, usage.inputTokens())).multiply(rule.inputMultiplier());
        BigDecimal outputCost = BigDecimal.valueOf(Math.max(0, usage.outputTokens())).multiply(rule.outputMultiplier());
        BigDecimal cost = inputCost.add(outputCost);
        if (fastMode) {
            cost = cost.multiply(BigDecimal.valueOf(2));
        }
        return cost.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal totalCost(PlatformUsage usage, boolean fastMode, BigDecimal multiplier) {
        BigDecimal units = usage.totalTokens() > 0
            ? BigDecimal.valueOf(usage.totalTokens())
            : usage.credits() == null ? BigDecimal.ZERO : usage.credits();
        if (fastMode) {
            units = units.multiply(BigDecimal.valueOf(2));
        }
        BigDecimal safeMultiplier = positive(multiplier)
            ? multiplier
            : PaymentSettingsService.DEFAULT_API_BILLING_MULTIPLIER;
        return units.multiply(safeMultiplier).setScale(6, RoundingMode.HALF_UP);
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
        if (!positive(totalMultiplier) && (!positive(inputMultiplier) || !positive(outputMultiplier))) {
            return null;
        }
        return new ModelPricingRule(
            clean(rule.ruleId()).isBlank() ? Hashing.shortUuid() : clean(rule.ruleId()),
            rule.enabled(),
            pattern,
            inputMultiplier,
            outputMultiplier,
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
        BigDecimal totalMultiplier = normalizeRequestMultiplier(request.totalMultiplier(), "totalMultiplier");
        BigDecimal inputMultiplier = normalizeRequestMultiplier(request.inputMultiplier(), "inputMultiplier");
        BigDecimal outputMultiplier = normalizeRequestMultiplier(request.outputMultiplier(), "outputMultiplier");
        if (!positive(totalMultiplier) && (!positive(inputMultiplier) || !positive(outputMultiplier))) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_PRICE_REQUIRED",
                "Configure totalMultiplier, or both inputMultiplier and outputMultiplier"
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
                    rule.totalMultiplier(),
                    rule.priority(),
                    rule.createdAt(),
                    rule.updatedAt()
                ))
                .toList()
        );
    }

    private void replaceCache(List<ModelPricingRule> rules) {
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

    private BigDecimal normalizeRequestMultiplier(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        if (value.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PRICE_INVALID", fieldName + " must not be negative");
        }
        return value.setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeStoredMultiplier(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0
            ? null
            : value.setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP);
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
        BigDecimal totalMultiplier,
        Integer priority,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
