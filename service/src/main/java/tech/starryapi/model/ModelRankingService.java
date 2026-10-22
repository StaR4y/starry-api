package tech.starryapi.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.ModelRankingDtos.BenchmarkStatus;
import tech.starryapi.model.ModelRankingDtos.RankingEntry;
import tech.starryapi.model.ModelRankingDtos.RankingSettingsResponse;
import tech.starryapi.model.ModelRankingDtos.RankingSnapshot;
import tech.starryapi.model.ModelRankingDtos.UpdateRankingSettingsRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ModelRankingService {

    static final String BUNDLED_DIRECTORY = "model-ranking/";
    static final List<String> BENCHMARKS = List.of(
        ModelRankingDtos.PROGRAM_BENCH,
        ModelRankingDtos.FRONTIER_BENCH
    );
    static final String ENABLED = "model_ranking_enabled";
    static final String REMOTE_REFRESH_ENABLED = "model_ranking_remote_refresh_enabled";
    static final String MAX_ENTRIES = "model_ranking_max_entries";
    static final String PROGRAMBENCH_URL = "model_ranking_programbench_url";
    static final String FRONTIERBENCH_URL = "model_ranking_frontierbench_url";
    private static final Logger log = LoggerFactory.getLogger(ModelRankingService.class);
    private static final Duration FRESHNESS = Duration.ofHours(24);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> SETTING_KEYS = List.of(
        ENABLED,
        REMOTE_REFRESH_ENABLED,
        MAX_ENTRIES,
        PROGRAMBENCH_URL,
        FRONTIERBENCH_URL
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ModelRankingProperties properties;
    private final SystemSettingRepository repository;
    private final Path cacheDirectory;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    private final AtomicReference<EffectiveSettings> settings;
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final Map<String, RankingSnapshot> snapshots = new ConcurrentHashMap<>();

    public ModelRankingService(
        @Qualifier("upstreamWebClient") WebClient webClient,
        ObjectMapper objectMapper,
        ModelRankingProperties properties
    ) {
        this(webClient, objectMapper, properties, null);
    }

    @Autowired
    public ModelRankingService(
        @Qualifier("upstreamWebClient") WebClient webClient,
        ObjectMapper objectMapper,
        ModelRankingProperties properties,
        SystemSettingRepository repository
    ) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.repository = repository;
        this.cacheDirectory = Path.of(properties.getCacheDir()).toAbsolutePath().normalize();
        this.settings = new AtomicReference<>(EffectiveSettings.from(properties));
        for (String benchmark : BENCHMARKS) {
            snapshots.put(benchmark, loadInitial(benchmark));
        }
    }

    public boolean enabled() {
        return settings.get().enabled();
    }

    public RankingSnapshot snapshot(String benchmark) {
        if (!enabled()) {
            throw new ApiException(
                HttpStatus.NOT_FOUND,
                "MODEL_RANKING_DISABLED",
                "Model ranking is disabled"
            );
        }
        String id = normalizeBenchmark(benchmark);
        RankingSnapshot snapshot = snapshots.get(id);
        if (snapshot == null || snapshot.entries() == null || snapshot.entries().isEmpty()) {
            throw new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MODEL_RANKING_UNAVAILABLE",
                "Model ranking data is not available"
            );
        }
        return snapshot;
    }

    public RankingSettingsResponse adminSettings() {
        EffectiveSettings current = settings.get();
        List<BenchmarkStatus> statuses = BENCHMARKS.stream()
            .map(id -> {
                RankingSnapshot snapshot = snapshots.get(id);
                return new BenchmarkStatus(
                    id,
                    snapshot == null ? defaultTitle(id) : snapshot.title(),
                    snapshot == null ? null : snapshot.sourceUrl(),
                    snapshot == null ? null : snapshot.fetchedAt(),
                    snapshot == null || snapshot.entries() == null ? 0 : snapshot.entries().size(),
                    isDue(id)
                );
            })
            .toList();
        return new RankingSettingsResponse(
            current.enabled(),
            current.remoteRefreshEnabled(),
            properties.getRefreshCron(),
            current.maxEntries(),
            current.programbenchUrl(),
            current.frontierbenchUrl(),
            lastError.get(),
            statuses
        );
    }

    public Mono<RankingSettingsResponse> updateSettings(UpdateRankingSettingsRequest request) {
        EffectiveSettings merged = merge(request, settings.get());
        Mono<Void> persist = persistSettings(merged);
        if (request != null && Boolean.TRUE.equals(request.refreshNow())) {
            return persist.then(refreshNow());
        }
        return persist.thenReturn(adminSettings());
    }

    public Mono<RankingSettingsResponse> refreshNow() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return Mono.error(new ApiException(
                HttpStatus.CONFLICT,
                "MODEL_RANKING_REFRESH_IN_PROGRESS",
                "Model ranking refresh is already running"
            ));
        }
        AtomicInteger failures = new AtomicInteger();
        return Flux.fromIterable(BENCHMARKS)
            .concatMap(benchmark -> refreshOne(benchmark, true)
                .doOnError(error -> {
                    failures.incrementAndGet();
                    lastError.set(benchmark + ": " + error.getMessage());
                    log.warn("Manual {} ranking refresh failed", benchmark, error);
                })
                .onErrorResume(error -> Mono.empty()))
            .then(Mono.fromCallable(() -> {
                if (failures.get() >= BENCHMARKS.size()) {
                    throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "MODEL_RANKING_REFRESH_FAILED",
                        lastError.get() == null ? "Model ranking refresh failed" : lastError.get()
                    );
                }
                if (failures.get() == 0) {
                    lastError.set(null);
                }
                return adminSettings();
            }))
            .doFinally(signal -> refreshInProgress.set(false));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        loadPersistedSettings()
            .then(refreshDue())
            .subscribe(
                null,
                error -> log.warn("Failed to refresh model ranking on startup; keeping stored snapshot", error)
            );
    }

    @Scheduled(cron = "${starry.model-ranking.refresh-cron:0 40 4 * * *}", zone = "UTC")
    public void scheduledRefresh() {
        refreshDue().subscribe(
            null,
            error -> log.warn("Failed to refresh model ranking; keeping stored snapshot", error)
        );
    }

    public Mono<Void> refreshDue() {
        return refresh(true);
    }

    public Mono<Void> refresh() {
        return refresh(false);
    }

    private Mono<Void> refresh(boolean dueOnly) {
        if (!settings.get().enabled()
            || !settings.get().remoteRefreshEnabled()
            || !refreshInProgress.compareAndSet(false, true)) {
            return Mono.empty();
        }
        return Flux.fromIterable(BENCHMARKS)
            .filter(benchmark -> !dueOnly || isDue(benchmark))
            .concatMap(benchmark -> refreshOne(benchmark, false).onErrorResume(error -> Mono.empty()))
            .then()
            .doFinally(signal -> refreshInProgress.set(false));
    }

    Mono<RankingSnapshot> refreshOne(String benchmark) {
        return refreshOne(benchmark, false);
    }

    Mono<RankingSnapshot> refreshOne(String benchmark, boolean force) {
        if (!force && !settings.get().remoteRefreshEnabled()) {
            return Mono.empty();
        }
        String id = normalizeBenchmark(benchmark);
        String url = settings.get().urlFor(id);
        return webClient.get()
            .uri(url)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(byte[].class)
            .timeout(FETCH_TIMEOUT)
            .map(content -> parse(id, content, Instant.now()))
            .doOnNext(snapshot -> {
                snapshots.put(id, snapshot);
                log.info("Loaded {} {} ranking entries from {}", snapshot.entries().size(), id, url);
            })
            .flatMap(snapshot -> Mono.fromRunnable(() -> persist(snapshot))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(snapshot))
            .doOnError(error -> log.warn("Failed to refresh {} model ranking from {}", id, url, error));
    }

    boolean isDue(String benchmark) {
        RankingSnapshot snapshot = snapshots.get(normalizeBenchmark(benchmark));
        if (snapshot == null || snapshot.fetchedAt() == null) {
            return true;
        }
        return snapshot.fetchedAt().isBefore(Instant.now().minus(FRESHNESS));
    }

    RankingSnapshot parse(String benchmark, byte[] content, Instant fetchedAt) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Model ranking payload is empty");
        }
        if (content.length > properties.getMaxBytes()) {
            throw new IllegalArgumentException("Model ranking payload exceeds configured size limit");
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Model ranking payload must be a JSON object");
            }
            JsonNode entriesNode = root.get("entries");
            if (entriesNode == null || !entriesNode.isArray()) {
                throw new IllegalArgumentException("Model ranking payload must include an entries array");
            }
            List<RankingEntry> entries = new ArrayList<>();
            for (JsonNode node : entriesNode) {
                RankingEntry entry = parseEntry(node);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("Model ranking payload contains no usable entries");
            }
            entries.sort(Comparator.comparingInt(RankingEntry::rank));
            int limit = Math.min(Math.max(1, settings.get().maxEntries()), entries.size());
            String title = text(root.get("title"));
            if (title.isBlank()) {
                title = defaultTitle(benchmark);
            }
            Instant storedFetchedAt = fetchedAt;
            if (storedFetchedAt == null) {
                storedFetchedAt = parseInstant(root.get("fetchedAt"));
            }
            return new RankingSnapshot(
                benchmark,
                title,
                blankToNull(text(root.get("sourceUrl"))),
                storedFetchedAt,
                List.copyOf(entries.subList(0, limit))
            );
        } catch (JacksonException error) {
            throw new IllegalArgumentException("Could not parse model ranking payload", error);
        }
    }

    private RankingSnapshot loadInitial(String benchmark) {
        Path cached = cacheFile(benchmark);
        if (Files.isRegularFile(cached)) {
            try {
                return parse(benchmark, Files.readAllBytes(cached), null);
            } catch (IOException | RuntimeException error) {
                log.warn("Ignoring unreadable {} ranking cache {}", benchmark, cached, error);
            }
        }
        return loadBundled(benchmark);
    }

    private RankingSnapshot loadBundled(String benchmark) {
        ClassPathResource resource = new ClassPathResource(BUNDLED_DIRECTORY + benchmark + ".json");
        try (InputStream input = resource.getInputStream()) {
            RankingSnapshot bundled = parse(benchmark, input.readAllBytes(), null);
            log.info("Loaded {} bundled {} ranking entries", bundled.entries().size(), benchmark);
            return bundled;
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Could not load bundled " + benchmark + " ranking snapshot", error);
        }
    }

    private void persist(RankingSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(cacheDirectory);
            Path target = cacheFile(snapshot.benchmark());
            Path upload = Files.createTempFile(cacheDirectory, "." + snapshot.benchmark() + "-", ".tmp");
            try {
                objectMapper.writeValue(upload.toFile(), snapshot);
                try {
                    Files.move(upload, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(upload, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(upload);
            }
        } catch (IOException | JacksonException error) {
            log.warn("Unable to persist {} ranking snapshot", snapshot.benchmark(), error);
        }
    }

    private Mono<Void> loadPersistedSettings() {
        if (repository == null) {
            return Mono.empty();
        }
        return repository.findAllById(SETTING_KEYS)
            .collectMap(SystemSettingEntity::getSettingKey, SystemSettingEntity::getSettingValue)
            .doOnNext(stored -> settings.set(EffectiveSettings.from(properties, stored)))
            .then();
    }

    private Mono<Void> persistSettings(EffectiveSettings next) {
        settings.set(next);
        if (repository == null) {
            return Mono.empty();
        }
        String[] values = SETTING_KEYS.stream().map(next::storedValue).toArray(String[]::new);
        return repository.upsertSettings(SETTING_KEYS.toArray(String[]::new), values).then();
    }

    private EffectiveSettings merge(UpdateRankingSettingsRequest request, EffectiveSettings previous) {
        EffectiveSettings base = previous == null ? EffectiveSettings.from(properties) : previous;
        if (request == null) {
            return base;
        }
        boolean enabled = request.enabled() == null ? base.enabled() : request.enabled();
        boolean remoteRefreshEnabled = request.remoteRefreshEnabled() == null
            ? base.remoteRefreshEnabled()
            : request.remoteRefreshEnabled();
        int maxEntries = request.maxEntries() == null ? base.maxEntries() : request.maxEntries();
        if (maxEntries < 1 || maxEntries > 30) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_RANKING_MAX_ENTRIES_INVALID", "maxEntries must be between 1 and 30");
        }
        return new EffectiveSettings(
            enabled,
            remoteRefreshEnabled,
            maxEntries,
            httpUrl(request.programbenchUrl(), base.programbenchUrl()),
            httpUrl(request.frontierbenchUrl(), base.frontierbenchUrl())
        );
    }

    private static String httpUrl(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value.trim();
        try {
            URI uri = URI.create(cleaned);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IllegalArgumentException("scheme");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("host");
            }
            return cleaned;
        } catch (RuntimeException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_RANKING_URL_INVALID", "Ranking source URL must be http or https");
        }
    }

    private Path cacheFile(String benchmark) {
        return cacheDirectory.resolve(benchmark + ".json");
    }

    private RankingEntry parseEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String model = text(node.get("model"));
        if (model.isBlank() || !node.path("rank").isNumber() || !node.path("score").isNumber()) {
            return null;
        }
        Double secondary = node.path("secondaryScore").isNumber() ? node.get("secondaryScore").asDouble(0.0) : null;
        return new RankingEntry(
            node.get("rank").asInt(0),
            model,
            text(node.get("organization")),
            text(node.get("agent")),
            node.get("score").asDouble(0.0),
            secondary
        );
    }

    private static String normalizeBenchmark(String benchmark) {
        String normalized = benchmark == null ? "" : benchmark.trim().toLowerCase(Locale.ROOT);
        if (!BENCHMARKS.contains(normalized)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MODEL_RANKING_NOT_FOUND", "Unknown model ranking benchmark");
        }
        return normalized;
    }

    private static String defaultTitle(String benchmark) {
        return ModelRankingDtos.PROGRAM_BENCH.equals(benchmark)
            ? "ProgramBench Leaderboard"
            : "Terminal-Bench 3.0 Leaderboard";
    }

    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            if (node.isString()) {
                return Instant.parse(node.asString(""));
            }
            if (node.isNumber()) {
                long value = node.asLong(0L);
                return value > 10_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString("").trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record EffectiveSettings(
        boolean enabled,
        boolean remoteRefreshEnabled,
        int maxEntries,
        String programbenchUrl,
        String frontierbenchUrl
    ) {
        static EffectiveSettings from(ModelRankingProperties properties) {
            return from(properties, Map.of());
        }

        static EffectiveSettings from(ModelRankingProperties properties, Map<String, String> stored) {
            Map<String, String> values = stored == null ? Map.of() : stored;
            return new EffectiveSettings(
                boolOr(values.get(ENABLED), properties.isEnabled()),
                boolOr(values.get(REMOTE_REFRESH_ENABLED), properties.isRemoteRefreshEnabled()),
                intOr(values.get(MAX_ENTRIES), properties.getMaxEntries(), 1, 30),
                firstNonBlank(values.get(PROGRAMBENCH_URL), properties.getProgrambenchUrl()),
                firstNonBlank(values.get(FRONTIERBENCH_URL), properties.getFrontierbenchUrl())
            );
        }

        String urlFor(String benchmark) {
            return ModelRankingDtos.PROGRAM_BENCH.equals(benchmark) ? programbenchUrl : frontierbenchUrl;
        }

        String storedValue(String key) {
            return switch (key) {
                case ENABLED -> Boolean.toString(enabled);
                case REMOTE_REFRESH_ENABLED -> Boolean.toString(remoteRefreshEnabled);
                case MAX_ENTRIES -> Integer.toString(maxEntries);
                case PROGRAMBENCH_URL -> programbenchUrl;
                case FRONTIERBENCH_URL -> frontierbenchUrl;
                default -> "";
            };
        }

        private static boolean boolOr(String value, boolean fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Boolean.parseBoolean(value.trim());
        }

        private static int intOr(String value, int fallback, int min, int max) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Math.min(max, Math.max(min, Integer.parseInt(value.trim())));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static String firstNonBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
