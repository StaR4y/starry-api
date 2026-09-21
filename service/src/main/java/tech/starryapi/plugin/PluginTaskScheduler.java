package tech.starryapi.plugin;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import reactor.core.Disposable;
import tech.starryapi.plugin.sdk.PluginContext;
import tech.starryapi.plugin.sdk.PluginTasks;

final class PluginTaskScheduler implements PluginTasks, AutoCloseable {
    private final String plugin;
    private final PluginLogs logs;
    private final BooleanSupplier active;
    private final Set<Task> tasks = new HashSet<>();
    private ScheduledThreadPoolExecutor executor;
    private boolean closed;

    PluginTaskScheduler(String plugin, PluginLogs logs, BooleanSupplier active) {
        this.plugin = plugin;
        this.logs = logs;
        this.active = active;
    }

    @Override public Disposable schedule(String name, Duration delay, Runnable task) {
        return submit(name, nanos(delay, false), 0, Objects.requireNonNull(task));
    }

    @Override public Disposable repeat(String name, Duration initialDelay, Duration delay, Runnable task) {
        return submit(name, nanos(initialDelay, false), nanos(delay, true), Objects.requireNonNull(task));
    }

    private synchronized Disposable submit(String name, long initial, long delay, Runnable callback) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid task name");
        if (closed || !active.getAsBoolean()) throw new IllegalStateException("Plugin context has been revoked");
        if (tasks.size() >= 64) throw new IllegalStateException("At most 64 tasks per plugin load");
        if (executor == null) {
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                var thread = new Thread(runnable, "plugin-" + plugin + "-tasks");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }
        var task = new Task(name, callback, delay > 0);
        tasks.add(task);
        try {
            task.future = delay == 0 ? executor.schedule(task, initial, TimeUnit.NANOSECONDS)
                : executor.scheduleWithFixedDelay(task, initial, delay, TimeUnit.NANOSECONDS);
            logs.add(plugin, PluginContext.Level.DEBUG, "TASK_SCHEDULED " + name);
            return task;
        } catch (RuntimeException failure) {
            tasks.remove(task);
            throw failure;
        }
    }

    @Override public void close() {
        List<Task> snapshot;
        ScheduledThreadPoolExecutor current;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = List.copyOf(tasks);
            current = executor;
        }
        snapshot.forEach(Task::dispose);
        if (current != null) current.shutdownNow();
    }

    boolean awaitStopped(Duration timeout) throws InterruptedException {
        ScheduledThreadPoolExecutor current;
        synchronized (this) { current = executor; }
        return current == null || current.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static long nanos(Duration value, boolean positive) {
        Objects.requireNonNull(value);
        if (value.isNegative() || (positive && value.isZero())) throw new IllegalArgumentException("Invalid task delay");
        return value.toNanos();
    }

    private final class Task implements Disposable, Runnable {
        private final String name;
        private final Runnable callback;
        private final boolean repeating;
        private final AtomicBoolean disposed = new AtomicBoolean();
        private ScheduledFuture<?> future;

        Task(String name, Runnable callback, boolean repeating) {
            this.name = name;
            this.callback = callback;
            this.repeating = repeating;
        }

        @Override public void run() {
            if (disposed.get() || !active.getAsBoolean()) { dispose(); return; }
            try { callback.run(); }
            catch (Exception | LinkageError | AssertionError failure) { logs.failure(plugin, "TASK_FAILED " + name, failure); }
            finally {
                if (!repeating) {
                    disposed.set(true);
                    synchronized (PluginTaskScheduler.this) { tasks.remove(this); }
                }
            }
        }

        @Override public void dispose() {
            if (!disposed.compareAndSet(false, true)) return;
            synchronized (PluginTaskScheduler.this) {
                tasks.remove(this);
                if (future != null) future.cancel(true);
            }
            logs.add(plugin, PluginContext.Level.DEBUG, "TASK_CANCELLED " + name);
        }

        @Override public boolean isDisposed() { return disposed.get(); }
    }
}
