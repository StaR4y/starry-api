package tech.starryapi.plugin;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.starryapi.plugin.sdk.PluginContext;

@Component
public class PluginLogs {
    private static final Logger LOG = LoggerFactory.getLogger("tech.starryapi.plugins");
    private static final Pattern SECRET = Pattern.compile("(?i)(password|authorization|token|secret|api[_-]?key|code)([\\s\"':=]+)[^\\s,;}]+|(?:adm-|sk-)[A-Za-z0-9_-]{8,}");
    private final ArrayDeque<PluginDtos.Log> entries = new ArrayDeque<>();
    private long sequence;

    public synchronized void add(String plugin, PluginContext.Level level, String message) {
        String text = sanitize(message);
        var entry = new PluginDtos.Log(++sequence, Instant.now(), plugin, level.name(), text);
        entries.addLast(entry);
        while (entries.size() > 2000) entries.removeFirst();
        switch (level) {
            case ERROR -> LOG.error("plugin_id={} event={}", plugin, text);
            case WARN -> LOG.warn("plugin_id={} event={}", plugin, text);
            case DEBUG -> LOG.debug("plugin_id={} event={}", plugin, text);
            default -> LOG.info("plugin_id={} event={}", plugin, text);
        }
    }

    public synchronized List<PluginDtos.Log> list(String plugin, long after) {
        return entries.stream().filter(e -> (plugin.isEmpty() || plugin.equals(e.pluginId())) && e.sequence() > after)
            .skip(Math.max(0, entries.stream().filter(e -> (plugin.isEmpty() || plugin.equals(e.pluginId()))
                && e.sequence() > after).count() - 500)).toList();
    }

    void failure(String plugin, String event, Throwable failure) {
        add(plugin, PluginContext.Level.ERROR, event + " " + failure.getClass().getSimpleName());
        for (var frame : java.util.Arrays.stream(failure.getStackTrace()).limit(12).toList()) {
            add(plugin, PluginContext.Level.ERROR, "at " + frame);
        }
    }

    static String sanitize(String message) {
        String value = message == null ? "" : message.substring(0, Math.min(message.length(), 2000));
        return SECRET.matcher(value.replaceAll("[\\p{Cntrl}]", " ")).replaceAll("[REDACTED]");
    }
}
