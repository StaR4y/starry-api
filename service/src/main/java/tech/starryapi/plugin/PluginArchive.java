package tech.starryapi.plugin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.springframework.http.HttpStatus;

final class PluginArchive {
    static final long MAX_BYTES = 32L * 1024 * 1024;

    private PluginArchive() {}

    static PluginStore.Installed inspect(Path path) throws Exception {
        long size = Files.size(path);
        if (size == 0 || size > MAX_BYTES) throw invalid("插件 JAR 必须为 1 字节至 32 MiB");
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.getManifest() == null) throw invalid("缺少插件 MANIFEST.MF");
            Attributes manifest = jar.getManifest().getMainAttributes();
            String id = value(manifest, "Plugin-Id", 80);
            String version = value(manifest, "Plugin-Version", 80);
            String type = value(manifest, "Plugin-Class", 240);
            if (!id.matches("[a-z][a-z0-9-]{1,79}") || !version.matches("[A-Za-z0-9][A-Za-z0-9.+_-]{0,79}")) {
                throw invalid("Plugin-Id 或 Plugin-Version 格式无效");
            }
            if (!type.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")
                || !"1".equals(manifest.getValue("Starry-Plugin-Api"))) {
                throw invalid("需要有效的 Plugin-Class 和 Starry-Plugin-Api: 1");
            }
            if (manifest.getValue("Class-Path") != null || manifest.getValue("Plugin-Dependencies") != null) {
                throw invalid("第一版仅支持自包含 JAR，不支持外部 Class-Path 或跨插件依赖");
            }
            var names = new HashSet<String>();
            var entries = jar.entries();
            long expanded = 0;
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name) || names.size() > 20_000 || name.startsWith("/") || name.contains("\\")
                    || name.contains("../") || name.startsWith("BOOT-INF/")) throw invalid("不支持此 JAR 布局");
                if (name.startsWith("tech/starryapi/") || name.startsWith("org/pf4j/") || name.startsWith("reactor/")) {
                    throw invalid("SDK、宿主 API、PF4J 和 Reactor 必须使用 compileOnly，不能打包到插件");
                }
                if (entry.isDirectory()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    int count;
                    while ((count = in.read(buffer)) >= 0) {
                        expanded += count;
                        if (expanded > 128L * 1024 * 1024) throw invalid("插件解压后体积超过 128 MiB");
                    }
                }
            }
            if (!names.contains(type.replace('.', '/') + ".class")) throw invalid("Plugin-Class 未包含在 JAR 中");
            return new PluginStore.Installed(id, version, value(manifest, "Plugin-Provider", 160),
                value(manifest, "Plugin-Description", 1000), digest(path), size, false, "{}", Instant.now());
        }
    }

    static String digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String value(Attributes manifest, String key, int max) {
        String value = manifest.getValue(key);
        value = value == null ? "" : value.trim();
        if (value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw invalid("插件元数据无效: " + key);
        return value;
    }

    static tech.starryapi.common.ApiException invalid(String message) {
        return new tech.starryapi.common.ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN", message);
    }
}
