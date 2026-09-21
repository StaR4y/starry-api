package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PluginWebAssetsTest {
    @Test
    void acceptsPlainWebFiles() {
        assertThat(PluginWebAssets.classpathResource("maid.png")).isEqualTo("web/maid.png");
        assertThat(PluginWebAssets.classpathResource("/widget.json")).isEqualTo("web/widget.json");
        assertThat(PluginWebAssets.classpathResource("icons/a-1.png")).isEqualTo("web/icons/a-1.png");
        assertThat(PluginWebAssets.classpathResource("widget.js")).isEqualTo("web/widget.js");
        assertThat(PluginWebAssets.classpathResource("Ya1.mp3")).isEqualTo("web/Ya1.mp3");
    }

    @Test
    void rejectsPathTraversalAndOpaqueNames() {
        assertThat(PluginWebAssets.classpathResource("../secret")).isNull();
        assertThat(PluginWebAssets.classpathResource("web/../widget.json")).isNull();
        assertThat(PluginWebAssets.classpathResource("C:/maid.png")).isNull();
        assertThat(PluginWebAssets.classpathResource("maid.png?x=1")).isNull();
        assertThat(PluginWebAssets.classpathResource("")).isNull();
        assertThat(PluginWebAssets.classpathResource(null)).isNull();
    }
}
