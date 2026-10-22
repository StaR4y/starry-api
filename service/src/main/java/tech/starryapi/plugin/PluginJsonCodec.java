package tech.starryapi.plugin;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tech.starryapi.plugin.sdk.PluginJson;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.LookupCache;
import tools.jackson.databind.util.SimpleLookupCache;

final class PluginJsonCodec implements PluginJson {
    private final ObjectMapper mapper;
    private final Runnable requireActive;

    PluginJsonCodec(ObjectMapper hostMapper, Runnable requireActive) {
        // Plugin DTO classes must not be retained in the host mapper's shared type cache.
        LookupCache<Object, JavaType> types = new SimpleLookupCache<>(16, 200);
        this.mapper = hostMapper.rebuild().typeFactory(hostMapper.getTypeFactory().withCache(types))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
        this.requireActive = requireActive;
    }

    @Override public <T> T read(String json, Class<T> type) {
        requireActive.run();
        Objects.requireNonNull(type);
        size(json);
        try { return mapper.readValue(json, type); }
        catch (JacksonException failure) { throw new IllegalArgumentException("Invalid JSON for " + type.getName()); }
    }

    @Override public String write(Object value) {
        requireActive.run();
        try {
            String result = mapper.writeValueAsString(value);
            size(result);
            return result;
        } catch (JacksonException failure) { throw new IllegalArgumentException("Unable to encode plugin JSON"); }
    }

    private static void size(String json) {
        if (json == null || json.length() > 65536 || json.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("Plugin JSON must not exceed 64 KiB");
        }
    }
}
