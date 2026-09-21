package tech.starryapi.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;

@Service
public class AppearanceSettingsService {

    static final String SITE_NAME = "appearance_site_name";
    static final String LOGO_URL = "appearance_logo_url";
    static final String FAVICON_URL = "appearance_favicon_url";
    static final String BACKGROUND_URL = "appearance_background_url";
    static final String PRESET = "appearance_preset";
    static final String PRIMARY = "appearance_primary";
    static final String PRIMARY_MID = "appearance_primary_mid";
    static final String ACCENT = "appearance_accent";
    static final String SUCCESS = "appearance_success";
    static final String WARNING = "appearance_warning";
    static final String DANGER = "appearance_danger";
    static final String HEADER = "appearance_header";
    static final String TEXT = "appearance_text";
    static final String TEXT_SECONDARY = "appearance_text_secondary";
    static final String TEXT_MUTED = "appearance_text_muted";
    static final String SIDEBAR_OPACITY = "appearance_sidebar_opacity";
    static final String SURFACE_OPACITY = "appearance_surface_opacity";
    static final String BLUR_PX = "appearance_blur_px";
    static final String BACKGROUND_BLUR_PX = "appearance_background_blur_px";
    static final String CHROME = "appearance_chrome";

    private static final String LOCK = "starry_api:system_settings:appearance";
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private static final Pattern HEX = Pattern.compile("^#([0-9a-fA-F]{6})$");
    private static final List<String> SETTING_KEYS = List.of(
        SITE_NAME,
        LOGO_URL,
        FAVICON_URL,
        BACKGROUND_URL,
        PRESET,
        PRIMARY,
        PRIMARY_MID,
        ACCENT,
        SUCCESS,
        WARNING,
        DANGER,
        HEADER,
        TEXT,
        TEXT_SECONDARY,
        TEXT_MUTED,
        SIDEBAR_OPACITY,
        SURFACE_OPACITY,
        BLUR_PX,
        BACKGROUND_BLUR_PX,
        CHROME
    );

    private final SystemSettingRepository repository;
    private final BrandingStorageService brandingStorageService;
    private final AtomicReference<CachedAppearance> cache = new AtomicReference<>();

    public AppearanceSettingsService(
        SystemSettingRepository repository,
        BrandingStorageService brandingStorageService
    ) {
        this.repository = repository;
        this.brandingStorageService = brandingStorageService;
    }

    static AppearanceDtos.AppearanceResponse defaults() {
        BrandTheme theme = BrandTheme.STARRY;
        return new AppearanceDtos.AppearanceResponse(
            AppearanceDtos.DEFAULT_SITE_NAME,
            null,
            null,
            null,
            AppearanceDtos.PRESET_DEFAULT,
            theme.primary(),
            theme.primaryMid(),
            theme.accent(),
            "#36c08a",
            "#f0a84d",
            theme.danger(),
            theme.header(),
            theme.text(),
            theme.textSecondary(),
            theme.textMuted(),
            0.62,
            0.62,
            18,
            0,
            AppearanceDtos.CHROME_CLASSIC
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmCache() {
        settings().subscribe();
    }

    public Mono<AppearanceDtos.AppearanceResponse> settings() {
        CachedAppearance cached = cache.get();
        if (cached != null && !cached.expired()) {
            return Mono.just(cached.response);
        }
        return load().doOnNext(this::remember);
    }

    public void invalidateCache() {
        cache.set(null);
    }

    @Transactional
    public Mono<AppearanceDtos.AppearanceResponse> update(AppearanceDtos.UpdateAppearanceRequest request) {
        return repository.acquireTransactionLock(LOCK)
            .then(load())
            .flatMap(previous -> {
                AppearanceDtos.AppearanceResponse merged = merge(previous, request);
                return persist(merged, previous);
            });
    }

    @Transactional
    public Mono<AppearanceDtos.AppearanceResponse> reset() {
        return repository.acquireTransactionLock(LOCK)
            .then(load())
            .flatMap(previous -> persist(defaults(), previous));
    }

    @Transactional
    public Mono<AppearanceDtos.AppearanceResponse> upload(String kind, Mono<FilePart> file) {
        return repository.acquireTransactionLock(LOCK)
            .then(load())
            .flatMap(previous -> brandingStorageService.store(kind, file)
                .flatMap(stored -> {
                    AppearanceDtos.AppearanceResponse next = withAsset(previous, kind, stored.url());
                    return persist(next, previous);
                }));
    }

    @Transactional
    public Mono<AppearanceDtos.AppearanceResponse> clearAsset(String kind) {
        return repository.acquireTransactionLock(LOCK)
            .then(load())
            .flatMap(previous -> persist(withAsset(previous, kind, null), previous));
    }

    private Mono<AppearanceDtos.AppearanceResponse> persist(
        AppearanceDtos.AppearanceResponse next,
        AppearanceDtos.AppearanceResponse previous
    ) {
        List<String> keys = SETTING_KEYS;
        String[] values = keys.stream().map(key -> storedValue(key, next)).toArray(String[]::new);
        return repository.upsertSettings(keys.toArray(String[]::new), values)
            .then(Mono.fromRunnable(() -> deleteReplacedAssets(previous, next)))
            .thenReturn(next)
            .doOnNext(this::remember);
    }

    private Mono<AppearanceDtos.AppearanceResponse> load() {
        return repository.findAllById(SETTING_KEYS)
            .collectMap(SystemSettingEntity::getSettingKey, SystemSettingEntity::getSettingValue)
            .map(this::fromStored);
    }

    private AppearanceDtos.AppearanceResponse fromStored(Map<String, String> stored) {
        AppearanceDtos.AppearanceResponse fallback = defaults();
        Map<String, String> values = new LinkedHashMap<>();
        SETTING_KEYS.forEach(key -> values.put(key, ""));
        if (stored != null) {
            stored.forEach((key, value) -> {
                if (value != null) {
                    values.put(key, value);
                }
            });
        }
        String preset = normalizePreset(values.get(PRESET));
        AppearanceDtos.AppearanceResponse parsed = new AppearanceDtos.AppearanceResponse(
            firstNonBlank(values.get(SITE_NAME), fallback.siteName()),
            blankToNull(values.get(LOGO_URL)),
            blankToNull(values.get(FAVICON_URL)),
            blankToNull(values.get(BACKGROUND_URL)),
            preset,
            hexStored(values.get(PRIMARY), fallback.primary()),
            hexStored(values.get(PRIMARY_MID), fallback.primaryMid()),
            hexStored(values.get(ACCENT), fallback.accent()),
            hexStored(values.get(SUCCESS), fallback.success()),
            hexStored(values.get(WARNING), fallback.warning()),
            hexStored(values.get(DANGER), fallback.danger()),
            hexStored(values.get(HEADER), fallback.header()),
            hexStored(values.get(TEXT), fallback.text()),
            hexStored(values.get(TEXT_SECONDARY), fallback.textSecondary()),
            hexStored(values.get(TEXT_MUTED), fallback.textMuted()),
            decimalOr(values.get(SIDEBAR_OPACITY), fallback.sidebarOpacity(), 0.15, 1.0),
            decimalOr(values.get(SURFACE_OPACITY), fallback.surfaceOpacity(), 0.20, 0.95),
            integerOr(values.get(BLUR_PX), fallback.blurPx(), 4, 48),
            integerOr(values.get(BACKGROUND_BLUR_PX), fallback.backgroundBlurPx(), 0, 40),
            normalizeChrome(values.get(CHROME))
        );
        return parsed;
    }

    private AppearanceDtos.AppearanceResponse merge(
        AppearanceDtos.AppearanceResponse previous,
        AppearanceDtos.UpdateAppearanceRequest request
    ) {
        if (request == null) {
            return previous;
        }
        if (Boolean.TRUE.equals(request.restoreDefault())) {
            return defaults();
        }
        String preset = request.preset() == null ? previous.preset() : normalizePreset(request.preset());
        AppearanceDtos.AppearanceResponse base = previous;
        String siteName = request.siteName() == null
            ? base.siteName()
            : firstNonBlank(request.siteName().trim(), AppearanceDtos.DEFAULT_SITE_NAME);
        if (siteName.length() > 40) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "APPEARANCE_SITE_NAME_TOO_LONG", "Site name is too long");
        }
        AppearanceDtos.AppearanceResponse merged = new AppearanceDtos.AppearanceResponse(
            siteName,
            Boolean.TRUE.equals(request.clearLogo()) ? null : base.logoUrl(),
            Boolean.TRUE.equals(request.clearFavicon()) ? null : base.faviconUrl(),
            Boolean.TRUE.equals(request.clearBackground()) ? null : base.backgroundImageUrl(),
            preset,
            hexOr(request.primary(), base.primary()),
            hexOr(request.primaryMid(), base.primaryMid()),
            hexOr(request.accent(), base.accent()),
            hexOr(request.success(), base.success()),
            hexOr(request.warning(), base.warning()),
            hexOr(request.danger(), base.danger()),
            hexOr(request.header(), base.header()),
            hexOr(request.text(), base.text()),
            hexOr(request.textSecondary(), base.textSecondary()),
            hexOr(request.textMuted(), base.textMuted()),
            request.sidebarOpacity() == null ? base.sidebarOpacity() : clamp(request.sidebarOpacity(), 0.15, 1.0),
            request.surfaceOpacity() == null ? base.surfaceOpacity() : clamp(request.surfaceOpacity(), 0.20, 0.95),
            request.blurPx() == null ? base.blurPx() : clamp(request.blurPx(), 4, 48),
            request.backgroundBlurPx() == null ? base.backgroundBlurPx() : clamp(request.backgroundBlurPx(), 0, 40),
            request.chrome() == null ? base.chrome() : normalizeChrome(request.chrome())
        );
        if (request.preset() == null && !matchesPreset(merged)) {
            return withPreset(merged, AppearanceDtos.PRESET_CUSTOM);
        }
        return merged;
    }

    private AppearanceDtos.AppearanceResponse withAsset(
        AppearanceDtos.AppearanceResponse previous,
        String kind,
        String url
    ) {
        return switch (kind) {
            case "logo" -> new AppearanceDtos.AppearanceResponse(
                previous.siteName(), url, previous.faviconUrl(), previous.backgroundImageUrl(),
                previous.preset(), previous.primary(), previous.primaryMid(), previous.accent(),
                previous.success(), previous.warning(), previous.danger(), previous.header(),
                previous.text(), previous.textSecondary(), previous.textMuted(),
                previous.sidebarOpacity(), previous.surfaceOpacity(), previous.blurPx(), previous.backgroundBlurPx(),
                previous.chrome()
            );
            case "favicon" -> new AppearanceDtos.AppearanceResponse(
                previous.siteName(), previous.logoUrl(), url, previous.backgroundImageUrl(),
                previous.preset(), previous.primary(), previous.primaryMid(), previous.accent(),
                previous.success(), previous.warning(), previous.danger(), previous.header(),
                previous.text(), previous.textSecondary(), previous.textMuted(),
                previous.sidebarOpacity(), previous.surfaceOpacity(), previous.blurPx(), previous.backgroundBlurPx(),
                previous.chrome()
            );
            case "background" -> new AppearanceDtos.AppearanceResponse(
                previous.siteName(), previous.logoUrl(), previous.faviconUrl(), url,
                previous.preset(), previous.primary(), previous.primaryMid(), previous.accent(),
                previous.success(), previous.warning(), previous.danger(), previous.header(),
                previous.text(), previous.textSecondary(), previous.textMuted(),
                previous.sidebarOpacity(), previous.surfaceOpacity(), previous.blurPx(), previous.backgroundBlurPx(),
                previous.chrome()
            );
            default -> throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "BRANDING_KIND_INVALID",
                "Unknown branding asset kind"
            );
        };
    }

    private void deleteReplacedAssets(
        AppearanceDtos.AppearanceResponse previous,
        AppearanceDtos.AppearanceResponse next
    ) {
        if (previous == null) {
            return;
        }
        if (changed(previous.logoUrl(), next.logoUrl())) {
            brandingStorageService.deletePublicUrl(previous.logoUrl());
        }
        if (changed(previous.faviconUrl(), next.faviconUrl())) {
            brandingStorageService.deletePublicUrl(previous.faviconUrl());
        }
        if (changed(previous.backgroundImageUrl(), next.backgroundImageUrl())) {
            brandingStorageService.deletePublicUrl(previous.backgroundImageUrl());
        }
    }

    private boolean changed(String previous, String next) {
        if (previous == null || previous.isBlank()) {
            return false;
        }
        return !previous.equals(next);
    }

    private void remember(AppearanceDtos.AppearanceResponse response) {
        cache.set(new CachedAppearance(response, Instant.now().plus(CACHE_TTL)));
        AppearanceRuntime.replace(response);
    }

    private String storedValue(String key, AppearanceDtos.AppearanceResponse appearance) {
        return switch (key) {
            case SITE_NAME -> nullToEmpty(appearance.siteName());
            case LOGO_URL -> nullToEmpty(appearance.logoUrl());
            case FAVICON_URL -> nullToEmpty(appearance.faviconUrl());
            case BACKGROUND_URL -> nullToEmpty(appearance.backgroundImageUrl());
            case PRESET -> appearance.preset();
            case PRIMARY -> appearance.primary();
            case PRIMARY_MID -> appearance.primaryMid();
            case ACCENT -> appearance.accent();
            case SUCCESS -> appearance.success();
            case WARNING -> appearance.warning();
            case DANGER -> appearance.danger();
            case HEADER -> appearance.header();
            case TEXT -> appearance.text();
            case TEXT_SECONDARY -> appearance.textSecondary();
            case TEXT_MUTED -> appearance.textMuted();
            case SIDEBAR_OPACITY -> Double.toString(appearance.sidebarOpacity());
            case SURFACE_OPACITY -> Double.toString(appearance.surfaceOpacity());
            case BLUR_PX -> Integer.toString(appearance.blurPx());
            case BACKGROUND_BLUR_PX -> Integer.toString(appearance.backgroundBlurPx());
            case CHROME -> nullToEmpty(normalizeChrome(appearance.chrome()));
            default -> "";
        };
    }

    private static String normalizePreset(String preset) {
        String normalized = preset == null ? "" : preset.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || AppearanceDtos.PRESET_DEFAULT.equals(normalized)) {
            return AppearanceDtos.PRESET_DEFAULT;
        }
        return AppearanceDtos.PRESET_CUSTOM;
    }

    private static boolean matchesPreset(AppearanceDtos.AppearanceResponse appearance) {
        if (!AppearanceDtos.PRESET_DEFAULT.equals(appearance.preset())) {
            return true;
        }
        AppearanceDtos.AppearanceResponse factory = defaults();
        return appearance.primary().equalsIgnoreCase(factory.primary())
            && appearance.primaryMid().equalsIgnoreCase(factory.primaryMid())
            && appearance.accent().equalsIgnoreCase(factory.accent())
            && appearance.sidebarOpacity().equals(factory.sidebarOpacity())
            && appearance.surfaceOpacity().equals(factory.surfaceOpacity())
            && appearance.blurPx().equals(factory.blurPx());
    }

    private static AppearanceDtos.AppearanceResponse withPreset(
        AppearanceDtos.AppearanceResponse appearance,
        String preset
    ) {
        return new AppearanceDtos.AppearanceResponse(
            appearance.siteName(),
            appearance.logoUrl(),
            appearance.faviconUrl(),
            appearance.backgroundImageUrl(),
            preset,
            appearance.primary(),
            appearance.primaryMid(),
            appearance.accent(),
            appearance.success(),
            appearance.warning(),
            appearance.danger(),
            appearance.header(),
            appearance.text(),
            appearance.textSecondary(),
            appearance.textMuted(),
            appearance.sidebarOpacity(),
            appearance.surfaceOpacity(),
            appearance.blurPx(),
            appearance.backgroundBlurPx(),
            appearance.chrome()
        );
    }

    private static String normalizeChrome(String chrome) {
        String normalized = chrome == null ? "" : chrome.trim().toLowerCase(Locale.ROOT);
        if (AppearanceDtos.CHROME_EDITORIAL.equals(normalized)) {
            return AppearanceDtos.CHROME_EDITORIAL;
        }
        return AppearanceDtos.CHROME_CLASSIC;
    }

    private static String hexOr(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) {
            return fallback;
        }
        if (!HEX.matcher(cleaned).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "APPEARANCE_COLOR_INVALID", "Color must be #RRGGBB");
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private static String hexStored(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank() || !HEX.matcher(cleaned).matches()) {
            return fallback;
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private static Double decimalOr(String value, Double fallback, double min, double max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return clamp(Double.parseDouble(value.trim()), min, max);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static Integer integerOr(String value, Integer fallback, int min, int max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return clamp(Integer.parseInt(value.trim()), min, max);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record CachedAppearance(AppearanceDtos.AppearanceResponse response, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
