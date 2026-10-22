package tech.starryapi.model;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.OfficialPriceResolver.OfficialPriceMatch;
import tech.starryapi.platform.catalog.ModelPriceRepoModelIds;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OfficialModelPricingService {

    static final String BUNDLED_CATALOG = "model-pricing/model_prices_and_context_window.json";
    static final String REMOTE_REFRESH_ENABLED = "model_pricing_remote_refresh_enabled";
    static final String SYNC_CATALOG_IDS = "model_pricing_sync_catalog_ids";
    static final String SOURCE_URL = "model_pricing_source_url";
    static final String CHECKSUM_URL = "model_pricing_checksum_url";
    static final List<String> SETTING_KEYS = List.of(
        REMOTE_REFRESH_ENABLED,
        SYNC_CATALOG_IDS,
        SOURCE_URL,
        CHECKSUM_URL
    );

    private static final Logger log = LoggerFactory.getLogger(OfficialModelPricingService.class);
    private static final int MAX_MODEL_ID_LENGTH = 512;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final OfficialModelPricingProperties properties;
    private final SystemSettingRepository repository;
    private final DefaultModelCatalogProvider catalogProvider;
    private final Path cacheDirectory;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    private final AtomicReference<SourceSettings> settings;
    private volatile Map<String, OfficialModelPrice> catalog;
    private volatile OfficialPriceResolver resolver;
    private volatile JsonNode catalogJson;
    private volatile Instant refreshedAt;
    private volatile String lastError;

    public OfficialModelPricingService(
        @Qualifier("upstreamWebClient") WebClient webClient,
        ObjectMapper objectMapper,
        OfficialModelPricingProperties properties
    ) {
        this(webClient, objectMapper, properties, null, null);
    }

    @Autowired
    public OfficialModelPricingService(
        @Qualifier("upstreamWebClient") WebClient webClient,
        ObjectMapper objectMapper,
        OfficialModelPricingProperties properties,
        SystemSettingRepository repository,
        DefaultModelCatalogProvider catalogProvider
    ) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.repository = repository;
        this.catalogProvider = catalogProvider;
        this.cacheDirectory = repository == null || properties.getCacheDir() == null || properties.getCacheDir().isBlank()
            ? null
            : Path.of(properties.getCacheDir()).toAbsolutePath().normalize();
        this.settings = new AtomicReference<>(SourceSettings.from(properties));
        loadInitialCatalog();
    }

    public Optional<OfficialModelPrice> find(String model) {
        return resolver.findExact(model);
    }

    public Optional<OfficialPriceMatch> resolve(String model, boolean approximate) {
        return resolver.resolve(model, approximate);
    }

    public int size() {
        return catalog.size();
    }

    public Instant refreshedAt() {
        return refreshedAt;
    }

    public String lastError() {
        return lastError;
    }

    public SourceSettings sourceSettings() {
        return settings.get();
    }

    public ModelDtos.ModelPricingSourceStatus sourceStatus() {
        SourceSettings current = settings.get();
        return new ModelDtos.ModelPricingSourceStatus(
            current.remoteRefreshEnabled(),
            current.syncCatalogIds(),
            current.sourceUrl(),
            current.checksumUrl(),
            properties.getRefreshInterval(),
            size(),
            refreshedAt,
            lastError
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        loadPersistedSettings()
            .doOnSuccess(ignored -> applySyncedCatalog())
            .then(Mono.defer(() -> settings.get().remoteRefreshEnabled() ? refreshQuietly() : Mono.empty()))
            .subscribe(null, error -> log.warn("Failed to refresh official model prices; keeping bundled catalog", error));
    }

    @Scheduled(
        fixedDelayString = "${starry.model-pricing.refresh-interval:PT10M}",
        initialDelayString = "${starry.model-pricing.refresh-interval:PT10M}"
    )
    public void scheduledRefresh() {
        if (!settings.get().remoteRefreshEnabled()) {
            return;
        }
        refreshQuietly().subscribe(
            null,
            error -> log.warn("Failed to refresh official model prices; keeping cached catalog", error)
        );
    }

    public Mono<Void> refresh() {
        return downloadAndApply(false);
    }

    public Mono<ModelDtos.ModelPricingSourceStatus> refreshNow() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return Mono.error(new ApiException(
                HttpStatus.CONFLICT,
                "MODEL_PRICE_REFRESH_IN_PROGRESS",
                "Official model price refresh is already running"
            ));
        }
        return downloadAndApply(true)
            .then(Mono.fromCallable(this::sourceStatus))
            .doFinally(signal -> refreshInProgress.set(false));
    }

    public Mono<ModelDtos.ModelPricingSourceStatus> updateSource(ModelDtos.UpdateModelPricingSourceRequest request) {
        SourceSettings current = settings.get();
        SourceSettings next = current.merge(request, properties);
        return persistSettings(next)
            .then(Boolean.TRUE.equals(request == null ? null : request.refreshNow())
                ? refreshNow()
                : Mono.fromRunnable(this::applySyncedCatalog).then(Mono.fromCallable(this::sourceStatus)));
    }

    private Mono<Void> refreshQuietly() {
        return downloadAndApply(false)
            .onErrorResume(error -> {
                lastError = error.getMessage();
                log.warn("Failed to refresh official model prices; keeping cached catalog", error);
                return Mono.empty();
            });
    }

    private Mono<Void> downloadAndApply(boolean force) {
        SourceSettings current = settings.get();
        if (!force && !current.remoteRefreshEnabled()) {
            return Mono.empty();
        }
        boolean acquiredHere = !force && refreshInProgress.compareAndSet(false, true);
        if (!force && !acquiredHere) {
            return Mono.empty();
        }
        Mono<String> checksum = webClient.get()
            .uri(current.checksumUrl())
            .accept(MediaType.TEXT_PLAIN)
            .retrieve()
            .bodyToMono(String.class);
        Mono<byte[]> content = webClient.get()
            .uri(current.sourceUrl())
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(byte[].class);
        return Mono.zip(checksum, content)
            .map(result -> verifiedCatalog(result.getT1(), result.getT2()))
            .doOnNext(parsed -> {
                replaceCatalog(parsed.prices(), parsed.json());
                refreshedAt = Instant.now();
                lastError = null;
                persistCache(parsed.raw(), sha256(parsed.raw()));
                log.info("Loaded {} official model prices from {}", parsed.prices().size(), current.sourceUrl());
                applySyncedCatalog();
            })
            .then()
            .doOnError(error -> lastError = error.getMessage())
            .doFinally(signal -> {
                if (acquiredHere) {
                    refreshInProgress.set(false);
                }
            });
    }

    public Mono<Void> loadPersistedSettings() {
        if (repository == null) {
            return Mono.empty();
        }
        return repository.findAllById(SETTING_KEYS)
            .collectMap(SystemSettingEntity::getSettingKey, SystemSettingEntity::getSettingValue)
            .doOnNext(stored -> settings.set(SourceSettings.from(properties, stored)))
            .then();
    }

    private Mono<Void> persistSettings(SourceSettings next) {
        settings.set(next);
        if (repository == null) {
            return Mono.empty();
        }
        return repository.upsertSettings(
            new String[] { REMOTE_REFRESH_ENABLED, SYNC_CATALOG_IDS, SOURCE_URL, CHECKSUM_URL },
            new String[] {
                Boolean.toString(next.remoteRefreshEnabled()),
                Boolean.toString(next.syncCatalogIds()),
                next.sourceUrl(),
                next.checksumUrl()
            }
        ).then();
    }

    Map<String, OfficialModelPrice> parseCatalog(byte[] content) {
        return parseCatalogDocument(content).prices();
    }

    private ParsedCatalog parseCatalogDocument(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Official model price catalog is empty");
        }
        if (content.length > properties.getMaxCatalogBytes()) {
            throw new IllegalArgumentException("Official model price catalog exceeds configured size limit");
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Official model price catalog must be a JSON object");
            }
            Map<String, OfficialModelPrice> parsed = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                String model = normalizeModelId(entry.getKey());
                JsonNode value = entry.getValue();
                if (model.isBlank() || model.length() > MAX_MODEL_ID_LENGTH || value == null || !value.isObject()) {
                    return;
                }
                BigDecimal input = nonNegativeDecimal(value.get("input_cost_per_token"));
                BigDecimal output = nonNegativeDecimal(value.get("output_cost_per_token"));
                if (input == null || output == null) {
                    return;
                }
                BigDecimal cacheWrite = nonNegativeDecimal(value.get("cache_creation_input_token_cost"));
                BigDecimal cacheRead = nonNegativeDecimal(value.get("cache_read_input_token_cost"));
                parsed.put(model, new OfficialModelPrice(
                    model,
                    input,
                    output,
                    cacheWrite == null ? input : cacheWrite,
                    cacheRead == null ? input : cacheRead
                ));
            });
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("Official model price catalog contains no usable token prices");
            }
            return new ParsedCatalog(Map.copyOf(parsed), root, content);
        } catch (JacksonException error) {
            throw new IllegalArgumentException("Could not parse official model price catalog", error);
        }
    }

    private void loadInitialCatalog() {
        if (cacheDirectory != null) {
            Path cached = cacheDirectory.resolve("catalog.json");
            if (Files.isRegularFile(cached)) {
                try {
                    ParsedCatalog parsed = parseCatalogDocument(Files.readAllBytes(cached));
                    replaceCatalog(parsed.prices(), parsed.json());
                    log.info("Loaded {} cached official model prices", parsed.prices().size());
                    return;
                } catch (IOException | RuntimeException error) {
                    log.warn("Ignoring unreadable official model price cache {}", cached, error);
                }
            }
        }
        loadBundledCatalog();
    }

    private void loadBundledCatalog() {
        try (InputStream input = new ClassPathResource(BUNDLED_CATALOG).getInputStream()) {
            ParsedCatalog parsed = parseCatalogDocument(input.readAllBytes());
            replaceCatalog(parsed.prices(), parsed.json());
            log.info("Loaded {} bundled official model prices", parsed.prices().size());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Could not load bundled official model price catalog", error);
        }
    }

    private ParsedCatalog verifiedCatalog(String checksumDocument, byte[] content) {
        String expected = checksumDocument == null ? "" : checksumDocument.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Official model price checksum is invalid");
        }
        String actual = sha256(content);
        if (!MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new IllegalArgumentException("Official model price checksum does not match downloaded catalog");
        }
        return parseCatalogDocument(content);
    }

    private void replaceCatalog(Map<String, OfficialModelPrice> next, JsonNode json) {
        catalog = next;
        catalogJson = json;
        resolver = new OfficialPriceResolver(next);
    }

    private void persistCache(byte[] content, String checksum) {
        if (cacheDirectory == null || content == null) {
            return;
        }
        try {
            Files.createDirectories(cacheDirectory);
            Path target = cacheDirectory.resolve("catalog.json");
            Path upload = Files.createTempFile(cacheDirectory, ".catalog-", ".tmp");
            try {
                Files.write(upload, content);
                try {
                    Files.move(upload, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(upload, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(upload);
            }
            if (checksum != null) {
                Files.writeString(cacheDirectory.resolve("catalog.sha256"), checksum);
            }
        } catch (IOException error) {
            log.warn("Unable to persist official model price cache", error);
        }
    }

    private void applySyncedCatalog() {
        if (catalogProvider == null) {
            return;
        }
        if (!settings.get().syncCatalogIds() || catalogJson == null) {
            catalogProvider.applySyncedBuiltIns(List.of());
            return;
        }
        catalogProvider.applySyncedBuiltIns(ModelPriceRepoModelIds.syncedSpecs(catalogJson));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private BigDecimal nonNegativeDecimal(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        return value.signum() >= 0 ? value : null;
    }

    private String normalizeModelId(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    public record OfficialModelPrice(
        String model,
        BigDecimal inputCostPerToken,
        BigDecimal outputCostPerToken,
        BigDecimal cacheWriteCostPerToken,
        BigDecimal cacheReadCostPerToken
    ) {
    }

    record SourceSettings(
        boolean remoteRefreshEnabled,
        boolean syncCatalogIds,
        String sourceUrl,
        String checksumUrl
    ) {
        static SourceSettings from(OfficialModelPricingProperties properties) {
            return from(properties, Map.of());
        }

        static SourceSettings from(OfficialModelPricingProperties properties, Map<String, String> stored) {
            return new SourceSettings(
                bool(stored.get(REMOTE_REFRESH_ENABLED), properties.isRemoteRefreshEnabled()),
                bool(stored.get(SYNC_CATALOG_IDS), properties.isSyncCatalogIds()),
                text(stored.get(SOURCE_URL), properties.getSourceUrl()),
                text(stored.get(CHECKSUM_URL), properties.getChecksumUrl())
            );
        }

        SourceSettings merge(ModelDtos.UpdateModelPricingSourceRequest request, OfficialModelPricingProperties ignored) {
            if (request == null) {
                return this;
            }
            return new SourceSettings(
                request.remoteRefreshEnabled() == null ? remoteRefreshEnabled : request.remoteRefreshEnabled(),
                request.syncCatalogIds() == null ? syncCatalogIds : request.syncCatalogIds(),
                request.sourceUrl() == null || request.sourceUrl().isBlank() ? sourceUrl : request.sourceUrl().trim(),
                request.checksumUrl() == null || request.checksumUrl().isBlank() ? checksumUrl : request.checksumUrl().trim()
            );
        }

        private static boolean bool(String value, boolean fallback) {
            return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
        }

        private static String text(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private record ParsedCatalog(
        Map<String, OfficialModelPrice> prices,
        JsonNode json,
        byte[] raw
    ) {
    }
}
