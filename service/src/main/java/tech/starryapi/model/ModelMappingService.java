package tech.starryapi.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.Hashing;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ModelMappingService {

    private static final Logger log = LoggerFactory.getLogger(ModelMappingService.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Integer>> INTEGER_LIST = new TypeReference<>() {};
    static final int MAX_MODEL_PATTERN_LENGTH = 256;

    private final ModelMappingRepository repository;
    private final ObjectMapper objectMapper;
    private final AtomicLong cacheGeneration = new AtomicLong();
    private volatile List<ModelMappingEntity> cachedRules = List.of();

    public ModelMappingService(ModelMappingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCache() {
        refresh().subscribe(null, error -> log.warn("Failed to warm up model mapping cache", error));
    }

    @Scheduled(fixedDelay = 30_000)
    public void scheduledRefresh() {
        refresh().subscribe(null, error -> log.warn("Failed to refresh model mapping cache", error));
    }

    public Mono<List<ModelDtos.MappingResponse>> list() {
        return repository.findAll()
            .sort(Comparator.comparing(rule -> Objects.requireNonNullElse(rule.getPriority(), 100)))
            .map(this::toResponse)
            .collectList();
    }

    public Mono<ModelDtos.MappingResponse> create(ModelDtos.CreateMappingRequest request) {
        validateSourceModel(request.sourceModel());
        ModelMappingEntity entity = new ModelMappingEntity();
        entity.setMappingId(Hashing.shortUuid());
        entity.setName(request.name());
        entity.setEnabled(true);
        entity.setMappingType(request.mappingType());
        entity.setSourceModel(request.sourceModel());
        entity.setTargetModels(toJson(request.targetModels()));
        entity.setWeights(toJson(request.weights()));
        entity.setPriority(Objects.requireNonNullElse(request.priority(), 100));
        entity.setApiKeyIds(toJson(request.apiKeyIds()));
        return repository.save(entity)
            .flatMap(saved -> refresh().thenReturn(toResponse(saved)));
    }

    public Mono<ModelDtos.MappingResponse> update(String mappingId, ModelDtos.UpdateMappingRequest request) {
        return repository.findByMappingId(mappingId)
            .switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "MAPPING_NOT_FOUND", "Model mapping not found")))
            .flatMap(entity -> {
                if (request.name() != null) entity.setName(request.name());
                if (request.enabled() != null) entity.setEnabled(request.enabled());
                if (request.mappingType() != null) entity.setMappingType(request.mappingType());
                if (request.sourceModel() != null) {
                    validateSourceModel(request.sourceModel());
                    entity.setSourceModel(request.sourceModel());
                }
                if (request.targetModels() != null) entity.setTargetModels(toJson(request.targetModels()));
                if (request.weights() != null) entity.setWeights(toJson(request.weights()));
                if (request.priority() != null) entity.setPriority(request.priority());
                if (request.apiKeyIds() != null) entity.setApiKeyIds(toJson(request.apiKeyIds()));
                return repository.save(entity);
            })
            .flatMap(saved -> refresh().thenReturn(toResponse(saved)));
    }

    public Mono<Void> delete(String mappingId) {
        return repository.deleteByMappingId(mappingId).then(refresh());
    }

    public String apply(String sourceModel, String apiKeyId) {
        for (ModelMappingEntity rule : cachedRules) {
            if (!matchesApiKey(rule, apiKeyId) || !matchesModel(rule.getSourceModel(), sourceModel)) {
                continue;
            }
            List<String> targets = parseStringList(rule.getTargetModels());
            if (targets.isEmpty()) {
                continue;
            }
            String type = Objects.requireNonNullElse(rule.getMappingType(), "replace").toLowerCase();
            if ("loadbalance".equals(type)) {
                return weightedPick(targets, parseIntegerList(rule.getWeights()));
            }
            return targets.get(0);
        }
        return sourceModel;
    }

    public Mono<Void> refresh() {
        return Mono.defer(() -> {
            long generation = cacheGeneration.get();
            return repository.findAllByEnabledTrueOrderByPriorityAsc()
                .collectList()
                .doOnNext(rules -> {
                    if (cacheGeneration.get() == generation) {
                        cachedRules = List.copyOf(rules);
                    }
                })
                .then();
        });
    }

    public void invalidateAllCaches() {
        cacheGeneration.incrementAndGet();
        cachedRules = List.of();
    }

    private boolean matchesApiKey(ModelMappingEntity rule, String apiKeyId) {
        List<String> ids = parseStringList(rule.getApiKeyIds());
        return ids.isEmpty() || ids.contains(apiKeyId);
    }

    private boolean matchesModel(String pattern, String model) {
        if (pattern == null || model == null) {
            return false;
        }
        if (pattern.length() > MAX_MODEL_PATTERN_LENGTH || model.length() > MAX_MODEL_PATTERN_LENGTH) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        if (!pattern.contains("*")) {
            return pattern.equalsIgnoreCase(model);
        }
        return wildcardMatches(pattern, model);
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
            } else if (patternIndex < pattern.length()
                && equalsIgnoreCase(pattern.charAt(patternIndex), model.charAt(modelIndex))) {
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

    private boolean equalsIgnoreCase(char left, char right) {
        return Character.toLowerCase(left) == Character.toLowerCase(right);
    }

    private void validateSourceModel(String sourceModel) {
        if (sourceModel != null && sourceModel.length() > MAX_MODEL_PATTERN_LENGTH) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_PATTERN_TOO_LONG",
                "sourceModel must be at most " + MAX_MODEL_PATTERN_LENGTH + " characters"
            );
        }
    }

    private String weightedPick(List<String> targets, List<Integer> weights) {
        if (weights.size() != targets.size()) {
            return targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        }
        int total = weights.stream().mapToInt(value -> Math.max(value, 0)).sum();
        if (total <= 0) {
            return targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        }
        int cursor = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < targets.size(); i++) {
            cursor -= Math.max(weights.get(i), 0);
            if (cursor < 0) {
                return targets.get(i);
            }
        }
        return targets.getLast();
    }

    private ModelDtos.MappingResponse toResponse(ModelMappingEntity entity) {
        return new ModelDtos.MappingResponse(
            entity.getMappingId(),
            entity.getName(),
            entity.getEnabled(),
            entity.getMappingType(),
            entity.getSourceModel(),
            parseStringList(entity.getTargetModels()),
            parseIntegerList(entity.getWeights()),
            entity.getPriority(),
            parseStringList(entity.getApiKeyIds()),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private List<Integer> parseIntegerList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, INTEGER_LIST);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "JSON_ENCODE_ERROR", "Invalid JSON field");
        }
    }
}
