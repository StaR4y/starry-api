package tech.starryapi.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public final class AppearanceDtos {

    private AppearanceDtos() {
    }

    public static final String PRESET_DEFAULT = "default";
    public static final String PRESET_CUSTOM = "custom";
    public static final String CHROME_CLASSIC = "classic";
    public static final String CHROME_EDITORIAL = "editorial";
    public static final String DEFAULT_SITE_NAME = "Starry API";

    public record AppearanceResponse(
        String siteName,
        String logoUrl,
        String faviconUrl,
        String backgroundImageUrl,
        String preset,
        String primary,
        String primaryMid,
        String accent,
        String success,
        String warning,
        String danger,
        String header,
        String text,
        String textSecondary,
        String textMuted,
        Double sidebarOpacity,
        Double surfaceOpacity,
        Integer blurPx,
        Integer backgroundBlurPx,
        @JsonProperty("chrome")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        String chrome
    ) {
        public AppearanceResponse {
            chrome = CHROME_EDITORIAL.equalsIgnoreCase(chrome == null ? "" : chrome.trim())
                ? CHROME_EDITORIAL
                : CHROME_CLASSIC;
        }

        public AppearanceResponse(
            String siteName,
            String logoUrl,
            String faviconUrl,
            String backgroundImageUrl,
            String preset,
            String primary,
            String primaryMid,
            String accent,
            String success,
            String warning,
            String danger,
            String header,
            String text,
            String textSecondary,
            String textMuted,
            Double sidebarOpacity,
            Double surfaceOpacity,
            Integer blurPx,
            Integer backgroundBlurPx
        ) {
            this(
                siteName,
                logoUrl,
                faviconUrl,
                backgroundImageUrl,
                preset,
                primary,
                primaryMid,
                accent,
                success,
                warning,
                danger,
                header,
                text,
                textSecondary,
                textMuted,
                sidebarOpacity,
                surfaceOpacity,
                blurPx,
                backgroundBlurPx,
                CHROME_CLASSIC
            );
        }
    }

    public record UpdateAppearanceRequest(
        @Size(max = 40) String siteName,
        String preset,
        String primary,
        String primaryMid,
        String accent,
        String success,
        String warning,
        String danger,
        String header,
        String text,
        String textSecondary,
        String textMuted,
        @DecimalMin("0.15") @DecimalMax("1.0") Double sidebarOpacity,
        @DecimalMin("0.20") @DecimalMax("0.95") Double surfaceOpacity,
        @Min(4) @Max(48) Integer blurPx,
        @Min(0) @Max(40) Integer backgroundBlurPx,
        Boolean clearLogo,
        Boolean clearFavicon,
        Boolean clearBackground,
        Boolean restoreDefault,
        @JsonProperty("chrome") String chrome
    ) {
        public UpdateAppearanceRequest(
            String siteName,
            String preset,
            String primary,
            String primaryMid,
            String accent,
            String success,
            String warning,
            String danger,
            String header,
            String text,
            String textSecondary,
            String textMuted,
            Double sidebarOpacity,
            Double surfaceOpacity,
            Integer blurPx,
            Integer backgroundBlurPx,
            Boolean clearLogo,
            Boolean clearFavicon,
            Boolean clearBackground,
            Boolean restoreDefault
        ) {
            this(
                siteName,
                preset,
                primary,
                primaryMid,
                accent,
                success,
                warning,
                danger,
                header,
                text,
                textSecondary,
                textMuted,
                sidebarOpacity,
                surfaceOpacity,
                blurPx,
                backgroundBlurPx,
                clearLogo,
                clearFavicon,
                clearBackground,
                restoreDefault,
                null
            );
        }
    }

    public record BrandingUploadResponse(
        String url,
        String fileName,
        long sizeBytes,
        String kind
    ) {
    }
}
