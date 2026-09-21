package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class PluginTaskSchedulerTest {
    @Test void taskRunsOffCallerThreadAndReleasesItsRegistration() throws Exception {
        try (var tasks = new PluginTaskScheduler("test-plugin", new PluginLogs(), () -> true)) {
            var done = new CountDownLatch(1);
            var thread = new AtomicReference<String>();
            tasks.schedule("once", Duration.ZERO, () -> { thread.set(Thread.currentThread().getName()); done.countDown(); });
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("plugin-test-plugin-tasks");
            for (int i = 0; i < 100; i++) {
                var next = new CountDownLatch(1);
                tasks.schedule("once", Duration.ZERO, next::countDown);
                assertThat(next.await(3, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test void recurringTaskSurvivesCallbackFailureWithoutLoggingPrivateMessage() throws Exception {
        var logs = new PluginLogs();
        try (var tasks = new PluginTaskScheduler("test-plugin", logs, () -> true)) {
            var done = new CountDownLatch(2);
            var calls = new AtomicInteger();
            tasks.repeat("repeat", Duration.ZERO, Duration.ofMillis(10), () -> {
                done.countDown();
                if (calls.incrementAndGet() == 1) throw new AssertionError("private-task-value");
            });
            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(logs.list("test-plugin", 0)).anyMatch(e -> e.message().contains("TASK_FAILED repeat"))
                .noneMatch(e -> e.message().contains("private-task-value"));
        }
    }

    @Test void cancellationFreesCapacityAndShutdownRejectsNewTasks() {
        var tasks = new PluginTaskScheduler("test-plugin", new PluginLogs(), () -> true);
        try {
            var first = tasks.schedule("cancel", Duration.ofDays(1), () -> {});
            for (int i = 0; i < 63; i++) tasks.schedule("pending", Duration.ofDays(1), () -> {});
            assertThatThrownBy(() -> tasks.schedule("overflow", Duration.ZERO, () -> {})).isInstanceOf(IllegalStateException.class);
            first.dispose();
            var replacement = tasks.schedule("replacement", Duration.ofDays(1), () -> fail("Cancelled task executed"));
            tasks.close();
            assertThat(replacement.isDisposed()).isTrue();
            assertThatThrownBy(() -> tasks.schedule("closed", Duration.ZERO, () -> {})).isInstanceOf(IllegalStateException.class);
        } finally { tasks.close(); }
    }

    @Test void shutdownInterruptsCooperativeRunningTask() throws Exception {
        var tasks = new PluginTaskScheduler("test-plugin", new PluginLogs(), () -> true);
        try {
            var started = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            tasks.schedule("blocking", Duration.ZERO, () -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            tasks.close();
            assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
        } finally { tasks.close(); }
    }

    @Test void validatesTaskNameDelaysAndRevokedContext() {
        try (var tasks = new PluginTaskScheduler("test-plugin", new PluginLogs(), () -> true)) {
            assertThatThrownBy(() -> tasks.schedule("invalid\nname", Duration.ZERO, () -> {})).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tasks.schedule("negative", Duration.ofSeconds(-1), () -> {})).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tasks.repeat("busy", Duration.ZERO, Duration.ZERO, () -> {})).isInstanceOf(IllegalArgumentException.class);
        }
        try (var revoked = new PluginTaskScheduler("test-plugin", new PluginLogs(), () -> false)) {
            assertThatThrownBy(() -> revoked.schedule("revoked", Duration.ZERO, () -> {})).isInstanceOf(IllegalStateException.class);
        }
    }
}
