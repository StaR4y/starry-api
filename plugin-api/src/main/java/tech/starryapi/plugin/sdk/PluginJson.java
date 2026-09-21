package tech.starryapi.plugin.sdk;

/** JSON conversion without exposing the host's mutable ObjectMapper. */
public interface PluginJson {
    <T> T read(String json, Class<T> type);
    String write(Object value);
}
