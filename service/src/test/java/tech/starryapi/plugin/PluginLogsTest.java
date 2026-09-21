package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tech.starryapi.plugin.sdk.PluginContext;

class PluginLogsTest {
    @Test void redactsCommonSecretsAndControlCharacters() {
        assertThat(PluginLogs.sanitize("password=hidden token:secretvalue adm-123456789012\nhello"))
            .doesNotContain("hidden", "secretvalue", "123456789012", "\n").contains("hello");
    }

    @Test void limitsMessageSize() { assertThat(PluginLogs.sanitize("x".repeat(9999))).hasSize(2000); }

    @Test void supportsPluginFilterAndIncrementalCursor() {
        var logs = new PluginLogs();
        logs.add("one", PluginContext.Level.DEBUG, "first");
        logs.add("two", PluginContext.Level.DEBUG, "second");
        logs.add("one", PluginContext.Level.DEBUG, "third");
        assertThat(logs.list("one", 1)).extracting(PluginDtos.Log::message).containsExactly("third");
    }

    @Test void boundsRetainedEntriesAndResponseSize() {
        var logs = new PluginLogs();
        for (int i = 0; i < 2500; i++) logs.add("one", PluginContext.Level.DEBUG, "entry");
        assertThat(logs.list("", 0)).hasSize(500);
        assertThat(logs.list("", 2499)).hasSize(1);
    }
}
