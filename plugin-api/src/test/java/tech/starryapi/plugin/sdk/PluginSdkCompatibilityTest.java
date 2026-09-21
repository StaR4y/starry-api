package tech.starryapi.plugin.sdk;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PluginSdkCompatibilityTest {
    @Test void originalContextImplementationsDoNotNeedNewAbstractMethods() {
        PluginContext legacy = new PluginContext() {
            public String pluginId() { return "legacy"; }
            public String configJson() { return "{}"; }
            public Path dataDirectory() { return Path.of("."); }
            public <T> T service(Class<T> type) { return null; }
            public Mono<ApiResult> request(String method, String path, Map<String, String> headers, byte[] body) { return Mono.empty(); }
            public void log(Level level, String message) { }
        };
        assertEquals("1.0", legacy.sdkVersion());
        assertTrue(legacy.capabilities().isEmpty());
        assertThrows(UnsupportedOperationException.class, legacy::tasks);
        assertThrows(UnsupportedOperationException.class, () -> legacy.requestStream("GET", "/", Map.of(), null).blockLast());
    }

    @Test void responseHeadersAreDeeplyImmutable() {
        var values = new ArrayList<>(List.of("one"));
        var headers = new HashMap<>(Map.of("X-Test", values));
        var event = new PluginHttpEvent.Head(200, new HashMap<>(headers));
        values.add("two");
        assertEquals(List.of("one"), event.headers().get("X-Test"));
        assertThrows(UnsupportedOperationException.class, () -> event.headers().get("X-Test").add("three"));
    }

    @Test void dataDoesNotExposeMutableBackingBuffer() {
        byte[] bytes = {1, 2};
        var event = new PluginHttpEvent.Data(bytes);
        bytes[0] = 9;
        event.bytes()[1] = 9;
        assertArrayEquals(new byte[] {1, 2}, event.bytes());
    }
}
