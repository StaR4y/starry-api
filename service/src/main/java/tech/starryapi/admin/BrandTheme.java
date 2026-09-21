package tech.starryapi.admin;

/**
 * Console and HTML email share this palette.
 *
 * Hex values must stay in lockstep with {@code webui/src/assets/styles/global.scss}
 * ({@code --kp-*}). When a new platform theme is designed, update this record and
 * the SCSS tokens together so mailers pick up the same colors.
 */
public record BrandTheme(
    String primary,
    String primaryMid,
    String accent,
    String danger,
    String header,
    String page,
    String cardBorder,
    String rule,
    String surface,
    String surfaceDanger,
    String text,
    String textSecondary,
    String textMuted,
    String onHeader,
    String onHeaderMuted
) {

    /**
     * Current Starry API theme. Swap or add palettes here when the console
     * gets additional color schemes.
     */
    public static final BrandTheme STARRY = new BrandTheme(
        "#5b8def",
        "#7b7cff",
        "#b387ff",
        "#ef5b6b",
        "#1a1f36",
        "#eef0f4",
        "#d8dce4",
        "#e6e8ee",
        "#f6f7fa",
        "#f8f1f1",
        "#1f2a44",
        "#54607a",
        "#8a93a8",
        "#ffffff",
        "#d7dbe6"
    );

    private static volatile BrandTheme active = STARRY;

    public static BrandTheme current() {
        return active;
    }

    public static void activate(BrandTheme theme) {
        active = theme == null ? STARRY : theme;
    }

    public static BrandTheme fromAppearance(AppearanceDtos.AppearanceResponse appearance) {
        if (appearance == null) {
            return STARRY;
        }
        return new BrandTheme(
            or(appearance.primary(), STARRY.primary),
            or(appearance.primaryMid(), STARRY.primaryMid),
            or(appearance.accent(), STARRY.accent),
            or(appearance.danger(), STARRY.danger),
            or(appearance.header(), STARRY.header),
            STARRY.page,
            STARRY.cardBorder,
            STARRY.rule,
            STARRY.surface,
            STARRY.surfaceDanger,
            or(appearance.text(), STARRY.text),
            or(appearance.textSecondary(), STARRY.textSecondary),
            or(appearance.textMuted(), STARRY.textMuted),
            STARRY.onHeader,
            STARRY.onHeaderMuted
        );
    }

    public String headerGradientCss() {
        return "linear-gradient(90deg," + primary + " 0%," + primaryMid + " 50%," + accent + " 100%)";
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
