package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PluginResourceScopeTest {
    @Test void closesInReverseOrderExactlyOnceIncludingManualDisposal() {
        var scope = new PluginResourceScope("test-plugin", new PluginLogs(), () -> true);
        var calls = new ArrayList<Integer>();
        scope.add(() -> calls.add(1));
        var middle = scope.add(() -> calls.add(2));
        scope.add(() -> calls.add(3));
        middle.dispose(); middle.dispose();
        scope.close(); scope.close();
        assertThat(calls).containsExactly(2, 3, 1);
    }

    @Test void failedCleanupDoesNotSkipRemainingResourcesOrLogSecrets() {
        var logs = new PluginLogs();
        var scope = new PluginResourceScope("test-plugin", logs, () -> true);
        var closed = new AtomicBoolean();
        scope.add(() -> closed.set(true));
        scope.add(() -> { throw new IllegalStateException("private-resource-value"); });
        scope.close();
        assertThat(closed).isTrue();
        assertThat(scope.failures()).isEqualTo(1);
        assertThat(scope.pending()).isZero();
        assertThat(logs.list("test-plugin", 0)).anyMatch(e -> e.message().contains("RESOURCE_CLOSE_FAILED"))
            .noneMatch(e -> e.message().contains("private-resource-value"));
    }

    @Test void resourcesOfferedAfterRevocationAreRejectedAndClosed() {
        var active = new AtomicBoolean(true);
        var closed = new AtomicInteger();
        var scope = new PluginResourceScope("test-plugin", new PluginLogs(), active::get);
        scope.add(closed::incrementAndGet);
        active.set(false);
        assertThatThrownBy(() -> scope.add(closed::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        assertThat(closed).hasValue(1);
        scope.close();
        assertThat(closed).hasValue(2);
    }

    @Test void capacityIsBoundedAndRejectedResourcesAreClosed() {
        var scope = new PluginResourceScope("test-plugin", new PluginLogs(), () -> true);
        var closed = new AtomicInteger();
        for (int i = 0; i < 256; i++) scope.add(closed::incrementAndGet);
        assertThatThrownBy(() -> scope.add(closed::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        scope.close();
        assertThat(closed).hasValue(257);
    }

    @Test void manualDisposalInProgressIsNotReportedAsFullyClosed() throws Exception {
        var scope = new PluginResourceScope("test-plugin", new PluginLogs(), () -> true);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var handle = scope.add(() -> { entered.countDown(); release.await(); });
        var closing = CompletableFuture.runAsync(handle::dispose);
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            scope.close();
            scope.awaitIdle(Duration.ofMillis(20));
            assertThat(handle.isDisposed()).isTrue();
            assertThat(scope.pending()).isEqualTo(1);
        } finally { release.countDown(); closing.get(3, TimeUnit.SECONDS); }
        scope.awaitIdle(Duration.ofSeconds(1));
        assertThat(scope.pending()).isZero();
        assertThat(scope.failures()).isZero();
    }

    @Test void concurrentRegistrationAndShutdownCannotLoseResources() {
        var scope = new PluginResourceScope("test-plugin", new PluginLogs(), () -> true);
        var closed = new AtomicInteger();
        var registrations = IntStream.range(0, 100).mapToObj(i -> CompletableFuture.runAsync(() -> {
            try { scope.add(closed::incrementAndGet); } catch (IllegalStateException expected) { }
        })).toArray(CompletableFuture[]::new);
        scope.close();
        CompletableFuture.allOf(registrations).join();
        assertThat(closed).hasValue(100);
    }
}
