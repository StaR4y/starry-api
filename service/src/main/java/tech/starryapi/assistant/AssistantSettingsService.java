package tech.starryapi.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.assistant.AssistantDtos.PublicSettings;
import tech.starryapi.assistant.AssistantDtos.Settings;
import tech.starryapi.common.ApiException;
import tech.starryapi.plugin.PluginRuntime;

@Service
public class AssistantSettingsService {

    static final Settings DEFAULTS = new Settings(
        true,
        "鲸鱼助手",
        "有什么可以帮你的？",
        """
        你是 Starry API 控制台的鲸鱼助手。用简洁的中文回答登录用户关于本站的问题。
        优先依据提供的站点文档作答；文档没有写到的内容要明确说不确定，不要编造密钥、密码或未公开的内部实现。
        可以解释控制台、模型 ID、API Key、计费、插件和常见故障。不要输出思考过程。
        """.strip(),
        "auto",
        12,
        0.4
    );

    private final PluginRuntime plugins;
    private final ObjectMapper json;

    public AssistantSettingsService(PluginRuntime plugins, ObjectMapper json) {
        this.plugins = plugins;
        this.json = json;
    }

    public Mono<Settings> get() {
        return plugins.assistantConfigJson()
            .map(this::read)
            .map(AssistantSettingsService::normalize)
            .switchIfEmpty(Mono.just(disabled(DEFAULTS)));
    }

    public Mono<PublicSettings> publicSettings() {
        return get().map(settings -> new PublicSettings(
            settings.enabled(),
            settings.displayName(),
            settings.greeting()
        ));
    }

    private Settings read(String value) {
        if (value == null || value.isBlank() || "{}".equals(value.trim())) {
            return DEFAULTS;
        }
        try {
            return json.readValue(value, Settings.class);
        } catch (Exception error) {
            return DEFAULTS;
        }
    }

    private static Settings disabled(Settings settings) {
        return new Settings(
            false,
            settings.displayName(),
            settings.greeting(),
            settings.systemPrompt(),
            settings.modelId(),
            settings.maxHistory(),
            settings.temperature()
        );
    }

    static Settings normalize(Settings request) {
        Settings source = request == null ? DEFAULTS : request;
        String name = text(source.displayName(), DEFAULTS.displayName(), 32);
        String greeting = text(source.greeting(), DEFAULTS.greeting(), 200);
        String prompt = text(source.systemPrompt(), DEFAULTS.systemPrompt(), 8000);
        String modelId = text(source.modelId(), DEFAULTS.modelId(), 256).toLowerCase(Locale.ROOT);
        if (!modelId.matches("[a-z0-9][a-z0-9._:/-]{0,254}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_MODEL_INVALID", "对话模型 ID 无效");
        }
        int history = source.maxHistory() <= 0 ? DEFAULTS.maxHistory() : source.maxHistory();
        if (history > 40) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_HISTORY_INVALID", "上下文轮次必须在 1 到 40 之间");
        }
        double temperature = source.temperature();
        if (temperature < 0 || temperature > 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_TEMPERATURE_INVALID", "温度必须在 0 到 2 之间");
        }
        return new Settings(source.enabled(), name, greeting, prompt, modelId, history, temperature);
    }

    private static String text(String value, String fallback, int max) {
        String trimmed = value == null ? "" : value.trim();
        String resolved = trimmed.isEmpty() ? fallback : trimmed;
        if (resolved.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_FIELD_TOO_LONG", "字段长度不能超过 " + max);
        }
        return resolved;
    }
}
