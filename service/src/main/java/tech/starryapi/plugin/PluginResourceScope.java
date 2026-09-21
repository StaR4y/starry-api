package tech.starryapi.plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import reactor.core.Disposable;

final class PluginResourceScope implements AutoCloseable {
    private final String plugin;
    private final PluginLogs logs;
    private final BooleanSupplier active;
    private final Set<Resource> resources = new LinkedHashSet<>();
    private boolean closed;
    private int closing;
    private int failures;

    PluginResourceScope(String plugin, PluginLogs logs, BooleanSupplier active) {
        this.plugin = plugin;
        this.logs = logs;
        this.active = active;
    }

    Disposable add(AutoCloseable resource) {
        var handle = new Resource(Objects.requireNonNull(resource));
        synchronized (this) {
            if (!closed && active.getAsBoolean() && resources.size() < 256) {
                resources.add(handle);
                return handle;
            }
        }
        handle.dispose();
        throw new IllegalStateException("Plugin resource scope is closed or has reached its 256-resource limit");
    }

    @Override public void close() {
        ArrayList<Resource> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = new ArrayList<>(resources);
        }
        for (var resource : snapshot.reversed()) resource.dispose();
    }

    synchronized int failures() { return failures; }
    synchronized int pending() { return resources.size() + closing; }

    synchronized void awaitIdle(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (closing > 0 || !resources.isEmpty()) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) return;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    private final class Resource implements Disposable {
        private final AutoCloseable resource;
        private final AtomicBoolean disposed = new AtomicBoolean();

        Resource(AutoCloseable resource) { this.resource = resource; }

        @Override public void dispose() {
            synchronized (PluginResourceScope.this) {
                if (!disposed.compareAndSet(false, true)) return;
                resources.remove(this);
                closing++;
            }
            try { resource.close(); }
            catch (Exception | LinkageError | AssertionError failure) {
                synchronized (PluginResourceScope.this) { failures++; }
                logs.failure(plugin, "RESOURCE_CLOSE_FAILED", failure);
            } finally {
                synchronized (PluginResourceScope.this) { closing--; PluginResourceScope.this.notifyAll(); }
            }
        }

        @Override public boolean isDisposed() { return disposed.get(); }
    }
}
