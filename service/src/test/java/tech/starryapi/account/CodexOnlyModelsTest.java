package tech.starryapi.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodexOnlyModelsTest {

    @Test
    void theCodexModelsAndGpt55AreServedByCodexAlone() {
        for (String model : new String[] {"gpt-5-codex", "gpt-5.1-codex-max", "codex-mini-latest", "gpt-5.5", "gpt-5.5-pro",
            "GPT-5.5", "starry/gpt-5.5", "openai/gpt-5.5", "openai-compatible/gpt-5.5", "starry/openai/gpt-5.1-codex", " gpt-5.5 "}) {
            assertThat(CodexOnlyModels.isCodexOnly(model)).as(model).isTrue();
        }
    }

    @Test
    void everyOtherModelIsNot() {
        for (String model : new String[] {"gpt-5.4", "gpt-5", "gpt-4.1", "o3", "claude-sonnet-4.5", "deepseek-v4-flash", "", null}) {
            assertThat(CodexOnlyModels.isCodexOnly(model)).as(String.valueOf(model)).isFalse();
        }
    }
}
