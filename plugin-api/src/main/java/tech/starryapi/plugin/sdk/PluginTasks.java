package tech.starryapi.plugin.sdk;

import java.time.Duration;
import reactor.core.Disposable;

/** Tasks are serialized per plugin, off HTTP event threads, and cancelled at unload. */
public interface PluginTasks {
    Disposable schedule(String name, Duration delay, Runnable task);

    /** Delay is measured from the previous callback's completion; executions never overlap. */
    Disposable repeat(String name, Duration initialDelay, Duration delay, Runnable task);
}
