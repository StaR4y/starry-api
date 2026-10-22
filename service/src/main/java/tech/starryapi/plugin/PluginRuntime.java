package tech.starryapi.plugin;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.pf4j.JarPluginManager;
import org.pf4j.PluginState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;
import tech.starryapi.plugin.sdk.PluginContext;
import tech.starryapi.plugin.sdk.StarryPlugin;
import tools.jackson.databind.ObjectMapper;

@Service
public class PluginRuntime {
    private static final Duration DB_TIMEOUT = Duration.ofSeconds(15);
    private final PluginStore store;
    private final PluginLogs logs;
    private final ApplicationContext host;
    private final ObjectMapper json;
    private final SystemMaintenanceTaskGate maintenance;
    private final Path root;
    private final boolean runtimeDisabled;
    private final JarPluginManager manager;
    private final Scheduler operations = Schedulers.newBoundedElastic(1, 32, "plugin-lifecycle");
    private final Map<String, String> states = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final Map<String, PluginHostContext> contexts = new ConcurrentHashMap<>();
    private final Map<String, PluginDtos.RestartReason> restartReasons = new ConcurrentHashMap<>();
    private final ThreadLocal<Execution> execution = new ThreadLocal<>();
    private final Duration operationTimeout;
    private final Duration cleanupGrace;
    private volatile URI base = URI.create("http://127.0.0.1:8080/");

    @Autowired
    public PluginRuntime(PluginStore store, PluginLogs logs, ApplicationContext host, ObjectMapper json,
                         SystemMaintenanceTaskGate maintenance,
                         @Value("${starry.plugins.directory:./data/plugins}") String directory,
                         @Value("${starry.plugins.disabled:false}") boolean runtimeDisabled) {
        this(store, logs, host, json, maintenance, directory, runtimeDisabled, Duration.ofSeconds(60), Duration.ofSeconds(2));
    }

    PluginRuntime(PluginStore store, PluginLogs logs, ApplicationContext host, ObjectMapper json,
                  SystemMaintenanceTaskGate maintenance, String directory, boolean runtimeDisabled,
                  Duration operationTimeout, Duration cleanupGrace) {
        this.store = store;
        this.logs = logs;
        this.host = host;
        this.json = json;
        this.maintenance = maintenance;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.runtimeDisabled = runtimeDisabled;
        this.operationTimeout = operationTimeout;
        this.cleanupGrace = cleanupGrace;
        this.manager = new JarPluginManager(root.resolve("packages"));
        manager.setSystemVersion("1.0.0");
    }

    @EventListener
    public void serverReady(WebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() != null) return;
        boolean ssl = host.getEnvironment().getProperty("server.ssl.enabled", Boolean.class, false);
        String address = host.getEnvironment().getProperty("server.address", "127.0.0.1");
        if (address.equals("0.0.0.0") || address.equals("::")) address = "127.0.0.1";
        if (address.contains(":")) address = "[" + address + "]";
        base = URI.create((ssl ? "https://" : "http://") + address + ":" + event.getWebServer().getPort() + "/");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startup() {
        maintenance.whenOperational(() -> operation("", "system-startup", "STARTUP", () -> {
            if (runtimeDisabled || !store.security().block(DB_TIMEOUT).enabled()) return true;
            for (var plugin : store.installed().collectList().block(DB_TIMEOUT)) {
                if (!plugin.autoStart()) continue;
                try { start(plugin, "system-startup"); }
                catch (Exception failure) { fail(plugin.id(), "system-startup", "START", failure); }
            }
            return true;
        })).subscribe(ignored -> {}, failure -> logs.add("", PluginContext.Level.ERROR,
            "STARTUP_FAILED " + failure.getClass().getSimpleName()));
    }

    public boolean disabled() { return runtimeDisabled; }
    public boolean restartRequired() { return !restartReasons.isEmpty(); }
    public List<PluginDtos.RestartReason> restartReasons() {
        return restartReasons.values().stream().sorted(java.util.Comparator.comparing(PluginDtos.RestartReason::at)).toList();
    }

    private boolean blocked(String id) { return restartReasons.values().stream().anyMatch(reason -> reason.pluginId().equals(id)); }

    public List<PluginDtos.Widget> widgets() {
        List<PluginDtos.Widget> widgets = new ArrayList<>();
        for (var entry : states.entrySet()) {
            if (!"STARTED".equals(entry.getValue()) || blocked(entry.getKey())) {
                continue;
            }
            WidgetSpec spec = widgetSpec(entry.getKey());
            if (spec == null || spec.image() == null || spec.image().isBlank()) {
                continue;
            }
            String image = PluginWebAssets.classpathResource(spec.image());
            if (image == null) {
                continue;
            }
            String title = spec.title() == null || spec.title().isBlank() ? entry.getKey() : spec.title().trim();
            String scriptUrl = null;
            if (spec.script() != null && !spec.script().isBlank()
                && PluginWebAssets.classpathResource(spec.script()) != null) {
                scriptUrl = "/plugin-assets/" + entry.getKey() + "/" + spec.script().replaceFirst("^/+", "");
            }
            widgets.add(new PluginDtos.Widget(
                entry.getKey(),
                title,
                "/plugin-assets/" + entry.getKey() + "/" + spec.image().replaceFirst("^/+", ""),
                scriptUrl
            ));
        }
        return List.copyOf(widgets);
    }

    public Resource widgetAsset(String pluginId, String relativePath) {
        if (!"STARTED".equals(states.get(pluginId)) || blocked(pluginId)) {
            return null;
        }
        String resource = PluginWebAssets.classpathResource(relativePath);
        if (resource == null) {
            return null;
        }
        var wrapper = manager.getPlugin(pluginId);
        if (wrapper == null) {
            return null;
        }
        URL url = wrapper.getPluginClassLoader().getResource(resource);
        if (url == null) {
            return null;
        }
        return new UrlResource(url);
    }

    private WidgetSpec widgetSpec(String pluginId) {
        var wrapper = manager.getPlugin(pluginId);
        if (wrapper == null) {
            return null;
        }
        try (var stream = wrapper.getPluginClassLoader().getResourceAsStream("web/widget.json")) {
            if (stream == null) {
                return null;
            }
            return json.readValue(stream, WidgetSpec.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record WidgetSpec(String title, String image, String script) {
    }

    public List<PluginDtos.SettingPage> settingPages() {
        List<PluginDtos.SettingPage> pages = new ArrayList<>();
        for (var entry : states.entrySet()) {
            if (!"STARTED".equals(entry.getValue()) || blocked(entry.getKey())) {
                continue;
            }
            PluginDtos.SettingSpec spec = settingSpec(entry.getKey());
            if (spec == null || spec.title() == null || spec.title().isBlank()) {
                continue;
            }
            String group = spec.group() == null || spec.group().isBlank() ? "内容与界面" : spec.group().trim();
            pages.add(new PluginDtos.SettingPage(entry.getKey(), spec.title().trim(), group));
        }
        return List.copyOf(pages);
    }

    public PluginDtos.SettingSpec settingSpec(String pluginId) {
        var wrapper = manager.getPlugin(pluginId);
        if (wrapper == null) {
            return null;
        }
        try (var stream = wrapper.getPluginClassLoader().getResourceAsStream("web/settings.json")) {
            if (stream == null) {
                return null;
            }
            return json.readValue(stream, PluginDtos.SettingSpec.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean moduleSettingsAvailable(String pluginId) {
        return "STARTED".equals(states.get(pluginId)) && !blocked(pluginId) && settingSpec(pluginId) != null;
    }

    public Mono<String> assistantConfigJson() {
        for (var entry : states.entrySet()) {
            if (!"STARTED".equals(entry.getValue()) || blocked(entry.getKey())) {
                continue;
            }
            PluginDtos.SettingSpec spec = settingSpec(entry.getKey());
            if (spec == null || !spec.assistant()) {
                continue;
            }
            String id = entry.getKey();
            return store.installed()
                .filter(plugin -> plugin.id().equals(id))
                .next()
                .map(plugin -> plugin.config() == null || plugin.config().isBlank() ? "{}" : plugin.config());
        }
        return Mono.empty();
    }

    public Mono<Void> saveModuleConfig(String id, String value, String actor) {
        return operation(id, actor, "MODULE_CONFIGURE", () -> {
            find(id);
            if (!moduleSettingsAvailable(id)) {
                throw PluginArchive.invalid("请先启动提供设置页的插件");
            }
            validateJson(value, true);
            store.config(id, value).block(DB_TIMEOUT);
            audit(id, actor, "CONFIGURE", "SUCCEEDED", "module settings updated; values omitted");
            return true;
        }).then();
    }

    public Mono<List<PluginDtos.PluginInfo>> list() {
        return store.installed().map(p -> new PluginDtos.PluginInfo(p.id(), p.version(), p.provider(), p.description(),
            p.sha256(), p.size(), p.autoStart(), blocked(p.id()) ? "RESTART_REQUIRED" : states.getOrDefault(p.id(), "STOPPED"),
            errors.getOrDefault(p.id(), ""), p.installedAt())).collectList();
    }

    public Mono<Void> requireMode() {
        return store.security().flatMap(state -> state.enabled() && !runtimeDisabled ? Mono.empty()
            : Mono.error(PluginSecurityService.error(HttpStatus.FORBIDDEN, "PLUGIN_DEVELOPER_MODE_REQUIRED",
                runtimeDisabled ? "插件已被启动配置强制禁用" : "请先完成双重验证并开启开发者模式")));
    }

    public Mono<Void> install(FilePart file, String actor) {
        if (file.filename() == null || !file.filename().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return Mono.error(PluginArchive.invalid("仅支持普通 .jar 插件包"));
        }
        return requireMode().then(Mono.usingWhen(
            Mono.fromCallable(() -> Files.createTempFile("starry-plugin-", ".jar")).subscribeOn(Schedulers.boundedElastic()),
            path -> {
                AtomicLong size = new AtomicLong();
                var content = file.content().<DataBuffer>handle((buffer, sink) -> {
                    if (size.addAndGet(buffer.readableByteCount()) > PluginArchive.MAX_BYTES) {
                        DataBufferUtils.release(buffer);
                        sink.error(PluginArchive.invalid("插件包不能超过 32 MiB"));
                    } else sink.next(buffer);
                }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                return DataBufferUtils.write(content, path).then(operation("", actor, "INSTALL", () -> {
                    requireMode().block(DB_TIMEOUT);
                    audit("", actor, "INSTALL", "REQUESTED", "JAR upload");
                    String id = "";
                    try {
                        var plugin = PluginArchive.inspect(path);
                        id = plugin.id();
                        requireReloadable(id);
                        List<PluginStore.Installed> installed = store.installed().collectList().block(DB_TIMEOUT);
                        var previous = installed.stream().filter(p -> p.id().equals(plugin.id())).findFirst();
                        if (previous.isEmpty() && installed.size() >= 100) throw PluginArchive.invalid("最多安装 100 个插件");
                        if (previous.isPresent()) stop(plugin.id(), actor);
                        Path target = artifact(plugin);
                        Files.createDirectories(target.getParent());
                        Path staged = Files.createTempFile(target.getParent(), ".install-", ".tmp");
                        try {
                            Files.copy(path, staged, StandardCopyOption.REPLACE_EXISTING);
                            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(staged); }
                        checkExecution();
                        store.save(plugin).block(DB_TIMEOUT);
                        if (previous.isPresent() && !previous.get().sha256().equals(plugin.sha256())) Files.deleteIfExists(artifact(previous.get()));
                        states.put(id, "STOPPED");
                        errors.remove(id);
                        audit(id, actor, "INSTALL", "SUCCEEDED", "version=" + plugin.version() + " sha256=" + plugin.sha256());
                        return true;
                    } catch (Exception | LinkageError | AssertionError failure) {
                        fail(id, actor, "INSTALL", failure);
                        throw publicFailure(failure);
                    }
                })).then();
            }, path -> Mono.fromRunnable(() -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            }).subscribeOn(Schedulers.boundedElastic()).then()
        )).doFinally(signal -> file.delete().subscribe(ignored -> {}, ignored -> {}));
    }

    public Mono<Void> action(String id, String action, String actor) {
        if (!List.of("start", "stop", "reload", "remove").contains(action)) return Mono.error(PluginArchive.invalid("未知插件操作"));
        return operation(id, actor, action.toUpperCase(java.util.Locale.ROOT), () -> {
            requireMode().block(DB_TIMEOUT);
            var plugin = find(id);
            audit(id, actor, action.toUpperCase(java.util.Locale.ROOT), "REQUESTED", "lifecycle operation");
            try {
                switch (action) {
                    case "start" -> start(plugin, actor);
                    case "stop" -> stop(id, actor);
                    case "reload" -> { requireReloadable(id); stop(id, actor); start(find(id), actor); }
                    case "remove" -> {
                        stop(id, actor);
                        store.remove(id).block(DB_TIMEOUT);
                        Files.deleteIfExists(artifact(plugin));
                        states.remove(id);
                        errors.remove(id);
                        audit(id, actor, "REMOVE", "SUCCEEDED", "plugin data retained");
                    }
                    default -> throw PluginArchive.invalid("未知插件操作");
                }
                return true;
            } catch (Exception | LinkageError | AssertionError failure) {
                fail(id, actor, action.toUpperCase(java.util.Locale.ROOT), failure);
                throw publicFailure(failure);
            }
        }).then();
    }

    private void start(PluginStore.Installed plugin, String actor) throws Exception {
        requireReloadable(plugin.id());
        checkExecution();
        requireMode().block(DB_TIMEOUT);
        if ("STARTED".equals(states.get(plugin.id()))) return;
        Path path = artifact(plugin);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !plugin.sha256().equals(PluginArchive.digest(path))) {
            throw PluginArchive.invalid("插件文件缺失或 SHA-256 已改变，请重新上传");
        }
        store.autoStart(plugin.id(), false).block(DB_TIMEOUT);
        states.put(plugin.id(), "STARTING");
        boolean startInvoked = false;
        boolean constructionAttempted = false;
        try {
            if (manager.getPlugin(plugin.id()) != null) throw new IllegalStateException("Previous plugin load was not released");
            track(plugin.id(), "LOAD");
            checkExecution();
            String loaded = manager.loadPlugin(path);
            if (!plugin.id().equals(loaded)) throw PluginArchive.invalid("插件身份与安装记录不一致");
            constructionAttempted = true;
            var implementation = manager.getPlugin(plugin.id()).getPlugin();
            if (!(implementation instanceof StarryPlugin starry)) throw PluginArchive.invalid("Plugin-Class 必须继承 StarryPlugin");
            Path data = root.resolve("data").resolve(plugin.id());
            Files.createDirectories(data);
            var context = new PluginHostContext(plugin, host, store, logs, data, base, json);
            contexts.put(plugin.id(), context);
            starry.initialize(context);
            checkExecution();
            requireMode().block(DB_TIMEOUT);
            track(plugin.id(), "START");
            checkExecution();
            startInvoked = true;
            if (manager.startPlugin(plugin.id()) != PluginState.STARTED) throw PluginArchive.invalid("插件未能启动");
            checkExecution();
            requireReloadable(plugin.id());
            requireMode().block(DB_TIMEOUT);
            store.autoStart(plugin.id(), true).block(DB_TIMEOUT);
            checkExecution();
            var current = execution.get();
            synchronized (current) {
                checkExecution();
                states.put(plugin.id(), "STARTED");
            }
            errors.remove(plugin.id());
            audit(plugin.id(), actor, "START", "SUCCEEDED", "version=" + plugin.version() + " sha256=" + plugin.sha256());
            safePoint();
        } catch (Exception | LinkageError | AssertionError failure) {
            if (constructionAttempted && !contexts.containsKey(plugin.id())) {
                restart(plugin.id(), actor, "START", "INITIALIZATION_FAILED", "插件构造或初始化失败，无法确认其自行创建的资源已释放");
            }
            tearDown(plugin.id(), actor, startInvoked);
            auditLater(store.autoStart(plugin.id(), false));
            states.put(plugin.id(), "FAILED");
            errors.put(plugin.id(), failure.getClass().getSimpleName());
            throw publicFailure(failure);
        }
    }

    private void stop(String id, String actor) throws Exception {
        store.autoStart(id, false).block(DB_TIMEOUT);
        checkExecution();
        states.put(id, "STOPPING");
        if (!tearDown(id, actor, false)) throw restartError();
        checkExecution();
        states.put(id, "STOPPED");
        errors.remove(id);
        audit(id, actor, "STOP", "SUCCEEDED", "managed resources drained; plugin classloader unloaded");
        safePoint();
    }

    private boolean tearDown(String id, String actor, boolean partialStart) {
        var context = contexts.get(id);
        var wrapper = manager.getPlugin(id);
        boolean clean = true;
        if (wrapper != null || context != null) track(id, "STOP");
        if (context != null) context.stopTasks();
        try {
            if (wrapper != null) {
                if (partialStart && wrapper.getPluginState() != PluginState.STARTED) {
                    wrapper.getPlugin().stop();
                    wrapper.setPluginState(PluginState.STOPPED);
                } else {
                    PluginState stopped = manager.stopPlugin(id);
                    if (stopped == PluginState.FAILED || stopped == PluginState.STARTED) throw new IllegalStateException("Plugin stop failed");
                }
            }
        } catch (Exception | LinkageError | AssertionError failure) {
            clean = false;
            restart(id, actor, "STOP", "STOP_FAILED", "停止回调失败（" + failure.getClass().getSimpleName() + "）");
            logs.failure(id, "STOP_FAILED", failure);
        }
        if (context != null) track(id, "CLEANUP");
        try {
            if (context != null) {
                var report = context.cleanup(cleanupGrace);
                if (report.resourceFailures() > 0) restart(id, actor, "CLEANUP", "RESOURCE_CLOSE_FAILED", "托管资源关闭失败：" + report.resourceFailures());
                if (report.pendingResources() > 0) restart(id, actor, "CLEANUP", "RESOURCES_STILL_CLOSING", "仍有资源关闭回调未结束：" + report.pendingResources());
                if (!report.tasksTerminated()) restart(id, actor, "CLEANUP", "TASKS_STILL_RUNNING", "托管任务在清理等待期内未退出");
                if (report.pendingRequests() > 0) restart(id, actor, "CLEANUP", "REQUESTS_STILL_ACTIVE", "仍有 SDK 请求订阅未结束：" + report.pendingRequests());
                clean &= report.complete();
            }
        } catch (Exception | LinkageError | AssertionError failure) {
            clean = false;
            restart(id, actor, "CLEANUP", "CLEANUP_FAILED", "清理未完成（" + failure.getClass().getSimpleName() + "）");
            logs.failure(id, "CLEANUP_FAILED", failure);
        } finally {
            if (context != null) context.deactivate();
        }
        if (clean && wrapper != null) {
            track(id, "UNLOAD");
            try {
                if (!manager.unloadPlugin(id)) throw new IllegalStateException("Plugin unload failed");
            } catch (Exception | LinkageError | AssertionError failure) {
                clean = false;
                restart(id, actor, "UNLOAD", "UNLOAD_FAILED", "类加载器卸载失败（" + failure.getClass().getSimpleName() + "）");
                logs.failure(id, "UNLOAD_FAILED", failure);
            }
        }
        if (clean && context != null) contexts.remove(id, context);
        if (clean) safePoint();
        return clean;
    }

    public Mono<Void> stopAll(String actor) {
        return Mono.defer(() -> {
            contexts.values().forEach(PluginHostContext::deactivate);
            return operation("", actor, "DISABLE", () -> {
                for (var p : List.copyOf(manager.getPlugins())) {
                    try { stop(p.getPluginId(), actor); }
                    catch (Exception | LinkageError | AssertionError error) { fail(p.getPluginId(), actor, "STOP", error); }
                }
                return true;
            }).then();
        });
    }

    public Mono<String> configuration(String id) {
        return store.installed().filter(p -> p.id().equals(id)).next().map(PluginStore.Installed::config)
            .switchIfEmpty(Mono.error(PluginArchive.invalid("插件不存在")));
    }

    public Mono<Void> configure(String id, String value, String actor) {
        return operation(id, actor, "CONFIGURE", () -> {
            requireMode().block(DB_TIMEOUT);
            find(id);
            validateJson(value, true);
            store.config(id, value).block(DB_TIMEOUT);
            audit(id, actor, "CONFIGURE", "SUCCEEDED", "configuration updated; reload to apply; values omitted");
            return true;
        }).then();
    }

    public Mono<PluginDtos.DebugResult> debug(String id, PluginDtos.Debug input, String actor) {
        return operation(id, actor, "DEBUG", () -> {
            requireMode().block(DB_TIMEOUT);
            find(id);
            requireReloadable(id);
            if (!"STARTED".equals(states.get(id))) throw PluginArchive.invalid("请先启动插件");
            validateJson(input.json(), false);
            audit(id, actor, "DEBUG", "REQUESTED", "command=" + input.command());
            long start = System.nanoTime();
            try {
                track(id, "DEBUG");
                checkExecution();
                String result = ((StarryPlugin) manager.getPlugin(id).getPlugin()).debug(input.command(), input.json());
                checkExecution();
                if (result == null || result.length() > 65536) throw PluginArchive.invalid("调试结果必须为 JSON，最大 64 KiB");
                validateJson(result, false);
                long duration = (System.nanoTime() - start) / 1_000_000;
                audit(id, actor, "DEBUG", "SUCCEEDED", "command=" + input.command() + " duration_ms=" + duration);
                safePoint();
                return new PluginDtos.DebugResult(result, duration);
            } catch (Exception | LinkageError | AssertionError failure) {
                reportFailure(id, actor, "DEBUG", failure);
                throw publicFailure(failure);
            }
        });
    }

    private PluginStore.Installed find(String id) {
        return store.installed().filter(p -> p.id().equals(id)).next()
            .switchIfEmpty(Mono.error(PluginArchive.invalid("插件不存在"))).block(DB_TIMEOUT);
    }

    private void validateJson(String value, boolean object) {
        try {
            var parsed = value == null || value.length() > 65536
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536 ? null
                : json.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (parsed == null || (object && !parsed.isObject())) throw PluginArchive.invalid("需要有效的 JSON，配置必须为对象，最大 64 KiB");
        } catch (tools.jackson.core.JacksonException failure) {
            throw PluginArchive.invalid("JSON 格式无效");
        }
    }

    private Path artifact(PluginStore.Installed plugin) {
        if (!plugin.id().matches("[a-z][a-z0-9-]{1,79}") || !plugin.sha256().matches("[a-f0-9]{64}")) {
            throw PluginArchive.invalid("插件安装记录无效");
        }
        return root.resolve("packages").resolve(plugin.id() + "-" + plugin.sha256() + ".jar");
    }

    private <T> Mono<T> operation(String id, String actor, String name, Callable<T> action) {
        return Mono.defer(() -> {
            var current = new Execution(id, actor, name);
            var work = Mono.fromCallable(() -> {
                synchronized (current) {
                    if (current.cancelled) throw timeoutError();
                    current.started = true;
                }
                execution.set(current);
                try { return action.call(); }
                finally { current.finished = true; execution.remove(); }
            }).subscribeOn(operations);
            // Mark cancellation before the timeout operator interrupts the worker.
            return work.timeout(Mono.delay(operationTimeout).doOnNext(ignored -> expire(current, "OPERATION_TIMEOUT")),
                    Mono.defer(() -> Mono.error(timeoutError())))
                .doOnCancel(() -> expire(current, "OPERATION_CANCELLED"));
        });
    }

    private void expire(Execution current, String code) {
        boolean unsafe;
        String id;
        String phase;
        boolean started;
        synchronized (current) {
            if (current.finished || current.cancelled) return;
            current.cancelled = true;
            unsafe = current.started && current.touched;
            id = current.id;
            phase = current.phase;
            started = current.started;
        }
        String detail = "phase=" + phase + " started=" + started;
        logs.add(id, PluginContext.Level.WARN, code + " " + detail);
        auditLater(store.audit(id, current.actor, current.name, code, detail));
        if (unsafe) {
            restart(id, current.actor, phase, code,
                "插件操作" + (code.equals("OPERATION_TIMEOUT") ? "超时" : "被取消") + "，无法确认已执行代码完全停止");
            var context = contexts.get(id);
            if (context != null) context.deactivate();
            auditLater(store.autoStart(id, false));
        }
    }

    private void track(String id, String phase) {
        var current = execution.get();
        if (current != null) {
            synchronized (current) { current.id = id; current.phase = phase; current.touched = true; }
        }
    }

    private void safePoint() {
        var current = execution.get();
        if (current != null) {
            synchronized (current) { current.touched = false; }
        }
    }

    private void checkExecution() {
        var current = execution.get();
        if (current != null && current.cancelled) throw timeoutError();
    }

    private void requireReloadable(String id) { if (blocked(id)) throw restartError(); }

    private static ApiException restartError() {
        return PluginSecurityService.error(HttpStatus.CONFLICT, "PLUGIN_RESTART_REQUIRED", "插件清理状态异常，已阻止重新加载；请检查重启原因和运行日志");
    }

    private static ApiException timeoutError() {
        return PluginSecurityService.error(HttpStatus.SERVICE_UNAVAILABLE, "PLUGIN_OPERATION_TIMEOUT",
            "插件操作超时或已取消；请刷新状态查看是否需要重启，排队中的操作不会继续执行");
    }

    private void restart(String id, String actor, String operation, String code, String detail) {
        var reason = new PluginDtos.RestartReason(id, operation, code, detail, Instant.now());
        if (restartReasons.putIfAbsent(id + ":" + code, reason) == null) {
            logs.add(id, PluginContext.Level.ERROR, "RESTART_REQUIRED " + operation + " " + code + " " + detail);
            auditLater(store.audit(id, actor, "RESTART_REQUIRED", code, detail));
        }
        states.put(id, "FAILED");
    }

    private void auditLater(Mono<Void> write) {
        write.timeout(DB_TIMEOUT).subscribe(ignored -> {}, failure -> logs.add("", PluginContext.Level.ERROR,
            "PLUGIN_STATE_WRITE_FAILED " + failure.getClass().getSimpleName()));
    }

    private static final class Execution {
        private volatile String id;
        private final String actor;
        private final String name;
        private volatile String phase;
        private volatile boolean started;
        private volatile boolean finished;
        private volatile boolean cancelled;
        private volatile boolean touched;

        private Execution(String id, String actor, String name) {
            this.id = id; this.actor = actor; this.name = name; this.phase = name;
        }
    }

    private void fail(String id, String actor, String action, Throwable failure) {
        Throwable origin = failure instanceof ApiException && failure.getCause() != null ? failure.getCause() : failure;
        if (!id.isEmpty()) { states.put(id, "FAILED"); errors.put(id, origin.getClass().getSimpleName()); }
        reportFailure(id, actor, action, origin);
    }

    private void reportFailure(String id, String actor, String action, Throwable origin) {
        audit(id, actor, action, "FAILED", origin.getClass().getSimpleName());
        for (var frame : java.util.Arrays.stream(origin.getStackTrace()).limit(12).toList()) {
            logs.add(id, PluginContext.Level.ERROR, "at " + frame);
        }
    }

    private void audit(String id, String actor, String action, String outcome, String detail) {
        logs.add(id, outcome.equals("FAILED") ? PluginContext.Level.ERROR : PluginContext.Level.INFO,
            action + " " + outcome + " " + detail);
        store.audit(id, actor, action, outcome, detail).block(DB_TIMEOUT);
    }

    private RuntimeException publicFailure(Throwable failure) {
        if (failure instanceof ApiException api) return api;
        var error = PluginSecurityService.error(HttpStatus.BAD_REQUEST, "PLUGIN_OPERATION_FAILED",
            "插件操作失败（" + failure.getClass().getSimpleName() + "），请检查插件兼容性与运行日志");
        error.initCause(failure);
        return error;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void pruneAudit() {
        maintenance.whenOperational(store::prune).subscribe(ignored -> {}, failure -> logs.add("",
            PluginContext.Level.WARN, "AUDIT_PRUNE_FAILED " + failure.getClass().getSimpleName()));
    }

    @PreDestroy
    public void shutdown() {
        contexts.values().forEach(PluginHostContext::deactivate);
        try {
            Mono.fromRunnable(() -> {
                for (var p : List.copyOf(manager.getPlugins())) tearDown(p.getPluginId(), "system-shutdown", false);
            }).subscribeOn(operations).timeout(Duration.ofSeconds(5)).block();
        } catch (Exception failure) {
            logs.add("", PluginContext.Level.WARN, "SHUTDOWN_CLEANUP_INCOMPLETE " + failure.getClass().getSimpleName());
        }
        operations.dispose();
    }
}
