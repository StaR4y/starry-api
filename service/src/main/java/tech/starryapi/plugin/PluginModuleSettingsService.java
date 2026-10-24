package tech.starryapi.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.model.ModelCatalogProvider;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PluginModuleSettingsService {

    private final PluginRuntime runtime;
    private final ObjectMapper json;
    private final ModelCatalogProvider catalog;

    public PluginModuleSettingsService(PluginRuntime runtime, ObjectMapper json, ModelCatalogProvider catalog) {
        this.runtime = runtime;
        this.json = json;
        this.catalog = catalog;
    }

    public List<PluginDtos.SettingPage> pages() {
        return runtime.settingPages();
    }

    public Mono<PluginDtos.ModuleSettings> view(String pluginId) {
        PluginDtos.SettingSpec spec = requireSpec(pluginId);
        return runtime.configuration(pluginId).map(raw -> new PluginDtos.ModuleSettings(
            pluginId,
            spec.title(),
            spec.hint() == null ? "" : spec.hint(),
            spec.fields(),
            parseObject(raw),
            modelChoices(spec)
        ));
    }

    public Mono<PluginDtos.ModuleSettings> save(String pluginId, JsonNode body, String actor) {
        requireSpec(pluginId);
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLUGIN_SETTINGS_INVALID", "设置必须是 JSON 对象");
        }
        try {
            return runtime.saveModuleConfig(pluginId, json.writeValueAsString(body), actor).then(view(pluginId));
        } catch (Exception error) {
            return Mono.error(error);
        }
    }

    private PluginDtos.SettingSpec requireSpec(String pluginId) {
        if (!runtime.moduleSettingsAvailable(pluginId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLUGIN_SETTINGS_UNAVAILABLE", "插件未启动或没有设置页");
        }
        return runtime.settingSpec(pluginId);
    }

    private Map<String, Object> parseObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = json.readTree(raw);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            return json.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<PluginDtos.ModelChoice> modelChoices(PluginDtos.SettingSpec spec) {
        boolean needsModels = spec.fields().stream().anyMatch(field -> "model".equals(field.type()));
        if (!needsModels) {
            return List.of();
        }
        return catalog.models().stream()
            .map(model -> new PluginDtos.ModelChoice(model.id(), model.name(), model.provider()))
            .limit(400)
            .toList();
    }
}
