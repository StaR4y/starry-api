package tech.starryapi.plugin.sdk;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/** Implement start/stop and release every owned subscription, thread and connection in stop. */
public abstract class StarryPlugin extends Plugin {
    private PluginContext context;

    protected StarryPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    public final void initialize(PluginContext context) {
        if (this.context != null) throw new IllegalStateException("Plugin is already initialized");
        this.context = context;
    }

    protected final PluginContext context() {
        if (context == null) throw new IllegalStateException("Plugin is not initialized");
        return context;
    }

    /** Return JSON. Inputs and outputs are deliberately excluded from lifecycle audit logs. */
    public String debug(String command, String json) throws Exception {
        throw new UnsupportedOperationException("This plugin does not implement a debug command");
    }
}
