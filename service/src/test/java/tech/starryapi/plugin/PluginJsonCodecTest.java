package tech.starryapi.plugin;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PluginJsonCodecTest {
    record Configuration(String title, boolean enabled) {}

    @Test void roundTripsRecordsWithoutMutatingHostMapper() {
        // Jackson 3 ignores unknown properties by default, so make the host strict for the assertion below to mean something
        var host = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        var json = new PluginJsonCodec(host, () -> {});
        var config = json.read("{\"title\":\"test\",\"enabled\":true,\"extra\":1}", Configuration.class);
        assertThat(config).isEqualTo(new Configuration("test", true));
        assertThat(json.read(json.write(config), Configuration.class)).isEqualTo(config);
        assertThat(host.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    @Test void rejectsTrailingJsonAndInvalidTypesWithoutLeakingInput() {
        var json = new PluginJsonCodec(new ObjectMapper(), () -> {});
        assertThatThrownBy(() -> json.read("{} {}", Configuration.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.read("{\"enabled\":\"private-json-value\"}", Configuration.class))
            .isInstanceOf(IllegalArgumentException.class).hasNoCause().hasMessageNotContaining("private-json-value");
    }

    @Test void enforcesUtf8ByteLimitOnInputAndOutput() {
        var json = new PluginJsonCodec(new ObjectMapper(), () -> {});
        String value = "\u4e2d".repeat(22000);
        assertThatThrownBy(() -> json.read("\"" + value + "\"", String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.write(Map.of("value", value))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void retainedCodecRejectsOperationsAfterRevocation() {
        var active = new AtomicBoolean(true);
        var json = new PluginJsonCodec(new ObjectMapper(), () -> {
            if (!active.get()) throw new IllegalStateException("revoked");
        });
        active.set(false);
        assertThatThrownBy(() -> json.write(Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> json.read("{}", Configuration.class)).isInstanceOf(IllegalStateException.class);
    }
}
