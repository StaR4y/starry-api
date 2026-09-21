package tech.starryapi.plugin.sdk;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Full-trust developer API. This interface is not a sandbox or a permission boundary. */
public interface PluginContext {
    String pluginId();
    String configJson();
    Path dataDirectory();
    <T> T service(Class<T> apiType);
    Mono<ApiResult> request(String method, String relativePath, Map<String, String> headers, byte[] body);
    void log(Level level, String message);

    /** Feature discovery; manifest compatibility remains Starry-Plugin-Api: 1. */
    default String sdkVersion() { return "1.0"; }
    default Set<String> capabilities() { return Set.of(); }

    default <T> T service(String beanName, Class<T> apiType) {
        throw new UnsupportedOperationException("Named service lookup requires SDK 1.1");
    }

    default <T> Map<String, T> services(Class<T> apiType) {
        throw new UnsupportedOperationException("Service discovery requires SDK 1.1");
    }

    default PluginJson json() { throw new UnsupportedOperationException("JSON support requires SDK 1.1"); }

    /** Decodes the current load's immutable configuration snapshot. */
    default <T> T config(Class<T> type) { return json().read(configJson(), type); }

    /** Disposing the returned handle closes and unregisters the resource exactly once. */
    default Disposable manage(AutoCloseable resource) {
        throw new UnsupportedOperationException("Managed resources require SDK 1.1");
    }

    default PluginTasks tasks() { throw new UnsupportedOperationException("Managed tasks require SDK 1.1"); }

    /** Emits one response head followed by raw chunks, not parsed SSE events or complete UTF-8 strings. */
    default Flux<PluginHttpEvent> requestStream(String method, String path, Map<String, String> headers, byte[] body) {
        return Flux.error(new UnsupportedOperationException("Streaming HTTP requires SDK 1.1"));
    }

    enum Level { DEBUG, INFO, WARN, ERROR }

    record ApiResult(int status, Map<String, String> headers, byte[] body) {}
}
