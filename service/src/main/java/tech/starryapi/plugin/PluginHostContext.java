package tech.starryapi.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.admin.AdminSessionService;
import tech.starryapi.admin.AdminUserRepository;
import tech.starryapi.admin.AdminUserEntity;
import tech.starryapi.plugin.sdk.PluginContext;
import tech.starryapi.plugin.sdk.PluginHttpEvent;
import tech.starryapi.plugin.sdk.PluginJson;
import tech.starryapi.plugin.sdk.PluginTasks;

final class PluginHostContext implements PluginContext {
    private static final Set<String> HOP_HEADERS = Set.of("host", "content-length", "connection", "transfer-encoding",
        "upgrade", "proxy-authorization", "x-admin-token", "cookie");
    private final PluginStore.Installed plugin;
    private final ApplicationContext host;
    private final PluginStore store;
    private final PluginLogs logs;
    private final Path data;
    private final URI base;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Sinks.Empty<Void> revoked = Sinks.empty();
    private final Object requestsLock = new Object();
    private final Map<String, AdminSessionService> sessions = new HashMap<>();
    private int activeRequests;
    private final PluginJsonCodec json;
    private final PluginResourceScope resources;
    private final PluginTaskScheduler tasks;
    private final WebClient client = WebClient.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();

    PluginHostContext(PluginStore.Installed plugin, ApplicationContext host, PluginStore store,
                      PluginLogs logs, Path data, URI base, ObjectMapper mapper) {
        this.plugin = plugin;
        this.host = host;
        this.store = store;
        this.logs = logs;
        this.data = data;
        this.base = base;
        this.json = new PluginJsonCodec(mapper, this::requireActive);
        this.resources = new PluginResourceScope(plugin.id(), logs, active::get);
        this.tasks = new PluginTaskScheduler(plugin.id(), logs, active::get);
    }

    void deactivate() {
        synchronized (requestsLock) {
            if (!active.compareAndSet(true, false)) return;
            sessions.forEach((token, service) -> service.invalidateSession(token));
        }
        tasks.close();
        revoked.tryEmitEmpty();
    }

    void revoke() { deactivate(); resources.close(); }
    PluginCleanupReport cleanup(Duration grace) throws InterruptedException {
        long end = System.nanoTime() + grace.toNanos();
        revoke();
        boolean terminated = tasks.awaitStopped(remaining(end));
        resources.awaitIdle(remaining(end));
        synchronized (requestsLock) {
            while (activeRequests > 0 && System.nanoTime() < end) {
                TimeUnit.NANOSECONDS.timedWait(requestsLock, Math.max(1, end - System.nanoTime()));
            }
            return new PluginCleanupReport(resources.failures(), resources.pending(), terminated, activeRequests);
        }
    }

    private static Duration remaining(long end) { return Duration.ofNanos(Math.max(0, end - System.nanoTime())); }
    void stopTasks() { tasks.close(); }
    private void requireActive() { if (!active.get()) throw new IllegalStateException("Plugin context has been revoked"); }
    @Override public String pluginId() { return plugin.id(); }
    @Override public String configJson() { requireActive(); return plugin.config(); }
    @Override public Path dataDirectory() { requireActive(); return data; }
    @Override public String sdkVersion() { return "1.1"; }
    @Override public Set<String> capabilities() {
        return Set.of("json", "service-discovery", "managed-resources", "managed-tasks", "http-stream");
    }
    @Override public PluginJson json() { requireActive(); return json; }
    @Override public PluginTasks tasks() { requireActive(); return tasks; }
    @Override public Disposable manage(AutoCloseable resource) { return resources.add(resource); }

    @Override public <T> T service(Class<T> apiType) {
        requireActive();
        log(Level.DEBUG, "SERVICE_LOOKUP " + apiType.getName());
        return host.getBean(apiType);
    }

    @Override public <T> T service(String beanName, Class<T> apiType) {
        requireActive();
        log(Level.DEBUG, "SERVICE_LOOKUP " + apiType.getName());
        return host.getBean(beanName, apiType);
    }

    @Override public <T> Map<String, T> services(Class<T> apiType) {
        requireActive();
        log(Level.DEBUG, "SERVICE_DISCOVERY " + apiType.getName());
        return Map.copyOf(host.getBeansOfType(apiType));
    }

    @Override public Mono<ApiResult> request(String method, String relativePath, Map<String, String> headers, byte[] body) {
        return Mono.defer(() -> {
            beginRequest();
            return bufferedRequest(method, relativePath, headers, body).doFinally(signal -> endRequest());
        });
    }

    private Mono<ApiResult> bufferedRequest(String method, String relativePath, Map<String, String> headers, byte[] body) {
        return Mono.defer(() -> {
            requireActive();
            URI relative = localPath(relativePath);
            String auditPath = relative.getPath();
            long start = System.nanoTime();
            return rootIdentity().flatMap(root -> {
                AdminSessionService sessions = host.getBean(AdminSessionService.class);
                return Mono.using(() -> createSession(sessions, root), token -> {
                    requireActive();
                    return requestSpec(method, relative, headers, body, token)
                        .exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                            .map(bytes -> new ApiResult(response.statusCode().value(),
                                Map.copyOf(response.headers().asHttpHeaders().toSingleValueMap()), bytes)));
                }, this::releaseSession)
                    .timeout(java.time.Duration.ofSeconds(60))
                    .doOnNext(result -> log(Level.INFO, "API " + method + " " + auditPath + " status=" + result.status()
                        + " duration_ms=" + (System.nanoTime() - start) / 1_000_000))
                    .doOnError(error -> log(Level.WARN, "API " + method + " " + auditPath + " failed=" + error.getClass().getSimpleName()));
            });
        }).takeUntilOther(revocation()).switchIfEmpty(Mono.defer(() -> {
            requireActive();
            return Mono.empty();
        }));
    }

    @Override public Flux<PluginHttpEvent> requestStream(String method, String path, Map<String, String> headers, byte[] body) {
        return Flux.defer(() -> {
            beginRequest();
            return streamingRequest(method, path, headers, body).doFinally(signal -> endRequest());
        });
    }

    private Flux<PluginHttpEvent> streamingRequest(String method, String path, Map<String, String> headers, byte[] body) {
        return Flux.defer(() -> {
            requireActive();
            URI relative = localPath(path);
            long start = System.nanoTime();
            return rootIdentity().flatMapMany(root -> {
                AdminSessionService sessions = host.getBean(AdminSessionService.class);
                return Flux.using(() -> createSession(sessions, root), token -> {
                    requireActive();
                    return requestSpec(method, relative, headers, body, token).exchangeToFlux(response -> {
                        log(Level.INFO, "API_STREAM " + method + " " + relative.getPath() + " status=" + response.statusCode().value());
                        var head = new PluginHttpEvent.Head(response.statusCode().value(), response.headers().asHttpHeaders());
                        return Flux.<PluginHttpEvent>just(head).concatWith(response.bodyToFlux(DataBuffer.class)
                            .map(buffer -> {
                                try {
                                    byte[] bytes = new byte[buffer.readableByteCount()];
                                    buffer.read(bytes);
                                    return (PluginHttpEvent) new PluginHttpEvent.Data(bytes);
                                } finally { DataBufferUtils.release(buffer); }
                            }).doOnDiscard(DataBuffer.class, DataBufferUtils::release));
                    });
                }, this::releaseSession);
            }).timeout(java.time.Duration.ofSeconds(60))
                .doOnError(error -> log(Level.WARN, "API_STREAM " + method + " " + relative.getPath() + " failed=" + error.getClass().getSimpleName()))
                .doFinally(signal -> log(Level.INFO, "API_STREAM " + method + " " + relative.getPath() + " ended=" + signal
                    + " duration_ms=" + (System.nanoTime() - start) / 1_000_000));
        }).takeUntilOther(revocation()).concatWith(Flux.defer(() -> {
            requireActive();
            return Flux.empty();
        }));
    }

    private Mono<Void> revocation() {
        // A completion notifier cancels upstream before we expose the revocation error downstream.
        return revoked.asMono().publishOn(Schedulers.boundedElastic());
    }

    private void beginRequest() {
        synchronized (requestsLock) { requireActive(); activeRequests++; }
    }

    private void endRequest() {
        synchronized (requestsLock) { activeRequests--; requestsLock.notifyAll(); }
    }

    private String createSession(AdminSessionService service, AdminUserEntity root) {
        synchronized (requestsLock) {
            requireActive();
            String token = service.create(root).token();
            sessions.put(token, service);
            return token;
        }
    }

    private void releaseSession(String token) {
        synchronized (requestsLock) {
            var service = sessions.remove(token);
            if (service != null) service.invalidateSession(token);
        }
    }

    private Mono<AdminUserEntity> rootIdentity() {
        return store.security().flatMap(state -> {
            requireActive();
            if (!state.enabled()) return Mono.error(new IllegalStateException("Developer mode is disabled"));
            return host.getBean(AdminUserRepository.class).findApplicationRoot()
                .filter(root -> Boolean.TRUE.equals(root.getEnabled()) && !Boolean.TRUE.equals(root.getFirstLogin()))
                .switchIfEmpty(Mono.error(new IllegalStateException("Root identity unavailable")));
        });
    }

    private WebClient.RequestHeadersSpec<?> requestSpec(String method, URI relative, Map<String, String> headers, byte[] body, String token) {
        return client.method(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)))
            .uri(base.resolve(relative)).headers(h -> {
                h.setBearerAuth(token);
                h.set("Content-Type", "application/json");
                if (headers != null) headers.forEach((name, value) -> {
                    if (!HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) h.set(name, value);
                });
            }).bodyValue(body == null ? new byte[0] : body);
    }

    private static URI localPath(String path) {
        URI relative = URI.create(path);
        if (!path.startsWith("/") || path.startsWith("//") || relative.isAbsolute()
            || relative.getRawAuthority() != null || relative.getRawFragment() != null || path.contains("\\")) {
            throw new IllegalArgumentException("Only same-host absolute paths are accepted");
        }
        return relative;
    }

    @Override public void log(Level level, String message) {
        logs.add(plugin.id(), level == null ? Level.INFO : level, message);
    }
}
