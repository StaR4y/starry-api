package tech.starryapi.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tech.starryapi.assistant.AssistantDtos.Settings;
import tech.starryapi.common.ApiException;
import tech.starryapi.plugin.PluginRuntime;
import tools.jackson.databind.json.JsonMapper;

class AssistantSettingsServiceTest {

    @Test
    void rejectsOutOfRangeTemperature() {
        assertThatThrownBy(() -> AssistantSettingsService.normalize(new Settings(
            true, "助手", "你好", "提示", "auto", 8, 3
        ))).isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getCode())
            .isEqualTo("ASSISTANT_TEMPERATURE_INVALID");
    }

    @Test
    void fillsBlankFieldsFromDefaults() {
        Settings settings = AssistantSettingsService.normalize(new Settings(
            false, "  ", "", "", "gpt-5.5", 12, 0.2
        ));
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.displayName()).isEqualTo("鲸鱼助手");
        assertThat(settings.modelId()).isEqualTo("gpt-5.5");
        assertThat(settings.systemPrompt()).contains("鲸鱼助手");
    }

    @Test
    void staysDisabledWhenAssistantPluginIsNotStarted() {
        PluginRuntime runtime = mock(PluginRuntime.class);
        when(runtime.assistantConfigJson()).thenReturn(Mono.empty());
        AssistantSettingsService service = new AssistantSettingsService(runtime, JsonMapper.builder().build());

        assertThat(service.publicSettings().block().enabled()).isFalse();
    }
}
