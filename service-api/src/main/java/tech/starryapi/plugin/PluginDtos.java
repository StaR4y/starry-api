package tech.starryapi.plugin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PluginDtos {
    private PluginDtos() {}

    public record Status(boolean root, boolean developerMode, boolean runtimeDisabled,
                         boolean restartRequired, String rootEmail, String apiVersion,
                         List<PluginInfo> plugins, List<RestartReason> restartReasons) {}
    public record RestartReason(String pluginId, String operation, String code, String detail, Instant at) {}
    public record PluginInfo(String id, String version, String provider, String description,
                             String sha256, long size, boolean autoStart, String state,
                             String error, Instant installedAt) {}
    public record SendCode(@NotBlank @Size(max = 256) String password) {
        @Override public String toString() { return "SendCode[REDACTED]"; }
    }
    public record Challenge(String id, String email, Instant expiresAt, int resendAfterSeconds) {}
    public record Enable(@NotBlank @Size(max = 256) String password,
                         @NotBlank @Size(max = 64) String challengeId,
                         @NotBlank @Pattern(regexp = "[0-9]{6}") String code, boolean acceptRisk) {
        @Override public String toString() { return "Enable[REDACTED]"; }
    }
    public record Configure(@NotBlank @Size(max = 65536) String json) {
        @Override public String toString() { return "Configure[REDACTED]"; }
    }
    public record Debug(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{1,64}") String command,
                        @NotBlank @Size(max = 65536) String json) {
        @Override public String toString() { return "Debug[REDACTED]"; }
    }
    public record DebugResult(String json, long durationMs) {}
    public record Audit(long id, Instant at, String pluginId, String actor, String action,
                        String outcome, String detail) {}
    public record Log(long sequence, Instant at, String pluginId, String level, String message) {}
    public record Widget(String pluginId, String title, String imageUrl, String scriptUrl) {}
    public record SettingPage(String pluginId, String title, String group) {}
    public record SettingField(String key, String type, String label, Integer min, Integer max, Double step,
                               Integer rows, @JsonProperty("default") Object defaultValue) {}
    public record SettingSpec(String title, String group, String hint, boolean assistant, List<SettingField> fields) {
        public SettingSpec {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }
    public record ModelChoice(String id, String name, String provider) {}
    public record ModuleSettings(String pluginId, String title, String hint, List<SettingField> fields,
                                 Map<String, Object> config, List<ModelChoice> models) {
        public ModuleSettings {
            fields = fields == null ? List.of() : List.copyOf(fields);
            config = config == null ? Map.of() : Map.copyOf(config);
            models = models == null ? List.of() : List.copyOf(models);
        }
    }
}
