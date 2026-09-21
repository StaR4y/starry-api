package tech.starryapi.admin;

final class AppearanceRuntime {

    private static volatile AppearanceDtos.AppearanceResponse current = AppearanceSettingsService.defaults();

    private AppearanceRuntime() {
    }

    static AppearanceDtos.AppearanceResponse current() {
        return current;
    }

    static String siteName() {
        String name = current.siteName();
        return name == null || name.isBlank() ? AppearanceDtos.DEFAULT_SITE_NAME : name.trim();
    }

    static void replace(AppearanceDtos.AppearanceResponse next) {
        AppearanceDtos.AppearanceResponse resolved = next == null ? AppearanceSettingsService.defaults() : next;
        current = resolved;
        BrandTheme.activate(BrandTheme.fromAppearance(resolved));
    }
}
