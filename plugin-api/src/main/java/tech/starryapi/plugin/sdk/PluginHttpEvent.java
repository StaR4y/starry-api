package tech.starryapi.plugin.sdk;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public sealed interface PluginHttpEvent {
    record Head(int status, Map<String, List<String>> headers) implements PluginHttpEvent {
        public Head {
            headers = headers.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        }
    }

    record Data(byte[] bytes) implements PluginHttpEvent {
        public Data { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
