package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemMaintenanceTaskGate;
import tech.starryapi.common.ApiException;

@Timeout(20)
class PluginRuntimeTest {
    @TempDir Path directory;
    PluginStore store;
    PluginRuntime runtime;
    PluginLogs logs;
    Map<String, PluginStore.Installed> installed;
    AtomicBoolean enabled;

    @BeforeEach void setup() {
        System.clearProperty("starry.plugin.fixture.loaded");
        System.clearProperty("starry.plugin.fixture.closed");
        System.clearProperty("starry.plugin.fixture.taskDisposed");
        store = mock(PluginStore.class); logs = new PluginLogs();
        installed = new ConcurrentHashMap<>(); enabled = new AtomicBoolean(true);
        when(store.security()).thenAnswer(i -> Mono.fromSupplier(() -> new PluginStore.SecurityState(
            enabled.get(), null, null, null, null, null, null, null, false, 0, null)));
        when(store.installed()).thenAnswer(i -> Flux.defer(() -> Flux.fromIterable(installed.values())));
        when(store.save(any())).thenAnswer(i -> Mono.fromRunnable(() -> {
            PluginStore.Installed p = i.getArgument(0);
            installed.compute(p.id(), (id, old) -> old == null ? p : new PluginStore.Installed(id, p.version(),
                p.provider(), p.description(), p.sha256(), p.size(), false, old.config(), p.installedAt()));
        }));
        when(store.autoStart(anyString(), anyBoolean())).thenAnswer(i -> Mono.fromRunnable(() -> installed.computeIfPresent(
            i.getArgument(0), (id, p) -> new PluginStore.Installed(id, p.version(), p.provider(), p.description(),
                p.sha256(), p.size(), i.getArgument(1), p.config(), p.installedAt()))));
        when(store.config(anyString(), anyString())).thenAnswer(i -> Mono.fromRunnable(() -> installed.computeIfPresent(
            i.getArgument(0), (id, p) -> new PluginStore.Installed(id, p.version(), p.provider(), p.description(),
                p.sha256(), p.size(), p.autoStart(), i.getArgument(1), p.installedAt()))));
        when(store.remove(anyString())).thenAnswer(i -> Mono.fromRunnable(() -> installed.remove(i.getArgument(0))));
        when(store.audit(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        runtime = new PluginRuntime(store, logs, mock(ApplicationContext.class), new ObjectMapper(),
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(), directory.toString(), false);
    }

    @AfterEach void cleanup() {
        var release = (CountDownLatch) System.getProperties().get("starry.plugin.fixture.release");
        if (release != null) release.countDown();
        runtime.shutdown();
        System.clearProperty("starry.plugin.fixture.loaded");
        System.clearProperty("starry.plugin.fixture.closed");
        System.clearProperty("starry.plugin.fixture.taskDisposed");
        for (var name : new String[] {"entered", "release", "exited", "recovered"}) {
            System.getProperties().remove("starry.plugin.fixture." + name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "reload", "remove", "disable"})
    void lifecycleCleansManagedResourcesAndCancelsTasks(String action) throws Exception {
        runtime.install(upload(), "root").block();
        runtime.configure("fixture-plugin", "{\"managed\":true}", "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        if (action.equals("disable")) {
            enabled.set(false);
            runtime.stopAll("root").block();
        } else runtime.action("fixture-plugin", action, "root").block();
        assertThat(System.getProperty("starry.plugin.fixture.closed")).isEqualTo("1");
        assertThat(System.getProperty("starry.plugin.fixture.taskDisposed")).isEqualTo("true");
        assertThat(runtime.restartRequired()).isFalse();
        assertThat(runtime.restartReasons()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fail", "stopFail"})
    void callbackFailuresStillCleanManagedResources(String failure) throws Exception {
        runtime.install(upload(), "root").block();
        runtime.configure("fixture-plugin", "{\"managed\":true,\"" + failure + "\":true}", "root").block();
        if (failure.equals("fail")) {
            assertThatThrownBy(() -> runtime.action("fixture-plugin", "start", "root").block()).isInstanceOf(ApiException.class);
        } else {
            runtime.action("fixture-plugin", "start", "root").block();
            assertThatThrownBy(() -> runtime.action("fixture-plugin", "stop", "root").block()).isInstanceOf(ApiException.class);
        }
        assertThat(System.getProperty("starry.plugin.fixture.closed")).isEqualTo("1");
        assertThat(System.getProperty("starry.plugin.fixture.taskDisposed")).isEqualTo("true");
        assertThat(runtime.list().block().getFirst().autoStart()).isFalse();
    }

    @Test void installDoesNotExecuteAndLifecycleConfigDebugReloadRemoveWork() throws Exception {
        runtime.install(upload(), "root").block();
        assertThat(System.getProperty("starry.plugin.fixture.loaded")).isNull();
        assertThat(runtime.list().block().getFirst().state()).isEqualTo("STOPPED");
        runtime.action("fixture-plugin", "start", "root").block();
        assertThat(System.getProperty("starry.plugin.fixture.loaded")).isEqualTo("true");
        assertThat(runtime.list().block().getFirst().state()).isEqualTo("STARTED");
        runtime.configure("fixture-plugin", "{\"value\":7}", "root").block();
        assertThat(runtime.debug("fixture-plugin", new PluginDtos.Debug("health", "{}"), "root").block().json()).isEqualTo("{}");
        runtime.action("fixture-plugin", "reload", "root").block();
        assertThat(runtime.debug("fixture-plugin", new PluginDtos.Debug("health", "{}"), "root").block().json()).contains("7");
        runtime.action("fixture-plugin", "stop", "root").block();
        assertThat(runtime.list().block().getFirst().autoStart()).isFalse();
        runtime.action("fixture-plugin", "remove", "root").block();
        assertThat(runtime.list().block()).isEmpty();
        assertThat(runtime.restartRequired()).isFalse();
        assertThat(runtime.restartReasons()).isEmpty();
    }

    @Test void reloadAndReplacementUseFreshClassLoadersWithoutRestart() throws Exception {
        runtime.install(upload(), "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        String first = generation();
        runtime.configure("fixture-plugin", "{\"value\":9}", "root").block();
        runtime.action("fixture-plugin", "reload", "root").block();
        String second = generation();
        assertThat(second).isNotEqualTo(first);
        runtime.install(upload("fixture-plugin", "2.0.0"), "root").block();
        assertThat(runtime.list().block().getFirst().state()).isEqualTo("STOPPED");
        assertThat(runtime.list().block().getFirst().version()).isEqualTo("2.0.0");
        runtime.action("fixture-plugin", "start", "root").block();
        assertThat(generation()).isNotIn(first, second);
        assertThat(runtime.configuration("fixture-plugin").block()).contains("9");
        assertThat(runtime.restartReasons()).isEmpty();
    }

    @Test void cleanPartialStartFailureCanBeReconfiguredAndStartedWithoutRestart() throws Exception {
        runtime.install(upload(), "root").block();
        runtime.configure("fixture-plugin", "{\"managed\":true,\"fail\":true}", "root").block();
        assertThatThrownBy(() -> runtime.action("fixture-plugin", "start", "root").block()).isInstanceOf(ApiException.class);
        assertThat(runtime.restartRequired()).isFalse();
        runtime.configure("fixture-plugin", "{}", "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        assertThat(runtime.list().block().getFirst().state()).isEqualTo("STARTED");
        assertThat(runtime.restartReasons()).isEmpty();
    }

    @Test void failedStopBlocksStartReloadDebugAndReplacementEvenAfterCleanupRetry() throws Exception {
        runtime.install(upload(), "root").block();
        runtime.configure("fixture-plugin", "{\"stopFail\":true}", "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        assertThat(runtime.widgets()).extracting(PluginDtos.Widget::pluginId).containsExactly("fixture-plugin");
        assertThat(runtime.settingPages()).extracting(PluginDtos.SettingPage::title).containsExactly("Fixture settings");
        assertThat(runtime.widgetAsset("fixture-plugin", "fixture.png")).isNotNull();
        assertThatThrownBy(() -> runtime.action("fixture-plugin", "stop", "root").block()).isInstanceOf(ApiException.class);
        assertThat(runtime.restartReasons()).extracting(PluginDtos.RestartReason::code).containsExactly("STOP_FAILED");
        assertThat(runtime.list().block().getFirst().state()).isEqualTo("RESTART_REQUIRED");
        assertThat(runtime.widgetAsset("fixture-plugin", "fixture.png")).isNull();
        assertThat(runtime.widgets()).isEmpty();
        assertThat(runtime.settingPages()).isEmpty();
        for (String action : new String[] {"start", "reload"}) {
            assertThatThrownBy(() -> runtime.action("fixture-plugin", action, "root").block())
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PLUGIN_RESTART_REQUIRED");
        }
        assertThatThrownBy(() -> runtime.debug("fixture-plugin", new PluginDtos.Debug("health", "{}"), "root").block())
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PLUGIN_RESTART_REQUIRED");
        assertThatThrownBy(() -> runtime.install(upload(), "root").block()).isInstanceOf(ApiException.class);
        System.setProperty("starry.plugin.fixture.recovered", "true");
        runtime.action("fixture-plugin", "stop", "root").block();
        assertThat(runtime.restartRequired()).isTrue();
        assertThat(runtime.list().block().getFirst().autoStart()).isFalse();
    }

    @Test void failedResourceCloseRecordsReasonAndPreventsReload() throws Exception {
        runtime.install(upload(), "root").block();
        runtime.configure("fixture-plugin", "{\"closeFail\":true}", "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        assertThatThrownBy(() -> runtime.action("fixture-plugin", "reload", "root").block()).isInstanceOf(ApiException.class);
        assertThat(runtime.restartReasons()).extracting(PluginDtos.RestartReason::code).contains("RESOURCE_CLOSE_FAILED");
        assertThat(runtime.list().block().getFirst().autoStart()).isFalse();
    }

    @Test void cancelledFutureDoesNotHideStillRunningTask() throws Exception {
        fastRuntime();
        var entered = gate("entered"); var release = gate("release"); var exited = gate("exited");
        runtime.install(upload(), "root").block();
        runtime.configure("fixture-plugin", "{\"stubbornTask\":true}", "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> runtime.action("fixture-plugin", "stop", "root").block()).isInstanceOf(ApiException.class);
            assertThat(runtime.restartReasons()).extracting(PluginDtos.RestartReason::code).contains("TASKS_STILL_RUNNING");
        } finally { release.countDown(); assertThat(exited.await(3, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutOrCancellationCannotPublishLateStartAndQueuedTimeoutDoesNotRun(boolean cancel) throws Exception {
        fastRuntime();
        var entered = gate("entered"); var release = gate("release");
        runtime.install(upload(), "root").block();
        runtime.install(upload("other-plugin", "1.0.0"), "root").block();
        runtime.configure("fixture-plugin", "{\"blockStart\":true}", "root").block();
        var started = runtime.action("fixture-plugin", "start", "root").toFuture();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        try {
            if (cancel) started.cancel(true);
            else assertThatThrownBy(() -> started.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
            assertThatThrownBy(() -> runtime.action("other-plugin", "start", "root").block()).isInstanceOf(ApiException.class);
            assertThat(runtime.restartReasons()).allMatch(reason -> reason.pluginId().equals("fixture-plugin"))
                .extracting(PluginDtos.RestartReason::code).contains(cancel ? "OPERATION_CANCELLED" : "OPERATION_TIMEOUT");
            assertThat(installed.get("fixture-plugin").autoStart()).isFalse();
        } finally { release.countDown(); }
        // A queued host operation is a barrier for the interrupted callback and its teardown.
        runtime.configure("fixture-plugin", "{}", "root").block();
        assertThat(runtime.list().block()).allMatch(plugin -> !plugin.autoStart() && !plugin.state().equals("STARTED"));
        verify(store, never()).autoStart("fixture-plugin", true);
        assertThat(runtime.list().block()).filteredOn(p -> p.id().equals("other-plugin"))
            .extracting(PluginDtos.PluginInfo::state).containsExactly("STOPPED");
        verify(store).audit(eq("other-plugin"), eq("root"), eq("START"), eq("OPERATION_TIMEOUT"), contains("started=false"));
    }

    private String generation() {
        return runtime.debug("fixture-plugin", new PluginDtos.Debug("generation", "{}"), "root").block().json();
    }

    private CountDownLatch gate(String name) {
        var gate = new CountDownLatch(1);
        System.getProperties().put("starry.plugin.fixture." + name, gate);
        return gate;
    }

    private void fastRuntime() {
        runtime.shutdown();
        runtime = new PluginRuntime(store, logs, mock(ApplicationContext.class), new ObjectMapper(),
            SystemMaintenanceTaskGate.alwaysOperationalForDirectConstruction(), directory.toString(), false,
            Duration.ofSeconds(1), Duration.ofMillis(100));
    }

    @Test void disabledModeRejectsInstallationAndExecution() throws Exception {
        enabled.set(false);
        assertThatThrownBy(() -> runtime.install(upload(), "root").block()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> runtime.action("fixture-plugin", "start", "root").block()).isInstanceOf(ApiException.class);
        assertThat(System.getProperty("starry.plugin.fixture.loaded")).isNull();
    }

    @Test void changedArtifactIsRejectedBeforeLoadingClass() throws Exception {
        runtime.install(upload(), "root").block();
        var p = installed.get("fixture-plugin");
        Files.writeString(directory.resolve("packages").resolve(p.id() + "-" + p.sha256() + ".jar"), "changed");
        assertThatThrownBy(() -> runtime.action(p.id(), "start", "root").block()).isInstanceOf(ApiException.class);
        assertThat(System.getProperty("starry.plugin.fixture.loaded")).isNull();
    }

    @Test void pluginFailureDisablesAutomaticRestartAndIsAudited() throws Exception {
        runtime.install(upload(), "root").block();
        runtime.configure("fixture-plugin", "{\"fail\":true}", "root").block();
        assertThatThrownBy(() -> runtime.action("fixture-plugin", "start", "root").block()).isInstanceOf(ApiException.class);
        assertThat(runtime.list().block().getFirst().state()).isEqualTo("FAILED");
        assertThat(runtime.list().block().getFirst().autoStart()).isFalse();
        verify(store).audit(eq("fixture-plugin"), eq("root"), eq("START"), eq("FAILED"), anyString());
    }

    @Test void disableStopsPluginEvenWhenItsStopCallbackLogs() throws Exception {
        runtime.install(upload(), "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        enabled.set(false);
        runtime.stopAll("root").block();
        assertThat(runtime.list().block().getFirst().state()).isEqualTo("STOPPED");
        assertThat(logs.list("fixture-plugin", 0)).anyMatch(e -> e.message().contains("fixture stop"));
    }

    @Test void stopAllHasNoSideEffectsBeforeAuthorizationSubscribes() throws Exception {
        runtime.install(upload(), "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        runtime.stopAll("not-authorized");
        assertThat(runtime.debug("fixture-plugin", new PluginDtos.Debug("health", "{}"), "root").block().json()).isEqualTo("{}");
        assertThat(runtime.restartRequired()).isFalse();
    }

    @Test void invalidConfigurationNeverOverwritesExistingValue() throws Exception {
        runtime.install(upload(), "root").block();
        assertThatThrownBy(() -> runtime.configure("fixture-plugin", "[]", "root").block()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> runtime.configure("fixture-plugin", "{} {}", "root").block()).isInstanceOf(ApiException.class);
        String oversized = "{\"value\":\"" + "\u4e2d".repeat(22000) + "\"}";
        assertThatThrownBy(() -> runtime.configure("fixture-plugin", oversized, "root").block()).isInstanceOf(ApiException.class);
        assertThat(runtime.configuration("fixture-plugin").block()).isEqualTo("{}");
    }

    @Test void debugFailureIncludesStackFramesWithoutInputAndKeepsPluginRunning() throws Exception {
        runtime.install(upload(), "root").block();
        runtime.action("fixture-plugin", "start", "root").block();
        assertThatThrownBy(() -> runtime.debug("fixture-plugin", new PluginDtos.Debug("fail", "{\"secret\":\"private-value\"}"),
            "root").block()).isInstanceOf(ApiException.class);
        assertThat(logs.list("fixture-plugin", 0)).anyMatch(e -> e.message().contains("FixturePlugin.debug"))
            .noneMatch(e -> e.message().contains("private-value"));
        assertThat(runtime.list().block().getFirst().state()).isEqualTo("STARTED");
    }

    private FilePart upload() throws Exception {
        return upload("fixture-plugin", "1.0.0");
    }

    private FilePart upload(String id, String version) throws Exception {
        var manifest = new Manifest(); var a = manifest.getMainAttributes();
        a.put(Attributes.Name.MANIFEST_VERSION, "1.0"); a.putValue("Plugin-Id", id);
        a.putValue("Plugin-Version", version); a.putValue("Plugin-Class", "example.plugin.FixturePlugin");
        a.putValue("Starry-Plugin-Api", "1");
        Path jar = directory.resolve("input.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var in = getClass().getResourceAsStream("/example/plugin/FixturePlugin.class")) {
            out.putNextEntry(new JarEntry("example/plugin/FixturePlugin.class")); in.transferTo(out); out.closeEntry();
            out.putNextEntry(new JarEntry("web/widget.json"));
            out.write(new ObjectMapper().writeValueAsBytes(Map.of("title", "Fixture", "image", "fixture.png"))); out.closeEntry();
            out.putNextEntry(new JarEntry("web/settings.json"));
            out.write(new ObjectMapper().writeValueAsBytes(Map.of(
                "title", "Fixture settings",
                "group", "内容与界面",
                "assistant", true
            ))); out.closeEntry();
            out.putNextEntry(new JarEntry("web/fixture.png"));
            out.write(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aNioAAAAASUVORK5CYII="));
            out.closeEntry();
        }
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn("fixture.jar");
        byte[] bytes = Files.readAllBytes(jar);
        when(file.content()).thenAnswer(i -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)));
        when(file.delete()).thenReturn(Mono.empty());
        return file;
    }
}
