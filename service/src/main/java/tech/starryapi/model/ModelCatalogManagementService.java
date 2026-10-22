package tech.starryapi.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ModelCatalogManagementService {

    static final String MODEL_CATALOG_CUSTOMIZATIONS = "MODEL_CATALOG_CUSTOMIZATIONS";
    static final String MODEL_CATALOG_ORDER = "MODEL_CATALOG_ORDER";
    static final String MODEL_CATALOG_DISPLAY = "MODEL_CATALOG_DISPLAY";
    static final String OPENAI_CHAT_COMPLETIONS = "OPENAI_CHAT_COMPLETIONS";
    static final String DEFAULT_ENDPOINT = "/v1/chat/completions";

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogManagementService.class);
    private static final TypeReference<List<ModelCatalogCustomization>> CUSTOMIZATION_LIST = new TypeReference<>() {};
    private static final TypeReference<List<String>> ORDER_LIST = new TypeReference<>() {};
    private static final Pattern MODEL_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}$");
    private static final Pattern BADGE_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Set<String> LAYOUTS = Set.of("cards", "list", "compact");
    private static final Set<String> DENSITIES = Set.of("comfortable", "compact");
    private static final Set<String> GROUP_BY = Set.of("provider", "none");
    private static final Set<String> CARD_STYLES = Set.of("default", "outline", "flat");
    private static final Set<Integer> COLUMNS = Set.of(0, 1, 2, 3, 4);
    private static final int MAX_ORDER_ENTRIES = 2_000;
    private static final ModelDtos.ModelCatalogDisplaySettings DEFAULT_DISPLAY =
        ModelDtos.ModelCatalogDisplaySettings.defaults();
    private static final Set<String> FIRST_CLASS_PROVIDERS = Set.of(
        "openai",
        "openai-compatible",
        "openaicompatibility",
        "google",
        "gemini",
        "anthropic",
        "claude",
        "deepseek",
        "codex",
        "builderid",
        "builder_id",
        "kiro"
    );
    private static final int MAX_CUSTOMIZATIONS = 500;

    private final SystemSettingRepository repository;
    private final ObjectMapper objectMapper;
    private final DefaultModelCatalogProvider provider;
    private final AtomicReference<ModelDtos.ModelCatalogDisplaySettings> displayCache =
        new AtomicReference<>(DEFAULT_DISPLAY);

    public ModelCatalogManagementService(
        SystemSettingRepository repository,
        ObjectMapper objectMapper,
        DefaultModelCatalogProvider provider
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.provider = provider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCache() {
        refresh().subscribe(null, error -> log.warn("Failed to warm up managed model catalog", error));
        displaySettings().subscribe(null, error -> log.warn("Failed to warm up model catalog display settings", error));
    }

    @Scheduled(fixedDelay = 30_000)
    public void scheduledRefresh() {
        refresh().subscribe(null, error -> log.warn("Failed to refresh managed model catalog", error));
        displaySettings().subscribe(null, error -> log.warn("Failed to refresh model catalog display settings", error));
    }

    public Mono<List<ModelDtos.ManagedModelResponse>> list() {
        return refresh().then(Mono.fromSupplier(provider::managedModels));
    }

    public Mono<ModelDtos.ManagedModelResponse> upsert(ModelDtos.UpsertManagedModelRequest request) {
        ModelCatalogCustomization normalized = normalize(request);
        return readState()
            .flatMap(state -> {
                List<ModelCatalogCustomization> next = new ArrayList<>(state.customizations());
                int index = indexOf(next, normalized.id());
                if (index >= 0) {
                    next.set(index, normalized);
                } else {
                    if (next.size() >= MAX_CUSTOMIZATIONS) {
                        return Mono.error(new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "MODEL_CATALOG_LIMIT_EXCEEDED",
                            "Managed model customizations must not exceed " + MAX_CUSTOMIZATIONS
                        ));
                    }
                    next.add(normalized);
                }
                List<String> order = appendToOrder(state.order(), normalized.id());
                return persist(syncVendorColor(next, normalized), order).thenReturn(normalized.id());
            })
            .flatMap(modelId -> Mono.fromSupplier(() -> managedModel(modelId)));
    }

    public Mono<Void> delete(String modelId) {
        String normalizedId = normalizeModelId(modelId);
        return readState()
            .flatMap(state -> {
                List<ModelCatalogCustomization> next = new ArrayList<>(state.customizations());
                int index = indexOf(next, normalizedId);
                if (!provider.isBuiltIn(normalizedId)) {
                    if (index < 0) {
                        return Mono.error(notFound());
                    }
                    next.remove(index);
                    return persist(next, removeFromOrder(state.order(), normalizedId));
                }

                ModelDtos.ManagedModelResponse current = managedModel(normalizedId);
                ModelCatalogCustomization hidden = new ModelCatalogCustomization(
                    current.id(),
                    current.name(),
                    current.icon(),
                    current.provider(),
                    current.family(),
                    current.targetModelId(),
                    current.requestFormat(),
                    current.endpoint(),
                    current.contextWindow(),
                    current.description(),
                    false,
                    current.badgeColor()
                );
                if (index >= 0) {
                    next.set(index, hidden);
                } else {
                    next.add(hidden);
                }
                return persist(next, state.order());
            });
    }

    public Mono<Void> deleteVendor(String vendor) {
        String providerName = vendor == null ? "" : vendor.trim();
        if (providerName.isEmpty()) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_VENDOR_REQUIRED",
                "Model vendor is required"
            ));
        }
        if (isFirstClassProvider(providerName)) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_VENDOR_PROTECTED",
                "Built-in vendors cannot be deleted"
            ));
        }
        String providerKey = key(providerName);
        return readState()
            .flatMap(state -> {
                provider.applyCustomizations(state.customizations(), state.order());
                List<ModelDtos.ManagedModelResponse> matching = provider.managedModels().stream()
                    .filter(model -> providerKey.equals(key(model.provider())))
                    .toList();
                if (matching.isEmpty()) {
                    return Mono.error(new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODEL_VENDOR_NOT_FOUND",
                        "Model vendor not found"
                    ));
                }
                if (matching.stream().anyMatch(model -> Boolean.TRUE.equals(model.builtIn()))) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "MODEL_VENDOR_HAS_BUILTIN",
                        "Vendors that contain built-in models cannot be deleted"
                    ));
                }
                Set<String> removed = new HashSet<>();
                for (ModelDtos.ManagedModelResponse model : matching) {
                    removed.add(key(model.id()));
                }
                List<ModelCatalogCustomization> next = new ArrayList<>();
                for (ModelCatalogCustomization item : state.customizations()) {
                    if (!removed.contains(key(item.id()))) {
                        next.add(item);
                    }
                }
                List<String> order = new ArrayList<>();
                for (String modelId : state.order()) {
                    if (!removed.contains(key(modelId))) {
                        order.add(modelId);
                    }
                }
                return persist(next, order);
            });
    }

    public Mono<Void> reorder(ModelDtos.ReorderManagedModelsRequest request) {
        if (request == null) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_ORDER_REQUIRED",
                "Model display order is required"
            ));
        }
        return readState()
            .flatMap(state -> {
                if (Boolean.TRUE.equals(request.restoreDefault())) {
                    return persist(state.customizations(), List.of());
                }
                if (request.modelIds().isEmpty()) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "MODEL_ORDER_REQUIRED",
                        "Model display order is required"
                    ));
                }
                if (request.modelIds().size() > MAX_ORDER_ENTRIES) {
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "MODEL_ORDER_LIMIT_EXCEEDED",
                        "Model display order must not exceed " + MAX_ORDER_ENTRIES + " entries"
                    ));
                }
                provider.applyCustomizations(state.customizations(), state.order());
                List<String> currentIds = provider.managedModels().stream()
                    .map(ModelDtos.ManagedModelResponse::id)
                    .toList();
                return persist(state.customizations(), mergeOrder(request.modelIds(), currentIds));
            });
    }

    public Mono<ModelDtos.ModelCatalogDisplaySettings> displaySettings() {
        return readDisplay().doOnNext(displayCache::set);
    }

    public Mono<ModelDtos.ModelCatalogViewSettings> viewSettings() {
        return Mono.zip(displaySettings(), readOrder())
            .map(tuple -> new ModelDtos.ModelCatalogViewSettings(tuple.getT1(), tuple.getT2()));
    }

    public Mono<ModelDtos.ModelCatalogDisplaySettings> updateDisplay(
        ModelDtos.UpdateModelCatalogDisplayRequest request
    ) {
        if (request != null && Boolean.TRUE.equals(request.restoreDefault())) {
            return persistDisplay(DEFAULT_DISPLAY);
        }
        return displaySettings()
            .map(current -> normalizeDisplay(request, current, true))
            .flatMap(this::persistDisplay);
    }

    public Mono<Void> refresh() {
        return readState()
            .doOnNext(state -> provider.applyCustomizations(state.customizations(), state.order()))
            .then();
    }

    public void invalidateCacheForSetting(String settingKey) {
        if (MODEL_CATALOG_CUSTOMIZATIONS.equals(settingKey) || MODEL_CATALOG_ORDER.equals(settingKey)) {
            refresh().subscribe(null, error -> log.warn("Failed to refresh managed model catalog", error));
        }
        if (MODEL_CATALOG_DISPLAY.equals(settingKey)) {
            displaySettings().subscribe(
                null,
                error -> log.warn("Failed to refresh model catalog display settings", error)
            );
        }
    }

    private Mono<CatalogState> readState() {
        return Mono.zip(readCustomizations(), readOrder())
            .map(tuple -> new CatalogState(tuple.getT1(), tuple.getT2()));
    }

    private Mono<List<ModelCatalogCustomization>> readCustomizations() {
        return repository.findById(MODEL_CATALOG_CUSTOMIZATIONS)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty("[]")
            .map(this::fromJson);
    }

    private Mono<List<String>> readOrder() {
        return repository.findById(MODEL_CATALOG_ORDER)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty("[]")
            .map(this::fromOrderJson);
    }

    private Mono<ModelDtos.ModelCatalogDisplaySettings> readDisplay() {
        return repository.findById(MODEL_CATALOG_DISPLAY)
            .map(SystemSettingEntity::getSettingValue)
            .defaultIfEmpty("")
            .map(this::fromDisplayJson);
    }

    private Mono<Void> persist(List<ModelCatalogCustomization> customizations, List<String> order) {
        List<ModelCatalogCustomization> customizationSnapshot = List.copyOf(customizations);
        List<String> orderSnapshot = List.copyOf(order);
        return repository.upsertSettings(
                new String[] { MODEL_CATALOG_CUSTOMIZATIONS, MODEL_CATALOG_ORDER },
                new String[] { toJson(customizationSnapshot), toOrderJson(orderSnapshot) }
            )
            .then(Mono.fromRunnable(() -> provider.applyCustomizations(customizationSnapshot, orderSnapshot)));
    }

    private Mono<ModelDtos.ModelCatalogDisplaySettings> persistDisplay(
        ModelDtos.ModelCatalogDisplaySettings settings
    ) {
        ModelDtos.ModelCatalogDisplaySettings snapshot = Objects.requireNonNullElse(settings, DEFAULT_DISPLAY);
        return repository.upsertSetting(MODEL_CATALOG_DISPLAY, toDisplayJson(snapshot))
            .then(Mono.fromRunnable(() -> displayCache.set(snapshot)))
            .thenReturn(snapshot);
    }

    private ModelCatalogCustomization normalize(ModelDtos.UpsertManagedModelRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_REQUIRED", "Model configuration is required");
        }
        String id = normalizeModelId(request.id());
        String requestFormat = text(request.requestFormat(), OPENAI_CHAT_COMPLETIONS).toUpperCase(Locale.ROOT);
        if (!OPENAI_CHAT_COMPLETIONS.equals(requestFormat)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_REQUEST_FORMAT_UNSUPPORTED",
                "Only OpenAI Chat Completions request format is supported"
            );
        }
        String endpoint = text(request.endpoint(), DEFAULT_ENDPOINT);
        if (hasControlCharacter(endpoint)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_ENDPOINT_INVALID", "Model endpoint is invalid");
        }
        String providerName = text(request.provider(), "Custom");
        String badgeColor = normalizeBadgeColor(request.badgeColor());
        if (badgeColor == null) {
            badgeColor = inheritedVendorColor(providerName);
        }
        if (requiresVendorColor(id, providerName) && badgeColor == null) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_BADGE_COLOR_REQUIRED",
                "Custom model vendors require a badge color"
            );
        }
        return new ModelCatalogCustomization(
            id,
            text(request.name(), id),
            nullableText(request.icon()),
            providerName,
            text(request.family(), "Custom"),
            text(request.targetModelId(), id),
            requestFormat,
            endpoint,
            Objects.requireNonNullElse(request.contextWindow(), ModelCatalogProvider.DEFAULT_INPUT_TOKENS),
            nullableText(request.description()),
            !Boolean.FALSE.equals(request.enabled()),
            badgeColor
        );
    }

    private boolean requiresVendorColor(String modelId, String providerName) {
        return !provider.isBuiltIn(modelId) && !isFirstClassProvider(providerName);
    }

    private boolean isFirstClassProvider(String providerName) {
        String providerKey = key(providerName);
        if (providerKey.isEmpty() || FIRST_CLASS_PROVIDERS.contains(providerKey)) {
            return true;
        }
        return providerKey.contains("builder")
            || providerKey.contains("kiro")
            || providerKey.contains("enterprise")
            || providerKey.contains("sso")
            || providerKey.contains("idc");
    }

    private String normalizeBadgeColor(String color) {
        String cleaned = nullableText(color);
        if (cleaned == null) {
            return null;
        }
        if (!BADGE_COLOR.matcher(cleaned).matches()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_BADGE_COLOR_INVALID",
                "Model vendor color must be a 6-digit hex value such as #4D6BFE"
            );
        }
        return cleaned;
    }

    private List<ModelCatalogCustomization> syncVendorColor(
        List<ModelCatalogCustomization> customizations,
        ModelCatalogCustomization saved
    ) {
        String color = saved.badgeColor();
        String providerKey = key(saved.provider());
        if (color == null || providerKey.isEmpty()) {
            return customizations;
        }
        List<ModelCatalogCustomization> next = new ArrayList<>(customizations.size());
        for (ModelCatalogCustomization item : customizations) {
            if (providerKey.equals(key(item.provider()))) {
                next.add(item.withBadgeColor(color));
            } else {
                next.add(item);
            }
        }
        return next;
    }

    private String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String inheritedVendorColor(String providerName) {
        String providerKey = key(providerName);
        if (providerKey.isEmpty()) {
            return null;
        }
        return provider.managedModels().stream()
            .filter(model -> providerKey.equals(key(model.provider())))
            .map(ModelDtos.ManagedModelResponse::badgeColor)
            .filter(color -> color != null && !color.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String normalizeModelId(String modelId) {
        String normalized = modelId == null ? "" : modelId.trim();
        if (!MODEL_ID.matcher(normalized).matches()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_ID_INVALID",
                "Model ID may contain letters, numbers, '.', '_', ':', '/', and '-'"
            );
        }
        return normalized;
    }

    private ModelDtos.ManagedModelResponse managedModel(String modelId) {
        return provider.managedModels().stream()
            .filter(model -> model.id().equalsIgnoreCase(modelId))
            .findFirst()
            .orElseThrow(this::notFound);
    }

    private int indexOf(List<ModelCatalogCustomization> customizations, String modelId) {
        for (int index = 0; index < customizations.size(); index++) {
            if (customizations.get(index).id().equalsIgnoreCase(modelId)) {
                return index;
            }
        }
        return -1;
    }

    private List<ModelCatalogCustomization> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ModelCatalogCustomization> parsed = objectMapper.readValue(json, CUSTOMIZATION_LIST);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (JacksonException error) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MODEL_CATALOG_INVALID",
                "Stored model catalog configuration is invalid"
            );
        }
    }

    private String toJson(List<ModelCatalogCustomization> customizations) {
        try {
            return objectMapper.writeValueAsString(customizations);
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not serialize managed model catalog", error);
        }
    }

    private String text(String value, String fallback) {
        String normalized = nullableText(value);
        return normalized == null ? fallback : normalized;
    }

    private String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "Managed model not found");
    }

    private List<String> appendToOrder(List<String> order, String modelId) {
        if (order == null || order.isEmpty()) {
            return order == null ? List.of() : order;
        }
        if (indexOfIds(order, modelId) >= 0) {
            return order;
        }
        List<String> next = new ArrayList<>(order);
        next.add(modelId);
        return next;
    }

    private List<String> removeFromOrder(List<String> order, String modelId) {
        if (order == null || order.isEmpty()) {
            return List.of();
        }
        int index = indexOfIds(order, modelId);
        if (index < 0) {
            return order;
        }
        List<String> next = new ArrayList<>(order);
        next.remove(index);
        return next;
    }

    private List<String> mergeOrder(List<String> requested, List<String> currentIds) {
        LinkedHashSet<String> current = new LinkedHashSet<>();
        for (String id : currentIds) {
            if (id != null && !id.isBlank()) {
                current.add(id);
            }
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String id : requested) {
            String match = matchId(current, id);
            if (match != null) {
                ordered.add(match);
            }
        }
        ordered.addAll(current);
        return List.copyOf(ordered);
    }

    private String matchId(LinkedHashSet<String> current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String candidateKey = key(candidate);
        for (String id : current) {
            if (candidateKey.equals(key(id))) {
                return id;
            }
        }
        return null;
    }

    private int indexOfIds(List<String> ids, String modelId) {
        for (int index = 0; index < ids.size(); index++) {
            if (ids.get(index).equalsIgnoreCase(modelId)) {
                return index;
            }
        }
        return -1;
    }

    private List<String> fromOrderJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, ORDER_LIST);
            if (parsed == null || parsed.isEmpty()) {
                return List.of();
            }
            LinkedHashSet<String> cleaned = new LinkedHashSet<>();
            for (String id : parsed) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                String trimmed = id.trim();
                if (cleaned.stream().noneMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
                    cleaned.add(trimmed);
                }
            }
            return List.copyOf(cleaned);
        } catch (JacksonException error) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MODEL_CATALOG_ORDER_INVALID",
                "Stored model catalog order is invalid"
            );
        }
    }

    private String toOrderJson(List<String> order) {
        try {
            return objectMapper.writeValueAsString(order == null ? List.of() : order);
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not serialize managed model catalog order", error);
        }
    }

    private ModelDtos.ModelCatalogDisplaySettings fromDisplayJson(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT_DISPLAY;
        }
        try {
            ModelDtos.ModelCatalogDisplaySettings parsed = objectMapper.readValue(
                json,
                ModelDtos.ModelCatalogDisplaySettings.class
            );
            return normalizeDisplay(toUpdateRequest(parsed), DEFAULT_DISPLAY, false);
        } catch (JacksonException error) {
            log.warn("Stored model catalog display settings are invalid; using defaults");
            return DEFAULT_DISPLAY;
        }
    }

    private String toDisplayJson(ModelDtos.ModelCatalogDisplaySettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not serialize model catalog display settings", error);
        }
    }

    private ModelDtos.UpdateModelCatalogDisplayRequest toUpdateRequest(
        ModelDtos.ModelCatalogDisplaySettings settings
    ) {
        if (settings == null) {
            return new ModelDtos.UpdateModelCatalogDisplayRequest(
                null, null, null, null, null, null, null, null, null, null, null
            );
        }
        return new ModelDtos.UpdateModelCatalogDisplayRequest(
            settings.layout(),
            settings.density(),
            settings.columns(),
            settings.showIcon(),
            settings.showFamily(),
            settings.showContext(),
            settings.showOrigin(),
            settings.showDescription(),
            settings.groupBy(),
            settings.cardStyle(),
            null
        );
    }

    private ModelDtos.ModelCatalogDisplaySettings normalizeDisplay(
        ModelDtos.UpdateModelCatalogDisplayRequest request,
        ModelDtos.ModelCatalogDisplaySettings current,
        boolean strict
    ) {
        ModelDtos.ModelCatalogDisplaySettings base = current == null ? DEFAULT_DISPLAY : current;
        if (request == null) {
            return base;
        }
        return new ModelDtos.ModelCatalogDisplaySettings(
            choice(request.layout(), base.layout(), LAYOUTS, strict, "MODEL_DISPLAY_LAYOUT_INVALID", "layout"),
            choice(request.density(), base.density(), DENSITIES, strict, "MODEL_DISPLAY_DENSITY_INVALID", "density"),
            columns(request.columns(), base.columns(), strict),
            flag(request.showIcon(), base.showIcon()),
            flag(request.showFamily(), base.showFamily()),
            flag(request.showContext(), base.showContext()),
            flag(request.showOrigin(), base.showOrigin()),
            flag(request.showDescription(), base.showDescription()),
            choice(request.groupBy(), base.groupBy(), GROUP_BY, strict, "MODEL_DISPLAY_GROUP_INVALID", "groupBy"),
            choice(request.cardStyle(), base.cardStyle(), CARD_STYLES, strict, "MODEL_DISPLAY_STYLE_INVALID", "cardStyle")
        );
    }

    private String choice(
        String candidate,
        String fallback,
        Set<String> allowed,
        boolean strict,
        String errorCode,
        String field
    ) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        if (allowed.contains(normalized)) {
            return normalized;
        }
        if (strict) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                errorCode,
                "Unsupported model catalog " + field
            );
        }
        return fallback;
    }

    private Integer columns(Integer candidate, Integer fallback, boolean strict) {
        if (candidate == null) {
            return fallback == null ? 0 : fallback;
        }
        if (COLUMNS.contains(candidate)) {
            return candidate;
        }
        if (strict) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "MODEL_DISPLAY_COLUMNS_INVALID",
                "Model catalog columns must be 0, 1, 2, 3, or 4"
            );
        }
        return fallback == null ? 0 : fallback;
    }

    private Boolean flag(Boolean candidate, Boolean fallback) {
        if (candidate != null) {
            return candidate;
        }
        return fallback == null || fallback;
    }

    private record CatalogState(
        List<ModelCatalogCustomization> customizations,
        List<String> order
    ) {
        private CatalogState {
            customizations = customizations == null ? List.of() : List.copyOf(customizations);
            order = order == null ? List.of() : List.copyOf(order);
        }
    }
}
