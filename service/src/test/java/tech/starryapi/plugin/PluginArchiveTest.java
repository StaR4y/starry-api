package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArchiveTest {
    @TempDir Path directory;

    @Test void acceptsMetadataWithoutLoadingAnyPluginClass() throws Exception {
        var p = PluginArchive.inspect(jar("demo-plugin", "1", "example/Test.class"));
        assertThat(p.id()).isEqualTo("demo-plugin");
        assertThat(p.autoStart()).isFalse();
        assertThat(p.sha256()).matches("[a-f0-9]{64}");
    }

    @Test void rejectsWrongApiVersion() throws Exception {
        Path path = jar("demo-plugin", "99", "example/Test.class");
        assertThatThrownBy(() -> PluginArchive.inspect(path)).hasMessageContaining("Starry-Plugin-Api");
    }

    @Test void rejectsPathTraversalId() throws Exception {
        Path path = jar("../bad", "1", "example/Test.class");
        assertThatThrownBy(() -> PluginArchive.inspect(path)).hasMessageContaining("Plugin-Id");
    }

    @Test void rejectsBundledSdk() throws Exception {
        Path path = jar("demo-plugin", "1", "tech/starryapi/plugin/sdk/Test.class");
        assertThatThrownBy(() -> PluginArchive.inspect(path)).hasMessageContaining("compileOnly");
    }

    @Test void rejectsBootArchive() throws Exception {
        Path path = jar("demo-plugin", "1", "BOOT-INF/classes/Test.class");
        assertThatThrownBy(() -> PluginArchive.inspect(path)).hasMessageContaining("布局");
    }

    @Test void rejectsMissingMainClass() throws Exception {
        Path path = jar("demo-plugin", "1", "example/Other.class");
        assertThatThrownBy(() -> PluginArchive.inspect(path)).hasMessageContaining("未包含");
    }

    @Test void rejectsMalformedJar() throws Exception {
        Path path = directory.resolve("broken.jar");
        Files.writeString(path, "not a jar");
        assertThatThrownBy(() -> PluginArchive.inspect(path)).isInstanceOf(Exception.class);
    }

    private Path jar(String id, String api, String entry) throws Exception {
        Manifest manifest = new Manifest();
        var a = manifest.getMainAttributes();
        a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        a.putValue("Plugin-Id", id); a.putValue("Plugin-Version", "1.0.0");
        a.putValue("Plugin-Class", "example.Test"); a.putValue("Starry-Plugin-Api", api);
        Path path = directory.resolve("test.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            out.putNextEntry(new JarEntry(entry)); out.write(new byte[] {1, 2, 3}); out.closeEntry();
        }
        return path;
    }
}
