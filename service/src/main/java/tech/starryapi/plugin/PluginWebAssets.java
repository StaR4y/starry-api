package tech.starryapi.plugin;

final class PluginWebAssets {
    private PluginWebAssets() {
    }

    static String classpathResource(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        String clean = relativePath.replace('\\', '/').replaceFirst("^/+", "");
        if (clean.isBlank() || clean.contains("..") || clean.contains(":") || !clean.matches("[A-Za-z0-9._\\-/]+")) {
            return null;
        }
        return "web/" + clean;
    }
}
