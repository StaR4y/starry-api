package tech.starryapi.platform.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.starryapi.account.AccountPlatforms;

class OpenAiEndpointsTest {

    @Test
    void rootOpenAiCompatibleBaseDefaultsToV1ApiPath() {
        assertThat(OpenAiEndpoints.openAiApi("https://joystar.cc", "/chat/completions"))
            .isEqualTo("https://joystar.cc/v1/chat/completions");
    }

    @Test
    void versionedOpenAiCompatibleBaseKeepsExistingApiPrefix() {
        assertThat(OpenAiEndpoints.openAiApi("https://openrouter.ai/api/v1", "/chat/completions"))
            .isEqualTo("https://openrouter.ai/api/v1/chat/completions");
    }

    @Test
    void fullOpenAiEndpointIsPreserved() {
        assertThat(OpenAiEndpoints.openAiApi("https://gateway.example.com/v1/chat/completions", "/chat/completions"))
            .isEqualTo("https://gateway.example.com/v1/chat/completions");
    }

    @Test
    void codexEndpointDoesNotAddOpenAiVersionPath() {
        assertThat(OpenAiEndpoints.codexApi("https://chatgpt.com/backend-api/codex", "/responses"))
            .isEqualTo(AccountPlatforms.DEFAULT_CODEX_BASE_URL + "/responses");
    }
}
